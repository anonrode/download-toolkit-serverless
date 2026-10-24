package com.anonrode.downloader.data.net

import com.anonrode.downloader.ui.components.shouldBlockSubresource
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CloudflareSolver discrimination, origin target resolution,
 * token extraction, and skeleton subresource firewall rules.
 */
class CloudflareSolverTest {

    private fun mockResponse(
        code: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        url: String = "https://example.com/page"
    ): Response {
        val req = Request.Builder().url(url).build()
        val builder = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Status $code")
            .body(body.toResponseBody())

        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    @Test
    fun isCloudflareChallenge_normal403OnCloudflareDoesNotTrigger() {
        // Normal 403 from expired token or hotlink block with Server: cloudflare + cf-ray
        val res = mockResponse(
            code = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "cf-ray" to "8d94bc72a1-DFW",
                "Content-Type" to "video/mp4"
            ),
            body = "Access Denied"
        )
        assertFalse(HttpClient.isCloudflareChallenge(res))
    }

    @Test
    fun isCloudflareChallenge_cfMitigatedHeaderTriggers() {
        val res = mockResponse(
            code = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "cf-mitigated" to "challenge",
                "cf-ray" to "8d94bc72a1-DFW"
            )
        )
        assertTrue(HttpClient.isCloudflareChallenge(res))
    }

    @Test
    fun isCloudflareChallenge_htmlJustAMomentTriggers() {
        val html = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>Checking...</body></html>"
        val res = mockResponse(
            code = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "Content-Type" to "text/html; charset=UTF-8"
            ),
            body = html
        )
        assertTrue(HttpClient.isCloudflareChallenge(res))
    }

    @Test
    fun isCloudflareChallenge_htmlTurnstileSignatureTriggers() {
        val html = "<html><body><div id='cf-turnstile' class='cf-challenge'></div></body></html>"
        val res = mockResponse(
            code = 403,
            headers = mapOf(
                "Content-Type" to "text/html; charset=UTF-8"
            ),
            body = html
        )
        assertTrue(HttpClient.isCloudflareChallenge(res))
    }

    @Test
    fun isCloudflareChallenge_htmlGenericForbiddenDoesNotTrigger() {
        val html = "<html><body><h1>403 Forbidden</h1><p>Directory listing denied.</p></body></html>"
        val res = mockResponse(
            code = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "cf-ray" to "8d94bc72a1-DFW",
                "Content-Type" to "text/html; charset=UTF-8"
            ),
            body = html
        )
        assertFalse(HttpClient.isCloudflareChallenge(res))
    }

    @Test
    fun isCloudflareChallenge_status200Or404DoesNotTrigger() {
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(200)))
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(404)))
    }

    @Test
    fun canAttemptSolve_respectsTagsAndForegroundState() {
        CloudflareSolver.isAppInForeground = true
        assertTrue(CloudflareSolver.canAttemptSolve(null))
        assertTrue(CloudflareSolver.canAttemptSolve("search"))
        assertTrue(CloudflareSolver.canAttemptSolve("resolve"))

        assertFalse(CloudflareSolver.canAttemptSolve("verify"))
        assertFalse(CloudflareSolver.canAttemptSolve("trending"))
        assertFalse(CloudflareSolver.canAttemptSolve("warmup"))
        assertFalse(CloudflareSolver.canAttemptSolve("hls-estimate"))
        assertFalse(CloudflareSolver.canAttemptSolve("background"))

        CloudflareSolver.isAppInForeground = false
        assertFalse(CloudflareSolver.canAttemptSolve(null))
        assertFalse(CloudflareSolver.canAttemptSolve("search"))

        // Reset
        CloudflareSolver.isAppInForeground = true
    }

    @Test
    fun resolveChallengeTargetUrl_normalizesEndpoints() {
        // Normal movie page stays intact
        val normalUrl = "https://dl.plutomovies.com/83902b/movie-mkv"
        assertEquals(normalUrl, CloudflareSolver.resolveChallengeTargetUrl(normalUrl, null))

        // AJAX POST endpoint with referer resolves to referer
        val ajaxUrl = "https://gogoanime.or.at/wp-admin/admin-ajax.php"
        val referer = "https://gogoanime.or.at/search.html"
        assertEquals(referer, CloudflareSolver.resolveChallengeTargetUrl(ajaxUrl, referer))

        // AJAX POST endpoint without referer resolves to root origin
        assertEquals("https://gogoanime.or.at/", CloudflareSolver.resolveChallengeTargetUrl(ajaxUrl, null))

        // API endpoint with referer resolves to referer
        val apiUrl = "https://dl.plutomovies.com/api/v1/dl-link/123"
        val showPage = "https://dl.plutomovies.com/123/show-mkv"
        assertEquals(showPage, CloudflareSolver.resolveChallengeTargetUrl(apiUrl, showPage))
    }

    @Test
    fun rootDomain_extractsApexDomain() {
        assertEquals("plutomovies.com", CloudflareSolver.rootDomain("dl.plutomovies.com"))
        assertEquals("plutomovies.com", CloudflareSolver.rootDomain("https://dl.plutomovies.com/file/123"))
        assertEquals("nepu.gd", CloudflareSolver.rootDomain("video.nepu.gd"))
        assertEquals("nepu.gd", CloudflareSolver.rootDomain("nepu.gd"))
    }

    @Test
    fun extractToken_detectsClearanceAndHvp() {
        val cfCookie = "cf_clearance=tok_test_12345; Path=/; Domain=.plutomovies.com"
        val cfResult = CloudflareSolver.extractToken(cfCookie)
        assertEquals(Pair("cf_clearance", "tok_test_12345"), cfResult)

        val hvpCookie = "hvp=nonce_test_67890; Path=/;"
        val hvpResult = CloudflareSolver.extractToken(hvpCookie)
        assertEquals(Pair("hvp", "nonce_test_67890"), hvpResult)

        val dummyCookie = "session_id=abcdef; theme=dark"
        assertNull(CloudflareSolver.extractToken(dummyCookie))
        assertNull(CloudflareSolver.extractToken(null))
    }

    @Test
    fun shouldBlockSubresource_filtersCorrectly() {
        // Whitelisted cloudflare engines
        assertFalse(shouldBlockSubresource("https", "challenges.cloudflare.com", "/turnstile/v0/api.js"))
        assertFalse(shouldBlockSubresource("https", "static.cloudflareinsights.com", "/beacon.min.js"))
        assertFalse(shouldBlockSubresource("blob", "challenges.cloudflare.com", ""))
        assertFalse(shouldBlockSubresource("data", "", ""))

        // Blocked heavy media and styles
        assertTrue(shouldBlockSubresource("https", "plutomovies.com", "/assets/banner.png"))
        assertTrue(shouldBlockSubresource("https", "plutomovies.com", "/styles/main.css"))
        assertTrue(shouldBlockSubresource("https", "plutomovies.com", "/fonts/inter.woff2"))
        assertTrue(shouldBlockSubresource("https", "plutomovies.com", "/video/preview.mp4"))

        // Blocked 3rd-party ad trackers
        assertTrue(shouldBlockSubresource("https", "google-analytics.com", "/analytics.js"))
        assertTrue(shouldBlockSubresource("https", "www.googletagmanager.com", "/gtm.js"))
    }

    @Test
    fun shouldAllowSubresource_nonHttpSchemesAllowedFirst() {
        // Non-http schemes must return true FIRST for Turnstile web workers
        assertTrue(CloudflareSolver.shouldAllowSubresource("blob:https://challenges.cloudflare.com/123", "plutomovies.com"))
        assertTrue(CloudflareSolver.shouldAllowSubresource("data:image/svg+xml;base64,abc", "plutomovies.com"))
        assertTrue(CloudflareSolver.shouldAllowSubresource("about:blank", "plutomovies.com"))

        // Whitelisted cloudflare engines
        assertTrue(CloudflareSolver.shouldAllowSubresource("https://challenges.cloudflare.com/turnstile/v0/api.js", "plutomovies.com"))
        assertTrue(CloudflareSolver.shouldAllowSubresource("https://static.cloudflareinsights.com/beacon.min.js", "plutomovies.com"))

        // Dropped media, css, and tracking scripts
        assertFalse(CloudflareSolver.shouldAllowSubresource("https://plutomovies.com/assets/style.css", "plutomovies.com"))
        assertFalse(CloudflareSolver.shouldAllowSubresource("https://plutomovies.com/hero.jpg", "plutomovies.com"))
        assertFalse(CloudflareSolver.shouldAllowSubresource("https://google-analytics.com/ga.js", "plutomovies.com"))
    }
}
