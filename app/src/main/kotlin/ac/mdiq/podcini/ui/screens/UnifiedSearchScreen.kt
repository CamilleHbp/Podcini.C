package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.playback.PlayerStatusSimple
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.sourcing.feed.PodcastLibrary
import ac.mdiq.podcini.sourcing.searcher.*
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.ui.actions.ActionButton
import ac.mdiq.podcini.ui.compose.*
import ac.mdiq.podcini.ui.screens.prefscreens.PFNav
import ac.mdiq.podcini.ui.screens.prefscreens.pfBackStack
import ac.mdiq.podcini.utils.localizedString
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xilinjia.krdb.query.Sort
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Shared handoff for existing shortcuts and per-screen search buttons. No persisted route changes. */
internal object SearchSession {
    val processToken = java.util.UUID.randomUUID().toString()
    var query = ""
    var scope = SearchScope.All
    var libraryOnly = false
    var revision by mutableIntStateOf(0)
    var focusRequested by mutableStateOf(false)
}

fun requestEverywhereSearch(query: String = "", scope: SearchScope = SearchScope.All) {
    SearchSession.query = query
    SearchSession.scope = scope
    SearchSession.revision++
}

fun openEverywhereSearch(query: String = "", scope: SearchScope = SearchScope.All) {
    requestEverywhereSearch(query, scope)
    navTo(Search)
}

internal data class LocalSearchResults(
    val podcasts: List<Feed> = emptyList(), val episodes: List<Episode> = emptyList(),
    val playlists: List<PlayQueue> = emptyList(), val loading: Boolean = false, val failed: Boolean = false
)

internal enum class OnlineSearchStatus { Idle, Loading, Results, Empty, Failed }
internal data class OnlineSearchResults(
    val status: OnlineSearchStatus = OnlineSearchStatus.Idle,
    val podcasts: List<FeedSearchResult> = emptyList(), val partial: Boolean = false
)

