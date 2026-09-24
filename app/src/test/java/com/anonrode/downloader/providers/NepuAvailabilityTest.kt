package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.resolvers.VidsrcResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NepuAvailabilityTest {

    @Before
    fun setUp() {
        VidsrcResolver.missingTmdb.clear()
        VidsrcResolver.availableTmdb.clear()
    }

    @Test
    fun testVidsrcAvailabilityCacheTtlAndLookup() {
        assertFalse(VidsrcResolver.isKnownUnavailable("movie", "518773"))
        assertFalse(VidsrcResolver.isKnownAvailable("movie", "518773"))

        VidsrcResolver.markUnavailable("movie", "518773")
        assertTrue(VidsrcResolver.isKnownUnavailable("movie", "518773"))
        assertFalse(VidsrcResolver.isKnownAvailable("movie", "518773"))

        VidsrcResolver.markAvailable("movie", "550")
        assertTrue(VidsrcResolver.isKnownAvailable("movie", "550"))
        assertFalse(VidsrcResolver.isKnownUnavailable("movie", "550"))
    }

    @Test
    fun testNepuFilterAvailablePrunesMissingTmdbEntries() = runBlocking {
        VidsrcResolver.markAvailable("movie", "550")
        VidsrcResolver.markUnavailable("movie", "518773")
        VidsrcResolver.markUnavailable("tv", "34188")
        VidsrcResolver.markAvailable("tv", "69158")

        val cards = listOf(
            ShowCard(title = "Fight Club", url = "https://nepu.gd/watch/movie/550", site = "nepu"),
            ShowCard(title = "Ghost Title 1", url = "https://nepu.gd/watch/movie/518773", site = "nepu"),
            ShowCard(title = "Ghost TV", url = "https://nepu.gd/watch/tv/34188", site = "nepu"),
            ShowCard(title = "The Good Fight", url = "https://nepu.gd/watch/tv/69158", site = "nepu")
        )

        val filtered = NepuProvider.filterAvailable(cards)
        assertEquals(2, filtered.size)
        assertEquals("https://nepu.gd/watch/movie/550", filtered[0].url)
        assertEquals("https://nepu.gd/watch/tv/69158", filtered[1].url)
    }

    @Test
    fun testNepuFilterAvailablePreservesNonTmdbUrls() = runBlocking {
        val cards = listOf(
            ShowCard(title = "Custom Page", url = "https://nepu.gd/watch/custom-stream", site = "nepu")
        )
        val filtered = NepuProvider.filterAvailable(cards)
        assertEquals(1, filtered.size)
        assertEquals("https://nepu.gd/watch/custom-stream", filtered[0].url)
    }

    @Test
    fun testNepuLoadEpisodesReturnsEmptyForKnownMissingTmdb() = runBlocking {
        VidsrcResolver.markUnavailable("movie", "518773")
        val details = NepuProvider.loadEpisodes("https://nepu.gd/watch/movie/518773")
        assertTrue(details.episodes.isEmpty())
    }
}
