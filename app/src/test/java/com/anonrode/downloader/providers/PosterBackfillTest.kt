package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Poster guardrail regressions (rule 15/16 + investigation 03).
 *
 * 9jaRocks' rev-2/3 payload binds `attr:enclosure:url`; every enclosure in
 * the live RSS is an MKV locker file, so cards shipped a video URL as their
 * poster. NKiri/NaijaVault pipeline cards shipped blank because the HTML
 * search page has no <img>. Compiled extraction is the source of truth and
 * must backfill by URL — with no payload, an old payload, or a hostile one.
 */
class PosterBackfillTest {

    @Before
    fun clearCache() {
        PosterBackfill.clearForTest()
    }

    @Test
    fun lockerAndMediaUrlsAreNeverPosters() {
        assertFalse(PosterBackfill.isUsablePoster("https://loadedfiles.net/x/Show.S01E01.mkv"))
        assertFalse(PosterBackfill.isUsablePoster("https://my9jarocks.bz/Show.mkv?token=1"))
        assertFalse(PosterBackfill.isUsablePoster("https://downloadwella.com/abc/Show.mkv.html"))
        assertFalse(PosterBackfill.isUsablePoster(""))
        assertFalse(PosterBackfill.isUsablePoster("about:blank"))
        assertFalse(PosterBackfill.isUsablePoster("data:image/png;base64,AAAA"))
    }

    @Test
    fun realArtworkUrlsAreAccepted() {
        assertTrue(PosterBackfill.isUsablePoster("https://9jarocks.net/wp-content/uploads/2026/10/poster.jpg"))
        assertTrue(PosterBackfill.isUsablePoster("https://image.tmdb.org/t/p/w342/abc.jpg"))
        assertTrue(PosterBackfill.isUsablePoster("https://cdn.example.com/art/12345"))
    }

    @Test
    fun sanitizeBlanksNonImagePostersAndKeepsRealOnes() {
        val cards = listOf(
            ShowCard("A", "https://s/a", "https://loadedfiles.net/a.mkv", "9jarocks"),
            ShowCard("B", "https://s/b", "https://s/wp-content/uploads/b.jpg", "9jarocks"),
            ShowCard("C", "https://s/c", "", "9jarocks")
        )
        val clean = PosterBackfill.sanitize(cards)
        assertEquals("", clean[0].posterUrl)
        assertEquals("https://s/wp-content/uploads/b.jpg", clean[1].posterUrl)
        assertEquals("", clean[2].posterUrl)
    }

    @Test
    fun backfillJoinsCompiledArtByUrlAndCachesTheLookup() = runBlocking {
        var lookups = 0
        val lookup: suspend (List<ShowCard>) -> Map<String, String> = { missing ->
            lookups++
            missing.associate { PosterBackfill.normalizeKey(it.url) to "https://s/wp-content/uploads/${missing.indexOf(it)}.jpg" }
        }
        val cards = listOf(
            ShowCard("A", "https://s/show-a/", "", "nkiri"),
            ShowCard("B", "https://s/show-b/", "https://loadedfiles.net/b.mkv", "nkiri")
        )
        val filled = PosterBackfill.backfill(cards, lookup)
        assertTrue(filled.all { it.posterUrl.startsWith("https://s/wp-content/uploads/") })
        assertEquals(1, lookups)

        // Second call is served from the URL-keyed cache: zero lookups.
        val again = PosterBackfill.backfill(cards, lookup)
        assertTrue(again.all { it.posterUrl.startsWith("https://s/wp-content/uploads/") })
        assertEquals(1, lookups)
    }

    @Test
    fun negativeResultIsCachedSoABlankPosterDoesNotRefetch() = runBlocking {
        var lookups = 0
        val lookup: suspend (List<ShowCard>) -> Map<String, String> = {
            lookups++
            emptyMap()
        }
        val cards = listOf(ShowCard("A", "https://s/a/", "", "dramarain"))
        PosterBackfill.backfill(cards, lookup)
        PosterBackfill.backfill(cards, lookup)
        assertEquals(1, lookups)
    }

    @Test
    fun brokenPayloadPosterIsReplacedByCompiledArt() = runBlocking {
        // Hostile payload shape: every card carries the MKV enclosure.
        val pipelineCards = listOf(
            ShowCard("The Promised Neverland", "https://9jarocks.net/show-a.html", "https://loadedfiles.net/x.mkv", "9jarocks")
        )
        val compiled = mapOf(
            PosterBackfill.normalizeKey("https://9jarocks.net/show-a.html") to
                "https://9jarocks.net/wp-content/uploads/2026/10/poster.jpg"
        )
        val out = PosterBackfill.backfill(pipelineCards) { compiled }
        assertEquals("https://9jarocks.net/wp-content/uploads/2026/10/poster.jpg", out.single().posterUrl)
    }

    @Test
    fun normalizeKeyIgnoresFragmentTrailingSlashAndHostCase() {
        assertEquals(
            PosterBackfill.normalizeKey("https://Example.COM/show-a/"),
            PosterBackfill.normalizeKey("https://example.com/show-a#top")
        )
    }
}
