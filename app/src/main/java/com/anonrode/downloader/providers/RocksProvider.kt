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
        val results = mutableListOf<ShowCard>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val rssUrl = "$mainUrl/search/$encoded/feed/rss2/"
            val xml = HttpClient.getText(rssUrl) ?: return emptyList()

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
                    results.add(
                        ShowCard(
                            title = title,
                            url = link,
                            posterUrl = poster,
                            site = name,
                            category = "Nollywood & Movies"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "Movie", url = showUrl, site = name)
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
                            lowerHref.contains("lulacloud")

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
                        val epNum = explicitSm?.groupValues?.getOrNull(2)?.toIntOrNull()
                            ?: epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: count

                        val epCode = "S%02dE%02d".format(itemSeason, epNum)
                        val qMatch = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(parentText)
                        val qualitySuffix = qMatch?.groupValues?.getOrNull(1)?.let { " [$it]" } ?: ""

                        val label = "$epCode$qualitySuffix"

                        episodes.add(
                            EpisodeItem(
                                title = label,
                                url = href,
                                episodeNum = itemSeason * 100 + epNum,
                                site = name
                            )
                        )
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
        val direct = ResolverRegistry.resolve(episodeUrl, quality) ?: episodeUrl
        return DownloadRecipe(
            directUrl = direct,
            filename = direct.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" },
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
