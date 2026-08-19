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
import java.net.URLEncoder
import java.util.regex.Pattern

object NepuProvider : SiteProvider {
    override val name: String = "nepu"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api/search?q=$encoded"
            val jsonStr = HttpClient.getText(url) ?: return emptyList()

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
        } catch (_: Exception) {}
        return results
    }

    private val EPISODE_PATTERN = Pattern.compile("""watch/tv/\d+/(\d+)/(\d+)(?:[?&#].*)?$""")

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(
            title = if (showUrl.contains("/tv/")) "Nepu TV" else "Nepu Movie",
            url = showUrl,
            site = name
        )

        if (showUrl.contains("/tv/")) {
            val episodes = mutableListOf<EpisodeItem>()
            try {
                val html = HttpClient.getText(showUrl, referer = "$mainUrl/") ?: ""
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
                    episodes.add(
                        EpisodeItem(
                            title = "S%02d E%02d".format(season, episode),
                            url = url,
                            episodeNum = idx + 1,
                            site = name
                        )
                    )
                }
            } catch (_: Exception) {}
            if (episodes.isNotEmpty()) {
                return ShowDetails(show = show, episodes = episodes)
            }
        }

        val episodes = listOf(
            EpisodeItem(
                title = "Stream / Movie",
                url = showUrl,
                episodeNum = 1,
                site = name
            )
        )
        return ShowDetails(show = show, episodes = episodes)
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        // Watch pages embed a vidsrc player whose chain (embed -> data.vidsrcme.ru
        // API -> wasm ChaCha20 decrypt -> CDN playlist) VidsrcResolver now cracks,
        // so directUrl is the token-stamped master playlist the engine feeds
        // straight to yt-dlp. Fall back to handing the raw watch page to the
        // engine's yt-dlp generic extractor if resolution ever comes up empty.
        var direct = ResolverRegistry.resolve(episodeUrl, quality)
        if (direct.isNullOrBlank()) {
            try {
                val html = HttpClient.getText(episodeUrl, referer = "$mainUrl/") ?: ""
                val doc = Jsoup.parse(html, episodeUrl)
                val iframe = doc.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embed = HttpClient.safeResolveUri(episodeUrl, iframe.attr("src"))
                    if (embed.isNotBlank()) direct = embed
                }
            } catch (_: Exception) {}
        }
        if (direct.isNullOrBlank()) direct = episodeUrl
        var filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" }
        if (filename.lowercase().endsWith(".m3u8")) filename = filename.dropLast(5) + ".mp4"
        return DownloadRecipe(
            directUrl = direct,
            filename = filename,
            backend = "ytdlp",
            parallelSockets = 16
        )
    }
}
