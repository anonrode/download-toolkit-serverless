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

    /** Splash holds ~1.1s; wait until the home screen is actually visible. */
    private fun waitForMainScreen() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("ANONRODE")
                .fetchSemanticsNodes().isNotEmpty()
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
        composeRule.onNodeWithContentDescription("Downloads").performClick()
        composeRule.onNodeWithText("DOWNLOADS & MEDIA").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("ANONRODE").assertIsDisplayed()
    }
}
