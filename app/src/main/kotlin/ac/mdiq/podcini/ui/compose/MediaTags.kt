package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.tags.MediaTagRepository
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

@Composable
fun tagLabel(path: String): String {
    val parts = decodeTagSegments(path).orEmpty()
    val root = when (parts.firstOrNull()) {
        "Genre" -> stringResource(R.string.file_tags_genre)
        "Mood" -> stringResource(R.string.file_tags_mood)
        "Tags" -> stringResource(R.string.tags_label)
        else -> parts.firstOrNull().orEmpty()
    }
    return (listOf(root) + parts.drop(1)).joinToString(" › ")
}

@Composable
fun tagLeafLabel(path: String): String = if (decodeTagSegments(path).orEmpty().size == 1) tagLabel(path) else decodeTagSegments(path)?.last().orEmpty()

@Composable
fun tagStatusLabel(status: String): String = stringResource(when (status) {
    "SAVED" -> R.string.file_tags_saved
    "PENDING" -> R.string.file_tags_pending
    "WAITING_FOR_FILE" -> R.string.file_tags_waiting
    "NEEDS_ACCESS" -> R.string.file_tags_access
    "CONFLICT" -> R.string.file_tags_conflict
    "UNSUPPORTED" -> R.string.file_tags_unsupported
    else -> R.string.file_tags_failed
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagTreeEditor(initial: List<Set<String>>, onDismiss: () -> Unit,
                          details: (@Composable () -> Unit)? = null,
                          onSave: (List<String>, List<String>) -> Unit) {
    val catalogue by remember { libraryCatalogueFlow() }.collectAsStateWithLifecycle(initialValue = LibraryCatalogue())
    val prefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    val colors = rememberTagColors()
    val recent = remember(prefs.recentMediaTags) { runCatching { libraryJson.decodeFromString<List<String>>(prefs.recentMediaTags) }.getOrDefault(emptyList()) }
    var parent by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var editingColor by rememberSaveable { mutableStateOf<String?>(null) }
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val all = remember(catalogue.tags, initial, added, recent) {
        (catalogue.tags + initial.flatten() + added + recent + TagKind.entries.map { it.root })
            .flatMap(::tagParents).distinctBy(::tagIdentity).sortedBy(::tagIdentity)
    }
    val counts = remember(initial) { initial.flatMap { it.distinctBy(::tagIdentity).map(::tagIdentity) }.groupingBy { it }.eachCount() }
    val addedKeys = added.map(::tagIdentity).toSet()
    val removedKeys = removed.map(::tagIdentity).toSet()
    fun selected(path: String): ToggleableState {
        val key = tagIdentity(path)
        if (key in removedKeys) return ToggleableState.Off
        if (key in addedKeys) return ToggleableState.On
        val count = counts[key] ?: 0
        return when { count == 0 -> ToggleableState.Off; count == initial.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
    }
    fun toggle(path: String) {
        val old = selected(path)
        added = added.filterNot { tagIdentity(it) == tagIdentity(path) }
        removed = removed.filterNot { tagIdentity(it) == tagIdentity(path) }
        if (old == ToggleableState.On) removed = removed + path else added = added + path
    }
    val chosen = (initial.flatten() + added).distinctBy(::tagIdentity).filter { selected(it) != ToggleableState.Off }.sortedBy(::tagIdentity)
    if (editingColor != null) TagColorPickerDialog(editingColor!!) { editingColor = null }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.file_tags_search)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Icon(Icons.Default.Close, stringResource(R.string.tag_clear_search))
                } })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!selectedOnly, { selectedOnly = false }, label = { Text(stringResource(R.string.tag_browse)) })
                FilterChip(selectedOnly, { selectedOnly = true }, label = { Text(stringResource(R.string.tag_selected_count, chosen.size)) })
            }
            if (!selectedOnly && search.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                if (parent.isNotBlank()) IconButton(onClick = { parent = encodeTagSegments(decodeTagSegments(parent).orEmpty().dropLast(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.file_tags_parent))
                }
                Text(if (parent.isBlank()) stringResource(R.string.tag_categories) else tagLabel(parent),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
        }
        Text(stringResource(R.string.tag_color_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val candidates = when {
            selectedOnly -> chosen
            search.isNotBlank() -> all.filter { TagPath.parse(it) != null }
            else -> libraryTagChildren(all, parent)
        }
        val rootLabels = mapOf("Genre" to stringResource(R.string.file_tags_genre), "Mood" to stringResource(R.string.file_tags_mood), "Tags" to stringResource(R.string.tags_label))
        val visible = candidates.filter { path ->
            val parts = decodeTagSegments(path).orEmpty()
            val label = (listOf(rootLabels[parts.firstOrNull()] ?: parts.firstOrNull().orEmpty()) + parts.drop(1)).joinToString(" ")
            search.isBlank() || label.contains(search, true) || path.contains(search, true)
        }
        val branches = remember(all) { all.flatMap { tagParents(it).dropLast(1) }.map(::tagIdentity).toSet() }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (initial.size > 1) item {
                Text(stringResource(R.string.tag_partial_hint), Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(visible, key = { it }) { path ->
                val hasChildren = tagIdentity(path) in branches || TagPath.parse(path) == null
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (TagPath.parse(path) != null) {
                        val label = tagLabel(path)
                        TriStateCheckbox(selected(path), onClick = { toggle(path) }, modifier = Modifier.semantics { contentDescription = label })
                    }
                    TagColorButton(path, colors) { editingColor = path }
                    val fullLabel = tagLabel(path)
                    Column(Modifier.weight(1f).clickable {
                        if (hasChildren && !selectedOnly && search.isBlank()) parent = path else toggle(path)
                    }.padding(vertical = 12.dp).semantics { contentDescription = fullLabel }) {
                        Text(tagLeafLabel(path), style = MaterialTheme.typography.bodyLarge)
                        if (selectedOnly || search.isNotBlank()) Text(tagLabel(encodeTagSegments(decodeTagSegments(path).orEmpty().dropLast(1))),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (hasChildren) IconButton(onClick = { parent = path; search = ""; selectedOnly = false }) {
                        Icon(Icons.Default.KeyboardArrowRight, stringResource(R.string.file_tags_open_branch))
                    }
                }
            }
            if (visible.isEmpty()) item {
                Text(stringResource(if (selectedOnly && search.isBlank()) R.string.tag_none_selected else R.string.tag_no_matches),
                    Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selectedOnly && search.isBlank() && parent.isNotBlank()) item {
                OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(stringResource(R.string.file_tags_new_child)) }, singleLine = true,
                    trailingIcon = { IconButton(enabled = normalizeTagSegment(newName).isNotBlank(), onClick = {
                        val path = encodeTagSegments(decodeTagSegments(parent).orEmpty() + normalizeTagSegment(newName))
                        if (TagPath.parse(path) != null) {
                            added = (added + path).distinctBy(::tagIdentity)
                            removed = removed.filterNot { tagIdentity(it) == tagIdentity(path) }
                            newName = ""
                        }
                    }) { Icon(Icons.Default.Add, stringResource(R.string.archive_add)) } })
            }
            if (!selectedOnly && search.isBlank() && parent.isBlank() && recent.isNotEmpty()) item {
                Text(stringResource(R.string.file_tags_recent), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.take(6).forEach { path ->
                        FilterChip(selected(path) == ToggleableState.On, { toggle(path) }, label = { Text(tagLabel(path)) },
                            leadingIcon = { TagColorDot(path, colors) })
                    }
                }
            }
            if (details != null) item {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(stringResource(if (showDetails) R.string.tag_hide_file_details else R.string.tag_file_details))
                }
                if (showDetails) details()
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) }
            Button(onClick = { onSave(added, removed) }) { Text(stringResource(R.string.confirm_label)) }
        }
    }
}

@Composable
fun TagSettingDialog(tagType: TagType, existingTags: Set<String>, multiples: Boolean = false, onDismiss: () -> Unit,
                     feedIds: List<Long> = emptyList(), onEmbeddingChoice: (Boolean) -> Unit = {}, cb: (List<String>) -> Unit) {
    var embedChoice by remember { mutableStateOf<List<String>?>(null) }
    val prefs by appPrefsFlow!!.collectAsStateWithLifecycle()
    fun save(tags: List<String>, embed: Boolean) {
        cb(tags)
        onEmbeddingChoice(embed)
        if (embed && feedIds.isNotEmpty()) runOnIOScope {
            val ids = realm.query(Episode::class).find().filter { it.feedId in feedIds }.map { it.id }
            MediaTagRepository.edit(ids, add = tags)
        }
        onDismiss()
    }
    if (embedChoice != null) AlertDialog(onDismissRequest = { embedChoice = null }, title = { Text(stringResource(R.string.file_tags_inheritance)) },
        text = { Text(stringResource(R.string.file_tags_embed_summary)) },
        confirmButton = { TextButton(onClick = { save(embedChoice!!, true) }) { Text(stringResource(R.string.file_tags_embed)) } },
        dismissButton = { TextButton(onClick = { save(embedChoice!!, false) }) { Text(stringResource(R.string.file_tags_keep_inherited)) } })
    else TagEditorDialog(onDismiss = onDismiss) {
            TagTreeEditor(listOf(existingTags), onDismiss) { added, removed ->
                val tags = canonicalTags(existingTags.filterNot { it in removed } + added)
                if (tagType == TagType.Feed && prefs.inheritedTagPolicy == "ask") embedChoice = tags
                else save(tags, tagType == TagType.Feed && prefs.inheritedTagPolicy == "embed")
            }
    }
}

@Composable
fun MediaTagsDialog(ids: List<Long>, onDismiss: () -> Unit) {
    val editorState = rememberSaveableStateHolder()
    var loaded by remember(ids) { mutableStateOf(false) }
    var initial by remember(ids) { mutableStateOf<List<Set<String>>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ids, refresh) {
        loaded = false; error = false
        try {
            ids.forEach { MediaTagRepository.refresh(it, true) }
            initial = ids.mapNotNull { realm.query(Episode::class, "id == $0", it).first().find()?.tags?.toSet() }
            loaded = true
        } catch (_: Exception) { error = true }
    }
    TagEditorDialog(onDismiss = { if (!saving) onDismiss() }, dismissEnabled = !saving) {
        if (!loaded || saving) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (error) {
                    Text(stringResource(R.string.file_tags_action_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.tag_retry)) }
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            if (error) Text(stringResource(R.string.file_tags_action_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            editorState.SaveableStateProvider("editor-$refresh") {
            TagTreeEditor(initial, onDismiss, details = {
                ids.forEach { id ->
                    val item = realm.query(Episode::class, "id == $0", id).first().find()
                    if (item != null) {
                        if (ids.size > 1) Text(item.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                        FileTagSaveControls(item, onChanged = { refresh++ })
                        item.feed?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                            Text(stringResource(R.string.file_tags_inherited), style = MaterialTheme.typography.labelSmall)
                            CompactTagList(tags, onTagClick = { ac.mdiq.podcini.ui.screens.openLibrary(LibraryDestination("tag", tag = it)); onDismiss() },
                                onShowAll = { ac.mdiq.podcini.ui.screens.openLibrary(LibraryDestination("tags")); onDismiss() })
                        }
                    }
                }
            }) { added, removed ->
                saving = true; error = false
                scope.launch {
                    try { MediaTagRepository.edit(ids, add = added, remove = removed); onDismiss() }
                    catch (_: Exception) { error = true } finally { saving = false }
                }
            }
            }
        }
    }
}

@Composable
fun FileTagSaveControls(item: Episode, onChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember { mutableStateOf(false) }
    var copySaved by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(false) }
    val state by remember(MediaTagRepository.key(item)) {
        realm.query(MediaTagState::class, "key == $0", MediaTagRepository.key(item)).first().asFlow().map { it.obj }
    }.collectAsStateWithLifecycle(initialValue = MediaTagRepository.state(item))
    fun action(block: suspend () -> Unit) { scope.launch { try { error = false; block(); onChanged() } catch (_: Exception) { error = true } } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) action {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        MediaTagRepository.repairAccess(uri)
        state?.let { MediaTagRepository.retry(it.key) }
    } }
    val mediaStore = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        state?.let { saved -> action { MediaTagRepository.retry(saved.key) } }
    }
    val copy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(item.mimeType ?: "application/octet-stream")) { uri ->
        if (uri != null) action { MediaTagRepository.saveCopy(item.id, uri); copySaved = true }
    }
    Text(tagStatusLabel(state?.status ?: if (item.fileUrl.isNullOrBlank()) "WAITING_FOR_FILE" else "PENDING"), style = MaterialTheme.typography.labelLarge)
    if (error) Text(stringResource(R.string.file_tags_action_failed), color = MaterialTheme.colorScheme.error)
    if (copySaved) Text(stringResource(R.string.file_tags_copy_saved))
    val saved = state
    if (saved != null) {
        if (saved.status != "SAVED") {
            TextButton(onClick = { action { MediaTagRepository.retry(saved.key) } }, enabled = saved.status != "CONFLICT") { Text(stringResource(R.string.file_tags_retry)) }
            if (saved.status == "NEEDS_ACCESS") TextButton(onClick = {
                val uri = Uri.parse(saved.uri)
                if (Build.VERSION.SDK_INT >= 30 && uri.authority == "media") {
                    runCatching { mediaStore.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender).build()) }
                        .onFailure { error = true }
                } else folder.launch(null)
            }) { Text(stringResource(R.string.file_tags_repair_access)) }
            if (saved.status == "CONFLICT") TextButton(onClick = { conflict = true }) { Text(stringResource(R.string.file_tags_review)) }
            if (saved.writing && saved.backupPath.isNotBlank()) TextButton(onClick = { restore = true }) { Text(stringResource(R.string.file_tags_restore)) }
            if (saved.uri.isNotBlank()) TextButton(onClick = { copy.launch(Uri.parse(saved.uri).lastPathSegment?.substringAfterLast('/') ?: "tagged-media") }) { Text(stringResource(R.string.file_tags_save_copy)) }
        }
        if (saved.previous.isNotBlank()) TextButton(onClick = { action { MediaTagRepository.undo(item.id) } }) { Text(stringResource(R.string.file_tags_undo)) }
        val original = runCatching { MediaTagRepository.values(saved.baseline) }.getOrNull()
        if (original != null && original.convention.isEmpty() && (original.genres + original.moods + original.tags).isNotEmpty())
            TextButton(onClick = { legacy = true }) { Text(stringResource(R.string.file_tags_legacy_review)) }
    }
    if (restore && saved != null) AlertDialog(onDismissRequest = { restore = false }, title = { Text(stringResource(R.string.file_tags_restore)) },
        text = { Text(stringResource(R.string.file_tags_restore_warning)) },
        confirmButton = { TextButton(onClick = { restore = false; action { MediaTagRepository.restoreOriginal(saved.key) } }) { Text(stringResource(R.string.confirm_label)) } },
        dismissButton = { TextButton(onClick = { restore = false }) { Text(stringResource(R.string.cancel_label)) } })
    if (conflict && saved != null) TagConflictDialog(saved, { conflict = false }) { paths ->
        conflict = false; action { MediaTagRepository.resolve(saved.key, paths, saved.external) }
    }
    if (legacy && saved != null) LegacyTagsDialog(MediaTagRepository.values(saved.baseline), { legacy = false }) { paths ->
        legacy = false; action { MediaTagRepository.edit(listOf(item.id), replace = paths) }
    }
}

