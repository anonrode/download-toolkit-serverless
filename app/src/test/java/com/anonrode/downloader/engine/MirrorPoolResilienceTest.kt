package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPoolResilienceTest {

    @Test
    fun sources_filtersInvalidAndDeduplicates() {
        val primary = "https://server1.example.com/file.mkv"
        val alts = listOf(
            "https://server2.example.com/file.mkv",
            "javascript:void(0)",
            "https://server1.example.com/file.mkv", // duplicate
            "ftp://insecure.example.com/file.mkv",  // non-http
            "https://server3.example.com/file.mkv"
        )
        val sources = MirrorPool.sources(primary, alts)
        assertEquals(3, sources.size)
        assertEquals(listOf(
            "https://server1.example.com/file.mkv",
            "https://server2.example.com/file.mkv",
            "https://server3.example.com/file.mkv"
        ), sources)
    }

    @Test
    fun eligible_partialLockWithoutForceRotate() {
        val primary = "https://server1.example.com/file.mkv"
        val alts = listOf("https://server2.example.com/file.mkv")
        val eligible = MirrorPool.eligible(
            primary = primary,
            alternatives = alts,
            selected = primary,
            hasPartial = true,
            forceRotate = false
        )
        // With existing partial bytes and no forceRotate, stay locked on current
        assertEquals(listOf(primary), eligible)
    }

    @Test
    fun eligible_forceRotateBypassesPartialLock() {
        val primary = "https://server1.example.com/file.mkv"
        val alts = listOf("https://server2.example.com/file.mkv")
        val eligible = MirrorPool.eligible(
            primary = primary,
            alternatives = alts,
            selected = primary,
            hasPartial = true,
            forceRotate = true
        )
        // forceRotate bypasses partial lock to allow failover to alternative
        assertTrue(primary !in eligible)
        assertTrue("https://server2.example.com/file.mkv" in eligible)
    }

    @Test
    fun nextMirror_picksNextUsableCandidate() {
        val primary = "https://server1.example.com/file.mkv"
        val alts = listOf(
            "https://server2.example.com/file.mkv",
            "https://server3.example.com/file.mkv"
        )
        val next = MirrorPool.nextMirror(primary, alts, failedUrl = primary)
        assertEquals("https://server2.example.com/file.mkv", next)

        val nextAfterSecond = MirrorPool.nextMirror(primary, alts, failedUrl = "https://server2.example.com/file.mkv")
        assertEquals(primary, nextAfterSecond)
    }
}
