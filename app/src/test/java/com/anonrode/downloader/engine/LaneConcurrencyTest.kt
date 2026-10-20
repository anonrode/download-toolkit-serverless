package com.anonrode.downloader.engine

import com.anonrode.downloader.pipeline.HostPolicyRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/**
 * TRUE concurrency proof (2026-10-19): three simultaneous Turbo tasks under a
 * 12-lane budget must never exceed 12 in-flight OkHttp calls, each must get
 * its guaranteed 4-lane minimum, and all must complete. The old shape was
 * 3 × 16 = 48 flows.
 */
class LaneConcurrencyTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val inFlight = AtomicInteger(0)
    private val maxInFlight = AtomicInteger(0)
    private val started = ConcurrentHashMap<Call, Boolean>()

    private val listenerFactory = object : EventListener() {
        override fun callStart(call: Call) {
            started[call] = true
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> if (a > b) a else b }
        }

        override fun callEnd(call: Call) {
            if (started.remove(call) != null) inFlight.decrementAndGet()
        }

        override fun callFailed(call: Call, ioe: IOException) {
            if (started.remove(call) != null) inFlight.decrementAndGet()
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .eventListener(listenerFactory)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Before
    fun setUp() {
        HostPolicyRegistry.LaneBudget.clearForTest()
        HostPolicyRegistry.LaneBudget.globalLanes = 12
        TurboDownloader.retryBaseDelayMs = 1L
        TurboDownloader.throttleBaseDelayMs = 1L
        dir = createTempDirectory("lane-concurrency").toFile()
        server = MockWebServer()
        server.dispatcher = RangeServer(payload(6 * 1024 * 1024))
        server.start()
    }

    @After
    fun tearDown() {
        HostPolicyRegistry.LaneBudget.clearForTest()
        TurboDownloader.retryBaseDelayMs = 1000L
        TurboDownloader.throttleBaseDelayMs = 15_000L
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test(timeout = 45_000)
    fun threeTasksStayWithinGlobalBudgetAndAllComplete() = runBlocking {
        val budget = HostPolicyRegistry.LaneBudget
        val grants = listOf("a", "b", "c").map { id ->
            budget.reserve(id, 16, hls = false, plannedTasks = 3)
        }
        assertEquals(listOf(4, 4, 4), grants)

        val results = listOf("a", "b", "c").map { id ->
            async {
                TurboDownloader.download(
                    url = server.url("/video.mp4").toString(),
                    dest = File(dir, "$id.mp4"),
                    configuredSockets = budget.grantFor(id),
                    client = client,
                    taskId = id
                )
            }
        }.awaitAll()

        assertTrue("maxInFlight=$maxInFlight exceeded budget 12", maxInFlight.get() <= 12)
        results.forEachIndexed { i, result ->
            assertTrue("task $i failed: $result", result is TurboDownloader.TurboResult.Success)
            assertEquals(
                "task $i must use its 4-lane grant",
                4,
                budget.grantFor(listOf("a", "b", "c")[i])
            )
        }
        assertTrue("ramp/warm-up must not churn lanes", TurboDownloader.dropEventCounts.isEmpty())
        assertEquals(0, inFlight.get()) // drained: no leaked in-flight calls
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 17 + 3).toByte() }
}

private class RangeServer(private val content: ByteArray) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.method == "HEAD") {
            return MockResponse()
                .setHeader("Content-Length", content.size)
                .setHeader("Accept-Ranges", "bytes")
        }
        val range = request.getHeader("Range")
        if (range == null) {
            return MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(content))
        }
        val spec = range.removePrefix("bytes=")
        val parts = spec.split('-')
        val start = parts[0].toInt()
        val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (content.size - 1)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${content.size}")
            .setHeader("Content-Length", end - start + 1)
            .setBody(Buffer().write(content, start, end - start + 1))
    }
}
