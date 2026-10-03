package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.storage.model.displayName
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.download.DownloadStatus
import ac.mdiq.podcini.sourcing.download.Downloader.Companion.downloadStatesFlow
import ac.mdiq.podcini.sourcing.download.EpisodeAdrDLManager
import ac.mdiq.podcini.utils.NetworkUtils.mobileAllowEpisodeDownload
import ac.mdiq.podcini.utils.NetworkUtils.networkMonitor
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.playback.PlayerStatusSimple
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.clientByEpisode
import ac.mdiq.podcini.storage.database.addRemoteToMiscSyndicate
import ac.mdiq.podcini.storage.database.addToAssQueue
import ac.mdiq.podcini.storage.database.addToQueue
import ac.mdiq.podcini.storage.database.deleteEpisodesWarnLocalRepeat
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.smartRemoveFromQueues
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.MediaType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.utils.durationStringFull
import ac.mdiq.podcini.ui.actions.ActionButton
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.actions.EpisodeAction
import ac.mdiq.podcini.ui.actions.NoAction
import ac.mdiq.podcini.ui.actions.SwipeActions
import ac.mdiq.podcini.ui.screens.FeedDetails
import ac.mdiq.podcini.ui.screens.FeedScreenMode
import ac.mdiq.podcini.ui.screens.handleBackSubScreens
import ac.mdiq.podcini.ui.screens.navTo
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.NetworkUtils.imageLoader
import ac.mdiq.podcini.utils.formatDateTimeFlex
import ac.mdiq.podcini.utils.formatLargeInteger
import ac.mdiq.podcini.utils.formatShortFileSize
import ac.mdiq.podcini.utils.stripDateTimeLines
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "EpisodesVM"

var showSwipeActionsDialog by mutableStateOf(false)

@Composable
fun EpisodeListInfoBar(
    episodes: List<Episode>,
    infoText: String,
    swipeActions: SwipeActions? = null,
    showRandom: Boolean = true,
    playNext: Boolean = false
) {
    if (episodes.isEmpty()) return

    InforBar(swipeActions) {
        Text(infoText, style = MaterialTheme.typography.bodyMedium)
        if (showRandom) {
            Spacer(Modifier.weight(0.1f))
            PlayRandom(episodes, playNext = playNext)
        }
    }
}

@Composable
fun InforBar(swipeActions: SwipeActions?, content: @Composable (RowScope.()->Unit)) {
    Row {
        if (swipeActions != null) {
            Icon(imageVector = ImageVector.vectorResource(swipeActions.left.iconRes), tint = buttonColor, contentDescription = stringResource(R.string.ui_left_action), modifier = Modifier.width(24.dp).height(24.dp).clickable { showSwipeActionsDialog = true })
            Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_arrow_left_alt_24), tint = textColor, contentDescription = stringResource(R.string.ui_left), modifier = Modifier.width(24.dp).height(24.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        content()
        Spacer(modifier = Modifier.weight(1f))
        if (swipeActions != null) {
            Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_arrow_right_alt_24), tint = textColor, contentDescription = stringResource(R.string.ui_right), modifier = Modifier.width(24.dp).height(24.dp))
            Icon(imageVector = ImageVector.vectorResource(swipeActions.right.iconRes), tint = buttonColor, contentDescription = stringResource(R.string.ui_right_action), modifier = Modifier.width(24.dp).height(24.dp).clickable { showSwipeActionsDialog = true })
        }
    }
}

enum class LayoutMode(val code: Int) {
    Normal(0), WideImage(1), FeedTitle(2);
}

enum class StatusRowMode {
    Normal, Comment, Tags, Todos;
}

