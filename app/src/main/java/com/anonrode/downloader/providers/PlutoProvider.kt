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

object PlutoProvider : SiteProvider {
    override val name: String = "pluto"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val clean = query.replace("'", "").replace("’", "").trim()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "$mainUrl/search/$encoded/page/1"
            val html = HttpClient.getText(url, referer = "$mainUrl/") ?: return emptyList()
            val doc = Jsoup.parse(html)

            val seen = mutableSetOf<String>()
            for (a in doc.select("a[href*='/movie/'], a[href*='/series/']")) {
                val href = a.attr("abs:href").ifEmpty { a.attr("href") }
                val title = a.attr("title").ifEmpty { a.text() }.trim()
                if (href.isBlank() || title.isBlank() || href in seen) continue
                seen.add(href)

                val isSeries = href.contains("/series/")
                val img = a.selectFirst("img")
                val poster = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") } ?: ""

                results.add(
                    ShowCard(
                        title = title,
                        url = href,
                        posterUrl = poster,
                        site = name,
                        category = if (isSeries) "TV Series" else "Movie"
                    )
                )
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Pluto Title", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl, referer = "$mainUrl/") ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html, showUrl)
            val episodes = mutableListOf<EpisodeItem>()

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".poster img, .cover img")?.attr("abs:src") ?: ""
            val title = doc.selectFirst("h1")?.text()?.trim() ?: "Pluto Video"

            if (showUrl.contains("/series/") || showUrl.contains("/season")) {
                // Monolith parity (plutomovies.py): episode pages live under
                // /series/<show>/<episode-slug> — there is no /episodes/ path,
                // so the old /episodes/-only selector found ZERO episodes
                // (user-reported). Season hubs are discovered first, then each
                // season page is scanned for episode links.
                val seasonRegex = Regex("""/(series|season)/[^/]+/[^/]*season-\d+""", RegexOption.IGNORE_CASE)
                val seasonLinks = doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, a.attr("href")) }
                    if (href.isNotBlank() && href != showUrl && seasonRegex.containsMatchIn(href)) href else null
                }.distinct()

                val pagesToScan = if (seasonLinks.isEmpty()) listOf(showUrl) else seasonLinks
                var count = 1
                val seen = mutableSetOf<String>()
                for (pageUrl in pagesToScan) {
                    val pageDoc = if (pageUrl == showUrl) doc else {
                        val pageHtml = HttpClient.getText(pageUrl, referer = "$mainUrl/") ?: continue
                        Jsoup.parse(pageHtml, pageUrl)
                    }
                    for (a in pageDoc.select("a[href]")) {
                        val href = a.attr("abs:href").ifBlank {
                            HttpClient.safeResolveUri(pageUrl, a.attr("href"))
                        }
                        if (href.isBlank() || href in seen || href == showUrl) continue
                        // Episode links: /series/ or /episodes/ paths that are NOT
                        // season hubs themselves and not the page we're scanning.
                        val isEpisodeLink = (href.contains("/series/") || href.contains("/episodes/")) &&
                            !seasonRegex.containsMatchIn(href) &&
                            href != pageUrl
                        if (!isEpisodeLink) continue
                        seen.add(href)
                        // Monolith strips "Previous Episode"/"Next Episode" nav text.
                        val epName = a.text().trim()
                            .replace(Regex("""(?i)^(previous|next)\s+episode\b\s*"""), "")
                            .trim().ifEmpty { "Episode $count" }
                        episodes.add(
                            EpisodeItem(
                                title = epName,
                                url = href,
                                episodeNum = count++,
                                site = name
                            )
                        )
                    }
                }
            } else {
                // Movie download link from detail page
                val dlLink = doc.selectFirst("a[href*='dl.plutomovies.com']")?.let {
                    it.attr("abs:href").ifBlank { it.attr("href") }
                } ?: showUrl
                episodes.add(
                    EpisodeItem(
                        title = "Full Movie",
                        url = dlLink,
                        episodeNum = 1,
                        site = name
                    )
                )
            }

            return ShowDetails(show = show.copy(title = title, posterUrl = poster), episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "video.mp4" },
            headers = mapOf("Referer" to "$mainUrl/"),
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
