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

object PlutoProvider : SiteProvider {
    override val name: String = "Pluto"
    override val mainUrl: String = "https://plutomovies.com"

    override suspend fun search(query: String): List<ShowCard> = withContext(Dispatchers.IO) {
        val searchUrl = "$mainUrl/?s=" + URLEncoder.encode(query.trim(), "UTF-8")
        val html = HttpClient.getText(searchUrl, referer = "$mainUrl/") ?: return@withContext emptyList()
        val doc = Jsoup.parse(html)
        val cards = mutableListOf<ShowCard>()

        doc.select("article, .movie-item").forEach { el ->
            val a = el.selectFirst("a[href]") ?: return@forEach
            val title = el.selectFirst("h2, h3, .title")?.text()?.trim() ?: a.text().trim()
            val poster = el.selectFirst("img")?.attr("src") ?: ""
            val href = a.attr("href")

            if (href.startsWith("http")) {
                cards.add(ShowCard(title = title, url = href, posterUrl = poster, site = "Pluto", category = "Movie"))
            }
        }
        cards
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails = withContext(Dispatchers.IO) {
        val html = HttpClient.getText(showUrl, referer = "$mainUrl/") ?: return@withContext ShowDetails(
            show = ShowCard(title = "Pluto Movie", url = showUrl, site = "Pluto")
        )
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Pluto Movie"
        val poster = doc.selectFirst("article img")?.attr("src") ?: ""

        val episodes = listOf(EpisodeItem(title = "Full Movie", url = showUrl, episodeNum = 1, site = "Pluto"))
        ShowDetails(show = ShowCard(title = title, url = showUrl, posterUrl = poster, site = "Pluto", totalEpisodes = 1), episodes = episodes)
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe = withContext(Dispatchers.IO) {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?'),
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to HttpClient.DEFAULT_UA),
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
