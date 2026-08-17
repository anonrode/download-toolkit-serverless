package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.rules.DynamicRulesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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

    fun searchStreaming(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = flow {
        val currentProviders = allProviders
        val targets = if (!siteFilter.isNullOrBlank() && siteFilter != "all") {
            currentProviders.filter { it.name.equals(siteFilter, ignoreCase = true) }
        } else {
            currentProviders
        }

        val accumulated = mutableListOf<ShowCard>()

        coroutineScope {
            val deferreds = targets.map { provider ->
                async(Dispatchers.IO) {
                    try {
                        val items = provider.search(query)
                        val enriched = items.map { card ->
                            if (card.posterUrl.isBlank()) {
                                val tmdbPoster = TmdbPosterResolver.resolvePoster(card.title)
                                if (!tmdbPoster.isNullOrBlank()) card.copy(posterUrl = tmdbPoster) else card
                            } else {
                                card
                            }
                        }
                        Pair(provider.name, enriched)
                    } catch (_: Exception) {
                        Pair(provider.name, emptyList<ShowCard>())
                    }
                }
            }

            for (def in deferreds) {
                val (_, items) = def.await()
                if (items.isNotEmpty()) {
                    accumulated.addAll(items)
                    val ranked = RelevanceScorer.filterAndSort(query, accumulated)
                    emit(ranked)
                }
            }
        }

        if (accumulated.isEmpty()) {
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    fun searchFlow(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = searchStreaming(query, siteFilter)

    suspend fun loadEpisodes(show: ShowCard): ShowDetails {
        val provider = getProvider(show.site) ?: return ShowDetails(show = show)
        return provider.loadEpisodes(show.url)
    }
}
