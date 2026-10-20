package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the 9jaRocks "The Promised Neverland" search failure.
 *
 * The OTA HTML source answered first with five unrelated cards, so the RSS
 * source (which carries the verified post) was never consulted. The rules now
 * try RSS first, and RocksProvider refuses a non-empty-but-irrelevant pipeline
 * result and merges the compiled RSS fallback instead.
 */
class RocksProviderSearchGuardTest {

    private val query = "The Promised Neverland"

    private val verifiedPost = ShowCard(
        title = "The Promised Neverland Season 1 (Complete) (Anime)",
        url = "https://my9jarocks.bz/the-promised-neverland-season-1-anime-id383590.html",
        site = "9jarocks",
        category = "Nollywood & Movies"
    )

    private val unrelatedHtmlCards = listOf(
        ShowCard("John Wick Chapter 4", "https://my9jarocks.bz/john-wick-4-id1.html", site = "9jarocks"),
        ShowCard("The Matrix Resurrections", "https://my9jarocks.bz/the-matrix-id2.html", site = "9jarocks"),
        ShowCard("2012", "https://my9jarocks.bz/2012-id3.html", site = "9jarocks"),
        ShowCard("Gladiator II", "https://my9jarocks.bz/gladiator-2-id4.html", site = "9jarocks"),
        ShowCard("Oppenheimer", "https://my9jarocks.bz/oppenheimer-id5.html", site = "9jarocks")
    )

    @Test
    fun verifiedRssPostMeetsRelevanceFloor() {
        assertTrue(RelevanceScorer.score(query, verifiedPost.title) >= 0.50)
        assertTrue(RocksProvider.hasRelevantResult(query, listOf(verifiedPost)))
    }

    @Test
    fun unrelatedHtmlCardsAreNotAcceptedAsRelevant() {
        assertFalse(RocksProvider.hasRelevantResult(query, unrelatedHtmlCards))
    }

    @Test
    fun mergeKeepsVerifiedPostAheadOfUnrelatedPipelineNoise() {
        val merged = RocksProvider.mergeResults(query, listOf(verifiedPost), unrelatedHtmlCards)
        assertTrue(merged.any { it.url == verifiedPost.url })
        assertEquals(verifiedPost.url, merged.first().url)
        assertEquals(1 + unrelatedHtmlCards.size, merged.size)
    }

    @Test
    fun mergeDedupesByUrlAndTitle() {
        val duplicateUrl = verifiedPost.copy(title = "Different title, same post")
        val duplicateTitle = verifiedPost.copy(url = "https://my9jarocks.bz/other-id.html")
        val merged = RocksProvider.mergeResults(
            query,
            listOf(verifiedPost),
            listOf(duplicateUrl, duplicateTitle)
        )
        assertEquals(1, merged.size)
        assertEquals(verifiedPost.url, merged.first().url)
    }
}
