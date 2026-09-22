package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.specs.EpisodeFilter
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.actions.SwipeActions
import ac.mdiq.podcini.ui.compose.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun ListenScreen() {
    val libraryContentFlow = remember {
        combine(feedCountFlow, realm.query(Episode::class).count().asFlow()) { feedCount, episodeCount ->
            when {
                feedCount > 0 || episodeCount > 0 -> true
                feedCount == 0 -> false
                else -> null
            }
        }.distinctUntilChanged()
    }
    val hasLibraryContent by libraryContentFlow.collectAsStateWithLifecycle(initialValue = null)
    if (hasLibraryContent != true) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ArchiveTopBar(stringResource(R.string.archive_listen)) {
                IconButton(onClick = { navTo(Settings) }) { Icon(ImageVector.vectorResource(R.drawable.ic_settings), stringResource(R.string.archive_settings)) }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (hasLibraryContent == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                else ArchiveEmpty(
                    title = R.string.archive_empty_listen,
                    message = R.string.archive_first_listen_body,
                    action = R.string.archive_discover_podcasts,
                    icon = R.drawable.archive_explore
                ) { selectPrimary(FindFeeds, resetToRoot = true) }
            }
        }
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filterText by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(EpisodeSortOrder.DATE_DESC.name) }
    var durationFloor by rememberSaveable { mutableIntStateOf(0) }
    var durationCeiling by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    var titleText by rememberSaveable { mutableStateOf("") }
    var filterAndOr by rememberSaveable { mutableStateOf("AND") }
    val filter = remember(filterText, durationFloor, durationCeiling, titleText, filterAndOr) { EpisodeFilter(filterText, andOr = filterAndOr).apply { this.durationFloor = durationFloor; this.durationCeiling = durationCeiling; this.titleText = titleText } }
    val sort = EpisodeSortOrder.valueOf(sortName)
    val hasFilters = filterText.isNotBlank()
    fun clearFilters() {
        filterText = ""
        durationFloor = 0
        durationCeiling = Int.MAX_VALUE
        titleText = ""
        filterAndOr = "AND"
    }
    var showFilters by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val latestFlow = remember(filter, sort) { getEpisodesAsListFlow(filter, sort) }
    val downloadedFlow = remember(filter, sort) { getEpisodesAsListFlow(filter, sort, scopeQuery = "fileUrl != nil") }
    val queueFlow = remember {
        actQueueFlow.map { it.id }.distinctUntilChanged().flatMapLatest { id ->
            realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", id).asFlow().map { it.list.map { entry -> entry.episodeId } }.flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyList())
                else realm.query(Episode::class, "id IN $0", ids).asFlow().map { change ->
                    val indexed = change.list.associateBy { it.id }
                    ids.mapNotNull { indexed[it] }
                }
            }
        }
    }
    val flow = when (tab) { 1 -> queueFlow; 2 -> downloadedFlow; else -> latestFlow }
    val episodes by flow.collectAsStateWithLifecycle(initialValue = null)
    val detailFlow = remember { MutableStateFlow<List<Episode>>(emptyList()) }
    LaunchedEffect(episodes) { detailFlow.value = episodes.orEmpty() }
    val activeQueue by actQueueFlow.collectAsStateWithLifecycle()
    val queues by remember { queuesFlow.map { it.list } }.collectAsStateWithLifecycle(initialValue = emptyList())
    val latestScroll = rememberLazyListState()
    val queueScroll = rememberLazyListState()
    val downloadsScroll = rememberLazyListState()
    val swipeActions = remember(tab) { SwipeActions("Listen_$tab") }
    if (showFilters) EpisodesFilterDialog(filter_ = filter,
        scopeQuery = if (tab == 2) "fileUrl != nil" else "id > 0",
        subtitle = stringResource(if (tab == 2) R.string.archive_downloads else R.string.archive_latest),
        onDismiss = { showFilters = false }) {
        filterText = it.propertySet.joinToString(",")
        durationFloor = it.durationFloor
        durationCeiling = it.durationCeiling
        titleText = it.titleText
        filterAndOr = it.andOr
    }
    if (showSort) EpisodeSortDialog(initOrder = sort, onDismiss = { showSort = false }) { if (it != null) sortName = it.name }
    swipeActions.ActionOptionsDialog()
    DisposableEffect(episodeForInfo) {
        if (episodeForInfo != null) handleBackSubScreens.add("Listen") else handleBackSubScreens.remove("Listen")
        onDispose { handleBackSubScreens.remove("Listen") }
    }
    BackHandler(episodeForInfo != null) { episodeForInfo = null }
    Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ArchiveTopBar(stringResource(R.string.archive_listen)) {
                IconButton(onClick = { navTo(Search) }) { Icon(ImageVector.vectorResource(R.drawable.ic_search), stringResource(R.string.archive_search)) }
                IconButton(onClick = { navTo(Settings) }) { Icon(ImageVector.vectorResource(R.drawable.ic_settings), stringResource(R.string.archive_settings)) }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (androidx.compose.ui.platform.LocalConfiguration.current.fontScale > 1.2f) {
                    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
                        listOf(R.string.archive_latest, R.string.archive_up_next, R.string.archive_downloads).forEachIndexed { index, label ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label), maxLines = 1) })
                        }
                    }
                } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    listOf(R.string.archive_latest, R.string.archive_up_next, R.string.archive_downloads).forEachIndexed { index, label ->
                        SegmentedButton(selected = tab == index, onClick = { tab = index }, shape = SegmentedButtonDefaults.itemShape(index, 3), icon = {}) { Text(stringResource(label), maxLines = 1) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tab == 1) {
                        var pickQueue by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { pickQueue = true }) { Text(activeQueue.name, style = MaterialTheme.typography.titleMedium) }
                            DropdownMenu(expanded = pickQueue, onDismissRequest = { pickQueue = false }) {
                                queues.forEach { queue -> DropdownMenuItem(text = { Text(queue.name) }, onClick = { actQueueFlow.value = queue; pickQueue = false }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.archive_manage_queues)) }, onClick = { pickQueue = false; navTo(Queues(activeQueue.id)) })
                            }
                        }
                    } else Text(stringResource(if (tab == 2) R.string.archive_downloads else R.string.archive_your_episodes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (tab != 1) {
                        if (!episodes.isNullOrEmpty()) IconButton(onClick = { showSort = true }) { Icon(ImageVector.vectorResource(R.drawable.arrows_sort), stringResource(R.string.archive_sort)) }
                        if (!episodes.isNullOrEmpty() || hasFilters) IconButton(onClick = { showFilters = true }) { Icon(ImageVector.vectorResource(R.drawable.ic_filter), stringResource(R.string.archive_filter)) }
                    }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.archive_more)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            if (tab == 1 && !episodes.isNullOrEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.archive_repeat_queue)) }, trailingIcon = { Checkbox(checked = activeQueue.repeatQueue, onCheckedChange = null) }, onClick = { runOnIOScope { upsert(activeQueue) { it.repeatQueue = !it.repeatQueue } }; more = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive_history)) }, onClick = { more = false; navTo(Facets(QuickAccess.History.name)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.statistics_label)) }, onClick = { more = false; navTo(Statistics) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive_manage_queues)) }, onClick = { more = false; navTo(Queues()) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive_more_listening)) }, onClick = { more = false; navTo(Facets()) })
                        }
                    }
                }
                if (tab != 1 && filterText.isNotBlank()) {
                    ArchiveFilterChips(filter, onRemove = { removed -> filterText = filter.propertySet.filterNot { it == removed }.joinToString(",") }, onClear = { clearFilters() })
                }
                when {
                    episodes == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    episodes!!.isEmpty() -> when {
                        tab != 1 && hasFilters -> ArchiveEmpty(R.string.archive_no_filter_matches, R.string.archive_no_filter_matches_body, R.string.archive_clear_filters) { clearFilters() }
                        tab == 1 -> ArchiveEmpty(R.string.archive_empty_queue, R.string.archive_empty_queue_body, R.string.archive_browse_library) { selectPrimary(Library) }
                        tab == 2 -> ArchiveEmpty(R.string.archive_empty_downloads, R.string.archive_empty_downloads_body, R.string.archive_latest) { tab = 0 }
                        else -> ArchiveEmpty(R.string.archive_empty_listen, R.string.archive_empty_listen_body, R.string.archive_find_podcasts) { selectPrimary(FindFeeds, resetToRoot = true) }
                    }
                    else -> EpisodeLazyColumn(episodes!!, curQueue = if (tab == 1) activeQueue else null,
                        lazyListState = when (tab) { 1 -> queueScroll; 2 -> downloadsScroll; else -> latestScroll },
                        swipeActions = swipeActions, actionButtonCB = { episode, type ->
                            if (tab != 1 && type in listOf(ButtonTypes.PLAY, ButtonTypes.PLAY_LOCAL, ButtonTypes.STREAM)) runOnIOScope { queueToVirtual(episode, episodes!!, "Listen.$tab", sort) }
                        })
                }
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = detailFlow, allowOpenFeed = true)
    }
}
