package com.anonrode.downloader.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM matrix tests for the SSRF floor + terminal-validation logic (S0).
 * Everything here is the pure half of HttpClient (top-level functions per the
 * isTlsChainFailure convention) plus the DNS-free isSafeTarget pre-filter —
 * no OkHttp client is ever constructed, so these run in plain unit tests.
 * The live paths are covered by the probe harness.
 */
class HttpClientSafetyTest {

    // ------------------------------------------------ isBlockedAddress matrix

    private fun blocked(host: String): Boolean =
        isBlockedAddress(java.net.InetAddress.getByName(host))

    @Test
    fun blocked_ipv4PrivateRanges() {
        for (h in listOf(
            "127.0.0.1", "127.9.9.9", "10.0.0.5", "10.255.255.255",
            "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.99.99",
            "169.254.169.254", // cloud metadata
            "0.0.0.0", "0.1.2.3", // any-local + "this network"
            "100.64.0.1", "100.127.255.255", // CGNAT 100.64/10
            "255.255.255.255" // limited broadcast — NOT covered by JDK multicast
        )) assertTrue("$h must be blocked", blocked(h))
    }

    @Test
    fun blocked_publicIpv4Allowed() {
        for (h in listOf(
            "8.8.8.8", "1.1.1.1", "80.82.65.46", // nkiri's IP base — must work
            "172.15.255.255", "172.32.0.1", // just outside 172.16/12
            "192.169.1.1", "100.128.0.1", "169.253.0.1" // just outside other ranges
        )) assertFalse("$h must be allowed", blocked(h))
    }

    @Test
    fun blocked_ipv6LocalAndWrappers() {
        assertTrue(blocked("::1"))
        assertTrue(blocked("::"))
        assertTrue(blocked("fe80::1")) // link-local
        assertTrue(blocked("ff02::1")) // multicast
        assertTrue(blocked("2002:7f00:1::")) // 6to4 embedding 127.0.1.0 (loopback /8)
        assertTrue(blocked("2002:c0a8:1::")) // 6to4 embedding 192.168.0.1
        // Teredo with client IPv4 = ~0x80FFFFFE = 127.0.0.1:
        assertTrue(blocked("2001:0000:0000:0000:0000:0000:80ff:fffe"))
        assertTrue(blocked("::ffff:10.1.2.3")) // v4-mapped site-local
        assertTrue(blocked("::10.1.2.3")) // obsolete v4-compat form, same unwrap
        assertFalse(blocked("2606:4700:4700::1111")) // Cloudflare public
        assertFalse(blocked("2001:4860:4860::8888")) // Google public
        assertFalse(blocked("2002:0808:0808::")) // 6to4 embedding 8.8.8.8
    }

    // --------------------------------------------------------- isSafeTarget

    @Test
    fun safeTarget_schemeAndPortRules() {
        assertTrue(HttpClient.isSafeTarget("https://naijavault.com/x"))
        assertTrue(HttpClient.isSafeTarget("http://80.82.65.46/x")) // public IP literal
        assertTrue(HttpClient.isSafeTarget("https://cdn.example.com:8443/f.mkv"))
        assertFalse(HttpClient.isSafeTarget("ftp://example.com/x"))
        assertFalse(HttpClient.isSafeTarget("file:///etc/passwd"))
        assertFalse(HttpClient.isSafeTarget("data:text/html,<script>"))
        assertFalse(HttpClient.isSafeTarget("not a url"))
        assertFalse(HttpClient.isSafeTarget(""))
        assertFalse(HttpClient.isSafeTarget("https://router.local:22/"))
        assertFalse(HttpClient.isSafeTarget("http://host:53/x"))
        assertFalse(HttpClient.isSafeTarget("http://host:3389/x"))
    }

    @Test
    fun safeTarget_privateIpLiteralsRefused() {
        assertFalse(HttpClient.isSafeTarget("http://127.0.0.1/dl/x"))
        assertFalse(HttpClient.isSafeTarget("http://192.168.0.1/admin"))
        assertFalse(HttpClient.isSafeTarget("http://169.254.169.254/latest/meta-data/"))
        assertFalse(HttpClient.isSafeTarget("http://10.0.0.1/"))
        // userinfo trick: HttpUrl must split host from credentials (documented
        // OkHttp behavior, asserted here as a load-bearing regression guard):
        assertFalse(HttpClient.isSafeTarget("http://evil:pw@127.0.0.1/x"))
    }

