package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import java.net.URLEncoder

object DramaRainProvider : SiteProvider {
    override val siteName: String = "dramarain"
    override val baseUrl: String = "https://dramarain.com"

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val slug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")
            val candidateUrls = listOf(
                "$baseUrl/$slug/",
                "$baseUrl/drama/$slug/",
                "$baseUrl/$slug-korean-drama/",
                "$baseUrl/$slug-season-1/"
            )

            for (url in candidateUrls) {
                val html = HttpClient.getText(url) ?: continue
                val doc = Jsoup.parse(html)
                val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: continue
                val poster = doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src") ?: ""

                results.add(
                    ShowCard(
                        title = title,
                        posterUrl = poster,
                        detailUrl = url,
                        site = siteName,
                        category = "Asian Drama"
                    )
                )
                break
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(detailUrl: String): ShowDetails? {
        try {
            val html = HttpClient.getText(detailUrl) ?: return null
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Drama"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src") ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val links = doc.select(".entry-content a[href*='download'], .entry-content a[href*='episode']")

            var count = 1
            for (a in links) {
                val href = a.attr("abs:href")
                val text = a.text().trim()
                if (href.isNotBlank()) {
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank()) text else "Episode $count",
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
