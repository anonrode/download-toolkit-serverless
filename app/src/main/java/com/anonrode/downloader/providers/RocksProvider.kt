package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.util.PostContentSanitizer
import org.jsoup.Jsoup
import java.net.URLEncoder

object RocksProvider : SiteProvider {
    override val name: String = "9jarocks"
    override val mainUrl: String get() = DynamicRulesManager.getBaseUrl(name)

    override suspend fun search(query: String): List<ShowCard> {
        DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
            val results = RulesPipeline.runSearch(name, pl, query)
            if (results.isNotEmpty()) return results
        }
        val results = mutableListOf<ShowCard>()
        val noLinks = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val rssUrl = "$mainUrl/search/$encoded/feed/rss2/"
            val xml = HttpClient.getText(rssUrl, tag = "search") ?: return emptyList()

            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            for (item in doc.select("item")) {
                val title = item.selectFirst("title")?.text()?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                val link = item.selectFirst("link")?.text()?.trim() ?: ""

                // The RSS description/content carries an <img> thumbnail inline;
                // pull it so the result card shows a real poster instead of the
                // lettered fallback. No extra network call.
                val desc = (item.selectFirst("content|encoded")?.text()
                    ?: item.selectFirst("description")?.text() ?: "")
                val poster = Regex("""<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(desc)?.groupValues?.get(1) ?: ""

                if (title.isNotBlank() && link.isNotBlank()) {
                    val card = ShowCard(
                        title = title,
                        url = link,
                        posterUrl = poster,
                        site = name,
                        category = "Nollywood & Movies"
                    )
                    // The feed's content:encoded already carries the post body
                    // with the locker URLs (this provider's own loadEpisodes
                    // allowlist — loadedfiles/downloadwella/wetafiles/waffi/
                    // vikingfile/lulacloud — is a subset of the gate markers).
                    // Zero extra requests; live-verified 2026-09-11: 22/22
                    // items pass today, the gate only drops future stubs.
                    if (DownloadLinkGate.hasDownloadLink(desc)) results.add(card) else noLinks.add(card)
                }
            }
        } catch (_: Exception) {}

        // All-dropped (unknown locker family) -> keep the ungated list.
        // Prioritize full packages / early episodes first
        return (if (results.isEmpty()) noLinks else results).sortedBy { card ->
            val t = card.title
            when {
                Regex("""\b(complete|full|season\s*\d+\s*\(episode\s*1\s*-\s*\d+\)|1\s*-\s*\d+)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 0
                else -> {
                    val m = Regex("""\b(?:episode|ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(t)
                    m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 50
                }
            }
        }
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Movie", url = showUrl, site = name)
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

            val title = doc.selectFirst("h1.entry-title, h1, .post-title")?.text()?.trim() ?: "Movie"
            val poster = doc.selectFirst(".entry-content img, .post-thumb img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            val seenSeasonZips = mutableSetOf<Int>()
            val entry = doc.selectFirst(".entry-content") ?: doc.body()
            PostContentSanitizer.clean(entry)

            var currentSeason = 1
            val slugMatch = Regex("""season-(\d{1,2})""", RegexOption.IGNORE_CASE).find(showUrl)
                ?: Regex("""season\s*(\d{1,2})""", RegexOption.IGNORE_CASE).find(title)
            val isExplicitSingleSeasonPage = slugMatch != null
            if (isExplicitSingleSeasonPage) {
                currentSeason = slugMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            }

            var count = 1
            val allElements = entry.allElements

            // A "SEASON n" section label in the post body (same style rules the
            // sweep below uses: short, non-exclusion text in the heading-ish tags).
            fun seasonMarker(el: org.jsoup.nodes.Element): Int? {
                val t = el.text().trim()
                val isExclusion = Regex("""\b(synopsis|storyline|about|comment|how to|click|added)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                if (t.length >= 60 || isExclusion) return null
                val sm = Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b(?!\s*-\s*\d+)""", RegexOption.IGNORE_CASE).find(t) ?: return null
                return sm.groupValues.getOrNull(1)?.toIntOrNull()
            }

            // In-page season sections decide the season when the page declares
            // MORE seasons than the slug. Live-verified 2026-10-14: the site
            // ships multi-season posts under a season-1 slug — "The Promised
            // Neverland Season 1" (…season-1-anime-id383590.html) carries
            // <p><strong>SEASON 2</strong></p> between its S01Exx and S02Exx
            // blocks, and "Legend of Sho Season 1-2" keeps S01 file names even
            // inside the season-2 block — so the slug pin alone collapsed both
            // seasons into a single "Season 1" chip. The pin still wins when
            // every in-page marker agrees with it (plain single-season posts
            // keep their pinned season); the site's related-posts carousel
            // lives outside .entry-content so it never seeds a marker.
            var trackHeadings = !isExplicitSingleSeasonPage
            if (!trackHeadings) {
                for (el in allElements) {
                    if (el.tagName().lowercase() !in listOf("h1", "h2", "h3", "h4", "strong", "b", "p", "div", "span")) continue
                    val marker = seasonMarker(el)
                    if (marker != null && marker != currentSeason) {
                        trackHeadings = true
                        break
                    }
                }
            }

            // Season for an anchor that declares an SxxEyy token: without
            // in-page markers the token wins (today's behavior); with them, a
            // token contradicting the active section is the site's own mislabel
            // ("…S01E11…" under the SEASON 2 heading) and the section decides.
            var markerSeasonActive = false
            fun anchorSeason(tokenSeason: Int?): Int = when {
                tokenSeason == null -> currentSeason
                !trackHeadings -> tokenSeason
                tokenSeason == currentSeason -> tokenSeason
                !markerSeasonActive -> tokenSeason
                else -> currentSeason
            }

            // One page-level series check: the loop's mirror/part branches and
            // the no-locker fallback both key off it (showUrl/title never vary
            // per anchor).
            val isSeriesPage = isExplicitSingleSeasonPage || showUrl.contains("/series/") ||
                Regex("""\b(?:season|s\d{1,2})\b""", RegexOption.IGNORE_CASE).containsMatchIn(showUrl) ||
                Regex("""\b(?:season|s\d{1,2})\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)

            for (elem in allElements) {
                val tagName = elem.tagName().lowercase()

                // Track active Season headings in page content
                if (trackHeadings && tagName in listOf("h1", "h2", "h3", "h4", "strong", "b", "p", "div", "span")) {
                    val sm = seasonMarker(elem)
                    if (sm != null) {
                        currentSeason = sm
                        markerSeasonActive = true
                    }
                }

                if (tagName == "a" && elem.hasAttr("href")) {
                    val rawHref = elem.attr("href")
                    val href = elem.attr("abs:href").ifBlank {
                        HttpClient.safeResolveUri(showUrl, rawHref)
                    }
                    val lowerHref = href.lowercase()

                    if (href.isBlank() || href in seen || href.contains("error?e=", ignoreCase = true) || href.contains("errore=", ignoreCase = true)) continue
                    if (PostContentSanitizer.isSameSitePostPermalink(href, "my9jarocks.bz") || PostContentSanitizer.isSiblingPostAnchorText(elem.text())) continue
                    val isLocker = lowerHref.contains("loadedfiles") ||
                            lowerHref.contains("downloadwella") ||
                            lowerHref.contains("wetafiles") ||
                            lowerHref.contains("waffi") ||
                            lowerHref.contains("vikingfile") ||
                            lowerHref.contains("lulacloud") ||
                            lowerHref.contains("kissorgrab") ||
                            lowerHref.contains("wildshare") ||
                            lowerHref.contains("pixeldrain") ||
                            com.anonrode.downloader.pipeline.StrictLinkClassifier.isKnownLocker(href) ||
                            com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(href)

                    if (isLocker) {
                        seen.add(href)
                        val text = elem.text().trim()
                        val parentText = elem.parent()?.text()?.trim() ?: ""
                        val parentLinks = elem.parent()?.select("a[href]")?.size ?: 1
                        val safeParentText = if (parentLinks <= 1) parentText else {
                            var prev = elem.previousSibling()
                            var s = ""
                            while (prev != null && s.length < 60) {
                                if (prev is org.jsoup.nodes.Element && prev.tagName() == "a") break
                                if (prev is org.jsoup.nodes.TextNode) s = prev.text() + s
                                else if (prev is org.jsoup.nodes.Element) s = prev.text() + s
                                prev = prev.previousSibling()
                            }
                            s.trim()
                        }

                        // Season ZIP detection: strict anchor and target inspection
                        val anchorText = text
                        val isZip = href.endsWith(".zip", ignoreCase = true) ||
                            href.contains(".zip?", ignoreCase = true) ||
                            anchorText.contains("ZIP", ignoreCase = true) ||
                            (parentText.contains("ZIP", ignoreCase = true) && (anchorText.contains("DOWNLOAD", ignoreCase = true) || href.contains(".zip", ignoreCase = true)))

                        if (isZip) {
                            val zipSeason = Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(parentText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(anchorText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: currentSeason

                            if (zipSeason !in seenSeasonZips) {
                                seenSeasonZips.add(zipSeason)
                                val zipLabel = "S%02d Complete Season ZIP".format(zipSeason)
                                episodes.add(
                                    EpisodeItem(
                                        title = zipLabel,
                                        url = href,
                                        episodeNum = zipSeason * 100,
                                        site = name
                                    )
                                )
                            }
                            continue
                        }

                        val explicitSm = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(href)
                            ?: Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).findAll(safeParentText).lastOrNull()

                        val epMatch = Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(href)
                            ?: Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).findAll(safeParentText).lastOrNull()

                        val itemSeason = anchorSeason(explicitSm?.groupValues?.getOrNull(1)?.toIntOrNull())
                        val qMatch = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).findAll(safeParentText).lastOrNull()
                        val qualitySuffix = qMatch?.groupValues?.getOrNull(1)?.let { " [$it]" } ?: ""

                        // Server-mirror / part detection for marker-free links.
                        // Device-verified 2026-09-13: movie posts host ONE file on
                        // two lockers labelled "DOWNLOAD VIDEO SERVER 1/2" — with
                        // no S/E marker the old code numbered them as sequential
                        // episodes, so a single film rendered as S01E01+S01E02.
                        // Text-first search keeps the label faithful to the link
                        // the user is actually tapping.
                        val mirrorMark = if (explicitSm == null && epMatch == null) {
                            Regex("""\b(?:video\s+)?server\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(text)
                                ?: Regex("""\b(?:video\s+)?server\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(safeParentText)
                        } else null
                        val partMark = if (mirrorMark == null && explicitSm == null && epMatch == null) {
                            Regex("""\b(?:file\s+)?part\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(text)
                                ?: Regex("""\b(?:file\s+)?part\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(safeParentText)
                        } else null

                        val isSeries = isSeriesPage
                        val isDownloadAnchor = Regex("""(?i)^(episode\s*)?download(\s+\d{3,4}p|\s+mp4|\s+mkv)?$""").matches(text.trim()) ||
                            Regex("""(?i)^download\s*(\d{3,4}p)?$""").matches(text.trim())

                        when {
                            mirrorMark != null -> episodes.add(
                                EpisodeItem(
                                    title = "Server ${mirrorMark.groupValues[1]}$qualitySuffix",
                                    url = href,
                                    episodeNum = if (isSeries) itemSeason * 100 + count else (mirrorMark.groupValues[1].toIntOrNull() ?: count),
                                    site = name
                                )
                            )
                            partMark != null -> episodes.add(
                                EpisodeItem(
                                    title = "Part ${partMark.groupValues[1]}$qualitySuffix",
                                    url = href,
                                    episodeNum = if (isSeries) itemSeason * 100 + (partMark.groupValues[1].toIntOrNull() ?: count) else (partMark.groupValues[1].toIntOrNull() ?: count),
                                    site = name
                                )
                            )
                            !isSeries && explicitSm == null && epMatch == null -> {
                                if (episodes.isNotEmpty()) {
                                    val existing = episodes[0]
                                    if (href !in existing.mirrorUrls && href != existing.url) {
                                        episodes[0] = existing.copy(mirrorUrls = existing.mirrorUrls + href)
                                    }
                                } else {
                                    episodes.add(
                                        EpisodeItem(
                                            title = "Full Movie$qualitySuffix",
                                            url = href,
                                            episodeNum = 1,
                                            site = name
                                        )
                                    )
                                }
                            }
                            else -> {
                                val epNum = explicitSm?.groupValues?.getOrNull(2)?.toIntOrNull()
                                    ?: epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    ?: if (isDownloadAnchor && episodes.isNotEmpty()) (episodes.last().episodeNum % 100) else count
                                val targetEpNum = if (isSeries) itemSeason * 100 + epNum else epNum

                                val existingIndex = episodes.indexOfFirst { it.episodeNum == targetEpNum }
                                if (existingIndex >= 0) {
                                    val existing = episodes[existingIndex]
                                    if (href !in existing.mirrorUrls && href != existing.url) {
                                        episodes[existingIndex] = existing.copy(mirrorUrls = existing.mirrorUrls + href)
                                    }
                                } else {
                                    val displayTitle = if (isSeries) "S%02dE%02d".format(itemSeason, epNum) + qualitySuffix
                                        else if (epNum == 1) "Full Movie$qualitySuffix" else "Episode $epNum$qualitySuffix"
                                    episodes.add(
                                        EpisodeItem(
                                            title = displayTitle,
                                            url = href,
                                            episodeNum = targetEpNum,
                                            site = name
                                        )
                                    )
                                }
                            }
                        }
                        count++
                    }
                }
            }

            // If no locker links found, check fallback download buttons
            if (episodes.isEmpty()) {
                val dls = doc.select(".entry-content a[href*='download'], .download-links a")
                for (a in dls) {
                    val rawHref = a.attr("href")
                    val href = a.attr("abs:href").ifBlank {
                        HttpClient.safeResolveUri(showUrl, rawHref)
                    }
                    if (href.isNotBlank() && href !in seen) {
                        seen.add(href)
                        val text = a.text().trim()
                        // The old fallback copied the anchor text verbatim —
                        // every row titled "Download" with count numbering that
                        // restarted per season block. Derive the identity from
                        // the file the anchor points at, exactly like the main
                        // sweep: SxxEyy token from the filename, then a bare
                        // episode token, then a quality tag. The anchor text is
                        // kept only when it is more specific than "Download".
                        val file = href.substringBefore('?').substringBefore('#').substringAfterLast('/')
                        val smFb = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(file)
                        val epFb = Regex("""(?:^|[^a-z0-9])e(?:p)?[\s._-]*(\d{1,3})(?![a-z0-9])""", RegexOption.IGNORE_CASE).find(file)
                        val qFb = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(file)
                        val qualitySuffix = qFb?.groupValues?.getOrNull(1)?.let { " [$it]" } ?: ""
                        val genericText = text.isBlank() || text.equals("Download", ignoreCase = true)
                        val fbSm = smFb?.groupValues
                        when {
                            fbSm != null -> {
                                val s = anchorSeason(fbSm.getOrNull(1)?.toIntOrNull())
                                val e = fbSm.getOrNull(2)?.toIntOrNull() ?: count
                                episodes.add(
                                    EpisodeItem(
                                        title = "S%02dE%02d".format(s, e) + qualitySuffix,
                                        url = href,
                                        episodeNum = s * 100 + e,
                                        site = name
                                    )
                                )
                            }
                            epFb != null -> {
                                val e = epFb.groupValues.getOrNull(1)?.toIntOrNull() ?: count
                                val label = if (genericText) {
                                    if (isSeriesPage) "S%02dE%02d".format(currentSeason, e) else "Episode $e"
                                } else text
                                episodes.add(
                                    EpisodeItem(
                                        title = label + qualitySuffix,
                                        url = href,
                                        episodeNum = if (isSeriesPage) currentSeason * 100 + e else e,
                                        site = name
                                    )
                                )
                            }
                            else -> episodes.add(
                                EpisodeItem(
                                    title = ((if (genericText) "Download Link $count" else text) + qualitySuffix).trim(),
                                    url = href,
                                    episodeNum = count,
                                    site = name
                                )
                            )
                        }
                        if (fbSm == null && epFb == null) count++
                    }
                }
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedWith(compareBy({ it.episodeNum }, { it.title })))
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        // OTA resolve recipe first (signed terminal-gated crack); compiled
        // registry stays the untouched fallback.
        val direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: ResolverRegistry.resolve(episodeUrl, quality)
        val target = if (!direct.isNullOrBlank()) direct else {
            if (com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(episodeUrl)) episodeUrl else ""
        }
        val isHls = target.contains(".m3u8", ignoreCase = true) || target.contains("manifest", ignoreCase = true)
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
