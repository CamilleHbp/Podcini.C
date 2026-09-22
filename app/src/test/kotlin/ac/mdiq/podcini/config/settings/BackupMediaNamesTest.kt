package ac.mdiq.podcini.config.settings

import org.junit.Assert.*
import org.junit.Test

class BackupMediaNamesTest {
    @Test fun `media identity survives duplicate titles and dots in titles`() {
        assertEquals(123L, backupMediaEpisodeId("An episode. Part 1.123.mp3"))
        assertEquals(456L, backupMediaEpisodeId("An episode. Part 1.456.mp3"))
        assertEquals(123L, backupMediaEpisodeId("Renamed title.123.m4a"))
    }

    @Test fun `clip names can contain underscores and dots`() {
        assertEquals(123L, backupClipEpisodeId("recorded_123_01.20_favorite.m4a"))
    }

    @Test fun `unrecognized files do not attach to an episode`() {
        for (name in listOf(".nomedia", "cover.jpg", "title.mp3", "title.abc.mp3", "title.9223372036854775808.mp3")) {
            assertNull(name, backupMediaEpisodeId(name))
        }
        for (name in listOf("cover.jpg", "recorded_bad_time.m4a", "other_123_time.m4a")) {
            assertNull(name, backupClipEpisodeId(name))
        }
    }
}
