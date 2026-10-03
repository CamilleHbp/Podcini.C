package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.EnqueueLocation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map

@Composable
fun PlaylistPickerDialog(items: List<Episode>, onDismiss: () -> Unit) {
    val playlists by remember { queuesFlow.map { it.list.filter { q -> q.id != LISTENING_QUEUE_ID && !q.smart } } }.collectAsStateWithLifecycle(initialValue = emptyList())
    var create by remember { mutableStateOf(false) }
    if (create) PlaylistEditorDialog(items = items, onDismiss = onDismiss)
    else AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.playlist_add)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(if (playlists.isEmpty()) R.string.episode_playlist_empty else R.string.playlist_reference_hint), style = MaterialTheme.typography.bodyMedium)
            playlists.forEach { list -> EpisodeActionRow(list.displayName, R.drawable.ic_playlist_play) {
                runOnIOScope { addToQueue(items, list, EnqueueLocation.BACK) }; onDismiss()
            } }
        }
    }, confirmButton = { TextButton(onClick = { create = true }) { Text(stringResource(R.string.playlist_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}

@Composable
fun PlaylistEditorDialog(existing: PlayQueue? = null, rules: PlayQueue? = null, items: List<Episode> = emptyList(), onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var smart by remember { mutableStateOf(existing?.smart ?: (rules != null)) }
    var browseRules by remember { mutableStateOf((existing ?: rules)?.ruleBrowseFilter.orEmpty()) }
    var editBrowseRules by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(existing?.removeWhenFinished ?: false) }
    var kind by remember { mutableStateOf((existing ?: rules)?.ruleKind ?: "all") }
    var unfinished by remember { mutableStateOf((existing ?: rules)?.ruleUnfinished ?: false) }
    var favourite by remember { mutableStateOf((existing ?: rules)?.ruleFavourite ?: false) }
    var downloaded by remember { mutableStateOf((existing ?: rules)?.ruleDownloaded ?: false) }
    var tag by remember { mutableStateOf((existing ?: rules)?.ruleTag.orEmpty()) }
    var artist by remember { mutableStateOf((existing ?: rules)?.ruleArtist.orEmpty()) }
    var text by remember { mutableStateOf((existing ?: rules)?.ruleText.orEmpty()) }
    var descendants by remember { mutableStateOf((existing ?: rules)?.ruleTagDescendants ?: true) }
    var tags by remember { mutableStateOf(existing?.tags?.toSet().orEmpty()) }
    var editTags by remember { mutableStateOf(false) }
    var embedTags by remember { mutableStateOf(false) }
    if (editTags) TagSettingDialog(TagType.Feed, tags, onDismiss = { editTags = false }, onEmbeddingChoice = { embedTags = it }) { tags = it.toSet() }
    var sourcesExpanded by remember { mutableStateOf(false) }
    val sources = remember { mutableStateListOf<Long>().apply { addAll((existing ?: rules)?.ruleFeedIds.orEmpty()) } }
    val feeds by remember { realm.query(Feed::class, "id > 1000").asFlow().map { it.list } }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (editBrowseRules) LibraryFilterEditor(decodeLibraryFilter(browseRules) ?: LibraryFilter(), onDismiss = { editBrowseRules = false }) { browseRules = it.encode(); editBrowseRules = false }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (existing == null) R.string.playlist_new else R.string.playlist_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true)
            if (existing == null && items.isEmpty()) LabelSwitch(R.string.playlist_smart, smart) { smart = it }
            if (smart) {
                Text(stringResource(R.string.playlist_rules_hint), style = MaterialTheme.typography.bodyMedium)
                if (browseRules.isNotBlank()) {
                    Text(stringResource(R.string.browse_saved_rules), style = MaterialTheme.typography.titleMedium)
                    val saved = decodeLibraryFilter(browseRules)
                    saved?.let {
                        if (it.scopeTag.isNotBlank()) Text(it.scopeTag.replace("/", " › "))
                        if (it.scopeCreator.isNotBlank()) Text(libraryCreatorName(it.scopeCreator))
                        if (it.scopeAlbum.isNotBlank()) Text(stringResource(R.string.browse_album_rule))
                        if (it.scopeFeed != 0L) Text(feeds.firstOrNull { feed -> feed.id == it.scopeFeed }?.title.orEmpty())
                        if (it.text.isNotBlank()) Text(it.text)
                        LibraryAppliedFilters(it) { edited -> browseRules = edited.encode() }
                    }
                    TextButton(onClick = { editBrowseRules = true }) { Text(stringResource(R.string.browse_edit_rules)) }
                }
                KindPicker(kind) { kind = it }
                LabelSwitch(R.string.library_unfinished, unfinished) { unfinished = it }
                LabelSwitch(R.string.library_favourites, favourite) { favourite = it }
                LabelSwitch(R.string.archive_downloads, downloaded) { downloaded = it }
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.library_search)) })
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.library_artists)) })
                OutlinedTextField(tag, { tag = it }, label = { Text(stringResource(R.string.library_tag_filter)) })
                LabelSwitch(R.string.library_include_children, descendants) { descendants = it }
                TextButton(onClick = { sourcesExpanded = !sourcesExpanded }) { Text(stringResource(R.string.playlist_sources, sources.size)) }
                if (sourcesExpanded) feeds.forEach { feed -> Row(Modifier.fillMaxWidth().clickable { if (!sources.remove(feed.id)) sources.add(feed.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(feed.id in sources, null); Text(feed.title.orEmpty(), Modifier.weight(1f))
                } }
            } else {
                Text(stringResource(R.string.playlist_after_completion), style = MaterialTheme.typography.titleSmall)
                LabelSwitch(R.string.playlist_remove_completed, remove) { remove = it }
                Text(stringResource(if (remove) R.string.playlist_cleanup_hint else R.string.playlist_keep_hint), style = MaterialTheme.typography.bodySmall)
            }
            CompactTagList(tags, onTagClick = { editTags = true }, onShowAll = { editTags = true })
            TextButton(onClick = { editTags = true }) { Text(stringResource(R.string.tags_label)) }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        runOnIOScope {
            val draft = PlayQueue().apply {
                ruleKind = kind; ruleUnfinished = unfinished; ruleFavourite = favourite; ruleDownloaded = downloaded
                ruleBrowseFilter = browseRules; ruleTag = tag.trim(); ruleTagDescendants = descendants; ruleText = text; ruleArtist = artist; ruleFeedIds.addAll(sources)
                sortOrder = (existing ?: rules)?.sortOrder ?: ac.mdiq.podcini.storage.specs.EpisodeSortOrder.DATE_DESC
            }
            val playlist = existing ?: createPlaylist(name, items, if (smart) draft else null)
            upsert(playlist) { q ->
                q.name = name.trim(); q.removeWhenFinished = remove
                q.ruleKind = kind; q.ruleUnfinished = unfinished; q.ruleFavourite = favourite; q.ruleDownloaded = downloaded
                q.ruleBrowseFilter = browseRules; q.ruleTag = tag.trim(); q.ruleTagDescendants = descendants; q.ruleText = text; q.ruleArtist = artist
                q.ruleFeedIds.clear(); q.ruleFeedIds.addAll(sources)
                q.tags.clear(); q.tags.addAll(canonicalTags(tags))
                q.update()
            }
            if (embedTags) ac.mdiq.podcini.storage.tags.MediaTagRepository.edit(playlistItems(playlist).map { it.id }, add = tags)
        }
        onDismiss()
    }) { Text(stringResource(R.string.library_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}

@Composable
fun LabelSwitch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), Modifier.weight(1f)); Switch(checked, onChange)
    }
}

