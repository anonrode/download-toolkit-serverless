package com.anonrode.downloader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI emulator smoke tests (Level 2): prove the real app launches on an
 * Android device, the main screen renders, and the Downloads screen
 * navigates. Deliberately network-free — site reachability is covered by
 * the Python verification suite; here we catch launch crashes and UI
 * regressions on every push.
 */
@RunWith(AndroidJUnit4::class)
class MainSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    // SplashContent also renders "ANONRODE", so polling for that string
    // alone returns while the splash is still up. Wait for the search bar
    // placeholder instead — that text only exists in the post-splash
    // HomeScreen. This is the same fix applied to EmulatorGatingSmokeTest.
    private fun waitForMainScreen() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(
                "Search series, anime, movies, torrents..."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // The splash paints as the TOPMOST child of the same composition —
        // HomeScreen is already composed (and its placeholder already in the
        // tree) while the splash is still fading. That made the placeholder
        // poll return with TWO "ANONRODE" texts in the tree and the plain
        // assertIsDisplayed below fail ("expected at most 1 node, found 2").
        // Wait until only the home header remains; this also guarantees taps
        // below land on the real UI, not the splash overlay.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("ANONRODE").fetchSemanticsNodes().size == 1
        }
        // Storage rationale AlertDialog (API 30+) covers HomeScreen with a
        // scrim. Dismiss it before any click/assertion (checked after the
        // splash is gone so the tap lands on the dialog). The button only
        // exists on Android 11+; on older API the query is empty and the
        // call is a no-op.
        if (composeRule.onAllNodesWithText("Not now")
                .fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Not now").performClick()
        }
    }

    @Test
    fun appLaunches_andMainScreenRenders() {
        waitForMainScreen()
        composeRule.onNodeWithText("ANONRODE").assertIsDisplayed()
        composeRule.onNodeWithText("Search series, anime, movies, torrents...")
            .assertIsDisplayed()
    }

    @Test
    fun downloadsScreen_opensAndNavigatesBack() {
        waitForMainScreen()
        // The nav item's icon description is merged into the item's semantics
        // node, so the merged tree exposes no node whose own
        // ContentDescription == "Downloads" — look in the unmerged tree
        // (the CI failure message recommends exactly this).
        composeRule.onNodeWithContentDescription("Downloads", useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithText("DOWNLOADS & MEDIA").assertIsDisplayed()
        // The Downloads header's back arrow returns to the Search tab; its
        // distinct label ("Back to Search") keeps it unambiguous next to the
        // home overlays' own "Back" icons. Unmerged tree: the description is
        // merged into the icon-button node's semantics otherwise.
        composeRule.onNodeWithContentDescription("Back to Search", useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithText("ANONRODE").assertIsDisplayed()
    }
}
