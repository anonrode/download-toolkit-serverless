package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.pipeline.ResolveCache
import com.anonrode.downloader.providers.RulesPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure guards for the two live failure shapes:
 *  - vidsrc.buzz `_stream?id=...` answers 200 with a 1-byte mpegurl body;
 *  - nsrplay resolve answers binary/hand-built URLs with control bytes.
 */
class ResolverStubGuardsTest {

    @Test
    fun oneByteMpegurlIsAStubNotAManifest() {
        val verdict = VidsrcResolver.headPayloadVerdict(
            "application/vnd.apple.mpegurl",
            "1".toByteArray(Charsets.UTF_8)
        )
        assertEquals(VidsrcResolver.MintHeadVerdict.STUB, verdict)
    }

    @Test
    fun realExtM3uPlaylistIsAManifest() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttps://cdn.example/x.m3u8\n"
        assertEquals(
            VidsrcResolver.MintHeadVerdict.MANIFEST,
            VidsrcResolver.headPayloadVerdict("application/vnd.apple.mpegurl", body.toByteArray())
        )
    }

    @Test
    fun emptyAndTinyNonPlaylistBodiesAreStubs() {
        assertEquals(
            VidsrcResolver.MintHeadVerdict.STUB,
            VidsrcResolver.headPayloadVerdict("text/plain", ByteArray(0))
        )
        assertEquals(
            VidsrcResolver.MintHeadVerdict.STUB,
            VidsrcResolver.headPayloadVerdict("text/plain", "1".toByteArray())
        )
    }

    @Test
    fun realMediaMagicStillWins() {
        val mp4 = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        assertEquals(
            VidsrcResolver.MintHeadVerdict.MEDIA,
            VidsrcResolver.headPayloadVerdict("text/plain", mp4)
        )
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)
        assertEquals(
            VidsrcResolver.MintHeadVerdict.AD,
            VidsrcResolver.headPayloadVerdict("image/jpeg", png)
        )
    }

    @Test
    fun poisonedStubUrlIsNotServedAgain() {
        val stub = "https://vidsrc.buzz/_stream?id=stub-test"
        try {
            ResolveCache.poison(stub)
            assertTrue(ResolveCache.isPoisoned(stub))
            ResolveCache.clearPoison()
            assertFalse(ResolveCache.isPoisoned(stub))
        } finally {
            ResolveCache.clearPoison()
        }
    }

    @Test
    fun binaryBodiesAreRejectedBeforeBinding() {
        val mp4 = "\u0000\u0000\u0000 ftypisom\u0000\u0000\u0000\u0000".repeat(8)
        assertTrue(RulesPipeline.looksBinaryBody(mp4, mapOf("Content-Type" to "video/mp4")))
        assertTrue(RulesPipeline.looksBinaryBody(mp4, emptyMap()))
        assertFalse(RulesPipeline.looksBinaryBody("""{"data":{"playUrl":"https://nsrplay.space/x"}}""", mapOf("Content-Type" to "application/json")))
        assertFalse(RulesPipeline.looksBinaryBody("<html><body>ok</body></html>", mapOf("Content-Type" to "text/html")))
    }

    @Test
    fun mpegurlTypeWithOneByteIsAStubEvenWithoutM3u8InUrl() {
        assertTrue(
            RulesPipeline.isHlsTerminalStub(
                "https://vidsrc.buzz/_stream?id=abc", 1L, "application/vnd.apple.mpegurl"
            )
        )
        assertFalse(
            RulesPipeline.isHlsTerminalStub(
                "https://cdn.example/master.m3u8", 4096L, "application/vnd.apple.mpegurl"
            )
        )
    }

    @Test
    fun zippyshareGetsTheExplicitShutdownMessage() {
        try {
            assertTrue(
                DynamicRulesManager.parseRulesJson(
                    """{"version":"dead-test","knownDead":["zippyshare.com"]}"""
                )
            )
            val reason = ResolverRegistry.knownDeadReason("https://www82.zippyshare.com/v/abc/file.html")
            assertTrue(reason != null && reason.contains("Zippyshare shut down in 2023"))
        } finally {
            DynamicRulesManager.parseRulesJson("""{"version":"dead-test-reset"}""")
        }
    }

    @Test
    fun terminalCandidatesRequireCleanHttps() {
        assertTrue(RulesPipeline.isSafeTerminalCandidate("https://nsrplay.space/api/v1/embed/stream-proxy?token=abc"))
        assertFalse(RulesPipeline.isSafeTerminalCandidate("http://nsrplay.space/api/v1/embed/stream-proxy?token=abc"))
        assertFalse(RulesPipeline.isSafeTerminalCandidate("https://nsrplay.space/proxy?x=%00%00%00%20ftypisom"))
        assertFalse(RulesPipeline.isSafeTerminalCandidate("https://nsrplay.space/proxy?x=a\u0000b"))
    }
}
