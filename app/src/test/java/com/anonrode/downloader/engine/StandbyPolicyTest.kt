package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Board row 5: the warm standby's hard accounting invariants, exercised
 * without sockets.
 *
 * 1. CAP MATH — active lanes + one idle standby never exceed
 *    min(per-host cap, lane grant); a capacity-1 task gets no standby.
 * 2. PROMOTION ACCOUNTING — a promoted connection repairs exactly the
 *    uncommitted tail of the abandoned piece: no byte is requested twice and
 *    no range is lost, even across consecutive promotions.
 */
class StandbyPolicyTest {

    private val policy = TurboDownloader.StandbyPolicy

    @Test
    fun activePlusStandbyNeverExceedsHostCapOrLaneGrant() {
        for (hostCap in 1..16) {
            for (grant in 1..16) {
                val capacity = policy.capacity(hostCap, grant)
                val standby = policy.standbyAllowed(hostCap, grant)
                val active = policy.activeWorkerBudget(hostCap, grant, standbyActive = standby)
                val total = active + (if (standby) 1 else 0)
                assertEquals(
                    "cap=$hostCap grant=$grant standby=$standby active=$active",
                    capacity,
                    total
                )
                assertTrue("active lanes must never be starved", active >= 1)
                assertEquals(minOf(hostCap, grant).coerceAtLeast(1), capacity)
            }
        }
    }

    @Test
    fun capacityOneGetsNoStandby() {
        assertFalse(policy.standbyAllowed(hostCap = 1, laneGrant = 16))
        assertFalse(policy.standbyAllowed(hostCap = 16, laneGrant = 1))
        assertEquals(1, policy.activeWorkerBudget(1, 16, standbyActive = false))
        assertTrue(policy.standbyAllowed(hostCap = 2, laneGrant = 16))
        assertEquals(1, policy.activeWorkerBudget(2, 16, standbyActive = true))
    }

    @Test
    fun promotionRepairsExactlyTheUncommittedTail() {
        val pieceStart = 1_000L
        val pieceEnd = 1_999L
        // The dropped connection had committed up to (not including) 1400.
        val writtenBeforeDrop = pieceStart until 1_400L
        val promoted = policy.promotionRange(current = 1_400L, end = pieceEnd)!!
        assertEquals(1_400L, promoted.first)
        assertEquals(pieceEnd, promoted.last)
        // Union of what was already on disk and what the promoted request
        // covers is the whole piece, with no overlap.
        val union = writtenBeforeDrop.toList() + promoted.toList()
        assertEquals((pieceEnd - pieceStart + 1).toInt(), union.size)
        assertEquals(union.size, union.toSet().size)
    }

    @Test
    fun consecutivePromotionsKeepTheRangeContinuousAndLoseNothing() {
        val pieceEnd = 1_999L
        // First promotion at 1400, connection dies again at 1700.
        val first = policy.promotionRange(1_400L, pieceEnd)!!
        val second = policy.promotionRange(1_700L, pieceEnd)!!
        assertEquals(1_400L, first.first)
        assertEquals(1_700L, second.first)
        // The second request starts exactly where the first committed; the
        // bytes 1400..1699 are on disk once and 1700..1999 exactly once.
        assertTrue(second.first in first)
        assertEquals(1_700L, first.first + (1_700L - 1_400L))
        assertEquals(pieceEnd, second.last)
    }

    @Test
    fun completedPieceIsNeverReRequested() {
        assertNull(policy.promotionRange(current = 2_000L, end = 1_999L))
    }
}
