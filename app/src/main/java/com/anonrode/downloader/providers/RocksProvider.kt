package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.resolvers.ResolverRegistry
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
            val entry = doc.selectFirst(".entry-content") ?: doc.body()

            var currentSeason = 1
            val slugMatch = Regex("""season-(\d{1,2})""", RegexOption.IGNORE_CASE).find(showUrl)
                ?: Regex("""season\s*(\d{1,2})""", RegexOption.IGNORE_CASE).find(title)
            val isExplicitSingleSeasonPage = slugMatch != null
            if (isExplicitSingleSeasonPage) {
                currentSeason = slugMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            }

            var count = 1
            val allElements = entry.allElements
            for (elem in allElements) {
                val tagName = elem.tagName().lowercase()

                // Track active Season headings in page content (only if not an explicit single-season page)
                if (!isExplicitSingleSeasonPage && tagName in listOf("h1", "h2", "h3", "h4", "strong", "b", "p", "div")) {
                    val t = elem.text().trim()
                    val isExclusion = Regex("""\b(synopsis|storyline|about|comment|download|how to|click|added)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                    if (t.length < 60 && !isExclusion) {
                        val sm = Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b(?!\s*-\s*\d+)""", RegexOption.IGNORE_CASE).find(t)
                        if (sm != null) {
                            currentSeason = sm.groupValues.getOrNull(1)?.toIntOrNull() ?: currentSeason
                        }
                    }
                }

                if (tagName == "a" && elem.hasAttr("href")) {
                    val rawHref = elem.attr("href")
                    val href = elem.attr("abs:href").ifBlank {
                        HttpClient.safeResolveUri(showUrl, rawHref)
                    }
                    val lowerHref = href.lowercase()

                    if (href.isBlank() || href in seen || href.contains("error?e=", ignoreCase = true) || href.contains("errore=", ignoreCase = true)) continue
                    val isLocker = lowerHref.contains("loadedfiles") ||
                            lowerHref.contains("downloadwella") ||
                            lowerHref.contains("wetafiles") ||
                            lowerHref.contains("waffi") ||
                            lowerHref.contains("vikingfile") ||
                            lowerHref.contains("lulacloud") ||
                            lowerHref.contains("kissorgrab") ||
                            lowerHref.contains("wildshare") ||
                            lowerHref.contains("pixeldrain") ||
                            com.anonrode.downloader.pipeline.StrictLinkClassifier.isKnownLocker(href)

                    if (isLocker) {
                        seen.add(href)
                        val text = elem.text().trim()
                        val parentText = elem.parent()?.text()?.trim() ?: ""

                        // Season ZIP detection
                        val zipMatch = Regex("""\b(?:SEASON|S)\s*(\d{1,2})\b.*\bZIP\b""", RegexOption.IGNORE_CASE).find(parentText)
                        val isZip = zipMatch != null || parentText.contains("ZIP", ignoreCase = true) || href.contains("ZIP", ignoreCase = true)
                        if (isZip) {
                            val zipSeason = zipMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: currentSeason
                            val zipLabel = "S%02d Complete Season ZIP".format(zipSeason)
                            episodes.add(
                                EpisodeItem(
                                    title = zipLabel,
                                    url = href,
                                    episodeNum = count++,
                                    site = name
                                )
                            )
                            continue
                        }

                        val explicitSm = Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(parentText)
                            ?: Regex("""\bS(\d{1,2})E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(href)

                        val epMatch = Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""\b(?:EPISODE|EP|E)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(parentText)

                        val itemSeason = explicitSm?.groupValues?.getOrNull(1)?.toIntOrNull() ?: currentSeason
                        val qMatch = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(parentText)
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
                                ?: Regex("""\b(?:video\s+)?server\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(parentText)
                        } else null
                        val partMark = if (mirrorMark == null && explicitSm == null && epMatch == null) {
                            Regex("""\b(?:file\s+)?part\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(text)
                                ?: Regex("""\b(?:file\s+)?part\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(parentText)
                        } else null

                        when {
                            mirrorMark != null -> episodes.add(
                                EpisodeItem(
                                    title = "Server ${mirrorMark.groupValues[1]}$qualitySuffix",
                                    url = href,
                                    episodeNum = itemSeason * 100 + 1,
                                    site = name
                                )
                            )
                            partMark != null -> episodes.add(
                                EpisodeItem(
                                    title = "Part ${partMark.groupValues[1]}$qualitySuffix",
                                    url = href,
                                    episodeNum = itemSeason * 100 + (partMark.groupValues[1].toIntOrNull() ?: count),
                                    site = name
                                )
                            )
                            else -> {
                                val epNum = explicitSm?.groupValues?.getOrNull(2)?.toIntOrNull()
                                    ?: epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    ?: count
                                episodes.add(
                                    EpisodeItem(
                                        title = "S%02dE%02d".format(itemSeason, epNum) + qualitySuffix,
                                        url = href,
                                        episodeNum = itemSeason * 100 + epNum,
                                        site = name
                                    )
                                )
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
                        val text = a.text().trim().ifEmpty { "Download Link $count" }
                        episodes.add(
                            EpisodeItem(
                                title = text,
                                url = href,
                                episodeNum = count++,
                                site = name
                            )
                        )
                    }
                }
            }

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes.sortedBy { it.episodeNum })
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
        return DownloadRecipe(
            directUrl = target,
            filename = target.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" },
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
