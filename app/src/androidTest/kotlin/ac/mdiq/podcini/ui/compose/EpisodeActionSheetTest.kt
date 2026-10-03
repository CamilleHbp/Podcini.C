package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.ui.actions.ActionButton
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises real gestures, playlist selection and persistence without starting playback. */
class EpisodeActionSheetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(80)
        }
        fail(message)
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun tap(text: String, longPress: Boolean = false) {
        await("Missing action: $text") { find(text) != null }
        val bounds = Rect().also { find(text)!!.getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN)
        if (longPress) Thread.sleep(700)
        event(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync(); Thread.sleep(350)
    }
    private fun capture(name: String) {
        val review = InstrumentationRegistry.getArguments().getString("actionReview") ?: return
        instrumentation.waitForIdleSync(); Thread.sleep(500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "actions-$review-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun longPressAndOverflowShareQuickActionsAndKeepAdvancedControls() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        await("App initialization") { AppConfig.isInitialized.value }
        val episode = runBlocking { upsert(Episode().apply {
            id = getEntityId(); title = "The art of listening"; parentTitle = "Everyday discoveries"; duration = 1_800_000
        }) {} }
        val playlist = runBlocking { createPlaylist("Weekend listening") }
        var selected = false
        main { activity.setContent { PodciniTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ArchiveEpisodeRow(episode, remember { ActionButton(episode) }, playing = false, current = false,
                        selected = false, selecting = false, isExternal = false, statusMode = StatusRowMode.Normal,
                        showActions = true, downloadProgress = null, onOpen = {}, onSelect = { selected = true }, onAction = null)
                }
            }
        } } }
        fun label(id: Int) = activity.getString(id)
        fun open() = tap(episode.title!!, longPress = true)
        fun member(queueId: Long) = realm.query(QueueEntry::class, "queueId == $0 AND episodeId == $1", queueId, episode.id).count().find() == 1L
        try {
            open()
            await("Quick actions visible") { find(label(R.string.episode_more_actions)) != null }
            assertNotNull(find(label(R.string.episode_favourite)))
            assertNotNull(find(label(R.string.playlist_listen_later)))
            assertNotNull(find(label(R.string.playlist_add)))
            assertNotNull(find(label(R.string.episode_add_listening_queue)))
            assertNull(find(label(R.string.set_rating_label)))
            assertFalse(selected)
            capture("quick")
            tap(label(R.string.episode_favourite))
            await("Favourite must persist") { episodeById(episode.id)!!.rating >= Rating.GOOD.code }
            open(); tap(label(R.string.episode_unfavourite))
            await("Unfavourite must persist") { episodeById(episode.id)!!.rating == Rating.UNRATED.code }
            open(); tap(label(R.string.playlist_listen_later))
            await("Listen later must save to its playlist") { member(0L) }
            assertFalse(member(LISTENING_QUEUE_ID))
            open()
            await("Saved state visible") { find(label(R.string.episode_in_listen_later)) != null }
            tap(label(R.string.playlist_add)); capture("playlist"); tap(playlist.name)
            await("Chosen playlist must receive the item") { member(playlist.id) }
            assertFalse(member(LISTENING_QUEUE_ID))
            tap(activity.getString(R.string.archive_episode_actions, episode.title))
            tap(label(R.string.episode_add_listening_queue))
            await("Listening queue must receive the item") { member(LISTENING_QUEUE_ID) }
            open()
            await("Queue membership visible") { find(label(R.string.episode_in_listening_queue)) != null }
            capture("saved")
            tap(label(R.string.episode_more_actions)); capture("more")
            repeat(4) {
                if (find(label(R.string.archive_select)) == null) {
                    fun scroll(node: AccessibilityNodeInfo?): Boolean {
                        if (node == null) return false
                        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                        for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                        return false
                    }
                    scroll(instrumentation.uiAutomation.rootInActiveWindow); Thread.sleep(400)
                }
            }
            tap(label(R.string.archive_select))
            await("Selection callback must work") { selected }
        } finally {
            runBlocking { realm.write {
                delete(query(QueueEntry::class, "episodeId == $0", episode.id).find())
                delete(query(Episode::class, "id == $0", episode.id).find())
                delete(query(PlayQueue::class, "id == $0", playlist.id).find())
            } }
            main { activity.finish() }
        }
    }
}
