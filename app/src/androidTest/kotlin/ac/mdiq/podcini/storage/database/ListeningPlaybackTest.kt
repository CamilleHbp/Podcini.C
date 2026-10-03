package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.playback.*
import ac.mdiq.podcini.storage.model.*
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xilinjia.krdb.UpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ListeningPlaybackTest {
    @Test fun mixedLocalQueueAdvancesCompletesOriginAndStops() {
        val inst = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 20000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(message, condition())
        }
        await("App initialized") { AppConfig.isInitialized.value }
        val saved = realm.copyFromRealm(realm.query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find()!!)
        val entries = realm.query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).find().map { realm.copyFromRealm(it) }
        val listId = ac.mdiq.podcini.shared.getEntityId()
        val ids = (0..1).map { ac.mdiq.podcini.shared.getEntityId() }
        val wave = File(inst.targetContext.cacheDir, "listening-playback-test.wav")
        val size = 16000 * 2 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        wave.outputStream().use { it.write(header.array()); it.write(ByteArray(size)) }
        try {
            val media = realm.writeBlocking {
                copyToRealm(PlayQueue().apply { id = listId; name = "Playback test"; removeWhenFinished = true })
                ids.mapIndexed { i, id -> copyToRealm(Episode().apply {
                    this.id = id; title = "Local test $i"; contentKind = if (i == 0) "podcast" else "music"
                    fileUrl = wave.absolutePath; mimeType = "audio/wav"; duration = 2000
                }) }.also { items ->
                    items.forEachIndexed { i, item -> copyToRealm(QueueEntry().apply { id = ac.mdiq.podcini.shared.getEntityId(); queueId = listId; episodeId = item.id; position = i * 10000L }) }
                    snapshotListeningSession(items, "Playback test", listId, true, -1, 0)
                }
            }
            refreshListeningQueue()
            val first = episodeById(ids.first())!!
            inst.runOnMainSync { PlaybackStarter(first).start() }
            await("First item completed") { episodeById(ids[0])!!.playbackCompletionTime > 0 }
            await("Second item completed") { episodeById(ids[1])!!.playbackCompletionTime > 0 }
            await("Origin playlist cleaned") { realm.query(QueueEntry::class, "queueId == $0", listId).count().find() == 0L }
            await("Playback stopped at the end") { theatres[0].mPlayerFlow.value?.isStopped == true }
            assertEquals(2L, realm.query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).count().find())
            assertTrue(wave.exists())

            // Starting another item from a list snapshots the player from an IO coroutine.
            // This must read ExoPlayer on main, before entering the Realm write transaction.
            var expectedCurrentId = -1L
            var expectedPosition = -1
            inst.runOnMainSync {
                val player = theatres[0].mPlayerFlow.value!!
                expectedCurrentId = player.curMediaFlow.value?.id ?: -1L
                expectedPosition = player.getPosition().coerceAtLeast(0)
            }
            runBlocking(Dispatchers.IO) { replaceListeningQueue(listOf(first), first.id, "Replacement test") }
            val replacement = realm.query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find()!!
            assertEquals(expectedCurrentId, replacement.previousCurrentId)
            assertEquals(expectedPosition, replacement.previousPosition)
            assertEquals(ids, replacement.previousIds.toList())
            assertEquals(listOf(first.id), replacement.entries.map { it.episodeId })
        } finally {
            inst.runOnMainSync { theatres[0].mPlayerFlow.value?.pause(false) }
            realm.writeBlocking {
                delete(query(QueueEntry::class, "queueId IN $0", listOf(LISTENING_QUEUE_ID, listId)).find())
                delete(query(PlayQueue::class, "id == $0", listId).find())
                delete(query(Episode::class, "id IN $0", ids).find())
                copyToRealm(saved, UpdatePolicy.ALL); entries.forEach { copyToRealm(it) }
            }
            refreshListeningQueue(); wave.delete()
        }
    }
}
