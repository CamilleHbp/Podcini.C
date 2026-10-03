package ac.mdiq.podcini.storage.tags

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.storage.model.FileTagValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class TagFileStat(val name: String, val size: Long, val modified: Long, val generation: Long = 0)

fun tagFileIdentity(location: String): String {
    if (location.isBlank()) return ""
    val uri = Uri.parse(location)
    return when (uri.scheme) {
        null, "", "file" -> "file:" + File(if (uri.scheme == "file") uri.path.orEmpty() else location).canonicalPath
        "content" -> runCatching { "document:${uri.authority}:${DocumentsContract.getDocumentId(uri)}" }.getOrElse { uri.normalizeScheme().toString() }
        else -> uri.normalizeScheme().toString()
    }
}

class TagFileAccess(val location: String) {
    val uri: Uri = Uri.parse(location)
    private val resolver get() = getAppContext().contentResolver
    val localFile: File? get() = when (uri.scheme) {
        null, "" -> File(location)
        "file" -> uri.path?.let(::File)
        else -> null
    }

    fun stat(): TagFileStat {
        localFile?.let { if (!it.isFile) throw FileNotFoundException("File unavailable"); return TagFileStat(it.name, it.length(), it.lastModified()) }
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(key: String): Long = cursor.getColumnIndex(key).takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                val modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it > 0 } ?: number("date_modified") * 1000
                return TagFileStat(name, number(OpenableColumns.SIZE), modified, number("generation_modified"))
            }
        }
        throw FileNotFoundException("File unavailable")
    }

    fun input(): InputStream = localFile?.inputStream() ?: resolver.openInputStream(uri) ?: throw IOException("File unavailable")
    fun hash(): String = input().use(::sha256)
    fun copyTo(target: File) { input().use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } } }

    fun read(): FileTagValues {
        localFile?.let { return NativeTagCodec.read(it) }
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            // TagLib detects containers from their contents when the descriptor has no extension.
            return NativeTagCodec.read(File("/proc/self/fd/${descriptor.fd}"))
        }
        throw IOException("File unavailable")
    }

    fun writeFrom(source: File) {
        val target = localFile
        if (target != null) {
            if (!target.canWrite() || target.parentFile?.canWrite() != true) throw SecurityException("File is not writable")
            val sibling = File.createTempFile(".podcini-tags-", ".tmp", target.parentFile)
            try {
                source.inputStream().use { input -> FileOutputStream(sibling).use { output -> input.copyTo(output); output.fd.sync() } }
                val permissions = Files.getPosixFilePermissions(target.toPath())
                Files.setPosixFilePermissions(sibling.toPath(), permissions)
                Files.move(sibling.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target.parentFile?.let(::syncTagDirectory)
            } finally { sibling.delete() }
        } else {
            resolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            } ?: throw IOException("File is not writable")
        }
    }
}

fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun File.tagHash(): String = inputStream().use(::sha256)

fun syncTagDirectory(directory: File) {
    require(directory.isDirectory)
    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}
