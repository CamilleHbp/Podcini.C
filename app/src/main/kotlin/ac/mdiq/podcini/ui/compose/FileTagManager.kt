package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.tags.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FileTagManagerDialog(onDismiss: () -> Unit) {
    val prefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    val states by remember { realm.query(MediaTagState::class).asFlow().map { it.list.toList() } }.collectAsStateWithLifecycle(initialValue = emptyList())
    var editor by remember { mutableStateOf<Long?>(null) }
    var session by remember { mutableStateOf<RetroImportSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) { scope.launch { busy = true; error = false
        try { block() } catch (_: Exception) { error = true } finally { busy = false }
    } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action { session = RetroTagImport.open(uri) }
    }
    if (editor != null) MediaTagsDialog(listOf(editor!!)) { editor = null }
    if (session != null) RetroImportDialog(session!!, { session = null }) { session = it }
    CommonPopupCard(onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.file_tags_settings), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.file_tags_inheritance), style = MaterialTheme.typography.titleSmall)
                listOf("ask" to R.string.file_tags_ask, "keep" to R.string.file_tags_keep_inherited, "embed" to R.string.file_tags_embed).forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { runOnIOScope { upsert(prefs) { it.inheritedTagPolicy = value } } }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(prefs.inheritedTagPolicy == value, null); Text(stringResource(label))
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Text(stringResource(R.string.file_tags_action_failed), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/json", "text/*")) }) { Text(stringResource(R.string.file_tags_retro_import)) }
                TextButton(enabled = !busy, onClick = { session = RetroTagImport.sessions().firstOrNull { saved -> saved.completed.size < RetroTagImport.document(saved).songs.size } }) {
                    Text(stringResource(R.string.file_tags_retro_pending))
                }
                TextButton(enabled = !busy, onClick = { action { withContext(Dispatchers.IO) {
                    realm.query(Episode::class).find().map { it.id }.forEach { MediaTagRepository.refresh(it, true) }
                } } }) { Text(stringResource(R.string.file_tags_refresh)) }
                TextButton(enabled = !busy, onClick = { action { undoTagBranch() } }) { Text(stringResource(R.string.file_tags_undo)) }
                Text(stringResource(R.string.file_tags_progress), style = MaterialTheme.typography.titleSmall)
                Text("${states.count { it.status == "SAVED" }} / ${states.size}", style = MaterialTheme.typography.bodySmall)
                if (states.none { it.status != "SAVED" }) Text(stringResource(R.string.file_tags_no_pending))
            }
            items(states.filter { it.status != "SAVED" }, key = { it.key }) { state ->
                val episode = realm.query(Episode::class, "id == $0", state.episodeId).first().find()
                ListItem(headlineContent = { Text(episode?.title.orEmpty()) }, supportingContent = { Text(tagStatusLabel(state.status)) },
                    modifier = Modifier.clickable { editor = state.episodeId })
            }
            item { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
        }
    }
}

@Composable
private fun RetroImportDialog(initial: RetroImportSession, onDismiss: () -> Unit, onChange: (RetroImportSession) -> Unit) {
    var session by remember(initial.folder) { mutableStateOf(initial) }
    val export = remember(initial.folder) { RetroTagImport.document(initial) }
    var matches by remember { mutableStateOf<List<RetroMatch>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(initial.sourceFolder) }
    var target by remember { mutableStateOf(initial.targetFolder) }
    var pick by remember { mutableStateOf<Int?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun change(next: RetroImportSession) { session = next; RetroTagImport.save(next); onChange(next) }
    LaunchedEffect(generation) {
        busy = true
        try {
            matches = RetroTagImport.match(session)
            val automatic = matches.filter { it.certain && it.index !in session.completed }.associate { it.index to it.candidates.single() }
            change(session.copy(selected = automatic + session.selected))
        } catch (_: Exception) { error = true } finally { busy = false }
    }
    if (pick != null) {
        val index = pick!!
        var search by remember { mutableStateOf("") }
        val all = remember { realm.query(Episode::class).find().filter { MediaTagRepository.location(it).isNotBlank() }.map(::unmanaged) }
        AlertDialog(onDismissRequest = { pick = null }, title = { Text(stringResource(R.string.file_tags_retro_select)) }, text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.library_search)) })
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(all.filter { search.isBlank() || it.title.orEmpty().contains(search, true) || it.fileUrl.orEmpty().contains(search, true) }) { item ->
                        ListItem(headlineContent = { Text(item.title.orEmpty()) }, supportingContent = { Text(item.fileUrl.orEmpty()) },
                            modifier = Modifier.clickable { change(session.copy(selected = session.selected + (index to item.id))); pick = null })
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { pick = null }) { Text(stringResource(R.string.cancel_label)) } })
    }
    CommonPopupCard(onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.file_tags_retro_matches), style = MaterialTheme.typography.titleLarge)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Text(stringResource(R.string.file_tags_action_failed), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(source, { source = it }, label = { Text(stringResource(R.string.file_tags_retro_source)) })
                OutlinedTextField(target, { target = it }, label = { Text(stringResource(R.string.file_tags_retro_target)) })
                TextButton(enabled = !busy, onClick = { change(session.copy(sourceFolder = source, targetFolder = target, selected = emptyMap())); generation++ }) { Text(stringResource(R.string.file_tags_retro_remap)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(session.nested, { change(session.copy(nested = it)) }); Text(stringResource(R.string.file_tags_nested_legacy)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(session.split, { change(session.copy(split = it)) }); Text(stringResource(R.string.file_tags_split_legacy)) }
            }
            items(export.songs.indices.toList()) { index ->
                val song = export.songs[index]
                val candidate = session.selected[index]?.let { realm.query(Episode::class, "id == $0", it).first().find() }
                Column {
                    Text(song.title.ifBlank { song.path }, style = MaterialTheme.typography.titleSmall)
                    Text(song.path, style = MaterialTheme.typography.bodySmall)
                    song.paths(session.nested, session.split).forEach { Text(tagLabel(it), style = MaterialTheme.typography.bodySmall) }
                    when {
                        index in session.completed -> Text(stringResource(R.string.file_tags_retro_done))
                        candidate != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(true, { change(session.copy(selected = session.selected - index)) })
                            Text(candidate.fileUrl.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Text(stringResource(if (matches.firstOrNull { it.index == index }?.candidates.orEmpty().size > 1) R.string.file_tags_retro_ambiguous else R.string.file_tags_retro_unmatched))
                    }
                    if (index !in session.completed) TextButton(enabled = !busy, onClick = { pick = index }) { Text(stringResource(R.string.file_tags_retro_select)) }
                    HorizontalDivider()
                }
            }
            item {
                Button(enabled = !busy && session.selected.keys.any { it !in session.completed }, onClick = {
                    scope.launch { busy = true; try { session = RetroTagImport.apply(session) { progress -> scope.launch { session = progress; onChange(progress) } } }
                        catch (_: Exception) { error = true } finally { busy = false } }
                }) { Text(stringResource(R.string.file_tags_retro_apply)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
