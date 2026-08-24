package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reopen park policy: no task may survive a restart in a state that the
 * network observer auto-starts. QUEUED included (v3.0.4: the observer's first
 * emission grabbed a restored QUEUED task within ~32ms of app open).
 */
class RestorePolicyTest {

    private fun task(
        status: TaskStatus,
        speed: Double = 12.5,
        error: String? = null,
        downloaded: Long = 5L * 1024 * 1024
    ) = DownloadTask(
        id = "t1",
        showTitle = "Show",
        episodeNum = 1,
        episodeTitle = "Ep 1",
        directUrl = "https://example.com/movie.mp4",
        downloadedBytes = downloaded,
        speedBytesPerSec = speed,
        status = status,
        errorMessage = error
    )

    @Test
    fun queuedParksAsPaused() {
        val t = parkForRestore(task(TaskStatus.QUEUED))
        assertEquals(TaskStatus.PAUSED, t.status)
        assertEquals(0.0, t.speedBytesPerSec, 0.0)
        assertNull(t.errorMessage)
    }

    @Test
    fun midFlightParksAsPaused() {
        for (s in listOf(TaskStatus.DOWNLOADING, TaskStatus.RESOLVING, TaskStatus.VALIDATING)) {
            val t = parkForRestore(task(s, error = "partial"))
            assertEquals(TaskStatus.PAUSED, t.status)
            assertEquals(0.0, t.speedBytesPerSec, 0.0)
            assertNull(t.errorMessage)
            // Bytes are kept so a manual resume continues where it stopped.
            assertEquals(5L * 1024 * 1024, t.downloadedBytes)
        }
    }

    @Test
    fun pausedClearsResumeMarkersKeepsBytes() {
        // "Waiting for Wi-Fi" / NETWORK_PAUSE_MESSAGE markers would trigger the
        // network observer on the next connect — they must not survive restart.
        val t = parkForRestore(task(TaskStatus.PAUSED, error = "Waiting for Wi-Fi"))
        assertEquals(TaskStatus.PAUSED, t.status)
        assertNull(t.errorMessage)
        assertEquals(5L * 1024 * 1024, t.downloadedBytes)
        assertEquals(12.5, t.speedBytesPerSec, 0.0)
    }

    @Test
    fun terminalStatesUntouched() {
        val failed = parkForRestore(task(TaskStatus.FAILED, error = "boom"))
        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("boom", failed.errorMessage)

        val done = parkForRestore(task(TaskStatus.COMPLETED))
        assertEquals(TaskStatus.COMPLETED, done.status)
    }
}
