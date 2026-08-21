package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

object NaijaPreyProvider : SiteProvider {
    override val name: String = "naijaprey"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val rssUrl = "$mainUrl/search/$encoded/feed/rss2/"
            val xml = HttpClient.getText(rssUrl, tag = "search") ?: return emptyList()

            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            for (item in doc.select("item")) {
                val title = item.selectFirst("title")?.text()?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                val link = item.selectFirst("link")?.text()?.trim() ?: ""

                val desc = item.selectFirst("content|encoded")?.text() ?: item.selectFirst("description")?.text() ?: ""
                val poster = Regex("""<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(desc)?.groupValues?.get(1) ?: ""

                if (title.isNotBlank() && link.isNotBlank()) {
                    // Category-page posts (/download-movies-xxx/) have no
                    // extractable media links — the RSS feed returns them as
                    // search results, but loadEpisodes filters them out and a
                    // task would fail with "resolver chain EMPTY" (user-
                    // reported in app-2026-08-6.txt: f3e46101, 2ef70fcd,
                    // 4dce7e87 all failed on /download-movies-vxi/).
                    if (Regex("""/download-(?:movies|series|tv|film|episode)""", RegexOption.IGNORE_CASE).containsMatchIn(link)) continue
                    results.add(
                        ShowCard(
                            title = title,
                            url = link,
                            posterUrl = poster,
                            site = name,
                            category = "Nollywood & Series"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "NaijaPrey Media", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl) ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "NaijaPrey Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src")
                ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val links = doc.select("a[href*='download'], a.elementor-button, .entry-content a")

            // Nav garbage the a[href*='download'] selector keeps catching:
            // "Download Movies" (/download-movies-xxx/), "Series Download"
            // (/series-download/), "Downloader", and how-to posts. Each became
            // a doomed task (live log: 8 failures in one browsing session).
            val navHref = Regex(
                """/download-(?:movies|series|tv|film|episode)|/series-download(?:-v\d+)?(?:$|[/?#])|/downloader(?:$|[/?#])|/how-to-download""",
                RegexOption.IGNORE_CASE
            )
            val navText = Regex("""(?i)^\s*(download\s+(movies|series|tv|films?|episodes?)|all\s+downloads?|series\s+downloads?|how\s+to\s+download.*)\s*$""")

            var count = 1
            for (a in links) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                val text = a.text().trim()
                if (href.isNotBlank() && href !in seen &&
                    !href.contains("/category/") && !href.contains("/tag/") &&
                    !navHref.containsMatchIn(href) && !navText.matches(text) &&
                    !href.equals(showUrl, ignoreCase = true)
                ) {
                    seen.add(href)
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank() && !text.equals("Download", ignoreCase = true)) text else "Download $count",
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
        var direct = ResolverRegistry.resolve(episodeUrl, quality)

        // ResolverRegistry has no handler for the vdl.np-downloader.com/
        // sdm_downloads gateway (a bare WordPress Simple Download Monitor
        // post), so a miss — or a non-media answer — would leave us handing
        // back a raw HTML page as the "direct" URL. Chase the embedded chain
        // ourselves instead: page -> a.sdm_download/wildshare hop -> media.
        if (direct.isNullOrBlank() ||
            (!direct.endsWith(".mkv") && !direct.endsWith(".mp4") &&
                !direct.contains("/d/") && !direct.contains("token="))
        ) {
            direct = extractFileLink(episodeUrl, 0)
        }

        // Empty = resolution failure: the engine treats blank as a failed
        // resolve and fails cleanly. NEVER return the raw page URL as
        // directUrl — that "downloads" the HTML page itself.
        val finalUrl = direct ?: ""

        return DownloadRecipe(
            directUrl = finalUrl,
            filename = finalUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "media.mp4" },
            backend = "aria2c",
            parallelSockets = 16
        )
    }

    // Follows the naijaprey download chain when the resolver registry comes up
    // empty: the page embeds a vdl.np-downloader.com/sdm_downloads link (its
    // post body holds a single a.sdm_download anchor pointing at wildshare.net,
    // which serves the direct media file). Depth-capped at 2 => at most
    // 2 page fetches (data discipline).
    private fun extractFileLink(url: String, depth: Int): String? {
        if (depth >= 2) return null
        val html = HttpClient.getText(url) ?: return null

        // 1) A ready direct media URL anywhere in the markup wins immediately.
        Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|avi|webm)[^\s"'<>]*""")
            .find(html)?.value?.let { return it }

        // 2) Otherwise hop to the next stage: the sdm_download anchor's href,
        //    or any vdl/wildshare URL embedded in the page.
        val doc = Jsoup.parse(html, url)
        val next = doc.selectFirst("a.sdm_download")?.attr("abs:href")?.takeIf { it.isNotBlank() }
            ?: Regex("""https://(?:vdl\.np-downloader\.com/sdm_downloads/|wildshare\.net/)[^\s"'<>]*""")
                .find(html)?.value
            ?: return null

        if (next == url) return null
        return extractFileLink(next, depth + 1)
    }
}
