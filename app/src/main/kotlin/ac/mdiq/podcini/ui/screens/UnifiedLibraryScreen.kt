package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.feed.loadLocalFolder
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.utils.AddLocalFolder
import ac.mdiq.podcini.ui.compose.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Links from media details use the same retained browser as the Library destination. */
internal object LibraryBrowseRequest {
    var destination = LibraryDestination()
    var revision by mutableIntStateOf(0)
}
fun openLibrary(destination: LibraryDestination) {
    episodeForInfo = null
    LibraryBrowseRequest.destination = destination
    LibraryBrowseRequest.revision++
    navTo(Library)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedLibraryScreen() {
    val prefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    val preferences = remember(prefs.libraryBrowsePreferences) { decodeBrowsePreferences(prefs.libraryBrowsePreferences) }
    var stackJson by rememberSaveable { mutableStateOf(libraryJson.encodeToString(listOf(LibraryDestination(section = preferences.start)))) }
    val stack = remember(stackJson) { runCatching { libraryJson.decodeFromString<List<LibraryDestination>>(stackJson) }.getOrDefault(listOf(LibraryDestination())).ifEmpty { listOf(LibraryDestination()) } }
    val destination = stack.last()
    var acceptedRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(LibraryBrowseRequest.revision) {
        if (LibraryBrowseRequest.revision > 0 && LibraryBrowseRequest.revision != acceptedRequest) {
            stackJson = libraryJson.encodeToString(if (LibraryBrowseRequest.destination.section == "home") listOf(LibraryDestination()) else listOf(LibraryDestination(), LibraryBrowseRequest.destination))
            acceptedRequest = LibraryBrowseRequest.revision
        }
    }
    fun visit(next: LibraryDestination) { stackJson = libraryJson.encodeToString(stack + next) }
    fun change(next: LibraryDestination) { stackJson = libraryJson.encodeToString(stack.dropLast(1) + next) }
    fun back() { stackJson = libraryJson.encodeToString(if (stack.size > 1) stack.dropLast(1) else listOf(LibraryDestination())) }
    fun root(section: String) { stackJson = libraryJson.encodeToString(if (section == "home") listOf(LibraryDestination()) else listOf(LibraryDestination(), LibraryDestination(section = section, grid = section == "albums"))) }
    var retry by remember { mutableIntStateOf(0) }
    val catalogue by remember(retry) { libraryCatalogueFlow() }.collectAsStateWithLifecycle(LibraryCatalogue())
    var addMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var playlistRules by remember { mutableStateOf<PlayQueue?>(null) }
    val title = libraryDestinationTitle(destination)
    val details = remember { MutableStateFlow<List<Episode>>(emptyList()) }
    val saveable = rememberSaveableStateHolder()
    fun preferencesEdit(change: (LibraryBrowsePreferences) -> LibraryBrowsePreferences) { runOnIOScope { try { updateLibraryPreferences(change) } catch (_: Exception) { error = true } } }
    fun importLibrary() {
        ac.mdiq.podcini.ui.screens.prefscreens.pfBackStack.clear()
        ac.mdiq.podcini.ui.screens.prefscreens.pfBackStack.add(ac.mdiq.podcini.ui.screens.prefscreens.PFNav.Portal)
        ac.mdiq.podcini.ui.screens.prefscreens.pfBackStack.add(ac.mdiq.podcini.ui.screens.prefscreens.PFNav.ImportExport)
        navTo(Settings)
    }
    val folderLauncher = rememberLauncherForActivityResult(AddLocalFolder()) { uri -> if (uri != null) runOnIOScope { loadLocalFolder(uri) } }
    BackHandler(episodeForInfo != null || stack.size > 1 || destination.section != "home") { if (episodeForInfo != null) episodeForInfo = null else back() }
    if (dialog == "url") AddMediaUrlDialog { dialog = "" }
    if (dialog == "playlist" || playlistRules != null) PlaylistEditorDialog(rules = playlistRules) { dialog = ""; playlistRules = null }
    if (dialog == "fileTags") FileTagManagerDialog { dialog = "" }
    if (dialog == "pin") LibraryNameDialog(stringResource(R.string.browse_pin_view), title, onDismiss = { dialog = "" }) { name ->
        preferencesEdit { it.copy(pins = it.pins + LibraryPin(UUID.randomUUID().toString(), name, destination)) }; dialog = ""
    }
    if (dialog == "start") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(R.string.browse_open_to)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            (listOf("home") + librarySections).forEach { section -> Row(Modifier.fillMaxWidth().clickable {
                preferencesEdit { it.copy(start = section) }; dialog = ""
            }, verticalAlignment = Alignment.CenterVertically) { RadioButton(preferences.start == section, null); Text(stringResource(librarySectionLabel(section))) } }
        }
    }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.cancel_label)) } })
    if (dialog in listOf("newTag", "renameTag", "moveTag", "mergeTag")) {
        val editing = dialog != "newTag"
        val initial = if (dialog == "renameTag") decodeTagSegments(destination.tag)?.last().orEmpty() else ""
        val parent = decodeTagSegments(destination.tag).orEmpty().dropLast(1)
        fun target(value: String) = if (dialog == "renameTag") encodeTagSegments(parent + normalizeTagSegment(value)) else if (!editing)
            encodeTagSegments(decodeTagSegments(destination.tag).orEmpty().ifEmpty { listOf("Tags") } + normalizeTagSegment(value)) else value.trim()
        LibraryNameDialog(stringResource(when (dialog) { "renameTag" -> R.string.rename; "moveTag" -> R.string.browse_move_tag; "mergeTag" -> R.string.browse_merge_tag; else -> R.string.browse_new_tag }), initial,
            hint = stringResource(if (editing) R.string.browse_tag_edit_hint else R.string.browse_tag_new_hint),
            valid = { value -> val path = target(value); TagPath.parse(path) != null &&
                (!editing || validTagDestination(destination.tag, path)) && (dialog != "mergeTag" || catalogue.tags.any { libraryKey(it) == libraryKey(path) }) &&
                (dialog == "mergeTag" || catalogue.tags.none { libraryKey(it) == libraryKey(path) }) }, onDismiss = { dialog = "" }) { value ->
            val path = target(value)
            if (!editing) preferencesEdit { it.copy(emptyTags = it.emptyTags + path) }
            else runOnIOScope { try { editLibraryTag(destination.tag, path); withContext(Dispatchers.Main) { change(destination.copy(tag = path)) } } catch (_: Exception) { error = true } }
            dialog = ""
        }
    }
    if (dialog == "removeTag") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(R.string.browse_remove_tag)) }, text = {
        Text(stringResource(R.string.browse_remove_tag_hint, destination.tag, catalogue.facets.values.count { item -> item.tags.any { tagMatches(it, destination.tag) } }))
    }, confirmButton = { TextButton(onClick = {
        runOnIOScope { try { editLibraryTag(destination.tag, ""); withContext(Dispatchers.Main) { back() } } catch (_: Exception) { error = true } }; dialog = ""
    }) { Text(stringResource(R.string.browse_remove_tag)) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.cancel_label)) } })

    Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            TopAppBar(title = { Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2) },
                navigationIcon = { if (destination.section != "home" || stack.size > 1) IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.archive_back)) } },
                actions = {
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, stringResource(R.string.library_add)) }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive_find_podcasts)) }, onClick = { addMenu = false; openEverywhereSearch(scope = ac.mdiq.podcini.sourcing.searcher.SearchScope.Podcasts) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_new)) }, onClick = { addMenu = false; dialog = "playlist" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_add_directory)) }, onClick = { addMenu = false; folderLauncher.launch(null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.media_add_url)) }, onClick = { addMenu = false; dialog = "url" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.browse_new_tag)) }, onClick = { addMenu = false; dialog = "newTag" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.search_import_library)) }, onClick = { addMenu = false; importLibrary() })
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.browse_options)) }
                        DropdownMenu(menu, { menu = false }) {
                            if (destination.section !in listOf("home", "manage", "pins", "highlights")) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.browse_pin_view)) }, onClick = { menu = false; dialog = "pin" })
                                if (destination.section != "playlists" && destination.group != "playlists") DropdownMenuItem(text = { Text(stringResource(R.string.browse_save_smart)) }, onClick = {
                                    menu = false; playlistRules = PlayQueue().apply { smart = true; ruleBrowseFilter = destination.query().encode(); sortOrder = EpisodeSortOrder.fromCode(destination.sort) }
                                })
                            }
                            if (TagPath.parse(destination.tag) != null) listOf("newTag" to R.string.browse_add_subtag, "renameTag" to R.string.rename,
                                "moveTag" to R.string.browse_move_tag, "mergeTag" to R.string.browse_merge_tag, "removeTag" to R.string.browse_remove_tag).forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; dialog = key })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.browse_open_to)) }, onClick = { menu = false; dialog = "start" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_tags_settings)) }, onClick = { menu = false; dialog = "fileTags" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.search_manage_library)) }, onClick = { menu = false; visit(LibraryDestination("manage")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive_settings)) }, onClick = { menu = false; navTo(Settings) })
                        }
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide && destination.section != "home") {
                        Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                            LibraryBrowseRow(stringResource(R.string.library), onClick = { root("home") })
                            librarySections.forEach { section -> LibraryBrowseRow(stringResource(librarySectionLabel(section)), selected = (when (destination.section) { "tag" -> "tags"; "creator" -> "creators"; "album" -> "albums"; "podcast" -> "podcasts"; else -> destination.section }) == section, onClick = { root(section) }) }
                            HorizontalDivider(Modifier.padding(16.dp))
                            LibraryBrowseRow(stringResource(R.string.browse_pinned), onClick = { root("pins") })
                            preferences.pins.take(6).forEach { pin -> LibraryBrowseRow(pin.name, onClick = { visit(pin.destination) }) }
                        }
                        VerticalDivider()
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (error) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.browse_save_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            IconButton(onClick = { error = false }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                        }
                        val key = "${stack.size}:${destination.section}:${destination.tag}:${destination.creator}:${destination.album}:${destination.feedId}"
                        saveable.SaveableStateProvider(key) {
                            when (destination.section) {
                                "home" -> LibraryIndex(catalogue, preferences, ::visit) { cards -> preferencesEdit { it.copy(homeCards = cards) } }
                                "pins" -> LibraryPins(preferences, ::visit, ::preferencesEdit)
                                "highlights" -> SavedLibraryScreen()
                                "manage" -> LazyColumn { item { LibraryBrowseRow(stringResource(R.string.search_import_library), onClick = ::importLibrary) }
                                    item { LibraryBrowseRow(stringResource(R.string.library_add_directory), onClick = { folderLauncher.launch(null) }) }
                                    item { LibraryBrowseRow(stringResource(R.string.media_add_url), onClick = { dialog = "url" }) }
                                    item { LibraryBrowseRow(stringResource(R.string.search_podcast_settings), onClick = { navTo(FeedsSettings) }) }
                                    items(catalogue.feeds.filter { it.isLocal }, key = { it.id }) { feed -> PodcastRow(feed.title.orEmpty(), stringResource(R.string.library_device_directory), feed.images.firstOrNull()?.href, { navTo(FeedDetails(feed.id)) }) }
                                }
                                else -> LibraryBrowser(destination, catalogue, ::change, ::visit, { dialog = "pin" }, { retry++ }, details)
                            }
                        }
                    }
                }
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = details, allowOpenFeed = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryIndex(catalogue: LibraryCatalogue, preferences: LibraryBrowsePreferences, visit: (LibraryDestination) -> Unit, setCards: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Surface(onClick = { SearchSession.libraryOnly = true; openEverywhereSearch() }, shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Search, null); Text(stringResource(R.string.library_search), style = MaterialTheme.typography.bodyLarge)
            }
        } }
        if (preferences.pins.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.browse_pinned), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { visit(LibraryDestination("pins")) }) { Text(stringResource(R.string.browse_edit_pins)) }
            }
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preferences.pins.take(6).forEach { pin -> SuggestionChip(onClick = { visit(pin.destination) }, label = { Text(pin.name, maxLines = 2) }) }
                if (preferences.pins.size > 6) TextButton(onClick = { visit(LibraryDestination("pins")) }) { Text(stringResource(R.string.browse_all_pins)) }
            }
        }
        item(key = "browse-layout") {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.browse_by), Modifier.align(Alignment.CenterVertically).padding(end = 16.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow {
                    listOf(true to R.string.browse_cards, false to R.string.browse_list).forEachIndexed { index, (cards, label) ->
                        SegmentedButton(selected = preferences.homeCards == cards, onClick = { setCards(cards) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
        }
        if (preferences.homeCards) item(key = "category-cards") {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                // Keep labels readable on narrow windows and at enlarged system text sizes.
                val minWidth = 136.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val columns = ((maxWidth + 12.dp) / (minWidth + 12.dp)).toInt().coerceIn(1, 3)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    librarySections.chunked(columns).forEach { sections ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            sections.forEach { section ->
                                Card(onClick = { visit(LibraryDestination(section, grid = section == "albums")) },
                                    modifier = Modifier.weight(1f).fillMaxHeight().semantics { role = Role.Button },
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                    Column(Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                                        Icon(librarySectionIcon(section), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(stringResource(librarySectionLabel(section)), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            repeat(columns - sections.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        } else items(librarySections, key = { it }) { section ->
            LibraryBrowseRow(stringResource(librarySectionLabel(section)), stringResource(librarySectionHint(section)),
                icon = librarySectionIcon(section), onClick = { visit(LibraryDestination(section, grid = section == "albums")) })
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)); LibraryBrowseRow(stringResource(R.string.search_highlights), onClick = { visit(LibraryDestination("highlights")) }) }
        if (catalogue.loaded && catalogue.media.isEmpty() && catalogue.feeds.isEmpty()) item {
            Text(stringResource(R.string.browse_empty_library), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openEverywhereSearch(scope = ac.mdiq.podcini.sourcing.searcher.SearchScope.Podcasts) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.archive_find_podcasts)) }
        }
    }
    }
}

@Composable
private fun librarySectionIcon(section: String): ImageVector = ImageVector.vectorResource(when (section) {
    "podcasts" -> R.drawable.archive_headphones
    "creators" -> R.drawable.baseline_people_alt_24
    "albums" -> R.drawable.library_album
    "tags" -> R.drawable.baseline_label_24
    "playlists" -> R.drawable.ic_playlist_play
    else -> R.drawable.rounded_books_movies_and_music_24
})

private fun librarySectionHint(section: String): Int = when (section) {
    "podcasts" -> R.string.browse_podcasts_hint
    "creators" -> R.string.browse_creators_hint
    "albums" -> R.string.browse_albums_hint
    "tags" -> R.string.browse_tags_hint
    "playlists" -> R.string.browse_playlists_hint
    else -> R.string.browse_items_hint
}

@Composable
private fun LibraryPins(preferences: LibraryBrowsePreferences, visit: (LibraryDestination) -> Unit, edit: ((LibraryBrowsePreferences) -> LibraryBrowsePreferences) -> Unit) {
    var rename by remember { mutableStateOf<LibraryPin?>(null) }
    rename?.let { pin -> LibraryNameDialog(stringResource(R.string.rename), pin.name, onDismiss = { rename = null }) { name -> edit { it.copy(pins = it.pins.map { p -> if (p.id == pin.id) p.copy(name = name) else p }) }; rename = null } }
    LazyColumn {
        item { Text(stringResource(R.string.browse_pins_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        itemsIndexed(preferences.pins, key = { _, pin -> pin.id }) { index, pin ->
            var expanded by remember { mutableStateOf(false) }
            ListItem(headlineContent = { Text(pin.name) }, supportingContent = { Text(libraryDestinationTitle(pin.destination)) },
                modifier = Modifier.clickable { visit(pin.destination) }, trailingContent = { Box {
                    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.browse_pin_options, pin.name)) }
                    DropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { expanded = false; rename = pin })
                        listOf(-1 to R.string.browse_move_up, 1 to R.string.browse_move_down).forEach { (offset, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = index + offset in preferences.pins.indices, onClick = {
                                expanded = false; edit { saved -> val pins = saved.pins.toMutableList(); val at = pins.indexOfFirst { it.id == pin.id }
                                    if (at >= 0 && at + offset in pins.indices) java.util.Collections.swap(pins, at, at + offset); saved.copy(pins = pins) }
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.browse_unpin)) }, onClick = { expanded = false; edit { it.copy(pins = it.pins.filterNot { p -> p.id == pin.id }) } })
                    }
                } })
        }
    }
}

private data class LibraryResults(val media: List<Episode> = emptyList(), val podcasts: List<Feed> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(), val creators: List<LibraryCreator> = emptyList(), val playlists: List<PlayQueue> = emptyList(), val tagCounts: Map<String, Int> = emptyMap())

@Composable
private fun LibraryBrowser(destination: LibraryDestination, catalogue: LibraryCatalogue, change: (LibraryDestination) -> Unit,
                           visit: (LibraryDestination) -> Unit, pin: () -> Unit, retry: () -> Unit, details: MutableStateFlow<List<Episode>>) {
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val title = libraryDestinationTitle(destination)
    val query = destination.query()
    val detail = destination.section in listOf("tag", "creator", "album", "podcast")
    val section = if (detail) destination.group else destination.section
    val result by produceState<LibraryResults?>(null, catalogue, destination) {
        value = withContext(Dispatchers.Default) {
            val media = catalogue.media.filter { query.matches(catalogue.facets.getValue(it.id)) }.toMutableList()
            if (destination.album.isNotBlank() && destination.sort == EpisodeSortOrder.TRACK_NUMBER_ASC.code) media.sortWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))
            else media.reorderWith(EpisodeSortOrder.fromCode(destination.sort))
            val ids = media.mapNotNull { it.feedId }.toSet()
            val hasMediaRules = query.unfinished || query.downloaded || query.favourite || query.maxMinutes > 0 || query.scopeAlbum.isNotBlank()
            val podcasts = catalogue.feeds.filter { feed -> !feed.isLocal && (feed.id in ids || (!hasMediaRules && query.matches(LibraryMedia(
                feed.id, feed.title.orEmpty(), "podcast", setOf(feed.author.orEmpty()), tags = feed.tags.toSet(), feedId = feed.id)))) }.sortedBy { libraryKey(it.title.orEmpty()) }
            val mediaIds = media.map { it.id }.toSet()
            val albums = catalogue.albums.filter { album -> album.itemIds.any { it in mediaIds } }
            val names = media.flatMap { catalogue.facets.getValue(it.id).creators }.map(::libraryKey).toSet() + podcasts.map { libraryKey(it.author.orEmpty()) }
            val creators = catalogue.creators.filter { libraryKey(it.name) in names }
            val playlists = catalogue.playlists.filter { list ->
                (destination.tag.isBlank() || list.tags.any { tagMatches(it, destination.tag, query.descendants) }) &&
                    (query.text.isBlank() || list.displayName.contains(query.text, true)) }
            // A child opens its own tag scope; directory search is consumed by choosing a tag.
            val tagFilter = destination.filter.copy(text = if (section == "tags") "" else destination.filter.text)
            val tagCounts = countLibraryTags(catalogue.facets.values.filter(tagFilter::matches), tagFilter.descendants)
            LibraryResults(media, podcasts, albums, creators, playlists, tagCounts)
        }
    }
    val rows = result ?: LibraryResults()
    LaunchedEffect(rows.media) { details.value = rows.media }
    var filterSession by rememberSaveable { mutableIntStateOf(0) }
    if (filterOpen) key(filterSession) { LibraryFilterEditor(query, catalogue, onDismiss = { filterOpen = false }) { change(destination.copy(filter = it.withoutScope())); filterOpen = false } }
    if (sortOpen) EpisodeSortDialog(initOrder = EpisodeSortOrder.fromCode(destination.sort), onDismiss = { sortOpen = false }) { order -> if (order != null) change(destination.copy(sort = order.code)) }
    Column(Modifier.fillMaxSize()) {
        TextField(destination.filter.text, { change(destination.copy(filter = destination.filter.copy(text = it))) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
            placeholder = { Text(stringResource(R.string.browse_search_within, title)) }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (destination.filter.text.isNotEmpty()) IconButton(onClick = { change(destination.copy(filter = destination.filter.copy(text = ""))) }) { Icon(Icons.Default.Close, stringResource(R.string.browse_clear_search)) } })
        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (section != "playlists") TextButton(onClick = { filterSession++; filterOpen = true }) { Text(stringResource(R.string.archive_filter)) }
            if (section == "items" || destination.section == "album" || destination.section == "podcast") TextButton(onClick = { sortOpen = true }) { Text(stringResource(R.string.archive_sort)) }
            if (section in listOf("albums", "podcasts")) TextButton(onClick = { change(destination.copy(grid = !destination.grid)) }) { Text(stringResource(if (destination.grid) R.string.browse_list_view else R.string.browse_grid_view)) }
            TextButton(onClick = pin) { Text(stringResource(R.string.browse_pin)) }
        }
        if (section != "playlists") LibraryAppliedFilters(destination.filter) { change(destination.copy(filter = it)) }
        else if (destination.filter.copy(text = "", descendants = true).isActive) Text(
            stringResource(R.string.browse_playlist_filters_paused), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val children = remember(catalogue.tags, destination.tag) { libraryTagChildren(catalogue.tags, destination.tag) }
        val header: @Composable () -> Unit = {
            if (destination.tag.isNotBlank()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    item { TextButton(onClick = { visit(LibraryDestination("tags")) }) { Text(stringResource(R.string.library_tags)) } }
                    items(tagParents(destination.tag)) { path -> TextButton(onClick = { if (!path.equals(destination.tag, true)) visit(destination.copy(section = "tag", tag = path)) }) { Text(tagLeafLabel(path)) } }
                }
                FilterChip(destination.filter.descendants, { change(destination.copy(filter = destination.filter.copy(descendants = !destination.filter.descendants))) },
                    label = { Text(stringResource(if (destination.filter.descendants) R.string.browse_including_subtags else R.string.browse_this_tag_only)) }, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (destination.section == "tag" && children.isNotEmpty()) {
                LibraryHeading(stringResource(R.string.browse_subtags))
                children.take(3).forEach { path -> TagBrowseRow(path, rows.tagCounts[libraryKey(path)] ?: 0, visit, destination.filter) }
                if (children.size > 3) TextButton(onClick = { visit(destination.copy(section = "tags")) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.browse_all_subtags)) }
            }
            if (destination.section == "album") catalogue.albums.firstOrNull { it.key == destination.album }?.let { album ->
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PodcastArtwork(album.image, Modifier.size(96.dp))
                    Column(Modifier.weight(1f)) { Text(album.title, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { visit(LibraryDestination("creator", creator = album.artist.ifBlank { UNKNOWN_LIBRARY_CREATOR })) }) { Text(libraryCreatorName(album.artist)) }
                        Text(pluralStringResource(R.plurals.browse_media_count, rows.media.size, rows.media.size), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (destination.section == "podcast") catalogue.feeds.firstOrNull { it.id == destination.feedId }?.let { feed ->
                if (!feed.author.isNullOrBlank()) TextButton(onClick = { visit(LibraryDestination("creator", creator = feed.author!!)) }, Modifier.padding(horizontal = 8.dp)) { Text(feed.author!!) }
                TextButton(onClick = { navTo(FeedDetails(feed.id)) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.browse_podcast_details)) }
            }
            if (destination.section in listOf("tag", "creator")) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val groups = if (destination.section == "tag") listOf("items", "podcasts", "playlists") else listOf("items", "podcasts", "albums")
                items(groups) { group -> FilterChip(destination.group == group, { change(destination.copy(group = group)) }, label = { Text(stringResource(librarySectionLabel(group))) }) }
            }
        }
        Box(Modifier.weight(1f)) {
            if (!catalogue.loaded || result == null) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.browse_loading))
            }
            else if (catalogue.failed) ArchiveEmpty(R.string.browse_load_failed, R.string.browse_load_failed_hint, R.string.browse_retry, onAction = retry)
            else when (section) {
                "tags" -> {
                    val candidatePaths = if (destination.filter.text.isBlank()) children else catalogue.tags.filter { it.contains(destination.filter.text, true) && (destination.tag.isBlank() || tagMatches(it, destination.tag)) }
                    val paths = if (destination.filter.copy(text = "").isActive) candidatePaths.filter { (rows.tagCounts[libraryKey(it)] ?: 0) > 0 } else candidatePaths
                    LazyColumn {
                        item { header() }
                        items(paths, key = { it }) { path -> TagBrowseRow(path, rows.tagCounts[libraryKey(path)] ?: 0, visit, destination.filter.copy(text = ""), showPath = destination.filter.text.isNotBlank()) }
                        if (paths.isEmpty()) item { LibraryEmptyMessage(R.string.browse_no_tags, R.string.browse_no_tags_hint) }
                    }
                }
                "creators" -> LazyColumn {
                    item { header() }
                    items(rows.creators, key = { libraryKey(it.name) }) { creator ->
                        val role = creator.roles.map { stringResource(when (it) { "publisher" -> R.string.browse_author_publisher; "artist" -> R.string.browse_artist; else -> R.string.browse_author }) }.joinToString(" · ")
                        LibraryBrowseRow(libraryCreatorName(creator.name), role, onClick = { visit(destination.copy(section = "creator", creator = creator.name, filter = destination.filter.copy(text = ""))) })
                    }
                    if (rows.creators.isEmpty()) item { LibraryEmptyMessage(R.string.browse_no_creators, R.string.browse_no_creators_hint) }
                }
                "albums", "podcasts" -> {
                    if (destination.grid) LazyVerticalGrid(GridCells.Adaptive(148.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item(span = { GridItemSpan(maxLineSpan) }) { header() }
                        if (section == "albums") items(rows.albums, key = { it.key }) { album -> LibraryCover(album.title, libraryCreatorName(album.artist), album.image) { visit(destination.copy(section = "album", album = album.key, title = album.title, sort = 21, group = "items", filter = destination.filter.copy(text = ""))) } }
                        else items(rows.podcasts, key = { it.id }) { feed -> LibraryCover(feed.title.orEmpty(), feed.author.orEmpty(), feed.images.firstOrNull()?.href) { visit(destination.copy(section = "podcast", feedId = feed.id, title = feed.title.orEmpty(), group = "items", filter = destination.filter.copy(text = ""))) } }
                        if ((section == "albums" && rows.albums.isEmpty()) || (section == "podcasts" && rows.podcasts.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }) { LibraryEmptyMessage(R.string.library_no_items, R.string.browse_no_matches_hint) }
                    } else LazyColumn {
                        item { header() }
                        if (section == "albums") items(rows.albums, key = { it.key }) { album -> PodcastRow(album.title, libraryCreatorName(album.artist), album.image,
                            { visit(destination.copy(section = "album", album = album.key, title = album.title, sort = 21, group = "items", filter = destination.filter.copy(text = ""))) }) }
                        else items(rows.podcasts, key = { it.id }) { feed -> PodcastRow(feed.title.orEmpty(), feed.author, feed.images.firstOrNull()?.href,
                            { visit(destination.copy(section = "podcast", feedId = feed.id, title = feed.title.orEmpty(), group = "items", filter = destination.filter.copy(text = ""))) }) }
                        if ((section == "albums" && rows.albums.isEmpty()) || (section == "podcasts" && rows.podcasts.isEmpty())) item { LibraryEmptyMessage(R.string.library_no_items, R.string.browse_no_matches_hint) }
                    }
                }
                "playlists" -> LazyColumn {
                    item { header() }
                    items(rows.playlists, key = { it.id }) { playlist -> LibraryBrowseRow(playlist.displayName, stringResource(if (playlist.smart) R.string.playlist_smart else R.string.playlist_keep_items), onClick = { navTo(Queues(playlist.id)) }) }
                    if (rows.playlists.isEmpty()) item { LibraryEmptyMessage(R.string.playlist_empty_library, R.string.playlist_empty_library_hint) }
                }
                else -> EpisodeLazyColumn(rows.media, lazyListState = rememberLazyListState(),
                    actionButtonCB = { item, _ -> replaceListeningQueue(rows.media, item.id, title) }, headerContent = {
                        header()
                        if (rows.media.isEmpty()) LibraryEmptyMessage(R.string.library_no_items, R.string.browse_no_matches_hint)
                        else if (destination.section != "album") Text(pluralStringResource(R.plurals.browse_media_count, rows.media.size, rows.media.size), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
            }
        }
        if (section != "playlists" && destination.filter.isActive) TextButton(onClick = { change(destination.copy(filter = LibraryFilter())) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.archive_clear_filters)) }
    }
}

