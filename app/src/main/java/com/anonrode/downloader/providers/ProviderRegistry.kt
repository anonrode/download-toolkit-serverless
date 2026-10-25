package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object ProviderRegistry {

    private val staticProviders: List<SiteProvider> = listOf(
        NkiriProvider,
        DramaKeyProvider,
        AsianCProvider,
        AnitakuProvider,
        PlutoProvider,
        DramaRainProvider,
        RocksProvider,
        NaijaVaultProvider,
        NaijaPreyProvider,
        NepuProvider,
        TorrentProvider
    )

    val allProviders: List<SiteProvider>
        get() = staticProviders + DynamicRulesManager.getDynamicProviders()

    /**
     * Providers that may accept NEW work right now: the full registry minus
     * every site the signed payload's `contentPolicy.disabledSites` switch
     * holds OFF. Consumers: search fan-out, feed/site lists, failover.
     *
     * [getProvider] deliberately stays UNFILTERED: an already-running
     * download must still resolve its own episode after the operator disables
     * the site ("existing/running downloads may finish"). Only start-of-work
     * paths consult this list.
     */
    val enabledProviders: List<SiteProvider>
        get() = allProviders.filter { DynamicRulesManager.isSiteEnabled(it.name) }

    /** Names of the currently enabled providers (UI chip lists / diagnostics). */
    fun enabledProviderNames(): List<String> = enabledProviders.map { it.name }

    /** The OTA kill-switch verdict for one provider id (default true). */
    fun isSiteEnabled(site: String): Boolean = DynamicRulesManager.isSiteEnabled(site)

    /** Snapshot of (provider id -> enabled) for the current registry. */
    fun siteEnabledSnapshot(): Map<String, Boolean> =
        DynamicRulesManager.siteEnabledSnapshot(allProviders.map { it.name })

    /** Drops cards belonging to a disabled site. Exposed for the home/trending
     *  and category/Asian feed consumers, which fan out to site-specific
     *  fetchers rather than through [searchStreaming]. */
    fun filterEnabledCards(cards: List<ShowCard>): List<ShowCard> =
        cards.filter { it.title.isNotBlank() && it.url.isNotBlank() && DynamicRulesManager.isSiteEnabled(it.site) }

    fun getProvider(site: String): SiteProvider? {
        return allProviders.find { it.name.equals(site, ignoreCase = true) }
    }

    // ---- last-good search cache (RC-2) --------------------------------------
    //
    // The first search of a session paints late because every provider crawl
    // starts cold (asianc's first API call alone wedged 30s twice, share-43
    // §RC-2). This cache keeps the LAST GOOD non-empty result of every
    // (provider, normalized query) pair for 5 minutes: a repeat/cold query
    // paints instantly from memory while the normal fan-out refreshes in the
    // background.
    //
    // Rules that are load-bearing:
    //  - exact key: provider + EXACT normalized query. A cache hit can never
    //    surface a different query's cards (the old cross-query bleed bug).
    //  - never cache empty/failed: providers swallow failures into empty
    //    lists; an empty answer must never overwrite a good entry. (The
    //    drawer negative cache in loadEpisodes is a separate mechanism and is
    //    deliberately not duplicated here.)
    //  - bounded: hard LRU of [MAX_SEARCH_CACHE_ENTRIES]; no unbounded map.
    internal object SearchLastGoodCache {
        const val MAX_SEARCH_CACHE_ENTRIES = 64

        /** 5-minute freshness (owner spec). Internal setter = test seam. */
        internal var ttlMs: Long = 5 * 60_000L

        private data class Entry(val query: String, val atMs: Long, val cards: List<ShowCard>)

        private val lock = Any()
        private val lru = object : java.util.LinkedHashMap<String, Entry>(
            MAX_SEARCH_CACHE_ENTRIES, 0.75f, true
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
                val evict = size > MAX_SEARCH_CACHE_ENTRIES
                if (evict) evictions.incrementAndGet()
                return evict
            }
        }
        private val hits = java.util.concurrent.atomic.AtomicLong()
        private val misses = java.util.concurrent.atomic.AtomicLong()
        private val stores = java.util.concurrent.atomic.AtomicLong()
        private val evictions = java.util.concurrent.atomic.AtomicLong()

        /** Case/whitespace-insensitive identity. Nothing else is normalized:
         *  two different queries must never share an entry. */
        fun normalizeQuery(raw: String): String = raw.trim().lowercase().replace(Regex("\\s+"), " ")

        private fun keyOf(provider: String, normalizedQuery: String): String =
            "${provider.trim().lowercase()}|$normalizedQuery"

        fun get(
            provider: String,
            normalizedQuery: String,
            nowMs: Long = System.currentTimeMillis()
        ): List<ShowCard>? {
            val key = keyOf(provider, normalizedQuery)
            synchronized(lock) {
                val entry = lru[key]
                if (entry == null) {
                    misses.incrementAndGet()
                    return null
                }
                // Exact-query guard: even a key-shape collision must not
                // serve another query's cards.
                if (entry.query != normalizedQuery) {
                    lru.remove(key)
                    misses.incrementAndGet()
                    return null
                }
                if (nowMs - entry.atMs >= ttlMs) {
                    lru.remove(key)
                    misses.incrementAndGet()
                    return null
                }
                hits.incrementAndGet()
                return entry.cards
            }
        }

        /** Only a NON-EMPTY live answer is last-good. Returns true on store. */
        fun put(
            provider: String,
            normalizedQuery: String,
            cards: List<ShowCard>,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean {
            if (cards.isEmpty()) return false
            synchronized(lock) {
                lru[keyOf(provider, normalizedQuery)] = Entry(normalizedQuery, nowMs, cards.toList())
                stores.incrementAndGet()
            }
            return true
        }

        internal data class Stats(
            val hits: Long,
            val misses: Long,
            val stores: Long,
            val evictions: Long,
            val size: Int
        )

        internal fun stats(): Stats = synchronized(lock) {
            Stats(hits.get(), misses.get(), stores.get(), evictions.get(), lru.size)
        }

        internal fun clear() = synchronized(lock) {
            lru.clear()
            hits.set(0)
            misses.set(0)
            stores.set(0)
            evictions.set(0)
        }
    }

    /** Test seam: the provider fan-out used by [searchStreaming]. Production
     *  leaves this null (= [enabledProviders]). */
    internal var searchProvidersForTest: List<SiteProvider>? = null

    internal var searchCacheTtlMs: Long
        get() = SearchLastGoodCache.ttlMs
        set(value) { SearchLastGoodCache.ttlMs = value }

    internal fun clearSearchCachesForTest() {
        SearchLastGoodCache.clear()
        SearchLastGoodCache.ttlMs = 5 * 60_000L
    }

    internal fun searchCacheStatsForTest(): SearchLastGoodCache.Stats = SearchLastGoodCache.stats()

    // ---- search-result hygiene ----------------------------------------------
    //
    // Site search pages (nkiri's WordPress theme especially) embed sponsored
    // and ad cards: links to sbani.pro, cutt.ly shorteners and friends that
    // flow straight into the results grid as tappable cards. Two filters:
    //
    //  1. JUNK_HOSTS — a hard denylist, no exceptions. Shortener/ad hosts are
    //     never a legitimate search result.
    //  2. on-domain suffix rule — for the single-host providers, a card whose
    //     URL host is not the provider's own base domain (or any of its
    //     registered mirrors) is junk from the page's ad slots. Per-provider
    //     opt-in, kept conservative: anitaku legitimately returns results from
    //     TWO hosts (gogoanime.or.at AND anitaku.com.ro), naijaprey's episode
    //     drawer leaves its domain entirely (vdl.np-downloader.com,
    //     wildshare.net), and the OTA-dynamic providers' base URLs are
    //     data-driven — none of those get the rule.
    private val JUNK_HOSTS = listOf(
        "sbani.pro", "cutt.ly", "bit.ly", "tinyurl.com", "t.ly",
        "shorturl.at", "is.gd", "goo.gl", "rebrand.ly", "tiny.cc"
    )

    private val ON_DOMAIN_PROVIDERS = setOf("nkiri", "dramakey", "asianc", "pluto", "dramarain", "9jarocks", "naijavault", "nepu")

    private fun isJunkCard(provider: SiteProvider, card: ShowCard): Boolean {
        val url = card.url
        // magnet:/anything-not-http never hits site ad slots; leave it alone.
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        val host = HttpClient.safeHost(url)
        if (host.isBlank()) return false
        if (JUNK_HOSTS.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }) return true
        if (provider.name !in ON_DOMAIN_PROVIDERS) return false
        val allowedHosts = DynamicRulesManager.getBaseUrls(provider.name)
            .mapNotNull { base -> HttpClient.safeHost(base).takeIf { h -> h.isNotBlank() } }
        if (allowedHosts.isEmpty()) return false
        return allowedHosts.none { allowed -> host.equals(allowed, ignoreCase = true) || host.endsWith(".$allowed", ignoreCase = true) }
    }

    /** Filters one provider's raw results and journals every drop with its
     *  reason — a silently vanishing card was the old bug; the log must show
     *  WHY it vanished. */
    private fun filteredItems(provider: SiteProvider, items: List<ShowCard>): List<ShowCard> {
        return items.filter { card ->
            val junk = isJunkCard(provider, card)
            if (junk) {
                DebugLog.resolve("dropped junk search card provider=${provider.name} title=\"${card.title.take(60)}\" url=${card.url.take(80)}")
            }
            !junk
        }
    }

    fun searchStreaming(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = channelFlow {
        val normalizedQuery = SearchLastGoodCache.normalizeQuery(query)
        val filterLabel = siteFilter?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "all"

        // Site-filter resolution must also honor the OTA kill switch: an
        // explicitly requested disabled site returns no cards, exactly like a
        // site with no results — no request is fired for it.
        val currentProviders = searchProvidersForTest ?: enabledProviders
        val targets = if (filterLabel != "all") {
            currentProviders.filter { it.name.equals(filterLabel, ignoreCase = true) || it.name.contains(filterLabel, ignoreCase = true) || filterLabel.contains(it.name, ignoreCase = true) }
        } else {
            currentProviders
        }.filter { it.searchEnabled && DynamicRulesManager.isSiteEnabled(it.name) }

        val timeoutMs = if (targets.size == 1) 15000L else 7000L

        // One basket per provider. A live refresh REPLACES that provider's
        // basket rather than appending, so a refreshed card can never sit
        // beside its stale cached twin.
        val baskets = java.util.Collections.synchronizedMap(LinkedHashMap<String, List<ShowCard>>())
        val emitMutex = Mutex()
        var liveResults = false

        fun snapshot(): List<ShowCard> {
            val all = synchronized(baskets) { baskets.values.toList().flatten() }
            return RelevanceScorer.filterAndSort(query, all)
        }

        // 1. INSTANT LAST-GOOD. Exact (provider, normalized query) entries
        // only — a different query never reads this entry. Enabled-site
        // filtering is re-applied because the OTA kill switch can flip
        // inside the TTL.
        for (provider in targets) {
            val cached = SearchLastGoodCache.get(provider.name, normalizedQuery) ?: continue
            // Re-filter: the OTA kill switch may have disabled a site since
            // this entry was stored. Never paint a basket that filters empty.
            val usable = filterEnabledCards(cached)
            if (usable.isNotEmpty()) baskets[provider.name] = usable
        }
        if (baskets.isNotEmpty()) {
            DebugLog.net(
                "search cache hit query=\"${normalizedQuery.take(60)}\" providers=${baskets.keys.joinToString(",")}"
            )
            send(snapshot())
        }

        // 2. BACKGROUND REFRESH. The crawl always runs — a fresh cache entry
        // must not freeze the results for its whole TTL. Empty/failed answers
        // never overwrite the last-good entry.
        coroutineScope {
            targets.forEach { provider ->
                launch(Dispatchers.IO) {
                    try {
                        val raw = withTimeoutOrNull(timeoutMs) { provider.search(query) } ?: emptyList()
                        val items = filterEnabledCards(filteredItems(provider, raw))
                        if (items.isNotEmpty()) {
                            SearchLastGoodCache.put(provider.name, normalizedQuery, items)
                            emitMutex.withLock {
                                baskets[provider.name] = items
                                liveResults = true
                                send(snapshot())
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. END STATE. A failed refresh keeps the last-good paint on screen;
        // only a query with nothing cached AND nothing live emits the explicit
        // empty list that clears the previous query's grid.
        if (!liveResults && baskets.isEmpty()) {
            send(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    // Search is deliberately INCLUDED in Nollywood suppression (unlike the
    // explicit filter's documented search bypass): a content-type block that
    // search can circumvent is only half-effective. Filtering happens at
    // EMISSION so the unfiltered list stays cached and a mid-TTL policy flip
    // still applies immediately on the next read.
    fun searchFlow(query: String, siteFilter: String? = null): Flow<List<ShowCard>> =
        searchStreaming(query, siteFilter)
            .map { com.anonrode.downloader.util.NollywoodFilter.filterSafe(it) }

    suspend fun loadEpisodes(show: ShowCard): ShowDetails {
        val provider = loadEpisodesProviderForTest ?: getProvider(show.site) ?: return ShowDetails(show = show)
        // OTA kill switch: a disabled site accepts no NEW work. Returning the
        // empty drawer (instead of throwing) is the same shape a site with no
        // episodes produces, so the UI shows its normal "no episodes" state.
        if (!DynamicRulesManager.isSiteEnabled(show.site)) {
            DebugLog.resolve("site disabled by OTA policy: refusing new episode load for ${show.site}")
            return ShowDetails(show = show)
        }
        // Drawer cache: the activity log showed the same ~130KiB show page being
        // re-fetched up to 5 times in one browsing session (every drawer open).
        // A 5-minute TTL per show kills that waste; the drawer's per-open job
        // still gets fresh data after the TTL or when the user pulls it again.
        val cacheKey = "${show.site}::${show.url}"
        val now = System.currentTimeMillis()
        freshEpisodes(cacheKey, now)?.let { return it.details }
        // Single-flight (activity-log share-43, 10-20 00:19): concurrent
        // callers — drawer open + oracle verifier + prioritize re-wave — each
        // raced the TTL check and fetched the same dead page 8x in 7.7s.
        // One fetch per key; late arrivals re-check the cache the winner
        // just filled. The negative-result cache below makes an empty drawer
        // cacheable too: with only the positive branch cached, the SAME
        // page re-fetched on every wave because empties never stuck.
        val keyMutex = loadLocks.computeIfAbsent(cacheKey) { Mutex() }
        val details: ShowDetails = keyMutex.withLock {
            freshEpisodes(cacheKey, System.currentTimeMillis())?.let { return@withLock it.details }
            // Force IO here, not just at the call site: loadEpisodes does blocking
            // OkHttp, and a caller that forgets to switch off Main gets a
            // NetworkOnMainThreadException that HttpClient's blanket catch swallows
            // into an empty list ("No episodes found"). Guaranteeing it at the
            // source makes that whole failure class impossible regardless of caller.
            val fetched = withContext(Dispatchers.IO) { provider.loadEpisodes(show.url) }
            val atMs = System.currentTimeMillis()
            episodesCache[cacheKey] = CacheEntry(atMs, fetched)
            return@withLock fetched
        }
        return details
    }

    /** Drawer-cache entry: positive results keep the historical 5-minute
     *  TTL; empty drawers get [emptyEpisodesTtlMs] so a 0-episode page
     *  (a-certain-dark-item-2026 class — site serves the post with an empty
     *  list) stops being re-fetched on every drawer open and verify wave
     *  while still healing itself within minutes when the site backfills.
     *  The TTL itself is read from the live policy in [freshEpisodes]. */
    private class CacheEntry(val atMs: Long, val details: ShowDetails)
    private val episodesCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private val loadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    internal var episodesTtlMs = 300_000L
    internal var emptyEpisodesTtlMs = 600_000L

    /** Cache hit only when present and inside the CURRENT ttl policy.
     *  The TTL is deliberately not snapshotted into the entry: an OTA/settings
     *  flip (or the test seam setting it to 0) must invalidate immediately,
     *  not after the previous window expires. */
    private fun freshEpisodes(cacheKey: String, now: Long): CacheEntry? {
        val entry = episodesCache[cacheKey] ?: return null
        val ttl = if (entry.details.episodes.isNotEmpty()) episodesTtlMs else emptyEpisodesTtlMs
        if (now - entry.atMs >= ttl) {
            episodesCache.remove(cacheKey, entry)
            return null
        }
        return entry
    }

    /** Seam over [getProvider] so [loadEpisodes]'s cache/single-flight is
     *  JVM-testable (static dispatch by site name). Production = null; tests
     *  inject a counting fake (ResultVerifier.chain pattern). */
    internal var loadEpisodesProviderForTest: SiteProvider? = null

    fun clearEpisodeCache() {
        episodesCache.clear()
        loadLocks.clear()
    }

    /** Resets all episode-cache state between tests
     *  (SubtitleSurfaces.clearForTest pattern). */
    internal fun clearEpisodeCachesForTest() {
        episodesCache.clear()
        loadLocks.clear()
    }

    suspend fun resolveEpisode(site: String, episodeUrl: String, quality: String): DownloadRecipe {
        val provider = getProvider(site) ?: return DownloadRecipe(
            directUrl = episodeUrl,
            filename = episodeUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "media.mp4" },
            backend = "aria2c"
        )
        val recipe = withContext(Dispatchers.IO) { provider.resolveEpisode(episodeUrl, quality) }
        // Carry the recipe's headers/subtitles across the String-only resolver
        // boundary (engine header propagation + subtitle sidecars).
        com.anonrode.downloader.pipeline.ResolvedHints.remember(episodeUrl, recipe)
        return recipe
    }
}
