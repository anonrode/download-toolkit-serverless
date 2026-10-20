package com.anonrode.downloader.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEPU episode pinning (2026-10-20 root cause F2).
 *
 * `srvMap` values are literal mirrors scraped from whichever watch page was
 * last fetched. The show page embeds the S01E01 cluster, and the old per-tmdb
 * cache put those URLs into EVERY episode's candidate list — the Servamp
 * S01E01==S01E02 duplicate and the S2E7-from-ep1 ResolveCache replay. The
 * cache key is now per episode and buildMirrors accepts a literal only when
 * its URL names the requested (tmdb, season, episode).
 */
class NepuMirrorPinningTest {

    @Test
    fun `srvMap cache key separates episodes and shows`() {
        assertEquals("83097|1|7", NepuProvider.srvMapKey("83097", 1, 7))
        assertEquals("83097|2|7", NepuProvider.srvMapKey("83097", 2, 7))
        assertEquals("83097|show", NepuProvider.srvMapKey("83097", null, null))
    }

    @Test
    fun `cross-episode srvMap literal never enters the candidate list`() {
        val srvMap = mapOf(
            "net79" to "https://net79.cc/watch-tmdb/83097?type=tv&season=1&episode=1",
            "vidbolt" to "https://vidbolt.xyz/api/resolve?type=tv&id=83097&season=2&episode=7"
        )
        val mirrors = NepuProvider.buildMirrors(
            mediaType = "tv",
            tmdbId = "83097",
            season = 2,
            episode = 7,
            srvMap = srvMap,
            primaryUrl = "https://nepu.gd/watch/tv/83097/2/7"
        )
        assertFalse(
            "episode-1 mirror leaked into S2E7",
            mirrors.any { it.contains("season=1&episode=1") }
        )
        assertTrue(
            "the correct-episode literal must survive",
            mirrors.any { it.contains("season=2&episode=7") }
        )
    }

    @Test
    fun `show-level literal without an episode identity is dropped`() {
        val mirrors = NepuProvider.buildMirrors(
            mediaType = "tv",
            tmdbId = "83097",
            season = 2,
            episode = 7,
            srvMap = mapOf("generic" to "https://embed.example/e/abc123")
        )
        assertFalse(mirrors.any { it.contains("embed.example") })
        // Synthetic live clusters still carry the right path shape.
        assertTrue(mirrors.any { it.contains("/tv/83097/2/7") })
    }

    @Test
    fun `same-episode literal and wrong-tmdb literal are handled`() {
        assertTrue(
            NepuProvider.mirrorNamesEpisode(
                "https://streamvaultsrc.click/embed/tv/67710/1/2", "67710", 1, 2
            )
        )
        assertFalse(
            NepuProvider.mirrorNamesEpisode(
                "https://streamvaultsrc.click/embed/tv/67710/1/1", "67710", 1, 2
            )
        )
        assertFalse(
            NepuProvider.mirrorNamesEpisode(
                "https://other.example/tv/99999/1/2", "67710", 1, 2
            )
        )
    }

    @Test
    fun `movie mirrors are unaffected by episode pinning`() {
        val mirrors = NepuProvider.buildMirrors(
            mediaType = "movie",
            tmdbId = "492715",
            season = null,
            episode = null,
            srvMap = mapOf("generic" to "https://embed.example/e/abc"),
            primaryUrl = "https://nepu.gd/watch/movie/492715"
        )
        assertTrue(mirrors.any { it.contains("/api/embed-tmdb/492715?type=movie") })
        assertTrue(mirrors.isNotEmpty())
    }
}
