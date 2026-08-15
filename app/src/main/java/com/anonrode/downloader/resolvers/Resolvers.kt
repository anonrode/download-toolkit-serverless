package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

interface BaseResolver {
    fun canResolve(url: String): Boolean
    suspend fun resolve(url: String): String?
}

object DownloadwellaResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("downloadwella.com") || u.contains("wetafiles.com")
    }

    override suspend fun resolve(url: String): String? {
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
    private val HOSTS = listOf("hglink.to", "streamwish.", "sfastwish.", "embedwish.", "stwish.", "wishembed.")

    override fun canResolve(url: String): Boolean {
        val u = url.lowercase()
        return HOSTS.any { u.contains(it) }
    }

    override suspend fun resolve(url: String): String? {
        try {
            val vid = url.trimEnd('/').substringAfterLast('/')
            if (vid.length < 6) return null

            val candidates = listOf("https://sfastwish.com/e/$vid", "https://embedwish.com/e/$vid", url)
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

object PlutoMoviesResolver : BaseResolver {
    override fun canResolve(url: String): Boolean {
        return url.lowercase().contains("plutomovies.com")
    }

    override suspend fun resolve(url: String): String? {
        try {
            val html = HttpClient.getText(url, referer = "https://plutomovies.com/") ?: return null
            val regex1 = Regex("""location\.href\s*=\s*['"](https://[^'"]+)['"]""")
            val match1 = regex1.find(html)
            if (match1 != null) return match1.groupValues[1]

            val regex2 = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
            val match2 = regex2.find(html)
            if (match2 != null) return match2.groupValues[1]

            return null
        } catch (_: Exception) {
            return null
        }
    }
}

object ResolverRegistry {
    private val resolvers: List<BaseResolver> = listOf(
        DownloadwellaResolver,
        StreamwishResolver,
        PlutoMoviesResolver
    )

    suspend fun resolve(url: String, quality: String = "720p"): String? {
        // Direct media links pass straight through
        val lower = url.lowercase().substringBefore('?')
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".m3u8")) {
            return url
        }

        for (resolver in resolvers) {
            if (resolver.canResolve(url)) {
                val res = resolver.resolve(url)
                if (!res.isNullOrBlank()) return res
            }
        }

        // Direct CDN fallback
        if (url.contains("nkiserv.com") || url.contains("cdn")) {
            return url
        }

        return url
    }
}

fun findDirectVideo(text: String): String? {
    val pattern = Pattern.compile("""https?://[^\s"'<>]+?\.(?:m3u8|mp4|mkv)[^\s"'<>]*""")
    val matcher = pattern.matcher(text)
    if (matcher.find()) {
        return matcher.group().trimEnd('.', ',', ';', ')')
    }
    return null
}
