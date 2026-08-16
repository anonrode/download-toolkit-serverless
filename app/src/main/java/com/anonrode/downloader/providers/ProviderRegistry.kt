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
        return allProviders.find { it.name.equals(site, ignoreCase = true) }
    }

    fun searchStreaming(query: String, siteFilter: String? = null): Flow<List<ShowCard>> = flow {
        val targets = if (!siteFilter.isNullOrBlank() && siteFilter != "all") {
            allProviders.filter { it.name.equals(siteFilter, ignoreCase = true) }
        } else {
            allProviders
        }

        val accumulated = mutableListOf<ShowCard>()

        coroutineScope {
            val deferreds = targets.map { provider ->
                async(Dispatchers.IO) {
                    try {
                        val items = provider.search(query)
                        Pair(provider.name, items)
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
}
