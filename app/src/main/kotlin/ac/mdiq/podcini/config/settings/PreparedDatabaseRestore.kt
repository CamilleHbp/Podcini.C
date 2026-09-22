package ac.mdiq.podcini.config.settings

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Only closed, validated databases reach this point. Installation runs before Realm opens. */
internal class PreparedDatabaseRestore(private val database: File) {
    private val pending = File(database.parentFile, "${database.name}.restore-ready")

    fun prepare(validatedDatabase: File) {
        check(!pending.exists()) { "A restore is already waiting for the app to restart" }
        if (!validatedDatabase.isFile || validatedDatabase.length() == 0L) throw IOException("The restored database is empty")
        RandomAccessFile(validatedDatabase, "rw").use { it.fd.sync() }
        Files.move(validatedDatabase.toPath(), pending.toPath(), ATOMIC_MOVE)
    }

    fun install() {
        if (!pending.exists()) return
        // Same-directory atomic replacement: an interrupted restart leaves either complete file.
        // Never delete or overwrite the database while the previous process has it open.
        Files.move(pending.toPath(), database.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
