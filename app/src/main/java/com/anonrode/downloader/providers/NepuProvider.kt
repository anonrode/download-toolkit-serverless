package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.json.JSONObject
import java.net.URLEncoder

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

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Nepu Movie", url = showUrl, site = name)
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
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        val isHls = direct.contains(".m3u8") || direct.contains("manifest")
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" },
            backend = if (isHls) "ytdlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
