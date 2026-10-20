package com.anonrode.downloader.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 451/429 scoping (NEPU rule-13 regression, 2026-10-20).
 *
 * The old single cooldown key meant one download fan-out 429 parked EVERY
 * request on that origin — the user's search included. Cooldowns are now
 * stored per origin+request-class; search only honors the search scope.
 */
class CooldownScopeTest {

    private val url = "https://nepu.gd/api/search"

    @Test
    fun `download cooldown never blocks search on the same origin`() {
        var now = 1_000L
        val c = RetryAfterCooldown(clock = { now })
        c.record(url, "30", scope = RetryAfterCooldown.SCOPE_DOWNLOAD)

        assertTrue(c.isCoolingDown(url, scope = RetryAfterCooldown.SCOPE_DOWNLOAD))
        assertFalse(c.isCoolingDown(url, scope = RetryAfterCooldown.SCOPE_SEARCH))
        assertFalse(c.isCoolingDown(url, scope = RetryAfterCooldown.SCOPE_VERIFY))
        assertEquals(0L, c.remainingMs(url, scope = RetryAfterCooldown.SCOPE_SEARCH))
        assertEquals(30_000L, c.remainingMs(url, scope = RetryAfterCooldown.SCOPE_DOWNLOAD))

        now = 40_000L
        assertFalse(c.isCoolingDown(url, scope = RetryAfterCooldown.SCOPE_DOWNLOAD))
    }

    @Test
    fun `search's own 429 does not park the download path`() {
        var now = 0L
        val c = RetryAfterCooldown(clock = { now })
        c.record(url, "10", scope = RetryAfterCooldown.SCOPE_SEARCH)
        assertTrue(c.isCoolingDown(url, scope = RetryAfterCooldown.SCOPE_SEARCH))
        assertFalse(c.isCoolingDown(url, scope = RetryAfterCooldown.SCOPE_DOWNLOAD))
    }

    @Test
    fun `default scope keeps every legacy caller on the download class`() {
        val c = RetryAfterCooldown(clock = { 0L })
        c.record(url, "5")
        assertTrue(c.isCoolingDown(url))
        assertEquals(5_000L, c.remainingMs(url))
        assertFalse(c.isCoolingDown(url, scope = RetryAfterCooldown.SCOPE_SEARCH))
    }

    @Test
    fun `each scope keeps its own later-expiry merge`() {
        var now = 0L
        val c = RetryAfterCooldown(clock = { now })
        c.record(url, "10", scope = RetryAfterCooldown.SCOPE_SEARCH)
        now = 1_000L
        c.record(url, "2", scope = RetryAfterCooldown.SCOPE_SEARCH)
        assertEquals(9_000L, c.remainingMs(url, scope = RetryAfterCooldown.SCOPE_SEARCH))
    }
}
