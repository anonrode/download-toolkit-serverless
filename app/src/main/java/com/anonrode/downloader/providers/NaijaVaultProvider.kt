package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.EpisodeItem
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
        val results = mutableListOf<ShowCard>()
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // silently ignore others
        }
        return results
    }

    override suspend fun loadEpisodes(showUrl: String): ShowDetails {
        val show = ShowCard(title = "NaijaVault Media", url = showUrl, site = name)
        try {
            val html = HttpClient.getText(showUrl)
            if (html.isNullOrBlank()) {
                com.anonrode.downloader.util.DebugLog.error("naijavault loadEpisodes: fetch returned null for $showUrl (lastFailure=${HttpClient.lastFailure})")
                return ShowDetails(show = show)
            }
            com.anonrode.downloader.util.DebugLog.resolve("naijavault loadEpisodes: ${html.length / 1024}KiB from $showUrl")
            val doc = Jsoup.parse(html, showUrl)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Movie"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("abs:src")
                ?: ""
            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim() ?: ""

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
            val links: List<org.jsoup.nodes.Element> = if (otaSel != null) {
                val ota = doc.select(otaSel)
                val knownElsewhere = doc.select("a[href]").filter { a ->
                    val h = a.attr("abs:href").ifBlank { a.attr("href") }
                    com.anonrode.downloader.resolvers.LockerRegistry.isKnownMedia(h)
                }
                (ota + knownElsewhere).distinctBy { it.attr("abs:href").ifBlank { it.attr("href") } }
            } else {
                doc.select("a[href]")
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

                // Direct-media detection: the site rotates download hosts
                // (filevault, streamsss, streamwish, downloadwella, ...).
                // The stable signal is the FILE ITSELF: any href ending in a
                // media/archive extension or carrying a CDN marker. Using
                // LockerRegistry.classify instead of a hardcoded host list
                // means an unknown host works on first contact (no APK update
                // needed — live-verified: The Blood of Youth's 40 streamsss
                // episodes were silently dropped before this fix).
                val isDownloadLink = lowerHref.contains("/dl-") ||
                    lowerHref.contains("/cdn/") ||
                    (com.anonrode.downloader.resolvers.LockerRegistry.classify(href) != com.anonrode.downloader.resolvers.LockerRegistry.MediaKind.None)

                if (isDownloadLink) {
                    seen.add(href)
                    val text = a.text().trim()
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
                    """https?://[^\s"\'<>]*(?:filevault|downloadwella|wetafiles|loadedfiles|nkiserv|vikingfile|lulacloud|pixeldrain|waffi|gtoddl|wapkizfile|/cdn/)[^\s"\'<>]*|https?://[^\s"\'<>]+\.(?:mkv|mp4|webm|avi|zip|rar)[^\s"\'<>]*""",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                val rawSeen = mutableSetOf<String>()
                while (rawHrefs.find()) {
                    val u = rawHrefs.group().replace("&amp;", "&").trimEnd('.', ',', ')', ']')
                    if (u.isBlank() || u in rawSeen || u in seen) continue
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

            val card = ShowCard(title = title, url = showUrl, posterUrl = poster, site = name)
            com.anonrode.downloader.util.DebugLog.resolve("naijavault loadEpisodes: found ${episodes.size} episodes")
            return ShowDetails(show = card, synopsis = synopsis, episodes = episodes)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ShowDetails(show = show)
        }
    }
    override suspend fun resolveEpisode(episodeUrl: String, quality: String): DownloadRecipe {
        var direct = ResolverRegistry.resolve(episodeUrl, quality)
        if (direct == null && episodeUrl.contains("/dl-")) {
            try {
                val html = HttpClient.getText(episodeUrl) ?: ""
                val duMatch = Regex("var\\s+downloadURL\\s*=\\s*\"([^\"]+)\"").find(html)
                if (duMatch != null) {
                    val cdnUrl = duMatch.groupValues.getOrNull(1) ?: ""
                    if (cdnUrl.isNotBlank()) {
                        direct = ResolverRegistry.resolve(cdnUrl, quality) ?: cdnUrl
                    }
                } else {
                    // Fallback to scanning HTML for locker hosts. NaijaVault
                    // uses a wide variety of lockers (streamsss, streamwish,
                    // streamtape, doodstream, vidhide, mixdrop, mp4upload,
                    // vikingfile, lulacloud, waffi, etc.) — race ALL of them
                    // concurrently so dead lockers cost nothing (the sequential
                    // walk used to wait out every corpse). Missing any one means
                    // a show silently drops to "0 episodes" (live-verified: The
                    // Blood of Youth had 40 episodes on streamsss, all ignored).
                    val lockerMatches = Regex(
                        """https?://(?:www\.)?(?:streamsss|streamwish|streamtape|doodstream|dood\.|vidhide|mixdrop|mp4upload|vikingfile|lulacloud|waffi)\.[a-z0-9-]+/[^\s"'<>]+""",
                        RegexOption.IGNORE_CASE
                    ).findAll(html).map { it.groupValues.getOrNull(0) ?: "" }
                        .filter { it.isNotBlank() }.toList()
                    if (lockerMatches.isNotEmpty()) {
                        direct = ResolverRegistry.resolveAny(lockerMatches, quality)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        val finalUrl = direct ?: episodeUrl
        return DownloadRecipe(
            directUrl = finalUrl,
            filename = finalUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "movie.mp4" },
            backend = "aria2c",
            parallelSockets = 16
        )
    }
}
