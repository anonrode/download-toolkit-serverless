package com.anonrode.downloader.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Board row 4, engine-side binding: the ongoing notification must print the
 * SAME windowed display speed the download card binds
 * (`DownloadTask.displaySpeedBytesPerSec`), falling back to the raw backend
 * sample only when no window reading exists. Kept pure so this runs in a
 * plain JVM test without an Android Service instance.
 */
class NotificationSpeedTextTest {

    @Test
    fun displaySpeedWinsOverRawWhenWindowHasReading() {
        // Raw 9 MB/s would print 9.0; the windowed 2 MB/s is what the card
        // shows, so the notification must agree with the card.
        val chosen = notificationSpeedBytesPerSec(2_000_000.0, 9_000_000.0)
        assertEquals(2_000_000.0, chosen, 0.0)
        assertEquals("Show • 1.9 MB/s", buildOngoingNotificationTitle("Show", chosen))
    }

    @Test
    fun rawSpeedIsTheFallbackOnlyWhenWindowIsWarming() {
        val chosen = notificationSpeedBytesPerSec(null, 9_000_000.0)
        assertEquals(9_000_000.0, chosen, 0.0)
        assertEquals("Show • 8.6 MB/s", buildOngoingNotificationTitle("Show", chosen))
    }

    @Test
    fun windowReadingOfZeroBeatsRawAndSuppressesTheSuffix() {
        // A live window reading of 0 B/s means the transfer is stalled; the
        // raw EMA may still be non-zero from a burst. 0 is still a reading,
        // not a missing one, so it wins and the card's "0.0 MB/s" form (no
        // suffix below the 0.05 MB/s floor) is mirrored.
        val chosen = notificationSpeedBytesPerSec(0.0, 4_000_000.0)
        assertEquals(0.0, chosen, 0.0)
        assertEquals("Show", buildOngoingNotificationTitle("Show", chosen))
    }

    @Test
    fun suffixFloorMatchesTheCard() {
        assertEquals("Show", buildOngoingNotificationTitle("Show", 0.05 * 1024 * 1024))
        assertEquals("Show • 0.1 MB/s", buildOngoingNotificationTitle("Show", 0.06 * 1024 * 1024))
    }

    @Test
    fun nonFiniteSamplesNeverReachTheNotification() {
        assertEquals("Show", buildOngoingNotificationTitle("Show", Double.NaN))
        assertEquals("Show", buildOngoingNotificationTitle("Show", Double.POSITIVE_INFINITY))
        assertEquals("Show", buildOngoingNotificationTitle("Show", -1_000_000.0))
    }
}
