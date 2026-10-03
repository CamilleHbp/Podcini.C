package ac.mdiq.podcini.storage.model

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TagColorsTest {
    @Test fun colorsAreInheritedAndCanBeOverridden() {
        val colors = mapOf("tags/activity" to 150, "tags/activity/focus" to 208)
        assertEquals(150, tagColorHue("Tags/Activity/Walk", colors))
        assertEquals(208, tagColorHue("TAGS/Activity/Focus/Deep", colors))
        assertEquals(tagColorHue("Genre/Rock", emptyMap()), tagColorHue("genre/rock/Alternative", emptyMap()))
    }
    @Test fun oldPreferencesDefaultAndColorRoundTrip() {
        assertTrue(decodeBrowsePreferences("{}").tagColors.isEmpty())
        val prefs = LibraryBrowsePreferences(tagColors = mapOf("tags/activity" to 150))
        assertEquals(prefs, decodeBrowsePreferences(libraryJson.encodeToString(prefs)))
        assertTrue(tagColorHue("Tags/Activity", mapOf("tags/activity" to -3)) in 0..359)
    }
    @Test fun movingRenamingMergingAndDeletingKeepColorsConsistent() {
        val original = mapOf("tags/activity" to 150, "tags/activity/focus" to 208, "tags/work" to 272)
        assertEquals(mapOf("tags/study" to 150, "tags/study/focus" to 208, "tags/work" to 272), remapTagColors(original, "Tags/Activity", "Tags/Study"))
        assertEquals(mapOf("tags/work" to 272, "tags/work/focus" to 208), remapTagColors(original, "Tags/Activity", "Tags/Work"))
        assertEquals(mapOf("tags/work" to 272), remapTagColors(original, "Tags/Activity", ""))
    }
    @Test fun escapedPathsDoNotInheritFromUnrelatedBranches() {
        val colors = mapOf("tags/a" to 38, "tags/a\\/b" to 272)
        assertEquals(272, tagColorHue("Tags/A\\/B/Child", colors))
        assertEquals(38, tagColorHue("Tags/A/B/Child", colors))
    }
    @Test fun fileMetadataRemainsTagNamesOnly() {
        val fileValues = FileTagValues.fromPaths(listOf("Genre/Rock", "Tags/Activity/Focus"))
        assertEquals(listOf("Genre/Rock", "Tags/Activity/Focus"), fileValues.paths())
        assertEquals(listOf("Activity/Focus"), fileValues.tags)
        assertFalse(libraryJson.encodeToString(fileValues).contains("color", ignoreCase = true))
    }
}
