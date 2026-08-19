package com.anonrode.downloader.data.net

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
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

    /**
     * Hybrid Smart-DNS:
     * 1. Primary: System DNS (Fastest 15ms latency + local ISP CDN geo-routing for max video download speeds).
     * 2. Fallback: Google Public DNS over HTTPS (Bypasses Nigerian ISP censorship/DNS-poisoning on blocked scraper sites).
     */
    private val hybridDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            dohCache[hostname]?.let { return it }
            return try {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: java.net.UnknownHostException) {
                try {
                    val encoded = java.net.URLEncoder.encode(hostname, "UTF-8")
                    val dohUrl = "https://dns.google/resolve?name=$encoded&type=A"
                    val req = Request.Builder()
                        .url(dohUrl)
                        .header("User-Agent", DEFAULT_UA)
                        .build()
                    bootstrapDohClient.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) throw e
                        val body = res.body?.string() ?: throw e
                        val json = org.json.JSONObject(body)
                        val answers = json.optJSONArray("Answer") ?: throw e
                        val addrs = mutableListOf<java.net.InetAddress>()
                        for (i in 0 until answers.length()) {
                            val data = answers.getJSONObject(i).optString("data")
                            if (data.isNotBlank() && !data.contains(":")) {
                                addrs.add(java.net.InetAddress.getByName(data))
                            }
                        }
                        if (addrs.isEmpty()) throw e
                        dohCache[hostname] = addrs
                        addrs
                    }
                } catch (_: Exception) {
                    throw e
                }
            }
        }
    }

    val shared: OkHttpClient = OkHttpClient.Builder()
        .dns(hybridDns)
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

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

    fun safeUrl(url: String): String {
        if (url.isBlank()) return url
        val parts = url.split("?", limit = 2)
        val base = parts[0].replace("[", "%5B").replace("]", "%5D").replace(" ", "%20")
        return if (parts.size > 1) "$base?${parts[1]}" else base
    }

    fun get(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val reqBuilder = Request.Builder()
            .url(safeUrl(url))
            .header("User-Agent", DEFAULT_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        return shared.newCall(reqBuilder.build()).execute()
    }

    fun getText(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String? {
        return try {
            get(url, referer, headers).use { res ->
                if (res.isSuccessful) {
                    res.body?.string()
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
}
