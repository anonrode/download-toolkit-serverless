package com.anonrode.downloader.data.net

import com.anonrode.downloader.ui.components.isChallengePage
import com.anonrode.downloader.ui.components.isValidContentPage
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
        val headers = mapOf(
            "Server" to "cloudflare",
            "cf-mitigated" to "challenge",
            "cf-ray" to "8d94bc72a1-DFW"
        )
        // Scoped host: an explicit cf-mitigated challenge is solved.
        assertTrue(
            HttpClient.isCloudflareChallenge(
                mockResponse(403, headers, url = "https://nepu.gd/watch/tv/5926")
            )
        )
        // Outside the solver scope the WEBVIEW SOLVER IS NOT INVOKED AT ALL —
        // a working site must never pay the solve tax, regardless of headers.
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(403, headers)))
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
            body = html,
            url = "https://asianc.id/search?keyword=romance"
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
            body = html,
            url = "https://anitaku.pe/home"
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
    fun isChallenge_scopedToSolverHosts() {
        // Genuine challenge markers on OUT-OF-SCOPE hosts never fire the solver.
        // dood embeds both serve real 403 challenges AND carry meta-refresh in
        // their normal 200 template; nepu watch pages carry meta-refresh on a
        // plain 200. Dood was deliberately left out of the scope (it is a
        // locker terminal, not one of the four provider hosts), so all of
        // these must stay FALSE and cost zero solve time.
        val htmlHeaders = mapOf("Content-Type" to "text/html; charset=UTF-8")
        val metaRefresh = "<html><head><meta http-equiv=\"refresh\" content=\"2;url=/index.php\"></head></html>"
        val justAMoment = "<html><head><title>Just a moment...</title></head><body>cf-challenge</body></html>"

        // nepu belongs to the scope, but a 200 page is still never a challenge.
        assertFalse(
            HttpClient.isCloudflareChallenge(
                mockResponse(200, htmlHeaders, metaRefresh, url = "https://nepu.gd/watch/tv/5926")
            )
        )
        // real markers, wrong host: no solve.
        assertFalse(
            HttpClient.isCloudflareChallenge(
                mockResponse(403, htmlHeaders, justAMoment, url = "https://dood.wf/e/i2kabsqjuwc2")
            )
        )
        assertFalse(
            HttpClient.isCloudflareChallenge(
                mockResponse(403, htmlHeaders, justAMoment, url = "https://www.naijavault.com/dl-10d565d1/")
            )
        )
        // subdomains of scoped root domains ARE in scope.
        assertTrue(
            HttpClient.isCloudflareChallenge(
                mockResponse(403, htmlHeaders, justAMoment, url = "https://video.nepu.gd/watch/tv/5926")
            )
        )
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

    @Test
    fun extractToken_detectsCompanionCookies() {
        val litespeedCookie = "dv1ok=dv_val_12345; Path=/;"
        val dvResult = CloudflareSolver.extractToken(litespeedCookie)
        assertEquals(Pair("dv1ok", "dv_val_12345"), dvResult)

        val bmCookie = "__cf_bm=bm_tok_67890; Path=/;"
        val bmResult = CloudflareSolver.extractToken(bmCookie)
        assertEquals(Pair("__cf_bm", "bm_tok_67890"), bmResult)

        val phpCookie = "PHPSESSID=sess_php_abcdef; Path=/;"
        val phpResult = CloudflareSolver.extractToken(phpCookie)
        assertEquals(Pair("PHPSESSID", "sess_php_abcdef"), phpResult)

        val sessCookie = "session=sess_val_9999; Path=/;"
        val sessResult = CloudflareSolver.extractToken(sessCookie)
        assertEquals(Pair("session", "sess_val_9999"), sessResult)

        val tokCookie = "token=auth_tok_5555; Path=/;"
        val tokResult = CloudflareSolver.extractToken(tokCookie)
        assertEquals(Pair("token", "auth_tok_5555"), tokResult)

        // Word-boundary safety: session_id or csrf_token must NOT match session or token
        val fakeSessionCookie = "session_id=abcdef; theme=dark"
        assertNull(CloudflareSolver.extractToken(fakeSessionCookie))

        val fakeTokenCookie = "csrf_token=xyz; theme=dark"
        assertNull(CloudflareSolver.extractToken(fakeTokenCookie))
    }

    @Test
    fun isChallenge_statusCodes_403and503Only_restoresPrePushContract() {
        val challengeHtml = "<html><head><title>Just a moment...</title></head><body>Verifying</body></html>"
        val htmlHeaders = mapOf("Content-Type" to "text/html; charset=UTF-8")
        val scoped = "https://asianc.id/mystic-nine-2026-episode-1.html"

        // Only 403 and 503 can be a WebView challenge.
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, challengeHtml, url = scoped)))
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(503, htmlHeaders, challengeHtml, url = scoped)))

        // The 11ec62e expansion taught the solver to attack working pages:
        // 200s are normal content, 429 is rate-limit backoff, 451 is an
        // unsolvable legal block. None of them may ever reach the solver.
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(429, htmlHeaders, challengeHtml, url = scoped)))
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(451, htmlHeaders, challengeHtml, url = scoped)))
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(200, htmlHeaders, challengeHtml, url = scoped)))

        // Non-challenge status codes do NOT trigger even with challenge markers
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(404, htmlHeaders, challengeHtml, url = scoped)))
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(500, htmlHeaders, challengeHtml, url = scoped)))
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(302, htmlHeaders, challengeHtml, url = scoped)))

        // 200 with normal HTML content does NOT trigger
        val normalHtml = "<html><head><title>Watch Movie</title></head><body>Welcome to the movie stream</body></html>"
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(200, htmlHeaders, normalHtml, url = scoped)))

        // 200 with normal JSON does NOT trigger
        val jsonHeaders = mapOf("Content-Type" to "application/json")
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(200, jsonHeaders, """{"status":"ok"}""")))
    }

    @Test
    fun isCloudflareChallenge_16kbPeekReachesDeepSignatures() {
        val htmlHeaders = mapOf("Content-Type" to "text/html; charset=UTF-8")
        val scoped = "https://nepu.gd/watch/tv/5926"

        // Signature placed at offset ~10,000 bytes (beyond old 4KB limit, within 16KB)
        val padding10k = " ".repeat(10_000)
        val deepChallengeHtml = "<html><head><title>Checking</title></head><body>${padding10k}<div class='cf-challenge'></div></body></html>"
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, deepChallengeHtml, url = scoped)))

        // Signature placed at offset ~20,000 bytes (beyond 16KB limit)
        val padding20k = " ".repeat(20_000)
        val tooDeepChallengeHtml = "<html><head><title>Checking</title></head><body>${padding20k}<div class='cf-challenge'></div></body></html>"
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, tooDeepChallengeHtml, url = scoped)))
    }

    @Test
    fun isCloudflareChallenge_liteSpeedSignatures() {
        val htmlHeaders = mapOf("Content-Type" to "text/html; charset=UTF-8")
        val scoped = "https://nepu.gd/api/search?q=test"

        // Quick check (nepu/LiteSpeed bot check) — 403/503 only.
        val quickCheckHtml = "<html><head><title>Quick check</title></head><body>Anti-DDoS</body></html>"
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, quickCheckHtml, url = scoped)))
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(503, htmlHeaders, quickCheckHtml, url = scoped)))

        // dv1ok cookie seeding script — 403/503 only.
        val dv1okHtml = "<html><body><script>document.cookie='dv1ok=active; path=/';</script></body></html>"
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, dv1okHtml, url = scoped)))

        // meta http-equiv="refresh" is a common NON-challenge template (dood
        // countdowns, nepu watch pages): removed from the marker set entirely.
        val metaRefreshDouble = "<html><head><meta http-equiv=\"refresh\" content=\"2;url=/index.php\"></head></html>"
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, metaRefreshDouble, url = scoped)))
        val metaRefreshSingle = "<html><head><meta http-equiv='refresh' content='2;url=/index.php'></head></html>"
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, metaRefreshSingle, url = scoped)))

        // A quick-check page served with 200 is a working page, not a challenge.
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(200, htmlHeaders, quickCheckHtml, url = scoped)))
    }

    @Test
    fun isCloudflareChallenge_ddosGuardSignatures() {
        val htmlHeaders = mapOf("Content-Type" to "text/html; charset=UTF-8")
        val scoped = "https://asianc.id/mystic-nine-2026-episode-1.html"

        val ddosGuardHtml = "<html><body><div id='ddos-guard'>DDOS Protection</div></body></html>"
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, ddosGuardHtml, url = scoped)))

        val checkingBrowserHtml = "<html><body><h1>Checking your browser</h1><p>Please wait...</p></body></html>"
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(503, htmlHeaders, checkingBrowserHtml, url = scoped)))
    }

    @Test
    fun isCloudflareChallenge_turnstileModernSignatures() {
        val htmlHeaders = mapOf("Content-Type" to "text/html; charset=UTF-8")
        val scoped = "https://anitaku.pe/home"

        val turnstileHumanHtml = "<html><body><p>Verifying you are human. This may take a few seconds.</p></body></html>"
        assertTrue(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, turnstileHumanHtml, url = scoped)))

        // "Performance & security by Cloudflare" + "Ray ID" text appears in
        // error pages AND some footers of working pages: 11ec62e made the pair
        // a challenge trigger and every such page paid a solve round. Removed.
        val cfRayHtml = "<html><body><p>Performance & security by Cloudflare</p><p>Cloudflare Ray ID: 8d94bc72a1</p></body></html>"
        assertFalse(HttpClient.isCloudflareChallenge(mockResponse(403, htmlHeaders, cfRayHtml, url = scoped)))
    }

    @Test
    fun universalCookieBanking_banksAllCookiesForRootDomain() {
        val rawHeader = "dv1ok=litespeed_ok; session=active_sess; __cf_bm=cfbm_tok; PHPSESSID=php_123; token=sec_tok"
        val host = "sub.vidhide.com"

        CloudflareSolver.bankCookiesFromHeader(rawHeader, host)

        // All companion cookies banked for apex root domain "vidhide.com"
        assertEquals("litespeed_ok", HttpClient.cookieValue("dv1ok", "vidhide.com"))
        assertEquals("active_sess", HttpClient.cookieValue("session", "vidhide.com"))
        assertEquals("cfbm_tok", HttpClient.cookieValue("__cf_bm", "vidhide.com"))
        assertEquals("php_123", HttpClient.cookieValue("PHPSESSID", "vidhide.com"))
        assertEquals("sec_tok", HttpClient.cookieValue("token", "vidhide.com"))
    }

    @Test
    fun dualCompletionDetection_challengeMarkers() {
        assertTrue(isChallengePage("Just a moment...", "https://streamwish.to/"))
        assertTrue(isChallengePage("Attention Required! | Cloudflare", "https://streamwish.to/"))
        assertTrue(isChallengePage("DDOS-GUARD", "https://site.com/"))
        assertTrue(isChallengePage("Quick check", "https://site.com/"))
        assertTrue(isChallengePage("Verifying you are human", "https://site.com/"))
        assertTrue(isChallengePage("Watch Movie", "https://streamwish.to/cdn-cgi/challenge-platform/h/b/flow"))

        assertFalse(isChallengePage("Watch Big Movie", "https://streamwish.to/e/12345"))

        assertTrue(isValidContentPage("Watch Big Movie", "https://streamwish.to/e/12345"))
        assertFalse(isValidContentPage("Just a moment...", "https://streamwish.to/e/12345"))
        assertFalse(isValidContentPage("", "https://streamwish.to/e/12345"))
        assertFalse(isValidContentPage("about:blank", "about:blank"))
    }
}
