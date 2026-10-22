package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Last-good search cache (share-43 §RC-2): the first search of a session used
 * to paint only after every provider crawl finished. The cache keeps each
 * provider's last NON-EMPTY answer per exact normalized query for 5 minutes.
 *
 * Contract under test:
 *  - exact keying: provider + normalized query; a different query/provider
 *    never reads another entry (no cross-query bleed);
 *  - TTL: expired entries are misses;
 *  - LRU: hard bound of 64, least-recently-used entry evicted;
 *  - no empty/failed cache: an empty live answer never becomes "last good"
 *    and never overwrites a good entry;
 *  - flow: a repeat query paints from cache BEFORE the background refresh,
 *    and a failed refresh does not wipe the last-good paint.
 */
class ProviderRegistrySearchCacheTest {

    private val calls = AtomicInteger()

    /** What the fake provider returns for the CURRENT call. */
    private var results: (String) -> List<ShowCard> = {
        listOf(card("Alpha Result", "https://fakec.test/alpha"))
    }

    private val fake = object : SiteProvider {
        override val name = "fakec"
        override val mainUrl = "https://fakec.test"
        override suspend fun search(query: String): List<ShowCard> {
            calls.incrementAndGet()
            delay(400) // stands in for the cold network crawl
            return results(query)
        }
        override suspend fun loadEpisodes(showUrl: String): ShowDetails =
            ShowDetails(show = ShowCard(title = "Fake", url = showUrl, site = name))
        override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe =
            DownloadRecipe(directUrl = episodeUrl, filename = "f.mp4", backend = "aria2c")
    }

    private fun card(title: String, url: String, site: String = "fakec") =
        ShowCard(title = title, url = url, site = site)

    @Before
    fun setUp() {
        calls.set(0)
        results = { listOf(card("Alpha Result", "https://fakec.test/alpha")) }
        ProviderRegistry.clearSearchCachesForTest()
        ProviderRegistry.searchProvidersForTest = listOf(fake)
    }

    @After
    fun tearDown() {
        ProviderRegistry.searchProvidersForTest = null
        ProviderRegistry.clearSearchCachesForTest()
    }

    // ---- cache discipline ---------------------------------------------------

    @Test
    fun cacheKeysOnProviderAndExactNormalizedQuery() {
        val cards = listOf(card("Alpha Result", "https://fakec.test/alpha"))
        assertTrue(ProviderRegistry.SearchLastGoodCache.put("asianc", "alpha", cards))

        assertEquals(cards, ProviderRegistry.SearchLastGoodCache.get("asianc", "alpha"))
        assertNull("a different query must never read this entry",
            ProviderRegistry.SearchLastGoodCache.get("asianc", "beta"))
        assertNull("a different provider must never read this entry",
            ProviderRegistry.SearchLastGoodCache.get("nepu", "alpha"))

        // Provider ids are case-insensitive; queries normalize case+whitespace.
        assertEquals(cards, ProviderRegistry.SearchLastGoodCache.get("ASIANC", "alpha"))
        ProviderRegistry.SearchLastGoodCache.put(
            "asianc",
            ProviderRegistry.SearchLastGoodCache.normalizeQuery("  A   Certain  Dark "),
            cards
        )
        assertEquals(
            cards,
            ProviderRegistry.SearchLastGoodCache.get(
                "asianc",
                ProviderRegistry.SearchLastGoodCache.normalizeQuery("a certain dark")
            )
        )
        assertNull(
            "a longer, different query is not the same query",
            ProviderRegistry.SearchLastGoodCache.get(
                "asianc",
                ProviderRegistry.SearchLastGoodCache.normalizeQuery("a certain dark item")
            )
        )
    }

    @Test
    fun entriesExpireAtTheirOwnTtl() {
        val cards = listOf(card("Alpha Result", "https://fakec.test/alpha"))
        ProviderRegistry.SearchLastGoodCache.put("asianc", "alpha", cards, nowMs = 1_000L)
        assertNotNull(ProviderRegistry.SearchLastGoodCache.get("asianc", "alpha", nowMs = 1_000L + 299_999L))
        assertNull(
            "entry at exactly 5 minutes is stale",
            ProviderRegistry.SearchLastGoodCache.get("asianc", "alpha", nowMs = 1_000L + 300_000L)
        )
    }

