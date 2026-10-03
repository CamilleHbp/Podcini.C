package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.utils.localizedString
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import androidx.compose.material3.IconButton
import ac.mdiq.podcini.ui.compose.ArchiveEmpty
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.download.EpisodeAdrDLManager
import ac.mdiq.podcini.sourcing.searcher.CombinedSearcher
import ac.mdiq.podcini.sourcing.feed.FeedBuilder
import ac.mdiq.podcini.sourcing.searcher.FeedUrlNotFoundException
import ac.mdiq.podcini.sourcing.searcher.PodcastSearcherRegistry
import ac.mdiq.podcini.sourcing.feed.subscribe
import ac.mdiq.podcini.utils.NetworkUtils.getFinalRedirectedUrl
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.shared.prepareUrl
import ac.mdiq.podcini.sourcing.EPISODE_BATCH_SIZE
import ac.mdiq.podcini.sourcing.SourceGatewayClient
import ac.mdiq.podcini.sourcing.clientBySearcher
import ac.mdiq.podcini.sourcing.isExtFeed
import ac.mdiq.podcini.sourcing.sourceClients
import ac.mdiq.podcini.storage.database.allFeeds
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.getEpisodesCount
import ac.mdiq.podcini.storage.database.getFeed
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.Feed.Companion.EPISODES_LIMIT
import ac.mdiq.podcini.storage.model.ShareLog
import ac.mdiq.podcini.storage.model.SubscriptionLog
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.feedLogsMap
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.takeCodePoints
import ac.mdiq.podcini.storage.model.tmpQueue
import ac.mdiq.podcini.storage.model.toFeed
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.storage.specs.Rating.Companion.fromCode
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.actions.SwipeActions
import ac.mdiq.podcini.ui.compose.CustomTextStyles
import ac.mdiq.podcini.ui.compose.EpisodeLazyColumn
import ac.mdiq.podcini.ui.compose.EpisodeScreen
import ac.mdiq.podcini.ui.compose.EpisodeSortDialog
import ac.mdiq.podcini.ui.compose.EpisodeListInfoBar
import ac.mdiq.podcini.ui.compose.NumberEditor
import ac.mdiq.podcini.ui.compose.episodeForInfo
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.ui.utils.HtmlToPlainText
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.NetworkUtils.imageLoader
import ac.mdiq.podcini.utils.formatAbbrev
import ac.mdiq.podcini.utils.timeIt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class OnlineFeedVM(url: String = "", source: String = "", shared: Boolean = false): ViewModel() {
    var feedSource: String = ""
    internal var feedUrl: String = ""
    internal var isShared: Boolean = false

    internal var urlToLog: String = ""
    internal var showTabsDialog by mutableStateOf(false)

    internal var showEpisodes by mutableStateOf(false)
    internal var showFeedDisplay by mutableStateOf(false)
    internal var showProgress by mutableStateOf(true)
    internal var autoDownloadChecked by mutableStateOf(false)
    internal var limitEpisodesCount by mutableIntStateOf(0)
    internal var enableSubscribe by mutableStateOf(true)
    internal var enableEpisodes by mutableStateOf(true)
    internal var subButTextRes by mutableIntStateOf(R.string.subscribe_label)
    var addError by mutableStateOf(false)

    var numEpisodes by mutableIntStateOf(0)

    internal var preparedUrl = ""

    internal var feedOptions: List<String?> = listOf()


    var episodeSortOrder by mutableStateOf(EpisodeSortOrder.DATE_DESC)

    internal val episodes = mutableStateListOf<Episode>()

    internal var feedId by mutableLongStateOf(0L)
    var updatedFeedUrl by mutableStateOf("")
    internal var feed by mutableStateOf<Feed?>(null)
    internal var username: String? = null
    internal var password: String? = null

    val subLogs = mutableStateListOf<SubscriptionLog>()

    internal var isPaused = false
    internal var subscribePress = false
    internal var isFeedFoundBySearch = false

    var relatedResults by mutableStateOf<List<FeedSearchResult>>(listOf())

    internal var errorMessage by mutableStateOf("")
    internal var errorDetails by mutableStateOf("")

    var gatewayClient: SourceGatewayClient? = null

    init {
        timeIt("$TAG start of init")
        feedUrl = url
        feedSource = source
        isShared = shared
        preparedUrl = prepareUrl(feedUrl)

        Logd(TAG) { "OnlineFeedVM init feedUrl: $feedUrl feedSource: $feedSource isShared: $isShared" }

        findExisting(preparedUrl)?.apply { feedId = this.id }

        val showError = { message: String?, details: String ->
            errorMessage = message ?: "No message"
            errorDetails = details
        }
        gatewayClient = clientBySearcher(source)

        if (feedUrl.isEmpty()) Loge(TAG, localizedString(R.string.message_feedurl_is_null))
        else {
            Logd(TAG) { "Activity was started with url $feedUrl" }
            showProgress = true
            // Remove subscribeonandroid.com from feed URL in order to subscribe to the actual feed URL
            if (feedUrl.contains("subscribeonandroid.com")) feedUrl = feedUrl.replaceFirst("((www.)?(subscribeonandroid.com/))".toRegex(), "")

            suspend fun handleClientFeeds(): Boolean {
                feedOptions = gatewayClient?.withProvider { it.feedsTitlesAtUrl(url) } ?: listOf()
                val feedOptions_ = feedOptions.filter { it != null && it != "playlists" && it != "shorts" }
                Logd(TAG) { "feedOptions_: ${feedOptions_.size}" }
                when {
                    feedOptions_.size > 1 -> {
                        showTabsDialog = true
                        feedOptions.forEach { Logd(TAG) { "feedOptions: $it" } }
                        return true
                    }
                    feedOptions_.size <= 1 -> {
                        val fipc = gatewayClient?.withProvider { it.buildFeed(url, 0) }
                        if (fipc != null) {
                            var exist = findExisting(preparedUrl)
                            if (exist != null) {
                                setExist(exist, R.string.archive_view_episodes)
                                return true
                            }
                            exist = findExisting(fipc.toFeed())
                            if (exist != null) {
                                setExist(exist, R.string.update_url)
                                updatedFeedUrl = fipc.downloadUrl ?:""
                                Logd(TAG) { "handleClientFeeds updatedFeedUrl: $updatedFeedUrl" }
                                return true
                            }
                            Logd(TAG) { "handleClientFeeds feed exists: $exist ${fipc.title}" }
                            val eList = mutableListOf<EpisodeIPC>()
                            var episodes = gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) } ?: listOf()
                            while (episodes.isNotEmpty()) {
                                eList.addAll(episodes)
                                numEpisodes = eList.size
                                if (limitEpisodesCount in 1..<numEpisodes || numEpisodes > EPISODES_LIMIT || episodes.size < EPISODE_BATCH_SIZE) break
                                Logd(TAG) { "handleClientFeeds Subscribing eList: ${eList.size}" }
                                episodes = gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) } ?: listOf()
                            }
                            fipc.episodes = eList
                            Logd(TAG) { "handleClientFeeds fipc: ${fipc.title} ${fipc.author}" }
                            handleFeed(fipc.toFeed())
                            return true
                        }
                    }
                }
                return false
            }
            viewModelScope.launch(Dispatchers.IO) {
                urlToLog = feedUrl
                if (gatewayClient != null) handleClientFeeds()
                else {
                    val client = sourceClients.find { it.withProvider { p-> p.canHandleUrl(feedUrl) == 1 } == true }
                    Logd(TAG) { "try positive client: ${client != null}" }
                    if (client != null) {
                        gatewayClient = client
                        if (handleClientFeeds()) return@launch
                    }
                    val clients = sourceClients.filter { it.withProvider { p-> p.canHandleUrl(feedUrl) == 0 } == true }
                    Logd(TAG) { "try neutral clients: ${clients.size}" }
                    for (client in clients) {
                        gatewayClient = client
                        if (handleClientFeeds()) return@launch
                    }
                    try {
                        val urlString = PodcastSearcherRegistry.lookupUrl(feedUrl)
                        Logd(TAG) { "lookupUrlAndBuild: urlString: $urlString" }
                        val feedBuilder = FeedBuilder(showError)
                        feedBuilder.buildPodcast(getFinalRedirectedUrl(urlString), username, password) { feed_, _ -> handleFeed(feed_) }
                    } catch (error: FeedUrlNotFoundException) {
                        Logd(TAG) { "lookupUrlAndBuild in error, trying to Retrieve FeedUrl By Search" }
                        var url: String? = null
                        val searcher = CombinedSearcher()
                        val query = "${error.trackName} ${error.artistName}"
                        val results = searcher.search(query)
                        if (results.isEmpty()) return@launch
                        for (result in results) {
                            if (result.feedUrl != null && result.author != null && result.author.equals(error.artistName, ignoreCase = true)
                                && result.title.equals(error.trackName, ignoreCase = true)) {
                                url = result.feedUrl
                                break
                            }
                        }
                        if (url != null) {
                            urlToLog = url
                            Logd(TAG) { "Successfully retrieve feed url: $url" }
                            isFeedFoundBySearch = true
                            val feedBuilder = FeedBuilder(showError)
                            feedBuilder.buildPodcast(getFinalRedirectedUrl(url), username, password) { feed_, _ -> handleFeed(feed_) }
                        } else {
                            showProgress = false
                            Loge(TAG, getAppContext().getString(R.string.null_value_podcast_error))
                        }
                    }
                }
            }
            viewModelScope.launch { snapshotFlow { episodeSortOrder }.collectLatest { episodes.reorderWith(episodeSortOrder) } }
        }
        timeIt("$TAG end of init")
    }

    fun setExist(exist: Feed, textRes: Int) {
        feedId = exist.id
        feed = exist
        numEpisodes = getEpisodesCount(null, feedId)
        showProgress = false
        showFeedDisplay = true
        enableSubscribe = true
        subButTextRes = R.string.search_in_library
    }

    fun findExisting(feed_: Feed?): Feed? {
        if (feed_ == null) return null
        ac.mdiq.podcini.sourcing.feed.PodcastLibrary.existing(feed_.downloadUrl)?.let { return it }
        val identifier = feed_.identifier?.takeIf { it.isNotBlank() } ?: return null
        // Publisher name, host and description alone are not a podcast identity.
        return allFeeds.firstOrNull { it.identifier == identifier && it.identifyingValue == feed_.identifyingValue }
    }

    fun findExisting(url: String): Feed? = ac.mdiq.podcini.sourcing.feed.PodcastLibrary.existing(url)


    internal fun handleFeed(feed_: Feed) {
        Logd(TAG) { "handleFeed feed_.title: ${feed_.title} ${feed_.author}" }
        feed = feed_
//        findExisting(preparedUrl, feed)?.apply { feedId = this.id }

        val results = mutableSetOf<SubscriptionLog>()
        if (!feed_.title.isNullOrBlank()) feedLogsMap?.get(feed_.title)?.apply { results.add(this) }
        if (!feed_.downloadUrl.isNullOrBlank()) feedLogsMap?.get(feed_.downloadUrl)?.apply { results.add(this) }
        feed_.description?.takeCodePoints(100).takeIf { !it.isNullOrBlank() }.apply { feedLogsMap?.get(this)?.apply { results.add(this) } }
        if (results.isNotEmpty()) {
            subLogs.clear()
            subLogs.addAll(results)
        }

        numEpisodes = feed_.episodes.size
        if (isShared) {
            val log = realm.query(ShareLog::class).query("url == $0", urlToLog).first().find()
            if (log != null) upsertBlk(log) {
                it.title = feed_.title
                it.author = feed_.author
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val author = feed?.author?.takeIf { it.isNotBlank() } ?: return@launch
            val fl = CombinedSearcher().searchOutcome("$author podcasts").results
            withContext(Dispatchers.Main) { if (fl.isNotEmpty()) relatedResults = fl }
        }
        showProgress = false
        showFeedDisplay = true
        if (isFeedFoundBySearch) Loge(TAG, getAppContext().getString(R.string.no_feed_url_podcast_found_by_search))
        handleSubscribeStatus()
    }

    internal fun showEpisodes() {
        if (feed == null) return
        if (episodes.isEmpty()) {
            episodes.addAll(feed!!.episodes)

            Logd(TAG) { "showEpisodes ${episodes.size}" }
            if (episodes.isEmpty()) return
//            episodes.sortByDescending { it.pubDate }
            for (episode in episodes) {
                episode.id = getEntityId()
                episode.origFeedlink = feed!!.link
                episode.origFeeddownloadUrl = feed!!.downloadUrl
                episode.origFeedTitle = feed!!.title
            }
            episodes.reorderWith(episodeSortOrder)
        }
        showEpisodes = true
    }

    fun addToLibrary() {
        val preview = feed ?: return
        if (!enableSubscribe || feedId != 0L) return
        enableSubscribe = false
        addError = false
        viewModelScope.launch {
            try {
                if (limitEpisodesCount > 0) preview.limitEpisodesCount = limitEpisodesCount
                feedId = ac.mdiq.podcini.sourcing.feed.PodcastLibrary.save(preview)
                subscribePress = true
                if (isShared) withContext(Dispatchers.IO) {
                    realm.query(ShareLog::class, "url == $0", feedUrl).first().find()?.let { log ->
                        upsert(log) { it.status = ShareLog.Status.SUCCESS.code }
                    }
                }
                handleSubscribeStatus()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { addError = true }
            finally { enableSubscribe = true; enableEpisodes = true }
        }
    }

    internal fun handleSubscribeStatus() {
        if (preparedUrl.isBlank()) return

        when {
            EpisodeAdrDLManager.manager.isDownloading(preparedUrl) -> {
                Logd(TAG) { "handleUpdatedFeedStatus isDownloading" }
                enableSubscribe = false
                subButTextRes = R.string.subscribe_label
            }
            feedId != 0L -> {
                Logd(TAG) { "handleUpdatedFeedStatus feedId != 0L" }
                enableSubscribe = true
                subButTextRes = R.string.search_in_library
                if (subscribePress) {
                    subscribePress = false
                    runOnIOScope {
                        val feedExisting = getFeed(feedId, true)?: return@runOnIOScope
                        Logd(TAG) { "handleUpdatedFeedStatus ${feedExisting.title} ${feedExisting.author}" }
                        if (appPrefsFlow!!.value.enableAutoDl && !isExtFeed(feedExisting)) feedExisting.autoDownload = autoDownloadChecked
                        if (!username.isNullOrBlank()) {
                            feedExisting.username = username
                            feedExisting.password = password
                        }
                        upsert(feedExisting) {}
                    }
                }
            }
            else -> {
                Logd(TAG) { "handleUpdatedFeedStatus else" }
                enableSubscribe = true
                subButTextRes = R.string.subscribe_label
            }
        }
    }

    override fun onCleared() {
        Logd(TAG) { "VM onCleared" }
        episodes.clear()
    }
}

@ExperimentalMaterial3Api
@Composable
fun OnlineFeedScreen(url: String = "", source: String = "", shared: Boolean = false) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerController = LocalDrawerController.current
    val context by rememberUpdatedState(LocalContext.current)
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    var optionsExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var descriptionOverflows by remember { mutableStateOf(false) }

    val vm: OnlineFeedVM = viewModel(key = url, factory = viewModelFactory { initializer { OnlineFeedVM(url, source, shared) } })

    var swipeActions by remember { mutableStateOf(SwipeActions(TAG, false)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> Logd(TAG) { "feedUrl: ${vm.feedUrl}" }
                Lifecycle.Event.ON_START -> {
                    vm.isPaused = false
                }
                Lifecycle.Event.ON_STOP -> vm.isPaused = true
                Lifecycle.Event.ON_DESTROY -> {}
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(vm.showEpisodes, episodeForInfo) {
        if (vm.showEpisodes || episodeForInfo != null) handleBackSubScreens.add(TAG)
        else handleBackSubScreens.remove(TAG)
        onDispose { handleBackSubScreens.remove(TAG) }
    }

    BackHandler(enabled = handleBackSubScreens.contains(TAG)) {
        when {
            episodeForInfo != null -> episodeForInfo = null
            else -> vm.showEpisodes = false
        }
    }

    var showSortDialog by remember { mutableStateOf(false) }
    if (showSortDialog) EpisodeSortDialog(initOrder = vm.episodeSortOrder, feed = vm.feed, onDismiss = { showSortDialog = false }) { order -> vm.episodeSortOrder = order ?: EpisodeSortOrder.DATE_DESC }

    @Composable
    fun ShowTabsDialog(onDismiss: () -> Unit) {
        val ytTabsMap = remember { mutableStateMapOf<Int, String>() }
        AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { onDismiss() },
            title = { Text(stringResource(R.string.choose_tab), style = CustomTextStyles.titleCustom) },
            text = {
                Column {
                    val selectedId = remember { mutableStateOf<Int?>(null) }
                    for (i in vm.feedOptions.indices) {
                        val urlEnd = vm.feedOptions[i]
                        if (!urlEnd.isNullOrBlank() && urlEnd != "playlists" && urlEnd != "shorts") Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 30.dp)) {
                                var checked by remember { mutableStateOf(false) }
//                                val isChecked = ytTabsMap.contains(i)   // TODO: better enable multi-select
                                Checkbox(checked = selectedId.value == i, onCheckedChange = {
                                    selectedId.value = if (selectedId.value == i) null else i
                                    checked = it
                                    if (checked) ytTabsMap[i] = urlEnd else ytTabsMap.remove(i)
                                })
                                Text(text = urlEnd, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        // TODO: ytTabsMap doesn't handle multiple keys
                        for (i in ytTabsMap.keys) {
                            Logd(TAG) { "Subscribing $i ${vm.feedOptions[i]} ${ytTabsMap[i]}" }
                            val endUrl = ytTabsMap[i] ?: continue
                            val fipc = vm.gatewayClient?.withProvider { it.buildFeed(url, i) }
                            if (fipc != null) {
                                fipc.title = "${fipc.title}: $endUrl"
                                Logd(TAG) { "url: $url" }
                                Logd(TAG) { "preparedUrl: ${vm.preparedUrl}" }
                                Logd(TAG) { "fipc.title: ${fipc.title} ${fipc.downloadUrl}" }
                                var exist = vm.findExisting(url)
                                if (exist != null) {
                                    vm.setExist(exist, R.string.archive_view_episodes)
                                    return@launch
                                }
                                exist = vm.findExisting(fipc.toFeed())
                                if (exist != null) {
                                    vm.setExist(exist, R.string.update_url)
                                    vm.updatedFeedUrl = fipc.downloadUrl ?:""
                                    Logd(TAG) { "updatedFeedUrl: ${vm.updatedFeedUrl}" }
                                    return@launch
                                }
                                val eList = mutableListOf<EpisodeIPC>()
                                var episodes = vm.gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) }?: listOf()
                                while (episodes.isNotEmpty()) {
                                    eList.addAll(episodes)
                                    vm.numEpisodes = eList.size
                                    if (vm.limitEpisodesCount in 1..vm.numEpisodes || vm.numEpisodes > EPISODES_LIMIT || episodes.size < EPISODE_BATCH_SIZE) break
                                    Logd(TAG) { "Subscribing eList: ${eList.size}" }
                                    episodes = vm.gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) }?: listOf()
                                }
                                fipc.episodes = eList
                                vm.handleFeed(fipc.toFeed())
                            } else Loge(TAG, localizedString(R.string.message_subscribe_feed_failed))
                        }
                    }
                    onDismiss()
                }) { Text(text = stringResource(R.string.confirm_label)) }
            },
            dismissButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel_label)) } }
        )
    }
    if (vm.showTabsDialog) ShowTabsDialog(onDismiss = { vm.showTabsDialog = false })

    LaunchedEffect(vm.errorMessage) {
        if (vm.errorMessage.isNotBlank()) {
            vm.showProgress = false
            Loge(TAG, localizedString(R.string.message_n, (vm.errorMessage).toString(), (vm.errorDetails).toString()))
        }
    }

    swipeActions.ActionOptionsDialog()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            Box {
                TopAppBar(title = {  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(R.string.search_podcast_details), modifier = Modifier.weight(1f))
                    if (vm.showEpisodes && vm.episodes.isNotEmpty()) Icon(imageVector = ImageVector.vectorResource(R.drawable.arrows_sort), contentDescription = stringResource(R.string.archive_sort), modifier = Modifier.padding(start = 7.dp).clickable { showSortDialog = true })
                } },
                    navigationIcon = {
                        IconButton(onClick = { if (vm.showEpisodes) vm.showEpisodes = false else navBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.archive_back))
                        }
                    })
                HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }) { innerPadding ->
            if (vm.feed == null && vm.showProgress) Column(Modifier.padding(innerPadding).fillMaxSize().padding(24.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.archive_loading_source), modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyLarge)
            } else if (vm.feed == null && vm.errorMessage.isNotBlank()) Box(Modifier.padding(innerPadding)) {
                ArchiveEmpty(R.string.archive_source_failed, R.string.archive_source_failed_body, R.string.archive_back) { navBack() }
            } else if (vm.showEpisodes) Column(modifier = Modifier.padding(innerPadding).fillMaxSize().padding(start = 5.dp, end = 5.dp).background(MaterialTheme.colorScheme.surface)) {
                EpisodeListInfoBar(vm.episodes, pluralStringResource(R.plurals.search_episode_count, vm.episodes.size, vm.episodes.size), swipeActions, showRandom = false)
                EpisodeLazyColumn(vm.episodes, isExternal = true, swipeActions = swipeActions, actionButtonCB = { e, type -> if (type in listOf(ButtonTypes.PLAY, ButtonTypes.PLAY_LOCAL, ButtonTypes.STREAM)) ac.mdiq.podcini.storage.database.replaceListeningQueue(vm.episodes, e.id, e.feed?.title.orEmpty()) })
            } else Column(modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp).background(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ac.mdiq.podcini.ui.compose.PodcastArtwork(vm.feed?.images?.firstOrNull()?.href, Modifier.size(80.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(vm.feed?.title ?: stringResource(R.string.archive_no_title), style = MaterialTheme.typography.titleLarge)
                            Text(vm.feed?.author.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (vm.showFeedDisplay) Button(enabled = vm.enableSubscribe, onClick = {
                            if (vm.feedId != 0L) {
                                if (vm.isShared) {
                                    val log = realm.query(ShareLog::class).query("url == $0", vm.feedUrl).first().find()
                                    if (log != null) upsertBlk(log) { it.status = ShareLog.Status.EXISTING.code }
                                }
                                if (vm.updatedFeedUrl.isNotBlank() && vm.feed != null) upsertBlk(vm.feed!!) { it.downloadUrl = vm.updatedFeedUrl }
                                navTo(FeedDetails(feedId = vm.feedId, modeName = FeedScreenMode.List.name))
                            } else {
                                vm.addToLibrary()
                            }
                        }) { Text(stringResource(if (!vm.enableSubscribe) R.string.search_adding else vm.subButTextRes)) }

                        when {
                            vm.showEpisodes -> Button(onClick = { vm.showEpisodes = false }) { Text(stringResource(R.string.feed)) }
                            vm.enableEpisodes && vm.feed != null && vm.numEpisodes > 0 -> TextButton(onClick = { vm.showEpisodes() }) { Text(stringResource(R.string.archive_view_episodes)) }
                            else -> {}
                        }

                    }
                    Text(stringResource(R.string.search_membership_hint), modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.addError) Text(stringResource(R.string.search_add_failed), modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                }
                if (vm.feedId == 0L) TextButton(onClick = { optionsExpanded = !optionsExpanded }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.search_podcast_options)) }
                if (optionsExpanded && vm.feedId == 0L) Column(Modifier.padding(vertical = 8.dp)) {
                    //                    TODO: add alternate_urls_spinner
                    if (vm.feedId == 0L) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.limit_episodes_to), modifier = Modifier.weight(0.5f))
                        NumberEditor(vm.limitEpisodesCount, label = stringResource(R.string.ui_no_limit), nz = false, instant = false, modifier = Modifier.weight(0.5f)) {
                            Logd(TAG) { "limitEpisodesCount: $it" }
                            vm.limitEpisodesCount = it
                        }
                    }
                    val isAudoDL = remember(vm.feed) { vm.feed?.type in listOf(FeedType.RSS.name, FeedType.ATOM.name) }
                    if (appPrefs.enableAutoDl && isAudoDL) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.autoDownloadChecked, onCheckedChange = { vm.autoDownloadChecked = it })
                        Text(text = stringResource(R.string.include_in_auto_downloads), style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)) {
                        if (vm.subLogs.isNotEmpty()) {
                            Text(stringResource(R.string.feed_likely_removed), color = MaterialTheme.colorScheme.primary, style = CustomTextStyles.titleCustom, modifier = Modifier.padding(start = 5.dp))
                            for (sLog in vm.subLogs) {
                                Text(sLog.comment, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                                val ratingRes = remember(sLog.id) { fromCode(sLog.rating).res }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp)) {
                                    Text(stringResource(R.string.rating_label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 5.dp))
                                    Icon(imageVector = ImageVector.vectorResource(ratingRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = null)
                                }
                                if (!sLog.description.isNullOrBlank()) Text(sLog.description ?: "", color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                                Text(sLog.url ?: stringResource(R.string.ui_unavailable), color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                                val cancelDate = remember(sLog.id) { formatAbbrev(sLog.cancelDate) }
                                Text(stringResource(R.string.removed_on) + ": " + cancelDate, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 10.dp))
                            }
                        }
                        if (!vm.feed?.medium.isNullOrBlank()) Text(stringResource(R.string.medium) + ": " + vm.feed!!.medium!!, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        if (vm.feed?.aiContent == true) Text(stringResource(R.string.is_ai_content), color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        Text(pluralStringResource(R.plurals.search_episode_count, vm.numEpisodes, vm.numEpisodes), color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
                        Text(stringResource(R.string.description_label), color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                        Text(HtmlToPlainText.getPlainText(vm.feed?.description ?: ""), color = textColor, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            onTextLayout = { if (!descriptionExpanded) descriptionOverflows = it.hasVisualOverflow })
                        if (descriptionOverflows || descriptionExpanded) TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                            Text(stringResource(if (descriptionExpanded) R.string.search_read_less else R.string.search_read_more))
                        }
                        if (!vm.feed?.episodes.isNullOrEmpty()) {
                            Text(stringResource(R.string.recent_episode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                            TextButton(onClick = { vm.showEpisodes() }) { Text(vm.feed?.episodes[0]?.title ?: "", style = MaterialTheme.typography.bodyMedium) }
                        }
                        if (vm.relatedResults.isNotEmpty()) {
                            TextButton(onClick = { openEverywhereSearch("${vm.feed?.author.orEmpty()} podcasts", ac.mdiq.podcini.sourcing.searcher.SearchScope.Podcasts) }) {
                                Text(stringResource(R.string.search_related_podcasts), style = MaterialTheme.typography.titleMedium)
                            }
                            LazyRow(state = rememberLazyListState(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(vm.relatedResults) { result ->
                                    Column(Modifier.width(136.dp).clickable { result.feedUrl?.let { navTo(OnlineFeed(it, result.source)) } }.padding(4.dp)) {
                                        ac.mdiq.podcini.ui.compose.PodcastArtwork(result.imageUrl, Modifier.size(96.dp))
                                        Text(result.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { sourceExpanded = !sourceExpanded }) { Text(stringResource(R.string.search_source_details)) }
                        if (sourceExpanded) {
                            val info = remember(vm.feed) { if (vm.feed == null) "" else "${vm.feed!!.langSet.joinToString(" ")} ${vm.feed!!.type.orEmpty()} ${vm.feed!!.lastUpdate.orEmpty()}" }
                            Text(info, style = MaterialTheme.typography.bodySmall)
                            Text(vm.feed?.link ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                            Text(vm.feed?.downloadUrl ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (vm.showProgress && vm.feed != null) Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                CircularProgressIndicator(strokeWidth = 10.dp, color = textColor, modifier = Modifier.size(50.dp).align(Alignment.Center))
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!)
    }
}

private val TAG: String = Screens.OnlineFeed.name
