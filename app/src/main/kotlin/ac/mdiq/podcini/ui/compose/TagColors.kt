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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import kotlinx.coroutines.launch

@Composable
fun rememberTagColors(): Map<String, Int> {
    val prefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    return remember(prefs.libraryBrowsePreferences) { decodeBrowsePreferences(prefs.libraryBrowsePreferences).tagColors }
}

@Composable
fun TagColorDot(path: String, colors: Map<String, Int>, modifier: Modifier = Modifier) {
    Box(modifier.size(16.dp).background(Color(tagColorArgb(path, colors)), CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
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
    val accent = Color(tagColorArgb(path, colors))
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

@Composable
fun TagColorPickerDialog(path: String, onDismiss: () -> Unit) {
    val colors = rememberTagColors()
    val identity = tagIdentity(path)
    var automatic by rememberSaveable(path) { mutableStateOf(identity !in colors) }
    var argb by rememberSaveable(path) { mutableIntStateOf(tagColorArgb(path, colors)) }
    var hex by rememberSaveable(path) { mutableStateOf(tagColorHex(argb)) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val preview = if (automatic) colors - identity else colors + (identity to argb)
    val controller = rememberColorPickerController()
    val initialColor = remember(path) { Color(argb) }
    val validHex = hex.length == 6 && hex.all { it.digitToIntOrNull(16) != null }
    SideEffect { controller.enabled = !saving }
    fun choose(color: Color) {
        automatic = false
        argb = color.copy(alpha = 1f).toArgb()
        hex = tagColorHex(argb)
    }
    val wheelLabel = stringResource(R.string.tag_color_wheel)
    val brightnessLabel = stringResource(R.string.tag_color_brightness)
    val brightness = Color(argb).let { maxOf(it.red, it.green, it.blue) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.tag_color_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ColoredTagChip(path, preview, {})
            HsvColorPicker(
                modifier = Modifier.fillMaxWidth().height(224.dp).semantics {
                    contentDescription = wheelLabel
                    stateDescription = "#${tagColorHex(argb)}"
                },
                controller = controller,
                initialColor = initialColor,
                onColorChanged = { if (it.fromUser) choose(it.color) },
                onStart = { automatic = false },
            )
            Text(brightnessLabel, style = MaterialTheme.typography.labelLarge)
            BrightnessSlider(
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                    contentDescription = brightnessLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(brightness, 0f..1f)
                    if (!saving) setProgress { value ->
                        automatic = false
                        controller.setBrightness(value.coerceIn(0f, 1f), fromUser = true)
                        true
                    }
                },
                controller = controller,
                initialColor = initialColor,
                borderSize = 1.dp,
                borderColor = MaterialTheme.colorScheme.outline,
                onColorChanged = { if (it.fromUser) choose(it.color) },
                onStart = { automatic = false },
            )
            OutlinedTextField(value = hex, onValueChange = { value ->
                val entered = value.removePrefix("#").uppercase()
                if (entered.length <= 6 && entered.all { it.digitToIntOrNull(16) != null }) {
                    hex = entered
                    automatic = false
                    if (entered.length == 6) {
                        argb = entered.toInt(16) or (255 shl 24)
                        controller.selectByColor(Color(argb), fromUser = false)
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                label = { Text(stringResource(R.string.tag_color_hex)) }, prefix = { Text("#") },
                isError = !validHex,
                supportingText = if (!validHex) ({ Text(stringResource(R.string.tag_color_hex_hint)) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(automatic, enabled = !saving, role = Role.RadioButton,
                onClick = {
                    automatic = true
                    argb = tagColorArgb(path, colors - identity)
                    hex = tagColorHex(argb)
                    controller.selectByColor(Color(argb), fromUser = false)
                }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(automatic, null); Text(stringResource(if (decodeTagSegments(path).orEmpty().size > 1) R.string.tag_color_automatic else R.string.tag_color_default), Modifier.weight(1f))
            }
            Text(stringResource(R.string.tag_color_local_hint), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(stringResource(R.string.tag_color_save_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !saving && validHex, onClick = {
            saving = true; failed = false
            scope.launch {
                try {
                    updateLibraryPreferences { it.copy(tagColors = if (automatic) it.tagColors - identity else it.tagColors + (identity to argb)) }
                    onDismiss()
                } catch (_: Exception) { failed = true } finally { saving = false }
            }
        }) { Text(stringResource(R.string.library_save)) }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}

private fun tagColorHex(argb: Int) = (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