@Composable
private fun LibraryCover(title: String, subtitle: String, image: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PodcastArtwork(image, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TagBrowseRow(path: String, count: Int, visit: (LibraryDestination) -> Unit, filter: LibraryFilter, showPath: Boolean = false) {
    LibraryBrowseRow(tagLeafLabel(path), if (showPath) tagLabel(path) else pluralStringResource(R.plurals.browse_media_count, count, count),
        onClick = { visit(LibraryDestination("tag", tag = path, filter = filter)) })
}

@Composable
private fun LibraryBrowseRow(title: String, subtitle: String? = null, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        leadingContent = icon?.let { { Icon(it, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) } },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = { Icon(ImageVector.vectorResource(R.drawable.baseline_arrow_right_alt_24), null) },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick))
}

@Composable
private fun LibraryHeading(title: String) { Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium) }

@Composable
private fun LibraryEmptyMessage(title: Int, body: Int) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun AddMediaUrlDialog(onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("music") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.media_add_url)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.media_title)) })
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.media_url)) })
            KindPicker(kind, allowAll = false) { kind = it }
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://")), onClick = {
        runOnIOScope { if (realm.query(Episode::class, "downloadUrl == $0", url.trim()).first().find() == null) upsert(Episode().apply {
            id = ac.mdiq.podcini.shared.getEntityId(); this.title = title.trim(); downloadUrl = url.trim(); contentKind = kind
            mimeType = if (kind == "video") "video/mp4" else "audio/mpeg"
        }) {} }; onDismiss()
    }) { Text(stringResource(R.string.archive_add)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } })
}
