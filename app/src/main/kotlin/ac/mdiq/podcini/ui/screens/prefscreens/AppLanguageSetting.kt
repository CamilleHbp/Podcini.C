package ac.mdiq.podcini.ui.screens.prefscreens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import ac.mdiq.podcini.R
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

@Composable
fun AppLanguageSetting() {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val locales = AppCompatDelegate.getApplicationLocales()
    val selected = locales[0]?.language.orEmpty()
    val choices = listOf(
        "" to stringResource(R.string.app_language_system),
        "en" to stringResource(R.string.app_language_english),
        "fr" to stringResource(R.string.app_language_french)
    )
    val current = choices.firstOrNull { it.first == selected }?.second
        ?: locales[0]?.getDisplayName(locales[0]).orEmpty()

    SettingsAction(stringResource(R.string.app_language), stringResource(R.string.app_language_summary), value = current) { showPicker = true }
    if (showPicker) AlertDialog(
        onDismissRequest = { showPicker = false },
        title = { Text(stringResource(R.string.app_language)) },
        text = {
            Column(Modifier.selectableGroup()) {
                choices.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = selected == tag,
                            role = Role.RadioButton,
                            onClick = {
                                showPicker = false
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                            }
                        ).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == tag, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel_label)) } }
    )
}
