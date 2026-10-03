package ac.mdiq.podcini.config.settings

import androidx.test.platform.app.InstrumentationRegistry
import ac.mdiq.podcini.storage.database.createRealmConfiguration
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.database.migrateListeningLibrary
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
        val backupConfig = createRealmConfiguration(backup.absolutePath)
        @Suppress("UNCHECKED_CAST")
        val schema = backupConfig.schema as Set<kotlin.reflect.KClass<out io.github.xilinjia.krdb.types.TypedRealmObject>>
        val legacyConfig = RealmConfiguration.Builder(schema).directory(backup.absolutePath).name("Podcini.realm").schemaVersion(168).build()
        val source = Realm.open(legacyConfig)
        try {
            source.writeBlocking {
                copyToRealm(AppPrefs().apply { customMediaUri = "/upstream/private/media"; useCustomMediaFolder = true })
                copyToRealm(Volume().apply { id = 24; name = "Science" })
                copyToRealm(Feed().apply { id = 10001; eigenTitle = "A followed podcast"; customTitle = "My podcast"; downloadUrl = "https://example.invalid/feed.xml"; volumeId = 24; tags.add("Saved") })
                copyToRealm(Feed().apply { id = 200; eigenTitle = "Field notes"; tags.add("Personal") })
                copyToRealm(PlayQueue().apply { id = 84; name = "Commute" })
                copyToRealm(QueueEntry().apply { id = 81; queueId = 84; episodeId = 2; position = 10000 })
                copyToRealm(QueueEntry().apply { id = 82; queueId = 84; episodeId = 1; position = 20000 })
                copyToRealm(Episode().apply { id = 1; feedId = 10001; title = "Same title"; position = 12345; fileUrl = "/upstream/one.mp3"; downloaded = true; setPlayState(EpisodeState.PROGRESS); addComment("Keep my note", addition = false); tags.add("Favourite passage") })
                copyToRealm(Episode().apply { id = 2; feedId = 200; title = "Same title"; setPlayState(EpisodeState.PLAYED); position = 67890; playedDuration = 90000; fileUrl = "/upstream/two.mp3"; downloaded = true })
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
            migrateListeningLibrary(restored)
            migrateListeningLibrary(restored)
            val migrated = restored.query(Episode::class, "id == $0", 1L).first().find()!!
            assertEquals(12345, migrated.playedPosition)
            assertEquals(EpisodeState.PROGRESS.code, migrated.playState)
            assertEquals(EpisodeState.PLAYED.code, second.playState)
            assertEquals(90000, second.playedDuration)
            assertEquals("Keep my note", migrated.comment)
            assertTrue(migrated.tags.contains("Favourite passage"))
            assertEquals(10001L, migrated.feedId)
            val podcast = restored.query(Feed::class, "id == 10001").first().find()!!
            assertEquals("My podcast", podcast.title)
            assertEquals("https://example.invalid/feed.xml", podcast.downloadUrl)
            assertTrue(podcast.tags.contains("Saved"))
            assertTrue(podcast.tags.contains("Science"))
            val collection = restored.query(PlayQueue::class, "identity == $0", "collection:200").first().find()!!
            assertEquals("Field notes", collection.name)
            assertTrue(collection.tags.contains("Personal"))
            assertEquals(listOf(2L), restored.query(QueueEntry::class, "queueId == $0", collection.id).find().map { it.episodeId })
            assertEquals(1L, restored.query(PlayQueue::class, "identity == $0", "collection:200").count().find())
            assertEquals("Commute", restored.query(PlayQueue::class, "id == 84").first().find()!!.name)
            assertEquals(listOf(2L, 1L), restored.query(QueueEntry::class, "queueId == 84 SORT(position ASC)").find().map { it.episodeId })
            assertEquals("first audio", File(first.fileUrl!!).readText())
            assertEquals("second audio", File(second.fileUrl!!).readText())
            assertTrue(first.downloaded && second.downloaded)
            val prefs = restored.query(AppPrefs::class).first().find()!!
            assertTrue(prefs.useCustomMediaFolder)
            assertEquals("clip audio", File(prefs.customMediaUri, "clips/recorded_1_01.20_favorite.m4a").readText())
            assertEquals(2, restored.query(Episode::class).find().size)
        } finally { restored.close() }
    }

    @Test fun browsePreferencesRulesAndAlbumMetadataSurviveFullRestore() = runBlocking {
        val filter = ac.mdiq.podcini.storage.model.LibraryFilter(scopeTag = "Science", excludedTags = listOf("Interview"), maxMinutes = 30)
        val preference = ac.mdiq.podcini.storage.model.LibraryBrowsePreferences("albums", listOf(
            ac.mdiq.podcini.storage.model.LibraryPin("saved-view", "Morning", ac.mdiq.podcini.storage.model.LibraryDestination("tag", tag = "Science", filter = filter))))
        val encoded = ac.mdiq.podcini.storage.model.libraryJson.encodeToString(ac.mdiq.podcini.storage.model.LibraryBrowsePreferences.serializer(), preference)
        val source = Realm.open(createRealmConfiguration(backup.absolutePath))
        try { source.writeBlocking {
            query(AppPrefs::class).first().find()!!.libraryBrowsePreferences = encoded
            query(PlayQueue::class, "id == 84").first().find()!!.apply { smart = true; ruleBrowseFilter = filter.encode() }
            query(Episode::class, "id == 1").first().find()!!.apply { albumArtist = "Various Artists"; discNumber = 2 }
        } } finally { source.close() }
        ComboRestore.restore(File(backup, "Podcini.realm").toUF(), media.toUF(), clips.toUF(), File(currentConfig.path), { destination })
        PreparedDatabaseRestore(File(currentConfig.path)).install()
        val restored = Realm.open(currentConfig)
        try {
            assertEquals(preference, ac.mdiq.podcini.storage.model.decodeBrowsePreferences(restored.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences))
            assertEquals(filter, ac.mdiq.podcini.storage.model.decodeLibraryFilter(restored.query(PlayQueue::class, "id == 84").first().find()!!.ruleBrowseFilter))
            assertEquals("Various Artists", restored.query(Episode::class, "id == 1").first().find()!!.albumArtist)
            assertEquals(2, restored.query(Episode::class, "id == 1").first().find()!!.discNumber)
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
