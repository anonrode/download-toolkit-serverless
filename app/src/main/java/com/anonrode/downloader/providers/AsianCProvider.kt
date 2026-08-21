package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

object AsianCProvider : SiteProvider {
    override val name: String = "asianc"
    override val mainUrl: String = "https://asianc.id"

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api?a=search&keyword=$encoded"
            val jsonStr = HttpClient.getText(url, referer = "$mainUrl/", tag = "search") ?: return emptyList()

            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val rawUrl = item.optString("url")
                val title = item.optString("name").ifEmpty { item.optString("value") }
                val cover = item.optString("cover")

                if (rawUrl.isNotBlank() && title.isNotBlank()) {
                    val fullUrl = if (rawUrl.startsWith("/")) "$mainUrl$rawUrl" else rawUrl
                    results.add(
                        ShowCard(
                            title = title,
                            url = fullUrl,
                            posterUrl = cover,
                            site = name,
                            category = "Asian Drama"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Asian Drama", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl, referer = "$mainUrl/") ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst("h1, .info h1")?.text()?.trim() ?: "Asian Drama"
            val poster = doc.selectFirst(".img img, .details img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".info p, .details p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val epLinks = doc.select("ul.list-episode-item-2 li a, .all-episodes li a, .list-episode a, .list-episode-item a, a[href*='-episode-']")

            for (link in epLinks) {
                val rawHref = link.attr("href")
                val href = link.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                if (href.isBlank() || href in seen) continue
                seen.add(href)

                val epRaw = link.selectFirst(".title, h3")?.text()?.trim() ?: link.text().trim()
                val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epRaw)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)

                val cleanTitle = if (epRaw.contains("Episode", ignoreCase = true)) {
                    "Episode $epNum" + if (epRaw.contains("RAW", ignoreCase = true)) " (RAW)" else ""
                } else {
                    "Episode $epNum"
                }

                episodes.add(
                    EpisodeItem(
                        title = cleanTitle,
                        url = href,
                        episodeNum = epNum,
                        site = name
                    )
                )
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedBy { it.episodeNum })
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        var direct = ResolverRegistry.resolve(episodeUrl, quality)
        if (direct.isNullOrBlank()) {
            try {
                val html = HttpClient.getText(episodeUrl, referer = "$mainUrl/") ?: ""
                val doc = Jsoup.parse(html, episodeUrl)
                for (iframe in doc.select("iframe[src]")) {
                    val rawSrc = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                    val src = HttpClient.safeResolveUri(episodeUrl, rawSrc)

                    val resolved = ResolverRegistry.resolve(src, quality)
                    if (!resolved.isNullOrBlank()) {
                        direct = resolved
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        val target = direct ?: episodeUrl
        val isHls = target.contains(".m3u8") || target.contains("manifest")
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
