package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import java.net.URLEncoder

object RocksProvider : SiteProvider {
    override val name: String = "9jarocks"
    override val mainUrl: String = "https://9jarocks.com"

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val rssUrl = "$mainUrl/search/$encoded/feed/rss2/"
            val xml = HttpClient.getText(rssUrl) ?: return emptyList()

            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            for (item in doc.select("item")) {
                val title = item.selectFirst("title")?.text()?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                val link = item.selectFirst("link")?.text()?.trim() ?: ""

                if (title.isNotBlank() && link.isNotBlank()) {
                    results.add(
                        ShowCard(
                            title = title,
                            url = link,
                            posterUrl = "",
                            site = name,
                            category = "Nollywood & Movies"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Movie", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Movie"
            val poster = doc.selectFirst(".entry-content img, .post-thumb img")?.attr("abs:src") ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val dls = doc.select(".entry-content a[href*='download'], .download-links a")

            var count = 1
            for (a in dls) {
                val href = a.attr("abs:href")
                val text = a.text().trim()
                if (href.isNotBlank()) {
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank()) text else "Download Link $count",
                            url = href,
                            episodeNum = count++,
                            site = name
                        )
                    )
                }
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" },
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
