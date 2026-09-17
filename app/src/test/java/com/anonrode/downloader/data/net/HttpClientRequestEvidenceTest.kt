package com.anonrode.downloader.data.net

import android.app.Application
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

/** Interceptor-only fixtures: no DNS, sockets, server or media downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class HttpClientRequestEvidenceTest {
    private fun response(request: Request, code: Int = 200, body: ResponseBody = "page".toResponseBody()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").header("Retry-After", "120").body(body).build()

    private fun body(read: (Buffer, Long) -> Long, close: () -> Unit = {}): ResponseBody {
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = read.invoke(sink, byteCount)
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = close.invoke()
        }.buffer()
        return object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = source
        }
    }

    @Test
    fun postCooldownPreventsSecondDispatchAndLeavesOtherOriginsUsable() {
        for (code in listOf(429, 503)) {
            val url = "https://post-$code.example/form"
            var dispatched = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                dispatched++
                response(chain.request(), if (chain.request().url.host == "post-$code.example") code else 200)
            }.build()
            val errors = mutableListOf<Throwable>()
            val listener = HttpClient.FailureListener { _, error -> errors.add(error) }
            repeat(2) {
                assertNull(HttpClient.postFormWithClient(client, url, mapOf("q" to "test"), onFailure = listener))
            }
            assertEquals(1, dispatched)
            assertEquals(2, errors.size)
            assertTrue(errors.all { it is OriginCooldownException && it.originUrl == url })
            assertEquals("page", HttpClient.postFormWithClient(client, "https://other-post-$code.example/form", emptyMap()))
            assertEquals(2, dispatched)
        }
    }

    @Test
    fun getCooldownReportsActualRedirectOriginAndClosesUnreadBody() {
        val original = "https://redirect-evidence.example/page"
        val target = "https://redirect-limited.example/page"
        var closed = false
        val client = OkHttpClient.Builder().addInterceptor {
            response(Request.Builder().url(target).build(), 429,
                body({ _, _ -> fail("Rate limited body must not be read"); -1L }, { closed = true }))
        }.build()
        var error: Throwable? = null
        assertNull(HttpClient.getTextWithClient(client, original,
            onFailure = HttpClient.FailureListener { url, failure -> assertEquals(original, url); error = failure }))
        assertEquals(target, (error as OriginCooldownException).originUrl)
        assertTrue(closed)
        assertTrue(HttpClient.remainingCooldownMs(target) > 0L)
        assertEquals(0L, HttpClient.remainingCooldownMs(original))
        // Public overload must deliver admission evidence without dispatching.
        error = null
        assertNull(HttpClient.getText(target, onFailure = HttpClient.FailureListener { _, failure -> error = failure }))
        assertTrue(error is OriginCooldownException)
    }

    @Test
    fun networkEvidenceSurvivesAnotherRequestsDiagnosticWrite() {
        val first = UnknownHostException("first request")
        val second = IOException("second request")
        val clientA = OkHttpClient.Builder().addInterceptor { throw first }.build()
        val clientB = OkHttpClient.Builder().addInterceptor { throw second }.build()
        var observed: Throwable? = null
        var notifications = 0
        assertNull(HttpClient.getTextWithClient(clientA, "https://evidence-a.example/page",
            onFailure = HttpClient.FailureListener { _, error ->
                notifications++
                assertNull(HttpClient.getTextWithClient(clientB, "https://evidence-b.example/page"))
                observed = error
            }))
        assertEquals(1, notifications)
        assertSame(first, observed)
        assertTrue(HttpClient.lastFailure!!.contains("second request"))
    }

    @Test
    fun getBodyRetainsTagUntilCloseAndCompletedCallIsUnregistered() {
        lateinit var captured: Call
        val tag = "net-body-regression"
        var closed = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.call()
            response(chain.request(), body = body({ _, _ ->
                assertFalse(captured.isCanceled())
                HttpClient.cancelTagged(tag)
                assertTrue(captured.isCanceled())
                -1L
            }, { closed = true }))
        }.build()
        assertEquals("", HttpClient.getTextWithClient(client, "https://body-tag.example/page", tag = tag))
        assertTrue(closed)

        val completed = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.call()
            response(chain.request())
        }.build()
        assertEquals("page", HttpClient.getTextWithClient(completed, "https://body-done.example/page", tag = tag))
        HttpClient.cancelTagged(tag)
        assertFalse(captured.isCanceled())
    }

    @Test
    fun bodyFailureIsLocalEvidenceButLegacyPartialReadBehaviorIsPreserved() {
        val error = IOException("read interrupted")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), body = body({ _, _ -> throw error }))
        }.build()
        var observed: Throwable? = null
        assertNull(HttpClient.getTextWithClient(client, "https://body-evidence.example/page",
            onFailure = HttpClient.FailureListener { _, failure -> observed = failure }))
        assertSame(error, observed)
        assertEquals("", HttpClient.getTextWithClient(client, "https://body-legacy.example/page"))
    }

    @Test
    fun cancellationIsNotConvertedIntoNullOrFailureEvidence() {
        val cancellation = CancellationException("cancel fixture")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), body = body({ _, _ -> throw cancellation }))
        }.build()
        val listener = HttpClient.FailureListener { _, _ -> fail("Cancellation is not failure evidence") }
        for (post in listOf(false, true)) {
            try {
                if (post) HttpClient.postFormWithClient(client, "https://cancel-post.example/form", emptyMap(), onFailure = listener)
                else HttpClient.getTextWithClient(client, "https://cancel-get.example/page", onFailure = listener)
                fail("Cancellation must escape")
            } catch (error: CancellationException) {
                assertSame(cancellation, error)
            }
        }
    }

    @Test
    fun acceptedStatusAndAlternativeNullContractRemainIntact() {
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), 404) }.build()
        val listener = HttpClient.FailureListener { _, _ -> fail("Accepted status is not failure") }
        assertEquals("page", HttpClient.getTextWithClient(client, "https://accepted.example/page", acceptStatus = setOf(404), onFailure = listener))
        assertNull(HttpClient.getTextWithClient(client, "https://alternative.example/page"))
    }
}
