package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val librarySections = listOf("podcasts", "creators", "albums", "tags", "playlists", "items")
fun librarySectionLabel(section: String): Int = when (section) {
    "podcasts" -> R.string.library_podcasts; "creators" -> R.string.browse_creators
    "albums" -> R.string.browse_albums; "tags" -> R.string.library_tags
    "playlists" -> R.string.library_playlists; "items" -> R.string.search_library_media
    "highlights" -> R.string.search_highlights; "manage" -> R.string.search_manage_library
    else -> R.string.library
}

@Composable
fun libraryCreatorName(value: String): String = if (value == UNKNOWN_LIBRARY_CREATOR || value.isBlank()) stringResource(R.string.browse_unknown_creator) else value

@Composable
fun libraryDestinationTitle(destination: LibraryDestination): String = when {
    destination.section in listOf("tag", "tags") && destination.tag.isNotBlank() -> tagLeafLabel(destination.tag)
    destination.section == "creator" && destination.creator.isNotBlank() -> libraryCreatorName(destination.creator)
    destination.title.isNotBlank() -> destination.title
    else -> stringResource(librarySectionLabel(destination.section))
}

@Composable
fun LibraryAppliedFilters(filter: LibraryFilter, onChange: (LibraryFilter) -> Unit) {
    val entries = mutableListOf<Pair<String, LibraryFilter>>()
    if (filter.kind != "all") entries += libraryKindName(filter.kind) to filter.copy(kind = "all")
    if (filter.unfinished) entries += stringResource(R.string.library_unfinished) to filter.copy(unfinished = false)
    if (filter.downloaded) entries += stringResource(R.string.archive_downloads) to filter.copy(downloaded = false)
    if (filter.favourite) entries += stringResource(R.string.library_favourites) to filter.copy(favourite = false)
    if (filter.maxMinutes > 0) entries += stringResource(R.string.browse_duration_chip, filter.maxMinutes) to filter.copy(maxMinutes = 0)
    if (filter.tags.isNotEmpty()) entries += stringResource(if (filter.allTags) R.string.browse_all_tags_chip else R.string.browse_any_tags_chip,
        filter.tags.map { tagLabel(it) }.joinToString(", ")) to filter.copy(tags = emptyList())
    if (filter.excludedTags.isNotEmpty()) entries += stringResource(R.string.browse_excluded_chip, filter.excludedTags.map { tagLabel(it) }.joinToString(", ")) to filter.copy(excludedTags = emptyList())
    if (filter.creators.isNotEmpty()) entries += stringResource(R.string.browse_creators_chip, filter.creators.map { libraryCreatorName(it) }.joinToString(", ")) to filter.copy(creators = emptyList())
    if (entries.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries) { (label, updated) -> InputChip(true, { onChange(updated) }, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.browse_remove_filter, label)) }) }
    }
}

@Composable
fun libraryKindName(kind: String): String = stringResource(when (kind) {
    "podcast" -> R.string.library_podcasts; "music" -> R.string.library_music; "video" -> R.string.library_videos
    "audiobook" -> R.string.browse_audiobooks; "audio" -> R.string.browse_other_audio; else -> R.string.library_items
})

