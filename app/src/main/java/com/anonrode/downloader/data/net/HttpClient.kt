package com.anonrode.downloader.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object HttpClient {
    const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val reqBuilder = Request.Builder()
            .url(url)
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
                if (res.isSuccessful) res.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
