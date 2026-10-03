package ac.mdiq.podcini.storage.tags

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.unmanaged
import ac.mdiq.podcini.storage.model.*
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

@Serializable
data class RetroTagExport(val format: String, val version: Int, val songs: List<RetroTagSong>)
@Serializable
data class RetroTagSong(val path: String, val size: Long = 0, val sha256: String? = null, val title: String = "",
    val artist: String = "", val album: String = "", val durationMs: Long = 0,
    val genres: List<String> = emptyList(), val moods: List<String> = emptyList(), val tags: List<String> = emptyList()) {
    fun paths(nested: Boolean, split: Boolean) = FileTagValues(genres, moods, tags).paths(split, nested)
}
@Serializable
data class RetroImportSession(val folder: String, val selected: Map<Int, Long> = emptyMap(), val completed: Set<Int> = emptySet(),
    val sourceFolder: String = "", val targetFolder: String = "", val nested: Boolean = false, val split: Boolean = false)
data class RetroMatch(val index: Int, val candidates: List<Long>, val certain: Boolean)

object RetroTagImport {
    private val root get() = File(getAppContext().filesDir, "retro-tag-imports").apply { mkdirs() }
    fun sessions(): List<RetroImportSession> = root.listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull {
        runCatching { libraryJson.decodeFromString<RetroImportSession>(File(it, "session.json").readText()) }.getOrNull()
    }
    fun document(session: RetroImportSession): RetroTagExport = libraryJson.decodeFromString(File(File(root, session.folder), "original.json").readText())
    fun save(session: RetroImportSession) {
        require(session.folder.matches(Regex("[a-f0-9-]+")))
        val atomic = AtomicFile(File(File(root, session.folder), "session.json"))
        val stream = atomic.startWrite()
        try { stream.write(libraryJson.encodeToString(session).toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    suspend fun open(uri: Uri): RetroImportSession = withContext(Dispatchers.IO) {
        val bytes = getAppContext().contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 32 * 1024 * 1024) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Cannot read export")
        require(bytes.size <= 32 * 1024 * 1024) { "Export exceeds size limit" }
        val export = libraryJson.decodeFromString<RetroTagExport>(bytes.toString(Charsets.UTF_8))
        require(export.format == "retro-music-tags" && export.version == 1)
        require(export.songs.size <= 100_000 && export.songs.all { it.path.isNotBlank() })
        val session = RetroImportSession(UUID.randomUUID().toString())
        val folder = File(root, session.folder).apply { mkdirs() }
        File(folder, "original.json").outputStream().use { it.write(bytes); it.fd.sync() }
        save(session)
        session
    }
    fun comparablePath(value: String): String {
        val uri = Uri.parse(value)
        val path = if (uri.scheme == "content") runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault(value) else uri.path ?: value
        return path.removePrefix("/storage/emulated/0/").removePrefix("/sdcard/").removePrefix("primary:").trimEnd('/')
    }
    suspend fun match(session: RetroImportSession): List<RetroMatch> = withContext(Dispatchers.IO) {
        val candidates = realm.query(Episode::class).find().map(::unmanaged).filter { MediaTagRepository.location(it).isNotBlank() }
            .distinctBy(MediaTagRepository::location)
        val hashes = mutableMapOf<String, String?>()
        document(session).songs.mapIndexed { index, song ->
            val path = if (session.sourceFolder.isNotEmpty() && (song.path == session.sourceFolder || song.path.startsWith(session.sourceFolder.trimEnd('/') + "/")))
                session.targetFolder.trimEnd('/') + song.path.removePrefix(session.sourceFolder.trimEnd('/')) else song.path
            val identical = if (song.sha256.isNullOrBlank()) emptyList() else candidates.filter { item ->
                val uri = MediaTagRepository.location(item)
                val access = TagFileAccess(uri)
                runCatching { (song.size == 0L || access.stat().size == song.size) &&
                    hashes.getOrPut(uri) { runCatching { access.hash() }.getOrNull() } == song.sha256 }.getOrDefault(false)
            }
            val byPath = candidates.filter { comparablePath(MediaTagRepository.location(it)) == comparablePath(path) }
            val descriptive = candidates.filter { song.title.isNotBlank() && song.artist.isNotBlank() &&
                it.title.equals(song.title, true) && it.artist.equals(song.artist, true) && it.album.equals(song.album, true) &&
                (song.durationMs == 0L || kotlin.math.abs(it.duration.toLong() - song.durationMs) < 1500) }
            val matches = identical.ifEmpty { byPath.ifEmpty { descriptive } }
            RetroMatch(index, matches.map { it.id }, identical.size == 1 || (song.sha256.isNullOrBlank() && byPath.size == 1))
        }
    }
    suspend fun apply(session: RetroImportSession, progress: (RetroImportSession) -> Unit): RetroImportSession = withContext(Dispatchers.IO) {
        var current = session
        save(current)
        val export = document(current)
        current.selected.forEach { (index, id) ->
            if (index !in current.completed && realm.query(Episode::class, "id == $0", id).first().find() != null) {
                MediaTagRepository.edit(listOf(id), add = export.songs[index].paths(current.nested, current.split))
                current = current.copy(completed = current.completed + index)
                save(current); progress(current)
            }
        }
        current
    }
}
