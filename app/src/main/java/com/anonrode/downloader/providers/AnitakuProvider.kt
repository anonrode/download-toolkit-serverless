package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.json.JSONObject
import org.jsoup.Jsoup
import com.anonrode.downloader.util.PostContentSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.regex.Pattern

data class CleanedQuery(
    val coreTitle: String,
    val targetSeason: Int? = null,
    val targetPart: Int? = null
)

object SeasonNoiseStripper {
    fun clean(query: String): CleanedQuery {
        var core = query.trim()
        var season: Int? = null
        var part: Int? = null

        // 1. Extract season (e.g. s05, s5, season 5, 2nd season, 7th season)
        Regex("""(?i)\b(?:season|s)\s*(\d{1,2})\b""").find(core)?.let {
            season = it.groupValues[1].toIntOrNull()
        } ?: Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)\s+season\b""").find(core)?.let {
            season = it.groupValues[1].toIntOrNull()
        }

        // 2. Extract part/cour (e.g. part 2, cour 1)
        Regex("""(?i)\b(?:part|cour)\s*(\d{1,2})\b""").find(core)?.let {
            part = it.groupValues[1].toIntOrNull()
        }

        // 3. Strip season/part/series noise
        core = core.replace(Regex("""(?i)\b(?:season|s)\s*\d{1,2}\b"""), " ")
            .replace(Regex("""(?i)\b\d{1,2}(?:st|nd|rd|th)\s+season\b"""), " ")
            .replace(Regex("""(?i)\b(?:part|cour)\s*\d{1,2}\b"""), " ")
            .replace(Regex("""(?i)\bseries\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val finalCore = if (core.length >= 2) core else query.trim()
        return CleanedQuery(finalCore, season, part)
    }
}

object AnitakuProvider : SiteProvider {
    override val name: String = "anitaku"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    private fun formatDisplayTitle(title: String, coreQuery: String): String {
        val qTokens = RelevanceScorer.tokenize(coreQuery)
        val tTokens = RelevanceScorer.tokenize(title)
        if (qTokens.isNotEmpty() && qTokens.intersect(tTokens).isEmpty()) {
            val prettyCore = coreQuery.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            return "$title ($prettyCore)"
        }
        return title
    }

    private fun postProcessResults(items: List<ShowCard>, cleaned: CleanedQuery): List<ShowCard> {
        val deduplicated = items.distinctBy { it.url.trimEnd('/') }
        if (cleaned.targetSeason == null) return deduplicated

        val sNum = cleaned.targetSeason
        return deduplicated.sortedByDescending { card ->
            val tLower = card.title.lowercase()
            when {
                tLower.contains("season $sNum") || tLower.contains("season 0$sNum") -> 3
                tLower.contains("s$sNum") || tLower.contains("s0$sNum") -> 2
                tLower.contains("${sNum}st season") || tLower.contains("${sNum}nd season") ||
                    tLower.contains("${sNum}rd season") || tLower.contains("${sNum}th season") -> 3
                else -> 0
            }
        }
    }

