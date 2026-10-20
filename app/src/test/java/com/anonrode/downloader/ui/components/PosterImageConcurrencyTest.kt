package com.anonrode.downloader.ui.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import coil.request.CachePolicy
import coil.size.Size
import com.anonrode.downloader.PosterImages
import com.anonrode.downloader.providers.PosterNetwork
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the image-plane hard limits against a live MockWebServer:
 * <=6 requests in flight overall and <=3 per host, using the same dispatcher
 * factory production uses. Also proves the shared request factory never asks
 * for ORIGINAL and uses the display size + explicit cache keys.
 */
class PosterImageDispatcherTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        servers.clear()
    }

    @Test(timeout = 45_000)
    fun sixTotalAndThreePerHost() {
        val one = MockWebServer().also { servers.add(it); it.start() }
        val two = MockWebServer().also { servers.add(it); it.start() }
        repeat(8) { one.enqueue(MockResponse().setResponseCode(200).setBody("ok")) }
        repeat(8) { two.enqueue(MockResponse().setResponseCode(200).setBody("ok")) }

        val total = AtomicInteger()
        val maxTotal = AtomicInteger()
        val perHost = ConcurrentHashMap<String, AtomicInteger>()
        val maxPerHost = ConcurrentHashMap<String, AtomicInteger>()

        val client = OkHttpClient.Builder()
            .dispatcher(PosterImages.newDispatcher())
            .addInterceptor { chain ->
                val host = chain.request().url.host
                val nowTotal = total.incrementAndGet()
                maxTotal.accumulateAndGet(nowTotal) { a, b -> maxOf(a, b) }
                val hostCounter = perHost.computeIfAbsent(host) { AtomicInteger() }
                val nowHost = hostCounter.incrementAndGet()
                maxPerHost.computeIfAbsent(host) { AtomicInteger() }
                    .accumulateAndGet(nowHost) { a, b -> maxOf(a, b) }
                try {
                    Thread.sleep(120)
                    chain.proceed(chain.request())
                } finally {
                    total.decrementAndGet()
                    hostCounter.decrementAndGet()
                }
            }
            .build()

        // Distinct host NAMES: maxRequestsPerHost keys on host, not host:port.
        val hostOne = one.url("/").newBuilder().host("127.0.0.1").build()
        val hostTwo = two.url("/").newBuilder().host("localhost").build()
        val latch = CountDownLatch(16)
        val failures = AtomicInteger()

        fun fire(url: HttpUrl) {
            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failures.incrementAndGet()
                    latch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { it.body?.string() }
                    latch.countDown()
                }
            })
        }

        repeat(8) { i ->
            fire(hostOne.newBuilder().addPathSegment("a$i.jpg").build())
            fire(hostTwo.newBuilder().addPathSegment("b$i.jpg").build())
        }

        assertTrue("requests did not finish", latch.await(20, TimeUnit.SECONDS))
        assertEquals("no transport failures", 0, failures.get())
        assertEquals(PosterImages.MAX_REQUESTS, client.dispatcher.maxRequests)
        assertEquals(PosterImages.MAX_REQUESTS_PER_HOST, client.dispatcher.maxRequestsPerHost)
        assertTrue("total concurrency was ${maxTotal.get()}", maxTotal.get() <= 6)
        assertTrue("total concurrency too low: ${maxTotal.get()}", maxTotal.get() >= 2)
        assertTrue("127.0.0.1 concurrency ${maxPerHost["127.0.0.1"]?.get()}", (maxPerHost["127.0.0.1"]?.get() ?: 0) <= 3)
        assertTrue("localhost concurrency ${maxPerHost["localhost"]?.get()}", (maxPerHost["localhost"]?.get() ?: 0) <= 3)
        assertTrue("per-host limit was not exercised", (maxPerHost["localhost"]?.get() ?: 0) >= 2)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PosterRequestFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun resetNetworkFlag() {
        PosterNetwork.isOnline = true
    }

    @Test
    fun requestUsesDisplaySizeAndExplicitKeysNeverOriginal() {
        val url = "https://image.tmdb.org/t/p/original/abc.jpg"
        val request = PosterRequests.request(
            context = context,
            url = url,
            widthPx = 372,
            heightPx = 558
        )
        assertEquals(Size(372, 558), runBlocking { request.sizeResolver.size() })
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", request.diskCacheKey)
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", request.memoryCacheKey?.key)
        assertEquals(CachePolicy.ENABLED, request.networkCachePolicy)
    }

    @Test
    fun offlineRequestIsCacheOnly() {
        PosterNetwork.isOnline = false
        val request = PosterRequests.request(
            context = context,
            url = "https://cdn.example/a.jpg"
        )
        assertEquals(CachePolicy.DISABLED, request.networkCachePolicy)
    }

    @Test
    fun seenGuardDeduplicatesAndIsBounded() {
        PosterRequests.clearSeenForTest()
        assertTrue(PosterRequests.markSeen("https://cdn.example/dup.jpg"))
        assertTrue(!PosterRequests.markSeen("https://cdn.example/dup.jpg"))
        assertTrue(PosterRequests.wasSeen("https://cdn.example/dup.jpg"))

        repeat(600) { i ->
            PosterRequests.markSeen("https://cdn.example/$i.jpg")
        }
        // The set is capped; the newest entries are still remembered.
        assertTrue(PosterRequests.wasSeen("https://cdn.example/599.jpg"))
        PosterRequests.clearSeenForTest()
    }
}
