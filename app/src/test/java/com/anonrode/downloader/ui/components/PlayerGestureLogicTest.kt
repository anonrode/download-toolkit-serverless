package com.anonrode.downloader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure math behind the player's gesture layer (directive §5.3). CI runs this
 * without a device: tap zones, seek clamps, brightness floor and HUD text.
 */
class PlayerGestureLogicTest {

    @Test
    fun tapZonesSplitAtThirtyFivePercent() {
        assertEquals(PlayerTapZone.REWIND, playerTapZone(0f, 1000f))
        assertEquals(PlayerTapZone.REWIND, playerTapZone(349f, 1000f))
        assertEquals(PlayerTapZone.PLAY_PAUSE, playerTapZone(350f, 1000f))
        assertEquals(PlayerTapZone.PLAY_PAUSE, playerTapZone(500f, 1000f))
        assertEquals(PlayerTapZone.PLAY_PAUSE, playerTapZone(650f, 1000f))
        assertEquals(PlayerTapZone.FORWARD, playerTapZone(651f, 1000f))
        assertEquals(PlayerTapZone.FORWARD, playerTapZone(1000f, 1000f))
    }

    @Test
    fun tapZoneBeforeLayoutIsPlayPause() {
        assertEquals(PlayerTapZone.PLAY_PAUSE, playerTapZone(0f, 0f))
        assertEquals(PlayerTapZone.PLAY_PAUSE, playerTapZone(10f, -5f))
    }

    @Test
    fun seekClampsToMediaBounds() {
        assertEquals(0L, clampedSeekTarget(5_000L, 60_000L, -10_000L))
        assertEquals(60_000L, clampedSeekTarget(55_000L, 60_000L, 10_000L))
        assertEquals(25_000L, clampedSeekTarget(15_000L, 60_000L, 10_000L))
    }

    @Test
    fun seekWithoutDurationOnlyClampsAtZero() {
        assertEquals(0L, clampedSeekTarget(2_000L, 0L, -10_000L))
        assertEquals(12_000L, clampedSeekTarget(2_000L, 0L, 10_000L))
    }

    @Test
    fun upwardDragBrightensAndNeverGoesBlack() {
        // Full-screen upward drag adds 100 points.
        assertEquals(100f, brightnessPercentAfterDrag(40f, -1000f, 1000f), 0.001f)
        // Full-screen downward drag subtracts 100, floored at 2.
        assertEquals(2f, brightnessPercentAfterDrag(40f, 1000f, 1000f), 0.001f)
        // Half a screen up from 30 = 80.
        assertEquals(80f, brightnessPercentAfterDrag(30f, -500f, 1000f), 0.001f)
    }

    @Test
    fun brightnessDragWithoutHeightIsStable() {
        assertEquals(55f, brightnessPercentAfterDrag(55f, -100f, 0f), 0.001f)
        assertEquals(2f, brightnessPercentAfterDrag(0f, 0f, 0f), 0.001f)
    }

    @Test
    fun seekHudLabelsCarrySign() {
        assertEquals("-10s", formatSeekHud(-10_000L))
        assertEquals("+10s", formatSeekHud(10_000L))
    }
}
