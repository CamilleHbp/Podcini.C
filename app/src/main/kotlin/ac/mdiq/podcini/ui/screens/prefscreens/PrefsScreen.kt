package ac.mdiq.podcini.ui.screens.prefscreens

import ac.mdiq.podcini.BuildConfig
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import ac.mdiq.podcini.ui.screens.navBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import ac.mdiq.podcini.ui.screens.ReceiveContentDialog
import ac.mdiq.podcini.R
import ac.mdiq.podcini.activity.BugReportActivity
import ac.mdiq.podcini.config.settings.developerEmail
import ac.mdiq.podcini.config.settings.getCopyrightNoticeText
import ac.mdiq.podcini.config.settings.githubAddress
import ac.mdiq.podcini.ui.compose.ConfirmDialog
import ac.mdiq.podcini.ui.compose.CommonPopupCard
import ac.mdiq.podcini.ui.compose.CustomTextStyles
import ac.mdiq.podcini.ui.compose.IconTitleSummaryActionRow
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.ui.screens.LocalDrawerController
import ac.mdiq.podcini.ui.screens.PopMode
import ac.mdiq.podcini.ui.screens.defaultNavKey
import ac.mdiq.podcini.ui.screens.navTo
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.openInSystemDefault
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "PrefsMainScreen"

val pfBackStack = mutableStateListOf<PFNavKey>(PFNav.Portal)

fun pfNavBack(): Boolean {
    if (pfBackStack.size > 1) {
        pfBackStack.removeLastOrNull()
        return true
    }
    return false
}


@Serializable
sealed class PFNavKey

object PFNav {
    @Serializable
    data object Portal : PFNavKey()
    @Serializable data object Automation : PFNavKey()
    @Serializable data object Providers : PFNavKey()
    @Serializable data object Sync : PFNavKey()

    @Serializable
    data object Interface : PFNavKey()

    @Serializable
    data object NetworkStorage : PFNavKey()

    @Serializable
    data object ImportExport : PFNavKey()

    @Serializable
    data object Playback : PFNavKey()

    @Serializable
    data object Notification : PFNavKey()

    @Serializable
    data object About : PFNavKey()

    @Serializable
    data object Licenses : PFNavKey()
}

@OptIn(ExperimentalMaterial3Api::class)
val pfEntryProvider = entryProvider {
    entry<PFNav.Portal>{ PrefPortalScreen() }
    entry<PFNav.Automation>{ NetworkStorageScreen("automation") }
    entry<PFNav.Providers>{ NetworkStorageScreen("providers") }
    entry<PFNav.Sync>{
        var receive by remember { mutableStateOf(false) }
        if (receive) ReceiveContentDialog { receive = false }
        BackHandler { pfNavBack() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.archive_transfer_backup), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.archive_transfer_backup_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { pfBackStack.add(PFNav.ImportExport) }) { Text(stringResource(R.string.archive_transfer_backup)) }
            Text(stringResource(R.string.archive_device_transfer), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.archive_device_transfer_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { receive = true }) { Text(stringResource(R.string.receive_contents)) }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.archive_server_sync), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.archive_server_sync_body), style = MaterialTheme.typography.bodyMedium)
            SynchronizationScreen()
        }
    }
    entry<PFNav.Interface>{ UserInterfaceScreen() }
    entry<PFNav.NetworkStorage>{ NetworkStorageScreen() }
    entry<PFNav.ImportExport>{ ImportExportScreen() }
    entry<PFNav.Playback>{ PlaybackScreen() }
    entry<PFNav.Notification>{ NotificationPrefScreen() }
    entry<PFNav.About>{ AboutScreen() }
    entry<PFNav.Licenses>{ LicensesScreen() }
}

