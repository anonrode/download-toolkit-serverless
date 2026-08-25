package com.anonrode.downloader.ui.screens

import android.app.Application
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric JVM tests for DownloadCard, the per-task row of the Downloads
 * screen. Locks the user-visible contract for each TaskStatus: determinate vs
 * indeterminate/estimating progress, pause/resume/retry affordances, the
 * honest verification note only on COMPLETED cards, and queued/error copy.
 * Rendering only — action callbacks are no-ops, so engine wiring stays out.
 */
@RunWith(RobolectricTestRunner::class)
// AnonApp.onCreate would start the DownloadEngine, yt-dlp/ffmpeg init and
// rule sync just to render a card; the composable only needs a Context, so use
// the bare Application instead of the manifest one.
@Config(sdk = [34], application = Application::class)
class DownloadCardStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setCard(task: DownloadTask) {
        composeRule.setContent {
            DownloadCard(
                task = task,
                context = LocalContext.current,
                onPlay = {},
                onPause = {},
                onRetry = {},
                onCancel = {}
            )
        }
    }

    private fun task(
        id: String = "t",
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBytesPerSec: Double = 0.0,
        etaSeconds: Long = 0L,
        status: TaskStatus = TaskStatus.QUEUED,
        filePath: String = "",
        errorMessage: String? = null,
        validationNote: String? = null
    ): DownloadTask = DownloadTask(
        id = id,
        showTitle = "Test Show",
        episodeNum = 1,
        episodeTitle = "Episode 1",
        directUrl = "https://cdn.example.invalid/ep1.mp4",
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        speedBytesPerSec = speedBytesPerSec,
        etaSeconds = etaSeconds,
        status = status,
        filePath = filePath,
        errorMessage = errorMessage,
        validationNote = validationNote
    )

    @Test
    fun downloadingKnownTotal_showsDeterminatePercent() {
        setCard(
            task(
                downloadedBytes = 50L * MB,
                totalBytes = 100L * MB,
                speedBytesPerSec = 2.0 * MB,
                etaSeconds = 30,
                status = TaskStatus.DOWNLOADING
            )
        )
        // Half-way: the fraction must surface as a real percentage (the
        // progress bar is driven by the same ratio) alongside size/speed/ETA.
        composeRule.onNodeWithText("50% • 50.0 MB / 100.0 MB • 2.0 MB/s • 00:30 left").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun downloadingUnknownTotal_estimatesWithoutBogusPercent() {
        setCard(
            task(
                downloadedBytes = 100L * MB,
                status = TaskStatus.DOWNLOADING
            )
        )
        // CDN without Content-Length: size + "Estimating..." must replace any
        // percentage — a fraction of an unknown total is a fabricated number.
        composeRule.onNodeWithText("100.0 MB • Estimating...").assertExists()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().size
        )
        composeRule.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun paused_showsResumeAndHidesStaleSpeed() {
        setCard(
            task(
                downloadedBytes = 50L * MB,
                totalBytes = 100L * MB,
                status = TaskStatus.PAUSED
            )
        )
        composeRule.onNodeWithText("Paused at 50% • 50.0 MB / 100.0 MB").assertExists()
        composeRule.onNodeWithContentDescription("Resume").assertExists()
        // Nothing transfers while paused: no speed, no pause-from-pause
        // affordance, and the DOWNLOADING-only "Estimating..." copy.
        assertEquals(
            0,
            composeRule.onAllNodesWithText("MB/s", substring = true).fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Estimating...", substring = true).fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().size
        )
    }

    @Test
    fun completedWithNote_rendersHonestWarning() {
        setCard(
            task(
                totalBytes = 200L * MB,
                filePath = "S01E12.mkv",
                status = TaskStatus.COMPLETED,
                validationNote = VALIDATION_NOTE
            )
        )
        // The note is the engine's own string, chained through verbatim.
        composeRule.onNodeWithText(VALIDATION_NOTE).assertExists()
        composeRule.onNodeWithText("200.0 MB • Tap to Play In-App").assertExists()
        composeRule.onNodeWithText("DONE").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun completedWithoutNote_hidesWarning() {
        setCard(
            task(
                totalBytes = 200L * MB,
                filePath = "S01E12.mkv",
                status = TaskStatus.COMPLETED
            )
        )
        // Null validationNote = verified clean: the warning must not appear.
        composeRule.onNodeWithText(VALIDATION_NOTE).assertDoesNotExist()
        composeRule.onNodeWithText("200.0 MB • Tap to Play In-App").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun failed_showsErrorMessageAndRetry() {
        setCard(
            task(
                status = TaskStatus.FAILED,
                errorMessage = "aria2c exited with code 5"
            )
        )
        composeRule.onNodeWithText("aria2c exited with code 5").assertExists()
        composeRule.onNodeWithText("FAILED").assertExists()
        composeRule.onNodeWithContentDescription("Retry").assertExists()
        // Retry is its own affordance (refresh icon); "Resume" is paused-only.
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Resume").fetchSemanticsNodes().size
        )
    }

    @Test
    fun failedWithoutMessage_fallsBackToDownloadFailed() {
        setCard(task(status = TaskStatus.FAILED))
        composeRule.onNodeWithText("Download failed").assertExists()
        composeRule.onNodeWithContentDescription("Retry").assertExists()
    }

    @Test
    fun queued_showsPendingCopyWithoutTransferActions() {
        setCard(task(status = TaskStatus.QUEUED))
        composeRule.onNodeWithText("Queued • Waiting to start").assertExists()
        composeRule.onNodeWithText("QUEUED").assertExists()
        // Cancel is the only action on a queued row: not started, not pausable.
        composeRule.onNodeWithContentDescription("Cancel").assertExists()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Resume").fetchSemanticsNodes().size
        )
    }

    @Test
    fun resolving_showsLinkStepWithPause() {
        setCard(task(status = TaskStatus.RESOLVING))
        composeRule.onNodeWithText("Resolving stream link...").assertExists()
        composeRule.onNodeWithText("RESOLVING").assertExists()
        // Resolution is part of the transfer window: pause stays available.
        composeRule.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun validating_showsCheckCopyWithoutTransferActions() {
        setCard(task(status = TaskStatus.VALIDATING))
        composeRule.onNodeWithText("Checking downloaded file...").assertExists()
        composeRule.onNodeWithText("CHECKING").assertExists()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Resume").fetchSemanticsNodes().size
        )
    }

    companion object {
        private const val MB = 1024L * 1024L
        // Representative engine message; the card renders whatever was stored,
        // so the same constant proves both the shown and the suppressed branch.
        private const val VALIDATION_NOTE = "Size lower than stream estimate - may be incomplete"
    }
}
