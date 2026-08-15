package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object ProviderRegistry {

    val allProviders: List<SiteProvider> = listOf(
        NkiriProvider,
        DramaKeyProvider,
        PlutoProvider
    )

    suspend fun searchAll(query: String, filterSite: String = "all"): List<ShowCard> = coroutineScope {
        val activeProviders = if (filterSite == "all") {
            allProviders
        } else {
            allProviders.filter { it.name.equals(filterSite, ignoreCase = true) }
        }

        val deferred = activeProviders.map { provider ->
            async {
                try {
                    provider.search(query)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        deferred.awaitAll().flatten()
    }

    fun getProvider(siteName: String): SiteProvider {
        return allProviders.find { it.name.equals(siteName, ignoreCase = true) } ?: NkiriProvider
    }
}
