package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.NameSanitizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /**
     * Card-title hygiene for every feed parser below: scraped titles carry
     * site decoration junk ("[Episode 1-16 Complete]", "_Watch Online_",
     * "(Korean Drama)") and entity escapes ("&#8211;") straight into the
     * Home UI, and their per-site variants defeat the cross-site dedup in
     * [mergeRoundRobin] / CategoryFeed.mixCards ("Show (Korean Drama)" vs
     * "Show" never key equal). Running the standard noise layer aligns the
     * variants so the same show dedupes across sites and reads like a title.
     * `.ifBlank { raw }` is the hard floor: a title can never vanish.
     * Taxonomy terms (wp:term, RSS <category>) deliberately stay RAW — the
     * explicit filter and genreConfirmed alias matching read them as-is.
     */
    private fun cleanCardTitle(raw: String): String =
        NameSanitizer.cleanTitle(raw).ifBlank { raw }

    /** Same nav-pattern NaijaPreyProvider uses to drop category pages. */
    private val NAV_GARBAGE = Regex(
        """/(?:download-(?:movies|series|tv|film|episode)(?:-[a-z0-9]{1,4})?|series-download(?:-v\d+)?|downloader|how-to-download.*)/?$""",
        RegexOption.IGNORE_CASE
    )

    suspend fun fetch(
        filterExplicit: Boolean = true,
        onPartial: (List<ShowCard>) -> Unit = {}
    ): List<ShowCard> = coroutineScope {
        // Per-site result slots in the CANONICAL order (naijavault, nkiri,
        // naijaprey, 9jarocks). Each site publishes a re-merge the moment it
        // lands — v3.1.5 awaited ALL sites before showing anything, so one
        // slow feed (9jarocks' RSS is the usual laggard, up to its full 7 s
        // timeout) starved cards that had already arrived: the device saw
        // "content not loading" while bytes sat unseen. Callers may run this
        // on Dispatchers.IO, where the four asyncs finish on different
        // threads — one lock serializes slot writes + partial publishing so
        // observers never see a torn or out-of-order merge.
        val slots = arrayOfNulls<List<ShowCard>>(4)
        val lock = Any()
        suspend fun fetchSlot(i: Int, block: suspend () -> List<ShowCard>) {
            val cards = withTimeoutOrNull(TIMEOUT_MS) { block() } ?: emptyList()
            synchronized(lock) {
                slots[i] = cards
                if (cards.isNotEmpty()) onPartial(mergeRoundRobin(slots.map { it ?: emptyList() }, filterExplicit))
            }
        }
        listOf(
            async { fetchSlot(0) { fetchWpRest("naijavault", filterExplicit = filterExplicit) } },
            async { fetchSlot(1) { fetchWpRest("nkiri", filterExplicit = filterExplicit) } },
            async { fetchSlot(2) { fetchRss("naijaprey", "/feed/", filterExplicit = filterExplicit) } },
            async { fetchSlot(3) { fetchRss("9jarocks", "/feed/", filterExplicit = filterExplicit) } }
        ).awaitAll()
        val perSite = slots.map { it ?: emptyList() }

        // Final merge == the same pure function, so the last publish and the
        // return value are consistent by construction.
        val out = mergeRoundRobin(perSite, filterExplicit)
        com.anonrode.downloader.util.DebugLog.resolve(
            "trending feed: ${out.size} cards (per-site ${perSite.map { it.size }})"
        )
        out
    }

    /**
     * Round-robin interleave so the row leads with variety instead of
     * four NaijaVault posts before the first nkiri card. PURE + deterministic
     * (unit-tested); works on PARTIAL inputs too — unarrived sites are
     * simply empty lists, so a two-site merge is a prefix-consistent preview
     * of the four-site merge.
     */
    internal fun mergeRoundRobin(
        perSite: List<List<ShowCard>>,
        filterExplicit: Boolean = true
    ): List<ShowCard> {
        val out = mutableListOf<ShowCard>()
        val seenTitles = mutableSetOf<String>()
        var idx = 0
        while (out.size < ROW_LIMIT) {
            var advanced = false
            for (site in perSite) {
                if (idx < site.size) {
                    val card = site[idx]
                    val key = card.title.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val isSafe = !filterExplicit || !com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(card)
                    if (out.size < ROW_LIMIT && key.isNotBlank() && isSafe && seenTitles.add(key)) {
                        out.add(card)
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
     * WP-REST posts with embedded featured media. [query] switches from the
     * latest-posts feed to the site's SEARCH endpoint — live-verified
     * 2026-09-14: nkiri's and naijavault's search *feeds* carry no poster
     * <img> at all, but REST search answers every genre query with embedded
     * posters (5/5 and 3/3), so CategoryFeed rows and the Home genre tiles
     * get real artwork from those two sites only through this path.
     */
    internal suspend fun fetchWpRest(
        site: String,
        query: String? = null,
        limit: Int = PER_SITE_LIMIT,
        extraParams: String = "",
        confirmTerms: Set<String>? = null,
        filterExplicit: Boolean = true
    ): List<ShowCard> =
        fetchWpRestFrom(
            DynamicRulesManager.getBaseUrl(site), site, query, limit,
            extraParams = extraParams, confirmTerms = confirmTerms,
            filterExplicit = filterExplicit
        )

    /** Same fetch against an explicit base host — NkiriProvider.search uses
     *  this to run its ISP-block mirror failover over the REST endpoint.
     *
     * [confirmTerms] (v3.1.6, genre pages only): normalized genre aliases.
     * When set, a card must be CONFIRMED by the site's own taxonomy (the
     * wp:term category/tag names already inside the _embed payload) or by a
     * title match — WP full-text search alone matches bodies, which is how
     * "sci-fi mentioned in a kdrama post" polluted the Sci-Fi row. Null
     * (trending, provider search, verify) keeps behavior byte-identical. */
    internal suspend fun fetchWpRestFrom(
        base: String,
        site: String,
        query: String?,
        limit: Int,
        extraParams: String = "",
        tag: String = "trending",
        confirmTerms: Set<String>? = null,
        filterExplicit: Boolean = true
    ): List<ShowCard> {
        val url = wpRestUrl(base, query, limit, extraParams) ?: return emptyList()
        val json = HttpClient.getText(url, referer = "${base.trimEnd('/')}/", tag = tag) ?: return emptyList()
        return gateWpRest(parseWpRestPosts(json, site), confirmTerms, filterExplicit = filterExplicit)
    }

    /** Pure endpoint assembly — shape live-verified 2026-09-14 on nkiri.top
     *  and naijavault (`orderby=relevance` accepted WITH a search param). */
    internal fun wpRestUrl(base: String, query: String?, limit: Int, extraParams: String = ""): String? {
        val clean = base.trimEnd('/')
        if (clean.isBlank()) return null
        val search = if (query == null) "" else "&search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return "$clean/wp-json/wp/v2/posts?per_page=$limit$search$extraParams&_embed=1"
    }

    /** A parsed card plus the rendered post body it came from (gate input)
     *  and the site's own category/tag term NAMES from `_embedded['wp:term']`
     *  (genre confirmation input — already in the bytes we fetch today). */
    internal data class RestPost(
        val card: ShowCard,
        val body: String,
        val terms: List<String> = emptyList()
    )

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
                // wp:term is an array of taxonomy groups (categories, post_tag),
                // each an array of {name, taxonomy, ...} — harvest every NAME.
                val terms = mutableListOf<String>()
                val termGroups = item.optJSONObject("_embedded")?.optJSONArray("wp:term")
                if (termGroups != null) {
                    for (g in 0 until termGroups.length()) {
                        val group = termGroups.optJSONArray(g) ?: continue
                        for (t in 0 until group.length()) {
                            val name = group.optJSONObject(t)?.optString("name").orEmpty()
                            if (name.isNotBlank()) terms.add(name)
                        }
                    }
                }
                if (title.isNotBlank() && link.isNotBlank()) {
                    out.add(
                        RestPost(
                            card = ShowCard(title = cleanCardTitle(title), url = link, posterUrl = poster, site = site),
                            body = item.optJSONObject("content")?.optString("rendered") ?: "",
                            terms = terms
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Stub-drop with the caller-keeps-its-fallback policy shared with
     *  [fetchRss]: all-miss (unknown locker family) keeps the ungated batch —
     *  BUT only when no [confirmTerms] is set. A genre row would rather show
     *  nothing than show noise: accuracy over fullness, the next site fills. */
    internal fun gateWpRest(
        posts: List<RestPost>,
        confirmTerms: Set<String>? = null,
        filterExplicit: Boolean = true
    ): List<ShowCard> {
        val safePosts = if (filterExplicit) {
            posts.filterNot { com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(it.card.title, it.terms) }
        } else {
            posts
        }
        val gated = safePosts.filter { DownloadLinkGate.hasDownloadLink(it.body) }
        val pool = if (gated.isNotEmpty()) gated else if (confirmTerms == null) safePosts else emptyList()
        return if (confirmTerms == null) {
            pool.map { it.card }
        } else {
            pool.filter { genreConfirmed(it.card.title, it.terms, confirmTerms) }.map { it.card }
        }
    }

    /** WordPress front-page RSS: latest posts, poster scraped from the
     *  description HTML (same technique NaijaPreyProvider.search uses).
     *
     * [path] is the feed endpoint under the base URL. CategoryFeed calls this
     * with the site's WP *search* feed (`/search/<q>/feed/…`) so the two
     * features share one RSS parser — poster sniffing, the NAV_GARBAGE filter
     * and the DownloadLinkGate stub-drop fallback exist once, not twice.
     */
    internal suspend fun fetchRss(
        site: String,
        path: String,
        confirmTerms: Set<String>? = null,
        filterExplicit: Boolean = true
    ): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl(site).trimEnd('/')
        if (base.isBlank()) return emptyList()
        val xml = HttpClient.getText("$base$path", referer = "$base/", tag = "trending") ?: return emptyList()
        return parseRssItems(xml, site, base, confirmTerms, filterExplicit = filterExplicit)
    }

    /** Pure RSS parse (JVM-testable): items → cards, poster = first <img> in
     *  the description, WP `<category>` names harvested for genre
     *  confirmation, NAV_GARBAGE + DownloadLinkGate policies shared with the
     *  REST path (all-miss keeps the ungated batch only when unconfirmed). */
    internal fun parseRssItems(
        xml: String,
        site: String,
        base: String,
        confirmTerms: Set<String>? = null,
        filterExplicit: Boolean = true
    ): List<ShowCard> {
        val out = mutableListOf<Pair<ShowCard, List<String>>>()
        val noLinks = mutableListOf<Pair<ShowCard, List<String>>>()
        try {
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            for (item in doc.select("item")) {
                val rawTitle = item.selectFirst("title")?.text()
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                val link = item.selectFirst("link")?.text()?.trim() ?: ""
                val desc = item.selectFirst("content|encoded")?.text()
                    ?: item.selectFirst("description")?.text() ?: ""
                val cats = item.select("category").map { it.text().trim() }.filter { it.isNotBlank() }
                // Explicit filter keeps reading the RAW title + raw categories —
                // cleanCardTitle strips decoration words, and a filter that only
                // sees the cleaned string would miss a term the site decorated.
                if (filterExplicit && com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(rawTitle, cats)) {
                    continue
                }
                // First <img> = the _Poster.jpg, and it STAYS that way on
                // purpose: live-measured 2026-09-15 on 9jarocks, the later
                // "_thumb.jpg" variant is BIGGER than the poster (372 KB vs
                // 224 KB — their "thumb" is a 540p episode still). Rewriting
                // towards any *thumb* URL would make image loading slower,
                // not faster. The real laggard is the shared host itself;
                // the fix there is streaming (fetch/onPartial), not URLs.
                val poster = Regex(
                    """<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                ).find(desc)?.groupValues?.get(1) ?: ""
                if (rawTitle.isNotBlank() && link.isNotBlank() && !NAV_GARBAGE.containsMatchIn(link)) {
                    val card = ShowCard(title = cleanCardTitle(rawTitle), url = link, posterUrl = poster, site = site)
                    if (DownloadLinkGate.hasDownloadLink(desc)) out.add(card to cats) else noLinks.add(card to cats)
                }
            }
        } catch (_: Exception) {}
        val pool = if (out.isNotEmpty()) out else if (confirmTerms == null) noLinks else emptyList()
        return (if (confirmTerms == null) pool.map { it.first }
                else pool.filter { genreConfirmed(it.first.title, it.second, confirmTerms) }.map { it.first })
            .take(PER_SITE_LIMIT)
    }

    // ---- genre confirmation (v3.1.6) ------------------------------------
    // Zero-Regex by choice: pure char filtering, identical on every engine.

    /** "Sci-Fi!" -> "scifi", "Science Fiction" -> "sciencefiction". */
    internal fun normalizeGenre(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /** A card belongs to the genre when the SITE says so (any taxonomy term
     *  normalizes to / contains a genre alias) or the TITLE says so. Terms
     *  empty (API sites, RSS without <category>) leaves the title check —
     *  which for the TMDB-style APIs IS the whole match (they search titles). */
    internal fun genreConfirmed(title: String, terms: List<String>, aliases: Set<String>): Boolean {
        if (aliases.isEmpty()) return true
        val normTitle = normalizeGenre(title)
        if (aliases.any { it.isNotBlank() && normTitle.contains(it) }) return true
        return terms.any { term ->
            val n = normalizeGenre(term)
            n.isNotBlank() && aliases.any { n.contains(it) }
        }
    }

    // ---- API-JSON genre sources (v3.1.6: nepu movies, asianc kdrama) -----
    // Neither site is WordPress — no /wp-json, no search RSS. Both expose a
    // bespoke JSON search the providers already use at SEARCH time; the genre
    // feeds reuse those exact shapes. No post bodies exist in these APIs, so
    // body-mention noise is structurally impossible: results are title matches.

    internal suspend fun fetchApiSearch(site: String, term: String, limit: Int): List<ShowCard> {
        val base = DynamicRulesManager.getBaseUrl(site).trimEnd('/')
        if (base.isBlank()) return emptyList()
        val enc = java.net.URLEncoder.encode(term, "UTF-8")
        val url = if (site == "nepu") "$base/api/search?q=$enc" else "$base/api?a=search&keyword=$enc"
        val json = HttpClient.getText(url, referer = "$base/", tag = "trending") ?: return emptyList()
        return (if (site == "nepu") parseNepuResults(json, site, base) else parseAsiancResults(json, site, base))
            .take(limit)
    }

    /** nepu: TMDB-proxy — {results:[{id, media_type, title|name, poster_path}]}
     *  → watch URL + image.tmdb.org poster (the fleet's fastest CDN). */
    internal fun parseNepuResults(json: String, site: String, base: String): List<ShowCard> {
        val out = mutableListOf<ShowCard>()
        try {
            val arr = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val type = item.optString("media_type", "movie")
                val title = item.optString("title").ifBlank { item.optString("name") }
                val posterPath = item.optString("poster_path")
                if (id.isBlank() || title.isBlank()) continue
                out.add(
                    ShowCard(
                        title = cleanCardTitle(title),
                        url = "$base/watch/$type/$id",
                        posterUrl = if (posterPath.isBlank()) "" else "https://image.tmdb.org/t/p/w342$posterPath",
                        site = site,
                        category = "Movies"
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    /** asianc: bare JSON array [{url, name|value, cover}] — same handling
     *  AsianCProvider.search uses on device (relative urls get the base). */
    internal fun parseAsiancResults(json: String, site: String, base: String): List<ShowCard> {
        val out = mutableListOf<ShowCard>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rawUrl = item.optString("url")
                val title = item.optString("name").ifEmpty { item.optString("value") }
                val cover = item.optString("cover")
                if (rawUrl.isBlank() || title.isBlank()) continue
                out.add(
                    ShowCard(
                        title = cleanCardTitle(title),
                        url = if (rawUrl.startsWith("/")) "$base$rawUrl" else rawUrl,
                        posterUrl = cover,
                        site = site,
                        category = "Asian Drama"
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    // ---- lite tile-poster fetch (v3.1.6) --------------------------------
    // Home genre tiles need ONE poster URL per genre. The full REST response
    // carries every post's entire rendered body (hundreds of KB) — _fields
    // asks WP for just the two we use. ~2KB per tile instead of a post body.

    internal fun wpRestLiteUrl(base: String, query: String, limit: Int): String? {
        val clean = base.trimEnd('/')
        if (clean.isBlank()) return null
        return "$clean/wp-json/wp/v2/posts?per_page=$limit" +
            "&search=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&_fields=title,jetpack_featured_media_url"
    }

    /** Tiles are artwork-only (tapping opens the real gated page), so no
     *  DownloadLinkGate and no _embed here — by design, on a ~2KB budget. */
    internal suspend fun fetchWpRestLitePoster(
        site: String,
        query: String,
        filterExplicit: Boolean = true
    ): String {
        val base = DynamicRulesManager.getBaseUrl(site)
        val limit = if (filterExplicit) 3 else 1
        val url = wpRestLiteUrl(base, query, limit) ?: return ""
        val json = HttpClient.getText(url, referer = "${base.trimEnd('/')}/", tag = "trending") ?: return ""
        return try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optJSONObject("title")?.optString("rendered").orEmpty()
                if (filterExplicit && com.anonrode.downloader.util.ExplicitContentFilter.isExplicit(title)) {
                    continue
                }
                val poster = obj.optString("jetpack_featured_media_url").orEmpty()
                if (poster.isNotBlank()) return poster
            }
            ""
        } catch (_: Exception) { "" }
    }
}
