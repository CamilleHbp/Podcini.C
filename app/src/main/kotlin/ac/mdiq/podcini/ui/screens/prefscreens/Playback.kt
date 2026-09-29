package ac.mdiq.podcini.ui.screens.prefscreens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import ac.mdiq.podcini.PodciniApp.Companion.forceRestart
import ac.mdiq.podcini.playback.activeTheatresCount
import ac.mdiq.podcini.playback.PlaybackService.Companion.playbackService
import ac.mdiq.podcini.ui.screens.actPlayerId
import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.forcePlaybackReset
import ac.mdiq.podcini.sourcing.clientsHaveMultiQ
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.prefStreamOverDownload
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.streamingCacheSizeMB
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.specs.AVQuality
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.ui.compose.CommonConfirmAttrib
import ac.mdiq.podcini.ui.compose.SetAVQuality
import ac.mdiq.podcini.ui.compose.VideoModeDialog
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.utils.Logd
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PrefHardwareButton(val res: Int, val res1: Int) {
    FF(R.string.button_action_fast_forward, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
    RW(R.string.button_action_rewind, KeyEvent.KEYCODE_MEDIA_REWIND),
    SKIP(R.string.button_action_skip_episode, KeyEvent.KEYCODE_MEDIA_NEXT),
    START(R.string.button_action_restart_episode, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
}

private const val TAG = "PlaybackScreen"
@Composable
fun PlaybackScreen() {
    val context by rememberUpdatedState(LocalContext.current)
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    var selectedRingtoneUri by remember { mutableStateOf(appPrefs.ringToneUriString?.toUri()) }
    var ringtoneName by remember { mutableStateOf(appPrefs.ringToneName) }
    val ringtonePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data ?: return@rememberLauncherForActivityResult
            val uri = IntentCompat.getParcelableExtra(intent, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) ?: return@rememberLauncherForActivityResult
//            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedRingtoneUri = uri
            ringtoneName = RingtoneManager.getRingtone(context, uri).getTitle(context) ?: context.getString(R.string.settings_silent)
            upsertBlk(appPrefs) {
                it.ringToneName = ringtoneName
                it.ringToneUriString = uri.toString()
            }
            Logd(TAG) { "ringtoneName $ringtoneName" }
        }
    }

    val appAttribs by appAttribsFlow!!.collectAsStateWithLifecycle()

    SettingsPage {
        SettingsSection(R.string.settings_players)
        val players by activeTheatresCount.collectAsStateWithLifecycle()
        SettingsSwitch(R.string.archive_two_players, R.string.archive_two_players_summary, players == 2) { enabled ->
            if (!enabled) { playbackService?.shutdownPlayer(1); actPlayerId = 0 }
            upsertBlk(appPrefs) { it.twoPlayers = enabled }
            activeTheatresCount.value = if (enabled) 2 else 1
            playbackService?.switchPlayersMode()
        }
        SettingsSection(R.string.interruptions)
        SettingsSwitch(R.string.pref_pauseOnHeadsetDisconnect_title, R.string.pref_pauseOnDisconnect_sum, appPrefs.pauseOnHeadsetDisconnect) {
            upsertBlk(appPrefs) { p-> p.pauseOnHeadsetDisconnect = it }
        }
        if (appPrefs.pauseOnHeadsetDisconnect) {
            SettingsSwitch(R.string.pref_unpauseOnHeadsetReconnect_title, R.string.pref_unpauseOnHeadsetReconnect_sum, appPrefs.unpauseOnHeadsetReconnect) {
                upsertBlk(appPrefs) { p-> p.unpauseOnHeadsetReconnect = it }
            }
            SettingsSwitch(R.string.pref_unpauseOnBluetoothReconnect_title, R.string.pref_unpauseOnBluetoothReconnect_sum, appPrefs.unpauseOnBluetoothReconnect) {
                upsertBlk(appPrefs) { p-> p.unpauseOnBluetoothReconnect = it }
            }
        }
        SettingsSection(R.string.playback_control)
        if (appAttribs.langSet.size > 1) {
            SettingsText(R.string.preferred_languages, R.string.preferred_languages_sum,
                appAttribs.langsPreferred.joinToString(", ")) { languages ->
                runOnIOScope {
                    upsert(appAttribs) { attrs ->
                        attrs.langsPreferred.clear()
                        attrs.langsPreferred.addAll(languages.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    withContext(Dispatchers.Main) { forcePlaybackReset = true }
                }
            }
            SettingsDescription(stringResource(R.string.ui_candidates, appAttribs.langSet.joinToString(", ")))

        }

        SettingsSwitch(R.string.use_ring_tone, R.string.use_ring_tone_sum, appPrefs.useRingTone) {
            upsertBlk(appPrefs) { p-> p.useRingTone = it }
        }
        if (appPrefs.useRingTone) {
            Column {
                Text(stringResource(R.string.ui_ringtone, ringtoneName.orEmpty()), modifier = Modifier.padding(start = 16.dp))
                SettingsAction(R.string.select_ring_tone, R.string.select_ring_tone_sum) {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.select_ring_tone))
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedRingtoneUri)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    }
                    ringtonePickerLauncher.launch(intent)
                }
                SettingsSwitch(R.string.disable_ring_tone_on_music, R.string.disable_ring_tone_on_music_sum, appPrefs.disableRingToneOnMusic) {
                    upsertBlk(appPrefs) { p-> p.disableRingToneOnMusic = it }
                }
            }
        }

        SettingsSection(R.string.settings_streaming)
        var prefStreaming by remember { mutableStateOf(prefStreamOverDownload) }
        SettingsSwitch(R.string.pref_stream_over_download_title, R.string.pref_stream_over_download_sum, appPrefs.streamOverDownload) {
            prefStreaming = it
            upsertBlk(appPrefs) { p-> p.streamOverDownload = it }
        }
        if (prefStreaming) SettingsNumber(R.string.pref_stream_cache, R.string.pref_stream_cache_sum, streamingCacheSizeMB,
            stringResource(R.string.ui_megabytes), min = 10) {
            streamingCacheSizeMB = it
            forceRestart()
        }
        val hasMultiQ = remember { clientsHaveMultiQ() }
        if (hasMultiQ) {
            var audioQuality by remember { mutableStateOf(  AVQuality.fromCode(appPrefs.audioQuality)) }
            var videoQuality by remember { mutableStateOf(AVQuality.fromCode(appPrefs.videoQuality)) }

            var showAudioDialog by remember { mutableStateOf(false) }
            if (showAudioDialog) SetAVQuality(selectedOption = audioQuality.tag, showGlobal = false, onDismiss = { showAudioDialog = false }) { type ->
                audioQuality = type
                upsertBlk(appPrefs) { it.audioQuality  = audioQuality.code }
            }
            SettingsAction(stringResource(R.string.pref_feed_audio_quality), stringResource(R.string.pref_audio_quality_sum),
                value = audioQuality.tag) { showAudioDialog = true }
            var showVideoDialog by remember { mutableStateOf(false) }
            if (showVideoDialog) SetAVQuality(selectedOption = videoQuality.tag, showGlobal = false, onDismiss = { showVideoDialog = false }) { type->
                videoQuality = type
                upsertBlk(appPrefs) { it.videoQuality  = videoQuality.code }
            }
            SettingsAction(stringResource(R.string.pref_feed_video_quality), stringResource(R.string.pref_video_quality_sum),
                value = videoQuality.tag) { showVideoDialog = true }
            SettingsSwitch(R.string.pref_low_quality_on_mobile_title, R.string.pref_low_quality_on_mobile_sum, appPrefs.lowQualityOnMobile) {
                upsertBlk(appPrefs) { p-> p.lowQualityOnMobile = it }
            }
        }
        SettingsSwitch(R.string.pref_use_adaptive_progress_title, R.string.pref_use_adaptive_progress_sum, appPrefs.useAdaptiveProgressUpdate) {
            upsertBlk(appPrefs) { p-> p.useAdaptiveProgressUpdate = it }
        }
        var showVideoModeDialog by remember { mutableStateOf(false) }
        if (showVideoModeDialog) VideoModeDialog(initMode =  VideoMode.fromCode(appPrefs.videoPlaybackMode), isDemuxed = false, muxed = appPrefs.useMuxedVideo, onDismiss = { showVideoModeDialog = false }) { mode, muxed ->
            upsertBlk(appPrefs) {
                it.videoPlaybackMode = mode.code
//                it.useMuxedVideo = muxed  // not used now
            }
        }
        SettingsAction(R.string.pref_playback_video_mode, R.string.pref_playback_video_mode_sum) { showVideoModeDialog = true }

        SettingsSection(R.string.reassign_hardware_buttons)
        var showHardwareForwardButtonOptions by remember { mutableStateOf(false) }
        var tempFFSelectedOption by remember(appPrefs.hardwareForwardButton) { mutableIntStateOf(appPrefs.hardwareForwardButton.toIntOrNull() ?: KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
        SettingsAction(R.string.pref_hardware_forward_button_title, R.string.pref_hardware_forward_button_summary) { tempFFSelectedOption = appPrefs.hardwareForwardButton.toIntOrNull() ?: KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; showHardwareForwardButtonOptions = true }
        if (showHardwareForwardButtonOptions) {
            AlertDialog(onDismissRequest = { showHardwareForwardButtonOptions = false },
                title = { Text(stringResource(R.string.pref_hardware_forward_button_title), style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column(modifier = Modifier) {
                        PrefHardwareButton.entries.forEach { option ->
                            SettingsChoice(stringResource(option.res), tempFFSelectedOption == option.res1) { tempFFSelectedOption = option.res1 }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        upsertBlk(appPrefs) { it.hardwareForwardButton = tempFFSelectedOption.toString() }
                        showHardwareForwardButtonOptions = false
                    }) { Text(text = stringResource(R.string.OK)) }
                },
                dismissButton = { TextButton(onClick = { showHardwareForwardButtonOptions = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        var showHardwarePreviousButtonOptions by remember { mutableStateOf(false) }
        var tempPRSelectedOption by remember(appPrefs.hardwarePreviousButton) { mutableIntStateOf(appPrefs.hardwarePreviousButton.toIntOrNull() ?: KeyEvent.KEYCODE_MEDIA_REWIND) }
        SettingsAction(R.string.pref_hardware_previous_button_title, R.string.pref_hardware_previous_button_summary) { tempPRSelectedOption = appPrefs.hardwarePreviousButton.toIntOrNull() ?: KeyEvent.KEYCODE_MEDIA_REWIND; showHardwarePreviousButtonOptions = true }
        if (showHardwarePreviousButtonOptions) {
            AlertDialog(onDismissRequest = { showHardwarePreviousButtonOptions = false },
                title = { Text(stringResource(R.string.pref_hardware_previous_button_title), style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column(modifier = Modifier) {
                        PrefHardwareButton.entries.forEach { option ->
                            SettingsChoice(stringResource(option.res), tempPRSelectedOption == option.res1) { tempPRSelectedOption = option.res1 }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        upsertBlk(appPrefs) { it.hardwarePreviousButton = tempPRSelectedOption.toString()}
                        showHardwarePreviousButtonOptions = false
                    }) { Text(text = stringResource(R.string.OK)) }
                },
                dismissButton = { TextButton(onClick = { showHardwarePreviousButtonOptions = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        SettingsSection(R.string.settings_queue_cleanup)
        SettingsSwitch(R.string.pref_enqueue_downloaded_title, R.string.pref_enqueue_downloaded_summary, appPrefs.enqueueDownloaded) {
            upsertBlk(appPrefs) { p-> p.enqueueDownloaded = it }
        }

        SettingsSwitch(R.string.pref_skip_keeps_episodes_title, R.string.pref_skip_keeps_episodes_sum, appPrefs.skipKeepsEpisode) {
            upsertBlk(appPrefs) { p-> p.skipKeepsEpisode = it }
        }
        SettingsSwitch(R.string.pref_mark_played_removes_from_queue_title, R.string.pref_mark_played_removes_from_queue_sum, appPrefs.removeFromQueueMarkPlayed) {
            upsertBlk(appPrefs) { p-> p.removeFromQueueMarkPlayed = it }
        }

        SettingsSwitch(R.string.auto_delete, R.string.pref_auto_delete_sum, appPrefs.autoDelete) {
            upsertBlk(appPrefs) { p-> p.autoDelete = it }
        }
        var blockAutoDeleteLocal by remember { mutableStateOf(true) }
        SettingsSwitch(R.string.pref_auto_local_delete_title, R.string.pref_auto_local_delete_sum, appPrefs.autoDeleteLocal) {
            if (blockAutoDeleteLocal && it) {
                commonConfirms.add(CommonConfirmAttrib(
                    title = "",
                    message = context.getString(R.string.pref_auto_local_delete_dialog_body),
                    confirmRes = R.string.yes,
                    cancelRes = R.string.cancel_label,
                    onConfirm = {
                        blockAutoDeleteLocal = false
                        upsertBlk(appPrefs) { p-> p.autoDeleteLocal = it }
                        blockAutoDeleteLocal = true
                    }))
            } else if (!it) upsertBlk(appPrefs) { prefs -> prefs.autoDeleteLocal = false }
        }
        SettingsSwitch(R.string.pref_keeps_important_episodes_title, R.string.pref_keeps_important_episodes_sum, appPrefs.favoriteKeepsEpisode) {
            upsertBlk(appPrefs) { p-> p.favoriteKeepsEpisode = it }
        }
        SettingsSwitch(R.string.pref_delete_removes_from_queue_title, R.string.pref_delete_removes_from_queue_sum, appPrefs.deleteRemovesFromQueue) {
            upsertBlk(appPrefs) { p-> p.deleteRemovesFromQueue = it }
        }
    }
}