    @Test
    fun cacheIsBoundedLruOf64() {
        val cards = listOf(card("Alpha Result", "https://fakec.test/alpha"))
        for (i in 1..64) {
            ProviderRegistry.SearchLastGoodCache.put("p", "query $i", cards)
        }
        assertEquals(64, ProviderRegistry.SearchLastGoodCache.stats().size)

        // Touch q1 so q2 becomes the least-recently-used entry.
        assertNotNull(ProviderRegistry.SearchLastGoodCache.get("p", "query 1"))
        ProviderRegistry.SearchLastGoodCache.put("p", "query 65", cards)

        assertEquals("cache must stay hard-bounded", 64, ProviderRegistry.SearchLastGoodCache.stats().size)
        assertNotNull("recently used entry survives", ProviderRegistry.SearchLastGoodCache.get("p", "query 1"))
        assertNull("least-recently-used entry was evicted", ProviderRegistry.SearchLastGoodCache.get("p", "query 2"))
        assertTrue(ProviderRegistry.SearchLastGoodCache.stats().evictions >= 1)
    }

    @Test
    fun emptyResultsAreNeverCachedAndNeverOverwriteGoodOnes() {
        val good = listOf(card("Alpha Result", "https://fakec.test/alpha"))
        assertFalse("an empty answer is not last-good",
            ProviderRegistry.SearchLastGoodCache.put("asianc", "alpha", emptyList()))
        assertNull(ProviderRegistry.SearchLastGoodCache.get("asianc", "alpha"))

        assertTrue(ProviderRegistry.SearchLastGoodCache.put("asianc", "alpha", good))
        assertFalse(ProviderRegistry.SearchLastGoodCache.put("asianc", "alpha", emptyList()))
        assertEquals("empty refresh must keep the good entry", good,
            ProviderRegistry.SearchLastGoodCache.get("asianc", "alpha"))
    }

    // ---- flow behaviour -----------------------------------------------------

    @Test
    fun repeatQueryPaintsFromCacheBeforeTheBackgroundRefresh() = runBlocking {
        val first = ProviderRegistry.searchStreaming("alpha").toList()
        assertEquals("cold query runs exactly one crawl", 1, calls.get())
        assertEquals(1, first.last().size)

        // Refresh will return two cards this time.
        results = {
            listOf(
                card("Alpha One", "https://fakec.test/alpha-1"),
                card("Alpha Two", "https://fakec.test/alpha-2")
            )
        }
        val t0 = System.currentTimeMillis()
        val emissions = mutableListOf<Pair<Long, List<ShowCard>>>()
        ProviderRegistry.searchStreaming("alpha")
            .onEach { emissions.add((System.currentTimeMillis() - t0) to it) }
            .toList()

        assertTrue("cached paint + refreshed emission expected", emissions.size >= 2)
        assertTrue(
            "cached paint must arrive before the 400ms crawl, took ${emissions.first().first}ms",
            emissions.first().first < 200
        )
        assertEquals("first paint is the last-good single card", 1, emissions.first().second.size)
        assertEquals("background refresh replaced it", 2, emissions.last().second.size)
        assertEquals("refresh still ran", 2, calls.get())
    }

    @Test
    fun failedRefreshNeverWipesTheLastGoodPaint() = runBlocking {
        ProviderRegistry.searchStreaming("alpha").toList()
        assertEquals(1, calls.get())

        // The refreshed crawl now fails / returns nothing.
        results = { emptyList() }
        val emissions = ProviderRegistry.searchStreaming("alpha").toList()

        assertEquals("cached paint only — no empty wipe", 1, emissions.size)
        assertEquals(1, emissions.single().size)
        assertEquals("refresh still attempted", 2, calls.get())
    }

    @Test
    fun differentQueryNeverServesTheOtherQueriesCards() = runBlocking {
        ProviderRegistry.searchStreaming("alpha").toList()
        assertEquals(1, calls.get())

        // "beta" has no cache entry; the crawl returns nothing.
        results = { emptyList() }
        val emissions = ProviderRegistry.searchStreaming("beta").toList()

        assertEquals("nothing to paint and nothing live -> explicit empty", 1, emissions.size)
        assertTrue("alpha's cards must never surface under beta", emissions.single().isEmpty())
        assertEquals(2, calls.get())
    }

    @Test
    fun cacheStatsAreExposedForTests() {
        val stats = ProviderRegistry.searchCacheStatsForTest()
        assertEquals(0, stats.size)
        assertEquals(0L, stats.hits)
        assertEquals(0L, stats.misses)
    }
}
