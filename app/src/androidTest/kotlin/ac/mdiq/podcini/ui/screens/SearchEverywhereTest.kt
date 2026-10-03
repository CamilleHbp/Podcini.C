package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.sourcing.feed.PodcastLibrary
import ac.mdiq.podcini.sourcing.searcher.*
import ac.mdiq.podcini.storage.database.*
import ac.mdiq.podcini.storage.model.*
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Search and acquisition use deterministic providers and a real RSS server, never the user's network library. */
class SearchEverywhereTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            Thread.sleep(80)
        }
        fail(message)
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("searchReview") != "true") return
        main { ac.mdiq.podcini.utils.toastMessagesFlow.value = emptyList(); ac.mdiq.podcini.ui.compose.commonMessage = null }
        instrumentation.waitForIdleSync(); Thread.sleep(1100)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "search-review-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
    } }

    @Test fun searchEntryFocusesInputAndShowsKeyboardInOneTap() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val powerManager = instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
        val wasExempt = powerManager.isIgnoringBatteryOptimizations(instrumentation.targetContext.packageName)
        if (!wasExempt) shell("dumpsys deviceidle whitelist +${instrumentation.targetContext.packageName}")
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("App initialization") { AppConfig.isInitialized.value }
        val oldQuery = SearchSession.query; val oldScope = SearchSession.scope
        val registry = PodcastSearcherRegistry.searcherInfos
        val oldProviders = registry.toList()
        val server = RssServer()
        fun imeVisible() = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        fun clickText(text: String) {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                val target = find(instrumentation.uiAutomation.rootInActiveWindow)
                if (target != null && target.isVisibleToUser) {
                    val bounds = android.graphics.Rect()
                    target.getBoundsInScreen(bounds)
                    val downTime = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                    }
                    return
                }
                Thread.sleep(80)
            }
            fail("No clickable search entry: $text")
        }
        fun assertReadyToType() {
            await("Search input should open the keyboard without a second tap") { imeVisible() }
            val original = SearchSession.query
            instrumentation.sendStringSync("x")
            await("Typing should reach the search field without tapping it") { SearchSession.query.length == original.length + 1 }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DEL)
            await("The typed character should be editable") { SearchSession.query == original }
        }
        try {
            registry.clear()
            registry.add(PodcastSearcherRegistry.SearcherInfo("Fixture", object : ItunesSearcher() {
                override suspend fun search(query: String) = emptyList<FeedSearchResult>()
            }, 1f))
            main { selectPrimary(Library, resetToRoot = true) }
            clickText(activity.getString(ac.mdiq.podcini.R.string.search_everywhere_hint))
            assertReadyToType()
            instrumentation.sendStringSync("focus")
            await("Typing should go directly into the search query") { SearchSession.query == "focus" }

            shell("input keyevent BACK")
            await("Back should dismiss the keyboard") { !imeVisible() }
            clickText(activity.getString(ac.mdiq.podcini.R.string.archive_search))
            assertReadyToType()

            shell("input keyevent BACK")
            await("Keyboard dismissed before opening a result") { !imeVisible() }
            main { navTo(OnlineFeed(server.url, "Fixture")) }
            instrumentation.waitForIdleSync(); Thread.sleep(600)
            main { assertTrue(navBack()) }
            instrumentation.waitForIdleSync(); Thread.sleep(600)
            main { assertFalse("Returning from a result should not request the keyboard again", imeVisible()) }

            main { navTo(OnlineFeed(server.url, "Fixture")); selectPrimary(Library) }
            clickText(activity.getString(ac.mdiq.podcini.R.string.search_everywhere_hint))
            await("Search shortcut should reach the input instead of a saved preview") { backStack.lastOrNull() == Search }
            assertReadyToType()

            main { selectPrimary(Library); navTo(Search) }
            assertReadyToType()
            main { selectPrimary(Library); navTo(FindFeeds) }
            assertReadyToType()
        } finally {
            main { requestEverywhereSearch(oldQuery, oldScope); selectPrimary(Listen, resetToRoot = true) }
            registry.clear(); registry.addAll(oldProviders)
            server.close()
            if (!wasExempt) shell("dumpsys deviceidle whitelist -${instrumentation.targetContext.packageName}")
        }
    }

    @Test fun discoveryAcquisitionAndNavigationPreserveTheLibrary() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("App initialization") { AppConfig.isInitialized.value }
        val originalLocale = AppCompatDelegate.getApplicationLocales()
        val registry = PodcastSearcherRegistry.searcherInfos
        val oldProviders = registry.toList()
        val oldQuery = SearchSession.query; val oldScope = SearchSession.scope; val oldLibraryOnly = SearchSession.libraryOnly
        val showId = ac.mdiq.podcini.shared.getEntityId()
        val episodeId = ac.mdiq.podcini.shared.getEntityId()
        val playlistId = ac.mdiq.podcini.shared.getEntityId()
        val sessionBefore = realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find().map { it.episodeId }
        val server = RssServer()
        var addedId: Long? = null
        var createdVm: EverywhereSearchVM? = null
        lateinit var vm: EverywhereSearchVM
        try {
            realm.writeBlocking {
                copyToRealm(Feed().apply { id = showId; eigenTitle = "Planet Money"; author = "NPR"; downloadUrl = "https://example.invalid/search-review-feed" })
                copyToRealm(Episode().apply { id = episodeId; feedId = showId; title = "Planet Money: The price of a good idea"; parentTitle = "Planet Money"; duration = 1500000; position = 12345; playedPosition = 20000; downloaded = true; fileUrl = "/preserved/download.mp3"; addComment("My saved note", addition = false) })
                copyToRealm(PlayQueue().apply { id = playlistId; name = "Planet Money favourites" })
            }
            val fixture = object : ItunesSearcher() {
                override val name = "Fixture"
                override suspend fun search(query: String): List<FeedSearchResult> {
                    delay(if (query == "slow") 1400 else 120)
                    if (query == "offline") throw java.io.IOException("Offline")
                    if (query == "no matches") return emptyList()
                    return listOf(
                        FeedSearchResult("Planet Money", null, "https://example.invalid/search-review-feed", "NPR", null, null, -1, name),
                        FeedSearchResult("Planet Money Summer School", null, server.url, "NPR", null, null, -1, name))
                }
            }
            registry.clear(); registry.add(PodcastSearcherRegistry.SearcherInfo("Fixture", fixture, 1f))
            main {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                selectPrimary(Library, resetToRoot = true)
                openEverywhereSearch("Planet Money")
                vm = EverywhereSearchVM(SavedStateHandle()); createdVm = vm
            }
            await("Local multi-word results") { !vm.local.loading && vm.local.podcasts.any { it.id == showId } && vm.local.episodes.any { it.id == episodeId } && vm.local.playlists.any { it.id == playlistId } }
            await("Automatic directory search without pressing submit") { vm.online.status == OnlineSearchStatus.Results }
            capture("phone-search")
            main { vm.changeQuery("slow") }
            Thread.sleep(550)
            main { vm.changeQuery("no matches") }
            await("Latest query wins over a slow request") { vm.online.status == OnlineSearchStatus.Empty }
            Thread.sleep(1000)
            assertEquals(OnlineSearchStatus.Empty, vm.online.status)
            main { vm.changeQuery("offline") }
            await("A failure is distinct from zero results") { vm.online.status == OnlineSearchStatus.Failed }
            main { vm.changeQuery("Planet Money"); vm.changeLibraryOnly(true) }
            await("Library-only scope suppresses directory search") { vm.online.status == OnlineSearchStatus.Idle && vm.local.podcasts.isNotEmpty() }
            main { vm.changeLibraryOnly(false) }
            await("Directory results resume without changing the query") { vm.online.status == OnlineSearchStatus.Results }
            val newShow = vm.online.podcasts.last()
            main { vm.addPodcast(newShow); vm.addPodcast(newShow) }
            await("Add saves the RSS feed exactly once") { vm.added[podcastUrlKey(server.url)] != null }
            addedId = vm.added[podcastUrlKey(server.url)]
            assertEquals(1L, realm.query(Feed::class, "downloadUrl == $0", server.url).count().find())
            val added = realm.query(Feed::class, "id == $0", addedId!!).first().find()!!
            assertFalse(added.autoDownload); assertFalse(added.autoEnqueue)
            assertTrue(realm.query(Episode::class, "feedId == $0", addedId!!).find().all { !it.downloaded })
            runBlocking {
                val duplicate = Feed().apply { eigenTitle = "Replace existing title?"; downloadUrl = "http://EXAMPLE.invalid/search-review-feed" }
                assertEquals(showId, PodcastLibrary.save(duplicate))
            }
            val existing = realm.query(Episode::class, "id == $0", episodeId).first().find()!!
            assertEquals(12345, existing.position); assertEquals(20000, existing.playedPosition)
            assertTrue(existing.downloaded); assertEquals("/preserved/download.mp3", existing.fileUrl); assertEquals("My saved note", existing.comment)
            assertEquals(sessionBefore, realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find().map { it.episodeId })
            main { openEverywhereSearch("Planet Money"); navTo(OnlineFeed(server.url, "Fixture")) }
            Thread.sleep(1500)
            capture("phone-preview")
            main { selectPrimary(Search, resetToRoot = true) }
            assertEquals("Planet Money", SearchSession.query)
            main { assertTrue(returnFromSearch()) }
            assertEquals(Library, primaryDestination())
            capture("phone-library")
            main { openEverywhereSearch("offline") }
            Thread.sleep(1100)
            capture("phone-offline")
            main { openEverywhereSearch("") }
            capture("phone-welcome")
            if (InstrumentationRegistry.getArguments().getString("searchReview") == "true") {
                shell("settings put system font_scale 1.3")
                shell("cmd uimode night yes")
                main { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")); openEverywhereSearch("Planet Money") }
                capture("phone-fr-large-dark")
                main { selectPrimary(Library, resetToRoot = true) }
                capture("phone-library-fr-large-dark")
                shell("wm size 1600x2560"); shell("wm density 240"); shell("settings put system font_scale 1.0"); shell("cmd uimode night no")
                main { openEverywhereSearch("Planet Money") }
                capture("tablet-fr-search")
            }
        } finally {
            createdVm?.let { main { it.viewModelScope.cancel() } }
            registry.clear(); registry.addAll(oldProviders)
            server.close()
            if (InstrumentationRegistry.getArguments().getString("searchReview") == "true") {
                shell("wm size reset"); shell("wm density reset"); shell("settings put system font_scale 1.0"); shell("cmd uimode night no")
            }
            main { AppCompatDelegate.setApplicationLocales(originalLocale); SearchSession.libraryOnly = oldLibraryOnly; requestEverywhereSearch(oldQuery, oldScope); selectPrimary(Listen, resetToRoot = true) }
            realm.writeBlocking {
                delete(query(Episode::class, "id == $0 OR feedId == $1", episodeId, addedId ?: -999L).find())
                delete(query(Feed::class, "id IN $0", listOf(showId, addedId ?: -999L)).find())
                delete(query(PlayQueue::class, "id == $0", playlistId).find())
            }
        }
    }

    @Test fun combinedSearchRetainsRelevancePartialSuccessAndCancellation() = runBlocking {
        fun result(title: String, url: String) = FeedSearchResult(title, null, url, "Publisher", null, null, -1, "Fixture")
        fun provider(name: String, block: suspend () -> List<FeedSearchResult>) = PodcastSearcherRegistry.SearcherInfo(name, object : ItunesSearcher() {
            override suspend fun search(query: String) = block()
        }, 1f)
        val sources = listOf(provider("A") { listOf(result("Z most relevant", "http://example.org/rss"), result("A less relevant", "https://example.org/other")) },
            provider("B") { listOf(result("Same show", "https://EXAMPLE.org/rss")) }, provider("Offline") { throw java.io.IOException("Offline") })
        val outcome = CombinedSearcher().searchOutcome("query", sources)
        assertFalse(outcome.failed); assertEquals(2, outcome.successfulSources)
        assertEquals(listOf("Offline"), outcome.failedSources)
        assertEquals(listOf("Z most relevant", "A less relevant"), outcome.results.map { it.title })
        assertTrue(CombinedSearcher().searchOutcome("query", sources.takeLast(1)).failed)
        var propagated = false
        try { CombinedSearcher().searchOutcome("query", listOf(provider("Cancelled") { throw CancellationException("New query") })) }
        catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }

    @Test fun processRestorationKeepsTheQueryUntilAFreshSearchRequest() {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("App initialization") { AppConfig.isInitialized.value }
        val oldQuery = SearchSession.query; val oldScope = SearchSession.scope
        val oldRevision = SearchSession.revision; val oldLibraryOnly = SearchSession.libraryOnly
        var restored: EverywhereSearchVM? = null
        try {
            main {
                SearchSession.query = ""; SearchSession.scope = SearchScope.All; SearchSession.revision = 0
                val saved = SavedStateHandle(mapOf("query" to "Saved search", "scope" to SearchScope.Podcasts.name,
                    "libraryOnly" to true, "revision" to 99, "processToken" to "previous-process"))
                restored = EverywhereSearchVM(saved).also { vm ->
                    vm.acceptLaunch()
                    assertEquals("Saved search", vm.query)
                    assertEquals(SearchScope.Podcasts, vm.scope)
                    assertTrue(vm.libraryOnly)
                    requestEverywhereSearch("New explicit search", SearchScope.All)
                    vm.acceptLaunch()
                    assertEquals("New explicit search", vm.query)
                    assertEquals(SearchScope.All, vm.scope)
                }
            }
        } finally {
            main {
                restored?.viewModelScope?.cancel()
                SearchSession.query = oldQuery; SearchSession.scope = oldScope
                SearchSession.revision = oldRevision; SearchSession.libraryOnly = oldLibraryOnly
            }
        }
    }

    private class RssServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:${socket.localPort}/feed.xml"
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) try {
                socket.accept().use { connection ->
                    val input = connection.getInputStream().bufferedReader()
                    val request = input.readLine().orEmpty()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val body = """<?xml version="1.0"?><rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Planet Money Summer School</title><link>https://example.invalid/summer</link><description>A fresh way to understand the economy. Explore ideas behind the everyday decisions we make.</description><itunes:author>NPR</itunes:author><item><title>The economics of a summer afternoon</title><guid>search-review-episode</guid><description>A lesson in opportunity cost.</description><enclosure url="https://example.invalid/summer.mp3" length="1234" type="audio/mpeg"/></item></channel></rss>""".toByteArray()
                    val output = connection.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/rss+xml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!request.startsWith("HEAD")) output.write(body)
                    output.flush()
                }
            } catch (_: java.io.IOException) { }
        }
        override fun close() { socket.close(); worker.join(1000) }
    }
}
