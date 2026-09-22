package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.specs.EpisodeFilter
import ac.mdiq.podcini.storage.specs.EpisodeFilter.EpisodesFilterGroup
import ac.mdiq.podcini.storage.specs.EpisodeFilter.States
import ac.mdiq.podcini.storage.specs.EpisodeFilterDraft
import ac.mdiq.podcini.ui.screens.SearchBy
import ac.mdiq.podcini.ui.utils.SearchAlgo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xilinjia.krdb.ext.query

private val episodeDraftSaver = mapSaver(
    save = { draft: EpisodeFilterDraft -> mapOf("properties" to draft.properties.toList(), "join" to draft.andOr, "floor" to draft.durationFloor, "ceiling" to draft.durationCeiling, "title" to draft.titleText) },
    restore = { saved -> EpisodeFilterDraft((saved["properties"] as List<*>).filterIsInstance<String>().toSet(), saved["join"] as String, saved["floor"] as Int, saved["ceiling"] as Int, saved["title"] as String) }
)

@Composable
fun EpisodeFilterEditor(
    filter: EpisodeFilter,
    disabled: Set<EpisodesFilterGroup> = emptySet(),
    showAndOr: Boolean = true,
    scopeQuery: String = "id > 0",
    subtitle: String? = null,
    preview: Boolean = true,
    onDismiss: () -> Unit,
    onApply: (EpisodeFilter) -> Unit,
) {
    var draft by rememberSaveable(stateSaver = episodeDraftSaver) { mutableStateOf(EpisodeFilterDraft(filter)) }
    val attributes by appAttribsFlow!!.collectAsStateWithLifecycle()
    var allStatuses by rememberSaveable { mutableStateOf(false) }
    var queryText by rememberSaveable { mutableStateOf(filter.extractText()) }
    val originalQuery = remember { filter.propertySet.filter { it.startsWith("${States.text.name} ") }.joinToString(" ") }
    var searchFields by rememberSaveable { mutableStateOf(SearchBy.entries.filter { it != SearchBy.AUTHOR && (originalQuery.isBlank() || when (it) {
        SearchBy.TITLE -> originalQuery.contains("title ")
        SearchBy.DESCRIPTION -> originalQuery.contains("description ") || originalQuery.contains("transcript ")
        SearchBy.COMMENT -> originalQuery.contains("comment ")
        else -> false
    }) }.map { it.name }) }
    var lower by rememberSaveable { mutableStateOf((filter.durationFloor / 1000).toString()) }
    var upper by rememberSaveable { mutableStateOf(if (filter.durationCeiling == Int.MAX_VALUE) "" else (filter.durationCeiling / 1000).toString()) }
    var customDuration by rememberSaveable { mutableStateOf(false) }
    val lowerSeconds = lower.toLongOrNull()
    val upperSeconds = if (upper.isBlank()) null else upper.toLongOrNull()
    val durationActive = EpisodesFilterGroup.DURATION.properties.any { it.filterId in draft.properties }
    val durationValid = !durationActive || (lowerSeconds != null && lowerSeconds in 0..2_147_483 &&
        (upper.isBlank() || (upperSeconds != null && upperSeconds in lowerSeconds..2_147_483)))
    val searchValid = queryText.isBlank() || searchFields.isNotEmpty()
    val activeGroups = EpisodesFilterGroup.entries.count { group -> group !in disabled && group.properties.any { it.filterId in draft.properties } } +
        (if (draft.properties.any { it.startsWith("${States.tags.name} ") }) 1 else 0) + (if (queryText.isNotBlank()) 1 else 0)
    val query = remember(draft, scopeQuery) { "($scopeQuery) AND (${draft.toFilter().queryString()})" }

    fun updateSearch(text: String = queryText, fields: List<String> = searchFields) {
        val algo = SearchAlgo()
        SearchBy.entries.forEach { algo.setSelected(it, it.name in fields) }
        val words = (if (text.contains(',')) text.split(',') else text.split("\\s+".toRegex())).map { it.trim() }.filter { it.isNotEmpty() }
        val searchQuery = if (text.isBlank()) "" else algo.episodesQueryString(0L, words)
        val edited = draft.toFilter().apply { addTextQuery(searchQuery) }
        draft = EpisodeFilterDraft(edited)
    }
    fun updateBounds(newLower: String = lower, newUpper: String = upper) {
        val floor = newLower.toLongOrNull() ?: return
        val ceiling = if (newUpper.isBlank()) Int.MAX_VALUE.toLong() else (newUpper.toLongOrNull() ?: return) * 1000
        if (floor in 0..2_147_483 && ceiling in (floor * 1000)..Int.MAX_VALUE.toLong()) draft = draft.copy(durationFloor = (floor * 1000).toInt(), durationCeiling = ceiling.toInt())
    }
    @Composable fun summary(groups: List<EpisodesFilterGroup>): String {
        val values = groups.mapNotNull { group ->
            val selected = group.properties.filter { it.filterId in draft.properties }.map { stringResource(it.displayName) }
            if (selected.isEmpty()) null else stringResource(group.nameRes) + ": " + selected.joinToString(", ")
        }
        return filterSelectionSummary(values)
    }
    @Composable fun group(group: EpisodesFilterGroup) {
        if (group in disabled) return
        val options = group.properties.map { it.filterId to stringResource(it.displayName) }
        val selected = options.map { it.first }.filter { it in draft.properties }.toSet()
        FilterSection(stringResource(group.nameRes)) {
            if (group.properties.size == 2) FilterSingleChoice(listOf("" to stringResource(R.string.filter_any)) + options, selected.firstOrNull() ?: "") {
                draft = draft.select(group, if (it.isBlank()) emptySet() else setOf(it))
            } else FilterMultiChoice(options, selected, { draft = draft.select(group, it) }, stringResource(R.string.filter_any), bulk = true)
        }
    }
    FilterEditor(title = stringResource(R.string.filter_episodes_title), subtitle = subtitle, activeGroups = activeGroups,
        previewKey = query, countMatches = if (preview) ({ realm.query(Episode::class).query(query).count().find() }) else null,
        valid = durationValid && searchValid,
        onReset = {
            draft = draft.reset(disabled)
            queryText = ""
            searchFields = SearchBy.entries.filter { it != SearchBy.AUTHOR }.map { it.name }
            lower = "0"
            upper = ""
            customDuration = false
        }, onDismiss = onDismiss, onApply = { onApply(draft.toFilter()); onDismiss() }) {
        if (EpisodesFilterGroup.PLAY_STATE !in disabled) {
            val group = EpisodesFilterGroup.PLAY_STATE
            val common = setOf(States.NEW.name, States.UNPLAYED.name, States.PROGRESS.name, States.PLAYED.name)
            val selected = group.properties.map { it.filterId }.filter { it in draft.properties }.toSet()
            val choices = group.properties.filter { allStatuses || it.filterId in common || it.filterId in selected }
            FilterSection(stringResource(group.nameRes)) {
                FilterMultiChoice(choices.map { it.filterId to stringResource(it.displayName) }, selected, { draft = draft.select(group, it) }, stringResource(R.string.filter_any), bulk = allStatuses)
                TextButton(onClick = { allStatuses = !allStatuses }) { Text(stringResource(if (allStatuses) R.string.filter_common_statuses else R.string.filter_all_statuses)) }
            }
        }
        if (EpisodesFilterGroup.DOWNLOADED !in disabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            group(EpisodesFilterGroup.DOWNLOADED)
        }
        if (EpisodesFilterGroup.DURATION !in disabled) {
            val selected = EpisodesFilterGroup.DURATION.properties.map { it.filterId }.filter { it in draft.properties }.toSet()
            val preset = when {
                selected.isEmpty() -> "any"
                selected == setOf(States.lower.name) && draft.durationFloor == 1_800_000 -> "short"
                selected == setOf(States.middle.name) && draft.durationFloor == 1_800_000 && draft.durationCeiling == 3_600_000 -> "medium"
                else -> "custom"
            }
            val durationSummary = when (preset) {
                "any" -> stringResource(R.string.filter_any_length)
                "short" -> stringResource(R.string.filter_under_30)
                "medium" -> stringResource(R.string.filter_30_60)
                else -> stringResource(R.string.filter_range_summary,
                    selected.map { stringResource(when (it) { States.lower.name -> R.string.filter_below; States.middle.name -> R.string.filter_between; else -> R.string.filter_above }) }.joinToString(", "),
                    lower, upper.ifBlank { stringResource(R.string.filter_unlimited) })
            }
            FilterExpansion(stringResource(R.string.duration), durationSummary) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("any" to R.string.filter_any_length, "short" to R.string.filter_under_30, "medium" to R.string.filter_30_60, "custom" to R.string.filter_custom).forEach { (value, label) ->
                        FilterOption(stringResource(label), if (value == "custom") customDuration || preset == "custom" else !customDuration && value == preset) {
                            customDuration = value == "custom"
                            when (value) {
                                "any" -> draft = draft.select(EpisodesFilterGroup.DURATION, emptySet())
                                "short" -> { lower = "1800"; upper = ""; draft = draft.select(EpisodesFilterGroup.DURATION, setOf(States.lower.name)).copy(durationFloor = 1_800_000, durationCeiling = Int.MAX_VALUE) }
                                "medium" -> { lower = "1800"; upper = "3600"; draft = draft.select(EpisodesFilterGroup.DURATION, setOf(States.middle.name)).copy(durationFloor = 1_800_000, durationCeiling = 3_600_000) }
                                else -> if (selected.isEmpty()) draft = draft.select(EpisodesFilterGroup.DURATION, setOf(States.middle.name))
                            }
                        }
                    }
                }
                if (customDuration || preset == "custom") {
                    OutlinedTextField(value = lower, onValueChange = { lower = it; updateBounds(newLower = it) }, label = { Text(stringResource(R.string.filter_lower_seconds)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = !durationValid, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = upper, onValueChange = { upper = it; updateBounds(newUpper = it) }, label = { Text(stringResource(R.string.filter_upper_seconds)) }, placeholder = { Text(stringResource(R.string.filter_no_upper_limit)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = !durationValid, modifier = Modifier.fillMaxWidth())
                    FilterMultiChoice(listOf(States.lower.name to stringResource(R.string.filter_below), States.middle.name to stringResource(R.string.filter_between), States.higher.name to stringResource(R.string.filter_above)), selected, { draft = draft.select(EpisodesFilterGroup.DURATION, it) })
                    Text(stringResource(if (durationValid) R.string.filter_duration_help else R.string.filter_invalid_duration), style = MaterialTheme.typography.bodySmall, color = if (durationValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
        val organization = listOf(EpisodesFilterGroup.RATING, EpisodesFilterGroup.TAGGED).filter { it !in disabled }
        val tags = attributes.episodeTagSet.toList().sorted()
        val selectedTags = draft.properties.filter { it.startsWith("${States.tags.name} ") }.map { it.removePrefix("${States.tags.name} ") }.toSet()
        val organizationSummary = if (selectedTags.isEmpty()) summary(organization) else listOfNotNull(
            summary(organization).takeIf { organization.any { group -> group.properties.any { it.filterId in draft.properties } } },
            stringResource(R.string.filter_tag_summary, filterSelectionSummary(selectedTags.toList()))).joinToString(" · ")
        FilterExpansion(stringResource(R.string.filter_organization), organizationSummary) {
            group(EpisodesFilterGroup.RATING)
            FilterSection(stringResource(R.string.tags_label)) {
                if (tags.isEmpty()) Text(stringResource(R.string.filter_no_tags), style = MaterialTheme.typography.bodyMedium)
                else FilterMultiChoice(tags.map { it to it }, selectedTags, { selected ->
                    draft = draft.copy(properties = draft.properties.filterNot { it.startsWith("${States.tags.name} ") }.toSet() + selected.map { "${States.tags.name} $it" })
                }, stringResource(R.string.filter_any), bulk = true)
            }
            group(EpisodesFilterGroup.TAGGED)
        }
        FilterExpansion(stringResource(R.string.filter_text_title), filterSelectionSummary(listOfNotNull(queryText.takeIf { it.isNotBlank() }, draft.titleText.takeIf { it.isNotBlank() && (States.title_include.name in draft.properties || States.title_exclude.name in draft.properties) }))) {
            OutlinedTextField(value = queryText, onValueChange = { queryText = it; updateSearch(text = it) }, label = { Text(stringResource(R.string.filter_search_words)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.filter_search_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.filter_search_fields), style = MaterialTheme.typography.titleSmall)
            FilterMultiChoice(listOf(SearchBy.TITLE.name to stringResource(R.string.title), SearchBy.DESCRIPTION.name to stringResource(R.string.filter_description_transcript), SearchBy.COMMENT.name to stringResource(R.string.comments)), searchFields.toSet(), {
                searchFields = it.toList(); updateSearch(fields = it.toList())
            })
            if (!searchValid) Text(stringResource(R.string.filter_choose_search_field), color = MaterialTheme.colorScheme.error)
            if (EpisodesFilterGroup.TITLE_TEXT !in disabled) {
                val selected = when { States.title_include.name in draft.properties -> States.title_include.name; States.title_exclude.name in draft.properties -> States.title_exclude.name; else -> "" }
                FilterSection(stringResource(R.string.title)) {
                    FilterSingleChoice(listOf("" to stringResource(R.string.filter_any), States.title_include.name to stringResource(R.string.include), States.title_exclude.name to stringResource(R.string.exclude)), selected) {
                        draft = draft.select(EpisodesFilterGroup.TITLE_TEXT, if (it.isBlank()) emptySet() else setOf(it))
                    }
                    if (selected.isNotBlank()) OutlinedTextField(value = draft.titleText, onValueChange = { draft = draft.copy(titleText = it) }, label = { Text(stringResource(R.string.filter_title_words)) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val other = EpisodesFilterGroup.entries.filter { it !in disabled && it !in setOf(EpisodesFilterGroup.PLAY_STATE, EpisodesFilterGroup.DOWNLOADED, EpisodesFilterGroup.DURATION, EpisodesFilterGroup.RATING, EpisodesFilterGroup.TAGGED, EpisodesFilterGroup.TITLE_TEXT) }
        if (other.isNotEmpty()) FilterExpansion(stringResource(R.string.filter_content_automation), summary(other)) { other.forEach { group(it) } }
        if (showAndOr) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FilterSection(stringResource(R.string.filter_match_groups)) {
                FilterSingleChoice(listOf("AND" to stringResource(R.string.filter_match_all), "OR" to stringResource(R.string.filter_match_any)), draft.andOr) { draft = draft.copy(andOr = it) }
                Text(stringResource(R.string.filter_match_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
