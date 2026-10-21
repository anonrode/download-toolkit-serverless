package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.util.PostContentSanitizer
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
        if (!SiteGate.isEnabled(name)) return emptyList()
        com.anonrode.downloader.data.rules.DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) {
                return PosterBackfill.backfill(PosterBackfill.sanitize(results)) { missing ->
                    if (missing.isEmpty()) emptyMap()
                    else searchCompiled(query).mapNotNull { card ->
                        if (card.posterUrl.isBlank()) null
                        else PosterBackfill.normalizeKey(card.url) to card.posterUrl
                    }.toMap()
                }
            }
        }
        return searchCompiled(query)
    }

    /** Cold-start budget for the search API (share-43 §RC-2): the endpoint is
     *  0.2-0.5s warm, but twice on 2026-10-20 the day's FIRST call wedged its
     *  HTTP/2 preface to the full 30s read timeout, holding the search
     *  thread (00:08:09 and 00:42:12). Bound each attempt to 10s and retry
     *  once — getTextWithTimeout derives a fresh client per call, so the
     *  retry lands on a new connection instead of the wedged pooled edge. */
    private const val SEARCH_TIMEOUT_MS = 10_000L

    private suspend fun searchCompiled(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api?a=search&keyword=$encoded"
            val jsonStr = HttpClient.getTextWithTimeout(url, SEARCH_TIMEOUT_MS, referer = "$mainUrl/", tag = "search")
                ?: HttpClient.getTextWithTimeout(url, SEARCH_TIMEOUT_MS, referer = "$mainUrl/", tag = "search")
                ?: return emptyList()

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
                            category = "Asian Drama",
                            tags = listOf("Asian Drama"),
                            genres = listOf("Asian Drama")
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return PosterBackfill.sanitize(results)
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        if (!SiteGate.isEnabled(name)) {
            return ShowDetails(show = ShowCard(title = "Asian Drama", url = showUrl, site = name))
        }
        val show = ShowCard(title = "Asian Drama", url = showUrl, site = name)
        com.anonrode.downloader.data.rules.DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, showUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.title.ifBlank { show.title },
                    url = showUrl,
                    posterUrl = if (PosterBackfill.isUsablePoster(res.posterUrl)) res.posterUrl else "",
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
            // Season pin from the post identity (canon: NaijaVault/NaijaPrey
            // season-post handling). asianc publishes ONE POST PER SEASON
            // ("/drama-detail/taxi-driver-season-2", live-verified 2026-10-15:
            // 16 anchors "Episode 1..16") and each list renumbers from 1 —
            // without the pin every season of a series landed in the drawer's
            // single "Season 1" chip with colliding keys across sibling posts.
            // Season 1 and seasonless posts keep the historical 1..N numbering.
            val slugSeason = Regex("""season-(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE).find(showUrl)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(title)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            // Container selectors FIRST; the page-wide `-episode-` tail is a
            // sidebar magnet (related-episode widgets share the slug shape),
            // so it only rescues an otherwise-empty drawer.
            PostContentSanitizer.clean(doc)
            var epLinks = doc.select("ul.list-episode-item-2 li a, .all-episodes li a, .list-episode a, .list-episode-item a")
            if (epLinks.isEmpty()) epLinks = doc.select("a[href*='-episode-']")

            // (episode, extraFraction) pairs pending their own drawer key: the
            // final pass hands "Episode 16.5" rows a key no integer episode
            // uses, without disturbing the historical 1..N numbering.
            data class Draft(val item: EpisodeItem, val fractional: Boolean)
            val drafts = mutableListOf<Draft>()

            for (link in epLinks) {
                val rawHref = link.attr("href")
                val href = link.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                if (href.isBlank() || href in seen) continue

                val epRaw = link.selectFirst(".title, h3")?.text()?.trim() ?: link.text().trim()
                if (PostContentSanitizer.isSiblingPostAnchorText(epRaw)) continue
                seen.add(href)
                val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epRaw)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)
                // Fractional specials ("/vincenzo-2021-episode-16-5.html",
                // "Episode 16.5" — live-verified 2026-10-15): the text/href
                // above still parse 16, and without the fraction the special
                // collapses into episode 16 (same label -> it became a
                // "mirror" of a DIFFERENT episode).
                val epFrac = Regex("""episode-\d+-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                // Mirror-server / part rows must not be relabeled "Episode N"
                // (2026-09-13 drawer bug class, shared with Rocks).
                val partMatch = Regex("""\b(?:Part|Pt)\.?\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(epRaw)
                    ?: Regex("""\b(?:Part|Pt)\.?\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(href)
                val partNum = partMatch?.groupValues?.get(1)?.toIntOrNull()

                val mirrorLabel = DownloadLinkLabels.serverOrPart(epRaw, href)
                val cleanTitle = when {
                    mirrorLabel != null -> mirrorLabel
                    partNum != null -> "Episode $epNum Part $partNum" + if (epRaw.contains("RAW", ignoreCase = true)) " (RAW)" else ""
                    slugSeason != null && slugSeason >= 2 ->
                        "S%02dE%02d".format(slugSeason, epNum) +
                            (if (epFrac != null) ".$epFrac" else "") +
                            if (epRaw.contains("RAW", ignoreCase = true)) " (RAW)" else ""
                    epRaw.contains("Episode", ignoreCase = true) ->
                        "Episode $epNum" +
                            (if (epFrac != null) ".$epFrac" else "") +
                            if (epRaw.contains("RAW", ignoreCase = true)) " (RAW)" else ""
                    else -> "Episode $epNum" + if (epFrac != null) ".$epFrac" else ""
                }

                val existing = episodes.firstOrNull { it.episodeNum == epNum && it.title == cleanTitle }
                if (existing != null) {
                    if (href != existing.url && href !in existing.mirrorUrls) {
                        val idx = episodes.indexOf(existing)
                        episodes[idx] = existing.copy(mirrorUrls = existing.mirrorUrls + href)
                    }
                } else {
                    val rowSeason = (slugSeason ?: 1).coerceAtLeast(1)
                    drafts.add(
                        Draft(
                            EpisodeItem(
                                title = cleanTitle,
                                url = href,
                                episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(rowSeason, epNum),
                                site = name,
                                season = rowSeason,
                                episode = epNum
                            ),
                            fractional = epFrac != null
                        )
                    )
                    episodes.add(drafts.last().item)
                }
            }
            // Fractional rows keep their faithful label but must not share the
            // integer episode's re-resolve key (the engine re-resolves via
            // firstOrNull { episodeNum == task.episodeNum }, so "Episode 16.5"
            // colliding with "Episode 16" would download the wrong file):
            // stamps the collision above the list maximum.
            var overflowKey = (episodes.maxOfOrNull { it.episodeNum } ?: 0) + 1
            for (d in drafts.filter { it.fractional }) {
                if (episodes.count { it.episodeNum == d.item.episodeNum } > 1) {
                    val idx = episodes.indexOfFirst { it === d.item }
                    if (idx >= 0) episodes[idx] = d.item.copy(episodeNum = overflowKey++)
                }
            }

            // SINGLE-FILM shape used to yield an empty drawer (the site has
            // no episode list on movie pages): if a player is embedded, the
            // detail URL itself is the watch/download target.
            if (episodes.isEmpty()) {
                val player = doc.selectFirst("#video-embed iframe, .video-embed iframe, .watch-content iframe, iframe[src]")
                if (player != null) {
                    episodes.add(
                        EpisodeItem(
                            title = "Full Movie",
                            url = showUrl,
                            episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(1, 1),
                            site = name,
                            season = 1,
                            episode = 1
                        )
                    )
                }
            }
            if (episodes.isEmpty()) {
                // Live 2026-10-20: /drama-detail/a-certain-dark-item-2026
                // serves `<ul class="list-episode-item-2 all-episode"></ul>`
                // (zero <li>) and no player — a genuine site-side data gap,
                // not a selector miss. Real titles parse 12/20/25 episodes
                // with the same selectors. Say so instead of failing mutely.
                com.anonrode.downloader.util.DebugLog.resolve(
                    "asianc: detail page exposes no episode list and no player (site-side data gap): $showUrl"
                )
            }

            val subtitleTracks = SubtitleSurfaces.parsePageTracks(doc, showUrl)
            if (subtitleTracks.isNotEmpty()) SubtitleSurfaces.record(showUrl, subtitleTracks)

            val card = ShowCard(
                title = title,
                url = showUrl,
                posterUrl = if (PosterBackfill.isUsablePoster(poster)) poster else "",
                site = name
            )
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedWith(compareBy({ it.season.coerceAtLeast(1) }, { it.episode }, { it.title })))
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
                val candidates = mutableListOf<String>()
                doc.select("iframe[src]").forEach { iframe ->
                    val rawSrc = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                    val src = HttpClient.safeResolveUri(episodeUrl, rawSrc)
                    if (src.startsWith("http")) candidates.add(src)
                }
                doc.select("li[data-video], a[data-video]").forEach { el ->
                    val rawSrc = el.attr("data-video")
                    val src = HttpClient.safeResolveUri(episodeUrl, rawSrc)
                    if (src.startsWith("http") && src !in candidates) candidates.add(src)
                }
                if (candidates.isNotEmpty()) {
                    // Provider-issued fan-out: stale HostHealth backoff must not
                    // pre-skip this episode's own page servers (share-31).
                    direct = ResolverRegistry.resolveAny(candidates, quality, bypassHealth = true)
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
