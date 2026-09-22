package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.model.Episode
import kotlinx.coroutines.flow.map
import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.*
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.utils.durationStringAdapt
import ac.mdiq.podcini.ui.screens.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
private fun playbackPosition(player: MediaPlayerBase?): Int {
    val media by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    var position by remember(player) { mutableIntStateOf(player?.curMediaFlow?.value?.position ?: 0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(player, owner, media?.id) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                position = if (player?.castPlayer?.currentMediaItem?.mediaId == media?.id.toString()) player?.getPosition()?.coerceAtLeast(0) ?: 0 else media?.position ?: 0
                delay(500)
            }
        }
    }
    return position
}

private fun startArchivePlayback(episode: Episode, player: MediaPlayerBase?, playerId: Int) {
    if (player?.playbackErrorFlow?.value == true) forcePlaybackReset = true
    PlaybackStarter(episode).shouldStreamThisTime(null).start(playerId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivePlayerChoices() {
    val count by activeTheatresCount.collectAsStateWithLifecycle()
    if (count != 2) return
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        (0..1).forEach { id ->
            val player by theatres[id].mPlayerFlow.collectAsStateWithLifecycle()
            val episode by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
            val status by player?.statusSimpleFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(PlayerStatusSimple.OTHER) }
            SegmentedButton(selected = actPlayerId == id, onClick = { actPlayerId = id }, shape = SegmentedButtonDefaults.itemShape(id, 2), icon = {}) {
                Column {
                    Text(stringResource(R.string.archive_player_number, id + 1), style = MaterialTheme.typography.labelLarge)
                    Text(episode?.title ?: stringResource(R.string.archive_choose_episode), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (episode == null) R.string.archive_player_empty else if (status == PlayerStatusSimple.PLAYING) R.string.archive_playing else R.string.archive_paused), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun ArchiveCompactPlayer(vm: AVPlayerVM) {
    val player by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val episode by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val status by player?.statusSimpleFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(PlayerStatusSimple.OTHER) }
    val position = playbackPosition(player)
    val count by activeTheatresCount.collectAsStateWithLifecycle()
    val playing = status == PlayerStatusSimple.PLAYING
    val loading by player?.loadingFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val failed by player?.playbackErrorFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            ArchivePlayerChoices()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = stringResource(R.string.archive_open_player)) { actPlayerId = vm.playerId; psState = PSState.Expanded }, verticalAlignment = Alignment.CenterVertically) {
                    ArchiveArtwork(episode, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(episode?.title ?: stringResource(R.string.archive_choose_episode), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when { failed -> stringResource(R.string.archive_playback_failed); loading -> stringResource(R.string.archive_buffering); episode == null -> stringResource(R.string.archive_choose_episode_body); else -> episode?.feed?.title ?: stringResource(R.string.archive_no_source) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                FilledIconButton(onClick = { if (playing) player?.pause(false) else episode?.let { startArchivePlayback(it, player, vm.playerId) } }, enabled = episode != null && !loading, modifier = Modifier.size(48.dp)) {
                    Icon(ImageVector.vectorResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_24dp), stringResource(if (failed) R.string.archive_retry else if (playing) R.string.archive_pause else R.string.archive_play))
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { player?.skip(force = true) }, enabled = episode != null) { Icon(ImageVector.vectorResource(R.drawable.ic_skip_48dp), stringResource(R.string.archive_next)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else LinearProgressIndicator(progress = { if ((episode?.duration ?: 0) > 0) (position.toFloat() / episode!!.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
fun ArchiveSeekBar(vm: AVPlayerVM) {
    val player by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val episode by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val position = playbackPosition(player)
    var seeking by remember(episode?.id) { mutableStateOf(false) }
    var target by remember { mutableFloatStateOf(0f) }
    val duration = (episode?.duration ?: 0).coerceAtLeast(1)
    val label = stringResource(R.string.archive_position)
    Column(Modifier.fillMaxWidth()) {
        Slider(value = if (seeking) target.coerceIn(0f, duration.toFloat()) else position.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { seeking = true; target = it }, valueRange = 0f..duration.toFloat(), enabled = episode != null && duration > 1,
            onValueChangeFinished = { player?.seekTo(target.toInt()); seeking = false },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(durationStringAdapt(if (seeking) target.toInt() else position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(durationStringAdapt(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ArchiveTransport(vm: AVPlayerVM) {
    val context = LocalContext.current
    val player by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val episode by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val status by player?.statusSimpleFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(PlayerStatusSimple.OTHER) }
    val playing = status == PlayerStatusSimple.PLAYING
    val loading by player?.loadingFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val failed by player?.playbackErrorFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var speed by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(false) }
    var bookmarkAt by remember(episode?.id) { mutableStateOf<Long?>(null) }
    var editBookmarkNote by remember { mutableStateOf(false) }
    var bookmarkNote by remember(episode?.id, episode?.comment) { mutableStateOf(TextFieldValue(episode?.comment.orEmpty())) }
    if (editBookmarkNote) CommentEditingDialog(textState = bookmarkNote, autoSave = false, onTextChange = { bookmarkNote = it }, onDismiss = { editBookmarkNote = false }, onSave = {
        episode?.let { item -> runOnIOScope { upsert(item) { it.addComment(bookmarkNote.text, addition = false) } } }
        editBookmarkNote = false
    })
    val sleepRemaining by produceState(0L) {
        while (isActive) { value = SleepManager.sleepManager?.timeLeft ?: 0; delay(1000) }
    }
    val recordingStart by player?.clipStartFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    if (speed) PlaybackSpeedFullDialog(vm.playerId, indexDefault = 0, maxSpeed = 3f, onDismiss = { speed = false })
    if (sleep) SleepTimerDialog { sleep = false }
    if (volume) VolumeDialog(vm) { volume = false }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.archive_buffering), style = MaterialTheme.typography.bodySmall) }
        if (failed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.archive_playback_failed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { episode?.let { startArchivePlayback(it, player, vm.playerId) } }) { Text(stringResource(R.string.archive_retry)) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player?.seekDelta(-rewindSecs * 1000) }, modifier = Modifier.size(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_fast_rewind), stringResource(R.string.archive_rewind, rewindSecs), Modifier.size(28.dp))
                    Text(rewindSecs.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
            FilledIconButton(onClick = { if (playing) player?.pause(false) else episode?.let { startArchivePlayback(it, player, vm.playerId) } }, enabled = episode != null && !loading, modifier = Modifier.size(80.dp)) {
                Icon(ImageVector.vectorResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_48dp), stringResource(if (failed) R.string.archive_retry else if (playing) R.string.archive_pause else R.string.archive_play), Modifier.size(40.dp))
            }
            IconButton(onClick = { player?.seekDelta(fastForwardSecs * 1000) }, modifier = Modifier.size(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_fast_forward), stringResource(R.string.archive_forward, fastForwardSecs), Modifier.size(28.dp))
                    Text(fastForwardSecs.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalButton(onClick = { speed = true }, contentPadding = PaddingValues(12.dp)) { Text(String.format(java.util.Locale.getDefault(), "%.1f×", vm.curPlaybackSpeed)) }
                Text(stringResource(R.string.archive_speed), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = { sleep = true }, modifier = Modifier.size(48.dp)) { Icon(ImageVector.vectorResource(R.drawable.ic_sleep), stringResource(R.string.sleep_timer_label)) }
                Text(if (sleepRemaining > 0) durationStringAdapt(sleepRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else stringResource(R.string.sleep_timer_label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = {
                    val item = episode ?: return@FilledTonalIconButton
                    val mark = (player?.getPosition() ?: item.position).coerceAtLeast(0).toLong()
                    scope.launch {
                        upsert(item) { if (mark !in it.marks) it.marks.add(mark) }
                        bookmarkAt = mark
                        val result = snackbar.showSnackbar(context.getString(R.string.archive_bookmark_saved, durationStringAdapt(mark.toInt())), context.getString(R.string.archive_undo))
                        if (result == SnackbarResult.ActionPerformed) { upsert(item) { it.marks.remove(mark) }; bookmarkAt = null }
                    }
                }, modifier = Modifier.size(48.dp)) { Icon(ImageVector.vectorResource(R.drawable.archive_bookmark), stringResource(R.string.archive_bookmark)) }
                Text(stringResource(R.string.archive_bookmark), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
        if (bookmarkAt != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.archive_bookmark_saved, durationStringAdapt(bookmarkAt!!.toInt())), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { editBookmarkNote = true }) { Text(stringResource(R.string.archive_add_note)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (recordingStart != null) TextButton(onClick = { player?.cancelClipRecording() }) { Text(stringResource(R.string.cancel_label)) }
            if (recordingStart != null) TextButton(onClick = { player?.let { active -> val start = recordingStart ?: return@let; val end = active.getPosition().toLong(); if (end > start) active.recordClip(start, end); active.clipStartFlow.value = null } }) { Text(stringResource(R.string.archive_finish_recording)) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.archive_more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    Text(stringResource(R.string.archive_current_speed, (player?.getPlaybackSpeed() ?: 1f).toString()), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    DropdownMenuItem(text = { Text(stringResource(R.string.archive_record_clip)) }, enabled = playing && recordingStart == null, onClick = { more = false; player?.let { active -> val start = active.getPosition().toLong(); active.clipStartFlow.value = start; active.recordClip(start) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.volume_adaptation)) }, onClick = { more = false; volume = true })
                    if (fallbackSpeed > 0.1f && player?.isSpeedForward != true) DropdownMenuItem(text = { Text(if (player?.isFallbackSpeed == true) stringResource(R.string.archive_normal_speed) else stringResource(R.string.archive_alternate_speed, fallbackSpeed.toString())) }, onClick = { more = false; player?.toggleFallbackSpeed(fallbackSpeed) })
                    if (speedforwardSpeed > 0.1f && player?.isFallbackSpeed != true) DropdownMenuItem(text = { Text(if (player?.isSpeedForward == true) stringResource(R.string.archive_normal_speed) else stringResource(R.string.archive_fast_speed, speedforwardSpeed.toString())) }, onClick = { more = false; player?.speedForward(speedforwardSpeed) })
                    if (skipforwardSpeed > 0.1f && player?.isFallbackSpeed != true && player?.isSpeedForward != true && skipforwardSpeed != speedforwardSpeed) DropdownMenuItem(text = { Text(stringResource(R.string.archive_skip_speed, skipforwardSpeed.toString())) }, onClick = { more = false; player?.speedForward(skipforwardSpeed) })
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveExpandedPlayer(vm: AVPlayerVM, onDetails: () -> Unit, singleColumn: Boolean = false, video: (@Composable () -> Unit)? = null) {
    val player by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val episode by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val item = episode
    if (item == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ArchivePlayerChoices()
            ArchiveEmpty(R.string.archive_choose_episode, R.string.archive_choose_episode_body, R.string.archive_listen) { selectPrimary(Listen); psState = PSState.PartiallyExpanded }
        }
        return
    }
    val queue by actQueueFlow.collectAsStateWithLifecycle()
    val queuedIds by remember(queue.id) { realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", queue.id).asFlow().map { change -> change.list.map { it.episodeId } } }.collectAsStateWithLifecycle(initialValue = emptyList())
    val nextId = queuedIds.let { ids -> val index = ids.indexOf(item.id); ids.getOrNull(index + 1) ?: ids.firstOrNull()?.takeIf { queue.repeatQueue && it != item.id } }
    val next = nextId?.let { episodeById(it) }
    val configuration = LocalConfiguration.current
    var tab by rememberSaveable(item.id) { mutableIntStateOf(0) }
    var editNote by remember { mutableStateOf(false) }
    var note by remember(item.id, item.comment) { mutableStateOf(TextFieldValue(item.comment)) }
    if (editNote) CommentEditingDialog(textState = note, autoSave = false, onTextChange = { note = it }, onDismiss = { editNote = false }, onSave = { runOnIOScope { upsert(item) { it.addComment(note.text, addition = false) } }; editNote = false })
    Column(Modifier.widthIn(max = 960.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        val count by activeTheatresCount.collectAsStateWithLifecycle()
        ArchivePlayerChoices()
        @Composable fun Heading() {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (video != null) video() else ArchiveArtwork(item, Modifier.size(if (configuration.screenHeightDp < 650 || configuration.fontScale >= 1.3f) 120.dp else 176.dp))
                Spacer(Modifier.height(16.dp))
                Text(item.title ?: stringResource(R.string.archive_no_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(item.feed?.title ?: stringResource(R.string.archive_no_source), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        if (!singleColumn && configuration.screenWidthDp >= 600) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Heading() }
            Column(Modifier.weight(1f)) { ArchiveSeekBar(vm); ArchiveTransport(vm) }
        } else { Heading(); Spacer(Modifier.height(12.dp)); ArchiveSeekBar(vm); ArchiveTransport(vm) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (next != null) { ArchiveArtwork(next, Modifier.size(48.dp)); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.archive_up_next), style = MaterialTheme.typography.titleMedium)
                Text(next?.title ?: stringResource(R.string.archive_queue_finished), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (next != null) Text(listOfNotNull(next.feed?.title, next.duration.takeIf { it > 0 }?.let { durationStringAdapt(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if(queue.isVirtual()) stringResource(R.string.archive_from_list) else queue.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { player?.skip(force = true) }, enabled = next != null) { Text(stringResource(R.string.archive_next)) }
        }
        TextButton(onClick = { navTo(Queues(queue.id)); psState = PSState.PartiallyExpanded }) { Text(stringResource(R.string.archive_manage_queues)) }
        val tabs = if (item.chapters.isEmpty()) listOf(R.string.archive_details, R.string.archive_notes) else listOf(R.string.archive_details, R.string.archive_chapters, R.string.archive_notes)
        PrimaryTabRow(selectedTabIndex = tab.coerceAtMost(tabs.lastIndex)) {
            tabs.forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) }) }
        }
        when (tabs.getOrElse(tab) { R.string.archive_details }) {
            R.string.archive_details -> TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text(stringResource(R.string.archive_episode_details)) }
            R.string.archive_chapters -> item.chapters.forEach { chapter -> ListItem(headlineContent = { Text(chapter.title ?: "") }, supportingContent = { Text(durationStringAdapt(chapter.start.toInt())) }, modifier = Modifier.clickable { player?.seekTo(chapter.start.toInt()) }) }
            else -> {
                if (item.comment.isNotBlank()) Text(item.comment, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { editNote = true }) { Text(stringResource(R.string.archive_add_note)) }
                item.marks.forEach { mark -> TextButton(onClick = { player?.seekTo(mark.toInt()) }, modifier = Modifier.fillMaxWidth()) { Icon(ImageVector.vectorResource(R.drawable.archive_bookmark), null); Spacer(Modifier.width(8.dp)); Text(durationStringAdapt(mark.toInt())) } }
                if (item.marks.isEmpty() && item.comment.isBlank()) Text(stringResource(R.string.archive_no_notes_body), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDetails) { Text(stringResource(R.string.archive_all_notes_clips)) }
            }
        }
    }
}