    private fun fetchAjaxCards(
        ajaxUrl: String,
        referer: String,
        term: String,
        cleaned: CleanedQuery
    ): List<ShowCard> {
        return try {
            val form = mapOf("action" to "ts_ac_do_search", "ts_ac_query" to term)
            val body = HttpClient.postForm(
                url = ajaxUrl,
                form = form,
                referer = referer,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                tag = "search"
            ) ?: return emptyList()
            parseAjaxCards(body, cleaned)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Pure admin-ajax parse (captured-fixture JVM test). Shape:
     *  `{"anime":[{"all":[{post_link, post_title, post_image, post_sub}]}]}`. */
    internal fun parseAjaxCards(body: String, cleaned: CleanedQuery): List<ShowCard> {
        val batch = mutableListOf<ShowCard>()
        try {
            val json = JSONObject(body)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val group = json.optJSONArray(key) ?: continue
                for (i in 0 until group.length()) {
                    val block = group.optJSONObject(i) ?: continue
                    val all = block.optJSONArray("all") ?: continue
                    for (j in 0 until all.length()) {
                        val item = all.getJSONObject(j)
                        val link = item.optString("post_link")
                        val rawTitle = item.optString("post_title").trim()
                        val image = item.optString("post_image")
                        val sub = item.optString("post_sub")

                        if (link.isNotBlank() && rawTitle.isNotBlank()) {
                            val displayTitle = formatDisplayTitle(rawTitle, cleaned.coreTitle)
                            batch.add(
                                ShowCard(
                                    title = displayTitle,
                                    url = link,
                                    posterUrl = if (PosterBackfill.isUsablePoster(image)) image else "",
                                    site = name,
                                    category = "Anime ${if (sub.isNotBlank()) "($sub)" else ""}"
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return batch
    }

    override suspend fun search(query: String): List<ShowCard> {
        if (!SiteGate.isEnabled(name)) return emptyList()
        val cleaned = SeasonNoiseStripper.clean(query)
        val cacheKey = query.trim().lowercase()
        cachedSearch(cacheKey)?.let { return it }

        val results = searchLive(cleaned, query)
        rememberSearch(cacheKey, results)
        return results
    }

    private suspend fun searchLive(cleaned: CleanedQuery, rawQuery: String): List<ShowCard> {
        val expandedTerms = AnimeOracle.expandTerms(cleaned.coreTitle)
        val primaryTerm = if (cleaned.coreTitle.length >= 2) cleaned.coreTitle else rawQuery.trim()
        val searchTerms = (if (expandedTerms.isNotEmpty()) expandedTerms else listOf(primaryTerm))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        // OTA pipeline first (dual admin-ajax POST merge lives in the playbook).
        // ONE term only: the payload can answer multiple terms itself, and the
        // old 2-term loop doubled every POST during per-keystroke searches.
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val otaResults = mutableListOf<ShowCard>()
            val res = RulesPipeline.runSearch(name, pl, primaryTerm)
            if (res.isNotEmpty()) {
                otaResults.addAll(res.map { it.copy(title = formatDisplayTitle(it.title, cleaned.coreTitle)) })
            }
            if (otaResults.isNotEmpty()) {
                return backfillAnimePosters(primaryTerm, postProcessResults(otaResults, cleaned))
            }
        }

        // Compiled path: ONE term × ONE host at a time, sequentially. The old
        // (terms × both hosts) concurrent fan-out was 4 blocking POSTs on every
        // keystroke — live timing 2026-10-20: 0.8 s healthy, 2.1–2.7 s under
        // repeat load, which is exactly the admin-ajax timeout class in the
        // device logs. Fallbacks only run when the previous answer was empty.
        val hosts = searchHosts()
        var fallbackCards: List<ShowCard> = emptyList()
        for (term in searchTerms.take(2)) {
            for ((ajaxUrl, referer) in hosts) {
                val cards = withContext(Dispatchers.IO) { fetchAjaxCards(ajaxUrl, referer, term, cleaned) }
                if (cards.isNotEmpty()) {
                    return backfillAnimePosters(term, postProcessResults(cards, cleaned))
                }
            }
            if (fallbackCards.isEmpty()) {
                fallbackCards = withContext(Dispatchers.IO) { searchHtml(term, cleaned) }
            }
        }
        if (fallbackCards.isNotEmpty()) return backfillAnimePosters(cleaned.coreTitle, fallbackCards)
        return emptyList()
    }

    /** Primary host first (live 2026-10-20: 11 cards vs 4 on the mirror). */
    private fun searchHosts(): List<Pair<String, String>> {
        val configured = DynamicRulesManager.getBaseUrls(name)
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http", ignoreCase = true) }
        val hosts = (listOf("https://gogoanime.or.at") + configured + listOf("https://anitaku.com.ro"))
            .distinctBy { it.lowercase() }
        return hosts.take(2).map { "$it/wp-admin/admin-ajax.php" to "$it/" }
    }

    /** HTML search fallback; current theme is `.listupd > article.bs > .bsx > a`. */
    private fun searchHtml(term: String, cleaned: CleanedQuery): List<ShowCard> {
        return try {
            val base = searchHosts().first().second
            val searchUrl = base + "?s=" + java.net.URLEncoder.encode(term, "UTF-8")
            val html = HttpClient.getText(searchUrl, referer = base) ?: return emptyList()
            val doc = Jsoup.parse(html, searchUrl)
            val items = mutableListOf<ShowCard>()
            for (el in doc.select(
                ".listupd article, .listupd .bs, .bs .bsx, .film_list-wrap .flw-item, " +
                    ".last_episodes ul li, .items li, div.anime-card"
            )) {
                val a = el.selectFirst("a[href]") ?: continue
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (href.isBlank() || href.contains("/category/") || href.contains("/genre/")) continue
                val titleEl = el.selectFirst(".film-name, .name, .entry-title, h2, h3") ?: a
                val title = titleEl.text().trim().ifBlank { a.attr("title").trim() }
                if (title.isBlank()) continue
                if (items.any { it.url == href }) continue
                val img = el.selectFirst("img[src], img[data-src]")?.let {
                    it.attr("abs:src").ifBlank { it.attr("abs:data-src") }
                        .ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
                } ?: ""
                items.add(
                    ShowCard(
                        title = formatDisplayTitle(title, cleaned.coreTitle),
                        url = href,
                        posterUrl = if (PosterBackfill.isUsablePoster(img)) img else "",
                        site = name,
                        category = "Anime"
                    )
                )
            }
            postProcessResults(items, cleaned)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Backfills a pipeline card's blank poster from the compiled AJAX search. */
    private suspend fun backfillAnimePosters(term: String, cards: List<ShowCard>): List<ShowCard> {
        val clean = PosterBackfill.sanitize(cards)
        if (clean.none { it.posterUrl.isBlank() }) return clean
        val cleaned = SeasonNoiseStripper.clean(term)
        val posters = HashMap<String, String>()
        for ((ajaxUrl, referer) in searchHosts()) {
            val compiled = withContext(Dispatchers.IO) { fetchAjaxCards(ajaxUrl, referer, term, cleaned) }
            if (compiled.isEmpty()) continue
            for (card in compiled) {
                if (card.posterUrl.isNotBlank()) {
                    posters[PosterBackfill.normalizeKey(card.url)] = card.posterUrl
                }
            }
            break
        }
        return PosterBackfill.backfill(clean) { posters }
    }

    private fun cachedSearch(key: String): List<ShowCard>? = synchronized(searchCacheLock) {
        val hit = searchCache[key] ?: return null
        if (hit.expiresAtMs < System.currentTimeMillis()) {
            searchCache.remove(key)
            return null
        }
        hit.cards
    }

    private fun rememberSearch(key: String, cards: List<ShowCard>) {
        if (key.isBlank() || cards.isEmpty()) return
        synchronized(searchCacheLock) {
            searchCache[key] = CachedSearch(cards, System.currentTimeMillis() + SEARCH_CACHE_TTL_MS)
        }
    }

    internal fun clearSearchCacheForTest() {
        synchronized(searchCacheLock) { searchCache.clear() }
    }

    private data class CachedSearch(val cards: List<ShowCard>, val expiresAtMs: Long)
    private const val SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L
    private val searchCacheLock = Any()
    private val searchCache = object : java.util.LinkedHashMap<String, CachedSearch>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSearch>?): Boolean =
            size > 64
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        if (!SiteGate.isEnabled(name)) {
            return ShowDetails(show = ShowCard(title = "Anime", url = showUrl, site = name))
        }
        val show = ShowCard(title = "Anime", url = showUrl, site = name)

        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, showUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.metaTitle ?: "Anime",
                    url = showUrl,
                    posterUrl = if (PosterBackfill.isUsablePoster(res.metaPoster.orEmpty())) res.metaPoster.orEmpty() else "",
                    site = name
                )
                return ShowDetails(show = card, synopsis = res.metaSynopsis ?: "", episodes = res.episodes)
            }
        }

        try {
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst(".anime_info_body_bg h1, h1.entry-title, h1")?.text()?.trim() ?: "Anime"
            val poster = doc.selectFirst(".anime_info_body_bg img, .thumb img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".description, .entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            PostContentSanitizer.clean(doc)
            // Live 2026-10-20 (gogoanime.or.at): the list is
            // `.eplister ul li a > .epl-num` + `.epl-title`; the old selector
            // set already matched .eplister but read the number from raw
            // anchor text (which also carries the show title and "1" from
            // "Season 1" — the .epl-num cell is the exact episode).
            val epLinks = doc.select(
                ".eplister ul li a, .bixbox.bxcl.epcheck a, .episodes a, ul.episodes-lists li a, " +
                    "#episode_page a, a[href*='-episode-'], a[href*='-movie-'], a[href*='episode']"
            )

            for (a in epLinks) {
                val rawHref = a.attr("href").trim()
                if (rawHref.isBlank() || rawHref == "#" || rawHref.startsWith("#") || rawHref.startsWith("javascript:")) continue

                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }.substringBefore('#')

                if (href.isBlank() || href == showUrl || href in seen || href.contains("/category/") || href.contains("/genre/")) continue
                if (showUrl.contains("/anime/") && href.endsWith("/anime/")) continue
                val nameText = a.selectFirst(".epl-title")?.text()?.trim().orEmpty().ifBlank { a.text().trim() }
                if (PostContentSanitizer.isSiblingPostAnchorText(nameText)) continue
                seen.add(href)
                val num = a.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()
                    ?: Regex("""\d+""").find(nameText)?.value?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)

                episodes.add(
                    EpisodeItem(
                        title = if (nameText.isNotBlank() && !nameText.equals("#", ignoreCase = true)) nameText else "Episode $num",
                        url = href,
                        episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(1, num),
                        site = name,
                        season = 1,
                        episode = num
                    )
                )
            }

            // Single-episode / Movie fallback if epLinks were empty or pointed to the show page itself
            if (episodes.isEmpty()) {
                val playerIframe = doc.selectFirst("iframe[src], .anime_muti_link a[data-video], .servers a[data-video]")
                if (playerIframe != null) {
                    episodes.add(
                        EpisodeItem(
                            title = "Movie / Episode 1",
                            url = showUrl,
                            episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(1, 1),
                            site = name,
                            season = 1,
                            episode = 1
                        )
                    )
                }
            }

            // Subtitle surfaces on the show/episode page (srt/vtt/ass links or
            // <track> elements) — recorded for the engine's sidecar step.
            val subtitleTracks = SubtitleSurfaces.parsePageTracks(doc, showUrl)
            if (subtitleTracks.isNotEmpty()) SubtitleSurfaces.record(showUrl, subtitleTracks)

            val card = ShowCard(
                title = title,
                url = showUrl,
                posterUrl = if (PosterBackfill.isUsablePoster(poster)) poster else "",
                site = name
            )
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedBy { it.episodeNum })
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val shadowControlStart = System.currentTimeMillis()
        // OTA resolve recipe first: when a signed playbook carries the
        // resolve+terminal stages it replaces this compiled scraping flow
        // entirely — and skips the page fetch below on success. The compiled
        // path stays the untouched fallback (playbook absent, or resolve fails).
        val ota = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
        var direct: String? = ota

        if (direct == null) {
            try {
                val html = HttpClient.getText(episodeUrl, referer = "https://gogoanime.or.at/") ?: ""

                // 1. Direct candidate player embeds on page (newplayer.php, megaplay.buzz, megaplays.se, takuembed, mirror dropdowns)
                val doc = Jsoup.parse(html, episodeUrl)
                val subtitleTracks = SubtitleSurfaces.parsePageTracks(doc, episodeUrl)
                if (subtitleTracks.isNotEmpty()) SubtitleSurfaces.record(episodeUrl, subtitleTracks)
                val candidates = mutableListOf<String>()

                // Dynamic OTA mirror selectors allow updating mirror/server DOM selectors in future OTA updates
                val otaSelectors = DynamicRulesManager.getMirrorSelectors(name) +
                    DynamicRulesManager.getMirrorSelectors("gogoanime") +
                    DynamicRulesManager.getMirrorSelectors("anitaku")
                val baseSelectors = listOf(
                    "select.mirror option[value]",
                    "select#mirror-select option[value]",
                    "select[name='mirror'] option[value]",
                    ".anime_muti_link a[data-video]",
                    ".servers a[data-video]",
                    ".anime_muti_link a[href]",
                    "iframe[src]"
                )
                val allSelectors = (otaSelectors + baseSelectors).distinct()

                for (selector in allSelectors) {
                    for (el in doc.select(selector)) {
                        val valueAttr = el.attr("value").trim()
                        if (valueAttr.isNotBlank()) {
                            // Check if value is base64-encoded iframe or URL
                            val decoded = if (valueAttr.length >= 16 && (valueAttr.startsWith("PGlm") || valueAttr.startsWith("aHR0") || !valueAttr.contains(" "))) {
                                val b = try {
                                    android.util.Base64.decode(valueAttr, android.util.Base64.DEFAULT)
                                        ?.takeIf { it.isNotEmpty() }
                                        ?: java.util.Base64.getDecoder().decode(valueAttr)
                                } catch (_: Throwable) {
                                    try {
                                        java.util.Base64.getDecoder().decode(valueAttr)
                                    } catch (_: Throwable) { null }
                                }
                                if (b != null) String(b, Charsets.UTF_8).trim() else ""
                            } else ""

                            if (decoded.isNotBlank()) {
                                val m = Pattern.compile("""src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(decoded)
                                if (m.find()) {
                                    val src = m.group(1)?.trim().orEmpty()
                                    if (src.isNotBlank() && !src.startsWith("javascript:")) candidates.add(src)
                                } else if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                                    candidates.add(decoded)
                                }
                            } else if (valueAttr.startsWith("http://") || valueAttr.startsWith("https://")) {
                                candidates.add(valueAttr)
                            }
                        }

                        val dataVideo = el.attr("data-video").trim()
                        if (dataVideo.isNotBlank() && !dataVideo.startsWith("javascript:")) {
                            candidates.add(dataVideo)
                        }

                        val src = el.attr("src").trim()
                        if (src.isNotBlank() && !src.startsWith("javascript:")) {
                            candidates.add(src)
                        }

                        val href = el.attr("href").trim()
                        if (href.isNotBlank() && !href.startsWith("javascript:") && !href.startsWith("#")) {
                            candidates.add(href)
                        }
                    }
                }

                if (candidates.isNotEmpty()) {
                    val fullCandidates = candidates.distinct().map { HttpClient.safeResolveUri(episodeUrl, it) }
                    // Provider-issued fan-out; bypass stale HostHealth pre-judges.
                    val resolved = ResolverRegistry.resolveAny(fullCandidates, quality, bypassHealth = true)
                    if (!resolved.isNullOrBlank()) {
                        direct = resolved
                    }
                }

                // 2. Gogoanime fetch_download_links API fallback
                if (direct.isNullOrBlank()) {
                    val malMatch = Pattern.compile("""malId\s*=\s*['"](\d+)['"]""").matcher(html)
                    val epMatch = Pattern.compile("""ep\s*=\s*['"](\d+)['"]""").matcher(html)
                    if (malMatch.find() && epMatch.find()) {
                        val malId = malMatch.group(1) ?: ""
                        val ep = epMatch.group(1) ?: ""
                        // N2-fix (investigation anime100): some gogo-hosted
                        // episodes expose ONLY a transport-dead player lane, but
                        // the page still carries malId/ep — the megaplays mal
                        // endpoint is the live lane (mal 59433/5 proven to
                        // MPEG-TS on the wire). The fetchdl ajax below stays as
                        // the follow-up fallback (its gap is site-side data).
                        ResolverRegistry.resolveAny(
                            listOf("https://megaplays.se/stream/mal/$malId/$ep/sub"),
                            quality,
                            bypassHealth = true
                        )?.let { direct = it }
                        val host = HttpClient.safeHost(episodeUrl, "gogoanime.or.at")
                        val ajaxUrl = "https://$host/wp-admin/admin-ajax.php"

                        val form = mapOf(
                            "action" to "fetch_download_links",
                            "mal_id" to malId,
                            "ep" to ep
                        )
                        // Ajax runs only if the megaplays handoff above missed.
                        val body = if (direct.isNullOrBlank()) HttpClient.postForm(
                            url = ajaxUrl,
                            form = form,
                            referer = episodeUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        ) else null
                        if (!body.isNullOrBlank()) {
                            val dlHtml = JSONObject(body).optJSONObject("data")?.optString("result") ?: ""
                            if (dlHtml.isNotBlank()) {
                                val dlDoc = Jsoup.parse(dlHtml, episodeUrl)
                                val dlList = mutableListOf<Pair<Int, String>>()
                                for (a in dlDoc.select("a[href]")) {
                                    val label = a.text().trim().lowercase()
                                    val hVal = Regex("""(\d+)p?""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 720
                                    val link = a.attr("abs:href").ifBlank { a.attr("href") }
                                    if (link.isNotBlank()) dlList.add(hVal to link)
                                }
                                val reqHeight = quality.filter { it.isDigit() }.toIntOrNull() ?: 720
                                val sortedLinks = if (dlList.isNotEmpty()) {
                                    dlList.sortedWith(compareBy({ (h, _) -> if (h <= reqHeight) 0 else 1 }, { (h, _) -> Math.abs(h - reqHeight) }))
                                        .map { it.second }
                                } else {
                                    dlDoc.select("a[href]").map { it.attr("abs:href").ifBlank { it.attr("href") } }
                                }
                                val resolved = ResolverRegistry.resolveAny(sortedLinks, quality, bypassHealth = true)
                                if (!resolved.isNullOrBlank()) {
                                    direct = resolved
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val target = if (!direct.isNullOrBlank()) direct else {
            if (com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(episodeUrl)) episodeUrl else ""
        }
        val isHls = target.contains(".m3u8") || target.contains("manifest")
        // Scientist control seam: this compiled path IS the user-facing
        // control whenever the pipelines family runs shadow-armed (published
        // empty). Report only the outcome class — never URLs — so ShadowRunner
        // issues real both-engine comparison records.
        com.anonrode.downloader.pipeline.ShadowRunner.observeControl(
            name,
            success = target.isNotBlank(),
            wallMs = System.currentTimeMillis() - shadowControlStart
        )
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "anime.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
