package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.regex.Pattern

object NaijaVaultProvider : SiteProvider {
    override val name: String = "naijavault"
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
            val url = "$mainUrl/wp-json/wp/v2/posts?search=$encoded&_embed=1"
            val jsonStr = HttpClient.getText(url, tag = "search") ?: return emptyList()

            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val titleObj = item.optJSONObject("title")
                val title = titleObj?.optString("rendered")?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val link = item.optString("link")

                // Extract poster from featured media embedded JSON
                var poster = item.optString("jetpack_featured_media_url")
                if (poster.isBlank()) {
                    val embedded = item.optJSONObject("_embedded")
                    val featured = embedded?.optJSONArray("wp:featuredmedia")
                    if (featured != null && featured.length() > 0) {
                        poster = featured.getJSONObject(0).optString("source_url")
                    }
                }

                if (link.isNotBlank() && title.isNotBlank()) {
                    val card = ShowCard(
                        title = title,
                        url = link,
                        posterUrl = poster,
                        site = name,
                        category = "Nollywood & Series"
                    )
                    // Download-link gate (same zero-cost check trending uses):
                    // this endpoint already returns content.rendered, so the
                    // check is an in-memory string test on bytes we paid for
                    // anyway — search stays exactly as fast. It drops posts
                    // whose body carries no locker link: site-side stubs and
                    // YouTube-watch embeds (yooyotvlive wrappers) that open
                    // an empty drawer. If a whole batch misses the markers
                    // (unknown future locker host) the ungated batch is
                    // returned — no empty results page ever.
                    val content = item.optJSONObject("content")?.optString("rendered") ?: ""
                    if (DownloadLinkGate.hasDownloadLink(content)) results.add(card) else noLinks.add(card)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // silently ignore others
        }
        return if (results.isEmpty()) noLinks else results
    }

    private fun isJunkSameSite(href: String, siteHost: String?): Boolean =
        com.anonrode.downloader.pipeline.StrictLinkClassifier.isNavigationJunk(href, siteHost)

    // Movie mirrors retain their document order and server/part labels.
    internal fun movieDownloadItems(doc: org.jsoup.nodes.Document, showUrl: String): List<EpisodeItem> {
        val siteHost = try { URI(showUrl).host?.lowercase()?.removePrefix("www.") } catch (_: Exception) { null }
        val showPath = try { URI(showUrl).path ?: "" } catch (_: Exception) { "" }
        fun isNonDownloadTarget(href: String): Boolean =
            com.anonrode.downloader.pipeline.StrictLinkClassifier.isNavigationJunk(href, siteHost, showPath)

        var count = 0
        val items = doc.select("a#download-button").mapNotNull { b ->
            val raw = b.attr("href").trim()
            if (raw.isBlank() || raw.startsWith("#")) return@mapNotNull null
            val resolved = b.attr("abs:href").ifBlank {
                try { URI(showUrl).resolve(raw).toString() } catch (_: Exception) { "" }
            }.trim()
            if (isNonDownloadTarget(resolved) || isJunkSameSite(resolved, siteHost)) return@mapNotNull null
            count++
            val t = b.text().trim()
            EpisodeItem(
                title = DownloadLinkLabels.serverOrPart(t, resolved) ?: t.ifBlank { "Download $count" },
                url = resolved,
                episodeNum = count,
                site = name
            )
        }
        // Only explicit server labels establish interchangeable movie mirrors;
        // PART labels may describe different pieces of the movie.
        val mirrors = items.filter { Regex("^Server \\d+$").matches(it.title) }
            .map { it.url }.distinct().take(8)
        return items.map { item ->
            if (item.url in mirrors && Regex("^Server \\d+$").matches(item.title))
                item.copy(mirrorUrls = mirrors.filter { it != item.url })
            else item
        }
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "NaijaVault Media", url = showUrl, site = name)
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
            val html = HttpClient.getText(showUrl)
            if (html.isNullOrBlank()) {
                com.anonrode.downloader.util.DebugLog.error("naijavault loadEpisodes: fetch returned null for $showUrl")
                return ShowDetails(show = show)
            }
            com.anonrode.downloader.util.DebugLog.resolve("naijavault loadEpisodes: ${html.length / 1024}KiB from $showUrl")
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Movie"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src")
                ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

            // MOVIE-PAGE GUARD (live-verified 2026-09, My Name Is Khan). A
            // movie post's <article> carries category-download-movies-* and
            // exactly ONE download path: <a id="download-button"
            // href="https://www.lulacloud.com/d/..."><b>DOWNLOAD MOVIE</b></a>
            // — a cross-host gateway the OTA episodeSelector (a[href*='/dl-'],
            // a[href*='.mkv'], ...) never matches (0 selector hits across 90
            // anchors on that page), so the episode sweep instead scraped the
            // sidebar ("found 54 episodes" incl. 'Reacher Season 4' plus a
            // junk task at the bare homepage that cycled backoff forever).
            // Series posts carry category-tv and N "EPISODE NN" buttons on
            // same-host /dl-* links — they fail both checks here and keep the
            // episode-list path below.
            val dlButtons = doc.select("a#download-button")
            // Host of the page itself, shared by the movie fallback sweep and
            // the episode sweep; derived from the page URL so OTA base-url
            // swaps keep the junk guard working.
            val siteHost = try { URI(showUrl).host?.lowercase() } catch (_: Exception) { null }
            // "EPISODE NN" buttons always mean a series (posts can carry a
            // movie category alongside Series — live: 'Hello Future Me' is
            // Nollywood + Series), so they veto the movie path entirely.
            val episodeLabeled = dlButtons.any { it.text().contains("EPISODE", ignoreCase = true) }
            val isMoviePage = !episodeLabeled && (
                doc.selectFirst("article[class*=category-download-movies]") != null ||
                (dlButtons.size == 1 && dlButtons.first().text().contains("MOVIE", ignoreCase = true))
                )
            if (isMoviePage) {
                // Every #download-button on a MOVIE page is a download of
                // THIS film — live-verified 2026-09-13 (Project Sacrifice):
                // one button, "WATCH & DOWNLOAD MOVIE HERE". The shape this
                // replaces (firstOrNull) silently DROPPED server 2 whenever
                // a film was published on two mirror buttons. Navigation
                // buttons (bare homepage, self-page, fragments, javascript:)
                // are filtered by the unit-tested parser below — one of those
                // (NaijaVault homepage-as-movie, device log 2026-09-16)
                // spawned a task that cycled resolver backoff forever.
                val movieItems = movieDownloadItems(doc, showUrl).ifEmpty {
                    // No literal button (theme variant): any known locker
                    // link on the page is the movie's download.
                    // isJunkSameSite() guard applied here too — without it,
                    // a cached proven-locker host match could pick the site's
                    // own homepage or nav link as the download target.
                    val fallback = doc.select("a[href]").firstOrNull { a ->
                        val h = a.attr("abs:href").ifBlank { a.attr("href") }
                        !isJunkSameSite(h, siteHost) && com.anonrode.downloader.resolvers.LockerRegistry.isKnownMedia(h)
                    }?.attr("abs:href").orEmpty()
                    if (fallback.isBlank()) emptyList()
                    else listOf(EpisodeItem(title = "Download 1", url = fallback, episodeNum = 1, site = name))
                }
                if (movieItems.isNotEmpty()) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "naijavault movie page: ${movieItems.size} download button(s), first=${movieItems.first().url}"
                    )
                    return ShowDetails(
                        show = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name),
                        synopsis = synopsis,
                        episodes = MovieSizeMetadata.enrich(movieItems, showUrl)
                    )
                }
                // Movie page with no usable link: fall through to the sweep
                // (its same-site filter below keeps it junk-free) instead of
                // returning empty on a guard false-positive.
            }

            val episodes = mutableListOf<EpisodeItem>()
            val seen = mutableSetOf<String>()
            // OTA episodeSelector wins when the playbook declares one: it
            // matches on href substrings (a[href*='nkiserv'], a[href*='/dl-'],
            // ...) so it is theme-independent AND precise — the all-links
            // sweep below lets sidebar/comment links (other shows, #mh-
            // comments fragments) into the episode list as junk entries
            // (live-verified: ~23 junk per real download link).
            //
            // The selector is static, though — union in any OTHER link
            // classify() KNOWS as a direct file or a known locker
            // (playbook-seeded OR learned via HostHealth), so a host the
            // selector never listed (streamsss, streamwish, ...) still
            // surfaces. Unknown/deep same-site links stay excluded — they
            // are sidebar junk naijavault cannot resolve anyway.
            // Fallback = full-document scan (charter rule 3): the monolith
            // walks every <a> because the site's theme drops .entry-content
            // on some layouts — constraining to that container found zero
            // episodes (user-reported).
            val otaSel = DynamicRulesManager.getSiteConfig(name)
                ?.episodeSelector?.takeIf { it.isNotBlank() }
            // Same-site junk guard: on this site the only same-host download
            // links are /dl-* episode gateways (series) and /cdn/ streams.
            // Every other same-host href — the bare homepage, /category/,
            // /tag/, /season-list/, sibling post slugs like
            // '/reacher-season-4/' — is nav or sidebar/related-post junk
            // that LockerRegistry's showLike heuristic ("season" in the
            // slug) used to bless as Unknown media, yielding 54 junk
            // "episodes" on a movie page; one junk task even pointed at the
            // bare homepage and cycled resolver backoff forever. Host is
            // derived from the page itself so OTA base-url swaps keep it
            // working; cross-host lockers are untouched.
            val explicitButtons = doc.select("a#download-button, a[class*='button'], a.download-btn, a.btn-download")
            val explicitEpAnchors = doc.select("a[href]").filter { a ->
                val t = a.text().trim()
                val h = a.attr("href")
                Regex("""(?i)\b(?:Episode|Ep|E)[- ]*\d{1,4}\b""").containsMatchIn(t) ||
                    h.contains("/sdm_downloads/") ||
                    h.contains("/dl-")
            }
            val links: List<org.jsoup.nodes.Element> = if (otaSel != null) {
                val ota = doc.select(otaSel)
                val knownElsewhere = doc.select("a[href]").filter { a ->
                    val h = a.attr("abs:href").ifBlank { a.attr("href") }
                    !isJunkSameSite(h, siteHost) && (
                        com.anonrode.downloader.resolvers.LockerRegistry.isKnownMedia(h) ||
                        h.contains("/sdm_downloads/") ||
                        h.contains("/dl-")
                    )
                }
                (ota + explicitButtons + explicitEpAnchors + knownElsewhere)
                    .distinctBy { it.attr("abs:href").ifBlank { it.attr("href") } }
            } else {
                (doc.select("a[href]") + explicitButtons).distinctBy { it.attr("abs:href").ifBlank { it.attr("href") } }
            }

            var count = 1
            for (a in links) {
                val rawHref = a.attr("href")
                val href = a.attr("abs:href").ifBlank {
                    HttpClient.safeResolveUri(showUrl, rawHref)
                }
                val lowerHref = href.lowercase()

                if (href.isBlank() || href in seen) continue
                // Skip social/navigation links
                if (lowerHref.contains("telegram") || lowerHref.contains("facebook") || lowerHref.contains("twitter") || lowerHref.contains("whatsapp")) continue
                // Never treat the site's own homepage/category/tag/nav pages
                // as downloadable episodes (bug: a bare homepage URL spawned
                // a download task that failed "resolver chain EMPTY" and
                // cycled backoff forever).
                if (isJunkSameSite(href, siteHost)) continue

                val isExplicitEpisodeAnchor = Regex("""(?i)\b(?:Episode|Ep|E)[- ]*(\d{1,4})\b""").containsMatchIn(a.text()) ||
                    Regex("""(?i)\b(?:Episode|Ep|E)[- ]*(\d{1,4})\b""").containsMatchIn(href) ||
                    a.id() == "download-button" || a.className().contains("elementor-button")

                // Direct-media detection: the site rotates download hosts
                // (filevault, streamsss, streamwish, downloadwella, ...).
                // The stable signal is the FILE ITSELF: any href ending in a
                // media/archive extension or carrying a CDN marker. Using
                // LockerRegistry.classify instead of a hardcoded host list
                // means an unknown host works on first contact.
                val isDownloadLink = lowerHref.contains("/dl-") ||
                    lowerHref.contains("/cdn/") ||
                    lowerHref.contains("/sdm_downloads/") ||
                    isExplicitEpisodeAnchor ||
                    (com.anonrode.downloader.resolvers.LockerRegistry.classify(href) != com.anonrode.downloader.resolvers.LockerRegistry.MediaKind.None)

                if (isDownloadLink) {
                    seen.add(href)
                    val text = a.text().trim()
                    val parsedNum = Regex("""(?i)\b(?:Episode|Ep|E)[- ]*(\d{1,4})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(?i)\b(?:Episode|Ep|E)[- ]*(\d{1,4})\b""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(
                        EpisodeItem(
                            title = if (text.isNotBlank() && !text.equals("Download", ignoreCase = true)) text else "Download $count",
                            url = href,
                            episodeNum = parsedNum ?: count++,
                            site = name
                        )
                    )
                }
            }

            // ── Sequential Episode Gap-Detection & Targeted Recovery Sweep ──
            val existingNums = episodes.map { it.episodeNum }.toSet()
            if (existingNums.isNotEmpty()) {
                val minEp = existingNums.minOrNull() ?: 1
                val effectiveMin = if (minEp == 2) 1 else minEp
                val maxEp = existingNums.maxOrNull() ?: 1
                val missingNums = (effectiveMin..maxEp).filter { it !in existingNums }

                if (missingNums.isNotEmpty()) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "naijavault: detected missing episode gaps $missingNums in range [$effectiveMin..$maxEp], running targeted recovery"
                    )
                    val allDocAnchors = doc.select("a[href]")
                    for (missing in missingNums) {
                        val targetPattern = Regex("""(?i)\b(?:Episode|Ep|E)[- ]*0*${missing}\b""")
                        val candidateAnchor = allDocAnchors.firstOrNull { a ->
                            val aText = a.text().trim()
                            val aHref = a.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, a.attr("href")) }
                            val lowH = aHref.lowercase()
                            val pText = a.parent()?.text()?.trim().orEmpty()

                            val matchesNumber = targetPattern.containsMatchIn(aText) ||
                                targetPattern.containsMatchIn(pText) ||
                                targetPattern.containsMatchIn(aHref)

                            val isNotJunk = aHref.isNotBlank() &&
                                aHref !in seen &&
                                !lowH.contains("telegram") &&
                                !lowH.contains("facebook") &&
                                !lowH.contains("twitter") &&
                                !lowH.contains("whatsapp") &&
                                !isJunkSameSite(aHref, siteHost)

                            val isGatewayOrLockerOrDirect = lowH.contains("/sdm_downloads/") ||
                                lowH.contains("/dl-") ||
                                lowH.contains("/cdn/") ||
                                a.id() == "download-button" ||
                                (com.anonrode.downloader.resolvers.LockerRegistry.classify(aHref) != com.anonrode.downloader.resolvers.LockerRegistry.MediaKind.None)

                            matchesNumber && isNotJunk && isGatewayOrLockerOrDirect
                        }
                        if (candidateAnchor != null) {
                            val rawHref = candidateAnchor.attr("href")
                            val href = candidateAnchor.attr("abs:href").ifBlank { HttpClient.safeResolveUri(showUrl, rawHref) }
                            seen.add(href)
                            val text = candidateAnchor.text().trim()
                            val epTitle = if (text.isNotBlank() && !text.equals("Download", ignoreCase = true)) text else "Episode %02d".format(missing)
                            episodes.add(
                                EpisodeItem(
                                    title = epTitle,
                                    url = href,
                                    episodeNum = missing,
                                    site = name
                                )
                            )
                            com.anonrode.downloader.util.DebugLog.resolve("naijavault: gap-fill recovered episode $missing -> $href")
                        }
                    }
                }
            }

            // Monolith parity (naijavault.py line 295): if the DOM yielded zero
            // download links, regex the raw HTML — the filevault links sometimes
            // live in script-rendered sections that Jsoup's DOM builder misses
            // while the monolith's raw-text search finds them every time.
            if (episodes.isEmpty()) {
                // Diagnose WHICH page variant the server served on this visit —
                // user reported episodes on first open but empty on re-check,
                // which points at the server serving different HTML to an
                // established session. The snippet shows the truth in the log.
                val stripped = html.replace(Regex("""<script[\s\S]*?</script>"""), "").replace(Regex("""<style[\s\S]*?</style>"""), "")
                com.anonrode.downloader.util.DebugLog.error(
                    "naijavault: 0 links from DOM, page starts: ${stripped.take(200).replace("\n", " ")}"
                )
                val rawHrefs = Pattern.compile(
                    """https?://[^\s"\'<>]*(?:filevault|downloadwella|wetafiles|loadedfiles|nkiserv|vikingfile|lulacloud|pixeldrain|waffi|gtoddl|wapkizfile|/cdn/|/sdm_downloads/)[^\s"\'<>]*|https?://[^\s"\'<>]+\.(?:mkv|mp4|webm|avi|zip|rar)[^\s"\'<>]*""",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                val rawSeen = mutableSetOf<String>()
                while (rawHrefs.find()) {
                    val u = rawHrefs.group().replace("&amp;", "&").trimEnd('.', ',', ')', ']')
                    if (u.isBlank() || u in rawSeen || u in seen) continue
                    if (isJunkSameSite(u, siteHost)) continue
                    rawSeen.add(u)
                    episodes.add(
                        EpisodeItem(
                            title = "Download ${episodes.size + 1}",
                            url = u,
                            episodeNum = count++,
                            site = name
                        )
                    )
                }
                if (episodes.isNotEmpty()) {
                    com.anonrode.downloader.util.DebugLog.resolve("naijavault raw-text fallback: found ${episodes.size} locker URLs")
                }
            }

            // Enforce strict ascending order
            episodes.sortWith(compareBy({ it.episodeNum }, { it.title }))

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            com.anonrode.downloader.util.DebugLog.resolve("naijavault loadEpisodes: found ${episodes.size} episodes")
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ShowDetails(show = show)
        }
    }
    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        // OTA resolve recipe first (signed terminal-gated crack); the compiled
        // registry + /dl- page scan below stay the untouched fallback.
        var direct = RulesPipeline.runResolveForSite(name, episodeUrl, quality)
            ?: ResolverRegistry.resolve(episodeUrl, quality)
        if (direct == null && (episodeUrl.contains("/dl-") || episodeUrl.contains("/sdm_downloads/"))) {
            try {
                val html = HttpClient.getText(episodeUrl, referer = "https://www.naijavault.com/") ?: ""
                val duMatch = Regex("var\\s+downloadURL\\s*=\\s*\"([^\"]+)\"").find(html)
                if (duMatch != null) {
                    val cdnUrl = duMatch.groupValues.getOrNull(1) ?: ""
                    if (cdnUrl.isNotBlank()) {
                        direct = ResolverRegistry.resolve(cdnUrl, quality) ?: cdnUrl
                    }
                } else {
                    val sdmDoc = Jsoup.parse(html, episodeUrl)
                    val sdmBtn = sdmDoc.selectFirst("a.sdm_download, a[href*='sdm_process_download'], a.download-btn, a.btn-download")
                    val sdmHref = sdmBtn?.attr("abs:href")?.ifBlank { sdmBtn.attr("href") }
                    if (!sdmHref.isNullOrBlank()) {
                        if (sdmHref.contains("sdm_process_download") || sdmHref.contains("/sdm_downloads/")) {
                            val redirected = HttpClient.probeTerminal(sdmHref, referer = episodeUrl)?.url ?: sdmHref
                            direct = ResolverRegistry.resolve(redirected, quality) ?: redirected
                        } else {
                            direct = ResolverRegistry.resolve(sdmHref, quality) ?: sdmHref
                        }
                    }
                    if (direct == null) {
                        val lockerMatches = Regex(
                            """https?://(?:www\.)?(?:streamsss|streamwish|streamtape|doodstream|dood\.|vidhide|mixdrop|mp4upload|vikingfile|lulacloud|waffi|downloadwella|loadedfiles|wetafiles|wildshare)\.[a-z0-9-]+/[^\s"'<>]+""",
                            RegexOption.IGNORE_CASE
                        ).findAll(html).map { it.groupValues.getOrNull(0) ?: "" }
                            .filter { it.isNotBlank() }.toList()
                        if (lockerMatches.isNotEmpty()) {
                            direct = ResolverRegistry.resolveAny(lockerMatches, quality)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        val finalUrl = direct ?: ""
        val isHls = finalUrl.contains(".m3u8", ignoreCase = true) || finalUrl.contains("manifest", ignoreCase = true)
        return DownloadRecipe(
            directUrl = finalUrl,
            filename = finalUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" },
            backend = if (isHls) "yt-dlp" else "aria2c",
            parallelSockets = 16
        )
    }
}
