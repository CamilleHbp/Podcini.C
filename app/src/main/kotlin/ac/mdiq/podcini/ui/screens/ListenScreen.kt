package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.playback.PlaybackStarter
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.ui.compose.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen() {
    val queue by actQueueFlow.collectAsStateWithLifecycle()
    val items by remember { playlistItemsFlow(LISTENING_QUEUE_ID) }.collectAsStateWithLifecycle(initialValue = null)
    val player by theatres[0].mPlayerFlow.collectAsStateWithLifecycle()
    val current by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val error by player?.playbackErrorFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val details = remember { MutableStateFlow<List<Episode>>(emptyList()) }
    LaunchedEffect(items) { details.value = items.orEmpty() }
    BackHandler(episodeForInfo != null) { episodeForInfo = null }
    if (save) PlaylistEditorDialog(items = items.orEmpty()) { save = false }
    Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ArchiveTopBar(stringResource(R.string.archive_listen)) {
                IconButton(onClick = { navTo(Settings) }) { Icon(ImageVector.vectorResource(R.drawable.ic_settings), stringResource(R.string.archive_settings)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.archive_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_save_queue)) }, enabled = !items.isNullOrEmpty(), onClick = { menu = false; save = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_edit_order)) }, enabled = !items.isNullOrEmpty(), onClick = { menu = false; editing = !editing })
                        DropdownMenuItem(text = { Text(stringResource(R.string.archive_history)) }, onClick = { menu = false; navTo(Facets(QuickAccess.History.name)) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.statistics_label)) }, onClick = { menu = false; navTo(Statistics) })
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!items.isNullOrEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.listening_queue), style = MaterialTheme.typography.titleLarge)
                        if (queue.originName.isNotBlank()) Text(stringResource(R.string.listening_from, queue.originName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (error) Text(stringResource(R.string.listening_unavailable), color = MaterialTheme.colorScheme.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { (current ?: items?.firstOrNull())?.let { playLibraryItem(it) } }) { Text(stringResource(R.string.listening_resume)) }
                            FilterChip(queue.repeatQueue, { runOnIOScope { upsert(queue) { it.repeatQueue = !it.repeatQueue } } }, label = { Text(stringResource(R.string.listening_repeat)) })
                            FilterChip(queue.unshuffledIds.isNotEmpty(), { runOnIOScope { shuffleListeningQueue() } }, label = { Text(stringResource(R.string.ui_shuffle)) })
                        }
                        LabelSwitch(R.string.listening_continue, queue.playInSequence) { value -> runOnIOScope { upsert(queue) { it.playInSequence = value } } }
                    }
                }
                if (queue.undoAvailable) TextButton(onClick = { scope.launch {
                    player?.pause(false)
                    val restored = withContext(Dispatchers.IO) { undoListeningQueue() }
                    if (restored != null) PlaybackStarter(restored).shouldStreamThisTime(null).setStartNow(false).start(0)
                } }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.listening_undo)) }
                if (editing) {
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.playlist_done)) }
                    OrderingList(items.orEmpty(), queue)
                } else when {
                    items == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    items!!.isEmpty() -> ArchiveEmpty(R.string.listening_empty, R.string.listening_empty_hint, R.string.archive_browse_library) { selectPrimary(Library) }
                    else -> EpisodeLazyColumn(items!!, curQueue = queue)
                }
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = details, allowOpenFeed = true)
    }
}
