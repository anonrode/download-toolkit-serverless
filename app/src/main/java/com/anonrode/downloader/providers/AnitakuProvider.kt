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
        val batch = mutableListOf<ShowCard>()
        try {
            val form = mapOf("action" to "ts_ac_do_search", "ts_ac_query" to term)
            val body = HttpClient.postForm(
                url = ajaxUrl,
                form = form,
                referer = referer,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                tag = "search"
            ) ?: return emptyList()
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
                                    posterUrl = image,
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
        val cleaned = SeasonNoiseStripper.clean(query)
        val expandedTerms = AnimeOracle.expandTerms(cleaned.coreTitle)
        val primaryTerm = if (cleaned.coreTitle.length >= 2) cleaned.coreTitle else query.trim()
        val searchTerms = if (expandedTerms.isNotEmpty()) expandedTerms else listOf(primaryTerm)

        // OTA pipeline first (dual admin-ajax POST merge lives in the playbook)
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val otaResults = mutableListOf<ShowCard>()
            for (term in searchTerms.take(2)) {
                val res = RulesPipeline.runSearch(name, pl, term)
                if (res.isNotEmpty()) {
                    otaResults.addAll(res.map {
                        it.copy(title = formatDisplayTitle(it.title, cleaned.coreTitle))
                    })
                }
            }
            if (otaResults.isNotEmpty()) {
                return postProcessResults(otaResults, cleaned)
            }
        }

        return coroutineScope {
            val endpoints = listOf(
                "https://gogoanime.or.at/wp-admin/admin-ajax.php" to "https://gogoanime.or.at/",
                "https://anitaku.com.ro/wp-admin/admin-ajax.php" to "https://anitaku.com.ro/"
            )

            // Concurrently query (terms x endpoints)
            val deferreds = searchTerms.take(2).flatMap { term ->
                endpoints.map { (ajaxUrl, referer) ->
                    async(Dispatchers.IO) {
                        fetchAjaxCards(ajaxUrl, referer, term, cleaned)
                    }
                }
            }

            val allResults = deferreds.awaitAll().flatten()
            val distinct = postProcessResults(allResults, cleaned)
            if (distinct.isNotEmpty()) return@coroutineScope distinct

            // HTML search fallback on gogoanime.or.at if AJAX autocomplete returned empty
            try {
                val searchUrl = "https://gogoanime.or.at/?s=" + java.net.URLEncoder.encode(cleaned.coreTitle, "UTF-8")
                val html = HttpClient.getText(searchUrl, referer = "https://gogoanime.or.at/")
                if (!html.isNullOrBlank()) {
                    val doc = Jsoup.parse(html, searchUrl)
                    val items = mutableListOf<ShowCard>()
                    for (el in doc.select(".film_list-wrap .flw-item, .last_episodes ul li, .items li, .listupd article, div.anime-card")) {
                        val a = el.selectFirst("a[href]") ?: continue
                        val href = a.attr("abs:href").ifBlank { a.attr("href") }
                        if (href.isBlank() || href.contains("/category/") || href.contains("/genre/")) continue
                        val titleEl = el.selectFirst(".film-name, .name, .entry-title, h2, h3") ?: a
                        val title = titleEl.text().trim()
                        val img = el.selectFirst("img[src]")?.let { it.attr("abs:src").ifBlank { it.attr("src") } } ?: ""
                        if (title.isNotBlank()) {
                            val displayTitle = formatDisplayTitle(title, cleaned.coreTitle)
                            items.add(
                                ShowCard(
                                    title = displayTitle,
                                    url = href,
                                    posterUrl = img,
                                    site = name,
                                    category = "Anime"
                                )
                            )
                        }
                    }
                    if (items.isNotEmpty()) return@coroutineScope postProcessResults(items, cleaned)
                }
            } catch (_: Exception) {}

            emptyList()
        }
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Anime", url = showUrl, site = name)

        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, showUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.metaTitle ?: "Anime",
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

            val title = doc.selectFirst(".anime_info_body_bg h1, h1.entry-title, h1")?.text()?.trim() ?: "Anime"
            val poster = doc.selectFirst(".anime_info_body_bg img, .thumb img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".description, .entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            PostContentSanitizer.clean(doc)
            val epLinks = doc.select(
                ".bixbox.bxcl.epcheck a, .eplister ul li a, .episodes a, ul.episodes-lists li a, a[href*='-episode-'], a[href*='-movie-'], a[href*='episode']"
            )

            for (a in epLinks) {
                val rawHref = a.attr("href").trim()
                if (rawHref.isBlank() || rawHref == "#" || rawHref.startsWith("#") || rawHref.startsWith("javascript:")) continue

                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }.substringBefore('#')

                if (href.isBlank() || href == showUrl || href in seen || href.contains("/category/") || href.contains("/genre/")) continue
                if (showUrl.contains("/anime/") && href.endsWith("/anime/")) continue
                val nameText = a.text().trim()
                if (PostContentSanitizer.isSiblingPostAnchorText(nameText)) continue
                seen.add(href)
                val num = Regex("""\d+""").find(nameText)?.value?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)

                episodes.add(
                    EpisodeItem(
                        title = if (nameText.isNotBlank() && !nameText.equals("#", ignoreCase = true)) nameText else "Episode $num",
                        url = href,
                        episodeNum = num,
                        site = name
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
                            episodeNum = 1,
                            site = name
                        )
                    )
                }
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedBy { it.episodeNum })
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        // OTA resolve recipe first: when a signed playbook carries the
        // resolve+terminal stages it replaces this compiled scraping flow
        // entirely — and skips the page fetch below on success. The compiled
        // path stays the untouched fallback (playbook absent, or resolve fails).
        val ota = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
        var direct: String? = ota

        if (direct == null) {
            try {
                val html = HttpClient.getText(episodeUrl, referer = "https://gogoanime.or.at/") ?: ""

                // 1. Direct candidate player embeds on page (newplayer.php, megaplay.buzz, megaplays.se, takuembed)
                val doc = Jsoup.parse(html, episodeUrl)
                val candidates = mutableListOf<String>()
                for (a in doc.select(".anime_muti_link a[data-video], .servers a[data-video], .anime_muti_link a[href]")) {
                    val dataVideo = a.attr("data-video").ifEmpty { a.attr("href") }
                    if (dataVideo.isNotBlank() && !dataVideo.startsWith("javascript:")) candidates.add(dataVideo)
                }
                for (iframe in doc.select("iframe[src]")) {
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && !src.startsWith("javascript:")) candidates.add(src)
                }

                if (candidates.isNotEmpty()) {
                    val fullCandidates = candidates.map { HttpClient.safeResolveUri(episodeUrl, it) }
                    val resolved = ResolverRegistry.resolveAny(fullCandidates, quality)
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
                        val host = HttpClient.safeHost(episodeUrl, "gogoanime.or.at")
                        val ajaxUrl = "https://$host/wp-admin/admin-ajax.php"

                        val form = mapOf(
                            "action" to "fetch_download_links",
                            "mal_id" to malId,
                            "ep" to ep
                        )
                        val body = HttpClient.postForm(
                            url = ajaxUrl,
                            form = form,
                            referer = episodeUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        )
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
                                val resolved = ResolverRegistry.resolveAny(sortedLinks, quality)
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
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "anime.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
