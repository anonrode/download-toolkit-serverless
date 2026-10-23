package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Speed readout fix (investigation/14_player_trace.md §B): DownloadRepository
 * carries a per-task (time, bytes) ring window. The DISPLAYED speed is the
 * byte delta across that window — never the raw 250 ms EMA nor the
 * verbatim parsed yt-dlp/aria line — with a starting gate (~3 s), a
 * physical-ceiling clamp, and a sticky total so the displayed denominator
 * cannot regress mid-download. The RAW task.speedBytesPerSec keeps its
 * exact semantics for the engine's ProgressWatchdog and is asserted
 * unchanged here.
 */
class DownloadSpeedDisplayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Injected clock: ticks advance it by 250 ms per updateProgress like
     *  the real backends do. */
    private var now = 0L

    private fun seededRepo(task: DownloadTask): DownloadRepository {
        val dir: File = tmp.newFolder("state-${System.nanoTime()}")
        val json = Json { encodeDefaults = true }
        File(dir, "download_tasks.json").writeText(json.encodeToString(listOf(task)))
        val repo = DownloadRepository()
        repo.initPersistence(dir, context = null)
        repo.clockMs = { now }
        // initPersistence parks every DOWNLOADING row (process-death safety).
        // These fixtures model a LIVE transfer, so revive the row explicitly.
        repo.update("t1") { it.copy(status = TaskStatus.DOWNLOADING, errorMessage = null, userPaused = false) }
        return repo
    }

    private fun downloadingTask(totalBytes: Long) = DownloadTask(
        id = "t1",
        showTitle = "Show",
        episodeNum = 1,
        episodeTitle = "E1",
        directUrl = "https://cdn/e1.mp4",
        sourceUrl = "https://site/e1",
        status = TaskStatus.DOWNLOADING,
        totalBytes = totalBytes,
        downloadedBytes = 0L
    )

    private fun repoTask(repo: DownloadRepository): DownloadTask =
        repo.tasks.value.first { it.id == "t1" }

    private fun updateProgress(
        repo: DownloadRepository,
        downloaded: Long,
        total: Long,
        speed: Double,
        eta: Long
    ) {
        repo.updateProgress(taskId = "t1", downloaded = downloaded, total = total, speed = speed, eta = eta)
    }

    // ── Pure window math ────────────────────────────────────────────────

    @Test
    fun windowStaysNullUntilMinSpanThenReportsWindowedRate() {
        val window = TaskDisplayWindow()
        assertNull(window.record(0, 0L))
        assertNull(window.record(1_000, 2_000_000L))
        assertNull(window.record(2_000, 4_000_000L))
        // 3.1 s span over a steady 2 MB/s ramp: the reading is the window
        // average (6.2 MB over 3.1 s), NOT the last instant.
        val rate = window.record(3_100, 6_200_000L)
        assertEquals(2_000_000.0, rate!!, 1.0)
    }

    @Test
    fun impossibleBurstIsClampedAtThePhysicalCap() {
        val window = TaskDisplayWindow()
        assertNull(window.record(0, 0L))
        assertNull(window.record(1_000, 1_000_000L))
        // The 2.4 TB/s resume-priming shape: one sample claims a huge
        // recount jump. It is clamped against the previous sample at the
        // cap for the elapsed time — the window can never lift it out, and
        // the reading itself can never exceed the cap.
        val rate = window.record(4_000, 1_000_000L + 2_400_000_000_000L)
        assertTrue(rate!! <= DISPLAY_SPEED_CEILING_BPS)
        // The consumed burst also cannot push later readings over the cap.
        val later = window.record(7_000, 1_000_000L + 2_400_000_000_000L + 3_000_000L)
        assertTrue(later!! <= DISPLAY_SPEED_CEILING_BPS)
        assertEquals(DISPLAY_SPEED_CEILING_BPS, later!!, 1.0)
    }

    @Test
    fun staleSamplesBeyondTheWindowDoNotDiluteTheRate() {
        val window = TaskDisplayWindow()
        window.record(0, 0L)
        window.record(1_000, 2_000_000L)
        window.record(2_000, 4_000_000L)
        // A long pause (or process-gap) followed by a resume: samples older
        // than the window edge drop, so the first reading after the gap
        // starts a FRESH window (null) instead of averaging across 58 idle
        // seconds.
        assertNull(window.record(60_000, 10_000_000L))
        val resumed = window.record(63_000, 16_000_000L)
        assertEquals(2_000_000.0, resumed!!, 1.0)
    }

    @Test
    fun bytesNeverMoveBackwardsInsideTheWindow() {
        val window = TaskDisplayWindow()
        window.record(0, 5_000_000L)
        window.record(1_000, 7_000_000L)
        // A reset-shaped report claiming FEWER bytes: monotonic clamp keeps
        // the window telescoping forward only — the rate degrades to the
        // true floor (0) rather than going negative.
        val rate = window.record(3_500, 6_000_000L)
        // delta clamped to (7 MB - 5 MB) over the full 3.5 s span.
        assertEquals(571_428.57, rate!!, 1.0)
        // Zero elapsed time cannot divide: the clamp ceiling collapses to
        // the previous bytes and the reading stays finite.
        val sameInstant = window.record(3_500, 9_000_000L)
        assertTrue(sameInstant!! >= 0.0)
    }

    @Test
    fun stickyTotalNeverShrinks() {
        val window = TaskDisplayWindow()
        assertEquals(12_000_000_000L, window.maxTotalOf(12_000_000_000L))
        // The banked-stream reset shape: a later, smaller total cannot
        // shrink the sticky maximum.
        assertEquals(12_000_000_000L, window.maxTotalOf(4_000_000_000L))
    }

    // ── Repository wiring (seeded via the persisted state file) ─────────

    @Test
    fun startingStateKillsTheResumeBurstAtTheConsumer() {
        val repo = seededRepo(downloadingTask(totalBytes = 6_000_000_000L))
        // Warm-up: before ~3 s of samples the display fields stay null —
        // "Starting…" — no first sample can leak into them early.
        now = 0L
        updateProgress(repo, downloaded = 500_000L, total = 6_000_000_000L, speed = 2.4e12, eta = 0)
        // Eleven more 250 ms ticks of a 2 MB/s ramp: t = 0.25 s .. 2.75 s.
        var base = 500_000L
        repeat(11) { i ->
            now += 250
            base += 500_000L
            updateProgress(repo, downloaded = base, total = 6_000_000_000L, speed = 2.0e6, eta = 0)
        }
        var row = repoTask(repo)
        assertNull(row.displaySpeedBytesPerSec)
        assertNull(row.displayEtaSeconds)
        assertTrue(row.speedBytesPerSec > 0.0)
        // t = 3.0 s: the window spans the full minimum — exactly 2 MB/s —
        // while the backend (resume recount!) screams 2.4 TB/s. The raw
        // field keeps the burst (watchdog contract); the display field
        // carries the window.
        now += 250
        updateProgress(repo, downloaded = 6_500_000L, total = 6_000_000_000L, speed = 2.4e12, eta = 5)
        row = repoTask(repo)
        assertEquals(2_000_000.0, row.displaySpeedBytesPerSec!!, 1.0)
        // Display ETA derives from the WINDOW (~3000 s left), not the raw
        // per-line "ETA 00:05" the backend just passed.
        assertTrue(row.displayEtaSeconds!! > 2_500L)
        // Raw fields keep their exact old semantics.
        assertEquals(2.4e12, row.speedBytesPerSec, 0.1)
        assertEquals(5L, row.etaSeconds)
    }

    @Test
    fun etaFollowsTheWindowThroughAMergeShapeNotTheJumpyLine() {
        val repo = seededRepo(downloadingTask(totalBytes = 6_000_000_000L))
        // 12 ticks (t = 0.25 .. 3.0 s) of a 2 MB/s ramp …
        repeat(12) { i ->
            now += 250
            updateProgress(repo, downloaded = 500_000L * (i + 1), total = 6_000_000_000L, speed = 2.0e6, eta = 0)
        }
        // … a jumpy per-line "ETA 00:05" lands RAW, like the trace
        // describes; the display ETA stays at the window's ~3000 s.
        now += 250
        updateProgress(repo, downloaded = 6_250_000L, total = 6_000_000_000L, speed = 2.1e6, eta = 5)
        val midRow = repoTask(repo)
        assertEquals(5L, midRow.etaSeconds)
        assertTrue(midRow.displayEtaSeconds!! > 2_500L)
        // … then the MERGER tail: bytes freeze for good (17 ticks past
        // 3.25 s put the whole moving part out of the window edge).
        repeat(17) {
            now += 250
            updateProgress(repo, downloaded = 6_250_000L, total = 6_000_000_000L, speed = 0.0, eta = 3)
        }
        // Byte-flat window decays to a zero rate: display speed collapses
        // to 0 (any card hides it) and display ETA goes null — the raw
        // sticky fallback stands in, no flip-flopping numbers.
        val row = repoTask(repo)
        assertEquals(0.0, row.displaySpeedBytesPerSec!!, 0.0)
        assertNull(row.displayEtaSeconds)
        // The denominator never shrank along the whole shape.
        assertEquals(6_000_000_000L, row.totalBytes)
    }

    @Test
    fun displayedTotalNeverRegressesMidDownload() {
        val repo = seededRepo(downloadingTask(totalBytes = 12_000_000_000L))
        now = 0L
        updateProgress(repo, downloaded = 100_000_000L, total = 12_000_000_000L, speed = 1.0e6, eta = 0)
        // The old ">10 GB rows may take a <5 GB total" allowance is gone:
        // a reset-shaped total parks against the sticky max instead.
        now += 250
        updateProgress(repo, downloaded = 101_000_000L, total = 4_000_000_000L, speed = 1.0e6, eta = 0)
        assertEquals(12_000_000_000L, repoTask(repo).totalBytes)
        // Growth still refines upward normally.
        now += 250
        updateProgress(repo, downloaded = 102_000_000L, total = 13_000_000_000L, speed = 1.0e6, eta = 0)
        assertEquals(13_000_000_000L, repoTask(repo).totalBytes)
    }

    @Test
    fun nonTransferringRowsClearTheDisplayFields() {
        val repo = seededRepo(downloadingTask(totalBytes = 1_000_000_000L))
        now = 0L
        repeat(14) { i ->
            now += 250
            updateProgress(repo, downloaded = 500_000L * (i + 1), total = 1_000_000_000L, speed = 2.0e6, eta = 0)
        }
        assertTrue(repoTask(repo).displaySpeedBytesPerSec != null)
        // User/system pause: the engine writes the row via update() — the
        // display values must not linger on the paused card (the next
        // progress tick carries the not-transferring copy that clears them;
        // the raw speed zeroes the same way it always did).
        repo.update("t1") { it.copy(status = TaskStatus.PAUSED) }
        now += 250
        updateProgress(repo, downloaded = 7_000_000L, total = 1_000_000_000L, speed = 0.0, eta = 0)
        val row = repoTask(repo)
        assertNull(row.displaySpeedBytesPerSec)
        assertNull(row.displayEtaSeconds)
        assertFalse(row.status == TaskStatus.DOWNLOADING)
        assertEquals(0.0, row.speedBytesPerSec, 0.0)
    }
}
