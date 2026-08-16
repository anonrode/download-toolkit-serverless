package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

object AsianCProvider : SiteProvider {
    override val siteName: String = "asianc"
    override val baseUrl: String = "https://asianc.id"

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api?a=search&keyword=$encoded"
            val jsonStr = HttpClient.getText(url, referer = "$baseUrl/") ?: return emptyList()

            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val rawUrl = item.optString("url")
                val name = item.optString("name").ifEmpty { item.optString("value") }
                val cover = item.optString("cover")

                if (rawUrl.isNotBlank() && name.isNotBlank()) {
                    val fullUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                    results.add(
                        ShowCard(
                            title = name,
                            posterUrl = cover,
                            detailUrl = fullUrl,
                            site = siteName,
                            category = "Asian Drama"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(detailUrl: String): ShowDetails? {
        try {
            val html = HttpClient.getText(detailUrl, referer = "$baseUrl/") ?: return null
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1, .info h1")?.text()?.trim() ?: "Asian Drama"
            val poster = doc.selectFirst(".img img, .details img")?.attr("abs:src") ?: ""
            val synopsis = doc.selectFirst(".info p, .details p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val epLinks = doc.select("ul.list-episode-item-2 li a, .all-episodes li a, .list-episode a")

            for (link in epLinks) {
                val href = link.attr("abs:href")
                val epText = link.selectFirst(".title, h3")?.text()?.trim() ?: link.text().trim()
                val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)

                if (href.isNotBlank()) {
                    episodes.add(
                        EpisodeItem(
                            title = epText.ifEmpty { "Episode $epNum" },
                            episodeNum = epNum,
                            downloadPageUrl = href,
                            isDirect = false
                        )
                    )
                }
            }

            // Normalise episode ordering
            val sorted = episodes.sortedBy { it.episodeNum }
            return ShowDetails(
                title = title,
                posterUrl = poster,
                synopsis = synopsis,
                episodes = sorted,
                site = siteName
            )
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun resolveEpisode(episode: EpisodeItem): String? {
        try {
            val html = HttpClient.getText(episode.downloadPageUrl, referer = "$baseUrl/") ?: return null
            val doc = Jsoup.parse(html)

            // Extract embed players (Streamwish, Vidhide, Standard)
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("abs:src")
                val direct = ResolverRegistry.resolve(src)
                if (!direct.isNullOrBlank()) return direct
            }

            // Download buttons
            val dls = doc.select(".download a, a[download]")
            for (a in dls) {
                val href = a.attr("abs:href")
                val direct = ResolverRegistry.resolve(href)
                if (!direct.isNullOrBlank()) return direct
            }

            return null
        } catch (_: Exception) {
            return null
        }
    }
}
