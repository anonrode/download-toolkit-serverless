package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-facing error text (AGENTS rule 14, 2026-10-20): the raw
 * CanceledException wrapper and the empty `ERROR: ` aggregate must be
 * recognized and replaced, never shown as-is.
 */
class YoutubeDlErrorTextTest {

    @Test
    fun `all-canceled ladder is recognized`() {
        assertTrue(
            YoutubeDlDownloader.isAllCanceledLadder(
                "run 1: CanceledException\nrun 2: CanceledException\nrun 3: CanceledException",
                3
            )
        )
        assertTrue(
            YoutubeDlDownloader.isAllCanceledLadder("run 1: read interrupted by close() on another thread", 1)
        )
    }

    @Test
    fun `a real platform error is not treated as a cancel`() {
        assertFalse(YoutubeDlDownloader.isAllCanceledLadder("run 1: ERROR: Unsupported URL", 1))
    }

    @Test
    fun `empty yt-dlp aggregate is recognized`() {
        assertTrue(YoutubeDlDownloader.isEmptyYtDlpError(""))
        assertTrue(YoutubeDlDownloader.isEmptyYtDlpError("run 1: ERROR: \nrun 2: ERROR: "))
        assertFalse(YoutubeDlDownloader.isEmptyYtDlpError("run 1: ERROR: HTTP Error 403"))
    }

    // ---- truth preservation (charter 12.6, share-43 rows 8/32): the blank
    // `ERROR: ` aggregate must not discard the backend's diagnostic line and
    // HTTP status — 8 parks held 89,794 KiB with blank reasons while the
    // source answered 200 WITH Referer / 403 WITHOUT.

    @Test
    fun `diagnostic line filter rejects progress and accepts error evidence`() {
        assertFalse(YoutubeDlDownloader.isYtDlpDiagnosticLine("[download]  42.3% of  300.00MiB at  2.10MiB/s"))
        assertFalse(YoutubeDlDownloader.isYtDlpDiagnosticLine("[download] Destination: out.mp4"))
        assertFalse(YoutubeDlDownloader.isYtDlpDiagnosticLine("progress:@@DLP@@12|300|2100"))
        assertTrue(YoutubeDlDownloader.isYtDlpDiagnosticLine("ERROR: Unable to download webpage: HTTP Error 403: Forbidden"))
        assertTrue(YoutubeDlDownloader.isYtDlpDiagnosticLine("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertTrue(YoutubeDlDownloader.isYtDlpDiagnosticLine("WARNING: unable to download video data"))
    }

    @Test
    fun `last http status is extracted from the real stderr shapes`() {
        assertEquals(
            403,
            YoutubeDlDownloader.lastHttpStatus(
                "run 1: ERROR: Unable to download webpage: HTTP Error 403: Forbidden"
            )
        )
        assertEquals(
            401,
            YoutubeDlDownloader.lastHttpStatus(
                "run 2: ERROR: unable to download video data: HTTP Error 401: Unauthorized"
            )
        )
        assertEquals(
            403,
            YoutubeDlDownloader.lastHttpStatus("[generic] 403 Forbidden for https://x")
        )
        assertNull(YoutubeDlDownloader.lastHttpStatus("run 1: ERROR: "))
    }

    @Test
    fun `empty aggregate error carries the diagnostic verbatim plus the http status`() {
        val diagnostic = "ERROR: Unable to download webpage: HTTP Error 403: Forbidden"
        val msg = YoutubeDlDownloader.buildEmptyYtDlpErrorResponse(diagnostic, 403)
        assertTrue("diagnostic line must be verbatim", msg.contains(diagnostic))
        assertTrue("status must be named", msg.contains("last HTTP status 403"))
        assertFalse("no blank aggregate claims", msg.contains("returned an empty response"))
    }

    @Test
    fun `empty aggregate error says so honestly when nothing was captured`() {
        val msg = YoutubeDlDownloader.buildEmptyYtDlpErrorResponse(null, null)
        assertTrue(msg.contains("no diagnostic line"))
        assertTrue(msg.contains("no HTTP status reported"))
    }

    @Test
    fun `isSocialExtractorUrl classifies social URLs correctly`() {
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://www.instagram.com/reel/DeFVVFRuDvW/"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://instagr.am/p/12345/"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://www.facebook.com/share/r/19aMkNLP5v/"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://fb.watch/xyz/"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://twitter.com/user/status/123"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://x.com/AsianDawn4/status/2113744220393762900"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://www.tiktok.com/@user/video/123"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(YoutubeDlDownloader.isSocialExtractorUrl("https://youtu.be/abc"))

        assertFalse(YoutubeDlDownloader.isSocialExtractorUrl("https://kissorgrab.com/dl/123"))
        assertFalse(YoutubeDlDownloader.isSocialExtractorUrl("https://wildshare.net/123"))
        assertFalse(YoutubeDlDownloader.isSocialExtractorUrl("https://silversurfer.wildshare.net/123"))
    }
}
