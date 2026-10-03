package ac.mdiq.podcini.sourcing.searcher

import org.junit.Assert.*
import org.junit.Test

class PodcastSearchPolicyTest {
    @Test fun discoveryRunsWithoutAnyLibraryMatchOrSubmitAction() {
        assertTrue(shouldSearchPodcasts("Planet Money", SearchScope.All, false))
        assertTrue(shouldSearchPodcasts("  planète  ", SearchScope.Podcasts, false))
        assertFalse(shouldSearchPodcasts("Planet Money", SearchScope.Episodes, false))
        assertFalse(shouldSearchPodcasts("Planet Money", SearchScope.Playlists, false))
        assertFalse(shouldSearchPodcasts("Planet Money", SearchScope.All, true))
        assertFalse(shouldSearchPodcasts("a", SearchScope.All, false))
        assertFalse(shouldSearchPodcasts("HTTPS://private.example/feed.xml?token=abc", SearchScope.All, false))
    }

    @Test fun urlIdentityMergesSafeAliasesButPreservesPrivateAndDistinctFeeds() {
        assertEquals(podcastUrlKey("http://EXAMPLE.org:80/podcast.xml"), podcastUrlKey("https://example.org:443/podcast.xml#section"))
        assertNotEquals(podcastUrlKey("https://example.org/Feed"), podcastUrlKey("https://example.org/feed"))
        assertNotEquals(podcastUrlKey("https://example.org/feed?token=a"), podcastUrlKey("https://example.org/feed?token=b"))
        assertNotEquals(podcastUrlKey("https://one:secret@example.org/feed"), podcastUrlKey("https://two:secret@example.org/feed"))
        assertNotEquals(podcastUrlKey("http://example.org:443/feed"), podcastUrlKey("https://example.org/feed"))
        assertNull(podcastUrlKey(null))
        assertNull(podcastUrlKey(" "))
    }

    @Test fun multiWordSearchUsesAllWordsAcrossFieldsWithoutLegacySyntax() {
        assertTrue(matchesSearch("planet money", "Planet Money", "NPR"))
        assertTrue(matchesSearch("planet npr", "Planet Money", "NPR"))
        assertFalse(matchesSearch("planet missing", "Planet Money", "NPR"))
        assertFalse(matchesSearch("planet,money", "Planet Money", "NPR"))
        assertEquals(listOf("L’histoire", "d’une", "planète"), searchWords(" L’histoire   d’une\nplanète "))
    }
}
