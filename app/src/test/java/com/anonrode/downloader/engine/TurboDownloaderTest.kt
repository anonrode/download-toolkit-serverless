package com.anonrode.downloader.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/**
 * JVM tests for TurboDownloader resilience: piece-level retry after mid-body
 * disconnects, retry exhaustion, and single-stream resume. The engine's
 * telemetry ticker makes 1 MiB+ pieces fast even with a 1 ms retry backoff.
 */
class TurboDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dir: File

    @Before
    fun setUp() {
        TurboDownloader.retryBaseDelayMs = 1L
        dir = createTempDirectory("turbo-test").toFile()
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        TurboDownloader.retryBaseDelayMs = 1000L
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun segmentedDownload_completesWithCorrectBytes() {
        val payload = payload(20 * 1024 * 1024)
        server.dispatcher = FlakyRangeServer(payload)
        val result = downloadTo("video.mp4")
        assertTrue("expected Success, got $result", result is TurboDownloader.TurboResult.Success)
        val success = result as TurboDownloader.TurboResult.Success
        assertTrue(success.segmented)
        assertEquals(payload.size.toLong(), success.bytes)
        assertTrue(File(dir, "video.mp4").readBytes().contentEquals(payload))
    }

    @Test
    fun segmentedDownload_retriesPieceAfterMidBodyDisconnect() {
        val payload = payload(20 * 1024 * 1024)
        server.dispatcher = FlakyRangeServer(payload, failFirstRanges = 1)
        val result = downloadTo("video.mp4")
        assertTrue("expected Success, got $result", result is TurboDownloader.TurboResult.Success)
        assertTrue(File(dir, "video.mp4").readBytes().contentEquals(payload))
    }

    @Test
    fun segmentedDownload_givesUpAfterMaxAttempts() {
        val payload = payload(20 * 1024 * 1024)
        server.dispatcher = FlakyRangeServer(payload, failFirstRanges = Int.MAX_VALUE)
        val result = downloadTo("video.mp4")
        assertTrue("expected Failure, got $result", result is TurboDownloader.TurboResult.Failure)
        val failure = result as TurboDownloader.TurboResult.Failure
        assertEquals(null, failure.httpStatus) // every response was 206, just truncated
        assertNotNull(failure.message)
    }

    @Test
    fun singleDownload_resumesAfterMidBodyDisconnect() {
        // Small enough to skip segmented mode; ranges still supported so the
        // retry can resume with Range: bytes=N-.
        val payload = payload(2 * 1024 * 1024)
        server.dispatcher = FlakyRangeServer(payload, failFirstFullGets = 1, supportsRanges = true)
        val result = downloadTo("video.mp4")
        assertTrue("expected Success, got $result", result is TurboDownloader.TurboResult.Success)
        val success = result as TurboDownloader.TurboResult.Success
        assertFalse(success.segmented)
        assertTrue(File(dir, "video.mp4").readBytes().contentEquals(payload))
    }

    @Test
    fun contiguousPrefixBytes_readsLongestCompletePrefix() {
        val state = TurboState(File(dir, "video.mp4.turbo"))
        state.commit(
            listOf(
                TurboChunk(0, 99, 100),   // complete
                TurboChunk(100, 199, 100), // incomplete
                TurboChunk(200, 299, 300)  // complete
            ),
            total = 300,
            force = true
        )
        assertEquals(100L, state.contiguousPrefixBytes())
    }

    @Test
    fun contiguousPrefixBytes_allCompleteAndMissingSidecar() {
        val sidecar = File(dir, "video.mp4.turbo")
        val state = TurboState(sidecar)
        state.commit(listOf(TurboChunk(0, 99, 100)), total = 100, force = true)
        assertEquals(100L, state.contiguousPrefixBytes())
        sidecar.delete()
        assertEquals(null, state.contiguousPrefixBytes())
    }

    private fun downloadTo(destName: String): TurboDownloader.TurboResult = runBlocking {
        TurboDownloader.download(
            url = server.url("/video.mp4").toString(),
            dest = File(dir, destName),
            configuredSockets = 4,
            client = client
        )
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }
}

/**
 * Mock server that drops the connection mid-body for the first N range or
 * full GET requests, simulating a flaky CDN.
 */
private class FlakyRangeServer(
    private val content: ByteArray,
    private val failFirstRanges: Int = 0,
    private val failFirstFullGets: Int = 0,
    private val supportsRanges: Boolean = true
) : Dispatcher() {

    private val rangeRequests = AtomicInteger(0)
    private val fullGets = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")
        if (request.method == "HEAD") {
            return MockResponse()
                .setHeader("Content-Length", content.size)
                .setHeader("Accept-Ranges", if (supportsRanges) "bytes" else "none")
        }
        if (range == null) {
            val n = fullGets.incrementAndGet()
            return MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(content))
                .apply { if (n <= failFirstFullGets) setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
        }
        if (!supportsRanges) {
            // No range support: ignore the Range header and serve the full body.
            val n = fullGets.incrementAndGet()
            return MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(content))
                .apply { if (n <= failFirstFullGets) setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
        }
        val n = rangeRequests.incrementAndGet()
        val spec = range.removePrefix("bytes=")
        val parts = spec.split('-')
        val start = parts[0].toInt()
        val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (content.size - 1)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${content.size}")
            .setHeader("Content-Length", end - start + 1)
            .setBody(Buffer().write(content, start, end - start + 1))
            .apply { if (n <= failFirstRanges) setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
    }
}
