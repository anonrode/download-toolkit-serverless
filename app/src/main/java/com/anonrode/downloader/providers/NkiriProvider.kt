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
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encoded"
            val html = HttpClient.getText(searchUrl, referer = "$mainUrl/", tag = "search")
            if (!html.isNullOrBlank()) {
                val doc = Jsoup.parse(html, searchUrl)
                val articles = doc.select("article, .post-item, .elementor-post, h2.entry-title a")

                for (art in articles) {
                    val linkElem = if (art.tagName() == "a") art else art.selectFirst("h2 a, .entry-title a, a")
                    if (linkElem == null) continue
                    val title = linkElem.text().trim()
                    val rawLink = linkElem.attr("abs:href").ifBlank { linkElem.attr("href") }
                    val link = rawLink.substringBefore("?")

                    if (link.isBlank() || title.isBlank() || link.contains("/category/") || link.contains("/how-to-") || link.contains("/page/")) {
                        continue
                    }

                    val posterElem = art.selectFirst("img")
                    val poster = posterElem?.attr("abs:src")?.ifBlank { posterElem.attr("src") } ?: ""

                    val lowerTitle = title.lowercase()
                    val cat = when {
                        lowerTitle.contains("korean") || lowerTitle.contains("kdrama") || lowerTitle.contains("c-drama") || lowerTitle.contains("drama") || lowerTitle.contains("series") || lowerTitle.contains("season") -> "Asian Drama"
                        lowerTitle.contains("nollywood") || lowerTitle.contains("yoruba") -> "Nollywood"
                        else -> "Asian Drama & Movies"
                    }

                    if (results.none { it.url == link }) {
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
            val doc = Jsoup.parse(html, cleanUrl)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "NKiri Show"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, meta[property=og:image]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p, .elementor-widget-theme-post-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val allLinks = doc.select("a[href]")

            var count = 1
            for (a in allLinks) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(cleanUrl, rawHref)
                }
                val lowerHref = href.lowercase()

                if (href.isBlank() || href in seen) continue
                if (lowerHref.contains("error?e=") || lowerHref.contains("errore=") || lowerHref.contains("telegram") || lowerHref.contains("facebook") || lowerHref.contains("twitter") || lowerHref.contains("whatsapp") || lowerHref.contains("how-to") || lowerHref.contains("cant-download")) {
                    continue
                }

                val isLocker = lowerHref.contains("downloadwella.com") ||
                        lowerHref.contains("wetafiles.com") ||
                        lowerHref.contains("loadedfiles") ||
                        lowerHref.contains("nkiserv.com") ||
                        lowerHref.contains("vikingfile") ||
                        lowerHref.contains("lulacloud") ||
                        lowerHref.contains("waffi")

                if (isLocker) {
                    seen.add(href)
                    val text = a.text().trim()
                    val parent = a.parent()
                    val prevHeading = parent?.previousElementSibling()?.let { elem ->
                        if (elem.tagName().startsWith("h", ignoreCase = true) || elem.tagName() == "p") elem.text().trim() else null
                    }

                    val epTitle = when {
                        !prevHeading.isNullOrBlank() && prevHeading.contains("Episode", ignoreCase = true) -> prevHeading
                        text.isNotBlank() && text.length < 40 && !text.equals("Download Episode", ignoreCase = true) && !text.equals("Download Movie", ignoreCase = true) && !text.equals("Download", ignoreCase = true) -> text
                        else -> "Episode $count"
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
            parallelSockets = 16
        )
    }
}
