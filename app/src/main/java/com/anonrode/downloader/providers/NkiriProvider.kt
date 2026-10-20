package com.anonrode.downloader.providers

import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.util.PostContentSanitizer
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
        if (!SiteGate.isEnabled(name)) return emptyList()
        // OTA pipeline first: a signed playbook can rewrite this site's search
        // (selectors, mirrors, filters) without an app update. Non-empty wins;
        // anything less falls through to the compiled path below untouched.
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) {
                // The rev-2/3 payload binds `attr:img:src` on an HTML search
                // page that carries no <img> at all (live-verified 2026-10-20:
                // 5 h2.entry-title anchors, 0 images) — every card shipped a
                // blank tile. Backfill from the compiled WP-REST extractor
                // that produced artwork before the pipeline took over.
                return backfillPosters(query, results)
            }
        }

        val results = mutableListOf<ShowCard>()
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Failover: the original IP (80.82.65.46) is ISP-blocked on some
        // networks while the Cloudflare mirror works — try each host in order
        // (live-verified 2026-08-22: thenkiri.com full catalog, nkiri.top
        // partial but reachable everywhere).
        //
        // REST FIRST (measured 2026-09-14): the HTML `?s=` page shows top posts
        // with no download link at all — 3 of 5 sampled cards were tap-dead
        // stubs (60%), and no body gate is possible there because the search
        // page never carries the post body. WP-REST search DOES carry it, so
        // the same DownloadLinkGate the other three sites run applies here for
        // free. `orderby=relevance` live-verified 2026-09-14 on nkiri.top
        // (200, bodies + featured media); date order stays for the discovery
        // feeds — a typed query wants its best match, not its newest post.
        val bases = DynamicRulesManager.getBaseUrls(name)
        for ((idx, base) in bases.withIndex()) {
            if (base.isBlank()) continue
            val cards = TrendingFeed.fetchWpRestFrom(
                base, name, query, REST_LIMIT,
                extraParams = "&orderby=relevance", tag = "search"
            ).map { it.copy(category = categoryForTitle(it.title)) }
            for (c in cards) if (results.none { it.url == c.url }) results.add(c)
            // A host that answered with content is authoritative — the mirror
            // only kicks in when the primary is unreachable or empty.
            if (results.isNotEmpty() || idx == bases.lastIndex) break
        }
        if (results.isNotEmpty()) return results

        // HTML-scrape fallback, reached only when REST failed on EVERY base.
        // These cards skip the body gate (the page has no bodies); the search
        // verify oracle confirms or silently keeps them (ResultVerifier).
        try {
            val searchUrl = "$mainUrl/?s=$encoded"
            val html = HttpClient.getText(searchUrl, referer = "$mainUrl/", tag = "search")
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

                    if (results.none { it.url == link }) {
                        results.add(
                            ShowCard(
                                title = title,
                                url = link,
                                posterUrl = poster,
                                site = name,
                                category = categoryForTitle(title)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return PosterBackfill.sanitize(results)
    }

    /**
     * Compiles the poster map once for the cards a pipeline answer left blank.
     * The REST search is the same request the compiled path already made
     * before the payload took over, so this restores prior behavior rather
     * than adding a new source.
     */
    private suspend fun backfillPosters(query: String, pipelineCards: List<ShowCard>): List<ShowCard> {
        return PosterBackfill.backfill(PosterBackfill.sanitize(pipelineCards)) { missing ->
            if (missing.isEmpty()) return@backfill emptyMap()
            val map = mutableMapOf<String, String>()
            for (base in DynamicRulesManager.getBaseUrls(name)) {
                if (base.isBlank()) continue
                val compiled = try {
                    TrendingFeed.fetchWpRestFrom(
                        base, name, query, REST_LIMIT,
                        extraParams = "&orderby=relevance", tag = "search"
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                for (c in compiled) {
                    if (c.posterUrl.isNotBlank()) {
                        map[PosterBackfill.normalizeKey(c.url)] = c.posterUrl
                    }
                }
                if (compiled.isNotEmpty()) break
            }
            map
        }
    }

    private const val REST_LIMIT = 15

    /** The site's own title conventions, unchanged from the HTML path. */
    internal fun categoryForTitle(title: String): String {
        val lowerTitle = title.lowercase()
        return when {
            lowerTitle.contains("korean") || lowerTitle.contains("kdrama") || lowerTitle.contains("c-drama") || lowerTitle.contains("drama") || lowerTitle.contains("series") || lowerTitle.contains("season") -> "Asian Drama"
            lowerTitle.contains("nollywood") || lowerTitle.contains("yoruba") -> "Nollywood"
            else -> "Asian Drama & Movies"
        }
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        if (!SiteGate.isEnabled(name)) return ShowDetails(show = ShowCard(title = "NKiri Show", url = showUrl, site = name))
        val cleanUrl = showUrl.substringBefore("?")

        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, cleanUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.metaTitle ?: "NKiri Show",
                    url = cleanUrl,
                    posterUrl = if (PosterBackfill.isUsablePoster(res.metaPoster.orEmpty())) res.metaPoster.orEmpty() else "",
                    site = name
                )
                return ShowDetails(show = card, synopsis = res.metaSynopsis ?: "", episodes = stampPipelineSeasons(res.episodes))
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
            val contentRoot = doc.selectFirst("article, .entry-content, .elementor-widget-theme-post-content") ?: doc.body() ?: doc
            PostContentSanitizer.clean(contentRoot)
            val allLinks = contentRoot.select("a[href]")

            // Slug season pin (canon: NaijaVault/NaijaPrey). thenkiri publishes
            // one post per season ("/adventure-time-side-quests-s02-complete-…",
            // live-verified 2026-10-15: 20 anchors, files S02E01..S02E20) and
            // each list renumbers from 1 — the pin puts season 2+ posts into
            // their own drawer chip; season-1/seasonless posts keep 1..N.
            val slugSeason = Regex("""(?i)(?:^|[.\-_ /])s(\d{1,2})(?!\d)""").find(cleanUrl)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?i)(?:^|[.\-_ /])season[-.]?(\d{1,2})(?!\d)""").find(cleanUrl)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(title)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

            // Page-level S>=2 token guard (RulesPipeline parity): SxxEyy
            // tokens only drive the season*100 key when every tokened anchor
            // names the SAME series prefix (one show, not a list page).
            val tokenPrefixes = HashSet<String>()
            for (a in allLinks) {
                val h = a.attr("abs:href").ifBlank { a.attr("href") }
                fileSeasonToken(h)?.takeIf { it.first >= 2 }?.let { tokenPrefixes.add(it.second) }
            }
            val allowTokenSeasons = tokenPrefixes.size <= 1

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
                if (PostContentSanitizer.isSameSitePostPermalink(href, "nkiri.top") || PostContentSanitizer.isSiblingPostAnchorText(a.text())) {
                    continue
                }

                val isLocker = lowerHref.contains("downloadwella.com") ||
                        lowerHref.contains("wetafiles.com") ||
                        lowerHref.contains("loadedfiles") ||
                        lowerHref.contains("nkiserv.com") ||
                        lowerHref.contains("vikingfile") ||
                        lowerHref.contains("lulacloud") ||
                        lowerHref.contains("waffi") ||
                        lowerHref.contains("kissorgrab") ||
                        lowerHref.contains("wildshare") ||
                        lowerHref.contains("pixeldrain") ||
                        com.anonrode.downloader.pipeline.StrictLinkClassifier.isKnownLocker(href) ||
                        com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(href)

                if (isLocker) {
                    seen.add(href)
                    val text = a.text().trim()
                    val parent = a.parent()
                    var prevHeading: String? = parent?.previousElementSibling()?.let { elem ->
                        if (elem.tagName().startsWith("h", ignoreCase = true) || elem.tagName() == "p") elem.text().trim() else null
                    }
                    if (prevHeading.isNullOrBlank()) {
                        var curr: org.jsoup.nodes.Element? = a
                        for (depth in 0..4) {
                            if (curr == null || curr == contentRoot) break
                            var sib = curr.previousElementSibling()
                            while (sib != null) {
                                val headingElem = if (sib.tagName().startsWith("h", ignoreCase = true) || sib.tagName() == "p") sib
                                    else sib.selectFirst("h1, h2, h3, h4, h5, h6, p, .elementor-heading-title")
                                if (headingElem != null) {
                                    val ht = headingElem.text().trim()
                                    if (ht.contains("Episode", ignoreCase = true) || Regex("""\b(?:Ep|E)[- ]*\d{1,4}\b""", RegexOption.IGNORE_CASE).containsMatchIn(ht)) {
                                        prevHeading = ht
                                        break
                                    }
                                }
                                sib = sib.previousElementSibling()
                            }
                            if (!prevHeading.isNullOrBlank()) break
                            curr = curr.parent()
                        }
                    }

                    // Number from the anchor's own evidence before falling back
                    // to the running counter: a filename token (…E17., S01E19…)
                    // or the heading above the button is the episode's real
                    // number, while the counter drifts as soon as any extra
                    // locker link (ad, season pack) precedes it. Combined
                    // "Episode 17 & 18" posts expand to one item per episode,
                    // each reusing the one anchor that exists in the HTML.
                    val headingNums = headingEpisodeNumbers(prevHeading)
                    val fileNums = filenameEpisodeNums(href)
                    val nums = when {
                        headingNums.size > 1 -> headingNums
                        fileNums.isNotEmpty() -> fileNums
                        else -> headingNums
                    }

                    if (nums.size > 1) {
                        for (n in nums) {
                            // Season identity is explicit now: the slug season
                            // (or season 1) + the declared episode number.
                            // episodeNum is only the collision-free identity
                            // key (SeasonEpisode.encode, 1000-base) — never a
                            // season*100 value a decoder could misread.
                            val seasonForRow = slugSeason?.takeIf { it >= 1 } ?: 1
                            val key = com.anonrode.downloader.util.SeasonEpisode.encode(seasonForRow, n)
                            val existingIdx = episodes.indexOfFirst { it.episodeNum == key }
                            if (existingIdx >= 0) {
                                val existing = episodes[existingIdx]
                                val isNewDirect = href.contains("nkiserv.com") || com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(href)
                                val isOldDirect = existing.url.contains("nkiserv.com") || com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(existing.url)
                                if (isNewDirect && !isOldDirect) {
                                    episodes[existingIdx] = existing.copy(url = href)
                                } else if (href != existing.url && href !in existing.mirrorUrls) {
                                    episodes[existingIdx] = existing.copy(mirrorUrls = existing.mirrorUrls + href)
                                }
                            } else {
                                episodes.add(
                                    EpisodeItem(
                                        title = "Episode $n",
                                        url = href,
                                        episodeNum = key,
                                        site = name,
                                        season = seasonForRow,
                                        episode = n
                                    )
                                )
                            }
                        }
                        count = nums.last() + 1
                    } else {
                        val mirrorLabel = DownloadLinkLabels.serverOrPart(
                            text, prevHeading, href.substringAfterLast('/').substringBefore('?')
                        )
                        // Numbering priority: a season token on the file itself
                        // (…S04E01… on the -s04- post, single-series prefix
                        // verified page-wide) decides; then the anchor's own
                        // evidence (filename E-token / heading); the positional
                        // counter is last — slug-pinned posts offset it into
                        // the season*100 space so season 2+ gets its own chip.
                        val filename = href.substringBefore('#').substringBefore('?').substringAfterLast('/')
                        val seasonEp = fileSeasonToken(href)?.takeIf { allowTokenSeasons && it.first in 2..99 }
                        val tokenE = seasonEp?.let {
                            SEASON_EP_REGEX.find(filename)?.groupValues?.getOrNull(2)?.toIntOrNull()
                        }
                        val plainNum = nums.firstOrNull() ?: count
                        val plainPinned = slugSeason != null && slugSeason >= 1 && plainNum in 1..999
                        val num: Int
                        val seasonLabel: String?
                        val rowSeason: Int
                        val rowEpisode: Int
                        if (seasonEp != null && tokenE != null) {
                            rowSeason = seasonEp.first.coerceAtLeast(1)
                            rowEpisode = tokenE
                            num = com.anonrode.downloader.util.SeasonEpisode.encode(rowSeason, rowEpisode)
                            seasonLabel = "S%02dE%02d".format(rowSeason, rowEpisode)
                        } else if (plainPinned) {
                            rowSeason = slugSeason!!.coerceAtLeast(1)
                            rowEpisode = plainNum
                            num = com.anonrode.downloader.util.SeasonEpisode.encode(rowSeason, rowEpisode)
                            seasonLabel = "S%02dE%02d".format(rowSeason, rowEpisode)
                        } else {
                            // Flat single-season numbering: never 100-base, so
                            // a 150-episode season cannot masquerade as S02E50.
                            rowSeason = 1
                            rowEpisode = plainNum
                            num = plainNum
                            seasonLabel = null
                        }
                        val epTitle = when {
                            mirrorLabel != null -> mirrorLabel
                            seasonLabel != null -> seasonLabel
                            !prevHeading.isNullOrBlank() && prevHeading.contains("Episode", ignoreCase = true) -> prevHeading
                            text.isNotBlank() && text.length < 40 && !text.equals("Download Episode", ignoreCase = true) && !text.equals("Download Movie", ignoreCase = true) && !text.equals("Download", ignoreCase = true) -> text
                            else -> "Episode $plainNum"
                        }

                        val existingIdx = episodes.indexOfFirst { it.episodeNum == num && (mirrorLabel == null || it.title == epTitle) }
                        if (existingIdx >= 0) {
                            val existing = episodes[existingIdx]
                            val isNewDirect = href.contains("nkiserv.com") || com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(href)
                            val isOldDirect = existing.url.contains("nkiserv.com") || com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(existing.url)
                            if (isNewDirect && !isOldDirect) {
                                episodes[existingIdx] = existing.copy(url = href)
                            } else if (href != existing.url && href !in existing.mirrorUrls) {
                                episodes[existingIdx] = existing.copy(mirrorUrls = existing.mirrorUrls + href)
                            }
                        } else {
                            episodes.add(
                                EpisodeItem(
                                    title = epTitle,
                                    url = href,
                                    episodeNum = num,
                                    site = name,
                                    season = rowSeason,
                                    episode = rowEpisode
                                )
                            )
                            if (num >= count) count = plainNum + 1
                        }
                    }
                }
            }

            // Uniqueness net (RulesPipeline parity, b57339f): the engine
            // re-resolves an episode by firstOrNull { episodeNum == … } — a
            // pinned season*100 key colliding across a mirror/server row and
            // its real episode (both land in the slug's season space now) or
            // across two token rows would silently pick the wrong URL.
            // Re-stamp later duplicates above the list maximum; the item keeps
            // its title/URL, only the internal re-resolve key moves.
            if (episodes.size > 1) {
                val seen = HashSet<Int>(episodes.size)
                var overflow = (episodes.maxOfOrNull { it.episodeNum } ?: 0) + 1
                for (i in episodes.indices) {
                    if (!seen.add(episodes[i].episodeNum)) {
                        episodes[i] = episodes[i].copy(episodeNum = overflow++)
                    }
                }
            }

            episodes.sortWith(compareBy({ it.episodeNum }, { it.title }))

            val card = ShowCard(
                title = title,
                url = cleanUrl,
                posterUrl = if (PosterBackfill.isUsablePoster(poster)) poster else "",
                site = name
            )
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    /** Explicit season/episode for pipeline rows that carry none. NKiri is a
     *  legacy 100-based site, so SeasonEpisode.of decodes its historical
     *  episodeNum correctly; new rows are re-encoded collision-free. */
    private fun stampPipelineSeasons(episodes: List<EpisodeItem>): List<EpisodeItem> =
        episodes.map { ep ->
            if (ep.season > 0 && ep.episode > 0) ep
            else {
                val (s, e) = com.anonrode.downloader.util.SeasonEpisode.of(ep)
                ep.copy(
                    season = s,
                    episode = e,
                    episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(s, e)
                )
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
            ?: ResolverRegistry.resolve(effective, quality)
        val target = if (!direct.isNullOrBlank()) direct else {
            if (com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(effective)) effective else ""
        }
        val isSingleSocket = effective.contains("nkiserv.com") || episodeUrl.contains("nkiserv.com") || target.contains(".m3u8")
        val isHls = target.contains(".m3u8") || target.contains("manifest")

        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mkv" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = if (isSingleSocket) 1 else 16
        )
    }

    /** Unwraps the nkiri download-manager wrapper:
     *  `.../dl/download-17827/?redirect=https%3A%2F%2Fdownloadwella.com%2F...`
     *  → `https://downloadwella.com/...`. Returns the input unchanged when the
     *  parameter is absent or the decoded target isn't an http URL. */
    private fun unwrapDownloadManagerRedirect(url: String): String {
        return try {
            val wrapper = URI(url)
            if (wrapper.path?.startsWith("/dl/", ignoreCase = true) != true) return url
            val parameter = wrapper.rawQuery?.split('&')?.firstOrNull {
                URLDecoder.decode(it.substringBefore('='), "UTF-8").equals("redirect", ignoreCase = true)
            } ?: return url
            val target = URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
            val parsed = URI(target)
            if ((parsed.scheme.equals("http", ignoreCase = true) ||
                        parsed.scheme.equals("https", ignoreCase = true)) && !parsed.host.isNullOrBlank()) {
                target
            } else url
        } catch (_: Exception) {
            url
        }
    }

    /** S02E05 season episode numbers from locker filenames. Mirrors the number
     *  patterns RulesPipeline applies to anchors, kept as NkiriProvider-local
     *  helpers (see [filenameEpisodeNums]). */
    private val SEASON_EP_REGEX = Regex("""(?i)\bs(\d+)e(\d+)\b""")
    // "(?:^|[^a-z0-9])" replaces "(?<![a-z0-9])" — lookbehinds are banned in
    // main source (Android regex-engine crash class, see NameSanitizer ENGINE
    // RULE); the non-capturing prefix keeps group 1 = the episode number.
    private val EP_TOKEN_REGEX = Regex("""(?i)(?:^|[^a-z0-9])e(?:p)?[\s._-]*(\d{1,4})(?![a-z0-9])""")

    /** Numbers an episode heading declares: the bare number, "E5", "Episode 5",
     *  or a combined "Episode 17 & 18" / "Episodes 17-18" (all of them). */
    internal fun headingEpisodeNumbers(heading: String?): List<Int> {
        val h = heading?.trim() ?: return emptyList()
        if (h.isBlank()) return emptyList()
        // Normalize separators so "17 & 18", "17-18" and "17, 18" share a path.
        val parts = h.replace("&", ",").replace("-", ",").split(",", " to ", " To ")
        val headNum = Regex("""(?i)episode?s?\s*(\d{1,4})""").find(h)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)(?:^|[^a-z0-9])e(?:p)?[\s._-]*(\d{1,4})(?![a-z0-9])""").find(h)?.groupValues?.get(1)?.toIntOrNull()
        if (headNum == null) return emptyList()
        val out = mutableListOf(headNum)
        for (part in parts.drop(1)) {
            val n = Regex("""(?:^|\D)(\d{1,4})(?!\d)""").find(part)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n > headNum && n <= headNum + 30) out.add(n)
        }
        return out.distinct()
    }

    /** Numbers a locker URL itself carries: "Alchemy of Souls E17.(NKIRI.COM).mkv"
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

    /** (season, normalized series prefix) from the locker file's SxxEyy token
     *  ("…/Show.S02E05.(THENKIRI.COM).mkv" -> (2, "show")) — RulesPipeline.fileSeasonToken
     *  parity for the compiled sweep. null when the filename carries no token.
     *  The prefix feeds the page-level single-series guard: equal prefixes
     *  across a post's tokened anchors = one show; differing prefixes = a
     *  cross-show list page whose tokens must not drive season keys. */
    internal fun fileSeasonToken(url: String): Pair<Int, String>? {
        val file = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val m = SEASON_EP_REGEX.find(file) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        return s to file.take(m.range.first).lowercase().filter { it.isLetterOrDigit() }
    }
}
