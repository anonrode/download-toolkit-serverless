package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drawer [ProviderRegistry.loadEpisodes] cache discipline (activity-log
 * share-43, 10-20 00:19): the same dead /drama-detail page was fetched 8x
 * in 7.7s because (a) empty drawers were NEVER cached — the cache write was
 * guarded by `episodes.isNotEmpty()` — and (b) the concurrent callers that
 * race the TTL check (drawer open + oracle verifier + prioritize re-wave)
 * had no single-flight. Contract under test:
 *  - concurrent calls for the same show make exactly ONE provider fetch and
 *    every caller gets the same result;
 *  - an EMPTY drawer is cached for [ProviderRegistry.emptyEpisodesTtlMs] —
 *    the next call inside the window makes NO fetch; a zero TTL refetches;
 *  - non-empty drawers keep the historical positive TTL;
 *  - different show URLs never share an entry.
 */
class ProviderRegistryLoadEpisodesCacheTest {

    private val fetches = AtomicInteger(0)

    /** Per-test drawer shape: true → the provider returns an empty list. */
    private var emptyDrawer = false

    private val fake = object : SiteProvider {
        override val name = "fakec"
        override val mainUrl = "https://fakec.test"
        override suspend fun search(query: String): List<ShowCard> = emptyList()
        override suspend fun loadEpisodes(showUrl: String): ShowDetails {
            fetches.incrementAndGet()
            delay(150) // hold the single-flight window open for racers
            val eps = if (emptyDrawer) emptyList() else listOf(
                EpisodeItem(title = "Episode 1", url = "$showUrl-episode-1.html", episodeNum = 1, site = name)
            )
            return ShowDetails(show = ShowCard(title = "Fake Show", url = showUrl, site = name), episodes = eps)
        }
        override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe =
            DownloadRecipe(directUrl = episodeUrl, filename = "f.mp4", backend = "aria2c")
    }

    private fun card(url: String) = ShowCard(title = "Fake Show", url = url, site = "fakec")

    @Before
    fun setUp() {
        fetches.set(0)
        emptyDrawer = false
        ProviderRegistry.loadEpisodesProviderForTest = fake
        ProviderRegistry.episodesTtlMs = 300_000L
        ProviderRegistry.emptyEpisodesTtlMs = 600_000L
        ProviderRegistry.clearEpisodeCachesForTest()
    }

    @After
    fun tearDown() {
        ProviderRegistry.loadEpisodesProviderForTest = null
    }

    @Test
    fun concurrentCallersMakeOneProviderFetchAndShareTheResult() = runBlocking {
        val results = (1..5).map {
            async { ProviderRegistry.loadEpisodes(card("https://fakec.test/d1")) }
        }.awaitAll()
        assertEquals("single fetch for 5 concurrent callers", 1, fetches.get())
        assertTrue(results.all { it.episodes.size == 1 })
        assertTrue(results.all { it.show.url == "https://fakec.test/d1" })
    }

    @Test
    fun emptyDrawerIsCachedInsideTheNegativeTtlWindow() = runBlocking {
        emptyDrawer = true
        val first = ProviderRegistry.loadEpisodes(card("https://fakec.test/d2"))
        val second = ProviderRegistry.loadEpisodes(card("https://fakec.test/d2"))
        assertTrue(first.episodes.isEmpty())
        assertTrue(second.episodes.isEmpty())
        assertEquals("empty drawer hit must not refetch", 1, fetches.get())
        ProviderRegistry.emptyEpisodesTtlMs = 0L
        ProviderRegistry.loadEpisodes(card("https://fakec.test/d2"))
        assertEquals("zero negative TTL must refetch", 2, fetches.get())
    }

    @Test
    fun nonEmptyDrawerKeepsThePositiveTtl() = runBlocking {
        ProviderRegistry.loadEpisodes(card("https://fakec.test/d3"))
        ProviderRegistry.loadEpisodes(card("https://fakec.test/d3"))
        assertEquals(1, fetches.get())
        ProviderRegistry.episodesTtlMs = 0L
        ProviderRegistry.loadEpisodes(card("https://fakec.test/d3"))
        assertEquals(2, fetches.get())
    }

    @Test
    fun differentShowUrlsNeverShareAnEntry() = runBlocking {
        ProviderRegistry.loadEpisodes(card("https://fakec.test/d4"))
        val other = ProviderRegistry.loadEpisodes(card("https://fakec.test/d5"))
        assertEquals(2, fetches.get())
        assertEquals("https://fakec.test/d5", other.show.url)
    }
}
