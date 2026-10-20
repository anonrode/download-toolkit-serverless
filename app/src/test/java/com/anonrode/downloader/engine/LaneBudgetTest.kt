package com.anonrode.downloader.engine

import com.anonrode.downloader.pipeline.HostPolicyRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Global lane ledger + HLS per-host caps (2026-10-19). Three real tasks used
 * 48 connections and the CDN answered with throttling/403s; the ledger caps
 * the fleet at 24 and HLS at 4-6 lanes per task.
 */
class LaneBudgetTest {

    private val budget = HostPolicyRegistry.LaneBudget

    @Before
    fun setUp() = budget.clearForTest()

    @After
    fun tearDown() = budget.clearForTest()

    @Test
    fun directReservationsShareTwentyFourLanes() {
        assertEquals(8, budget.reserve("t1", 16, hls = false, plannedTasks = 3))
        // New tasks are admitted only with the minimum/fair share.
        assertEquals(8, budget.reserve("t2", 16, hls = false, plannedTasks = 3))
        assertEquals(8, budget.reserve("t3", 16, hls = false, plannedTasks = 3))
        assertEquals(24, budget.reservedLanes())
        // No capacity for a fourth minimum grant -> admission must queue.
        assertEquals(0, budget.reserve("t4", 16, hls = false, plannedTasks = 3))
        assertFalse(budget.hasMinimumCapacity())
    }

    @Test
    fun hlsIsCappedAtEightPerTaskAndNeverBelowFour() {
        assertEquals(8, budget.reserve("h1", 16, hls = true, plannedTasks = 3))
        assertEquals(8, budget.reserve("h2", 16, hls = true, plannedTasks = 3))
        assertEquals(8, budget.reserve("h3", 16, hls = true, plannedTasks = 3))
        assertEquals(24, budget.reservedLanes())
        assertEquals(0, budget.reserve("h4", 16, hls = true, plannedTasks = 3))
        // A 2-lane request is honored (floor = requested when < MIN).
        budget.release("h2")
        assertEquals(2, budget.reserve("h5", 2, hls = true))
    }

    @Test
    fun releaseFreesCapacityAndReReserveDoesNotDoubleCount() {
        assertEquals(8, budget.reserve("t1", 16, hls = false, plannedTasks = 3))
        assertEquals(8, budget.reserve("t2", 16, hls = false, plannedTasks = 3))
        assertEquals(8, budget.reserve("t2", 16, hls = false, plannedTasks = 3))
        assertEquals(16, budget.reservedLanes())
        assertEquals(8, budget.release("t2"))
        assertEquals(8, budget.reservedLanes())
        assertEquals(8, budget.reserve("t2", 16, hls = false, plannedTasks = 3))
    }

    @Test
    fun rebalanceRedistributesSurplusAndRespectsMinimums() {
        assertEquals(8, budget.reserve("t1", 16, hls = false, plannedTasks = 3))
        assertEquals(8, budget.reserve("t2", 16, hls = false, plannedTasks = 3))
        assertEquals(8, budget.reserve("t3", 16, hls = false, plannedTasks = 3))
        // t3 completes: its lanes return to the pool.
        budget.release("t3")
        // t1's extra lanes are not improving (no speed samples) -> it yields
        // one; t2 can grow toward its fair share.
        budget.reportSpeed("t1", 500_000.0)
        budget.reportSpeed("t2", 500_000.0)
        budget.rebalance()
        budget.rebalance()
        assertTrue(budget.grantFor("t1") >= 4)
        assertTrue(budget.grantFor("t2") >= 4)
        assertTrue(budget.reservedLanes() <= 24)
        assertTrue(budget.hasMinimumCapacity())
    }

    @Test
    fun hlsHostCapsBindMasterAndSegmentHosts() {
        val plain = HostPolicyRegistry.effectiveHlsLanesForUrl(
            "https://master.example/a.m3u8",
            "https://seg.example/a.ts",
            requested = 16
        )
        assertTrue("plain host must stay within 4..8, got $plain", plain in 4..8)

        val capped = HostPolicyRegistry.effectiveHlsLanesForUrl(
            "https://fetch.nexabloom.top/a.m3u8",
            "https://vidsrc.xyz/a.ts",
            requested = 16
        )
        assertTrue("known CDN cap must be <=6, got $capped", capped in 1..6)

        // A low requested value is never raised by the cap logic.
        assertEquals(
            2,
            HostPolicyRegistry.effectiveHlsLanesForUrl(
                "https://fetch.nexabloom.top/a.m3u8",
                "https://seg.example/a.ts",
                requested = 2
            )
        )
    }
}
