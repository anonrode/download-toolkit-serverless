package com.anonrode.downloader.engine

import com.anonrode.downloader.pipeline.LinkResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 pin — the HLS 1-byte terminal gate (activity-log audit §3.4). The
 * terminal probe accepts any 200 for an m3u8/mpegurl target
 * (HttpClient.kt:1303-1308), so consumers that only see the probe's
 * totalBytes must reject the sub-1024-byte class themselves:
 * `LinkResolver.isHlsStubTerminal` (used by RulesPipeline's probe terminal
 * and ResultVerifier's Live verdict). A real playlist is never 1 byte;
 * `200 size=1` was live-measured on embed.vidrift.net master.m3u8 URLs.
 */
class HlsStubTerminalTest {

    @Test
    fun oneByteHlsStubIsRejected() {
        assertTrue(LinkResolver.isHlsStubTerminal("https://embed.vidrift.net/x/master.m3u8", 1L))
        assertTrue(LinkResolver.isHlsStubTerminal("https://cdn.x/some/path/index.m3u8?token=abc", 512L))
        // The .mpegurl spelling is the same class.
        assertTrue(LinkResolver.isHlsStubTerminal("https://cdn.x/live/stream.mpegurl", 4L))
    }

    @Test
    fun realPlaylistSizesAndNonHlsUrlsPass() {
        assertFalse(LinkResolver.isHlsStubTerminal("https://cdn.x/master.m3u8", 1024L))
        assertFalse(LinkResolver.isHlsStubTerminal("https://cdn.x/master.m3u8", 3_000L))
        // A tiny direct media file is not the HLS class this gate targets.
        assertFalse(LinkResolver.isHlsStubTerminal("https://cdn.x/movie.mp4", 1L))
        // Zero/negative is never a stub verdict (probe floors at 1 anyway).
        assertFalse(LinkResolver.isHlsStubTerminal("https://cdn.x/master.m3u8", 0L))
    }
}
