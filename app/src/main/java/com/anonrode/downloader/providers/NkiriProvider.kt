package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import java.net.URLEncoder

object NkiriProvider : SiteProvider {
    override val name: String = "nkiri"
    override val mainUrl: String = "https://thenkiri.com"
    override val requiresSingleSocket: Boolean = true

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val rssUrl = "$mainUrl/search/$encoded/feed/rss2/"
            val xml = HttpClient.getText(rssUrl)
            if (!xml.isNullOrBlank()) {
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
                                category = "Asian Drama"
                            )
                        )
                    }
                }
            }

            // HTML search fallback if RSS was empty
            if (results.isEmpty()) {
                val html = HttpClient.getText("$mainUrl/?s=$encoded")
                if (!html.isNullOrBlank()) {
                    val doc = Jsoup.parse(html)
                    for (article in doc.select("article, .post-item, .elementor-post")) {
                        val link = article.selectFirst("a")?.attr("abs:href") ?: ""
                        val title = article.selectFirst("h2, .entry-title, h3")?.text()?.trim() ?: ""
                        val poster = article.selectFirst("img")?.attr("abs:src") ?: ""
                        if (link.isNotBlank() && title.isNotBlank()) {
                            results.add(
                                ShowCard(
                                    title = title,
                                    url = link,
                                    posterUrl = poster,
                                    site = name,
                                    category = "Asian Drama"
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "NKiri Show", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html)

            val pageTitle = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "NKiri Drama"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src") ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val dlLinks = doc.select(".entry-content a[href*='downloadwella.com'], .entry-content a[href*='wetafiles.com'], .entry-content a.elementor-button")

            var count = 1
            for (a in dlLinks) {
                val href = a.attr("abs:href")
                val text = a.text().trim()
                if (href.isNotBlank()) {
                    val epTitle = if (text.isNotBlank() && !text.equals("download", ignoreCase = true)) text else "Episode $count"
                    episodes.add(
                        EpisodeItem(
                            title = epTitle,
                            url = href,
                            episodeNum = count++,
                            site = name
                        )
                    )
                }
            }

            val card = ShowCard(title = pageTitle, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mkv" },
            headers = mapOf("Referer" to mainUrl),
            backend = "aria2c",
            parallelSockets = 1
        )
    }
}
