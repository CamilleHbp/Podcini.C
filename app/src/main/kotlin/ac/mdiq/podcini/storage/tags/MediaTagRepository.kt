package ac.mdiq.podcini.storage.tags

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.unmanaged
import ac.mdiq.podcini.storage.model.*
import android.content.Context
import android.net.Uri
import androidx.work.*
import io.github.xilinjia.krdb.MutableRealm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** All mutation paths share the same journal and per-file serialization. */
object MediaTagRepository {
    private val lock = Mutex()
    private val schedulingLock = Any()
    private var scheduled: Job? = null
    suspend fun serialized(block: suspend () -> Unit) = withContext(Dispatchers.IO) { lock.withLock { block() } }
    fun location(item: Episode): String = item.fileUrl.orEmpty().takeUnless {
        it.startsWith("http:") || it.startsWith("https:")
    }.orEmpty()
    fun key(item: Episode): String = tagFileIdentity(location(item)).ifBlank { "episode:${item.id}" }
    fun state(item: Episode): MediaTagState? = realm.query(MediaTagState::class, "key == $0", key(item)).first().find()
    fun values(value: String): FileTagValues = if (value.isBlank()) FileTagValues() else libraryJson.decodeFromString(value)
    private fun encoded(values: FileTagValues) = libraryJson.encodeToString(values)

    fun schedule() {
        synchronized(schedulingLock) {
            scheduled?.cancel()
            scheduled = ac.mdiq.podcini.PodciniApp.appIOScope.launch {
                delay(750)
                enqueueWorker()
            }
        }
    }

