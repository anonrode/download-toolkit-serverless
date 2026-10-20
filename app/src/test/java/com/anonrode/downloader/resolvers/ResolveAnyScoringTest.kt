package com.anonrode.downloader.resolvers

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * resolveAny settle-and-score (2026-10-20).
 *
 * The 11:26 NEPU race validated a 390-byte self-loop first and cancelled the
 * genuine 3,347-byte HLS master that finished 8 ms later. The race now opens
 * a short settle window after the first success, stops launching new legs,
 * and picks by score instead of arrival order.
 */
class ResolveAnyScoringTest {

    @Test
    fun `in-flight better terminal wins over the first answer`() = runBlocking {
        val result = ResolverRegistry.settleAndScore(
            candidates = listOf("https://cdn.example/opaque-endpoint", "https://cdn.example/episode.mp4"),
            maxConcurrency = 2,
            episode = null,
            settleMs = 800L
        ) { url ->
            if (url.endsWith(".mp4")) {
                delay(150)
                url
            } else url
        }
        assertEquals("https://cdn.example/episode.mp4", result)
    }

    @Test
    fun `single fast success is returned after the settle window`() = runBlocking {
        val result = ResolverRegistry.settleAndScore(
            candidates = listOf("https://cdn.example/only"),
            maxConcurrency = 2,
            episode = null,
            settleMs = 100L
        ) { it }
        assertEquals("https://cdn.example/only", result)
    }

    @Test
    fun `no success yields null`() = runBlocking {
        val result = ResolverRegistry.settleAndScore(
            candidates = listOf("https://cdn.example/a", "https://cdn.example/b"),
            maxConcurrency = 2,
            episode = null,
            settleMs = 100L
        ) { null }
        assertNull(result)
    }

    @Test
    fun `episode pinning rejects cross-episode candidates`() {
        val ref = EpisodeRef(season = 2, episode = 7, tmdbId = "83097")
        assertFalse(
            episodeCandidateAllowed("https://nsrplay.space/embed/tv/83097/1/1", ref)
        )
        assertTrue(
            episodeCandidateAllowed("https://nsrplay.space/embed/tv/83097/2/7", ref)
        )
        // A CDN/terminal URL carries no identity: never rejected on absence.
        assertTrue(episodeCandidateAllowed("https://cdn.example/master.m3u8?token=x", ref))
        // A different tmdb is a different show.
        assertFalse(episodeCandidateAllowed("https://nsrplay.space/embed/tv/999/2/7", ref))
    }

    @Test
    fun `parse episode from the live mirror shapes`() {
        assertEquals(
            Triple("67710", 1, 2),
            parseEpisodeFromUrl("https://streamvaultsrc.click/embed/tv/67710/1/2")
        )
        assertEquals(
            Triple("", 2, 7),
            parseEpisodeFromUrl("https://net79.cc/watch-tmdb/83097?type=tv&season=2&episode=7")
        )
        assertEquals(
            Triple("", 2, 1),
            parseEpisodeFromUrl("https://cdn.example/The.Show.S02E01.mkv")
        )
    }

    @Test
    fun `score ranks direct media over opaque and rejects self-loops`() {
        val direct = terminalScore("https://x/ep.mp4", null, 0)
        val opaque = terminalScore("https://x/opaque", null, 0)
        assertTrue("direct should outrank opaque", direct > opaque)

        val selfLoop = com.anonrode.downloader.pipeline.UrlClassifier.TerminalEvidence(
            com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.SELF_REFERENTIAL,
            reason = "self"
        )
        val rejected = terminalScore("https://nsrplay.space/api/v1/embed/stream-proxy?token=x", selfLoop, 0)
        assertTrue("self-loop must be unselectable", rejected < 0)
    }
}
