package ac.mdiq.podcini.storage.model

import org.junit.Assert.*
import org.junit.Test

class TagPathTest {
    @Test fun escapedSegmentsRoundTripWithoutInventingAncestors() {
        val tag = TagPath(TagKind.CUSTOM, listOf("Activity", "AC/DC", "a\\b", "Français", "\uD83C\uDFB5", "one, two; three"))
        assertEquals(tag, TagPath.parse(tag.path))
        assertTrue(tagMatches(tag.path, "Tags/Activity"))
        assertFalse(tagMatches(tag.path, "Tags/Activity/AC"))
        assertEquals("AC/DC", TagPath.parse(tag.path)!!.segments[1])
        assertEquals(7, tagParents(tag.path).size)
    }

    @Test fun categoryCaseWhitespaceAndAccentRulesAreStable() {
        val tags = canonicalTags(listOf("Genre/ Rock / Alternative", "genre/rock/alternative", "Tags/Rock/Alternative", "Tags/Été", "Tags/Ete"))
        assertEquals(4, tags.size)
        assertTrue(tags.contains("Genre/Rock/Alternative"))
        assertFalse(tagMatches("Tags/Rock", "Genre/Rock"))
        assertFalse(tagMatches("Tags/Été", "Tags/Ete"))
        assertTrue(tagMatches("Tags/Été", "tags/été"))
        assertEquals(TagPath.parse("Tags/Été"), TagPath.parse("Tags/E\u0301te\u0301"))
    }

    @Test fun unmarkedValuesAreLiteralUntilAnExplicitPreviewChoice() {
        val file = FileTagValues(genres = listOf("Rock/Alternative", "Rhythm, Blues"), moods = listOf("Calm; Quiet"), tags = listOf("Activity/Focus"))
        assertEquals(listOf("Rock/Alternative"), TagPath.parse(file.paths().first { it.startsWith("Genre/Rock") })!!.segments)
        assertTrue(file.paths().contains("Tags/Activity\\/Focus"))
        assertTrue(file.paths(nestedLegacy = true).contains("Tags/Activity/Focus"))
        assertEquals(6, file.paths(splitLegacy = true).size)
        assertEquals(file.paths(), FileTagValues.fromPaths(file.paths()).paths())
    }

    @Test fun fileIsSufficientToReconstructRootsAndPaths() {
        val paths = listOf("Genre/Rock/Alternative", "Mood/Calm", "Tags/Activity/Focus", "Tags/Français/\uD83C\uDFB5", "Tags/AC\\/DC")
        val written = FileTagValues.fromPaths(paths)
        assertEquals("1", written.convention)
        assertEquals(canonicalTags(paths), written.paths())
        assertEquals(setOf("Genre", "Mood", "Tags"), libraryTagChildren(written.paths(), "").toSet())
        assertEquals(listOf("Tags/AC\\/DC", "Tags/Activity", "Tags/Français"), libraryTagChildren(written.paths(), "Tags"))
    }

    @Test fun malformedPathsCannotBecomeUnintendedBranches() {
        listOf("", "/", "Tags//Focus", "Tags/Focus/", "Tags/a\\q", "Tags/a\\", "Tags/a\u0000b").forEach { assertNull(it, TagPath.parse(it)) }
        assertEquals("Tags/Work/a\\/b", remapLibraryTag("Tags/Home/a\\/b", "Tags/Home", "Tags/Work"))
        assertFalse(validTagDestination("Tags/Home", "Tags/Home/Child"))
    }

    @Test fun descendantAnyAllAndExclusionDoNotConfuseLiteralSlashes() {
        val item = LibraryMedia(1, tags = setOf("Tags/AC\\/DC", "Mood/Calm", "Genre/Rock/Alternative"))
        assertTrue(LibraryFilter(tags = listOf("Genre/Rock", "Mood/Calm"), allTags = true).matches(item))
        assertFalse(LibraryFilter(tags = listOf("Tags/AC")).matches(item))
        assertFalse(LibraryFilter(excludedTags = listOf("Mood")).matches(item))
    }

    @Test fun legacyFilterStorageNowPreservesTagPunctuation() {
        val filter = ac.mdiq.podcini.storage.specs.EpisodeFilter()
        filter.addTag("Tags/one, two; three")
        assertTrue(ac.mdiq.podcini.storage.specs.EpisodeFilter(filter.encode()).containsTag("Tags/one, two; three"))
    }
}
