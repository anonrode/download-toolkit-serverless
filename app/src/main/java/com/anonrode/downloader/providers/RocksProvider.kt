package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager

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
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

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

                // The RSS description/content carries an <img> thumbnail inline;
                // pull it so the result card shows a real poster instead of the
                // lettered fallback. No extra network call.
                val desc = (item.selectFirst("content|encoded")?.text()
                    ?: item.selectFirst("description")?.text() ?: "")
                val poster = Regex("""<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(desc)?.groupValues?.get(1) ?: ""

                if (title.isNotBlank() && link.isNotBlank()) {
                    results.add(
                        ShowCard(
                            title = title,
                            url = link,
                            posterUrl = poster,
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
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst("h1.entry-title, h1, .post-title")?.text()?.trim() ?: "Movie"
            val poster = doc.selectFirst(".entry-content img, .post-thumb img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val allLinks = doc.select("a[href]")

            var count = 1
            for (a in allLinks) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    if (rawHref.startsWith("http")) rawHref else java.net.URI(showUrl).resolve(rawHref).toString()
                }
                val lowerHref = href.lowercase()

                if (href.isBlank() || href in seen) continue
                val isLocker = lowerHref.contains("loadedfiles") ||
                        lowerHref.contains("downloadwella") ||
                        lowerHref.contains("wetafiles") ||
                        lowerHref.contains("waffi") ||
                        lowerHref.contains("vikingfile") ||
                        lowerHref.contains("lulacloud")

                if (isLocker) {
                    seen.add(href)
                    val text = a.text().trim()
                    val parent = a.parent()
                    val parentText = parent?.text()?.trim() ?: ""

                    val epMatch = Regex("""(?:EPISODE|EP|E)\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(text)
                        ?: Regex("""(?:EPISODE|EP|E)\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(parentText)
                        ?: Regex("""S\d{1,2}E(\d{1,3})""", RegexOption.IGNORE_CASE).find(href)

                    val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: count
                    val baseName = "Episode $epNum"
                    val label = if (text.isNotBlank() && text != baseName && text.length < 35 && !text.equals("Download", ignoreCase = true)) {
                        "$baseName ($text)"
                    } else {
                        baseName
                    }

                    episodes.add(
                        EpisodeItem(
                            title = label,
                            url = href,
                            episodeNum = epNum,
                            site = name
                        )
                    )
                    count++
                }
            }

            // If no locker links found, check fallback download buttons
            if (episodes.isEmpty()) {
                val dls = doc.select(".entry-content a[href*='download'], .download-links a")
                for (a in dls) {
                    val rawHref = a.attr("href")
                    val href = a.attr("abs:href").ifBlank {
                        if (rawHref.startsWith("http")) rawHref else java.net.URI(showUrl).resolve(rawHref).toString()
                    }
                    if (href.isNotBlank() && href !in seen) {
                        seen.add(href)
                        val text = a.text().trim().ifEmpty { "Download Link $count" }
                        episodes.add(
                            EpisodeItem(
                                title = text,
                                url = href,
                                episodeNum = count++,
                                site = name
                            )
                        )
                    }
                }
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedBy { it.episodeNum })
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