@Composable
fun EpisodeLazyColumn(episodes: List<Episode>, feed: Feed? = null, isExternal: Boolean = false, curQueue: PlayQueue? = null,
                      lazyListState: LazyListState = rememberLazyListState(), scrollToOnStart: Int = -1,
                      layoutMode: Int = LayoutMode.Normal.code,
                      showCoverImage: Boolean = true, forceFeedImage: Boolean = false,
                      statusRowMode: StatusRowMode = StatusRowMode.Normal,
                      swipeActions: SwipeActions? = null,
                      refreshCB: (()->Unit)? = null, selectModeCB: ((Boolean)->Unit)? = null,
                      showActionButtons: Boolean = true, preferSingleAction: Boolean = false, showHighlights: Boolean = false,
                      actionButtonType: ButtonTypes? = null, actionButtonCB: (suspend (Episode, ButtonTypes)->Unit)? = null,
                      headerContent: (@Composable () -> Unit)? = null) {

    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Episode>() }
    val scope = rememberCoroutineScope()
    var longPressIndex by remember { mutableIntStateOf(-1) }
    val context by rememberUpdatedState(LocalContext.current)
    val activeQueue by actQueueFlow.collectAsStateWithLifecycle()
    
    
    val localTime = remember { nowInMillis() }

    fun multiSelectCB(index: Int, aboveOrBelow: Int): List<Episode> {
        return when (aboveOrBelow) {
            0 -> episodes
            -1 -> if (index < episodes.size) episodes.subList(0, index+1) else episodes
            1 -> if (index < episodes.size) episodes.subList(index, episodes.size) else episodes
            else -> listOf()
        }
    }

    var leftSwipeCB: ((Episode)->Unit)? = null
    var rightSwipeCB: ((Episode)->Unit)? = null
    val leftActionState = remember { mutableStateOf<EpisodeAction>(NoAction()) }
    val rightActionState = remember { mutableStateOf<EpisodeAction>(NoAction()) }
    if (swipeActions != null) {
        leftActionState.value = swipeActions.left
        rightActionState.value = swipeActions.right
        leftSwipeCB = {
            if (leftActionState.value is NoAction) showSwipeActionsDialog = true
            else leftActionState.value.performAction(it)
        }
        rightSwipeCB = {
            if (rightActionState.value is NoAction) showSwipeActionsDialog = true
            else rightActionState.value.performAction(it)
        }
        if (showSwipeActionsDialog) swipeActions.SwipeActionsSettingDialog(onDismiss = { showSwipeActionsDialog = false })
    }

    var showChooseRatingDialog by remember { mutableStateOf(false) }
    var showAddCommentDialog by remember { mutableStateOf(false) }
    var showEditTagsDialog by remember { mutableStateOf(false) }
    var showIgnoreDialog by remember { mutableStateOf(false) }
    var futureState by remember { mutableStateOf(EpisodeState.UNSPECIFIED) }
    var showPlayStateDialog by remember { mutableStateOf(false) }
    var showPutToQueueDialog by remember { mutableStateOf(false) }
    var showShelveDialog by remember { mutableStateOf(false) }
    var showMulticastDialog by remember { mutableStateOf(false) }
    var showEraseDialog by remember { mutableStateOf(false) }
    val clientEpisodes = remember { mutableListOf<Episode>() }
    var showAddEpisodesDialog by remember { mutableStateOf(false) }

    @Composable
    fun OpenDialogs() {
        if (showAddEpisodesDialog) ConfirmAddToFeed(onDismiss = { showAddEpisodesDialog = false }) { toFeed->
            val existing = toFeed.episodes
            for (episode in clientEpisodes) {
                if (existing.firstOrNull { it.identifyingValue == episode.identifyingValue } != null) continue
                Logd(TAG) { "addToFeed adding new episode: ${episode.title}" }
                episode.id = getEntityId()
                episode.feedId = toFeed.id
                upsertBlk(episode) {}
            }
            EventFlow.postStickyEvent(FlowEvent.FeedUpdatingEvent(false))
        }
        if (showChooseRatingDialog) ChooseRatingDialog(selected) { showChooseRatingDialog = false }
        if (showAddCommentDialog) {
            var editCommentText by remember { mutableStateOf(TextFieldValue("") ) }
            CommentEditingDialog(textState = editCommentText, autoSave = false, onTextChange = { editCommentText = it }, onDismiss = { showAddCommentDialog = false },
                onSave = { runOnIOScope { for (e in selected) upsert(e) { it.addComment(editCommentText.text) } } })
        }
        if (showEditTagsDialog) MediaTagsDialog(selected.map { it.id }) { showEditTagsDialog = false }
        if (showPlayStateDialog) PlayStateDialog(selected, onDismiss = { showPlayStateDialog = false }, futureCB = { futureState = it }, ignoreCB = { showIgnoreDialog = true })
        if (showPutToQueueDialog) PutToQueueDialog(selected) { showPutToQueueDialog = false }
        if (showShelveDialog) ShelveDialog(selected) { showShelveDialog = false }
        if (showMulticastDialog) MulticastDialog(selected) { showMulticastDialog = false }

        if (showEraseDialog && feed != null) EraseEpisodesDialog(selected, feed, onDismiss = { showEraseDialog = false })
        if (showIgnoreDialog) IgnoreEpisodesDialog(selected, onDismiss = { showIgnoreDialog = false })
        if (futureState in listOf(EpisodeState.AGAIN, EpisodeState.FOREVER, EpisodeState.LATER)) FutureStateDialog(selected, futureState, onDismiss = { futureState = EpisodeState.UNSPECIFIED })
    }

    OpenDialogs()

    DisposableEffect(episodeForInfo) {
        if (episodeForInfo != null) handleBackSubScreens.add(TAG)
        else handleBackSubScreens.remove(TAG)
        onDispose { handleBackSubScreens.remove(TAG) }
    }
    BackHandler(enabled = handleBackSubScreens.contains(TAG)) {
        Logd(TAG) { "BackHandler" }
        episodeForInfo = null
    }

    var refreshing by remember { mutableStateOf(false)}
    PullToRefreshBox(modifier = Modifier.fillMaxSize(), isRefreshing = refreshing, indicator = {}, onRefresh = {
        refreshing = true
        refreshCB?.invoke()
        refreshing = false
    }) {
//        val rowHeightPx = with(LocalDensity.current) { 56.dp.toPx() }
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
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(episodes.size, scrollToOnStart) {
            val lifecycleState = lifecycleOwner.lifecycle.currentState
            Logd(TAG) { "LaunchedEffect(scrollToOnStart) ${episodes.size} $scrollToOnStart ${lazyListState.firstVisibleItemIndex} $lifecycleState" }
            if (episodes.size > 5 && lifecycleState >= Lifecycle.State.RESUMED && scrollToOnStart >= 0) lazyListState.scrollToItem(scrollToOnStart)
        }

        val swipeVelocityThreshold = 1500f
        val swipeDistanceThreshold = with(LocalDensity.current) { 100.dp.toPx() }
        val useFeedImage = remember(feed?.useEpisodeImage) { feed?.useFeedImage() == true }

        val titleMaxLines = if (layoutMode == LayoutMode.Normal.code) { if (statusRowMode == StatusRowMode.Comment) 1 else 2 } else 3
//        val density = LocalDensity.current
        val imageWidth = if (layoutMode == LayoutMode.WideImage.code) 150.dp else 56.dp
        val imageHeight = if (layoutMode == LayoutMode.WideImage.code) 100.dp else 56.dp

        val downloadStates by downloadStatesFlow.collectAsStateWithLifecycle()
        val player0 by theatres[0].mPlayerFlow.collectAsStateWithLifecycle()
        val player1 by theatres[1].mPlayerFlow.collectAsStateWithLifecycle()
        val statusSimple0 by player0?.statusSimpleFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(PlayerStatusSimple.OTHER) }
        val statusSimple1 by player1?.statusSimpleFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(PlayerStatusSimple.OTHER) }
        val curMedia0 by player0?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
        val curMedia1 by player1?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

        //        Logd(TAG) { "outside of LazyColumn" }
        LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (headerContent != null) item(key = "library-header") { headerContent() }
            items(items = episodes, key = { it.id }) { episode_ ->
                val episode = when (episode_.id) {
                    curMedia0?.id -> curMedia0 ?: episode_
                    curMedia1?.id -> curMedia1 ?: episode_
                    else -> episode_
                }
                val actionButton by remember(episode.id, preferSingleAction) { mutableStateOf(when {
                    preferSingleAction -> ActionButton(episode, feed = feed, preferSingle = preferSingleAction)
                    actionButtonType != null -> ActionButton(episode, feed = feed, typeInit = actionButtonType)
                    else -> ActionButton(episode)
                }) }
                var showAltActionsDialog by remember(episode.id) { mutableStateOf(false) }
                var isSelected by remember(episode.id, selectMode, selected.size) { mutableStateOf( selectMode && episode in selected ) }
                fun toggleSelected(e: Episode) {
                    isSelected = !isSelected
                    if (isSelected) selected.add(e)
                    else selected.remove(e)
                }

                val velocityTracker = remember { VelocityTracker() }
                val offsetX = remember(episode.id) { Animatable(0f) }

                Box(modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { velocityTracker.resetTracking() },
                        onHorizontalDrag = { change, dragAmount ->
//                            Logd(TAG) { "detectHorizontalDragGestures onHorizontalDrag $dragAmount" }
                            if (abs(dragAmount) > 4) {
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                            }
                        },
                        onDragEnd = {
//                            Logd(TAG) { "detectHorizontalDragGestures onDragEnd" }
                            scope.launch {
                                val velocity = velocityTracker.calculateVelocity().x
                                val distance = offsetX.value
//                                Logd(TAG) { "detectHorizontalDragGestures velocity: $velocity distance: $distance" }
                                val shouldSwipe = abs(distance) > swipeDistanceThreshold && abs(velocity) > swipeVelocityThreshold
                                if (shouldSwipe) {
                                    if (distance > 0) rightSwipeCB?.invoke(episode)
                                    else leftSwipeCB?.invoke(episode)
                                }
                                offsetX.animateTo(targetValue = 0f, animationSpec = tween(300))
                            }
                        },
                    )
                }.offset { IntOffset(offsetX.value.roundToInt(), 0) }) {
                    ArchiveEpisodeRow(
                        episode = episode, button = actionButton,
                        playing = (curMedia0?.id == episode.id && statusSimple0 == PlayerStatusSimple.PLAYING) || (curMedia1?.id == episode.id && statusSimple1 == PlayerStatusSimple.PLAYING),
                        current = curMedia0?.id == episode.id || curMedia1?.id == episode.id,
                        selected = isSelected, selecting = selectMode, isExternal = isExternal,
                        statusMode = statusRowMode, showActions = showActionButtons, showHighlights = showHighlights,
                        downloadProgress = downloadStates[episode.downloadUrl]?.activeProgress,
                        onOpen = { if (selectMode) toggleSelected(episode) else episodeForInfo = episode },
                        onSelect = {
                            selectMode = true
                            selectModeCB?.invoke(true)
                            if (episode !in selected) selected.add(episode)
                            longPressIndex = episodes.indexOfFirst { it.id == episode.id }
                        },
                        onAction = { item, type ->
                            if (actionButtonCB != null) actionButtonCB(item, type)
                            else if (curQueue == null) ac.mdiq.podcini.storage.database.replaceListeningQueue(episodes, item.id, item.feed?.title.orEmpty())
                        }
                    )
                }
            }
        }
        if (selectMode) {
            Row(modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.archive_selected_count, selected.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                var selectionMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { selectionMenu = true }) { Text(stringResource(R.string.archive_select)) }
                    DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                        listOf(-1 to R.string.archive_select_above, 1 to R.string.archive_select_below, 0 to R.string.archive_select_all).forEach { (direction, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                selected.clear()
                                val custom = multiSelectCB(longPressIndex, direction)
                                if(custom.isNotEmpty()) selected.addAll(custom)
                                else selected.addAll(when(direction) { -1 -> episodes.take((longPressIndex + 1).coerceAtLeast(0)); 1 -> episodes.drop(longPressIndex.coerceAtLeast(0)); else -> episodes })
                                selectionMenu = false
                            })
                        }
                    }
                }
                TextButton(onClick = { selected.clear(); selectMode = false; selectModeCB?.invoke(false) }) { Text(stringResource(R.string.archive_selection_done)) }
                @Composable
                fun EpisodeSpeedDial(modifier: Modifier = Modifier) {
                    var isExpanded by remember { mutableStateOf(false) }
                    val bgColor = MaterialTheme.colorScheme.tertiaryContainer
                    val fgColor = remember { complementaryColorOf(bgColor) }
                    fun onSelected() {
                        isExpanded = false
                        selectModeCB?.invoke(selectMode)
                        selectMode = false
                    }
                    val options = mutableListOf<@Composable () -> Unit>(
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            showPlayStateDialog = true
                            onSelected()
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_mark_played), contentDescription = stringResource(R.string.ui_play_status))
                            Text(stringResource(id = R.string.set_play_state_label)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            showChooseRatingDialog = true
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_star), contentDescription = stringResource(R.string.ui_rating))
                            Text(stringResource(id = R.string.set_rating_label)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            showEditTagsDialog = true
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.baseline_label_24), contentDescription = stringResource(R.string.ui_edit_tags))
                            Text(stringResource(id = R.string.edit_tags)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            showAddCommentDialog = true
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.baseline_comment_24), contentDescription = null)
                            Text(stringResource(id = R.string.add_comments)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            if (mobileAllowEpisodeDownload || !networkMonitor.isNetworkRestricted) EpisodeAdrDLManager.manager.downloadNow(selected, true)
                            else {
                                commonConfirms.add(CommonConfirmAttrib(
                                    title = context.getString(R.string.confirm_mobile_download_dialog_title),
                                    message = context.getString(if (networkMonitor.isNetworkRestricted && networkMonitor.isVpnOverWifi) R.string.confirm_mobile_download_dialog_message_vpn else R.string.confirm_mobile_download_dialog_message),
                                    confirmRes = R.string.confirm_mobile_download_dialog_download_later,
                                    cancelRes = R.string.cancel_label,
                                    neutralRes = R.string.confirm_mobile_download_dialog_allow_this_time,
                                    onConfirm = { EpisodeAdrDLManager.manager.download(selected) },
                                    onNeutral = { EpisodeAdrDLManager.manager.downloadNow(selected, true) }))
                            }
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_download), contentDescription = stringResource(R.string.download))
                            Text(stringResource(id = R.string.download_label)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            runOnIOScope { selected.forEach { addToAssQueue(listOf(it)) } }
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_play), contentDescription = null)
                            Text(stringResource(id = R.string.add_to_associated_queue)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            runOnIOScope { addToQueue(selected, actQueueFlow.value) }
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_play), contentDescription = null)
                            Text(stringResource(R.string.archive_add_named_queue, activeQueue.displayName)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            showPutToQueueDialog = true
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_play), contentDescription = stringResource(R.string.enqueue))
                            Text(stringResource(id = R.string.add_to_queue)) } },
                        { if (!isExternal) Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            runOnIOScope { for (e in selected) smartRemoveFromQueues(e) }
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_remove), contentDescription = stringResource(R.string.remove_from_cur_queue))
                            Text(stringResource(id = R.string.remove_from_all_queues)) } }
                    )
                    if (selected.isNotEmpty()) {
                        if (curQueue != null && !isExternal) {
                            options.add {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                                    onSelected()
                                    runOnIOScope { for (e in selected) smartRemoveFromQueues(e, listOf(curQueue)) }
                                }) {
                                    Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_remove), contentDescription = stringResource(R.string.remove_from_cur_queue))
                                    Text(stringResource(id = R.string.remove_from_cur_queue))
                                }
                            }
                        }
                        if (!isExternal) options.add {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                                onSelected()
                                runOnIOScope {
                                    realm.write {
                                        val selected_ = query(Episode::class, "id IN $0", selected.map { it.id }.toList()).find()
                                        for (e in selected_) {
                                            for (e1 in selected_) {
                                                if (e.id == e1.id) continue
                                                Logd(TAG) { "set related: ${e.id} ${e1.id}" }
                                                e.related.add(e1)
                                            }
                                        }
                                    }
                                }
                            }) {
                                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_delete), contentDescription = stringResource(R.string.set_related))
                                Text(stringResource(id = R.string.set_related)) }
                        }
                        if (!isExternal) options.add { Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            showShelveDialog = true
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.baseline_shelves_24), contentDescription = stringResource(R.string.ui_shelve))
                            Text(stringResource(id = R.string.shelve_label)) }
                        }
                        if (isExternal)
                            options.add {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                                    onSelected()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        clientEpisodes.clear()
                                        for (e in selected) {
                                            val client = clientByEpisode(e)
                                            if (client != null) {
                                                e.feedType = client.attributes?.feedType
                                                clientEpisodes.add(e)
                                            } else addRemoteToMiscSyndicate(e)
                                        }
                                        if (clientEpisodes.isNotEmpty()) showAddEpisodesDialog = true
                                    }
                                }) {
                                    Icon(Icons.Filled.AddCircle, contentDescription = stringResource(R.string.ui_reserve))
                                    Text(stringResource(id = R.string.reserve_episodes_label))
                                }
                            }
                        if (!isExternal) options.add {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                                onSelected()
                                runOnIOScope {
                                    realm.write {
                                        for (e_ in selected) {
                                            val e = findLatest(e_)
                                            if (e == null || (!e.downloaded && e.feed?.isLocal != true)) continue
                                            val almostEnded = e.hasAlmostEnded()
                                            if (almostEnded) {
                                                if (e.playState < EpisodeState.PLAYED.code) e.setPlayState(EpisodeState.PLAYED)
                                                e.playbackCompletionTime = nowInMillis()
                                            }
                                        }
                                    }
                                    deleteEpisodesWarnLocalRepeat(selected)
                                }
                            }) {
                                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_delete), contentDescription = stringResource(R.string.archive_delete_local))
                                Text(stringResource(id = R.string.delete_episode_label))
                            }
                        }
                        if (feed != null && !isExternal) {
                            options.add {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                                    onSelected()
                                    showEraseDialog = true
                                }) {
                                    Icon(imageVector = ImageVector.vectorResource(id = R.drawable.baseline_delete_forever_24), contentDescription = stringResource(R.string.ui_erase))
                                    Text(stringResource(id = R.string.erase_episodes_label))
                                }
                            }
                        }
                        if (!isExternal) options.add { Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable {
                            onSelected()
                            showMulticastDialog = true
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_share), contentDescription = stringResource(R.string.multicast_to_devices))
                            Text(stringResource(id = R.string.multicast_to_devices)) }
                        }
                    }
                    if (isExpanded) CommonPopupCard(onDismiss = { isExpanded = false }) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { options.forEachIndexed { _, entry -> entry() } }
                    }
                    FloatingActionButton(containerColor = bgColor, contentColor = fgColor, onClick = { isExpanded = !isExpanded }) { Icon(Icons.Filled.Menu, "Menu") }
                }
                EpisodeSpeedDial(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
