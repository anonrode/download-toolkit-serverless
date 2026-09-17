package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.net.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object MovieSizeMetadata {
    private val sizePattern = Regex("""(?<![\w.,-])(\d{1,6}(?:\.\d{1,3})?)\s*(MiB|GiB|MB|GB)\b""", RegexOption.IGNORE_CASE)

    fun parse(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, header, footer, aside").remove()
        val sizes = sizePattern.findAll(doc.text()).mapNotNull { match ->
            val number = match.groupValues[1]
            if ((number.toDoubleOrNull() ?: 0.0) <= 0.0) null
            else "$number ${match.groupValues[2].uppercase(Locale.ROOT)}"
        }.distinct().take(2).toList()
        // Multiple sizes may describe different encodings; never guess which one is selected.
        return sizes.singleOrNull().orEmpty()
    }

    fun isMetadataPage(url: String, showUrl: String): Boolean = try {
        val target = URI(url)
        val show = URI(showUrl)
        target.scheme in listOf("http", "https") && target.scheme == show.scheme &&
            target.host != null && target.host.equals(show.host, ignoreCase = true) &&
            target.port == show.port && target.userInfo == null &&
            (target.path.startsWith("/dl-") || target.path.startsWith("/temp/"))
    } catch (_: Exception) { false }

    suspend fun enrich(
        items: List<EpisodeItem>,
        showUrl: String,
        fetch: (String, String, Long) -> String? = ::fetchPage
    ): List<EpisodeItem> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        var attempts = 0
        return items.map { item ->
            currentCoroutineContext().ensureActive()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (item.sizeText.isNotBlank() || attempts >= 2 || remaining <= 0 || !isMetadataPage(item.url, showUrl)) {
                item
            } else {
                attempts++
                val size = try {
                    fetch(item.url, showUrl, remaining)?.let(::parse).orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { "" }
                currentCoroutineContext().ensureActive()
                item.copy(sizeText = size)
            }
        }
    }

    private fun fetchPage(startUrl: String, showUrl: String, budgetMs: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceAtMost(3000L))
        var url = startUrl
        repeat(3) {
            if (!isMetadataPage(url, showUrl)) return null
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0L) return null
            val client = HttpClient.shared.newBuilder()
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(remaining, TimeUnit.MILLISECONDS).build()
            val req = Request.Builder().url(url)
                .header("User-Agent", HttpClient.DEFAULT_UA).header("Referer", showUrl).build()
            val next = HttpClient.executeCancellable(client, req) { response ->
                if (response.code in 300..399) {
                    response.header("Location")?.let { HttpClient.safeResolveUri(url, it) }
                } else {
                    if (!response.isSuccessful) return null
                    val type = response.header("Content-Type").orEmpty().substringBefore(';').trim()
                    if (!type.equals("text/html", true) && !type.equals("application/xhtml+xml", true)) return null
                    return HttpClient.cappedText(response, maxBytes = 65536L, budgetMs = remaining)
                }
            } ?: return null
            url = next
        }
        return null
    }
}
