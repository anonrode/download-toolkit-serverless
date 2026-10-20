package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload-driven Nollywood suppression tests for the exact OTA configuration
 * shipped in scraper_rules.json contentPolicy. 9jarocks is deliberately NOT in
 * nollywoodSites (its category literal is "Nollywood & Movies" on every card),
 * so only the title-term rule may filter it.
 */
class NollywoodSuppressionTest {

    private val payload = """
        {
          "version": "nollywood-test",
          "contentPolicy": {
            "suppressNollywood": true,
            "nollywoodSites": ["naijavault", "naijaprey"],
            "nollywoodTerms": ["nollywood", "yoruba", "naija", "igbo", "hausa"]
          }
        }
    """.trimIndent()

    @After
    fun resetPolicy() {
        DynamicRulesManager.parseRulesJson("""{"version":"nollywood-test-reset"}""")
    }

    @Test
    fun jagunWithNollywoodTermIsFiltered() {
        assertTrue(DynamicRulesManager.parseRulesJson(payload))
        val jagun = ShowCard(
            title = "Jagun (2024) Nollywood Movie",
            url = "https://9jarocks.net/jagun-nollywood-movie-id1.html",
            site = "9jarocks",
            category = "Nollywood & Movies"
        )
        assertTrue(NollywoodFilter.isNollywood(jagun))
    }

    @Test
    fun nonNollywood9jarocksCardSurvivesDespiteCategoryLiteral() {
        assertTrue(DynamicRulesManager.parseRulesJson(payload))
        val neverland = ShowCard(
            title = "The Promised Neverland Season 1 (Complete) (Anime)",
            url = "https://9jarocks.net/the-promised-neverland-season-1-anime-id383590.html",
            site = "9jarocks",
            category = "Nollywood & Movies"
        )
        assertFalse(NollywoodFilter.isNollywood(neverland))
    }

    @Test
    fun bareJagunWithoutATermStillPassesHonestLimitation() {
        assertTrue(DynamicRulesManager.parseRulesJson(payload))
        val bare = ShowCard(
            title = "Jagun",
            url = "https://9jarocks.net/jagun-id2.html",
            site = "9jarocks",
            category = "Nollywood & Movies"
        )
        // Documented limitation: the title rule is the only safe rule for
        // 9jarocks; a bare Nigerian title with no term cannot be classified.
        assertFalse(NollywoodFilter.isNollywood(bare))
    }
}
