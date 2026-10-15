package com.anonrode.downloader.ui.screens

import androidx.compose.ui.graphics.Color
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Downloads v6 status vocabulary (mock frame 1:
 * ui-proposal-downloads-v6-drawer.html). Every state must carry colour + WORD
 * + GLYPH — never colour alone — with the mock's pinned chroma, and the grey
 * detail after the word must stay honest: real percent/bytes where the record
 * knows them, no stale rate on paused rows, the last known percentage kept on
 * failed rows.
 */
class DownloadStatusStyleTest {

    private fun task(
        status: TaskStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBytesPerSec: Double = 0.0,
        etaSeconds: Long = 0L,
        errorMessage: String? = null
    ): DownloadTask = DownloadTask(
        id = "t",
        showTitle = "Test Show",
        episodeNum = 1,
        episodeTitle = "Episode 1",
        directUrl = "https://cdn.example.invalid/ep1.mp4",
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        speedBytesPerSec = speedBytesPerSec,
        etaSeconds = etaSeconds,
        status = status,
        errorMessage = errorMessage
    )

    @Test
    fun liveCarriesWordGlyphAndMockChroma() {
        val live = downloadStatusStyle(TaskStatus.DOWNLOADING)
        assertEquals(DownloadStateKind.LIVE, live.kind)
        assertEquals("Downloading", live.word)
        assertEquals("●", live.glyph)
        assertEquals(Color(0xFF0E7490), live.accent) // mock --live
        assertEquals(Color(0xFFE0F2F7), live.tint)   // mock --live-tint
        // The meter fill (#1591B3, mock --live-fill) is pinned in the screen's
        // meter composable, not in this mapping.
        // The engine's RESOLVING phase belongs to the live set (same bucketing
        // as downloadsStats), so it speaks the same word + glyph + chroma.
        assertEquals(live, downloadStatusStyle(TaskStatus.RESOLVING))
    }

    @Test
    fun donePausedFailedCarryWordsGlyphsAndMockChroma() {
        val done = downloadStatusStyle(TaskStatus.COMPLETED)
        assertEquals(DownloadStateKind.DONE, done.kind)
        assertEquals("Done", done.word)
        assertEquals("✓", done.glyph)
        assertEquals(Color(0xFF1F7A4D), done.accent) // mock --done
        assertEquals(Color(0xFFE6F4EC), done.tint)   // mock --done-tint

        val paused = downloadStatusStyle(TaskStatus.PAUSED)
        assertEquals(DownloadStateKind.PAUSED, paused.kind)
        assertEquals("Paused", paused.word)
        assertEquals("❚❚", paused.glyph)
        assertEquals(Color(0xFF92610F), paused.accent) // mock --paused
        assertEquals(Color(0xFFFBF1DE), paused.tint)   // mock --paused-tint

        val failed = downloadStatusStyle(TaskStatus.FAILED)
        assertEquals(DownloadStateKind.FAILED, failed.kind)
        assertEquals("Failed", failed.word)
        assertEquals("✕", failed.glyph)
        assertEquals(Color(0xFFA84A42), failed.accent) // mock --failed
        assertEquals(Color(0xFFF9E9E7), failed.tint)   // mock --failed-tint

        // Four states must stay distinguishable by more than colour alone.
        assertEquals(4, listOf(done, paused, failed, live()).map { it.glyph }.distinct().size)
    }

    @Test
    fun nonMockStatesStayNeutralWithWordAndGlyph() {
        val queued = downloadStatusStyle(TaskStatus.QUEUED)
        assertEquals(DownloadStateKind.NEUTRAL, queued.kind)
        assertEquals("Queued", queued.word)
        assertEquals("○", queued.glyph)
        assertEquals(Color(0xFF55627A), queued.accent)
        assertEquals(Color(0xFFEEF2F6), queued.tint)

        val checking = downloadStatusStyle(TaskStatus.VALIDATING)
        assertEquals(DownloadStateKind.NEUTRAL, checking.kind)
        assertEquals("Checking", checking.word)
        // Word + glyph still differentiate it from the live dot's "Downloading".
        assertTrue(checking.word != downloadStatusStyle(TaskStatus.DOWNLOADING).word)
    }

    @Test
    fun groupCountColoursFollowTheMockSectRules() {
        assertEquals(Color(0xFF0E7490), groupCountColor("ACTIVE"))   // state header → live
        assertEquals(Color(0xFF0E7490), groupCountColor("TODAY"))    // mock .sect .cnt default
        assertEquals(Color(0xFF0E7490), groupCountColor("THIS WEEK"))
        assertEquals(Color(0xFF0E7490), groupCountColor("Social & Media".uppercase()))
        assertEquals(Color(0xFF92610F), groupCountColor("PAUSED"))
        assertEquals(Color(0xFFA84A42), groupCountColor("FAILED"))
        assertEquals(Color(0xFF1F7A4D), groupCountColor("DONE"))
    }

    @Test
    fun detailLineStaysHonestPerState() {
        // Paused: percent + real bytes, NO stale speed (v5 rule kept).
        val paused = task(
            TaskStatus.PAUSED,
            downloadedBytes = 50L * MB,
            totalBytes = 100L * MB,
            speedBytesPerSec = 2.0 * MB
        )
        assertEquals("· 50% · 50.0 MB / 100.0 MB", statusDetailText(paused))

        // Live: percent + bytes + the fresh rate.
        val live = task(
            TaskStatus.DOWNLOADING,
            downloadedBytes = 50L * MB,
            totalBytes = 100L * MB,
            speedBytesPerSec = 2.0 * MB
        )
        assertEquals("· 50% · 50.0 MB / 100.0 MB · 2.0 MB/s", statusDetailText(live))

        // Failed: freezes at the last known percentage ("· stuck at N%", the
        // mock's stall meter companion); with no known data it still tells the
        // user the row's verb.
        assertEquals(
            "· stuck at 71%",
            statusDetailText(task(TaskStatus.FAILED, downloadedBytes = 71L * MB, totalBytes = 100L * MB))
        )
        assertEquals("· tap to retry", statusDetailText(task(TaskStatus.FAILED)))

        // Done: the finished size.
        assertEquals(
            "· 100.0 MB",
            statusDetailText(task(TaskStatus.COMPLETED, downloadedBytes = 100L * MB, totalBytes = 100L * MB))
        )

        // Queued: no numbers until the slot opens.
        assertEquals("· waiting to start", statusDetailText(task(TaskStatus.QUEUED)))
    }

    private fun live(): DownloadStatusStyle = downloadStatusStyle(TaskStatus.DOWNLOADING)

    private companion object {
        const val MB = 1024L * 1024L
    }
}