internal class EverywhereSearchVM(private val saved: SavedStateHandle) : ViewModel() {
    var query by mutableStateOf(saved.get<String>("query") ?: SearchSession.query)
        private set
    var scope by mutableStateOf(saved.get<String>("scope")?.let { runCatching { SearchScope.valueOf(it) }.getOrNull() } ?: SearchSession.scope)
        private set
    var libraryOnly by mutableStateOf(saved.get<Boolean>("libraryOnly") ?: SearchSession.libraryOnly)
        private set
    private var acceptedRevision = when {
        saved.get<String>("processToken") == SearchSession.processToken -> saved.get<Int>("revision") ?: -1
        SearchSession.revision == 0 -> 0 // Restore saved state; no fresh request exists in this process.
        else -> -1
    }
    private var retry by mutableIntStateOf(0)
    var local by mutableStateOf(LocalSearchResults())
        private set
    var online by mutableStateOf(OnlineSearchResults())
        private set
    val adding = mutableStateMapOf<String, Boolean>()
    val added = mutableStateMapOf<String, Long>()
    val notices = Channel<Pair<Int, Long?>>(Channel.BUFFERED)
    val libraryFeeds = realm.query(Feed::class, "id > 1000").asFlow().map { it.list.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        saved["processToken"] = SearchSession.processToken
        saved["revision"] = acceptedRevision
        viewModelScope.launch {
            snapshotFlow { query.trim() }.distinctUntilChanged().collectLatest { text ->
                if (text.isBlank() || text.startsWith("http://", true) || text.startsWith("https://", true)) {
                    local = LocalSearchResults()
                    return@collectLatest
                }
                local = LocalSearchResults(loading = true)
                try {
                    val words = searchWords(text)
                    fun predicate(fields: List<String>) = words.indices.joinToString(" AND ") { index ->
                        fields.joinToString(" OR ", "(", ")") { "$it CONTAINS[c] $$index" }
                    }
                    val podcasts = realm.query(Feed::class, "id > 1000 AND (${predicate(listOf("eigenTitle", "customTitle", "author"))})", *words.toTypedArray())
                        .asFlow().map { change -> change.list.filter { !it.isLocal } }
                    val episodes = realm.query(Episode::class, predicate(listOf("title", "shortDescription", "description", "comment", "artist", "origFeedTitle")), *words.toTypedArray())
                        .sort("pubDate", Sort.DESCENDING).asFlow().map { it.list.toList() }
                    val playlists = realm.query(PlayQueue::class, "id != $0", LISTENING_QUEUE_ID).asFlow().map { change ->
                        change.list.filter { matchesSearch(text, it.displayName, it.tags.joinToString(" ")) }
                    }
                    combine(podcasts, episodes, playlists) { p, e, lists -> LocalSearchResults(p, e, lists) }
                        .flowOn(Dispatchers.Default).collect { local = it }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { local = LocalSearchResults(failed = true) }
            }
        }
        viewModelScope.launch {
            snapshotFlow { listOf(query.trim(), scope.name, libraryOnly.toString(), retry.toString()) }.collectLatest { request ->
                online = OnlineSearchResults()
                val text = request[0]
                if (!shouldSearchPodcasts(text, SearchScope.valueOf(request[1]), request[2].toBoolean())) return@collectLatest
                delay(450)
                online = OnlineSearchResults(OnlineSearchStatus.Loading)
                try {
                    val outcome = withTimeout(25_000) { withContext(Dispatchers.IO) { CombinedSearcher().searchOutcome(text) } }
                    online = OnlineSearchResults(
                        when { outcome.failed -> OnlineSearchStatus.Failed; outcome.results.isEmpty() -> OnlineSearchStatus.Empty; else -> OnlineSearchStatus.Results },
                        outcome.results, outcome.failedSources.isNotEmpty() && !outcome.failed
                    )
                } catch (_: TimeoutCancellationException) { online = OnlineSearchResults(OnlineSearchStatus.Failed) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { online = OnlineSearchResults(OnlineSearchStatus.Failed) }
            }
        }
    }

    fun acceptLaunch() {
        if (acceptedRevision == SearchSession.revision) return
        acceptedRevision = SearchSession.revision
        saved["revision"] = acceptedRevision
        changeLibraryOnly(SearchSession.libraryOnly)
        changeQuery(SearchSession.query)
        changeScope(SearchSession.scope)
    }
    fun changeQuery(value: String) { query = value; saved["query"] = value; SearchSession.query = value }
    fun changeScope(value: SearchScope) { scope = value; saved["scope"] = value.name; SearchSession.scope = value }
    fun changeLibraryOnly(value: Boolean) { libraryOnly = value; saved["libraryOnly"] = value; SearchSession.libraryOnly = value }
    fun submit() {
        val text = query.trim()
        if (text.isBlank()) return
        if (text.startsWith("https://", true) || text.startsWith("http://", true)) { navTo(OnlineFeed(url = text)); return }
        retry++
        viewModelScope.launch(Dispatchers.IO) { upsert(appAttribsFlow!!.value) { attributes ->
            attributes.searchHistory.remove(text)
            attributes.searchHistory.add(0, text)
            while (attributes.searchHistory.size > 12) attributes.searchHistory.removeLast()
        } }
    }
    fun retryOnline() { retry++ }
    fun addPodcast(result: FeedSearchResult) {
        val key = podcastUrlKey(result.feedUrl) ?: return
        if (adding[key] == true) return
        adding[key] = true
        viewModelScope.launch {
            try {
                val id = PodcastLibrary.add(result)
                added[key] = id
                notices.send(R.string.search_added to id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notices.send(R.string.search_add_failed to null) }
            finally { adding.remove(key) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSearchScreen() {
    val vm: EverywhereSearchVM = viewModel()
    val revision = SearchSession.revision
    LaunchedEffect(revision) { vm.acceptLaunch() }
    val feeds by vm.libraryFeeds.collectAsStateWithLifecycle()
    val attributes by appAttribsFlow!!.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val focusRequested = SearchSession.focusRequested
    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            // Wait for the destination's input to attach, then consume this explicit entry request.
            withFrameNanos { }
            inputFocus.requestFocus()
            keyboard?.show()
            SearchSession.focusRequested = false
        }
    }
    var menu by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    LaunchedEffect(vm) {
        snapshotFlow { Triple(vm.query, vm.scope, vm.libraryOnly) }.drop(1).collect { state.scrollToItem(0) }
    }
    val local = vm.local
    val online = vm.online
    val player by theatres[0].mPlayerFlow.collectAsStateWithLifecycle()
    val current by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val playback by player?.statusSimpleFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(PlayerStatusSimple.OTHER) }
    val details = remember { MutableStateFlow<List<Episode>>(emptyList()) }
    LaunchedEffect(local.episodes) { details.value = local.episodes }
    BackHandler(episodeForInfo == null && canReturnFromSearch()) { keyboard?.hide(); returnFromSearch() }
    BackHandler(episodeForInfo != null) { episodeForInfo = null }
    LaunchedEffect(vm) {
        for ((message, feedId) in vm.notices) {
            val result = snackbar.showSnackbar(localizedString(message), if (feedId != null) localizedString(R.string.search_view_podcast) else null, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed && feedId != null) navTo(FeedDetails(feedId))
        }
    }
    if (urlDialog) PodcastUrlDialog { urlDialog = false }
    Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(value = vm.query, onValueChange = vm::changeQuery, singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_query_hint), maxLines = 1) },
                        modifier = Modifier.weight(1f).focusRequester(inputFocus).semantics { traversalIndex = -1f }, shape = MaterialTheme.shapes.extraLarge,
                        leadingIcon = { IconButton(onClick = { keyboard?.hide(); if (!returnFromSearch()) selectPrimary(Library) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.archive_back))
                        } },
                        trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.changeQuery("") }) {
                            Icon(Icons.Default.Close, stringResource(R.string.search_clear))
                        } else Icon(Icons.Default.Search, null) },
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.submit(); keyboard?.hide() }))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.archive_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.search_add_url)) }, onClick = { menu = false; urlDialog = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.search_advanced)) }, onClick = { menu = false; setLegacySearchTerms(vm.query); navTo(AdvancedSearch) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.search_directories)) }, onClick = { menu = false; searchFeedsOnline(query = vm.query); navTo(PodcastDirectories) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.search_import_library)) }, onClick = {
                                menu = false; pfBackStack.clear(); pfBackStack.add(PFNav.Portal); pfBackStack.add(PFNav.ImportExport); navTo(Settings)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive_settings)) }, onClick = { menu = false; navTo(Settings) })
                        }
                    }
                }
                LazyRow(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(SearchScope.All to R.string.search_all, SearchScope.Podcasts to R.string.library_podcasts, SearchScope.Episodes to R.string.episodes_label,
                        SearchScope.Playlists to R.string.library_playlists)) { (scope, title) ->
                        FilterChip(vm.scope == scope, { vm.changeScope(scope) }, label = { Text(stringResource(title)) })
                    }
                }
                Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (vm.libraryOnly || vm.scope in setOf(SearchScope.Episodes, SearchScope.Playlists)) R.string.search_local_scope else R.string.search_online_scope),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.scope in setOf(SearchScope.All, SearchScope.Podcasts)) TextButton(onClick = { vm.changeLibraryOnly(!vm.libraryOnly) }) {
                        Text(stringResource(if (vm.libraryOnly) R.string.search_include_online else R.string.search_library_only))
                    }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(state = state, modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (vm.query.isBlank()) {
                        item { Column(Modifier.padding(24.dp)) {
                            Text(stringResource(R.string.search_start_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.search_start_body), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { urlDialog = true }) { Text(stringResource(R.string.search_add_url)) }
                                TextButton(onClick = { navTo(TopChart) }) { Text(stringResource(R.string.search_browse)) }
                            }
                        } }
                        val recent = (attributes.searchHistory + attributes.onlineSearchHistory).distinct().take(8)
                        if (recent.isNotEmpty()) {
                            item { SearchSection(stringResource(R.string.search_recent)) }
                            items(recent, key = { "history:$it" }) { value ->
                                ListItem(headlineContent = { Text(value) }, leadingContent = { Icon(Icons.Default.Search, null) },
                                    modifier = Modifier.clickable { vm.changeQuery(value); vm.submit(); keyboard?.hide() })
                            }
                        }
                    } else if (vm.query.trim().startsWith("https://", true) || vm.query.trim().startsWith("http://", true)) {
                        item { Column(Modifier.padding(24.dp)) {
                            Text(stringResource(R.string.search_url_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.search_url_body), Modifier.padding(vertical = 12.dp))
                            Button(onClick = vm::submit) { Text(stringResource(R.string.search_preview)) }
                        } }
                    } else {
                        item { SearchSection(stringResource(R.string.search_in_your_library)) }
                        if (local.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
                        if (local.failed) item { SearchMessage(stringResource(R.string.search_local_failed)) }
                        val podcasts = if (vm.scope in setOf(SearchScope.All, SearchScope.Podcasts)) local.podcasts else emptyList()
                        val episodes = if (vm.scope in setOf(SearchScope.All, SearchScope.Episodes)) local.episodes else emptyList()
                        val playlists = if (vm.scope in setOf(SearchScope.All, SearchScope.Playlists)) local.playlists else emptyList()
                        if (!local.loading && !local.failed && podcasts.isEmpty() && episodes.isEmpty() && playlists.isEmpty()) item {
                            SearchMessage(stringResource(R.string.search_no_local))
                        }
                        items(if (vm.scope == SearchScope.All) podcasts.take(3) else podcasts, key = { "feed:${it.id}" }) { feed ->
                            PodcastRow(feed.title.orEmpty(), feed.author, feed.images.firstOrNull()?.href, { navTo(FeedDetails(feed.id)) })
                        }
                        if (vm.scope == SearchScope.All && podcasts.size > 3) item { TextButton(onClick = { vm.changeScope(SearchScope.Podcasts) }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.search_show_podcasts, podcasts.size)) } }
                        items(if (vm.scope == SearchScope.All) episodes.take(3) else episodes, key = { "episode:${it.id}" }) { episode ->
                            ArchiveEpisodeRow(episode, remember(episode.id) { ActionButton(episode) }, current?.id == episode.id && playback == PlayerStatusSimple.PLAYING,
                                current?.id == episode.id, false, false, false, StatusRowMode.Normal, true, null,
                                onOpen = { episodeForInfo = episode }, onSelect = { episodeForInfo = episode },
                                onAction = { e, _ -> replaceListeningQueue(episodes, e.id, localizedString(R.string.archive_search)) }, showHighlights = false)
                        }
                        if (vm.scope == SearchScope.All && episodes.size > 3) item { TextButton(onClick = { vm.changeScope(SearchScope.Episodes) }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.search_show_episodes, episodes.size)) } }
                        items(if (vm.scope == SearchScope.All) playlists.take(3) else playlists, key = { "playlist:${it.id}" }) { playlist ->
                            ListItem(headlineContent = { Text(playlist.displayName) }, supportingContent = { Text(stringResource(if (playlist.smart) R.string.playlist_smart else R.string.library_playlists)) }, modifier = Modifier.clickable { navTo(Queues(playlist.id)) })
                        }
                        if (vm.scope == SearchScope.All && playlists.size > 3) item { TextButton(onClick = { vm.changeScope(SearchScope.Playlists) }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.search_show_playlists, playlists.size)) } }
                        if (vm.scope in setOf(SearchScope.All, SearchScope.Podcasts) && !vm.libraryOnly) {
                            item { SearchSection(stringResource(R.string.search_online_podcasts)) }
                            if (!local.loading && local.podcasts.isEmpty() && !local.failed && online.status == OnlineSearchStatus.Loading) item { SearchMessage(stringResource(R.string.search_expanding)) }
                            when (online.status) {
                                OnlineSearchStatus.Idle -> item { SearchMessage(stringResource(if (vm.query.trim().length < 2) R.string.search_type_more else R.string.search_waiting)) }
                                OnlineSearchStatus.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
                                OnlineSearchStatus.Empty -> item { SearchMessage(stringResource(R.string.search_no_online)) }
                                OnlineSearchStatus.Failed -> item { Column(Modifier.padding(horizontal = 16.dp)) {
                                    SearchMessage(stringResource(R.string.search_online_failed))
                                    TextButton(onClick = vm::retryOnline) { Text(stringResource(R.string.search_retry_online)) }
                                } }
                                OnlineSearchStatus.Results -> {
                                    if (online.partial) item { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.search_partial), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = vm::retryOnline) { Text(stringResource(R.string.search_retry_online)) }
                                    } }
                                    itemsIndexed(online.podcasts, key = { index, result -> "online:${podcastUrlKey(result.feedUrl) ?: "${result.source}:$index:${result.title}"}" }) { _, result ->
                                        val key = podcastUrlKey(result.feedUrl)
                                        val id = feeds.firstOrNull { key != null && podcastUrlKey(it.downloadUrl) == key }?.id ?: vm.added[key]?.takeIf { addedId -> feeds.any { it.id == addedId } }
                                        PodcastRow(result.title, result.author, result.imageUrl,
                                            onOpen = { if (id != null) navTo(FeedDetails(id)) else result.feedUrl?.let { navTo(OnlineFeed(it, result.source)) } },
                                            inLibrary = id != null, adding = vm.adding[key] == true,
                                            onAdd = if (result.feedUrl != null) ({ vm.addPodcast(result) }) else null)
                                    }
                                }
                            }
                            item { TextButton(onClick = { urlDialog = true }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.search_add_url)) } }
                        }
                    }
                    item {
                        TextButton(onClick = {
                            pfBackStack.clear(); pfBackStack.add(PFNav.Portal); pfBackStack.add(PFNav.Providers); navTo(Settings)
                        }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.search_settings)) }
                    }
                }
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = details, allowOpenFeed = true)
    }
}

@Composable
private fun SearchSection(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun SearchMessage(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PodcastUrlDialog(onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.search_add_url)) },
        text = { OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.search_url_label)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = url.trim().startsWith("https://", true) || url.trim().startsWith("http://", true), onClick = { onDismiss(); navTo(OnlineFeed(url.trim())) }) { Text(stringResource(R.string.search_preview)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}
