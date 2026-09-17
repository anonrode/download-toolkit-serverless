package com.anonrode.downloader.data.net

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.util.Collections
import java.util.concurrent.TimeUnit

object HttpClient {
    const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val pool = okhttp3.ConnectionPool(64, 5, TimeUnit.MINUTES)
    private val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 128
        maxRequestsPerHost = 32
    }

    /**
     * In-memory cookie store shared by every request.
     *
     * This is load-bearing, not a nicety. OkHttp defaults to CookieJar.NO_COOKIES,
     * so every Set-Cookie was being dropped -- and the multi-step locker
     * handshakes are session-based. loadedfiles hands out a fresh `?pt=` token on
     * each hop and only issues the final 302 to the CDN once it recognises the
     * session; without cookies the chain loops forever and the resolver reports
     * "Could not crack stream link". Verified against the live host: identical
     * requests fail without a cookie jar and reach the CDN in 3 hops with one.
     *
     * The Python monolith got this for free because every resolver is handed a
     * `requests.Session()`, which carries cookies. This restores parity.
     *
     * One flat list filtered by Cookie.matches() rather than a host->list map:
     * matches() honours domain/path/secure rules, so a cookie set on
     * `.example.com` correctly applies to `www.example.com` (a host-keyed map
     * would miss it).
     */
    private val cookies: MutableList<Cookie> = Collections.synchronizedList(mutableListOf())
    private const val MAX_COOKIES = 400

    private val sessionCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookieList: List<Cookie>) {
            if (cookieList.isEmpty()) return
            synchronized(cookies) {
                for (c in cookieList) {
                    // Replace same name+domain+path instead of appending duplicates.
                    cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                    cookies.add(c)
                }
                // Bound the store so a long session can't grow without limit.
                while (cookies.size > MAX_COOKIES) cookies.removeAt(0)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            synchronized(cookies) {
                cookies.removeAll { it.expiresAt < now }
                return cookies.filter { it.matches(url) }
            }
        }
    }

    /** Drop all session cookies (e.g. when a handshake must start clean). */
    fun clearCookies() {
        synchronized(cookies) { cookies.clear() }
    }

    /**
     * Value of a stored session cookie by name (most-recent match wins), for
     * callers that must echo it in a header (Instagram's logged-out GraphQL
     * rejects a POST whose X-CSRFToken does not equal the csrftoken cookie the
     * jar auto-sends — see InstagramPhotoMuxer). Purely a read; no new surface
     * for the resolver plane.
     */
    fun cookieValue(name: String, host: String? = null): String? {
        fun domainMatches(cookieDomain: String, reqHost: String): Boolean {
            val bare = cookieDomain.removePrefix(".")
            return reqHost == bare || reqHost.endsWith(".$bare") || reqHost == cookieDomain
        }
        synchronized(cookies) {
            for (i in cookies.indices.reversed()) {
                val c = cookies[i]
                if (c.name != name) continue
                if (host != null && !domainMatches(c.domain, host)) continue
                return c.value
            }
        }
        return null
    }

    /**
     * Why the most recent getText() returned null. Every resolver funnels through
     * getText and swallows failures into null, so all 25 of them produce the same
     * opaque "Could not crack stream link". Recording the real cause (HTTP code or
     * exception) makes the next failure diagnosable instead of guesswork.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /** In-memory cache for DoH-resolved IP addresses to avoid redundant HTTPS lookups */
    private val dohCache = java.util.concurrent.ConcurrentHashMap<String, List<java.net.InetAddress>>()

    private val bootstrapDohClient by lazy {
        OkHttpClient.Builder()
            .dns(okhttp3.Dns.SYSTEM)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    // Fixed workers + bounded queue: timed-out native lookups retain their
    // worker until they really return, even when getaddrinfo ignores interrupts.
    private val dnsScheduler by lazy {
        BoundedDnsScheduler(
            primary = { okhttp3.Dns.SYSTEM.lookup(it) },
            fallback = { dohFallback(it) }
        )
    }

    private val retryAfterCooldown = RetryAfterCooldown()

    /** Optional coroutine admission wait; existing synchronous APIs never sleep. */
    suspend fun awaitOriginCooldown(url: String) {
        retryAfterCooldown.waitUntilUsable(safeUrl(url))
    }

    private fun admitRequest(url: String) {
        val remaining = retryAfterCooldown.remainingMs(safeUrl(url))
        if (remaining > 0) throw OriginCooldownException(safeUrl(url))
    }

    private fun recordCooldown(response: Response) {
        // Attribute to the request that actually received the response, including
        // redirects, not the initial URL or the global diagnostic lastFailure.
        val code = response.code
        if (code == 429 || code == 503) {
            retryAfterCooldown.record(response.request.url.toString(), response.header("Retry-After"))
        }
    }

    /**
     * Hybrid Smart-DNS:
     * 1. Primary: System DNS (fast, geo-routes CDN IPs for max video speeds).
     *    Capped at 3 s — on some Nigerian ISPs the carrier DNS server goes
     *    unresponsive (not NXDOMAIN, just silent), blocking getaddrinfo for up
     *    to 75 s before timing out. Three seconds is enough for a healthy
     *    system DNS; anything slower falls through to DoH immediately.
     * 2. Fallback: Google Public DNS over HTTPS (bypasses ISP DNS poisoning /
     *    censorship on blocked scraper sites).
     */
    private val hybridDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            dohCache[hostname]?.let { return it }
            // Bounded system-DNS race: fixed workers + bounded queue, DoH on
            // timeout/saturation/interrupt (see BoundedDnsScheduler).
            return dnsScheduler.lookup(hostname)
        }
    }

    private fun dohFallback(hostname: String): List<java.net.InetAddress> {
        return try {
            val encoded = java.net.URLEncoder.encode(hostname, "UTF-8")
            val dohUrl = "https://dns.google/resolve?name=$encoded&type=A"
            val req = Request.Builder()
                .url(dohUrl)
                .header("User-Agent", DEFAULT_UA)
                .build()
            bootstrapDohClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw java.net.UnknownHostException(hostname)
                val body = res.body?.string() ?: throw java.net.UnknownHostException(hostname)
                val json = org.json.JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: throw java.net.UnknownHostException(hostname)
                val addrs = mutableListOf<java.net.InetAddress>()
                for (i in 0 until answers.length()) {
                    val data = answers.getJSONObject(i).optString("data")
                    if (data.isNotBlank() && !data.contains(":")) {
                        addrs.add(java.net.InetAddress.getByName(data))
                    }
                }
                if (addrs.isEmpty()) throw java.net.UnknownHostException(hostname)
                dohCache[hostname] = addrs
                addrs
            }
        } catch (e: java.net.UnknownHostException) {
            throw e
        } catch (_: Exception) {
            throw java.net.UnknownHostException(hostname)
        }
    }


    /**
     * SSRF floor for every PAGE/RESOLVER fetch: after the hybrid resolver, drop
     * any address that points into private/loopback/link-local/metadata space.
     * A hostile or compromised page we merely fetch can declare a URL that names
     * the user's router (192.168.x), the LAN, or a cloud metadata endpoint
     * (169.254.169.254); making DNS refuse those targets is the one check that
     * cannot be bypassed by redirect chains (OkHttp re-consults Dns per connect)
     * or by odd IP spellings (they all resolve to the same InetAddress).
     * If EVERY address is private the host is unresolvable to us by design.
     */
    private val safeDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val resolved = hybridDns.lookup(hostname)
            val safe = resolved.filterNot { isBlockedAddress(it) }
            if (safe.isEmpty()) {
                throw java.net.UnknownHostException(
                    "$hostname resolves only to private/link-local addresses — refused (SSRF guard)"
                )
            }
            return safe
        }
    }

    private val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    private val sslSocketFactory: javax.net.ssl.SSLSocketFactory by lazy {
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        sslContext.socketFactory
    }

    val shared: OkHttpClient = OkHttpClient.Builder()
        .dns(safeDns)
        .connectionPool(pool)
        .dispatcher(dispatcher)
        .cookieJar(sessionCookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val downloadClient: OkHttpClient = shared.newBuilder()
        // The DOWNLOAD plane deliberately keeps the raw hybrid DNS: a download
        // URL is user-chosen (a shared/pasted link, a picked episode), not
        // page-injected, and users legitimately pull files from a LAN server.
        // The SSRF floor guards the page/redirect-following plane, where hosts
        // are chosen by remote content. permissiveClient derives from shared
        // and keeps safeDns — it serves resolver traffic (broken-TLS lockers).
        .dns(hybridDns)
        // HTTP/1.1 only: with the default HTTP_2 ALPN, OkHttp coalesces every
        // concurrent Range request to one host onto a SINGLE TCP connection —
        // silently defeating Turbo's multi-socket throttle bypass and making
        // the whole transfer ramp on one connection's slow start (part of the
        // user-visible 100KB → 3MB crawl). N sockets must mean N real
        // connections; aria2c behaves the same (no h2). Page/resolver traffic
        // on [shared] keeps HTTP/2.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Trust-all SSL client for locker hosts with broken TLS chains
     * (wetafiles.com omits its intermediate, kissorgrab.com serves an
     * invalid cert). Used ONLY by DownloadwellaResolver (downloadwella
     * family) and the permissive probe fallback — the shared client stays
     * strict because trust-all must never apply to every request the app
     * makes.
     */
    val permissiveClient: OkHttpClient by lazy {
        shared.newBuilder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Trust-all variant of [downloadClient] (longer streaming timeouts) for
     * the download path: when a strict probe proves the chain is broken, the
     * whole turbo job must run trust-all — mixing a permissive probe with
     * strict sockets would just re-trigger the same handshake failure on all
     * 16 segments. Only reached after [isTlsChainFailure] matched.
     */
    val permissiveDownloadClient: OkHttpClient by lazy {
        downloadClient.newBuilder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Execute a one-off probe call under the same cancellation registry as
     * [get] — pausing/cancelling a task must also kill the StreamValidator /
     * turbo probe so no straggler socket keeps draining data (v3.0.4 showed
     * traffic continuing seconds after a cancel).
     */
    fun executeRegistered(call: okhttp3.Call): okhttp3.Response {
        inFlightCalls.add(call)
        try {
            return call.execute()
        } finally {
            inFlightCalls.remove(call)
        }
    }

    fun safeResolveUri(base: String, relative: String): String {
        if (relative.startsWith("http://", ignoreCase = true) || relative.startsWith("https://", ignoreCase = true) || relative.startsWith("magnet:", ignoreCase = true)) {
            return relative
        }
        return try {
            val safeBase = safeUrl(base)
            val safeRel = relative.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
            java.net.URI(safeBase).resolve(safeRel).toString()
        } catch (_: Exception) {
            if (relative.startsWith("/")) {
                val root = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore("/")
                root + relative
            } else {
                base.trimEnd('/') + "/" + relative.trimStart('/')
            }
        }
    }

    fun safeHost(url: String, defaultHost: String = ""): String {
        return try {
            val clean = url.substringBefore('?').substringBefore('#')
            val afterProto = if (clean.contains("://")) clean.substringAfter("://") else clean
            afterProto.substringBefore('/').substringBefore(':').ifBlank { defaultHost }
        } catch (_: Exception) {
            defaultHost
        }
    }

    /**
     * The TRUE host of [url] per okhttp3.HttpUrl — the same parser that decides
     * which server a request actually connects to. [safeHost] is a lenient
     * string-split with legacy callers and MUST NOT be used for security
     * decisions: HttpUrl.kt 4.12.0 ends the host at `@ / \ ? #` and strips
     * userinfo, so "https://vikingfile.com:443@evil.com/f" really fetches
     * evil.com while safeHost() cheerfully answers "vikingfile.com". Any
     * allowlist gate must compare against THIS function (fact-checked
     * 2026-09-12 against parent-4.12.0 HttpUrl.kt parse(), authority loop).
     * Null for anything HttpUrl itself refuses.
     */
    fun parsedHost(url: String): String? {
        return try {
            parseHttpUrl(safeUrl(url))?.host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Single chokepoint for okhttp URL parsing in the security gates.
     * HttpUrl.parse is DEPRECATION-ERROR level against okhttp 4.12.0 (an
     * @Suppress("DEPRECATION") does not silence it), and the recommended
     * String.toHttpUrlOrNull extension does not resolve from this CI's Kotlin
     * setup — so we route through Request.Builder.url(), which delegates to
     * the SAME non-lenient HttpUrl parser (same userinfo/host canonicalization
     * the safety comment above relies on) and is not deprecated anywhere.
     * Malformed input throws IllegalArgumentException where parse would
     * return null; identical net semantics. Do not add raw HttpUrl.parse
     * call sites.
     */
    private fun parseHttpUrl(url: String): okhttp3.HttpUrl? = try {
        okhttp3.Request.Builder().url(url).build().url
    } catch (_: IllegalArgumentException) {
        null
    }

    fun safeUrl(url: String): String {
        if (url.isBlank()) return url
        val parts = url.split("?", limit = 2)
        val base = encodeBaseKeepingIpv6Literal(parts[0])
        return if (parts.size > 1) "$base?${parts[1]}" else base
    }

    /** Encode stray `[`/`]` and spaces, but PRESERVE a well-formed IPv6 host
     *  literal (`scheme://[v6]:port/path`). The old blanket encode broke every
     *  IPv6 URL into `%5Bfd00::5%5D`, which HttpUrl.parse then refused — so the
     *  v6 branch of [isSafeTarget] was dead and LAN/NAS targets written as v6
     *  literals were unreachable. OkHttp parses the bracketed form and returns
     *  a bare `fd00::5` host, which then flows through isBlockedAddress. */
    private fun encodeBaseKeepingIpv6Literal(base: String): String {
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return base.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")
        val afterScheme = schemeEnd + 3
        if (afterScheme < base.length && base[afterScheme] == '[') {
            val close = base.indexOf(']', afterScheme)
            if (close > afterScheme) {
                val literal = base.substring(0, close + 1)          // "http://[fd00::5]"
                val rest = base.substring(close + 1)
                return literal + rest.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")
            }
        }
        return base.replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")
    }

    /**
     * Cheap, DNS-free pre-filter for a target URL before any fetch. This is a
     * FAST path, not the security boundary — [safeDns] is the real gate (it
     * catches every odd IP spelling and every DNS rebind at connect time).
     * What it guards here: non-http(s) schemes, service ports that no locker
     * host uses (ssh/smtp/db/admin), and IP-literal hosts that are already
     * obviously private (so we never even attempt the connect).
     * HttpUrl.parse canonicalizes userinfo ("http://evil@127.0.0.1/") out of
     * the host — asserted by HttpClientSafetyTest; do not hand string-split.
     */
    private val SERVICE_PORTS = setOf(
        21, 22, 23, 25, 53, 110, 135, 137, 139, 143, 445, 1433, 2049, 3306,
        3389, 5432, 5984, 6379, 9200, 11211, 27017
    )

    fun isSafeTarget(url: String): Boolean {
        val parsed = parseHttpUrl(safeUrl(url)) ?: return false
        if (parsed.scheme != "http" && parsed.scheme != "https") return false
        if (parsed.port in SERVICE_PORTS) return false
        val host = parsed.host
        if (host.isBlank()) return false
        // IP-literal hosts (v4 quad, decimal/hex single-token, or any v6 form)
        // get the full address check WITHOUT touching the network; plain
        // hostnames are deferred to safeDns, which is the real boundary.
        val bare = if (host.startsWith("[")) host.removeSurrounding("[", "]") else host
        val looksLikeIp = bare.indexOf(':') >= 0 ||
            (bare.isNotEmpty() && bare[0].isDigit() && !bare.any { it.isLetter() || it == '-' })
        if (looksLikeIp) {
            val addr = try { java.net.InetAddress.getByName(bare) } catch (_: Exception) {
                return false // unparseable "numeric" host: refuse it
            }
            if (isBlockedAddress(addr)) return false
        }
        return true
    }

    /** get()/postForm()/getText() entry guard: throw (their existing failure
     *  channels already log throws) instead of silently connecting. */
    private fun refuseUnsafeTarget(url: String) {
        if (!isSafeTarget(url)) {
            lastFailure = "unsafe target refused: ${url.take(120)}"
            throw java.io.IOException("Refused unsafe target: ${url.take(160)}")
        }
    }

    fun get(url: String, referer: String? = null, headers: Map<String, String> = emptyMap(), tag: String? = null, permissive: Boolean = false): Response {
        // SSRF floor, page plane. safeDns filters DNS-resolved hosts, but OkHttp
        // SKIPS the custom Dns for IP-literal hosts (RouteSelector:
        // canParseAsIpAddress -> raw getByName; fact-checked against 4.12.0
        // source). isSafeTarget closes that gap + service ports pre-flight.
        refuseUnsafeTarget(url)
        val reqBuilder = Request.Builder()
            .url(safeUrl(url))
            .header("User-Agent", DEFAULT_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val client = if (permissive) permissiveClient else shared
        val call = client.newCall(reqBuilder.build())
        inFlightCalls.add(call)
        if (tag != null) {
            taggedCalls.computeIfAbsent(tag) { java.util.concurrent.CopyOnWriteArrayList() }.add(call)
        }
        val started = System.currentTimeMillis()
        return try {
            val res = call.execute()
            com.anonrode.downloader.util.DebugLog.net(
                "GET ${safeUrl(url)} -> ${res.code} in ${System.currentTimeMillis() - started}ms"
            )
            res
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.net(
                "GET ${safeUrl(url)} FAILED ${e.javaClass.simpleName}: ${e.message} after ${System.currentTimeMillis() - started}ms"
            )
            throw e
        } finally {
            inFlightCalls.remove(call)
            if (tag != null) {
                taggedCalls[tag]?.remove(call)
            }
        }
    }

    /**
     * In-flight resolver/probe calls. pause()/cancel() cancel these so a hung
     * connect or a slow header phase cannot outlive the task that started it.
     * Body reads are separately bounded by [MAX_TEXT_BYTES] — the actual data
     * drain guard: a resolver misdirected onto a direct file URL can no longer
     * stream the whole video into memory.
     */
    private val inFlightCalls = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call>()

    /**
     * Calls registered under a tag (e.g. "search") can be cancelled as a group
     * when a newer call of the same kind supersedes them. A cancelled search's
     * coroutine dies instantly, but its blocking OkHttp calls keep running
     * until they finish — on mobile that meant every keystroke left ~10 stale
     * requests draining data for seconds after the next search started.
     */
    private val taggedCalls = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call>>()

    /** Cancel every in-flight resolver/probe HTTP call (user paused/cancelled). */
    fun cancelInFlight() {
        // A user pause/cancel must not kill their live search: search calls are
        // tagged separately and get cancelled only when a newer search supersedes
        // them (cancelTagged). The log showed pausing one download aborting
        // unrelated in-flight searches mid-keystroke.
        val searchCalls = taggedCalls["search"]
        for (c in inFlightCalls) {
            if (searchCalls?.contains(c) == true) continue
            try { c.cancel() } catch (_: Exception) {}
        }
        inFlightCalls.clear()
        // Leave taggedCalls intact: surviving search calls still need their tag
        // so a newer search can supersede them; completed calls self-remove.
    }

    /** Cancel only the calls tagged [tag] (a superseded search, etc.). */
    fun cancelTagged(tag: String) {
        taggedCalls[tag]?.let { calls ->
            for (c in calls) {
                try { c.cancel() } catch (_: Exception) {}
            }
        }
        taggedCalls.remove(tag)
    }

    /** Registration covers headers AND body reads; the response cannot escape this scope. */
    internal inline fun <T> executeCancellable(client: OkHttpClient, req: Request, block: (Response) -> T): T {
        val call = registerResolverCall(client, req)
        try {
            return executeResolverCall(call).use(block)
        } finally {
            unregisterResolverCall(call)
        }
    }

    @PublishedApi
    internal fun registerResolverCall(client: OkHttpClient, req: Request): okhttp3.Call {
        refuseUnsafeTarget(req.url.toString())
        admitRequest(req.url.toString())
        return client.newCall(req).also { inFlightCalls.add(it) }
    }

    @PublishedApi
    internal fun executeResolverCall(call: okhttp3.Call): Response {
        val response = call.execute()
        recordCooldown(response)
        if (response.code == 429 || response.code == 503) {
            val url = response.request.url.toString()
            response.close()
            throw OriginCooldownException(url)
        }
        return response
    }

    @PublishedApi
    internal fun unregisterResolverCall(call: okhttp3.Call) {
        inFlightCalls.remove(call)
    }

    /** Remaining per-origin cooldown ms (0 = usable). */
    fun remainingCooldownMs(url: String): Long = retryAfterCooldown.remainingMs(safeUrl(url))

    /**
     * Hard cap on any body read through [cappedText]/[cappedBytes]. Resolver
     * responses are HTML/JSON/wasm — kilobytes. A response larger than this
     * is by definition not a page (a misdirected file fetch), and reading it
     * would burn the user's mobile data for nothing.
     */
    const val MAX_TEXT_BYTES = 3L * 1024 * 1024
    const val MAX_BIN_BYTES = 5L * 1024 * 1024

    /**
     * Bounded body drain shared by [cappedText]/[cappedBytes]. Okio's
     * `request(n)` blocks until n bytes or EOF; a server trickling one byte
     * every 14 s therefore held the calling thread (and the task's
     * cancellation point) open essentially forever — [budgetMs] is now a hard
     * wall, checked between 64 KiB chunks, on top of the 15 s socket read
     * timeout. Returns the bytes obtained (size vs the caller's cap decides
     * truncation semantics); EOF or budget expiry stops the drain.
     */
    private const val DRAIN_STEP = 64L * 1024
    /** Overall budget for one page/blob body read. Generous for real pages
     *  (kilobytes) yet a hard wall against trickling servers. */
    const val DEFAULT_BODY_BUDGET_MS = 90_000L
    private fun drainCapped(source: okio.BufferedSource, maxBytes: Long, budgetMs: Long): ByteArray {
        val want = java.lang.Long.min(maxBytes + 1, Int.MAX_VALUE.toLong())
        val deadline = System.currentTimeMillis() + budgetMs
        var have = 0L
        while (have < want) {
            val target = minOf(have + DRAIN_STEP, want)
            val ok = try {
                source.request(target)
            } catch (_: Exception) {
                false
            }
            have = source.buffer.size
            if (!ok || have >= want || System.currentTimeMillis() > deadline) break
        }
        return source.buffer.readByteArray()
    }

    /** Read at most [maxBytes] of the response body as UTF-8 text. */
    fun cappedText(res: Response, maxBytes: Long = MAX_TEXT_BYTES, budgetMs: Long = DEFAULT_BODY_BUDGET_MS): String? {
        val body = res.body ?: return null
        val bytes = drainCapped(body.source(), maxBytes, budgetMs)
        val truncated = bytes.size > maxBytes
        val text = String(if (truncated) bytes.copyOf(maxBytes.toInt()) else bytes, Charsets.UTF_8)
        if (truncated) {
            com.anonrode.downloader.util.DebugLog.net(
                "body from ${res.request.url.host} truncated at $maxBytes bytes (not a page — likely a misdirected file fetch)"
            )
        }
        return text
    }

    /** Read at most [maxBytes] of the response body as bytes.
     *  An oversize body is REJECTED (null), never truncated-and-returned: the
     *  callers are byte consumers (ffmpeg inputs, wasm blobs) and a clipped
     *  file is corrupt garbage that still "succeeds" — strictly worse than a
     *  clean null that routes to the normal failure path. When the server
     *  announces the length we refuse before reading a single byte (data cap). */
    fun cappedBytes(res: Response, maxBytes: Long = MAX_BIN_BYTES, budgetMs: Long = DEFAULT_BODY_BUDGET_MS): ByteArray? {
        val body = res.body ?: return null
        val announced = body.contentLength()
        if (announced > maxBytes) {
            com.anonrode.downloader.util.DebugLog.net(
                "binary body from ${res.request.url.host} is $announced bytes > cap $maxBytes — refused unread"
            )
            return null
        }
        val bytes = drainCapped(body.source(), maxBytes, budgetMs)
        if (bytes.size > maxBytes) {
            com.anonrode.downloader.util.DebugLog.net(
                "binary body from ${res.request.url.host} exceeds $maxBytes bytes — refused (not truncated)"
            )
            return null
        }
        return bytes
    }

    fun getText(url: String, referer: String? = null, headers: Map<String, String> = emptyMap(), acceptStatus: Set<Int> = emptySet(), tag: String? = null, permissive: Boolean = false, maxBytes: Long = MAX_TEXT_BYTES): String? {
        return try {
            admitRequest(url)
            get(url, referer, headers, tag, permissive).use { res ->
                recordCooldown(res)
                if (res.isSuccessful || res.code in acceptStatus) {
                    // The host answered: clear any stale "dead" mark. The
                    // previous behavior treated a 2xx on a previously-failed
                    // host as a normal success (recorded via recordOk
                    // elsewhere) but DID NOT short-circuit the 60s backoff
                    // — so vdl.np-downloader.com, which the engine had
                    // tagged dead for 60s after a single DNS hiccup, kept
                    // getting skipped even after the very next GET to it
                    // returned 200 OK (live-verified: 50+ health-gate ERR
                    // events in app-2026-08-29 / 09-01, each followed by
                    // a 200 response on the immediate next request). This
                    // clearIfAlive call is the single line that fixes it.
                    com.anonrode.downloader.pipeline.HostHealth.clearIfAlive(url)
                    cappedText(res, maxBytes)
                } else {
                    lastFailure = "HTTP ${res.code} for ${url.take(120)}"
                    Log.w("HttpClient", lastFailure!!)
                    null
                }
            }
        } catch (e: Exception) {
            lastFailure = "${e.javaClass.simpleName}: ${e.message} for ${url.take(120)}"
            Log.w("HttpClient", lastFailure!!)
            null
        }
    }

    /**
     * POST with a form-urlencoded body, mirroring [getText]: same 3MB body
     * cap, same lastFailure journaling, same tag tracking (search-tagged
     * calls stay cancellable as a group). Used by the rules pipeline for
     * sites whose search is an admin-ajax style POST.
     */
    fun postForm(url: String, form: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap(), tag: String? = null, maxBytes: Long = MAX_TEXT_BYTES): String? {
        return try {
            refuseUnsafeTarget(url) // same floor as get() — INSIDE the try so an
            // unsafe target journal+returns null exactly like getText does; one
            // guard throwing for one verb and returning null for the other is
            // the kind of split-brain the resolvers were hardened against.
            val body = okhttp3.FormBody.Builder().apply {
                form.forEach { (k, v) -> add(k, v) }
            }.build()
            val reqBuilder = Request.Builder()
                .url(safeUrl(url))
                .post(body)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", referer)
            }
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            val call = shared.newCall(reqBuilder.build())
            inFlightCalls.add(call)
            if (tag != null) {
                taggedCalls.computeIfAbsent(tag) { java.util.concurrent.CopyOnWriteArrayList() }.add(call)
            }
            val started = System.currentTimeMillis()
            try {
                call.execute().use { res ->
                    com.anonrode.downloader.util.DebugLog.net(
                        "POST ${safeUrl(url)} -> ${res.code} in ${System.currentTimeMillis() - started}ms"
                    )
                    recordCooldown(res)
                    if (res.isSuccessful) {
                        cappedText(res, maxBytes)
                    } else {
                        lastFailure = "HTTP ${res.code} for ${url.take(120)}"
                        Log.w("HttpClient", lastFailure!!)
                        null
                    }
                }
            } finally {
                inFlightCalls.remove(call)
                if (tag != null) {
                    taggedCalls[tag]?.remove(call)
                }
            }
        } catch (e: Exception) {
            lastFailure = "${e.javaClass.simpleName}: ${e.message} for ${url.take(120)}"
            Log.w("HttpClient", lastFailure!!)
            null
        }
    }

    /**
     * Reachability probe: request headers only (no body read) with a hard call
     * timeout, and report whether the server answered. Used by the HLS
     * pre-flight so a dead segment CDN fails a task in seconds instead of
     * pinning it in DOWNLOADING at 0 bytes for minutes.
     */
    fun probe(url: String, referer: String? = null, timeoutMs: Long = 10_000L, tag: String? = null): Boolean {
        if (!isSafeTarget(url)) {
            com.anonrode.downloader.util.DebugLog.net("PROBE refused by isSafeTarget: ${url.take(140)}")
            return false
        }
        // Request construction (Builder.url, newCall) sits INSIDE the try on
        // purpose: okhttp3 throws IllegalArgumentException for a malformed
        // URL, and probe's contract is "answer true/false, never throw" —
        // callers run it inside coroutine children (SearchStrategyRunner's
        // concurrent suffix probes) where an escaping exception would fail
        // the whole scope, not just the one candidate.
        try {
            val reqBuilder = Request.Builder()
                .url(safeUrl(url))
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")
            if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)
            val call = shared.newCall(reqBuilder.build())
            call.timeout().timeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            inFlightCalls.add(call)
            if (tag != null) {
                taggedCalls.computeIfAbsent(tag) { java.util.concurrent.CopyOnWriteArrayList() }.add(call)
            }
            return try {
                val res = call.execute()
                // 2xx proves the host serves; 416 (range not satisfiable for a
                // bytes=0-0 probe) still proves it is reachable and alive.
                val ok = res.code in 200..299 || res.code == 416
                // Headers-only check: close the body without reading it, so a
                // server that ignores Range cannot stream data past us.
                try { res.body?.close() } catch (_: Exception) {}
                com.anonrode.downloader.util.DebugLog.net("PROBE ${safeUrl(url)} -> ${res.code} (timeout=${timeoutMs}ms)")
                ok
            } finally {
                inFlightCalls.remove(call)
                if (tag != null) {
                    taggedCalls[tag]?.remove(call)
                }
            }
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.net("PROBE ${url.take(140)} FAILED ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    // ------------------------------------------------------------ terminal gate

    /**
     * ONE canonical "is this URL actually serving the file" verdict, replacing
     * the hand-copied checks that used to live inside individual resolvers
     * (they disagreed with each other — the class of bug behind the vikingfile
     * R2 misdirect rejection, 84e1d90). Semantics, each load-bearing and
     * live-verified:
     *  - no-redirect client: a 3xx is a HOP, never an acceptance. The shared
     *    client auto-follows, which HIDES Locations inside OkHttp — that is why
     *    this must run on a no-follow client (see [TerminalProbe.location]).
     *  - `Range: bytes=0-0`: at most 1 body byte crosses the wire (metered-
     *    data discipline); servers honoring it answer 206 + Content-Range.
     *  - 200 with Content-Length = whole-file size: also accepted (servers
     *    ignoring Range). No size proof (chunked/absent CL) = reject.
     *  - text/html / application/xhtml = interstitial/ad page, reject.
     *  - hard per-call timeout + [inFlightCalls] registration: pause/cancel
     *    kills the probe (the old private copies were raw execute() —
     *    uncancellable stragglers).
     * Returns null ONLY on network failure; otherwise a [TerminalProbe] whose
     * `totalBytes != null` is the sole acceptance signal.
     */
    fun probeTerminal(
        url: String,
        referer: String? = null,
        timeoutMs: Long = 8_000L,
        permissive: Boolean = false,
        tag: String? = null
    ): TerminalProbe? {
        if (!isSafeTarget(url)) {
            com.anonrode.downloader.util.DebugLog.net("TERMINAL refused by isSafeTarget: ${url.take(140)}")
            return null
        }
        // Request construction inside the try, exactly as in [probe]: "returns
        // null ONLY on network failure" is the documented contract, and an
        // IllegalArgumentException from a malformed URL must honor it.
        try {
            val reqBuilder = Request.Builder()
                .url(safeUrl(url))
                .header("User-Agent", DEFAULT_UA)
                .header("Range", "bytes=0-0")
            if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)
            val client = if (permissive) terminalProbeClientPermissive else terminalProbeClient
            val call = client.newCall(reqBuilder.build())
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            inFlightCalls.add(call)
            if (tag != null) {
                taggedCalls.computeIfAbsent(tag) { java.util.concurrent.CopyOnWriteArrayList() }.add(call)
            }
            try {
                call.execute().use { res ->
                    val ct = res.header("Content-Type")?.lowercase() ?: ""
                    val total = acceptsTerminalResponse(
                        res.code, res.header("Content-Length"), res.header("Content-Range"), res.header("Content-Type")
                    )
                    val tp = TerminalProbe(res.code, res.header("Location"), ct, total)
                    com.anonrode.downloader.util.DebugLog.net(
                        "TERMINAL ${safeUrl(url)} -> ${res.code}" +
                            (if (total != null) " size=$total" else " (not a terminal)")
                    )
                    return tp
                }
            } finally {
                inFlightCalls.remove(call)
                if (tag != null) taggedCalls[tag]?.remove(call)
            }
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.net(
                "TERMINAL ${url.take(140)} FAILED ${e.javaClass.simpleName}: ${e.message}"
            )
            return null
        }
    }

    /** Cached no-follow variants (pool/dispatcher/cookies/safeDns are shared
     *  with [shared] via newBuilder — only the redirect policy differs). */
    private val terminalProbeClient: OkHttpClient by lazy {
        shared.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    private val terminalProbeClientPermissive: OkHttpClient by lazy {
        permissiveClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
}

/**
 * Result of [HttpClient.probeTerminal]: the raw verdict on ONE no-redirect
 * request. `totalBytes` non-null means VALIDATED TERMINAL (200/206, real size,
 * non-HTML type). `location` is present on 3xx so callers can express the
 * HOP explicitly (auto-following would swallow it).
 */
data class TerminalProbe(
    val code: Int,
    val location: String?,
    val contentType: String,
    val totalBytes: Long?
)

/**
 * Pure decision half of [HttpClient.probeTerminal] — no network, JVM-testable.
 * Returns the file's total byte size when the response proves it serves a
 * real file, else null. Kept top-level per the isTlsChainFailure precedent so
 * tests exercise the real accept/reject matrix without an Android-bound client.
 */
internal fun acceptsTerminalResponse(
    code: Int,
    contentLength: String?,
    contentRange: String?,
    contentType: String?
): Long? {
    if (code != 200 && code != 206) return null
    val ct = (contentType ?: "").lowercase()
    if (ct.startsWith("text/html") || ct.startsWith("application/xhtml")) return null
    if (code == 206) {
        // On 206 the Content-Length is just the 1 probed byte; the real total
        // lives in Content-Range: "bytes 0-0/159703784" (or ".../*" — unknown,
        // in which case the single-byte CL is the only size proof we get).
        val cr = contentRange?.trim()
        if (cr != null) {
            val slash = cr.lastIndexOf('/')
            if (slash >= 0) {
                val t = cr.substring(slash + 1).trim()
                if (t == "*") return (contentLength?.toLongOrNull() ?: 0L).takeIf { it > 0 }
                return t.toLongOrNull()?.takeIf { it > 0 }
            }
            return null // malformed Content-Range: no size proof
        }
        // 206 without Content-Range is spec-violating — refuse, do not guess.
        return null
    }
    // 200: Content-Length IS the size. Absent (chunked) = no proof.
    return (contentLength?.toLongOrNull() ?: 0L).takeIf { it > 0 }
}

/**
 * SSRF address predicate used by [HttpClient.safeDns] and the URL pre-filter.
 * Blocks loopback, "this-network", link-local (incl. 169.254.169.254 cloud
 * metadata), site-local RFC1918, multicast, CGNAT 100.64/10, and the private
 * address smuggled inside 6to4 (2002::/16) / Teredo (2001::/32) IPv6 wrappers.
 * IPv4-mapped v6 (::ffff:x) is delegated to the JDK's own is* checks.
 * Top-level + pure per the isTlsChainFailure convention: full JVM matrix test.
 */
internal fun isBlockedAddress(addr: java.net.InetAddress): Boolean {
    if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
        addr.isSiteLocalAddress || addr.isMulticastAddress
    ) return true
    val b = addr.address ?: return true // fail closed on anything unreadable
    if (b.size == 4) {
        val o0 = b[0].toInt() and 0xFF
        if (o0 == 0) return true // 0.0.0.0/8 "this network"
        if (o0 == 100 && (b[1].toInt() and 0xC0) == 0x40) return true // 100.64.0.0/10 CGNAT
        if (o0 == 0xFF && (b[1].toInt() and 0xFF) == 0xFF && (b[2].toInt() and 0xFF) == 0xFF &&
            (b[3].toInt() and 0xFF) == 0xFF
        ) return true // limited broadcast (JDK's isMulticastAddress does NOT cover it)
        return false
    }
    if (b.size == 16) {
        // ::ffff:a.b.c.d (mapped) and the obsolete ::a.b.c.d (compat): unwrap to
        // the v4 rules OURSELVES — whether getByName hands us an Inet4Address
        // or an Inet6Address for those texts is a JDK text-format detail we
        // refuse to depend on. (NAT64 64:ff9b::/96 has non-zero leading bytes
        // and is deliberately left alone: it is how v6-only networks reach
        // public v4.)
        val leadingZeros = (0..9).all { b[it].toInt() == 0 }
        if (leadingZeros) {
            val w10 = b[10].toInt() and 0xFF
            val w11 = b[11].toInt() and 0xFF
            if ((w10 == 0xFF && w11 == 0xFF) || (w10 == 0 && w11 == 0)) {
                val v4 = embeddedV4OrNull(byteArrayOf(b[12], b[13], b[14], b[15])) ?: return true
                if (isBlockedAddress(v4)) return true
            }
        }
        // 6to4: 2002:<v4>:: — unwrap the embedded IPv4 and re-check.
        if (b[0].toInt() and 0xFF == 0x20 && b[1].toInt() and 0xFF == 0x02) {
            val v4 = embeddedV4OrNull(byteArrayOf(b[2], b[3], b[4], b[5])) ?: return true
            if (isBlockedAddress(v4)) return true
        }
        // Teredo: 2001:0000::<..><client-v4 bitwise-NOT> — unwrap and re-check.
        if (b[0].toInt() and 0xFF == 0x20 && b[1].toInt() and 0xFF == 0x01 &&
            b[2].toInt() and 0xFF == 0x00 && b[3].toInt() and 0xFF == 0x00
        ) {
            val raw = byteArrayOf(
                (b[12].toInt() and 0xFF).inv().toByte(), (b[13].toInt() and 0xFF).inv().toByte(),
                (b[14].toInt() and 0xFF).inv().toByte(), (b[15].toInt() and 0xFF).inv().toByte()
            )
            val v4 = embeddedV4OrNull(raw) ?: return true
            if (isBlockedAddress(v4)) return true
        }
    }
    return false
}

private fun embeddedV4OrNull(bytes: ByteArray): java.net.InetAddress? =
    try { java.net.Inet4Address.getByAddress(bytes) } catch (_: Exception) { null }

/**
 * True when [e]'s cause chain is a TLS handshake/chain failure (strict
 * client vs a server that omits its intermediate cert). Only this class
 * of error justifies the trust-all retry — timeouts, DNS and HTTP errors
 * are still real failures and must not be papered over by disabling
 * certificate validation. Top-level (not an [HttpClient] member) so JVM
 * tests can exercise it without initializing the Android-bound client.
 */
internal fun isTlsChainFailure(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        if (t is javax.net.ssl.SSLException || t is java.security.cert.CertPathValidatorException) return true
        val msg = t.message?.lowercase() ?: ""
        if (msg.contains("trust anchor") || msg.contains("certpath") || msg.contains("certificate_unknown")) return true
        t = t.cause
    }
    return false
}
