package com.anonrode.downloader.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.anonrode.downloader.data.models.ShowCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression repro for:
 *   IllegalStateException: currentLine > lineIndex
 *     at LazyGridSpanLayoutProvider.getLineConfiguration
 *     at LazyGridState.scheduleLinePrefetch <- onScroll
 *
 * The old inline grids appended streamed items and inserted a conditional
 * full-span footer while the user was scrolling. The stable grid freezes the
 * rendered list during scroll, keeps the spinner outside the span topology,
 * and resets/clamps on shrink. These tests drive both sequences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StablePosterGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun card(i: Int) = ShowCard(
        title = "Card $i",
        url = "https://example.com/show/$i",
        site = "test"
    )

    @Test
    fun appendAndLoadMoreDuringFlingDoesNotCrash() {
        val items = mutableStateOf((0 until 60).map(::card))
        val loadingMore = mutableStateOf(false)
        composeRule.setContent {
            StablePosterGrid(
                items = items.value,
                modifier = Modifier.fillMaxSize(),
                isLoadingMore = loadingMore.value
            ) { show ->
                Text(show.title)
            }
        }

        // Freeze the clock so the drag/scroll is still in progress when the
        // list and the footer visibility mutate (the exact crash window).
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("stable_poster_grid").performTouchInput {
            down(center)
            moveBy(Offset(0f, -800f))
        }
        loadingMore.value = true
        items.value = items.value + (60 until 90).map(::card)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag("stable_poster_grid").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        // No exception = no span crash. The appended tail is reachable.
        composeRule.onNodeWithTag("stable_poster_grid").assertExists()
        composeRule.onNodeWithTag("stable_poster_grid").performScrollToIndex(89)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Card 89").assertExists()
    }

    @Test
    fun filterShrinkWhileScrolledDoesNotCrash() {
        val items = mutableStateOf((0 until 60).map(::card))
        composeRule.setContent {
            StablePosterGrid(
                items = items.value,
                modifier = Modifier.fillMaxSize()
            ) { show ->
                Text(show.title)
            }
        }

        composeRule.onNodeWithTag("stable_poster_grid").performScrollToIndex(40)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("stable_poster_grid").performTouchInput {
            down(center)
            moveBy(Offset(0f, -600f))
        }
        // A status/genre chip swaps in a much smaller subset mid-scroll.
        items.value = items.value.take(6)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag("stable_poster_grid").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("stable_poster_grid").assertExists()
        composeRule.onNodeWithTag("stable_poster_grid").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Card 0").assertExists()
    }
}
