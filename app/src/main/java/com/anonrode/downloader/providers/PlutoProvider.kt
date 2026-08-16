package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import java.net.URLEncoder
import java.util.regex.Pattern

object PlutoProvider : SiteProvider {
    override val name: String = "pluto"
    override val mainUrl: String = "https://plutomovies.com"

    private val RESULT_RE = Pattern.compile(
        """href="(https?://plutomovies\.com/(movies|series)/[^"]+)".*?alt="([^"]+)""",
        Pattern.CASE_INSENSITIVE
    )

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val clean = query.replace("'", "").replace("’", "").trim()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "$mainUrl/search/$encoded/page/1"
            val html = HttpClient.getText(url) ?: return emptyList()

            val matcher = RESULT_RE.matcher(html)
            val seen = mutableSetOf<String>()

            while (matcher.find()) {
                val link = matcher.group(1) ?: continue
                val kind = matcher.group(2) ?: "movie"
                val title = matcher.group(3)?.trim() ?: ""

                if (link !in seen && title.isNotBlank()) {
                    seen.add(link)
                    results.add(
                        ShowCard(
                            title = title,
                            url = link,
                            posterUrl = "",
                            site = name,
                            category = if (kind == "series") "TV Series" else "Movie"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Pluto Title", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val episodes = mutableListOf<EpisodeItem>()

            if (showUrl.contains("/series/")) {
                val epPat = Pattern.compile("""href="(https?://plutomovies\.com/episodes/[^"]+)"[^>]*>.*?Episode\s*(\d+)""", Pattern.CASE_INSENSITIVE)
                val m = epPat.matcher(html)
                var count = 1
                while (m.find()) {
                    val epUrl = m.group(1) ?: continue
                    val epNum = m.group(2)?.toIntOrNull() ?: count
                    episodes.add(
                        EpisodeItem(
                            title = "Episode $epNum",
                            url = epUrl,
                            episodeNum = epNum,
                            site = name
                        )
                    )
                    count++
                }
            } else {
                // Single movie
                episodes.add(
                    EpisodeItem(
                        title = "Full Movie",
                        url = showUrl,
                        episodeNum = 1,
                        site = name
                    )
                )
            }

            return ShowDetails(show = show, episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "video.mp4" },
            headers = mapOf("Referer" to "$mainUrl/"),
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
