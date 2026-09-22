package ac.mdiq.podcini.storage.specs

import org.junit.Assert.*
import org.junit.Test

class FilterDraftsTest {
    @Test fun `editing and resetting a draft leave the applied filter untouched`() {
        val applied = EpisodeFilter(EpisodeFilter.States.UNPLAYED.name, andOr = "OR").apply {
            durationFloor = 600_000
            durationCeiling = 1_800_000
            titleText = "travel"
            addTag("Study")
        }
        val original = EpisodeFilterDraft(applied)
        val edited = original.select(EpisodeFilter.EpisodesFilterGroup.PLAY_STATE, setOf(EpisodeFilter.States.PLAYED.name))
            .copy(andOr = "AND", titleText = "science")
        assertEquals(original, EpisodeFilterDraft(applied))
        assertEquals(EpisodeFilterDraft(), edited.reset(emptySet()))
        assertEquals(setOf(EpisodeFilter.States.PLAYED.name, "tags Study"), edited.properties)
    }

    @Test fun `applying retains join duration title and every selected value without sharing state`() {
        val draft = EpisodeFilterDraft(setOf("UNPLAYED", "PROGRESS", "lower", "higher", "title_exclude", "tags Research"), "OR", 900_000, 3_600_000, "trailer")
        val applied = draft.toFilter()
        assertEquals(draft, EpisodeFilterDraft(applied))
        applied.propertySet.clear()
        assertEquals(6, draft.properties.size)
    }

    @Test fun `reset preserves locked criteria but clears text and bounds`() {
        val draft = EpisodeFilterDraft(setOf("downloaded", "UNPLAYED", "title_include"), "OR", 1000, 2000, "word")
        assertEquals(EpisodeFilterDraft(setOf("downloaded")), draft.reset(setOf(EpisodeFilter.EpisodesFilterGroup.DOWNLOADED)))
    }

    @Test fun `a library group edit preserves language tags queues and unrelated criteria`() {
        val original = FeedFilterDraft(setOf("good", "autoDownload"), setOf("en", "fr"), setOf("Study"), setOf(10L, 20L))
        val edited = original.select(FeedFilter.FeedFilterGroup.RATING, setOf("bad", "unrated"))
        assertEquals(original.languages, edited.languages)
        assertEquals(original.tags, edited.tags)
        assertEquals(original.queueIds, edited.queueIds)
        assertTrue("autoDownload" in edited.properties)
        assertTrue("good" in original.properties)
        assertFalse("good" in edited.properties)
    }
}
