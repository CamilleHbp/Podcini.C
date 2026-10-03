package ac.mdiq.podcini.storage.model

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class LibraryBrowseTest {
    private val astronomy = LibraryMedia(1, "A lunar atlas", "podcast", setOf("Maya Chen", "Observatory"),
        tags = setOf("Science/Astronomy/Moon", "Study"), downloaded = true, durationMs = 1_200_000)

    @Test fun tagRulesComposeWithoutLosingScope() {
        val query = LibraryFilter(scopeTag = "Science", tags = listOf("Study", "History"), maxMinutes = 30, downloaded = true)
        assertTrue(query.matches(astronomy))
        assertFalse(query.copy(allTags = true).matches(astronomy))
        assertFalse(query.copy(excludedTags = listOf("Science/Astronomy")).matches(astronomy))
        assertFalse(query.copy(descendants = false).matches(astronomy))
        assertFalse(query.copy(scopeTag = "Science-fiction").matches(astronomy))
    }

    @Test fun creatorSelectionIsAnExactUnionAndDoesNotSplitNames() {
        assertTrue(LibraryFilter(creators = listOf("Other", "maya chen")).matches(astronomy))
        assertFalse(LibraryFilter(creators = listOf("Maya")).matches(astronomy))
        assertFalse(LibraryFilter(scopeCreator = "Other", creators = listOf("Maya Chen")).matches(astronomy))
        assertTrue(LibraryFilter(creators = listOf("Earth, Wind & Fire")).matches(astronomy.copy(creators = setOf("Earth, Wind & Fire"))))
    }

    @Test fun tagCountsMatchTheChildQueryWithFiltersAndExactOrSubtreeScope() {
        val items = listOf(astronomy, astronomy.copy(id = 2, downloaded = false),
            astronomy.copy(id = 3, tags = setOf("Science/Astronomy", "Study")))
        for (descendants in listOf(true, false)) {
            val filter = LibraryFilter(downloaded = true, descendants = descendants)
            val counts = countLibraryTags(items.filter(filter::matches), descendants)
            for (tag in listOf("Science", "Science/Astronomy", "Science/Astronomy/Moon")) {
                assertEquals(items.count { filter.copy(scopeTag = tag).matches(it) }, counts[libraryKey(tag)] ?: 0)
            }
        }
        assertEquals(2, countLibraryTags(items.filter { it.downloaded })["science/astronomy"])
        assertEquals(1, countLibraryTags(items.filter { it.downloaded }, false)["science/astronomy"])
    }

    @Test fun durationLimitExcludesUnknownDurationAndAvoidsOverflow() {
        assertFalse(LibraryFilter(maxMinutes = 30).matches(astronomy.copy(durationMs = 0)))
        assertTrue(LibraryFilter().matches(astronomy.copy(durationMs = 0)))
        assertTrue(LibraryFilter(maxMinutes = 35791).matches(astronomy))
        assertFalse(LibraryFilter(maxMinutes = 19).matches(astronomy))
    }

    @Test fun albumIdentityKeepsCompilationsTogetherAndNamesakesApart() {
        val first = libraryAlbumKey("Night Studies", "Various Artists", "Maya Chen")
        assertEquals(first, libraryAlbumKey("Night Studies", "Various Artists", "Noah Martin"))
        assertNotEquals(first, libraryAlbumKey("Night Studies", "Another Ensemble", "Noah Martin"))
        assertNotEquals(libraryAlbumKey("Greatest Hits", "", "One"), libraryAlbumKey("Greatest Hits", "", "Two"))
        assertEquals("", libraryAlbumKey("", "", "One"))
    }

    @Test fun childrenAndBranchEditsRespectHierarchyBoundaries() {
        val tags = listOf("Science/Astronomy/Moon", "Science/Biology", "Science-fiction", "science/Astronomy")
        assertEquals(listOf("Science/Astronomy", "Science/Biology"), libraryTagChildren(tags, "SCIENCE"))
        assertEquals("Study/Space/Moon", remapLibraryTag("Science/Astronomy/Moon", "Science/Astronomy", "Study/Space"))
        assertEquals("Science-fiction", remapLibraryTag("Science-fiction", "Science", "Study"))
        assertNull(remapLibraryTag("Science/Astronomy", "Science", ""))
        assertFalse(validTagDestination("Science", "Science/Astronomy/New"))
        assertFalse(validTagDestination("Science", "Study//Space"))
        assertTrue(validTagDestination("Science", "Study/Science"))
    }

    @Test fun pinsRoundTripTheEntireBrowseStateAndOldPreferencesStillLoad() {
        val destination = LibraryDestination("tag", tag = "Science", filter = LibraryFilter(tags = listOf("Study"), excludedTags = listOf("Interview"), downloaded = true), sort = 21, grid = true, group = "podcasts")
        val original = LibraryBrowsePreferences("tags", listOf(LibraryPin("stable-id", "Morning", destination)), listOf("Study/Empty"))
        assertEquals(original, decodeBrowsePreferences(libraryJson.encodeToString(original)))
        val listHome = original.copy(homeCards = false)
        assertEquals(listHome, decodeBrowsePreferences(libraryJson.encodeToString(listHome)))
        val legacy = decodeBrowsePreferences("""{"start":"tags","pins":[{"id":"saved","name":"Albums","destination":{"section":"albums","grid":true}}]}""")
        assertTrue(legacy.homeCards)
        assertEquals("tags", legacy.start)
        assertTrue(legacy.pins.single().destination.grid)
        assertEquals(LibraryBrowsePreferences(), decodeBrowsePreferences(""))
        assertEquals("home", decodeBrowsePreferences("{\"unrecognisedFutureField\":true}").start)
        assertNull(decodeLibraryFilter("bad saved rule"))
        assertEquals(destination.query(), decodeLibraryFilter(destination.query().encode()))
    }
}
