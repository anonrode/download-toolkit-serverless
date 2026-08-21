package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.util.regex.Pattern

object AnitakuProvider : SiteProvider {
    override val name: String = "anitaku"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> = coroutineScope {
        val endpoints = listOf(
            "https://gogoanime.or.at/wp-admin/admin-ajax.php" to "https://gogoanime.or.at/",
            "https://anitaku.com.ro/wp-admin/admin-ajax.php" to "https://anitaku.com.ro/"
        )

        val cleanQuery = query.replace(Regex("""(?i) (season|series|part|s\d+)\s*\d* """), "").trim()
        val queryToUse = if (cleanQuery.length >= 2) cleanQuery else query

        val deferreds = endpoints.map { (ajaxUrl, referer) ->
            async(Dispatchers.IO) {
                val batch = mutableListOf<ShowCard>()
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
                                        batch.add(
                                            ShowCard(
                                                title = title,
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
                    }
                } catch (_: Exception) {}
                batch
            }
        }

        val allResults = deferreds.awaitAll().flatten()
        allResults.distinctBy { it.url }
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Anime", url = showUrl, site = name)
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
            val epLinks = doc.select(".bixbox.bxcl.epcheck a, .eplister ul li a, #episode_related a, .episodes a, ul.episodes-lists li a, a[href*='episode']")

            for (a in epLinks) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                if (href.isBlank() || href in seen || href.contains("/category/") || href.contains("/genre/")) continue
                seen.add(href)

                val nameText = a.text().trim()
                val num = Regex("""\d+""").find(nameText)?.value?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)

                episodes.add(
                    EpisodeItem(
                        title = "Episode $num",
                        url = href,
                        episodeNum = num,
                        site = name
                    )
                )
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedBy { it.episodeNum })
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        var direct: String? = null

        try {
            val html = HttpClient.getText(episodeUrl, referer = "https://gogoanime.or.at/") ?: ""
            
            // 0. Check Gogoanime direct download links API
            val malMatch = Pattern.compile("""malId\s*=\s*['"](\d+)['"]""").matcher(html)
            val epMatch = Pattern.compile("""ep\s*=\s*['"](\d+)['"]""").matcher(html)
            if (malMatch.find() && epMatch.find()) {
                val malId = malMatch.group(1) ?: ""
                val ep = epMatch.group(1) ?: ""
                val host = HttpClient.safeHost(episodeUrl, "gogoanime.or.at")
                val ajaxUrl = "https://$host/wp-admin/admin-ajax.php"

                val form = FormBody.Builder()
                    .add("action", "fetch_download_links")
                    .add("mal_id", malId)
                    .add("ep", ep)
                    .build()

                val req = Request.Builder()
                    .url(ajaxUrl)
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .header("Referer", episodeUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form)
                    .build()

                HttpClient.shared.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val dlHtml = JSONObject(body).optJSONObject("data")?.optString("result") ?: ""
                        if (dlHtml.isNotBlank()) {
                            val dlDoc = Jsoup.parse(dlHtml, episodeUrl)
                            for (a in dlDoc.select("a[href]")) {
                                val dlLink = a.attr("href")
                                val resolved = ResolverRegistry.resolve(dlLink, quality)
                                if (!resolved.isNullOrBlank()) {
                                    direct = resolved
                                    break
                                }
                            }
                        }
                    }
                }
            }

            if (direct.isNullOrBlank()) {
                val doc = Jsoup.parse(html, episodeUrl)
                val candidates = mutableListOf<String>()
                for (a in doc.select(".anime_muti_link a[data-video], .servers a[data-video], .anime_muti_link a[href]")) {
                    val dataVideo = a.attr("data-video").ifEmpty { a.attr("href") }
                    if (dataVideo.isNotBlank()) candidates.add(dataVideo)
                }
                for (iframe in doc.select("iframe[src]")) {
                    candidates.add(iframe.attr("src"))
                }

                for (cand in candidates) {
                    val src = HttpClient.safeResolveUri(episodeUrl, cand)
                    val resolved = ResolverRegistry.resolve(src, quality)
                    if (!resolved.isNullOrBlank()) {
                        direct = resolved
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        val target = direct ?: episodeUrl
        val isHls = target.contains(".m3u8") || target.contains("manifest")
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "anime.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
