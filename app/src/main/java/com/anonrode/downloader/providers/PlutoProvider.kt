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
            val cfg = DynamicRulesManager.getSiteConfig(name)
            val searchPattern = cfg?.searchPattern?.ifBlank { null } ?: "/search/{query}/page/1"
            val cardSelector = cfg?.cardSelector?.ifBlank { null } ?: "a[href*='/movie/'], a[href*='/series/']"
            val url = if (searchPattern.startsWith("http")) {
                searchPattern.replace("{base}", mainUrl.trimEnd('/')).replace("{query}", encoded)
            } else {
                "$mainUrl" + searchPattern.replace("{query}", encoded)
            }
            val html = HttpClient.getText(url, referer = "$mainUrl/", tag = "search") ?: return emptyList()
            val doc = Jsoup.parse(html, url)

            val seen = mutableSetOf<String>()
            for (a in doc.select(cardSelector)) {
                val href = (a.attr("abs:href").ifEmpty { a.attr("href") }).substringBefore('#')
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
            val html = HttpClient.getText(showUrl, referer = "$mainUrl/")
            if (html.isNullOrBlank()) {
                com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes: fetch returned null for $showUrl (lastFailure=${HttpClient.lastFailure})")
                return ShowDetails(show = show)
            }
            if (looksLikeSecurityChallenge(html)) {
                com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes: site returned a security challenge (Cloudflare) for $showUrl")
                return ShowDetails(show = show)
            }
            com.anonrode.downloader.util.DebugLog.resolve("pluto loadEpisodes: ${html.length / 1024}KiB from $showUrl")
            val doc = Jsoup.parse(html, showUrl)
            val episodes = mutableListOf<EpisodeItem>()

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".poster img, .cover img")?.attr("abs:src") ?: ""
            val title = doc.selectFirst("h1")?.text()?.trim() ?: "Pluto Video"
            val cfg = DynamicRulesManager.getSiteConfig(name)

            if (showUrl.contains("/series/") || showUrl.contains("/season")) {
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
                        val isEpisodeLink = (href.contains("/series/") || href.contains("/episodes/")) &&
                            !seasonRegex.containsMatchIn(href) &&
                            href != pageUrl
                        if (!isEpisodeLink) continue
                        seen.add(href)
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
                val dlSelector = cfg?.downloadAnchorSelector?.ifBlank { null } ?: "a[href*='dl.plutomovies.com']"
                val dlLink = doc.selectFirst(dlSelector)?.let {
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

            com.anonrode.downloader.util.DebugLog.resolve("pluto loadEpisodes: found ${episodes.size} episodes")
            return ShowDetails(show = show.copy(title = title, posterUrl = poster), episodes = episodes)
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes exception: ${e.javaClass.simpleName}: ${e.message}")
            return ShowDetails(show = show)
        }
    }

    /** Cloudflare/JS-challenge pages answer HTTP 200 with no real content. */
    private fun looksLikeSecurityChallenge(html: String): Boolean {
        val low = html.lowercase()
        return low.contains("just a moment") || low.contains("cf-challenge") ||
            low.contains("challenge-platform") || (low.contains("cloudflare") && low.contains("verify"))
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
