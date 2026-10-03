package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.sourcing.download.EpisodeAdrDLManager
import ac.mdiq.podcini.ui.actions.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ac.mdiq.podcini.storage.database.availableLocalLocation
import ac.mdiq.podcini.storage.database.availableRemoteLocation
import ac.mdiq.podcini.storage.database.libraryKind
import ac.mdiq.podcini.storage.database.playableLocations
import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.isPlaybackFinished
import ac.mdiq.podcini.storage.model.playedPercentage
import ac.mdiq.podcini.utils.formatDateTimeFlex
import ac.mdiq.podcini.storage.specs.EnqueueLocation
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.utils.durationStringAdapt
import ac.mdiq.podcini.ui.actions.ActionButton
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.screens.*
import ac.mdiq.podcini.utils.NetworkUtils.imageLoader
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ArchiveNavigation(rail: Boolean) {
    val destinations = listOf(
        Triple(Listen, R.string.archive_listen, R.drawable.archive_headphones),
        Triple(Library, R.string.library, R.drawable.ic_subscriptions),
        Triple(Search, R.string.archive_search, R.drawable.ic_search)
    )
    val selected = primaryDestination()
    if (rail) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Spacer(Modifier.height(16.dp))
        destinations.forEach { (key, label, icon) ->
            NavigationRailItem(selected = selected == key, onClick = { selectPrimary(key) },
                icon = { Icon(ImageVector.vectorResource(icon), null) }, label = { Text(stringResource(label)) })
        }
    } else NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        destinations.forEach { (key, label, icon) ->
            NavigationBarItem(selected = selected == key, onClick = { selectPrimary(key) },
                icon = { Icon(ImageVector.vectorResource(icon), null) }, label = { Text(stringResource(label)) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveTopBar(title: String, back: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (back) IconButton(onClick = { navBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.archive_back)) } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun ArchiveEmpty(
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes action: Int? = null,
    @DrawableRes icon: Int? = null,
    onAction: () -> Unit = {}
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            if (icon != null) {
                Icon(
                    imageVector = ImageVector.vectorResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(action)) }
            }
        }
    }
}

@Composable
fun ArchiveArtwork(episode: Episode?, modifier: Modifier = Modifier, finished: Boolean = false) {
    PodcastArtwork(episode?.imageLocation(), modifier, finished)
}

