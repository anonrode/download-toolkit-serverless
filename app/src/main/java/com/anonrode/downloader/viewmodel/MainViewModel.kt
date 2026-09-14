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
    val totalStorageGb: Long = 0L,
    // Trending-on-open row (feature request: show what's trending when the
    // app opens, scrolling left to right). Fetched once per app process; a
    // failed fetch shows a Retry affordance instead of silently hiding it.
    val trending: List<ShowCard> = emptyList(),
    val isTrendingLoading: Boolean = false,
    val trendingFailed: Boolean = false,
    // Category sections (chips under Trending). activeCategory is the open
    // page's Category (null = Home shows the normal landing). rows/loading/
    // failed are the fetch trio for whatever page is open — same shape as the
    // trending trio above, reused per category via a process cache.
    val activeCategory: com.anonrode.downloader.providers.CategoryFeed.Category? = null,
    val categoryRows: List<com.anonrode.downloader.providers.CategoryFeed.CategoryRow> = emptyList(),
    val isCategoryLoading: Boolean = false,
    val categoryFailed: Boolean = false,
    // Home genre tiles: each category's current top-post poster, fetched once
    // per process alongside the trending row. An empty list (or an empty
    // posterUrl) renders the colored name-tile fallback — the row must never
    // disappear over artwork.
    val genreTiles: List<com.anonrode.downloader.providers.CategoryFeed.GenreTile> = emptyList(),
    // Search-verify oracle verdicts keyed by VerdictPolicy.keyFor(card.url).
    // HomeScreen renders through VerdictPolicy.visibleOrdered: proven-dead
    // hidden, LIVE first + captioned, everything else exactly as before.
    val verdicts: Map<String, com.anonrode.downloader.pipeline.Verdict> = emptyMap()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val engine: DownloadEngine = (application as com.anonrode.downloader.AnonApp).engine

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var episodesJob: Job? = null
    private var trendingJob: Job? = null
    private var categoryJob: Job? = null
    // Genre-label -> fetched rows. Like trending, a category is fetched once
    // per process (the data is "what's up this week" and re-crawling on every
    // chip tap burns metered data for near-identical rows). Retry forces.
    private val categoryCache = mutableMapOf<String, List<com.anonrode.downloader.providers.CategoryFeed.CategoryRow>>()
    private var searchSequence = 0L
    // Last query+filter actually launched; an identical search while it is
    // still running is a duplicate keystroke, not a new request.
    private var lastSearchKey: String? = null
    // The query whose results are currently in uiState.searchResults (set when
    // a search actually launches). Lets search() drop the PREVIOUS query's
    // cards the moment a different query starts, so they can never be tapped
    // under the new query while the new crawl runs — the same contract
    // onFilterSelected already applies to filter switches. Without this, a
    // slow or failed crawl leaves the old query's results on screen and
    // neither the loading branch (results non-empty) nor the "No results"
    // branch in HomeScreen can ever replace them.
    private var resultsForQuery: String? = null

    // ---- settings-save coalescing -------------------------------------------
    //
    // Every toggle/slider in the settings sheet called saveSettings →
    // engine.saveAllSettings, which rewrites ALL prefs and reconfigures log
    // retention each time. The activity log showed 14 full saves in 8 seconds
    // while the user flipped a few switches. The latest snapshot is held here
    // and flushed once, 500ms after the last change; explicit flushes on
    // ON_PAUSE/dispose cover the "flip switch, immediately background/close
    // the app" window. Persistence format and keys are unchanged.
    private data class SettingsSnapshot(
        val maxConcurrent: Int,
        val parallelSockets: Int,
        val quality: String,
        val autoOrganize: Boolean,
        val storageGuard: Double,
        val wifiOnlyTorrents: Boolean,
        val instantSocial: Boolean,
        val showPosters: Boolean,
        val stallTimeout: Int,
        val magnetRetries: Int,
        val ytdlpRetries: Int,
        val hlsFragments: Int,
        val speedLimit: Int,
        val peers: Int,
        val privacyMode: Boolean,
        val wifiAll: Boolean,
        val clipboard: Boolean,
        val notifications: Boolean,
        val debugLog: Boolean,
        val logRetention: Int,
        val downloadSubs: Boolean,
        val subLang: String
    )

    private var pendingSettings: SettingsSnapshot? = null
    private var settingsSaveJob: Job? = null

    /** Writes the held snapshot to the engine NOW (on the caller's thread —
     *  the engine's save is SharedPreferences apply(), safe off Main too, and
     *  callers here are Main). Safe to call repeatedly; no-op when nothing
     *  is pending. */
    fun flushPendingSettings() {
        settingsSaveJob?.cancel()
        settingsSaveJob = null
        val snapshot = pendingSettings ?: return
        pendingSettings = null
        engine.saveAllSettings(
            maxConcurrent = snapshot.maxConcurrent,
            parallelSockets = snapshot.parallelSockets,
            quality = snapshot.quality,
            autoOrganize = snapshot.autoOrganize,
            storageGuard = snapshot.storageGuard,
            wifiOnlyTorrents = snapshot.wifiOnlyTorrents,
            instantSocial = snapshot.instantSocial,
            showPosters = snapshot.showPosters,
            stallTimeout = snapshot.stallTimeout,
            magnetRetries = snapshot.magnetRetries,
            ytdlpRetries = snapshot.ytdlpRetries,
            hlsFragments = snapshot.hlsFragments,
            speedLimit = snapshot.speedLimit,
            peers = snapshot.peers,
            privacyMode = snapshot.privacyMode,
            wifiAll = snapshot.wifiAll,
            clipboard = snapshot.clipboard,
            notifications = snapshot.notifications,
            debugLog = snapshot.debugLog,
            logRetention = snapshot.logRetention,
            downloadSubs = snapshot.downloadSubs,
            subLang = snapshot.subLang
        )
        com.anonrode.downloader.util.DebugLog.user(
            "settings saved (sockets=${snapshot.parallelSockets} quality=${snapshot.quality} stall=${snapshot.stallTimeout}s hls=${snapshot.hlsFragments} peers=${snapshot.peers} speedLimit=${snapshot.speedLimit})"
        )
    }

    init {
        refreshStorageInfo()
        loadTrending()
        loadGenreTiles()
        // Oracle verdicts ride their own stream: a background verification
        // landing seconds after the search flow completed still re-renders
        // (floats the card up + captions it) without restarting the crawl.
        viewModelScope.launch {
            com.anonrode.downloader.pipeline.ResultVerifier.updates().collect { v ->
                _uiState.update { it.copy(verdicts = v) }
            }
        }
    }

    /** One fetch per app process, same rule as the trending row: six
     *  per_page=1 requests, cached in memory, failure degrades to glyph
     *  tiles instead of hiding the section. */
    private var genreTilesJob: Job? = null
    fun loadGenreTiles() {
        if (genreTilesJob?.isActive == true || _uiState.value.genreTiles.isNotEmpty()) return
        genreTilesJob = viewModelScope.launch {
            try {
                val tiles = withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.CategoryFeed.tilePosters()
                }
                _uiState.update { it.copy(genreTiles = tiles) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // No throw-up: empty genreTiles already renders as name-tiles.
            }
        }
    }

    /** One fetch per app process: the row is for the moment of opening, and
     *  re-fetching on every tab return would burn data for near-identical
     *  cards. force=true (Retry tap) always re-crawls. */
    fun loadTrending(force: Boolean = false) {
        if (trendingJob?.isActive == true) return
        if (!force && _uiState.value.trending.isNotEmpty()) return
        trendingJob = viewModelScope.launch {
            _uiState.update { it.copy(isTrendingLoading = true, trendingFailed = false) }
            com.anonrode.downloader.util.DebugLog.user("trending: fetch started")
            try {
                val items = withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.TrendingFeed.fetch()
                }
                _uiState.update {
                    it.copy(
                        trending = items,
                        isTrendingLoading = false,
                        trendingFailed = items.isEmpty()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("trending: fetch failed ${e.message}")
                _uiState.update { it.copy(isTrendingLoading = false, trendingFailed = true) }
            }
        }
    }

    /** Open a category's full-page of per-site rows. Cached rows render with
     *  no loading flash; a first tap shows the spinner while fetching. A
     *  still-running load for a DIFFERENT category belongs to a page the user
     *  already left and is cancelled (the fetch itself label-guards its
     *  state writes, so a late arrival can never bleed into another page). */
    fun openCategory(category: com.anonrode.downloader.providers.CategoryFeed.Category) {
        if (categoryJob?.isActive == true && _uiState.value.activeCategory?.label != category.label) {
            categoryJob?.cancel()
        }
        val cached = categoryCache[category.label]
        _uiState.update {
            it.copy(
                activeCategory = category,
                categoryRows = cached ?: emptyList(),
                isCategoryLoading = cached == null,
                categoryFailed = cached?.isEmpty() == true
            )
        }
        if (cached != null) return
        loadCategory(category)
    }

    /** Close the page; cancels any in-flight crawl (partials stay in the
     *  process cache only on success — a cancelled fetch caches nothing, so
     *  reopening refetches). */
    fun closeCategory() {
        categoryJob?.cancel()
        categoryJob = null
        _uiState.update {
            it.copy(
                activeCategory = null,
                categoryRows = emptyList(),
                isCategoryLoading = false,
                categoryFailed = false
            )
        }
    }

    /** Same contract as [loadTrending]: one fetch per category per process,
     *  force=true (the Retry tap) always re-crawls. */
    fun loadCategory(
        category: com.anonrode.downloader.providers.CategoryFeed.Category,
        force: Boolean = false
    ) {
        if (categoryJob?.isActive == true) return
        val cached = categoryCache[category.label]
        if (!force && cached != null) {
            _uiState.update {
                it.copy(categoryRows = cached, isCategoryLoading = false, categoryFailed = cached.isEmpty())
            }
            return
        }
        categoryJob = viewModelScope.launch {
            _uiState.update { it.copy(isCategoryLoading = true, categoryFailed = false) }
            com.anonrode.downloader.util.DebugLog.user("category '${category.label}': fetch started")
            try {
                val rows = withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.CategoryFeed.fetch(category)
                }
                categoryCache[category.label] = rows
                if (_uiState.value.activeCategory?.label == category.label) {
                    _uiState.update {
                        it.copy(categoryRows = rows, isCategoryLoading = false, categoryFailed = rows.isEmpty())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("category '${category.label}': fetch failed ${e.message}")
                if (_uiState.value.activeCategory?.label == category.label) {
                    _uiState.update { it.copy(isCategoryLoading = false, categoryFailed = true) }
                }
            }
        }
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
            // Same rule as search(): the dead coroutine stops instantly, but
            // its blocking search HTTP keeps draining until it finishes.
            // Killing the tagged calls keeps a cleared search from trailing.
            com.anonrode.downloader.data.net.HttpClient.cancelTagged("search")
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
        // A genuinely NEW query must not keep displaying the previous query's
        // cards while it crawls (the filter path already enforces this — see
        // onFilterSelected). Without the clear, a slow or failed crawl leaves
        // the old query's results on screen under the new query — the
        // "searched a movie, got the last search's series results" report —
        // and HomeScreen's loading branch (which requires empty results) and
        // its "No results" branch can never replace them.
        val lcq = q.lowercase()
        if (resultsForQuery != lcq) {
            _uiState.update { it.copy(searchResults = emptyList()) }
        }
        resultsForQuery = lcq
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
                    // Background-verify the best unverified cards (per-query
                    // budget inside the verifier; fresh verdicts are skipped,
                    // so streaming snapshots never double-spend).
                    com.anonrode.downloader.pipeline.ResultVerifier.submit(
                        viewModelScope, q, incomingRanked
                    )
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
        // If the oracle is still verifying this card, move it to the head so
        // its verdict (and the cracked direct URL it caches) lands now — the
        // drawer's own loadEpisodes and the verifier share ProviderRegistry's
        // 5-min show cache, so the tap is already fast regardless; this just
        // finishes the badge.
        com.anonrode.downloader.pipeline.ResultVerifier.prioritize(show)
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
            site = episode.site.ifBlank { show.site },
            // Oracle instant-tap: if verification byte-proved THIS episode's
            // direct URL seconds ago, the engine skips RESOLVING entirely
            // (guarded inside enqueue; null/absent = today's exact path).
            verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier
                .verifiedDirect(episode.url)
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
                site = ep.site.ifBlank { show.site },
                verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier
                    .verifiedDirect(ep.url)
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
        privacyMode: Boolean = false,
        wifiAll: Boolean = false,
        clipboard: Boolean = true,
        notifications: Boolean = true,
        debugLog: Boolean = false,
        logRetention: Int = 7,
        downloadSubs: Boolean = true,
        subLang: String = "en"
    ) {
        // Coalesce: hold the latest snapshot and flush once 500ms after the
        // last change (see the SettingsSnapshot note above). No persistence
        // format change — engine.saveAllSettings signature untouched.
        pendingSettings = SettingsSnapshot(
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
            privacyMode = privacyMode,
            wifiAll = wifiAll,
            clipboard = clipboard,
            notifications = notifications,
            debugLog = debugLog,
            logRetention = logRetention,
            downloadSubs = downloadSubs,
            subLang = subLang
        )
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            flushPendingSettings()
        }
    }

    /** Re-reads free/total storage so the Settings sheet shows live values
     *  instead of the app-start snapshot. Runs on IO: StatFs is a syscall
     *  that can stall behind the storage daemon, and this is invoked from
     *  a LaunchedEffect when Settings opens — doing it on the main thread
     *  was the visible hitch on tapping the Settings tab. */
    fun refreshStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
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
}
