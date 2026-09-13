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
import java.net.URLDecoder
import java.net.URLEncoder

object NkiriProvider : SiteProvider {
    override val name: String = "nkiri"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        // OTA pipeline first: a signed playbook can rewrite this site's search
        // (selectors, mirrors, filters) without an app update. Non-empty wins;
        // anything less falls through to the compiled path below untouched.
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) return results
        }

        val results = mutableListOf<ShowCard>()
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Failover: the original IP (80.82.65.46) is ISP-blocked on some
        // networks while the Cloudflare mirror works — try each host in order
        // (live-verified 2026-08-22: thenkiri.com full catalog, nkiri.top
        // partial but reachable everywhere).
        for (base in DynamicRulesManager.getBaseUrls(name)) {
            if (base.isBlank()) continue
            try {
                val searchUrl = "$base/?s=$encoded"
                val html = HttpClient.getText(searchUrl, referer = "$base/", tag = "search")
                if (!html.isNullOrBlank()) {
                    val doc = Jsoup.parse(html, searchUrl)
                    val articles = doc.select("article, .post-item, .elementor-post, h2.entry-title a")

                    for (art in articles) {
                        val linkElem = if (art.tagName() == "a") art else art.selectFirst("h2 a, .entry-title a, a")
                        if (linkElem == null) continue
                        val title = linkElem.text().trim()
                        val rawLink = linkElem.attr("abs:href").ifBlank { linkElem.attr("href") }
                        val link = rawLink.substringBefore("?")

                        if (link.isBlank() || title.isBlank() || link.contains("/category/") || link.contains("/how-to-") || link.contains("/page/")) {
                            continue
                        }

                        val posterElem = art.selectFirst("img")
                        val poster = posterElem?.attr("abs:src")?.ifBlank { posterElem.attr("src") } ?: ""

                        val lowerTitle = title.lowercase()
                        val cat = when {
                            lowerTitle.contains("korean") || lowerTitle.contains("kdrama") || lowerTitle.contains("c-drama") || lowerTitle.contains("drama") || lowerTitle.contains("series") || lowerTitle.contains("season") -> "Asian Drama"
                            lowerTitle.contains("nollywood") || lowerTitle.contains("yoruba") -> "Nollywood"
                            else -> "Asian Drama & Movies"
                        }

                        if (results.none { it.url == link }) {
                            results.add(
                                ShowCard(
                                    title = title,
                                    url = link,
                                    posterUrl = poster,
                                    site = name,
                                    category = cat
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
            // A host that answered with content is authoritative — the mirror
            // only kicks in when the primary is unreachable or empty.
            if (results.isNotEmpty() || base == DynamicRulesManager.getBaseUrls(name).last()) {
                break
            }
        }
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val cleanUrl = showUrl.substringBefore("?")

        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, cleanUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.metaTitle ?: "NKiri Show",
                    url = cleanUrl,
                    posterUrl = res.metaPoster ?: "",
                    site = name
                )
                return ShowDetails(show = card, synopsis = res.metaSynopsis ?: "", episodes = res.episodes)
            }
        }

        val show = ShowCard(title = "NKiri Show", url = cleanUrl, site = name)
        try {
            val html = HttpClient.getText(cleanUrl, referer = "$mainUrl/") ?: return ShowDetails(show = show)
            val doc = Jsoup.parse(html, cleanUrl)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "NKiri Show"
            val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, meta[property=og:image]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p, .elementor-widget-theme-post-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val allLinks = doc.select("a[href]")

            var count = 1
            for (a in allLinks) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(cleanUrl, rawHref)
                }
                val lowerHref = href.lowercase()

                if (href.isBlank() || href in seen) continue
                if (lowerHref.contains("error?e=") || lowerHref.contains("errore=") || lowerHref.contains("telegram") || lowerHref.contains("facebook") || lowerHref.contains("twitter") || lowerHref.contains("whatsapp") || lowerHref.contains("how-to") || lowerHref.contains("cant-download")) {
                    continue
                }

                val isLocker = lowerHref.contains("downloadwella.com") ||
                        lowerHref.contains("wetafiles.com") ||
                        lowerHref.contains("loadedfiles") ||
                        lowerHref.contains("nkiserv.com") ||
                        lowerHref.contains("vikingfile") ||
                        lowerHref.contains("lulacloud") ||
                        lowerHref.contains("waffi")

                if (isLocker) {
                    seen.add(href)
                    val text = a.text().trim()
                    val parent = a.parent()
                    val prevHeading = parent?.previousElementSibling()?.let { elem ->
                        if (elem.tagName().startsWith("h", ignoreCase = true) || elem.tagName() == "p") elem.text().trim() else null
                    }

                    // Number from the anchor's own evidence before falling back
                    // to the running counter: a filename token (…E17., S01E19…)
                    // or the heading above the button is the episode's real
                    // number, while the counter drifts as soon as any extra
                    // locker link (ad, season pack) precedes it. Combined
                    // "Episode 17 & 18" posts expand to one item per episode,
                    // each reusing the one anchor that exists in the HTML.
                    val headingNums = RulesPipeline.headingEpisodeNumbers(prevHeading)
                    val fileNums = RulesPipeline.filenameEpisodeNums(href)
                    val nums = when {
                        headingNums.size > 1 -> headingNums
                        fileNums.isNotEmpty() -> fileNums
                        else -> headingNums
                    }

                    if (nums.size > 1) {
                        for (n in nums) {
                            episodes.add(
                                EpisodeItem(
                                    title = "Episode $n",
                                    url = href,
                                    episodeNum = n,
                                    site = name
                                )
                            )
                        }
                        count = nums.last() + 1
                    } else {
                        val num = nums.firstOrNull() ?: count
                        val epTitle = when {
                            !prevHeading.isNullOrBlank() && prevHeading.contains("Episode", ignoreCase = true) -> prevHeading
                            text.isNotBlank() && text.length < 40 && !text.equals("Download Episode", ignoreCase = true) && !text.equals("Download Movie", ignoreCase = true) && !text.equals("Download", ignoreCase = true) -> text
                            else -> "Episode $num"
                        }

                        episodes.add(
                            EpisodeItem(
                                title = epTitle,
                                url = href,
                                episodeNum = num,
                                site = name
                            )
                        )
                        count = num + 1
                    }
                }
            }

            val card = ShowCard(title = title, url = cleanUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        // nkiri's download-manager buttons now point at /dl/download-<id>/?redirect=
        // <locker> wrapper pages that answer 404 site-side (live-verified
        // 2026-09-11: bare path, no-slash, raw and encoded ?redirect=, ?url= —
        // every variant 404s, with post referer and cookies set). The locker
        // URL is right there in the redirect= parameter: decode it and resolve
        // THAT. The sweep already stores these hrefs (the encoded query
        // contains the locker host, so canResolve matched by substring) — the
        // resolver just never reached the target behind the dead wrapper.
        val effective = unwrapDownloadManagerRedirect(episodeUrl)
        // OTA resolve recipe (a playbook can carry the wrapper-unwrap + locker
        // crack) wins when present; compiled registry otherwise.
        val direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: ResolverRegistry.resolve(effective, quality) ?: effective
        val isSingleSocket = effective.contains("nkiserv.com") || episodeUrl.contains("nkiserv.com") || direct.contains(".m3u8")
        val isHls = direct.contains(".m3u8") || direct.contains("manifest")

        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mkv" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }

    /** Unwraps the nkiri download-manager wrapper:
     *  `.../dl/download-17827/?redirect=https%3A%2F%2Fdownloadwella.com%2F...`
     *  → `https://downloadwella.com/...`. Returns the input unchanged when the
     *  parameter is absent or the decoded target isn't an http URL. */
    private fun unwrapDownloadManagerRedirect(url: String): String {
        val m = Regex("""/dl/[^?]*\?.*?[?&]redirect=([^&]+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return url
        val target = try {
            URLDecoder.decode(m.groupValues[1], "UTF-8")
        } catch (_: Exception) {
            null
        }
        return if (!target.isNullOrBlank() && target.startsWith("http", ignoreCase = true)) target else url
    }

    /** S02E05 season episode numbers from locker filenames. Private mirrors of
     *  RulesPipeline's number patterns; see [RulesPipeline.filenameEpisodeNums]. */
    private val SEASON_EP_REGEX = Regex("""(?i)\bs(\d+)e(\d+)\b""")
    private val EP_TOKEN_REGEX = Regex("""(?i)(?<![a-z0-9])e(?:p)?[\s._-]*(\d{1,4})(?![a-z0-9])""")

    /** Numbers an episode heading declares: the bare number, "E5", "Episode 5",
     *  or a combined "Episode 17 & 18" / "Episodes 17-18" (all of them). */
    internal fun headingEpisodeNumbers(heading: String?): List<Int> {
        val h = heading?.trim() ?: return emptyList()
        if (h.isBlank()) return emptyList()
        // Normalize separators so "17 & 18", "17-18" and "17, 18" share a path.
        val parts = h.replace("&", ",").replace("-", ",").split(",", " to ", " To ")
        val headNum = Regex("""(?i)episode?s?\s*(\d{1,4})""").find(h)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)(?<![a-z0-9])e(?:p)?[\s._-]*(\d{1,4})(?![a-z0-9])""").find(h)?.groupValues?.get(1)?.toIntOrNull()
        if (headNum == null) return emptyList()
        val out = mutableListOf(headNum)
        for (part in parts.drop(1)) {
            val n = Regex("""(?<!\d)(\d{1,4})(?!\d)""").find(part)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n > headNum && n <= headNum + 30) out.add(n)
        }
        return out.distinct()
    }

    /** Numbers a locker URL itself carries: "Alchemy.of.Souls.E17.(NKIRI.COM).mkv"
     *  → [17]; "Show.S01E19.NKIRI.COM.mkv" → [19] (season-1 only — season 2+
     *  filenames use a numbering space the episode page does not identify). */
    internal fun filenameEpisodeNums(url: String): List<Int> {
        val stem = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val seasonEp = SEASON_EP_REGEX.find(stem)
        if (seasonEp != null) {
            return if (seasonEp.groupValues[1].toIntOrNull() == 1) {
                listOfNotNull(seasonEp.groupValues[2].toIntOrNull())
            } else {
                emptyList()
            }
        }
        return EP_TOKEN_REGEX.findAll(stem)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..2000 }
            .distinct()
            .toList()
    }
}