@Composable
fun ArchiveEpisodeRow(
    episode: Episode, button: ActionButton, playing: Boolean, current: Boolean,
    selected: Boolean, selecting: Boolean, isExternal: Boolean,
    statusMode: StatusRowMode, showActions: Boolean,
    downloadProgress: Int?, onOpen: () -> Unit, onSelect: () -> Unit,
    onAction: (suspend (Episode, ButtonTypes) -> Unit)?,
    showHighlights: Boolean = false
) {
    var expanded by remember(episode.id) { mutableStateOf(false) }
    var showMore by remember(episode.id) { mutableStateOf(false) }
    var rate by remember(episode.id) { mutableStateOf(false) }
    var organize by remember(episode.id) { mutableStateOf(false) }
    var schedule by remember(episode.id) { mutableStateOf(false) }
    val extraActions = remember(episode.id) { listOf(AddComment(), AddTag(), Shelve(), AddTodo(), SetPlaybackState(), SetDueDate(), Timer()) }
    extraActions.forEach { it.ActionOptions() }
    if (rate) ChooseRatingDialog(listOf(episode)) { rate = false }
    if (organize || schedule) CommonPopupCard(onDismiss = { organize = false; schedule = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(if (schedule) R.string.archive_schedule else R.string.archive_organize), style = MaterialTheme.typography.titleLarge)
            extraActions.filter { if (schedule) it is SetPlaybackState || it is SetDueDate || it is Timer else it is AddComment || it is AddTag || it is Shelve || it is AddTodo }.forEach { action ->
                EpisodeAction.onEpisode = episode
                if (action.enabled()) EpisodeActionRow(action.title, action.iconRes) { organize = false; schedule = false; action.performAction(episode) }
            }
        }
    }
    var locations by remember(episode.id) { mutableStateOf(false) }
    if (locations) MediaLocationsDialog(episode) { locations = false }
    var chooseQueue by remember(episode.id) { mutableStateOf(false) }
    val title = episode.title ?: stringResource(R.string.archive_no_title)
    val primaryLabel = stringResource(if (playing) R.string.archive_pause_episode else R.string.archive_play_episode, title)
    val largeText = LocalConfiguration.current.fontScale >= 1.3f
    val prefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    val finished = episode.isPlaybackFinished(prefs.completionPercent)
    val surface = when { selected -> MaterialTheme.colorScheme.secondaryContainer; current -> MaterialTheme.colorScheme.primaryContainer; finished -> MaterialTheme.colorScheme.surfaceContainerLow; else -> MaterialTheme.colorScheme.surface }
    if (showMore) button.AltActionsDialog(includeDownloads = isExternal) { showMore = false }
    if (chooseQueue) PlaylistPickerDialog(listOf(episode)) { chooseQueue = false }
    if (expanded) EpisodeActionSheet(episode, onDismiss = { expanded = false }, onChoosePlaylist = { expanded = false; chooseQueue = true }) {
        if (!isExternal) EpisodeActionRow(stringResource(R.string.archive_play_next), R.drawable.ic_playlist_play) {
            expanded = false
            runOnIOScope { addToQueue(listOf(episode), actQueueFlow.value, EnqueueLocation.AFTER_CURRENTLY_PLAYING) }
        }
        EpisodeActionRow(stringResource(R.string.play_only_item), R.drawable.ic_play_24dp) {
            expanded = false
            button.item = episode
            button.type = if (episode.availableLocalLocation != null) ButtonTypes.PLAY_ONE else ButtonTypes.STREAM_ONE
            button.onClick()
        }
        if (!isExternal) {
            val downloading = downloadProgress != null || episode.downloadUrl?.let { EpisodeAdrDLManager.manager.isDownloading(it) } == true
            val local = episode.feed?.isLocal == true
            val fileAction = episodeDownloadAction(local, !episode.fileUrl.isNullOrBlank() && (episode.downloaded || local), downloading,
                !episode.downloadUrl.isNullOrBlank(), isMediaDownloadable(episode))
            val downloadType = when (fileAction) {
                EpisodeDownloadAction.CANCEL -> ButtonTypes.CANCEL
                EpisodeDownloadAction.REMOVE_DOWNLOAD, EpisodeDownloadAction.DELETE_LOCAL_FILE -> ButtonTypes.DELETE
                EpisodeDownloadAction.DOWNLOAD -> ButtonTypes.DOWNLOAD
                null -> null
            }
            if (downloadType != null) EpisodeActionRow(stringResource(when (downloadType) {
                ButtonTypes.CANCEL -> R.string.archive_cancel_download
                ButtonTypes.DELETE -> if (local) R.string.archive_delete_local else R.string.delete_episode_label
                else -> R.string.download_label
            }), when (downloadType) {
                ButtonTypes.CANCEL -> R.drawable.ic_cancel
                ButtonTypes.DELETE -> R.drawable.ic_delete
                else -> R.drawable.ic_download
            }) {
                expanded = false
                button.item = episode
                button.typeToCancel = ButtonTypes.DOWNLOAD
                button.type = downloadType
                button.onClick()
            }
            EpisodeActionRow(stringResource(R.string.archive_mark_played), R.drawable.ic_mark_played) {
                expanded = false
                runOnIOScope { upsert(episode) { it.setPlayState(EpisodeState.PLAYED) } }
            }
            EpisodeActionRow(stringResource(R.string.set_rating_label), R.drawable.ic_star) { expanded = false; rate = true }
            EpisodeActionRow(stringResource(R.string.archive_organize), R.drawable.baseline_label_24) { expanded = false; organize = true }
            EpisodeActionRow(stringResource(R.string.archive_schedule), R.drawable.baseline_watch_later_24) { expanded = false; schedule = true }
        }
        EpisodeActionRow(stringResource(R.string.media_available_from), R.drawable.ic_info) { expanded = false; locations = true }
        EpisodeActionRow(stringResource(R.string.archive_playback_options), R.drawable.ic_play_24dp) { expanded = false; showMore = true }
        if (!isExternal) EpisodeActionRow(stringResource(R.string.archive_select), R.drawable.ic_check) { expanded = false; onSelect() }
    }
    fun play() {
        if (playing) {
            theatres.filter { it.mPlayerFlow.value?.curMediaFlow?.value?.id == episode.id }.forEach { it.mPlayerFlow.value?.pause(false) }
            return
        }
        button.item = episode
        val type = when {
            episode.availableLocalLocation != null -> if (button.preferSingle) ButtonTypes.PLAY_ONE else ButtonTypes.PLAY
            episode.availableRemoteLocation == null -> ButtonTypes.TTS_NOW
            else -> if (button.preferSingle) ButtonTypes.STREAM_ONE else ButtonTypes.STREAM
        }
        button.type = type
        button.beforePlayback = { onAction?.invoke(episode, type) }
        button.onClick()
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surface)
        .combinedClickable(onClick = onOpen, onLongClickLabel = stringResource(R.string.archive_episode_actions, title),
            onLongClick = { if (selecting) onSelect() else expanded = true })) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onOpen() })
            else ArchiveArtwork(episode, Modifier.size(if (largeText) 56.dp else 72.dp), finished = finished)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(title, color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = if (largeText) 5 else 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(listOf(episode.artist, episode.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { episode.feed?.title ?: episode.parentTitle ?: stringResource(R.string.archive_no_source) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val state = buildList {
                    if (current) add(stringResource(if (playing) R.string.archive_playing else R.string.archive_paused))
                    if (finished) add(stringResource(R.string.playback_finished))
                    else if (episode.downloaded) add(stringResource(R.string.archive_downloaded))
                }.joinToString(" · ")
                val time = durationStringAdapt((episode.duration - episode.position).coerceAtLeast(0))
                Text(listOf(time, state).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EpisodePlaybackHistory(episode)
                if (showHighlights) {
                    val reasons = buildList {
                        if (episode.rating >= Rating.GOOD.code) add(stringResource(R.string.archive_liked))
                        if (episode.comment.isNotBlank()) add(stringResource(R.string.archive_has_notes))
                        if (episode.marks.isNotEmpty()) add(stringResource(R.string.archive_has_bookmarks))
                        if (episode.clips.isNotEmpty()) add(stringResource(R.string.archive_has_clips))
                    }
                    Text(reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (statusMode == StatusRowMode.Comment && episode.comment.isNotBlank()) Text(episode.comment, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                if (statusMode == StatusRowMode.Tags && episode.tags.isNotEmpty()) {
                    var tagEditor by remember { mutableStateOf(false) }
                    if (tagEditor) MediaTagsDialog(listOf(episode.id)) { tagEditor = false }
                    CompactTagList(episode.tags, onTagClick = { tagEditor = true }, onShowAll = { tagEditor = true })
                }
                if (downloadProgress != null) LinearProgressIndicator(progress = { downloadProgress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            if (!selecting && showActions) {
                FilledTonalIconButton(onClick = ::play, modifier = Modifier.size(48.dp),
                    colors = if (playing) IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else IconButtonDefaults.filledTonalIconButtonColors()) {
                    Icon(ImageVector.vectorResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_24dp), primaryLabel, Modifier.size(28.dp))
                }
            }
            if (!selecting) IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.archive_episode_actions, title))
            }
        }
        if (episode.duration > 0 && (current || episode.playedPosition > 0)) LinearProgressIndicator(
            progress = { ((if (current) episode.position else episode.playedPosition).toFloat() / episode.duration).coerceIn(0f, 1f) },
            color = if (finished) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}


@Composable
fun EpisodePlaybackHistory(episode: Episode, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        episode.playedPercentage?.let { percent ->
            Text(stringResource(R.string.playback_percent_played, percent), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (episode.lastPlayedTime > 0L) Text(
            stringResource(R.string.playback_last_played_on, formatDateTimeFlex(episode.lastPlayedTime)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ArchiveFilterChips(filter: ac.mdiq.podcini.storage.specs.EpisodeFilter, onRemove: (String) -> Unit, onClear: () -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filter.propertySet.size) { index ->
            val key = filter.propertySet.elementAt(index)
            val group = ac.mdiq.podcini.storage.specs.EpisodeFilter.EpisodesFilterGroup.entries.firstOrNull { it.properties.any { property -> property.filterId == key } }
            val property = group?.properties?.firstOrNull { it.filterId == key }
            val label = when {
                key.startsWith("tags ") -> stringResource(R.string.filter_tag_summary, key.removePrefix("tags "))
                key.startsWith("text ") -> stringResource(R.string.filter_text_summary, filter.extractText())
                group == ac.mdiq.podcini.storage.specs.EpisodeFilter.EpisodesFilterGroup.TITLE_TEXT && property != null -> stringResource(R.string.filter_title_summary, stringResource(property.displayName), filter.titleText)
                group == ac.mdiq.podcini.storage.specs.EpisodeFilter.EpisodesFilterGroup.DURATION && property != null -> stringResource(R.string.filter_range_summary,
                    stringResource(when (key) { "lower" -> R.string.filter_below; "middle" -> R.string.filter_between; else -> R.string.filter_above }),
                    (filter.durationFloor / 1000).toString(), if (filter.durationCeiling == Int.MAX_VALUE) stringResource(R.string.filter_unlimited) else (filter.durationCeiling / 1000).toString())
                group != null && property != null -> stringResource(group.nameRes) + ": " + stringResource(property.displayName)
                else -> key.substringAfter(' ', key)
            }
            InputChip(selected = true, onClick = { onRemove(key) }, label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.archive_remove_filter, label), Modifier.size(18.dp)) })
        }
        item { TextButton(onClick = onClear) { Text(stringResource(R.string.archive_clear_filters)) } }
    }
}
