package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import java.net.URLEncoder

object RocksProvider : SiteProvider {
    override val siteName: String = "9jarocks"
    override val baseUrl: String = "https://9jarocks.com"

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val rssUrl = "$baseUrl/search/$encoded/feed/rss2/"
            val xml = HttpClient.getText(rssUrl) ?: return emptyList()

            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            val items = doc.select("item")

            for (item in items) {
                val title = item.selectFirst("title")?.text()?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                val link = item.selectFirst("link")?.text()?.trim() ?: ""

                if (title.isNotBlank() && link.isNotBlank()) {
                    results.add(
                        ShowCard(
                            title = title,
                            posterUrl = "",
                            detailUrl = link,
                            site = siteName,
                            category = "Nollywood & Movies"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(detailUrl: String): ShowDetails? {
        try {
            val html = HttpClient.getText(detailUrl) ?: return null
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
                            episodeNum = count++,
                            downloadPageUrl = href,
                            isDirect = false
                        )
                    )
                }
            }

            return ShowDetails(
                title = title,
                posterUrl = poster,
                synopsis = synopsis,
                episodes = episodes,
                site = siteName
            )
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun resolveEpisode(episode: EpisodeItem): String? {
        return ResolverRegistry.resolve(episode.downloadPageUrl)
    }
}
