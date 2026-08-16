package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

data class DynamicSiteConfig(
    val id: String,
    val displayName: String,
    val category: String,
    val baseUrl: String,
    val searchUrlPattern: String, // e.g. "{base}/?s={query}" or RSS
    val searchType: String = "html", // html, rss, or json
    val cardSelector: String = "article",
    val titleSelector: String = "h2, .entry-title, h3",
    val linkSelector: String = "a",
    val posterSelector: String = "img",
    val episodeLinkSelector: String = "a[href*='download'], a[href*='episode']"
)

class GenericDeclarativeProvider(
    private val config: DynamicSiteConfig
) : SiteProvider {

    override val name: String = config.id
    override val mainUrl: String get() = config.baseUrl

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val targetUrl = config.searchUrlPattern
                .replace("{base}", mainUrl.trimEnd('/'))
                .replace("{query}", encoded)

            val raw = HttpClient.getText(targetUrl, referer = "$mainUrl/") ?: return emptyList()

            if (config.searchType == "rss") {
                val doc = Jsoup.parse(raw, "", org.jsoup.parser.Parser.xmlParser())
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
                                category = config.category
                            )
                        )
                    }
                }
            } else if (config.searchType == "json") {
                val json = JSONObject(raw)
                val items = json.optJSONArray("results") ?: json.optJSONArray("data")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val obj = items.getJSONObject(i)
                        val title = obj.optString("title").ifEmpty { obj.optString("name") }
                        val link = obj.optString("url").ifEmpty { obj.optString("link") }
                        val poster = obj.optString("poster").ifEmpty { obj.optString("image") }
                        if (title.isNotBlank() && link.isNotBlank()) {
                            results.add(
                                ShowCard(
                                    title = title,
                                    url = if (link.startsWith("/")) "$mainUrl$link" else link,
                                    posterUrl = poster,
                                    site = name,
                                    category = config.category
                                )
                            )
                        }
                    }
                }
            } else {
                // HTML scraping
                val doc = Jsoup.parse(raw)
                val cards = doc.select(config.cardSelector)
                for (card in cards) {
                    val linkEl = if (config.linkSelector == "self") card else card.selectFirst(config.linkSelector)
                    val titleEl = if (config.titleSelector == "self") card else card.selectFirst(config.titleSelector)
                    val posterEl = card.selectFirst(config.posterSelector)

                    val href = linkEl?.attr("abs:href") ?: linkEl?.attr("href") ?: ""
                    val title = titleEl?.text()?.trim() ?: ""
                    val poster = posterEl?.attr("abs:src") ?: posterEl?.attr("src") ?: ""

                    if (href.isNotBlank() && title.isNotBlank()) {
                        results.add(
                            ShowCard(
                                title = title,
                                url = href,
                                posterUrl = poster,
                                site = name,
                                category = config.category
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = config.displayName, url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl, referer = "$mainUrl/") ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: config.displayName
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, img.cover")?.attr("abs:src") ?: ""
            val synopsis = doc.selectFirst(".entry-content p, .synopsis, .description")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val links = doc.select(config.episodeLinkSelector)

            var count = 1
            for (a in links) {
                val href = a.attr("abs:href")
                val text = a.text().trim()
                if (href.isNotBlank()) {
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank() && !text.equals("download", ignoreCase = true)) text else "Episode $count",
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
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "media.mp4" },
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
