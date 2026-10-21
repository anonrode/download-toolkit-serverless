package com.anonrode.downloader.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostHealth.hardBlockActive — the session-window named-rejection gate
 * (investigation 11 R1 / fix row 10). Contract pinned without network:
 *  - reason "HTTP 436"/"HTTP 403" (the HttpClient HttpStatusCodeException
 *    record, and resolver failure texts embedding the code) block;
 *  - 404/timeouts/cancellation reasons never block;
 *  - a later recordOk (or clearIfAlive, same lastOkMs path) lifts the block;
 *  - unique per-test fake hosts sidestep cross-test ledger bleed (the
 *    PipelineTest HostHealth convention — the ledger is process-global).
 */
class HostHealthHardBlockTest {

    private fun freshHost(tag: String) = "https://$tag.hardblock-test.invalid/x/file.mkv"

    @Test
    fun namedEdgeRejectionBlocksTheHost() {
        val host436 = freshHost("rej436")
        val host403 = freshHost("rej403")
        HostHealth.recordFail(host436, reason = "HTTP 436 2anime.xyz")
        HostHealth.recordFail(host403, reason = "IOException: HTTP 403 for https://edge/x")
        assertTrue(HostHealth.hardBlockActive(host436))
        assertTrue(HostHealth.hardBlockActive(host403))
    }

    @Test
    fun deadLinksAndTimeoutsNeverHardBlock() {
        val host404 = freshHost("dead404")
        val hostTmo = freshHost("slowtmo")
        val hostCancel = freshHost("usercancel")
        HostHealth.recordFail(host404, reason = "HTTP 404 for https://x/y")
        HostHealth.recordFail(hostTmo, reason = "SocketTimeoutException: Read timed out after 15000ms")
        HostHealth.recordFail(hostCancel, reason = "IOException: Canceled")
        assertFalse(HostHealth.hardBlockActive(host404))
        assertFalse(HostHealth.hardBlockActive(hostTmo))
        assertFalse(HostHealth.hardBlockActive(hostCancel))
    }

    @Test
    fun aLaterSuccessLiftsTheBlock() {
        val host = freshHost("recovers")
        HostHealth.recordFail(host, reason = "HTTP 436 edge said no")
        assertTrue(HostHealth.hardBlockActive(host))
        HostHealth.recordOk(host)
        assertFalse(
            "the host answered again — the hard block must yield to proof of life",
            HostHealth.hardBlockActive(host)
        )
    }

    @Test
    fun clearIfAliveAlsoLiftsTheBlock() {
        val host = freshHost("clearalive")
        HostHealth.recordFail(host, reason = "HTTP 436")
        assertTrue(HostHealth.hardBlockActive(host))
        HostHealth.clearIfAlive(host)
        assertFalse(HostHealth.hardBlockActive(host))
    }

    @Test
    fun unknownHostHasNoBlock() {
        assertFalse(HostHealth.hardBlockActive(freshHost("untouched")))
    }
}
