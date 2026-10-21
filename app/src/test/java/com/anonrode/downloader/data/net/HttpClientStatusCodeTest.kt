package com.anonrode.downloader.data.net

import com.anonrode.downloader.pipeline.HostHealth
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Typed HTTP statuses at the getText boundary (investigation 11 R1 / fix row
 * 10, share-43): a non-2xx used to die as a bare IOException string, so
 * 2anime.xyz's 436 was re-fetched five times inside ONE resolve — nothing in
 * the chain ever learned the host rejects the client. Now:
 *  - the message keeps its exact historical shape ("HTTP {code} for {url}")
 *    for every lastFailure/park-reason consumer, and
 *  - 403/436 (the observed edge-rejection class) prove the host hostile in
 *    HostHealth for a session window, which resolveAny's candidate gate
 *    honors even under bypassHealth — the fan-out falls through to the next
 *    lane instead of re-learning the same rejection.
 *
 * MockWebServer serves ONE host, and HostHealth's ledger is process-global
 * (by design), so every test is self-contained on a fresh queue + relies on
 * tearDown's recordOk lifting any block its own assertions created.
 */
class HttpClientStatusCodeTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: okhttp3.HttpUrl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/")
    }

    @After
    fun tearDown() {
        // Lift any hard block this test recorded so later tests on this host
        // start clean (same ledger-host across all MockWebServer URLs).
        HostHealth.recordOk(baseUrl.toString())
        server.shutdown()
    }

    private fun pathUrl(path: String): String =
        baseUrl.newBuilder().encodedPath(path).build().toString()

    @Test
    fun http436ReturnsNullAndHardBlocksTheHost() {
        server.enqueue(MockResponse().setResponseCode(436).setBody("blocked"))
        val url = pathUrl("/embed/mayonaka-punch-short-anime-episode-1")
        assertNull(HttpClient.getText(url))
        assertTrue(
            "436 must land in lastFailure with the historical shape",
            HttpClient.lastFailure?.startsWith("HTTP 436 for ") == true
        )
        assertTrue(
            "the 436 host must be session-hard-blocked for the resolver gate",
            HostHealth.hardBlockActive(url)
        )
    }

    @Test
    fun http403AlsoHardBlocks() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val url = pathUrl("/embed/third-host-403")
        assertNull(HttpClient.getText(url))
        assertTrue(
            "403 belongs to the same edge-rejection class as 436",
            HostHealth.hardBlockActive(url)
        )
    }

    @Test
    fun plain404IsADeadLinkNotAHostileHost() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val url = pathUrl("/embed/normal-404")
        assertNull(HttpClient.getText(url))
        assertFalse(
            "a single 404 must not hard-block the host (dead-link signal only)",
            HostHealth.hardBlockActive(url)
        )
    }
}
