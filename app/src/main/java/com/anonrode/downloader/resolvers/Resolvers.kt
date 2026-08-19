package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

interface BaseResolver {
    fun canResolve(url: String): Boolean
    suspend fun resolve(url: String, quality: String = "720p"): String?
}

object ResolverRegistry {
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

    suspend fun resolve(url: String, quality: String = "720p"): String? {
        val trimmed = url.trim()
        for (resolver in RESOLVERS) {
            if (resolver.canResolve(trimmed)) {
                val direct = resolver.resolve(trimmed, quality)
                if (!direct.isNullOrBlank()) {
                    return direct
                }
            }
        }
        return null
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
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) } && !lower.endsWith(".m3u8") && !lower.endsWith(".mp4")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val direct = decryptPayload(html)
            if (!direct.isNullOrBlank()) return direct

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

            val ifrMatcher = Pattern.compile("""<iframe[^>]+src=["']([^"']+)["']""").matcher(html)
            if (ifrMatcher.find()) {
                var inner = ifrMatcher.group(1) ?: ""
                inner = HttpClient.safeResolveUri(url, inner)
                if (inner != url) {
                    val innerDirect = resolve(inner, quality)
                    if (!innerDirect.isNullOrBlank()) return innerDirect
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun decryptPayload(html: String): String? {
        val m = Pattern.compile("""data-(?:name=["']crypto["'][^>]*?data-)?value=["']([^"']+)["']""").matcher(html)
        if (!m.find()) return null
        val b64 = m.group(1) ?: return null
        try {
            val cipherBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(KEY, "AES")
            val ivSpec = IvParameterSpec(IV)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
            if (decrypted.startsWith("http") && (decrypted.contains(".m3u8") || decrypted.contains(".mp4"))) {
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
        return lower.contains("kissasian9.ro") && lower.contains("/kisskh/") && !lower.endsWith(".m3u8")
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
        "gogoanime.me.uk", "vkspeed.com"
    )

    override fun canResolve(url: String): Boolean {
        if (url.contains("/playlist.php") || url.contains("/api/")) return false
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) } || lower.contains("/kisskh/")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null

            // Inner iframe (tamilembed / blogger)
            val doc = Jsoup.parse(html)
            val innerIframe = doc.selectFirst("iframe[src]")
            if (innerIframe != null) {
                val rawSrc = innerIframe.attr("src")
                val src = HttpClient.safeResolveUri(url, rawSrc)
                if (src != url && !src.startsWith("javascript:")) {
                    if (src.contains("blogger.com")) return src
                    val nested = ResolverRegistry.resolve(src, quality)
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
            if (url.contains("megaplay")) {
                val realIdMatcher = Pattern.compile("""data-realid=["'](\d+)["']""").matcher(html)
                val epIdMatcher = Pattern.compile("""/stream/(?:s-\d+/)?(\d+)""").matcher(url)
                val streamId = if (realIdMatcher.find()) realIdMatcher.group(1) else if (epIdMatcher.find()) epIdMatcher.group(1) else null
                if (!streamId.isNullOrBlank()) {
                    val apiUrl = "https://megaplay.buzz/stream/getSources?id=$streamId"
                    val apiJson = HttpClient.getText(apiUrl, referer = url)
                    if (!apiJson.isNullOrBlank()) {
                        val data = JSONObject(apiJson)
                        val fileUrl = data.optJSONObject("sources")?.optString("file")
                        if (!fileUrl.isNullOrBlank()) return fileUrl
                    }
                }
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
        return low.contains("blogger.com") && (low.contains("video.g") || low.contains("token="))
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
                val body = res.body?.string() ?: return null

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
        val low = url.lowercase()
        return HOSTS.any { low.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            var embedUrl = url.replace("nepu.to/", "nepu.gd/")
            if (embedUrl.contains("nepu.gd/watch")) {
                // Watch pages hide the player in iframe#playerFrame (or a
                // vidsrc-src iframe) — hop through it to the embed URL.
                val html = HttpClient.getText(embedUrl, referer = "https://nepu.gd/") ?: return null
                val doc = Jsoup.parse(html)
                val iframe = doc.selectFirst("iframe#playerFrame")
                    ?: doc.selectFirst("iframe[src*=vidsrc]")
                    ?: return null
                embedUrl = HttpClient.safeResolveUri(embedUrl, iframe.attr("src"))
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
                        if (res.isSuccessful) res.body?.bytes() else null
                    } ?: return null
                    val key = VidsrcWasmCrypto.extractKey(wasm) ?: return null
                    VidsrcWasmCrypto.decrypt(su, key).firstOrNull()
                }
                else -> null
            } ?: return null

            // Playlist URLs are CDN-gated by an IP-bound JWT issued by the
            // origin's generate.php; without it the CDN answers 401.
            val om = ORIGIN_PATTERN.matcher(streamUrl)
            val origin = if (om.find()) om.group() else return null
            val token = HttpClient.getText("$origin/generate.php")?.trim().orEmpty()
            val cleaned = streamUrl.replace(Regex("[?&]token=[^&]*"), "")
            return if (token.isNotEmpty()) {
                if (cleaned.contains("__TOKEN__")) cleaned.replace("__TOKEN__", token)
                else cleaned + (if (cleaned.contains("?")) "&" else "?") + "token=$token"
            } else cleaned
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
        return url.lowercase().contains("lightdl.cc")
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
                val body = res.body?.string() ?: return null
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
        val low = url.lowercase()
        return low.contains("5play.cc")
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
        return low.contains("vikingfile.com") && !low.endsWith(".mp4") && !low.endsWith(".mkv") && !low.endsWith(".m3u8")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/") ?: return null
            val m = Pattern.compile("""(?:window\.location|location\.href)\s*=\s*["']([^"']+)["']""").matcher(html)
            if (m.find()) {
                val loc = m.group(1) ?: return null
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
        return url.lowercase().contains("lulacloud.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/") ?: return null
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
        return (low.contains("dramarain.com") || low.contains("dramakey.cc")) && low.contains("/download")
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
        return low.contains("naijavault.com") && (low.contains("/dl-") || low.contains("/temp/"))
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://www.naijavault.com/") ?: return null
            val soup = Jsoup.parse(html)
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
        val low = url.lowercase()
        return KNOWN.any { low.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
        val lower = url.lowercase()
        return lower.contains("dl.plutomovies.com") || lower.contains("plutomovies.com/movie/")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://plutomovies.com/") ?: return null
            val m = Pattern.compile("""location\.href\s*=\s*['"](https?://[^'"]+)['"]""").matcher(html)
            if (m.find()) {
                val dest = m.group(1) ?: ""
                if (dest.isNotBlank() && !dest.equals(url, ignoreCase = true) && !isRootLockerDomain(dest)) return dest
            }

            val soup = Jsoup.parse(html)
            val btn = soup.selectFirst("a[href*='kissorgrab.com']")
            if (btn != null) {
                val href = btn.attr("abs:href")
                if (href.isNotBlank() && !href.equals(url, ignoreCase = true) && !isRootLockerDomain(href)) return href
            }

            return findDirectMediaUrl(html)
        } catch (_: Exception) {}
        return null
    }
}

// -------------------------------------------------------------
// 14. DownloadwellaResolver
// -------------------------------------------------------------
object DownloadwellaResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("downloadwella.com") || lower.contains("wetafiles.com") || lower.contains("kissorgrab.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val doc = Jsoup.parse(html, url)
            val formEl = doc.selectFirst("form") ?: return null

            val formAction = formEl.attr("abs:action").ifBlank { url }

            val formBuilder = FormBody.Builder()
            var hasInputs = false
            for (inp in formEl.select("input[name]")) {
                val name = inp.attr("name")
                val value = inp.attr("value")
                if (name != "method_free") {
                    formBuilder.add(name, value)
                    hasInputs = true
                }
            }
            if (!hasInputs) return null
            formBuilder.add("method_free", "Free Download")

            val form = formBuilder.build()
            val req = Request.Builder()
                .url(formAction)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", url)
                .post(form)
                .build()

            HttpClient.shared.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null

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
                    HttpClient.shared.newCall(step2Req).execute().use { res2 ->
                        if (res2.isSuccessful) {
                            val body2 = res2.body?.string() ?: ""
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
        return HOST_RE.matcher(url.lowercase()).find()
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val noRedirectClient = HttpClient.shared.newBuilder().followRedirects(false).build()

            // Find a host that actually answers, then run the token chain on it.
            var currUrl: String? = null
            for (host in candidateHosts(url)) {
                val candidate = HttpClient.safeUrl(rewriteHost(url, host))
                val probe = HttpClient.getText(candidate, referer = "https://my9jarocks.bz/")
                if (probe != null) {
                    lastWorkingHost = host
                    currUrl = candidate
                    break
                }
            }
            if (currUrl == null) {
                android.util.Log.w("AnonDownload", "Loadedfiles: no live host (${HttpClient.lastFailure})")
                return null
            }

            for (step in 1..8) {
                val referer = if (step == 1) "https://my9jarocks.bz/" else currUrl!!
                val req = Request.Builder()
                    .url(HttpClient.safeUrl(currUrl!!))
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .header("Referer", referer)
                    .build()

                noRedirectClient.newCall(req).execute().use { res ->
                    val loc = res.header("Location")
                    if (!loc.isNullOrBlank()) {
                        val safeLoc = HttpClient.safeUrl(loc)
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
                        val body = res.body?.string() ?: return@use
                        val direct = findDirectMediaUrl(body)
                        if (!direct.isNullOrBlank() && !isRootLockerDomain(direct)) {
                            val safeDirect = HttpClient.safeUrl(direct)
                            android.util.Log.d("AnonDownload", "Loadedfiles found direct media in body: $safeDirect")
                            return safeDirect
                        }

                        val m = Pattern.compile("""var downloadUrl = '([^']+)'""", Pattern.CASE_INSENSITIVE).matcher(body)
                        if (m.find()) {
                            // Keep the chain on the host that answered -- the page can
                            // hand back a link on a dead sibling TLD.
                            val next = m.group(1) ?: return@use
                            currUrl = HttpClient.safeUrl(
                                lastWorkingHost?.let { rewriteHost(next, it) } ?: next
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AnonDownload", "LoadedfilesResolver error: ${e.message}", e)
        }
        return null
    }
}

// -------------------------------------------------------------
// 16. WildshareResolver
// -------------------------------------------------------------
object WildshareResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return url.lowercase().contains("wildshare.net")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val ptMatcher = Pattern.compile("""pt=([A-Za-z0-9%+=/]+)""").matcher(html)
            if (ptMatcher.find()) {
                val pt = ptMatcher.group(0)
                val parts = url.trimEnd('/').split('/')
                val fileId = parts.lastOrNull { !it.endsWith(".mkv") && !it.endsWith(".mp4") } ?: parts.last()
                return "https://wildshare.net/$fileId?$pt"
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
        return url.lowercase().contains("waffi.cloud")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        return if (url.contains("?preview")) url.substringBefore("?preview") else url
    }
}

// -------------------------------------------------------------
// 18. VidmolyResolver
// -------------------------------------------------------------
object VidmolyResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return url.lowercase().contains("vidmoly.")
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
    private val HOSTS = listOf(
        "minochinos.com", "vidhide.", "vidhidepro.", "vidhidevip.",
        "filelions.", "vid-guard.", "nining.", "peytonepre.com",
        "techradar.ink", "ryderjet.com"
    )

    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val m3u8 = extractM3u8FromHtml(html)
            if (!m3u8.isNullOrBlank()) return m3u8

            val unpacked = JsUnpacker.unpack(html)
            if (!unpacked.isNullOrBlank()) {
                val direct = extractM3u8FromHtml(unpacked)
                if (!direct.isNullOrBlank()) return direct
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
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val host = HttpClient.safeHost(url, "dood.to")
            val embedUrl = url.replace("/d/", "/e/").replace("/f/", "/e/")
            val html = HttpClient.getText(embedUrl, referer = "https://$host/") ?: return null
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
                    return "${token.trim()}$randomStr?token=$tokenSlug&expiry=$expiry"
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
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val embedUrl = url.replace("/f/", "/e/")
            val host = HttpClient.safeHost(url, "mixdrop.co")
            val html = HttpClient.getText(embedUrl, referer = "https://$host/") ?: return null
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
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
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
        return url.lowercase().contains("pixeldrain.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
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
        val lower = url.lowercase()
        return HOSTS.any { lower.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
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

private fun extractM3u8FromHtml(html: String): String? {
    val matcher = Pattern.compile("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").matcher(html)
    if (matcher.find()) {
        return matcher.group(0)
    }
    return null
}

private fun extractMp4FromHtml(html: String): String? {
    val matcher = Pattern.compile("""https?://[^\s"'<>]+\.(?:mp4|mkv)[^\s"'<>]*""").matcher(html)
    if (matcher.find()) {
        return matcher.group(0)
    }
    return null
}

fun isDirectMediaUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return listOf(".mp4", ".mkv", ".m3u8", ".webm", ".avi", ".ts").any { clean.endsWith(it) }
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

