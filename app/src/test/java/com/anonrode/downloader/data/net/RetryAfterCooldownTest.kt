package com.anonrode.downloader.data.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RetryAfterCooldownTest {
    private val url = "https://example.com/file"

    @Test
    fun hugeSecondsNeverWrapIntoAnExpiredCooldown() {
        val cooldown = RetryAfterCooldown(clock = { 1000L })
        for (header in listOf(Long.MAX_VALUE.toString(), "9223372036854776", "999999999999999999999999999")) {
            cooldown.clear()
            cooldown.record(url, header)
            assertEquals(header, RetryAfterCooldown.MAX_COOLDOWN_MS, cooldown.remainingMs(url))
            assertTrue(cooldown.isCoolingDown(url))
        }
    }

    @Test
    fun ordinarySecondsAndMalformedValuesKeepTheirSemantics() {
        val cooldown = RetryAfterCooldown(clock = { 0L })
        assertEquals(12000L, cooldown.parseRetryAfterMs("12", 0L))
        assertEquals(0L, cooldown.parseRetryAfterMs("0", 0L))
        assertNull(cooldown.parseRetryAfterMs("-1", 0L))
        assertNull(cooldown.parseRetryAfterMs("bad", 0L))
        assertNull(cooldown.parseRetryAfterMs(null, 0L))
        cooldown.record(url, "bad")
        assertEquals(RetryAfterCooldown.DEFAULT_COOLDOWN_MS, cooldown.remainingMs(url))
    }

    @Test
    fun pastHttpDateMeansNoWait() {
        val cooldown = RetryAfterCooldown()
        assertEquals(0L, cooldown.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 1600000000000L))
    }

    @Test
    fun originsAreIsolatedAndLaterExpiryWins() {
        var now = 1000L
        val cooldown = RetryAfterCooldown(clock = { now })
        cooldown.record(url, "12")
        cooldown.record(url, "1")
        assertEquals(12000L, cooldown.remainingMs("https://example.com/other"))
        assertEquals(0L, cooldown.remainingMs("http://example.com/file"))
        assertEquals(0L, cooldown.remainingMs("https://other.example/file"))
        now = 13000L
        assertFalse(cooldown.isCoolingDown(url))
    }

    @Test(timeout = 5000)
    fun waitReturnsEvenWhenTheRecordedClockDoesNotAdvance() = runBlocking {
        val cooldown = RetryAfterCooldown(clock = { 0L }, maxCooldownMs = 30L)
        cooldown.record(url, "120")
        cooldown.waitUntilUsable(url)
        assertTrue(cooldown.isCoolingDown(url))
    }

    @Test(timeout = 5000)
    fun parentCancellationEscapesTheWait() = runBlocking {
        val cooldown = RetryAfterCooldown(clock = { 0L })
        cooldown.record(url, "120")
        var returned = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            cooldown.waitUntilUsable(url)
            returned = true
        }
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
        assertFalse(returned)
    }
}
