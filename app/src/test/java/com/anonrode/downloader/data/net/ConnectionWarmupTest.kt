package com.anonrode.downloader.data.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Boot warm-up contract (share-43 §RC-2 cold start): the first AsianC search
 * of a session used to pay DNS + TCP/TLS + HTTP/2 negotiation — twice it
 * landed on a wedged edge and burned the full 30s read timeout.
 *
 * Contract under test, no sockets:
 *  - idempotent: a second call for an already-warmed origin dials nothing;
 *  - silent: a failing warmer never escapes (the Job completes normally);
 *  - non-blocking: warmAsync returns immediately while the transport is still
 *    stalled/slow;
 *  - single-flight: concurrent calls warm each origin once;
 *  - budget isolation: warm-up traffic is DOWNLOAD class, never SEARCH.
 */
class ConnectionWarmupTest {

    @Before
    fun setUp() {
        ConnectionWarmup.resetForTest()
    }

    @After
    fun tearDown() {
        ConnectionWarmup.resetForTest()
    }

    @Test
    fun warmsEachOriginOnceEvenWhenCalledAgain() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val warmer: suspend (String) -> Boolean = { origin ->
            calls.computeIfAbsent(origin) { AtomicInteger() }.incrementAndGet()
            true
        }

        ConnectionWarmup.warmAsync(listOf("https://a.test/", "https://b.test/"), warmer = warmer).join()
        ConnectionWarmup.warmAsync(listOf("https://a.test/", "https://b.test/"), warmer = warmer).join()

        assertEquals(1, calls.getValue("https://a.test/").get())
        assertEquals(1, calls.getValue("https://b.test/").get())
        val stats = ConnectionWarmup.statsForTest()
        assertEquals("no second attempt for warmed origins", 2, stats.attempts)
        assertEquals(2, stats.successes)
        assertEquals(0, stats.failures)
        assertEquals(2, stats.warmed)
    }

    @Test
    fun duplicateOriginsInOneCallAreWarmedOnce() = runBlocking {
        val calls = AtomicInteger()
        ConnectionWarmup.warmAsync(
            listOf("nepu.gd", "https://nepu.gd/", "nepu.gd")
        ) { calls.incrementAndGet(); true }.join()
        assertEquals(1, calls.get())
        assertEquals("scheme-less origin normalized once", 1, ConnectionWarmup.statsForTest().warmed)
    }

    @Test
    fun failingWarmerIsSilentAndRetryable() = runBlocking {
        val attempts = AtomicInteger()
        // The first round throws (DNS/connect failure shape); the Job must
        // still complete normally and the origin must stay retryable.
        val failing: suspend (String) -> Boolean = { attempts.incrementAndGet(); throw IOException("dns down") }
        ConnectionWarmup.warmAsync(listOf("https://dead.test/"), warmer = failing).join()
        assertEquals(1, ConnectionWarmup.statsForTest().failures)
        assertEquals(0, ConnectionWarmup.statsForTest().warmed)

        ConnectionWarmup.warmAsync(listOf("https://dead.test/")) { attempts.incrementAndGet(); true }.join()
        assertEquals("a failed warm may be retried", 2, attempts.get())
        assertEquals(1, ConnectionWarmup.statsForTest().successes)
        assertEquals(1, ConnectionWarmup.statsForTest().warmed)
    }

    @Test
    fun warmAsyncReturnsWithoutWaitingForTheTransport() = runBlocking {
        val t0 = System.currentTimeMillis()
        val job = ConnectionWarmup.warmAsync(listOf("https://slow.test/")) {
            delay(600) // stands in for a cold DNS+TLS+H2 negotiation
            true
        }
        val launchMs = System.currentTimeMillis() - t0
        assertTrue("warmAsync launch must not block the caller, took ${launchMs}ms", launchMs < 250)
        assertFalse("work still runs in the background", job.isCompleted)

        job.join()
        assertEquals(1, ConnectionWarmup.statsForTest().successes)
    }

    @Test
    fun concurrentCallsShareTheInFlightWarm() = runBlocking {
        val calls = AtomicInteger()
        val warmer: suspend (String) -> Boolean = { calls.incrementAndGet(); delay(250); true }
        val first = ConnectionWarmup.warmAsync(listOf("https://race.test/"), warmer = warmer)
        val second = ConnectionWarmup.warmAsync(listOf("https://race.test/"), warmer = warmer)
        first.join()
        second.join()
        assertEquals("concurrent calls must not double-dial", 1, calls.get())
    }

    @Test
    fun warmupTrafficNeverTakesTheSearchBudget() {
        assertEquals(
            "warm-up must be DOWNLOAD class so the first search keeps its slots",
            OriginRequestClass.DOWNLOAD,
            HttpClient.warmupRequestClassForTest()
        )
    }
}
