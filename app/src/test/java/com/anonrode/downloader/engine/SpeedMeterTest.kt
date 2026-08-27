package com.anonrode.downloader.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM test for SpeedMeter fast acquisition: after a slow first window
 * (connections still handshaking/ramping), the reported speed must reach
 * the true rate within ~1 second instead of lagging ~2.5s behind it —
 * the lag users saw as "100KB → 400KB → 3MB" while the transfer was
 * already at full speed.
 */
class SpeedMeterTest {

    @Test
    fun reportedSpeedCatchesUpWithinFirstSecond() {
        val meter = SpeedMeter(0L)
        var bytes = 0L

        // Window 1: slow start — the handshake-bound first 250ms window.
        Thread.sleep(300)
        bytes += 30_000
        meter.sample(bytes)

        // Windows 2-5: the transfer is already at full speed. Under the old
        // fixed alpha=0.25 the meter shows only ~69% of the true rate after
        // these four samples; fast acquisition must show >=85%.
        val fastStartBytes = bytes
        val fastStart = System.currentTimeMillis()
        repeat(4) {
            Thread.sleep(300)
            bytes += 900_000
            meter.sample(bytes)
        }
        val fastElapsed = System.currentTimeMillis() - fastStart
        val trueRate = (bytes - fastStartBytes) * 1000.0 / fastElapsed

        val reported = meter.getSpeed()
        assertTrue(
            "reported=$reported should be >= 85% of true=${trueRate.toLong()} after ~1s",
            reported >= 0.85 * trueRate
        )
    }
}
