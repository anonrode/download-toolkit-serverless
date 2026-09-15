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
    val catalogOpen: Boolean = false,
    val catalogRows: Map<String, List<ShowCard>> = emptyMap(),
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
        // v3.1.6: paint the LAST session's feeds from disk before anything
        // touches the network (user: "close it and open again, the last
        // stuff will still be there"). The loaders below then refetch
        // silently only when a group is stale (>30 min) or forced.
        val cached = com.anonrode.downloader.providers.FeedCache.snapshot
        if (cached.trending.isNotEmpty() || cached.categories.isNotEmpty()) {
            categoryCache.putAll(cached.categories.mapValues { it.value.cards })
            val tiles = cached.tiles.mapNotNull { t ->
                com.anonrode.downloader.providers.CategoryFeed.CATEGORIES
                    .firstOrNull { it.label == t.label }
                    ?.let { com.anonrode.downloader.providers.CategoryFeed.GenreTile(it, t.posterUrl) }
            }
            _uiState.update {
                it.copy(
                    trending = cached.trending,
                    genreTiles = tiles,
                    catalogRows = cached.categories.mapValues { e -> e.value.cards }
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
                    com.anonrode.downloader.providers.CategoryFeed.tilePosters()
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
    fun loadTrending(force: Boolean = false) {
        if (trendingJob?.isActive == true) return
        if (!force && _uiState.value.trending.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isTrendingFresh()
        ) return
        trendingJob = viewModelScope.launch {
            val showSpinner = _uiState.value.trending.isEmpty()
            if (showSpinner) _uiState.update { it.copy(isTrendingLoading = true, trendingFailed = false) }
            com.anonrode.downloader.util.DebugLog.user("trending: fetch started")
            try {
                val items = withContext(Dispatchers.IO) {
                    // Streaming: each site re-publishes the partial
                    // round-robin merge the moment it lands, so the row fills
                    // while a laggard (9jarocks' RSS) is still crawling.
                    com.anonrode.downloader.providers.TrendingFeed.fetch(
                        onPartial = { partial ->
                            if (partial.isNotEmpty()) {
                                _uiState.update { it.copy(trending = partial) }
                            }
                        }
                    )
                }
                if (items.isNotEmpty()) {
                    _uiState.update {
                        it.copy(trending = items, isTrendingLoading = false, trendingFailed = false)
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

    /** Open a genre's MIXED grid page. Disk/memory cards render instantly
     *  (no blank, no flash); a stale or missing group refetches silently in
     *  the background, streaming its growing mix into the open page. A
     *  still-running load for a DIFFERENT genre is cancelled — its page is
     *  gone, and label-guarded writes already prevent bleed. */
    fun openCategory(category: com.anonrode.downloader.providers.CategoryFeed.Category) {
        if (categoryJob?.isActive == true && _uiState.value.activeCategory?.label != category.label) {
            categoryJob?.cancel()
        }
        val cached = categoryCache[category.label]
            ?.ifEmpty { com.anonrode.downloader.providers.FeedCache.categoryCards(category.label) }
            ?: com.anonrode.downloader.providers.FeedCache.categoryCards(category.label)
        if (cached.isNotEmpty()) categoryCache[category.label] = cached
        _uiState.update {
            it.copy(
                activeCategory = category,
                categoryCards = cached,
                isCategoryLoading = cached.isEmpty(),
                categoryFailed = false
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
        _uiState.update {
            it.copy(
                activeCategory = null,
                categoryCards = emptyList(),
                isCategoryLoading = false,
                categoryFailed = false
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
        val cached = categoryCache[category.label]
            ?: com.anonrode.downloader.providers.FeedCache.categoryCards(category.label)
        if (!force && cached.isNotEmpty() &&
            com.anonrode.downloader.providers.FeedCache.isCategoryFresh(category.label)
        ) {
            _uiState.update {
                it.copy(
                    categoryCards = if (it.activeCategory?.label == category.label) cached else it.categoryCards,
                    catalogRows = it.catalogRows + (category.label to cached),
                    isCategoryLoading = false
                )
            }
            return
        }
        categoryJob = viewModelScope.launch {
            if (cached.isEmpty() && _uiState.value.activeCategory?.label == category.label) {
                _uiState.update { it.copy(isCategoryLoading = true, categoryFailed = false) }
            }
            com.anonrode.downloader.util.DebugLog.user("category '${category.label}': fetch started")
            try {
                val cards = withContext(Dispatchers.IO) {
                    // Streaming mixed partials (same contract as trending):
                    // fast sites paint while a laggard crawls. Label-guarded
                    // like the final write — a partial from a genre the user
                    // already left must never bleed into the open page.
                    com.anonrode.downloader.providers.CategoryFeed.fetch(category) { partial ->
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
                    com.anonrode.downloader.providers.FeedCache.saveCategory(category.label, cards)
                    _uiState.update {
                        it.copy(
                            categoryCards = if (it.activeCategory?.label == category.label) cards else it.categoryCards,
                            catalogRows = it.catalogRows + (category.label to cards),
                            isCategoryLoading = false,
                            categoryFailed = false
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
