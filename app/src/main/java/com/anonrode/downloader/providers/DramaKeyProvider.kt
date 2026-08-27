package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import java.net.URLEncoder

object DramaKeyProvider : SiteProvider {
    override val name: String = "dramakey"
    // Domain is OTA-tunable via the playbook's domains entry (bundled default
    // is the same dramakey.com), so a host move is a rules edit, not a release.
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    // Live-verified 2026-08-24: dramakey.com is a live WordPress site — search
    // (?s=) returns real drama cards and episode pages carry per-episode locker
    // links (downloadwella). An earlier 2026-08-21 verification declared the
    // domain dead (soft-404 on every slug) and disabled search; that finding is
    // stale/wrong — the ?s= endpoint works, so search is back on.
    override val searchEnabled: Boolean get() = true

    override suspend fun search(query: String): List<ShowCard> {
        // OTA pipeline first; non-empty wins, else compiled path below.
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) return results
        }

        val results = mutableListOf<ShowCard>()
        try {
            val clean = query.trim()
            if (clean.isBlank()) return results
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "$mainUrl/?s=$encoded"
            val html = HttpClient.getText(url) ?: return results
            val doc = Jsoup.parse(html, url)

            for (article in doc.select("article.entry")) {
                val titleA = article.selectFirst(".search-entry-title a") ?: continue
                val rawTitle = titleA.text().trim()
                if (rawTitle.isBlank()) continue
                val href = titleA.attr("abs:href")
                if (href.isBlank()) continue

                val category = article.classNames()
                    .firstOrNull { it.startsWith("category-") }
                    ?.removePrefix("category-")?.replace('-', ' ')
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Asian Drama"

                val poster = article.selectFirst(".thumbnail img, .search-entry-thumbnail img")?.attr("abs:src")
                    ?: ""

                results.add(
                    ShowCard(
                        title = rawTitle,
                        url = href,
                        posterUrl = poster,
                        site = name,
                        category = category
                    )
                )
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Asian Drama", url = showUrl, site = name)

        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, showUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.metaTitle ?: "Asian Drama",
                    url = showUrl,
                    posterUrl = res.metaPoster ?: "",
                    site = name
                )
                return ShowDetails(show = card, synopsis = res.metaSynopsis ?: "", episodes = res.episodes)
            }
        }

        try {
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html, showUrl)

            // Strip the boilerplate "DOWNLOAD " prefix and trailing
            // "| Chinese Drama" suffix the site puts on every title.
            val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Asian Drama"
            val title = rawTitle.removePrefix("DOWNLOAD").trim().substringBefore(" |").trim()
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            // Per-episode download links: lockers only (downloadwella is the
            // site's host, others may appear later). Anchor text is a generic
            // "Download Episode" for every row, so the real episode identity
            // comes from the filename in the URL (The.Road...S01E01...mkv).
            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val episodeRe = Regex("""(?i)S(\d+)E(\d+)""")
            val links = doc.select("a[href]").filter { a ->
                val h = a.attr("abs:href").ifBlank { a.attr("href") }
                h.contains("downloadwella.com") || h.contains("wetafiles.com") ||
                    h.contains("loadedfiles.") || h.contains("dood.") ||
                    h.contains("mega.") || h.contains("/download/") || h.contains("?download")
            }

            var count = 1
            for (a in links) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                if (href.isBlank() || href in seen || href.contains("/category/") || href.contains("/tag/")) continue
                seen.add(href)

                val filename = href.substringAfterLast('/').substringBefore('?').substringBefore('#')
                val label = episodeRe.find(filename)?.let { m ->
                    "S${m.groupValues[1]} E${m.groupValues[2]}"
                } ?: "Episode $count"

                episodes.add(
                    EpisodeItem(
                        title = label,
                        url = href,
                        episodeNum = count++,
                        site = name
                    )
                )
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
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mp4" },
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
