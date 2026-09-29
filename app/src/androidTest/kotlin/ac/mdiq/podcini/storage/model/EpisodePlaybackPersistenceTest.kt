package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.storage.database.createRealmConfiguration
import ac.mdiq.podcini.storage.specs.EpisodeState
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xilinjia.krdb.Realm
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EpisodePlaybackPersistenceTest {
    @Test fun resumedProgressAndCompletionSurviveStaleSnapshotsAndReopening() {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "playback-test-").toFile()
        val config = createRealmConfiguration(root.absolutePath)
        try {
            val database = Realm.open(config)
            try {
                val paused = database.writeBlocking {
                    copyToRealm(Episode().apply {
                        id = 1
                        duration = 70 * 60_000
                        recordPlaybackProgress(53 * 60_000, 97, now = 1_000)
                    })
                }
                database.writeBlocking {
                    findLatest(paused)!!.recordPlaybackProgress(68 * 60_000, 97, now = 2_000)
                }
                assertEquals(53 * 60_000, paused.position)
                database.writeBlocking {
                    // Completion can receive the snapshot that existed before the final position write.
                    val latest = findLatest(paused)!!
                    assertEquals(68 * 60_000, latest.position)
                    latest.recordPlaybackProgress(latest.position, 97, ended = true, now = 3_000)
                }
            } finally { database.close() }
            val reopened = Realm.open(config)
            try {
                val saved = reopened.query(Episode::class).first().find()!!
                assertEquals(0, saved.position)
                assertEquals(70 * 60_000, saved.playedPosition)
                assertEquals(100, saved.playedPercentage)
                assertEquals(EpisodeState.PLAYED.code, saved.playState)
                assertEquals(3_000L, saved.lastPlayedTime)
                assertEquals(3_000L, saved.playbackCompletionTime)
            } finally { reopened.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun thresholdDefaultsTo97AndCustomValueSurvivesReopening() {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "threshold-test-").toFile()
        val config = createRealmConfiguration(root.absolutePath)
        try {
            val database = Realm.open(config)
            try {
                database.writeBlocking {
                    val prefs = copyToRealm(AppPrefs())
                    assertEquals(97, prefs.completionPercent)
                    prefs.completionPercent = 100
                }
            } finally { database.close() }
            val reopened = Realm.open(config)
            try { assertEquals(100, reopened.query(AppPrefs::class).first().find()!!.completionPercent) }
            finally { reopened.close() }
        } finally { root.deleteRecursively() }
    }
}
