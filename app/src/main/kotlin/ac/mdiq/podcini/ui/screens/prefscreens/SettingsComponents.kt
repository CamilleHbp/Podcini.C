package ac.mdiq.podcini.ui.screens.prefscreens

import ac.mdiq.podcini.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp), content = content
        )
    }
}

@Composable
internal fun SettingsSection(titleRes: Int) {
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
internal fun SettingsDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
internal fun SettingsAction(titleRes: Int, summaryRes: Int = 0, onClick: () -> Unit) {
    SettingsAction(stringResource(titleRes), if (summaryRes == 0) null else stringResource(summaryRes), onClick = onClick)
}

@Composable
internal fun SettingsAction(title: String, summary: String? = null, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!value.isNullOrBlank()) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SettingsSwitch(titleRes: Int, summaryRes: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (summaryRes != 0) Text(stringResource(summaryRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun SettingsChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun SettingsCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SettingsNumber(titleRes: Int, summaryRes: Int, value: Int, unit: String, min: Int = 0, max: Int = Int.MAX_VALUE, onSave: (Int) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingsAction(stringResource(titleRes), if (summaryRes == 0) null else stringResource(summaryRes),
        value = "$value $unit") { editing = true }
    if (editing) {
        var draft by rememberSaveable { mutableStateOf(value.toString()) }
        val number = draft.toIntOrNull()
        val valid = number != null && number in min..max
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(titleRes)) }, text = {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                label = { Text(unit) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !valid, supportingText = {
                    if (!valid) Text(if (max == Int.MAX_VALUE) stringResource(R.string.settings_number_min, min)
                        else stringResource(R.string.settings_number_range, min, max))
                })
        }, confirmButton = {
            TextButton(enabled = valid, onClick = { number?.let(onSave); editing = false }) { Text(stringResource(R.string.settings_save)) }
        }, dismissButton = {
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel_label)) }
        })
    }
}

@Composable
internal fun SettingsText(titleRes: Int, summaryRes: Int, value: String, onSave: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingsAction(stringResource(titleRes), stringResource(summaryRes), value = value) { editing = true }
    if (editing) {
        var draft by rememberSaveable { mutableStateOf(value) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(titleRes)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(summaryRes), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                    label = { Text(stringResource(titleRes)) })
            }
        }, confirmButton = {
            TextButton(onClick = { onSave(draft.trim()); editing = false }) { Text(stringResource(R.string.settings_save)) }
        }, dismissButton = {
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel_label)) }
        })
    }
}
