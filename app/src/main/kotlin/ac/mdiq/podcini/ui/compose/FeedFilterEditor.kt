package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.specs.FeedFilter
import ac.mdiq.podcini.storage.specs.FeedFilterDraft
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private val feedDraftSaver = mapSaver(
    save = { draft: FeedFilterDraft -> mapOf("properties" to draft.properties.toList(), "languages" to draft.languages.toList(), "tags" to draft.tags.toList(), "queues" to draft.queueIds.toList()) },
    restore = { saved -> FeedFilterDraft((saved["properties"] as List<*>).filterIsInstance<String>().toSet(), (saved["languages"] as List<*>).filterIsInstance<String>().toSet(), (saved["tags"] as List<*>).filterIsInstance<String>().toSet(), (saved["queues"] as List<*>).filterIsInstance<Long>().toSet()) }
)

@Composable
fun FeedFilterEditor(
    initial: FeedFilterDraft,
    languages: Set<String>,
    tags: Set<String>,
    queues: List<Pair<Long, String>>,
    scopeKey: String,
    countMatches: suspend (FeedFilterDraft) -> Long,
    onDismiss: () -> Unit,
    onApply: (FeedFilterDraft) -> Unit,
) {
    var draft by rememberSaveable(stateSaver = feedDraftSaver) { mutableStateOf(initial) }
    val queueIds = queues.map { it.first }.toSet()
    val activeGroups = FeedFilter.FeedFilterGroup.entries.count { group -> group.values.any { it.filterId in draft.properties } } +
        (if (draft.languages != languages) 1 else 0) + (if (draft.tags != tags) 1 else 0) + (if (draft.queueIds != queueIds) 1 else 0)
    @Composable fun summary(groups: List<FeedFilter.FeedFilterGroup>): String = filterSelectionSummary(groups.mapNotNull { group ->
        val selected = group.values.filter { it.filterId in draft.properties }.map { stringResource(it.displayName) }
        if (selected.isEmpty()) null else stringResource(group.nameRes) + ": " + selected.joinToString(", ")
    })
    @Composable fun group(group: FeedFilter.FeedFilterGroup) {
        val options = group.values.map { it.filterId to stringResource(it.displayName) }
        val selected = options.map { it.first }.filter { it in draft.properties }.toSet()
        FilterSection(stringResource(group.nameRes)) {
            if (options.size == 2) FilterSingleChoice(listOf("" to stringResource(R.string.filter_any)) + options, selected.firstOrNull() ?: "") {
                draft = draft.select(group, if (it.isBlank()) emptySet() else setOf(it))
            } else FilterMultiChoice(options, selected, { draft = draft.select(group, it) }, stringResource(R.string.filter_any), bulk = true)
        }
    }
    FilterEditor(title = stringResource(R.string.filter_sources_title), activeGroups = activeGroups,
        previewKey = "$scopeKey|$draft", countMatches = { countMatches(draft) },
        onReset = { draft = FeedFilterDraft(languages = languages, tags = tags, queueIds = queueIds) },
        onDismiss = onDismiss, onApply = { onApply(draft); onDismiss() }) {
        group(FeedFilter.FeedFilterGroup.RATING)
        if (languages.isNotEmpty()) FilterExpansion(stringResource(R.string.languages), when {
            draft.languages == languages -> stringResource(R.string.filter_all_languages)
            draft.languages.isEmpty() -> stringResource(R.string.filter_with_language)
            else -> filterSelectionSummary(draft.languages.toList())
        }) {
            FilterMultiChoice(languages.sorted().map { it to it }, draft.languages, { draft = draft.copy(languages = it) }, stringResource(R.string.filter_all_languages), languages, bulk = true)
            FilterOption(stringResource(R.string.filter_with_language), draft.languages.isEmpty()) { draft = draft.copy(languages = emptySet()) }
        }
        FilterExpansion(stringResource(R.string.tags_label), when {
            draft.tags == tags -> stringResource(R.string.filter_all_tags)
            draft.tags.isEmpty() -> stringResource(R.string.filter_untagged)
            else -> filterSelectionSummary(draft.tags.toList())
        }) {
            if (tags.isEmpty()) Text(stringResource(R.string.filter_no_tags), style = MaterialTheme.typography.bodyMedium)
            else {
                FilterMultiChoice(tags.sorted().map { it to it }, draft.tags, { draft = draft.copy(tags = it) }, stringResource(R.string.filter_all_tags), tags, bulk = true)
                FilterOption(stringResource(R.string.filter_untagged), draft.tags.isEmpty()) { draft = draft.copy(tags = emptySet()) }
            }
        }
        val queueSummary = when {
            draft.queueIds == queueIds -> stringResource(R.string.filter_all_queues)
            draft.queueIds.isEmpty() -> stringResource(R.string.filter_no_queue)
            else -> filterSelectionSummary(queues.filter { it.first in draft.queueIds }.map { it.second }, stringResource(R.string.filter_no_queue_match))
        }
        val sourceGroups = listOf(FeedFilter.FeedFilterGroup.ORIGIN, FeedFilter.FeedFilterGroup.TYPE, FeedFilter.FeedFilterGroup.IS_LOCAL, FeedFilter.FeedFilterGroup.HAS_VIDEO, FeedFilter.FeedFilterGroup.OPINION)
        FilterExpansion(stringResource(R.string.filter_source_details), summary(sourceGroups)) { sourceGroups.forEach { group(it) } }
        val other = FeedFilter.FeedFilterGroup.entries.filter { it !in sourceGroups && it != FeedFilter.FeedFilterGroup.RATING }
        val playbackSummary = filterSelectionSummary(listOfNotNull(
            summary(other).takeIf { other.any { group -> group.values.any { it.filterId in draft.properties } } },
            stringResource(R.string.filter_playback_queue_summary, queueSummary).takeIf { draft.queueIds != queueIds }
        ))
        FilterExpansion(stringResource(R.string.filter_playback_automation), playbackSummary) {
            FilterSection(stringResource(R.string.filter_playback_queue)) {
                Text(stringResource(R.string.filter_playback_queue_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterMultiChoice(queues.map { it.first.toString() to it.second }, draft.queueIds.map { it.toString() }.toSet(), { values -> draft = draft.copy(queueIds = values.mapNotNull { it.toLongOrNull() }.toSet()) }, stringResource(R.string.filter_all_queues), queueIds.map { it.toString() }.toSet(), bulk = true)
                FilterOption(stringResource(R.string.filter_no_queue), draft.queueIds.isEmpty()) { draft = draft.copy(queueIds = emptySet()) }
            }
            other.forEach { group(it) }
        }
        Text(stringResource(R.string.filter_source_match_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
fun FeedFilterChips(draft: FeedFilterDraft, languages: Set<String>, tags: Set<String>, queues: List<Pair<Long, String>>, onChange: (FeedFilterDraft) -> Unit) {
    val entries = mutableListOf<Pair<String, FeedFilterDraft>>()
    FeedFilter.FeedFilterGroup.entries.forEach { group ->
        val selected = group.values.filter { it.filterId in draft.properties }
        if (selected.isNotEmpty()) entries.add((stringResource(group.nameRes) + ": " + filterSelectionSummary(selected.map { stringResource(it.displayName) })) to draft.select(group, emptySet()))
    }
    if (draft.languages != languages) entries.add((stringResource(R.string.languages) + ": " + filterSelectionSummary(draft.languages.toList(), stringResource(R.string.filter_with_language))) to draft.copy(languages = languages))
    if (draft.tags != tags) entries.add((stringResource(R.string.tags_label) + ": " + filterSelectionSummary(draft.tags.toList(), stringResource(R.string.filter_untagged))) to draft.copy(tags = tags))
    val queueIds = queues.map { it.first }.toSet()
    if (draft.queueIds != queueIds) entries.add(stringResource(R.string.filter_playback_queue_summary, filterSelectionSummary(queues.filter { it.first in draft.queueIds }.map { it.second }, stringResource(R.string.filter_no_queue))) to draft.copy(queueIds = queueIds))
    if (entries.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries) { (label, cleared) -> InputChip(selected = true, onClick = { onChange(cleared) }, label = { Text(label) }, trailingIcon = {
            Icon(Icons.Default.Close, stringResource(R.string.archive_remove_filter, label), Modifier.size(18.dp))
        }) }
        item { TextButton(onClick = { onChange(FeedFilterDraft(languages = languages, tags = tags, queueIds = queueIds)) }) { Text(stringResource(R.string.archive_clear_filters)) } }
    }
}
