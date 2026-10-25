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
        if (!SiteGate.isEnabled(name)) return emptyList()
        val pipelineResults = PosterBackfill.sanitize(
            DynamicRulesManager.getPipeline(name)?.search?.let { pl ->
                RulesPipeline.runSearch(name, pl, query)
            } ?: emptyList()
        )

        // The HTML search endpoint answers with five UNRELATED cards for some
        // queries (verified live: "The Promised Neverland") and used to win
        // merely because it was the first source. Only trust pipeline output
        // when at least one card matches the query tokens; otherwise run the
        // compiled RSS search and merge, so a non-empty wrong result can never
        // mask the verified post.
        if (pipelineResults.isNotEmpty() && hasRelevantResult(query, pipelineResults)) {
            // The rev-2/3 payload binds `attr:enclosure:url`; 9jaRocks RSS
            // first enclosures are the MKV locker files (live-verified
            // 2026-10-20: 339/339), so sanitize blanks them and the compiled
            // RSS inline <img> backfills the real poster.
            if (pipelineResults.none { it.posterUrl.isBlank() }) return pipelineResults
            val rssPosters = posterMap(searchCompiledRss(query))
            return PosterBackfill.backfill(pipelineResults) { rssPosters }
        }
        val rssResults = searchCompiledRss(query)
        if (rssResults.isEmpty()) return pipelineResults
        if (pipelineResults.isEmpty()) return rssResults
        return PosterBackfill.sanitize(mergeResults(query, rssResults, pipelineResults))
    }

    private fun posterMap(cards: List<ShowCard>): Map<String, String> =
        cards.mapNotNull { card ->
            if (card.posterUrl.isBlank()) null
            else PosterBackfill.normalizeKey(card.url) to card.posterUrl
        }.toMap()

    /** True when at least one card reaches the RelevanceScorer acceptance floor. */
    internal fun hasRelevantResult(query: String, cards: List<ShowCard>): Boolean =
        cards.any { RelevanceScorer.score(query, it.title) >= 0.50 }

    /** URL/title dedupe; [primary] (the compiled RSS result) stays ahead of
     *  unrelated pipeline noise, then relevance orders the merged list. */
    internal fun mergeResults(query: String, primary: List<ShowCard>, extra: List<ShowCard>): List<ShowCard> {
        val out = mutableListOf<ShowCard>()
        val seenUrls = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()
        fun normalizedUrl(url: String) = url.trim().substringBefore('#')
        fun normalizedTitle(title: String) = title.lowercase().filter { it.isLetterOrDigit() }
        for (card in primary + extra) {
            val u = normalizedUrl(card.url)
            val t = normalizedTitle(card.title)
            if (u.isNotBlank() && !seenUrls.add(u)) continue
            if (t.isNotBlank() && !seenTitles.add(t)) continue
            out.add(card)
        }
        return out.sortedByDescending { RelevanceScorer.score(query, it.title) }
    }

    /** The original compiled RSS path, kept as the correctness fallback. */
    private suspend fun searchCompiledRss(query: String): List<ShowCard> {
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
        return PosterBackfill.sanitize((if (results.isEmpty()) noLinks else results).sortedBy { card ->
            val t = card.title
            when {
                Regex("""\b(complete|full|season\s*\d+\s*\(episode\s*1\s*-\s*\d+\)|1\s*-\s*\d+)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 0
                else -> {
                    val m = Regex("""\b(?:episode|ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(t)
                    m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 50
                }
            }
        })
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        if (!SiteGate.isEnabled(name)) {
            return ShowDetails(show = ShowCard(title = "Movie", url = showUrl, site = name))
        }
        // The compiled parser is AUTHORITATIVE for season identity (live-
        // verified 2026-10-20): the site publishes one post per season, a
        // multi-season post (…season-1… page carrying SEASON 2 blocks) and
        // flat 150-episode seasons. The OTA episodes pipeline has no season
        // extraction, so accepting it first collapsed S2 into S1 and made
        // S01E101 collide with S02E01 under the old 100-base key. Pipeline
        // rows are merged only when they carry EXPLICIT season info.
        val pipeline = DynamicRulesManager.getPipeline(name)?.episodes?.let { pl ->
            RulesPipeline.runEpisodes(name, pl, showUrl)
        }
        val pipelineEpisodes = pipeline?.episodes.orEmpty()
        val compiled = loadCompiledEpisodes(showUrl)

        // OTA-authoritative episodes (contentPolicy.episodesAuthority, set for
        // 9jarocks in rev 19): a non-empty pipeline answer REPLACES the
        // compiled parse. The post bodies carry fully tokened locker filenames
        // (live-verified 2026-10-25: 50/60/51/64/98/44 rows match filename
        // tokens exactly), so the pipeline answer is the post's real files;
        // merging under the compiled heuristic could surface rows the post
        // never contained (activity-log sibling-series leak report).
        if (DynamicRulesManager.isEpisodesAuthority(name) && pipelineEpisodes.isNotEmpty()) {
            val slugSeason = pagePinOf(showUrl, pipeline?.title.orEmpty()) ?: 1
            val stamped = sortEpisodes(stampPipelineEpisodes(pipelineEpisodes, slugSeason))
            val card = ShowCard(
                title = compiled?.show?.title?.ifBlank { null }
                    ?: pipeline?.title?.ifBlank { null }
                    ?: "Movie",
                url = showUrl,
                posterUrl = if (compiled?.show?.posterUrl.isNullOrBlank()) pipeline?.posterUrl.orEmpty()
                else compiled.show.posterUrl,
                site = name
            )
            return ShowDetails(
                show = card,
                synopsis = compiled?.synopsis ?: pipeline?.synopsis ?: "",
                episodes = stamped
            )
        }

        if (compiled == null || compiled.episodes.isEmpty()) {
            if (pipelineEpisodes.isNotEmpty()) {
                val slugSeason = pagePinOf(showUrl, pipeline?.title.orEmpty()) ?: 1
                val stamped = stampPipelineEpisodes(pipelineEpisodes, slugSeason)
                val card = compiled?.show ?: ShowCard(
                    title = pipeline?.title?.ifBlank { "Movie" } ?: "Movie",
                    url = showUrl,
                    posterUrl = if (PosterBackfill.isUsablePoster(pipeline?.posterUrl.orEmpty())) {
                        pipeline?.posterUrl.orEmpty()
                    } else "",
                    site = name
                )
                return ShowDetails(
                    show = card,
                    synopsis = pipeline?.synopsis ?: "",
                    episodes = sortEpisodes(stamped)
                )
            }
            return compiled ?: ShowDetails(show = ShowCard(title = "Movie", url = showUrl, site = name))
        }

        return compiled.copy(episodes = sortEpisodes(mergeExplicitPipeline(compiled.episodes, pipelineEpisodes)))
    }

    /** The compiled page parse. null only when the page could not be fetched,
     *  so the caller can still fall back to a pipeline answer. */
    private suspend fun loadCompiledEpisodes(showUrl: String): ShowDetails? {
        val show = ShowCard(title = "Movie", url = showUrl, site = name)
        val html = try {
            HttpClient.getText(showUrl)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            parseCompiledPage(Jsoup.parse(html, showUrl), showUrl)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ShowDetails(show = show)
        }
    }

    /** Pure page parse — captured-fixture JVM tests call this directly. */
    internal fun parseCompiledPage(doc: org.jsoup.nodes.Document, showUrl: String): ShowDetails {
            val title = doc.selectFirst("h1.entry-title, h1, .post-title")?.text()?.trim() ?: "Movie"
            val poster = doc.selectFirst(".entry-content img, .post-thumb img, meta[property='og:image']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src").ifBlank { it.attr("src") }
            } ?: ""
            val synopsis = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim() ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            val seenUrls = mutableSetOf<String>()
            val seenZipLabels = mutableSetOf<String>()
            // Scope is .entry-content: related-post carousels live outside it
            // and must never seed markers.
            val entry = doc.selectFirst(".entry-content") ?: doc.body()
            PostContentSanitizer.clean(entry)

            // ── Page pin (verified rule 04_9jarocks_season_matrix §1) ─────
            // Slug `season-N` first, then title `Season N`. A RANGE
            // ("Season 1-4", "Season 1 – 3") is NOT a pin; "Part I/II" is
            // never a season and never changes the pin.
            val pagePin = pagePinOf(showUrl, title)
            var currentSeason = pagePin ?: 1

            val allElements = entry.allElements
            val headingTags = setOf("h1", "h2", "h3", "h4", "strong", "b", "p", "div", "span")

            // A "SEASON n" section label in the post body.
            fun seasonMarker(el: org.jsoup.nodes.Element): Int? {
                val t = el.text().trim()
                val isExclusion = Regex("""\b(synopsis|storyline|about|comment|how to|click|added)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                if (t.length >= 60 || isExclusion) return null
                val sm = Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b(?!\s*-\s*\d+)""", RegexOption.IGNORE_CASE).find(t) ?: return null
                return sm.groupValues.getOrNull(1)?.toIntOrNull()
            }

            val sectionsPresent = allElements.any { el ->
                el.tagName().lowercase() in headingTags && seasonMarker(el) != null
            }

            // ── Pre-scan: page declaration, token distribution, file styles ─
            val episodeAnchors = entry.select("a[href]")
                .filter { isLockerHref(it) && !isZipAnchor(it) }
                .distinctBy { resolvedHref(it, showUrl) }

            // §3a: "Episode N Added" / "Episode X-Y Added" is the strongest
            // signal, but ONLY when the declared count matches the number of
            // episode anchors (otherwise it is a changelog, not contents).
            val declaration = episodeDeclaration(title)
            val declaredStart: Int? = declaration?.let { (start, end) ->
                if (episodeAnchors.size == end - start + 1) start else null
            }
            val declaredEpisodeByUrl: Map<String, Int> = if (declaredStart != null) {
                episodeAnchors.mapIndexed { i, a -> resolvedHref(a, showUrl) to (declaredStart + i) }.toMap()
            } else emptyMap()

            val tokenCounts = HashMap<Int, Int>()
            val pinSeasonEpisodes = HashSet<Int>()
            val outlierEpisodes = sortedSetOf<Int>()
            val pinStyleCounts = HashMap<String, Int>()
            val outlierStyles = mutableSetOf<String>()
            for (a in episodeAnchors) {
                val href = resolvedHref(a, showUrl)
                val token = tokenEpisode(a.text(), href) ?: continue
                tokenCounts[token.first] = (tokenCounts[token.first] ?: 0) + 1
                if (pagePin != null) {
                    if (token.first == pagePin) {
                        pinSeasonEpisodes.add(token.second)
                        val style = filenameStyle(href)
                        pinStyleCounts[style] = (pinStyleCounts[style] ?: 0) + 1
                    } else {
                        outlierEpisodes.add(token.second)
                        outlierStyles.add(filenameStyle(href))
                    }
                }
            }
            val totalTokens = tokenCounts.values.sum()
            val dominantTokenSeason = tokenCounts.maxByOrNull { it.value }?.key
            val outlierCount = if (pagePin != null) totalTokens - (tokenCounts[pagePin] ?: 0) else totalTokens
            val majorityPinStyle = pinStyleCounts.maxByOrNull { it.value }?.key
            // §3b fallback evidence: every condition must hold, otherwise a
            // genuine cross-season page (adversarial tests 11/12) would lose
            // its real S4 rows to a gap-fill guess.
            val outlierSparse = outlierEpisodes.size <= 1 ||
                outlierEpisodes.zipWithNext().any { (x, y) -> y - x > 1 }
            val outliersGapOnly = outlierEpisodes.all { it !in pinSeasonEpisodes }
            val outliersStylistic = outlierStyles.isEmpty() ||
                (majorityPinStyle != null && outlierStyles.size == 1 && outlierStyles.first() != majorityPinStyle)
            val titleNamesOtherSeason = pagePin != null && Regex("""(?i)\bseason\s*(\d{1,2})\b""")
                .findAll(title)
                .any { m -> m.groupValues[1].toIntOrNull()?.let { it != pagePin } == true }
            val pinFallback = !sectionsPresent && pagePin != null &&
                (totalTokens == 0 || (
                    dominantTokenSeason == pagePin &&
                        outlierCount <= maxOf(2, Math.ceil(totalTokens * 0.2).toInt()) &&
                        !titleNamesOtherSeason && outliersGapOnly && outliersStylistic && outlierSparse
                    ))

            val maxEpisodeBySeason = HashMap<Int, Int>()
            fun nextEpisode(season: Int): Int {
                val next = (maxEpisodeBySeason[season] ?: 0) + 1
                maxEpisodeBySeason[season] = next
                return next
            }
            fun noteEpisode(season: Int, episode: Int) {
                if (episode > (maxEpisodeBySeason[season] ?: 0)) maxEpisodeBySeason[season] = episode
            }
            fun encode(season: Int, episode: Int): Int =
                com.anonrode.downloader.util.SeasonEpisode.encode(season.coerceAtLeast(1), episode.coerceAtLeast(0))

            fun addRow(rowTitle: String, href: String, season: Int, episode: Int) {
                if (href.isBlank() || !seenUrls.add(href)) return
                val existingIndex = episodes.indexOfFirst { it.season == season && it.episode == episode }
                if (existingIndex >= 0) {
                    val existing = episodes[existingIndex]
                    // Same identity + same title = mirror of one file.
                    if (existing.title == rowTitle) {
                        if (href != existing.url && href !in existing.mirrorUrls) {
                            episodes[existingIndex] = existing.copy(mirrorUrls = existing.mirrorUrls + href)
                        }
                        return
                    }
                    // A different title on one (season, episode) is a
                    // documented special (E5.5) or pack — keep both; the
                    // uniqueness net re-stamps the legacy key.
                }
                episodes.add(
                    EpisodeItem(
                        title = rowTitle,
                        url = href,
                        episodeNum = encode(season, episode),
                        site = name,
                        season = season.coerceAtLeast(1),
                        episode = episode
                    )
                )
                noteEpisode(season, episode)
            }

            var count = 1
            var lastTokenSeason: Int? = null

            for (elem in allElements) {
                val tagName = elem.tagName().lowercase()

                // In-page markers are highest precedence and apply in
                // document order (Promised Neverland: SEASON 2 precedes
                // anchor 12).
                if (sectionsPresent && tagName in headingTags) {
                    seasonMarker(elem)?.let { currentSeason = it }
                }

                if (tagName != "a" || !elem.hasAttr("href")) continue
                if (!isLockerHref(elem)) continue
                val href = resolvedHref(elem, showUrl)
                if (href.isBlank() || DynamicRulesManager.isKnownDead(href) ||
                    href.contains("error?e=", ignoreCase = true) || href.contains("errore=", ignoreCase = true)
                ) continue
                if (PostContentSanitizer.isSameSitePostPermalink(href, "my9jarocks.bz") ||
                    PostContentSanitizer.isSiblingPostAnchorText(elem.text())
                ) continue

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

                // ── Season ZIP packs (§5) ────────────────────────────────
                val isZip = href.endsWith(".zip", ignoreCase = true) ||
                    href.contains(".zip?", ignoreCase = true) ||
                    text.contains("ZIP", ignoreCase = true) ||
                    (parentText.contains("ZIP", ignoreCase = true) && text.contains("DOWNLOAD", ignoreCase = true))
                if (isZip) {
                    val zipSeason = Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(href)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(parentText)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(text)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: currentSeason
                    val range = Regex("""(?i)(?:S\d{1,2}\s*)?E?(\d{1,3})\s*[-–]\s*(\d{1,3})""")
                        .find(text)
                        ?: Regex("""(?i)(?:S\d{1,2}\s*)?E?(\d{1,3})\s*[-–]\s*(\d{1,3})""").find(href.substringAfterLast('/'))
                    val start = range?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val end = range?.groupValues?.getOrNull(2)?.toIntOrNull()
                    // One item per RANGE, never one per season: Dragon Ball
                    // Kai ships five S01 ZIP ranges.
                    val zipLabel = if (start != null && end != null) {
                        "S%02dE%d-%d ZIP".format(zipSeason.coerceAtLeast(1), start, end)
                    } else {
                        "S%02d Complete Season ZIP".format(zipSeason.coerceAtLeast(1))
                    }
                    if (seenZipLabels.add(zipLabel)) {
                        addRow(zipLabel, href, zipSeason, start ?: 0)
                    }
                    continue
                }

                val explicitToken = tokenEpisode(text, href)
                    ?: tokenEpisode(safeParentText, href)
                val fraction = fractionalEpisode(text, href) ?: fractionalEpisode(safeParentText, href)
                val textEpisodeNum = Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(href)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).findAll(safeParentText)
                        .lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()

                // ── Season resolution (§2/§3a/§3b/§4) ──────────────────
                val declaredEpisode = declaredEpisodeByUrl[href]
                val resolvedSeason: Int
                when {
                    sectionsPresent -> {
                        // §2: section season wins; a contradictory file
                        // token inside it is a site mislabel.
                        resolvedSeason = currentSeason
                    }
                    declaredEpisode != null -> {
                        // §3a: the title declares this page's contents and
                        // the anchor count matches it — the declaration maps
                        // document order to S(pin)Estart..Eend.
                        resolvedSeason = pagePin ?: 1
                    }
                    pinFallback -> {
                        // §3b: evidence-complete remap (gap + style + sparse
                        // + no competing season in the title).
                        val pin = pagePin ?: 1
                        if (explicitToken != null && explicitToken.first != pin &&
                            explicitToken.second !in pinSeasonEpisodes
                        ) {
                            resolvedSeason = pin
                        } else if (explicitToken != null && explicitToken.first != pin) {
                            resolvedSeason = explicitToken.first
                        } else {
                            resolvedSeason = pin
                        }
                    }
                    else -> {
                        // §4 (or a genuine cross-season page): token seasons
                        // drive the split; un-tokened rows follow the nearest
                        // preceding token season.
                        resolvedSeason = explicitToken?.first
                            ?: lastTokenSeason
                            ?: dominantTokenSeason
                            ?: pagePin
                            ?: 1
                    }
                }
                if (explicitToken != null) lastTokenSeason = resolvedSeason

                val qualitySuffix = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(text)
                    ?.groupValues?.getOrNull(1)?.let { " [$it]" }
                    ?: Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).findAll(safeParentText).lastOrNull()
                        ?.groupValues?.getOrNull(1)?.let { " [$it]" }
                    ?: ""

                val mirrorMark = if (explicitToken == null && textEpisodeNum == null) {
                    Regex("""\b(?:video\s+)?server\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(text)
                        ?: Regex("""\b(?:video\s+)?server\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(safeParentText)
                } else null
                val partMark = if (mirrorMark == null && explicitToken == null && textEpisodeNum == null) {
                    Regex("""\b(?:file\s+)?part\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(text)
                        ?: Regex("""\b(?:file\s+)?part\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(safeParentText)
                } else null

                val isSeries = sectionsPresent || pagePin != null ||
                    showUrl.contains("/series/") ||
                    Regex("""\b(?:season|s\d{1,2})\b""", RegexOption.IGNORE_CASE).containsMatchIn(showUrl) ||
                    Regex("""\b(?:season|s\d{1,2})\b""", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
                    explicitToken != null || textEpisodeNum != null

                val isDownloadAnchor = Regex("""(?i)^(episode\s*)?download(\s+\d{3,4}p|\s+mp4|\s+mkv)?$""").matches(text.trim()) ||
                    Regex("""(?i)^download\s*(\d{3,4}p)?$""").matches(text.trim())

                when {
                    mirrorMark != null -> {
                        val rowEpisode = if (isSeries) nextEpisode(resolvedSeason)
                            else (mirrorMark.groupValues[1].toIntOrNull() ?: nextEpisode(1))
                        count++
                        addRow("Server ${mirrorMark.groupValues[1]}$qualitySuffix", href, resolvedSeason, rowEpisode)
                    }
                    partMark != null -> {
                        val rowEpisode = if (isSeries) nextEpisode(resolvedSeason)
                            else (partMark.groupValues[1].toIntOrNull() ?: nextEpisode(1))
                        count++
                        addRow("Part ${partMark.groupValues[1]}$qualitySuffix", href, resolvedSeason, rowEpisode)
                    }
                    !isSeries && explicitToken == null && textEpisodeNum == null -> {
                        val existing = episodes.firstOrNull()
                        if (existing != null) {
                            if (href != existing.url && href !in existing.mirrorUrls) {
                                val idx = episodes.indexOf(existing)
                                episodes[idx] = existing.copy(mirrorUrls = existing.mirrorUrls + href)
                            }
                        } else {
                            addRow("Full Movie$qualitySuffix", href, 1, 1)
                        }
                    }
                    else -> {
                        val tokenEpisodeNum = explicitToken?.second
                            ?: fraction?.first
                            ?: textEpisodeNum
                        val rowEpisode = declaredEpisode
                            ?: tokenEpisodeNum
                            ?: if (isDownloadAnchor && (maxEpisodeBySeason[resolvedSeason] ?: 0) > 0) {
                                maxEpisodeBySeason[resolvedSeason] ?: 1
                            } else nextEpisode(resolvedSeason)
                        val fracLabel = fraction?.let { ".${it.second}" }.orEmpty()
                        // §3a/§3b transparency: a remapped token keeps its
                        // raw file label, so no data is silently lost.
                        val rawTokenNote = if (explicitToken != null &&
                            explicitToken != (resolvedSeason.coerceAtLeast(1) to rowEpisode)
                        ) {
                            " (file labeled S%02dE%02d)".format(explicitToken.first, explicitToken.second)
                        } else ""
                        val displayTitle = if (isSeries) {
                            "S%02dE%02d".format(resolvedSeason.coerceAtLeast(1), rowEpisode) +
                                fracLabel + rawTokenNote + qualitySuffix
                        } else if (rowEpisode == 1) {
                            "Full Movie$qualitySuffix"
                        } else {
                            "Episode $rowEpisode$qualitySuffix"
                        }
                        addRow(displayTitle, href, resolvedSeason, rowEpisode)
                        count++
                    }
                }
            }

            // If no locker links found, check fallback download buttons
            if (episodes.isEmpty()) {
                val dls = doc.select(".entry-content a[href*='download'], .download-links a")
                for (a in dls) {
                    val href = resolvedHref(a, showUrl)
                    if (href.isBlank() || DynamicRulesManager.isKnownDead(href)) continue
                    if (!isLockerHref(a)) continue
                    val text = a.text().trim()
                    val file = href.substringBefore('?').substringBefore('#').substringAfterLast('/')
                    val smFb = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(file)
                    val epFb = Regex("""(?:^|[^a-z0-9])e(?:p)?[\s._-]*(\d{1,3})(?![a-z0-9])""", RegexOption.IGNORE_CASE).find(file)
                    val qualitySuffix = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(file)
                        ?.groupValues?.getOrNull(1)?.let { " [$it]" } ?: ""
                    val genericText = text.isBlank() || text.equals("Download", ignoreCase = true)
                    when {
                        smFb != null -> {
                            val s = (smFb.groupValues.getOrNull(1)?.toIntOrNull() ?: currentSeason).coerceAtLeast(1)
                            val e = smFb.groupValues.getOrNull(2)?.toIntOrNull() ?: nextEpisode(s)
                            addRow("S%02dE%02d".format(s, e) + qualitySuffix, href, s, e)
                        }
                        epFb != null -> {
                            val e = epFb.groupValues.getOrNull(1)?.toIntOrNull() ?: nextEpisode(currentSeason)
                            val label = if (genericText) "Episode $e" else text
                            addRow(label + qualitySuffix, href, currentSeason, e)
                        }
                        else -> {
                            val e = nextEpisode(currentSeason)
                            addRow(((if (genericText) "Download Link $count" else text) + qualitySuffix).trim(), href, currentSeason, e)
                            count++
                        }
                    }
                }
            }

            // Uniqueness net: duplicate legacy keys would make a by-number
            // re-resolve pick the wrong URL. Explicit season/episode stay
            // untouched; only the internal key moves.
            if (episodes.size > 1) {
                val keys = HashSet<Int>(episodes.size)
                var overflow = (episodes.maxOfOrNull { it.episodeNum } ?: 0) + 1
                for (i in episodes.indices) {
                    if (!keys.add(episodes[i].episodeNum)) {
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
                episodes = sortEpisodes(episodes)
            )
    }

    /** True when the anchor points at a known locker/direct file. */
    private fun isLockerHref(a: org.jsoup.nodes.Element): Boolean {
        val lower = a.attr("href").lowercase()
        return lower.contains("loadedfiles") ||
            lower.contains("downloadwella") ||
            lower.contains("wetafiles") ||
            lower.contains("waffi") ||
            lower.contains("vikingfile") ||
            lower.contains("lulacloud") ||
            lower.contains("kissorgrab") ||
            lower.contains("wildshare") ||
            lower.contains("pixeldrain") ||
            com.anonrode.downloader.pipeline.StrictLinkClassifier.isKnownLocker(lower) ||
            com.anonrode.downloader.pipeline.StrictLinkClassifier.isDirectMedia(lower)
    }

    private fun resolvedHref(a: org.jsoup.nodes.Element, showUrl: String): String {
        val raw = a.attr("href")
        return a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, raw) }
    }

    /** (season, episode) from an SxxEyy token in text or filename. */
    internal fun tokenEpisode(text: String, href: String): Pair<Int, Int>? {
        val m = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(href)
            ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return s to e
    }

    /** (episode, fraction) for a special like S02E5.5 / Episode 5.5. */
    internal fun fractionalEpisode(text: String, href: String): Pair<Int, String>? {
        val m = Regex("""(?i)\bS\d{1,2}E(\d{1,3})[.\-](\d{1,2})\b""").find(text)
            ?: Regex("""(?i)\bS\d{1,2}E(\d{1,3})[.\-](\d{1,2})\b""").find(href)
            ?: Regex("""(?i)\b(?:Episode|Ep)\s*(\d{1,3})[.,](\d{1,2})\b""").find(text)
            ?: Regex("""(?i)\b(?:Episode|Ep)\s*(\d{1,3})[.,](\d{1,2})\b""").find(href)
            ?: return null
        val e = m.groupValues[1].toIntOrNull() ?: return null
        val frac = m.groupValues[2].trim()
        return e to frac
    }

    /** §3a: "Episode N Added/Fixed" -> (1, N); "Episode X-Y Added" -> (X, Y). */
    internal fun episodeDeclaration(title: String): Pair<Int, Int>? {
        val range = Regex("""(?i)\bepisodes?\s*(\d{1,4})\s*[-–]\s*(\d{1,4})\s*(?:added|fixed)\b""").find(title)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull() ?: return null
            val end = range.groupValues[2].toIntOrNull() ?: return null
            if (end >= start && end - start <= 999) return start to end
        }
        val single = Regex("""(?i)\bepisodes?\s*(\d{1,4})\s*(?:added|fixed)\b""").find(title) ?: return null
        val n = single.groupValues[1].toIntOrNull() ?: return null
        if (n !in 1..999) return null
        return 1 to n
    }

    /** Filename style fingerprint: case profile of the pre-token prefix plus
     *  its separator shape ("LINK.CLICK.S04E01" vs "Link.Click.S03E02"). */
    internal fun filenameStyle(href: String): String {
        val file = href.substringBefore('?').substringAfterLast('/')
        val token = Regex("""(?i)S\d{1,2}E\d{1,3}""").find(file) ?: return "none"
        val prefix = file.substring(0, token.range.first)
        val letters = prefix.filter { it.isLetter() }
        val upper = letters.isNotEmpty() && letters.none { it.isLowerCase() }
        val shape = prefix.map { if (it.isLetterOrDigit()) 'a' else it }.joinToString("")
        return (if (upper) "U" else "L") + "|" + shape
    }

    private fun isZipAnchor(a: org.jsoup.nodes.Element): Boolean {
        val href = a.attr("href")
        return href.endsWith(".zip", ignoreCase = true) ||
            href.contains(".zip?", ignoreCase = true) ||
            a.text().contains("ZIP", ignoreCase = true)
    }

    /** Slug/title season pin; ranges and "Part I/II" are never a pin. */
    internal fun pagePinOf(showUrl: String, title: String): Int? {
        val rangeRe = Regex("""(?i)\bseason\s*[-_. ]?\s*\d{1,2}\s*[-–]\s*\d{1,2}\b""")
        if (rangeRe.containsMatchIn(title) || rangeRe.containsMatchIn(showUrl.replace('_', '-'))) return null
        return Regex("""(?i)(?:^|[/_-])season[-_](\d{1,2})(?!\d)""").find(showUrl)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)\bseason\s*(\d{1,2})\b""").find(title)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
    /** Pipeline rows with no season identity are stamped with the post's slug
     *  season (the only place 9jaRocks records it on text-only pages). */
    private fun stampPipelineEpisodes(episodes: List<EpisodeItem>, fallbackSeason: Int): List<EpisodeItem> =
        episodes.map { ep ->
            if (ep.season > 0 && ep.episode > 0) {
                ep.copy(episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(ep.season, ep.episode))
            } else {
                val season = ep.season.takeIf { it > 0 } ?: fallbackSeason.coerceAtLeast(1)
                val episode = ep.episode.takeIf { it > 0 }
                    ?: ep.episodeNum.takeIf { it in 1..999 }
                    ?: (ep.episodeNum % 1000).coerceAtLeast(1)
                ep.copy(
                    season = season,
                    episode = episode,
                    episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(season, episode)
                )
            }
        }

    /** Compiled rows win on season conflicts; a pipeline row is merged only
     *  when it carries explicit season info (never able to flatten S2 into S1). */
    internal fun mergeExplicitPipeline(
        compiled: List<EpisodeItem>,
        pipeline: List<EpisodeItem>
    ): List<EpisodeItem> {
        val out = compiled.toMutableList()
        for (row in pipeline) {
            if (row.season <= 0 || row.episode <= 0) continue
            val index = out.indexOfFirst { it.season == row.season && it.episode == row.episode }
            if (index < 0) {
                out.add(
                    row.copy(episodeNum = com.anonrode.downloader.util.SeasonEpisode.encode(row.season, row.episode))
                )
            } else {
                val existing = out[index]
                if (row.url != existing.url && row.url !in existing.mirrorUrls && row.url.isNotBlank()) {
                    out[index] = existing.copy(mirrorUrls = existing.mirrorUrls + row.url)
                }
            }
        }
        return out
    }

    private fun sortEpisodes(episodes: List<EpisodeItem>): List<EpisodeItem> =
        episodes.sortedWith(compareBy({ it.season.coerceAtLeast(1) }, { it.episode }, { it.title }))

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
