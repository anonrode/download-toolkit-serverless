package com.anonrode.downloader.resolvers

import com.anonrode.downloader.pipeline.HostHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * resolveAny's candidate gate under bypassHealth (investigation 11 R1 / fix
 * row 10). bypassHealth exists for provider-endorsed fan-outs to lift STALE
 * cross-session backoffs (share-31: 2,189 hops pre-skipped) — it must never
 * rescind a rejection THIS session proved: a 403/436 recorded seconds ago
 * (HttpClient HttpStatusCodeException → HostHealth hard block) skips the
 * candidate and the fan-out falls through to the remaining lanes (mayonaka
 * 436 ×5 happened precisely because every hop re-learned it).
 */
class ResolverCandidateHealthTest {

    private fun freshHost(tag: String) = "https://$tag.candgate-test.invalid/embed/episode-1"

    @Test
    fun hardBlockSkipsTheCandidateEvenUnderBypassHealth() {
        val blocked = freshHost("hard")
        HostHealth.recordFail(blocked, reason = "HTTP 436 edge")
        assertFalse(
            "bypassHealth lifts stale backoffs, never a fresh named rejection",
            ResolverRegistry.candidateAllowedByHealth(blocked, bypassHealth = true)
        )
        assertFalse(ResolverRegistry.candidateAllowedByHealth(blocked, bypassHealth = false))
    }

    @Test
    fun bypassStillLiftsAStaleOrdinaryBackoff() {
        val stale = freshHost("stale")
        // Three consecutive non-hard failures IS the ordinary isUsable backoff
        // (30s first window) but NOT a hard block — bypassHealth semantics.
        HostHealth.recordFail(stale, reason = "SocketTimeoutException: timeout after 15000ms")
        HostHealth.recordFail(stale, reason = "SocketTimeoutException: timeout after 15000ms")
        HostHealth.recordFail(stale, reason = "SocketTimeoutException: timeout after 15000ms")
        assertFalse(HostHealth.hardBlockActive(stale))
        assertFalse(ResolverRegistry.candidateAllowedByHealth(stale, bypassHealth = false))
        assertTrue(
            "the provider-endorsed fan-out must still override stale backoffs",
            ResolverRegistry.candidateAllowedByHealth(stale, bypassHealth = true)
        )
    }

    @Test
    fun healthyCandidatePassesBothWays() {
        val healthy = freshHost("healthy")
        assertTrue(ResolverRegistry.candidateAllowedByHealth(healthy, bypassHealth = true))
        assertTrue(ResolverRegistry.candidateAllowedByHealth(healthy, bypassHealth = false))
    }

    @Test
    fun proofOfLifeReopensTheCandidateForTheHardGate() {
        val recovered = freshHost("recovered")
        HostHealth.recordFail(recovered, reason = "HTTP 436")
        assertFalse(ResolverRegistry.candidateAllowedByHealth(recovered, bypassHealth = true))
        HostHealth.recordOk(recovered)
        assertTrue(
            "once the host serves again the hard gate reopens",
            ResolverRegistry.candidateAllowedByHealth(recovered, bypassHealth = true)
        )
    }
}
