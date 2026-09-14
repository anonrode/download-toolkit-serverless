package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Category sections (feature request: chips like "Action" under Trending, each
 * opening a page of per-site rows of that genre).
 *
 * One row = one site's answer for the genre. [rows] holds up to 3 rows in a
 * fixed site priority order; a site that is slow, errors or returns nothing
 * drops out silently so the next candidate fills its place — the row set is
 * "the first 3 sites that actually answered", never a fixed trio with holes.
 *
 * Mechanism (live-probed 2026-09-14, all six genres on all four sites): each
 * row is the site's genre listing — WP SEARCH feed (9jarocks, naijaprey) or
 * WP-REST search with embedded posters (nkiri, naijavault; their search feeds
 * carry no poster <img> at all). This deliberately is NOT a category
 * taxonomy: these sites' WP categories are REGIONAL (nkiri: K-Drama/TV
 * Series; 9jarocks: Hollywood/Anime; naijaprey: Movie/Series), so there is
 * no `/category/action/` to point at — but genre search returns genuinely
 * correct posts (Horror → Scream 7, Horror in the High Desert;
 * Romance → A Tailor-Made Romance, Chennai Love Story), and both feed shapes
 * are already what the app's own providers hit for ordinary search.
 *
 * Reuses TrendingFeed.fetchRss (its XML parse + poster sniff + stub gate) so
 * a category card is byte-for-byte the same ShowCard shape as a trending one.
 * Base URLs come from DynamicRulesManager inside fetchRss — an OTA base swap
 * keeps the rows working without an APK update.
 */
object CategoryFeed {

    /** How many rows the page shows at most. */
    const val MAX_ROWS = 3

    /** Cap per row so a 40-result feed doesn't balloon the horizontal scroll. */
    const val PER_ROW_LIMIT = 12

    private const val TIMEOUT_MS = 7000L

    /**
     * A tile label and the per-site query overrides. [queryTerms] lets one
     * chip query one site with the term that site's index responds to best;
     * no override ships today (all six bare lowercase genres responded on
     * every site probed), but this is the hook for when one needs
     * "science fiction" where another wants "sci-fi".
     */
    data class Category(val label: String, val queryTerms: Map<String, String> = emptyMap()) {
        fun termFor(site: String): String = queryTerms[site] ?: label.lowercase()
    }

    /** One genre row: a source site and its latest cards for the category. */
    data class CategoryRow(val site: String, val items: List<ShowCard>)

    /** Tile order on Home. Every genre here was probe-verified on >=3 sites. */
    val CATEGORIES: List<Category> = listOf(
        Category("Action"),
        Category("Comedy"),
        Category("Horror"),
        Category("Romance"),
        Category("Sci-Fi"),
        Category("Thriller")
    )

    /** How a site serves genre listings, and whether its posters survive. */
    internal enum class FeedKind { RSS, WP_REST }

    /**
     * Row priority: earlier sites fill the page first, later ones are the
     * fallback that steps in when an earlier site is slow or empty.
     *
     * Feed kind per site was decided by the 2026-09-14 probe, not taste:
     * 9jarocks' and naijaprey's search RSS items embed poster <img> tags
     * (22/22 and 5/5), while nkiri and naijavault serve poster-less text
     * feeds but answer WP-REST search with embedded featured media (5/5,
     * 3/3). Both shapes are the same endpoints the app already uses for
     * those sites' ordinary search/trending — CategoryFeed just points them
     * at the genre term. Internal so the JVM test pins the probe-verified
     * set, kinds and order.
     */
    internal val SITE_FEEDS: List<Triple<String, FeedKind, String>> = listOf(
        // (site, kind, RSS path template — the %s is the URL-encoded term; unused for WP_REST)
        Triple("nkiri", FeedKind.WP_REST, ""),
        Triple("9jarocks", FeedKind.RSS, "/search/%s/feed/rss2/"),
        Triple("naijaprey", FeedKind.RSS, "/search/%s/feed/rss2/"),
        Triple("naijavault", FeedKind.WP_REST, "")
    )

    /** A Home genre tile: the category plus its current top-post poster. */
    data class GenreTile(val category: Category, val posterUrl: String)

    /**
     * Fetch the genre page. Returns up to [MAX_ROWS] rows, each tagged with
     * its source site, in SITE_FEEDS priority order. Empty only if every site
     * failed/timed out — the caller shows a Retry affordance for that.
     */
    suspend fun fetch(category: Category): List<CategoryRow> = coroutineScope {
        // Kick all sites off concurrently; each carries its own timeout, so
        // one slow site never blanks the page.
        val jobs = SITE_FEEDS.map { (site, kind, template) ->
            async {
                val cards = withTimeoutOrNull(TIMEOUT_MS) {
                    when (kind) {
                        FeedKind.RSS ->
                            TrendingFeed.fetchRss(site, String.format(template, encodedTerm(category.termFor(site))))
                        FeedKind.WP_REST ->
                            TrendingFeed.fetchWpRest(site, query = category.termFor(site))
                    }.take(PER_ROW_LIMIT)
                } ?: emptyList()
                site to cards
            }
        }
        val pairs = jobs.map { it.await() }
        val rows = assembleRows(pairs)
        com.anonrode.downloader.util.DebugLog.resolve(
            "category '${category.label}': ${rows.size} rows (${rows.joinToString { it.site + "=" + it.items.size }})"
        )
        rows
    }

    /**
     * The Home genre-tile row: each category's CURRENT top post's poster,
     * fetched from the top-priority site (the same site whose first card the
     * opened page will show — tile and page lead with the same artwork).
     * One per_page=1 request per genre; a failure yields an empty posterUrl
     * and the tile renders as the colored name-tile fallback.
     */
    suspend fun tilePosters(): List<GenreTile> = coroutineScope {
        CATEGORIES.map { category ->
            async {
                val poster = withTimeoutOrNull(TIMEOUT_MS) {
                    val (site, kind, template) = SITE_FEEDS.first()
                    when (kind) {
                        FeedKind.RSS ->
                            TrendingFeed.fetchRss(site, String.format(template, encodedTerm(category.termFor(site))))
                        FeedKind.WP_REST -> TrendingFeed.fetchWpRest(site, query = category.termFor(site), limit = 1)
                    }.firstOrNull()?.posterUrl.orEmpty()
                } ?: ""
                GenreTile(category, poster)
            }
        }.map { it.await() }
    }

    private fun encodedTerm(term: String): String = java.net.URLEncoder.encode(term, "UTF-8")

    /**
     * Pure row assembly over (site, cards) pairs in priority order — kept
     * separate from the network fetch so JVM tests can exercise the whole
     * policy: drop empty sites, cap at [MAX_ROWS], and dedupe cross-site
     * reposts (these sites mirror each other's titles; a title surfaces once,
     * at the highest-priority site that has it).
     */
    internal fun assembleRows(pairs: List<Pair<String, List<ShowCard>>>): List<CategoryRow> {
        val rows = mutableListOf<CategoryRow>()
        val seenTitles = mutableSetOf<String>()
        for ((site, cards) in pairs) {
            if (rows.size >= MAX_ROWS) break
            val fresh = cards.filter { card ->
                val key = card.title.lowercase().replace(Regex("[^a-z0-9]"), "")
                key.isNotBlank() && seenTitles.add(key)
            }
            if (fresh.isNotEmpty()) rows.add(CategoryRow(site, fresh))
        }
        return rows
    }
}
