package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.pipeline.LinkResolver
import com.anonrode.downloader.pipeline.PipelineError
import com.anonrode.downloader.pipeline.PipelineJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import com.anonrode.downloader.data.rules.DynamicRulesManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

interface BaseResolver {
    fun canResolve(url: String): Boolean
    suspend fun resolve(url: String, quality: String = "720p", depth: Int = 0): String?

    /** Legacy diagnostic only; concurrent registry attempts never infer outcomes from it. */
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
 *                             the raw URL contains the path part; a path-only
 *                             entry (empty host part, e.g. "/embed/") claims
 *                             on the path alone — legacy semantics, kept
 *                             honest rather than silently never-matching;
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
                if (h.isEmpty()) lower.contains(path) // path-only entry
                else (host == h || host.endsWith(".$h")) && lower.contains(path)
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

    val RESOLVERS: List<BaseResolver> = listOf(
        DynamicLockerResolver,
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

    suspend fun resolve(url: String, quality: String = "720p", depth: Int = 0, bypassHealth: Boolean = false): String? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // Cache + health apply ONCE per user-facing resolve (depth==0); the
        // recursive descent below stays uncached so gateway chains work.
        val cacheKey = com.anonrode.downloader.pipeline.ResolveCache.keyFor(url, quality)
        if (depth == 0) {
            com.anonrode.downloader.pipeline.ResolveCache.get(cacheKey)?.let { cached ->
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "cache", url = url, ok = true, ms = 0
                )
                return@withContext cached
            }
            // Manual retry taps grant ONE bypass of the health gate: the user
            // explicitly asked for a fresh attempt at a cooling-down host, and
            // the gate must not answer "skipped" before the request even fires.
            if (!bypassHealth && !com.anonrode.downloader.pipeline.HostHealth.isUsable(url)) {
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "health-gate", url = url, ok = false, ms = 0,
                    detail = "host dead or in backoff window — skipped without a request"
                )
                return@withContext null
            }
        }
        val outcome = resolveInternal(url, quality, depth)
        currentCoroutineContext().ensureActive()
        val result = (outcome as? ResolverOutcome.Success)?.url
        if (depth == 0) {
            // Health must be keyed on the TRUE host (same source of truth as
            // hostClaim/the terminal gate): the old string-split host recorded
            // `vikingfile.com:443@evil.com` as a key for forged URLs, leaving
            // the real victim host's backoff stale while garbage keys grew.
            val host = HttpClient.parsedHost(url)
                ?: url.trim().substringAfter("://").substringBefore('/').substringBefore('?')
            if (result != null) {
                com.anonrode.downloader.pipeline.ResolveCache.put(cacheKey, result)
                com.anonrode.downloader.pipeline.HostHealth.recordOk(host)
            } else {
                val reason = (outcome as? ResolverOutcome.Failure)?.reason
                com.anonrode.downloader.pipeline.HostHealth.recordFail(
                    host,
                    rateLimited = reason?.contains("429") == true,
                    reason = reason
                )
            }
        }
        result
    }

    /** Try all usable candidates in a bounded window; first success cancels losers. */
    suspend fun resolveAny(urls: List<String>, quality: String = "720p", maxConcurrency: Int = 3): String? {
        val candidates = urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .filter { com.anonrode.downloader.pipeline.HostHealth.isUsable(it) }
            .toList()
        return boundedFirstSuccess(candidates, maxConcurrency) { resolve(it, quality) }
    }

    private suspend fun resolveInternal(url: String, quality: String = "720p", depth: Int = 0): ResolverOutcome {
        currentCoroutineContext().ensureActive()
        if (depth > RESOLVE_DEPTH_LIMIT) {
            com.anonrode.downloader.pipeline.PipelineJournal.hop(
                site = "", stage = "registry", url = url, ok = false, ms = 0,
                detail = PipelineError.BudgetExceeded("depth", 0).message ?: "depth limit"
            )
            return ResolverOutcome.Failure("depth limit")
        }
        val trimmed = url.trim()
        var failure: ResolverOutcome = ResolverOutcome.NoMatch
        for (resolver in RESOLVERS) {
            currentCoroutineContext().ensureActive()
            if (resolver.canResolve(trimmed)) {
                val start = System.currentTimeMillis()
                val outcome = resolveWithRetry(resolver, trimmed, quality, depth)
                val direct = (outcome as? ResolverOutcome.Success)?.url
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
                        // Universal direct-stream guard: if the resolver output
                        // is already a provably-direct CDN endpoint (kissorgrab
                        // /dl/, downloadwella /d/, vikingfile /d/, token-signed
                        // R2/S3, etc.) the recursion STOPS HERE. No subsequent
                        // resolver can claim and POST to a finished media file.
                        // This closes the entire class of bugs where a different
                        // resolver hijacked a working stream and killed it with
                        // HTTP 405 (root case: Pluto → kissorgrab, bd93aae;
                        // guard now covers all future cross-provider handoffs).
                        if (!com.anonrode.downloader.pipeline.LinkResolver.isProvablyDirectFile(direct) &&
                            !(sameResolverReclaims && mediaPath)) {
                            val deeper = resolveInternal(direct, quality, depth + 1)
                            if (deeper is ResolverOutcome.Success) return deeper
                            // Reference parity (resolvers.py:2473-2475): a failed
                            // deeper pass must NOT fall back to returning the
                            // uncracked intermediary as Success -- the old code
                            // did, handing web locker pages to aria2c as if they
                            // were media (ghost-file class). Terminal CDNs are
                            // exempted above: a media-path result the SAME
                            // resolver re-claims is its final answer, kept even
                            // when a hypothetical deeper pass would fail.
                            if (deeper is ResolverOutcome.Failure) return deeper
                        }
                    }
                    return ResolverOutcome.Success(direct)
                }
                val failureDetail = (outcome as? ResolverOutcome.Failure)?.reason
                    ?: resolver.lastResolveFailure()
                    ?: "no playable stream or token extracted"
                com.anonrode.downloader.pipeline.PipelineJournal.hop(
                    site = "", stage = "crack:${resolver::class.simpleName}",
                    url = trimmed, ok = false, ms = elapsed,
                    detail = failureDetail.take(160)
                )
                failure = if (outcome is ResolverOutcome.Failure) outcome else ResolverOutcome.Failure(failureDetail)
            }
        }
        return failure
    }

    // Retry only request-local connectivity evidence, never singleton diagnostic strings.
    private suspend fun resolveWithRetry(resolver: BaseResolver, url: String, quality: String, depth: Int): ResolverOutcome {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            HttpClient.awaitOriginCooldown(url)
            val outcome = if (resolver === LoadedfilesResolver) {
                LoadedfilesResolver.resolveOutcome(url, quality, depth)
            } else {
                captureResolverOutcome { resolver.resolve(url, quality, depth) }
            }
            currentCoroutineContext().ensureActive()
            val cooldownUrl = (outcome as? ResolverOutcome.Failure)?.cooldownUrl
                ?: url.takeIf { HttpClient.remainingCooldownMs(it) > 0L }
            val retryable = (outcome as? ResolverOutcome.Failure)?.retryable == true || cooldownUrl != null
            if (attempt >= 2 || outcome is ResolverOutcome.Success || !retryable) return outcome
            attempt++
            com.anonrode.downloader.util.DebugLog.resolve("transient failure, retry #$attempt ${resolver::class.simpleName}")
            delay(NETWORK_RETRY_DELAY_MS)
            if (cooldownUrl != null && cooldownUrl != url) HttpClient.awaitOriginCooldown(cooldownUrl)
        }
    }
}

