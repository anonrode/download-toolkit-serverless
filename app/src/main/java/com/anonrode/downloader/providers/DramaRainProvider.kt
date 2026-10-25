package com.anonrode.downloader.providers

import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.util.PostContentSanitizer
import com.anonrode.downloader.util.SeasonEpisode
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
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.LinkedHashMap

object DramaRainProvider : SiteProvider {
    override val name: String = "dramarain"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    private val CATEGORY_DRAMA_RE = Regex("""(?i)^https?://[^/]+/[a-z]+-drama/?$""")

    /** Episode gateway page → resolved locker URL, kept for 30 min. */
    private const val GATEWAY_TTL_MS = 30 * 60 * 1000L
    private val gatewayLock = Any()
    private val gatewayCache = object : LinkedHashMap<String, Pair<String, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?): Boolean =
            size > 64
    }

    override suspend fun search(query: String): List<ShowCard> {
        if (!SiteGate.isEnabled(name)) return emptyList()

        // 1) OTA pipeline first — a signed playbook may retarget the site.
        //    Its cards are junk-guarded (the rev-2/3 payload's title spec
        //    `.post-title a, h2 a` matches NOTHING on the live h4 markup, and
        //    a broken payload can bind unrelated rows) and poster-sanitized.
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = PosterBackfill.sanitize(RulesPipeline.runSearch(name, pl, query))
                .filter { isPlausibleShowUrl(it.url) }
            if (results.isNotEmpty()) return results
        }

        // 2) Compiled search — live-verified 2026-10-20: <article> rows carry
        //    <h4 class="entry-title"><a>. The old `h2 a` selector missed all
        //    of them, which is why every query fell through to slugGuess.
        val compiled = searchCompiled(query)
        if (compiled.isNotEmpty()) return compiled

        // 3) OTA search strategies are DATA, never a source of fabricated
        //    cards: a slug guess that answers 200 with a nav page ("/the/")
        //    is dropped, and what survives must prove it has episodes.
        SearchStrategyRunner.run(name, query, mainUrl)?.let { raw ->
            val plausible = PosterBackfill.sanitize(raw).filter { isPlausibleShowUrl(it.url) }
            val verified = verifyEpisodeSurface(plausible)
            if (verified.isNotEmpty()) return verified
        }
        return emptyList()
    }

    /**
     * A real show URL has a slug that identifies a show. `slugGuess` produced
     * `https://dramarain.com/the/` for query "the" (live 2026-10-20: 200, a
     * show <h1>, ZERO episode/download links) — a one-word root is never a
     * show, while real posts always carry a suffix ("-drama", "-episode-",
     * "season", a year) or a multi-word slug.
     */
    internal fun isPlausibleShowUrl(url: String): Boolean {
        val path = try {
            (URI(url).path ?: "").trim('/')
        } catch (_: Exception) {
            ""
        }
        if (path.isEmpty()) return false
        val lower = path.lowercase()
        if (lower.startsWith("category/") || lower.contains("/category/") ||
            lower.startsWith("tag/") || lower.contains("/tag/") ||
            lower.startsWith("page/") || lower.startsWith("genre/") ||
            lower.contains("/genre/")
        ) return false
        val slug = lower.substringAfterLast('/')
        if (slug.isEmpty()) return false
        if (slug.contains("-drama") || slug.contains("season") || slug.contains("-episode-") ||
            slug.contains("-movie-") || slug.contains("-series-")
        ) return true
        return slug.contains("-") && slug.length >= 10
    }

    /** Cards from a search-strategy chain must prove the page has episodes. */
    private suspend fun verifyEpisodeSurface(cards: List<ShowCard>): List<ShowCard> {
        if (cards.isEmpty()) return emptyList()
        return coroutineScope {
            cards.take(4).map { card ->
                async(Dispatchers.IO) {
                    val html = try {
                        HttpClient.getText(card.url, referer = "$mainUrl/", tag = "search")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (html.isNullOrBlank()) null
                    else if (hasEpisodeSurface(Jsoup.parse(html, card.url))) card else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** True when the page actually exposes an episode/download surface. */
    internal fun hasEpisodeSurface(doc: Document): Boolean =
        doc.select(
            "a[href*='/download'], a[href*='downloadwella'], a[href*='loadedfiles'], " +
                "a[href*='wetafiles'], a[href*='waffi'], a[href*='vikingfile'], " +
                "a[href*='-episode-'], a[href*='episode-']"
        ).isNotEmpty()

    /** Same-site `/download?link=<base64>` episode gateway (live 2026-10-20). */
    internal fun isDownloadGateway(href: String): Boolean {
        val uri = try {
            URI(href)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val siteHost = try {
            URI(mainUrl).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        } ?: "dramarain.com"
        if (host != siteHost && !host.endsWith(".$siteHost")) return false
        val path = (uri.path ?: "").lowercase()
        val query = (uri.rawQuery ?: "").lowercase()
        return path == "/download" || path == "/download/" || query.contains("link=")
    }

    private suspend fun searchCompiled(query: String): List<ShowCard> {
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encoded"
            val html = HttpClient.getText(searchUrl, tag = "search")
            if (!html.isNullOrBlank()) {
                val doc = Jsoup.parse(html, searchUrl)
                val articles = doc.select("article, .post-item, .post-title, .entry-title a, h4.entry-title a, h2.entry-title a")
                for (item in articles) {
                    val a = if (item.tagName() == "a") item else item.selectFirst("h4.entry-title a")
                        ?: item.selectFirst(".entry-title a")
                        ?: item.selectFirst(".post-title a")
                        ?: item.selectFirst("h2 a")
                        ?: item.selectFirst("a[href]")
                    val title = a?.text()?.trim() ?: ""
                    val rawHref = a?.attr("href") ?: ""
                    val href = a?.attr("abs:href")?.ifBlank {
                        HttpClient.safeResolveUri(searchUrl, rawHref)
                    } ?: ""
                    val img = item.selectFirst("img")?.let {
                        it.attr("abs:src").ifBlank { it.attr("src") }
                    } ?: ""

                    val lowerHref = href.lowercase()
                    if (CATEGORY_DRAMA_RE.matches(href) ||
                        lowerHref.contains("/category/") ||
                        lowerHref.contains("/tag/") ||
                        lowerHref.contains("/page/") ||
                        lowerHref.contains("/genre/") ||
                        !isPlausibleShowUrl(href)
                    ) {
                        continue
                    }

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
                val slug = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                if (slug.isBlank()) return emptyList()
                val cfg = DynamicRulesManager.getSiteConfig(name)
                val suffixes = if (!cfg?.slugSuffixes.isNullOrEmpty()) cfg!!.slugSuffixes else listOf(
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
                // The empty suffix is deliberately GONE: it fabricated
                // "/the/" (a one-word root) and every query 404-probed it.
                val candidateUrls = suffixes.map { "$base/$slug$it/" } + listOf("$base/drama/$slug/")

                // Parallel 3s liveness probes; a 404 costs one round trip and
                // no body. Only plausible slugs are probed at all.
                val plausible = candidateUrls.filter { isPlausibleShowUrl(it) }
                val alive = coroutineScope {
                    plausible.map { url ->
                        async(Dispatchers.IO) {
                            if (HttpClient.probe(url, referer = "$mainUrl/", timeoutMs = 3_000L, tag = "search")) url else null
                        }
                    }.awaitAll().filterNotNull().toSet()
                }
                for (url in plausible.filter { it in alive }) {
                    val directHtml = HttpClient.getText(url, tag = "search") ?: continue
                    val doc = Jsoup.parse(directHtml, url)
                    // A guessed slug must prove it is a show page — the old
                    // h1-only check accepted nav/root pages with zero episodes.
                    if (!hasEpisodeSurface(doc)) continue
                    val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: continue
                    val poster = doc.selectFirst(".entry-content img, .post-thumbnail img, meta[property='og:image']")?.let {
                        if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
                    } ?: ""

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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {}
        return PosterBackfill.sanitize(results)
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        if (!SiteGate.isEnabled(name)) {
            return ShowDetails(show = ShowCard(title = "Drama", url = showUrl, site = name))
        }
        val show = ShowCard(title = "Drama", url = showUrl, site = name)
        DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            val res = RulesPipeline.runEpisodes(name, pl, showUrl)
            if (res != null && res.episodes.isNotEmpty()) {
                val card = ShowCard(
                    title = res.title.ifBlank { show.title },
                    url = showUrl,
                    posterUrl = if (PosterBackfill.isUsablePoster(res.posterUrl)) res.posterUrl else "",
                    site = name
                )
                return ShowDetails(show = card, synopsis = res.synopsis, episodes = stampPipelineSeasons(res.episodes))
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
            val entryRoot = doc.selectFirst(".entry-content, article") ?: doc.body() ?: doc
            PostContentSanitizer.clean(entryRoot)
            val links = entryRoot.select("a[href]").filter { cand ->
                val raw = cand.attr("href")
                val h = cand.attr("abs:href").ifBlank { raw }.lowercase()
                // Same-site /download?link= gateways ARE the episode rows on
                // this site (live 2026-10-20: 7 "DOWNLOAD EPISODE N" links) —
                // the generic same-site-permalink guard must not eat them.
                isDownloadGateway(h) ||
                    (!PostContentSanitizer.isSameSitePostPermalink(cand.attr("href"), "dramarain.com") &&
                        !PostContentSanitizer.isSiblingPostAnchorText(cand.text()) &&
                        (h.contains("download") || h.contains("episode") || h.contains("loadedfiles") ||
                            h.contains("waffi") || h.contains(".mkv") || h.contains(".mp4") ||
                            com.anonrode.downloader.pipeline.StrictLinkClassifier.classify(h) is com.anonrode.downloader.pipeline.StrictLinkClassifier.LinkClass.KnownLocker ||
                            com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(h)))
            }

            // Season pin from the post identity (canon: NaijaVault/NaijaPrey).
            // dramarain publishes one post per season ("/joy-of-life-s02-chinese-
            // drama/", live-verified 2026-10-15: 36 text-numbered anchors) and
            // the list renumbers from 1 — the pin puts season-2+ posts into
            // their own drawer chip; season-1/seasonless posts keep 1..N.
            val slugSeason = Regex("""(?i)(?:^|[.\-_ /])(?:s|season)[-.]?(\d{1,2})(?!\d)""").find(showUrl)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(title)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val baseSeason = (slugSeason ?: 1).coerceAtLeast(1)
            val maxBySeason = HashMap<Int, Int>()
            fun nextIn(season: Int): Int {
                val next = (maxBySeason[season] ?: 0) + 1
                maxBySeason[season] = next
                return next
            }

            for (a in links) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                val text = a.text().trim()
                val gateway = isDownloadGateway(href)
                if (href.isNotBlank() && href !in seen &&
                    (gateway || !com.anonrode.downloader.pipeline.StrictLinkClassifier.isNavigationJunk(href)) &&
                    (gateway || !PostContentSanitizer.isSameSitePostPermalink(href, "dramarain.com")) &&
                    !PostContentSanitizer.isSiblingPostAnchorText(text)
                ) {
                    seen.add(href)
                    val filename = href.substringAfterLast('/').substringBefore('?').substringBefore('#')
                    val quality = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.getOrNull(1)?.let { " [$it]" }
                        ?: Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE)
                            .find(text)?.groupValues?.getOrNull(1)?.let { " [$it]" }
                    // Mirror-server / part rows of ONE file are not episodes
                    // (device bug class 2026-09-13, shared with Rocks).
                    val mirrorLabel = DownloadLinkLabels.serverOrPart(text, filename)
                    // Episode identity FIRST, position last: the site
                    // text-numbers every row ("DOWNLOAD EPISODE 20"), so an
                    // extra link (ad, subtitle) ahead of the list would shift
                    // every positional number after it.
                    val textNum = Regex("""(?i)\b(?:Episode|Ep)[ -]*(\d{1,4})\b""").find(text)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(?i)(?:^|[^a-z0-9])e(?:p)?[ ._-]*(\d{1,4})(?![a-z0-9])""").find(text)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(?i)\b(?:Episode|Ep|E)[- ]*(\d{1,4})\b""").find(href)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val token = Regex("""(?i)S(\d{1,2})E(\d{1,3})""").find(filename)
                        ?: Regex("""(?i)S(\d{1,2})E(\d{1,3})""").find(text)

                    val item = when {
                        mirrorLabel != null -> {
                            val ep = nextIn(baseSeason)
                            EpisodeItem(
                                title = mirrorLabel + (quality ?: ""),
                                url = href,
                                episodeNum = SeasonEpisode.encode(baseSeason, ep),
                                site = name,
                                season = baseSeason,
                                episode = ep
                            )
                        }
                        token != null -> {
                            val s = token.groupValues[1].toIntOrNull()?.coerceIn(1, 200) ?: 1
                            val e = token.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: nextIn(s)
                            maxBySeason[s] = maxOf(maxBySeason[s] ?: 0, e)
                            EpisodeItem(
                                title = "S%d E%02d".format(s, e) + (quality ?: ""),
                                url = href,
                                episodeNum = SeasonEpisode.encode(s, e),
                                site = name,
                                season = s,
                                episode = e
                            )
                        }
                        textNum != null -> {
                            maxBySeason[baseSeason] = maxOf(maxBySeason[baseSeason] ?: 0, textNum)
                            EpisodeItem(
                                // The site's own shouty "DOWNLOAD EPISODE 20" anchor
                                // text becomes the canonical "Episode 20" (OTA
                                // labelChain parity); the pinned slug turns it into
                                // the Sxx chip format.
                                title = if (baseSeason >= 2)
                                    "S%d E%02d".format(baseSeason, textNum) + (quality ?: "")
                                else "Episode $textNum" + (quality ?: ""),
                                url = href,
                                episodeNum = SeasonEpisode.encode(baseSeason, textNum),
                                site = name,
                                season = baseSeason,
                                episode = textNum
                            )
                        }
                        else -> {
                            val ep = nextIn(baseSeason)
                            val textUsable = text.isNotBlank() &&
                                !text.equals("Download", ignoreCase = true) &&
                                !text.equals("Download Episode", ignoreCase = true) &&
                                text.length < 40
                            EpisodeItem(
                                title = (if (textUsable) text else "Episode $ep") + (quality ?: ""),
                                url = href,
                                episodeNum = SeasonEpisode.encode(baseSeason, ep),
                                site = name,
                                season = baseSeason,
                                episode = ep
                            )
                        }
                    }
                    episodes.add(item)
                }
            }

            // Uniqueness net (RulesPipeline/Nkiri parity, b57339f): two rows
            // landing on one episodeNum (two "EPISODE N" anchors with distinct
            // hrefs, or a mirror row's positional key colliding with a text
            // number) would make the engine's firstOrNull { episodeNum == … }
            // re-resolve pick the wrong URL. Re-stamp later duplicates above
            // the list maximum; only the internal key moves.
            if (episodes.size > 1) {
                val seenKeys = HashSet<Int>(episodes.size)
                var overflow = (episodes.maxOfOrNull { it.episodeNum } ?: 0) + 1
                for (i in episodes.indices) {
                    if (!seenKeys.add(episodes[i].episodeNum)) {
                        episodes[i] = episodes[i].copy(episodeNum = overflow++)
                    }
                }
            }

            val card = ShowCard(
                title = title,
                url = showUrl,
                posterUrl = if (PosterBackfill.isUsablePoster(poster)) poster else "",
                site = name
            )
            return ShowDetails(
                show = card,
                synopsis = synopsis,
                episodes = episodes.sortedWith(compareBy({ it.season.coerceAtLeast(1) }, { it.episode }, { it.title }))
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    private fun stampPipelineSeasons(episodes: List<EpisodeItem>): List<EpisodeItem> =
        episodes.map { ep ->
            if (ep.season > 0 && ep.episode > 0) {
                ep.copy(episodeNum = SeasonEpisode.encode(ep.season, ep.episode))
            } else {
                val (s, e) = SeasonEpisode.of(ep)
                ep.copy(season = s, episode = e, episodeNum = SeasonEpisode.encode(s, e))
            }
        }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        // The site wraps every episode in `https://dramarain.com/download?link=<b64>`
        // → 301 `/download/?link=` → a "Secure Download" page whose script
        // carries the real locker URL (live-verified 2026-10-20:
        // https://new.waffi.cloud/c/…/Light-the-Dark-S01E01-DRAMARAIN-COM.mkv).
        // The wrapper is not resolvable by the registry, so unwrap it first.
        val effective = if (isDownloadGateway(episodeUrl)) {
            gatewayTarget(episodeUrl) ?: episodeUrl
        } else episodeUrl

        val direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: RulesPipeline.runResolveForSite(name, effective, quality)
            ?: ResolverRegistry.resolve(effective, quality)
        val target = if (!direct.isNullOrBlank()) direct else {
            if (com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(effective)) effective else ""
        }
        val isHls = target.contains(".m3u8") || target.contains("manifest")
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "episode.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }

    /** Fetches an episode gateway and extracts the locker/media URL it hides. */
    private suspend fun gatewayTarget(gatewayUrl: String): String? {
        val now = System.currentTimeMillis()
        synchronized(gatewayLock) {
            val hit = gatewayCache[gatewayUrl]
            if (hit != null && hit.second > now) return hit.first
        }
        val html = try {
            HttpClient.getText(gatewayUrl, referer = "$mainUrl/", tag = "resolve")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val target = Regex("""(?:window\.location(?:\.href)?|location\.href|window\.location\.replace)\s*(?:=\s*)?\(?\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>]+\.(?:mkv|mp4|m4v|webm)(?:[^\s"'<>]*)""")
                .find(html)?.value
            ?: Regex("""https?://[^\s"'<>]*(?:downloadwella|loadedfiles|wetafiles|waffi|vikingfile|lightdl|kissorgrab|wildshare)[^\s"'<>]*""")
                .find(html)?.value
        if (target.isNullOrBlank()) return null
        val clean = target.replace("&amp;", "&").trim()
        if (!clean.startsWith("http", ignoreCase = true)) return null
        synchronized(gatewayLock) { gatewayCache[gatewayUrl] = clean to (now + GATEWAY_TTL_MS) }
        return clean
    }
}
