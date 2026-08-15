package com.anonrode.downloader.viewmodel

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.ShowDetails
import com.anonrode.downloader.engine.DownloadEngine
import com.anonrode.downloader.providers.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val query: String = "",
    val searchResults: List<ShowCard> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val selectedFilter: String = "all",
    val activeShowForDrawer: ShowCard? = null,
    val drawerEpisodes: List<EpisodeItem> = emptyList(),
    val isEpisodesLoading: Boolean = false,
    val episodesError: String? = null,
    val freeStorageGb: Double = 0.0,
    val totalStorageGb: Double = 0.0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val engine = DownloadEngine.instance

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("anon_serverless_prefs", Context.MODE_PRIVATE)

    init {
        loadSettings()
        engine.initPersistence(application.filesDir)
        refreshStorage()
    }

    private fun loadSettings() {
        engine.maxConcurrentDownloads = prefs.getInt("max_concurrent", 2)
        engine.parallelSocketsPerFile = prefs.getInt("parallel_sockets", 16)
        engine.defaultQuality = prefs.getString("default_quality", "720p") ?: "720p"
        engine.autoOrganizeByShow = prefs.getBoolean("auto_organize", true)
        engine.instantSocialDownload = prefs.getBoolean("instant_social", false)
    }

    fun saveSettings(maxConcurrent: Int, parallelSockets: Int, defaultQuality: String, autoOrganize: Boolean) {
        engine.maxConcurrentDownloads = maxConcurrent
        engine.parallelSocketsPerFile = parallelSockets
        engine.defaultQuality = defaultQuality
        engine.autoOrganizeByShow = autoOrganize

        prefs.edit()
            .putInt("max_concurrent", maxConcurrent)
            .putInt("parallel_sockets", parallelSockets)
            .putString("default_quality", defaultQuality)
            .putBoolean("auto_organize", autoOrganize)
            .apply()
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
    }

    fun onFilterSelected(filter: String) {
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
        val q = _uiState.value.query.trim()
        if (q.isNotBlank()) search(q)
    }

    fun search(queryOverride: String? = null) {
        val q = (queryOverride ?: _uiState.value.query).trim()
        if (q.isBlank()) return

        _uiState.value = _uiState.value.copy(isSearching = true, searchError = null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = ProviderRegistry.searchAll(q, _uiState.value.selectedFilter)
                _uiState.value = _uiState.value.copy(
                    searchResults = results,
                    isSearching = false,
                    searchError = if (results.isEmpty()) "No results found for \"$q\"" else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchError = "Search failed: ${e.message}"
                )
            }
        }
    }

    fun openEpisodeDrawer(show: ShowCard) {
        _uiState.value = _uiState.value.copy(
            activeShowForDrawer = show,
            drawerEpisodes = emptyList(),
            isEpisodesLoading = true,
            episodesError = null
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val provider = ProviderRegistry.getProvider(show.site)
                val details = provider.loadEpisodes(show.url)
                _uiState.value = _uiState.value.copy(
                    drawerEpisodes = details.episodes,
                    isEpisodesLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isEpisodesLoading = false,
                    episodesError = "Could not load episodes: ${e.message}"
                )
            }
        }
    }

    fun closeEpisodeDrawer() {
        _uiState.value = _uiState.value.copy(activeShowForDrawer = null)
    }

    fun downloadEpisode(episode: EpisodeItem) {
        val show = _uiState.value.activeShowForDrawer ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val provider = ProviderRegistry.getProvider(episode.site)
            val recipe = provider.resolveEpisode(episode.url, engine.defaultQuality)

            engine.enqueue(
                showTitle = show.title,
                episodeNum = episode.episodeNum,
                episodeTitle = "${show.title}: ${episode.title}",
                sourceUrl = recipe.directUrl,
                isDirect = true,
                headers = recipe.headers,
                backend = recipe.backend,
                parallelSockets = recipe.parallelSockets
            )
        }
    }

    fun downloadAllEpisodes(episodes: List<EpisodeItem>) {
        val show = _uiState.value.activeShowForDrawer ?: return
        viewModelScope.launch(Dispatchers.IO) {
            episodes.forEach { ep ->
                val provider = ProviderRegistry.getProvider(ep.site)
                val recipe = provider.resolveEpisode(ep.url, engine.defaultQuality)

                engine.enqueue(
                    showTitle = show.title,
                    episodeNum = ep.episodeNum,
                    episodeTitle = "${show.title}: ${ep.title}",
                    sourceUrl = recipe.directUrl,
                    isDirect = true,
                    headers = recipe.headers,
                    backend = recipe.backend,
                    parallelSockets = recipe.parallelSockets
                )
            }
        }
    }

    fun refreshStorage() {
        try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalGb = (totalBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)
            val freeGb = (availableBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)

            _uiState.value = _uiState.value.copy(
                freeStorageGb = String.format("%.1f", freeGb).toDouble(),
                totalStorageGb = String.format("%.1f", totalGb).toDouble()
            )
        } catch (_: Exception) {}
    }
}