@Composable
private fun TagConflictDialog(state: MediaTagState, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val file = MediaTagRepository.values(state.external).paths()
    val pending = MediaTagRepository.values(state.desired).paths()
    var merge by remember { mutableStateOf(false) }
    if (merge) TagEditorDialog(onDismiss = { merge = false }) {
        TagTreeEditor(listOf((file + pending).toSet()), onDismiss = { merge = false }) { add, remove ->
            onSave(canonicalTags((file + pending).filterNot { it in remove } + add))
        }
    } else CommonPopupCard(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.file_tags_conflict), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.file_tags_file_values), style = MaterialTheme.typography.titleSmall)
            file.forEach { Text(tagLabel(it)) }
            TextButton(onClick = { onSave(file) }) { Text(stringResource(R.string.file_tags_use_file)) }
            Text(stringResource(R.string.file_tags_pending_values), style = MaterialTheme.typography.titleSmall)
            pending.forEach { Text(tagLabel(it)) }
            TextButton(onClick = { onSave(pending) }) { Text(stringResource(R.string.file_tags_use_pending)) }
            TextButton(onClick = { merge = true }) { Text(stringResource(R.string.file_tags_merge)) }
        }
    }
}

@Composable
private fun LegacyTagsDialog(values: FileTagValues, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var split by remember { mutableStateOf(false) }
    var nested by remember { mutableStateOf(false) }
    CommonPopupCard(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.file_tags_legacy_review), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(split, { split = it }); Text(stringResource(R.string.file_tags_split_legacy)) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(nested, { nested = it }); Text(stringResource(R.string.file_tags_nested_legacy)) }
            values.paths(split, nested).forEach { Text(tagLabel(it)) }
            Button(onClick = { onSave(values.paths(split, nested)) }) { Text(stringResource(R.string.confirm_label)) }
        }
    }
}

@Composable
private fun TagEditorDialog(onDismiss: () -> Unit, dismissEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = dismissEnabled, dismissOnClickOutside = dismissEnabled)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.94f), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tags_label), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        IconButton(enabled = dismissEnabled, onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    }
                    Column(Modifier.weight(1f), content = content)
                }
            }
        }
    }
}
