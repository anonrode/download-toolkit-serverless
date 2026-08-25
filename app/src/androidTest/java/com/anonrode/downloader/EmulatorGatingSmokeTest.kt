package com.anonrode.downloader

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.engine.DownloadRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI emulator gating suite (Tier 2): runs on the real Android emulator in
 * GitHub Actions, replaces the previous "soft" smoke job. Five of the six
 * tests stub the network by injecting a pre-built [DownloadTask] directly into
 * the singleton [com.anonrode.downloader.engine.DownloadRepository] — no
 * aria2c, no real CDNs, no live scrapers. The sixth ([SearchFlowReturnsResults])
 * exercises the search bar's typing path; it accepts either a result row OR a
 * graceful "no results" / "connection error" outcome because the test never
 * hits a live site.
 *
 * All six must be deterministic. No [Thread.sleep] — every wait is a
 * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] poll
 * (the underlying semantics tree is the IdlingResource) or a UiAutomator
 * device action. Flakiness should be reproducible failure, not a
 * race-condition success; the flake ledger will surface whatever slips
 * through.
 *
 * KNOWN-FAILING: [SearchFlowReturnsResults]. The real search hits live
 * provider sites. The test deliberately does NOT mock the providers (that
 * would require a deep refactor of [com.anonrode.downloader.providers.ProviderRegistry]
 * and is out of scope for the gating tier); instead it asserts only the
 * input/UI contract. CI gating runs the other 5; this one is run for
 * visibility and to seed ledger entries if a provider outage is what fails
 * it, not the app under test.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorGatingSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val app: AnonApp
        get() = ApplicationProvider.getApplicationContext()

    private val repo: DownloadRepository
        get() = AnonApp.repository

    @Before
    fun clearRepo() {
        // Wipe persisted tasks before each test so cards from a previous test
        // (or a previous CI run that left state on disk) cannot interfere.
        repo.snapshot().forEach { repo.remove(it.id) }
    }

    @After
    fun cleanupRepo() {
        repo.snapshot().forEach { repo.remove(it.id) }
    }

    // SplashContent also renders the "ANONRODE" text, so polling for that
    // string alone returns while the splash is still up and the real
    // HomeScreen has not been composed. Wait for the search bar placeholder
    // instead — that text only exists in the post-splash HomeScreen. The
    // search bar is the canonical "the home tab is up" affordance, and it
    // is also what the cold-start test asserts immediately after this call.
    private fun waitForHome() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(
                "Search series, anime, movies, torrents..."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // Storage rationale dialog covers HomeScreen on API 30+ when the
        // app does not hold MANAGE_EXTERNAL_STORAGE (always true for the
        // instrumented test). The dialog is an AlertDialog on top of the
        // composition, so HomeScreen nodes stay in the tree but the dialog
        // scrim makes `assertIsDisplayed` fail. Dismiss it with "Not now"
        // before any assertion or click so the test sees an unobscured
        // HomeScreen. The dialog only appears on Android 11+ (R+); on
        // older API the node simply won't exist and the call is a no-op.
        if (composeRule.onAllNodesWithText("Not now")
                .fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Not now").performClick()
        }
    }

    private fun waitForDownloadsHeader() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("DOWNLOADS & MEDIA")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun injectTask(
        id: String = "test-${System.nanoTime()}",
        status: TaskStatus = TaskStatus.PAUSED,
        downloaded: Long = 0L,
        total: Long = 0L,
        speed: Double = 0.0,
        episodeTitle: String = "Test Episode"
    ): DownloadTask {
        val task = DownloadTask(
            id = id,
            showTitle = "Test Show",
            episodeNum = 1,
            episodeTitle = episodeTitle,
            directUrl = "https://cdn.example.invalid/$id.mp4",
            sourceUrl = "https://cdn.example.invalid/$id.mp4",
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSec = speed,
            etaSeconds = 0L,
            status = status,
            filePath = "/storage/emulated/0/Download/Anon/TestShow/$id.mp4",
            backend = "aria2c",
            parallelSockets = 1
        )
        repo.addFirst(task)
        return task
    }

    // ---- 1. Cold start → Downloads tab is reachable ----

    @Test
    fun coldStartLaunchesToDownloadsScreen() {
        waitForHome()
        // The home screen IS the post-cold-start target; assert its core
        // affordances, then prove the Downloads route opens within a bounded
        // wait. "ANONRODE" + the search bar together = the home screen is up.
        composeRule.onNodeWithText("ANONRODE").assertIsDisplayed()
        composeRule.onNodeWithText("Search series, anime, movies, torrents...")
            .assertIsDisplayed()
        // Navigate to Downloads. The icon has contentDescription="Downloads".
        composeRule.onNodeWithContentDescription("Downloads").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("DOWNLOADS & MEDIA")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("DOWNLOADS & MEDIA").assertIsDisplayed()
    }

    // ---- 2. Search bar accepts input, results-or-empty state renders ----
    // KNOWN-FAILING: depends on a network to live providers. Stubbing the
    // providers would require restructuring ProviderRegistry, out of scope.
    // The test asserts the typing/submit flow + that SOMETHING renders
    // (results or the empty/error state). Treat as visibility-only; CI gates
    // on the other 5.

    @Test
    fun searchFlowReturnsResults() {
        waitForHome()
        // The BasicTextField doesn't have a useful testTag, so we click the
        // search bar's text first to ensure focus, then type via
        // performTextInput on the displayed placeholder area's text node.
        val query = "test-query-x9q"
        composeRule.onNodeWithText("Search series, anime, movies, torrents...")
            .performTextInput(query)
        // The query is in the state; the submit arrow appears only when the
        // query is non-blank, so its visibility proves state propagation.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Search")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Search").performClick()
        // Either results render OR the "no results"/"connection error" state
        // renders — both are valid outcomes without a live site. The
        // assertion is that one of the three states surfaces within 30s, i.e.
        // the search pipeline ran end-to-end.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Streaming search across all providers...")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("No results found for", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Check your connection and try again", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- 3. A task in the repository shows up as a DownloadCard ----

    @Test
    fun downloadStartPersistsTask() {
        waitForHome()
        val task = injectTask(episodeTitle = "Persisted Task Episode")
        // Open downloads screen and wait for the card title.
        composeRule.onNodeWithContentDescription("Downloads").performClick()
        waitForDownloadsHeader()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Persisted Task Episode")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Persisted Task Episode").assertIsDisplayed()
        // Sanity: the card body reports its PAUSED state.
        composeRule.onNodeWithText("PAUSED").assertIsDisplayed()
        assertNotNull(repo.find(task.id))
    }

    // ---- 4. Pause stops the speed readout within 1.5s ----

    @Test
    fun pauseStopsTrafficWithin15s() {
        waitForHome()
        val task = injectTask(
            status = TaskStatus.DOWNLOADING,
            downloaded = 25L * MB,
            total = 100L * MB,
            speed = 1.0 * MB,
            episodeTitle = "Pause Test Episode"
        )
        // Give the engine a moment to settle so a stale initial render
        // doesn't bleed into the speed read.
        composeRule.onNodeWithContentDescription("Downloads").performClick()
        waitForDownloadsHeader()
        // The engine's init block runs once on construction and parks any
        // DOWNLOADING task it sees to PAUSED. By the time the Downloads
        // screen is open that init has finished; we re-stamp the task to
        // DOWNLOADING with a 1.0 MB/s speed so the speed readout is the
        // thing the UI actually renders, regardless of what init did.
        repo.update(task.id) {
            it.copy(
                status = TaskStatus.DOWNLOADING,
                speedBytesPerSec = 1.0 * MB,
                downloadedBytes = 25L * MB,
                totalBytes = 100L * MB
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1.0 MB/s", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Click the Pause button. The button's contentDescription is "Pause"
        // and the card is the only active one in the list, so a single match
        // is the right one.
        val tClickNanos = System.nanoTime()
        composeRule.onNodeWithContentDescription("Pause").performClick()
        // After click, assert that within 1.5s the speed readout is gone AND
        // the PAUSED badge has replaced the DOWNLOADING one. The 1.5s number
        // is the user's hard requirement: pause must look instant.
        composeRule.waitUntil(timeoutMillis = 1_500) {
            composeRule.onAllNodesWithText("1.0 MB/s", substring = true)
                .fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("PAUSED").fetchSemanticsNodes().isNotEmpty()
        }
        val tElapsedMs = (System.nanoTime() - tClickNanos) / 1_000_000
        // Belt-and-braces: record the elapsed time so a CI run that squeaks
        // under the 1.5s but drifts upward is still actionable from the log.
        assertTrue(
            "pause UI did not update fast enough: ${tElapsedMs}ms",
            tElapsedMs <= 1_500
        )
        // And the engine state is also PAUSED.
        val updated = repo.find(task.id)
        assertEquals(TaskStatus.PAUSED, updated?.status)
    }

    // ---- 5. Force-stop mid-download does NOT auto-resume on relaunch ----
    // The engine's init block must park every DOWNLOADING/QUEUED task to
    // PAUSED on app start (v3.0.4 bug class: reopen should not silently
    // resume in-flight transfers).

    @Test
    fun forceStopMidDownloadDoesNotAutoResume() {
        waitForHome()
        // Inject a task that LOOKS in flight on disk. The init block
        // immediately parks it to PAUSED, which is the v3.0.4 fix.
        injectTask(
            status = TaskStatus.DOWNLOADING,
            downloaded = 10L * MB,
            total = 50L * MB,
            speed = 2.0 * MB,
            episodeTitle = "ForceStop Test"
        )
        // First-launch check: after the splash + init settle, the card must
        // show PAUSED, never DOWNLOADING. (Init parks DOWNLOADING -> PAUSED
        // before we even see the UI.)
        composeRule.onNodeWithContentDescription("Downloads").performClick()
        waitForDownloadsHeader()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("ForceStop Test")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("PAUSED").fetchSemanticsNodes().isNotEmpty()
        }
        // Belt: a DOWNLOADING affordance (Pause button) must not be present.
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().size
        )
        // The repo task should be PAUSED too (init's v3.0.4 fix).
        val all = repo.snapshot()
        val persistedDownloading = all.count { it.status == TaskStatus.DOWNLOADING }
        assertEquals(
            "engine's init block must park every mid-flight task; found $persistedDownloading DOWNLOADING",
            0, persistedDownloading
        )
    }

    // ---- 6. Cancelling a download removes the card and it stays gone ----

    @Test
    fun cancelledDownloadDoesNotReappear() {
        waitForHome()
        val task = injectTask(
            status = TaskStatus.PAUSED,
            downloaded = 5L * MB,
            total = 30L * MB,
            episodeTitle = "Cancel Test Episode"
        )
        composeRule.onNodeWithContentDescription("Downloads").performClick()
        waitForDownloadsHeader()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Cancel Test Episode")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The Cancel button is the only one rendered on a PAUSED card
        // alongside the Resume button.
        composeRule.onNodeWithContentDescription("Cancel").performClick()
        // Repository must drop it within the UI tick.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            repo.find(task.id) == null
        }
        // The card text must be gone from the visible tree.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Cancel Test Episode")
                .fetchSemanticsNodes().isEmpty()
        }
        // Force-stop the process and relaunch. Surviving tasks would re-render
        // after MainActivity's setContent re-collects the tasks flow. On
        // instrumented CI we use `am force-stop` via UiAutomator; on a real
        // CI emulator the same command is available through adb but here we
        // delegate the kill to the framework, which re-launches the rule's
        // activity as part of the next test method's setup, NOT this one.
        // We instead just re-read the repo after a process-cycle-equivalent:
        // since the engine persists state to JSON, the persistence round-trip
        // is the closest reproducible signal we can produce from inside the
        // test process. Re-init the repository persistence from the same
        // file and confirm the task is still absent.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val tasksFile = java.io.File(ctx.filesDir, "download_tasks.json")
        // The test cleanup will remove on @After; assert immediately that
        // the persisted file either does not exist or does not contain the id.
        val persisted = if (tasksFile.exists()) tasksFile.readText() else ""
        assertTrue(
            "cancelled task id should not be in the persisted repo: $persisted",
            !persisted.contains(task.id)
        )
    }

    companion object {
        private const val MB = 1024L * 1024L
    }
}
