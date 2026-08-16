package com.anonrode.downloader.viewmodel

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anonrode.downloader.AnonApp
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.router.ParsedUrl
import com.anonrode.downloader.data.router.UrlRouter
import com.anonrode.downloader.engine.DownloadEngine
import com.anonrode.downloader.providers.ProviderRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class HomeUiState(
    val query: String = "",
    val selectedFilter: String = "all",
    val isSearching: Boolean = false,
    val searchResults: List<ShowCard> = emptyList(),
    val searchError: String? = null,
    val activeShowForDrawer: ShowCard? = null,
    val drawerEpisodes: List<EpisodeItem> = emptyList(),
    val isEpisodesLoading: Boolean = false,
    val episodesError: String? = null,
    val freeStorageGb: Long = 0L,
    val totalStorageGb: Long = 0L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val engine: DownloadEngine = (application as AnonApp).engine

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshStorageInfo()
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
    }

    fun onFilterSelected(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter) }
        val currentQuery = _uiState.value.query
        if (currentQuery.isNotBlank()) {
            search(currentQuery)
        }
    }

    fun handlePastedInput(input: String, onOpenSocial: (String, String) -> Unit) {
        when (val parsed = UrlRouter.parse(input)) {
            is ParsedUrl.DramaUrl -> {
                // Instantly open the show / episode drawer
                openEpisodeDrawer(parsed.showCard)
            }
            is ParsedUrl.SocialUrl -> {
                onOpenSocial(parsed.platform, parsed.cleanUrl)
            }
            is ParsedUrl.DirectMediaUrl -> {
                engine.enqueue(
                    showTitle = "Direct Downloads",
                    episodeNum = 1,
                    episodeTitle = parsed.filename,
                    sourceUrl = parsed.url,
                    isDirect = true,
                    backend = "aria2c",
                    parallelSockets = 16
                )
            }
            is ParsedUrl.SearchQuery -> {
                onQueryChanged(parsed.query)
                search(parsed.query)
            }
        }
    }

    fun search(query: String = _uiState.value.query) {
        val q = query.trim()
        if (q.isBlank()) return

        searchJob?.cancel()
        _uiState.update { it.copy(isSearching = true, searchError = null, searchResults = emptyList()) }

        searchJob = viewModelScope.launch {
            try {
                val filter = _uiState.value.selectedFilter
                ProviderRegistry.searchStreaming(q, filter).collect { partialResults ->
                    _uiState.update {
                        it.copy(
                            searchResults = partialResults,
                            isSearching = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchError = e.message ?: "Search encountered an error"
                    )
                }
            }
        }
    }

    fun openEpisodeDrawer(show: ShowCard) {
        _uiState.update {
            it.copy(
                activeShowForDrawer = show,
                drawerEpisodes = emptyList(),
                isEpisodesLoading = true,
                episodesError = null
            )
        }

        viewModelScope.launch {
            try {
                val provider = ProviderRegistry.getProvider(show.site)
                if (provider != null) {
                    val details = provider.loadEpisodes(show.detailUrl)
                    if (details != null && details.episodes.isNotEmpty()) {
                        _uiState.update {
                            it.copy(
                                drawerEpisodes = details.episodes,
                                isEpisodesLoading = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isEpisodesLoading = false,
                                episodesError = "No episodes found for this show"
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isEpisodesLoading = false,
                            episodesError = "Unknown provider: ${show.site}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isEpisodesLoading = false,
                        episodesError = e.message ?: "Failed to load episodes"
                    )
                }
            }
        }
    }

    fun closeEpisodeDrawer() {
        _uiState.update { it.copy(activeShowForDrawer = null, drawerEpisodes = emptyList()) }
    }

    fun downloadEpisode(episode: EpisodeItem) {
        val show = _uiState.value.activeShowForDrawer ?: return
        engine.enqueue(
            showTitle = show.title,
            episodeNum = episode.episodeNum,
            episodeTitle = "${show.title} - ${episode.title}",
            sourceUrl = episode.downloadPageUrl,
            isDirect = episode.isDirect,
            backend = if (episode.isDirect) "aria2c" else "aria2c",
            parallelSockets = 16
        )
    }

    fun downloadAllEpisodes(episodes: List<EpisodeItem>) {
        val show = _uiState.value.activeShowForDrawer ?: return
        for (ep in episodes) {
            engine.enqueue(
                showTitle = show.title,
                episodeNum = ep.episodeNum,
                episodeTitle = "${show.title} - ${ep.title}",
                sourceUrl = ep.downloadPageUrl,
                isDirect = ep.isDirect,
                backend = if (ep.isDirect) "aria2c" else "aria2c",
                parallelSockets = 16
            )
        }
    }

    fun saveSettings(
        maxConcurrent: Int,
        parallelSockets: Int,
        quality: String,
        autoOrganize: Boolean
    ) {
        engine.maxConcurrentDownloads = maxConcurrent
        engine.parallelSocketsPerFile = parallelSockets
        engine.defaultQuality = quality
        engine.autoOrganizeByShow = autoOrganize
    }

    private fun refreshStorageInfo() {
        try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalGb = (totalBlocks * blockSize) / (1024 * 1024 * 1024)
            val freeGb = (availableBlocks * blockSize) / (1024 * 1024 * 1024)

            _uiState.update { it.copy(freeStorageGb = freeGb, totalStorageGb = totalGb) }
        } catch (_: Exception) {}
    }
}
