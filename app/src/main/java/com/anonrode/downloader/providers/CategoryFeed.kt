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
 * row is the site's WordPress SEARCH feed with the genre as query
 * (`/search/<term>/feed/…`). This deliberately is NOT a category taxonomy:
 * these sites' WP categories are REGIONAL (nkiri: K-Drama/TV Series;
 * 9jarocks: Hollywood/Anime; naijaprey: Movie/Series), so there is no
 * `/category/action/` to point at — but their search feeds return genuinely
 * genre-correct posts (Horror → Scream 7, Horror in the High Desert;
 * Romance → A Tailor-Made Romance, Chennai Love Story), and two of the four
 * URL shapes here are already what RocksProvider/NaijaPreyProvider hit for
 * ordinary search. Per-site path differs (rss2 vs plain feed); both were
 * verified 200 returning items.
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
     * A chip label and the per-site query overrides. [queryTerms] lets one
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

    /** Chip order on Home. Every genre here was probe-verified on >=3 sites. */
    val CATEGORIES: List<Category> = listOf(
        Category("Action"),
        Category("Comedy"),
        Category("Horror"),
        Category("Romance"),
        Category("Sci-Fi"),
        Category("Thriller")
    )

    /**
     * Row priority: earlier sites fill the page first, later ones are the
     * fallback that steps in when an earlier site is slow or empty. Internal
     * so the JVM test pins the probe-verified set and order.
     */
    internal val SITE_PATHS: List<Pair<String, String>> = listOf(
        // site to its search-feed path template (the %s is the URL-encoded term)
        "nkiri" to "/search/%s/feed/",
        "9jarocks" to "/search/%s/feed/rss2/",
        "naijaprey" to "/search/%s/feed/rss2/",
        "naijavault" to "/search/%s/feed/"
    )

    /**
     * Fetch the genre page. Returns up to [MAX_ROWS] rows, each tagged with
     * its source site, in SITE_PATHS priority order. Empty only if every site
     * failed/timed out — the caller shows a Retry affordance for that.
     */
    suspend fun fetch(category: Category): List<CategoryRow> = coroutineScope {
        // Kick all sites off concurrently; each carries its own timeout, so
        // one slow site never blanks the page.
        val jobs = SITE_PATHS.map { (site, template) ->
            async {
                val term = java.net.URLEncoder.encode(category.termFor(site), "UTF-8")
                val path = String.format(template, term)
                val cards = withTimeoutOrNull(TIMEOUT_MS) {
                    TrendingFeed.fetchRss(site, path).take(PER_ROW_LIMIT)
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
