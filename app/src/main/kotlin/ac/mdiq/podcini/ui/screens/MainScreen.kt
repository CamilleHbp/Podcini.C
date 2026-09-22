package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.ui.compose.CommonConfirmDialog
import ac.mdiq.podcini.ui.compose.CommonToast
import ac.mdiq.podcini.ui.compose.LargePoster
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.compose.commonMessage
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import ac.mdiq.podcini.ui.compose.ArchiveNavigation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MainScreen"

var playerMinHeight by mutableIntStateOf(100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(restoreLastScreen: Boolean = true) {
    val windowView = androidx.compose.ui.platform.LocalView.current
    val lightSystemBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    fun syncSystemBarAppearance() {
        val window = (windowView.context as? android.app.Activity)?.window ?: return
        androidx.core.view.WindowCompat.getInsetsController(window, windowView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
    }
    androidx.compose.runtime.SideEffect { syncSystemBarAppearance() }
    DisposableEffect(lifecycleOwner, lightSystemBars) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) syncSystemBarAppearance()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val downloadSnackbar = remember { SnackbarHostState() }
    val downloadDetailsLabel = stringResource(R.string.download_error_details)
    LaunchedEffect(downloadDetailsLabel) {
        EventFlow.events.filterIsInstance<FlowEvent.DownloadMessageEvent>().collectLatest { event ->
            val result = downloadSnackbar.showSnackbar(event.message,
                actionLabel = downloadDetailsLabel, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) {
                psState = if (theatres.any { it.mPlayerFlow.value?.curMediaFlow?.value != null }) PSState.PartiallyExpanded else PSState.Hidden
                navTo(DownloadLogs)
            }
        }
    }
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    val player0 by theatres[0].mPlayerFlow.collectAsStateWithLifecycle()
    val player1 by theatres[1].mPlayerFlow.collectAsStateWithLifecycle()
    val media0 by player0?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val media1 by player1?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val hasMedia = media0 != null || media1 != null
    val destinationState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val paired = LocalConfiguration.current.screenWidthDp >= 840 && hasMedia && backStack.lastOrNull() == Listen && psState != PSState.Expanded
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerController = remember(drawerState) {
        object : DrawerController {
            override fun isOpen() = drawerState.isOpen
            override fun open() { scope.launch { drawerState.open() } }
            override fun close() { scope.launch { drawerState.close() } }
            override fun toggle() { if (isOpen()) close() else open() }
        }
    }
    LaunchedEffect(Unit) {
        ac.mdiq.podcini.playback.ensureAController()
        if (restoreLastScreen && appAttribsFlow!!.value.restoreLastScreen) {
            runCatching { Json.decodeFromString<List<NavKey>>(appAttribsFlow!!.value.backstack) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { restored ->
                    backStack.clear()
                    backStack.addAll(restored.takeLast(10))
                }
        }
        snapshotFlow { backStack.toList() }.debounce(200.milliseconds).collect { stack ->
            val json = Json.encodeToString(stack)
            withContext(Dispatchers.IO) { upsert(appAttribsFlow!!.value) { it.backstack = json } }
        }
    }
    LaunchedEffect(hasMedia) {
        if (hasMedia && psState == PSState.Hidden) psState = PSState.PartiallyExpanded
    }

    CommonToast(onDismiss = {})
    if (commonConfirms.isNotEmpty()) CommonConfirmDialog(commonConfirms[0])
    if (commonMessage != null) LargePoster(commonMessage!!)
    LaunchedEffect(appPrefs.customFolderUnavailable) {
        if (appPrefs.customFolderUnavailable) Loge(TAG, ac.mdiq.podcini.PodciniApp.Companion.getAppContext().getString(R.string.custum_folder_warning))
    }

    CompositionLocalProvider(LocalDrawerController provides drawerController, LocalDrawerState provides drawerState) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = { NavDrawerScreen() }
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxSize()) {
                    if (wide) ArchiveNavigation(rail = true)
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        snackbarHost = { SnackbarHost(downloadSnackbar) },
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            Column {
                                if (hasMedia && psState != PSState.Expanded && !paired) AVPlayerScreen()
                                if (!wide) ArchiveNavigation(rail = false)
                                else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            }
                        }
                    ) { padding ->
                        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                            Box(Modifier.weight(if (paired) 0.56f else 1f).fillMaxHeight()) {
                            destinationState.SaveableStateProvider(primaryDestination().toString()) {
                            NavDisplay(
                                backStack = backStack,
                                onBack = { navBack() },
                                entryProvider = myEntryProvider,
                                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator())
                            )
                            }
                            }
                            if (paired) {
                                androidx.compose.material3.VerticalDivider()
                                Box(Modifier.weight(0.44f).fillMaxHeight()) { AVPlayerScreen(embedded = true) }
                            }
                        }
                    }
                }
                if (hasMedia && psState == PSState.Expanded) {
                    Surface(Modifier.fillMaxSize()) { AVPlayerScreen() }
                    SnackbarHost(downloadSnackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                }
            }
        }
    }
    BackHandler(enabled = handleBackSubScreens.isEmpty() && (drawerState.isOpen || psState == PSState.Expanded || backStack.size > 1)) {
        when {
            drawerState.isOpen -> drawerController.close()
            psState == PSState.Expanded -> psState = PSState.PartiallyExpanded
            else -> navBack()
        }
    }
}
