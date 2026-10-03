package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.EpisodeState
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xilinjia.krdb.Realm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ListeningLibraryPersistenceTest {
    private fun databaseTest(body: (Realm) -> Unit) {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "library-test-").toFile()
        val database = Realm.open(createRealmConfiguration(root.absolutePath))
        try { body(database) } finally { database.close(); root.deleteRecursively() }
    }

    @Test fun upgradesOlderProgressSchemaWithoutLosingPositions() {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "legacy-library-").toFile()
        val current = createRealmConfiguration(root.absolutePath)
        @Suppress("UNCHECKED_CAST")
        val schema = current.schema as Set<kotlin.reflect.KClass<out io.github.xilinjia.krdb.types.TypedRealmObject>>
        val old = io.github.xilinjia.krdb.RealmConfiguration.Builder(schema).directory(root.absolutePath).name("Podcini.realm").schemaVersion(168).build()
        try {
            var db = Realm.open(old)
            try { db.writeBlocking {
                copyToRealm(AppPrefs())
                copyToRealm(Episode().apply { id = 1; duration = 100000; position = 50000 })
            } } finally { db.close() }
            db = Realm.open(current)
            try {
                assertEquals(97, db.query(AppPrefs::class).first().find()!!.completionPercent)
                assertEquals(50000, db.query(Episode::class).first().find()!!.playedPosition)
                migrateListeningLibrary(db)
                assertNotNull(db.query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find())
            } finally { db.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun migrationKeepsSavedListsMediaProgressAndOneSession() = databaseTest { db ->
        db.writeBlocking {
            copyToRealm(AppPrefs().apply { twoPlayers = true })
            copyToRealm(Episode().apply { id = 42; title = "An episode"; position = 12345; addComment("My note", addition = false) })
            copyToRealm(PlayQueue().apply { id = 0; name = "Default" })
            copyToRealm(PlayQueue().apply { id = 8; name = "Commute" })
            copyToRealm(QueueEntry().apply { id = 101; queueId = 8; episodeId = 42; position = 10000 })
            copyToRealm(CurrentState().apply { id = 0; curMediaId = 42 })
        }
        migrateListeningLibrary(db)
        migrateListeningLibrary(db)
        assertEquals(1L, db.query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).count().find())
        assertEquals(listOf(42L), db.query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).find().map { it.episodeId })
        assertEquals(1L, db.query(QueueEntry::class, "queueId == 8").count().find())
        val item = db.query(Episode::class).first().find()!!
        assertEquals(12345, item.position); assertEquals("My note", item.comment)
        assertFalse(db.query(AppPrefs::class).first().find()!!.twoPlayers)
    }

    @Test fun legacyListSessionNeverClaimsAnUnrelatedPlaylistAsItsOrigin() = databaseTest { db ->
        db.writeBlocking {
            copyToRealm(Episode().apply { id = 42 })
            copyToRealm(CurrentState().apply { id = 0; curMediaId = 42 })
            copyToRealm(PlayQueue().apply { id = LISTENING_QUEUE_ID; name = "Virtual" })
            copyToRealm(QueueEntry().apply { id = 1; queueId = LISTENING_QUEUE_ID; episodeId = 42; originPlaylistId = 0 })
        }
        migrateListeningLibrary(db)
        assertEquals(-1L, db.query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).first().find()!!.originPlaylistId)
    }

    @Test fun sessionSnapshotsMoreThan50ItemsAndDoesNotFollowPlaylistEdits() = databaseTest { db ->
        migrateListeningLibrary(db)
        val media = db.writeBlocking { (1L..120L).map { copyToRealm(Episode().apply { id = it; title = "Item $it" }) } }
        db.writeBlocking {
            snapshotListeningSession(media, "First list", 0L, true, -1, 0)
            copyToRealm(QueueEntry().apply { id = 9000; queueId = 0; episodeId = 1; position = 10000 })
        }
        assertEquals(120L, db.query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).count().find())
        db.writeBlocking {
            delete(query(QueueEntry::class, "queueId == 0").find())
            snapshotListeningSession(media.reversed().take(3), "Second list", 55, true, 1, 34000)
        }
        val session = db.query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find()!!
        assertEquals(120, session.previousIds.size)
        assertEquals(34000, session.previousPosition)
        assertEquals(listOf(120L,119L,118L), db.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find().map { it.episodeId })
        assertTrue(session.undoAvailable)
        assertEquals(120L, db.query(Episode::class).count().find())
    }

    @Test fun completionRemovesOnlyTheOriginMembershipAndKeepsItemAndSession() = databaseTest { db ->
        migrateListeningLibrary(db)
        db.writeBlocking {
            copyToRealm(Episode().apply { id = 99; addComment("Keep me", addition = false) })
            copyToRealm(PlayQueue().apply { id = 7; name = "Keep" })
            listOf(0L, 7L, LISTENING_QUEUE_ID).forEachIndexed { i, listId -> copyToRealm(QueueEntry().apply {
                id = 100 + i.toLong(); queueId = listId; episodeId = 99
            }) }
        }
        runBlocking { completePlaylistItem(99, 0, db) }
        assertEquals(0L, db.query(QueueEntry::class, "queueId == 0").count().find())
        assertEquals(1L, db.query(QueueEntry::class, "queueId == 7").count().find())
        assertEquals(1L, db.query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).count().find())
        assertEquals("Keep me", db.query(Episode::class).first().find()!!.comment)
        assertNotNull(db.query(PlayQueue::class, "id == 0").first().find())
    }
}
