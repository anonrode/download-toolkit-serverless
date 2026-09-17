package com.anonrode.downloader.data.net

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ResolverHttpCallTest {
    @Test
    fun pauseCancelsTheCallWhileTheResponseIsBeingConsumed() {
        lateinit var captured: Call
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.call()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("page".toResponseBody()).build()
        }.build()
        val request = Request.Builder().url("https://body.example/page").build()
        HttpClient.executeCancellable(client, request) { response ->
            assertFalse(captured.isCanceled())
            HttpClient.cancelInFlight()
            assertTrue(captured.isCanceled())
            assertEquals(200, response.code)
        }
    }

    @Test
    fun completedCallsAreRemovedFromTheCancellationRegistry() {
        lateinit var captured: Call
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.call()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("page".toResponseBody()).build()
        }.build()
        val request = Request.Builder().url("https://complete.example/page").build()
        assertEquals("page", HttpClient.executeCancellable(client, request) { it.body!!.string() })
        HttpClient.cancelInFlight()
        assertFalse(captured.isCanceled())
    }

    @Test
    fun rateLimitedResponseRecordsTheActualResponseOrigin() {
        val request = Request.Builder().url("https://limited.example/page").build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests").header("Retry-After", "120")
                .body("wait".toResponseBody()).build()
        }.build()
        try {
            HttpClient.executeCancellable(client, request) { fail("Must reject rate-limited response") }
            fail("Expected cooldown exception")
        } catch (error: OriginCooldownException) {
            assertEquals(request.url.toString(), error.originUrl)
            assertTrue(HttpClient.remainingCooldownMs(error.originUrl) > 0L)
            assertEquals(0L, HttpClient.remainingCooldownMs("https://isolated.example/page"))
        }
    }
}
