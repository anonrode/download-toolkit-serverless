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
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.providers.RelevanceScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val engine: DownloadEngine = (application as com.anonrode.downloader.AnonApp).engine

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var episodesJob: Job? = null
    private var searchSequence = 0L
    // Last query+filter actually launched; an identical search while it is
    // still running is a duplicate keystroke, not a new request.
    private var lastSearchKey: String? = null

    init {
        refreshStorageInfo()
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        debounceJob?.cancel()
        val q = newQuery.trim()
        if (q.length >= 2) {
            debounceJob = viewModelScope.launch {
                kotlinx.coroutines.delay(350)
                search(q)
            }
        } else if (q.isBlank()) {
            searchJob?.cancel()
            _uiState.update { it.copy(isSearching = false, searchResults = emptyList(), searchError = null) }
        }
    }

    fun onFilterSelected(filter: String) {
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                // Clear stale results from the previous filter so the user never
                // sees (or taps) all-sites results under an Anitaku filter while
                // the new search is in flight.
                searchResults = emptyList()
            )
        }
        val currentQuery = _uiState.value.query
        if (currentQuery.isNotBlank()) {
            search(currentQuery)
        }
    }

    fun handlePastedInput(input: String, onOpenSocial: (String, String) -> Unit) {
        com.anonrode.downloader.util.DebugLog.user("paste: ${input.take(120)}")
        when (val parsed = UrlRouter.parse(input)) {
            is ParsedUrl.DramaUrl -> {
                com.anonrode.downloader.util.DebugLog.user("routed DramaUrl -> open drawer ${parsed.showCard.title}")
                openEpisodeDrawer(parsed.showCard)
            }
            is ParsedUrl.SocialUrl -> {
                com.anonrode.downloader.util.DebugLog.user("routed SocialUrl platform=${parsed.platform} instant=${engine.instantSocialDownload}")
                if (engine.instantSocialDownload) {
                    engine.enqueue(
                        showTitle = "Social/${parsed.platform}",
                        episodeNum = 1,
                        episodeTitle = "${parsed.platform} Video",
                        sourceUrl = parsed.cleanUrl,
                        isDirect = false,
                        backend = "yt-dlp",
                        parallelSockets = engine.parallelSocketsPerFile
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
                    parallelSockets = engine.parallelSocketsPerFile
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
        // Dedupe: the log showed the same query+filter re-fired seconds apart
        // (double keystroke), doubling the whole crawl. Skip when an identical
        // search is already running — its results will land.
        val key = "${q.lowercase()}::${_uiState.value.selectedFilter}"
        if (searchJob?.isActive == true && lastSearchKey == key) return
        lastSearchKey = key
        com.anonrode.downloader.util.DebugLog.user("search \"$q\" filter=${_uiState.value.selectedFilter}")

        debounceJob?.cancel()
        searchJob?.cancel()
        // The old search's coroutine dies instantly, but its blocking HTTP
        // calls would keep running — on mobile, keystroke-spam searches left
        // stale requests draining data for seconds (activity log: ~40 requests
        // per keystroke, overlapping searches). Kill just the search-tagged
        // calls; download/resolver calls are untouched.
        com.anonrode.downloader.data.net.HttpClient.cancelTagged("search")
        searchJob = viewModelScope.launch {
            val seq = ++searchSequence
            _uiState.update { it.copy(isSearching = true, searchError = null) }

            val filter = _uiState.value.selectedFilter

            try {
                ProviderRegistry.searchFlow(q, filter).collect { incomingRanked ->
                    _uiState.update { it.copy(searchResults = incomingRanked) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled search (new query, filter change, clear) must not
                // run the failure path or clear isSearching for the *new* job.
                // Rethrow so the finally below only runs for this job's state.
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(searchError = e.message ?: "Search failed") }
            } finally {
                // Only clear isSearching when this job is still the newest one:
                // a superseded job's finally runs *after* the replacement job
                // started (both on Main.immediate), and clearing the flag then
                // would render "No results found" for the entire new search.
                if (seq == searchSequence) {
                    _uiState.update { it.copy(isSearching = false) }
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

        // Track the load job so closing the drawer cancels it: otherwise
        // reopening the same show launches a second concurrent load and both
        // race to write shared drawer state.
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    ProviderRegistry.loadEpisodes(show)
                }
                _uiState.update {
                    it.copy(
                        drawerEpisodes = details.episodes,
                        isEpisodesLoading = false,
                        episodesError = if (details.episodes.isEmpty()) "No episodes found on this page" else null
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        episodesJob?.cancel()
        _uiState.update { it.copy(activeShowForDrawer = null, drawerEpisodes = emptyList()) }
    }

    fun downloadEpisode(episode: EpisodeItem) {
        val show = _uiState.value.activeShowForDrawer ?: return
        com.anonrode.downloader.util.DebugLog.user("enqueue episode: ${show.title} - ${episode.title} url=${episode.url.take(100)}")
        engine.enqueue(
            showTitle = show.title,
            episodeNum = episode.episodeNum,
            episodeTitle = "${show.title} - ${episode.title}",
            sourceUrl = episode.url,
            isDirect = false,
            backend = "aria2c",
            parallelSockets = engine.parallelSocketsPerFile,
            site = episode.site.ifBlank { show.site }
        )
    }

    fun downloadAllEpisodes(episodes: List<EpisodeItem>) {
        val show = _uiState.value.activeShowForDrawer ?: return
        com.anonrode.downloader.util.DebugLog.user("enqueue batch: ${episodes.size} episodes of ${show.title}")
        for (ep in episodes) {
            engine.enqueue(
                showTitle = show.title,
                episodeNum = ep.episodeNum,
                episodeTitle = "${show.title} - ${ep.title}",
                sourceUrl = ep.url,
                isDirect = false,
                backend = "aria2c",
                parallelSockets = engine.parallelSocketsPerFile,
                site = ep.site.ifBlank { show.site }
            )
        }
    }

    fun saveSettings(
        maxConcurrent: Int,
        parallelSockets: Int,
        quality: String,
        autoOrganize: Boolean,
        storageGuard: Double,
        wifiOnlyTorrents: Boolean,
        instantSocial: Boolean,
        showPosters: Boolean,
        stallTimeout: Int = 60,
        magnetRetries: Int = 3,
        ytdlpRetries: Int = 3,
        hlsFragments: Int = 16,
        speedLimit: Int = 0,
        peers: Int = -1,
        wifiAll: Boolean = false,
        clipboard: Boolean = true,
        notifications: Boolean = true,
        debugLog: Boolean = false
    ) {
        engine.saveAllSettings(
            maxConcurrent = maxConcurrent,
            parallelSockets = parallelSockets,
            quality = quality,
            autoOrganize = autoOrganize,
            storageGuard = storageGuard,
            wifiOnlyTorrents = wifiOnlyTorrents,
            instantSocial = instantSocial,
            showPosters = showPosters,
            stallTimeout = stallTimeout,
            magnetRetries = magnetRetries,
            ytdlpRetries = ytdlpRetries,
            hlsFragments = hlsFragments,
            speedLimit = speedLimit,
            peers = peers,
            wifiAll = wifiAll,
            clipboard = clipboard,
            notifications = notifications,
            debugLog = debugLog
        )
        com.anonrode.downloader.util.DebugLog.user(
            "settings saved (sockets=$parallelSockets quality=$quality stall=${stallTimeout}s hls=$hlsFragments peers=$peers speedLimit=$speedLimit)"
        )
    }

    /** Re-reads free/total storage so the Settings sheet shows live values
     *  instead of the app-start snapshot. */
    fun refreshStorageInfo() {
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
