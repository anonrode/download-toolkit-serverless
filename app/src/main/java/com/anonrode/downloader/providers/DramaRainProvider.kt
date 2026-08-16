package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

object DramaRainProvider : SiteProvider {
    override val name: String = "dramarain"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encoded"
            val html = HttpClient.getText(searchUrl)
            if (!html.isNullOrBlank()) {
                val doc = Jsoup.parse(html)
                val articles = doc.select("article, .post-item, .entry-title a")
                for (item in articles) {
                    val a = if (item.tagName() == "a") item else item.selectFirst("a[href]")
                    val title = a?.text()?.trim() ?: ""
                    val href = a?.attr("abs:href") ?: ""
                    val img = item.selectFirst("img")?.attr("abs:src") ?: ""

                    if (href.isNotBlank() && title.isNotBlank()) {
                        results.add(
                            ShowCard(
                                title = title,
                                url = href,
                                posterUrl = img,
                                site = name,
                                category = "Asian Drama"
                            )
                        )
                    }
                }
            }

            if (results.isEmpty()) {
                val slug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")
                val candidateUrls = listOf(
                    "$mainUrl/$slug/",
                    "$mainUrl/drama/$slug/",
                    "$mainUrl/$slug-korean-drama/",
                    "$mainUrl/$slug-season-1/"
                )

                for (url in candidateUrls) {
                    val directHtml = HttpClient.getText(url) ?: continue
                    val doc = Jsoup.parse(directHtml)
                    val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: continue
                    val poster = doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src") ?: ""

                    results.add(
                        ShowCard(
                            title = title,
                            url = url,
                            posterUrl = poster,
                            site = name,
                            category = "Asian Drama"
                        )
                    )
                    break
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Drama", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Drama"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src") ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val links = doc.select(".entry-content a[href*='download'], .entry-content a[href*='episode'], .entry-content a[href*='loadedfiles'], .entry-content a[href*='waffi']")

            var count = 1
            for (a in links) {
                val href = a.attr("abs:href")
                val text = a.text().trim()
                if (href.isNotBlank()) {
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank()) text else "Episode $count",
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
        val isHls = direct.contains(".m3u8") || direct.contains("manifest")
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mp4" },
            backend = if (isHls) "ytdlp" else "aria2c",
            parallelSockets = if (isHls) 1 else 16
        )
    }
}
