package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.pipeline.PipelineError
import com.anonrode.downloader.pipeline.PipelineJournal
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.selects.select
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

interface BaseResolver {
    fun canResolve(url: String): Boolean
    suspend fun resolve(url: String, quality: String = "720p", depth: Int = 0): String?

    /**
     * Why the LAST resolve() attempt failed, or null when nothing is known.
     * The registry logs this per-attempt reason (hop journal, retry decision,
     * HostHealth.recordFail) instead of the GLOBAL HttpClient.lastFailure,
     * which is stale for resolvers running their own OkHttp clients (the
     * loadedfiles case: its private client swallows the real exception, so
     * the global showed an unrelated canceled search from 60s earlier).
     */
    fun lastResolveFailure(): String? = null
}

/**
 * TRUE-HOST dispatch gate for resolver claims (parsedHost retrofit, 2026-09-12).
 *
 * The historical form `url.lowercase().contains("locker.com")` claims a URL as
 * soon as the host STRING appears anywhere in it — and a URL can be crafted so
 * okhttp fetches a DIFFERENT host than the string shows:
 *   `https://vikingfile.com:443@evil.com/f` (userinfo — host ends at '@')
 *   `https://evil\.downloadwella.com/f`     (authority stops at the backslash)
 * Both pass the substring test, get claimed by the locker's resolver, and are
 * then fetched — and POSTed to — at evil. This gate compares each claim entry
 * against the host `HttpClient.parsedHost()` computes (okhttp3.HttpUrl's own
 * parse): gate and fetcher can no longer disagree about where bytes go. The
 * OTA terminal gate (RulesPipeline) already uses the same source of truth.
 *
 * Entry semantics (matching how the resolver lists are written):
 *  - contains '/'          -> host equals-or-subdomain of the host part AND
 *                             the raw URL contains the path part;
 *  - ends with '.'         -> label-prefix claim: the host starts with it or
 *                             contains ".<entry>" (dood. -> dood.to, sub.dood.to);
 *  - dotted domain         -> host equals it or is a sub-domain suffix of it;
 *  - dot-free fragment     -> substring of the HOST only (no path/query spoof).
 *
 * Unparseable URLs fall back to the legacy whole-string test on purpose: a URL
 * okhttp cannot parse cannot be fetched either, so this keeps dispatch behavior
 * identical for legacy edge inputs, while every FORGED URL (which parses fine
 * — that is the whole class) now hits the true-host rule.
 */
internal fun hostClaim(url: String, hosts: List<String>): Boolean {
    val lower = url.lowercase()
    val host = HttpClient.parsedHost(url)
    if (host == null) return hosts.any { it.isNotBlank() && lower.contains(it) }
    return hosts.any { e ->
        val entry = e.lowercase().trim()
        when {
            entry.isBlank() -> false
            entry.contains('/') -> {
                val h = entry.substringBefore('/')
                val path = "/" + entry.substringAfter('/')
                (host == h || host.endsWith(".$h")) && lower.contains(path)
            }
            entry.endsWith(".") -> host.startsWith(entry) || host.contains(".$entry")
            entry.contains('.') -> host == entry || host.endsWith(".$entry")
            else -> host.contains(entry)
        }
    }
}

object ResolverRegistry {
    const val RESOLVE_DEPTH_LIMIT = 6
    private const val NETWORK_RETRY_DELAY_MS = 1500L

    // The last resolver's per-attempt failure reason (null when the last
    // attempt succeeded or reported nothing). Filled by resolveInternal so the
    // depth-0 recordFail in resolve() sees the REAL cause, not the global
    // HttpClient.lastFailure (which LoadedfilesResolver's private OkHttp
    // client never updates).
    private var lastAttemptFailure: String? = null

    val RESOLVERS: List<BaseResolver> = listOf(
        VidbasicResolver,
        KissasianResolver,
        KisskhMegaplayResolver,
        VidmolyResolver,
        DownloadwellaResolver,
        LoadedfilesResolver,
        WildshareResolver,
        WaffiCloudResolver,
        StreamwishResolver,
        VidhideResolver,
        DoodstreamResolver,
        MixdropResolver,
        StreamtapeResolver,
        PixelDrainResolver,
        PlutoMoviesResolver,
        LightDLResolver,
        FivePlayResolver,
        VikingFileResolver,
        LulaCloudResolver,
        DramaGatewayResolver,
        NaijaVaultGatewayResolver,
        BloggerResolver,
        VidsrcResolver,
        EmbedResolver,
        GenericLockerResolver
    )

    suspend fun resolve(url: String, quality: String = "720p", depth: Int = 0, bypassHealth: Boolean = false): String? {
        // Cache + health apply ONCE per user-facing resolve (depth==0); the
        // recursive descent below stays uncached so gateway chains work.
        val cacheKey = com.anonrode.downloader.pipeline.ResolveCache.keyFor(url, quality)
        if (depth == 0) {
            com.anonrode.downloader.pipeline.ResolveCache.get(cacheKey)?.let { cached ->
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "cache", url = url, ok = true, ms = 0
                )
                return cached
            }
            // Manual retry taps grant ONE bypass of the health gate: the user
            // explicitly asked for a fresh attempt at a cooling-down host, and
            // the gate must not answer "skipped" before the request even fires.
            if (!bypassHealth && !com.anonrode.downloader.pipeline.HostHealth.isUsable(url)) {
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "health-gate", url = url, ok = false, ms = 0,
                    detail = "host dead or in backoff window — skipped without a request"
                )
                return null
            }
        }
        // A fresh user-facing resolve starts with no attempt history: a stale
        // reason from an EARLIER task must never reach this call's recordFail.
        if (depth == 0) lastAttemptFailure = null
        val result = resolveInternal(url, quality, depth)
        if (depth == 0) {
            val host = url.trim().substringAfter("://").substringBefore('/').substringBefore('?')
            if (result != null) {
                com.anonrode.downloader.pipeline.ResolveCache.put(cacheKey, result)
                com.anonrode.downloader.pipeline.HostHealth.recordOk(host)
            } else {
                val reason = lastAttemptFailure
                com.anonrode.downloader.pipeline.HostHealth.recordFail(
                    host,
                    rateLimited = reason?.contains("429") == true,
                    reason = reason
                )
            }
        }
        return result
    }

    /**
     * Race up to [maxConcurrency] locker candidates CONCURRENTLY and return
     * the first success; losers are cancelled mid-flight. Health-dead hosts
     * are filtered before launch. This is where the app beats the monolith:
     * a page embedding three lockers with two dead costs seconds, not the
     * full sequential walk.
     */
    suspend fun resolveAny(urls: List<String>, quality: String = "720p", maxConcurrency: Int = 3): String? {
        val candidates = urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .filter { com.anonrode.downloader.pipeline.HostHealth.isUsable(it) }
            .take(maxConcurrency.coerceIn(1, 6))
            .toList()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return resolve(candidates.first(), quality)

        return kotlinx.coroutines.supervisorScope {
            val deferreds = candidates.map { u ->
                async {
                    try { resolve(u, quality) } catch (_: Exception) { null }
                }
            }
            val winner = select<String?> {
                deferreds.forEach { d ->
                    d.onAwait { it }
                }
            }
            deferreds.forEach { it.cancel() }
            winner
        }
    }

    private suspend fun resolveInternal(url: String, quality: String = "720p", depth: Int = 0): String? {
        if (depth > RESOLVE_DEPTH_LIMIT) {
            com.anonrode.downloader.pipeline.PipelineJournal.hop(
                site = "", stage = "registry", url = url, ok = false, ms = 0,
                detail = PipelineError.BudgetExceeded("depth", 0).message ?: "depth limit"
            )
            return null
        }
        val trimmed = url.trim()
        for (resolver in RESOLVERS) {
            if (resolver.canResolve(trimmed)) {
                val start = System.currentTimeMillis()
                val direct = resolveWithRetry(resolver, trimmed, quality, depth)
                val elapsed = System.currentTimeMillis() - start
                if (!direct.isNullOrBlank()) {
                    com.anonrode.downloader.pipeline.PipelineJournal.hop(
                        site = "", stage = "crack:${resolver::class.simpleName}",
                        url = trimmed, ok = true, ms = elapsed,
                        detail = "-> ${direct.take(80)}"
                    )
                    // Reference parity (resolvers.py:2185): an intermediate
                    // result that differs from the input flows BACK through
                    // the registry — gateway chains (dramarain download?link=
                    // -> waffi.cloud?preview) need the second pass to crack
                    // the real file URL. Skipped only when the SAME resolver
                    // re-claims a clean media path (its final answer).
                    if (direct != trimmed && depth < RESOLVE_DEPTH_LIMIT) {
                        val path = direct.substringBefore('?').substringBefore('#').lowercase()
                        val mediaPath = isDirectMediaUrl(path)
                        val sameResolverReclaims = resolver.canResolve(direct)
                        if (!(sameResolverReclaims && mediaPath)) {
                            val deeper = resolveInternal(direct, quality, depth + 1)
                            if (!deeper.isNullOrBlank()) return deeper
                        }
                    }
                    return direct
                }
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "crack:${resolver::class.simpleName}",
                    url = trimmed, ok = false, ms = elapsed,
                    detail = resolver.lastResolveFailure()?.take(120) ?: ""
                )
                if (resolver.lastResolveFailure() != null) {
                    lastAttemptFailure = resolver.lastResolveFailure()
                }
            }
        }
        return null
    }

    // A dropped connection (DNS/reset/timeout) is not proof the host is gone:
    // retry network-class failures up to 3 times, but fail fast when the host
    // answered (HTTP error) or the page simply held nothing (clean null) —
    // monolith parity (resolvers.py registry retry loop). The before/after
    // comparison uses the RESOLVER's per-attempt reason: the global
    // HttpClient.lastFailure is stale for resolvers with their own OkHttp
    // client (loadedfiles), which would block network-class retries forever.
    private suspend fun resolveWithRetry(resolver: BaseResolver, url: String, quality: String, depth: Int): String? {
        var attempt = 0
        while (true) {
            val before = resolver.lastResolveFailure()
            val result = resolver.resolve(url, quality, depth)
            if (!result.isNullOrBlank()) return result
            val current = resolver.lastResolveFailure()
            if (attempt >= 2 || !isNetworkClassFailure(current, before)) return result
            attempt++
            com.anonrode.downloader.util.DebugLog.resolve("network-class failure, retry #$attempt ${resolver::class.simpleName}")
            delay(NETWORK_RETRY_DELAY_MS)
        }
    }

    private fun isNetworkClassFailure(current: String?, before: String?): Boolean {
        if (current == null || current == before) return false
        if (current.startsWith("HTTP ")) return false
        return true
    }
}

