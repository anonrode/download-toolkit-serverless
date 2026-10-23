package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-3 (rows 35/8, 2026-10-21): monolith fragment patience on the yt-dlp HLS
 * fallback ladder. The Python monolith's HLS path passes
 * `--retries 10 --fragment-retries 10 --retry-sleep 10
 * --retry-sleep fragment:exp=1:20` (download-toolkit/src/downloader.py:3576-3579);
 * the released 5×1s fragment ladder produced the literal "Giving up after 5
 * retries" stderr and 8 parks at the same frozen boundary (c6710cfb). This
 * config-builder fixture pins the exact flags for BOTH HLS branches, which
 * source them from one seam so they can never drift apart again.
 */
class HlsFragmentPatienceFlagsTest {

    private val flags = YoutubeDlDownloader.hlsFragmentPatienceFlags()
        .also {
            // The list ends in the boolean --abort-on-unavailable-fragments,
            // so parity is not a contract; every option still starts with --.
            assertTrue("expected at least one option", it.isNotEmpty())
            assertTrue("every entry must be a yt-dlp option/word", it.all { s -> s.startsWith("--") || !s.startsWith("-") })
        }

    private fun valueAfter(option: String, occurrence: Int = 0): String? {
        val idx = flags.indices.filter { flags[it] == option }.getOrNull(occurrence) ?: return null
        return flags.getOrNull(idx + 1)
    }

    @Test
    fun `mirror the monolith exactly`() {
        // downloader.py:3576-3579, value-for-value.
        assertEquals("10", valueAfter("--retries"))
        assertEquals("10", valueAfter("--fragment-retries"))
        assertEquals("10", valueAfter("--retry-sleep", 0))
        // The fragment ladder's exponential backoff: 1s growing to a 20s cap.
        assertEquals("fragment:exp=1:20", valueAfter("--retry-sleep", 1))
    }

    @Test
    fun `ladder still fails loudly on missing fragments`() {
        assertTrue(flags.contains("--abort-on-unavailable-fragments"))
    }

    @Test
    fun `socket timeout survives the parity swap`() {
        assertEquals("15", valueAfter("--socket-timeout"))
    }
}
