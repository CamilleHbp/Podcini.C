package ac.mdiq.podcini.storage.model

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TagColorsTest {
    @Test fun colorsAreInheritedAndCanBeOverridden() {
        val colors = mapOf("tags/activity" to 150, "tags/activity/focus" to 208)
        assertEquals(0xFF25935C.toInt(), tagColorArgb("Tags/Activity/Walk", colors))
        assertEquals(0xFF255F93.toInt(), tagColorArgb("TAGS/Activity/Focus/Deep", colors))
        assertEquals(tagColorArgb("Genre/Rock", emptyMap()), tagColorArgb("genre/rock/Alternative", emptyMap()))
    }
    @Test fun oldPreferencesDefaultAndColorRoundTrip() {
        assertTrue(decodeBrowsePreferences("{}").tagColors.isEmpty())
        val prefs = LibraryBrowsePreferences(tagColors = mapOf("tags/activity" to 150))
        assertEquals(prefs, decodeBrowsePreferences(libraryJson.encodeToString(prefs)))
        assertEquals(tagColorArgb("Tags/Activity", emptyMap()), tagColorArgb("Tags/Activity", mapOf("tags/activity" to 400)))
    }
    @Test fun fullColorsSurvivePersistenceAndInheritance() {
        for (color in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF96745B.toInt(), 0xFFB8CFDF.toInt())) {
            val prefs = LibraryBrowsePreferences(tagColors = mapOf("tags/activity" to color))
            val restored = decodeBrowsePreferences(libraryJson.encodeToString(prefs))
            assertEquals(color, tagColorArgb("Tags/Activity/Focus", restored.tagColors))
            assertEquals(color, remapTagColors(restored.tagColors, "Tags/Activity", "Tags/Work")["tags/work"])
        }
    }
    @Test fun mixedLegacyAndFullColorOverridesResolveNearestParent() {
        val colors = mapOf("tags/activity" to 150, "tags/activity/focus" to 0xFF96745B.toInt())
        assertEquals(0xFF96745B.toInt(), tagColorArgb("Tags/Activity/Focus/Deep", colors))
        assertEquals(0xFF25935C.toInt(), tagColorArgb("Tags/Activity/Walk", colors))
    }
    @Test fun movingRenamingMergingAndDeletingKeepColorsConsistent() {
        val original = mapOf("tags/activity" to 150, "tags/activity/focus" to 208, "tags/work" to 272)
        assertEquals(mapOf("tags/study" to 150, "tags/study/focus" to 208, "tags/work" to 272), remapTagColors(original, "Tags/Activity", "Tags/Study"))
        assertEquals(mapOf("tags/work" to 272, "tags/work/focus" to 208), remapTagColors(original, "Tags/Activity", "Tags/Work"))
        assertEquals(mapOf("tags/work" to 272), remapTagColors(original, "Tags/Activity", ""))
    }
    @Test fun escapedPathsDoNotInheritFromUnrelatedBranches() {
        val colors = mapOf("tags/a" to 38, "tags/a\\/b" to 272)
        assertEquals(0xFF5F2593.toInt(), tagColorArgb("Tags/A\\/B/Child", colors))
        assertEquals(0xFF936A25.toInt(), tagColorArgb("Tags/A/B/Child", colors))
    }
    @Test fun fileMetadataRemainsTagNamesOnly() {
        val fileValues = FileTagValues.fromPaths(listOf("Genre/Rock", "Tags/Activity/Focus"))
        assertEquals(listOf("Genre/Rock", "Tags/Activity/Focus"), fileValues.paths())
        assertEquals(listOf("Activity/Focus"), fileValues.tags)
        assertFalse(libraryJson.encodeToString(fileValues).contains("color", ignoreCase = true))
    }
}
