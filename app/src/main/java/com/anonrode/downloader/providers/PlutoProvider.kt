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

object PlutoProvider : SiteProvider {
    override val name: String = "pluto"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val clean = query.replace("'", "").replace("’", "").trim()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val cfg = DynamicRulesManager.getSiteConfig(name)
            val searchPattern = cfg?.searchPattern?.ifBlank { null } ?: "/search/{query}/page/1"
            val cardSelector = cfg?.cardSelector?.ifBlank { null } ?: "a[href*='/movie/'], a[href*='/series/']"
            val url = if (searchPattern.startsWith("http")) {
                searchPattern.replace("{base}", mainUrl.trimEnd('/')).replace("{query}", encoded)
            } else {
                "$mainUrl" + searchPattern.replace("{query}", encoded)
            }
            val html = HttpClient.getText(url, referer = "$mainUrl/", tag = "search") ?: return emptyList()
            val doc = Jsoup.parse(html, url)

            val seen = mutableSetOf<String>()
            for (a in doc.select(cardSelector)) {
                val href = (a.attr("abs:href").ifEmpty { a.attr("href") }).substringBefore('#')
                val title = a.attr("title").ifEmpty { a.text() }.trim()
                if (href.isBlank() || title.isBlank() || href in seen) continue
                seen.add(href)

                val isSeries = href.contains("/series/")
                val img = a.selectFirst("img")
                val poster = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") } ?: ""

                results.add(
                    ShowCard(
                        title = title,
                        url = href,
                        posterUrl = poster,
                        site = name,
                        category = if (isSeries) "TV Series" else "Movie"
                    )
                )
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Pluto Title", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl, referer = "$mainUrl/")
            if (html.isNullOrBlank()) {
                com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes: fetch returned null for $showUrl (lastFailure=${HttpClient.lastFailure})")
                return ShowDetails(show = show)
            }
            if (looksLikeSecurityChallenge(html)) {
                com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes: site returned a security challenge (Cloudflare) for $showUrl")
                return ShowDetails(show = show)
            }
            com.anonrode.downloader.util.DebugLog.resolve("pluto loadEpisodes: ${html.length / 1024}KiB from $showUrl")
            val doc = Jsoup.parse(html, showUrl)
            val episodes = mutableListOf<EpisodeItem>()

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".poster img, .cover img")?.attr("abs:src") ?: ""
            val title = doc.selectFirst("h1")?.text()?.trim() ?: "Pluto Video"
            val cfg = DynamicRulesManager.getSiteConfig(name)

