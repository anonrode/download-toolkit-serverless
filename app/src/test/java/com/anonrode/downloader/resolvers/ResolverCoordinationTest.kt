package com.anonrode.downloader.resolvers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class ResolverCoordinationTest {
    @Test(timeout = 5000)
    fun activeWorkIsBoundedAndAllFailuresAreTried() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val filled = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val tried = mutableListOf<Int>()
        val race = async {
            boundedFirstSuccess<Int, String>((0 until 100).toList(), 3) { candidate ->
                tried += candidate
                active++
                peak = maxOf(peak, active)
                if (active == 3) filled.complete(Unit)
                try {
                    release.await()
                    null
                } finally {
                    active--
                }
            }
        }
        filled.await()
        yield()
        assertEquals(3, tried.size)
        assertEquals(3, active)
        release.complete(Unit)
        assertNull(race.await())
        assertEquals((0 until 100).toList(), tried.sorted())
        assertEquals(3, peak)
        assertEquals(0, active)
    }

    @Test(timeout = 5000)
    fun laterCandidateWinsWhileEarlierLoserIsStillRunning() = runBlocking {
        val loserStarted = CompletableDeferred<Unit>()
        var loserCancelled = false
        val tried = mutableListOf<Int>()
        val result = boundedFirstSuccess((0..8).toList(), 2) { candidate ->
            tried += candidate
            if (candidate == 0) {
                loserStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    loserCancelled = true
                }
            }
            loserStarted.await()
            if (candidate == 5) "winner" else null
        }
        assertEquals("winner", result)
        assertTrue(loserCancelled)
        assertTrue(tried.contains(5))
        assertFalse(tried.contains(6))
    }

    @Test(timeout = 5000)
    fun throwingCandidateDoesNotHideLaterSuccess() = runBlocking {
        assertEquals("ok", boundedFirstSuccess(listOf(1, 2, 3), 1) {
            if (it < 3) throw IllegalStateException("bad candidate")
            "ok"
        })
        assertNull(boundedFirstSuccess<Int, String>(listOf(1), 3) {
            throw IllegalArgumentException("single candidate")
        })
    }

    @Test(timeout = 5000)
    fun parentCancellationStopsWorkersWithoutLaunchingMore() = runBlocking {
        val filled = CompletableDeferred<Unit>()
        var started = 0
        var stopped = 0
        var returned = false
        val parent = launch {
            boundedFirstSuccess<Int, String>((0..99).toList(), 3) {
                started++
                if (started == 3) filled.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped++
                }
            }
            returned = true
        }
        filled.await()
        parent.cancelAndJoin()
        assertEquals(3, started)
        assertEquals(3, stopped)
        assertFalse(returned)
    }

    @Test(timeout = 5000)
    fun cancellationExceptionIsNotAnOrdinaryCandidateFailure() = runBlocking {
        var laterStarted = false
        try {
            boundedFirstSuccess(listOf(1, 2), 1) {
                if (it == 1) throw CancellationException("cancel candidate")
                laterStarted = true
                "unexpected"
            }
            fail("Cancellation must escape")
        } catch (_: CancellationException) {
            assertFalse(laterStarted)
        }
    }

    @Test(timeout = 5000)
    fun emptyAndNonpositiveLimitsRemainSafe() = runBlocking {
        assertNull(boundedFirstSuccess<Int, String>(emptyList(), 0) {
            error("No candidate expected")
        })
        assertEquals("ok", boundedFirstSuccess(listOf(1, 2), -1) {
            if (it == 2) "ok" else null
        })
    }

    @Test(timeout = 5000)
    fun typedOutcomesBelongToTheirOwnAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failed = async {
            captureResolverOutcome {
                entered.complete(Unit)
                release.await()
                throw SocketTimeoutException("request A")
            }
        }
        entered.await()
        assertEquals(ResolverOutcome.Success("B"), captureResolverOutcome { "B" })
        assertEquals(ResolverOutcome.NoMatch, captureResolverOutcome { null })
        release.complete(Unit)
        val failure = failed.await() as ResolverOutcome.Failure
        assertTrue(failure.retryable)
        assertTrue(failure.reason.contains("request A"))
        assertFalse((captureResolverOutcome { throw IllegalArgumentException("parse") }
            as ResolverOutcome.Failure).retryable)
    }

    @Test(timeout = 5000)
    fun cooldownFailureRetainsTheOriginOfTheActualHop() = runBlocking {
        val hop = "https://mirror.example/token"
        val outcome = captureResolverOutcome {
            throw com.anonrode.downloader.data.net.OriginCooldownException(hop)
        } as ResolverOutcome.Failure
        assertTrue(outcome.retryable)
        assertEquals(hop, outcome.cooldownUrl)
        val other = resolverFailure(SocketTimeoutException("other request"))
        assertNull(other.cooldownUrl)
    }

    @Test(timeout = 5000)
    fun outcomeAdapterDoesNotSwallowCancellation() = runBlocking {
        try {
            captureResolverOutcome { throw CancellationException("parent") }
            fail("Cancellation must escape")
        } catch (_: CancellationException) {
            // Expected control flow, not an outcome.
        }
    }
}