// -------------------------------------------------------------
// 1. VidbasicResolver (AES-256-CBC Decryptor)
// -------------------------------------------------------------
object VidbasicResolver : BaseResolver {
    private val KEY = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
    private val IV = "5259228356829423".toByteArray(Charsets.UTF_8)
    private val HOSTS = listOf("vidbasic.", "vidb.top", "embedload.cfd")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, HOSTS) && !low.endsWith(".m3u8") && !low.endsWith(".mp4")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // Reference parity (resolvers.py:557): the page is fetched WITHOUT a
            // Referer header.
            val html = HttpClient.getText(url) ?: run {
                lastFailure = "Vidbasic: empty HTTP response for $url"
                return null
            }

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
            lastFailure = "Vidbasic: no crypto payload, mirror candidates, or 3rdplayer found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidbasic: ${e.javaClass.simpleName}: ${e.message}"
        }
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
            if (decrypted.startsWith("http") && !decrypted.contains(" ")) {
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
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return hostClaim(url, listOf("kissasian9.ro")) && lower.contains("/kisskh/") && !lower.endsWith(".m3u8")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Kissasian: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""sourceUrl"\s*:\s*"([^"]+)""").matcher(html)
            if (m.find()) {
                val apiPath = m.group(1) ?: return null
                val apiUrl = HttpClient.safeResolveUri(url, apiPath)
                val apiJson = HttpClient.getText(apiUrl, referer = url) ?: run {
                    lastFailure = "Kissasian: API fetch failed for $apiUrl"
                    return null
                }
                val obj = JSONObject(apiJson)
                val src = obj.optString("source")
                if (src.startsWith("http") && !src.contains(" ")) {
                    return src
                }
            }
            lastFailure = "Kissasian: sourceUrl not found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Kissasian: ${e.javaClass.simpleName}: ${e.message}"
        }
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
    @Volatile private var lastFailure: String? = null
    private val MEGAPLAY_KEY = ByteArray(32).also { target ->
        val src = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8)
        System.arraycopy(src, 0, target, 0, minOf(32, src.size))
    }
    private val MEGAPLAY_IV = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8)

    override fun lastResolveFailure(): String? = lastFailure

    private fun decryptMegaplayEnc(enc: String): String? {
        return try {
            val clean = enc.replace('-', '+').replace('_', '/').replace(Regex("""\s"""), "")
            val pad = clean.length % 4
            val padded = if (pad > 0) clean + "=".repeat(4 - pad) else clean
            val cipherBytes = try {
                android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                java.util.Base64.getDecoder().decode(padded)
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(MEGAPLAY_KEY, "AES")
            val ivSpec = IvParameterSpec(MEGAPLAY_IV)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val jsonObj = JSONObject(jsonStr)
            jsonObj.optString("file").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    override fun canResolve(url: String): Boolean {
        if (url.contains("/playlist.php") || url.contains("/api/")) return false
        val lower = url.lowercase()
        if (hostClaim(url, HOSTS)) return true
        // Legacy path-shape claim: kisskh mirrors ride rotating hosts whose
        // only stable marker IS the /kisskh/ path, so the clause stays — but
        // path/authority only, never query/fragment: `evil.test/x?u=/kisskh/`
        // must not be pulled into this resolver by a spoofed parameter.
        val pathPart = lower.substringAfter("://").substringBefore('#').substringBefore('?')
        return pathPart.contains("/kisskh/")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // tamilembed serves the real player under HTTP 404 — accept the body
            // (monolith parity, resolvers.py:693-698). LIVE (2026-08): tamilembed
            // now 403s when the fetch carries a referer; it needs referer=null.
            val html = HttpClient.getText(
                url,
                referer = if (url.contains("tamilembed")) null else url,
                acceptStatus = if (url.contains("tamilembed")) setOf(404) else emptySet()
            ) ?: run {
                lastFailure = "KisskhMegaplay: empty HTTP response for $url"
                return null
            }

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
                        val enc = data.optString("enc")
                        if (enc.isNotBlank()) {
                            val decrypted = decryptMegaplayEnc(enc)
                            if (!decrypted.isNullOrBlank()) return decrypted
                        }
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

            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "KisskhMegaplay: no stream extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "KisskhMegaplay: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 4. BloggerResolver (batchexecute RPC -> direct googlevideo MP4)
// -------------------------------------------------------------
object BloggerResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("blogger.com")) && (low.contains("video.g") || low.contains("token="))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url) ?: run {
                lastFailure = "Blogger: empty HTTP response for $url"
                return null
            }

            val fsidMatcher = Pattern.compile("""FdrFJe":"([^"]+)""").matcher(html)
            if (!fsidMatcher.find()) {
                lastFailure = "Blogger: FdrFJe sid token not found in HTML"
                return null
            }
            val fSid = fsidMatcher.group(1) ?: return null

            val blMatcher = Pattern.compile("""boq_bloggeruiserver_[^'", ]+""").matcher(html)
            if (!blMatcher.find()) {
                lastFailure = "Blogger: boq_bloggeruiserver bl token not found in HTML"
                return null
            }
            val bl = blMatcher.group(0)

            val tokenMatcher = Pattern.compile("""[?&]token=([^&]+)""").matcher(url)
            if (!tokenMatcher.find()) {
                lastFailure = "Blogger: token param not found in URL"
                return null
            }
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

            HttpClient.executeCancellable(HttpClient.shared, req) use@{ res ->
                if (!res.isSuccessful) {
                    lastFailure = "Blogger: RPC returned HTTP ${res.code}"
                    return null
                }
                val body = HttpClient.cappedText(res) ?: run {
                    lastFailure = "Blogger: empty RPC body"
                    return null
                }

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
                lastFailure = "Blogger: no googlevideo stream URL in RPC response"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Blogger: ${e.javaClass.simpleName}: ${e.message}"
        }
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
    private val TMDB_PATTERN = Pattern.compile("""/(?:movie|tv)/(\d+)(?:[/_-](\d+)[/_-](\d+))?""")
    private val ORIGIN_PATTERN = Pattern.compile("""https?://[^/]+""")
    internal val missingTmdb = ConcurrentHashMap<String, Long>()
    internal val availableTmdb = ConcurrentHashMap<String, Long>()
    private const val MISSING_TMDB_TTL_MS = 15 * 60_000L
    private const val AVAILABLE_TMDB_TTL_MS = 60 * 60_000L
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    fun isKnownUnavailable(mediaType: String, tmdbId: String, season: String = "", episode: String = ""): Boolean {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        val cachedMissingAt = missingTmdb[cacheKey] ?: return false
        if (System.currentTimeMillis() - cachedMissingAt < MISSING_TMDB_TTL_MS) {
            return true
        }
        missingTmdb.remove(cacheKey, cachedMissingAt)
        return false
    }

    fun isKnownAvailable(mediaType: String, tmdbId: String, season: String = "", episode: String = ""): Boolean {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        val cachedAvailableAt = availableTmdb[cacheKey] ?: return false
        if (System.currentTimeMillis() - cachedAvailableAt < AVAILABLE_TMDB_TTL_MS) {
            return true
        }
        availableTmdb.remove(cacheKey, cachedAvailableAt)
        return false
    }

    fun markUnavailable(mediaType: String, tmdbId: String, season: String = "", episode: String = "") {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        missingTmdb[cacheKey] = System.currentTimeMillis()
    }

    fun markAvailable(mediaType: String, tmdbId: String, season: String = "", episode: String = "") {
        val cacheKey = "$mediaType:$tmdbId:$season:$episode"
        availableTmdb[cacheKey] = System.currentTimeMillis()
    }

    suspend fun checkAvailability(mediaType: String, tmdbId: String, tag: String? = "search"): Boolean {
        if (tmdbId.isBlank()) return false
        if (isKnownUnavailable(mediaType, tmdbId)) return false
        if (isKnownAvailable(mediaType, tmdbId)) return true

        val cacheKey = "$mediaType:$tmdbId::"
        return try {
            val apiUrl = "https://data.vidsrcme.ru/api.php?type=$mediaType&tmdb=$tmdbId&stream_urls"
            val json = HttpClient.getText(apiUrl, referer = "https://cloudorchestranova.com/", tag = tag)
                ?: return true // Fail open on network/timeout error so offline/flaky connection doesn't drop items
            val root = JSONObject(json)
            val sc = root.optInt("status_code", 0)
            val data = root.optJSONObject("data")
            if (sc == 404 || (sc == 0 && data == null)) {
                missingTmdb[cacheKey] = System.currentTimeMillis()
                false
            } else if (data != null || sc == 200) {
                availableTmdb[cacheKey] = System.currentTimeMillis()
                true
            } else {
                true
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            true // Fail open on transient error
        }
    }

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            var embedUrl = url.replace("nepu.to/", "nepu.gd/")
            if (embedUrl.contains("nepu.gd/watch")) {
                // Watch pages require Cookie: hv=1 to bypass JS gate and hide
                // the player in iframe#playerFrame (or vidsrc/vsembed iframe).
                try {
                    val html = HttpClient.getText(
                        embedUrl,
                        referer = "https://nepu.gd/",
                        headers = mapOf("Cookie" to "hv=1")
                    )
                    if (!html.isNullOrBlank()) {
                        val doc = Jsoup.parse(html, embedUrl)
                        val iframe = doc.selectFirst("iframe#playerFrame")
                            ?: doc.selectFirst("iframe[src*=vidsrc]")
                            ?: doc.selectFirst("iframe[src*=vsembed]")
                            ?: doc.selectFirst("iframe[src]")
                        if (iframe != null && iframe.attr("src").isNotBlank()) {
                            embedUrl = HttpClient.safeResolveUri(embedUrl, iframe.attr("src"))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }

            // vsembed.ru embeds relay through an internal data-api (vs_src.php)
            // which mints the signed cloudorchestranova player URL.
            if (embedUrl.contains("vsembed.ru")) {
                try {
                    val vHtml = HttpClient.getText(embedUrl, referer = "https://vidsrc.mov/")
                    if (!vHtml.isNullOrBlank()) {
                        val apiMatcher = Pattern.compile("""data-api=["']([^"']+)["']""").matcher(vHtml)
                        val apiPath = if (apiMatcher.find()) apiMatcher.group(1) else null
                        if (!apiPath.isNullOrBlank()) {
                            val fullApi = HttpClient.safeResolveUri(embedUrl, apiPath.replace("&amp;", "&"))
                            val apiJson = HttpClient.getText(fullApi, referer = embedUrl)
                            if (!apiJson.isNullOrBlank()) {
                                val src = JSONObject(apiJson).optString("src")
                                if (src.startsWith("http")) embedUrl = src
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }

            // The embed URL carries the TMDB id (and season/episode for TV).
            val m = TMDB_PATTERN.matcher(embedUrl)
            if (!m.find()) {
                lastFailure = "Vidsrc: no TMDB id pattern matched in $embedUrl"
                return null
            }
            val tmdb = m.group(1) ?: return null
            val mediaType = if (embedUrl.contains("/tv/")) "tv" else "movie"
            val cacheKey = "$mediaType:$tmdb:${m.group(2).orEmpty()}:${m.group(3).orEmpty()}"
            val cachedMissingAt = missingTmdb[cacheKey]
            if (cachedMissingAt != null) {
                if (System.currentTimeMillis() - cachedMissingAt < MISSING_TMDB_TTL_MS) {
                    lastFailure = "Vidsrc: known unavailable tmdb=$tmdb"
                    return null
                }
                missingTmdb.remove(cacheKey, cachedMissingAt)
            }
            val apiUrl = if (embedUrl.contains("/tv/")) {
                val season = m.group(2) ?: return null
                val episode = m.group(3) ?: return null
                "https://data.vidsrcme.ru/api.php?type=tv&tmdb=$tmdb&season=$season&episode=$episode&stream_urls"
            } else {
                "https://data.vidsrcme.ru/api.php?type=movie&tmdb=$tmdb&stream_urls"
            }

            val json = HttpClient.getText(apiUrl, referer = "https://cloudorchestranova.com/") ?: run {
                lastFailure = "Vidsrc: API request failed for $apiUrl"
                return null
            }
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: run {
                val sc = root.optInt("status_code", 404)
                if (sc == 404) missingTmdb[cacheKey] = System.currentTimeMillis()
                lastFailure = "Vidsrc: API returned status $sc for tmdb=$tmdb"
                return null
            }
            availableTmdb[cacheKey] = System.currentTimeMillis()
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
            } ?: run {
                lastFailure = "Vidsrc: no valid stream_urls extracted from data"
                return null
            }

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
            if (token.isEmpty()) {
                lastFailure = "Vidsrc: generate.php returned empty token for $origin"
                return null
            }
            return if (streamUrl.contains("__TOKEN__")) streamUrl.replace("__TOKEN__", token)
            else streamUrl + (if (streamUrl.contains("?")) "&" else "?") + "token=$token"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidsrc: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 6. LightDLResolver
// -------------------------------------------------------------
object LightDLResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        if (url.contains("/api/download/")) return false
        return hostClaim(url, listOf("lightdl.cc"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val code = url.trimEnd('/').substringAfterLast('/')
            if (code.isBlank()) {
                lastFailure = "LightDL: code blank in $url"
                return null
            }
            val fileJson = HttpClient.getText("https://lightdl.cc/api/files/code/$code", referer = url) ?: run {
                lastFailure = "LightDL: file query failed for code $code"
                return null
            }
            val fileId = JSONObject(fileJson).optJSONObject("file")?.optString("id") ?: run {
                lastFailure = "LightDL: file id not found in response"
                return null
            }

            val req = Request.Builder()
                .url("https://lightdl.cc/api/files/$fileId/download-token")
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", url)
                .post(FormBody.Builder().build())
                .build()

            HttpClient.executeCancellable(HttpClient.shared, req) use@{ res ->
                if (!res.isSuccessful) {
                    lastFailure = "LightDL: download-token HTTP ${res.code}"
                    return null
                }
                val body = HttpClient.cappedText(res) ?: run {
                    lastFailure = "LightDL: empty download-token body"
                    return null
                }
                val obj = JSONObject(body)
                val dlUrl = obj.optString("downloadUrl")
                if (dlUrl.isNotBlank()) return dlUrl
                lastFailure = "LightDL: no downloadUrl in token response"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "LightDL: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 7. FivePlayResolver
// -------------------------------------------------------------
object FivePlayResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("5play.cc"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = "https://dramakey.cc/") ?: run {
                lastFailure = "FivePlay: empty HTTP response for $url"
                return null
            }
            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "FivePlay: no stream extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "FivePlay: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 8. VikingFileResolver
// -------------------------------------------------------------
object VikingFileResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("vikingfile.com")) && (low.contains("/d/") || (!low.endsWith(".mp4") && !low.endsWith(".mkv") && !low.endsWith(".m3u8")))
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
        lastFailure = null
        try {
            // vikingfile.com has TWO URL shapes:
            //
            // 1. /f/<id>  → 302 redirect to /d/<token>/<filename>.mkv
            //    The /f/<id> page itself is just a token-mint; the actual
            //    file is at the /d/<token>/<file> URL. Live-verified: the
            //    page returns 302 with Location: /d/ZlVDRbze6i/...mkv
            //
            // 2. /d/<token>/<file>  → 302 redirect to Cloudflare R2 presigned URL
            //    which serves 206 Partial Content on a Range probe.
            //    Resolving follows hops until reaching the terminal R2 storage URL,
            //    preventing 16 parallel download sockets from hammering Vikingfile's
            //    Nginx frontend with 429 Too Many Requests.
            var curr = url
            for (hop in 1..5) {
                val tp = HttpClient.probeTerminal(curr, referer = "https://www.naijavault.com/")
                if (tp == null) {
                    lastFailure = "VikingFile: probeTerminal failed for $curr"
                    return null
                }
                // Case (a): curr is already the direct storage/media URL (e.g. Cloudflare R2)
                if (tp.totalBytes != null) {
                    val lowCurr = curr.lowercase()
                    if (lowCurr.contains("r2.cloudflarestorage.com") ||
                        lowCurr.contains(".r2.dev/") ||
                        lowCurr.contains("cloudflarestorage.com/")) {
                        val verified = probeStorageLocation(curr)
                        if (verified != null) return verified
                    }
                    if (tp.code in 200..206) {
                        val isMediaContent = tp.contentType?.contains("video") == true ||
                            tp.contentType?.contains("octet-stream") == true ||
                            tp.contentType?.contains("matroska") == true ||
                            isDirectMediaUrl(curr)
                        if (!hostClaim(curr, listOf("vikingfile.com")) || isMediaContent) {
                            com.anonrode.downloader.util.DebugLog.resolve(
                                "VikingFileResolver: $curr is the direct file (Range probe ${tp.code}, size=${tp.totalBytes}) — returning as-is"
                            )
                            return curr
                        }
                    }
                }
                // Case (b): 30x redirect
                val rawLoc = tp.location
                if (tp.code in 301..308 && !rawLoc.isNullOrBlank()) {
                    val loc = HttpClient.safeResolveUri(curr, rawLoc)
                    val lowLoc = loc.lowercase()
                    if (lowLoc.contains("r2.cloudflarestorage.com") ||
                        lowLoc.contains(".r2.dev/") ||
                        lowLoc.contains("cloudflarestorage.com/")) {
                        val verified = probeStorageLocation(loc)
                        if (verified != null) return verified
                    }
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "VikingFileResolver: followed ${tp.code} → ${loc.take(120)}"
                    )
                    curr = HttpClient.safeUrl(loc)
                    continue
                }
                // Case (c): HTML page that doesn't redirect
                val html = HttpClient.getText(curr, referer = "https://www.naijavault.com/") ?: run {
                    lastFailure = "VikingFile: empty HTTP response at hop $hop for $curr"
                    return null
                }
                val m = Pattern.compile("""(?:window\.location|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
                if (m.find()) {
                    val loc = m.group(1) ?: return null
                    val lowLoc = loc.lowercase()
                    if (lowLoc.contains("r2.cloudflarestorage.com") ||
                        lowLoc.contains(".r2.dev/") ||
                        lowLoc.contains("cloudflarestorage.com/")) {
                        val verified = probeStorageLocation(loc)
                        if (verified != null) return verified
                    }
                    curr = HttpClient.safeUrl(HttpClient.safeResolveUri(curr, loc))
                    continue
                }
                val direct = extractMp4FromHtml(html) ?: extractM3u8FromHtml(html)
                if (!direct.isNullOrBlank()) return direct
                lastFailure = "VikingFile: no redirect or media found at hop $hop ($curr)"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "VikingFile: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 9. LulaCloudResolver
// -------------------------------------------------------------
object LulaCloudResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("lulacloud.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // lulacloud.com serves a broken CA chain (live-verified verify
            // code 20) — permissive client, same bounded scope as the
            // downloadwella family.
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/", permissive = true) ?: run {
                lastFailure = "LulaCloud: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                return m.group(1)
            }
            val direct = extractMp4FromHtml(html) ?: extractM3u8FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val doc = Jsoup.parse(html, url)
            val btn = doc.selectFirst("a.download-btn, a[href*='download'], a[href*='/d/']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank() && href != url) return href
            }

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractMp4FromHtml(unpacked) ?: extractM3u8FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "LulaCloud: no media or redirect found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "LulaCloud: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 10. DramaGatewayResolver
// -------------------------------------------------------------
object DramaGatewayResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("dramarain.com", "dramakey.cc")) && low.contains("/download")
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val host = HttpClient.safeHost(url, "dramarain.com")
            val html = HttpClient.getText(url, referer = "https://$host/") ?: run {
                lastFailure = "DramaGateway: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                val dest = m.group(1)
                if (!dest.isNullOrBlank()) return dest
            }

            val doc = Jsoup.parse(html, url)
            val btn = doc.selectFirst("a.download-btn, a[href*='waffi'], a[href*='download'], a[href*='stream']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank() && href != url) return href
            }
            val lockerAnchor = doc.select("a[href]").map { it.attr("abs:href") }
                .firstOrNull { LinkResolver.isKnownLockerHost(it) && it != url }
            if (!lockerAnchor.isNullOrBlank()) return lockerAnchor

            lastFailure = "DramaGateway: no window.location or download link found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "DramaGateway: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 11. NaijaVaultGatewayResolver
// -------------------------------------------------------------
object NaijaVaultGatewayResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return hostClaim(url, listOf("naijavault.com")) && (low.contains("/dl-") || low.contains("/temp/"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/") ?: run {
                lastFailure = "NaijaVault: empty HTTP response for $url"
                return null
            }
            val soup = Jsoup.parse(html, url)
            val btn = soup.selectFirst("a.download-btn, a.btn-download, a[href*='vikingfile'], a[href*='lulacloud'], a[href*='loadedfiles'], a[href*='downloadwella'], a[href*='wetafiles'], a[href*='pixeldrain']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank()) return href
            }
            val lockerAnchor = soup.select("a[href]").map { it.attr("abs:href") }
                .firstOrNull { LinkResolver.isKnownLockerHost(it) && it != url }
            if (!lockerAnchor.isNullOrBlank()) return lockerAnchor

            val m = Pattern.compile("""var\s+downloadURL\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                val u = m.group(1)
                if (!u.isNullOrBlank()) return u
            }
            lastFailure = "NaijaVault: no download anchor or var downloadURL found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "NaijaVault: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 12. EmbedResolver
// -------------------------------------------------------------
object EmbedResolver : BaseResolver {
    private val KNOWN = listOf("megaplay.buzz", "megaplay.cc", "tamilembed.lol", "embedsito.com")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, KNOWN)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "EmbedResolver: empty HTTP response for $url"
                return null
            }
            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "EmbedResolver: no stream extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "EmbedResolver: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 13. PlutoMoviesResolver
// -------------------------------------------------------------
object PlutoMoviesResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

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
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = "https://plutomovies.com/") ?: run {
                lastFailure = "PlutoMovies: empty HTTP response for $url"
                return null
            }
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
            lastFailure = "PlutoMovies: no dl anchor or direct media found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "PlutoMovies: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 14. DownloadwellaResolver
// -------------------------------------------------------------
object DownloadwellaResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        // kissorgrab.com/dl/ and downloadwella/wetafiles /d/ URLs are
        // already-cracked direct media files (live-verified: HTTP 206, MKV/MP4).
        // The resolver must NOT claim them — POSTing a web form to a direct file
        // returns HTTP 405/400 and kills the entire download chain.
        val lower = url.lowercase()
        if (lower.contains("kissorgrab.com") && lower.contains("/dl/")) return false
        if ((lower.contains("downloadwella.com") || lower.contains("wetafiles.com")) && lower.contains("/d/")) return false
        return hostClaim(url, listOf("downloadwella.com", "wetafiles.com", "kissorgrab.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // Permissive client: wetafiles.com omits its TLS intermediate and
            // kissorgrab.com serves an invalid cert — strict verification
            // fails every crack on those hosts (live-verified). Scoped here
            // only; the shared client stays strict for everything else.
            val html = HttpClient.getText(url, referer = url, permissive = true) ?: run {
                lastFailure = "Downloadwella: empty HTTP response for $url"
                return null
            }
            val doc = Jsoup.parse(html, url)
            val cfg = DynamicRulesManager.getResolverConfig("downloadwella")
            val formSelector = cfg?.optString("formActionSelector")
                ?.takeIf { it.isNotBlank() && it.length <= 600 }
                ?: "form"
            val formEl = try {
                doc.selectFirst(formSelector)
            } catch (_: Exception) {
                null
            }

            // Movie pages of this locker family sometimes render with NO form
            // at all (live-verified 2026-09-11: downloadwella.com/9mktxnflcqtc/
            // …DC.(THENKIRI.COM).mkv.html?preview has zero <form>/<input>
            // tags, while series pages carry the classic F1 form). The server
            // still cracks them when the standard F1 body is POSTed with the
            // file id taken from the URL's first path segment — so fall back
            // to the synthetic body instead of returning null; the response
            // scan below finds the /d/ direct anchor either way.
            val formAction = formEl?.attr("abs:action").orEmpty().ifBlank { url }
            fun configuredDirect(bodyText: String): String? {
                val tokenRegex = cfg?.optString("tokenRegex")?.takeIf { it.isNotBlank() && it.length <= 600 } ?: return null
                return try {
                    val matcher = Pattern.compile(tokenRegex, Pattern.CASE_INSENSITIVE).matcher(bodyText)
                    if (!matcher.find()) return null
                    for (group in 1..matcher.groupCount()) {
                        val value = matcher.group(group)?.replace("\\\\/", "/")?.trim().orEmpty()
                        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                            val safe = HttpClient.safeUrl(HttpClient.safeResolveUri(url, value))
                            if (isDirectMediaUrl(safe) || safe.contains("/d/") || safe.contains("/token/download/")) return safe
                        }
                    }
                    null
                } catch (_: Exception) {
                    null
                }
            }

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
                if (fileId.isNullOrBlank()) {
                    lastFailure = "Downloadwella: fileId could not be extracted from path $url"
                    return null
                }
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

            HttpClient.executeCancellable(HttpClient.permissiveClient, req) use@{ res ->
                if (!res.isSuccessful) {
                    lastFailure = "Downloadwella: step 1 POST HTTP ${res.code}"
                    return null
                }
                val body = HttpClient.cappedText(res) ?: run {
                    lastFailure = "Downloadwella: step 1 empty POST body"
                    return null
                }

                val directMedia = findDirectMediaUrl(body) ?: configuredDirect(body)
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
                val step2Form = try {
                    postDoc.selectFirst(formSelector)
                } catch (_: Exception) {
                    null
                } ?: postDoc.selectFirst("form[name='F1'], form")
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
                    HttpClient.executeCancellable(HttpClient.permissiveClient, step2Req) use@{ res2 ->
                        if (res2.isSuccessful) {
                            val body2 = HttpClient.cappedText(res2) ?: ""
                            val direct2 = findDirectMediaUrl(body2) ?: configuredDirect(body2)
                            if (!direct2.isNullOrBlank() && !isRootLockerDomain(direct2)) return direct2
                            val postDoc2 = Jsoup.parse(body2, url)
                            val directAnchor2 = postDoc2.select("a[href]").mapNotNull { a ->
                                val href = a.attr("abs:href")
                                if (isDirectMediaUrl(href) || (href.contains("/d/") && !href.endsWith(".html"))) href else null
                            }.firstOrNull { !it.equals(url, ignoreCase = true) && !isRootLockerDomain(it) }
                            if (!directAnchor2.isNullOrBlank()) return directAnchor2
                        }
                    }
                }
                lastFailure = "Downloadwella: no direct media link found after form submit (body len=${body.length})"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Downloadwella: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 15. LoadedfilesResolver
// -------------------------------------------------------------
object LoadedfilesResolver : BaseResolver {
    private val HOST_RE = Pattern.compile("""loadedfiles\.[a-z0-9-]+""", Pattern.CASE_INSENSITIVE)

    // Legacy diagnostic retained for callers; the registry uses resolveOutcome instead.
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

    override suspend fun resolve(url: String, quality: String, depth: Int): String? =
        (resolveOutcome(url, quality, depth) as? ResolverOutcome.Success)?.url

    internal suspend fun resolveOutcome(url: String, quality: String, depth: Int): ResolverOutcome = withContext(Dispatchers.IO) {
        var failure: ResolverOutcome.Failure? = null
        val outcome = captureResolverOutcome { resolveAttempt(url) { failure = it } }
        val result = if (outcome == ResolverOutcome.NoMatch) failure ?: outcome else outcome
        lastResolveError = (result as? ResolverOutcome.Failure)?.reason
        result
    }

    private suspend fun resolveAttempt(url: String, onFailure: (ResolverOutcome.Failure?) -> Unit): String? {
        try {
            // The token chain needs the shared client's longer read timeout (a
            // slow wait page must not abort the hop), but the host-candidate
            // probe is pure liveness: a dead host should fail in 5s instead of
            // burning the shared 15s per candidate before the next TLD is tried.
            val noRedirectClient = HttpClient.shared.newBuilder().followRedirects(false).build()
            val probeClient = HttpClient.shared.newBuilder()
                .followRedirects(false)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val slowProbeClient = HttpClient.shared.newBuilder()
                .followRedirects(false)
                .connectTimeout(6, TimeUnit.SECONDS)
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
                val effective = try {
                    probeEffectiveUrl(probeClient, candidate)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    onFailure(resolverFailure(e))
                    android.util.Log.w("AnonDownload", "Loadedfiles fast-probe failed for $host: ${e.message}")
                    null
                }
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
                    val effective = try {
                        probeEffectiveUrl(slowProbeClient, candidate)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        onFailure(resolverFailure(e))
                        android.util.Log.w("AnonDownload", "Loadedfiles slow-probe failed for $host: ${e.message}")
                        null
                    }
                    if (effective != null) {
                        lastWorkingHost = effective.substringAfter("://").substringBefore('/').lowercase()
                        currUrl = effective
                        break
                    }
                }
            }
            if (currUrl == null) {
                onFailure(ResolverOutcome.Failure("Loadedfiles: no live candidate host responded (${hosts.joinToString()})"))
                android.util.Log.w("AnonDownload", "Loadedfiles: no live host")
                return null
            }

            onFailure(null)
            val workingHost = HttpClient.parsedHost(currUrl!!)
            var ptHops = 0
            for (step in 1..8) {
                currentCoroutineContext().ensureActive()
                // No-progress guard: a step that ends on the SAME url (200 body
                // with no downloadUrl and no media) would otherwise be re-fetched
                // up to 8 times -- the server is rotating tokens in the page, so
                // re-reading the identical URL cannot advance the chain. End the
                // walk the first time nothing moved.
                val pageBeforeStep = currUrl
                // Wait-page chain: the second ?pt= hop only redirects to the CDN
                // when sent WITHOUT a Referer -- any Referer makes the server
                // rotate tokens forever (monolith parity: resolvers.py
                // LoadedfilesResolver). Plain redirect chains keep the old
                // self-referer behavior; the first wait page gets the host root.
                val referer = when {
                    ptHops >= 1 && currUrl!!.contains("?pt=") -> null
                    currUrl!!.contains("?pt=") ->
                        workingHost?.let { "https://$it/" } ?: "https://my9jarocks.bz/"
                    step == 1 -> "https://my9jarocks.bz/"
                    else -> currUrl
                }
                val req = Request.Builder()
                    .url(HttpClient.safeUrl(currUrl!!))
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .apply { referer?.let { header("Referer", it) } }
                    .build()

                HttpClient.executeCancellable(noRedirectClient, req) use@{ res ->
                    val loc = res.header("Location")
                    if (!loc.isNullOrBlank()) {
                        val safeLoc = HttpClient.safeUrl(HttpClient.safeResolveUri(res.request.url.toString(), loc))
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

                        var next: String? = null
                        val mDlTimer = Pattern.compile(
                            """dlTimer\(\{\s*seconds:\s*\d+,\s*link:\s*['"]([^'"]+)['"]""",
                            Pattern.CASE_INSENSITIVE
                        ).matcher(body)
                        if (mDlTimer.find()) {
                            val raw = mDlTimer.group(1)
                            if (!raw.isNullOrBlank()) {
                                next = unescapeJsUrl(raw)
                            }
                        }
                        if (next == null) {
                            val mLegacy = Pattern.compile(
                                """var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'""",
                                Pattern.CASE_INSENSITIVE
                            ).matcher(body)
                            if (mLegacy.find()) {
                                next = mLegacy.group(1)
                            }
                        }
                        if (next == null) {
                            val cfg = DynamicRulesManager.getResolverConfig("loadedfiles")
                            val customRegex = cfg?.optString("tokenRegex")
                                ?.takeIf { it.isNotBlank() && it.length <= 600 }
                            if (!customRegex.isNullOrBlank()) {
                                val mCustom = Pattern.compile(customRegex, Pattern.CASE_INSENSITIVE).matcher(body)
                                if (mCustom.find()) {
                                    for (g in 1..mCustom.groupCount()) {
                                        val v = mCustom.group(g)
                                        if (!v.isNullOrBlank()) {
                                            next = unescapeJsUrl(v)
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        if (next == null) {
                            try {
                                val doc = Jsoup.parse(body, currUrl!!)
                                val btn = doc.selectFirst("a[href*='/d/'], a[href*='/token/download/'], a[href*='?pt='], a.download-btn, a.btn-download")
                                if (btn != null) {
                                    val href = btn.attr("abs:href")
                                    if (href.isNotBlank() && href != currUrl) {
                                        next = href
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        if (!next.isNullOrBlank()) {
                            currUrl = HttpClient.safeUrl(next)
                            ptHops++
                        }
                    }
                }
                if (currUrl == pageBeforeStep) {
                    onFailure(ResolverOutcome.Failure("Loadedfiles token chain stalled on step $step ($currUrl) — neither dlTimer nor downloadUrl found"))
                    // Same page came back with nothing actionable: further steps
                    // would repeat it verbatim. Give up on this host here; the
                    // mirror-fallthrough below lets the next candidate try.
                    android.util.Log.w("AnonDownload", "Loadedfiles token chain stalled (no progress) on $currUrl")
                    break
                }
            }
            // The token chain failed on the reachable-first host (it answered
            // the probe but the chain dead-ended). reachable-first would pin
            // THIS host for every later call, so the other mirror TLDs would
            // never run their own chain. Release the pin: the next resolve
            // (retry, next episode) starts from the link's own TLD again.
            lastWorkingHost = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            onFailure(resolverFailure(e))
            android.util.Log.e("AnonDownload", "LoadedfilesResolver error: ${e.message}", e)
        }
        return null
    }

    /**
     * Follow up to 3 redirects manually and return the URL that actually
     * served a page. loadedfiles.org is a 301 shell for loadedfiles.net —
     * treating the shell as the working host poisoned the whole chain.
     */
    private suspend fun probeEffectiveUrl(client: okhttp3.OkHttpClient, startUrl: String): String? {
        var url = startUrl
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder()
                .url(HttpClient.safeUrl(url))
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", "https://my9jarocks.bz/")
                .build()
            HttpClient.executeCancellable(client, req) use@{ res ->
                val loc = res.header("Location")
                if (res.code in 300..399 && !loc.isNullOrBlank()) {
                    url = HttpClient.safeUrl(HttpClient.safeResolveUri(res.request.url.toString(), loc))
                } else if (res.code in 200..299) {
                    return url
                } else {
                    return null
                }
            }
        }
        return null
    }

    private fun unescapeJsUrl(s: String): String {
        var str = s.replace("\\/", "/")
        if (str.contains("\\u")) {
            val m = Pattern.compile("""\\u([0-9a-fA-F]{4})""").matcher(str)
            val sb = StringBuffer()
            while (m.find()) {
                val hex = m.group(1)
                val code = hex?.toIntOrNull(16)
                if (code != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(code.toChar().toString()))
                }
            }
            m.appendTail(sb)
            str = sb.toString()
        }
        return str
    }
}

// -------------------------------------------------------------
// 16. WildshareResolver
// -------------------------------------------------------------
object WildshareResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("wildshare.net"))
    }

    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Wildshare: empty HTTP response for $url"
                return null
            }
            val ptMatcher = Pattern.compile("""[?&'"]pt(?:=|["']\s*:\s*["'])([A-Za-z0-9%+=/]+)""").matcher(html)
            if (ptMatcher.find()) {
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
                    .header("Referer", url)
                    .build()
                HttpClient.executeCancellable(noRedirectClient, req) use@{ res ->
                    if (res.code !in 200..399) {
                        lastFailure = "Wildshare: ?pt= returned HTTP ${res.code} for $fileId"
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "WildshareResolver: ?pt= returned HTTP ${res.code} for $fileId"
                        )
                        return null
                    }
                    val loc = res.header("Location")
                    if (!loc.isNullOrBlank()) {
                        return HttpClient.safeUrl(loc)
                    }
                    lastFailure = "Wildshare: no Location header returned on HTTP ${res.code}"
                    return null
                }
            }
            findDirectMediaUrl(html)?.let { return it }
            extractMp4FromHtml(html)?.let { return it }
            lastFailure = "Wildshare: no pt token or direct media found in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Wildshare: ${e.javaClass.simpleName}: ${e.message}"
        }
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
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("vidmoly."))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Vidmoly: empty HTTP response for $url"
                return null
            }
            val m = Pattern.compile("""(?:file|source|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").matcher(html)
            if (m.find()) {
                val stream = m.group(1)
                if (!stream.isNullOrBlank()) return stream
            }

            val direct = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
            if (!direct.isNullOrBlank()) return direct

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val um = Pattern.compile("""(?:file|source|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").matcher(unpacked)
                if (um.find()) {
                    val stream = um.group(1)
                    if (!stream.isNullOrBlank()) return stream
                }
                val unpDirect = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!unpDirect.isNullOrBlank()) return unpDirect
            }
            lastFailure = "Vidmoly: no m3u8/mp4 stream found in HTML or unpacked JS (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidmoly: ${e.javaClass.simpleName}: ${e.message}"
        }
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
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
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
                val m3u8 = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
                if (!m3u8.isNullOrBlank()) return m3u8

                val unpacked = JsUnpacker.unpack(html)
                if (!unpacked.isNullOrBlank()) {
                    val direct = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                    if (!direct.isNullOrBlank()) return direct
                }
            }
            lastFailure = "Streamwish: no playable stream extracted from ${candidates.size} candidate(s)"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Streamwish: ${e.javaClass.simpleName}: ${e.message}"
        }
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
        "vidhide.com", "ryderjet.com", "minochinos.com", "vidhidefast.com",
        "vidhidevip.com", "vidhidepro.com", "filelions.to"
    )
    @Volatile private var lastWorkingHost: String? = null
    @Volatile private var lastFailure: String? = null
    private val HOST_PART = Pattern.compile("""(https?://)([^/:]+)""", Pattern.CASE_INSENSITIVE)

    override fun lastResolveFailure(): String? = lastFailure

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
        lastFailure = null
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
                val m3u8 = extractM3u8FromHtml(html) ?: extractMp4FromHtml(html)
                if (!m3u8.isNullOrBlank()) {
                    lastWorkingHost = host
                    return m3u8
                }
                val unpacked = JsUnpacker.unpack(html)
                if (!unpacked.isNullOrBlank()) {
                    val direct = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                    if (!direct.isNullOrBlank()) {
                        lastWorkingHost = host
                        return direct
                    }
                }
            }
            lastFailure = "Vidhide: no stream extracted across ${candidates.size} mirror(s)"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Vidhide: ${e.javaClass.simpleName}: ${e.message}"
        }
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
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val urlHost = HttpClient.safeHost(url, "dood.to")
            val rawPath = url.replace("https://$urlHost", "").replace("http://$urlHost", "")
            val embedPath = rawPath.replace("/d/", "/e/").replace("/f/", "/e/")
            val candidates = LinkedHashSet<String>().apply {
                add(urlHost)
                add("doodstream.com")
                add("dood.to")
                add("d000d.com")
            }
            var html: String? = null
            var activeHost = urlHost
            var activeEmbedUrl = "https://$urlHost$embedPath"
            for (h in candidates) {
                val candidateUrl = "https://$h$embedPath"
                val res = HttpClient.getText(candidateUrl, referer = "https://$h/")
                if (!res.isNullOrBlank() && !looksLikeDeadPage(res)) {
                    html = res
                    activeHost = h
                    activeEmbedUrl = candidateUrl
                    break
                }
            }
            if (html == null) {
                lastFailure = "Doodstream: empty or blocked response across candidate mirrors (${candidates.joinToString()})"
                return null
            }
            val passPattern = Pattern.compile("""/pass_md5/([^"'\s]+)""")
            val matcher = passPattern.matcher(html)
            if (matcher.find()) {
                val passPath = matcher.group(1)
                val passUrl = "https://$activeHost/pass_md5/$passPath"
                val token = HttpClient.getText(passUrl, referer = activeEmbedUrl)
                if (!token.isNullOrBlank()) {
                    val tokenSlug = passPath.trimEnd('/').substringAfterLast('/')
                    val randomStr = (1..10).map { ('a'..'z').random() }.joinToString("")
                    val expiry = System.currentTimeMillis()
                    // /pass_md5/ returns a bare md5 token; the playable URL is
                    // https://<host>/e/<md5><random>?token=<md5>&expiry=<ts>.
                    // The scheme+host prefix is mandatory — a hostless string is
                    // not a URL and every downloader rejects it.
                    return "https://$activeHost/e/${token.trim()}$randomStr?token=$tokenSlug&expiry=$expiry"
                }
                lastFailure = "Doodstream: pass_md5 token fetch returned empty from $passUrl"
            } else {
                lastFailure = "Doodstream: pass_md5 pattern not found in HTML (len=${html.length})"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Doodstream: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 22. MixdropResolver
// -------------------------------------------------------------
object MixdropResolver : BaseResolver {
    private val HOSTS = listOf("mixdrop.", "mixdrp.", "mdfx9dc8n.net", "mixdroop.")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val embedUrl = url.replace("/f/", "/e/")
            val host = HttpClient.safeHost(url, "mixdrop.co")
            // Permissive SSL client: mixdrop.co frequently serves mismatched or broken CA chains
            val html = HttpClient.getText(embedUrl, referer = "https://$host/", permissive = true) ?: run {
                lastFailure = "Mixdrop: empty HTTP response for $embedUrl"
                return null
            }
            if (looksLikeDeadPage(html)) {
                lastFailure = "Mixdrop: page matches dead file markers"
                return null
            }
            val unpacked = JsUnpacker.unpack(html)
            val source = if (!unpacked.isNullOrBlank()) unpacked else html
            var matcher = Pattern.compile("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").matcher(source)
            if (matcher.find()) {
                var streamUrl = matcher.group(1) ?: ""
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
                return streamUrl
            }
            matcher = Pattern.compile("""(?:wurl|player\.src)\s*=\s*["']([^"']+)["']""").matcher(source)
            if (matcher.find()) {
                var streamUrl = matcher.group(1) ?: ""
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
                return streamUrl
            }
            lastFailure = "Mixdrop: MDCore.wurl pattern not found in HTML/unpacked JS"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Mixdrop: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 23. StreamtapeResolver
// -------------------------------------------------------------
object StreamtapeResolver : BaseResolver {
    private val HOSTS = listOf("streamtape.", "watchadsontape.", "strtape.tech")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val html = HttpClient.getText(url, referer = url) ?: run {
                lastFailure = "Streamtape: empty HTTP response for $url"
                return null
            }
            if (looksLikeDeadPage(html)) {
                lastFailure = "Streamtape: page matches dead file markers"
                return null
            }
            var matcher = Pattern.compile("""document\.getElementById\(["']robotlink["']\)\.innerHTML\s*=\s*["']([^"']+)["']\s*\+\s*(?:\(["']|["'])([^"'\)]+)(?:["']\)|["'])""").matcher(html)
            if (matcher.find()) {
                val part1 = matcher.group(1) ?: ""
                val part2 = matcher.group(2) ?: ""
                var stream = "$part1$part2"
                if (stream.startsWith("//")) stream = "https:$stream"
                return stream
            }
            matcher = Pattern.compile("""["']robotlink["']\s*\)\s*\.innerHTML\s*=\s*["']([^"']+)["']""").matcher(html)
            if (matcher.find()) {
                var stream = matcher.group(1) ?: ""
                if (stream.startsWith("//")) stream = "https:$stream"
                if (stream.startsWith("http")) return stream
            }
            lastFailure = "Streamtape: robotlink pattern not matched in HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "Streamtape: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 24. PixelDrainResolver
// -------------------------------------------------------------
object PixelDrainResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, listOf("pixeldrain.com"))
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            val clean = url.trimEnd('/').substringBefore('?')
            val fileId = clean.substringAfterLast('/')
            if (fileId.isNotBlank() && fileId != "u" && fileId != "d" && fileId != "pixeldrain.com") {
                return "https://pixeldrain.com/api/file/$fileId?download"
            }
            lastFailure = "PixelDrain: could not extract fileId from $url"
        } catch (e: Exception) {
            lastFailure = "PixelDrain: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 25. GenericLockerResolver
// -------------------------------------------------------------
object GenericLockerResolver : BaseResolver {
    private val HOSTS = listOf("lulacloud.com")
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean {
        return hostClaim(url, HOSTS)
    }

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        try {
            // Covers lulacloud.com (broken CA chain) — permissive, bounded
            // to these locker page fetches only.
            val html = HttpClient.getText(url, referer = url, permissive = true) ?: run {
                lastFailure = "GenericLocker: empty HTTP response for $url"
                return null
            }
            val m3u8 = extractM3u8FromHtml(html)
            if (!m3u8.isNullOrBlank()) return m3u8

            val mp4 = extractMp4FromHtml(html)
            if (!mp4.isNullOrBlank()) return mp4

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val direct = extractM3u8FromHtml(unpacked) ?: extractMp4FromHtml(unpacked)
                if (!direct.isNullOrBlank()) return direct
            }
            lastFailure = "GenericLocker: no media extracted from HTML (len=${html.length})"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastFailure = "GenericLocker: ${e.javaClass.simpleName}: ${e.message}"
        }
        return null
    }
}

// -------------------------------------------------------------
// 26. DynamicLockerResolver
// -------------------------------------------------------------
object DynamicLockerResolver : BaseResolver {
    @Volatile private var lastFailure: String? = null

    override fun lastResolveFailure(): String? = lastFailure

    override fun canResolve(url: String): Boolean = DynamicLockerEngine.canResolve(url)

    override suspend fun resolve(url: String, quality: String, depth: Int): String? {
        lastFailure = null
        val result = DynamicLockerEngine.resolve(url, quality, depth)
        if (result.isNullOrBlank()) {
            lastFailure = "DynamicLocker: OTA pipeline yielded no stream for $url"
        }
        return result
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
    if (exts.any { clean.endsWith(it) }) return true
    val query = url.substringAfter('?', "").lowercase()
    if (query.contains("response-content-disposition=") ||
        query.contains("filename=") ||
        query.contains("filename*=") ||
        query.contains("file=")) {
        return exts.any { query.contains(it) }
    }
    return false
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

