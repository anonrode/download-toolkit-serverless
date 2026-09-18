package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Category sections — the MIXED genre pages (v3.1.6 redesign, user: "dont
 * need to announce that its from so and so sites, lets just mix everything";
 * "browse my genre shouldnt scroll left and right, it should drop down…
 * 3 per row").
 *
 * One genre = ONE flat list: every candidate site's cards interleaved
 * round-robin (variety first, exactly like the trending row), deduped by
 * normalized title, capped at [GRID_CAP]. Site identity never appears on
 * the page — the drawer still shows it after a tap.
 *
 * Membership is CONFIRMED, not guessed (user: "i just want the genre to be
 * accurate"): WP cards must carry the genre in the site's own taxonomy
 * (`_embedded['wp:term']`, already in the fetched bytes) or in the title;
 * RSS cards likewise via the feed's <category> names / title. API sites
 * (nepu, asianc) search titles by construction — no bodies exist to leak
 * noise through. A site with nothing confirmed contributes nothing; the
 * next site fills. Accuracy over fullness.
 *
 * Per-genre candidate ORDER is data (Category.sites): the movie-heavy
 * genres lead with nepu (HD, TMDB-CDN posters — the fleet's fastest art),
 * Romance leads with asianc (the C/K-drama well the movie sites don't
 * carry). Terms are per-site too (Category.queryTerms): live-probed
 * 2026-09-15, nepu's search matches TITLES, so "action"/"thriller" return
 * ZERO while "fight"/"war"/"revenge" return 16-20 — the genre rows ask
 * nepu with the words its catalog actually answers.
 *
 * Base URLs come from DynamicRulesManager inside the fetchers — an OTA base
 * swap keeps genres working without an APK update.
 */
object CategoryFeed {

    /** Cap of the mixed grid page (3-wide, scroll-down — ~12 screens). */
    const val GRID_CAP = 36

    /** Cards taken per site before mixing (also the View-More row length). */
    const val PER_ROW_LIMIT = 12

    private const val TIMEOUT_MS = 7000L

    /**
     * A genre: label, per-site query overrides ([termFor]), the candidate
     * sites in priority order ([candidateSites]) and the NORMALIZED taxonomy
     * aliases that confirm membership ([aliases]). Empty sites/aliases fall
     * back to the global order / no confirmation.
     */
    data class Category(
        val label: String,
        val queryTerms: Map<String, String> = emptyMap(),
        val sites: List<String> = emptyList(),
        val aliases: Set<String> = emptySet()
    ) {
        fun termFor(site: String): String = queryTerms[site] ?: label.lowercase()
        // SITE_FEEDS is already the ordered list of site names, so the fallback
        // is the list itself (it used to be a list of pairs, and the leftover
        // `.map { it.first }` made `it` a String — that is a compile error, not
        // a silent reordering).
        fun candidateSites(): List<String> = sites.ifEmpty { SITE_FEEDS }
    }

    /** Probe-verified 2026-09-15: nepu leads the movie genres, asianc the
     *  kdrama-heavy Romance; the WP sites keep their 09-14 order behind. */
    private val MOVIE_SITES = listOf("nepu", "nkiri", "9jarocks", "naijaprey", "naijavault")
    private val ROMANCE_SITES = listOf("asianc", "nkiri", "9jarocks", "naijaprey", "naijavault")

    /** Tile order on Home. Every genre here was probe-verified on >=3 sites. */
    val CATEGORIES: List<Category> = listOf(
        Category(
            "Action",
            queryTerms = mapOf("nepu" to "fight"),
            sites = MOVIE_SITES,
            aliases = setOf("action", "martialarts")
        ),
        Category(
            "Comedy",
            sites = MOVIE_SITES,
            aliases = setOf("comedy", "sitcom")
        ),
        Category(
            "Horror",
            sites = MOVIE_SITES,
            aliases = setOf("horror")
        ),
        Category(
            "Romance",
            queryTerms = mapOf("nepu" to "love"),
            sites = ROMANCE_SITES,
            aliases = setOf("romance", "romantic", "love")
        ),
        Category(
            "Sci-Fi",
            sites = MOVIE_SITES,
            aliases = setOf("scifi", "sciencefiction")
        ),
        Category(
            "Thriller",
            queryTerms = mapOf("nepu" to "revenge"),
            sites = MOVIE_SITES,
            aliases = setOf("thriller", "suspense")
        )
    )

    /** How a site serves genre listings. */
    internal enum class FeedKind { RSS, WP_REST, API_JSON }

    /**
     * Feed kind + path template per site. The four WordPress sites were
     * decided by the 2026-09-14 probe (9jarocks/naijaprey search RSS embed
     * poster <img> 22/22 & 5/5; nkiri/naijavault serve posters via WP-REST
     * _embed search). nepu and asianc joined 2026-09-15 via their bespoke
     * JSON search APIs — neither is WordPress (no /wp-json, no search RSS).
     * Internal so the JVM test pins the probe-verified set and kinds.
     */
    internal val FEED_SOURCES: Map<String, Pair<FeedKind, String>> = mapOf(
        // RSS template: %s is the URL-encoded genre term (WP search feed).
        "nkiri" to (FeedKind.WP_REST to ""),
        "9jarocks" to (FeedKind.RSS to "/search/%s/feed/rss2/"),
        "naijaprey" to (FeedKind.RSS to "/search/%s/feed/rss2/"),
        "naijavault" to (FeedKind.WP_REST to ""),
        "nepu" to (FeedKind.API_JSON to ""),
        "asianc" to (FeedKind.API_JSON to "")
    )

    /** Global default priority (candidates without an explicit list). */
    internal val SITE_FEEDS: List<String> = listOf("nkiri", "9jarocks", "naijaprey", "naijavault")

    /** A Home genre tile: the category plus its current top-post poster. */
    data class GenreTile(val category: Category, val posterUrl: String)

    /**
     * Fetch a genre page: ONE mixed card list. Sites race concurrently with
     * individual timeouts, and every arrival re-publishes the growing mixed
     * list through [onPartial] (same streaming contract as TrendingFeed —
     * a slow site never starves the grid). Runs under Dispatchers.IO from
     * the VM: one lock serializes slot writes + publishing.
     */
    suspend fun fetch(
        category: Category,
        filterExplicit: Boolean = true,
        onPartial: (List<ShowCard>) -> Unit = {}
    ): List<ShowCard> = coroutineScope {
        val candidates = category.candidateSites()
        val slots = arrayOfNulls<List<ShowCard>>(candidates.size)
        val lock = Any()
        candidates.mapIndexed { i, site ->
            async {
                val cards = withTimeoutOrNull(TIMEOUT_MS) { fetchSite(site, category, filterExplicit) } ?: emptyList()
                synchronized(lock) {
                    slots[i] = cards
                    if (cards.isNotEmpty()) {
                        onPartial(mixCards(candidates.mapIndexed { j, s -> s to (slots[j] ?: emptyList()) }, filterExplicit))
                    }
                }
            }
        }.awaitAll()
        val pairs = candidates.mapIndexed { j, site -> site to (slots[j] ?: emptyList()) }
        val mixed = mixCards(pairs, filterExplicit)
        com.anonrode.downloader.util.DebugLog.resolve(
            "category '${category.label}': ${mixed.size} mixed (${pairs.joinToString { it.first + "=" + it.second.size }})"
        )
        mixed
    }

    /** One site's confirmed cards for a genre, by its feed kind. */
    private suspend fun fetchSite(site: String, category: Category, filterExplicit: Boolean = true): List<ShowCard> {
        val (kind, template) = FEED_SOURCES[site] ?: return emptyList()
        val term = category.termFor(site)
        return when (kind) {
            FeedKind.WP_REST -> TrendingFeed.fetchWpRest(
                site, query = term, limit = PER_ROW_LIMIT,
                extraParams = "&orderby=relevance",
                confirmTerms = category.aliases,
                filterExplicit = filterExplicit
            )
            FeedKind.RSS -> TrendingFeed.fetchRss(
                site, String.format(template, encodedTerm(term)),
                confirmTerms = category.aliases,
                filterExplicit = filterExplicit
            )
            FeedKind.API_JSON -> {
                val cards = TrendingFeed.fetchApiSearch(site, term, PER_ROW_LIMIT)
                if (filterExplicit) com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(cards) else cards
            }
        }
    }

    /**
     * Pure mixing policy (JVM-tested): round-robin interleave across the
     * candidate sites in priority order — the row leads with variety, never
     * twelve nepu cards before the first nkiri one — normalized-title dedupe
     * (sites mirror each other), capped at [GRID_CAP]. Works on PARTIAL
     * inputs: sites that have not answered yet are empty lists, so a
     * mid-stream publish is an ORDER-PRESERVING subset of the final mix —
     * a late site interleaves its own cards between what's on screen but
     * never reorders, removes, or duplicates a card already painted.
     */
    internal fun mixCards(
        pairs: List<Pair<String, List<ShowCard>>>,
        filterExplicit: Boolean = true
    ): List<ShowCard> {
        val out = mutableListOf<ShowCard>()
        val seen = mutableSetOf<String>()
        var idx = 0
        while (out.size < GRID_CAP) {
            var advanced = false
            for ((_, cards) in pairs) {
                if (idx < cards.size) {
                    val card = cards[idx]
                    val key = card.title.lowercase().filter { it.isLetterOrDigit() }
                    val isSafe = !filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(card)
                    if (out.size < GRID_CAP && key.isNotBlank() && isSafe && seen.add(key)) out.add(card)
                    advanced = true
                }
            }
            if (!advanced) break
            idx++
        }
        return out
    }

    /**
     * The Home genre-tile row: each category's CURRENT top-post poster —
     * the first candidate site that answers with artwork wins, so a nepu
     * genre with zero title matches (its search IS title matching) falls
     * through to nkiri's lite REST poster instead of a blank tile.
     * Lite requests only (~2KB via _fields / API per_page=1): tiles are
     * artwork, tapping opens the real gated page.
     */
    suspend fun tilePosters(filterExplicit: Boolean = true): List<GenreTile> = coroutineScope {
        CATEGORIES.map { category ->
            async { GenreTile(category, tilePosterFor(category, filterExplicit)) }
        }.awaitAll()
    }

    private suspend fun tilePosterFor(category: Category, filterExplicit: Boolean = true): String {
        for (site in category.candidateSites()) {
            val (kind, template) = FEED_SOURCES[site] ?: continue
            val term = category.termFor(site)
            val poster = withTimeoutOrNull(TIMEOUT_MS) {
                when (kind) {
                    FeedKind.WP_REST -> TrendingFeed.fetchWpRestLitePoster(site, term, filterExplicit)
                    FeedKind.RSS -> TrendingFeed.fetchRss(
                        site, String.format(template, encodedTerm(term)),
                        confirmTerms = category.aliases,
                        filterExplicit = filterExplicit
                    ).firstOrNull { !filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(it) }?.posterUrl.orEmpty()
                    FeedKind.API_JSON -> TrendingFeed.fetchApiSearch(site, term, 5)
                        .firstOrNull { !filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(it) }?.posterUrl.orEmpty()
                }
            }.orEmpty()
            if (poster.isNotBlank()) return poster
        }
        return ""
    }

    private fun encodedTerm(term: String): String = java.net.URLEncoder.encode(term, "UTF-8")
}
