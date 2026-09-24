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
import java.util.regex.Pattern

object NepuProvider : SiteProvider {
    override val name: String = "nepu"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
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
                val jsonStr = HttpClient.getText(url, tag = "search") ?: return emptyList()

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
                        val fullUrl = "$mainUrl/watch/$mediaType/$tmdbId"
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
        return filterAvailable(rawResults)
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

    internal fun buildMirrors(
        mediaType: String,
        tmdbId: String,
        season: Int? = null,
        episode: Int? = null,
        srvMap: Map<String, String> = emptyMap(),
        primaryUrl: String = ""
    ): List<String> {
        val mirrors = mutableListOf<String>()
        srvMap.values.forEach { url ->
            val clean = url.trim()
            if (clean.isNotBlank() && clean != primaryUrl && !mirrors.contains(clean)) {
                mirrors.add(clean)
            }
        }
        val isTv = mediaType == "tv" && season != null && episode != null
        val tvPath = if (isTv) "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"
        val synthetic = listOf(
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
        synthetic.forEach { sUrl ->
            if (sUrl != primaryUrl && !mirrors.contains(sUrl)) {
                mirrors.add(sUrl)
            }
        }
        return mirrors
    }

    private val EPISODE_PATTERN = Pattern.compile("""watch/tv/\d+/(\d+)/(\d+)(?:[?&#].*)?$""")

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(
            title = if (showUrl.contains("/tv/")) "Nepu TV" else "Nepu Movie",
            url = showUrl,
            site = name
        )

        val tmdbId = showUrl.substringAfterLast('/').substringBefore('?')
        val mediaType = if (showUrl.contains("/tv/")) "tv" else "movie"

        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, showUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                // The playbook carries no meta for nepu — the show title stays
                // the compiled TV/Movie default derived from the URL.
                val card = ShowCard(title = res.metaTitle ?: show.title, url = showUrl, site = name)
                return ShowDetails(show = card, synopsis = res.metaSynopsis ?: "", episodes = res.episodes)
            }
        }

        if (showUrl.contains("/tv/")) {
            val episodes = mutableListOf<EpisodeItem>()
            try {
                val html = HttpClient.getText(showUrl, referer = "$mainUrl/", headers = mapOf("Cookie" to "hv=1")) ?: ""
                val srvMap = extractSrvMap(html)
                val doc = Jsoup.parse(html, showUrl)
                val seen = mutableSetOf<Pair<Int, Int>>()
                val links = mutableListOf<Triple<Int, Int, String>>() // (season, episode, url)
                for (a in doc.select("a[href]")) {
                    val href = a.attr("href")
                    val m = EPISODE_PATTERN.matcher(href)
                    if (m.find()) {
                        val season = m.group(1)?.toIntOrNull() ?: continue
                        val episode = m.group(2)?.toIntOrNull() ?: continue
                        if (seen.add(Pair(season, episode))) {
                            links.add(Triple(season, episode, HttpClient.safeResolveUri(showUrl, href)))
                        }
                    }
                }
                links.sortWith(compareBy({ it.first }, { it.second }))
                links.forEachIndexed { idx, (season, episode, url) ->
                    val mirrors = buildMirrors("tv", tmdbId, season, episode, srvMap, primaryUrl = url)
                    episodes.add(
                        EpisodeItem(
                            title = "S%02d E%02d".format(season, episode),
                            url = url,
                            episodeNum = idx + 1,
                            site = name,
                            mirrorUrls = mirrors
                        )
                    )
                }
            } catch (_: Exception) {}
            if (episodes.isNotEmpty()) {
                return ShowDetails(show = show, episodes = episodes)
            }
        }

        val html = try {
            HttpClient.getText(showUrl, referer = "$mainUrl/", headers = mapOf("Cookie" to "hv=1")) ?: ""
        } catch (_: Exception) { "" }
        val srvMap = extractSrvMap(html)
        val mirrors = buildMirrors("movie", tmdbId, srvMap = srvMap, primaryUrl = showUrl)

        val episodes = listOf(
            EpisodeItem(
                title = "Stream / Movie",
                url = showUrl,
                episodeNum = 1,
                site = name,
                mirrorUrls = mirrors
            )
        )
        return ShowDetails(show = show, episodes = episodes)
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val ota = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
        var direct = ota ?: ResolverRegistry.resolve(episodeUrl, quality)

        // If primary URL didn't resolve to a stream, try the other 13 embed servers!
        if (direct.isNullOrBlank()) {
            val tmdbId = episodeUrl.substringAfterLast('/').substringBefore('?')
            val mediaType = if (episodeUrl.contains("/tv/")) "tv" else "movie"
            if (tmdbId.isNotBlank() && tmdbId.all { it.isDigit() }) {
                val candidateMirrors = buildMirrors(mediaType, tmdbId, primaryUrl = episodeUrl)
                for (mirror in candidateMirrors) {
                    val resolved = ResolverRegistry.resolve(mirror, quality)
                    if (!resolved.isNullOrBlank()) {
                        direct = resolved
                        break
                    }
                }
            }
        }

        if (direct.isNullOrBlank()) direct = ""
        var filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" }
        if (filename.lowercase().endsWith(".m3u8")) filename = filename.dropLast(5) + ".mp4"
        return DownloadRecipe(
            directUrl = direct,
            filename = filename,
            backend = "yt-dlp",
            parallelSockets = 16
        )
    }
}
