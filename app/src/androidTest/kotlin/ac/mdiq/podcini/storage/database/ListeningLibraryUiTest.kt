package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.ui.screens.*
import android.content.Intent
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xilinjia.krdb.UpdatePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ListeningLibraryUiTest {
    @Test fun browsingSavedListsLeavesTheSessionUntouched() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = System.currentTimeMillis() + 15000
        while (!AppConfig.isInitialized.value && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(AppConfig.isInitialized.value)
        val session = realm.copyFromRealm(realm.query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find()!!)
        val entries = realm.query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).find().map { realm.copyFromRealm(it) }
        val locale = AppCompatDelegate.getApplicationLocales()
        val playlistId = ac.mdiq.podcini.shared.getEntityId()
        val ids = (0..2).map { ac.mdiq.podcini.shared.getEntityId() }
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("libraryReview") != "true") return
            instrumentation.waitForIdleSync(); Thread.sleep(900)
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            val media = realm.writeBlocking {
                listOf("The quiet side of the Moon", "A walk through the night sky", "Blue in Green").mapIndexed { i, title ->
                    copyToRealm(Episode().apply { id = ids[i]; this.title = title; parentTitle = "The Observatory"; duration = 1800000
                        downloadUrl = "https://example.invalid/library-review-$i.mp3"; mimeType = "audio/mpeg"
                        contentKind = if (i == 2) "music" else "podcast"; if (i == 2) { artist = "Miles Davis"; album = "Kind of Blue" }
                    })
                }.also { media ->
                    copyToRealm(PlayQueue().apply { id = playlistId; name = "Morning listening"; removeWhenFinished = true })
                    media.forEachIndexed { i, item -> copyToRealm(QueueEntry().apply { id = ac.mdiq.podcini.shared.getEntityId(); queueId = playlistId; episodeId = item.id; position = i * 10000L }) }
                    snapshotListeningSession(media, "Morning listening", playlistId, true, -1, 0)
                }
            }
            refreshListeningQueue()
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")); selectPrimary(Listen, resetToRoot = true) }
            capture("library-review-phone-listen")
            instrumentation.runOnMainSync { selectPrimary(Library, resetToRoot = true) }
            capture("library-review-phone-library")
            instrumentation.runOnMainSync { navTo(Queues(playlistId)) }
            capture("library-review-phone-playlist")
            assertEquals(ids, realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find().map { it.episodeId })
            if (InstrumentationRegistry.getArguments().getString("libraryReview") == "true") {
                instrumentation.uiAutomation.executeShellCommand("wm size 1600x2560").close()
                instrumentation.uiAutomation.executeShellCommand("wm density 240").close()
                Thread.sleep(800)
                instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")); selectPrimary(Listen, resetToRoot = true) }
                capture("library-review-tablet-fr")
            }
        } finally {
            if (InstrumentationRegistry.getArguments().getString("libraryReview") == "true") {
                instrumentation.uiAutomation.executeShellCommand("wm size reset").close()
                instrumentation.uiAutomation.executeShellCommand("wm density reset").close()
            }
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(locale); selectPrimary(Listen, resetToRoot = true) }
            realm.writeBlocking {
                delete(query(QueueEntry::class, "queueId IN $0", listOf(LISTENING_QUEUE_ID, playlistId)).find())
                delete(query(PlayQueue::class, "id == $0", playlistId).find())
                delete(query(Episode::class, "id IN $0", ids).find())
                copyToRealm(session, UpdatePolicy.ALL)
                entries.forEach { copyToRealm(it) }
            }
            refreshListeningQueue()
        }
    }
}
