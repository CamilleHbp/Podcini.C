package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.specs.EnqueueLocation
import ac.mdiq.podcini.storage.specs.Rating
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map

/** The same entry point for long-press and overflow, with advanced controls disclosed on demand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeActionSheet(
    episode: Episode,
    onDismiss: () -> Unit,
    onChoosePlaylist: () -> Unit,
    moreActions: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(episode.id) { mutableStateOf(false) }
    var editTags by remember { mutableStateOf(false) }
    if (editTags) MediaTagsDialog(listOf(episode.id)) { editTags = false }
    val savedEpisode by remember(episode.id) {
        realm.query(Episode::class, "id == $0", episode.id).first().asFlow().map { it.obj }
    }.collectAsStateWithLifecycle(initialValue = episode)
    val entries by remember(episode.id) {
        realm.query(QueueEntry::class, "episodeId == $0", episode.id).asFlow().map { it.list }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val favourite = (savedEpisode ?: episode).rating >= Rating.GOOD.code
    val inLater = entries.any { it.queueId == 0L }
    val inQueue = entries.any { it.queueId == LISTENING_QUEUE_ID }
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 560.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets.safeDrawing },
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = lightSurface, isAppearanceLightNavigationBars = lightSurface),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                ArchiveArtwork(episode, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episode.title ?: stringResource(R.string.archive_no_title), style = MaterialTheme.typography.titleMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    val source = episode.feed?.title ?: episode.parentTitle
                    if (!source.isNullOrBlank()) Text(source, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            EpisodeActionRow(stringResource(if (favourite) R.string.episode_unfavourite else R.string.episode_favourite), R.drawable.ic_star, active = favourite) {
                runOnIOScope { upsert(savedEpisode ?: episode) { it.setRating(if (favourite) Rating.UNRATED else Rating.SUPER) } }
                onDismiss()
            }
            EpisodeActionRow(stringResource(if (inLater) R.string.episode_in_listen_later else R.string.playlist_listen_later),
                R.drawable.baseline_watch_later_24, enabled = !inLater, active = inLater) {
                runOnIOScope {
                    val later = realm.query(PlayQueue::class, "id == 0").first().find()
                        ?: upsert(PlayQueue().apply { id = 0; name = "Listen later"; removeWhenFinished = true }) {}
                    addToQueue(listOf(episode), later, EnqueueLocation.BACK)
                }
                onDismiss()
            }
            EpisodeActionRow(stringResource(if (inQueue) R.string.episode_in_listening_queue else R.string.episode_add_listening_queue),
                R.drawable.ic_playlist_play, enabled = !inQueue, active = inQueue) {
                runOnIOScope { addToQueue(listOf(episode), actQueueFlow.value, EnqueueLocation.BACK) }
                onDismiss()
            }
            EpisodeActionRow(stringResource(R.string.playlist_add), R.drawable.outline_playlist_add_24, opensChoices = true, onClick = onChoosePlaylist)
            EpisodeActionRow(stringResource(R.string.tags_label), R.drawable.ic_tag, opensChoices = true) { editTags = true }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val expansionState = stringResource(if (expanded) R.string.filter_expanded else R.string.filter_collapsed)
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .semantics(mergeDescendants = true) { stateDescription = expansionState }
                .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(if (expanded) R.string.episode_fewer_actions else R.string.episode_more_actions),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) moreActions()
        }
    }
}

@Composable
internal fun EpisodeActionRow(
    label: String,
    @DrawableRes icon: Int,
    enabled: Boolean = true,
    active: Boolean = false,
    opensChoices: Boolean = false,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { if (active) selected = true }
        .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Icon(ImageVector.vectorResource(icon), null, Modifier.size(24.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (active && !enabled) Icon(ImageVector.vectorResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        if (opensChoices) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