@Composable
fun LibraryFilterEditor(filter: LibraryFilter, catalogue: LibraryCatalogue? = null,
                        onDismiss: () -> Unit, onApply: (LibraryFilter) -> Unit) {
    val ownCatalogue = if (catalogue == null) remember { libraryCatalogueFlow() }.collectAsStateWithLifecycle(LibraryCatalogue()).value else catalogue
    var encoded by rememberSaveable(filter.encode()) { mutableStateOf(filter.encode()) }
    val draft = decodeLibraryFilter(encoded) ?: filter
    fun change(value: LibraryFilter) { encoded = value.encode() }
    var maxMinutes by rememberSaveable(filter.encode()) { mutableStateOf(filter.maxMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
    val valid = maxMinutes.isBlank() || maxMinutes.toIntOrNull()?.let { it in 1..35791 } == true
    val reset = LibraryFilter(scopeTag = filter.scopeTag, scopeCreator = filter.scopeCreator, scopeAlbum = filter.scopeAlbum, scopeFeed = filter.scopeFeed)
    val activeGroups = listOf(draft.text.isNotBlank(), draft.kind != "all", draft.unfinished || draft.downloaded || draft.favourite,
        draft.tags.isNotEmpty(), draft.excludedTags.isNotEmpty(), draft.creators.isNotEmpty(), draft.maxMinutes > 0).count { it }
    FilterEditor(stringResource(R.string.archive_filter), activeGroups = activeGroups,
        previewKey = encoded + ownCatalogue.facets.hashCode(), countMatches = if (ownCatalogue.loaded && !ownCatalogue.failed) ({ ownCatalogue.facets.values.count(draft::matches).toLong() }) else null,
        valid = valid, onReset = { change(reset); maxMinutes = "" }, onDismiss = { change(filter); maxMinutes = filter.maxMinutes.takeIf { it > 0 }?.toString().orEmpty(); onDismiss() }, onApply = { onApply(draft) }) {
        OutlinedTextField(draft.text, { change(draft.copy(text = it)) }, label = { Text(stringResource(R.string.library_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilterSection(stringResource(R.string.browse_quick_filters)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterOption(stringResource(R.string.library_unfinished), draft.unfinished) { change(draft.copy(unfinished = !draft.unfinished)) }
                FilterOption(stringResource(R.string.archive_downloads), draft.downloaded) { change(draft.copy(downloaded = !draft.downloaded)) }
                FilterOption(stringResource(R.string.library_favourites), draft.favourite) { change(draft.copy(favourite = !draft.favourite)) }
            }
        }
        FilterSection(stringResource(R.string.browse_media_type)) {
            FilterSingleChoice(listOf("all", "podcast", "music", "audiobook", "video", "audio").map { it to libraryKindName(it) }, draft.kind) { change(draft.copy(kind = it)) }
        }
        FilterExpansion(stringResource(R.string.library_tags), filterSelectionSummary(draft.tags.map { tagLabel(it) })) {
            FilterSingleChoice(listOf("any" to stringResource(R.string.browse_match_any), "all" to stringResource(R.string.browse_match_all)), if (draft.allTags) "all" else "any") { change(draft.copy(allTags = it == "all")) }
            LibraryChoiceList(ownCatalogue.tags.map { it to tagLabel(it) }, draft.tags) { change(draft.copy(tags = it)) }
            LabelSwitch(R.string.library_include_children, draft.descendants) { change(draft.copy(descendants = it)) }
        }
        FilterExpansion(stringResource(R.string.browse_exclude_tags), filterSelectionSummary(draft.excludedTags.map { tagLabel(it) })) {
            LibraryChoiceList(ownCatalogue.tags.map { it to tagLabel(it) }, draft.excludedTags) { change(draft.copy(excludedTags = it)) }
        }
        FilterExpansion(stringResource(R.string.browse_creators), filterSelectionSummary(draft.creators.map { libraryCreatorName(it) })) {
            Text(stringResource(R.string.browse_creators_any_hint), style = MaterialTheme.typography.bodySmall)
            LibraryChoiceList(ownCatalogue.creators.map { it.name to libraryCreatorName(it.name) }, draft.creators) { change(draft.copy(creators = it)) }
        }
        FilterExpansion(stringResource(R.string.browse_duration), if (draft.maxMinutes > 0) stringResource(R.string.browse_duration_chip, draft.maxMinutes) else stringResource(R.string.filter_any)) {
            OutlinedTextField(maxMinutes, { value -> maxMinutes = value; change(draft.copy(maxMinutes = value.toIntOrNull() ?: 0)) },
                label = { Text(stringResource(R.string.browse_max_minutes)) }, singleLine = true, isError = !valid,
                supportingText = { Text(stringResource(if (valid) R.string.browse_duration_hint else R.string.browse_invalid_duration)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun LibraryMetadataLinks(item: Episode, editTags: () -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val creators = listOf(item.artist, item.feed?.author.orEmpty()).filter { it.isNotBlank() }.distinctBy(::libraryKey)
        creators.forEach { creator -> TextButton(onClick = { ac.mdiq.podcini.ui.screens.openLibrary(LibraryDestination("creator", creator = creator)) }) { Text(creator) } }
        if (item.album.isNotBlank()) TextButton(onClick = { ac.mdiq.podcini.ui.screens.openLibrary(LibraryDestination("album", album = libraryAlbumKey(item.album, item.albumArtist, item.artist), title = item.album, sort = 21)) }) { Text(item.album) }
    }
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.tags.forEach { tag -> SuggestionChip(onClick = { ac.mdiq.podcini.ui.screens.openLibrary(LibraryDestination("tag", tag = tag)) }, label = { Text(tagLabel(tag)) }) }
        if (item.tags.isNotEmpty()) TextButton(onClick = editTags) { Text(stringResource(R.string.edit_tags)) }
    }
    val inherited = item.feed?.tags.orEmpty().filter { it !in item.tags }
    if (inherited.isNotEmpty()) {
        Text(stringResource(R.string.browse_inherited_tags), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            inherited.forEach { tag -> SuggestionChip(onClick = { ac.mdiq.podcini.ui.screens.openLibrary(LibraryDestination("tag", tag = tag)) }, label = { Text(tagLabel(tag)) }) }
        }
    }
}

@Composable
private fun LibraryChoiceList(options: List<Pair<String, String>>, selected: List<String>, onChange: (List<String>) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(30) }
    OutlinedTextField(search, { search = it; limit = 30 }, label = { Text(stringResource(R.string.filter_search_choices)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val results = options.filter { it.second.contains(search, true) }
    results.take(limit).forEach { (key, label) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(if (key in selected) selected - key else selected + key) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(key in selected, null); Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (results.size > limit) TextButton(onClick = { limit += 30 }) { Text(stringResource(R.string.browse_show_more)) }
    if (results.isEmpty()) Text(stringResource(R.string.filter_no_options), Modifier.padding(vertical = 12.dp))
}

@Composable
fun LibraryNameDialog(title: String, initial: String = "", hint: String? = null, valid: (String) -> Boolean = { it.isNotBlank() },
                      onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hint != null) Text(hint)
            OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.browse_name)) }, isError = value.isNotBlank() && !valid(value.trim()), modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = valid(value.trim()), onClick = { onSave(value.trim()) }) { Text(stringResource(R.string.library_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}
