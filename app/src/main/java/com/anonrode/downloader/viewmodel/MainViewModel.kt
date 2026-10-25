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
import com.anonrode.downloader.providers.PosterNetwork
import com.anonrode.downloader.providers.PosterStore
import com.anonrode.downloader.providers.RelevanceScorer
import com.anonrode.downloader.util.SearchQueryGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HomeUiState(
    val query: String = "",
    val selectedFilter: String = "all",
    val isSearching: Boolean = false,
    val searchResults: List<ShowCard> = emptyList(),
    val searchError: String? = null,
    val activeShowForDrawer: ShowCard? = null,
    val drawerEpisodes: List<EpisodeItem> = emptyList(),
    // PL-6: the story blurb every provider already parses (ShowDetails.synopsis)
    // and the UI used to drop. Rendered in the drawer header; empty = hidden.
    val drawerSynopsis: String = "",
    val isEpisodesLoading: Boolean = false,
    val episodesError: String? = null,
    // Track 4 ("Download Remaining"): when the drawer was launched from a
    // Downloads-series card, the un-downloaded episodes auto-select on first
    // load instead of making the user hunt them out of a 130-row list.
    val drawerPreselectRemaining: Boolean = false,
    val freeStorageGb: Long = 0L,
    val totalStorageGb: Long = 0L,
    // Trending-on-open row (feature request: show what's trending when the
    // app opens, scrolling left to right). Fetched once per app process; a
    // failed fetch shows a Retry affordance instead of silently hiding it.
    val trending: List<ShowCard> = emptyList(),
    val isTrendingLoading: Boolean = false,
    val trendingFailed: Boolean = false,
    // Category sections (v3.1.6 MIXED redesign). activeCategory is the open
    // grid page's Category (null = not open); categoryCards is that page's
    // single mixed list — every site's cards interleaved, no per-site rows,
    // no site names. catalogOpen shows the "View More" page: one horizontal
    // mixed row per genre, fed by catalogRows, each row lazy-loading as it
    // scrolls into view (never six genres × five sites at once).
    val activeCategory: com.anonrode.downloader.providers.CategoryFeed.Category? = null,
    val categoryCards: List<ShowCard> = emptyList(),
    val isCategoryLoading: Boolean = false,
    val categoryFailed: Boolean = false,
    val categoryPage: Int = 1,
    val isCategoryLoadingMore: Boolean = false,
    val categoryHasMore: Boolean = true,
    val catalogOpen: Boolean = false,
    val catalogRows: Map<String, List<ShowCard>> = emptyMap(),
    // Trending pagination
    val trendingPage: Int = 1,
    val isTrendingLoadingMore: Boolean = false,
    val trendingHasMore: Boolean = true,
    // Home genre tiles: each category's current top-post poster, fetched once
    // per process alongside the trending row. An empty list (or an empty
    // posterUrl) renders the colored name-tile fallback — the row must never
    // disappear over artwork.
    val genreTiles: List<com.anonrode.downloader.providers.CategoryFeed.GenreTile> = emptyList(),
    // Search-verify oracle verdicts keyed by VerdictPolicy.keyFor(card.url).
    // HomeScreen renders through VerdictPolicy.visibleOrdered: proven-dead
    // hidden, LIVE first + captioned, everything else exactly as before.
    val verdicts: Map<String, com.anonrode.downloader.pipeline.Verdict> = emptyMap(),
    // Asian Drama Explorer state
    val activeDramaRegion: com.anonrode.downloader.providers.DramaRegion? = null,
    val activeDramaEra: com.anonrode.downloader.providers.DramaEra = com.anonrode.downloader.providers.DramaEra.MODERN,
    val activeDramaStatus: com.anonrode.downloader.providers.DramaStatusFilter = com.anonrode.downloader.providers.DramaStatusFilter.ALL,
    val activeDramaGenre: com.anonrode.downloader.providers.DramaGenre? = null,
    val dramaRawCards: List<ShowCard> = emptyList(),
    val dramaCards: List<ShowCard> = emptyList(),
    val isDramaLoading: Boolean = false,
    val dramaFailed: Boolean = false,
    val dramaPage: Int = 1,
    val isDramaLoadingMore: Boolean = false,
    val dramaHasMore: Boolean = true,
    /** Per-region snapshot so an exiting overlay page never re-reads the
     *  next region's live list during the AnimatedContent transition. */
    val dramaCardsByRegion: Map<com.anonrode.downloader.providers.DramaRegion, List<ShowCard>> = emptyMap(),
    /** True when the OS reports no usable network. PosterStore promotes the
     *  last loaded URL to primary in this state (zero network attempts). */
    val isOffline: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val engine: DownloadEngine = (application as com.anonrode.downloader.AnonApp).engine

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ---- YouTube playlist picker state (2026-09-15) -------------------------
    // Separate flow (not HomeUiState): the sheet is a modal surface with its
    // own lifecycle, and every HomeUiState copy site would need touching
    // otherwise. url != null means the sheet is open.
    data class PlaylistUiState(
        val url: String? = null,
        val parsed: com.anonrode.downloader.pipeline.PlaylistPicker.ParsedList? = null,
        val loading: Boolean = false,
        val meta: com.anonrode.downloader.pipeline.PlaylistPicker.PlaylistMeta? = null,
        val error: String? = null
    )
    private val _playlistState = MutableStateFlow(PlaylistUiState())
    val playlistState: StateFlow<PlaylistUiState> = _playlistState.asStateFlow()
    private var playlistJob: Job? = null

    val activeSocialTarget = MutableStateFlow<Pair<String, String>?>(null)
    val activePlayingTaskId = MutableStateFlow<String?>(null)

    /** Read a playlist's flat metadata. Cheap by design: ONE
     *  `yt-dlp -J --flat-playlist` dump (no per-video page loads — on a
     *  capped plan that's the difference between ~10 KB and hundreds). */
    fun openPlaylist(url: String) {
        val parsed = com.anonrode.downloader.pipeline.PlaylistPicker.detect(url) ?: return
        com.anonrode.downloader.util.DebugLog.user("playlist open: ${parsed.listId} kind=${parsed.kind}")
        _playlistState.value = PlaylistUiState(url = url, parsed = parsed, loading = true)
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            if (!com.anonrode.downloader.AnonApp.ensureReady()) {
                _playlistState.update { it.copy(loading = false, error = "The downloader core is still starting — try again in a moment.") }
                return@launch
            }
            val json = com.anonrode.downloader.engine.YoutubeDlDownloader.fetchPlaylistJson(
                getApplication(), parsed.playlistUrl
            )
            val meta = json?.let { com.anonrode.downloader.pipeline.PlaylistPicker.parseFlatJson(it) }
            _playlistState.update {
                if (meta == null || meta.entries.isEmpty())
                    it.copy(
                        loading = false,
                        error = "Couldn't read this playlist — it may be private, age-restricted, or YouTube is busy. Try again."
                    )
                else
                    it.copy(loading = false, meta = meta)
            }
        }
    }

    fun closePlaylist() {
        playlistJob?.cancel()
        _playlistState.value = PlaylistUiState()
    }

    /** Queue the selection as one grouped show; closes the sheet. */
    fun confirmPlaylist(indices: List<Int>, audioOnly: Boolean, quality: String?) {
        val meta = _playlistState.value.meta ?: return
        val n = engine.enqueuePlaylist(meta, indices, audioOnly, quality)
        com.anonrode.downloader.util.DebugLog.user("playlist enqueue: $n/${indices.size} under \"${meta.title}\"")
        closePlaylist()
    }

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var episodesJob: Job? = null
    private var trendingJob: Job? = null
    private var categoryJob: Job? = null
    // Genre-label -> mixed cards. L2 in-process cache layered over FeedCache
    // (disk L1, 30-min TTL): a fetched genre never re-crawls within the
    // process, and across restarts the disk snapshot paints first while a
    // stale group refetches silently. Retry/refresh force through both.
    private val categoryCache = mutableMapOf<String, List<com.anonrode.downloader.data.models.ShowCard>>()
    // Genres whose catalog row scrolled into view WHILE another genre's
    // single-flight crawl was running. Without this queue those rows would
    // sit as spinners until the user scrolls away and back (their
    // LaunchedEffect won't re-fire) — the queue drains after each crawl so
    // every row that was ever seen eventually loads, still one at a time.
    private val pendingCatalogGenres = mutableSetOf<String>()
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
        val subLang: String,
        val filterExplicit: Boolean = true
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
            subLang = snapshot.subLang,
            filterExplicit = snapshot.filterExplicit
        )
        com.anonrode.downloader.util.DebugLog.user(
            "settings saved (sockets=${snapshot.parallelSockets} quality=${snapshot.quality} stall=${snapshot.stallTimeout}s hls=${snapshot.hlsFragments} peers=${snapshot.peers} speedLimit=${snapshot.speedLimit})"
        )
    }

    override fun onCleared() {
        // viewModelScope is cancelled right after this call; a pending
        // 500ms coalesced settings write would otherwise be dropped when the
        // Activity finishes. The engine save is SharedPreferences.apply().
        flushPendingSettings()
        super.onCleared()
    }

    // ── Genre tiles: one retry loop per category, backoff 2/5/15/60/300s ────

    private val tileJobs = mutableMapOf<String, Job>()
    /** Broadcast connection epoch: every retry waiter observes the change. */
    private val networkEpoch = MutableStateFlow(0L)
    private val tileRevalidateMs = 12L * 60 * 60 * 1000

    init {
        refreshStorageInfo()
        val app = application as com.anonrode.downloader.AnonApp
        // Hydrate only after BOTH files have been read. The old synchronous
        // read raced AnonApp's background init: a slow cold start painted
        // nothing and then started a network crawl over an empty cache.
        viewModelScope.launch {
            app.awaitCachesReady().await()
            hydrateFromCaches()
            app.awaitInitReady().await()
            // loadTrending() itself returns early when the disk row is fresh;
            // stale rows refetch silently behind the painted cards.
            loadTrending()
            loadGenreTiles()
        }
        viewModelScope.launch {
            app.networkObserver.status.collect { status ->
                val online = status.isConnected
                PosterNetwork.isOnline = online
                _uiState.update { it.copy(isOffline = !online) }
                // Broadcast (not a single-consumer Channel): EVERY failed
                // category must wake immediately on reconnect.
                networkEpoch.value = networkEpoch.value + 1L
            }
        }
        // Oracle verdicts ride their own stream: a background verification
        // landing seconds after the search flow completed still re-renders
        // (floats the card up + captions it) without restarting the crawl.
        viewModelScope.launch {
            com.anonrode.downloader.pipeline.ResultVerifier.updates().collect { v ->
                _uiState.update { it.copy(verdicts = v) }
            }
        }
    }

    /** Disk hydration, now deterministic: FeedCache + PosterStore are ready
     *  before the first state publish. Real last-good art wins; the static
     *  preview map is only a UI-level last resort. */
    private fun hydrateFromCaches() {
        val cached = com.anonrode.downloader.providers.FeedCache.snapshot
        val filterExplicit = engine.filterExplicitContent
        val offline = !PosterNetwork.isOnline
        val cachedTrending = PosterStore.enrich(
            com.anonrode.downloader.util.NollywoodFilter.filterSafe(
                if (filterExplicit) {
                    com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(cached.trending)
                } else {
                    cached.trending
                }
            ),
            offline
        )
        val cachedCategories = cached.categories.mapValues { e ->
            val cards = if (filterExplicit) {
                com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(e.value.cards)
            } else {
                e.value.cards
            }
            e.value.copy(
                cards = PosterStore.enrich(
                    com.anonrode.downloader.util.NollywoodFilter.filterSafe(cards),
                    offline
                )
            )
        }
        if (cachedTrending.isNotEmpty() || cachedCategories.isNotEmpty()) {
            categoryCache.putAll(cachedCategories.mapValues { it.value.cards })
        }
        val tiles = com.anonrode.downloader.providers.CategoryFeed.CATEGORIES.mapNotNull { category ->
            val urls = PosterStore.tileUrls(category.label)
            when {
                urls.isNotEmpty() -> com.anonrode.downloader.providers.CategoryFeed.GenreTile(
                    category,
                    urls.getOrElse(0) { "" },
                    urls.getOrElse(1) { "" }
                )
                else -> com.anonrode.downloader.providers.FeedCache.tile(category.label)
                    ?.posterUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { com.anonrode.downloader.providers.CategoryFeed.GenreTile(category, it) }
            }
        }
        if (cachedTrending.isNotEmpty() || cachedCategories.isNotEmpty() || tiles.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    trending = cachedTrending,
                    genreTiles = tiles,
                    catalogRows = cachedCategories.mapValues { e -> e.value.cards },
                    isOffline = offline
                )
            }
        }
    }

    fun loadGenreTiles(force: Boolean = false) {
        for (category in com.anonrode.downloader.providers.CategoryFeed.CATEGORIES) {
            val running = tileJobs[category.label]
            if (running?.isActive == true) {
                if (force) running.cancel() else continue
            }
            if (!force) {
                val urls = PosterStore.tileUrls(category.label)
                val age = System.currentTimeMillis() - PosterStore.tileSavedAt(category.label)
                if (urls.isNotEmpty() && age in 0 until tileRevalidateMs) continue
            }
            tileJobs[category.label] = viewModelScope.launch {
                retryTileUntilSuccess(category, forceOnce = force)
            }
        }
    }

    private suspend fun retryTileUntilSuccess(
        category: com.anonrode.downloader.providers.CategoryFeed.Category,
        forceOnce: Boolean
    ) {
        var attempt = PosterStore.tileFailures(category.label)
        var first = forceOnce
        while (currentCoroutineContext().isActive) {
            if (!PosterNetwork.isOnline) {
                networkEpoch.first { PosterNetwork.isOnline }
                continue
            }
            if (!first) {
                val wait = PosterStore.nextTileRetryAt(category.label) - System.currentTimeMillis()
                if (wait > 0) {
                    val epoch = networkEpoch.value
                    withTimeoutOrNull(wait) { networkEpoch.first { it != epoch } }
                }
            }
            first = false
            val urls = try {
                withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.CategoryFeed.tilePostersFor(
                        category,
                        engine.filterExplicitContent,
                        max = 2
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (urls.any { it.isNotBlank() }) {
                val merged = PosterStore.saveTileSuccess(category.label, urls)
                PosterStore.flush()
                publishGenreTile(category, merged)
                com.anonrode.downloader.providers.FeedCache.saveTiles(currentTilesForCache())
                return
            }
            attempt += 1
            val backoff = tileBackoff(attempt)
            PosterStore.recordTileFailure(
                category.label,
                attempt,
                System.currentTimeMillis() + backoff
            )
            // Old art (if any) stays on screen; the retry loop keeps going.
            val epoch = networkEpoch.value
            withTimeoutOrNull(backoff) { networkEpoch.first { it != epoch } }
        }
    }

    private fun tileBackoff(attempt: Int): Long = when (attempt) {
        1 -> 2_000L
        2 -> 5_000L
        3 -> 15_000L
        4 -> 60_000L
        else -> 300_000L
    }

    private fun publishGenreTile(
        category: com.anonrode.downloader.providers.CategoryFeed.Category,
        urls: List<String>
    ) {
        _uiState.update { state ->
            val byLabel = state.genreTiles.associateBy { it.category.label }.toMutableMap()
            byLabel[category.label] = com.anonrode.downloader.providers.CategoryFeed.GenreTile(
                category,
                urls.getOrElse(0) { "" },
                urls.getOrElse(1) { "" }
            )
            state.copy(
                genreTiles = com.anonrode.downloader.providers.CategoryFeed.CATEGORIES
                    .mapNotNull { byLabel[it.label] }
            )
        }
    }

    private fun currentTilesForCache(): List<com.anonrode.downloader.providers.TilePoster> =
        _uiState.value.genreTiles.mapNotNull { tile ->
            val primary = tile.posterUrl.ifBlank { tile.secondaryPosterUrl }
            if (primary.isBlank()) null
            else com.anonrode.downloader.providers.TilePoster(tile.category.label, primary)
        }

    /** v3.1.6 cache-first: a disk-fresh row (<=30 min) painted at init means
     *  ZERO network this launch. Stale → refetch SILENTLY behind the painted
     *  cards (spinner only when there is nothing to show); success rewrites
     *  memory + disk. force=true (the header Refresh tap) always crawls. */
    private var trendingMoreJob: Job? = null

    /** v3.1.6 cache-first: a disk-fresh row (<=30 min) painted at init means
     *  ZERO network this launch. Stale → refetch SILENTLY behind the painted
     *  cards (spinner only when there is nothing to show); success rewrites
     *  memory + disk. force=true (the header Refresh tap) always crawls. */
    fun loadTrending(force: Boolean = false) {
        if (trendingJob?.isActive == true) return
        val existing = _uiState.value.trending
        if (!force && existing.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isTrendingFresh()
        ) return
        trendingJob = viewModelScope.launch {
            val showSpinner = existing.isEmpty()
            if (showSpinner) _uiState.update { it.copy(isTrendingLoading = true, trendingFailed = false, trendingPage = 1, trendingHasMore = true) }
            com.anonrode.downloader.util.DebugLog.user("trending: fetch started")
            try {
                val items = withContext(Dispatchers.IO) {
                    // Streaming: each site re-publishes the partial
                    // round-robin merge the moment it lands, so the row fills
                    // while a laggard (9jarocks' RSS) is still crawling.
                    // Only a genuinely empty screen streams: publishing a
                    // page-1 partial over an existing multi-page list is the
                    // shrink that trips LazyGrid.
                    com.anonrode.downloader.providers.TrendingFeed.fetch(
                        page = 1,
                        filterExplicit = engine.filterExplicitContent,
                        onPartial = { partial ->
                            if (existing.isEmpty() && partial.isNotEmpty()) {
                                val enriched = PosterStore.enrich(
                                    partial.distinctBy { it.url },
                                    offline = !PosterNetwork.isOnline
                                )
                                _uiState.update { it.copy(trending = enriched) }
                            }
                        }
                    )
                }
                if (items.isNotEmpty()) {
                    _uiState.update { state ->
                        val merged = mergeCards(state.trending, items)
                        val enriched = PosterStore.enrich(
                            merged.distinctBy { it.url },
                            offline = !PosterNetwork.isOnline
                        )
                        state.copy(
                            trending = enriched,
                            isTrendingLoading = false,
                            trendingFailed = false,
                            trendingPage = 1,
                            trendingHasMore = true
                        )
                    }
                    com.anonrode.downloader.providers.FeedCache.saveTrending(
                        PosterStore.persistable(_uiState.value.trending)
                    )
                } else {
                    // Nothing new: keep whatever is painted; only the empty
                    // state earns the failure banner + Retry.
                    _uiState.update {
                        it.copy(isTrendingLoading = false, trendingFailed = it.trending.isEmpty())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("trending: fetch failed ${e.message}")
                _uiState.update {
                    it.copy(isTrendingLoading = false, trendingFailed = it.trending.isEmpty())
                }
            }
        }
    }

    fun loadMoreTrending() {
        val state = _uiState.value
        if (state.isTrendingLoading || state.isTrendingLoadingMore || !state.trendingHasMore) return
        if (trendingMoreJob?.isActive == true) return

        val nextPage = state.trendingPage + 1
        _uiState.update { it.copy(isTrendingLoadingMore = true) }

        trendingMoreJob = viewModelScope.launch {
            try {
                val newCards = withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.TrendingFeed.fetch(
                        page = nextPage,
                        filterExplicit = engine.filterExplicitContent
                    )
                }
                _uiState.update { current ->
                    val existingUrls = current.trending.map { it.url }.toSet()
                    val existingTitles = current.trending.map { it.title.lowercase().filter(Char::isLetterOrDigit) }.toSet()

                    val distinctNew = newCards.filter { card ->
                        card.url.isNotBlank() &&
                        card.url !in existingUrls &&
                        card.title.lowercase().filter(Char::isLetterOrDigit) !in existingTitles
                    }
                    val enriched = PosterStore.enrich(
                        (current.trending + distinctNew).distinctBy { it.url },
                        offline = !PosterNetwork.isOnline
                    )

                    current.copy(
                        trending = enriched,
                        trendingPage = nextPage,
                        isTrendingLoadingMore = false,
                        trendingHasMore = distinctNew.isNotEmpty()
                    )
                }
                com.anonrode.downloader.providers.FeedCache.saveTrending(
                    PosterStore.persistable(_uiState.value.trending)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("loadMoreTrending failed: ${e.message}")
                _uiState.update { it.copy(isTrendingLoadingMore = false) }
            }
        }
    }

    /** Refresh must never shrink a painted multi-page list: update matching
     *  URLs in place, append genuinely new ones, keep the old order, and cap
     *  the result so repeated refreshes cannot grow the list without bound. */
    private fun mergeCards(old: List<ShowCard>, fresh: List<ShowCard>): List<ShowCard> {
        if (old.isEmpty()) return fresh
        val freshByUrl = fresh.filter { it.url.isNotBlank() }.associateBy { it.url }
        val oldUrls = old.map { it.url }.toHashSet()
        val updatedOld = old.map { freshByUrl[it.url] ?: it }
        val appended = fresh.filter { it.url.isNotBlank() && it.url !in oldUrls }
        return (updatedOld + appended).distinctBy { it.url }.take(180)
    }

    private var categoryMoreJob: Job? = null
    private val categoryPages = mutableMapOf<String, Int>()

    /** Open a genre's MIXED grid page. Disk/memory cards render instantly
     *  (no blank, no flash); a stale or missing group refetches silently in
     *  the background, streaming its growing mix into the open page. A
     *  still-running load for a DIFFERENT genre is cancelled — its page is
     *  gone, and label-guarded writes already prevent bleed. */
    fun openCategory(category: com.anonrode.downloader.providers.CategoryFeed.Category) {
        if (categoryJob?.isActive == true && _uiState.value.activeCategory?.label != category.label) {
            categoryJob?.cancel()
        }
        categoryMoreJob?.cancel()
        val cached = categoryCache[category.label]
            ?.ifEmpty { com.anonrode.downloader.providers.FeedCache.categoryCards(category.label) }
            ?: com.anonrode.downloader.providers.FeedCache.categoryCards(category.label)
        if (cached.isNotEmpty()) categoryCache[category.label] = cached
        val page = categoryPages[category.label] ?: 1
        _uiState.update {
            it.copy(
                activeCategory = category,
                categoryCards = cached,
                isCategoryLoading = cached.isEmpty(),
                categoryFailed = false,
                categoryPage = page,
                isCategoryLoadingMore = false,
                categoryHasMore = true
            )
        }
        if (cached.isNotEmpty() && com.anonrode.downloader.providers.FeedCache.isCategoryFresh(category.label)) return
        loadCategory(category)
    }

    /** The "View More" catalog page: one horizontal mixed row per genre,
     *  each row lazy-loading via [ensureCategoryCards] as it scrolls into
     *  view — opening the page NEVER stamps all six genres at once. */
    fun openCatalog() {
        _uiState.update { it.copy(catalogOpen = true) }
    }

    fun closeCatalog() {
        _uiState.update { it.copy(catalogOpen = false) }
    }

    /** Close the genre grid; cancels its crawl (successful partials stay in
     *  the caches — a cancelled fetch caches nothing, so reopening refetches).
     *  If the catalog is open underneath, the back gesture lands there. */
    fun closeCategory() {
        categoryJob?.cancel()
        categoryJob = null
        categoryMoreJob?.cancel()
        categoryMoreJob = null
        _uiState.update {
            it.copy(
                activeCategory = null,
                categoryCards = emptyList(),
                isCategoryLoading = false,
                categoryFailed = false,
                categoryPage = 1,
                isCategoryLoadingMore = false,
                categoryHasMore = true
            )
        }
    }

    /** Catalog row loader: no-op while a genre is cached and disk-fresh, so
     *  scrolling the catalog costs bandwidth only for genres not yet seen. */
    fun ensureCategoryCards(category: com.anonrode.downloader.providers.CategoryFeed.Category) {
        val cached = categoryCache[category.label] ?: emptyList()
        if (cached.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isCategoryFresh(category.label)
        ) return
        if (categoryJob?.isActive == true) {
            pendingCatalogGenres.add(category.label)
            return
        }
        loadCategory(category)
    }

    /** Process the queue left by [ensureCategoryCards] skips. Runs in its
     *  own coroutine: called from the finally of a finishing crawl, where
     *  [categoryJob] still reads active until that coroutine completes. */
    private fun drainCatalogQueue() {
        if (pendingCatalogGenres.isEmpty()) return
        viewModelScope.launch {
            while (pendingCatalogGenres.isNotEmpty()) {
                if (categoryJob?.isActive == true) return@launch
                val label = pendingCatalogGenres.first()
                pendingCatalogGenres.remove(label)
                val cat = com.anonrode.downloader.providers.CategoryFeed.CATEGORIES
                    .firstOrNull { it.label == label } ?: continue
                ensureCategoryCards(cat)
            }
        }
    }

    /** Fetch a genre's MIXED cards: memory+disk first, silent refetch when
     *  stale, force=true (Refresh/Retry taps) always crawls. Success writes
     *  the process cache, the disk cache, the open grid page (label-guarded)
     *  AND the catalog row map. */
    fun loadCategory(
        category: com.anonrode.downloader.providers.CategoryFeed.Category,
        force: Boolean = false
    ) {
        if (categoryJob?.isActive == true) return
        categoryMoreJob?.cancel()
        val rawCached = categoryCache[category.label]
            ?: com.anonrode.downloader.providers.FeedCache.categoryCards(category.label)
        val cached = PosterStore.enrich(rawCached, offline = !PosterNetwork.isOnline)
        if (!force && cached.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isCategoryFresh(category.label)
        ) {
            _uiState.update {
                it.copy(
                    categoryCards = if (it.activeCategory?.label == category.label) cached else it.categoryCards,
                    catalogRows = it.catalogRows + (category.label to cached),
                    isCategoryLoading = false,
                    categoryPage = it.categoryPage,
                    categoryHasMore = true
                )
            }
            return
        }
        categoryJob = viewModelScope.launch {
            if (cached.isEmpty() && _uiState.value.activeCategory?.label == category.label) {
                _uiState.update { it.copy(isCategoryLoading = true, categoryFailed = false, categoryPage = 1, categoryHasMore = true) }
            }
            com.anonrode.downloader.util.DebugLog.user("category '${category.label}': fetch started")
            try {
                val cards = withContext(Dispatchers.IO) {
                    // Only a first paint streams. A refresh over a painted
                    // (possibly multi-page) list holds the old cards until a
                    // nonempty final list lands: no shrink during scroll.
                    com.anonrode.downloader.providers.CategoryFeed.fetch(
                        category,
                        page = 1,
                        filterExplicit = engine.filterExplicitContent
                    ) { partial ->
                        if (cached.isEmpty() && partial.isNotEmpty()) {
                            val enriched = PosterStore.enrich(
                                partial.distinctBy { it.url },
                                offline = !PosterNetwork.isOnline
                            )
                            _uiState.update {
                                it.copy(
                                    categoryCards = if (it.activeCategory?.label == category.label) enriched else it.categoryCards,
                                    catalogRows = it.catalogRows + (category.label to enriched)
                                )
                            }
                        }
                    }
                }
                if (cards.isNotEmpty()) {
                    // Merge against THIS label's cache, never the live
                    // categoryCards: the user may have opened another genre
                    // while this crawl was running.
                    val previous = categoryCache[category.label] ?: rawCached
                    val merged = mergeCards(previous, cards)
                    val enriched = PosterStore.enrich(
                        merged.distinctBy { it.url },
                        offline = !PosterNetwork.isOnline
                    )
                    categoryCache[category.label] = enriched
                    if (previous.isEmpty()) categoryPages[category.label] = 1
                    _uiState.update { current ->
                        current.copy(
                            categoryCards = if (current.activeCategory?.label == category.label) enriched else current.categoryCards,
                            catalogRows = current.catalogRows + (category.label to enriched),
                            isCategoryLoading = false,
                            categoryFailed = false,
                            categoryPage = if (previous.isEmpty()) 1 else current.categoryPage,
                            categoryHasMore = true
                        )
                    }
                    com.anonrode.downloader.providers.FeedCache.saveCategory(
                        category.label,
                        PosterStore.persistable(enriched)
                    )
                } else {
                    _uiState.update {
                        it.copy(
                            isCategoryLoading = false,
                            categoryFailed = it.activeCategory?.label == category.label && it.categoryCards.isEmpty()
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("category '${category.label}': fetch failed ${e.message}")
                if (_uiState.value.activeCategory?.label == category.label) {
                    _uiState.update {
                        it.copy(isCategoryLoading = false, categoryFailed = it.categoryCards.isEmpty())
                    }
                }
            } finally {
                drainCatalogQueue()
            }
        }
    }

    fun loadMoreCategory() {
        val category = _uiState.value.activeCategory ?: return
        val state = _uiState.value
        if (state.isCategoryLoading || state.isCategoryLoadingMore || !state.categoryHasMore) return
        if (categoryMoreJob?.isActive == true) return

        val nextPage = state.categoryPage + 1
        _uiState.update { it.copy(isCategoryLoadingMore = true) }

        categoryMoreJob = viewModelScope.launch {
            try {
                val newCards = withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.CategoryFeed.fetch(
                        category = category,
                        page = nextPage,
                        filterExplicit = engine.filterExplicitContent
                    )
                }
                val base = categoryCache[category.label] ?: state.categoryCards
                val existingUrls = base.map { it.url }.toSet()
                val existingTitles = base.map { it.title.lowercase().filter(Char::isLetterOrDigit) }.toSet()
                val distinctNew = newCards.filter { card ->
                    card.url.isNotBlank() &&
                    card.url !in existingUrls &&
                    card.title.lowercase().filter(Char::isLetterOrDigit) !in existingTitles
                }
                val updated = PosterStore.enrich(
                    (base + distinctNew).distinctBy { it.url },
                    offline = !PosterNetwork.isOnline
                )
                categoryCache[category.label] = updated
                categoryPages[category.label] = nextPage
                _uiState.update { current ->
                    if (current.activeCategory?.label != category.label) return@update current
                    current.copy(
                        categoryCards = updated,
                        catalogRows = current.catalogRows + (category.label to updated),
                        categoryPage = nextPage,
                        isCategoryLoadingMore = false,
                        categoryHasMore = distinctNew.isNotEmpty()
                    )
                }
                com.anonrode.downloader.providers.FeedCache.saveCategory(
                    category.label,
                    PosterStore.persistable(updated)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("loadMoreCategory '${category.label}': failed ${e.message}")
                _uiState.update { it.copy(isCategoryLoadingMore = false) }
            }
        }
    }

    // ---- Asian Drama Explorer (K-Drama / C-Drama) -------------------------
    private var dramaJob: Job? = null
    private var dramaMoreJob: Job? = null

    fun openAsianDramaHub(
        region: com.anonrode.downloader.providers.DramaRegion,
        era: com.anonrode.downloader.providers.DramaEra = com.anonrode.downloader.providers.DramaEra.MODERN
    ) {
        if (dramaJob?.isActive == true) dramaJob?.cancel()
        if (dramaMoreJob?.isActive == true) dramaMoreJob?.cancel()
        _uiState.update {
            val cachedForRegion = it.dramaCardsByRegion[region].orEmpty()
            it.copy(
                activeDramaRegion = region,
                activeDramaEra = era,
                activeDramaStatus = com.anonrode.downloader.providers.DramaStatusFilter.ALL,
                activeDramaGenre = null,
                // Never show the previous region's cards under the new hub.
                dramaRawCards = emptyList(),
                dramaCards = cachedForRegion,
                isDramaLoading = true,
                dramaFailed = false,
                dramaPage = 1,
                isDramaLoadingMore = false,
                dramaHasMore = true
            )
        }
        loadDramaFeed(region, era, force = false)
    }

    fun selectDramaEra(era: com.anonrode.downloader.providers.DramaEra) {
        val region = _uiState.value.activeDramaRegion ?: return
        if (_uiState.value.activeDramaEra == era && _uiState.value.dramaCards.isNotEmpty()) return
        if (dramaJob?.isActive == true) dramaJob?.cancel()
        if (dramaMoreJob?.isActive == true) dramaMoreJob?.cancel()
        _uiState.update {
            it.copy(
                activeDramaEra = era,
                activeDramaStatus = com.anonrode.downloader.providers.DramaStatusFilter.ALL,
                activeDramaGenre = null,
                isDramaLoading = true,
                dramaFailed = false,
                dramaPage = 1,
                isDramaLoadingMore = false,
                dramaHasMore = true
            )
        }
        loadDramaFeed(region, era, force = false)
    }

    fun selectDramaStatus(status: com.anonrode.downloader.providers.DramaStatusFilter) {
        _uiState.update {
            val region = it.activeDramaRegion ?: com.anonrode.downloader.providers.DramaRegion.KDRAMA
            val era = it.activeDramaEra
            val filtered = it.dramaRawCards.filter { card ->
                com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                    card = card,
                    region = region,
                    era = era,
                    statusFilter = status,
                    genreFilter = it.activeDramaGenre
                )
            }
            val enriched = PosterStore.enrich(filtered, offline = !PosterNetwork.isOnline)
            it.copy(
                activeDramaStatus = status,
                dramaCards = enriched,
                dramaCardsByRegion = it.dramaCardsByRegion + (region to enriched)
            )
        }
    }

    fun selectDramaGenre(genre: com.anonrode.downloader.providers.DramaGenre?) {
        _uiState.update {
            val region = it.activeDramaRegion ?: com.anonrode.downloader.providers.DramaRegion.KDRAMA
            val era = it.activeDramaEra
            val filtered = it.dramaRawCards.filter { card ->
                com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                    card = card,
                    region = region,
                    era = era,
                    statusFilter = it.activeDramaStatus,
                    genreFilter = genre
                )
            }
            val enriched = PosterStore.enrich(filtered, offline = !PosterNetwork.isOnline)
            it.copy(
                activeDramaGenre = genre,
                dramaCards = enriched,
                dramaCardsByRegion = it.dramaCardsByRegion + (region to enriched)
            )
        }
    }

    fun closeAsianDramaHub() {
        if (dramaJob?.isActive == true) dramaJob?.cancel()
        if (dramaMoreJob?.isActive == true) dramaMoreJob?.cancel()
        // Keep dramaCardsByRegion: the AnimatedContent exit still paints the
        // closing page from its own immutable snapshot.
        _uiState.update {
            it.copy(
                activeDramaRegion = null,
                dramaCards = emptyList(),
                dramaRawCards = emptyList(),
                isDramaLoading = false,
                dramaFailed = false,
                dramaPage = 1,
                isDramaLoadingMore = false,
                dramaHasMore = true
            )
        }
    }

    fun refreshDramaFeed() {
        val region = _uiState.value.activeDramaRegion ?: return
        val era = _uiState.value.activeDramaEra
        if (dramaJob?.isActive == true) dramaJob?.cancel()
        if (dramaMoreJob?.isActive == true) dramaMoreJob?.cancel()
        _uiState.update {
            it.copy(
                dramaPage = 1,
                isDramaLoadingMore = false,
                dramaHasMore = true
            )
        }
        loadDramaFeed(region, era, force = true)
    }

    private fun loadDramaFeed(
        region: com.anonrode.downloader.providers.DramaRegion,
        era: com.anonrode.downloader.providers.DramaEra,
        force: Boolean
    ) {
        val key = com.anonrode.downloader.providers.AsianDramaFeed.cacheKeyFor(region, era)
        val rawCached = com.anonrode.downloader.providers.FeedCache.categoryCards(key)
        val cached = PosterStore.enrich(rawCached, offline = !PosterNetwork.isOnline)
        if (cached.isNotEmpty()) {
            val initialFiltered = PosterStore.enrich(
                cached.filter { card ->
                    com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                        card, region, era, _uiState.value.activeDramaStatus, _uiState.value.activeDramaGenre
                    )
                },
                offline = !PosterNetwork.isOnline
            )
            _uiState.update {
                it.copy(
                    dramaRawCards = cached,
                    dramaCards = initialFiltered,
                    dramaCardsByRegion = it.dramaCardsByRegion + (region to initialFiltered),
                    isDramaLoading = !com.anonrode.downloader.providers.FeedCache.isCategoryFresh(key),
                    dramaPage = 1,
                    isDramaLoadingMore = false,
                    dramaHasMore = true
                )
            }
            if (!force && com.anonrode.downloader.providers.FeedCache.isCategoryFresh(key)) {
                return
            }
        }

        dramaJob = viewModelScope.launch {
            try {
                val cards = com.anonrode.downloader.providers.AsianDramaFeed.fetch(
                    region = region,
                    era = era,
                    page = 1,
                    forceRefresh = force,
                    filterExplicit = engine.filterExplicitContent,
                    onPartial = { partial ->
                        if (cached.isEmpty() &&
                            _uiState.value.activeDramaRegion == region &&
                            _uiState.value.activeDramaEra == era
                        ) {
                            val filtered = partial.filter { card ->
                                com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                                    card, region, era, _uiState.value.activeDramaStatus, _uiState.value.activeDramaGenre
                                )
                            }
                            val enrichedRaw = PosterStore.enrich(partial, offline = !PosterNetwork.isOnline)
                            val enriched = PosterStore.enrich(filtered, offline = !PosterNetwork.isOnline)
                            _uiState.update {
                                it.copy(
                                    dramaRawCards = enrichedRaw,
                                    dramaCards = enriched,
                                    dramaCardsByRegion = it.dramaCardsByRegion + (region to enriched),
                                    isDramaLoading = false
                                )
                            }
                        }
                    }
                )
                if (_uiState.value.activeDramaRegion == region && _uiState.value.activeDramaEra == era) {
                    _uiState.update { current ->
                        val base = if (current.dramaRawCards.isEmpty()) cached else current.dramaRawCards
                        val merged = mergeCards(base, cards)
                        val filtered = merged.filter { card ->
                            com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                                card, region, era, current.activeDramaStatus, current.activeDramaGenre
                            )
                        }
                        val enrichedRaw = PosterStore.enrich(merged, offline = !PosterNetwork.isOnline)
                        val enriched = PosterStore.enrich(filtered, offline = !PosterNetwork.isOnline)
                        current.copy(
                            dramaRawCards = enrichedRaw,
                            dramaCards = enriched,
                            dramaCardsByRegion = current.dramaCardsByRegion + (region to enriched),
                            isDramaLoading = false,
                            dramaFailed = enrichedRaw.isEmpty(),
                            dramaPage = if (current.dramaRawCards.isEmpty()) 1 else current.dramaPage,
                            isDramaLoadingMore = false,
                            dramaHasMore = merged.isNotEmpty()
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("dramaFeed: fetch failed ${e.message}")
                _uiState.update {
                    it.copy(
                        isDramaLoading = false,
                        dramaFailed = it.dramaRawCards.isEmpty()
                    )
                }
            }
        }
    }

    fun loadMoreDrama() {
        val region = _uiState.value.activeDramaRegion ?: return
        val era = _uiState.value.activeDramaEra
        val state = _uiState.value
        if (state.isDramaLoading || state.isDramaLoadingMore || !state.dramaHasMore) return
        if (dramaMoreJob?.isActive == true) return

        val nextPage = state.dramaPage + 1
        _uiState.update { it.copy(isDramaLoadingMore = true) }

        dramaMoreJob = viewModelScope.launch {
            try {
                val newCards = withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.AsianDramaFeed.fetch(
                        region = region,
                        era = era,
                        page = nextPage,
                        forceRefresh = false,
                        filterExplicit = engine.filterExplicitContent
                    )
                }

                _uiState.update { current ->
                    if (current.activeDramaRegion != region || current.activeDramaEra != era) return@update current
                    val existingUrls = current.dramaRawCards.map { it.url }.toSet()
                    val existingTitles = current.dramaRawCards.map { it.title.lowercase().filter(Char::isLetterOrDigit) }.toSet()

                    val distinctNew = newCards.filter { card ->
                        card.url.isNotBlank() &&
                        card.url !in existingUrls &&
                        card.title.lowercase().filter(Char::isLetterOrDigit) !in existingTitles
                    }

                    val updatedRaw = PosterStore.enrich(
                        (current.dramaRawCards + distinctNew).distinctBy { it.url },
                        offline = !PosterNetwork.isOnline
                    )
                    val filtered = updatedRaw.filter { card ->
                        com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                            card = card,
                            region = region,
                            era = era,
                            statusFilter = current.activeDramaStatus,
                            genreFilter = current.activeDramaGenre
                        )
                    }
                    val enriched = PosterStore.enrich(filtered, offline = !PosterNetwork.isOnline)

                    current.copy(
                        dramaRawCards = updatedRaw,
                        dramaCards = enriched,
                        dramaCardsByRegion = current.dramaCardsByRegion + (region to enriched),
                        dramaPage = nextPage,
                        isDramaLoadingMore = false,
                        dramaHasMore = distinctNew.isNotEmpty()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("loadMoreDrama $region $era: failed ${e.message}")
                _uiState.update { it.copy(isDramaLoadingMore = false) }
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        // The text field retains active typing (including trailing spaces) so the
        // user can type multi-word queries. We only cap length to MAX for display;
        // whitespace normalization and trimming are applied upon search debounce/submit.
        val capped = newQuery.take(SearchQueryGuard.MAX)
        _uiState.update { it.copy(query = capped) }
        debounceJob?.cancel()
        when (val decision = SearchQueryGuard.decide(newQuery)) {
            is SearchQueryGuard.Decision.Refuse -> {
                // Oversized paste: keep the capped field text, kill in-flight
                // tagged searches and fan out to NOTHING (row 12).
                searchJob?.cancel()
                com.anonrode.downloader.data.net.HttpClient.cancelTagged("search")
                com.anonrode.downloader.data.net.HttpClient.cancelTagged("search-strategy")
                _uiState.update { it.copy(isSearching = false, searchError = null) }
            }
            is SearchQueryGuard.Decision.Debounce -> {
                debounceJob = viewModelScope.launch {
                    delay(SearchQueryGuard.DEBOUNCE_MS)
                    search(decision.query)
                }
            }
            SearchQueryGuard.Decision.Clear -> {
                searchJob?.cancel()
                // Same rule as search(): the dead coroutine stops instantly, but
                // its blocking search HTTP keeps draining until it finishes.
                // Killing the tagged calls keeps a cleared search from trailing.
                com.anonrode.downloader.data.net.HttpClient.cancelTagged("search")
                com.anonrode.downloader.data.net.HttpClient.cancelTagged("search-strategy")
                _uiState.update { it.copy(isSearching = false, searchResults = emptyList(), searchError = null) }
            }
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
                // A YouTube URL that carries ?list= opens the playlist picker
                // INSTEAD of the single-video path — even a watch link inside
                // a playlist (the shape YouTube's own Share button produces).
                // Seal only reacts to playlist-shaped URLs; this is the first
                // edge. Instant-download mode does NOT bypass it: silently
                // queueing N videos from one paste is exactly the surprise
                // the instant mode promises to avoid.
                val playlist = com.anonrode.downloader.pipeline.PlaylistPicker.detect(parsed.cleanUrl)
                if (playlist != null) {
                    com.anonrode.downloader.util.DebugLog.user("routed SocialUrl -> playlist picker ${playlist.listId}")
                    openPlaylist(parsed.cleanUrl)
                } else if (engine.instantSocialDownload) {
                    com.anonrode.downloader.util.DebugLog.user("routed SocialUrl platform=${parsed.platform} instant=true")
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
                    com.anonrode.downloader.util.DebugLog.user("routed SocialUrl platform=${parsed.platform} instant=false")
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
        // Refuse fan-out for an oversized RAW paste (row 12). The state query
        // is capped already, but paste/search-query routing can call search()
        // with an unbounded string directly.
        if (SearchQueryGuard.isOversized(query)) return
        val q = SearchQueryGuard.normalize(query)
        if (q.isBlank()) return
        // Dedupe: the log showed the same query+filter re-fired seconds apart
        // (double keystroke), doubling the whole crawl. Skip when an identical
        // search is already running — its results will land.
        val key = SearchQueryGuard.submissionKey(q, _uiState.value.selectedFilter)
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
        com.anonrode.downloader.data.net.HttpClient.cancelTagged("search-strategy")
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

    fun openEpisodeDrawer(show: ShowCard, preselectRemaining: Boolean = false) {
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
                drawerSynopsis = "",
                isEpisodesLoading = true,
                episodesError = null,
                drawerPreselectRemaining = preselectRemaining
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
                        drawerSynopsis = details.synopsis.trim(),
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
        _uiState.update {
            it.copy(
                activeShowForDrawer = null,
                drawerEpisodes = emptyList(),
                drawerSynopsis = "",
                drawerPreselectRemaining = false
            )
        }
    }

    /** Track 4 ("Download Remaining"): open the drawer for a series the user
     *  is partway through, with the un-downloaded episodes pre-selected and
     *  the already-queued ones marked. seriesUrl/seriesTotal were stamped on
     *  tasks by the drawer at enqueue time; a blank seriesUrl (direct pastes,
     *  tasks from older builds) never reaches here — the chip is only
     *  rendered for stamped tasks. */
    fun openRemainingEpisodes(seriesTitle: String, seriesUrl: String, site: String) {
        if (seriesUrl.isBlank()) return
        openEpisodeDrawer(
            show = ShowCard(
                title = seriesTitle,
                url = seriesUrl,
                site = site,
                category = "Drama"
            ),
            preselectRemaining = true
        )
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
        subLang: String = "en",
        filterExplicit: Boolean = true
    ) {
        val explicitFilterChanged = (filterExplicit != engine.filterExplicitContent)
        if (explicitFilterChanged) {
            if (filterExplicit) {
                _uiState.update { current ->
                    current.copy(
                        trending = com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(current.trending),
                        categoryCards = com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(current.categoryCards),
                        catalogRows = current.catalogRows.mapValues { (_, list) ->
                            com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(list)
                        }
                    )
                }
                categoryCache.keys.toList().forEach { k ->
                    categoryCache[k] = com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(categoryCache[k] ?: emptyList())
                }
            } else {
                categoryCache.clear()
            }
        }
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
            subLang = subLang,
            filterExplicit = filterExplicit
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
