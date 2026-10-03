package ac.mdiq.podcini.storage.model

import org.junit.Assert.*
import org.junit.Test

class LibraryRulesTest {
    @Test fun hierarchyRespectsNamesAndNamespaces() {
        assertTrue(tagMatches("topic:science/space/moon", "topic:science"))
        assertTrue(tagMatches("Science/Space", "science"))
        assertFalse(tagMatches("topic:science-fiction", "topic:science"))
        assertFalse(tagMatches("other:science/space", "topic:science"))
        assertFalse(tagMatches("science/space", "science", descendants = false))
        assertFalse(tagMatches("science", ""))
        assertEquals(listOf("topic:science", "topic:science/space", "topic:science/space/moon"), tagParents("topic:science/space/moon"))
    }

    @Test fun automaticAndExplicitNextHaveTheSameOrderButRespectStop() {
        val ids = listOf(7L, 2L, 9L)
        assertEquals(2L, nextListeningId(ids, 7L, true, false))
        assertNull(nextListeningId(ids, 7L, false, false))
        assertEquals(2L, nextListeningId(ids, 7L, false, false, manual = true))
        assertNull(nextListeningId(ids, 9L, true, false))
        assertEquals(7L, nextListeningId(ids, 9L, true, true))
        assertEquals(7L, nextListeningId(listOf(7L), 7L, true, true))
        assertNull(nextListeningId(listOf(7L), 7L, true, false))
        assertNull(nextListeningId(emptyList(), null, true, true))
    }
}
