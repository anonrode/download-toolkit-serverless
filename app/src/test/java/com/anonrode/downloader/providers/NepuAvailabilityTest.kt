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
    fun testNepuExtractSrvMap() {
        val html = """
        <script>
        var SRV_MAP={"vidsrcto":"https://vidsrc.mov/embed/movie/550","vidrock":"https://vidrock.net/movie/550"},SRV_DEF="vidsrcto";
        </script>
        """.trimIndent()
        val map = NepuProvider.extractSrvMap(html)
        assertEquals(2, map.size)
        assertEquals("https://vidsrc.mov/embed/movie/550", map["vidsrcto"])
        assertEquals("https://vidrock.net/movie/550", map["vidrock"])
    }

    @Test
    fun testNepuBuildMirrorsContainsAll14Servers() {
        val mirrors = NepuProvider.buildMirrors("movie", "550", primaryUrl = "https://nepu.gd/watch/movie/550")
        assertEquals(14, mirrors.size)
        assertTrue(mirrors.any { it.contains("vidsrc.mov") })
        assertTrue(mirrors.any { it.contains("vidsrc.fyi") })
        assertTrue(mirrors.any { it.contains("vidrock.net") })
        assertTrue(mirrors.any { it.contains("vidnest.fun") })
        assertTrue(mirrors.any { it.contains("vidking.net") })
        assertTrue(mirrors.any { it.contains("vidlink.pro") })
        assertTrue(mirrors.any { it.contains("vidfast.pro") })
        assertTrue(mirrors.any { it.contains("vidup.to") })
        assertTrue(mirrors.any { it.contains("videasy.net") })
        assertTrue(mirrors.any { it.contains("111movies.com") })
        assertTrue(mirrors.any { it.contains("2embed.cc") })
        assertTrue(mirrors.any { it.contains("multiembed.mov") })
        assertTrue(mirrors.any { it.contains("superflixapi.co") })
        assertTrue(mirrors.any { it.contains("peachify.top") })
    }

    @Test
    fun testNepuLoadEpisodesPopulatesMirrorUrls() = runBlocking {
        val details = NepuProvider.loadEpisodes("https://nepu.gd/watch/movie/550")
        assertEquals(1, details.episodes.size)
        val mirrors = details.episodes[0].mirrorUrls
        assertTrue("Expected mirrors to contain alternative embed servers, got ${mirrors.size}", mirrors.size >= 14)
    }
}
