package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.EnqueueLocation
import ac.mdiq.podcini.ui.actions.ActionButton
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.compose.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(id: Long) {
    if (id == LISTENING_QUEUE_ID) { ListenScreen(); return }
    if (id == -1L) { UnifiedLibraryScreen(); return }
    val playlist by remember(id) { realm.query(PlayQueue::class, "id == $0", id).asFlow().map { it.list.firstOrNull() } }.collectAsStateWithLifecycle(initialValue = null)
    val items by remember(id) { playlistItemsFlow(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val details = remember { MutableStateFlow<List<Episode>>(emptyList()) }
    LaunchedEffect(items) { details.value = items }
    BackHandler(episodeForInfo != null) { episodeForInfo = null }
    if (edit && playlist != null) PlaylistEditorDialog(existing = playlist) { edit = false }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.playlist_delete)) }, text = { Text(stringResource(R.string.playlist_delete_hint)) },
        confirmButton = { TextButton(onClick = {
            runOnIOScope { realm.write {
                query(Feed::class, "queueId == $0", id).find().forEach { it.queue = query(PlayQueue::class, "id == 0").first().find() }
                delete(query(QueueEntry::class, "queueId == $0", id).find())
                query(PlayQueue::class, "id == $0", id).first().find()?.let { delete(it) }
            } }; deleting = false; navBack()
        }) { Text(stringResource(R.string.playlist_delete)) } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel_label)) } })
    Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ArchiveTopBar(playlist?.displayName ?: stringResource(R.string.library_playlists), back = true) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.archive_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_edit)) }, onClick = { menu = false; edit = true })
                        if (playlist?.smart == false) DropdownMenuItem(text = { Text(stringResource(R.string.playlist_edit_order)) }, onClick = { menu = false; ordering = !ordering })
                        if (id != 0L) DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete)) }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(stringResource(if (playlist?.smart == true) R.string.playlist_rules_hint else if (playlist?.removeWhenFinished == true) R.string.playlist_cleanup_hint else R.string.playlist_keep_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = items.isNotEmpty(), onClick = {
                        val first = items.firstOrNull()
                        if (first != null) playLibraryItem(first) { replaceListeningQueue(items, first.id, playlist?.displayName.orEmpty(), id) }
                    }) { Text(stringResource(R.string.playlist_play)) }
                    TextButton(enabled = items.isNotEmpty(), onClick = { runOnIOScope { addToQueue(items, actQueueFlow.value, EnqueueLocation.BACK) } }) { Text(stringResource(R.string.archive_add_queue)) }
                }
                if (ordering) {
                    TextButton(onClick = { ordering = false }) { Text(stringResource(R.string.playlist_done)) }
                    playlist?.let { OrderingList(items, it) }
                } else if (items.isEmpty()) ArchiveEmpty(R.string.playlist_empty, R.string.playlist_empty_hint, R.string.archive_browse_library) { selectPrimary(Library) }
                else EpisodeLazyColumn(items, actionButtonCB = { item, _ -> replaceListeningQueue(items, item.id, playlist?.displayName.orEmpty(), id) })
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = details, allowOpenFeed = true)
    }
}

fun playLibraryItem(item: Episode, before: (suspend () -> Unit)? = null) {
    ActionButton(item, typeInit = if (item.availableLocalLocation != null) ButtonTypes.PLAY else ButtonTypes.STREAM).apply { beforePlayback = before }.onClick()
}

@Composable
fun OrderingList(items: List<Episode>, playlist: PlayQueue) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(item.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Row {
                    IconButton(enabled = index > 0, onClick = { runOnIOScope {
                        val ordered = items.toMutableList(); ordered.add(index - 1, ordered.removeAt(index)); persistOrdered(ordered, playlist.entries)
                    } }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.playlist_move_up, item.title.orEmpty())) }
                    IconButton(enabled = index < items.lastIndex, onClick = { runOnIOScope {
                        val ordered = items.toMutableList(); ordered.add(index + 1, ordered.removeAt(index)); persistOrdered(ordered, playlist.entries)
                    } }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.playlist_move_down, item.title.orEmpty())) }
                    IconButton(onClick = { runOnIOScope { removeFromQueue(playlist, listOf(item)) } }) { Icon(Icons.Default.Close, stringResource(R.string.playlist_remove_item, item.title.orEmpty())) }
                }
            }
        }
    }
}
