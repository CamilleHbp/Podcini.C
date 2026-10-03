package ac.mdiq.podcini.ui.screens.prefscreens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import ac.mdiq.podcini.utils.localizedString
import ac.mdiq.podcini.PodciniApp.Companion.forceRestart
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.config.settings.ComboRestore
import ac.mdiq.podcini.config.settings.ClipsTransporter
import ac.mdiq.podcini.config.settings.DatabaseTransporter
import ac.mdiq.podcini.config.settings.DocumentFileExportWorker
import ac.mdiq.podcini.config.settings.EpisodesProgressWriter
import ac.mdiq.podcini.config.settings.ExportTypes
import ac.mdiq.podcini.config.settings.ExportWorker
import ac.mdiq.podcini.config.settings.ExportWriter
import ac.mdiq.podcini.config.settings.FavoritesWriter
import ac.mdiq.podcini.config.settings.HtmlWriter
import ac.mdiq.podcini.config.settings.MediaFilesTransporter
import ac.mdiq.podcini.config.settings.OpmlTransporter
import ac.mdiq.podcini.config.settings.OpmlTransporter.OpmlElement
import ac.mdiq.podcini.config.settings.OpmlTransporter.OpmlWriter
import ac.mdiq.podcini.config.settings.importAP
import ac.mdiq.podcini.config.settings.importPA
import ac.mdiq.podcini.sync.SyncService.Companion.isValidGuid
import ac.mdiq.podcini.sync.model.EpisodeAction
import ac.mdiq.podcini.sync.model.EpisodeAction.Companion.readFromJsonObject
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.episodeByGuidOrUrl
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.utils.autoBackupDirName
import ac.mdiq.podcini.storage.utils.persistedTrees
import ac.mdiq.podcini.storage.utils.tempRoottree
import ac.mdiq.podcini.storage.utils.toAndroidUri
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.ui.compose.ConfirmDialog
import ac.mdiq.podcini.ui.compose.CommonConfirmAttrib
import ac.mdiq.podcini.ui.compose.CommonPopupCard
import ac.mdiq.podcini.ui.compose.OpmlImportSelectionDialog
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.dateStampFilename
import ac.mdiq.podcini.utils.shareFile
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.buffer
import org.json.JSONArray

