package com.anonrode.downloader.providers

import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

object DramaRainProvider : SiteProvider {
    override val name: String = "dramarain"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) return results
        }
        // OTA search-strategy chain runs first when the playbook declares
        // one (dramarain's ?s= endpoint broke server-side; fallbacks are now
        // OTA data, not code). Legacy path below stays as final fallback.
        SearchStrategyRunner.run(name, query, mainUrl)?.let { return it }
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encoded"
            val html = HttpClient.getText(searchUrl, tag = "search")
            if (!html.isNullOrBlank()) {
                val doc = Jsoup.parse(html, searchUrl)
                val articles = doc.select("article, .post-item, .entry-title a, h2.entry-title a")
                for (item in articles) {
                    val a = if (item.tagName() == "a") item else item.selectFirst("h2 a, .entry-title a, a[href]")
                    val title = a?.text()?.trim() ?: ""
                    val rawHref = a?.attr("href") ?: ""
                    val href = a?.attr("abs:href")?.ifBlank {
                        HttpClient.safeResolveUri(searchUrl, rawHref)
                    } ?: ""
                    val img = item.selectFirst("img")?.let {
                        it.attr("abs:src").ifBlank { it.attr("src") }
                    } ?: ""

                    if (href.isNotBlank() && title.isNotBlank() && results.none { it.url == href }) {
                        results.add(
                            ShowCard(
                                title = title,
                                url = href,
                                posterUrl = img,
                                site = name,
                                category = "Asian Drama"
                            )
                        )
                    }
                }
            }

            if (results.isEmpty()) {
                val slug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")
                val cfg = DynamicRulesManager.getSiteConfig(name)
                val suffixes = if (!cfg?.slugSuffixes.isNullOrEmpty()) cfg!!.slugSuffixes else listOf(
                    "",
                    "-chinese-drama",
                    "-thai-drama",
                    "-japanese-drama",
                    "-philippines-drama",
                    "-korean-drama",
                    "-season-1"
                )
                // trimEnd('/') avoids the double-slash collision without
                // touching the scheme — the old .replace("//","/") mangled
                // https:// -> https:/ (seen in the 2026-08-22 activity log).
                val base = mainUrl.trimEnd('/')
                val candidateUrls = suffixes.map { "$base/$slug$it/" } + listOf("$base/drama/$slug/")

                // 8 slug candidates per miss, each its own page. The old
                // loop fetched them SEQUENTIALLY with getText — a dead or
                // slow host blocks on the shared client's connect/read
                // timeouts, and the caller's withTimeoutOrNull cannot
                // interrupt a blocking execute(), so one query could pin
                // its search worker for tens of seconds (a "search is
                // slow" report contributor). Two-stage instead: parallel
                // 3s liveness probes (call-timeout bounded; a 404 costs
                // one round trip and no body), then page fetches only for
                // candidates that are alive — in list priority order.
                val alive = coroutineScope {
                    candidateUrls.map { url ->
                        async(Dispatchers.IO) {
                            if (HttpClient.probe(url, referer = "$mainUrl/", timeoutMs = 3_000L, tag = "search")) url else null
                        }
                    }.awaitAll().filterNotNull().toSet()
                }
                for (url in candidateUrls.filter { it in alive }) {
                    val directHtml = HttpClient.getText(url, tag = "search") ?: continue
                    val doc = Jsoup.parse(directHtml, url)
                    val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: continue
                    val poster = doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src") ?: ""

                    results.add(
                        ShowCard(
                            title = title,
                            url = url,
                            posterUrl = poster,
                            site = name,
                            category = "Asian Drama"
                        )
                    )
                    break
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Drama", url = showUrl, site = name)
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
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Drama"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            // Content-rooted sweep with the SAME host/shape allowlist the
            // loose union used — the old `.entry-content a` catch-all made
            // every synopsis/trailer link an "episode".
            val entryRoot = doc.selectFirst(".entry-content") ?: doc.body()
            val links = entryRoot.select("a[href]").filter { cand ->
                val h = cand.attr("href").lowercase()
                h.contains("download") || h.contains("episode") || h.contains("loadedfiles") ||
                    h.contains("waffi") || h.contains(".mkv") || h.contains(".mp4")
            }

            var count = 1
            for (a in links) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                val text = a.text().trim()
                if (href.isNotBlank() && href !in seen && !com.anonrode.downloader.pipeline.StrictLinkClassifier.isNavigationJunk(href)) {
                    seen.add(href)
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank() && !text.equals("Download", ignoreCase = true)) text
                                else DownloadLinkLabels.serverOrPart(text, href.substringAfterLast('/')) ?: "Episode $count",
                            url = href,
                            episodeNum = count++,
                            site = name
                        )
                    )
                }
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        val direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: ResolverRegistry.resolve(episodeUrl, quality)
        val target = if (!direct.isNullOrBlank()) direct else {
            if (com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(episodeUrl)) episodeUrl else ""
        }
        val isHls = target.contains(".m3u8") || target.contains("manifest")
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