            if (showUrl.contains("/series/") || showUrl.contains("/season")) {
                // Live structure (verified 2026-08-27): every page — season hub
                // or single episode — gets its OWN numeric /series/<id>/ URL,
                // so siblings can never be matched by id. Search links even
                // carry site-truncated slugs (.../sofia-the-first-royal-magic-s0,
                // ~30-char cap), so slug matching must be prefix-tolerant.
                // Episode slugs come in both -s01-e01 and -s01e01 shapes.
                val showSlug = showUrl.substringAfterLast('/').substringBefore('?')
                val stem = slugStem(showSlug)
                val seasonLinkRegex = Regex("""-season-\d+""", RegexOption.IGNORE_CASE)

                // Collect this show's season hubs and pagination pages.
                val pagesToScan = mutableListOf(showUrl)
                for (a in doc.select("a[href]")) {
                    val href = a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, a.attr("href")) }
                    if (href.isBlank() || href == showUrl || href in pagesToScan) continue
                    if (seasonLinkRegex.containsMatchIn(href) &&
                        sameSlugStem(stem, slugStem(href.substringAfterLast('/').substringBefore('?')))
                    ) {
                        pagesToScan.add(href)
                    } else if (href.contains("/page/") && href.startsWith(showUrl.substringBefore('?'))) {
                        pagesToScan.add(href)
                    }
                }

                val seenUrls = mutableSetOf<String>()
                val seenEpKeys = mutableSetOf<String>()
                fun addEpisode(href: String, epTitle: String, epNum: Int, key: String) {
                    if (href.isBlank() || href in seenUrls || key in seenEpKeys) return
                    seenUrls.add(href)
                    seenEpKeys.add(key)
                    episodes.add(
                        EpisodeItem(
                            title = epTitle.ifBlank { "Episode $epNum" },
                            url = href,
                            episodeNum = epNum,
                            site = name
                        )
                    )
                }

                // 1. When the page itself is an episode (search lands on
                //    episode pages directly), list it. The dl anchor's
                //    filename carries the full slug, revealing the true
                //    number even when the page URL's slug is truncated.
                val dlSelector = cfg?.downloadAnchorSelector?.ifBlank { null } ?: "a[href*='dl.plutomovies.com']"
                val dlHref = doc.selectFirst(dlSelector)?.let {
                    it.attr("abs:href").ifBlank { it.attr("href") }
                }?.substringBefore('#') ?: ""
                val selfKey = parseEpisodeKey(dlHref) ?: parseEpisodeKey(showUrl)
                val isEpisodePage = dlHref.isNotBlank() || EP_SLUG_REGEX.containsMatchIn(showUrl)
                if (isEpisodePage) {
                    val selfTitle = title
                        .replace(Regex("""(?i)\s*download\s*(mp4|mkv|hd)?\s*$"""), "")
                        .trim()
                    val epNum = selfKey?.second ?: 1
                    addEpisode(showUrl, selfTitle, epNum, selfKey?.let { "s${it.first}e${it.second}" } ?: showUrl)
                }

                // 2. Previous/next navigation on episode pages. Those links
                //    carry truncated slugs under unique ids, so number them
                //    arithmetically from the current episode instead.
                if (isEpisodePage && selfKey != null) {
                    for (a in doc.select(".previous_and_next_post a[href]")) {
                        val href = a.attr("abs:href")
                            .ifBlank { HttpClient.safeResolveUri(showUrl, a.attr("href")) }
                            .substringBefore('#')
                        if (href.isBlank() || href == showUrl || !href.contains("/series/")) continue
                        val cls = a.className().lowercase()
                        val delta = when {
                            "left" in cls || "prev" in cls -> -1
                            "right" in cls || "next" in cls -> 1
                            else -> 0
                        }
                        if (delta == 0) continue
                        val num = selfKey.second + delta
                        if (num < 1) continue
                        val label = a.text().trim()
                            .replace(Regex("""(?i)^(previous|next)\s+episode\b\s*"""), "")
                            .trim()
                        addEpisode(href, label, num, "s${selfKey.first}e$num")
                    }
                }

                // 3. Episode links across this show's pages: season hubs list
                //    every episode, pagination continues the list. The stem
                //    filter rejects the unrelated-show sidebar links that also
                //    use /series/ URLs.
                for (pageUrl in pagesToScan.distinct()) {
                    val pageDoc = if (pageUrl == showUrl) doc else {
                        val pageHtml = HttpClient.getText(pageUrl, referer = "$mainUrl/") ?: continue
                        Jsoup.parse(pageHtml, pageUrl)
                    }
                    for (a in pageDoc.select("a[href]")) {
                        val href = a.attr("abs:href").ifBlank {
                            HttpClient.safeResolveUri(pageUrl, a.attr("href"))
                        }.substringBefore('#')
                        if (href.isBlank() || href == pageUrl || seasonLinkRegex.containsMatchIn(href)) continue
                        val key = parseEpisodeKey(href)
                        if (key == null && !href.contains("/episodes/")) continue
                        if (!sameSlugStem(stem, slugStem(href.substringAfterLast('/').substringBefore('?')))) continue
                        val label = a.text().trim()
                            .replace(Regex("""(?i)^(previous|next)\s+episode\b\s*"""), "")
                            .trim()
                        addEpisode(
                            href,
                            label,
                            key?.second ?: (episodes.size + 1),
                            key?.let { "s${it.first}e${it.second}" } ?: href
                        )
                    }
                }

                episodes.sortBy { it.episodeNum }
            } else {
                // Movie download link from detail page
                val dlSelector = cfg?.downloadAnchorSelector?.ifBlank { null } ?: "a[href*='dl.plutomovies.com']"
                val dlLink = doc.selectFirst(dlSelector)?.let {
                    it.attr("abs:href").ifBlank { it.attr("href") }
                } ?: showUrl
                episodes.add(
                    EpisodeItem(
                        title = "Full Movie",
                        url = dlLink,
                        episodeNum = 1,
                        site = name
                    )
                )
            }

            com.anonrode.downloader.util.DebugLog.resolve("pluto loadEpisodes: found ${episodes.size} episodes")
            return ShowDetails(show = show.copy(title = title, posterUrl = poster), episodes = episodes)
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.error("pluto loadEpisodes exception: ${e.javaClass.simpleName}: ${e.message}")
            return ShowDetails(show = show)
        }
    }

    /** Episode slug pattern: both -s01-e01 (dash) and -s01e01 (compact) occur live. */
    private val EP_SLUG_REGEX = Regex("""-s\d{1,2}[-_]?e\d{1,2}""", RegexOption.IGNORE_CASE)

    /** (season, episode) parsed from a slug or URL, tolerant of both slug shapes. */
    private fun parseEpisodeKey(href: String): Pair<Int, Int>? {
        val m = Regex("""-s(\d{1,2})[-_]?e(\d{1,2})""", RegexOption.IGNORE_CASE).find(href) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return s to e
    }

    /**
     * Comparable show-stem of a slug: season/episode suffixes stripped, plus
     * the dangling fragments Pluto's site-wide ~30-char slug truncation
     * leaves behind ("-s0", "-se", "-s"). Applied to both sides of a
     * comparison so truncation cancels out.
     */
    internal fun slugStem(slug: String): String = slug
        .replace(Regex("""-season-\d+.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""-s\d{1,2}[-_]?e\d{1,2}.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""-s\d{0,2}[-_]?e?\d{0,2}$""", RegexOption.IGNORE_CASE), "")
        .lowercase()
        .trim('-', '_')

    /**
     * Prefix-tolerant stem comparison. Because of the slug truncation one
     * stem is often a prefix of the other ("…royal-magic-s0" ⊂ "…royal-magic"),
     * and mid-word cuts rule out a dash-boundary requirement. Stems too
     * short to judge trust the page they came from. Recall over precision:
     * a rare franchise over-match merely lists an extra episode, while a
     * false rejection reproduces the "no episodes" bug.
     */
    internal fun sameSlugStem(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4) return true
        if (a == b) return true
        val long = if (a.length >= b.length) a else b
        val short = if (a.length >= b.length) b else a
        return long.startsWith(short)
    }

    /** Cloudflare/JS-challenge pages answer HTTP 200 with no real content. */
    private fun looksLikeSecurityChallenge(html: String): Boolean {
        val low = html.lowercase()
        return low.contains("just a moment") || low.contains("cf-challenge") ||
            low.contains("challenge-platform") || (low.contains("cloudflare") && low.contains("verify"))
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
