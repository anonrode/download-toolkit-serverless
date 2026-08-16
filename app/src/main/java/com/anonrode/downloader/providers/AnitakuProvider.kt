package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

object AnitakuProvider : SiteProvider {
    override val siteName: String = "anitaku"
    override val baseUrl: String = "https://gogoanime.or.at"

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        val endpoints = listOf(
            "https://gogoanime.or.at/wp-admin/admin-ajax.php" to "https://gogoanime.or.at/",
            "https://anitaku.com.ro/wp-admin/admin-ajax.php" to "https://anitaku.com.ro/"
        )

        val cleanQuery = query.replace(Regex("""(?i)\b(season|series|part|s\d+)\s*\d*\b"""), "").trim()
        val queryToUse = if (cleanQuery.length >= 2) cleanQuery else query

        for ((ajaxUrl, referer) in endpoints) {
            try {
                val form = FormBody.Builder()
                    .add("action", "ts_ac_do_search")
                    .add("ts_ac_query", queryToUse)
                    .build()

                val req = Request.Builder()
                    .url(ajaxUrl)
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .header("Referer", referer)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form)
                    .build()

                HttpClient.shared.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val body = res.body?.string() ?: return@use
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
                                val title = item.optString("post_title")
                                val image = item.optString("post_image")
                                val sub = item.optString("post_sub")

                                if (link.isNotBlank() && title.isNotBlank()) {
                                    results.add(
                                        ShowCard(
                                            title = title,
                                            posterUrl = image,
                                            detailUrl = link,
                                            site = siteName,
                                            category = "Anime ${if (sub.isNotBlank()) "($sub)" else ""}"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return results
    }

    override suspend fun loadEpisodes(detailUrl: String): ShowDetails? {
        try {
            val html = HttpClient.getText(detailUrl) ?: return null
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst(".anime_info_body_bg h1, h1.entry-title")?.text()?.trim() ?: "Anime"
            val poster = doc.selectFirst(".anime_info_body_bg img, .thumb img")?.attr("abs:src") ?: ""
            val synopsis = doc.selectFirst(".description, .entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val epLinks = doc.select("#episode_related a, .episodes a, ul.episodes-lists li a")

            for (a in epLinks) {
                val href = a.attr("abs:href")
                val name = a.text().trim()
                val num = Regex("""\d+""").find(name)?.value?.toIntOrNull() ?: (episodes.size + 1)
                if (href.isNotBlank()) {
                    episodes.add(
                        EpisodeItem(
                            title = "Episode $num",
                            episodeNum = num,
                            downloadPageUrl = href,
                            isDirect = false
                        )
                    )
                }
            }

            return ShowDetails(
                title = title,
                posterUrl = poster,
                synopsis = synopsis,
                episodes = episodes.sortedBy { it.episodeNum },
                site = siteName
            )
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun resolveEpisode(episode: EpisodeItem): String? {
        try {
            val html = HttpClient.getText(episode.downloadPageUrl) ?: return null
            val doc = Jsoup.parse(html)
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("abs:src")
                val direct = ResolverRegistry.resolve(src)
                if (!direct.isNullOrBlank()) return direct
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }
}