@Composable
fun KindPicker(kind: String, allowAll: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val kinds = listOf("all" to R.string.library_items, "podcast" to R.string.library_podcasts, "music" to R.string.library_music, "audiobook" to R.string.browse_audiobooks, "audio" to R.string.browse_other_audio, "video" to R.string.library_videos).filter { allowAll || it.first != "all" }
    Box {
        TextButton(onClick = { expanded = true }) { Text(stringResource(kinds.firstOrNull { it.first == kind }?.second ?: R.string.library_items)) }
        DropdownMenu(expanded, { expanded = false }) { kinds.forEach { (key, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onChange(key); expanded = false }) } }
    }
}

@Composable
fun MediaLocationsDialog(item: Episode, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(item.libraryKind) }
    var artist by remember { mutableStateOf(item.artist) }
    var album by remember { mutableStateOf(item.album) }
    var albumArtist by remember { mutableStateOf(item.albumArtist) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val attachFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runOnIOScope { upsert(item) { if (uri.toString() !in it.playableLocations) it.additionalLocations.add(uri.toString()) } }
            onDismiss()
        }
    }
    val valid = url.startsWith("https://") || url.startsWith("http://")
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.media_available_from)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.media_locations_hint))
            item.playableLocations.forEach { location ->
                Text(location, style = MaterialTheme.typography.bodySmall)
                if (location in item.additionalLocations) TextButton(onClick = { runOnIOScope { upsert(item) { it.additionalLocations.remove(location) } }; onDismiss() }) { Text(stringResource(R.string.media_remove_location)) }
            }
            TextButton(onClick = { attachFile.launch(arrayOf("audio/*", "video/*")) }) { Text(stringResource(R.string.media_attach_file)) }
            KindPicker(kind, allowAll = false) { kind = it }
            if (kind != "video") {
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.library_artists)) })
                OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.media_album)) })
                OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text(stringResource(R.string.browse_album_artist)) })
            }
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.media_add_url)) }, isError = url.isNotBlank() && !valid)
        }
    }, confirmButton = { TextButton(enabled = url.isBlank() || valid, onClick = {
        runOnIOScope { upsert(item) { it.contentKind = kind; it.artist = artist.trim(); it.album = album.trim(); it.albumArtist = albumArtist.trim(); if (valid && url !in it.playableLocations) it.additionalLocations.add(url.trim()) } }; onDismiss()
    }) { Text(stringResource(R.string.library_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}
