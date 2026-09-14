package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.models.ShowCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The doctrine, tested. Every assertion here is a product rule from the
 * 2026-09-14 design review: hide ONLY proven-dead, badge ONLY live, keys
 * must never collide two different tokens, and an absent verdict is never
 * an absence of a card.
 */
class VerdictPolicyTest {

    private val now = 1_000_000_000_000L
    private fun live(bytes: Long? = 220_000_000, eps: Int = 1, age: Long = 0) =
        Verdict.Live("https://cdn.x/f?pt=abc", bytes, eps, now - age)
    private fun unreach(age: Long = 0) = Verdict.Unreachable("429", now - age)
    private fun dead(age: Long = 0) = Verdict.Dead("terminal-404x2", now - age)

    @Test
    fun `keyFor lowercases host and scheme, keeps path and token case, drops fragment`() {
        assertEquals(
            "https://nk.test/Show.S01E01.mkv?pt=AbC",
            VerdictPolicy.keyFor("HTTPS://Nk.TEST/Show.S01E01.mkv?pt=AbC#reviews")
        )
        // two tokens on the same path are two verdicts, never one:
        assertFalse(
            VerdictPolicy.keyFor("https://x/f?pt=AAA") == VerdictPolicy.keyFor("https://x/f?pt=aaa")
        )
        assertEquals("", VerdictPolicy.keyFor("  "))
    }

    @Test
    fun `freshness windows per state`() {
        assertTrue(VerdictPolicy.isFresh(live(age = VerdictPolicy.LIVE_TTL_MS - 1), now))
        assertFalse(VerdictPolicy.isFresh(live(age = VerdictPolicy.LIVE_TTL_MS + 1), now))
        assertTrue(VerdictPolicy.isFresh(dead(age = VerdictPolicy.DEAD_TTL_MS - 1), now))
        assertFalse(VerdictPolicy.isFresh(dead(age = VerdictPolicy.DEAD_TTL_MS + 1), now))
        assertTrue(VerdictPolicy.isFresh(unreach(age = 60_000), now))
        assertFalse(VerdictPolicy.isFresh(unreach(age = VerdictPolicy.UNREACHABLE_RETRY_MS + 1), now))
    }

    @Test
    fun `only a fresh Dead hides - nothing else ever does`() {
        assertTrue(VerdictPolicy.hideFromResults(dead(age = 60_000), now))
        assertFalse("a stale Dead returns to the list awaiting re-check",
            VerdictPolicy.hideFromResults(dead(age = VerdictPolicy.DEAD_TTL_MS + 1000), now))
        assertFalse(VerdictPolicy.hideFromResults(live(), now))
        assertFalse(VerdictPolicy.hideFromResults(unreach(), now))
        assertFalse("absence of a verdict is NOT a hide signal", VerdictPolicy.hideFromResults(null, now))
    }

    @Test
    fun `visible keeps order and everything except proven-dead`() {
        fun card(u: String) = ShowCard(title = u, url = u, site = "nkiri")
        val ranked = listOf(card("a"), card("b"), card("c"))
        val verdicts = mapOf(
            VerdictPolicy.keyFor("b") to dead() as Verdict,
            VerdictPolicy.keyFor("c") to unreach() as Verdict
        )
        assertEquals(listOf("a", "c"), VerdictPolicy.visible(ranked, verdicts, now).map { it.url })
    }

    @Test
    fun `caption badges winners only`() {
        assertEquals("✓ 220 MB", VerdictPolicy.captionFor(live(bytes = 220_000_000, eps = 1)))
        assertEquals("✓ 24 eps · 450 MB/ep", VerdictPolicy.captionFor(live(bytes = 450_000_000, eps = 24)))
        assertEquals("✓ 1.5 GB", VerdictPolicy.captionFor(live(bytes = 1_500_000_000, eps = 0)))
        assertEquals("✓", VerdictPolicy.captionFor(live(bytes = null, eps = 0)))
        assertNull(VerdictPolicy.captionFor(unreach()))
        assertNull(VerdictPolicy.captionFor(null))
        // <1 MB still reads honestly, not "0 MB":
        assertEquals("✓ 1 MB", VerdictPolicy.captionFor(live(bytes = 500_000, eps = 1)))
    }

    @Test
    fun `visibleOrdered floats fresh Live cards to the top, stable`() {
        fun card(u: String) = ShowCard(title = u, url = u, site = "nkiri")
        val ranked = listOf(card("a"), card("b"), card("c"), card("d"))
        val verdicts = mapOf(
            VerdictPolicy.keyFor("c") to live() as Verdict,
            VerdictPolicy.keyFor("b") to dead() as Verdict,
            VerdictPolicy.keyFor("d") to unreach() as Verdict
        )
        val ordered = VerdictPolicy.visibleOrdered(ranked, verdicts, now)
        // c LIVE floats to top; b hidden (proven dead); a stays rank-first
        // of the unverified half; d keeps its place with no badge.
        assertEquals(listOf("c", "a", "d"), ordered.map { it.url })
        // stale Live does NOT float (honesty: the bytes were proven 31min ago)
        val stale = mapOf(VerdictPolicy.keyFor("c") to live(age = VerdictPolicy.LIVE_TTL_MS + 1) as Verdict)
        assertEquals(listOf("a", "c", "d"), VerdictPolicy.visibleOrdered(
            ranked.filter { it.url != "b" }, stale, now
        ).map { it.url })
    }

    @Test
    fun `preresolved requires fresh live WITH size proof`() {
        assertTrue(VerdictPolicy.isPreresolvable(live(age = 119_000), now))
        assertFalse(VerdictPolicy.isPreresolvable(live(age = 121_000), now))
        assertFalse(VerdictPolicy.isPreresolvable(live(bytes = null), now))
        assertFalse(VerdictPolicy.isPreresolvable(dead(), now))
        assertFalse(VerdictPolicy.isPreresolvable(null, now))
    }
}
