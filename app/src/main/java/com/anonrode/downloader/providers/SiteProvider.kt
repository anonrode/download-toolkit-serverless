package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails

interface SiteProvider {
    val name: String
    val mainUrl: String
    val requiresSingleSocket: Boolean get() = false
    /** False for sites with no working search (dead/parked domains). */
    val searchEnabled: Boolean get() = true

    suspend fun search(query: String): List<ShowCard>
    suspend fun loadEpisodes(showUrl: String): ShowDetails
    suspend fun resolveEpisode(episodeUrl: String, quality: String = "720p"): DownloadRecipe
    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        val siteKey = name.lowercase()
        if (lower.contains(siteKey)) return true
        if (mainUrl.isNotBlank()) {
            val domain = mainUrl.substringAfter("://").substringBefore("/").lowercase()
            if (domain.isNotBlank() && lower.contains(domain)) return true
        }
        return false
    }
}
