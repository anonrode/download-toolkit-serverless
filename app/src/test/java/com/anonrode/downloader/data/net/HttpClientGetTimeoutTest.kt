package com.anonrode.downloader.data.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cold-start search budget (share-43 §RC-2): a wedged HTTP/2 edge — TCP open,
 * TLS done, preface never written — used to hold the caller for the client's
 * full 30s read timeout (asianc "Required SETTINGS preface … 30978ms",
 * "Read timed out after 30529ms", both on the day's FIRST search call).
 * getTextWithTimeout must answer within its own budget: null fast enough to
 * retry on a stalled origin, and the body untouched on a healthy one.
 */
class HttpClientGetTimeoutTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun stalledOriginReturnsNullWithinTheCallBudget() {
        // NO_RESPONSE: connection accepted, headers never written — the same
        // shape the app saw from the wedged CDN edge (SETTINGS-preface stall).
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.currentTimeMillis()
        val text = HttpClient.getTextWithTimeout(server.url("/api?a=search&keyword=romance").toString(), 1_500L)
        val elapsed = System.currentTimeMillis() - t0
        assertNull(text)
        assertTrue("stall must return within ~budget, took ${elapsed}ms", elapsed < 6_000)
    }

    @Test
    fun healthyOriginReturnsTheBodyInsideTheSameBudget() {
        server.enqueue(MockResponse().setBody("""[{"value":"Alpha","url":"/drama-detail/alpha-2026","name":"Alpha"}]"""))
        val text = HttpClient.getTextWithTimeout(server.url("/api?a=search&keyword=alpha").toString(), 5_000L)
        assertTrue(text != null && text.contains("drama-detail"))
    }
}
