package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

interface BaseResolver {
    fun canResolve(url: String): Boolean
    suspend fun resolve(url: String, quality: String = "720p"): String?
}

object DownloadwellaResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("downloadwella.com") || u.contains("wetafiles.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url) ?: return null
            val doc = Jsoup.parse(html)
            val form = doc.selectFirst("form") ?: return null

            val formBuilder = FormBody.Builder()
            form.select("input[name]").forEach { input ->
                val name = input.attr("name")
                val value = input.attr("value")
                if (name != "method_free") {
                    formBuilder.add(name, value)
                }
            }
            formBuilder.add("method_free", "Free Download")

            val postReq = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .post(formBuilder.build())
                .build()

            HttpClient.shared.newCall(postReq).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                return findDirectVideo(body)
            }
        } catch (_: Exception) {
            return null
        }
    }
}

object StreamwishResolver : BaseResolver {
    private val HOSTS = listOf("hglink.to", "streamwish.", "sfastwish.", "embedwish.", "stwish.", "wishembed.", "vidhide.", "vidhidepro.", "filelions.")

    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return HOSTS.any { u.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val vid = url.trimEnd('/').substringAfterLast('/')
            if (vid.length < 5) return null

            val candidates = listOf(
                "https://sfastwish.com/e/$vid",
                "https://embedwish.com/e/$vid",
                "https://vidhidepro.com/e/$vid",
                url
            )
            for (cand in candidates) {
                val html = HttpClient.getText(cand, referer = "https://asianc.id/") ?: continue
                val unpacked = JsUnpacker.unpack(html).ifEmpty { html }
                val direct = findDirectVideo(unpacked)
                if (direct != null) return direct
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }
}

object DoodstreamResolver : BaseResolver {
    private val HOSTS = listOf("dood.to", "dood.so", "dood.la", "dood.ws", "dso2.top", "doodstream.com", "d000d.com")

    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return HOSTS.any { u.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://dood.to/") ?: return null
            val passMatch = Regex("""/pass_md5/[^"'\s]+""").find(html)?.value ?: return null
            val passUrl = "https://dood.to$passMatch"

            val token = HttpClient.getText(passUrl, referer = url) ?: return null
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val randomStr = (1..10).map { chars.random() }.joinToString("")
            val expiry = System.currentTimeMillis()

            return "$token$randomStr?token=${passMatch.substringAfterLast('/')}&expiry=$expiry"
        } catch (_: Exception) {
            return null
        }
    }
}

object MixdropResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("mixdrop.co") || u.contains("mixdrop.to") || u.contains("mixdrop.sx")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val embedUrl = if (!url.contains("/e/")) url.replace("/f/", "/e/") else url
            val html = HttpClient.getText(embedUrl, referer = "https://mixdrop.co/") ?: return null
            val unpacked = JsUnpacker.unpack(html).ifEmpty { html }

            val srcMatch = Regex("""wurl\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""MDCore\.wurl\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)

            if (!srcMatch.isNullOrBlank()) {
                return if (srcMatch.startsWith("//")) "https:$srcMatch" else srcMatch
            }
            return findDirectVideo(unpacked)
        } catch (_: Exception) {
            return null
        }
    }
}

object StreamtapeResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("streamtape.com") || u.contains("watchadsontape.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://streamtape.com/") ?: return null
            val match = Regex("""id="videolink"[^>]*>.*?innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*['"]([^'"]+)['"]""").find(html)
            if (match != null) {
                val p1 = match.groupValues[1]
                val p2 = match.groupValues[2]
                val full = if ((p1 + p2).startsWith("//")) "https:" + (p1 + p2) else p1 + p2
                return full
            }
            return findDirectVideo(html)
        } catch (_: Exception) {
            return null
        }
    }
}

object PixelDrainResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return url.lowercase().contains("pixeldrain.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        val fileId = url.trimEnd('/').substringAfterLast('/')
        return if (fileId.isNotBlank()) "https://pixeldrain.com/api/file/$fileId" else null
    }
}

object PlutoMoviesResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return url.lowercase().contains("plutomovies.com")
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://plutomovies.com/") ?: return null
            val regex1 = Regex("""location\.href\s*=\s*['"](https://[^'"]+)['"]""")
            val match1 = regex1.find(html)
            if (match1 != null) return match1.groupValues[1]

            val regex2 = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
            val match2 = regex2.find(html)
            if (match2 != null) return match2.groupValues[1]

            return findDirectVideo(html)
        } catch (_: Exception) {
            return null
        }
    }
}

object GenericLockerResolver : BaseResolver {
    private val HOSTS = listOf("waffi.cloud", "loadedfiles.", "wildshare.net", "vikingfile.com", "lulacloud.com", "vidmoly.me")

    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return HOSTS.any { u.contains(it) }
    }

    override suspend fun resolve(url: String, quality: String): String? {
        try {
            val html = HttpClient.getText(url) ?: return null
            val unpacked = JsUnpacker.unpack(html).ifEmpty { html }
            return findDirectVideo(unpacked)
        } catch (_: Exception) {
            return null
        }
    }
}

object ResolverRegistry {
    private val resolvers: List<BaseResolver> = listOf(
        DownloadwellaResolver,
        StreamwishResolver,
        DoodstreamResolver,
        MixdropResolver,
        StreamtapeResolver,
        PixelDrainResolver,
        PlutoMoviesResolver,
        GenericLockerResolver
    )

    suspend fun resolve(url: String, quality: String = "720p", depth: Int = 0): String? {
        if (depth > 4) return url

        val lower = url.lowercase().substringBefore('?')
        val resolverDomains = listOf("waffi.cloud", "loadedfiles.", "wildshare.net", "vikingfile.com", "lulacloud.com", "pixeldrain.com", "streamtape.com", "vidmoly.me")
        if ((lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".m3u8")) && !resolverDomains.any { lower.contains(it) }) {
            return url
        }

        for (resolver in resolvers) {
            if (resolver.canResolve(url)) {
                val res = resolver.resolve(url, quality)
                if (!res.isNullOrBlank()) {
                    if (res != url) return resolve(res, quality, depth + 1)
                    return res
                }
            }
        }

        if (url.contains("nkiserv.com") || url.contains("cdn")) {
            return url
        }

        // Nothing could resolve this and it isn't a known direct host. Returning
        // the embed PAGE url here (the old behavior) meant aria2c downloaded the
        // HTML page -- a ~0.6MB unplayable file that then passed the size guard
        // and got marked COMPLETED. Fail cleanly instead so the caller reports it.
        return null
    }
}

fun findDirectVideo(text: String): String? {
    val pattern = Pattern.compile("""https?://[^\s"'<>]+?\.(?:m3u8|mp4|mkv)[^\s"'<>]*""")
    val matcher = pattern.matcher(text)
    if (matcher.find()) {
        val clean = matcher.group()
        return clean.trimEnd('.', ',', ';', ')')
    }
    return null
}
