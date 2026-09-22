package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterEditor(
    title: String,
    subtitle: String? = null,
    activeGroups: Int,
    previewKey: String?,
    countMatches: (suspend () -> Long)? = null,
    valid: Boolean = true,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var matches by remember(previewKey) { mutableStateOf<Long?>(null) }
    var previewFailed by remember(previewKey) { mutableStateOf(false) }
    val latestCount by rememberUpdatedState(countMatches)
    LaunchedEffect(previewKey, valid) {
        if (!valid || previewKey == null || latestCount == null) return@LaunchedEffect
        delay(180)
        try { matches = withContext(Dispatchers.IO) { latestCount!!.invoke() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { previewFailed = true }
    }
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
    val editorContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (wide) 1f else 0.92f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reset)) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) { content() }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                val summary = when {
                    previewFailed -> stringResource(R.string.filter_count_error)
                    matches == 0L -> stringResource(R.string.filter_no_matches)
                    countMatches != null && matches == null && valid -> stringResource(R.string.filter_counting)
                    activeGroups == 0 -> stringResource(R.string.filter_none_active)
                    else -> pluralStringResource(R.plurals.filter_active_groups, activeGroups, activeGroups)
                }
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = if (previewFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel_label)) }
                    Button(onClick = onApply, enabled = valid && !previewFailed && (countMatches == null || matches != null), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(if (matches == null) stringResource(R.string.filter_apply) else pluralStringResource(R.plurals.filter_show_results, matches!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), matches!!))
                    }
                }
            }
        }
    }
    if (wide) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.CenterEnd) {
                Surface(modifier = Modifier.width(440.dp).fillMaxHeight(), shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp), color = MaterialTheme.colorScheme.surface) { editorContent() }
            }
        }
    } else {
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = lightSurface, isAppearanceLightNavigationBars = lightSurface),
            containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = { WindowInsets.safeDrawing }) { editorContent() }
    }
}

@Composable
internal fun FilterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
internal fun FilterExpansion(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expansionState = stringResource(if (expanded) R.string.filter_expanded else R.string.filter_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.filter_collapse_section else R.string.filter_expand_section, title)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = actionLabel) { expanded = !expanded }
        .semantics(mergeDescendants = true) { stateDescription = expansionState }
        .padding(vertical = 16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.padding(start = 12.dp))
    }
    if (expanded) Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
internal fun FilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null)
}

@Composable
internal fun FilterSingleChoice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    if (options.size <= 3 && LocalConfiguration.current.fontScale <= 1.2f) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = selected == value, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(index, options.size), modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) -> FilterOption(label, selected == value) { onSelect(value) } }
    }
}

@Composable
internal fun FilterMultiChoice(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    anyLabel: String? = null,
    anyValues: Set<String> = emptySet(),
    bulk: Boolean = false,
) {
    var search by rememberSaveable { mutableStateOf("") }
    if (options.size > 15) OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text(stringResource(R.string.filter_search_choices)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val visible = options.filter { search.isBlank() || it.second.contains(search, ignoreCase = true) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (anyLabel != null) FilterOption(anyLabel, selected == anyValues) { onSelect(anyValues) }
        visible.forEach { (value, label) -> FilterOption(label, value in selected) { onSelect(if (value in selected) selected - value else selected + value) } }
    }
    if (visible.isEmpty()) Text(stringResource(R.string.filter_no_options), style = MaterialTheme.typography.bodyMedium)
    if (bulk && options.size > 2) {
        var showBulk by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { showBulk = !showBulk }) { Text(stringResource(R.string.filter_bulk_selection)) }
        if (showBulk) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSelect(options.map { it.first }.toSet()) }) { Text(stringResource(R.string.filter_select_all)) }
                TextButton(onClick = { onSelect(emptySet()) }) { Text(stringResource(R.string.filter_clear_selection)) }
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    val end = options.indexOfLast { it.first in selected }
                    if (end >= 0) onSelect(selected + options.take(end + 1).map { it.first })
                }) { Text(stringResource(R.string.filter_select_lower)) }
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    val start = options.indexOfFirst { it.first in selected }
                    if (start >= 0) onSelect(selected + options.drop(start).map { it.first })
                }) { Text(stringResource(R.string.filter_select_upper)) }
            }
        }
    }
}

@Composable
internal fun filterSelectionSummary(values: List<String>, empty: String = stringResource(R.string.filter_any)): String = when {
    values.isEmpty() -> empty
    values.size <= 3 -> values.joinToString(", ")
    else -> values.take(2).joinToString(", ") + " · " + pluralStringResource(R.plurals.filter_selected_count, values.size, values.size)
}
