package ac.mdiq.podcini.config.settings

import androidx.test.platform.app.InstrumentationRegistry
import ac.mdiq.podcini.storage.database.createRealmConfiguration
import ac.mdiq.podcini.storage.model.AppPrefs
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.utils.UnifiedFile
import ac.mdiq.podcini.storage.utils.toUF
import io.github.xilinjia.krdb.Realm
import io.github.xilinjia.krdb.RealmConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Uses real Realm files and media copies, isolated from the installed app's library. */
class ComboRestoreTest {
    private lateinit var root: File
    private lateinit var currentConfig: RealmConfiguration
    private lateinit var backup: File
    private lateinit var media: File
    private lateinit var clips: File
    private lateinit var destination: File

    @Before fun setUp() {
        root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "restore-test-").toFile()
        currentConfig = createRealmConfiguration(File(root, "current").apply { mkdir() }.absolutePath)
        backup = File(root, "backup").apply { mkdir() }
        media = File(backup, "Podcini-MediaFiles").apply { mkdir() }
        clips = File(backup, "Podcini-Clips").apply { mkdir() }
        destination = File(root, "destination").apply { mkdir() }
        val original = Realm.open(currentConfig)
        try {
            original.writeBlocking { copyToRealm(Episode().apply { id = 99; title = "Original library" }) }
        } finally { original.close() }
        val source = Realm.open(createRealmConfiguration(backup.absolutePath))
        try {
            source.writeBlocking {
                copyToRealm(AppPrefs().apply { customMediaUri = "/upstream/private/media"; useCustomMediaFolder = true })
                copyToRealm(Episode().apply { id = 1; title = "Same title"; position = 12345; fileUrl = "/upstream/one.mp3"; downloaded = true })
                copyToRealm(Episode().apply { id = 2; title = "Same title"; position = 67890; fileUrl = "/upstream/two.mp3"; downloaded = true })
            }
        } finally { source.close() }
        val feed = File(media, "Podcast").apply { mkdir() }
        File(feed, "Same title.1.mp3").writeText("first audio")
        File(feed, "Same title.2.mp3").writeText("second audio")
        File(clips, "recorded_1_01.20_favorite.m4a").writeText("clip audio")
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun databaseMediaAndClipsRestoreTogetherWithoutTouchingOpenDatabase() = runBlocking {
        val live = Realm.open(currentConfig)
        try {
            ComboRestore.restore(File(backup, "Podcini.realm").toUF(), media.toUF(), clips.toUF(), File(currentConfig.path), { destination })
            assertEquals("Original library", live.query(Episode::class).first().find()!!.title)
            assertEquals(1, live.query(Episode::class).find().size)
        } finally { live.close() }

        // This is the exact activation used at the next process startup.
        PreparedDatabaseRestore(File(currentConfig.path)).install()
        val restored = Realm.open(currentConfig)
        try {
            val first = restored.query(Episode::class, "id == $0", 1L).first().find()!!
            val second = restored.query(Episode::class, "id == $0", 2L).first().find()!!
            assertEquals(12345, first.position)
            assertEquals(67890, second.position)
            assertEquals("first audio", File(first.fileUrl!!).readText())
            assertEquals("second audio", File(second.fileUrl!!).readText())
            assertTrue(first.downloaded && second.downloaded)
            val prefs = restored.query(AppPrefs::class).first().find()!!
            assertTrue(prefs.useCustomMediaFolder)
            assertEquals("clip audio", File(prefs.customMediaUri, "clips/recorded_1_01.20_favorite.m4a").readText())
            assertEquals(2, restored.query(Episode::class).find().size)
        } finally { restored.close() }
    }

    @Test fun corruptBackupNeverReplacesCurrentLibrary() = runBlocking {
        val corrupt = File(root, "broken.realm").apply { writeText("not a Realm database") }
        val failure = runCatching { ComboRestore.restore(corrupt.toUF(), media.toUF(), clips.toUF(), File(currentConfig.path), { destination }) }.exceptionOrNull()
        assertNotNull(failure)
        assertOriginalLibrary()
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test fun unreadableClipAfterMediaCopyDoesNotCommitOrLeavePartialFiles() = runBlocking {
        val unreadable = object : UnifiedFile by File(clips, "recorded_1_01.20_favorite.m4a").toUF() {
            override suspend fun copyTo(target: UnifiedFile) { throw IOException("Storage disconnected") }
        }
        val source = object : UnifiedFile by clips.toUF() {
            override suspend fun listChildren(): List<UnifiedFile> = listOf(unreadable)
        }
        val failure = runCatching {
            ComboRestore.restore(File(backup, "Podcini.realm").toUF(), media.toUF(), source, File(currentConfig.path), { destination })
        }.exceptionOrNull()
        assertEquals("Storage disconnected", failure?.message)
        assertOriginalLibrary()
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test fun databaseOnlyRestoreDoesNotRequireMediaStorage() = runBlocking {
        ComboRestore.restore(File(backup, "Podcini.realm").toUF(), null, null, File(currentConfig.path), { error("Media storage should not be accessed") })
        PreparedDatabaseRestore(File(currentConfig.path)).install()
        val restored = Realm.open(currentConfig)
        try { assertEquals(2, restored.query(Episode::class).find().size) } finally { restored.close() }
    }

    @Test fun newerSchemaIsRejectedBeforeChangingTheLibrary() = runBlocking {
        val futureConfig = RealmConfiguration.Builder(setOf(AppPrefs::class))
            .directory(File(root, "future").apply { mkdir() }.absolutePath)
            .name("future.realm").schemaVersion(999).build()
        Realm.open(futureConfig).close()
        val failure = runCatching {
            ComboRestore.restore(File(futureConfig.path).toUF(), media.toUF(), clips.toUF(), File(currentConfig.path), { destination })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertOriginalLibrary()
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    private fun assertOriginalLibrary() {
        PreparedDatabaseRestore(File(currentConfig.path)).install()
        val original = Realm.open(currentConfig)
        try { assertEquals("Original library", original.query(Episode::class).first().find()!!.title) } finally { original.close() }
    }
}