    private fun enqueueWorker() {
        val request = OneTimeWorkRequestBuilder<MediaTagWorker>().setInitialDelay(2, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(getAppContext()).enqueueUniqueWork("embedded-media-tags", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    suspend fun refresh(id: Long, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock { refreshLocked(id, force) }
    }

    private suspend fun refreshLocked(id: Long, force: Boolean) {
        val item = realm.query(Episode::class, "id == $0", id).first().find() ?: return
        val uri = location(item)
        if (uri.isBlank()) return
        val identity = key(item)
        val old = state(item)?.let(::unmanaged)
        // A pending stream edit follows its episode when the download becomes available.
        val waiting = realm.query(MediaTagState::class, "key == $0", "episode:$id").first().find()?.let(::unmanaged)
        try {
            require(item.mimeType?.startsWith("video/") != true) { "Unsupported video container" }
            val access = TagFileAccess(uri)
            val stat = access.stat()
            require(stat.name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b")) { "Unsupported audio container" }
            if (!force && old != null && old.baseline.isNotEmpty() && old.size == stat.size && old.modified == stat.modified &&
                old.generation == stat.generation && old.status == TagSaveStatus.SAVED.name) return
            val current = access.read()
            require(current.convention.isEmpty() || current.convention == "1") { "Unsupported tag path version" }
            val raw = encoded(current)
            realm.write {
                val target = query(Episode::class, "id == $0", id).first().find() ?: return@write
                val saved = query(MediaTagState::class, "key == $0", identity).first().find() ?: copyToRealm(MediaTagState().apply {
                    key = identity; episodeId = id; this.uri = uri
                })
                if (saved.writing) return@write
                if (waiting != null) {
                    saved.desired = waiting.desired; saved.previous = waiting.previous; saved.revision = maxOf(saved.revision, waiting.revision) + 1
                    saved.baseline = ""; saved.external = ""
                    saved.status = TagSaveStatus.PENDING.name
                    query(MediaTagState::class, "key == $0", waiting.key).first().find()?.let { delete(it) }
                }
                if (saved.desired.isNotBlank() && saved.status != TagSaveStatus.SAVED.name) {
                    if (saved.baseline.isNotBlank() && saved.baseline != raw) {
                        saved.external = raw; saved.status = TagSaveStatus.CONFLICT.name
                    } else if (saved.baseline.isBlank()) {
                        val removed = values(saved.previous).paths().map(::tagIdentity).toSet() - values(saved.desired).paths().map(::tagIdentity).toSet()
                        val merged = canonicalTags(current.paths().filterNot { tagIdentity(it) in removed } + values(saved.desired).paths())
                        saved.desired = encoded(FileTagValues.fromPaths(merged)); saved.baseline = raw
                        saved.status = TagSaveStatus.PENDING.name
                        target.tags.clear(); target.tags.addAll(merged)
                    }
                } else {
                    val imported = if (saved.baseline.isBlank()) canonicalTags(current.paths() + target.tags) else current.paths()
                    target.tags.clear(); target.tags.addAll(imported)
                    saved.baseline = raw; saved.desired = encoded(FileTagValues.fromPaths(imported))
                    // Existing database-only labels are retained, but migration never writes files.
                    saved.status = if (canonicalTags(imported) == canonicalTags(current.paths())) TagSaveStatus.SAVED.name else TagSaveStatus.PENDING.name
                }
                saved.size = stat.size; saved.modified = stat.modified; saved.generation = stat.generation; saved.error = ""
                val indexed = if (saved.status == TagSaveStatus.SAVED.name) current.paths() else values(saved.desired).paths()
                query(Episode::class).find().filter { key(it) == saved.key }.forEach { it.tags.clear(); it.tags.addAll(indexed) }
                query(AppAttribs::class).first().find()?.episodeTagSet?.addAll(indexed)
            }
        } catch (error: Exception) {
            realm.write {
                val saved = query(MediaTagState::class, "key == $0", identity).first().find() ?: copyToRealm(MediaTagState().apply {
                    key = identity; episodeId = id; this.uri = uri; desired = encoded(FileTagValues.fromPaths(item.tags))
                })
                saved.status = classify(error).name; saved.error = error.message.orEmpty()
            }
        }
        if (waiting != null || realm.query(MediaTagState::class, "key == $0", identity).first().find()?.status == TagSaveStatus.PENDING.name) schedule()
    }

    suspend fun edit(ids: Collection<Long>, add: Collection<String> = emptyList(), remove: Collection<String> = emptyList(),
                     replace: Collection<String>? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            ids.distinct().forEach { refreshLocked(it, true) }
            realm.write {
                ids.distinct().forEach { id -> query(Episode::class, "id == $0", id).first().find()?.let { item ->
                    val desired = replace ?: (item.tags.filterNot { existing -> remove.any { tagMatches(existing, it) } } + add)
                    queue(this, item, desired)
                } }
                query(AppPrefs::class).first().find()?.let { prefs ->
                    val recent = runCatching { libraryJson.decodeFromString<List<String>>(prefs.recentMediaTags) }.getOrDefault(emptyList())
                    prefs.recentMediaTags = libraryJson.encodeToString((canonicalTags(replace ?: add) + recent).distinctBy(::tagIdentity).take(30))
                }
            }
        }
        schedule()
    }

    /** Called inside the branch transaction too, so index and pending writes cannot diverge. */
    fun queue(db: MutableRealm, item: Episode, paths: Collection<String>) = with(db) {
        val tags = canonicalTags(paths)
        val state = query(MediaTagState::class, "key == $0", key(item)).first().find() ?: copyToRealm(MediaTagState().apply {
            key = key(item); episodeId = item.id; uri = location(item)
        })
        if (canonicalTags(item.tags) == tags && state.desired.isNotBlank()) return@with
        state.previous = encoded(FileTagValues.fromPaths(item.tags))
        state.desired = encoded(FileTagValues.fromPaths(tags)); state.revision++; state.error = ""
        if (!state.writing) state.status = when {
            state.uri.isBlank() -> TagSaveStatus.WAITING_FOR_FILE
            state.status == TagSaveStatus.UNSUPPORTED.name -> TagSaveStatus.UNSUPPORTED
            state.external.isNotBlank() -> TagSaveStatus.CONFLICT
            state.status == TagSaveStatus.NEEDS_ACCESS.name -> TagSaveStatus.NEEDS_ACCESS
            else -> TagSaveStatus.PENDING
        }.name
        val aliases = if (state.uri.isBlank()) listOf(item) else query(Episode::class).find().filter { key(it) == state.key }
        aliases.forEach { it.tags.clear(); it.tags.addAll(tags) }
        query(AppAttribs::class).first().find()?.episodeTagSet?.addAll(tags)
    }

    suspend fun resolve(key: String, paths: Collection<String>, reviewedExternal: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = realm.query(MediaTagState::class, "key == $0", key).first().find()?.let(::unmanaged) ?: return@withLock
            require(!saved.writing) { "Recovery is required before editing this file" }
            val current = TagFileAccess(saved.uri).read()
            if (encoded(current) != reviewedExternal) {
                realm.write { query(MediaTagState::class, "key == $0", key).first().find()?.external = encoded(current) }
                throw IOException("File changed again; review the latest tags")
            }
            realm.write {
                val state = query(MediaTagState::class, "key == $0", key).first().find() ?: return@write
                state.baseline = encoded(current); state.external = ""; state.error = ""
                state.previous = state.desired; state.desired = encoded(FileTagValues.fromPaths(paths)); state.revision++
                state.status = TagSaveStatus.PENDING.name
                query(Episode::class).find().filter { key(it) == state.key }.forEach { it.tags.clear(); it.tags.addAll(canonicalTags(paths)) }
            }
        }
        schedule()
    }

    suspend fun retry(key: String) {
        realm.write { query(MediaTagState::class, "key == $0", key).first().find()?.let {
            if (it.status !in listOf(TagSaveStatus.CONFLICT.name, TagSaveStatus.UNSUPPORTED.name)) { it.status = TagSaveStatus.PENDING.name; it.error = "" }
        } }
        schedule()
    }

    suspend fun repairAccess(tree: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            realm.query(MediaTagState::class).find().map(::unmanaged).forEach { saved ->
                val old = Uri.parse(saved.uri)
                if (old.scheme != "content" || old.authority != tree.authority) return@forEach
                val repaired = runCatching {
                    val id = android.provider.DocumentsContract.getDocumentId(old)
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).also {
                        getAppContext().contentResolver.openFileDescriptor(it, "rw")?.close() ?: throw IOException("Access unavailable")
                    }.toString()
                }.getOrNull() ?: return@forEach
                realm.write {
                    query(Episode::class).find().filter { key(it) == saved.key }.forEach {
                        it.fileUrl = repaired
                        if (it.downloadUrl?.startsWith("content:") == true) it.downloadUrl = repaired
                    }
                    query(MediaTagState::class, "key == $0", saved.key).first().find()?.let {
                        it.uri = repaired
                        if (it.status in listOf("NEEDS_ACCESS", "WAITING_FOR_FILE", "FAILED")) it.status = "PENDING"
                    }
                }
            }
        }
        schedule()
    }

