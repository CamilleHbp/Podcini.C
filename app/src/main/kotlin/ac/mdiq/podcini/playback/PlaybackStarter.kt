package ac.mdiq.podcini.playback

import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.localizedString
import ac.mdiq.podcini.playback.Media3Player.Companion.getCache
import ac.mdiq.podcini.playback.Media3Player.Companion.simpleCache
import ac.mdiq.podcini.playback.MediaPlayerBase.Companion.isStreamingCapable
import ac.mdiq.podcini.playback.SleepManager.Companion.sleepManager
import ac.mdiq.podcini.storage.database.checkAndMarkDuplicates
import ac.mdiq.podcini.storage.database.isMediaDownloadable
import ac.mdiq.podcini.storage.database.prefStreamOverDownload
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class PlaybackStarter(private val media: Episode) {
    private val TAG = "PlaybackStarter"

    private var startImmediately = true
    private var shouldStreamThisTime = false
    private var audioOnly = false
    private var repeat = false

    private var widgetId: String = ""

    fun shouldStreamThisTime(shouldStreamThisTime: Boolean?): PlaybackStarter {
        this.shouldStreamThisTime = media.availableLocalLocation == null
        return this
    }

    fun setAudioOnly(): PlaybackStarter {
        audioOnly =  true
        return this
    }

    fun setStartNow(start: Boolean): PlaybackStarter {
        startImmediately = start
        return this
    }

    fun setToRepeat(repeat_: Boolean): PlaybackStarter {
        repeat = repeat_
        return this
    }

    fun setWidgetId(widgetId: String): PlaybackStarter {
        this.widgetId = widgetId
        return this
    }

    fun start(playerId: Int = 0) {
        if (!actQueueFlow.value.contains(media)) {
            runOnIOScope {
                replaceListeningQueue(listOf(media), media.id, media.title.orEmpty())
                withContext(Dispatchers.Main) { start(0) }
            }
            return
        }
        shouldStreamThisTime = media.availableLocalLocation == null
        Logd(TAG) { "start PlaybackService.isRunning: ${PlaybackService.isRunning}" }
//        showStackTrace()
        ensureAController()

        var media_ = media
        if (forcePlaybackReset && simpleCache != null) getCache().removeResource(media.id.toString())
        var sameMedia = !forcePlaybackReset
        val player = theatres[playerId].mPlayerFlow.value
        if (player?.curMediaFlow?.value?.id != media.id) {
            sameMedia = false
            media_ = if (media.libraryKind == "music") upsertBlk(media) { it.position = 0 } else media
//            player.setAsCurEpisode(media_)   // seems redundant
        }

        fun processTask() {
            val player = theatres[playerId].mPlayerFlow.value
            sameMedia = !forcePlaybackReset && player?.curMediaFlow?.value?.id == media_.id
            if (player == null) {
                Loge(TAG, localizedString(R.string.message_processtask_mplayerflow_value_null))
                return
            }
            if (shouldStreamThisTime && !isStreamingCapable(media_)) {
                player.playbackErrorFlow.value = true
                player.loadingFlow.value = false
                return
            }
            Logd(TAG) { "aCtrlFuture: ${aCtrlFuture != null} player status: ${player.status}" }
            player.shouldRepeatFlow.value = repeat
            Logd(TAG) { "start: statusFlow: ${player.status} sameMedia: $sameMedia" }
            if (sameMedia) player.refreshPlaylistOrigin()
            player.isStreaming = shouldStreamThisTime
            player.widgetId = widgetId
            when {
                player.isPlaying -> {
                    if (!sameMedia) {
                        player.pause(false)
                        player.isSkipping = true
                        player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset, repeatItem = repeat)
                        sleepManager?.restart()
                    }
                }
                player.isPaused || player.isPrepared -> {
                    if (sameMedia) { if (startImmediately) player.play() }
                    else {
                        player.isSkipping = true
                        player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset, repeatItem = repeat)
                    }
                    sleepManager?.restart()
                }
                player.isStopped -> {
//                    ContextCompat.startForegroundService(getAppContext(), Intent(getAppContext(), PlaybackService::class.java))
                    player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset, repeatItem = repeat)
                    sleepManager?.restart()
                }
                // TODO: test
                player.isInitialized -> {
                    player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset, repeatItem = repeat)
                    sleepManager?.restart()
                }
                else -> {
                    player.setAsCurMedia(media_)
                    player.reinit()
                    sleepManager?.restart()
                }
            }
            forcePlaybackReset = false
        }
        aCtrlFuture?.let { future ->
            if (future.isDone && aController?.isConnected == true) {
                Logd(TAG) { "aCtrlFuture aController ready, play, ${player?.status} $shouldStreamThisTime" }
                processTask()
            } else {
                Logd(TAG) { "aCtrlFuture starting PlaybackService" }
//                ContextCompat.startForegroundService(getAppContext(), Intent(getAppContext(), PlaybackService::class.java))
                CoroutineScope(Dispatchers.Default).launch {
                    while (!future.isDone || aController?.isConnected != true) {
                        Logd(TAG) { "aCtrlFuture delay ${future.isDone} ${aController?.isConnected}" }
                        delay(1.seconds)
                    }
                    withContext(Dispatchers.Main) { processTask() }
                }
            }
        } ?: run {
            Logd(TAG) { "aCtrlFuture is null, starting service" }
            processTask()
        }
    }
}
