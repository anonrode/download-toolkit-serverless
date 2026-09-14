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
            async { withTimeoutOrNull(TIMEOUT_MS) { fetchRss("naijaprey", "/feed/") } ?: emptyList() },
            async { withTimeoutOrNull(TIMEOUT_MS) { fetchRss("9jarocks", "/feed/") } ?: emptyList() }
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

    /**
     * WP-REST posts with embedded featured media. [query] switches from the
     * latest-posts feed to the site's SEARCH endpoint — live-verified
     * 2026-09-14: nkiri's and naijavault's search *feeds* carry no poster
     * <img> at all, but REST search answers every genre query with embedded
     * posters (5/5 and 3/3), so CategoryFeed rows and the Home genre tiles
     * get real artwork from those two sites only through this path.
     */
    internal suspend fun fetchWpRest(site: String, query: String? = null, limit: Int = PER_SITE_LIMIT): List<ShowCard> =
        fetchWpRestFrom(DynamicRulesManager.getBaseUrl(site), site, query, limit)

    /** Same fetch against an explicit base host — NkiriProvider.search uses
     *  this to run its ISP-block mirror failover over the REST endpoint. */
    internal suspend fun fetchWpRestFrom(
        base: String,
        site: String,
        query: String?,
        limit: Int,
        extraParams: String = "",
        tag: String = "trending"
    ): List<ShowCard> {
        val url = wpRestUrl(base, query, limit, extraParams) ?: return emptyList()
        val json = HttpClient.getText(url, referer = "${base.trimEnd('/')}/", tag = tag) ?: return emptyList()
        return gateWpRest(parseWpRestPosts(json, site))
    }

    /** Pure endpoint assembly — shape live-verified 2026-09-14 on nkiri.top
     *  and naijavault (`orderby=relevance` accepted WITH a search param). */
    internal fun wpRestUrl(base: String, query: String?, limit: Int, extraParams: String = ""): String? {
        val clean = base.trimEnd('/')
        if (clean.isBlank()) return null
        val search = if (query == null) "" else "&search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return "$clean/wp-json/wp/v2/posts?per_page=$limit$search$extraParams&_embed=1"
    }

    /** A parsed card plus the rendered post body it came from (gate input). */
    internal data class RestPost(val card: ShowCard, val body: String)

    /** Pure WP-REST parse — JSON in, cards out, no network, JVM-testable. */
    internal fun parseWpRestPosts(json: String, site: String): List<RestPost> {
        val out = mutableListOf<RestPost>()
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
                    out.add(
                        RestPost(
                            card = ShowCard(title = title, url = link, posterUrl = poster, site = site),
                            body = item.optJSONObject("content")?.optString("rendered") ?: ""
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Stub-drop with the caller-keeps-its-fallback policy shared with
     *  [fetchRss]: all-miss (unknown locker family) keeps the ungated batch. */
    internal fun gateWpRest(posts: List<RestPost>): List<ShowCard> {
        val out = posts.filter { DownloadLinkGate.hasDownloadLink(it.body) }.map { it.card }
        return if (out.isEmpty()) posts.map { it.card } else out
    }

    /** WordPress front-page RSS: latest posts, poster scraped from the
     *  description HTML (same technique NaijaPreyProvider.search uses).
     *
     * [path] is the feed endpoint under the base URL. CategoryFeed calls this
     * with the site's WP *search* feed (`/search/<q>/feed/…`) so the two
     * features share one RSS parser — poster sniffing, the NAV_GARBAGE filter
     * and the DownloadLinkGate stub-drop fallback exist once, not twice.
     */
    internal suspend fun fetchRss(site: String, path: String): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl(site).trimEnd('/')
        if (base.isBlank()) return emptyList()
        val xml = HttpClient.getText("$base$path", referer = "$base/", tag = "trending") ?: return emptyList()
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
