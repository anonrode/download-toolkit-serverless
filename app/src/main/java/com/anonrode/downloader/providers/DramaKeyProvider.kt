package com.anonrode.downloader.providers

import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.util.PostContentSanitizer
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.resolvers.ResolverRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

                // Check title relevance to prevent ingesting static catalog on sites like dramakey.cc
                val queryTokens = clean.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                val titleLower = rawTitle.lowercase()
                val isRelevant = (queryTokens.isNotEmpty() && queryTokens.all { it in titleLower }) ||
                    titleLower.contains(clean.lowercase())
                if (!isRelevant) continue

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

            if (results.isEmpty()) {
                val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val base = mainUrl.trimEnd('/')
                val prefixes = listOf("chinese", "korean", "thai", "japanese", "philippines", "drama", "")
                val candidateUrls = prefixes.map { prefix ->
                    if (prefix.isEmpty()) "$base/$slug/" else "$base/$prefix/$slug/"
                }

                val alive = coroutineScope {
                    candidateUrls.map { candUrl ->
                        async(Dispatchers.IO) {
                            if (HttpClient.probe(candUrl, referer = "$mainUrl/", timeoutMs = 3_000L, tag = "search")) candUrl else null
                        }
                    }.awaitAll().filterNotNull().toSet()
                }

                for (candUrl in candidateUrls.filter { it in alive }) {
                    val directHtml = HttpClient.getText(candUrl, tag = "search") ?: continue
                    val pDoc = Jsoup.parse(directHtml, candUrl)
                    val rawTitle = pDoc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: continue
                    val title = rawTitle.removePrefix("DOWNLOAD").trim().substringBefore(" |").trim()
                    val poster = pDoc.selectFirst(".entry-content img, .post-thumbnail img, meta[property='og:image']")?.let {
                        if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
                    } ?: ""

                    results.add(
                        ShowCard(
                            title = title.ifBlank { rawTitle },
                            url = candUrl,
                            posterUrl = poster,
                            site = name,
                            category = "Asian Drama"
                        )
                    )
                    break
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
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

            // Host allowlist shared by the accordion and flat sweeps (the 2026
            // dramakey rebuild moved the site's per-episode buttons to
            // lightdl.cc — live-verified 2026-10-15).
            fun isEpisodeTarget(h: String): Boolean =
                h.contains("lightdl.cc") ||
                    h.contains("downloadwella.com") || h.contains("wetafiles.com") ||
                    h.contains("loadedfiles.") || h.contains("dood.") ||
                    h.contains("mega.") || h.contains("/download/") || h.contains("?download") ||
                    h.contains("vikingfile") || h.contains("kissorgrab") ||
                    h.contains("wildshare") || h.contains("waffi") ||
                    com.anonrode.downloader.pipeline.StrictLinkClassifier.isKnownLocker(h)

            // ── Season accordion (playbook-mirror). The rebuilt site renders
            // one .season-accordion per season whose .season-header carries
            // data-season (live /korean/a-teen: Season 1 = 24 episodes +
            // Season 2 = 20, newest first) and textless SVG .download-btn
            // anchors inside .episode-item blocks that carry an
            // "Episode N" header. The flat sweep below sees the WHOLE body
            // at once: per-section counting + the season*100 key (the
            // drawer's chip contract, identical to the OTA sectionGrouping)
            // replaces a flat 1..N numbering that mixed all seasons.
            val accordions = doc.select(".season-accordion")
            if (accordions.isNotEmpty()) {
                val grouped = mutableListOf<EpisodeItem>()
                val groupedSeen = mutableSetOf<String>()
                for (acc in accordions) {
                    val headerText = acc.selectFirst(".season-header")?.text().orEmpty()
                    val season = acc.selectFirst(".season-header")?.attr("data-season")?.toIntOrNull()
                        ?: Regex("""\d+""").find(acc.attr("key"))?.value?.toIntOrNull()
                            ?.takeIf { it in 1..99 }
                        ?: Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
                            .find(headerText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: 1
                    var pos = 0
                    val rows = acc.select(".episode-item").ifEmpty { acc.select("a[href]") }
                    // Episode rows carry the season-relative number in their
                    // header; scanning .episode-item keeps each header paired
                    // with its own button even when a season block renumbers.
                    for (row in rows) {
                        val anchor = if (row.tagName() == "a") row else row.selectFirst("a[href]") ?: continue
                        val href = anchor.attr("abs:href").ifBlank {
                            HttpClient.safeResolveUri(showUrl, anchor.attr("href"))
                        }
                        if (href.isBlank() || href in groupedSeen || !isEpisodeTarget(href)) continue
                        groupedSeen.add(href)
                        val headerNum = row.selectFirst(".episode-number")?.text()?.trim().orEmpty()
                        val parsedNum = Regex("""(?i)(?:Episode|Ep|E)\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
                            .find(headerNum)?.groupValues?.get(1)?.toIntOrNull()
                        pos++
                        val posNum = parsedNum ?: pos
                        val seasonNum = season.coerceIn(1, 99)
                        val quality = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE)
                            .find(href)?.groupValues?.getOrNull(1)?.let { " [$it]" } ?: ""
                        grouped.add(
                            EpisodeItem(
                                title = "S%d E%02d".format(seasonNum, posNum) + quality,
                                url = href,
                                episodeNum = seasonNum * 100 + posNum,
                                site = name
                            )
                        )
                    }
                }
                if (grouped.isNotEmpty()) {
                    val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
                    return ShowDetails(
                        show = card, synopsis = synopsis,
                        episodes = grouped.sortedWith(compareBy({ it.episodeNum }, { it.title }))
                    )
                }
                // Accordions present but no usable links -> fall through to the
                // flat sweep rather than returning an empty drawer.
            }

            // Per-episode download links: lockers only (lightdl is the current
            // host, downloadwella others may appear later). Anchor text is a
            // generic "Download Episode" for every row, so the real episode
            // identity comes from the filename in the URL (The.Road...S01E01...mkv).
            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val container = doc.selectFirst(".entry-content, .post-content, article") ?: doc.body() ?: doc
            PostContentSanitizer.clean(container)
            val links = container.select("a[href]").filter { a ->
                val h = a.attr("abs:href").ifBlank { a.attr("href") }
                !PostContentSanitizer.isSameSitePostPermalink(h, "dramakey.com") &&
                    !PostContentSanitizer.isSiblingPostAnchorText(a.text()) &&
                    isEpisodeTarget(h)
            }

            // Season pin for season-scoped posts whose lists renumber from 1
            // (canon: NaijaVault/NaijaPrey). Season 1 keeps plain numbering.
            val slugSeason = Regex("""season-(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE).find(showUrl)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(title)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

            val episodeRe = Regex("""(?i)S(\d+)E(\d+)""")
            var count = 1
            for (a in links) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                if (href.isBlank() || href in seen || href.contains("/category/") || href.contains("/tag/")) continue
                if (PostContentSanitizer.isSameSitePostPermalink(href, "dramakey.com") || PostContentSanitizer.isSiblingPostAnchorText(a.text())) continue
                seen.add(href)

                val filename = href.substringAfterLast('/').substringBefore('?').substringBefore('#')
                val quality = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE)
                    .find(filename)?.groupValues?.getOrNull(1)?.let { " [$it]" }
                    ?: Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE)
                        .find(a.text())?.groupValues?.getOrNull(1)?.let { " [$it]" }
                val epMatch = episodeRe.find(filename)
                val label = epMatch?.let { m ->
                    "S${m.groupValues[1]} E${m.groupValues[2]}"
                } ?: DownloadLinkLabels.serverOrPart(a.text(), filename)
                // Season-pinned positional rows renumber from 1 inside their
                // season, so the drawer key (and label) must carry the slug's
                // season — unchanged for season-1/no-slug posts.
                val positionalNum = if (slugSeason != null && slugSeason >= 2 && count in 1..99)
                    slugSeason * 100 + count else count
                val positionalLabel = if (slugSeason != null && slugSeason >= 2 && count in 1..99)
                    "S$slugSeason E%02d".format(count) else "Episode $count"

                val parsedNum = epMatch?.let { m ->
                    val s = m.groupValues[1].toIntOrNull() ?: 1
                    val e = m.groupValues[2].toIntOrNull() ?: 1
                    s * 100 + e
                } ?: Regex("""(?i)\b(?:Episode|Ep|E)[- ]*(\d{1,4})\b""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()

                val labelFinal = label?.let { it + (quality ?: "") } ?: positionalLabel
                val num = parsedNum?.let { pn ->
                    if (pn !in 100..9999 && slugSeason != null && slugSeason >= 2 && pn in 1..99) slugSeason * 100 + pn else pn
                } ?: positionalNum
                episodes.add(
                    EpisodeItem(
                        title = labelFinal,
                        url = href,
                        episodeNum = num,
                        site = name
                    )
                )
                count++
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedWith(compareBy({ it.episodeNum }, { it.title })))
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        // OTA resolve recipe first: a signed playbook's "resolve"+"terminal"
        // stages can crack this site's download flow without an app update.
        // The compiled registry stays the untouched fallback.
        val direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: ResolverRegistry.resolve(episodeUrl, quality) ?: ""
        val isHls = direct.contains(".m3u8", ignoreCase = true) || direct.contains("manifest", ignoreCase = true)
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
