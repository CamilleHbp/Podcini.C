package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.storage.specs.EpisodeState
import org.junit.Assert.*
import org.junit.Test

class EpisodePlaybackTest {
    private fun episode() = Episode().apply { duration = 70 * 60_000 }

    @Test fun `resuming at 53 minutes advances progress and finishes a 70 minute episode`() {
        val episode = episode()
        episode.recordPlaybackProgress(53 * 60_000, 97, now = 1_000)
        assertEquals(75, episode.playedPercentage)
        assertFalse(episode.isPlaybackFinished(97))
        // A fresh playback session after pause or process restart.
        episode.startPosition = episode.position
        episode.playedDurationWhenStarted = episode.playedDuration
        episode.recordPlaybackProgress(68 * 60_000, 97, now = 2_000)
        assertEquals(68 * 60_000, episode.position)
        assertEquals(97, episode.playedPercentage)
        assertEquals(EpisodeState.PLAYED.code, episode.playState)
        assertEquals(2_000L, episode.lastPlayedTime)
        episode.recordPlaybackProgress(70 * 60_000, 97, ended = true, now = 3_000)
        assertEquals(0, episode.position)
        assertEquals(100, episode.playedPercentage)
        assertEquals(3_000L, episode.playbackCompletionTime)
    }

    @Test fun `completion records the endpoint even with an old saved position`() {
        val episode = episode().apply { position = 53 * 60_000 }
        episode.recordPlaybackProgress(episode.position, 100, ended = true, now = 5_000)
        assertEquals(100, episode.playedPercentage)
        assertTrue(episode.isPlaybackFinished(100))
        assertEquals(0, episode.position)
    }

    @Test fun `threshold uses exact progress and supports 100 percent`() {
        val episode = episode()
        val thresholdPosition = episode.duration * 97 / 100
        episode.recordPlaybackProgress(thresholdPosition - 1, 97, now = 1_000)
        assertFalse(episode.isPlaybackFinished(97))
        episode.recordPlaybackProgress(thresholdPosition, 97, now = 2_000)
        assertTrue(episode.isPlaybackFinished(97))
        val strict = episode()
        strict.recordPlaybackProgress(strict.duration - 1, 100, now = 1_000)
        assertFalse(strict.isPlaybackFinished(100))
        strict.recordPlaybackProgress(strict.duration, 100, now = 2_000)
        assertTrue(strict.isPlaybackFinished(100))
    }

    @Test fun `rewinds and replay retain history without counting repeated audio twice`() {
        val episode = episode()
        episode.recordPlaybackProgress(40 * 60_000, 97, now = 1_000)
        episode.playedDuration = 80 * 60_000
        episode.recordPlaybackProgress(10 * 60_000, 97, now = 2_000)
        assertEquals(57, episode.playedPercentage)
        assertFalse(episode.isPlaybackFinished(97))
        episode.recordPlaybackProgress(episode.duration, 97, ended = true, now = 3_000)
        episode.recordPlaybackProgress(60_000, 97, now = 4_000)
        assertEquals(100, episode.playedPercentage)
        assertEquals(4_000L, episode.lastPlayedTime)
    }

    @Test fun `unknown duration invalid position and long media are handled safely`() {
        val unknown = Episode()
        unknown.recordPlaybackProgress(-1, 97, now = 1_000)
        assertNull(unknown.playedPercentage)
        assertEquals(0L, unknown.lastPlayedTime)
        assertFalse(unknown.isPlaybackFinished(97))
        unknown.recordPlaybackProgress(60_000, 97, now = 2_000)
        assertNull(unknown.playedPercentage)
        assertFalse(unknown.isPlaybackFinished(97))
        assertFalse(completionReached(0, 0, 97))
        assertTrue(completionReached(Int.MAX_VALUE, Int.MAX_VALUE, 100))
    }

    @Test fun `skipped ignored and passed states are not displayed as finished`() {
        for (state in listOf(EpisodeState.SKIPPED, EpisodeState.IGNORED, EpisodeState.PASSED)) {
            val episode = episode()
            episode.setPlayState(state, setTime = 1_000)
            assertFalse(episode.isPlaybackFinished(97))
        }
    }

    @Test fun `repeat state is preserved when reaching completion`() {
        val episode = episode()
        episode.setPlayState(EpisodeState.FOREVER, setTime = 1_000)
        episode.recordPlaybackProgress(episode.duration, 97, ended = true, now = 2_000)
        assertEquals(EpisodeState.FOREVER.code, episode.playState)
        assertEquals(100, episode.playedPercentage)
    }
}
