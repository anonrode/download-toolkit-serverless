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
    // PL-6: the story blurb every provider already parses (ShowDetails.synopsis)
    // and the UI used to drop. Rendered in the drawer header; empty = hidden.
    val drawerSynopsis: String = "",
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
    val dramaHasMore: Boolean = true
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

    init {
        refreshStorageInfo()
        // v3.1.6: paint the LAST session's feeds from disk before anything
        // touches the network (user: "close it and open again, the last
        // stuff will still be there"). The loaders below then refetch
        // silently only when a group is stale (>30 min) or forced.
        val cached = com.anonrode.downloader.providers.FeedCache.snapshot
        val filterExplicit = engine.filterExplicitContent
        val cachedTrending = if (filterExplicit) com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(cached.trending) else cached.trending
        val cachedCategories = if (filterExplicit) {
            cached.categories.mapValues { e ->
                e.value.copy(cards = com.anonrode.downloader.util.ExplicitContentFilter.filterSafe(e.value.cards))
            }
        } else cached.categories

        if (cachedTrending.isNotEmpty() || cachedCategories.isNotEmpty()) {
            categoryCache.putAll(cachedCategories.mapValues { it.value.cards })
            val tiles = cached.tiles.mapNotNull { t ->
                com.anonrode.downloader.providers.CategoryFeed.CATEGORIES
                    .firstOrNull { it.label == t.label }
                    ?.let { com.anonrode.downloader.providers.CategoryFeed.GenreTile(it, t.posterUrl) }
            }
            _uiState.update {
                it.copy(
                    trending = cachedTrending,
                    genreTiles = tiles,
                    catalogRows = cachedCategories.mapValues { e -> e.value.cards }
                )
            }
        }
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
     *  tiles instead of hiding the section. Disk-fresh (v3.1.6): zero
     *  network — the hydrated tiles from FeedCache already render. */
    private var genreTilesJob: Job? = null
    fun loadGenreTiles(force: Boolean = false) {
        if (genreTilesJob?.isActive == true) return
        if (!force && _uiState.value.genreTiles.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isTilesFresh()
        ) return
        genreTilesJob = viewModelScope.launch {
            try {
                val tiles = withContext(Dispatchers.IO) {
                    com.anonrode.downloader.providers.CategoryFeed.tilePosters(filterExplicit = engine.filterExplicitContent)
                }
                if (tiles.any { it.posterUrl.isNotBlank() }) {
                    _uiState.update { it.copy(genreTiles = tiles) }
                    com.anonrode.downloader.providers.FeedCache.saveTiles(
                        tiles.map { com.anonrode.downloader.providers.TilePoster(it.category.label, it.posterUrl) }
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // No throw-up: empty genreTiles already renders as name-tiles.
            }
        }
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
        if (!force && _uiState.value.trending.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isTrendingFresh()
        ) return
        trendingJob = viewModelScope.launch {
            val showSpinner = _uiState.value.trending.isEmpty()
            if (showSpinner) _uiState.update { it.copy(isTrendingLoading = true, trendingFailed = false, trendingPage = 1, trendingHasMore = true) }
            com.anonrode.downloader.util.DebugLog.user("trending: fetch started")
            try {
                val items = withContext(Dispatchers.IO) {
                    // Streaming: each site re-publishes the partial
                    // round-robin merge the moment it lands, so the row fills
                    // while a laggard (9jarocks' RSS) is still crawling.
                    com.anonrode.downloader.providers.TrendingFeed.fetch(
                        page = 1,
                        filterExplicit = engine.filterExplicitContent,
                        onPartial = { partial ->
                            if (partial.isNotEmpty()) {
                                _uiState.update { it.copy(trending = partial) }
                            }
                        }
                    )
                }
                if (items.isNotEmpty()) {
                    _uiState.update {
                        it.copy(trending = items, isTrendingLoading = false, trendingFailed = false, trendingPage = 1, trendingHasMore = true)
                    }
                    com.anonrode.downloader.providers.FeedCache.saveTrending(items)
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

                    current.copy(
                        trending = current.trending + distinctNew,
                        trendingPage = nextPage,
                        isTrendingLoadingMore = false,
                        trendingHasMore = distinctNew.isNotEmpty()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anonrode.downloader.util.DebugLog.error("loadMoreTrending failed: ${e.message}")
                _uiState.update { it.copy(isTrendingLoadingMore = false) }
            }
        }
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
        val cached = categoryCache[category.label]
            ?: com.anonrode.downloader.providers.FeedCache.categoryCards(category.label)
        if (!force && cached.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isCategoryFresh(category.label)
        ) {
            _uiState.update {
                it.copy(
                    categoryCards = if (it.activeCategory?.label == category.label) cached else it.categoryCards,
                    catalogRows = it.catalogRows + (category.label to cached),
                    isCategoryLoading = false,
                    categoryPage = 1,
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
                    // Streaming mixed partials (same contract as trending):
                    // fast sites paint while a laggard crawls. Label-guarded
                    // like the final write — a partial from a genre the user
                    // already left must never bleed into the open page.
                    com.anonrode.downloader.providers.CategoryFeed.fetch(
                        category,
                        page = 1,
                        filterExplicit = engine.filterExplicitContent
                    ) { partial ->
                        if (partial.isNotEmpty()) {
                            _uiState.update {
                                it.copy(
                                    categoryCards = if (it.activeCategory?.label == category.label) partial else it.categoryCards,
                                    catalogRows = it.catalogRows + (category.label to partial)
                                )
                            }
                        }
                    }
                }
                if (cards.isNotEmpty()) {
                    categoryCache[category.label] = cards
                    categoryPages[category.label] = 1
                    com.anonrode.downloader.providers.FeedCache.saveCategory(category.label, cards)
                    _uiState.update {
                        it.copy(
                            categoryCards = if (it.activeCategory?.label == category.label) cards else it.categoryCards,
                            catalogRows = it.catalogRows + (category.label to cards),
                            isCategoryLoading = false,
                            categoryFailed = false,
                            categoryPage = 1,
                            categoryHasMore = true
                        )
                    }
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
                _uiState.update { current ->
                    if (current.activeCategory?.label != category.label) return@update current
                    val existingUrls = current.categoryCards.map { it.url }.toSet()
                    val existingTitles = current.categoryCards.map { it.title.lowercase().filter(Char::isLetterOrDigit) }.toSet()

                    val distinctNew = newCards.filter { card ->
                        card.url.isNotBlank() &&
                        card.url !in existingUrls &&
                        card.title.lowercase().filter(Char::isLetterOrDigit) !in existingTitles
                    }

                    val updated = current.categoryCards + distinctNew
                    categoryCache[category.label] = updated
                    categoryPages[category.label] = nextPage
                    current.copy(
                        categoryCards = updated,
                        catalogRows = current.catalogRows + (category.label to updated),
                        categoryPage = nextPage,
                        isCategoryLoadingMore = false,
                        categoryHasMore = distinctNew.isNotEmpty()
                    )
                }
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
            it.copy(
                activeDramaRegion = region,
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
            it.copy(activeDramaStatus = status, dramaCards = filtered)
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
            it.copy(activeDramaGenre = genre, dramaCards = filtered)
        }
    }

    fun closeAsianDramaHub() {
        if (dramaJob?.isActive == true) dramaJob?.cancel()
        if (dramaMoreJob?.isActive == true) dramaMoreJob?.cancel()
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
        val cached = com.anonrode.downloader.providers.FeedCache.categoryCards(key)
        if (cached.isNotEmpty()) {
            val initialFiltered = cached.filter { card ->
                com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                    card, region, era, _uiState.value.activeDramaStatus, _uiState.value.activeDramaGenre
                )
            }
            _uiState.update {
                it.copy(
                    dramaRawCards = cached,
                    dramaCards = initialFiltered,
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
                        if (_uiState.value.activeDramaRegion == region && _uiState.value.activeDramaEra == era) {
                            val filtered = partial.filter { card ->
                                com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                                    card, region, era, _uiState.value.activeDramaStatus, _uiState.value.activeDramaGenre
                                )
                            }
                            _uiState.update {
                                it.copy(dramaRawCards = partial, dramaCards = filtered, isDramaLoading = false)
                            }
                        }
                    }
                )
                if (_uiState.value.activeDramaRegion == region && _uiState.value.activeDramaEra == era) {
                    val filtered = cards.filter { card ->
                        com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                            card, region, era, _uiState.value.activeDramaStatus, _uiState.value.activeDramaGenre
                        )
                    }
                    _uiState.update {
                        it.copy(
                            dramaRawCards = cards,
                            dramaCards = filtered,
                            isDramaLoading = false,
                            dramaFailed = cards.isEmpty() && it.dramaRawCards.isEmpty(),
                            dramaPage = 1,
                            isDramaLoadingMore = false,
                            dramaHasMore = cards.isNotEmpty()
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

                    val updatedRaw = current.dramaRawCards + distinctNew
                    val filtered = updatedRaw.filter { card ->
                        com.anonrode.downloader.providers.DramaTagClassifier.matchesFilter(
                            card = card,
                            region = region,
                            era = era,
                            statusFilter = current.activeDramaStatus,
                            genreFilter = current.activeDramaGenre
                        )
                    }

                    current.copy(
                        dramaRawCards = updatedRaw,
                        dramaCards = filtered,
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
        _uiState.update { it.copy(query = newQuery) }
        debounceJob?.cancel()
        val q = newQuery.trim()
        if (q.length >= 2) {
            debounceJob = viewModelScope.launch {
                kotlinx.coroutines.delay(350)
                search(q)
            }
        } else {
            searchJob?.cancel()
            // Same rule as search(): the dead coroutine stops instantly, but
            // its blocking search HTTP keeps draining until it finishes.
            // Killing the tagged calls keeps a cleared search from trailing.
            com.anonrode.downloader.data.net.HttpClient.cancelTagged("search")
            com.anonrode.downloader.data.net.HttpClient.cancelTagged("search-strategy")
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
                drawerSynopsis = "",
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
        _uiState.update { it.copy(activeShowForDrawer = null, drawerEpisodes = emptyList(), drawerSynopsis = "") }
    }

    fun downloadEpisode(episode: EpisodeItem) {
        val show = _uiState.value.activeShowForDrawer ?: return
        com.anonrode.downloader.util.DebugLog.user("enqueue episode: ${show.title} - ${episode.title} url=${episode.url.take(100)}")
        engine.enqueue(
            showTitle = show.title,
            episodeNum = episode.episodeNum,
            episodeTitle = "${show.title} - ${episode.title}",
            sourceUrl = episode.url,
            mirrorUrls = episode.mirrorUrls,
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
                mirrorUrls = ep.mirrorUrls,
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
