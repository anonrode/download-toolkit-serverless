package com.anonrode.downloader

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.update.AnnouncementManager
import com.anonrode.downloader.update.UpdateManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * CI visual receipts (v3.3.0): captures PNGs of the main surfaces on the
 * emulator so every push ships screenshots of the real rendered UI as a
 * build artifact ("Anonrode-UI-Screenshots").
 *
 * Captured: 01 home, 02 downloads, 03 settings, then a payload INJECTED via
 * the debug-only `DynamicRulesManager.debugApplyPayloadForTests` renders the
 * NEW platform surfaces and they are captured too: 04 announcement dialog,
 * 05 soft update card, 06 house-ad slot (best-effort scroll).
 *
 * Design rules:
 *  - every capture is best-effort and can NEVER fail the gate (a screenshot
 *    is evidence, not an assertion); the only assertion is that the app is up.
 *  - captures go through the UiAutomation system screenshot (full display,
 *    every window) — the Compose `captureToImage` path PixelCopy-fails on
 *    the CI emulator's software GL, which is why the first three runs
 *    shipped an EMPTY artifact. Failures now log to logcat (never silent).
 *  - @After resets EVERYTHING this test injects (payload is in-memory only;
 *    the prefs it touches are cleared) so later test classes in the same
 *    install see a clean app and the smoke gates stay deterministic.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SurfaceScreenshotsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shotDir(): File =
        // INTERNAL app storage, deliberately: on the CI emulator
        // getExternalFilesDir(null) returns null (external storage not
        // mounted at test time), so the old external path silently
        // materialized NOTHING — three runs of empty artifacts, and the adb
        // pull failed with "No such file or directory". Internal storage is
        // always writable; the CI pull step reads it back via `run-as`
        // (debug build, debuggable=true).
        File(ctx.filesDir, "screenshots").apply { mkdirs() }

    @Before
    fun ensureShotDir() {
        // Create the directory up front so a mixed setup failure is visible
        // (mkdirs() returning false) instead of an opaque missing dir on the
        // device at pull time.
        try {
            if (!shotDir().mkdirs() && !shotDir().isDirectory) {
                android.util.Log.w("AnonrodeShots", "shot dir could not be created: ${shotDir()}")
            }
        } catch (t: Throwable) {
            android.util.Log.w("AnonrodeShots", "shot dir setup failed: $t")
        }
    }

    private fun capture(name: String) {
        try {
            composeRule.waitForIdle()
            // Full-screen capture via UiAutomation, NOT captureToImage(): on
            // the CI emulator (software GL) PixelCopy behind captureToImage
            // fails per root with "Failed to find ColorBuffer", so this test
            // shipped ZERO PNGs for three consecutive runs. The system
            // screenshot path is reliable on emulators and captures EVERY
            // window — exactly what the injected announcement / update card /
            // house-ad receipts need (they render in separate dialog windows;
            // the old per-root loop needed `_rootN` files for that).
            val shot = InstrumentationRegistry.getInstrumentation()
                .uiAutomation.takeScreenshot()
                ?: throw IllegalStateException("uiAutomation.takeScreenshot returned null")
            FileOutputStream(File(shotDir(), "$name.png")).use {
                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (t: Throwable) {
            // Capture is evidence, never a gate — but a SILENTLY empty
            // artifact cost three CI runs of confusion, so failures now leave
            // a logcat trail (the CI workflow dumps logcat every run).
            android.util.Log.w("AnonrodeShots", "capture '$name' failed: $t")
        }
    }

    /** Same idiom as MainSmokeTest: wait for the post-splash Home search bar,
     *  then the splash overlay itself, then dismiss the storage-rationale
     *  dialog if this API shows it. Waiting the splash fully out also keeps
     *  it out of capture 01 — captures happen right after this call. */
    private fun waitForMainScreen() {
        // The app cold-starts on the LAST-USED tab (pref_last_tab): poll until
        // the Search tab is up (tapping it as needed) so capture 01 is always
        // Home. Hidden tabs no longer expose semantics.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val homeUp = composeRule.onAllNodesWithText("Search series, anime, movies, torrents...")
                .fetchSemanticsNodes().isNotEmpty()
            if (!homeUp) {
                val dismiss = composeRule.onAllNodesWithText("Not now")
                if (dismiss.fetchSemanticsNodes().isNotEmpty()) {
                    try { dismiss[0].performClick() } catch (_: Throwable) {}
                } else {
                    val navSearch = composeRule.onAllNodesWithContentDescription("Search", useUnmergedTree = true)
                    if (navSearch.fetchSemanticsNodes().isNotEmpty()) {
                        try { navSearch[0].performClick() } catch (_: Throwable) {}
                    }
                }
            }
            homeUp
        }
        // Splash paints above the already-composed HomeScreen; one
        // "ANONRODE" node left = the home header, splash gone.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("ANONRODE").fetchSemanticsNodes().size == 1
        }
        if (composeRule.onAllNodesWithText("Not now")
                .fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Not now").performClick()
        }
    }

    /** Payload for the new platform surfaces (dummy copy; never published). */
    private val injectedPayload = """
        {
          "version": "ci-screenshots",
          "announcements": [
            {
              "id": "ci-shot-1",
              "title": "CI screenshot pass",
              "body": "This dialog was injected by the instrumented screenshot test.",
              "mode": "dialog",
              "action": "none",
              "actionLabel": "",
              "actionUrl": "",
              "oncePer": "id",
              "minApp": 0,
              "expiresAtMs": 0
            }
          ],
          "update": {
            "latestVersionName": "99.0.0",
            "latestVersionCode": 999999,
            "sources": [ { "kind": "github", "base": "https://example.com/" } ],
            "message": "CI screenshot: update card",
            "force": { "enabled": false, "minSupportedVersionCode": 0, "mode": "soft", "snoozeHours": 24, "escapeHatch": true }
          },
          "ads": {
            "enabled": true,
            "provider": "house",
            "placements": ["downloads_shelf", "home_footer"],
            "frequencyPerDay": 2,
            "house": {
              "id": "ci-house",
              "title": "House message (CI)",
              "body": "Injected house-ad slot.",
              "actionLabel": "",
              "actionUrl": ""
            }
          },
          "engagement": {
            "enabled": true,
            "inactiveDays": 7,
            "cooldownDays": 14,
            "title": "CI engagement",
            "body": "CI engagement body",
            "welcomeBack": true
          }
        }
    """.trimIndent()

    @Test
    fun captureMainSurfaces() {
        waitForMainScreen()
        composeRule.waitForIdle()
        // Let first-frame work (poster prefetch, skeletons) settle a beat —
        // this is a screenshot pass, not a latency test.
        Thread.sleep(2_000)
        composeRule.waitForIdle()
        capture("01_home_top")

        composeRule.onNodeWithContentDescription("Downloads", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        capture("02_downloads")

        composeRule.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        capture("03_settings")

        composeRule.onNodeWithContentDescription("Search", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        // ── Inject the platform surfaces and capture what they render ──
        DynamicRulesManager.debugApplyPayloadForTests(injectedPayload)
        AnnouncementManager.refresh(ctx)
        composeRule.waitForIdle()
        Thread.sleep(500)
        capture("04_announcement_dialog")
        if (composeRule.onAllNodesWithText("Dismiss").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Dismiss").performClick()
        }
        composeRule.waitForIdle()

        // Soft update card: fresh sync evidence via the literal pref the debug
        // build's UpdateManager reads, then re-evaluate.
        ctx.getSharedPreferences("anon_serverless_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putLong("last_rules_sync", System.currentTimeMillis()).apply()
        UpdateManager.evaluate(ctx)
        composeRule.waitForIdle()
        Thread.sleep(500)
        capture("05_update_card")
        if (composeRule.onAllNodesWithText("Later").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Later").performClick()
        }
        composeRule.waitForIdle()

        // House-ad slot on the Home footer (best-effort: the landing column
        // only re-reads the config on recomposition — nudge it with a tab hop).
        composeRule.onNodeWithContentDescription("Downloads", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText("Injected house-ad slot.")
                .fetchSemanticsNodes().isNotEmpty()) {
            try {
                composeRule.onNodeWithText("Injected house-ad slot.").performScrollTo()
            } catch (_: Throwable) {
            }
            composeRule.waitForIdle()
            capture("06_house_ad_slot")
        }
    }

    @After
    fun cleanUp() {
        try {
            // In-memory surfaces -> empty; persisted markers -> wiped. The
            // injected payload is never persisted anywhere.
            DynamicRulesManager.debugApplyPayloadForTests("""{"version":"ci-reset"}""")
            AnnouncementManager.current.value = null
            UpdateManager.state.value = UpdateManager.State.UNKNOWN
            UpdateManager.message.value = ""
            ctx.getSharedPreferences("downloader_settings", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
            ctx.getSharedPreferences("anon_serverless_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (_: Throwable) {
        }
    }
}