// -------------------------------------------------------------
// 1. VidbasicResolver (AES-256-CBC Decryptor)
// -------------------------------------------------------------
object VidbasicResolver : BaseResolver {
    private val KEY = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
    private val IV = "5259228356829423".toByteArray(Charsets.UTF_8)
    private val HOSTS = listOf("vidbasic.", "vidb.top", "embedload.cfd")

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, HOSTS) && !low.endsWith(".m3u8") && !low.endsWith(".mp4")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            // Reference parity (resolvers.py:557): the page is fetched WITHOUT a
            // Referer header.
            val html = HttpClient.getText(url) ?: return null

            // 1) this page already carries the encrypted payload (3rdplayer.html)
            val direct = decryptPayload(html)
            if (!direct.isNullOrBlank()) return direct

            // 1b) server-selector layout: vidb.top serves a multi-server page whose
            // data-video / data-src / iframe attrs point at EXTERNAL mirror embeds
            // (streamwish, vidhide, doodstream, streamtape). Try resolving the
            // candidates via the registry, falling through to the next mirror if
            // one is dead/expired (resolvers.py:571-589).
            val cands = mutableListOf<String>()
            val attrMatcher = Pattern.compile("""data-(?:video|src|embed|link)=["']([^"']+)["']""").matcher(html)
            while (attrMatcher.find()) cands.add(attrMatcher.group(1) ?: "")
            val iframeSrcMatcher = Pattern.compile("""<iframe[^>]+src=["']([^"']+)["']""").matcher(html)
            while (iframeSrcMatcher.find()) cands.add(iframeSrcMatcher.group(1) ?: "")
            val seen = mutableSetOf<String>()
            for (raw in cands) {
                var cand = raw.trim().replace("&amp;", "&")
                cand = HttpClient.safeResolveUri(url, cand)
                if (!cand.startsWith("http") || cand == url || !seen.add(cand)) continue
                for (other in ResolverRegistry.RESOLVERS) {
                    if (other is VidbasicResolver) continue
                    try {
                        if (other.canResolve(cand)) {
                            val resolved = ResolverRegistry.resolve(cand, quality, depth + 1)
                            if (!resolved.isNullOrBlank()) return resolved
                            break
                        }
                    } catch (_: Exception) {
                        continue
                    }
                }
            }

            // 2) embed page points at /3rdplayer.html — fetch and decrypt
            val mvMatcher = Pattern.compile("""data-video=["']([^"']+)["']""").matcher(html)
            if (mvMatcher.find()) {
                var playerUrl = mvMatcher.group(1) ?: ""
                playerUrl = HttpClient.safeResolveUri(url, playerUrl)
                val playerHtml = HttpClient.getText(playerUrl, referer = url)
                if (!playerHtml.isNullOrBlank()) {
                    val pDirect = decryptPayload(playerHtml)
                    if (!pDirect.isNullOrBlank()) return pDirect
                }
            }

            // 3) embedload.cfd wrapper iframes the real vidbasic host. This
            // recursion is our own counter capped at 3 so A->B->A cycles end
            // instead of spinning through the registry depth limit
            // (resolvers.py:606-611).
            val miMatcher = Pattern.compile("""<iframe[^>]+src=["']([^"']*(?:vidbasic|vidb\.top)[^"']*)["']""").matcher(html)
            if (miMatcher.find() && depth < 3) {
                var inner = miMatcher.group(1) ?: ""
                inner = HttpClient.safeResolveUri(url, inner)
                if (inner != url) {
                    val innerDirect = resolve(inner, quality, depth + 1)
                    if (!innerDirect.isNullOrBlank()) return innerDirect
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun decryptPayload(html: String): String? {
        // Attribute order varies between deployments -- try crypto-first,
        // then a bare data-value tag.
        var m = Pattern.compile("""data-name=["']crypto["'][^>]*?data-value=["']([^"']+)["']""").matcher(html)
        if (!m.find()) {
            m = Pattern.compile("""data-value=["']([^"']+)["']""").matcher(html)
            if (!m.find()) return null
        }
        val b64 = m.group(1) ?: return null
        try {
            val cipherBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(KEY, "AES")
            val ivSpec = IvParameterSpec(IV)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
            if (decrypted.startsWith("http") &&
                (decrypted.contains(".m3u8") || decrypted.contains(".mp4") || decrypted.contains(".mkv"))
            ) {
                return decrypted
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 2. KissasianResolver
// -------------------------------------------------------------
object KissasianResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return hostClaim(url, listOf("kissasian9.ro")) && lower.contains("/kisskh/") && !lower.endsWith(".m3u8")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val m = Pattern.compile("""sourceUrl"\s*:\s*"([^"]+)""").matcher(html)
            if (m.find()) {
                val apiPath = m.group(1) ?: return null
                val apiUrl = HttpClient.safeResolveUri(url, apiPath)
                val apiJson = HttpClient.getText(apiUrl, referer = url) ?: return null
                val obj = JSONObject(apiJson)
                val src = obj.optString("source")
                if (src.startsWith("http") && src.contains(".m3u8")) {
                    return src
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 3. KisskhMegaplayResolver
// -------------------------------------------------------------
object KisskhMegaplayResolver : BaseResolver {
    private val HOSTS = listOf(
        "kisskh.megaplay.", "megaplays.se", "embtaku.", "takuembed.",
        "anihdplay.", "gogohd.", "megaplay.", "animesama.", "tamilembed.",
        "gogoanime.me.uk", "vkspeed.com", "ansembed.net", "sibnet.ru"
    )

    override fun canResolve(url: String): Boolean {
        if (url.contains("/playlist.php") || url.contains("/api/")) return false
        val lower = url.lowercase()
        return hostClaim(url, HOSTS) || lower.contains("/kisskh/")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            // tamilembed serves the real player under HTTP 404 — accept the body
            // (monolith parity, resolvers.py:693-698). LIVE (2026-08): tamilembed
            // now 403s when the fetch carries a referer; it needs referer=null.
            val html = HttpClient.getText(
                url,
                referer = if (url.contains("tamilembed")) null else url,
                acceptStatus = if (url.contains("tamilembed")) setOf(404) else emptySet()
            ) ?: return null

            // Inner iframe (tamilembed / blogger)
            val doc = Jsoup.parse(html, url)
            val innerIframe = doc.selectFirst("iframe[src]")
            if (innerIframe != null) {
                val rawSrc = innerIframe.attr("src")
                val src = HttpClient.safeResolveUri(url, rawSrc)
                if (src != url && !src.startsWith("javascript:")) {
                    if (src.contains("blogger.com")) return src
                    val nested = ResolverRegistry.resolve(src, quality, depth + 1)
                    if (!nested.isNullOrBlank()) return nested
                }
            }

            // animesama layout
            val smMatcher = Pattern.compile("""const\s+STREAM\s*=\s*["']([^"']+)["']""").matcher(html)
            if (smMatcher.find()) return smMatcher.group(1)?.replace("\\/", "/")

            // megaplays / takuembed layout
            val defMatcher = Pattern.compile("""var\s+defaultUrl\s*=\s*["']([^"']+)["']""").matcher(html)
            if (defMatcher.find()) {
                val targetUrl = defMatcher.group(1)?.replace("\\/", "/") ?: ""
                val pbMatcher = Pattern.compile("""var\s+proxyBase\s*=\s*["']([^"']+)["']""").matcher(html)
                if (pbMatcher.find()) {
                    val proxyBase = pbMatcher.group(1) ?: ""
                    return proxyBase + URLEncoder.encode(targetUrl, "UTF-8")
                }
                return targetUrl
            }

            // megaplay.buzz getSources API
            // LIVE (2026-08): the API keys on data-id (the file id), NOT
            // data-realid / the /stream/ URL id, and 403s without the
            // X-Requested-With: XMLHttpRequest header.
            if (url.contains("megaplay")) {
                val dataIdMatcher = Pattern.compile("""data-id=["'](\d+)["']""").matcher(html)
                val realIdMatcher = Pattern.compile("""data-realid=["'](\d+)["']""").matcher(html)
                val epIdMatcher = Pattern.compile("""/stream/(?:s-\d+/)?(\d+)""").matcher(url)
                val streamId = when {
                    dataIdMatcher.find() -> dataIdMatcher.group(1)
                    realIdMatcher.find() -> realIdMatcher.group(1)
                    epIdMatcher.find() -> epIdMatcher.group(1)
                    else -> null
                }
                if (!streamId.isNullOrBlank()) {
                    val apiUrl = "https://megaplay.buzz/stream/getSources?id=$streamId"
                    val apiJson = HttpClient.getText(
                        apiUrl,
                        referer = url,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
                    if (!apiJson.isNullOrBlank()) {
                        val data = JSONObject(apiJson)
                        val fileUrl = data.optJSONObject("sources")?.optString("file")
                        if (!fileUrl.isNullOrBlank()) return fileUrl
                    }
                }
            }

            // sibnet.ru shell: the source is a RELATIVE path in inline JS --
            // player.src([{src: "/v/<hash>/<id>.mp4", ...}]) -- which 302s to a
            // signed CDN URL. Absolute-URL regexes never see it.
            val sibMatcher = Pattern.compile("""player\.src\(\[\{src:\s*["']([^"']+)["']""").matcher(html)
            if (sibMatcher.find() && sibMatcher.group(1)?.startsWith("/") == true) {
                return "https://video.sibnet.ru" + sibMatcher.group(1)
            }

            val direct = extractM3u8FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractM3u8FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 4. BloggerResolver (batchexecute RPC -> direct googlevideo MP4)
// -------------------------------------------------------------
object BloggerResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("blogger.com")) && (low.contains("video.g") || low.contains("token="))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url) ?: return null

            val fsidMatcher = Pattern.compile("""FdrFJe":"([^"]+)""").matcher(html)
            if (!fsidMatcher.find()) return null
            val fSid = fsidMatcher.group(1) ?: return null

            val blMatcher = Pattern.compile("""boq_bloggeruiserver_[^'", ]+""").matcher(html)
            if (!blMatcher.find()) return null
            val bl = blMatcher.group(0)

            val tokenMatcher = Pattern.compile("""[?&]token=([^&]+)""").matcher(url)
            if (!tokenMatcher.find()) return null
            val token = tokenMatcher.group(1) ?: return null

            val rpcUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=${URLEncoder.encode(fSid, "UTF-8")}&bl=${URLEncoder.encode(bl, "UTF-8")}&hl=en-US&rt=c"
            val fReq = """[[["WcwnYd","[\"$token\"]",null,"generic"]]]"""

            val form = FormBody.Builder()
                .add("f.req", fReq)
                .build()

            val req = Request.Builder()
                .url(rpcUrl)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", "https://www.blogger.com/")
                .header("Origin", "https://www.blogger.com")
                .header("X-Same-Domain", "1")
                .post(form)
                .build()

            HttpClient.shared.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = HttpClient.cappedText(res) ?: return null

                val urlMatches = Pattern.compile("""(https://[^"]+googlevideo\.com[^"]+)""").matcher(body)
                val urls = mutableListOf<String>()
                while (urlMatches.find()) {
                    var u = urlMatches.group(1) ?: continue
                    u = u.replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/").replace("""\""", "")
                    urls.add(u)
                }

                // Prefer 720p (itag 22), then 360p (itag 18)
                val itag22 = urls.find { it.contains("itag=22") }
                if (itag22 != null) return itag22
                val itag18 = urls.find { it.contains("itag=18") }
                if (itag18 != null) return itag18
                if (urls.isNotEmpty()) return urls.first()
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 5. VidsrcResolver (vidsrc / nepu watch pages -> token-stamped HLS)
// -------------------------------------------------------------
object VidsrcResolver : BaseResolver {
    private val HOSTS = listOf(
        "vidsrc.mov", "vidsrc.me", "vidsrc.net", "vidsrc.cc", "vidsrc.to",
        "vidsrc.in", "vidsrc.pm", "vidsrc.xyz", "vsembed.ru",
        "cloudorchestranova.com", "data.vidsrcme.ru",
        "nepu.gd/watch", "nepu.to/watch"
    )
    private val TMDB_PATTERN = Pattern.compile("""/(?:movie|tv)/(\d+)(?:/(\d+)/(\d+))?""")
    private val ORIGIN_PATTERN = Pattern.compile("""https?://[^/]+""")

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            var embedUrl = url.replace("nepu.to/", "nepu.gd/")
            if (embedUrl.contains("nepu.gd/watch")) {
                // Watch pages hide the player in iframe#playerFrame (or a
                // vidsrc-src iframe) — hop through it to the embed URL if present.
                try {
                    val html = HttpClient.getText(embedUrl, referer = "https://nepu.gd/")
                    if (!html.isNullOrBlank()) {
                        val doc = Jsoup.parse(html, embedUrl)
                        val iframe = doc.selectFirst("iframe#playerFrame")
                            ?: doc.selectFirst("iframe[src*=vidsrc]")
                        if (iframe != null && iframe.attr("src").isNotBlank()) {
                            embedUrl = HttpClient.safeResolveUri(embedUrl, iframe.attr("src"))
                        }
                    }
                } catch (_: Exception) {}
            }

            // The embed URL carries the TMDB id (and season/episode for TV).
            val m = TMDB_PATTERN.matcher(embedUrl)
            if (!m.find()) return null
            val tmdb = m.group(1) ?: return null
            val apiUrl = if (embedUrl.contains("/tv/")) {
                val season = m.group(2) ?: return null
                val episode = m.group(3) ?: return null
                "https://data.vidsrcme.ru/api.php?type=tv&tmdb=$tmdb&season=$season&episode=$episode&stream_urls"
            } else {
                "https://data.vidsrcme.ru/api.php?type=movie&tmdb=$tmdb&stream_urls"
            }

            val json = HttpClient.getText(apiUrl, referer = "https://cloudorchestranova.com/") ?: return null
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return null
            val streamUrl: String = when (val su = data.opt("stream_urls")) {
                is JSONArray -> if (su.length() > 0) su.getString(0) else null
                is String -> {
                    // Encrypted: the key lives in a freshly-built wasm module
                    // that rotates every ~5 minutes, so fetch it and extract
                    // the key from its own instruction stream.
                    val wasmUrl = root.optJSONObject("vs")?.optString("wasm_url") ?: return null
                    val wasm = HttpClient.get(wasmUrl, referer = "https://cloudorchestranova.com/").use { res ->
                        if (res.isSuccessful) HttpClient.cappedBytes(res) else null
                    } ?: return null
                    val key = VidsrcWasmCrypto.extractKey(wasm) ?: return null
                    VidsrcWasmCrypto.decrypt(su, key).firstOrNull()
                }
                else -> null
            } ?: return null

            // Playlist URLs are CDN-gated by an IP-bound JWT issued by the
            // origin's generate.php; without it the CDN answers 401. A URL that
            // already carries a token is authoritative — stripping and
            // re-stamping rotates a valid token into a dead one.
            if (streamUrl.contains("token=")) return streamUrl
            val om = ORIGIN_PATTERN.matcher(streamUrl)
            val origin = if (om.find()) om.group() else return null
            val token = HttpClient.getText("$origin/generate.php")?.trim().orEmpty()
            // Empty means generate.php refused us (rate-limit/window) — the
            // tokenless master is a guaranteed CDN 401, so fail this candidate
            // outright and let another mirror win instead of handing the player
            // a URL it cannot open.
            if (token.isEmpty()) return null
            return if (streamUrl.contains("__TOKEN__")) streamUrl.replace("__TOKEN__", token)
            else streamUrl + (if (streamUrl.contains("?")) "&" else "?") + "token=$token"
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 6. LightDLResolver
// -------------------------------------------------------------
object LightDLResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        if (url.contains("/api/download/")) return false
        return hostClaim(url, listOf("lightdl.cc"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val code = url.trimEnd('/').substringAfterLast('/')
            if (code.isBlank()) return null
            val fileJson = HttpClient.getText("https://lightdl.cc/api/files/code/$code", referer = url) ?: return null
            val fileId = JSONObject(fileJson).optJSONObject("file")?.optString("id") ?: return null

            val req = Request.Builder()
                .url("https://lightdl.cc/api/files/$fileId/download-token")
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", url)
                .post(FormBody.Builder().build())
                .build()

            HttpClient.shared.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = HttpClient.cappedText(res) ?: return null
                val obj = JSONObject(body)
                return obj.optString("downloadUrl")
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 7. FivePlayResolver
// -------------------------------------------------------------
object FivePlayResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("5play.cc"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://dramakey.cc/") ?: return null
            return extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 8. VikingFileResolver
// -------------------------------------------------------------
object VikingFileResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("vikingfile.com")) && !low.endsWith(".mp4") && !low.endsWith(".mkv") && !low.endsWith(".m3u8")
    }

    // The Sep-2 hardening blanket-rejected every redirect whose Location sat
    // on an R2 storage host, because the original misdirect (an unsigned
    // storage URL) answered 401 and burned 3MB bodies. vikingfile has since
    // made the presigned R2 URL the REAL serving path for /d/ links
    // (live-verified: 302 → R2 → 206 video/matroska, MKV magic), so the
    // hostname alone is no longer evidence of a misdirect. Decide by
    // probing, not by name: accept the Location only when it actually
    // serves media bytes (see HttpClient.probeTerminal). A 401/HTML target
    // — the original misdirect — still fails here and is rejected.
    // (Formerly a hand-copied no-redirect probe; unified onto probeTerminal
    // so all terminal checks share ONE accept matrix, ONE timeout, and the
    // cancellable in-flight registry — the old copy was raw execute(): not
    // cancellable by pause and had no call timeout.)
    private fun probeStorageLocation(loc: String): String? {
        val target = HttpClient.safeUrl(loc)
        val tp = HttpClient.probeTerminal(target)
        if (tp != null && tp.totalBytes != null) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "VikingFileResolver: storage Location serves media (${tp.code}, ct=${tp.contentType}, size=${tp.totalBytes}) — accepting as direct file"
            )
            return target
        }
        com.anonrode.downloader.util.DebugLog.resolve(
            "VikingFileResolver: rejected misdirect to storage backend (probe=${tp?.code ?: "network-fail"}): ${loc.take(120)}"
        )
        return null
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            // vikingfile.com has TWO URL shapes:
            //
            // 1. /f/<id>  → 302 redirect to /d/<token>/<filename>.mkv
            //    The /f/<id> page itself is just a token-mint; the actual
            //    file is at the /d/<token>/<file> URL. Live-verified: the
            //    page returns 302 with Location: /d/ZlVDRbze6i/...mkv
            //    (HTML body has a 0-second meta-refresh too).
            //
            // 2. /d/<token>/<file>  → 206 Partial Content on a Range probe
            //    (this IS the direct file URL — engine should go to aria2c).
            //
            // The OLD code did the wrong thing on both:
            //   - On /f/<id>, it tried to parse the body for window.location
            //     (but the body is HTML, the redirect is in the HTTP header
            //     AND a meta refresh — neither was the old regex's pattern).
            //     Then cappedText hit 3MB, saw HTML, called it "not a page",
            //     and 26 times on naijavault.com/dl-b8902199 returned
            //     "body from uz.vikingfile.com truncated at 3145728 bytes
            //     (not a page — likely a misdirected file fetch)".
            //   - On /d/<token>/<file>, it should have been treated as
            //     already-direct and skipped the resolver entirely. Instead
            //     the regex didn't find window.location and the resolver
            //     returned null.
            //
            // The FIX: do a no-redirect probe. Three outcomes:
            //   a) 200/206 with Content-Length > 0 → the URL IS the file
            //      (already on the /d/... path). Return as-is.
            //   b) 302 with Location → the URL is a token-mint redirector.
            //      Follow the Location header and return that. Today the
            //      /d/<token>/<file> hop itself 302s to a PRESIGNED R2 URL
            //      that serves the bytes — see probeStorageLocation below
            //      for how a storage Location is accepted vs rejected.
            //   c) HTML page that doesn't redirect → the URL is a true
            //      landing page with embedded player (rare). Try the
            //      legacy window.location regex as a final fallback.
            //
            // No 3MB body fetch here. The whole exchange fits in headers
            // (~500 bytes) and a 1-byte probe body, so the resolver
            // cost is ~600 bytes per call — no data waste.
            // Unified terminal gate (HttpClient.probeTerminal) — replaces the
            // old inline no-redirect copy so ALL terminal checks share one
            // accept matrix, one timeout, and the cancellable registry.
            // Failure semantics preserved: the old code let a network throw
            // skip everything (return null) — it did NOT fall through to (c),
            // so a null probe keeps that behavior.
            val tp = HttpClient.probeTerminal(url, referer = "https://www.naijavault.com/")
                ?: return null
            // Case (a): the URL is already the direct file
            if (tp.totalBytes != null) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "VikingFileResolver: $url is the direct file (Range probe ${tp.code}, size=${tp.totalBytes}) — returning as-is"
                )
                return url
            }
            // Case (b): the URL is a token-mint redirector
            val rawLoc = tp.location
            if (tp.code in 301..308 && !rawLoc.isNullOrBlank()) {
                // A 3xx Location may be RELATIVE ("Location: /d/<tok>/<f>.mkv")
                // — the old copy fed it to OkHttp/probes raw, which only worked
                // because vikingfile answers with absolute Locations. Absolutize
                // explicitly so relative hops resolve too.
                val loc = HttpClient.safeResolveUri(url, rawLoc)
                val lowLoc = loc.lowercase()
                if (lowLoc.contains("r2.cloudflarestorage.com") ||
                    lowLoc.contains(".r2.dev/") ||
                    lowLoc.contains("cloudflarestorage.com/")) {
                    return probeStorageLocation(loc)
                }
                com.anonrode.downloader.util.DebugLog.resolve(
                    "VikingFileResolver: followed ${tp.code} → ${loc.take(120)}"
                )
                return HttpClient.safeUrl(loc)
            }
            // Case (c): the URL is a true landing page. Try the legacy
            // window.location regex as a last resort.
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/") ?: return null
            val m = Pattern.compile("""(?:window\.location|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                val loc = m.group(1) ?: return null
                val lowLoc = loc.lowercase()
                if (lowLoc.contains("r2.cloudflarestorage.com") ||
                    lowLoc.contains(".r2.dev/") ||
                    lowLoc.contains("cloudflarestorage.com/")) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "VikingFileResolver: rejected misdirect to storage backend: ${loc.take(120)}"
                    )
                    return null
                }
                return loc
            }
            return extractMp4FromHtml(html) ?: extractM3u8FromHtml(html)
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 9. LulaCloudResolver
// -------------------------------------------------------------
object LulaCloudResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("lulacloud.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            // lulacloud.com serves a broken CA chain (live-verified verify
            // code 20) — permissive client, same bounded scope as the
            // downloadwella family.
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/", permissive = true) ?: return null
            val m = Pattern.compile("""(?:window\.location|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                return m.group(1)
            }
            return extractMp4FromHtml(html) ?: extractM3u8FromHtml(html)
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 10. DramaGatewayResolver
// -------------------------------------------------------------
object DramaGatewayResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("dramarain.com", "dramakey.cc")) && low.contains("/download")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val host = HttpClient.safeHost(url, "dramarain.com")
            val html = HttpClient.getText(url, referer = "https://$host/") ?: return null
            val m = Pattern.compile("""window\.location\.href\s*=\s*"([^"]+)""").matcher(html)
            if (m.find()) {
                return m.group(1)
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 11. NaijaVaultGatewayResolver
// -------------------------------------------------------------
object NaijaVaultGatewayResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("naijavault.com")) && (low.contains("/dl-") || low.contains("/temp/"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/") ?: return null
            val soup = Jsoup.parse(html, url)
            val btn = soup.selectFirst("a.download-btn, a[href*='vikingfile'], a[href*='lulacloud']")
            if (btn != null) {
                return btn.attr("abs:href")
            }
            val m = Pattern.compile("""var\s+downloadURL\s*=\s*"([^"]+)""").matcher(html)
            if (m.find()) {
                return m.group(1)
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 12. EmbedResolver
// -------------------------------------------------------------
object EmbedResolver : BaseResolver {
    private val KNOWN = listOf("megaplay.buzz", "megaplay.cc", "tamilembed.lol", "embedsito.com")

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, KNOWN)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            return extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 13. PlutoMoviesResolver
// -------------------------------------------------------------
object PlutoMoviesResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        // /series/ episode pages carry the same download link as movies —
        // without them the Vincenzo-style episode taps failed with
        // "resolver chain EMPTY" (live-verified 2026-08-21).
        return hostClaim(url, listOf(
            "dl.plutomovies.com",
            "plutomovies.com/movie/",
            "plutomovies.com/series/"
        ))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://plutomovies.com/") ?: return null
            val m = Pattern.compile("""location\.href\s*=\s*['"](https?://[^'"]+)['"]""").matcher(html)
            if (m.find()) {
                val dest = m.group(1) ?: ""
                if (dest.isNotBlank() && !dest.equals(url, ignoreCase = true) && !isRootLockerDomain(dest)) return dest
            }

            val soup = Jsoup.parse(html, url)
            // /series/ episode pages embed the download as a plain
            // dl.plutomovies.com anchor (live-verified: Vincenzo S01E05..E20)
            val dlAnchor = soup.selectFirst("a[href*='dl.plutomovies.com']")
            if (dlAnchor != null) {
                val href = dlAnchor.attr("abs:href")
                if (href.isNotBlank() && !href.equals(url, ignoreCase = true)) {
                    // The dl anchor often lands on an intermediate page whose
                    // own locker/download link is the real file — descend a
                    // bounded depth (monolith parity: PlutoMoviesResolver
                    // follows the anchor chain).
                    if (depth < 3) {
                        val nested = resolve(href, quality, depth + 1)
                        if (!nested.isNullOrBlank()) return nested
                    }
                    return href
                }
            }
            val btn = soup.selectFirst("a[href*='kissorgrab.com'], a[href*='/download/'], a.download-btn, a[href*='download'], a[href*='.mp4'], a[href*='.mkv']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank() && !href.equals(url, ignoreCase = true) && !isRootLockerDomain(href)) return href
            }

            findDirectMediaUrl(html)?.let { return it }

            // Directory pages expose no download link at all — only links to
            // child /series/ pages. Descend into the most specific child and
            // let the registry recursion walk hub → season → episode page →
            // dl anchor instead of failing cleanly (live-verified 2026-08-22:
            // /series/324767/all-american-2018-tv-series → season-7 listing →
            // s07-e01 → dl.plutomovies.com/...-mkv).
            val children = soup.select("a[href*='/series/']")
                .map { it.attr("abs:href").substringBefore('#') }
                .filter { href -> href.isNotBlank() && !href.equals(url, ignoreCase = true) }
                .distinct()
            if (children.isNotEmpty()) {
                val episodeish = children.firstOrNull { child ->
                    child.contains(Regex("""s\d{1,2}[-_]?e\d{1,2}|episode-\d{1,3}""", RegexOption.IGNORE_CASE))
                }
                val seasonish = children.firstOrNull { child ->
                    child.contains(Regex("""season-\d{1,2}""", RegexOption.IGNORE_CASE))
                }
                return episodeish ?: seasonish ?: children.first()
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 14. DownloadwellaResolver
// -------------------------------------------------------------
object DownloadwellaResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("downloadwella.com", "wetafiles.com", "kissorgrab.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            // Permissive client: wetafiles.com omits its TLS intermediate and
            // kissorgrab.com serves an invalid cert — strict verification
            // fails every crack on those hosts (live-verified). Scoped here
            // only; the shared client stays strict for everything else.
            val html = HttpClient.getText(url, referer = url, permissive = true) ?: return null
            val doc = Jsoup.parse(html, url)
            val formEl = doc.selectFirst("form")

            // Movie pages of this locker family sometimes render with NO form
            // at all (live-verified 2026-09-11: downloadwella.com/9mktxnflcqtc/
            // …DC.(THENKIRI.COM).mkv.html?preview has zero <form>/<input>
            // tags, while series pages carry the classic F1 form). The server
            // still cracks them when the standard F1 body is POSTed with the
            // file id taken from the URL's first path segment — so fall back
            // to the synthetic body instead of returning null; the response
            // scan below finds the /d/ direct anchor either way.
            val formAction = formEl?.attr("abs:action").orEmpty().ifBlank { url }

            // LIVE (2026-08): the server rejects the POST when method_free is
            // forced to "Free Download" — submit every form input verbatim
            // (including method_free="" as rendered), exactly like a browser.
            val formBuilder = FormBody.Builder()
            var hasInputs = false
            if (formEl != null) {
                for (inp in formEl.select("input[name]")) {
                    formBuilder.add(inp.attr("name"), inp.attr("value"))
                    hasInputs = true
                }
            }
            if (!hasInputs) {
                val fileId = try {
                    URI(url).path.trim('/').split('/').firstOrNull { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                if (fileId.isNullOrBlank()) return null
                formBuilder.add("op", "download2")
                formBuilder.add("id", fileId)
                formBuilder.add("rand", "")
                formBuilder.add("referer", url)
                formBuilder.add("method_free", "")
                formBuilder.add("method_premium", "")
            }

            val form = formBuilder.build()
            val req = Request.Builder()
                .url(formAction)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", url)
                .post(form)
                .build()

            HttpClient.permissiveClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = HttpClient.cappedText(res) ?: return null

                val directMedia = findDirectMediaUrl(body)
                if (!directMedia.isNullOrBlank() && !directMedia.equals(url, ignoreCase = true) && !isRootLockerDomain(directMedia)) {
                    return directMedia
                }

                val postDoc = Jsoup.parse(body, url)
                val directAnchor = postDoc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href")
                    if (isDirectMediaUrl(href) || (href.contains("/d/") && !href.endsWith(".html"))) href else null
                }.firstOrNull { !it.equals(url, ignoreCase = true) && !isRootLockerDomain(it) }

                if (!directAnchor.isNullOrBlank()) return directAnchor

                // Step 2 form if present
                val step2Form = postDoc.selectFirst("form[name='F1'], form")
                if (step2Form != null && step2Form.select("input[name='op']").isNotEmpty()) {
                    val step2Builder = FormBody.Builder()
                    for (inp in step2Form.select("input[name]")) {
                        step2Builder.add(inp.attr("name"), inp.attr("value"))
                    }
                    val step2Req = Request.Builder()
                        .url(step2Form.attr("abs:action").ifBlank { formAction })
                        .header("User-Agent", HttpClient.DEFAULT_UA)
                        .header("Referer", url)
                        .post(step2Builder.build())
                        .build()
                    HttpClient.permissiveClient.newCall(step2Req).execute().use { res2 ->
                        if (res2.isSuccessful) {
                            val body2 = HttpClient.cappedText(res2) ?: ""
                            val direct2 = findDirectMediaUrl(body2)
                            if (!direct2.isNullOrBlank() && !isRootLockerDomain(direct2)) return direct2
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 15. LoadedfilesResolver
// -------------------------------------------------------------
object LoadedfilesResolver : BaseResolver {
    private val HOST_RE = Pattern.compile("""loadedfiles\.[a-z0-9-]+""", Pattern.CASE_INSENSITIVE)

    // The real exception behind the last failed resolve() (its own OkHttp
    // client never touches the global HttpClient.lastFailure, so the registry
    // would otherwise log a stale unrelated failure — see BaseResolver).
    @Volatile private var lastResolveError: String? = null

    override fun lastResolveFailure(): String? = lastResolveError

    // loadedfiles keeps switching TLDs (.st / .net / .org / ...) while every host
    // serves the same file hashes. Pinning one TLD breaks whenever that host goes
    // dark, so try the last host that worked, then the link's own TLD, then the
    // known fallbacks. Ported from the monolith's _candidate_hosts (resolvers.py).
    private val FALLBACK_TLDS = listOf("st", "net", "org", "to", "com")
    @Volatile private var lastWorkingHost: String? = null

    private fun rewriteHost(text: String, host: String): String =
        HOST_RE.matcher(text).replaceAll(host)

    private fun candidateHosts(url: String): List<String> {
        val m = HOST_RE.matcher(url.lowercase())
        val urlHost = if (m.find()) m.group() else null
        val hosts = LinkedHashSet<String>()
        lastWorkingHost?.let { hosts.add(it) }
        urlHost?.let { hosts.add(it) }
        FALLBACK_TLDS.forEach { hosts.add("loadedfiles.$it") }
        return hosts.toList()
    }

    override fun canResolve(url: String): Boolean {
        val host = HttpClient.parsedHost(url)
        return if (host != null) HOST_RE.matcher(host).find()
        else HOST_RE.matcher(url.lowercase()).find()
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            // The token chain needs the shared client's longer read timeout (a
            // slow wait page must not abort the hop), but the host-candidate
            // probe is pure liveness: a dead host should fail in 5s instead of
            // burning the shared 15s per candidate before the next TLD is tried.
            val noRedirectClient = HttpClient.shared.newBuilder().followRedirects(false).build()
            val probeClient = HttpClient.shared.newBuilder()
                .followRedirects(false)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val slowProbeClient = HttpClient.shared.newBuilder()
                .followRedirects(false)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // Find a host that actually answers, then run the token chain on it.
            // The probe must record the host that ANSWERED, not the one
            // requested: loadedfiles.org is a 301 shell for loadedfiles.net,
            // and recording .org poisoned every later ?pt= referer and host
            // rewrite — the server then rotates tokens forever and the whole
            // 9jarocks path died for hours (live-verified 2026-08-21).
            val hosts = candidateHosts(url)
            var currUrl: String? = null
            for (host in hosts) {
                val candidate = HttpClient.safeUrl(rewriteHost(url, host))
                val effective = probeEffectiveUrl(probeClient, candidate)
                if (effective != null) {
                    lastWorkingHost = effective.substringAfter("://").substringBefore('/').lowercase()
                    currUrl = effective
                    break
                }
            }
            // Every candidate failed the fast probe — a merely SLOW host may
            // still answer past 5s, so give the same candidates one slower pass
            // before declaring the domain dead (a truly dead host now costs
            // 5s x N candidates + 10s x N, not 15s x N).
            if (currUrl == null) {
                for (host in hosts) {
                    val candidate = HttpClient.safeUrl(rewriteHost(url, host))
                    val effective = probeEffectiveUrl(slowProbeClient, candidate)
                    if (effective != null) {
                        lastWorkingHost = effective.substringAfter("://").substringBefore('/').lowercase()
                        currUrl = effective
                        break
                    }
                }
            }
            if (currUrl == null) {
                android.util.Log.w("AnonDownload", "Loadedfiles: no live host")
                return null
            }

            var ptHops = 0
            for (step in 1..8) {
                // Wait-page chain: the second ?pt= hop only redirects to the CDN
                // when sent WITHOUT a Referer -- any Referer makes the server
                // rotate tokens forever (monolith parity: resolvers.py
                // LoadedfilesResolver). Plain redirect chains keep the old
                // self-referer behavior; the first wait page gets the host root.
                val referer = when {
                    ptHops >= 1 && currUrl!!.contains("?pt=") -> null
                    currUrl!!.contains("?pt=") ->
                        lastWorkingHost?.let { "https://$it/" } ?: "https://my9jarocks.bz/"
                    step == 1 -> "https://my9jarocks.bz/"
                    else -> currUrl
                }
                val req = Request.Builder()
                    .url(HttpClient.safeUrl(currUrl!!))
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .apply { referer?.let { header("Referer", it) } }
                    .build()

                noRedirectClient.newCall(req).execute().use { res ->
                    val loc = res.header("Location")
                    if (!loc.isNullOrBlank()) {
                        val safeLoc = HttpClient.safeUrl(loc)
                        // Second-or-later ?pt= hop: the Location IS the answer
                        // (monolith parity, resolvers.py hop-3:
                        // `return r3.headers.get('location')`) -- no gating.
                        if (ptHops >= 1) {
                            android.util.Log.d("AnonDownload", "Loadedfiles cracked redirect URL: $safeLoc")
                            return safeLoc
                        }
                        if (isDirectMediaUrl(safeLoc) || safeLoc.contains("/token/download/") || safeLoc.contains("/d/")) {
                            if (!safeLoc.contains("?pt=")) {
                                android.util.Log.d("AnonDownload", "Loadedfiles cracked direct URL: $safeLoc")
                                return safeLoc
                            }
                        }
                        currUrl = safeLoc
                        return@use
                    }

                    if (res.isSuccessful) {
                        // The site changed its chain (2026-08-21): after ~2 token
                        // rotations the ?pt= hop answers 200 and serves THE FILE
                        // ITSELF (video/* body) — no Location, no downloadUrl.
                        // Detect it from headers BEFORE reading any body, or a
                        // 114MB response would be read as "no match".
                        val ct = res.header("Content-Type")?.lowercase() ?: ""
                        if (ct.startsWith("video/") || ct.contains("octet-stream") ||
                            ct.contains("matroska") || ct.contains("mpegurl")
                        ) {
                            android.util.Log.d("AnonDownload", "Loadedfiles token hop served the media directly ($ct)")
                            return currUrl
                        }

                        val body = HttpClient.cappedText(res) ?: return@use
                        val direct = findDirectMediaUrl(body)
                        if (!direct.isNullOrBlank() && !isRootLockerDomain(direct)) {
                            val safeDirect = HttpClient.safeUrl(direct)
                            android.util.Log.d("AnonDownload", "Loadedfiles found direct media in body: $safeDirect")
                            return safeDirect
                        }

                        val m = Pattern.compile("""var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'""", Pattern.CASE_INSENSITIVE).matcher(body)
                        if (m.find()) {
                            // Use the matched URL VERBATIM: the token is bound to
                            // the host in the link -- rewriting it onto the last
                            // working host breaks the chain (live-verified).
                            val next = m.group(1) ?: return@use
                            currUrl = HttpClient.safeUrl(next)
                            ptHops++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Surface the REAL cause to the registry: this resolver runs its
            // own OkHttp client, so the global HttpClient.lastFailure is stale
            // (it showed an unrelated canceled search). The registry logs and
            // retries on this per-attempt reason instead.
            lastResolveError = "${e.javaClass.simpleName}: ${e.message}"
            android.util.Log.e("AnonDownload", "LoadedfilesResolver error: ${e.message}", e)
        }
        return null
    }

    /**
     * Follow up to 3 redirects manually and return the URL that actually
     * served a page. loadedfiles.org is a 301 shell for loadedfiles.net —
     * treating the shell as the working host poisoned the whole chain.
     */
    private fun probeEffectiveUrl(client: okhttp3.OkHttpClient, startUrl: String): String? {
        var url = startUrl
        repeat(3) {
            val req = Request.Builder()
                .url(HttpClient.safeUrl(url))
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", "https://my9jarocks.bz/")
                .build()
            client.newCall(req).execute().use { res ->
                val loc = res.header("Location")
                if (res.code in 300..399 && !loc.isNullOrBlank()) {
                    url = HttpClient.safeUrl(loc)
                } else if (res.code in 200..299) {
                    return url
                } else {
                    return null
                }
            }
        }
        return null
    }
}

// -------------------------------------------------------------
// 16. WildshareResolver
// -------------------------------------------------------------
object WildshareResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("wildshare.net"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val ptMatcher = Pattern.compile("""pt=([A-Za-z0-9%+=/]+)""").matcher(html)
            if (ptMatcher.find()) {
                // THE BUGS (two of them, both fixed):
                //
                // 1. The previous code did `ptMatcher.group(0)` which returns the
                //    WHOLE MATCH (e.g. "pt=ZG1DYldW..."). Then it built the URL as
                //    `https://wildshare.net/$fileId?$pt` which produced "?pt=pt=ZG1D..."
                //    — a double-pt query. wildshare's edge returned an HTML
                //    interstitial for the malformed URL (it never matched a real
                //    download token), and the engine then tried to download that
                //    HTML as a .mkv, hit the "URL serves an HTML/error page, not
                //    media" check, and gave up. Live-verified: the 11-episode Pitt
                //    S02 wildshare cascade in app-2026-09-01 had every episode
                //    returning a `?pt=...` token in the page, but the engine
                //    couldn't follow it because the URL it constructed was
                //    garbage. `group(1)` extracts just the token value, so the
                //    final URL is `?pt=ZG1D...` — the form wildshare expects.
                //
                // 2. The previous code used a brand-new OkHttpClient with no
                //    cookieJar. wildshare's edge server sets a `filehosting`
                //    cookie on the first page visit (response headers confirmed),
                //    and the `?pt=...` 302 only fires when that cookie is
                //    present in the next request. Without the cookie the server
                //    returns 200 OK with HTML, not 302 — the resolver then
                //    silently failed. Reusing [HttpClient.shared] (which carries
                //    the sessionCookieJar populated by the page fetch above)
                //    preserves the cookie, and the 302 follows. Live-verified
                //    end-to-end: page → cookie set → follow ?pt= with cookie
                //    → 302 → real .mkv URL.
                val pt = ptMatcher.group(1) ?: return null
                if (pt.isBlank()) return null
                val parts = url.trimEnd('/').split('/')
                val fileId = parts.lastOrNull { !it.endsWith(".mkv") && !it.endsWith(".mp4") } ?: parts.last()
                if (fileId.isBlank()) return null
                val noRedirectClient = HttpClient.shared.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                val req = Request.Builder()
                    .url(HttpClient.safeUrl("https://wildshare.net/$fileId?pt=$pt"))
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    // The previous version did not set Referer on the follow;
                    // wildshare's edge 302s to an HTML page when Referer is
                    // missing. Set it to the original page URL.
                    .header("Referer", url)
                    .build()
                noRedirectClient.newCall(req).execute().use { res ->
                    if (res.code !in 200..399) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "WildshareResolver: ?pt= returned HTTP ${res.code} for $fileId (cookie present=${HttpClient.shared.cookieJar.loadForRequest(HttpUrl.parse("https://wildshare.net/")){ req -> req.headers }.size} cookies)"
                        )
                        return null
                    }
                    val loc = res.header("Location") ?: return null
                    if (loc.isBlank()) return null
                    return HttpClient.safeUrl(loc)
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 17. WaffiCloudResolver
// -------------------------------------------------------------
object WaffiCloudResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("waffi.cloud"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        // Reference parity (resolvers.py:275): a pure string strip with zero
        // network I/O. Stripping ?preview yields the direct file link; when the
        // URL was already clean this is an identity success.
        return url.substringBefore("?preview")
    }
}

// -------------------------------------------------------------
// 18. VidmolyResolver
// -------------------------------------------------------------
object VidmolyResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("vidmoly."))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val m = Pattern.compile("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").matcher(html)
            if (m.find()) {
                return m.group(1)
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 19. StreamwishResolver
// -------------------------------------------------------------
object StreamwishResolver : BaseResolver {
    private val HOSTS = listOf(
        "hglink.to", "streamwish.", "strwsh.", "stwish.", "wishembed.",
        "mwish.", "awish.", "sfastwish.", "swishsrv.", "ajmidyad", "khadhnayad",
        "obeywish.com", "jodwish.com", "streamwish.to", "embedwish.", "filelions."
    )

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val vid = url.trimEnd('/').substringAfterLast('/')
            val candidates = if (vid.length >= 6) {
                listOf(
                    "https://sfastwish.com/e/$vid",
                    "https://embedwish.com/e/$vid",
                    url
                )
            } else {
                listOf(url)
            }

            for (cand in candidates) {
                val html = HttpClient.getText(cand, referer = "https://asianc.id/") ?: continue
                if (looksLikeDeadPage(html)) continue
                val m3u8 = extractM3u8FromHtml(html)
                if (!m3u8.isNullOrBlank()) return m3u8

                val unpacked = JsUnpacker.unpack(html)
                if (!unpacked.isNullOrBlank()) {
                    val direct = extractM3u8FromHtml(unpacked)
                    if (!direct.isNullOrBlank()) return direct
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 20. VidhideResolver
// -------------------------------------------------------------
object VidhideResolver : BaseResolver {
    // filelions network: frontend domains rotate (vidhidepro.com -> vidhidefast.com,
    // 2026-08) and every frontend serves the same file-id space under the same
    // /e/ /f/ /d/ paths. A pasted link on a dead frontend (522 / conn error) is
    // retried on the live mirrors instead of failing outright.
    private val HOSTS = listOf(
        "vidhidefast.", "minochinos.com", "vidhide.", "vidhidepro.", "vidhidevip.",
        "filelions.", "vid-guard.", "nining.", "peytonepre.com",
        "techradar.ink", "ryderjet.com"
    )
    private val MIRROR_HOSTS = listOf(
        "vidhide.com", "minochinos.com", "vidhidefast.com",
        "vidhidevip.com", "vidhidepro.com", "filelions.to"
    )
    @Volatile private var lastWorkingHost: String? = null
    private val HOST_PART = Pattern.compile("""(https?://)([^/:]+)""", Pattern.CASE_INSENSITIVE)

    private fun rewriteHost(url: String, host: String): String {
        val m = HOST_PART.matcher(url)
        return if (m.find()) {
            HttpClient.safeUrl(url.substring(0, m.start(2)) + host + url.substring(m.end(2)))
        } else url
    }

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val urlHost = HttpClient.safeHost(url).lowercase()
            val candidates = LinkedHashSet<String>()
            lastWorkingHost?.let { candidates.add(it) }
            candidates.add(urlHost)
            candidates.addAll(MIRROR_HOSTS)
            for (host in candidates) {
                val cand = if (host == urlHost) url else rewriteHost(url, host)
                val html = HttpClient.getText(cand, referer = cand) ?: continue
                if (looksLikeDeadPage(html)) continue
                val m3u8 = extractM3u8FromHtml(html)
                if (!m3u8.isNullOrBlank()) {
                    lastWorkingHost = host
                    return m3u8
                }
                val unpacked = JsUnpacker.unpack(html)
                if (!unpacked.isNullOrBlank()) {
                    val direct = extractM3u8FromHtml(unpacked)
                    if (!direct.isNullOrBlank()) {
                        lastWorkingHost = host
                        return direct
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 21. DoodstreamResolver (DYNAMIC HOST FIX)
// -------------------------------------------------------------
object DoodstreamResolver : BaseResolver {
    private val HOSTS = listOf(
        "dood.", "doodstream.", "ds2play.com", "dooood.com", "d0000d.com",
        "d000d.com", "vidply.com", "do0od.com", "dood.re"
    )

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val host = HttpClient.safeHost(url, "dood.to")
            val embedUrl = url.replace("/d/", "/e/").replace("/f/", "/e/")
            val html = HttpClient.getText(embedUrl, referer = "https://$host/") ?: return null
            if (looksLikeDeadPage(html)) return null
            val passPattern = Pattern.compile("""/pass_md5/([^"'\s]+)""")
            val matcher = passPattern.matcher(html)
            if (matcher.find()) {
                val passPath = matcher.group(1)
                val passUrl = "https://$host/pass_md5/$passPath"
                val token = HttpClient.getText(passUrl, referer = embedUrl)
                if (!token.isNullOrBlank()) {
                    val tokenSlug = passPath.trimEnd('/').substringAfterLast('/')
                    val randomStr = (1..10).map { ('a'..'z').random() }.joinToString("")
                    val expiry = System.currentTimeMillis()
                    // /pass_md5/ returns a bare md5 token; the playable URL is
                    // https://<host>/e/<md5><random>?token=<md5>&expiry=<ts>.
                    // The scheme+host prefix is mandatory — a hostless string is
                    // not a URL and every downloader rejects it.
                    return "https://$host/e/${token.trim()}$randomStr?token=$tokenSlug&expiry=$expiry"
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 22. MixdropResolver
// -------------------------------------------------------------
object MixdropResolver : BaseResolver {
    private val HOSTS = listOf("mixdrop.", "mixdrp.", "mdfx9dc8n.net", "mixdroop.")

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val embedUrl = url.replace("/f/", "/e/")
            val host = HttpClient.safeHost(url, "mixdrop.co")
            val html = HttpClient.getText(embedUrl, referer = "https://$host/") ?: return null
            if (looksLikeDeadPage(html)) return null
            val unpacked = JsUnpacker.unpack(html)
            val source = if (!unpacked.isNullOrBlank()) unpacked else html
            val matcher = Pattern.compile("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").matcher(source)
            if (matcher.find()) {
                var streamUrl = matcher.group(1)
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
                return streamUrl
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 23. StreamtapeResolver
// -------------------------------------------------------------
object StreamtapeResolver : BaseResolver {
    private val HOSTS = listOf("streamtape.", "watchadsontape.", "strtape.tech")

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            if (looksLikeDeadPage(html)) return null
            val matcher = Pattern.compile("""document\.getElementById\('robotlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)""").matcher(html)
            if (matcher.find()) {
                val part1 = matcher.group(1)
                val part2 = matcher.group(2)
                var stream = "$part1$part2"
                if (stream.startsWith("//")) stream = "https:$stream"
                return stream
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 24. PixelDrainResolver
// -------------------------------------------------------------
object PixelDrainResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("pixeldrain.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            val fileId = url.substringAfterLast("/").substringBefore("?")
            if (fileId.isNotBlank()) {
                return "https://pixeldrain.com/api/file/$fileId?download"
            }
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 25. GenericLockerResolver
// -------------------------------------------------------------
object GenericLockerResolver : BaseResolver {
    private val HOSTS = listOf("vikingfile.com", "lulacloud.com")

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        try {
            // Covers lulacloud.com (broken CA chain) — permissive, bounded
            // to these locker page fetches only.
            val html = HttpClient.getText(url, referer = url, permissive = true) ?: return null
            val m3u8 = extractM3u8FromHtml(html)
            if (!m3u8.isNullOrBlank()) return m3u8

            val mp4 = extractMp4FromHtml(html)
            if (!mp4.isNullOrBlank()) return mp4

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val direct = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!direct.isNullOrBlank()) return direct
            }
        } catch (_: Exception) {}
        return null
    }
}

private val DEAD_FILE_MARKERS = listOf(
    "file is no longer available", "file was deleted", "file deleted",
    "file not found", "video not found", "this file was deleted",
    "has been removed", "no longer exists"
)

private fun looksLikeDeadPage(html: String): Boolean {
    val low = html.lowercase()
    return DEAD_FILE_MARKERS.any { low.contains(it) }
}

private fun extractM3u8FromHtml(html: String): String? {
    val matcher = Pattern.compile("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").matcher(html)
    if (matcher.find()) {
        return matcher.group(0)
    }
    return null
}

private fun extractMp4FromHtml(html: String): String? {
    // (?![a-zA-Z0-9]) so "site.webmanifest" (a common WP favicon link) is not
    // matched as ".webm"; HTML-escaped quotes are stripped off the tail.
    val matcher = Pattern.compile("""https?://[^\s"'<>]+\.(?:mp4|mkv)(?![a-zA-Z0-9])[^\s"'<>]*""").matcher(html)
    if (matcher.find()) {
        return matcher.group(0)?.substringBefore("&quot;")?.substringBefore("&amp;")
    }
    return null
}

fun isDirectMediaUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    val exts = com.anonrode.downloader.data.rules.DynamicRulesManager.getDirectMediaExtensions()
    return exts.any { clean.endsWith(it) }
}

fun isRootLockerDomain(url: String): Boolean {
    val clean = url.trimEnd('/')
    return listOf(
        "https://downloadwella.com",
        "http://downloadwella.com",
        "https://wetafiles.com",
        "http://wetafiles.com",
        "https://loadedfiles.net",
        "https://loadedfiles.st",
        "https://loadedfiles.to",
        "https://loadedfiles.org",
        "https://loadedfiles.com",
        "https://kissorgrab.com"
    ).any { clean.equals(it, ignoreCase = true) }
}

fun findDirectMediaUrl(text: String): String? {
    for (ext in listOf("m3u8", "mp4", "mkv", "webm", "avi")) {
        val matcher = Pattern.compile("""https?://[^\s"'<>,\\)]+\.$ext(?:[^\s"'<>,\\)]*)?""", Pattern.CASE_INSENSITIVE).matcher(text)
        while (matcher.find()) {
            val cand = matcher.group(0)?.trimEnd('.', ',', ';', ')') ?: continue
            val clean = cand.substringBefore('?').substringBefore('#').lowercase()
            if (listOf(".mp4", ".mkv", ".m3u8", ".webm", ".avi", ".ts").any { clean.endsWith(it) }) {
                return cand
            }
        }
    }
    return null
}