    @Test
    fun safeTarget_oddIpv4SpellingsRefused() {
        // Decimal single-token (== 127.0.0.1 under inet_aton rules): refused
        // whether Java parses it to loopback or refuses to parse it — both
        // paths return false by design.
        assertFalse(HttpClient.isSafeTarget("http://2130706433/x"))
        // Hex form: contains letters → fast path defers (hostnames get no
        // pre-filter) — must NOT be accepted as obviously safe either way it
        // flows to DNS where safeDns decides. Assert the conservative truth:
        // it parses and the guard does not crash; the connect-time layer owns it.
        assertTrue(HttpClient.isSafeTarget("http://0x7f.1/x"))
    }

    @Test
    fun safeTarget_hostnamesDefertoSafeDns() {
        // localhost can't be caught pre-DNS without a lookup; safeDns owns it.
        assertTrue(HttpClient.isSafeTarget("http://localhost:8080/x"))
    }

    // ---------------------------------------------------------- parsedHost
    // The A-5 terminal gate compares recipe-allowlist hosts against THIS view
    // of the host, not the lenient safeHost() string-split: HttpUrl.kt
    // (parent-4.12.0, authority loop) ends the host at  @ / \ ? #  — the
    // forgeries below pass safeHost() but must never pass the gate.

    @Test
    fun parsedHost_agreesWithTheFetcher_onHostIdentity() {
        assertEquals("vikingfile.com", HttpClient.parsedHost("https://vikingfile.com/dl/1"))
        assertEquals("vikingfile.com", HttpClient.parsedHost("https://VIKINGFILE.COM:8443/dl/1"))
        assertEquals("vikingfile.com", HttpClient.parsedHost("https://vikingfile.com/dl?x=1#f"))
        // toCanonicalHost (hostnames.kt) normalizes IPv6 WITHOUT brackets:
        assertEquals("::1", HttpClient.parsedHost("http://[::1]/x"))
        // userinfo+port forgery: safeHost() answers "vikingfile.com"; the
        // request actually goes to evil.com — parsedHost tells the truth:
        assertEquals("evil.com", HttpClient.parsedHost("https://vikingfile.com:443@evil.com/f"))
        // backslash ends the authority (WHATWG): "evil\.downloadwella.com" is
        // host "evil", NOT a downloadwella.com subdomain:
        assertEquals("evil", HttpClient.parsedHost("https://evil\\.downloadwella.com/f"))
        // unparseable -> null (the gate treats null as REFUSE):
        assertNull(HttpClient.parsedHost("not a url"))
        assertNull(HttpClient.parsedHost(""))
        assertNull(HttpClient.parsedHost("ftp://example.com/x"))
        assertNull(HttpClient.parsedHost("https://[bad"))
    }

    // ------------------------------------------------ safeUrl (v6 literals)
    // The blanket "[" -> %5B encode used to break every IPv6 URL into
    // "%5Bfd00::5%5D", which HttpUrl.parse then REFUSED — killing the v6
    // branch of isSafeTarget and making bracketed LAN/NAS targets
    // unreachable. encodeBaseKeepingIpv6Literal keeps the literal intact.

    @Test
    fun safeUrl_keepsIpv6LiteralAndEncodesStrayBrackets() {
        assertEquals("http://[fd00::5]:8080/x%20y.mkv",
            HttpClient.safeUrl("http://[fd00::5]:8080/x y.mkv"))
        // with the preserved literal, parsedHost now WORKS (used to be null):
        assertEquals("fd00::5", HttpClient.parsedHost("http://[fd00::5]:8080/x"))
        // hostname URLs keep the old stray-bracket/space encoding:
        assertEquals("https://ex.com/a%5Bb%5D.mkv", HttpClient.safeUrl("https://ex.com/a[b].mkv"))
        // query part is passed through untouched (only the base is encoded):
        assertEquals("https://ex.com/f?x=1 [2]",
            HttpClient.safeUrl("https://ex.com/f?x=1 [2]"))
    }

