package com.anonrode.downloader.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal-evidence classification (2026-10-20 NEPU root cause).
 *
 * The old code hardcoded `stream-proxy` in BOTH classifiers — "provably
 * direct" (LinkResolver) and "streaming/HLS" (UrlClassifier) — which is why a
 * 390-byte self-referential playlist went to yt-dlp and looped forever. These
 * tests pin the evidence-based replacement: the URL NAME decides nothing; the
 * bounded probe/body decides.
 */
class UrlClassifierEvidenceTest {

    @Test
    fun `stream-proxy is neither streaming nor provably direct by name`() {
        val proxy = "https://nsrplay.space/api/v1/embed/stream-proxy?token=abc123"
        assertFalse(UrlClassifier.isStreamingLink(proxy))
        assertFalse(LinkResolver.isProvablyDirectFile(proxy))
        assertFalse(UrlClassifier.isDirectMediaPath(proxy))
    }

    @Test
    fun `genuine m3u8 stays streaming`() {
        assertTrue(UrlClassifier.isStreamingLink("https://cdn.example/master.m3u8?token=x"))
        assertTrue(UrlClassifier.isStreamingLink("https://embed.example/api/orion/abc/master.m3u8"))
    }

    @Test
    fun `direct media extension stays direct`() {
        assertTrue(UrlClassifier.isDirectMediaPath("https://cdn.example/episode.mp4?sign=x"))
        assertTrue(UrlClassifier.isDirectMediaPath("https://cdn.example/episode.mkv"))
        assertFalse(UrlClassifier.isDirectMediaPath("https://cdn.example/playlist"))
    }

    @Test
    fun `self-referential master is detected even when only the token rotates`() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=798854,RESOLUTION=1280x720
            https://nsrplay.space/api/v1/embed/stream-proxy?token=ROTATED1
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=149133,URI="https://nsrplay.space/api/v1/embed/stream-proxy?token=ROTATED2"
        """.trimIndent()
        assertTrue(
            UrlClassifier.isSelfReferentialManifest(
                body, "https://nsrplay.space/api/v1/embed/stream-proxy?token=ORIGINAL"
            )
        )
    }

    @Test
    fun `a real multi-host master is not self-referential`() {
        val body = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=585840,RESOLUTION=1920x1080,AUDIO="aud"
            video/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=285840,RESOLUTION=1280x720,AUDIO="aud"
            video/720.m3u8
        """.trimIndent()
        assertFalse(
            UrlClassifier.isSelfReferentialManifest(
                body, "https://dash-hls-bridge.johndoesdmc.workers.dev/?url=https%3A%2F%2Fsacdn.example%2Fmaster.m3u8"
            )
        )
    }

    @Test
    fun `self-loop with one real variant is not rejected wholesale`() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=798854
            https://nsrplay.space/api/v1/embed/stream-proxy?token=ROTATED
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            https://cdn.example/real/1080.m3u8
        """.trimIndent()
        assertFalse(
            UrlClassifier.isSelfReferentialManifest(
                body, "https://nsrplay.space/api/v1/embed/stream-proxy?token=ORIGINAL"
            )
        )
    }

    @Test
    fun `tiny mpegurl without variants or segments is a stub`() {
        val tiny = "#EXTM3U\n#EXT-X-VERSION:3\n"
        assertTrue(UrlClassifier.isTinyManifest(tiny, "application/x-mpegurl", tiny.length.toLong()))
        val real = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:9.9,\nseg1.ts\n#EXTINF:9.9,\nseg2.ts\n"
        assertFalse(UrlClassifier.isTinyManifest(real, "application/x-mpegurl", 10_000L))
    }

    @Test
    fun `concatenated template url is repaired only when it is a doubled origin`() {
        assertEquals(
            "https://vidsrc.buzz/_stream?id=abc",
            UrlClassifier.repairConcatenatedUrl("https://vidsrc.buzzhttps://vidsrc.buzz/_stream?id=abc")
        )
        // A legitimate embedded URL parameter must be untouched.
        val bridge = "https://dash-hls-bridge.x.workers.dev/?url=https%3A%2F%2Fsacdn.example%2Fmaster.m3u8"
        assertEquals(bridge, UrlClassifier.repairConcatenatedUrl(bridge))
    }

    @Test
    fun `manifest shape detection includes mpegurl types and m3u8 paths`() {
        assertTrue(UrlClassifier.isManifestShaped("https://x/y?id=1", "application/vnd.apple.mpegurl"))
        assertTrue(UrlClassifier.isManifestShaped("https://x/master.m3u8", ""))
        assertFalse(UrlClassifier.isManifestShaped("https://x/movie.mp4", "video/mp4"))
    }

    @Test
    fun `direct content types are recognized`() {
        assertTrue(UrlClassifier.isDirectByContentType("video/mp4"))
        assertTrue(UrlClassifier.isDirectByContentType("application/octet-stream"))
        assertTrue(UrlClassifier.isDirectByContentType("audio/mpeg"))
        assertFalse(UrlClassifier.isDirectByContentType("application/vnd.apple.mpegurl"))
        assertFalse(UrlClassifier.isDirectByContentType("text/html"))
    }
}
