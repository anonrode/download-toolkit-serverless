package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.NameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

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

    /** Tile probes get a longer budget: the live-measured nepu/nkiri calls
     *  routinely take 8-15s, and the old 7s ceiling threw that art away. */
    internal const val TILE_TIMEOUT_MS = 10_000L

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
        // OTA kill-switch: a disabled site never enters the fan-out (and its
        // chip/tile slot is dropped), while an absent/old payload keeps every
        // site enabled.
        fun candidateSites(): List<String> = SiteGate.filterEnabled(sites.ifEmpty { SITE_FEEDS })
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
            queryTerms = mapOf(
                "nepu" to "sci-fi",
                "nkiri" to "sci-fi",
                "9jarocks" to "sci-fi",
                "naijaprey" to "sci-fi",
                "naijavault" to "sci-fi"
            ),
            sites = MOVIE_SITES,
            aliases = setOf("scifi", "sciencefiction")
        ),
        Category(
            "Thriller",
            queryTerms = mapOf("nepu" to "revenge"),
            sites = MOVIE_SITES,
            aliases = setOf("thriller", "suspense")
        ),
        Category(
            "Anime",
            queryTerms = mapOf("nkiri" to "anime", "9jarocks" to "anime", "anitaku" to "anime"),
            sites = listOf("anitaku", "nkiri", "9jarocks"),
            aliases = setOf("anime", "animation")
        )
    )

    /** How a site serves genre listings. */
    internal enum class FeedKind { RSS, WP_REST, API_JSON, ANIME_PROVIDER }

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
        "asianc" to (FeedKind.API_JSON to ""),
        "anitaku" to (FeedKind.ANIME_PROVIDER to "")
    )

    /** Global default priority (candidates without an explicit list). */
    internal val SITE_FEEDS: List<String> = listOf("nkiri", "9jarocks", "naijaprey", "naijavault")

    /** A Home genre tile: the category plus its current top-post artwork.
     *  [posterUrl] and [secondaryPosterUrl] are both REAL remote/cached URLs;
     *  the secondary slot is optional (blank when only one was found). */
    data class GenreTile(
        val category: Category,
        val posterUrl: String,
        val secondaryPosterUrl: String = ""
    )

    /**
     * Disjoint page window over a source's single-shot (page-parameter-less)
     * answer. Page 1 = the first [perPage] cards exactly as the historical
     * `.take(PER_ROW_LIMIT)`; page N = cards [(N-1)*perPage, N*perPage)
     * sliced BEFORE any caller-side per-card filtering, so page-1 output is
     * byte-identical to the old filter-after-trim behavior and windows never
     * overlap (the VM dedupes across pages by url/title). An empty window is
     * a proven end-of-feed — the honest signal that stops load-more — never
     * an error. For sources whose fetch limit can grow with the page (nepu:
     * `take(window + perPage)` upstream), windowing a superset bounded at
     * exactly [start + perPage] equals `drop(start)` on that fetch.
     * Pure, JVM-tested.
     */
    internal fun pageWindow(
        cards: List<ShowCard>,
        page: Int,
        perPage: Int = PER_ROW_LIMIT
    ): List<ShowCard> {
        if (page <= 1) return cards.take(perPage)
        val start = (page - 1) * perPage
        if (start <= 0 || start >= cards.size) return emptyList()
        return cards.drop(start).take(perPage)
    }

    /**
     * Fetch a genre page: ONE mixed card list. Sites race concurrently with
     * individual timeouts, and every arrival re-publishes the growing mixed
     * list through [onPartial] (same streaming contract as TrendingFeed —
     * a slow site never starves the grid). Runs under Dispatchers.IO from
     * the VM: one lock serializes slot writes + publishing.
     */
    suspend fun fetch(
        category: Category,
        page: Int = 1,
        filterExplicit: Boolean = true,
        onPartial: (List<ShowCard>) -> Unit = {}
    ): List<ShowCard> = coroutineScope {
        val candidates = category.candidateSites()
        val slots = arrayOfNulls<List<ShowCard>>(candidates.size)
        val lock = Any()
        candidates.mapIndexed { i, site ->
            async {
                val cards = withTimeoutOrNull(TIMEOUT_MS) { fetchSite(site, category, page, filterExplicit) } ?: emptyList()
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
            "category '${category.label}' (page $page): ${mixed.size} mixed (${pairs.joinToString { it.first + "=" + it.second.size }})"
        )
        mixed
    }

    /** One site's confirmed cards for a genre, by its feed kind. */
    private suspend fun fetchSite(site: String, category: Category, page: Int = 1, filterExplicit: Boolean = true): List<ShowCard> {
        if (!SiteGate.isEnabled(site)) return emptyList()
        val (kind, template) = FEED_SOURCES[site] ?: return emptyList()
        val term = category.termFor(site)
        return when (kind) {
            FeedKind.WP_REST -> TrendingFeed.fetchWpRest(
                site, query = term, page = page, limit = PER_ROW_LIMIT,
                extraParams = "&orderby=relevance",
                confirmTerms = category.aliases,
                filterExplicit = filterExplicit
            )
            FeedKind.RSS -> {
                val path = if (page > 1) {
                    String.format(template, encodedTerm(term)) + "?paged=$page"
                } else {
                    String.format(template, encodedTerm(term))
                }
                if (page > 1) {
                    fetchRssPage(site, path, category, page, filterExplicit)
                } else {
                    TrendingFeed.fetchRss(
                        site, path,
                        confirmTerms = category.aliases,
                        filterExplicit = filterExplicit
                    )
                }
            }
            FeedKind.API_JSON -> {
                if (site == "asianc") {
                    if (page > 1) {
                        fetchAsianCHtml(term, page, category.aliases, filterExplicit)
                    } else {
                        val rawCards = TrendingFeed.fetchApiSearch(site, term, PER_ROW_LIMIT)
                        if (filterExplicit) com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(rawCards) else rawCards
                    }
                } else {
                    // Nepu TMDB proxy has no page parameter: /api/search
                    // answers the WHOLE title-match set in one response, and
                    // the old page>1 hard-empty threw that surplus away so
                    // genre rows beyond page 1 never grew (trace row 33).
                    // Pages are disjoint windows over the same stable list
                    // ([pageWindow]); the fetch limit grows with the page so
                    // window N sees a monotone superset, page 1 keeps its
                    // exact historical output (fetch-12, take-12, filter),
                    // and an exhausted window is an honest end-of-feed.
                    val window = (page - 1).coerceAtLeast(0) * PER_ROW_LIMIT
                    val rawCards = pageWindow(
                        TrendingFeed.fetchApiSearch(site, term, window + PER_ROW_LIMIT),
                        page
                    )
                    val safeCards = if (filterExplicit) com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(rawCards) else rawCards
                    if (category.aliases.isNotEmpty()) {
                        safeCards.filter { TrendingFeed.genreConfirmed(it.title, it.genres.ifEmpty { it.tags }, category.aliases) }
                    } else {
                        safeCards
                    }
                }
            }
            FeedKind.ANIME_PROVIDER -> {
                // Anitaku search answers the full match set of ONE term
                // (admin-ajax batches, provider-cached ~5 min) — it has no
                // page parameter, and the old page>1 hard-empty capped the
                // Anime row at the same first 12 cards forever (trace row
                // 33). Pages are disjoint windows over the same stable list
                // ([pageWindow]; page 1 byte-identical to the old
                // .take(PER_ROW_LIMIT)); a window past the answer's end is
                // empty = honest end-of-feed. Deep pagination beyond the
                // search answer itself needs a page parameter inside
                // AnitakuProvider — deferred, not this file's to give.
                val animeCards = pageWindow(AnitakuProvider.search(term), page)
                if (filterExplicit) com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(animeCards) else animeCards
            }
        }
    }

    /** AsianC HTML search pagination for page > 1 */
    internal suspend fun fetchAsianCHtml(
        term: String,
        page: Int,
        aliases: Set<String>,
        filterExplicit: Boolean
    ): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl("asianc").ifBlank { "https://asianc.id" }.trimEnd('/')
        val url = "$base/search?type=drama&keyword=${encodedTerm(term)}&page=$page"
        val html = HttpClient.getText(url, referer = "$base/", tag = "browse") ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        val cards = mutableListOf<ShowCard>()
        for (item in doc.select("ul.listing.items li, .list-episode-item li, .video-block, li.filter-item")) {
            val a = item.selectFirst("a[href*='/drama-detail/']") ?: item.selectFirst("a[href]") ?: continue
            val rawHref = a.attr("href")
            if (rawHref.isBlank()) continue
            val fullUrl = if (rawHref.startsWith("/")) "$base$rawHref" else rawHref

            val titleEl = item.selectFirst(".title, h3, .name") ?: a
            val rawTitle = titleEl.text().trim()
            if (rawTitle.isBlank()) continue

            val img = item.selectFirst("img")
            val poster = img?.attr("abs:data-original")?.ifBlank {
                img.attr("data-original").ifBlank {
                    img.attr("abs:src").ifBlank { img.attr("src") }
                }
            }.orEmpty()

            val clean = NameSanitizer.cleanTitle(rawTitle)
            val card = ShowCard(
                title = clean.ifBlank { rawTitle },
                url = fullUrl,
                posterUrl = poster,
                site = "asianc",
                category = "Asian Drama",
                tags = listOf("Asian Drama"),
                genres = listOf("Asian Drama")
            )
            if (!filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(card)) {
                cards.add(card)
            }
            if (cards.size >= PER_ROW_LIMIT) break
        }
        return cards
    }

    /**
     * Pure mixing policy (JVM-tested): round-robin interleave across the
     * candidate sites in priority order — the row leads with variety, never
     * twelve nepu cards before the first nkiri one — normalized-title dedupe
     * (sites mirror each other). Works on PARTIAL
     * inputs: sites that have not answered yet are empty lists, so a
     * mid-stream publish is an ORDER-PRESERVING subset of the final mix —
     * a late site interleaves its own cards between what's on screen but
     * never reorders, removes, or duplicates a card already painted.
     */
    internal fun normalizeTitleKey(title: String, year: String = ""): String {
        var s = title.lowercase()
        // Strip trailing movie/region markers like | Movie, | Korean Movie, - Korean, - Chinese, [Chinese], etc.
        s = s.replace(Regex("""\s*\|\s*(korean\s+movie|chinese\s+movie|asian\s+movie|nollywood\s+movie|movie)\b.*"""), "")
        s = s.replace(Regex("""\s*-\s*(korean|chinese|asian|nollywood|yoruba|cinema)\b.*"""), "")
        s = s.replace(Regex("""\s*\[(chinese|korean|subbed|dubbed|complete)\]"""), "")
        val extractedYear = Regex("""\b(19\d\d|20\d\d)\b""").find(s)?.value ?: year.trim()
        s = s.replace(Regex("""\((?:19|20)\d{2}\)"""), "")
        val clean = s.filter { it.isLetterOrDigit() }.trim()
        return if (extractedYear.isNotBlank()) "${clean}_$extractedYear" else clean
    }

    internal fun mixCards(
        pairs: List<Pair<String, List<ShowCard>>>,
        filterExplicit: Boolean = true,
        limit: Int = Int.MAX_VALUE
    ): List<ShowCard> {
        val out = mutableListOf<ShowCard>()
        val seen = mutableMapOf<String, Int>()
        var idx = 0
        while (out.size < limit) {
            var advanced = false
            for ((_, cards) in pairs) {
                if (idx < cards.size) {
                    val card = cards[idx]
                    // Nollywood suppression rides the SAME purity-preserving
                    // gate as the explicit filter (OTA-switch-gated inside).
                    val isSafe = (!filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(card)) &&
                        !com.anonrode.downloader.util.NollywoodFilter.isNollywood(card)
                    if (isSafe) {
                        val key = normalizeTitleKey(card.title, card.year)
                        if (key.isNotBlank()) {
                            val existingIdx = seen[key]
                            if (existingIdx == null) {
                                if (out.size < limit) {
                                    seen[key] = out.size
                                    out.add(card)
                                }
                            } else {
                                // If duplicate is already present but incoming card has valid poster artwork, prefer it
                                val existing = out[existingIdx]
                                if (existing.posterUrl.isBlank() && card.posterUrl.isNotBlank()) {
                                    out[existingIdx] = card
                                }
                            }
                        }
                    }
                    advanced = true
                }
            }
            if (!advanced) break
            idx++
        }
        return out
    }

    /**
     * The Home genre-tile row: each category's CURRENT top-post artwork —
     * the first candidate site that answers with real art wins, so a nepu
     * genre with zero title matches (its search IS title matching) falls
     * through to nkiri's lite REST poster instead of a blank tile.
     * Lite requests only (~2KB via _fields / API per_page=1): tiles are
     * artwork, tapping opens the real gated page.
     */
    suspend fun tilePosters(filterExplicit: Boolean = true): List<GenreTile> = coroutineScope {
        CATEGORIES.map { category ->
            async {
                val urls = tilePostersFor(category, filterExplicit, max = 2)
                GenreTile(
                    category = category,
                    posterUrl = urls.getOrElse(0) { "" },
                    secondaryPosterUrl = urls.getOrElse(1) { "" }
                )
            }
        }.awaitAll()
    }

    /**
     * Up to [max] distinct REAL poster URLs for one category.
     *
     * The first three candidate sites are raced concurrently (each under
     * [TILE_TIMEOUT_MS]); results are consumed in candidate PRIORITY order,
     * so a fast lower-priority site never outranks the preferred source.
     * Slow sources are the known failure mode (thenkiri 8-15s), hence the
     * longer ceiling and the parallel race. Coroutine cancellation on an
     * early fill keeps the worst case at one timeout, not one per site.
     */
    internal suspend fun tilePostersFor(
        category: Category,
        filterExplicit: Boolean = true,
        max: Int = 2
    ): List<String> = coroutineScope {
        val sites = category.candidateSites().take(3)
        if (sites.isEmpty()) return@coroutineScope emptyList()
        val probes = sites.map { site ->
            async(Dispatchers.IO) {
                site to (
                    withTimeoutOrNull(TILE_TIMEOUT_MS) {
                        try {
                            tilePosterFromSite(site, category, filterExplicit)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            ""
                        }
                    } ?: ""
                    )
            }
        }
        val out = mutableListOf<String>()
        for (probe in probes) {
            val url = probe.await().second
            if (url.isNotBlank()) {
                val clean = PosterStore.normalizePosterUrl(url)
                if (clean.isNotBlank() && clean !in out) out.add(clean)
                if (out.size >= max) break
            }
        }
        if (out.size >= max) probes.forEach { it.cancel() }
        out
    }

    /** One site's best real poster URL for a genre (lite requests only). */
    private suspend fun tilePosterFromSite(
        site: String,
        category: Category,
        filterExplicit: Boolean
    ): String {
        val (kind, template) = FEED_SOURCES[site] ?: return ""
        val term = category.termFor(site)
        return when (kind) {
            FeedKind.WP_REST -> TrendingFeed.fetchWpRestLitePoster(site, term, filterExplicit)
            FeedKind.RSS -> TrendingFeed.fetchRss(
                site, String.format(template, encodedTerm(term)),
                confirmTerms = category.aliases,
                filterExplicit = filterExplicit
            ).firstOrNull {
                !filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(it)
            }?.posterUrl.orEmpty()
            FeedKind.API_JSON -> TrendingFeed.fetchApiSearch(site, term, 5)
                .firstOrNull {
                    !filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(it)
                }?.posterUrl.orEmpty()
            FeedKind.ANIME_PROVIDER -> AnitakuProvider.search(term)
                .firstOrNull {
                    !filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(it)
                }?.posterUrl.orEmpty()
        }
    }

    /**
     * Paginated RSS fetch with an explicit end-of-feed signal.
     *
     * `9jarocks .../search/action/feed/rss2/?paged=3 -> 404` is WordPress
     * saying "there is no page 3", not a transport error. It must not be
     * logged or retried. We remember the last valid page per feed so a later
     * page request short-circuits instead of re-probing the same 404.
     */
    private data class RssPageResult(
        val cards: List<ShowCard>,
        val endOfFeed: Boolean = false
    )

    private val rssEndPages = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val rssEndAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val rssEndTtlMs = 30L * 60_000L

    private suspend fun fetchRssPage(
        site: String,
        path: String,
        category: Category,
        page: Int,
        filterExplicit: Boolean
    ): List<ShowCard> {
        // Key WITHOUT the ?paged=N suffix: the 404 is a property of the feed,
        // not of one page, so page N+1 must short-circuit from page N's 404.
        val feedKey = "$site|${category.label}|${path.substringBefore("?paged=")}"
        val endedAt = rssEndPages[feedKey]
        if (endedAt != null && page > endedAt) {
            val checkedAt = rssEndAtMs[feedKey] ?: 0L
            if (System.currentTimeMillis() - checkedAt < rssEndTtlMs) return emptyList()
            // The feed may have grown since the 404; probe again.
            rssEndPages.remove(feedKey)
            rssEndAtMs.remove(feedKey)
        }

        val base = DynamicRulesManager.getBaseUrl(site).trimEnd('/')
        if (base.isBlank()) return emptyList()
        val url = "$base$path"

        val result = withContext(Dispatchers.IO) {
            try {
                HttpClient.awaitOriginCooldown(url)
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Referer", "$base/")
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .build()
                HttpClient.shared.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> RssPageResult(emptyList(), endOfFeed = true)
                        !response.isSuccessful -> RssPageResult(emptyList())
                        else -> {
                            val xml = response.body?.string().orEmpty()
                            RssPageResult(
                                if (xml.isBlank()) emptyList()
                                else TrendingFeed.parseRssItems(
                                    xml, site, base, category.aliases, filterExplicit
                                )
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                RssPageResult(emptyList())
            }
        }

        if (result.endOfFeed) {
            // Page N is gone => the feed ended at N-1. Remember it, never
            // log it as an error, never retry it.
            rssEndPages[feedKey] = (page - 1).coerceAtLeast(1)
            rssEndAtMs[feedKey] = System.currentTimeMillis()
            return emptyList()
        }
        if (result.cards.isNotEmpty()) {
            rssEndPages.remove(feedKey)
            rssEndAtMs.remove(feedKey)
        }
        return result.cards
    }

    private fun encodedTerm(term: String): String = java.net.URLEncoder.encode(term, "UTF-8")
}