    // ------------------------------------------ cappedText / cappedBytes caps
    // Binary consumers (ffmpeg inputs, wasm blobs) must NEVER receive a
    // truncated-and-blessed file: oversize binary bodies are REFUSED (null),
    // text bodies truncated (pages are previewable). An announced oversize is
    // refused before reading a byte of a metered connection.

    private fun fakeRes(body: okio.Buffer, announced: Long): okhttp3.Response {
        val req = okhttp3.Request.Builder().url("https://example.com/f").build()
        // Hand-rolled ResponseBody: the okhttp3.internal create/asResponseBody
        // helpers are not public API (an import of okhttp3.asResponseBody was
        // the first-ever test-compile failure of this arc). Public abstract
        // class only — no version coupling, no deprecation.
        val rb = object : okhttp3.ResponseBody() {
            override fun contentLength(): Long = announced
            override fun contentType(): okhttp3.MediaType? = null
            override fun source(): okio.Buffer = body
            override fun close() {}
        }
        return okhttp3.Response.Builder()
            .request(req)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body(rb)
            .build()
    }

    @Test
    fun cappedBytes_refusesOversize_neverTruncates() {
        val ten = okio.Buffer().write(ByteArray(10))
        // announced far over the cap: refused unread (0 bytes cross the "wire")
        assertNull(HttpClient.cappedBytes(fakeRes(ten, 6_000_000L), maxBytes = 1024))
        // no announcement, but the drained body exceeds the cap: refused
        val twenty = okio.Buffer().write(ByteArray(20))
        assertNull(HttpClient.cappedBytes(fakeRes(twenty, -1L), maxBytes = 10))
        // at the cap exactly: delivered whole
        val ten2 = okio.Buffer().write(ByteArray(10))
        assertEquals(10, HttpClient.cappedBytes(fakeRes(ten2, 10L), maxBytes = 10)?.size)
    }

    @Test
    fun cappedText_stillTruncatesPages() {
        val six = okio.Buffer().writeUtf8("abcdef")
        assertEquals("abc", HttpClient.cappedText(fakeRes(six, 6L), maxBytes = 3))
    }

    // ------------------------------------------------ acceptsTerminalResponse

    @Test
    fun terminal_directFileResponsesAccepted() {
        assertEquals(500L, acceptsTerminalResponse(200, "500", null, "video/mp4"))
        // 206 total comes from Content-Range, not Content-Length:
        assertEquals(
            159703784L,
            acceptsTerminalResponse(206, "1", "bytes 0-0/159703784", "video/matroska")
        )
        // unknown-range total falls back to the (positive) probe CL:
        assertEquals(1L, acceptsTerminalResponse(206, "1", "bytes */1", "application/octet-stream"))
        // case-insensitive Content-Type:
        assertEquals(9L, acceptsTerminalResponse(200, "9", null, "VIDEO/MP4"))
    }

    @Test
    fun terminal_nonFileResponsesRejected() {
        assertNull(acceptsTerminalResponse(200, "500", null, "text/html"))
        assertNull(acceptsTerminalResponse(200, "500", null, "application/xhtml+xml"))
        assertNull(acceptsTerminalResponse(200, "0", null, "video/mp4")) // empty file
        assertNull(acceptsTerminalResponse(200, null, null, "video/mp4")) // chunked: no size proof
        assertNull(acceptsTerminalResponse(206, "1", null, "video/mp4")) // 206 w/o Content-Range: violating
        assertNull(acceptsTerminalResponse(206, "1", "nonsense", "video/mp4")) // malformed range
        assertNull(acceptsTerminalResponse(401, "500", null, "video/mp4")) // expired token
        assertNull(acceptsTerminalResponse(403, "500", null, "video/mp4"))
        assertNull(acceptsTerminalResponse(416, "0", "bytes */0", "video/mp4")) // zero-byte file
        assertNull(acceptsTerminalResponse(302, "0", null, "text/html"))
        assertNull(acceptsTerminalResponse(500, null, null, "text/plain"))
    }
}
