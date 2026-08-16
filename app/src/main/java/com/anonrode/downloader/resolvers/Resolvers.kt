package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import okhttp3.FormBody
import okhttp3.Request
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
                if (playerUrl.startsWith("//")) playerUrl = "https:$playerUrl"
                else if (playerUrl.startsWith("/")) playerUrl = URI(url).resolve(playerUrl).toString()
                val playerHtml = HttpClient.getText(playerUrl, referer = url)
                if (!playerHtml.isNullOrBlank()) {
                    val pDirect = decryptPayload(playerHtml)
                    if (!pDirect.isNullOrBlank()) return pDirect
                }
            }

            val ifrMatcher = Pattern.compile("""<iframe[^>]+src=["']([^"']+)["']""").matcher(html)
            if (ifrMatcher.find()) {
                var inner = ifrMatcher.group(1) ?: ""
                if (inner.startsWith("//")) inner = "https:$inner"
                else if (inner.startsWith("/")) inner = URI(url).resolve(inner).toString()
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
                val apiUrl = URI(url).resolve(apiPath).toString()
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
                var src = innerIframe.attr("src")
                if (src.startsWith("//")) src = "https:$src"
                else if (src.startsWith("/")) src = URI(url).resolve(src).toString()
                if (src != url && !src.startsWith("javascript:")) {
                    if (src.contains("blogger.com")) return src
                    val nested = ResolverRegistry.resolve(src, quality)
                    if (!nested.isNullOrBlank()) return nested
                }
            }

            // animesama layout
            val smMatcher = Pattern.compile("""const\s+STREAM\s*=\s*["']([^"']+)["']""").matcher(html)
            if (smMatcher.find()) return smMatcher.group(1)?.replace("\/", "/")

            // megaplays / takuembed layout
            val defMatcher = Pattern.compile("""var\s+defaultUrl\s*=\s*["']([^"']+)["']""").matcher(html)
            if (defMatcher.find()) {
                val targetUrl = defMatcher.group(1)?.replace("\/", "/") ?: ""
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
            val fReq = """[[["WcwnYd","["$token"]",null,"generic"]]]"""

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
// 5. VidsrcResolver
// -------------------------------------------------------------
object VidsrcResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val low = url.lowercase()
        return listOf("vidsrc.mov", "vidsrc.net", "vidsrc.cc", "vidsrc.to", "vidsrc.in", "vidsrc.pm", "vidsrc.xyz").any { low.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
            val ifr = Jsoup.parse(html).selectFirst("iframe[src]")
            if (ifr != null) {
                var src = ifr.attr("src")
                if (src.startsWith("//")) src = "https:$src"
                else if (src.startsWith("/")) src = URI(url).resolve(src).toString()
                val resolved = ResolverRegistry.resolve(src, quality)
                if (!resolved.isNullOrBlank()) return resolved
            }
            val m3u8 = extractM3u8FromHtml(html)
            if (!m3u8.isNullOrBlank()) return m3u8
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
            val host = URI(url).host ?: "dramarain.com"
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
                return m.group(1)
            }

            val soup = Jsoup.parse(html)
            val btn = soup.selectFirst("a[href*='kissorgrab.com'], a[href*='download']")
            if (btn != null) {
                return btn.attr("abs:href")
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
        val lower = url.lowercase()
        return lower.contains("downloadwella.com") || lower.contains("wetafiles.com") || lower.contains("kissorgrab.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url) ?: return null
            val doc = Jsoup.parse(html)

            val op = doc.selectFirst("input[name=op]")?.attr("value") ?: "download2"
            val id = doc.selectFirst("input[name=id]")?.attr("value") ?: ""
            val rand = doc.selectFirst("input[name=rand]")?.attr("value") ?: ""
            val methodFree = doc.selectFirst("input[name=method_free]")?.attr("value") ?: "Free Download >>"

            if (id.isBlank()) {
                val directLink = doc.selectFirst("a[href*='.mkv'], a[href*='.mp4']")?.attr("href")
                if (!directLink.isNullOrBlank()) return directLink
                return null
            }

            val form = FormBody.Builder()
                .add("op", op)
                .add("id", id)
                .add("rand", rand)
                .add("method_free", methodFree)
                .build()

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Referer", url)
                .post(form)
                .build()

            HttpClient.shared.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                val postDoc = Jsoup.parse(body)
                val direct = postDoc.selectFirst("a.btn-download, a[href*='download'], a[href*='.mkv'], a[href*='.mp4']")?.attr("abs:href")
                if (!direct.isNullOrBlank()) return direct

                val linkMatch = Pattern.compile("""https?://[^\s"'<>]+\.(?:mkv|mp4)[^\s"'<>]*""").matcher(body)
                if (linkMatch.find()) {
                    return linkMatch.group(0)
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

    override fun canResolve(url: String): Boolean {
        return HOST_RE.matcher(url.lowercase()).find()
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://my9jarocks.bz/") ?: return null
            val m1 = Pattern.compile("""var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'""", Pattern.CASE_INSENSITIVE).matcher(html)
            if (m1.find()) {
                val step1 = m1.group(1) ?: return null
                val html2 = HttpClient.getText(step1, referer = url) ?: return null
                val m2 = Pattern.compile("""var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'""", Pattern.CASE_INSENSITIVE).matcher(html2)
                if (m2.find()) {
                    return m2.group(1)
                }
            }
        } catch (_: Exception) {}
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
    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return listOf("streamwish.", "sfastwish.", "embedwish.", "stwish.", "wishembed.", "filelions.", "hglink.to").any { lower.contains(it) }
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
// 20. VidhideResolver
// -------------------------------------------------------------
object VidhideResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("vidhide.") || lower.contains("vidhidepro.") || lower.contains("vidhides.")
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
    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return listOf("dood.to", "dood.so", "dood.la", "dood.ws", "dso2.top", "doodstream.com", "d000d.com").any { lower.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val host = URI(url).host ?: "dood.to"
            val html = HttpClient.getText(url, referer = url) ?: return null
            val passPattern = Pattern.compile("""/pass_md5/([^"']+)""")
            val matcher = passPattern.matcher(html)
            if (matcher.find()) {
                val passPath = matcher.group(1)
                val passUrl = "https://$host/pass_md5/$passPath"
                val token = HttpClient.getText(passUrl, referer = url)
                if (!token.isNullOrBlank()) {
                    val randomStr = (1..10).map { ('a'..'z').random() }.joinToString("")
                    val expiry = System.currentTimeMillis()
                    return "$token$randomStr?expiry=$expiry"
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
    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("mixdrop.co") || lower.contains("mixdrop.to") || lower.contains("mixdrop.sx")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = url) ?: return null
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
    override fun canResolve(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("streamtape.com") || lower.contains("strtape.tech")
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
                return "https://pixeldrain.com/api/file/$fileId"
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
