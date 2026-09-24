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

        val currentProviders = allProviders
        val targets = if (!siteFilter.isNullOrBlank() && siteFilter != "all") {
            currentProviders.filter { it.name.equals(siteFilter, ignoreCase = true) || it.name.contains(siteFilter, ignoreCase = true) || siteFilter.contains(it.name, ignoreCase = true) }
        } else {
            currentProviders
        }.filter { it.searchEnabled }

        val timeoutMs = if (targets.size == 1) 15000L else 7000L
        val accumulated = java.util.Collections.synchronizedList(mutableListOf<ShowCard>())
        val emitMutex = Mutex()

        coroutineScope {
            targets.forEach { provider ->
                launch(Dispatchers.IO) {
                    try {
                        val raw = withTimeoutOrNull(timeoutMs) { provider.search(query) } ?: emptyList()
                        val items = filteredItems(provider, raw)
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

    fun searchFlow(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = searchStreaming(query, siteFilter)

    suspend fun loadEpisodes(show: ShowCard): ShowDetails {
        val provider = getProvider(show.site) ?: return ShowDetails(show = show)
        // Drawer cache: the activity log showed the same ~130KiB show page being
        // re-fetched up to 5 times in one browsing session (every drawer open).
        // A 5-minute TTL per show kills that waste; the drawer's per-open job
        // still gets fresh data after the TTL or when the user pulls it again.
        val cacheKey = "${show.site}::${show.url}"
        val now = System.currentTimeMillis()
        val cached = episodesCache[cacheKey]
        if (cached != null && now - cached.first < 300_000L) {
            return cached.second
        }
        // Force IO here, not just at the call site: loadEpisodes does blocking
        // OkHttp, and a caller that forgets to switch off Main gets a
        // NetworkOnMainThreadException that HttpClient's blanket catch swallows
        // into an empty list ("No episodes found"). Guaranteeing it at the
        // source makes that whole failure class impossible regardless of caller.
        val details = withContext(Dispatchers.IO) { provider.loadEpisodes(show.url) }
        if (details.episodes.isNotEmpty()) {
            episodesCache[cacheKey] = Pair(now, details)
        }
        return details
    }

    private val episodesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ShowDetails>>()

    suspend fun resolveEpisode(site: String, episodeUrl: String, quality: String): DownloadRecipe {
        val provider = getProvider(site) ?: return DownloadRecipe(
            directUrl = episodeUrl,
            filename = episodeUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "media.mp4" },
            backend = "aria2c"
        )
        return withContext(Dispatchers.IO) { provider.resolveEpisode(episodeUrl, quality) }
    }
}
