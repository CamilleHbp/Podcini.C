package ac.mdiq.podcini.config.settings

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.storage.database.config
import ac.mdiq.podcini.storage.database.createRealmConfiguration
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.AppPrefs
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.utils.UnifiedFile
import ac.mdiq.podcini.storage.utils.clipsDir
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.storage.utils.mediaDir
import ac.mdiq.podcini.storage.utils.toUF
import io.github.xilinjia.krdb.Realm
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Restores selected content against the imported database, without replacing an open Realm. */
internal object ComboRestore {
    private val restoreLock = Mutex()

    suspend fun restore(
        database: UnifiedFile?,
        media: UnifiedFile?,
        clips: UnifiedFile?,
        activeDatabase: File = File(config.path),
        mediaStorage: () -> File = { getAppContext().getExternalFilesDir("media") ?: throw IOException("Media storage is unavailable") },
    ) {
        require(database != null || media != null || clips != null) { "Select something to restore" }
        check(restoreLock.tryLock()) { "A restore is already running" }
        try {
            if (database == null) {
                // A files-only restore updates the current library and needs no database swap.
                if (media != null) restoreMedia(media, mediaDir, realm)
                if (clips != null) restoreClips(clips, clipsDir, realm)
                return
            }

            val staging = Files.createTempDirectory(activeDatabase.parentFile!!.toPath(), "restore-").toFile()
            var restoredFiles: File? = null
            var prepared = false
            try {
                val stagedConfig = createRealmConfiguration(staging.absolutePath)
                database.copyTo(File(stagedConfig.path).toUF())
                currentCoroutineContext().ensureActive()
                // Opening the private copy validates compatibility and completes schema migration.
                val imported = Realm.open(stagedConfig)
                try {
                    if (media != null || clips != null) {
                        // Keep restored files separate from downloads still used by the running app.
                        val storage = mediaStorage()
                        val destination = Files.createTempDirectory(storage.toPath(), "restored-").toFile()
                        restoredFiles = destination
                        File(destination, ".nomedia").createNewFile()
                        val root = destination.toUF()
                        if (media != null) restoreMedia(media, root, imported)
                        if (clips != null) restoreClips(clips, root.createDirectory("clips"), imported)
                        imported.write {
                            val prefs = query(AppPrefs::class).first().find() ?: copyToRealm(AppPrefs())
                            prefs.useCustomMediaFolder = true
                            prefs.customMediaUri = destination.absolutePath
                            prefs.customFolderUnavailable = false
                        }
                    }
                } finally {
                    imported.close()
                }
                currentCoroutineContext().ensureActive()
                PreparedDatabaseRestore(activeDatabase).prepare(File(stagedConfig.path))
                prepared = true
            } finally {
                staging.deleteRecursively()
                if (!prepared) restoredFiles?.deleteRecursively()
            }
        } finally {
            restoreLock.unlock()
        }
    }

    private suspend fun restoreMedia(source: UnifiedFile, destination: UnifiedFile, database: Realm) {
        for (file in source.listChildren()) {
            currentCoroutineContext().ensureActive()
            if (file.isDirectory()) {
                // Clips have their own export and restore option.
                if (file.name != "clips") restoreMedia(file, destination.createDirectory(file.name), database)
                continue
            }
            val id = backupMediaEpisodeId(file.name) ?: continue
            val episode = database.query(Episode::class, "id == $0", id).first().find() ?: continue
            val target = copyFile(file, destination, episode.mimeType ?: "application/octet-stream")
            database.write {
                findLatest(episode)?.apply {
                    fileUrl = target.absPath
                    downloaded = true
                }
            }
        }
    }

    private suspend fun restoreClips(source: UnifiedFile, destination: UnifiedFile, database: Realm) {
        for (file in source.listChildren()) {
            currentCoroutineContext().ensureActive()
            if (file.isDirectory()) continue
            val id = backupClipEpisodeId(file.name) ?: continue
            val episode = database.query(Episode::class, "id == $0", id).first().find() ?: continue
            copyFile(file, destination, episode.mimeType ?: "application/octet-stream")
        }
    }

    private suspend fun copyFile(source: UnifiedFile, directory: UnifiedFile, mimeType: String): UnifiedFile {
        var target = directory / source.name
        if (!target.exists()) {
            target = directory.createFile(mimeType, source.name)
            try {
                source.copyTo(target)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        return target
    }
}
