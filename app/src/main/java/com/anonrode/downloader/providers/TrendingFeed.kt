package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

/**
 * Trending-on-open feed (feature request: "the app should show what's
 * trending when one opens it, scrolling left to right").
 *
 * There is no cross-site "trending" API, so this approximates it the way the
 * sites themselves do: their front-page "latest posts" listings, which is
 * what's currently hot. Every source below is first-party WordPress plumbing
 * that needs NO query and was live-verified returning 200 on 2026-09-11:
 *  - naijavault / nkiri: WP-REST /wp-json/wp/v2/posts (JSON, featured media
 *    embedded -> real poster URLs)
 *  - naijaprey / 9jarocks: the WP /feed/ RSS (no /search/ prefix — the plain
 *    feed lists actual episode/movie posts, not nav garbage)
 *
 * Base URLs come from DynamicRulesManager, so an OTA base swap keeps the feed
 * working without an APK update. All four sites race concurrently with the
 * same 7s timeout search uses; whatever arrives in time fills the row, so one
 * slow site never blanks the section.
 */
object TrendingFeed {

    private const val PER_SITE_LIMIT = 8
    private const val ROW_LIMIT = 16
    private const val TIMEOUT_MS = 7000L

    /** Same nav-pattern NaijaPreyProvider uses to drop category pages. */
    private val NAV_GARBAGE = Regex(
        """/(?:download-(?:movies|series|tv|film|episode)(?:-[a-z0-9]{1,4})?|series-download(?:-v\d+)?|downloader|how-to-download.*)/?$""",
        RegexOption.IGNORE_CASE
    )

    suspend fun fetch(): List<ShowCard> = coroutineScope {
        val perSite = listOf(
            async { withTimeoutOrNull(TIMEOUT_MS) { fetchWpRest("naijavault") } ?: emptyList() },
            async { withTimeoutOrNull(TIMEOUT_MS) { fetchWpRest("nkiri") } ?: emptyList() },
            async { withTimeoutOrNull(TIMEOUT_MS) { fetchRss("naijaprey") } ?: emptyList() },
            async { withTimeoutOrNull(TIMEOUT_MS) { fetchRss("9jarocks") } ?: emptyList() }
        ).map { it.await() }

        // Round-robin interleave so the row leads with variety instead of
        // four NaijaVault posts before the first nkiri card.
        val out = mutableListOf<ShowCard>()
        val seenTitles = mutableSetOf<String>()
        var idx = 0
        while (out.size < ROW_LIMIT) {
            var advanced = false
            for (site in perSite) {
                if (idx < site.size) {
                    val card = site[idx]
                    val key = card.title.lowercase().replace(Regex("[^a-z0-9]"), "")
                    if (out.size < ROW_LIMIT && key.isNotBlank() && seenTitles.add(key)) {
                        out.add(card)
                    }
                    advanced = true
                }
            }
            if (!advanced) break
            idx++
        }
        com.anonrode.downloader.util.DebugLog.resolve(
            "trending feed: ${out.size} cards (per-site ${perSite.map { it.size }})"
        )
        out
    }

    /** WP-REST latest posts with embedded featured media. */
    private suspend fun fetchWpRest(site: String): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl(site).trimEnd('/')
        if (base.isBlank()) return emptyList()
        val url = "$base/wp-json/wp/v2/posts?per_page=$PER_SITE_LIMIT&_embed=1"
        val json = HttpClient.getText(url, referer = "$base/", tag = "trending") ?: return emptyList()
        val out = mutableListOf<ShowCard>()
        val noLinks = mutableListOf<ShowCard>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val title = item.optJSONObject("title")?.optString("rendered")
                    ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val link = item.optString("link")
                var poster = item.optString("jetpack_featured_media_url")
                if (poster.isBlank()) {
                    val featured = item.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")
                    if (featured != null && featured.length() > 0) {
                        poster = featured.getJSONObject(0).optString("source_url")
                    }
                }
                if (title.isNotBlank() && link.isNotBlank()) {
                    val card = ShowCard(title = title, url = link, posterUrl = poster, site = site)
                    val content = item.optJSONObject("content")?.optString("rendered") ?: ""
                    if (DownloadLinkGate.hasDownloadLink(content)) out.add(card) else noLinks.add(card)
                }
            }
        } catch (_: Exception) {}
        return if (out.isEmpty()) noLinks else out
    }

    /** WordPress front-page RSS: latest posts, poster scraped from the
     *  description HTML (same technique NaijaPreyProvider.search uses). */
    private suspend fun fetchRss(site: String): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl(site).trimEnd('/')
        if (base.isBlank()) return emptyList()
        val xml = HttpClient.getText("$base/feed/", referer = "$base/", tag = "trending") ?: return emptyList()
        val out = mutableListOf<ShowCard>()
        val noLinks = mutableListOf<ShowCard>()
        try {
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            for (item in doc.select("item")) {
                val title = item.selectFirst("title")?.text()
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                val link = item.selectFirst("link")?.text()?.trim() ?: ""
                val desc = item.selectFirst("content|encoded")?.text()
                    ?: item.selectFirst("description")?.text() ?: ""
                val poster = Regex(
                    """<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                ).find(desc)?.groupValues?.get(1) ?: ""
                if (title.isNotBlank() && link.isNotBlank() && !NAV_GARBAGE.containsMatchIn(link)) {
                    val card = ShowCard(title = title, url = link, posterUrl = poster, site = site)
                    if (DownloadLinkGate.hasDownloadLink(desc)) out.add(card) else noLinks.add(card)
                }
            }
        } catch (_: Exception) {}
        return (if (out.isEmpty()) noLinks else out).take(PER_SITE_LIMIT)
    }
}
