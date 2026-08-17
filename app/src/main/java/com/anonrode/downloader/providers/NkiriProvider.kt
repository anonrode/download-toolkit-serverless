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

object NkiriProvider : SiteProvider {
    override val name: String = "nkiri"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val cleanSlug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")
            val rssUrl = "$mainUrl/search/$cleanSlug/feed/rss2/"

            val rssXml = HttpClient.getText(rssUrl, referer = "$mainUrl/")
            if (!rssXml.isNullOrBlank()) {
                val doc = Jsoup.parse(rssXml, "", org.jsoup.parser.Parser.xmlParser())
                val items = doc.select("item")

                for (item in items) {
                    val title = item.selectFirst("title")?.text()?.trim() ?: ""
                    val rawLink = item.selectFirst("link")?.text()?.trim() ?: ""
                    val link = rawLink.substringBefore("?") // Strip tracking UTM params

                    val desc = item.selectFirst("description")?.text() ?: ""
                    val content = item.selectFirst("content\\:encoded")?.text() ?: item.selectFirst("content:encoded")?.text() ?: ""
                    var poster = Regex("""<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
                        .find(desc)?.groupValues?.get(1)
                        ?: Regex("""<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
                            .find(content)?.groupValues?.get(1) ?: ""

                    val lowerTitle = title.lowercase()
                    val cat = when {
                        lowerTitle.contains("korean") || lowerTitle.contains("kdrama") || lowerTitle.contains("c-drama") || lowerTitle.contains("drama") || lowerTitle.contains("series") || lowerTitle.contains("season") -> "Asian Drama"
                        lowerTitle.contains("nollywood") || lowerTitle.contains("yoruba") -> "Nollywood"
                        else -> "Asian Drama & Movies"
                    }

                    if (link.isNotBlank() && title.isNotBlank()) {
                        results.add(
                            ShowCard(
                                title = title,
                                url = link,
                                posterUrl = poster,
                                site = name,
                                category = cat
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val cleanUrl = showUrl.substringBefore("?")
        val show = ShowCard(title = "NKiri Show", url = cleanUrl, site = name)
        try {
            val html = HttpClient.getText(cleanUrl, referer = "$mainUrl/") ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "NKiri Show"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, meta[property=og:image]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src")
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val lockerSelectors = listOf(
                ".entry-content a[href*='downloadwella']",
                ".entry-content a[href*='wetafiles']",
                ".entry-content a[href*='loadedfiles']",
                ".entry-content a[href*='waffi']",
                ".entry-content a[href*='vikingfile']",
                ".entry-content a[href*='lulacloud']",
                ".entry-content a[href*='nkiserv']",
                ".elementor-button-wrapper a[href*='download']",
                ".entry-content a.elementor-button"
            )

            val links = doc.select(lockerSelectors.joinToString(", "))

            var count = 1
            for (a in links) {
                val href = a.attr("abs:href")
                val text = a.text().trim()
                if (href.isNotBlank() && !href.contains("telegram", ignoreCase = true) && !href.contains("dramakey", ignoreCase = true)) {
                    val epTitle = if (text.isNotBlank() && text.length < 50 && !text.equals("Download Movie", ignoreCase = true)) {
                        text
                    } else if (links.size == 1) {
                        "Full Movie"
                    } else {
                        "Episode $count"
                    }

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

            val card = ShowCard(title = title, url = cleanUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        val isSingleSocket = episodeUrl.contains("nkiserv.com") || direct.contains(".m3u8")
        val isHls = direct.contains(".m3u8") || direct.contains("manifest")

        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mkv" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = if (isSingleSocket) 1 else 16
        )
    }
}
