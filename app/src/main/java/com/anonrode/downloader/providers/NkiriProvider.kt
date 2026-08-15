package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 100% on-device NKiri provider ported 1:1 from src/extractors/nkiri.py & src/search.py.
 */
object NkiriProvider : SiteProvider {
    override val name: String = "NKiri"
    override val mainUrl: String = "https://thenkiri.com"
    override val requiresSingleSocket: Boolean = true

    override suspend fun search(query: String): List<ShowCard> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val searchUrl = "$mainUrl/?s=" + URLEncoder.encode(cleanQuery, "UTF-8")
        val html = HttpClient.getText(searchUrl, referer = "$mainUrl/") ?: return@withContext emptyList()

        val doc = Jsoup.parse(html)
        val cards = mutableListOf<ShowCard>()

        doc.select("article").forEach { article ->
            val titleTag = article.selectFirst("h2, h3, .entry-title, .post-title") ?: article.selectFirst("a")
            val linkTag = article.selectFirst("a[href]")
            val imgTag = article.selectFirst("img")

            val title = titleTag?.text()?.trim() ?: return@forEach
            val url = linkTag?.attr("href") ?: return@forEach
            val poster = imgTag?.attr("src")?.ifBlank { imgTag.attr("data-src") } ?: ""

            if (url.startsWith("http") && url.contains("thenkiri.com")) {
                cards.add(
                    ShowCard(
                        title = title,
                        url = url,
                        posterUrl = poster,
                        site = "NKiri",
                        category = if (title.contains("Movie", ignoreCase = true)) "Movie" else "Drama"
                    )
                )
            }
        }
        cards
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails = withContext(Dispatchers.IO) {
        val html = HttpClient.getText(showUrl, referer = "$mainUrl/") ?: return@withContext ShowDetails(
            show = ShowCard(title = "Unknown", url = showUrl, site = "NKiri")
        )

        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1, .entry-title")?.text()?.trim() ?: "NKiri Show"
        val poster = doc.selectFirst(".post-thumbnail img, article img")?.attr("src") ?: ""
        val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

        val episodes = mutableListOf<EpisodeItem>()
        var epCount = 0

        // Find all downloadwella / nkiserv anchors
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.contains("downloadwella.com") || href.contains("wetafiles.com") || href.contains("nkiserv.com")) {
                epCount++
                val text = a.text().trim()
                val epLabel = if (text.isNotBlank() && !text.equals("Download Episode", ignoreCase = true)) {
                    text
                } else {
                    String.format("Episode %02d", epCount)
                }

                episodes.add(
                    EpisodeItem(
                        title = epLabel,
                        url = href,
                        episodeNum = epCount,
                        site = "NKiri"
                    )
                )
            }
        }

        ShowDetails(
            show = ShowCard(
                title = title,
                url = showUrl,
                posterUrl = poster,
                site = "NKiri",
                totalEpisodes = episodes.size
            ),
            synopsis = synopsis,
            episodes = episodes
        )
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe = withContext(Dispatchers.IO) {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        val isLocker = direct.contains("downloadwella") || direct.contains("wetafiles")

        DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?'),
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to HttpClient.DEFAULT_UA
            ),
            backend = "aria2c",
            parallelSockets = if (isLocker) 1 else 16
        )
    }
}
