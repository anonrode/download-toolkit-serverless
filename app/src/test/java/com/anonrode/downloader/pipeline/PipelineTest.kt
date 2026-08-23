package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the resolution-pipeline primitives: pre-enqueue sniffing,
 * the resolve cache, the host health ledger's backoff windows, and error
 * classification. No Android framework dependencies (ledger persists to a
 * no-op file until init()).
 */
class PipelineTest {

    // ---------------- StreamValidator.sniff ----------------

    @Test
    fun sniff_rejectsHtmlErrorPage() {
        assertNotNull(StreamValidator.sniff("<!DOCTYPE html><html>".toByteArray()))
        assertNotNull(StreamValidator.sniff("<html><body>404</body>".toByteArray()))
        assertNotNull(StreamValidator.sniff("{\"error\":\"not found\"}".toByteArray()))
    }

    @Test
    fun sniff_rejectsArchiveAndExecutableContainers() {
        assertNotNull(StreamValidator.sniff(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))           // PK zip/apk
        assertNotNull(StreamValidator.sniff(byteArrayOf(0x4d, 0x5a, 0x90.toByte(), 0x00)))    // MZ exe
    }

    @Test
    fun sniff_acceptsPlausibleMediaHeads() {
        assertNull(StreamValidator.sniff(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))   // MKV
        assertNull(StreamValidator.sniff("ftyp".toByteArray()))                                    // ISO BMFF marker
        assertNull(StreamValidator.sniff("RIFF....AVI ".toByteArray()))
        assertNull(StreamValidator.sniff("#EXTM3U".toByteArray()))                                  // HLS text
    }

    @Test
    fun sniff_tooShortHeadIsNotGuilty() {
        assertNull(StreamValidator.sniff("ab".toByteArray()))
    }

    // ---------------- ResolveCache ----------------

    @Test
    fun resolveCache_putGetInvalidateRoundTrip() {
        val key = ResolveCache.keyFor("https://site/ep/1", "720p")
        assertEquals("720p::https://site/ep/1", key)
        assertNull(ResolveCache.get(key))
        ResolveCache.put(key, "https://cdn/file.mkv")
        assertEquals("https://cdn/file.mkv", ResolveCache.get(key))
        ResolveCache.invalidate(key)
        assertNull(ResolveCache.get(key))
        ResolveCache.clear()
    }

    @Test
    fun resolveCache_qualityIsPartOfTheKey() {
        val a = ResolveCache.keyFor("https://site/ep/1", "720p")
        val b = ResolveCache.keyFor("https://site/ep/1", "1080p")
        assertTrue(a != b)
    }

    // ---------------- HostHealth ----------------

    @Test
    fun hostHealth_backoffWindowAfterFailures_resetsOnSuccess() {
        val host = "healthtest.example"
        HostHealth.recordFail(host)
        HostHealth.recordFail(host)
        // The backoff threshold is >= 3 CONSECUTIVE failures: a single
        // hiccup (one 404, one timeout) must not gate a host for 30s+
        // (live-verified: nepu.gd backoff after fast search typing).
        assertTrue("2 consecutive fails must NOT yet open a backoff window",
            HostHealth.isUsable("https://$host/file.mkv"))

        HostHealth.recordFail(host)
        assertFalse("3 consecutive fails must open a backoff window",
            HostHealth.isUsable("https://$host/file.mkv"))

        HostHealth.recordOk(host)
        assertTrue("success must close the backoff window immediately",
            HostHealth.isUsable("https://$host/file.mkv"))
    }

    @Test
    fun hostHealth_unknownHostIsUsable_andKnownDeadIsNot() {
        assertTrue(HostHealth.isUsable("https://never-seen.example/x.mp4"))
        // Seeded by the bundled playbook defaults? knownDead only arrives via
        // OTA parse — feed it here:
        DynamicRulesManager.parseRulesJson(
            """{"version":"t","knownDead":["deadlock.example"]}"""
        )
        assertFalse(HostHealth.isUsable("https://deadlock.example/a.mkv"))
    }

    // ---------------- PipelineError classification ----------------

    @Test
    fun classify_mapsFailureStringsToTypedErrors() {
        assertTrue(PipelineError.classify("h", null) is PipelineError.HostDead)
        assertTrue(PipelineError.classify("h", "HTTP 429 Too Many Requests") is PipelineError.RateLimited)
        assertTrue(PipelineError.classify("h", "HTTP 403 Forbidden") is PipelineError.BlockedIp)
        assertTrue(PipelineError.classify("h", "HTTP 404 Not Found") is PipelineError.HostDead)
        assertTrue(PipelineError.classify("h", "timeout") is PipelineError.SiteDown)
    }

    @Test
    fun pageHash_isStableAndShort() {
        assertEquals(PipelineJournal.pageHash("abc"), PipelineJournal.pageHash("abc"))
        assertEquals(8, PipelineJournal.pageHash("abc").length)
    }
}
