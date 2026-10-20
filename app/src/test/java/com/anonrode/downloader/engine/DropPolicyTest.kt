package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Retuned drop-and-replace policy (2026-10-19): 8s windows, 15s warm-up,
 * 14 KiB/s floor, max 2 drops and a 30s-half-life shared best. The old
 * monotonic best + 4s/40KiB/4-drop policy dropped all 16 lanes inside ~7s
 * on a shaped link, creating the reconnect storm that provoked CDN 403s.
 */
class DropPolicyTest {

    private val policy = TurboDownloader.DropPolicy

    @Test
    fun bestReferenceDecaysWithThirtySecondHalfLife() {
        val sample = TurboDownloader.BestSample(1_000_000L, 0L)
        assertEquals(1_000_000.0, policy.decayedBestBps(sample, 0L), 1.0)
        assertEquals(500_000.0, policy.decayedBestBps(sample, 30_000L), 1.0)
        assertEquals(250_000.0, policy.decayedBestBps(sample, 60_000L), 1.0)
        assertEquals(1_000_000.0, policy.decayedBestBps(sample, -5_000L), 1.0)
    }

    @Test
    fun observeBestAcceptsOnlyGenuineImprovement() {
        val ref = AtomicReference(TurboDownloader.BestSample(0L, 0L))
        policy.observeBest(ref, 100_000.0, 0L)
        assertEquals(100_000L, ref.get().bps)
        // At t=30s the reference is worth 50k, so 80k is a real improvement.
        policy.observeBest(ref, 80_000.0, 30_000L)
        assertEquals(80_000L, ref.get().bps)
        // At t=60s the 80k sample is worth 40k; a 45k window supersedes it.
        policy.observeBest(ref, 45_000.0, 60_000L)
        assertEquals(45_000L, ref.get().bps)
        // 30k at the same instant is below the decayed 45k — no update.
        policy.observeBest(ref, 30_000.0, 60_000L)
        assertEquals(45_000L, ref.get().bps)
    }

    @Test
    fun absoluteFloorIsFourteenKibibytes() {
        assertTrue(policy.isSlowAbsolute(10_000.0, 1_000_000.0))
        assertFalse(policy.isSlowAbsolute(20_000.0, 1_000_000.0))
        // Absolute floor alone is not enough: relative lag is required too.
        assertFalse(policy.isSlowAbsolute(10_000.0, 20_000.0))
    }

    @Test
    fun severeLagUsesTheDecayedReference() {
        assertTrue(policy.isSeverelyLagging(55_000.0, 1_500_000.0))
        assertFalse(policy.isSeverelyLagging(300_000.0, 1_500_000.0))
        // A 1.5MB/s burst 90s ago is worth ~187KB/s, so 55KB/s (29%) is no
        // longer "severe" — the churn source is gone.
        val decayed = policy.decayedBestBps(TurboDownloader.BestSample(1_500_000L, 0L), 90_000L)
        assertFalse(policy.isSeverelyLagging(55_000.0, decayed))
    }

    @Test
    fun warmupAndDropBudget() {
        assertFalse(policy.warmupElapsed(15_000L))
        assertTrue(policy.warmupElapsed(15_001L))
        assertTrue(policy.budgetAllows(0, noMoreDrops = false))
        assertTrue(policy.budgetAllows(1, noMoreDrops = false))
        assertFalse(policy.budgetAllows(2, noMoreDrops = false))
        assertFalse(policy.budgetAllows(0, noMoreDrops = true))
    }
}
