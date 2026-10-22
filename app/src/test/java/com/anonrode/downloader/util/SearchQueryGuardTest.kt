package com.anonrode.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 12 — the search cap/debounce/dedupe contract, tested as pure logic.
 * The ViewModel only executes these decisions; no Android is involved.
 */
class SearchQueryGuardTest {

    @Test
    fun normalizeCollapsesWhitespaceAndTrims() {
        assertEquals("one piece", SearchQueryGuard.normalize("  one \n\t  piece  "))
        assertEquals("a b c", SearchQueryGuard.normalize("a\tb\r\nc"))
        assertEquals("", SearchQueryGuard.normalize("   "))
    }

    @Test
    fun normalizeCapsAtOneHundredTwenty() {
        val dictated = "word ".repeat(340) // 1,700 chars
        assertEquals(SearchQueryGuard.MAX, SearchQueryGuard.normalize(dictated).length)

        assertEquals(120, SearchQueryGuard.normalize("a".repeat(120)).length)
        assertEquals(120, SearchQueryGuard.normalize("a".repeat(121)).length)
        assertEquals(120, SearchQueryGuard.normalize("a".repeat(1700)).length)
    }

    @Test
    fun oversizedIsMeasuredOnRawInputBeforeTheCap() {
        assertTrue(SearchQueryGuard.isOversized("a".repeat(1700)))
        assertTrue(SearchQueryGuard.isOversized("a".repeat(121)))
        assertFalse(SearchQueryGuard.isOversized("a".repeat(120)))
        // Whitespace is collapsed first: padding never counts as content.
        assertFalse(SearchQueryGuard.isOversized("   " + "a".repeat(120) + "   "))
        assertFalse(SearchQueryGuard.isOversized(" ".repeat(2000) + "hi"))
    }

    @Test
    fun underTwoCharactersClears() {
        assertEquals(SearchQueryGuard.Decision.Clear, SearchQueryGuard.decide(""))
        assertEquals(SearchQueryGuard.Decision.Clear, SearchQueryGuard.decide("   "))
        assertEquals(SearchQueryGuard.Decision.Clear, SearchQueryGuard.decide("a"))
        assertEquals(SearchQueryGuard.Decision.Clear, SearchQueryGuard.decide(" a "))
    }

    @Test
    fun normalQueryDebouncesWithTheCappedNormalizedText() {
        val decision = SearchQueryGuard.decide("  one   piece ")
        assertEquals(SearchQueryGuard.Decision.Debounce("one piece"), decision)
        assertEquals(350L, SearchQueryGuard.DEBOUNCE_MS)
    }

    @Test
    fun exactlyMaximumLengthIsAllowed() {
        val decision = SearchQueryGuard.decide("a".repeat(SearchQueryGuard.MAX))
        assertTrue(decision is SearchQueryGuard.Decision.Debounce)
        assertEquals(SearchQueryGuard.MAX, (decision as SearchQueryGuard.Decision.Debounce).query.length)
    }

    @Test
    fun oversizedPasteRefusesFanOutAndKeepsCappedText() {
        val dictated = "The quick brown fox ".repeat(90) // 1,710 chars
        val decision = SearchQueryGuard.decide(dictated)

        assertTrue("oversized paste must hit the cancel/refuse contract", decision is SearchQueryGuard.Decision.Refuse)
        decision as SearchQueryGuard.Decision.Refuse
        assertEquals(SearchQueryGuard.MAX, decision.display.length)
        // Still normalized/capped, never the raw blob.
        assertEquals(SearchQueryGuard.normalize(dictated), decision.display)
    }

    @Test
    fun submissionKeyIsCaseInsensitiveNormalizedAndFilterScoped() {
        assertEquals(
            "one piece::all",
            SearchQueryGuard.submissionKey("  One   PIECE  ", "all")
        )
        assertEquals(
            SearchQueryGuard.submissionKey("One Piece", "all"),
            SearchQueryGuard.submissionKey("one   piece", "all")
        )
        assertNotEquals(
            SearchQueryGuard.submissionKey("One Piece", "all"),
            SearchQueryGuard.submissionKey("One Piece", "anitaku")
        )
        // A pasted blob keys on the capped text, same as what is displayed.
        val key = SearchQueryGuard.submissionKey("a".repeat(1700), "all")
        assertEquals("a".repeat(SearchQueryGuard.MAX) + "::all", key)
    }
}
