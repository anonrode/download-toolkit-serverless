package com.anonrode.downloader.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anitaku admin-ajax parse against the captured live response
 * (`anime/anitaku_search_one_piece.json`, fetched 2026-10-20 from
 * https://gogoanime.or.at/wp-admin/admin-ajax.php).
 *
 * The shape is `{"anime":[{"all":[{post_link, post_title, post_image, …}]}]}`
 * and the old provider had no fixture coverage for it; the log class this
 * guards is "search returns no body / 0 cards" under load.
 */
class AnitakuAjaxParseTest {

    private fun body(): String =
        javaClass.classLoader!!.getResourceAsStream("anime/anitaku_search_one_piece.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun liveResponseYieldsCardsWithLinksAndPosters() {
        val cleaned = SeasonNoiseStripper.clean("one piece")
        val cards = AnitakuProvider.parseAjaxCards(body(), cleaned)
        assertTrue("cards=${cards.size}", cards.isNotEmpty())
        assertTrue(cards.all { it.url.startsWith("https://") })
        assertTrue(cards.all { it.posterUrl.startsWith("https://") })
        assertTrue(cards.any { it.title.contains("One Piece", ignoreCase = true) })
    }

    @Test
    fun malformedBodyIsEmptyNotACrash() {
        assertEquals(0, AnitakuProvider.parseAjaxCards("not json", SeasonNoiseStripper.clean("x")).size)
        assertEquals(0, AnitakuProvider.parseAjaxCards("{}", SeasonNoiseStripper.clean("x")).size)
    }

    @Test
    fun seasonNoiseIsStrippedFromTheQuery() {
        val cleaned = SeasonNoiseStripper.clean("One Piece Season 2")
        assertEquals(2, cleaned.targetSeason)
        assertEquals("One Piece", cleaned.coreTitle)
    }
}
