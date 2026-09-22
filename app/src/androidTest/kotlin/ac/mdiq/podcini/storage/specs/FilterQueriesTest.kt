package ac.mdiq.podcini.storage.specs

import ac.mdiq.podcini.storage.database.createRealmConfiguration
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.ui.utils.SearchAlgo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xilinjia.krdb.Realm
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FilterQueriesTest {
    private lateinit var directory: File
    private lateinit var database: Realm

    @Before fun setup() {
        directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "filter-tests-").toFile()
        database = Realm.open(createRealmConfiguration(directory.absolutePath))
        database.writeBlocking {
            copyToRealm(Episode().apply { id = 1; feedId = 1001; title = "Listener's guide"; duration = 600_000; setPlayState(EpisodeState.UNPLAYED); fileUrl = "/one.mp3"; tags.add("Listener's picks") })
            copyToRealm(Episode().apply { id = 2; feedId = 1001; title = "Long conversation"; duration = 4_000_000; setPlayState(EpisodeState.PROGRESS) })
            copyToRealm(Episode().apply { id = 3; feedId = 1002; title = "Other source"; duration = 600_000; setPlayState(EpisodeState.PLAYED); fileUrl = "/three.mp3" })
            copyToRealm(Feed().apply { id = 1001; title = "The Listening Room"; langSet.add("en"); tags.add("Listener's picks") })
            copyToRealm(Feed().apply { id = 1002; title = "Conversations"; langSet.add("fr"); queue = null })
        }
    }
    @After fun cleanup() {
        database.close()
        if (InstrumentationRegistry.getArguments().getString("keepFilterFixture") == "true") {
            File(directory, "Podcini.realm").copyTo(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "filter-screenshot-fixture.realm"), overwrite = true)
        }
        directory.deleteRecursively()
    }

    private fun ids(filter: EpisodeFilterDraft, scope: String = "id > 0") = database.query(Episode::class).query("($scope) AND (${filter.toFilter().queryString()})").find().map { it.id }.toSet()

    @Test fun anyGroupMatchingStaysInsideTheCurrentSourceOrDownloads() {
        val draft = EpisodeFilterDraft(setOf("UNPLAYED", "downloaded"), andOr = "OR")
        assertEquals(setOf(1L), ids(draft, "feedId == 1001"))
        assertEquals(setOf(1L, 3L), ids(draft, "fileUrl != nil"))
    }
    @Test fun multiSelectionAndOutsideDurationRangesKeepTheirMeaning() {
        val draft = EpisodeFilterDraft(setOf("UNPLAYED", "PROGRESS", "lower", "higher"), durationFloor = 900_000, durationCeiling = 3_600_000)
        assertEquals(setOf(1L, 2L), ids(draft))
        assertEquals(setOf(1L, 2L, 3L), ids(EpisodeFilterDraft()))
    }
    @Test fun quotesInTitleAndTagsAreLiteralAndCanBeExcluded() {
        assertEquals(setOf(1L), ids(EpisodeFilterDraft(setOf("title_include"), titleText = "Listener's")))
        assertEquals(setOf(2L, 3L), ids(EpisodeFilterDraft(setOf("title_exclude"), titleText = "Listener's")))
        assertEquals(setOf(1L), ids(EpisodeFilterDraft(setOf("tags Listener's picks"))))
    }
    @Test fun textSearchRoundTripsLiteralAndExcludedTerms() {
        val filter = EpisodeFilter().apply { addTextQuery(SearchAlgo().episodesQueryString(0, listOf("Listener's", "-conversation"))) }
        assertEquals(setOf(1L), ids(EpisodeFilterDraft(filter)))
        assertEquals("Listener's, -conversation", filter.extractText())
        val literals = listOf("double\"quote", "path\\name", "a,b", "épisode")
        literals.forEach { literal ->
            val roundTrip = EpisodeFilter().apply { addTextQuery(SearchAlgo().episodesQueryString(0, listOf(literal))) }
            assertEquals(literal, EpisodeFilter(roundTrip.propertySet.joinToString(",")).extractText())
            assertTrue(ids(EpisodeFilterDraft(roundTrip)).isEmpty())
        }
    }
    @Test fun librarySelectionSupportsAllUntaggedAndUnassignedQueues() {
        val languages = setOf("en", "fr")
        val tags = setOf("Listener's picks", "Other")
        val queues = setOf(0L, 20L)
        fun feeds(draft: FeedFilterDraft) = database.query(Feed::class).query(draft.queryString(languages, tags, queues)).find().map { it.id }.toSet()
        val all = FeedFilterDraft(languages = languages, tags = tags, queueIds = queues)
        assertEquals(setOf(1001L, 1002L), feeds(all))
        assertEquals(setOf(1001L), feeds(all.copy(tags = setOf("Listener's picks"))))
        assertEquals(setOf(1002L), feeds(all.copy(tags = emptySet(), queueIds = emptySet())))
    }
}
