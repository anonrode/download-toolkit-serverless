package com.anonrode.downloader.data.net

import android.content.Context
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero-bloat Cloudflare Turnstile & Bot-Check solver.
 *
 * Coordinates fingerprint alignment, clearance persistence, per-host single-flight
 * deduplication, and single-WebView process concurrency.
 */
object CloudflareSolver {
    private const val TAG = "CloudflareSolver"
    private const val VAULT_PREFS = "cloudflare_clearance_vault"
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val VAULT_TTL_MS = 12 * 60 * 60 * 1000L // 12 hours

    private val BACKGROUND_TAGS = setOf("verify", "trending", "warmup", "hls-estimate", "background")
    val CHALLENGE_STATUS_CODES = setOf(403, 503)

    /**
     * Root domains the WebView solve exists for — the sites OTA rev 10
     * re-enabled "with cloudflare solver" (nepu, anitaku, asianc) plus
     * gogoanime. Four weeks of client logs (share-19..48) never showed a
     * genuine challenge from ANY host, while the widened 11ec62e detection
     * burned real resolve time "solving" pages that are not challenges:
     * nepu /watch meta-refresh templates, naijavault dl pages carrying the
     * literal challenge-platform script name, and already-cleared dood 200
     * embeds. The solver is scoped by host: requests to anything outside
     * this list never enter a solve.
     */
    val SOLVER_HOSTS = setOf("asianc.id", "anitaku.pe", "gogoanime.or.at", "nepu.gd")

    fun isSolverScopedHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim()?.removePrefix("www.") ?: return false
        return h.isNotBlank() && SOLVER_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Accurate discrimination of Cloudflare Turnstile, LiteSpeed, DDOS-GUARD & Bot-Check
     * challenges on solver-scoped hosts. Evaluates status codes (403, 503), cf-mitigated
     * header, and peeks up to 16KB (16384 bytes) for genuine anti-bot challenge signatures.
     * 200/429/451 never qualify: a 200 is an ordinary page (meta-refresh is a common
     * non-challenge template), 429 belongs to the rate-limit backoff ladder, and a 451
     * legal block is unsolvable by definition.
     */
    fun isChallenge(res: Response): Boolean {
        val reqHost = try {
            res.request.url.host
        } catch (_: Exception) { null }
        if (!isSolverScopedHost(reqHost)) return false
        if (res.code !in CHALLENGE_STATUS_CODES) return false
        val cfMitigated = res.header("cf-mitigated")
        if (cfMitigated?.equals("challenge", ignoreCase = true) == true) return true

        val contentType = res.header("Content-Type").orEmpty()
        val isHtmlLike = contentType.contains("text/html", ignoreCase = true) ||
            contentType.contains("application/xhtml+xml", ignoreCase = true)

        if (isHtmlLike) {
            val bodySnippet = try {
                res.peekBody(16384L).string().lowercase()
            } catch (_: Exception) { "" }

            val hasMarker = bodySnippet.contains("just a moment") ||
                bodySnippet.contains("cf-challenge") ||
                bodySnippet.contains("challenge-platform") ||
                bodySnippet.contains("turnstile") ||
                bodySnippet.contains("__cf_chl_") ||
                bodySnippet.contains("__hvtok") ||
                bodySnippet.contains("quick check") ||
                bodySnippet.contains("dv1ok") ||
                bodySnippet.contains("ddos-guard") ||
                bodySnippet.contains("checking your browser") ||
                bodySnippet.contains("verifying you are human")
            if (hasMarker) return true
        }
        return false
    }

    fun isCloudflareChallenge(res: Response): Boolean = isChallenge(res)

    @Volatile
    var isAppInForeground: Boolean = false

    @Volatile
    var isHostActive: Boolean = true

    @Volatile
    private var appContext: Context? = null

    data class ChallengeRequest(
        val targetUrl: String,
        val host: String,
        val referer: String?,
        val deferred: CompletableDeferred<Boolean>,
        val staleClearance: String?
    )

    private val _challengeState = MutableStateFlow<ChallengeRequest?>(null)
    val challengeState: StateFlow<ChallengeRequest?> = _challengeState.asStateFlow()

