package ac.mdiq.podcini.ui.screens.prefscreens

import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import ac.mdiq.podcini.utils.localizedString
import ac.mdiq.podcini.PodciniApp.Companion.forceRestart
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.config.settings.MediaFilesTransporter
import ac.mdiq.podcini.sourcing.feed.FeedUpdateManager.checkAndScheduleUpdateTaskOnce
import ac.mdiq.podcini.sourcing.feed.FeedUpdateManager.intervalInMillis
import ac.mdiq.podcini.sync.SyncService
import ac.mdiq.podcini.sync.SynchronizationProviderViewData
import ac.mdiq.podcini.sync.SynchronizationSettings
import ac.mdiq.podcini.sync.SynchronizationSettings.isSyncProviderConnected
import ac.mdiq.podcini.sync.SynchronizationSettings.setSelectedSyncProvider
import ac.mdiq.podcini.sync.SynchronizationSettings.setWifiSyncEnabled
import ac.mdiq.podcini.sync.nextcloud.NextcloudLoginFlow
import ac.mdiq.podcini.sync.nextcloud.NextcloudLoginFlow.AuthenticationCallback
import ac.mdiq.podcini.sync.wifi.WifiSyncService.Companion.startInstantSync
import ac.mdiq.podcini.shared.PodciniHttpClient
import ac.mdiq.podcini.shared.PodciniHttpClient.getKtorClient
import ac.mdiq.podcini.shared.PodciniHttpClient.resetClient
import ac.mdiq.podcini.shared.ProxyConfig
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.AppGatewayRegistry
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.proxyConfig
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.utils.deleteDirectoryRecursively
import ac.mdiq.podcini.storage.utils.findRootForUri
import ac.mdiq.podcini.storage.utils.mediaDir
import ac.mdiq.podcini.storage.utils.persistedTrees
import ac.mdiq.podcini.storage.utils.toAndroidUri
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.ui.compose.CommonPopupCard
import ac.mdiq.podcini.ui.compose.ConfirmDialog
import ac.mdiq.podcini.ui.compose.NumberEditor
import ac.mdiq.podcini.ui.compose.Spinner
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.MobileUpdateOptions
import ac.mdiq.podcini.utils.fullDateTimeString
import android.app.Activity.RESULT_OK
import android.content.Context.WIFI_SERVICE
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xilinjia.krdb.ext.toRealmSet
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketAddress

private const val TAG = "NetworkPreferences"

