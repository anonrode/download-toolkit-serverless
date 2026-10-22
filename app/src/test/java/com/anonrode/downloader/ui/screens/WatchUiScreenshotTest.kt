package com.anonrode.downloader.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.anonrode.downloader.data.ContinueState
import com.anonrode.downloader.data.SeriesContinueCard
import com.anonrode.downloader.data.WatchHistoryCore
import com.anonrode.downloader.data.WatchRecord
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.ui.theme.AnonDownloaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * CI-renderable visual receipts for the watch-history UI (directive Phase 2).
 *
 * Renders each state of the new components with Robolectric's native graphics
 * and writes PNGs to `build/reports/screenshots/` so a CI run can publish
 * them as an artifact. Assertions on the visible text are the gate; a capture
 * failure is logged, never silently skipped, and never fails the build
 * (screenshots are evidence, not assertions).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class WatchUiScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var scenario by mutableStateOf(0)

    private val show = "Crystal Lake"
    private val path = "/files/crystal_lake_s01e04.mp4"

    private fun task(
        status: TaskStatus = TaskStatus.COMPLETED,
        filePath: String = path,
        season: Int = 1,
        episode: Int = 4
    ): DownloadTask = DownloadTask(
        id = "task-1",
        showTitle = show,
        episodeNum = episode,
        episodeTitle = "Episode $episode",
        directUrl = "https://cdn.example.invalid/ep4.mp4",
        status = status,
        filePath = filePath,
        totalBytes = 148L * 1024L * 1024L,
        season = season,
        episode = episode,
        site = "naijaprey",
        posterUrl = ""
    )

    private fun record(
        isWatched: Boolean = false,
        positionMs: Long = 1_380_000L,
        durationMs: Long = 2_380_000L
    ): WatchRecord = WatchRecord(
        id = path,
        showKey = "Crystal Lake",
        showTitle = show,
        kind = WatchHistoryCore.KIND_SERIES,
        filePath = path,
        sourceUrl = "https://source/ep4",
        seriesUrl = "https://site/crystal-lake",
        site = "naijaprey",
        episodeTitle = "Episode 4",
        season = 1,
        episode = 4,
        positionMs = positionMs,
        durationMs = durationMs,
        isWatched = isWatched,
        updatedAtMs = 1_000L
    )

    private fun card(state: ContinueState): SeriesContinueCard = SeriesContinueCard(
        state = state,
        showKey = "Crystal Lake",
        showTitle = show,
        record = record(isWatched = state != ContinueState.RESUME),
        task = task(),
        seriesUrl = "https://site/crystal-lake",
        site = "naijaprey",
        season = 1,
        episode = 4,
        positionMs = 1_380_000L,
        durationMs = 2_380_000L
    )

    private fun content() {
        composeRule.setContent {
            AnonDownloaderTheme(themeMode = "dark") {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(16.dp)
                ) {
                    when (scenario) {
                        0 -> ActivityRow(
                            task = task(), onPlay = {}, onPause = {}, onRetry = {},
                            onCancel = {}, onOpenActions = {}, watchRecord = null
                        )
                        1 -> ActivityRow(
                            task = task(), onPlay = {}, onPause = {}, onRetry = {},
                            onCancel = {}, onOpenActions = {}, watchRecord = record()
                        )
                        2 -> ActivityRow(
                            task = task(), onPlay = {}, onPause = {}, onRetry = {},
                            onCancel = {}, onOpenActions = {},
                            watchRecord = record(isWatched = true)
                        )
                        3 -> SeriesContinueTile(
                            card = card(ContinueState.RESUME), posterUrl = "",
                            onPlay = {}, onFetch = {}
                        )
                        4 -> SeriesContinueTile(
                            card = card(ContinueState.UP_NEXT), posterUrl = "",
                            onPlay = {}, onFetch = {}
                        )
                        5 -> SeriesContinueTile(
                            card = card(ContinueState.FETCH_NEXT), posterUrl = "",
                            onPlay = {}, onFetch = {}
                        )
                        6 -> SocialShelfTile(
                            task = DownloadTask(
                                id = "social-1",
                                showTitle = "Social/Instagram",
                                episodeNum = 0,
                                episodeTitle = "IG reel",
                                directUrl = "https://cdn.example.invalid/reel.mp4",
                                status = TaskStatus.COMPLETED,
                                filePath = "/files/reel.mp4",
                                totalBytes = 4L * 1024L * 1024L,
                                site = "social"
                            ),
                            watchRecord = WatchRecord(
                                id = "/files/reel.mp4",
                                showKey = "Social/Instagram",
                                showTitle = "Social/Instagram",
                                kind = WatchHistoryCore.KIND_SOCIAL,
                                filePath = "/files/reel.mp4",
                                positionMs = 18_000L,
                                durationMs = 62_000L,
                                updatedAtMs = 9_000L
                            ),
                            onPlay = {}, onOpenActions = {}
                        )
                        7 -> EpisodeRow(
                            episode = com.anonrode.downloader.data.models.EpisodeItem(
                                title = "Episode 4",
                                url = "https://source/ep4",
                                episodeNum = 4,
                                site = "naijaprey",
                                season = 1,
                                episode = 4
                            ),
                            isSelected = false,
                            onToggle = {},
                            isDownloaded = true,
                            isWatched = true,
                            onDownloadSingle = {}
                        )
                        else -> EpisodeRow(
                            episode = com.anonrode.downloader.data.models.EpisodeItem(
                                title = "Episode 5",
                                url = "https://source/ep5",
                                episodeNum = 5,
                                site = "naijaprey",
                                season = 1,
                                episode = 5
                            ),
                            isSelected = false,
                            onToggle = {},
                            watchTimeLeft = "23m left",
                            onDownloadSingle = {}
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        try {
            val dir = File(System.getProperty("user.dir"), "build/reports/screenshots")
                .apply { mkdirs() }
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            println("SHOT ${File(dir, "$name.png").absolutePath}")
        } catch (t: Throwable) {
            println("SHOT-FAILED $name: ${t.message}")
        }
    }

    @Test
    fun render_watch_states_and_shelf_tiles() {
        content()
        composeRule.waitForIdle()

        scenario = 0
        composeRule.waitForIdle()
        capture("10_row_unwatched_done")

        scenario = 1
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Resume").assertExists()
        capture("11_row_resume_23m")

        scenario = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Watched ✓").assertExists()
        capture("12_row_watched_muted")

        scenario = 3
        composeRule.waitForIdle()
        composeRule.onNodeWithText("RESUME").assertExists()
        capture("13_shelf_resume")

        scenario = 4
        composeRule.waitForIdle()
        composeRule.onNodeWithText("UP NEXT").assertExists()
        capture("14_shelf_up_next")

        scenario = 5
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NEXT EP ⬇").assertExists()
        capture("15_shelf_fetch_next")

        scenario = 6
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CONTINUE").assertExists()
        capture("16_social_continue")

        scenario = 7
        composeRule.waitForIdle()
        composeRule.onNodeWithText("✓ Watched").assertExists()
        capture("17_drawer_row_watched")

        scenario = 8
        composeRule.waitForIdle()
        composeRule.onNodeWithText("23m left").assertExists()
        capture("18_drawer_row_in_progress")
    }
}
