package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.tags.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xilinjia.krdb.Realm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaTagPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(extension: String): File {
        val file = File.createTempFile("native-tags-", ".$extension", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("media-tags/fixture.$extension").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
    private val paths = listOf("Genre/Rock/Alternative", "Genre/Jazz", "Mood/Calm", "Tags/Activity/Focus", "Tags/AC\\/DC", "Tags/a\\\\b", "Tags/Français/\uD83C\uDFB5", "Tags/one, two; three")

    @Test fun nativeJniRoundTripsAllContainersAndReconstructsWithoutADatabase() {
        listOf("mp3", "flac", "ogg", "opus", "m4a", "m4b").forEach { extension ->
            val file = fixture(extension)
            try {
                val expected = FileTagValues.fromPaths(paths)
                NativeTagCodec.write(file, expected)
                assertEquals(extension, expected, NativeTagCodec.read(file))
                val copied = File(file.parentFile, "fresh-${file.name}")
                try {
                    file.copyTo(copied)
                    assertEquals(canonicalTags(paths), NativeTagCodec.read(copied).paths())
                    android.os.ParcelFileDescriptor.open(copied, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        assertEquals(expected, NativeTagCodec.read(File("/proc/self/fd/${fd.fd}")))
                    }
                    NativeTagCodec.write(copied, FileTagValues.fromPaths(emptyList()))
                    assertTrue(NativeTagCodec.read(copied).paths().isEmpty())
                } finally { copied.delete() }
            } finally { file.delete() }
        }
    }

    @Test fun migrationPreservesIdsHistoryReferencesAndDoesNotEnqueueFileWrites() {
        val root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "tag-migration-").toFile()
        val db = Realm.open(createRealmConfiguration(root.path))
        try {
            db.writeBlocking {
                copyToRealm(AppPrefs().apply { libraryBrowsePreferences = libraryJson.encodeToString(LibraryBrowsePreferences(
                    emptyTags = listOf("Study/Space"), pins = listOf(LibraryPin("pin", "Study", LibraryDestination("tag", tag = "Study"))))) })
                copyToRealm(Episode().apply { id = 900; position = 12345; tags.add("Study/Space") })
                copyToRealm(Feed().apply { id = 901; tags.add("Study") })
                copyToRealm(PlayQueue().apply { id = 902; ruleTag = "Study"; ruleBrowseFilter = LibraryFilter(tags = listOf("Study")).encode() })
            }
            migrateTagPaths(db); migrateTagPaths(db)
            val item = db.query(Episode::class).first().find()!!
            assertEquals(900L, item.id); assertEquals(12345, item.position)
            assertEquals(setOf("Tags/Study/Space"), item.tags.toSet())
            assertEquals("Tags/Study", db.query(PlayQueue::class).first().find()!!.ruleTag)
            assertEquals(listOf("Tags/Study"), decodeLibraryFilter(db.query(PlayQueue::class).first().find()!!.ruleBrowseFilter)!!.tags)
            assertEquals("Tags/Study", decodeBrowsePreferences(db.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).pins.single().destination.tag)
            assertEquals(0L, db.query(MediaTagState::class).count().find())
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun pendingWritesCoalesceByPhysicalIdentityAndSurviveDatabaseReopen() {
        val root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "tag-jobs-").toFile()
        var db = Realm.open(createRealmConfiguration(root.path))
        try {
            db.writeBlocking {
                val item = copyToRealm(Episode().apply { id = 1; fileUrl = "/storage/emulated/0/Music/a.mp3"; position = 33 })
                copyToRealm(Episode().apply { id = 2; fileUrl = "file:///storage/emulated/0/Music/a.mp3" })
                copyToRealm(Episode().apply { id = 3; fileUrl = "/storage/emulated/0/Other/a.mp3" })
                repeat(10) { MediaTagRepository.queue(this, item, listOf("Tags/Revision$it")) }
            }
            db.close(); db = Realm.open(createRealmConfiguration(root.path))
            assertEquals(1L, db.query(MediaTagState::class).count().find())
            val pending = db.query(MediaTagState::class).first().find()!!
            assertEquals(10L, pending.revision)
            assertEquals(listOf("Tags/Revision9"), MediaTagRepository.values(pending.desired).paths())
            assertEquals(setOf("Tags/Revision9"), db.query(Episode::class, "id == 2").first().find()!!.tags.toSet())
            assertTrue(db.query(Episode::class, "id == 3").first().find()!!.tags.isEmpty())
            assertEquals(33, db.query(Episode::class, "id == 1").first().find()!!.position)
            db.writeBlocking {
                val stream = copyToRealm(Episode().apply { id = 4; downloadUrl = "https://example.invalid/audio.mp3" })
                MediaTagRepository.queue(this, stream, listOf("Tags/Focus"))
            }
            assertEquals("WAITING_FOR_FILE", db.query(MediaTagState::class, "episodeId == 4").first().find()!!.status)
            assertNull(db.query(Episode::class, "id == 4").first().find()!!.fileUrl)
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun sameSizeExternalEditConflictsAndRecoveryRetainsOriginal() = runBlocking {
        AppConfig.initialize()
        val file = fixture("mp3")
        val id = 8_730_001L
        var journalFolder: File? = null
        try {
            NativeTagCodec.write(file, FileTagValues.fromPaths(listOf("Tags/First")))
            realm.write { copyToRealm(Episode().apply { this.id = id; fileUrl = file.path; mimeType = "audio/mpeg" }) }
            MediaTagRepository.refresh(id, true)
            realm.write { MediaTagRepository.queue(this, query(Episode::class, "id == $0", id).first().find()!!, listOf("Tags/Local")) }
            val size = file.length(); val modified = file.lastModified()
            NativeTagCodec.write(file, FileTagValues.fromPaths(listOf("Tags/Other")))
            assertEquals(size, file.length()); file.setLastModified(modified)
            MediaTagRepository.refresh(id, true)
            val item = realm.query(Episode::class, "id == $0", id).first().find()!!
            assertEquals("CONFLICT", MediaTagRepository.state(item)!!.status)
            assertEquals(setOf("Tags/Local"), item.tags.toSet())
            assertEquals(listOf("Tags/Other"), NativeTagCodec.read(file).paths())

            journalFolder = Files.createTempDirectory(instrumentation.targetContext.filesDir.toPath(), "tag-recovery-").toFile()
            val backup = File(journalFolder, "original"); file.copyTo(backup)
            val stage = File(journalFolder, "stage"); file.copyTo(stage)
            NativeTagCodec.write(stage, FileTagValues.fromPaths(listOf("Tags/Local")))
            realm.write { query(MediaTagState::class, "key == $0", MediaTagRepository.key(item)).first().find()!!.apply {
                baseline = libraryJson.encodeToString(NativeTagCodec.read(backup)); desired = libraryJson.encodeToString(NativeTagCodec.read(stage))
                backupPath = backup.path; stagedPath = stage.path; originalHash = backup.tagHash(); stagedHash = stage.tagHash()
                writing = true; writeRevision = revision; status = "PENDING"; external = ""
            } }
            file.writeBytes(byteArrayOf(1, 2, 3))
            MediaTagRepository.runWrites()
            assertTrue(backup.isFile); assertEquals("FAILED", MediaTagRepository.state(item)!!.status)
            assertTrue(MediaTagRepository.state(item)!!.writing)
            // A crash after the final copy but before database acknowledgement is idempotently recovered.
            stage.copyTo(file, overwrite = true)
            MediaTagRepository.runWrites()
            assertEquals("SAVED", MediaTagRepository.state(item)!!.status)
            assertEquals(listOf("Tags/Local"), NativeTagCodec.read(file).paths())
            assertFalse(backup.exists())
        } finally {
            realm.write {
                delete(query(MediaTagState::class, "episodeId == $0", id).find())
                delete(query(Episode::class, "id == $0", id).find())
            }
            file.delete(); journalFolder?.deleteRecursively()
        }
    }

    @Test fun stagedSavePartialFailureAndDownloadedPendingTags() = runBlocking {
        AppConfig.initialize()
        val good = fixture("flac")
        val denied = fixture("mp3")
        val ids = listOf(8_730_011L, 8_730_012L, 8_730_013L)
        try {
            realm.write {
                copyToRealm(Episode().apply { id = ids[0]; fileUrl = good.path; mimeType = "audio/flac" })
                copyToRealm(Episode().apply { id = ids[1]; fileUrl = denied.path; mimeType = "audio/mpeg" })
                val stream = copyToRealm(Episode().apply { id = ids[2]; downloadUrl = "https://example.invalid/no-download.mp3" })
                MediaTagRepository.queue(this, stream, listOf("Tags/Waiting"))
            }
            ids.take(2).forEach { MediaTagRepository.refresh(it, true) }
            val original = denied.tagHash()
            denied.setWritable(false, false)
            realm.write { ids.take(2).forEach { id -> MediaTagRepository.queue(this, query(Episode::class, "id == $0", id).first().find()!!, paths) } }
            MediaTagRepository.runWrites()
            val success = realm.query(Episode::class, "id == $0", ids[0]).first().find()!!
            val failure = realm.query(Episode::class, "id == $0", ids[1]).first().find()!!
            assertEquals("SAVED", MediaTagRepository.state(success)!!.status)
            assertEquals(canonicalTags(paths), NativeTagCodec.read(good).paths())
            assertEquals("NEEDS_ACCESS", MediaTagRepository.state(failure)!!.status)
            assertEquals(original, denied.tagHash())
            assertEquals(canonicalTags(paths).toSet(), failure.tags.toSet())
            assertNull(realm.query(Episode::class, "id == $0", ids[2]).first().find()!!.fileUrl)
            // Attaching a completed download adopts the pending stream edit without downloading itself.
            realm.write { query(Episode::class, "id == $0", ids[2]).first().find()!!.fileUrl = good.path }
            MediaTagRepository.refresh(ids[2], true)
            MediaTagRepository.runWrites()
            assertTrue(NativeTagCodec.read(good).paths().contains("Tags/Waiting"))
            assertNull(realm.query(MediaTagState::class, "key == $0", "episode:${ids[2]}").first().find())
        } finally {
            denied.setWritable(true, false)
            realm.query(MediaTagState::class).find().filter { it.episodeId in ids }.forEach { if (it.backupPath.isNotBlank()) File(it.backupPath).parentFile?.deleteRecursively() }
            realm.write {
                query(MediaTagState::class).find().filter { it.episodeId in ids }.forEach { delete(it) }
                query(Episode::class).find().filter { it.id in ids }.forEach { delete(it) }
            }
            good.delete(); denied.delete()
        }
    }
}
