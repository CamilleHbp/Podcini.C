package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.updateLibraryPreferences
import ac.mdiq.podcini.storage.model.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun rememberTagColors(): Map<String, Int> {
    val prefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    return remember(prefs.libraryBrowsePreferences) { decodeBrowsePreferences(prefs.libraryBrowsePreferences).tagColors }
}

@Composable
private fun tagAccent(hue: Int): Color = Color.hsl(hue.toFloat(), .60f,
    if (MaterialTheme.colorScheme.surface.luminance() < .5f) .72f else .36f)

@Composable
fun TagColorDot(path: String, colors: Map<String, Int>, modifier: Modifier = Modifier) {
    Box(modifier.size(16.dp).background(tagAccent(tagColorHue(path, colors)), CircleShape))
}

@Composable
fun TagColorButton(path: String, colors: Map<String, Int>, onClick: () -> Unit) {
    val label = stringResource(R.string.tag_color_choose_for, tagLabel(path))
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        TagColorDot(path, colors, Modifier.size(24.dp))
    }
}

@Composable
fun ColoredTagChip(path: String, colors: Map<String, Int>, onClick: () -> Unit, fullPath: Boolean = true) {
    val accent = tagAccent(tagColorHue(path, colors))
    SuggestionChip(onClick = onClick, label = {
        Text(if (fullPath) tagLabel(path) else tagLeafLabel(path), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }, icon = { TagColorDot(path, colors) },
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = lerp(MaterialTheme.colorScheme.surface, accent, .10f),
            labelColor = MaterialTheme.colorScheme.onSurface), border = null)
}

/** A bounded preview; all tags remain reachable through the explicit overflow action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactTagList(tags: Collection<String>, onTagClick: (String) -> Unit, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    val colors = rememberTagColors()
    val paths = remember(tags.toList()) { tags.distinctBy(::tagIdentity).sortedBy(::tagIdentity) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.take(3).forEach { path -> ColoredTagChip(path, colors, { onTagClick(path) }, fullPath = false) }
        if (paths.size > 3) TextButton(onClick = onShowAll) { Text(pluralStringResource(R.plurals.tag_more_count, paths.size - 3, paths.size - 3)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagColorPickerDialog(path: String, onDismiss: () -> Unit) {
    val colors = rememberTagColors()
    val identity = tagIdentity(path)
    var automatic by rememberSaveable(path) { mutableStateOf(identity !in colors) }
    var hue by rememberSaveable(path) { mutableIntStateOf(tagColorHue(path, colors)) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val preview = if (automatic) colors - identity else colors + (identity to hue)
    val swatches = listOf(12 to R.string.tag_color_coral, 38 to R.string.tag_color_amber, 82 to R.string.tag_color_olive,
        150 to R.string.tag_color_green, 180 to R.string.tag_color_teal, 208 to R.string.tag_color_blue,
        238 to R.string.tag_color_indigo, 272 to R.string.tag_color_violet, 310 to R.string.tag_color_orchid, 340 to R.string.tag_color_rose)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.tag_color_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(tagLabel(path), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tag_color_local_hint), style = MaterialTheme.typography.bodyMedium)
            ColoredTagChip(path, preview, {})
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(automatic, enabled = !saving, role = Role.RadioButton,
                onClick = { automatic = true }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(automatic, null); Text(stringResource(if (decodeTagSegments(path).orEmpty().size > 1) R.string.tag_color_automatic else R.string.tag_color_default), Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                swatches.forEach { (value, name) ->
                    val selected = !automatic && hue == value
                    val label = stringResource(name)
                    val color = tagAccent(value)
                    Box(Modifier.size(48.dp).selectable(selected, enabled = !saving, role = Role.RadioButton,
                        onClick = { automatic = false; hue = value }).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(36.dp).background(color, CircleShape)
                            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Default.Check, null, tint = if (color.luminance() > .179f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Text(stringResource(R.string.tag_color_custom), style = MaterialTheme.typography.labelLarge)
            val hueLabel = stringResource(R.string.tag_color_hue)
            Slider(value = (if (automatic) tagColorHue(path, preview) else hue).toFloat(), onValueChange = { automatic = false; hue = it.toInt() },
                valueRange = 0f..359f, enabled = !saving, colors = SliderDefaults.colors(thumbColor = tagAccent(hue), activeTrackColor = tagAccent(hue)), modifier = Modifier.semantics { contentDescription = hueLabel })
            if (failed) Text(stringResource(R.string.tag_color_save_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = {
            saving = true; failed = false
            scope.launch {
                try {
                    updateLibraryPreferences { it.copy(tagColors = if (automatic) it.tagColors - identity else it.tagColors + (identity to hue)) }
                    onDismiss()
                } catch (_: Exception) { failed = true } finally { saving = false }
            }
        }) { Text(stringResource(R.string.library_save)) }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}
