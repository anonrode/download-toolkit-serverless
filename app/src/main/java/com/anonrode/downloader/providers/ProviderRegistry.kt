package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.data.rules.DynamicRulesManager
import kotlinx.coroutines.Dispatchers
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

    fun searchStreaming(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = channelFlow {
        val cacheKey = "${query.trim().lowercase()}::${siteFilter ?: "all"}"
        val now = System.currentTimeMillis()
        val cached = searchCache[cacheKey]

        // If cached within the last 4 minutes, emit instantly (0ms response time!)
        if (cached != null && (now - cached.first) < 240_000L && cached.second.isNotEmpty()) {
            send(cached.second)
        }

        val currentProviders = allProviders
        val targets = if (!siteFilter.isNullOrBlank() && siteFilter != "all") {
            currentProviders.filter { it.name.equals(siteFilter, ignoreCase = true) }
        } else {
            currentProviders
        }

        val accumulated = java.util.Collections.synchronizedList(mutableListOf<ShowCard>())
        val emitMutex = Mutex()

        coroutineScope {
            targets.forEach { provider ->
                launch(Dispatchers.IO) {
                    try {
                        val items = withTimeoutOrNull(4000L) { provider.search(query) } ?: emptyList()
                        if (items.isNotEmpty()) {
                            emitMutex.withLock {
                                accumulated.addAll(items)
                                val ranked = RelevanceScorer.filterAndSort(query, accumulated.toList())
                                send(ranked)
                            }
                        }
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
    }.flowOn(Dispatchers.IO)

    fun searchFlow(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = searchStreaming(query, siteFilter)

    suspend fun loadEpisodes(show: ShowCard): ShowDetails {
        val provider = getProvider(show.site) ?: return ShowDetails(show = show)
        // Force IO here, not just at the call site: loadEpisodes does blocking
        // OkHttp, and a caller that forgets to switch off Main gets a
        // NetworkOnMainThreadException that HttpClient's blanket catch swallows
        // into an empty list ("No episodes found"). Guaranteeing it at the
        // source makes that whole failure class impossible regardless of caller.
        return withContext(Dispatchers.IO) { provider.loadEpisodes(show.url) }
    }

    suspend fun resolveEpisode(site: String, episodeUrl: String, quality: String): DownloadRecipe {
        val provider = getProvider(site) ?: return DownloadRecipe(
            directUrl = episodeUrl,
            filename = episodeUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "media.mp4" },
            backend = "aria2c"
        )
        return withContext(Dispatchers.IO) { provider.resolveEpisode(episodeUrl, quality) }
    }
}
