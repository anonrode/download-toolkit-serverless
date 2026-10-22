package com.anonrode.downloader.pipeline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Board rows 5/29: adaptive per-host socket occupancy.
 *
 * Policy under test: start at the host cap, halve immediately on
 * 429/403/503, recover +1 lane only after two consecutive improving goodput
 * samples (the 188aa09 two-sample rule applied to growth), clamp 1..cap,
 * and remember the outcome per host for the rest of the session.
 */
class HostSocketOccupancyTest {

    private val occupancy = HostPolicyRegistry.HostSocketOccupancy
    private val hostA = "https://cdn-a.example.com/file.mp4"
    private val hostB = "https://cdn-b.example.com/file.mp4"

    @Before
    fun setUp() = occupancy.clearForTest()

    @After
    fun tearDown() = occupancy.clearForTest()

    @Test
    fun firstUseStartsAtHostCap() {
        assertEquals(16, occupancy.currentForUrl(hostA, 16))
        assertEquals(6, occupancy.currentForUrl(hostB, 6))
        // Reading the session value never mutates it.
        assertEquals(16, occupancy.currentForUrl(hostA, 16))
    }

    @Test
    fun backoffHalvesImmediatelyAndClampsAtOne() {
        assertEquals(8, occupancy.recordBackoff(hostA, 16))
        assertEquals(4, occupancy.recordBackoff(hostA, 16))
        assertEquals(2, occupancy.recordBackoff(hostA, 16))
        assertEquals(1, occupancy.recordBackoff(hostA, 16))
        assertEquals(1, occupancy.recordBackoff(hostA, 16))
        // Session memory: a NEW task on the host starts at the halved width.
        assertEquals(1, occupancy.currentForUrl(hostA, 16))
    }

    @Test
    fun recoveryNeedsTwoConsecutiveImprovingSamples() {
        assertEquals(8, occupancy.recordBackoff(hostA, 16))
        // Sample 1 seeds the reference; no growth.
        assertEquals(8, occupancy.recordGoodput(hostA, 16, 1_000_000.0))
        // Sample 2 improves (>5%) — first confirmation only.
        assertEquals(8, occupancy.recordGoodput(hostA, 16, 1_100_000.0))
        // Sample 3 improves again — second confirmation, +1 lane.
        assertEquals(9, occupancy.recordGoodput(hostA, 16, 1_210_000.0))
    }

    @Test
    fun aFlatSampleResetsTheHysteresisRun() {
        occupancy.recordBackoff(hostA, 16) // 8
        occupancy.recordGoodput(hostA, 16, 1_000_000.0) // seed
        occupancy.recordGoodput(hostA, 16, 1_100_000.0) // confirmation 1
        // Flat/no-improvement sample resets the run...
        assertEquals(8, occupancy.recordGoodput(hostA, 16, 1_100_000.0))
        // ...so ONE more improvement is not enough.
        assertEquals(8, occupancy.recordGoodput(hostA, 16, 1_200_000.0))
        assertEquals(9, occupancy.recordGoodput(hostA, 16, 1_320_000.0))
    }

    @Test
    fun recoveryClampsAtTheHostCap() {
        // Backoff to 8, then climb one lane per two improving samples.
        var sockets = occupancy.recordBackoff(hostA, 16)
        var bps = 1_000_000.0
        repeat(40) {
            bps *= 1.10
            sockets = occupancy.recordGoodput(hostA, 16, bps)
        }
        assertEquals(16, sockets)
        // At the cap there is no headroom: never exceed it.
        assertEquals(16, occupancy.recordGoodput(hostA, 16, bps * 2))
    }

    @Test
    fun sessionMemoryIsPerHost() {
        occupancy.recordBackoff(hostA, 16)
        assertEquals(4, occupancy.recordBackoff(hostA, 16))
        // A different CDN keeps its own (unpenalised) occupancy.
        assertEquals(12, occupancy.currentForUrl(hostB, 12))
        assertEquals(4, occupancy.currentForUrl(hostA, 16))
    }

    @Test
    fun nonFiniteAndZeroGoodputNeverGrow() {
        occupancy.recordBackoff(hostA, 16) // 8
        assertEquals(8, occupancy.recordGoodput(hostA, 16, Double.NaN))
        assertEquals(8, occupancy.recordGoodput(hostA, 16, Double.POSITIVE_INFINITY))
        assertEquals(8, occupancy.recordGoodput(hostA, 16, 0.0))
        assertEquals(8, occupancy.recordGoodput(hostA, 16, -5.0))
        assertTrue(occupancy.currentForUrl(hostA, 16) >= 1)
    }

    @Test
    fun postThrottleWorkerCapOnlyRestoresOnRealOccupancyGrowth() {
        // Throttle halved the live cap to 3; a flat sample must NOT undo it.
        assertEquals(
            3,
            occupancy.recoveryMaxWorkers(currentMax = 3, target = 7, occupancyGrew = false)
        )
        // A real occupancy growth sample restores the in-run width.
        assertEquals(
            7,
            occupancy.recoveryMaxWorkers(currentMax = 3, target = 7, occupancyGrew = true)
        )
        // Recovery never lowers the cap below where it already is.
        assertEquals(
            9,
            occupancy.recoveryMaxWorkers(currentMax = 9, target = 7, occupancyGrew = true)
        )
    }
}