    suspend fun undo(id: Long) {
        val item = realm.query(Episode::class, "id == $0", id).first().find() ?: return
        val previous = state(item)?.previous?.takeIf { it.isNotBlank() } ?: return
        edit(listOf(id), replace = values(previous).paths())
    }

    private fun classify(error: Exception): TagSaveStatus = when {
        error is SecurityException -> TagSaveStatus.NEEDS_ACCESS
        error.message?.contains("Unsupported", true) == true || error.message?.contains("encrypted", true) == true || error.message?.contains("Protected", true) == true -> TagSaveStatus.UNSUPPORTED
        error is java.io.FileNotFoundException -> TagSaveStatus.WAITING_FOR_FILE
        else -> TagSaveStatus.FAILED
    }

    private suspend fun fail(key: String, error: Exception) = realm.write {
        query(MediaTagState::class, "key == $0", key).first().find()?.let { it.status = classify(error).name; it.error = error.message.orEmpty() }
    }

    suspend fun runWrites(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            var deferred = false
            val states = realm.query(MediaTagState::class, "status == $0 OR writing == true", TagSaveStatus.PENDING.name).find().map(::unmanaged)
            for (state in states) {
                if (state.uri.isBlank()) continue
                if (theatres.any { it.mPlayerFlow.value?.curMediaFlow?.value?.let(::key) == state.key }) { deferred = true; continue }
                try {
                    if (state.writing) recover(state) else save(state)
                } catch (error: Exception) { fail(state.key, error) }
            }
            deferred
        }
    }

    private suspend fun save(state: MediaTagState) {
        val access = TagFileAccess(state.uri)
        val current = access.read()
        val raw = encoded(current)
        if (state.baseline.isBlank()) { refreshLocked(state.episodeId, true); schedule(); return }
        if (raw != state.baseline) {
            realm.write { query(MediaTagState::class, "key == $0", state.key).first().find()?.let {
                it.external = raw; it.status = TagSaveStatus.CONFLICT.name
            } }
            return
        }
        require(current.convention.isEmpty() || current.convention == "1") { "Unsupported tag path version" }
        val directory = File(getAppContext().filesDir, "tag-writes/${UUID.randomUUID()}").apply { mkdirs() }
        val stat = access.stat()
        if (stat.size < 0 || stat.size > (Long.MAX_VALUE - 16L * 1024 * 1024) / 3 || directory.usableSpace < stat.size * 3 + 16L * 1024 * 1024) {
            directory.delete(); throw IOException("Insufficient free space")
        }
        access.localFile?.parentFile?.let { if (it.usableSpace < stat.size + 8L * 1024 * 1024) throw IOException("Insufficient free space") }
        val backup = File(directory, "original")
        val stage = File(directory, "tagged")
        var journaled = false
        try {
            access.copyTo(backup)
            backup.copyTo(stage)
            // Read the copied baseline too: the source may have changed while it was being copied.
            if (encoded(NativeTagCodec.read(backup)) != raw) throw IOException("File changed during preparation")
            NativeTagCodec.write(stage, values(state.desired))
            java.io.RandomAccessFile(stage, "rw").use { it.fd.sync() }
            syncTagDirectory(directory)
            directory.parentFile?.let(::syncTagDirectory)
            val originalHash = backup.tagHash()
            val stagedHash = stage.tagHash()
            if (access.hash() != originalHash) throw IOException("File changed during preparation")
            realm.write { query(MediaTagState::class, "key == $0", state.key).first().find()?.let {
                it.backupPath = backup.path; it.stagedPath = stage.path; it.originalHash = originalHash
                it.stagedHash = stagedHash; it.writing = true; it.writeRevision = state.revision
            } }
            journaled = true
            access.writeFrom(stage)
            require(access.hash() == stagedHash && access.read() == values(state.desired)) { "Written file did not pass verification" }
            complete(state, access)
            directory.deleteRecursively()
        } finally { if (!journaled) directory.deleteRecursively() }
    }

    private suspend fun complete(state: MediaTagState, access: TagFileAccess) {
        val stat = access.stat()
        val current = encoded(access.read())
        realm.write { query(MediaTagState::class, "key == $0", state.key).first().find()?.let {
            it.baseline = current; it.external = ""; it.error = ""; it.writing = false
            it.backupPath = ""; it.stagedPath = ""; it.originalHash = ""; it.stagedHash = ""
            it.modified = stat.modified; it.size = stat.size; it.generation = stat.generation
            it.status = if (it.revision == state.revision) TagSaveStatus.SAVED.name else TagSaveStatus.PENDING.name
        } }
    }

    private suspend fun recover(state: MediaTagState) {
        val access = TagFileAccess(state.uri)
        when (access.hash()) {
            state.stagedHash -> {
                require(access.read() == NativeTagCodec.read(File(state.stagedPath))) { "Recovery verification failed" }
                val committed = unmanaged(state).apply { revision = writeRevision }
                complete(committed, access)
                File(state.backupPath).parentFile?.deleteRecursively()
            }
            state.originalHash -> {
                realm.write { query(MediaTagState::class, "key == $0", state.key).first().find()?.let {
                    it.writing = false; it.status = TagSaveStatus.PENDING.name
                    it.backupPath = ""; it.stagedPath = ""; it.originalHash = ""; it.stagedHash = ""
                } }
                File(state.backupPath).parentFile?.deleteRecursively(); schedule()
            }
            else -> throw IOException("Interrupted write: original backup retained; review before restoring")
        }
    }

    suspend fun restoreOriginal(key: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = realm.query(MediaTagState::class, "key == $0", key).first().find()?.let(::unmanaged) ?: return@withLock
            require(theatres.none { it.mPlayerFlow.value?.curMediaFlow?.value?.let(::key) == state.key }) { "File is in use by the player" }
            val backup = File(state.backupPath)
            require(state.writing && backup.isFile && backup.tagHash() == state.originalHash)
            val access = TagFileAccess(state.uri)
            access.writeFrom(backup)
            require(access.hash() == state.originalHash)
            recover(state)
        }
    }

    suspend fun saveCopy(id: Long, destination: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            val item = realm.query(Episode::class, "id == $0", id).first().find() ?: return@withLock
            require(tagFileIdentity(destination.toString()) != key(item))
            val stage = File.createTempFile("tag-copy-", ".media", getAppContext().cacheDir)
            try {
                TagFileAccess(location(item)).copyTo(stage)
                NativeTagCodec.write(stage, FileTagValues.fromPaths(item.tags))
                val target = TagFileAccess(destination.toString())
                target.writeFrom(stage)
                require(target.hash() == stage.tagHash() && target.read() == FileTagValues.fromPaths(item.tags))
            } finally { stage.delete() }
        }
    }
}

class MediaTagWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppConfig.initialize()
        return if (MediaTagRepository.runWrites()) Result.retry() else Result.success()
    }
}
