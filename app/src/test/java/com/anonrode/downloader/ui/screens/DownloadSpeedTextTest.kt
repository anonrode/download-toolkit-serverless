package com.anonrode.downloader.ui.screens

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pin the card-face display contract of the speed readout fix: cards
 *  read the repository's windowed display fields (never the raw
 *  speedBytesPerSec), render "Starting…" while the window warms up, hide
 *  stalled/flat windows, and never show any rate off the DOWNLOADING
 *  state. */
class DownloadSpeedTextTest {

    private fun task(
        status: TaskStatus,
        displaySpeed: Double?,
        displayEta: Long? = null,
        rawSpeed: Double = 2.4e12,
        rawEta: Long = 7
    ) = DownloadTask(
        id = "t1",
        showTitle = "Show",
        episodeNum = 1,
        episodeTitle = "E1",
        directUrl = "https://cdn/e1.mp4",
        sourceUrl = "https://site/e1",
        status = status,
        speedBytesPerSec = rawSpeed,
        etaSeconds = rawEta,
        displaySpeedBytesPerSec = displaySpeed,
        displayEtaSeconds = displayEta
    )

    @Test
    fun warmingWindowRendersStartingNotTheRawValue() {
        // DOWNLOADING with a window still under ~3 s: the 2.4 TB/s raw
        // resume burst can NOT reach the card any more — "Starting…".
        assertEquals("Starting…", displaySpeedTextForCards(task(TaskStatus.DOWNLOADING, null)))
    }

    @Test
    fun windowedValueFormatsExactlyLikeBefore() {
        assertEquals("2.8 MB/s", displaySpeedTextForCards(task(TaskStatus.DOWNLOADING, 2_900_000.0)))
        assertEquals("488 KB/s", displaySpeedTextForCards(task(TaskStatus.DOWNLOADING, 500_000.0)))
        assertEquals("300 B/s", displaySpeedTextForCards(task(TaskStatus.DOWNLOADING, 300.0)))
    }

    @Test
    fun flatWindowHidesTheRateInsteadOfPrintingZero() {
        assertNull(displaySpeedTextForCards(task(TaskStatus.DOWNLOADING, 0.0)))
    }

    @Test
    fun noRateIsEverShownOffTheDownloadingState() {
        assertNull(displaySpeedTextForCards(task(TaskStatus.PAUSED, 2_900_000.0)))
        assertNull(displaySpeedTextForCards(task(TaskStatus.COMPLETED, 2_900_000.0)))
        assertNull(displaySpeedTextForCards(task(TaskStatus.QUEUED, 2_900_000.0)))
    }

    @Test
    fun etaPrefersTheWindowAndFallsBackToTheRawSticky() {
        assertEquals(42L, displayEtaSecondsOf(task(TaskStatus.DOWNLOADING, 2.9e6, displayEta = 42L, rawEta = 7)))
        // Window unknown (merge tail, warm-up): raw sticky passes through.
        assertEquals(7L, displayEtaSecondsOf(task(TaskStatus.DOWNLOADING, 2.9e6, displayEta = null, rawEta = 7)))
    }
}
