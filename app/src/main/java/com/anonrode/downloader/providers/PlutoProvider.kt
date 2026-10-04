package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.URI
import java.net.URLEncoder

object PlutoProvider : SiteProvider {
    override val name: String = "pluto"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) return results
        }
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
        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
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
            val html = HttpClient.getText(showUrl, referer = "$mainUrl/")
            if (html.isNullOrBlank()) {
                com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes: fetch returned null for $showUrl")
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
                // Live structure (verified 2026-08-27): every page — season hub
                // or single episode — gets its OWN numeric /series/<id>/ URL,
                // so siblings can never be matched by id. Search links even
                // carry site-truncated slugs (.../sofia-the-first-royal-magic-s0,
                // ~30-char cap), so slug matching must be prefix-tolerant.
                // Episode slugs come in both -s01-e01 and -s01e01 shapes.
                val showSlug = showUrl.substringAfterLast('/').substringBefore('?')
                val stem = slugStem(showSlug)
                val seasonLinkRegex = Regex("""-season-\d+""", RegexOption.IGNORE_CASE)

                val seenUrls = mutableSetOf<String>()
                val seenEpKeys = mutableSetOf<String>()
                fun addEpisode(href: String, epTitle: String, epNum: Int, key: String) {
                    if (href.isBlank() || href in seenUrls || key in seenEpKeys) return
                    seenUrls.add(href)
                    seenEpKeys.add(key)
                    episodes.add(
                        EpisodeItem(
                            title = epTitle.ifBlank { "Episode $epNum" },
                            url = href,
                            episodeNum = epNum,
                            site = name
                        )
                    )
                }

                // 1. When the page itself is an episode (search lands on
                //    episode pages directly), list it. The dl anchor's
                //    filename carries the full slug, revealing the true
                //    number even when the page URL's slug is truncated.
                val dlSelector = cfg?.downloadAnchorSelector?.ifBlank { null } ?: "a[href*='dl.plutomovies.com']"
                val dlHref = doc.selectFirst(dlSelector)?.let {
                    it.attr("abs:href").ifBlank { it.attr("href") }
                }?.substringBefore('#') ?: ""
                val selfKey = parseEpisodeKey(dlHref) ?: parseEpisodeKey(showUrl)
                val isEpisodePage = dlHref.isNotBlank() || EP_SLUG_REGEX.containsMatchIn(showUrl)
                if (isEpisodePage) {
                    val selfTitle = title
                        .replace(Regex("""(?i)\s*download\s*(mp4|mkv|hd)?\s*$"""), "")
                        .trim()
                    val epNum = selfKey?.let { it.first * 100 + it.second } ?: 1
                    val epTitle = if (selfKey != null) {
                        "S%02dE%02d".format(selfKey.first, selfKey.second)
                    } else {
                        selfTitle
                    }
                    addEpisode(showUrl, epTitle, epNum, selfKey?.let { "s${it.first}e${it.second}" } ?: showUrl)
                }

                // 2. Previous/next navigation on episode pages. Those links
                //    carry truncated slugs under unique ids, so number them
                //    arithmetically from the current episode instead.
                if (isEpisodePage && selfKey != null) {
                    for (a in doc.select(".previous_and_next_post a[href]")) {
                        val href = a.attr("abs:href")
                            .ifBlank { HttpClient.safeResolveUri(showUrl, a.attr("href")) }
                            .substringBefore('#')
                        if (href.isBlank() || href == showUrl || !href.contains("/series/")) continue
                        val cls = a.className().lowercase()
                        val delta = when {
                            "left" in cls || "prev" in cls -> -1
                            "right" in cls || "next" in cls -> 1
                            else -> 0
                        }
                        if (delta == 0) continue
                        val num = selfKey.second + delta
                        if (num < 1) continue
                        val epCode = selfKey.first * 100 + num
                        val label = a.text().trim()
                            .replace(Regex("""^(previous|next)\s+episode\b\s*""", RegexOption.IGNORE_CASE), "")
                            .trim()
                        val epTitle = if (label.isBlank() || Regex("""^(episode\s*\d+|\d+)$""", RegexOption.IGNORE_CASE).matches(label)) {
                            "S%02dE%02d".format(selfKey.first, num)
                        } else {
                            "S%02dE%02d - $label".format(selfKey.first, num)
                        }
                        addEpisode(href, epTitle, epCode, "s${selfKey.first}e$num")
                    }
                }

                // 3. Inspect main panel for season hubs or direct episode items.
                // The main post content is inside .file-info-panel, isolating it
                // from the sidebar "Top 10 Monthly Trending" items.
                val mainPanel = doc.selectFirst(".file-info-panel") ?: doc
                val rawItems = mainPanel.select(".kontent .flist-item")
                val detectedSeasonHubs = mutableListOf<Pair<Int, String>>()
                for (it in rawItems) {
                    val a = it.selectFirst(".title a") ?: it.selectFirst("a[href]") ?: continue
                    val href = a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, a.attr("href")) }.substringBefore('#')
                    if (href.isBlank() || href == showUrl) continue
                    val titleAttr = a.attr("title")
                    val text = a.text().trim()
                    val combined = "$titleAttr $text $href"
                    val epKey = parseEpisodeKey(combined)
                    val sNum = parseSeasonNumber(combined)
                    if (epKey == null && sNum != null) {
                        detectedSeasonHubs.add(sNum to href)
                    }
                }

                if (detectedSeasonHubs.isNotEmpty()) {
                    val distinctHubs = mutableListOf<Pair<Int, String>>()
                    val seenHubUrls = mutableSetOf<String>()
                    for ((sNum, hUrl) in detectedSeasonHubs) {
                        if (seenHubUrls.add(hUrl)) {
                            distinctHubs.add(sNum to hUrl)
                        }
                    }
                    coroutineScope {
                        distinctHubs.map { (sNum, sUrl) ->
                            async(Dispatchers.IO) {
                                val sHtml = try {
                                    HttpClient.getText(sUrl, referer = "$mainUrl/")
                                } catch (_: Exception) { null }
                                if (!sHtml.isNullOrBlank()) {
                                    val sDoc = Jsoup.parse(sHtml, sUrl)
                                    val sPanel = sDoc.selectFirst(".file-info-panel") ?: sDoc
                                    val pagPages = mutableListOf(sUrl)
                                    for (pa in sPanel.select(".pagination-list a[href], a[href*='/page/']")) {
                                        val pHref = pa.attr("abs:href").ifBlank { HttpClient.safeResolveUri(sUrl, pa.attr("href")) }.substringBefore('#')
                                        if (pHref.isNotBlank() && pHref !in pagPages && pHref.startsWith(sUrl.substringBefore('?'))) {
                                            pagPages.add(pHref)
                                        }
                                    }
                                    val extraPagDocs = if (pagPages.size > 1) {
                                        pagPages.filter { it != sUrl }.map { pUrl ->
                                            async(Dispatchers.IO) {
                                                val pHtml = try { HttpClient.getText(pUrl, referer = "$mainUrl/") } catch (_: Exception) { null }
                                                if (!pHtml.isNullOrBlank()) Jsoup.parse(pHtml, pUrl) else null
                                            }
                                        }.awaitAll().filterNotNull()
                                    } else emptyList()

                                    val allSeasonDocs = listOf(sDoc) + extraPagDocs
                                    for (docItem in allSeasonDocs) {
                                        val panelItem = docItem.selectFirst(".file-info-panel") ?: docItem
                                        val base = docItem.baseUri().ifBlank { sUrl }
                                        for (it in panelItem.select(".kontent .flist-item")) {
                                            val a = it.selectFirst(".title a") ?: it.selectFirst("a[href]") ?: continue
                                            val href = a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(base, a.attr("href")) }.substringBefore('#')
                                            if (href.isBlank()) continue
                                            val titleAttr = a.attr("title")
                                            val text = a.text().trim()
                                            val combined = "$titleAttr $text $href"
                                            val epKey = parseEpisodeKey(combined) ?: parseEpisodeKey(href)
                                            val epNum = epKey?.second ?: EP_TEXT_REGEX.find(combined)?.groupValues?.get(1)?.toIntOrNull()
                                            val epSeason = epKey?.first ?: sNum
                                            if (epNum != null) {
                                                val epCode = epSeason * 100 + epNum
                                                val epTitle = "S%02dE%02d".format(epSeason, epNum)
                                                synchronized(episodes) {
                                                    addEpisode(href, epTitle, epCode, "s${epSeason}e${epNum}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    val currentSeasonNum = parseSeasonNumber(title) ?: parseSeasonNumber(showSlug) ?: 1
                    val pagPages = mutableListOf(showUrl)
                    for (pa in mainPanel.select(".pagination-list a[href], a[href*='/page/']")) {
                        val pHref = pa.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, pa.attr("href")) }.substringBefore('#')
                        if (pHref.isNotBlank() && pHref !in pagPages && pHref.startsWith(showUrl.substringBefore('?'))) {
                            pagPages.add(pHref)
                        }
                    }
                    val extraDocs = if (pagPages.size > 1) {
                        coroutineScope {
                            pagPages.filter { it != showUrl }.map { pUrl ->
                                async(Dispatchers.IO) {
                                    val pHtml = try { HttpClient.getText(pUrl, referer = "$mainUrl/") } catch (_: Exception) { null }
                                    if (!pHtml.isNullOrBlank()) Jsoup.parse(pHtml, pUrl) else null
                                }
                            }.awaitAll().filterNotNull()
                        }
                    } else emptyList()

                    val allCurrentDocs = listOf(doc) + extraDocs
                    for (pageDoc in allCurrentDocs) {
                        val pPanel = pageDoc.selectFirst(".file-info-panel") ?: pageDoc
                        val base = pageDoc.baseUri().ifBlank { showUrl }
                        for (it in pPanel.select(".kontent .flist-item")) {
                            val a = it.selectFirst(".title a") ?: it.selectFirst("a[href]") ?: continue
                            val href = a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(base, a.attr("href")) }.substringBefore('#')
                            if (href.isBlank()) continue
                            val titleAttr = a.attr("title")
                            val text = a.text().trim()
                            val combined = "$titleAttr $text $href"
                            val epKey = parseEpisodeKey(combined) ?: parseEpisodeKey(href)
                            val epNum = epKey?.second ?: EP_TEXT_REGEX.find(combined)?.groupValues?.get(1)?.toIntOrNull()
                            val epSeason = epKey?.first ?: currentSeasonNum
                            if (epNum != null) {
                                val epCode = epSeason * 100 + epNum
                                val epTitle = "S%02dE%02d".format(epSeason, epNum)
                                addEpisode(href, epTitle, epCode, "s${epSeason}e${epNum}")
                            }
                        }
                    }
                }

                // 4. Fallback legacy scan if episodes are still empty
                if (episodes.isEmpty()) {
                    for (a in doc.select("a[href]")) {
                        val href = a.attr("abs:href").ifBlank {
                            HttpClient.safeResolveUri(showUrl, a.attr("href"))
                        }.substringBefore('#')
                        if (href.isBlank() || href == showUrl || seasonLinkRegex.containsMatchIn(href)) continue
                        val key = parseEpisodeKey(href)
                        if (key == null && !href.contains("/episodes/")) continue
                        if (!sameSlugStem(stem, slugStem(href.substringAfterLast('/').substringBefore('?')))) continue
                        val label = a.text().trim()
                            .replace(Regex("""^(previous|next)\s+episode\b\s*""", RegexOption.IGNORE_CASE), "")
                            .trim()
                        val epCode = if (key != null) key.first * 100 + key.second else (episodes.size + 1)
                        val epTitle = if (key != null) {
                            "S%02dE%02d".format(key.first, key.second)
                        } else {
                            label.ifBlank { "Episode $epCode" }
                        }
                        addEpisode(href, epTitle, epCode, key?.let { "s${it.first}e${it.second}" } ?: href)
                    }
                }

                episodes.sortBy { it.episodeNum }
            } else {
                // Movie download links from the detail page. The old
                // selectFirst kept ONLY THE FIRST anchor: a film published
                // on two servers or as a 2-part file silently lost the
                // rest. Now one item per distinct href, Server/Part-labelled
                // when marked, single one keeps the plain "Full Movie" title.
                val dlSelector = cfg?.downloadAnchorSelector?.ifBlank { null } ?: "a[href*='dl.plutomovies.com']"
                val dlHrefs = doc.select(dlSelector).mapNotNull { a ->
                    a.attr("abs:href").ifBlank { a.attr("href") }.takeIf { it.isNotBlank() }
                }.distinct()
                when (dlHrefs.size) {
                    0 -> episodes.add(
                        EpisodeItem(title = "Full Movie", url = showUrl, episodeNum = 1, site = name)
                    )
                    1 -> episodes.add(
                        EpisodeItem(title = "Full Movie", url = dlHrefs.first(), episodeNum = 1, site = name)
                    )
                    else -> dlHrefs.forEachIndexed { i, h ->
                        val label = DownloadLinkLabels.serverOrPart(h.substringAfterLast('/'))
                            ?: "Full Movie (link ${i + 1})"
                        episodes.add(EpisodeItem(title = label, url = h, episodeNum = i + 1, site = name))
                    }
                }
            }

            com.anonrode.downloader.util.DebugLog.resolve("pluto loadEpisodes: found ${episodes.size} episodes")
            return ShowDetails(show = show.copy(title = title, posterUrl = poster), episodes = episodes)
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes exception: ${e.javaClass.simpleName}: ${e.message}")
            return ShowDetails(show = show)
        }
    }

    internal val SEASON_TEXT_REGEX = Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    internal val EP_CODE_REGEX = Regex("""\bs(\d{1,2})\s*[-_]?\s*e(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)
    internal val EP_TEXT_REGEX = Regex("""\b(?:episode|ep)\.?\s*(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)

    /** Episode slug pattern: both -s01-e01 (dash) and -s01e01 (compact) occur live.
     *  Episode digits go to 3 (long runners: -e105); the `(?!\d)` guards stop a
     *  3-digit episode from being read as 2 (E105 -> E10 silently dropped E105
     *  into addEpisode's key-dedupe). internal: pinned by PlutoProviderTest. */
    internal val EP_SLUG_REGEX = Regex("""-s\d{1,2}[-_]?e\d{1,3}(?!\d)""", RegexOption.IGNORE_CASE)

    /** (season, episode) parsed from a slug, label, or URL, tolerant of multiple shapes. */
    internal fun parseEpisodeKey(str: String): Pair<Int, Int>? {
        val mSlug = Regex("""(?:^|[-_])s(\d{1,2})(?!\d)[-_]?e(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE).find(str)
        if (mSlug != null) {
            val s = mSlug.groupValues[1].toIntOrNull() ?: return null
            val e = mSlug.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        val mCode = EP_CODE_REGEX.find(str)
        if (mCode != null) {
            val s = mCode.groupValues[1].toIntOrNull() ?: return null
            val e = mCode.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        val sMatch = SEASON_TEXT_REGEX.find(str)
        val eMatch = EP_TEXT_REGEX.find(str)
        if (sMatch != null && eMatch != null) {
            val s = sMatch.groupValues[1].toIntOrNull() ?: return null
            val e = eMatch.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        return null
    }

    internal fun parseSeasonNumber(str: String): Int? {
        val m = SEASON_TEXT_REGEX.find(str)
            ?: Regex("""-season-(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE).find(str)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Comparable show-stem of a slug: season/episode suffixes stripped, plus
     * the dangling fragments Pluto's site-wide ~30-char slug truncation
     * leaves behind ("-s0", "-se", "-s"). Applied to both sides of a
     * comparison so truncation cancels out.
     */
    internal fun slugStem(slug: String): String = slug
        .replace(Regex("""-season-\d+.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""-s\d{1,2}[-_]?e\d{1,2}.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""-s\d{0,2}[-_]?e?\d{0,2}$""", RegexOption.IGNORE_CASE), "")
        .lowercase()
        .trim('-', '_')

    /**
     * Prefix-tolerant stem comparison. Because of the slug truncation one
     * stem is often a prefix of the other ("…royal-magic-s0" ⊂ "…royal-magic"),
     * and mid-word cuts rule out a dash-boundary requirement. Stems too
     * short to judge trust the page they came from. Recall over precision:
     * a rare franchise over-match merely lists an extra episode, while a
     * false rejection reproduces the "no episodes" bug.
     */
    internal fun sameSlugStem(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4) return true
        if (a == b) return true
        val long = if (a.length >= b.length) a else b
        val short = if (a.length >= b.length) b else a
        return long.startsWith(short)
    }

    /** Cloudflare/JS-challenge pages answer HTTP 200 with no real content.
     *  Moved to pipeline.LinkResolver.isSecurityChallenge (2026-09-14) so the
     *  search-verify oracle treats a challenge as NEVER proof of absence. */
    private fun looksLikeSecurityChallenge(html: String): Boolean =
        com.anonrode.downloader.pipeline.LinkResolver.isSecurityChallenge(html)

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: ResolverRegistry.resolve(episodeUrl, quality)
        val target = if (!direct.isNullOrBlank()) direct else {
            if (com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(episodeUrl)) episodeUrl else ""
        }
        val isHls = target.contains(".m3u8", ignoreCase = true) || target.contains("manifest", ignoreCase = true)
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "video.mp4" },
            headers = mapOf("Referer" to "$mainUrl/"),
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
