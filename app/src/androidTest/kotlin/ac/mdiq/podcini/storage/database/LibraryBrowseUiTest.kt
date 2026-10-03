package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.ui.screens.*
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real database and native UI; all example library records are temporary fixtures. */
class LibraryBrowseUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            // The automation cache can retain the old app-bar label after a Compose destination change.
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (predicate()) return
            Thread.sleep(80)
        }
        if (InstrumentationRegistry.getArguments().getString("libraryBrowseReview") == "true") {
            fun describe(node: AccessibilityNodeInfo?): String = if (node == null) "" else
                "${node.text} | ${node.contentDescription} | visible=${node.isVisibleToUser}\n" +
                    (0 until node.childCount).joinToString("") { describe(node.getChild(it)) }
            File(instrumentation.targetContext.getExternalFilesDir(null), "browse-failure.txt").writeText(message + "\n" + describe(instrumentation.uiAutomation.rootInActiveWindow))
            capture("failure")
        }
        fail(message)
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } } }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(text: String) {
        waitUntil("Visible control: $text") { find(text)?.isVisibleToUser == true }
        val bounds = android.graphics.Rect(); find(text)!!.getBoundsInScreen(bounds)
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("libraryBrowseReview") != "true") return
        main { ac.mdiq.podcini.utils.toastMessagesFlow.value = emptyList(); ac.mdiq.podcini.ui.compose.commonMessage = null }
        instrumentation.waitForIdleSync(); Thread.sleep(1000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "browse-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun open(destination: LibraryDestination, expected: String) {
        main { openLibrary(destination) }
        waitUntil("Browser opened: $expected") { find(expected)?.isVisibleToUser == true }
        Thread.sleep(500)
    }

    @Test fun browsePinFilterAndTagBranchPreserveLibraryAndListeningSession() {
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val wasExempt = power.isIgnoringBatteryOptimizations(context.packageName)
        if (!wasExempt) shell("dumpsys deviceidle whitelist +${context.packageName}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil("Application initialization") { AppConfig.isInitialized.value }
        val previous = realm.copyFromRealm(realm.query(AppPrefs::class).first().find()!!)
        val locale = AppCompatDelegate.getApplicationLocales()
        val oldEpisodeTags = realm.query(Episode::class).find().associate { it.id to it.tags.toSet() }
        val oldFeedTags = realm.query(Feed::class).find().associate { it.id to it.tags.toSet() }
        val oldPlaylistTags = realm.query(PlayQueue::class).find().associate { it.id to Triple(it.tags.toSet(), it.ruleTag, it.ruleBrowseFilter) }
        val sessionEntries = realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find().map { it.episodeId }
        val feedId = ac.mdiq.podcini.shared.getEntityId()
        val mediaIds = (0..5).map { ac.mdiq.podcini.shared.getEntityId() }
        var smartId = 0L
        val review = InstrumentationRegistry.getArguments().getString("libraryBrowseReview") == "true"
        try {
            realm.writeBlocking {
                copyToRealm(Feed().apply { id = feedId; title = "The Observatory"; author = "Maya Chen"; keepUpdated = false; tags.add("Tags/Science/Astronomy") })
                listOf("Mapping the Moon", "Life in the deep ocean", "A guide to the night sky", "First light", "Blue hour", "Night walk").forEachIndexed { index, name ->
                    copyToRealm(Episode().apply {
                        id = mediaIds[index]; title = name; duration = 1_500_000; mimeType = "audio/mpeg"
                        downloadUrl = "https://example.invalid/browse-fixture-$index.mp3"
                        if (index == 0) fileUrl = "file:///browse-fixture-download.mp3"
                        if (index < 3) { this.feedId = feedId; contentKind = "podcast"; tags.add(if (index == 1) "Tags/Science/Biology" else "Tags/Study") }
                        else { contentKind = "music"; artist = "Maya Chen"; albumArtist = "Maya Chen"; album = "Night Studies"; trackNumber = index - 2; tags.add("Genre/Ambient") }
                    })
                }
                query(AppPrefs::class).first().find()!!.apply { theme = "system"; themeBlack = false; useDynamicThemes = false; libraryBrowsePreferences = "" }
            }
            main { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")); selectPrimary(Library, resetToRoot = true) }
            shell("cmd uimode night no"); shell("settings put system font_scale 1.0")
            runBlocking { updateLibraryPreferences { it.copy(pins = listOf(
                LibraryPin("fixture-science", "Science", LibraryDestination("tag", tag = "Tags/Science")),
                LibraryPin("fixture-music", "Evening music", LibraryDestination("items", filter = LibraryFilter(kind = "music"))))) } }
            open(LibraryDestination(), "Browse")
            capture("phone-library")
            assertTrue(decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).homeCards)
            click("List")
            waitUntil("List layout saved") { !decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).homeCards }
            waitUntil("List descriptions visible") { find("Your shows and their episodes")?.isVisibleToUser == true }
            capture("phone-library-list")
            click("Podcasts")
            waitUntil("List opens the podcast browser") { find("The Observatory")?.isVisibleToUser == true }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitUntil("Back retains the home list layout") { find("Your shows and their episodes")?.isVisibleToUser == true }
            click("Cards")
            waitUntil("Cards layout saved") { decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).homeCards }
            waitUntil("Cards replace list descriptions") { find("Your shows and their episodes") == null }
            assertEquals(2, decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).pins.size)
            click("Tags")
            click("Tags")
            click("Science")
            waitUntil("Nested tag children") { find("Astronomy") != null }
            capture("phone-tag")
            click("Astronomy")
            waitUntil("Inherited podcast tag reaches episodes") { find("Mapping the Moon") != null }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitUntil("Back restores parent tag") { find("Sub-tags") != null && find("Astronomy") != null }
            open(LibraryDestination("creator", creator = "Maya Chen"), "Maya Chen")
            capture("phone-creator")
            open(LibraryDestination("albums", grid = true), "Night Studies")
            click("Night Studies")
            waitUntil("Album tracks") { find("First light") != null }
            capture("phone-album")
            open(LibraryDestination("tag", tag = "Tags/Science"), "Science")
            click("Filter")
            waitUntil("Filter editor") { find("Quick filters") != null }
            click("Unfinished")
            capture("phone-filters")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            // The editor was cancelled; the browser has no applied Unfinished chip.
            waitUntil("Draft dismissed") { find("Quick filters") == null }
            assertNull(find("Unfinished"))
            click("Filter")
            waitUntil("Reopened editor discards the cancelled draft") { find(context.getString(ac.mdiq.podcini.R.string.filter_none_active)) != null }
            click("Unfinished")
            click("Show 3 results")
            waitUntil("Applied chip stays visible") { find("Unfinished") != null }
            click("Pin")
            click("Save")
            waitUntil("Pin persisted with filters") { decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).pins.any { it.destination.filter.unfinished } }
            open(LibraryDestination("tag", tag = "Tags/Science"), "Science")
            val rule = LibraryFilter(scopeTag = "Tags/Science", tags = listOf("Tags/Study"), creators = listOf("Maya Chen"), maxMinutes = 30)
            val saved = runBlocking { createPlaylist("Library browse fixture", rules = PlayQueue().apply { smart = true; ruleBrowseFilter = rule.encode() }) }
            smartId = saved.id
            realm.writeBlocking { query(PlayQueue::class, "id == $0", smartId).first().find()!!.tags.add("Tags/Science") }
            waitUntil("Source metadata has reached the feed index") { feedsMap[feedId] != null }
            val actual = playlistItems(saved).map { it.id }.filter { it in mediaIds }.toSet()
            assertEquals(setOf(mediaIds[0], mediaIds[2]), actual)
            val downloads = LibraryFilter(downloaded = true)
            open(LibraryDestination("tag", tag = "Tags/Science", filter = downloads.copy(descendants = false)), "Science")
            waitUntil("Child counts use the child's scope even in exact-tag mode") { find("1 media item") != null }
            assertNotNull(find("0 media items"))
            capture("phone-tag-filtered")
            click("Astronomy")
            waitUntil("Child count agrees with filtered results") { find("Mapping the Moon") != null && find("1 media item") != null }
            assertNull(find("Life in the deep ocean"))
            val creatorFilter = LibraryFilter(creators = listOf("Maya Chen"))
            val excludedFilter = LibraryFilter(excludedTags = listOf("Tags/Science/Biology"))
            for ((filter, label) in listOf(downloads to "Downloads", excludedFilter to "Exclude: Tags › Science › Biology",
                creatorFilter to "Creators: Maya Chen")) {
                open(LibraryDestination("tag", tag = "Tags/Science", filter = filter), "Science")
                waitUntil("Media filter applied: $label") { find(label) != null }
                click("Playlists")
                waitUntil("Playlist filters explicitly suspended") { find(context.getString(ac.mdiq.podcini.R.string.browse_playlist_filters_paused)) != null }
                assertNull(find(label))
                assertNull(find(context.getString(ac.mdiq.podcini.R.string.archive_clear_filters)))
                waitUntil("Tag scope still applies to playlists") { find("Library browse fixture") != null }
                if (filter == downloads) capture("phone-playlists-paused")
                click("Media")
                waitUntil("Media filter restored: $label") { find(label) != null && find("Mapping the Moon") != null }
            }
            open(LibraryDestination("tag", tag = "Tags/Science"), "Science")
            runBlocking { editLibraryTag("Tags/Science", "Tags/Learning/Science") }
            val after = realm.query(PlayQueue::class, "id == $0", smartId).first().find()!!
            assertEquals("Tags/Learning/Science", decodeLibraryFilter(after.ruleBrowseFilter)!!.scopeTag)
            val persisted = decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences)
            assertEquals("Tags/Learning/Science", persisted.pins.first().destination.tag)
            assertTrue(realm.query(Feed::class, "id == $0", feedId).first().find()!!.tags.contains("Tags/Learning/Science/Astronomy"))
            runBlocking { editLibraryTag("Tags/Learning/Science", "Tags/Science") }
            assertEquals(sessionEntries, realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find().map { it.episodeId })
            assertEquals(6, realm.query(Episode::class, "id IN $0", mediaIds).find().size)
            if (review) {
                shell("cmd uimode night yes"); shell("settings put system font_scale 1.3")
                main { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")) }
                open(LibraryDestination(), "Parcourir")
                capture("phone-library-fr-large-dark")
                click("Liste")
                waitUntil("French list visible") { find("Cartes")?.isVisibleToUser == true && !decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).homeCards }
                capture("phone-library-list-fr-large-dark")
                click("Cartes")
                waitUntil("French cards saved") { decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).homeCards }
                open(LibraryDestination("tag", tag = "Tags/Science"), "Science")
                capture("phone-fr-large-dark")
                click("Filtrer")
                capture("phone-fr-filters")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                shell("wm size 1600x2560"); shell("wm density 240"); shell("settings put system font_scale 1.0"); shell("cmd uimode night no")
                main { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                open(LibraryDestination(), "Browse")
                capture("tablet-library")
                open(LibraryDestination("tag", tag = "Tags/Science"), "Science")
                capture("tablet-tag")
            }
        } finally {
            if (review) { shell("wm size reset"); shell("wm density reset"); shell("settings put system font_scale 1.0"); shell("cmd uimode night no") }
            main { AppCompatDelegate.setApplicationLocales(locale); selectPrimary(Library, resetToRoot = true) }
            realm.writeBlocking {
                oldEpisodeTags.forEach { (id, tags) -> query(Episode::class, "id == $0", id).first().find()?.let { it.tags.clear(); it.tags.addAll(tags) } }
                oldFeedTags.forEach { (id, tags) -> query(Feed::class, "id == $0", id).first().find()?.let { it.tags.clear(); it.tags.addAll(tags) } }
                oldPlaylistTags.forEach { (id, saved) -> query(PlayQueue::class, "id == $0", id).first().find()?.let { it.tags.clear(); it.tags.addAll(saved.first); it.ruleTag = saved.second; it.ruleBrowseFilter = saved.third } }
                delete(query(QueueEntry::class, "queueId == $0", smartId).find())
                delete(query(PlayQueue::class, "id == $0", smartId).find())
                delete(query(Episode::class, "id IN $0", mediaIds).find())
                delete(query(Feed::class, "id == $0", feedId).find())
                query(AppPrefs::class).first().find()!!.apply { theme = previous.theme; themeBlack = previous.themeBlack; useDynamicThemes = previous.useDynamicThemes; libraryBrowsePreferences = previous.libraryBrowsePreferences }
            }
            if (!wasExempt) shell("dumpsys deviceidle whitelist -${context.packageName}")
            main { activity.finish() }
        }
    }
}
