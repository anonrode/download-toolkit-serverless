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

object DramaKeyProvider : SiteProvider {
    override val name: String = "DramaKey"
    override val mainUrl: String = "https://dramakey.cc"

    override suspend fun search(query: String): List<ShowCard> = withContext(Dispatchers.IO) {
        val searchUrl = "$mainUrl/?s=" + URLEncoder.encode(query.trim(), "UTF-8")
        val html = HttpClient.getText(searchUrl, referer = "$mainUrl/") ?: return@withContext emptyList()

        val doc = Jsoup.parse(html)
        val cards = mutableListOf<ShowCard>()

        doc.select("article, .post-item").forEach { el ->
            val a = el.selectFirst("a[href]") ?: return@forEach
            val title = el.selectFirst("h2, h3, .title")?.text()?.trim() ?: a.text().trim()
            val poster = el.selectFirst("img")?.attr("src") ?: ""
            val href = a.attr("href")

            if (href.startsWith("http") && title.isNotBlank()) {
                cards.add(
                    ShowCard(
                        title = title,
                        url = href,
                        posterUrl = poster,
                        site = "DramaKey",
                        category = "Drama"
                    )
                )
            }
        }
        cards
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails = withContext(Dispatchers.IO) {
        val html = HttpClient.getText(showUrl, referer = "$mainUrl/") ?: return@withContext ShowDetails(
            show = ShowCard(title = "DramaKey Show", url = showUrl, site = "DramaKey")
        )

        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1, .entry-title")?.text()?.trim() ?: "DramaKey Show"
        val poster = doc.selectFirst(".post-thumbnail img, article img")?.attr("src") ?: ""
        val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

        val episodes = mutableListOf<EpisodeItem>()
        var epCount = 0

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.contains("downloadwella.com") || href.contains("5play.cc") || href.contains("/download")) {
                epCount++
                episodes.add(
                    EpisodeItem(
                        title = String.format("Episode %02d", epCount),
                        url = href,
                        episodeNum = epCount,
                        site = "DramaKey"
                    )
                )
            }
        }

        ShowDetails(
            show = ShowCard(title = title, url = showUrl, posterUrl = poster, site = "DramaKey", totalEpisodes = episodes.size),
            synopsis = synopsis,
            episodes = episodes
        )
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe = withContext(Dispatchers.IO) {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        val isLocker = direct.contains("downloadwella")
        DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?'),
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to HttpClient.DEFAULT_UA),
            backend = "aria2c",
            parallelSockets = if (isLocker) 1 else 16
        )
    }
}
