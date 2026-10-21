package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restore policy under the owner's acceptance invariant (2026-10-21): every
 * error ends in a download or a proven no-source — a restart must never turn
 * a system park or an interrupted download into a stranded manual pause.
 *
 * CONTRACT CHANGE vs the pre-fix suite (deliberate, directive-driven): the
 * old "reopen never auto-resumes anything" contract mapped EVERY restored
 * task to PAUSED + userPaused=true + no marker, which made all system parks
 * (host cooldown, network flap, Wi-Fi gate, storage) permanent manual pauses
 * after any app restart or crash. The v3.0.4 drain concern is now answered
 * with a one-shot backoff floor (DownloadEngine's boot block stamps
 * requeueNotBeforeMs; the observer sweeps honor it), not by stranding.
 * Only a USER pause keeps its pause-immunity across restarts.
 */
class RestorePolicyTest {

    private fun task(
        status: TaskStatus,
        speed: Double = 12.5,
        error: String? = null,
        downloaded: Long = 5L * 1024 * 1024,
        userPaused: Boolean = false
    ) = DownloadTask(
        id = "t1",
        showTitle = "Show",
        episodeNum = 1,
        episodeTitle = "Ep 1",
        directUrl = "https://example.com/movie.mp4",
        downloadedBytes = downloaded,
        speedBytesPerSec = speed,
        status = status,
        errorMessage = error,
        userPaused = userPaused
    )

    @Test
    fun interruptedQueuedTaskRestoresAsRequeueableNetworkPause() {
        val t = parkForRestore(task(TaskStatus.QUEUED))
        assertEquals(TaskStatus.PAUSED, t.status)
        assertEquals(0.0, t.speedBytesPerSec, 0.0)
        assertEquals(DownloadEngine.NETWORK_PAUSE_MESSAGE, t.errorMessage)
        assertFalse("system restore must not strand as a manual pause", t.userPaused)
    }

    @Test
    fun interruptedMidFlightTasksRestoreAsRequeueableNetworkPause() {
        for (s in listOf(TaskStatus.DOWNLOADING, TaskStatus.RESOLVING, TaskStatus.VALIDATING)) {
            val t = parkForRestore(task(s, error = "partial"))
            assertEquals(TaskStatus.PAUSED, t.status)
            assertEquals(0.0, t.speedBytesPerSec, 0.0)
            assertEquals(DownloadEngine.NETWORK_PAUSE_MESSAGE, t.errorMessage)
            assertFalse(t.userPaused)
            // Banked bytes must survive the restore verbatim (THE RULE:
            // landed bytes are saved progress, never thrown away).
            assertEquals(5L * 1024 * 1024, t.downloadedBytes)
        }
    }

    @Test
    fun systemParksKeepTheirMarkerAndStayAutoResumable() {
        val markers = listOf(
            DownloadEngine.PARKED_HOST_MESSAGE + " — will retry in ~1m — 5 MB already saved, it will resume",
            DownloadEngine.NETWORK_PAUSE_MESSAGE,
            "Waiting for Wi-Fi (Wi-Fi Only enabled)",
            "Storage limit reached — 9.9 GB free of 10.0 GB guard"
        )
        for (marker in markers) {
            val t = parkForRestore(task(TaskStatus.PAUSED, error = marker, userPaused = false))
            assertEquals(TaskStatus.PAUSED, t.status)
            assertEquals("park marker must survive restart so its family loop requeues: $marker", marker, t.errorMessage)
            assertFalse(t.userPaused)
            assertEquals(5L * 1024 * 1024, t.downloadedBytes)
        }
    }

    @Test
    fun manualPauseStaysManualAcrossRestart() {
        val t = parkForRestore(task(TaskStatus.PAUSED, error = DownloadEngine.PARKED_HOST_MESSAGE, userPaused = true))
        assertEquals(TaskStatus.PAUSED, t.status)
        assertNull("manual pause marker must be cleared", t.errorMessage)
        assertTrue(t.userPaused)
    }

    @Test
    fun systemPauseWithNoRetryFamilyMapsToManual() {
        // A pause whose message no family loop can act on must not stay
        // !userPaused with a live marker nobody will honor.
        val t = parkForRestore(task(TaskStatus.PAUSED, error = "some unparsed producer message", userPaused = false))
        assertNull(t.errorMessage)
        assertTrue(t.userPaused)
        assertEquals(5L * 1024 * 1024, t.downloadedBytes)
    }

    @Test
    fun terminalStatesUntouched() {
        val failed = parkForRestore(task(TaskStatus.FAILED, error = "boom"))
        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("boom", failed.errorMessage)

        val done = parkForRestore(task(TaskStatus.COMPLETED))
        assertEquals(TaskStatus.COMPLETED, done.status)
    }

    @Test
    fun requeueableMarkerRecognizerMatchesExactlyTheFourFamilies() {
        assertTrue(isRequeueableParkMarker(DownloadEngine.PARKED_HOST_MESSAGE))
        assertTrue(isRequeueableParkMarker(DownloadEngine.PARKED_HOST_MESSAGE + " — will retry in ~15m"))
        assertTrue(isRequeueableParkMarker(DownloadEngine.NETWORK_PAUSE_MESSAGE))
        assertTrue(isRequeueableParkMarker("Waiting for Wi-Fi (Wi-Fi Only enabled)"))
        assertTrue(isRequeueableParkMarker("Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)"))
        assertTrue(isRequeueableParkMarker("Storage limit reached — free space needed"))
        assertFalse(isRequeueableParkMarker(null))
        assertFalse(isRequeueableParkMarker(""))
        assertFalse("network marker match is exact, not prefix", isRequeueableParkMarker("Waiting for network... (extra)"))
        assertFalse(isRequeueableParkMarker("boom"))
    }
}
