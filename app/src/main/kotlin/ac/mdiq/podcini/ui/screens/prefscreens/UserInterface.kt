package ac.mdiq.podcini.ui.screens.prefscreens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.ui.compose.AppThemes
import ac.mdiq.podcini.ui.compose.appTheme
import ac.mdiq.podcini.ui.screens.DefaultPages
import ac.mdiq.podcini.utils.LogLevel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun UserInterfaceScreen() {
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    SettingsPage {
        SettingsSection(R.string.settings_language_region)
        AppLanguageSetting()
        SettingsSection(R.string.appearance)
        val themeIndex = when (appTheme) {
            AppThemes.LIGHT -> 1
            AppThemes.DARK -> 2
            else -> 0
        }
        var showThemePicker by remember { mutableStateOf(false) }
        val themes = listOf(R.string.pref_theme_title_automatic, R.string.pref_theme_title_light, R.string.pref_theme_title_dark)
        SettingsAction(stringResource(R.string.settings_theme), value = stringResource(themes[themeIndex])) { showThemePicker = true }
        if (showThemePicker) AlertDialog(onDismissRequest = { showThemePicker = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    themes.forEachIndexed { index, label ->
                        SettingsChoice(stringResource(label), themeIndex == index) {
                            appTheme = when (index) { 1 -> AppThemes.LIGHT; 2 -> AppThemes.DARK; else -> AppThemes.SYSTEM }
                            showThemePicker = false
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = {
                TextButton(onClick = { showThemePicker = false }) { Text(stringResource(R.string.cancel_label)) }
            })
        SettingsSwitch(R.string.pref_dynamic_theme_title, R.string.pref_dynamic_theme_message, appPrefs.useDynamicThemes) {
            upsertBlk(appPrefs) { p-> p.useDynamicThemes = it } }
        if (themeIndex != 1) SettingsSwitch(R.string.pref_black_theme_title, R.string.pref_black_theme_message, appPrefs.themeBlack) {
            upsertBlk(appPrefs) { p-> p.themeBlack = it } }
        SettingsSwitch(R.string.pref_episode_cover_title, R.string.pref_episode_cover_summary, appPrefs.useEpisodeCover) {
            upsertBlk(appPrefs) { p-> p.useEpisodeCover = it } }
        SettingsSection(R.string.settings_navigation)
        var showDefaultPageOptions by remember { mutableStateOf(false) }
        SettingsAction(stringResource(R.string.pref_default_page), stringResource(R.string.pref_default_page_sum),
            value = if (appAttribsFlow!!.collectAsStateWithLifecycle().value.restoreLastScreen) stringResource(R.string.restore_on_start)
                else DefaultPages.entries.firstOrNull { it.name == appPrefs.defaultPage }?.let { stringResource(it.res) }) { showDefaultPageOptions = true }
        if (showDefaultPageOptions) {
            var tempSelectedOption by remember { mutableStateOf(appPrefs.defaultPage) }
            var restore by remember { mutableStateOf(appAttribsFlow!!.value.restoreLastScreen) }
            AlertDialog(onDismissRequest = { showDefaultPageOptions = false },
                title = { Text(stringResource(R.string.pref_default_page), style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column {
                        SettingsCheckbox(stringResource(R.string.restore_on_start), restore) { restore = it }
                        if (!restore) Column(Modifier.selectableGroup()) {
                            DefaultPages.entries.forEach { option ->
                                SettingsChoice(stringResource(option.res), tempSelectedOption == option.name) { tempSelectedOption = option.name }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        upsertBlk(appPrefs) { it.defaultPage = tempSelectedOption }
                        upsertBlk(appAttribsFlow!!.value) { it.restoreLastScreen = restore }
                        showDefaultPageOptions = false
                    }) { Text(text = stringResource(R.string.OK)) }
                },
                dismissButton = { TextButton(onClick = { showDefaultPageOptions = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        SettingsSwitch(R.string.pref_back_button_opens_drawer, R.string.pref_back_button_opens_drawer_summary, appPrefs.backButtonOpensDrawer) {
            upsertBlk(appPrefs) { p-> p.backButtonOpensDrawer = it } }

        SettingsSection(R.string.settings_diagnostics)
        var showLogPicker by remember { mutableStateOf(false) }
        val levels = listOf(LogLevel.Debug to R.string.archive_log_debug, LogLevel.Info to R.string.archive_log_info,
            LogLevel.Error to R.string.error_label, LogLevel.None to R.string.archive_no_queue)
        SettingsAction(stringResource(R.string.pref_show_log_level), stringResource(R.string.pref_show_log_level_sum),
            value = stringResource(levels.firstOrNull { it.first.code == appPrefs.showLogLevel }?.second ?: R.string.archive_no_queue)) { showLogPicker = true }
        if (showLogPicker) AlertDialog(onDismissRequest = { showLogPicker = false },
            title = { Text(stringResource(R.string.pref_show_log_level)) }, text = {
                Column(Modifier.selectableGroup()) {
                    levels.forEach { (level, label) ->
                        SettingsChoice(stringResource(label), level.code == appPrefs.showLogLevel) {
                            upsertBlk(appPrefs) { it.showLogLevel = level.code }
                            showLogPicker = false
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = {
                TextButton(onClick = { showLogPicker = false }) { Text(stringResource(R.string.cancel_label)) }
            })
        SettingsSwitch(R.string.pref_dont_ask_restricted, R.string.pref_dont_ask_restricted_sum, appPrefs.dont_ask_again_unrestricted_background) {
            upsertBlk(appPrefs) { p-> p.dont_ask_again_unrestricted_background = it } }
    }
}
