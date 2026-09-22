package ac.mdiq.podcini.sourcing.download

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.config.CHANNEL_ID
import ac.mdiq.podcini.config.AppConfig.initialize
import ac.mdiq.podcini.config.NotificationIds
import ac.mdiq.podcini.sourcing.download.DownloadRequest.Companion.requestFor
import ac.mdiq.podcini.sourcing.download.EpisodeAdrDLManager.Companion.WORK_DATA_PROGRESS
import ac.mdiq.podcini.sourcing.download.EpisodeDLManager.Companion.updateDB
import ac.mdiq.podcini.utils.NetworkUtils.mobileAllowEpisodeDownload
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.storage.database.addToAssQueue
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.deleteMedia
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.removeFromAllQueues
import ac.mdiq.podcini.storage.database.removeFromQueue
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.DownloadResult.Companion.logDownloadResult
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.takeCodePoints
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.utils.quietlyDeleteFile
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.Constraints.Builder
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class EpisodeAdrDLManager: EpisodeDLManager() {
    private val constraints: Constraints
        get() = Builder()
            .setRequiresCharging(!appPrefsFlow!!.value.enableAutoDownloadOnBattery)
            .setRequiredNetworkType(if (mobileAllowEpisodeDownload) NetworkType.CONNECTED else NetworkType.UNMETERED).build()

    override fun downloadNow(episodes: List<Episode>, ignoreConstraints: Boolean) {
        Logd(TAG) { "starting downloadNow" }
        val workRequest: OneTimeWorkRequest.Builder = requestBuilderFor(episodes)
        workRequest.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (ignoreConstraints) workRequest.setConstraints(Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        else workRequest.setConstraints(constraints)
        WorkManager.getInstance(getAppContext()).enqueueUniqueWork("DownloadEpisodesNow", ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest.build())
    }

    override fun download(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        Logd(TAG) { "starting download" }
        val workRequest: OneTimeWorkRequest.Builder = requestBuilderFor(episodes)
        workRequest.setConstraints(constraints)
        WorkManager.getInstance(getAppContext()).enqueueUniqueWork("DownloadEpisodes", ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest.build())
    }

    override suspend fun cancel(media: Episode) {
        Logd(TAG) { "starting cancel" }
        // This needs to be done here, not in the worker. Reason: The worker might or might not be running.
        // Remove partially downloaded file
        val episode_ = deleteMedia(media)
        if (appPrefsFlow!!.value.deleteRemovesFromQueue) removeFromAllQueues(listOf(episode_))
        val tag = WORK_TAG_EPISODE_URL + media.downloadUrl
        val future: Future<List<WorkInfo>> = WorkManager.getInstance(getAppContext()).getWorkInfosByTag(tag)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val workInfoList = future.get() // Wait for the completion of the future operation and retrieve the result
                workInfoList.forEach { workInfo -> if (workInfo.tags.contains(WORK_DATA_WAS_QUEUED)) removeFromQueue(actQueueFlow.value, listOf(media), playState = EpisodeState.UNSPECIFIED) }
            } catch (exception: Throwable) { Logs(TAG, exception)
            } finally { WorkManager.getInstance(getAppContext()).cancelAllWorkByTag(tag) }
        }
    }

    private fun requestBuilderFor(episodes: List<Episode>): OneTimeWorkRequest.Builder {
        Logd(TAG) { "starting getRequest" }
        val workRequest: OneTimeWorkRequest.Builder = OneTimeWorkRequest.Builder(EpisodesDownloadWorker::class.java)
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .addTag(EpisodesDownload)
        upsertBlk(appAttribsFlow!!.value) {
            episodes.forEach { episode ->
                if (episode.suitableForDownload()) {
                    workRequest.addTag(WORK_TAG_EPISODE_URL + episode.downloadUrl)
                    it.episodeIdsToDownload.add(episode.id)
                }
            }
        }
        if (appPrefsFlow!!.value.enqueueDownloaded) runBlocking { addToAssQueue(episodes) }
        return workRequest
    }

    override fun cancelAll() {
        WorkManager.getInstance(getAppContext()).cancelAllWorkByTag(WORK_TAG)
        WorkManager.getInstance(getAppContext()).cancelAllWorkByTag(EpisodesDownload)
    }

    companion object {
        private const val TAG = "DownloadService"

        const val WORK_TAG: String = "episodeDownload"
        const val EpisodesDownload: String = "episodesDownload"

        const val WORK_TAG_EPISODE_URL: String = "episodeUrl:"
        const val WORK_DATA_PROGRESS: String = "progress"
        const val WORK_DATA_WAS_QUEUED: String = "was_queued"

        val manager: EpisodeAdrDLManager by lazy { EpisodeAdrDLManager() }
    }
}

class EpisodesDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var downloader: Downloader? = null
    private val isLastRunAttempt: Boolean
        get() = runAttemptCount >= 2

    override suspend fun doWork(): Result = coroutineScope {
        initialize()
        getForegroundInfo()
        val ids = appAttribsFlow!!.value.episodeIdsToDownload.toList()
        if (ids.isEmpty()) return@coroutineScope Result.success()
        val medias = realm.query(Episode::class).query("id IN $0", ids).find()
        var retryPending = false
        var failed = false
        for (media in medias) {
            val request = requestFor(media).build()
            val progressUpdaterJob = launch(Dispatchers.IO) {
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                while (isActive) {
                    synchronized(notificationProgress) { notificationProgress[media.getEpisodeTitle()] = request.progressPercent }
                    setProgress(Data.Builder().putInt(WORK_DATA_PROGRESS, request.progressPercent).build())
                    nm.notify(NotificationIds.downloading, generateProgressNotification())
                    delay(1000)
                }
            }
            var outcome: Result? = null
            try {
                outcome = performTasks(request)
                when (outcome) {
                    Result.retry() -> retryPending = true
                    Result.failure() -> failed = true
                    else -> {}
                }
                // Leave retryable episodes pending for WorkManager's next attempt.
                if (outcome != Result.retry()) upsert(appAttribsFlow!!.value) { it.episodeIdsToDownload.remove(media.id) }
            } catch (e: CancellationException) {
                throw e
            } finally {
                progressUpdaterJob.cancel()
                if (outcome == Result.failure()) quietlyDeleteFile(request.destination.toSafeUri())
                downloader?.cancel()
                synchronized(notificationProgress) {
                    notificationProgress.remove(media.getEpisodeTitle())
                    if (notificationProgress.isEmpty()) {
                        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(NotificationIds.downloading)
                    }
                }
            }
        }
        when {
            retryPending -> Result.retry()
            failed -> Result.failure()
            else -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return withContext(Dispatchers.Main) { ForegroundInfo(NotificationIds.downloading, generateProgressNotification()) }
    }

    private suspend fun performTasks(request: DownloadRequest): Result {
        if (request.destination.isBlank()) return Result.failure()
        request.ensureMediaFileExists()
        val episodeDownloader = Downloader.downloaderFor(request) ?: return Result.failure()
        downloader = episodeDownloader
        try {
            episodeDownloader.download()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Episode download failed", e)
            episodeDownloader.result.reason = DownloadError.ERROR_IO_ERROR
            episodeDownloader.result.addDetail(e.message ?: e.javaClass.simpleName)
        }
        if (episodeDownloader.cancelled) return Result.success()
        val status = episodeDownloader.result
        if (status.isSuccessful) {
            updateDB(request)
            logDownloadResult(status)
            return Result.success()
        }
        val restart = status.reason == DownloadError.ERROR_HTTP_DATA_ERROR && status.reasonDetailed.trim() == "416"
        if (restart) quietlyDeleteFile(request.destination.toSafeUri())
        logDownloadResult(status)
        val immediateFailure = status.reason in listOf(
            DownloadError.ERROR_FORBIDDEN, DownloadError.ERROR_NOT_FOUND, DownloadError.ERROR_UNAUTHORIZED,
            DownloadError.ERROR_IO_BLOCKED, DownloadError.ERROR_FILE_TYPE, DownloadError.ERROR_MALFORMED_URL,
            DownloadError.ERROR_NOT_ENOUGH_SPACE, DownloadError.ERROR_CERTIFICATE
        )
        val retrying = !isLastRunAttempt && !immediateFailure
        val title = request.title ?: applicationContext.getString(R.string.download_log_title_unknown)
        val message = applicationContext.getString(
            if (retrying) R.string.download_error_retrying else R.string.download_error_not_retrying, title
        )
        // Keep diagnostics in the download log; show one actionable, non-modal message.
        if (runAttemptCount == 0 || !retrying)
            EventFlow.postEvent(FlowEvent.DownloadMessageEvent(message))
        if (!retrying) {
            val intent = Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("shortcut_route", "DownloadLogs")
            val pendingIntent = PendingIntent.getActivity(applicationContext, NotificationIds.download_report, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val reason = applicationContext.getString(status.reason?.res ?: R.string.download_error_error_unknown)
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID.error.name)
                .setContentTitle(applicationContext.getString(R.string.download_report_title))
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$reason"))
                .setSmallIcon(R.drawable.ic_notification_sync_error)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NotificationIds.download_report, notification)
        }
        return if (retrying) Result.retry() else Result.failure()
    }
    private fun generateProgressNotification(): Notification {
        val sb = StringBuilder()
        var progressCopy: Map<String, Int>
        synchronized(notificationProgress) { progressCopy = notificationProgress.toMap() }
        for ((key, value) in progressCopy) sb.append("$key ($value%)\n")
        val bigText = sb.toString().trim { it <= ' ' }
        val contentText = if (progressCopy.size == 1) bigText else applicationContext.resources.getQuantityString(R.plurals.downloads_left, progressCopy.size, progressCopy.size)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID.downloading.name)
        builder.setTicker(applicationContext.getString(R.string.download_notification_title_episodes))
            .setContentTitle(applicationContext.getString(R.string.download_notification_title_episodes))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            //                .setContentIntent(getDownloadsIntent(applicationContext))
            .setAutoCancel(false)
            .setOngoing(true)
            .setWhen(0)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return builder.build()
    }

    companion object {
        private const val TAG = "EpisodesDownloadWorker"
        private val notificationProgress: MutableMap<String, Int> = mutableMapOf()
    }
}
