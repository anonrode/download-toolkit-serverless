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
        cards.filter { DynamicRulesManager.isSiteEnabled(it.site) }

    fun getProvider(site: String): SiteProvider? {
        return allProviders.find { it.name.equals(site, ignoreCase = true) }
    }

    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ShowCard>>>()

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
        val cacheKey = "${query.trim().lowercase()}::${siteFilter ?: "all"}"
        val now = System.currentTimeMillis()
        val cached = searchCache[cacheKey]

        // If cached within the last 4 minutes, serve it and skip the live
        // crawl entirely — the old code emitted the cache but STILL fetched
        // every provider page, so the TTL bought a faster first paint while
        // burning the same mobile data as no cache at all. Stale entries
        // below still get the stale-while-revalidate treatment.
        if (cached != null && (now - cached.first) < 240_000L && cached.second.isNotEmpty()) {
            send(cached.second)
            return@channelFlow
        }
        // Stale-cache sweep: any OTHER query's cached results for this filter
        // are obsolete the moment this crawl starts, otherwise a failed/empty
        // crawl below emits NOTHING and the stale query's results stay on
        // screen (the "previous search bleeding into the next" report).
        if (searchCache.size > 64) {
            val cutoff = now - 240_000L
            searchCache.entries.removeIf { it.value.first < cutoff }
        }
        val staleKeys = searchCache.keys.filter { it.endsWith("::${siteFilter ?: "all"}") && it != cacheKey }
        for (sk in staleKeys) searchCache.remove(sk)

        // Site-filter resolution must also honor the OTA kill switch: an
        // explicitly requested disabled site returns no cards, exactly like a
        // site with no results — no request is fired for it.
        val currentProviders = enabledProviders
        val targets = if (!siteFilter.isNullOrBlank() && siteFilter != "all") {
            currentProviders.filter { it.name.equals(siteFilter, ignoreCase = true) || it.name.contains(siteFilter, ignoreCase = true) || siteFilter.contains(it.name, ignoreCase = true) }
        } else {
            currentProviders
        }.filter { it.searchEnabled && DynamicRulesManager.isSiteEnabled(it.name) }

        val timeoutMs = if (targets.size == 1) 15000L else 7000L
        val accumulated = java.util.Collections.synchronizedList(mutableListOf<ShowCard>())
        val emitMutex = Mutex()

        coroutineScope {
            targets.forEach { provider ->
                launch(Dispatchers.IO) {
                    try {
                        val raw = withTimeoutOrNull(timeoutMs) { provider.search(query) } ?: emptyList()
                        val items = filterEnabledCards(filteredItems(provider, raw))
                        if (items.isNotEmpty()) {
                            emitMutex.withLock {
                                accumulated.addAll(items)
                                val ranked = RelevanceScorer.filterAndSort(query, accumulated.toList())
                                send(ranked)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {}
                }
            }
        }

        if (accumulated.isNotEmpty()) {
            val finalRanked = RelevanceScorer.filterAndSort(query, accumulated.toList())
            searchCache[cacheKey] = Pair(now, finalRanked)
            send(finalRanked)
        } else if (cached == null || cached.second.isEmpty()) {
            send(emptyList())
        }
        // Dead-end guard: when the crawl came up empty and a DIFFERENT query's
        // stale results for this filter were still cached, emit an explicit
        // empty list. Without this the collector never fires, isSearching
        // clears to "done", and the previous query's cards stay on screen and
        // tappable under the new query.
        if (accumulated.isEmpty() && staleKeys.isNotEmpty()) {
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
            val ttl = if (fetched.episodes.isNotEmpty()) episodesTtlMs else emptyEpisodesTtlMs
            episodesCache[cacheKey] = CacheEntry(atMs, fetched, ttl)
            return@withLock fetched
        }
        return details
    }

    /** Drawer-cache entry: positive results keep the historical 5-minute
     *  TTL; empty drawers get [emptyEpisodesTtlMs] so a 0-episode page
     *  (a-certain-dark-item-2026 class — site serves the post with an empty
     *  list) stops being re-fetched on every drawer open and verify wave
     *  while still healing itself within minutes when the site backfills. */
    private class CacheEntry(val atMs: Long, val details: ShowDetails, val ttlMs: Long)
    private val episodesCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private val loadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    internal var episodesTtlMs = 300_000L
    internal var emptyEpisodesTtlMs = 600_000L

    /** Cache hit only when present and inside its own TTL. */
    private fun freshEpisodes(cacheKey: String, now: Long): CacheEntry? {
        val entry = episodesCache[cacheKey] ?: return null
        if (now - entry.atMs >= entry.ttlMs) {
            episodesCache.remove(cacheKey, entry)
            return null
        }
        return entry
    }

    /** Seam over [getProvider] so [loadEpisodes]'s cache/single-flight is
     *  JVM-testable (static dispatch by site name). Production = null; tests
     *  inject a counting fake (ResultVerifier.chain pattern). */
    internal var loadEpisodesProviderForTest: SiteProvider? = null

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