enum class ComboIEOptions {
    MeidaFiles,
    Database,
    Clips
}
@Composable
fun ImportExportScreen() {
    val context by rememberUpdatedState(LocalContext.current)
    val TAG = "ImportExportScreen"
    val backupDirName = "Podcini-Backups"
    val mediaFilesDirName = "Podcini-MediaFiles"
    val clipsDirName = "Podcini-Clips"

    @Composable
    fun backupOptionLabel(option: String): String = stringResource(when (option) {
        ComboIEOptions.Database.name -> R.string.backup_library_data
        ComboIEOptions.MeidaFiles.name -> R.string.backup_media_files
        else -> R.string.clips
    })

    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    val restoreScope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    BackHandler(enabled = true) { if (!restoring) pfBackStack.removeLastOrNull() }

    var processingText by remember { mutableStateOf("") }
    fun isJsonFile(uri: Uri): Boolean {
        val fileName = uri.lastPathSegment ?: return false
        return fileName.endsWith(".json", ignoreCase = true)
    }
    fun isRealmFile(uri: Uri): Boolean {
        val fileName = uri.lastPathSegment ?: return false
        return fileName.trim().endsWith(".realm", ignoreCase = true)
    }
    fun isComboDir(uri: Uri): Boolean {
        val fileName = uri.lastPathSegment ?: return false
        return fileName.contains(backupDirName, ignoreCase = true) || fileName.contains(autoBackupDirName, ignoreCase = true)
    }
    fun showExportSuccess(uri: Uri?, mimeType: String?) {
        commonConfirms.add(CommonConfirmAttrib(
            title = context.getString(R.string.export_success_title),
            message = "",
            confirmRes = R.string.share_label,
            cancelRes = R.string.no,
            onConfirm = {
                if (uri != null) context.shareFile(uri, mimeType?:"", R.string.share_file_label)
                else Loge(TAG, localizedString(R.string.message_share_file_failed_uri_is_null))
            }))
    }
    val showImporSuccessDialog = remember { mutableStateOf(false) }
    ConfirmDialog(titleRes = R.string.successful_import_label, message = stringResource(R.string.import_ok), showDialog = showImporSuccessDialog, cancellable = false) { forceRestart() }

    val showImporErrortDialog = remember { mutableStateOf(false) }
    var importErrorMessage by remember { mutableStateOf("") }
    ConfirmDialog(titleRes = R.string.import_export_error_label, message = importErrorMessage, showDialog = showImporErrortDialog) {}

    fun exportWithWriter(exportWriter: ExportWriter, uri: Uri?, exportType: ExportTypes) {
        processingText = context.getString(R.string.settings_exporting)
        if (uri == null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val output = ExportWorker(exportWriter).exportFile()
                    withContext(Dispatchers.Main) { showExportSuccess(output.toAndroidUri(), exportType.contentType) }
                } catch (e: Exception) {
                    processingText = ""
                    Logs(TAG, e, "export error")
                    importErrorMessage = e.message ?: context.getString(R.string.settings_transfer_error)
                    showImporErrortDialog.value = true
                } finally { processingText = "" }
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val worker = DocumentFileExportWorker(exportWriter, uri)
                try {
                    val output = worker.exportFile()
                    withContext(Dispatchers.Main) { showExportSuccess(output.toAndroidUri(), exportType.contentType) }
                } catch (e: Exception) {
                    processingText = ""
                    Logs(TAG, e, "export error")
                    importErrorMessage = e.message ?: context.getString(R.string.settings_transfer_error)
                    showImporErrortDialog.value = true
                } finally { processingText = "" }
            }
        }
    }

    val chooseOpmlExportPathLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.data!!
        exportWithWriter(OpmlWriter(), uri, ExportTypes.OPML)
    }
    val chooseHtmlExportPathLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.data!!
        exportWithWriter(HtmlWriter(), uri, ExportTypes.HTML)
    }
    val chooseFavoritesExportPathLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.data!!
        exportWithWriter(FavoritesWriter(), uri, ExportTypes.FAVORITES)
    }
    val chooseProgressExportPathLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.data!!
        exportWithWriter(EpisodesProgressWriter(), uri, ExportTypes.PROGRESS)
    }
    val restoreProgressLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.data
        uri?.let {
            if (isJsonFile(uri)) {
                processingText = context.getString(R.string.restore_backup_progress)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        EpisodeProgressReader().readDocument(uri.toUF().source().buffer())
                        withContext(Dispatchers.Main) {
                            showImporSuccessDialog.value = true
                            processingText = ""
                        }
                    } catch (e: Throwable) {
                        processingText = ""
                        Logs(TAG, e, "export error")
                        importErrorMessage = e.message ?: context.getString(R.string.settings_transfer_error)
                        showImporErrortDialog.value = true
                    }
                }
            } else {
                val message = context.getString(R.string.import_file_type_toast) + ".json"
                processingText = ""
                Loge(TAG, localizedString(R.string.message_export_error, (message).toString()))
                importErrorMessage = message
                showImporErrortDialog.value = true
            }
        }
    }
    var showOpmlImportSelectionDialog by remember { mutableStateOf(false) }
    var readElements by remember { mutableStateOf<List<OpmlElement>>(listOf()) }
    val chooseOpmlImportPathLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        Logd(TAG) { "chooseOpmlImportPathResult: uri: $uri" }
        OpmlTransporter.startImport(uri) {
            readElements = it
            Logd(TAG) { "readElements: ${readElements.size}" }
            showOpmlImportSelectionDialog = true
        }
    }

    val comboDic = remember { mutableStateMapOf<String, Boolean>() }
    var showComboImportDialog by remember { mutableStateOf(false) }
    if (showComboImportDialog) {
        AlertDialog(onDismissRequest = { showComboImportDialog = false },
            title = { Text(stringResource(R.string.combo_import_label)) },
            text = {
                Column {
                    comboDic.keys.forEach { option ->
                        SettingsCheckbox(backupOptionLabel(option), comboDic[option] == true) { comboDic[option] = it }
                    }
                    Text(stringResource(R.string.restore_backup_restart), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = tempRoottree
                    if (uri == null) {
                        Loge(TAG, localizedString(R.string.message_import_uri_is_null))
                        return@TextButton
                    }
                    val selection = comboDic.toMap()
                    restoring = true
                    restoreScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val children = uri.toUF().listChildren()
                                val databases = children.filter { !it.isDirectory() && it.name.endsWith(".realm", ignoreCase = true) }
                                val database = if (selection[ComboIEOptions.Database.name] == true) {
                                    require(databases.size == 1) { context.getString(R.string.restore_backup_database_missing) }
                                    databases.single()
                                } else null
                                val media = if (selection[ComboIEOptions.MeidaFiles.name] == true)
                                    requireNotNull(children.find { it.isDirectory() && it.name == mediaFilesDirName }) { context.getString(R.string.restore_backup_media_missing) } else null
                                val clips = if (selection[ComboIEOptions.Clips.name] == true)
                                    requireNotNull(children.find { it.isDirectory() && it.name == clipsDirName }) { context.getString(R.string.restore_backup_clips_missing) } else null
                                ComboRestore.restore(database, media, clips)
                            }
                            forceRestart()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs(TAG, e, "Restore failed")
                            importErrorMessage = e.message ?: context.getString(R.string.restore_backup_failed)
                            showImporErrortDialog.value = true
                        } finally {
                            restoring = false
                        }
                    }
                    showComboImportDialog = false
                }, enabled = comboDic.values.any { it }) { Text(stringResource(R.string.combo_import_label)) }
            },
            dismissButton = { TextButton(onClick = { showComboImportDialog = false }) { Text(stringResource(R.string.cancel_label)) } }
        )
    }
    var showComboExportDialog by remember { mutableStateOf(false) }
    if (showComboExportDialog) {
        AlertDialog(onDismissRequest = { showComboExportDialog = false },
            title = { Text(stringResource(R.string.combo_export_label)) },
            text = {
                Column {
                    comboDic.keys.forEach { option ->
                        SettingsCheckbox(backupOptionLabel(option), comboDic[option] == true) { comboDic[option] = it }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = tempRoottree
                    if (uri == null) {
                        Loge(TAG, localizedString(R.string.message_export_uri_is_null))
                        return@TextButton
                    }
                    processingText = context.getString(R.string.settings_exporting)
                    CoroutineScope(Dispatchers.IO).launch {
                        val chosenDir = uri.toUF()
                        val exportSubDir = chosenDir.createDirectory(dateStampFilename("$backupDirName-%s"))
                        if (comboDic[ComboIEOptions.MeidaFiles.name] == true) MediaFilesTransporter(mediaFilesDirName).fromMediaDirToUF(exportSubDir)
                        if (comboDic[ComboIEOptions.Clips.name] == true) ClipsTransporter(clipsDirName).fromMediaDirToUF(exportSubDir)
                        if (comboDic[ComboIEOptions.Database.name] == true) {
                            val realmFile = exportSubDir.createFile("application/octet-stream", "backup.realm")
                            DatabaseTransporter().exportToUri(realmFile)
                        }
                        withContext(Dispatchers.Main) { processingText = "" }
                    }
                    showComboExportDialog = false
                }, enabled = comboDic.values.any { it }) { Text(stringResource(R.string.combo_export_label)) }
            },
            dismissButton = { TextButton(onClick = { showComboExportDialog = false }) { Text(stringResource(R.string.cancel_label)) } }
        )
    }

    val selectAutoBackupDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val uri: Uri? = it.data?.data
            if (uri != null) {
                persistedTrees.add(uri)
                getAppContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                upsertBlk(appPrefs) { p-> p.autoBackupFolder = uri.toString() }
            }
        }
    }

    val restoreComboLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.data!!
        if (isComboDir(uri)) {
            tempRoottree = uri
            val rootFile = uri.toUF()
            comboDic.clear()
            runBlocking {
                for (child in rootFile.listChildren()) {
                    Logd(TAG) { "restoreComboLauncher child: ${child.isDirectory()} ${child.name} ${child.toAndroidUri()} " }
                    if (child.isDirectory()) {
                        if (child.name == clipsDirName) comboDic[ComboIEOptions.Clips.name] = true
                        if (child.name == mediaFilesDirName) comboDic[ComboIEOptions.MeidaFiles.name] = true
                    } else if (isRealmFile(child.toAndroidUri()!!)) comboDic[ComboIEOptions.Database.name] = true
                }
            }
            showComboImportDialog = true
        } else {
            val message = context.getString(R.string.import_directory_toast) + backupDirName + " or " + autoBackupDirName
            processingText = ""
            Loge(TAG, localizedString(R.string.message_export_error, (message).toString()))
            importErrorMessage = message
            showImporErrortDialog.value = true
        }
    }
    val backupComboLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val uri: Uri? = it.data?.data
            if (uri != null) {
                comboDic.clear()
                comboDic[ComboIEOptions.Database.name] = true
                comboDic[ComboIEOptions.Clips.name] = true
                comboDic[ComboIEOptions.MeidaFiles.name] = true
                tempRoottree = uri
                showComboExportDialog = true
            }
        }
    }

    val chooseAPImportPathLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processingText = context.getString(R.string.settings_importing_ap)
            importAP(uri) {
                showImporSuccessDialog.value = true
                processingText = ""
            }
        } }

    var importPADB by remember { mutableStateOf(false) }
    var importPADirectory by remember { mutableStateOf(false) }
    val choosePAImportPathLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processingText = context.getString(R.string.settings_importing_pa)
            CoroutineScope(Dispatchers.IO).launch {
                importPA(uri, importPADB, importPADirectory) {}
                showImporSuccessDialog.value = true
                processingText = ""
            }
        } }
    fun openExportPathPicker(exportType: ExportTypes, result: ActivityResultLauncher<Intent>, writer: ExportWriter) {
        val title = dateStampFilename(exportType.outputNameTemplate)
        val intentPickAction = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(exportType.contentType)
            .putExtra(Intent.EXTRA_TITLE, title)
        try {
            result.launch(intentPickAction)
            return
        } catch (e: ActivityNotFoundException) { Logs(TAG, e, "No activity found. Should never happen...") }
        // If we are using an SDK lower than API 21 or the implicit intent failed fallback to the legacy export process
        exportWithWriter(writer, null, exportType)
    }


    if (restoring) {
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.restore_backup_progress), modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }

    if (processingText.isNotBlank()) {
        CommonPopupCard(onDismiss = { processingText = "" }) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(processingText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            Logd(TAG) { "LifecycleEventObserver: $event" }
            when (event) {
                Lifecycle.Event.ON_START -> {}
                Lifecycle.Event.ON_STOP -> {}
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            tempRoottree = null
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    SettingsPage {
        var showFileTags by remember { mutableStateOf(false) }
        if (showFileTags) ac.mdiq.podcini.ui.compose.FileTagManagerDialog { showFileTags = false }
        SettingsAction(R.string.file_tags_settings, R.string.file_tags_retro_import) { showFileTags = true }
        SettingsSection(R.string.settings_backup_restore)
        SettingsAction(R.string.combo_export_label, R.string.combo_export_summary) {
            val uri = "content://com.android.externalstorage.documents/tree/primary:".toUri()
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra("android.provider.extra.INITIAL_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                backupComboLauncher.launch(intent)
            } catch (e: Exception) { Loge(TAG, e, localizedString(R.string.message_export_failed))}
        }
        val showComboImportDialog = remember { mutableStateOf(false) }
        ConfirmDialog(titleRes = R.string.combo_import_label, message = stringResource(R.string.combo_import_warning), showDialog = showComboImportDialog, confirmRes = R.string.combo_import_label) {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                restoreComboLauncher.launch(intent)
            } catch (e: Exception) { Loge(TAG, e, localizedString(R.string.message_import_failed))}
        }
        SettingsAction(R.string.combo_import_label, R.string.combo_import_summary) { showComboImportDialog.value = true }
        SettingsSection(R.string.settings_automatic_backups)
        SettingsSwitch(R.string.pref_backup_on_google_title, R.string.pref_backup_on_google_sum, appPrefs.OPMLBackup) {
            upsertBlk(appPrefs) { p -> p.OPMLBackup = it}
        }
        SettingsSwitch(R.string.pref_auto_backup_title, R.string.pref_auto_backup_sum, appPrefs.autoBackup) {
            upsertBlk(appPrefs) { p -> p.autoBackup = it}

        }
        if (appPrefs.autoBackup) {
            SettingsNumber(R.string.pref_auto_backup_interval, 0, appPrefs.autoBackupIntervall,
                stringResource(R.string.ui_hours), min = 1) { value ->
                upsertBlk(appPrefs) { it.autoBackupIntervall = value }
            }
            SettingsNumber(R.string.pref_auto_backup_limit, 0, appPrefs.autoBackupLimit,
                stringResource(R.string.settings_backups_unit), min = 1, max = 9) { value ->
                upsertBlk(appPrefs) { it.autoBackupLimit = value }
            }
            SettingsAction(stringResource(R.string.pref_auto_backup_folder),
                appPrefs.autoBackupFolder ?: stringResource(R.string.pref_auto_backup_folder_sum)) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                selectAutoBackupDirLauncher.launch(intent)
            }
        }
        SettingsSection(R.string.settings_import_other_apps)
        val showAPImportDialog = remember { mutableStateOf(false) }
        ConfirmDialog(titleRes = R.string.import_AP_label, message = stringResource(R.string.import_SQLite_message), showDialog = showAPImportDialog) {
            try { chooseAPImportPathLauncher.launch("*/*") } catch (e: ActivityNotFoundException) { Logs(TAG, e, "No activity found. Should never happen...") }
        }
        SettingsAction(R.string.import_AP_label, R.string.settings_import_ap_summary) { showAPImportDialog.value = true }
        val showPAImportDialog = remember { mutableStateOf(false) }
        if (showPAImportDialog.value) {
            AlertDialog(onDismissRequest = { showPAImportDialog.value = false },
                title = { Text(stringResource(R.string.import_PA_label)) },
                text = {
                    Column {
                        SettingsCheckbox(stringResource(R.string.import_PA_DB_label), importPADB) { importPADB = it }
                        Text(stringResource(R.string.import_PA_DB_message), color = textColor, style = MaterialTheme.typography.bodyMedium)
                        SettingsCheckbox(stringResource(R.string.import_PA_directory_label), importPADirectory) { importPADirectory = it }
                        Text(stringResource(R.string.import_PA_directory_message), color = textColor, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.import_PA_message), color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        try { choosePAImportPathLauncher.launch("*/*") } catch (e: ActivityNotFoundException) { Logs(TAG, e, "No activity found. Should never happen...") }
                        showPAImportDialog.value = false
                    }, enabled = importPADB || importPADirectory) { Text(stringResource(R.string.settings_choose_file)) }
                },
                dismissButton = { TextButton(onClick = { showPAImportDialog.value = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        SettingsAction(R.string.import_PA_label, R.string.settings_import_pa_summary) { showPAImportDialog.value = true }
        SettingsSection(R.string.archive_subscriptions_section)
        SettingsAction(R.string.opml_export_label, R.string.opml_export_summary) { openExportPathPicker(ExportTypes.OPML, chooseOpmlExportPathLauncher, OpmlWriter()) }
        if (showOpmlImportSelectionDialog) OpmlImportSelectionDialog(readElements) { showOpmlImportSelectionDialog = false }
        SettingsAction(R.string.opml_import_label, R.string.opml_import_summary) {
            try { chooseOpmlImportPathLauncher.launch("*/*") } catch (e: ActivityNotFoundException) { Logs(TAG, e, "No activity found. Should never happen...") } }
        SettingsSection(R.string.settings_listening_progress)
        SettingsAction(R.string.progress_export_label, R.string.progress_export_summary) { openExportPathPicker(ExportTypes.PROGRESS, chooseProgressExportPathLauncher, EpisodesProgressWriter()) }
        val showProgressImportDialog = remember { mutableStateOf(false) }
        ConfirmDialog(titleRes = R.string.progress_import_label, message = stringResource(R.string.progress_import_warning), showDialog = showProgressImportDialog) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            restoreProgressLauncher.launch(intent)
        }
        SettingsAction(R.string.progress_import_label, R.string.progress_import_summary) { showProgressImportDialog.value = true }
        SettingsSection(R.string.settings_readable_exports)
        SettingsAction(R.string.html_export_label, R.string.html_export_summary) { openExportPathPicker(ExportTypes.HTML, chooseHtmlExportPathLauncher, HtmlWriter()) }
        SettingsAction(R.string.favorites_export_label, R.string.favorites_export_summary) { openExportPathPicker(ExportTypes.FAVORITES, chooseFavoritesExportPathLauncher, FavoritesWriter()) }
    }
}

class EpisodeProgressReader {
    val TAG = "EpisodeProgressReader"

    fun readDocument(reader: BufferedSource) {
        val jsonString = reader.readUtf8Line()
        val jsonArray = JSONArray(jsonString)
        for (i in 0 until jsonArray.length()) {
            val jsonAction = jsonArray.getJSONObject(i)
            Logd(TAG) { "Loaded EpisodeActions message: $i $jsonAction" }
            val action = readFromJsonObject(jsonAction) ?: continue
            Logd(TAG) { "processing action: $action" }
            val result = processEpisodeAction(action) ?: continue
            //                upsertBlk(result.second) {}
        }
    }
    private fun processEpisodeAction(action: EpisodeAction): Pair<Long, Episode>? {
        val guid = if (isValidGuid(action.guid)) action.guid else null
        var feedItem = episodeByGuidOrUrl(guid, action.episode, false) ?: return null
        var idRemove = 0L
        feedItem = upsertBlk(feedItem) {
            it.startPosition = action.started * 1000
            it.position = action.position * 1000
            it.playedDuration = action.playedDuration * 1000
            it.lastPlayedTime = (action.timestamp!!)
            it.setRating(if (action.isFavorite) Rating.SUPER else Rating.UNRATED)
            it.setPlayState(EpisodeState.fromCode(action.playState))
            if (it.hasAlmostEnded()) {
                Logd(TAG) { "Marking as played: $action" }
                it.setPlayState(EpisodeState.PLAYED)
                //                it.setPosition(0)
                idRemove = it.id
            } else Logd(TAG) { "Setting position: $action" }
        }
        return Pair(idRemove, feedItem)
    }
}
