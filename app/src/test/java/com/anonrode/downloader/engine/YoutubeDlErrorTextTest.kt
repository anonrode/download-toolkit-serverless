package com.anonrode.downloader.engine

import org.junit.Assert.assertFalse
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
}