@Composable
fun NetworkStorageScreen(section: String = "downloads") {
    val context by rememberUpdatedState(LocalContext.current)
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    @Composable
    fun ProxyDialog(onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        var type by remember { mutableStateOf(proxyConfig.type) }
        var host by remember { mutableStateOf(proxyConfig.host.orEmpty()) }
        var port by remember { mutableStateOf(proxyConfig.port.takeIf { it > 0 }?.toString().orEmpty()) }
        var username by remember { mutableStateOf(proxyConfig.username.orEmpty()) }
        var password by remember { mutableStateOf(proxyConfig.password.orEmpty()) }
        var testing by remember { mutableStateOf(false) }
        var testSuccessful by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        val types = listOf(Proxy.Type.DIRECT, Proxy.Type.HTTP, Proxy.Type.SOCKS)
        val labels = listOf(stringResource(R.string.settings_proxy_none), "HTTP", "SOCKS")
        val hostValid = host.trim().let { it == "localhost" || (it.isNotBlank() && Patterns.DOMAIN_NAME.matcher(it).matches()) }
        val portValue = port.toIntOrNull()
        val portValid = portValue != null && portValue in 1..65535
        val valid = type == Proxy.Type.DIRECT || (hostValid && portValid)

        fun invalidateTest() {
            testSuccessful = false
            failed = false
            message = ""
        }

        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pref_proxy_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.proxy_type_label), style = MaterialTheme.typography.bodyMedium)
                    if (!testing) Spinner(items = labels, selectedItem = labels[types.indexOf(type)]) { index ->
                        type = types[index]
                        invalidateTest()
                    } else Text(labels[types.indexOf(type)])
                    if (type != Proxy.Type.DIRECT) {
                        OutlinedTextField(value = host, onValueChange = { host = it; invalidateTest() }, enabled = !testing,
                            label = { Text(stringResource(R.string.host_label)) }, singleLine = true,
                            isError = host.isNotBlank() && !hostValid, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = port, onValueChange = { port = it; invalidateTest() }, enabled = !testing,
                            label = { Text(stringResource(R.string.port_label)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = port.isNotBlank() && !portValid, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = username, onValueChange = { username = it; invalidateTest() }, enabled = !testing,
                            label = { Text(stringResource(R.string.username_label)) }, singleLine = true,
                            supportingText = { Text(stringResource(R.string.optional_hint)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = password, onValueChange = { password = it; invalidateTest() }, enabled = !testing,
                            label = { Text(stringResource(R.string.password_label)) }, singleLine = true,
                            supportingText = { Text(stringResource(R.string.optional_hint)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    }
                    if (testing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.proxy_checking), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                val canSave = type == Proxy.Type.DIRECT || testSuccessful
                TextButton(enabled = valid && !testing, onClick = {
                    val config = ProxyConfig(type, host.trim(), portValue ?: 0, username.ifBlank { null }, password.ifBlank { null })
                    if (canSave) {
                        proxyConfig = config
                        PodciniHttpClient.configProxy(config)
                        resetClient()
                        onDismiss()
                    } else {
                        testing = true
                        message = ""
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val address = InetSocketAddress.createUnresolved(config.host!!, config.port)
                                    HttpClient(OkHttp) {
                                        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 15_000 }
                                        engine { config { proxy(Proxy(type, address)) } }
                                    }.use { client ->
                                        val response = client.request("https://www.example.com") { method = HttpMethod.Get }
                                        if (!response.status.isSuccess()) throw IOException(response.status.description)
                                    }
                                }
                                testSuccessful = true
                                failed = false
                                message = context.getString(R.string.proxy_test_successful)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed = true
                                message = context.getString(R.string.proxy_test_failed) + ": " + e.message.orEmpty()
                            } finally { testing = false }
                        }
                    }
                }) { Text(stringResource(if (canSave) R.string.settings_save else R.string.proxy_test_label)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } }
        )
    }

    var showProxyDialog by remember { mutableStateOf(false) }
    if (showProxyDialog) ProxyDialog {showProxyDialog = false }
    val showImporSuccessDialog = remember { mutableStateOf(false) }
    ConfirmDialog(titleRes = R.string.successful_import_label, message = stringResource(R.string.import_ok), showDialog = showImporSuccessDialog, cancellable = false) { forceRestart() }

    var showProgress by remember { mutableStateOf(false) }
    if (showProgress) {
        CommonPopupCard(onDismiss = { showProgress = false }) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 10.dp, color = textColor, modifier = Modifier.size(50.dp).align(Alignment.TopCenter))
                Text(stringResource(R.string.archive_loading), color = textColor, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    Logd(TAG) { "useCustomMediaFolder: ${appPrefs.useCustomMediaFolder} customMediaUri: ${appPrefs.customMediaUri}" }
    val selectCustomMediaDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: Uri? = result.data?.data
            Logd(TAG) { "selectCustomMediaDirLauncher the chosen uri: $uri" }
            if (uri != null) {
                showProgress = true
                CoroutineScope(Dispatchers.IO).launch {
                    getAppContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    persistedTrees.add(uri)
                    val dir = uri.toUF()
                    dir.listChildren().forEach {
                        Logd(TAG) { "clearing destination: ${it.absPath}" }
                        deleteDirectoryRecursively(it)
                    }
                    val mediaDir_ = uri.toUF().createDirectory("Podcini.media")
                    MediaFilesTransporter("Podcini.media").fromMediaDirToUF(mediaDir_, move = true, useSubDir = false)
                    deleteDirectoryRecursively(mediaDir)
                    upsert(appPrefs) { ap ->
                        ap.useCustomMediaFolder = true
                        ap.customMediaUri = mediaDir_.toAndroidUri().toString()
                        ap.customFolderUnavailable = false
                    }
                    showProgress = false
                    showImporSuccessDialog.value = true
                }
            } else Loge("selectCustomMediaDirLauncher", localizedString(R.string.message_uri_is_null))
        } else Logt(TAG, localizedString(R.string.message_custom_dir_not_chosen))
    }

    var refreshInterval by remember { mutableStateOf(appPrefs.autoUpdateInterval.toString()) }
    SettingsPage {
        if (section == "providers") SettingsSection(R.string.settings_external_sources)
        if (section == "providers") SettingsSwitch(R.string.pref_use_external_apps, R.string.pref_use_external_app_sum, appPrefs.loadExternalApp) {
            val appPrefs_ = upsertBlk(appPrefs) { p-> p.loadExternalApp = it}
            AppGatewayRegistry.initialize(appPrefs_.loadExternalApp, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
        if (section == "automation") {
        SettingsSection(R.string.settings_feed_updates)
        SettingsNumber(R.string.feed_refresh_title, R.string.feed_refresh_sum, appPrefs.autoUpdateInterval,
            stringResource(R.string.time_minutes)) { interval ->
            refreshInterval = interval.toString()
            upsertBlk(appPrefs) { it.autoUpdateInterval = interval }
            checkAndScheduleUpdateTaskOnce(replace = true, force = interval > 0)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            fun getRefreshTime(): String {
                val initialDelay = intervalInMillis
                val lastUpdateTime = appAttribsFlow!!.value.prefLastFullUpdateTime
                Logd(TAG) { "lastUpdateTime: $lastUpdateTime updateInterval: $intervalInMillis" }
                return if (lastUpdateTime == 0L) {
                    if (initialDelay != 0L) fullDateTimeString(nowInMillis() + initialDelay + intervalInMillis)
                    else getAppContext().getString(R.string.before) + fullDateTimeString(nowInMillis() + intervalInMillis)
                } else fullDateTimeString(lastUpdateTime + intervalInMillis)
            }
            val nextRefreshTime = remember(refreshInterval) { getRefreshTime() }
            if (refreshInterval != "0") Text(stringResource(R.string.feed_next_refresh_time) + " " + nextRefreshTime,
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        SettingsSwitch(R.string.pref_fetch_media_size, R.string.pref_fetch_media_size_sum, appPrefs.fetchmediaSizes) {
            upsertBlk(appPrefs) { p-> p.fetchmediaSizes = it}
        }
        }
        if (section == "downloads") {
        SettingsSection(R.string.settings_download_storage)
        SettingsSwitch(R.string.pref_watch_storage_title, R.string.pref_watch_storage_sum, appPrefs.checkAvailableSpace) {
            upsertBlk(appPrefs) { p-> p.checkAvailableSpace = it}
        }
        var showResetCustomFolderDialog by remember { mutableStateOf(false) }
        if (showResetCustomFolderDialog) {
            AlertDialog(onDismissRequest = { showResetCustomFolderDialog = false },
                title = { Text(stringResource(R.string.pref_custom_media_dir_title), style = MaterialTheme.typography.titleLarge) },
                text = { Text(stringResource(R.string.pref_custom_media_dir_sum2), color = textColor, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(onClick = {
                        showProgress = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val uf = appPrefs.customMediaUri.toUF()
                            upsert(appPrefs) {
                                it.useCustomMediaFolder = false
                                it.customMediaUri = ""
                                it.customFolderUnavailable = false
                            }
                            MediaFilesTransporter("").fromUFToMediaDir(uf, move = true, verify = false)
                            deleteDirectoryRecursively(uf)
                            findRootForUri(appPrefs.customMediaUri.toSafeUri())?.let {
                                try { getAppContext().contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { Logd(TAG) { "uri can not be released: $it" }}
                            }
                            showProgress = false
                            showImporSuccessDialog.value = true
                            showResetCustomFolderDialog = false
                        }
                    }) { Text(stringResource(R.string.reset)) }
                },
                dismissButton = { TextButton(onClick = { showResetCustomFolderDialog = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        var showSetCustomFolderDialog by remember { mutableStateOf(false) }
        if (showSetCustomFolderDialog) {
            val sumTextRes = R.string.pref_custom_media_dir_sum1
            AlertDialog(onDismissRequest = { showSetCustomFolderDialog = false },
                title = { Text(stringResource(R.string.pref_custom_media_dir_title), style = MaterialTheme.typography.titleLarge) },
                text = { Text(stringResource(sumTextRes), color = textColor, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            intent.addCategory(Intent.CATEGORY_DEFAULT)
                            selectCustomMediaDirLauncher.launch(intent)
                            showSetCustomFolderDialog = false
                        } catch (e: Exception) { Loge(TAG, e, localizedString(R.string.message_can_t_select_custom_dir))}
                    }) { Text(stringResource(R.string.confirm_label)) }
                },
                dismissButton = { TextButton(onClick = { showSetCustomFolderDialog = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        SettingsAction(stringResource(R.string.pref_custom_media_dir_title),
            appPrefs.customMediaUri.ifBlank { stringResource(R.string.pref_custom_media_dir_sum) }) { showSetCustomFolderDialog = true }
        if (appPrefs.useCustomMediaFolder) SettingsAction(R.string.settings_reset_media_folder, R.string.settings_reset_media_summary) { showResetCustomFolderDialog = true }

        }
        if (section == "automation") {
        SettingsSection(R.string.settings_auto_downloads)
        SettingsSwitch(R.string.pref_automatic_download_title, R.string.pref_automatic_download_sum, appPrefs.enableAutoDl) {
            upsertBlk(appPrefs) { p -> p.enableAutoDl = it }
        }
        if (appPrefs.enableAutoDl) {
            SettingsNumber(R.string.pref_episode_cache_title, R.string.pref_episode_cache_summary, appPrefs.episodeCacheSize,
                stringResource(R.string.episodes_label)) { limit -> upsertBlk(appPrefs) { it.episodeCacheSize = limit } }
            var showCleanupOptions by remember { mutableStateOf(false) }
            SettingsAction(R.string.pref_episode_cleanup_title, R.string.pref_episode_cleanup_summary) { showCleanupOptions = true }
            if (showCleanupOptions) {
                var tempCleanupOption by remember { mutableStateOf(appPrefs.episodeCleanup) }
                var interval by remember { mutableStateOf(appPrefs.episodeCleanup) }
                if ((interval.toIntOrNull() ?: -1) > 0) tempCleanupOption = EpisodeCleanupOptions.LimitBy.num.toString()
                AlertDialog(onDismissRequest = { showCleanupOptions = false },
                    title = { Text(stringResource(R.string.pref_episode_cleanup_title), style = MaterialTheme.typography.titleLarge) },
                    text = {
                        Column {
                            EpisodeCleanupOptions.entries.forEach { option ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(2.dp)
                                    .selectable(selected = tempCleanupOption == option.num.toString(), role = androidx.compose.ui.semantics.Role.RadioButton) { tempCleanupOption = option.num.toString() }) {
                                    RadioButton(selected = tempCleanupOption == option.num.toString(), onClick = null)
                                    Text(stringResource(option.res), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (tempCleanupOption == EpisodeCleanupOptions.LimitBy.num.toString()) {
                                NumberEditor(interval.toIntOrNull() ?: 0, label = stringResource(R.string.ui_hours), modifier = Modifier.fillMaxWidth()) { interval = it.toString() }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            var num = if (tempCleanupOption == EpisodeCleanupOptions.LimitBy.num.toString()) interval else tempCleanupOption
                            if (num.toIntOrNull() == null) num = EpisodeCleanupOptions.Never.num.toString()
                            upsertBlk(appPrefs) { it.episodeCleanup = num}
                            showCleanupOptions = false
                        }) { Text(text = stringResource(R.string.OK)) }
                    },
                    dismissButton = { TextButton(onClick = { showCleanupOptions = false }) { Text(stringResource(R.string.cancel_label)) } }
                )
            }
            SettingsSwitch(R.string.pref_automatic_download_on_battery_title, R.string.pref_automatic_download_on_battery_sum, appPrefs.enableAutoDownloadOnBattery) {
                upsertBlk(appPrefs) { p-> p.enableAutoDownloadOnBattery = it}
            }
        }

        }
        if (section == "downloads") {
        SettingsSection(R.string.settings_network)
        var showMeteredNetworkOptions by remember { mutableStateOf(false) }
        SettingsAction(R.string.pref_metered_network_title, R.string.pref_mobileUpdate_sum) { showMeteredNetworkOptions = true }
        if (showMeteredNetworkOptions) {
            var tempSelectedOptions by remember { mutableStateOf(appPrefs.mobileUpdateTypes.toSet()) }
            fun updateSepections(option: MobileUpdateOptions) {
                tempSelectedOptions = if (tempSelectedOptions.contains(option.name)) tempSelectedOptions - option.name else tempSelectedOptions + option.name
                when (option) {
                    MobileUpdateOptions.auto_download if tempSelectedOptions.contains(option.name) -> {
                        tempSelectedOptions += MobileUpdateOptions.episode_download.name
                        tempSelectedOptions += MobileUpdateOptions.feed_refresh.name
                    }
                    MobileUpdateOptions.episode_download if !tempSelectedOptions.contains(option.name) -> tempSelectedOptions -= MobileUpdateOptions.auto_download.name
                    MobileUpdateOptions.feed_refresh if !tempSelectedOptions.contains(option.name) -> tempSelectedOptions -= MobileUpdateOptions.auto_download.name
                    else -> {}
                }
            }
            AlertDialog(onDismissRequest = { showMeteredNetworkOptions = false },
                title = { Text(stringResource(R.string.pref_metered_network_title), style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column {
                        MobileUpdateOptions.entries.forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(2.dp).clickable { updateSepections(option) }) {
                                Checkbox(checked = tempSelectedOptions.contains(option.name), onCheckedChange = { updateSepections(option) })
                                Text(stringResource(option.res), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        upsertBlk(appPrefs) { it.mobileUpdateTypes = tempSelectedOptions.toRealmSet() }
                        val optionsDiff = (tempSelectedOptions - appPrefs.mobileUpdateTypes) + (appPrefs.mobileUpdateTypes - tempSelectedOptions)
                        if (optionsDiff.contains(MobileUpdateOptions.feed_refresh.name) || optionsDiff.contains(MobileUpdateOptions.auto_download.name))
                            checkAndScheduleUpdateTaskOnce(replace = true, force = true)
                        showMeteredNetworkOptions = false
                    }) { Text(text = stringResource(R.string.OK)) }
                },
                dismissButton = { TextButton(onClick = { showMeteredNetworkOptions = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        SettingsAction(R.string.pref_proxy_title, R.string.pref_proxy_sum) { showProxyDialog = true }
        }
    }
}

enum class EpisodeCleanupOptions(val res: Int, val num: Int) {
    ExceptFavorites(R.string.episode_cleanup_except_favorite, -3),
    Never(R.string.episode_cleanup_never, -2),
    NotInQueue(R.string.episode_cleanup_not_in_queue, -1),
    LimitBy(R.string.episode_cleanup_limit_by, 0)
}

@Composable
fun SynchronizationScreen() {
    val context by rememberUpdatedState(LocalContext.current)
    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    val selectedSyncProviderKey: String = SynchronizationSettings.selectedSyncProviderKey?:""
    var selectedProvider by remember { mutableStateOf(SynchronizationProviderViewData.fromIdentifier(selectedSyncProviderKey)) }
    var loggedIn by remember { mutableStateOf(isSyncProviderConnected) }

    @Composable
    fun NextcloudAuthenticationDialog(onDismiss: ()->Unit) {
        var nextcloudLoginFlow = remember<NextcloudLoginFlow?> { null }
        var showUrlEdit by remember { mutableStateOf(true) }
        var serverUrlText by remember { mutableStateOf(appPrefsFlow!!.value.nextcloud_server_address) }
        var errorText by remember { mutableStateOf("") }
        var showChooseHost by remember { mutableStateOf(serverUrlText.isNotBlank()) }

        val nextCloudAuthCallback = object : AuthenticationCallback {
            override fun onNextcloudAuthenticated(server: String, username: String, password: String) {
                Logd("NextcloudAuthenticationDialog") { "onNextcloudAuthenticated: $server" }
                setSelectedSyncProvider(SynchronizationProviderViewData.NEXTCLOUD_GPODDER)
                SynchronizationSettings.clear()
                SynchronizationSettings.password = password
                SynchronizationSettings.hosturl = server
                SynchronizationSettings.username = username
                SyncService.fullSync()
                loggedIn = isSyncProviderConnected
                onDismiss()
            }
            override fun onNextcloudAuthError(errorMessage: String?) {
                errorText = errorMessage ?: ""
                showChooseHost = true
                showUrlEdit = true
            }
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) nextcloudLoginFlow?.poll() }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        AlertDialog(onDismissRequest = { onDismiss() },
            title = { Text(stringResource(R.string.gpodnetauth_login_butLabel), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(stringResource(R.string.synchronization_host_explanation))
                    if (showUrlEdit) TextField(value = serverUrlText, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.synchronization_host_label)) },
                        onValueChange = {
                            serverUrlText = it
                            showChooseHost = serverUrlText.isNotBlank()
                        }
                    )
                    Text(stringResource(R.string.synchronization_nextcloud_authenticate_browser))
                    if (errorText.isNotBlank()) Text(errorText)
                }
            },
            confirmButton = {
                if (showChooseHost) TextButton(onClick = {
                    upsertBlk(appPrefsFlow!!.value) { it.nextcloud_server_address = serverUrlText}
                    nextcloudLoginFlow = NextcloudLoginFlow(getKtorClient(), serverUrlText, getAppContext(), nextCloudAuthCallback)
                    errorText = ""
                    showChooseHost = false
                    nextcloudLoginFlow.start()
                    //                        onDismissRequest()
                }) { Text(stringResource(R.string.proceed_to_login_butLabel)) }
            },
            dismissButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel_label)) } }
        )
    }

    var showNextCloudAuthDialog by remember { mutableStateOf(false) }
    if (showNextCloudAuthDialog) NextcloudAuthenticationDialog { showNextCloudAuthDialog = false }

    @Composable
    fun ChooseProviderAndLoginDialog(onDismiss: ()->Unit) {
        AlertDialog(onDismissRequest = { onDismiss() },
            title = { Text(stringResource(R.string.dialog_choose_sync_service_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    SynchronizationProviderViewData.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(2.dp)
                            .clickable {
                                when (option) {
                                    //                                    SynchronizationProviderViewData.GPODDER_NET -> GpodderAuthenticationFragment().show(activity.supportFragmentManager, GpodderAuthenticationFragment.TAG)
                                    SynchronizationProviderViewData.NEXTCLOUD_GPODDER -> showNextCloudAuthDialog = true
                                }
                                loggedIn = isSyncProviderConnected
                                onDismiss()
                            }) {
                            Icon(painter = painterResource(id = option.iconResource), contentDescription = "", modifier = Modifier.size(40.dp).padding(end = 15.dp))
                            Text(stringResource(option.summaryResource), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel_label)) } }
        )
    }

    @Composable
    fun WifiAuthenticationDialog(onDismiss: ()->Unit) {
        val TAG = "WifiAuthenticationDialog"

        val context by rememberUpdatedState(LocalContext.current)
        var progressMessage by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            EventFlow.events.collectLatest { event ->
                Logd(TAG) { "Received event: ${event.TAG}" }
                when (event) {
                    is FlowEvent.SyncServiceEvent -> {
                        when (event.messageResId) {
                            R.string.sync_status_error -> {
                                errorMessage = event.message
                                Loge(TAG, errorMessage)
                                onDismiss()
                            }
                            R.string.sync_status_success -> {
                                Logt(TAG, context.getString(R.string.sync_status_success))
                                onDismiss()
                            }
                            R.string.sync_status_in_progress -> progressMessage = event.message
                            else -> Loge(TAG, localizedString(R.string.message_sync_result_unknown, (event.messageResId).toString()))
                        }
                    }
                    else -> {}
                }
            }
        }
        var portNum by remember { mutableIntStateOf(SynchronizationSettings.hostport) }
        var isGuest by remember { mutableStateOf<Boolean?>(null) }
        var hostAddress by remember { mutableStateOf(SynchronizationSettings.hosturl?:"") }
        var showHostAddress by remember { mutableStateOf(true)  }
        var portString by remember { mutableStateOf(SynchronizationSettings.hostport.toString()) }
        var showProgress by remember { mutableStateOf(false) }
        var showConfirm by remember { mutableStateOf(true)  }
        var showCancel by remember { mutableStateOf(true)  }
        AlertDialog(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { onDismiss() },
            title = { Text(stringResource(R.string.connect_to_peer), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(stringResource(R.string.wifisync_explanation_message), style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = {
                            val wifiManager = context.getSystemService(WIFI_SERVICE) as WifiManager
                            val ipAddress = wifiManager.connectionInfo.ipAddress
                            val ipString = "${ipAddress and 0xff}.${ipAddress shr 8 and 0xff}.${ipAddress shr 16 and 0xff}.${ipAddress shr 24 and 0xff}"
                            hostAddress = ipString
                            showHostAddress = false
                            portNum = portString.toInt()
                            isGuest = false
                            SynchronizationSettings.hostport = portNum
                        }) { Text(stringResource(R.string.host_butLabel)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            SynchronizationSettings.hosturl = hostAddress
                            showHostAddress = true
                            portNum = portString.toInt()
                            isGuest = true
                            SynchronizationSettings.hostport = portNum
                        }) { Text(stringResource(R.string.guest_butLabel)) }
                    }
                    Row {
                        if (showHostAddress) TextField(value = hostAddress, modifier = Modifier.weight(0.6f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            onValueChange = { input -> hostAddress = input },
                            label = { Text(stringResource(id = R.string.synchronization_host_address_label)) })
                        TextField(value = portString, modifier = Modifier.weight(0.4f).padding(start = 3.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            onValueChange = { input ->
                                portString = input
                                portNum = input.toInt()
                            },
                            label = { Text(stringResource(id = R.string.synchronization_host_port_label)) })
                    }
                    if (showProgress)  {
                        CircularProgressIndicator(strokeWidth = 10.dp, color = textColor, modifier = Modifier.size(50.dp))
                        Text(stringResource(R.string.wifisync_progress_message) + " " + progressMessage, color = textColor)
                    }
                    Text(errorMessage, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                if (showConfirm) TextButton(onClick = {
                    Logd(TAG) { "confirm button pressed" }
                    if (isGuest == null) {
                        Logt(TAG, getAppContext().getString(R.string.host_or_guest))
                        return@TextButton
                    }
                    showProgress = true
                    showConfirm = false
                    showCancel = false
                    setWifiSyncEnabled(true)
                    startInstantSync(portNum, hostAddress, isGuest!!)
                }) { Text(stringResource(R.string.confirm_label)) }
            },
            dismissButton = { if (showCancel) TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel_label)) } }
        )
    }

//    var showWifiAuthenticationDialog by remember { mutableStateOf(false) }
//    if (showWifiAuthenticationDialog) WifiAuthenticationDialog { showWifiAuthenticationDialog = false }

    var chooseProviderAndLoginDialog by remember { mutableStateOf(false) }
    if (chooseProviderAndLoginDialog) ChooseProviderAndLoginDialog { chooseProviderAndLoginDialog = false }

    //    fun isProviderSelected(provider: SynchronizationProviderViewData): Boolean {
    //        val selectedSyncProviderKey = selectedSyncProviderKey
    //        return provider.identifier == selectedSyncProviderKey
    //    }


//    SettingsAction(R.string.wifi_sync, R.string.wifi_sync_summary_unchoosen) {
//        showWifiAuthenticationDialog = true
//    }
    if (loggedIn) {
        selectedProvider = SynchronizationProviderViewData.fromIdentifier(selectedSyncProviderKey)
        selectedProvider?.let { provider -> SettingsDescription(stringResource(provider.summaryResource)) }
    } else {
        SettingsAction(R.string.synchronization_choose_title, R.string.synchronization_summary_unchoosen) { chooseProviderAndLoginDialog = true }
    }

    if (loggedIn) {
        SettingsAction(R.string.synchronization_sync_changes_title, R.string.synchronization_sync_summary) { SyncService.syncImmediately() }
        SettingsAction(R.string.synchronization_full_sync_title, R.string.synchronization_force_sync_summary) { SyncService.fullSync() }
        SettingsAction(R.string.synchronization_logout, 0) {
            SynchronizationSettings.clear()
            Logt("SynchronizationPreferencesScreen", context.getString(R.string.pref_synchronization_logout_toast))
            setSelectedSyncProvider(null)
            loggedIn = isSyncProviderConnected
        }
    }
}
