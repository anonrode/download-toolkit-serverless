package com.anonrode.downloader.viewmodel

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.router.ParsedUrl
import com.anonrode.downloader.data.router.UrlRouter
import com.anonrode.downloader.engine.DownloadEngine
import com.anonrode.downloader.engine.DownloadRepository
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.providers.RelevanceScorer
import com.anonrode.downloader.util.NetworkObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private val repository = DownloadRepository()
    val engine: DownloadEngine = DownloadEngine(application, repository, NetworkObserver(application))

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
                openEpisodeDrawer(parsed.showCard)
            }
            is ParsedUrl.SocialUrl -> {
                if (engine.instantSocialDownload) {
                    engine.enqueue(
                        showTitle = "Social/${parsed.platform}",
                        episodeNum = 1,
                        episodeTitle = "${parsed.platform} Video",
                        sourceUrl = parsed.cleanUrl,
                        isDirect = false,
                        backend = "yt-dlp",
                        parallelSockets = 1
                    )
                } else {
                    onOpenSocial(parsed.platform, parsed.cleanUrl)
                }
            }
            is ParsedUrl.MagnetUrl -> {
                engine.enqueue(
                    showTitle = "Torrents",
                    episodeNum = 1,
                    episodeTitle = parsed.title,
                    sourceUrl = parsed.magnet,
                    isDirect = true,
                    backend = "aria2c",
                    parallelSockets = 16
                )
            }
            is ParsedUrl.DirectMediaUrl -> {
                engine.enqueue(
                    showTitle = "Direct Downloads",
                    episodeNum = 1,
                    episodeTitle = parsed.filename,
                    sourceUrl = parsed.url,
                    isDirect = true,
                    backend = "aria2c",
                    parallelSockets = engine.parallelSocketsPerFile
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
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchResults = emptyList(), searchError = null) }

            val filter = _uiState.value.selectedFilter
            val collected = mutableListOf<ShowCard>()

            try {
                ProviderRegistry.searchFlow(q, filter).collect { incomingBatch ->
                    collected.addAll(incomingBatch)
                    val deduplicatedAndRanked = RelevanceScorer.filterAndSort(q, collected)
                    _uiState.update { it.copy(searchResults = deduplicatedAndRanked) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(searchError = e.message) }
            } finally {
                _uiState.update { it.copy(isSearching = false) }
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
                val details = ProviderRegistry.loadEpisodes(show)
                _uiState.update {
                    it.copy(
                        drawerEpisodes = details.episodes,
                        isEpisodesLoading = false,
                        episodesError = if (details.episodes.isEmpty()) "No episodes found on this page" else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isEpisodesLoading = false,
                        episodesError = "Failed to load episodes: ${e.message}"
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
            sourceUrl = episode.url,
            isDirect = false,
            backend = "aria2c",
            parallelSockets = engine.parallelSocketsPerFile
        )
    }

    fun downloadAllEpisodes(episodes: List<EpisodeItem>) {
        val show = _uiState.value.activeShowForDrawer ?: return
        for (ep in episodes) {
            engine.enqueue(
                showTitle = show.title,
                episodeNum = ep.episodeNum,
                episodeTitle = "${show.title} - ${ep.title}",
                sourceUrl = ep.url,
                isDirect = false,
                backend = "aria2c",
                parallelSockets = engine.parallelSocketsPerFile
            )
        }
    }

    fun saveSettings(
        maxConcurrent: Int,
        parallelSockets: Int,
        quality: String,
        autoOrganize: Boolean,
        storageGuard: Double,
        wifiOnlyTorrents: Boolean
    ) {
        engine.saveAllSettings(
            maxConcurrent = maxConcurrent,
            parallelSockets = parallelSockets,
            quality = quality,
            autoOrganize = autoOrganize,
            storageGuard = storageGuard,
            wifiOnlyTorrents = wifiOnlyTorrents
        )
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
