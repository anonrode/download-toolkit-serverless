package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.resolvers.VidsrcResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object NepuProvider : SiteProvider {
    override val name: String = "nepu"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    /**
     * SRV_MAP mirrors are EPISODE-specific: the show page embeds the default
     * S01E01 cluster, and the old per-tmdb key made those literals candidates
     * for EVERY episode of the show (Servamp S01E01==S01E02, and every NEPU
     * episode in the 2026-10-20 log). The key now carries the episode the
     * page actually represents; a show-level page (no /s/e in the URL) is
     * cached under "show" and may only serve S1E1.
     */
    internal val srvMapCache = ConcurrentHashMap<String, Map<String, String>>()

    internal fun srvMapKey(tmdbId: String, season: Int?, episode: Int?): String =
        if (season != null && episode != null && season > 0 && episode > 0) {
            "$tmdbId|$season|$episode"
        } else "$tmdbId|show"

    private fun srvMapFor(tmdbId: String, season: Int?, episode: Int?): Map<String, String> {
        if (season != null && episode != null) {
            srvMapCache[srvMapKey(tmdbId, season, episode)]?.let { return it }
            if (season == 1 && episode == 1) {
                srvMapCache[srvMapKey(tmdbId, null, null)]?.let { return it }
            }
        }
        return emptyMap()
    }

    override suspend fun search(query: String): List<ShowCard> {
        // RC-4: nepu.gd 451-blocks specific title keywords at the search API
        // (reproduced live 2026-10-21: servamp / the-promised-neverland /
        // demons-ascension → 451 while generic words → 200). Once recorded,
        // the same query skips the entire fan-out (OTA pipeline AND compiled
        // fallback AND the filterAvailable+verify wave) until the TTL heals.
        // The ledger is site-scoped and in-memory: other providers keep
        // serving the title, and the block is never persisted.
        if (com.anonrode.downloader.pipeline.SearchBlockLedger.isBlocked(name, query)) {
            com.anonrode.downloader.util.DebugLog.resolve("nepu search: query 451-blocked in ledger — serving empty without fan-out")
            return emptyList()
        }
        // OTA pipeline first (JSON API + URL synthesis live in the playbook);
        // non-empty wins, else the compiled path below runs.
        val rawResults = DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) results else null
        } ?: run {
            val results = mutableListOf<ShowCard>()
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$mainUrl/api/search?q=$encoded"
                val jsonStr = try {
                    HttpClient.getText(url, tag = "search")
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // The compiled path saw HITs of the same 451 keyword block
                    // as the OTA path (share-43 10-19: "HTTP 451 for
                    // .../api/search?q=the+promised+Neverland") — record it so
                    // the ledger gates both shapes of the fan-out.
                    if (com.anonrode.downloader.pipeline.SearchBlockLedger.isLegalBlockMessage(e.message)) {
                        com.anonrode.downloader.pipeline.SearchBlockLedger.recordBlocked(name, query)
                        com.anonrode.downloader.util.DebugLog.error("nepu search: HTTP 451 legal block recorded — ${e.message?.take(120)}")
                    }
                    null
                } ?: return emptyList()

                val obj = JSONObject(jsonStr)
                val array = obj.optJSONArray("results") ?: return emptyList()

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val tmdbId = item.optString("id")
                    val mediaType = item.optString("media_type", "movie")
                    val title = item.optString("title").ifEmpty { item.optString("name") }
                    val releaseDate = item.optString("release_date").ifEmpty { item.optString("first_air_date") }
                    val year = if (releaseDate.contains("-")) releaseDate.substringBefore('-') else ""
                    val posterPath = item.optString("poster_path")
                    val poster = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w342$posterPath" else ""

                    if (tmdbId.isNotBlank() && title.isNotBlank()) {
                        // The OTA playbook's urlTemplates can change the
                        // watch-URL shape fleet-wide (payload ships
                        // "/watch/{media_type}/{id}"); the hardcoded shape is
                        // the fallback, so a payload without a template behaves
                        // byte-identically to before. {base} is supported for
                        // absolute templates; unrenderable templates fall back.
                        val tpl = DynamicRulesManager.getUrlTemplate(name)
                        val fullUrl = if (tpl.isBlank()) {
                            "$mainUrl/watch/$mediaType/$tmdbId"
                        } else {
                            val vars = mapOf("base" to mainUrl, "media_type" to mediaType, "id" to tmdbId)
                            val rendered = RulesPipeline.renderTemplate(tpl, vars) { vars[it] }
                            when {
                                rendered.isNullOrBlank() -> "$mainUrl/watch/$mediaType/$tmdbId"
                                rendered.startsWith("http://") || rendered.startsWith("https://") -> rendered
                                else -> mainUrl.trimEnd('/') + "/" + rendered.trimStart('/')
                            }
                        }
                        results.add(
                            ShowCard(
                                title = if (year.isNotBlank()) "$title ($year)" else title,
                                url = fullUrl,
                                posterUrl = poster,
                                site = name,
                                category = if (mediaType == "tv") "TV Show" else "Movie",
                                year = year
                            )
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {}
            results
        }

        if (rawResults.isEmpty()) return emptyList()
        // NoSourceLedger: titles verified (multi-episode × multi-platform, 2026-10-16)
        // to have NO source on ANY platform don't even appear in search. A new
        // rules payload clears the ledger, so the day a mirror adds one of
        // these titles it re-appears automatically.
        val notHidden = rawResults.filter {
            !com.anonrode.downloader.pipeline.NoSourceLedger.isHidden(it.title)
        }
        if (notHidden.isEmpty()) return emptyList()
        return filterAvailable(notHidden)
    }

    internal suspend fun filterAvailable(cards: List<ShowCard>): List<ShowCard> = coroutineScope {
        val semaphore = Semaphore(6)
        cards.map { card ->
            async {
                val tmdbId = card.url.substringAfterLast('/').substringBefore('?')
                val mediaType = if (card.url.contains("/tv/")) "tv" else "movie"
                if (tmdbId.isBlank() || !tmdbId.all { it.isDigit() }) {
                    card
                } else {
                    val isAvail = semaphore.withPermit {
                        VidsrcResolver.checkAvailability(mediaType, tmdbId, tag = "search")
                    }
                    if (isAvail) card else null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private val SRV_MAP_PATTERN = Pattern.compile("""SRV_MAP\s*=\s*(\{.+?\})\s*[,;]""")

    internal fun extractSrvMap(html: String): Map<String, String> {
        val m = SRV_MAP_PATTERN.matcher(html)
        if (!m.find()) return emptyMap()
        val jsonStr = m.group(1) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(jsonStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k).replace("\\/", "/")
                if (v.isNotBlank()) out[k] = v
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * True only when [url] explicitly names this tmdb/season/episode. A URL
     * that carries no episode identity is NOT usable as an episode mirror
     * (the srvMap show-level leak). Pure, unit-tested.
     */
    internal fun mirrorNamesEpisode(url: String, tmdbId: String, season: Int, episode: Int): Boolean {
        val parsed = com.anonrode.downloader.resolvers.parseEpisodeFromUrl(url) ?: return false
        if (parsed.first.isNotBlank() && tmdbId.isNotBlank() && parsed.first != tmdbId) return false
        return parsed.second == season && parsed.third == episode
    }

    internal fun buildMirrors(
        mediaType: String,
        tmdbId: String,
        season: Int? = null,
        episode: Int? = null,
        srvMap: Map<String, String> = emptyMap(),
        primaryUrl: String = ""
    ): List<String> {
        val mirrors = mutableListOf<String>()
        val isTv = mediaType == "tv" && season != null && episode != null
        val tvPath = if (isTv) "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"

        // A dead mirror must never enter a candidate list: the user cannot
        // create a task from it, and the resolver sweep does not spend a pass
        // proving it dead again. knownDead is the OTA-owned verdict.
        fun addMirror(candidate: String) {
            val clean = candidate.trim()
            if (clean.isBlank() || clean == primaryUrl || mirrors.contains(clean)) return
            if (DynamicRulesManager.isKnownDead(clean)) return
            mirrors.add(clean)
        }

        // 5 live proven clusters (raced first ahead of legacy endpoints)
        val liveClusters = listOf(
            if (isTv) "https://net79.cc/api/embed-tmdb/$tmdbId?type=tv&se=$season&ep=$episode" else "https://net79.cc/api/embed-tmdb/$tmdbId?type=movie",
            if (isTv) "https://vidbolt.xyz/api/resolve?type=tv&id=$tmdbId&season=$season&episode=$episode" else "https://vidbolt.xyz/api/resolve?type=movie&id=$tmdbId",
            "https://embed.vidrift.net/embed/$tvPath",
            "https://nsrplay.space/embed/$tvPath",
            "https://streamvaultsrc.click/embed/$tvPath"
        )
        liveClusters.forEach { addMirror(it) }

        // Dynamic server mappings extracted from site HTML. STRICT episode
        // pinning: an srvMap literal is usable only when its URL names the
        // requested (season, episode). Mismatched or show-level (unparseable)
        // values are dropped — keeping them is what put an S1E1 URL into an
        // S2E7 candidate list and reused it from ResolveCache.
        if (isTv) {
            srvMap.values.forEach { candidate ->
                if (mirrorNamesEpisode(candidate, tmdbId, season!!, episode!!)) addMirror(candidate)
            }
        }

        val legacySynthetic = listOf(
            "https://vidsrc.mov/embed/$tvPath",
            "https://vidsrc.fyi/embed/$tvPath",
            "https://vidrock.net/" + (if (isTv) "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"),
            "https://vidnest.fun/" + (if (isTv) "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"),
            "https://www.vidking.net/embed/$tvPath",
            "https://vidlink.pro/" + (if (isTv) "tv/$tmdbId/$season/$episode?autoplay=true&title=true" else "movie/$tmdbId?autoplay=true&title=true"),
            "https://vidfast.pro/" + (if (isTv) "tv/$tmdbId/$season/$episode?autoPlay=true" else "movie/$tmdbId?autoPlay=true"),
            "https://vidup.to/" + (if (isTv) "tv/$tmdbId/$season/$episode?autoPlay=true" else "movie/$tmdbId?autoPlay=true"),
            "https://player.videasy.net/" + (if (isTv) "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"),
            "https://111movies.com/" + (if (isTv) "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"),
            if (isTv) "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode" else "https://www.2embed.cc/embed/$tmdbId",
            if (isTv) "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode" else "https://multiembed.mov/?video_id=$tmdbId&tmdb=1",
            if (isTv) "https://superflixapi.co/serie/$tmdbId/$season/$episode" else "https://superflixapi.co/filme/$tmdbId",
            "https://peachify.top/embed/$tvPath"
        )
        legacySynthetic.forEach { addMirror(it) }
        return mirrors
    }

    private val EPISODE_PATTERN = Pattern.compile("""watch/tv/\d+/(\d+)/(\d+)(?:[?&#].*)?$""")

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val isTv = showUrl.contains("/tv/")
        val show = ShowCard(
            title = if (isTv) "Nepu TV" else "Nepu Movie",
            url = showUrl,
            site = name
        )

        val tmdbId = showUrl.substringAfterLast('/').substringBefore('?')

        if (!isTv) {
            DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
                val res = RulesPipeline.runEpisodes(name, pl, showUrl)
                if (res != null && res.episodes.isNotEmpty()) {
                    val card = ShowCard(title = res.metaTitle ?: show.title, url = showUrl, site = name)
                    return ShowDetails(show = card, synopsis = res.metaSynopsis ?: "", episodes = res.episodes)
                }
            }
        }

        if (isTv) {
            val episodes = mutableListOf<EpisodeItem>()
            var extractedTitle: String? = null
            var extractedSynopsis: String? = null
            try {
                val html = fetchUnlockedHtml(showUrl)
                val srvMap = extractSrvMap(html)
                if (srvMap.isNotEmpty() && tmdbId.isNotBlank()) {
                    val pageRef = EPISODE_PATTERN.matcher(showUrl)
                    val pageSeason = if (pageRef.find()) pageRef.group(1)?.toIntOrNull() else null
                    val pageEpisode = if (pageSeason != null) pageRef.group(2)?.toIntOrNull() else null
                    srvMapCache[srvMapKey(tmdbId, pageSeason, pageEpisode)] = srvMap
                }
                val doc = Jsoup.parse(html, showUrl)

                extractedTitle = doc.selectFirst("h1")?.text()?.replace(Regex("""(?i)\s*(S\d+\s*E\d+|Season\s*\d+).*"""), "")?.trim()
                    ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - S")?.trim()
                extractedSynopsis = doc.selectFirst("p.leading-relaxed")?.text()?.trim()
                    ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

                // Check available seasons from select dropdown, tabs, or buttons
                val seasonOptions = doc.select("select option, a[href*='season-'], button[data-season], .season-tab")
                val seasons = mutableSetOf<Int>()
                for (opt in seasonOptions) {
                    val sVal = opt.attr("value").toIntOrNull()
                        ?: opt.attr("data-season").toIntOrNull()
                        ?: Regex("""season[- ]?(\d+)""", RegexOption.IGNORE_CASE).find(opt.attr("href") + " " + opt.text())?.groupValues?.get(1)?.toIntOrNull()
                    if (sVal != null && sVal > 0) {
                        seasons.add(sVal)
                    }
                }
                if (seasons.isEmpty()) seasons.add(1)

                val seen = mutableSetOf<Pair<Int, Int>>()
                val links = mutableListOf<Triple<Int, Int, String>>() // (season, episode, url)

                fun parseDocEpisodes(d: org.jsoup.nodes.Document, currentSeasonHint: Int?) {
                    for (a in d.select("a[href]")) {
                        val href = a.attr("href")
                        val m = EPISODE_PATTERN.matcher(href)
                        if (m.find()) {
                            val season = m.group(1)?.toIntOrNull() ?: currentSeasonHint ?: continue
                            val episode = m.group(2)?.toIntOrNull() ?: continue
                            if (seen.add(Pair(season, episode))) {
                                links.add(Triple(season, episode, HttpClient.safeResolveUri(showUrl, href)))
                            }
                        }
                    }
                }

                parseDocEpisodes(doc, 1)

                // For other seasons (> 1), fetch their season pages: /watch/tv/$tmdbId/$s/1
                for (s in seasons.sorted()) {
                    if (s == 1) continue
                    try {
                        val seasonUrl = "$mainUrl/watch/tv/$tmdbId/$s/1"
                        val sHtml = fetchUnlockedHtml(seasonUrl)
                        if (sHtml.isNotBlank()) {
                            val sDoc = Jsoup.parse(sHtml, seasonUrl)
                            parseDocEpisodes(sDoc, s)
                        }
                    } catch (_: Exception) {}
                }

                links.sortWith(compareBy({ it.first }, { it.second }))
                links.forEach { (season, episode, url) ->
                    val mirrors = buildMirrors("tv", tmdbId, season, episode, srvMap, primaryUrl = url)
                    episodes.add(
                        EpisodeItem(
                            title = "S%02d E%02d".format(season, episode),
                            url = url,
                            episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(season, episode),
                            site = name,
                            mirrorUrls = mirrors,
                            season = season,
                            episode = episode
                        )
                    )
                }
            } catch (_: Exception) {}

            val finalTitle = extractedTitle?.takeIf { it.isNotBlank() && it != "Nepu TV" } ?: show.title
            val finalCard = show.copy(title = finalTitle)
            return ShowDetails(show = finalCard, synopsis = extractedSynopsis ?: "", episodes = episodes)
        }

        val html = try {
            fetchUnlockedHtml(showUrl)
        } catch (_: Exception) { "" }
        val srvMap = extractSrvMap(html)
        if (srvMap.isNotEmpty() && tmdbId.isNotBlank()) {
            srvMapCache[srvMapKey(tmdbId, null, null)] = srvMap
        }
        val doc = Jsoup.parse(html, showUrl)
        val movieTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" |")?.trim()
        val movieSynopsis = doc.selectFirst("p.leading-relaxed")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val finalCard = show.copy(title = movieTitle?.takeIf { it.isNotBlank() } ?: show.title)
        val mirrors = buildMirrors("movie", tmdbId, srvMap = srvMap, primaryUrl = showUrl)

        val episodes = listOf(
            EpisodeItem(
                title = "Stream / Movie",
                url = showUrl,
                episodeNum = 1,
                site = name,
                mirrorUrls = mirrors,
                season = 1,
                episode = 1
            )
        )
        return ShowDetails(show = finalCard, synopsis = movieSynopsis ?: "", episodes = episodes)
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val tvMatch = Regex("""watch/tv/(\d+)(?:/(\d+)/(\d+))?""").find(episodeUrl)
        val movieMatch = Regex("""watch/movie/(\d+)""").find(episodeUrl)
        val isTv = tvMatch != null
        val tmdbId = tvMatch?.groupValues?.get(1)
            ?: movieMatch?.groupValues?.get(1)
            ?: episodeUrl.substringAfterLast('/').substringBefore('?')
        val season = tvMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: if (isTv) 1 else null
        val episode = tvMatch?.groupValues?.getOrNull(3)?.toIntOrNull() ?: if (isTv) 1 else null

        // OTA recipe first — detailed so the recipe's CDN referer (`upref`,
        // nsrplay data.referer) and subtitle list survive into the recipe.
        val detailed = RulesPipeline.runResolveForSiteDetailed(name, episodeUrl, quality)
        var direct = detailed?.directUrl ?: ResolverRegistry.resolve(episodeUrl, quality)

        // If primary URL didn't resolve to a stream, try the other embed servers!
        if (direct.isNullOrBlank() && tmdbId.isNotBlank() && tmdbId.all { it.isDigit() }) {
            // EPISODE-PINNED srvMap only: never the show-level map for a
            // non-S1E1 request (cross-episode mirror leak).
            val srvMap = srvMapFor(tmdbId, season, episode)
                .ifEmpty {
                    try {
                        val html = fetchUnlockedHtml(episodeUrl)
                        extractSrvMap(html).also {
                            if (it.isNotEmpty()) {
                                srvMapCache[srvMapKey(tmdbId, season, episode)] = it
                            }
                        }
                    } catch (_: Exception) { emptyMap() }
                }
            val mediaType = if (isTv) "tv" else "movie"
            // Mirror sweep via resolveAny (settle-and-score, episode-pinned
            // candidates; bypassHealth=true because this list is issued by the
            // provider for THIS episode and stale cross-session backoffs must
            // not pre-skip it — fresh outcomes still re-record health).
            direct = ResolverRegistry.resolveAny(
                buildMirrors(mediaType, tmdbId, season, episode, srvMap = srvMap, primaryUrl = episodeUrl),
                quality,
                bypassHealth = true,
                episode = if (isTv && season != null && episode != null) {
                    com.anonrode.downloader.resolvers.EpisodeRef(season, episode, tmdbId)
                } else null
            )
        }

        val finalUrl = direct.orEmpty()
        var filename = finalUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "media.mp4" }
        if (filename.lowercase().endsWith(".m3u8")) filename = filename.dropLast(5) + ".mp4"

        // Backend decision is EVIDENCE-based, never a blanket "yt-dlp":
        // a direct media path goes to Turbo/aria2c; only a genuine manifest
        // keeps yt-dlp. Unknown shapes stay aria2c (Turbo sniffs magic bytes).
        val isManifest = finalUrl.isNotBlank() &&
            (com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(finalUrl) ||
                com.anonrode.downloader.pipeline.UrlClassifier.isManifestShaped(finalUrl))
        val backend = if (isManifest) "yt-dlp" else "aria2c"

        val headers = detailed?.referer?.takeIf { it.isNotBlank() }
            ?.let { mapOf("Referer" to it) }
            ?: emptyMap()

        return DownloadRecipe(
            directUrl = finalUrl,
            filename = filename,
            headers = headers,
            backend = backend,
            parallelSockets = 16,
            subtitles = detailed?.subtitles.orEmpty()
        )
    }

    @Volatile
    private var cachedHv3: String? = null
    @Volatile
    private var cachedHv3Timestamp: Long = 0L

    internal suspend fun fetchUnlockedHtml(url: String): String {
        val now = System.currentTimeMillis()
        val currentToken = if (now - cachedHv3Timestamp < 23 * 3600 * 1000L) cachedHv3 else null
        val cookieHeader = if (!currentToken.isNullOrBlank()) "hv=1; hv2=1; hv3=$currentToken" else "hv=1; hv2=1"

        var html = try {
            HttpClient.getText(url, referer = "$mainUrl/", headers = mapOf("Cookie" to cookieHeader)) ?: ""
        } catch (_: Exception) { "" }

        if (html.isNotBlank() && !isChallengePage(html)) {
            return html
        }

        // Challenge encountered or token expired — solve PoW
        val solvedToken = solveHvTok(url)
        if (!solvedToken.isNullOrBlank()) {
            cachedHv3 = solvedToken
            cachedHv3Timestamp = now
            val solvedCookie = "hv=1; hv2=1; hv3=$solvedToken"
            html = try {
                HttpClient.getText(url, referer = "$mainUrl/", headers = mapOf("Cookie" to solvedCookie)) ?: ""
            } catch (_: Exception) { "" }
        }
        return html
    }

    private fun isChallengePage(html: String): Boolean {
        return html.contains("__hvtok") || html.contains("<title>Quick check</title>") ||
                (html.length < 25000 && !html.contains("SRV_MAP") && !html.contains("watch/tv/"))
    }

    internal suspend fun solveHvTok(showUrl: String): String? {
        val delim = if (showUrl.contains("?")) "&" else "?"
        val s1Url = "$showUrl${delim}__hvtok=1&s=1&r=0"
        val headers = mapOf(
            "Cookie" to "hv=1; hv2=1",
            "Referer" to showUrl
        )
        val s1Body = try {
            HttpClient.getText(s1Url, referer = showUrl, headers = headers)
        } catch (_: Exception) { null } ?: return null

        val seed: String
        val bits: Int
        try {
            val json = JSONObject(s1Body)
            seed = json.optString("seed")
            bits = json.optInt("bits", 12)
        } catch (_: Exception) {
            return null
        }
        if (seed.isBlank() || bits <= 0) return null

        val target = Math.pow(2.0, (32 - bits).toDouble()).toLong()
        val md = try { java.security.MessageDigest.getInstance("SHA-256") } catch (_: Exception) { return null }
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val colonByte = ':'.code.toByte()

        var solvedNonce = -1
        for (n in 1..1_000_000) {
            md.reset()
            md.update(seedBytes)
            md.update(colonByte)
            md.update(n.toString().toByteArray(Charsets.UTF_8))
            val digest = md.digest()
            val first4 = (((digest[0].toLong() and 0xFF) shl 24) or
                          ((digest[1].toLong() and 0xFF) shl 16) or
                          ((digest[2].toLong() and 0xFF) shl 8) or
                          (digest[3].toLong() and 0xFF)) and 0xFFFFFFFFL
            if (first4 < target) {
                solvedNonce = n
                break
            }
        }
        if (solvedNonce <= 0) return null

        val submitUrl = "$showUrl${delim}__hvtok=1&seed=$seed&n=$solvedNonce&r=0"
        val token = try {
            HttpClient.getText(submitUrl, referer = showUrl, headers = headers)?.trim()
        } catch (_: Exception) { null }
        return token?.takeIf { it.isNotBlank() && !it.contains("<") }
    }
}