    private val solverMutex = Mutex()
    private val inFlightByHost = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val defaultUa = WebSettings.getDefaultUserAgent(context)
            val cleanUa = defaultUa.replace("; wv", "")
            HttpClient.DEFAULT_UA = cleanUa
            Log.d(TAG, "Aligned HttpClient.DEFAULT_UA with native Android Chrome: $cleanUa")
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch default user agent: ${e.message}")
        }

        try {
            val prefs = context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val all = prefs.all
            val cookieManager = CookieManager.getInstance()
            val editor = prefs.edit()
            var modified = false

            for ((domain, raw) in all) {
                val str = raw as? String ?: continue
                try {
                    val obj = JSONObject(str)
                    val token = obj.getString("token")
                    val ua = obj.getString("ua")
                    val expiresAtMs = obj.getLong("expiresAtMs")

                    if (expiresAtMs <= now || ua != HttpClient.DEFAULT_UA) {
                        editor.remove(domain)
                        modified = true
                        continue
                    }

                    val tokenName = obj.optString("tokenName", "cf_clearance")
                    val remainingSec = (expiresAtMs - now) / 1000L
                    if (remainingSec > 0) {
                        HttpClient.storeCookie(tokenName, token, domain, "/", remainingSec)
                        try {
                            cookieManager.setCookie("https://$domain", "$tokenName=$token; Domain=.$domain; Path=/")
                        } catch (_: Exception) {}
                        Log.d(TAG, "Seeded valid clearance token ($tokenName) for domain: $domain ($remainingSec sec remaining)")
                    }
                } catch (_: Exception) {
                    editor.remove(domain)
                    modified = true
                }
            }
            if (modified) editor.apply()
            try { cookieManager.flush() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Clearance vault init error: ${e.message}")
        }
    }

    fun canAttemptSolve(tag: String?): Boolean {
        if (!isHostActive || !isAppInForeground) return false
        if (tag != null && tag in BACKGROUND_TAGS) return false
        return true
    }

    /**
     * Evaluates whether a subresource requested by the challenge WebView should be allowed.
     * Always returns true for non-HTTP(S) schemes (blob:, data:, about:blank) required by Turnstile workers.
     * Drops heavy media, stylesheets, and tracking domains on non-Cloudflare origins.
     */
    fun shouldAllowSubresource(reqUrl: String, targetHost: String): Boolean {
        if (!reqUrl.startsWith("http://", ignoreCase = true) && !reqUrl.startsWith("https://", ignoreCase = true)) {
            return true
        }
        val httpUrl = reqUrl.toHttpUrlOrNull() ?: return true
        val host = httpUrl.host.lowercase()
        if (host == "challenges.cloudflare.com" ||
            host.endsWith(".cloudflare.com") ||
            host.endsWith(".turnstile.com") ||
            host.endsWith(".cloudflareinsights.com")
        ) {
            return true
        }
        val blockedDomains = listOf(
            "google-analytics.com", "googletagmanager.com", "doubleclick.net",
            "adservice.google.com", "yandex.ru", "mc.yandex.ru"
        )
        if (blockedDomains.any { host.contains(it) }) return false

        val path = httpUrl.encodedPath.lowercase()
        val blockedExtensions = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
            ".mp4", ".mkv", ".m3u8", ".ts",
            ".woff", ".woff2", ".ttf", ".otf",
            ".css"
        )
        if (blockedExtensions.any { path.endsWith(it) }) {
            return false
        }

        return true
    }

    fun rootDomain(hostOrUrl: String): String {
        val h = if (hostOrUrl.contains("://")) {
            try {
                hostOrUrl.toHttpUrlOrNull()?.host ?: URI(hostOrUrl).host ?: hostOrUrl
            } catch (_: Exception) {
                hostOrUrl
            }
        } else {
            hostOrUrl
        }.lowercase().trim().removePrefix("www.")

        val httpUrl = try {
            HttpUrl.Builder().scheme("https").host(h).build()
        } catch (_: Exception) {
            null
        }
        return httpUrl?.topPrivateDomain() ?: h
    }

    fun resolveChallengeTargetUrl(reqUrl: String, referer: String?): String {
        return try {
            val uri = URI(reqUrl)
            val path = uri.path?.lowercase() ?: ""
            val isEndpoint = path.contains("/wp-admin/") ||
                path.contains("/api/") ||
                path.contains("/wp-json/") ||
                path.endsWith(".php") ||
                path.endsWith(".json")
            if (isEndpoint) {
                if (!referer.isNullOrBlank() && referer.startsWith("http")) {
                    referer
                } else {
                    "${uri.scheme ?: "https"}://${uri.host}/"
                }
            } else {
                reqUrl
            }
        } catch (_: Exception) {
            reqUrl
        }
    }

    fun solveSync(url: String, referer: String? = null, tag: String? = null): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "CloudflareSolver.solveSync must never be called on the Main looper"
        }
        if (!canAttemptSolve(tag)) return false
        return runBlocking(Dispatchers.IO) {
            solve(url, referer, tag)
        }
    }

    suspend fun solve(url: String, referer: String? = null, tag: String? = null): Boolean {
        if (!canAttemptSolve(tag)) return false
        val targetUrl = resolveChallengeTargetUrl(url, referer)
        val host = try {
            URI(targetUrl).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        if (host.isBlank()) return false
        if (!isSolverScopedHost(host)) return false
        val root = rootDomain(host)

        // Capture the stale token BEFORE waiting on any lock
        val staleClearance = HttpClient.cookieValue("cf_clearance", root)
            ?: HttpClient.cookieValue("hvp", root)
            ?: HttpClient.cookieValue("dv1ok", root)
            ?: HttpClient.cookieValue("__cf_bm", root)
            ?: extractToken(try { CookieManager.getInstance().getCookie(targetUrl) } catch (_: Throwable) { null })?.second

        // 1. Per-host single-flight coalescing OUTSIDE solverMutex
        val existing = inFlightByHost[root]
        if (existing != null) {
            return try {
                existing.await()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                false
            }
        }
        val myDeferred = CompletableDeferred<Boolean>()
        val prev = inFlightByHost.putIfAbsent(root, myDeferred)
        if (prev != null) {
            return try {
                prev.await()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                false
            }
        }

        // 2. Global 1-WebView cap across all hosts
        return try {
            solverMutex.withLock {
                if (!canAttemptSolve(tag)) {
                    myDeferred.complete(false)
                    return@withLock false
                }

                // Check if a fresh token was already minted while waiting for solverMutex
                val currentToken = HttpClient.cookieValue("cf_clearance", root)
                    ?: HttpClient.cookieValue("hvp", root)
                    ?: HttpClient.cookieValue("dv1ok", root)
                    ?: HttpClient.cookieValue("__cf_bm", root)
                if (!currentToken.isNullOrBlank() && currentToken != staleClearance) {
                    myDeferred.complete(true)
                    return@withLock true
                }

                // Purge the rejected stale cookie from both HttpClient and CookieManager
                HttpClient.purgeCookie("cf_clearance", root)
                HttpClient.purgeCookie("hvp", root)
                HttpClient.purgeCookie("dv1ok", root)
                try {
                    val cm = CookieManager.getInstance()
                    cm.setCookie(targetUrl, "cf_clearance=; Max-Age=0; Path=/")
                    cm.setCookie("https://$root", "cf_clearance=; Domain=.$root; Max-Age=0; Path=/")
                    cm.setCookie(targetUrl, "hvp=; Max-Age=0; Path=/")
                    cm.setCookie("https://$root", "hvp=; Domain=.$root; Max-Age=0; Path=/")
                    cm.setCookie(targetUrl, "dv1ok=; Max-Age=0; Path=/")
                    cm.setCookie("https://$root", "dv1ok=; Domain=.$root; Max-Age=0; Path=/")
                    cm.flush()
                } catch (_: Throwable) {}

                val req = ChallengeRequest(
                    targetUrl = targetUrl,
                    host = root,
                    referer = referer,
                    deferred = myDeferred,
                    staleClearance = staleClearance
                )

                _challengeState.value = req

                try {
                    withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                        myDeferred.await()
                    } ?: false
                } catch (e: CancellationException) {
                    myDeferred.complete(false)
                    false
                } finally {
                    if (!myDeferred.isCompleted) {
                        myDeferred.complete(false)
                    }
                    if (_challengeState.value === req) {
                        _challengeState.value = null
                    }
                }
            }
        } finally {
            if (!myDeferred.isCompleted) {
                myDeferred.complete(false)
            }
            inFlightByHost.remove(root, myDeferred)
        }
    }

    fun resolve(request: ChallengeRequest, success: Boolean, clearanceToken: String? = null, tokenName: String = "cf_clearance") {
        if (success) {
            val ttlSec = VAULT_TTL_MS / 1000L
            if (!clearanceToken.isNullOrBlank()) {
                HttpClient.storeCookie(tokenName, clearanceToken, request.host, "/", ttlSec)
                appContext?.let { ctx ->
                    persistClearance(ctx, request.host, clearanceToken, HttpClient.DEFAULT_UA, tokenName)
                }
            }
            // Universal cookie banking: sync ALL cookies set during the WebView challenge
            try {
                bankCookies(request.targetUrl, request.host)
            } catch (_: Throwable) {}

            // If clearanceToken wasn't explicitly passed, check if any recognized token was minted into CookieManager
            if (clearanceToken.isNullOrBlank()) {
                try {
                    val cm = CookieManager.getInstance()
                    val rawCookies = cm.getCookie(request.targetUrl).orEmpty()
                    val pair = extractToken(rawCookies)
                    if (pair != null) {
                        appContext?.let { ctx ->
                            persistClearance(ctx, request.host, pair.second, HttpClient.DEFAULT_UA, pair.first)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        request.deferred.complete(success)
        if (_challengeState.value === request) {
            _challengeState.value = null
        }
    }

    /**
     * Universal cookie banking: extracts all cookies from Android CookieManager for targetUrl
     * and persists them in HttpClient session jar for the root apex domain.
     */
    fun bankCookies(targetUrl: String, host: String) {
        try {
            val cm = CookieManager.getInstance()
            cm.flush()
            val rawCookies = cm.getCookie(targetUrl).orEmpty()
            bankCookiesFromHeader(rawCookies, host)
        } catch (_: Throwable) {}
    }

    /**
     * Stores all raw cookies from a Set-Cookie / Cookie header line into HttpClient for the host root domain.
     */
    fun bankCookiesFromHeader(cookieHeader: String?, host: String) {
        if (cookieHeader.isNullOrBlank()) return
        val root = rootDomain(host)
        val ttlSec = VAULT_TTL_MS / 1000L
        for (part in cookieHeader.split(";")) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq).trim()
            val v = part.substring(eq + 1).trim()
            if (k.isNotBlank() && v.isNotBlank()) {
                HttpClient.storeCookie(k, v, root, "/", ttlSec)
            }
        }
    }

    fun persistClearance(context: Context, host: String, token: String, ua: String, tokenName: String = "cf_clearance") {
        val root = rootDomain(host)
        val now = System.currentTimeMillis()
        val expiresAt = now + VAULT_TTL_MS
        try {
            val json = JSONObject().apply {
                put("token", token)
                put("tokenName", tokenName)
                put("ua", ua)
                put("expiresAtMs", expiresAt)
            }
            context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(root, json.toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist clearance for $root: ${e.message}")
        }
    }

    val KNOWN_TOKENS = listOf(
        "cf_clearance",
        "hvp",
        "dv1ok",
        "__cf_bm",
        "PHPSESSID",
        "session",
        "token"
    )

    fun extractToken(cookieString: String?): Pair<String, String>? {
        if (cookieString.isNullOrBlank()) return null
        for (tokenName in KNOWN_TOKENS) {
            val regex = Regex("""\b${Regex.escape(tokenName)}=([^;,\s]+)""")
            val match = regex.find(cookieString)
            if (match != null) {
                return Pair(tokenName, match.groupValues[1])
            }
        }
        return null
    }

    fun extractAllTokens(cookieString: String?): Map<String, String> {
        if (cookieString.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (part in cookieString.split(";")) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq).trim()
            val v = part.substring(eq + 1).trim()
            if (k.isNotBlank() && v.isNotBlank()) {
                result[k] = v
            }
        }
        return result
    }
}
