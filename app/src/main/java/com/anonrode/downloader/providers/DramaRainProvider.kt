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
                val doc = Jsoup.parse(html, searchUrl)
                val articles = doc.select("article, .post-item, .entry-title a, h2.entry-title a")
                for (item in articles) {
                    val a = if (item.tagName() == "a") item else item.selectFirst("h2 a, .entry-title a, a[href]")
                    val title = a?.text()?.trim() ?: ""
                    val rawHref = a?.attr("href") ?: ""
                    val href = a?.attr("abs:href")?.ifBlank {
                        HttpClient.safeResolveUri(searchUrl, rawHref)
                    } ?: ""
                    val img = item.selectFirst("img")?.let {
                        it.attr("abs:src").ifBlank { it.attr("src") }
                    } ?: ""

                    if (href.isNotBlank() && title.isNotBlank() && results.none { it.url == href }) {
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
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Drama"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val links = doc.select("a[href*='download'], a[href*='episode'], a[href*='loadedfiles'], a[href*='waffi'], .entry-content a")

            var count = 1
            for (a in links) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                val text = a.text().trim()
                if (href.isNotBlank() && href !in seen && !href.contains("/category/") && !href.contains("/tag/")) {
                    seen.add(href)
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank() && !text.equals("Download", ignoreCase = true)) text else "Episode $count",
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
            parallelSockets = 16
        )
    }
}