val pfAnyEntryProvider: (Any) -> NavEntry<Any> = { key ->
    pfEntryProvider(key as PFNavKey) as NavEntry<Any>
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrefsScreen() {
    val title = when(pfBackStack.lastOrNull()) {
        PFNav.Interface -> R.string.archive_appearance
        PFNav.Playback -> R.string.playback_pref
        PFNav.NetworkStorage -> R.string.archive_storage
        PFNav.Automation -> R.string.archive_library_automation
        PFNav.Providers -> R.string.archive_providers
        PFNav.Sync -> R.string.archive_sync_backup
        PFNav.ImportExport -> R.string.import_export_pref
        PFNav.Notification -> R.string.notification_pref_fragment
        PFNav.About -> R.string.about_pref
        PFNav.Licenses -> R.string.licenses
        else -> R.string.archive_settings
    }
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    Scaffold(contentWindowInsets = WindowInsets(0,0,0,0), topBar = {
        TopAppBar(title = { Text(stringResource(title)) }, navigationIcon = {
            IconButton(onClick = { if (!pfNavBack()) navBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.archive_back)) }
        })
    }) { padding ->
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            NavDisplay(backStack = pfBackStack, onBack = { if (!pfNavBack()) navBack() }, entryProvider = pfAnyEntryProvider, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
fun PrefPortalScreen() {
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    BackHandler { navBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
            label = { Text(stringResource(R.string.archive_search_settings)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        @Composable fun Setting(title: Int, summary: Int, icon: Int, action: () -> Unit) {
            val name = stringResource(title)
            val detail = if(summary != 0) stringResource(summary) else ""
            if (search.isBlank() || (name + detail).contains(search, ignoreCase = true)) ListItem(
                headlineContent = { Text(name) }, supportingContent = if(detail.isNotBlank()) {{ Text(detail) }} else null,
                leadingContent = { Icon(ImageVector.vectorResource(icon), null) },
                modifier = Modifier.clickable(onClick = action).heightIn(min = 72.dp))
        }
        Setting(R.string.playback_pref, R.string.playback_pref_sum, R.drawable.ic_play_24dp) { pfBackStack.add(PFNav.Playback) }
        Setting(R.string.archive_storage, R.string.archive_storage_summary, R.drawable.ic_download) { pfBackStack.add(PFNav.NetworkStorage) }
        Setting(R.string.archive_library_automation, R.string.archive_automation_summary, R.drawable.ic_subscriptions) { pfBackStack.add(PFNav.Automation) }
        Setting(R.string.archive_appearance, R.string.archive_appearance_summary, R.drawable.ic_appearance) { pfBackStack.add(PFNav.Interface) }
        Setting(R.string.archive_providers, R.string.archive_provider_summary, R.drawable.archive_explore) { pfBackStack.add(PFNav.Providers) }
        Setting(R.string.archive_sync_backup, R.string.archive_sync_summary, R.drawable.ic_storage) { pfBackStack.add(PFNav.Sync) }
        Setting(R.string.notification_pref_fragment, 0, R.drawable.ic_notifications) { pfBackStack.add(PFNav.Notification) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Setting(R.string.about_pref, 0, R.drawable.ic_info) { pfBackStack.add(PFNav.About) }
        Setting(R.string.documentation_support, 0, R.drawable.ic_questionmark) { openInSystemDefault(githubAddress) }
        Setting(R.string.archive_help_diagnostics, 0, R.drawable.ic_bug) { context.startActivity(Intent(context, BugReportActivity::class.java)) }
        Setting(R.string.whats_new, 0, R.drawable.ic_questionmark) { openInSystemDefault("${githubAddress}/blob/main/changelog.md") }
        Setting(R.string.pref_contribute, 0, R.drawable.ic_contribute) { openInSystemDefault(githubAddress) }
        if (search.isBlank()) {
            val notice = remember { getCopyrightNoticeText() }
            if(notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
fun AboutScreen() {
    val context by rememberUpdatedState(LocalContext.current)
    
    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    Column(modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp).background(MaterialTheme.colorScheme.surface)) {
        Image(painter = painterResource(R.drawable.teaser), contentDescription = "")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 5.dp, bottom = 5.dp)) {
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_star), contentDescription = "", tint = textColor)
            Column(Modifier.padding(start = 10.dp).clickable {
                val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val versionText = "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                val clip = ClipData.newPlainText(context.getString(R.string.bug_report_title), versionText)
                clipboard.setPrimaryClip(clip)
                if (Build.VERSION.SDK_INT <= 32) Logt(TAG, context.getString(R.string.copied_to_clipboard))
            }) {
                Text(stringResource(R.string.podcini_version), color = textColor, style = CustomTextStyles.titleCustom, fontWeight = FontWeight.Bold)
                Text(BuildConfig.VERSION_NAME, color = textColor)
            }
        }
        IconTitleSummaryActionRow(R.drawable.ic_questionmark, R.string.online_help, R.string.online_help_sum) { openInSystemDefault(githubAddress) }
        IconTitleSummaryActionRow(R.drawable.ic_info, R.string.privacy_policy, R.string.privacy_policy) { openInSystemDefault("${githubAddress}/blob/main/PrivacyPolicy.md") }
        IconTitleSummaryActionRow(R.drawable.ic_info, R.string.licenses, R.string.licenses_summary) { pfBackStack.add(PFNav.Licenses) }
        IconTitleSummaryActionRow(R.drawable.baseline_mail_outline_24, R.string.email_developer, R.string.email_sum) {
            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(developerEmail))
                putExtra(Intent.EXTRA_SUBJECT, "Regarding Podcini")
                type = "message/rfc822"
            }
            if (emailIntent.resolveActivity(context.packageManager) != null) context.startActivity(emailIntent)
            else Logt(TAG, context.getString(R.string.need_email_client))
        }
    }
}

@Composable
fun LicensesScreen() {
    val context by rememberUpdatedState(LocalContext.current)
    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    class LicenseItem(val title: String, val subtitle: String, val licenseUrl: String, val licenseTextFile: String)
    val licenses = remember { mutableStateListOf<LicenseItem>() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            licenses.clear()
            val stream = context.assets.open("licenses.xml")
            val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val libraryList = docBuilder.parse(stream).getElementsByTagName("library")
            for (i in 0 until libraryList.length) {
                val lib = libraryList.item(i).attributes
                licenses.add(LicenseItem(lib.getNamedItem("name").textContent, "By ${lib.getNamedItem("author").textContent}, ${lib.getNamedItem("license").textContent} license", lib.getNamedItem("website").textContent, lib.getNamedItem("licenseText").textContent))
            }
        }
    }
    val lazyListState = rememberLazyListState()
    
    val showLicense = remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf("") }
    ConfirmDialog(titleRes = 0, message = licenseText, showLicense) {}
    var showDialog by remember { mutableStateOf(false) }
    var curLicenseIndex by remember { mutableIntStateOf(-1) }
    if (showDialog) CommonPopupCard(onDismiss = { showDialog = false }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(licenses[curLicenseIndex].title, color = textColor, style = CustomTextStyles.titleCustom, fontWeight = FontWeight.Bold)
            Row {
                Button(onClick = { openInSystemDefault(licenses[curLicenseIndex].licenseUrl) }) { Text("View website") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    try {
                        val reader = BufferedReader(InputStreamReader(context.assets.open(licenses[curLicenseIndex].licenseTextFile), "UTF-8"))
                        val sb = StringBuilder()
                        var line = ""
                        while ((reader.readLine()?.also { line = it }) != null) sb.append(line).append("\n")
                        licenseText = sb.toString()
                        showLicense.value = true
                    } catch (e: IOException) { Logs(TAG, e) }
                    //                            showLicenseText(licenses[curLicenseIndex].licenseTextFile)
                }) { Text("View license") }
            }
        }
    }
    LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp).background(MaterialTheme.colorScheme.surface), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(licenses) { index, item ->
            Column(Modifier.clickable {
                curLicenseIndex = index
                showDialog = true
            }) {
                Text(item.title, color = textColor, style = CustomTextStyles.titleCustom, fontWeight = FontWeight.Bold)
                Text(item.subtitle, color = textColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun NotificationPrefScreen() {
    val context by rememberUpdatedState(LocalContext.current)
    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    val intent = Intent()
    intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    context.startActivity(intent)
}
