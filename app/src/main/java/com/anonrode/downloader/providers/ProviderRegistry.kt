package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

object ProviderRegistry {

    val allProviders: List<SiteProvider> = listOf(
        NkiriProvider,
        DramaKeyProvider,
        AsianCProvider,
        AnitakuProvider,
        PlutoProvider,
        DramaRainProvider,
        RocksProvider
    )

    fun getProvider(site: String): SiteProvider? {
        return allProviders.find { it.siteName.equals(site, ignoreCase = true) }
    }

    /**
     * Incremental Flow search: emits accumulated, relevance-scored results
     * in real-time as each provider finishes, giving instantaneous UI responsiveness.
     */
    fun searchStreaming(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = flow {
        val targets = if (!siteFilter.isNullOrBlank() && siteFilter != "all") {
            allProviders.filter { it.siteName.equals(siteFilter, ignoreCase = true) }
        } else {
            allProviders
        }

        val accumulated = mutableListOf<ShowCard>()

        coroutineScope {
            val deferreds = targets.map { provider ->
                async(Dispatchers.IO) {
                    try {
                        val items = provider.search(query)
                        Pair(provider.siteName, items)
                    } catch (_: Exception) {
                        Pair(provider.siteName, emptyList<ShowCard>())
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

    suspend fun searchAll(query: String, siteFilter: String? = null): List<ShowCard> = coroutineScope {
        val targets = if (!siteFilter.isNullOrBlank() && siteFilter != "all") {
            allProviders.filter { it.siteName.equals(siteFilter, ignoreCase = true) }
        } else {
            allProviders
        }

        val deferreds = targets.map { provider ->
            async(Dispatchers.IO) {
                try {
                    provider.search(query)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        val raw = deferreds.flatMap { it.await() }
        RelevanceScorer.filterAndSort(query, raw)
    }
}
