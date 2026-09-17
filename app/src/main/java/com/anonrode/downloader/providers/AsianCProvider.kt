package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.util.DownloadLinkLabels
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
    // OTA-movable like every other provider (v3.1.6): the default table maps
    // "asianc" to exactly the URL that was hardcoded here, so behavior is
    // identical today — but if the domain ever flips, the playbook can heal
    // it without an APK update (nepu already could; this closed the gap).
    override val mainUrl: String get() = com.anonrode.downloader.data.rules.DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        com.anonrode.downloader.data.rules.DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) return results
        }
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
        com.anonrode.downloader.data.rules.DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, showUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.title.ifBlank { show.title },
                    url = showUrl,
                    posterUrl = res.posterUrl.ifBlank { show.posterUrl },
                    site = name
                )
                return ShowDetails(show = card, synopsis = res.synopsis, episodes = res.episodes)
            }
        }
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
            // Container selectors FIRST; the page-wide `-episode-` tail is a
            // sidebar magnet (related-episode widgets share the slug shape),
            // so it only rescues an otherwise-empty drawer.
            var epLinks = doc.select("ul.list-episode-item-2 li a, .all-episodes li a, .list-episode a, .list-episode-item a")
            if (epLinks.isEmpty()) epLinks = doc.select("a[href*='-episode-']")

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

                // Mirror-server / part rows must not be relabeled "Episode N"
                // (2026-09-13 drawer bug class, shared with Rocks).
                val mirrorLabel = DownloadLinkLabels.serverOrPart(epRaw, href)
                val cleanTitle = when {
                    mirrorLabel != null -> mirrorLabel
                    epRaw.contains("Episode", ignoreCase = true) ->
                        "Episode $epNum" + if (epRaw.contains("RAW", ignoreCase = true)) " (RAW)" else ""
                    else -> "Episode $epNum"
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

            // SINGLE-FILM shape used to yield an empty drawer (the site has
            // no episode list on movie pages): if a player is embedded, the
            // detail URL itself is the watch/download target.
            if (episodes.isEmpty()) {
                val player = doc.selectFirst("#video-embed iframe, .video-embed iframe, .watch-content iframe, iframe[src]")
                if (player != null) {
                    episodes.add(EpisodeItem(title = "Full Movie", url = showUrl, episodeNum = 1, site = name))
                }
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedBy { it.episodeNum })
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        var direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: ResolverRegistry.resolve(episodeUrl, quality)
        if (direct.isNullOrBlank()) {
            try {
                val html = HttpClient.getText(episodeUrl, referer = "$mainUrl/") ?: ""
                val doc = Jsoup.parse(html, episodeUrl)
                val candidates = doc.select("iframe[src]").mapNotNull { iframe ->
                    val rawSrc = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                    val src = HttpClient.safeResolveUri(episodeUrl, rawSrc)
                    if (src.startsWith("http")) src else null
                }
                if (candidates.isNotEmpty()) {
                    direct = ResolverRegistry.resolveAny(candidates, quality)
                }
            } catch (_: Exception) {}
        }

        val target = if (!direct.isNullOrBlank()) direct else {
            if (com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(episodeUrl)) episodeUrl else ""
        }
        val isHls = target.contains(".m3u8") || target.contains("manifest")
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
