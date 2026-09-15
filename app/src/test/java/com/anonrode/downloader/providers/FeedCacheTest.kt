package com.anonrode.downloader.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for FeedCache's pure TTL math and self-healing disk read
 * (v3.1.6 — "close it and open again, the last stuff will still be there").
 * The Context-bound init/save paths are exercised on-device; everything
 * that decides WHAT is painted fresh vs refetched lives here, without
 * Android.
 */
class FeedCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // -- age math: the freshness gate every load path consults ----------------

    @Test
    fun `ageOf never-fresh for missing entries and clamps clock skew`() {
        assertEquals(FeedSnapshot.NEVER, FeedSnapshot.ageOf(0L, 12345L))
        assertEquals(FeedSnapshot.NEVER, FeedSnapshot.ageOf(-5L, 12345L))
        assertEquals(500L, FeedSnapshot.ageOf(1000L, 1500L))
        // savedAt in the FUTURE (NTP correction after save): age 0, fresh —
        // never negative, which would also be "fresh".
        assertEquals(0L, FeedSnapshot.ageOf(2000L, 1500L))
    }

    @Test
    fun `snapshot age accessors delegate per group and report missing category as NEVER`() {
        val s = FeedSnapshot(
            trending = listOf(card("one")),
            trendingAtMs = 1000L,
            categories = mapOf("Action" to CategoryEntry(savedAtMs = 2000L, cards = listOf(card("a")))),
            tiles = listOf(TilePoster("Action", "https://x/p.jpg")),
            tilesAtMs = 3000L
        )
        assertEquals(500L, s.trendingAgeMs(1500L))
        assertEquals(1000L, s.categoryAgeMs("Action", 3000L))
        assertEquals(FeedSnapshot.NEVER, s.categoryAgeMs("Horror", 3000L))
        assertEquals(0L, s.tilesAgeMs(3000L))
    }

    @Test
    fun `TTL is the agreed thirty minutes`() {
        assertEquals(30L * 60_000L, FeedCache.FRESH_MS)
    }

    // -- disk read: the self-heal contract ------------------------------------

    @Test
    fun `read missing file is an empty snapshot not an error`() {
        val s = FeedCache.read(File(tmp.root, "nope.json"))
        assertEquals(FeedSnapshot(), s)
    }

    @Test
    fun `read decodes partial and forward-compatible payloads with defaults`() {
        // Every field has a default — an older file (or one written by a
        // future schema the app rolled back to) must decode, not brick home.
        f("partial.json").writeText("""{"trendingAtMs":1000,"unknownFutureField":42}""")
        val s = FeedCache.read(f("partial.json"))
        assertEquals(1000L, s.trendingAtMs)
        assertTrue(s.trending.isEmpty())
        assertTrue(s.categories.isEmpty())

        f("full.json").writeText(
            """{"trending":[{"title":"A","url":"https://t/a","site":"nkiri","category":"Drama","year":"","totalEpisodes":0,"posterUrl":""}],
               "trendingAtMs":5,
               "categories":{"Sci-Fi":{"savedAtMs":6,"cards":[]}},
               "tiles":[{"label":"Action","posterUrl":"https://t/p.jpg"}],
               "tilesAtMs":7}"""
        )
        val full = FeedCache.read(f("full.json"))
        assertEquals(listOf("A"), full.trending.map { it.title })
        assertEquals(6L, full.categories["Sci-Fi"]?.savedAtMs)
        assertEquals("https://t/p.jpg", full.tiles.first().posterUrl)
        assertEquals(7L, full.tilesAtMs)
    }

    @Test
    fun `read corrupt cache deletes itself and returns empty`() {
        val file = f("broken.json")
        file.writeText("}{ this is not json at all")
        val s = FeedCache.read(file)
        assertEquals(FeedSnapshot(), s)
        // Self-heal: the poison file is gone, so the next save writes clean.
        assertFalse(file.exists())
    }

    @Test
    fun `read survives truncated writes (tmp rename race guard)`() {
        // Half a file on disk (kill mid-save on some filesystems) is the same
        // corrupt path: empty snapshot + deletion, never a crash on boot.
        val file = f("half.json")
        file.writeText("""{"trendingAtMs":10,"categories":""")
        assertEquals(FeedSnapshot(), FeedCache.read(file))
        assertFalse(file.exists())
    }

    private fun card(title: String) =
        com.anonrode.downloader.data.models.ShowCard(title = title, url = "https://x/$title", site = "nkiri")

    private fun f(name: String) = File(tmp.root, name)
}
