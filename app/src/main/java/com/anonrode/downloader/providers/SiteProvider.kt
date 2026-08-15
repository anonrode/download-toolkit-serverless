package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.DownloadRecipe
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails

interface SiteProvider {
    val name: String
    val mainUrl: String
    val requiresSingleSocket: Boolean get() = false

    suspend fun search(query: String): List<ShowCard>
    suspend fun loadEpisodes(showUrl: String): ShowDetails
    suspend fun resolveEpisode(episodeUrl: String, quality: String = "720p"): DownloadRecipe
}
