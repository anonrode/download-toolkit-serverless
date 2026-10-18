package com.anonrode.downloader.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.R
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.pipeline.VerdictPolicy
import com.anonrode.downloader.providers.CategoryFeed
import com.anonrode.downloader.ui.components.PosterFallbackWell
import com.anonrode.downloader.ui.components.PosterImageOrFallback
import com.anonrode.downloader.ui.components.SearchListSkeleton
import com.anonrode.downloader.ui.components.TrendingRowSkeleton
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.ui.util.TitleFormatting
import com.anonrode.downloader.util.UrlExtractor
import com.anonrode.downloader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UnusedParameter") // onOpenDownloads/onOpenSettings are reserved; both destinations are bottom-nav tabs now.
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSocial: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val filters = listOf(
        "all" to "All Sites",
        "torrents" to "Torrents (TPB)",
        "nkiri" to "NKiri",
        "9jarocks" to "9jaRocks",
        "asianc" to "AsianC",
        "dramakey" to "DramaKey",
        "anitaku" to "Anitaku Anime",
        "pluto" to "Pluto Movies",
        "nepu" to "Nepu HD",
        "naijavault" to "NaijaVault",
        "naijaprey" to "NaijaPrey",
        "dramarain" to "DramaRain"
    )

    var clipboardSnippet by remember { mutableStateOf<String?>(null) }

    // Clipboard auto-detect: register while HomeScreen is in composition so
    // anything copied after the app opens surfaces as a "Paste link:" banner
    // without needing a relaunch. The OS only delivers change events to a
    // foregrounded app — that's the intended behavior, not a bug to work
    // around with polling (background clipboard reads are blocked since
    // Android 10 for non-accessibility apps).
    DisposableEffect(Unit) {
        val detectEnabled = try {
            context.getSharedPreferences("downloader_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("pref_clipboard_detect", true)
        } catch (_: Exception) { true }
        if (!detectEnabled) {
            return@DisposableEffect onDispose { }
        }
        val cm = try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (_: Exception) { null }
        if (cm == null) {
            return@DisposableEffect onDispose { }
        }
        // Seed with the current clipboard so a link already on the clipboard
        // when the screen first composes still shows the banner.
        try {
            val seed = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            clipboardSnippet = if (!seed.isNullOrBlank() && UrlExtractor.isLikelyUrl(seed)) seed else null
        } catch (_: Exception) {}
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = try {
                cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            } catch (_: Exception) { null }
            // Clear on a non-URL so a stale "Paste link:" banner does not
            // linger after the user copies something unrelated (e.g. a
            // password). Compose skips equal state, so identical re-copies
            // don't cause flicker.
            clipboardSnippet = if (!text.isNullOrBlank() && UrlExtractor.isLikelyUrl(text)) text else null
        }
        cm.addPrimaryClipChangedListener(listener)
        onDispose { cm.removePrimaryClipChangedListener(listener) }
    }

    // Fast poster prefetching: prefetch the first 18 trending poster URLs with singleton ImageLoader
    LaunchedEffect(uiState.trending) {
        if (uiState.trending.isNotEmpty()) {
            val urls = uiState.trending.take(18).map { it.posterUrl }.filter { it.isNotBlank() }
            val imageLoader = context.imageLoader
            urls.forEach { url ->
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(coil.size.Size.ORIGINAL)
                    .build()
                imageLoader.enqueue(request)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // statusBarsPadding only: this Column sits inside MainScaffold's
                // content Box, which already clears the bottom navigation bar —
                // navigationBarsPadding() on top of that added a second
                // nav-bar-height dead band above the bottom bar.
                .statusBarsPadding()
                .padding(horizontal = Spacing.lg)
        ) {
            // Header Bar — brand mark + lockup (the same monogram the splash
            // assembles and the launcher carries; one brand everywhere).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xl, bottom = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bare Brand Monogram (user-approved "White No Tile A" / "Light Surface A"):
                // Floating geometric monogram without bounding squircle tile, tinted
                // dynamically with TextPrimary (#FFFFFF in Dark Mode, #07131A in Light Mode).
                Icon(
                    painter = painterResource(R.drawable.ic_anon_monogram),
                    contentDescription = "AnonRode Downloader logo",
                    tint = TextPrimary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ANONRODE",
                        fontSize = Type.brand.fontSize,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        // The one line a visitor reads under the brand — say
                        // what the app does, not what it's built with.
                        text = "Search · download · play",
                        fontSize = Type.caption.fontSize,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // No top-right icon row: Downloads + Settings are bottom-nav
                // tabs, and the live active-task badge lives on the Downloads
                // nav icon itself (MainScaffold). The duplicate header button
                // was redundant, so it was removed.
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Search Bar Component
            var isSearchFocused by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(SurfaceCard)
                    .border(
                        width = 1.dp,
                        color = if (isSearchFocused) AccentPrimary else BorderHairline,
                        shape = CircleShape
                    )
                    .padding(start = Spacing.md, end = Spacing.xs),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = if (isSearchFocused) AccentPrimary else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(Spacing.sm))

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (uiState.query.isEmpty()) {
                            Text(
                                text = "Search series, anime, movies, torrents...",
                                color = TextMuted,
                                fontSize = Type.rowTitle.fontSize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Long-press paste used to forward raw text into the
                        // search query, so a pasted URL would be treated as a
                        // movie title. Route the empty->URL jump through
                        // handlePastedInput instead. The "previous was empty"
                        // guard is what keeps ordinary typing ("titanic") from
                        // being misread as a paste: a paste is a single jump
                        // from blank to a full URL, not a per-keystroke build.
                        androidx.compose.foundation.text.BasicTextField(
                            value = uiState.query,
                            onValueChange = { newValue ->
                                val previous = uiState.query
                                if (previous.isEmpty() && UrlExtractor.isLikelyUrl(newValue)) {
                                    clipboardSnippet = null
                                    viewModel.handlePastedInput(newValue) { platform, url ->
                                        onOpenSocial(platform, url)
                                    }
                                    viewModel.onQueryChanged("")
                                } else {
                                    viewModel.onQueryChanged(newValue)
                                }
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = TextPrimary,
                                fontSize = Type.rowTitle.fontSize,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentPrimary),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                    viewModel.search()
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isSearchFocused = it.isFocused }
                        )
                    }

                    if (uiState.query.isNotBlank()) {
                        IconButton(
                            onClick = { viewModel.onQueryChanged("") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(Spacing.xs))

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    keyboardController?.hide()
                                    viewModel.search()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AccentPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowForward,
                                    contentDescription = "Search",
                                    tint = BackgroundDark,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        val clipManager = LocalClipboardManager.current
                        IconButton(
                            onClick = {
                                val clip = clipManager.getText()?.text?.trim()
                                if (!clip.isNullOrBlank()) {
                                    clipboardSnippet = null
                                    viewModel.handlePastedInput(clip) { platform, url ->
                                        onOpenSocial(platform, url)
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = "Paste Link",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Smart Clipboard Banner
            val snippetText = clipboardSnippet
            if (!snippetText.isNullOrBlank() && uiState.query.isBlank()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(SurfaceElevated)
                        .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                        .clickable {
                            clipboardSnippet = null
                            viewModel.handlePastedInput(snippetText) { platform, url ->
                                onOpenSocial(platform, url)
                            }
                        }
                        .heightIn(min = 48.dp) // 16dp icon + 8+8dp padding alone was a ~36dp target
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = run {
                                val head = if (snippetText.length > 35) snippetText.take(35) + "..." else snippetText
                                "Paste link: $head"
                            },
                            color = TextPrimary,
                            fontSize = Type.label.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text("Download", color = AccentPrimary, fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Filter Chips Carousel — 8dp gaps (v3.1.6 design pass: 4dp
            // between tap targets is below the mis-touch comfort line).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                filters.forEach { (key, label) ->
                    val isSelected = uiState.selectedFilter == key
                    val isDark = AnonTheme.colors.isDark
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onFilterSelected(key) },
                        label = {
                            Text(
                                text = label,
                                fontSize = Type.label.fontSize,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) {
                                    if (isDark) Color(0xFF090B10) else Color.White
                                } else {
                                    if (isDark) Color(0xFFD0D7E2) else TextSecondary
                                }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (isDark) Color(0xFFF3F5F9) else AccentPrimary,
                            containerColor = SurfaceCard
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else BorderHairline,
                            selectedBorderColor = if (isDark) Color(0xFFF3F5F9) else AccentPrimary
                        ),
                        shape = RoundedCornerShape(Radius.full)
                    )
                }
                // §5.5: end runway = 16dp so the last chip ("AsianC", "NaijaVault")
                // never sits clipped against the screen edge when scrolled.
                Spacer(modifier = Modifier.width(Spacing.lg))
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Live Search Results — rendered through the oracle's reveal
            // policy (2026-09-14): proven-dead cards never appear, LIVE
            // cards float to the top wearing their ✓ caption, everything
            // else holds its place exactly as before. Absence of a verdict
            // is never a hide: unverified ≠ unverifiable-looking.
            val visibleResults = remember(uiState.searchResults, uiState.verdicts) {
                VerdictPolicy.visibleOrdered(
                    uiState.searchResults, uiState.verdicts, System.currentTimeMillis()
                )
                    // Key uniqueness before item animation (UI research round):
                    // two providers can surface the SAME url for one title, and
                    // Modifier.animateItem is keyed — a duplicate key is a
                    // LazyColumn crash, not a cosmetic issue (the drawer hit
                    // exactly this class and documents it). First occurrence
                    // wins, so rank order is untouched.
                    .distinctBy { it.url }
            }
            if (uiState.isSearching && visibleResults.isEmpty()) {
                // Skeleton rows + honest progress copy (UI research round):
                // searches can exceed 1s across the source fan-out, so the
                // list's real shape stands in for a spinner.
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        text = "Searching all sources…",
                        color = TextSecondary,
                        fontSize = Type.body.fontSize
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SearchListSkeleton()
                }
            } else if (uiState.searchError != null && visibleResults.isEmpty()) {
                // A failed search must be distinguishable from an empty one:
                // "found nothing" and "couldn't search" are different situations.
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = uiState.searchError ?: "Search failed",
                            color = StatusError,
                            fontSize = Type.rowTitle.fontSize,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text("Check your connection and try again", color = TextMuted, fontSize = Type.label.fontSize)
                        // The hint said "try again" but the only way to try
                        // again was perturbing the query — mirror the
                        // trending panel and offer the retry as a button.
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        TextButton(onClick = { viewModel.search() }) {
                            Text("Retry", color = AccentPrimary, fontWeight = FontWeight.Bold, fontSize = Type.body.fontSize)
                        }
                    }
                }
            } else if (visibleResults.isEmpty() && uiState.query.trim().length >= 2 && !uiState.isSearching) {
                // Note: keyed off visibleResults, not the raw crawl — when
                // every returned card was proven dead, the honest message is
                // "No matches", not a blank landing page under a live query.
                // UI research round: a real empty state (icon + title + the
                // explanation + one action) instead of a lone grey sentence.
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        text = "No matches for \u201C${uiState.query.trim()}\u201D",
                        color = TextPrimary,
                        fontSize = Type.sectionTitle.fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Every source answered \u2014 try a shorter title or check the spelling.",
                        color = TextMuted,
                        fontSize = Type.label.fontSize
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    TextButton(onClick = { viewModel.onQueryChanged("") }) {
                        Text("Clear search", color = AccentPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (visibleResults.isEmpty()) {
                // Blank-query state: the trending row IS the landing content
                // (left/right — its established gesture). The genre tiles
                // below it WRAP three-per-row and drop down (v3.1.6, user:
                // "browse my genre shouldnt be scrollable left and right, it
                // should drop down… 3 per row"), ending with the View More
                // tile that opens the catalog. Landing state only, never
                // over results.
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                ) {
                    TrendingSection(
                        items = uiState.trending,
                        isLoading = uiState.isTrendingLoading,
                        failed = uiState.trendingFailed,
                        showPosters = viewModel.engine.showPostersInResults,
                        isLoadingMore = uiState.isTrendingLoadingMore,
                        hasMore = uiState.trendingHasMore,
                        onLoadMore = { viewModel.loadMoreTrending() },
                        onRefresh = { viewModel.loadTrending(force = true) },
                        onRetry = { viewModel.loadTrending(force = true) },
                        onOpen = { viewModel.openEpisodeDrawer(it) }
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    AsianDramaHeroSection(
                        onOpenKdrama = { viewModel.openAsianDramaHub(com.anonrode.downloader.providers.DramaRegion.KDRAMA) },
                        onOpenCdrama = { viewModel.openAsianDramaHub(com.anonrode.downloader.providers.DramaRegion.CDRAMA) }
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    CategoryTilesGrid(
                        onOpen = { category -> viewModel.openCategory(category) },
                        onMore = { viewModel.openCatalog() }
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(visibleResults, key = { it.url }) { show ->
                        ShowCardItem(
                            show = show,
                            showPosters = viewModel.engine.showPostersInResults,
                            verifiedCaption = VerdictPolicy.captionFor(
                                uiState.verdicts[VerdictPolicy.keyFor(show.url)]
                            ),
                            onClick = { viewModel.openEpisodeDrawer(show) },
                            // Cards gliding in as sources answer (UI research
                            // round): fade in on the effects spring, glide to
                            // a new slot on the spatial spring when a verdict
                            // re-ranks the list. Items already on screen are
                            // untouched — animateItem state is per key, so
                            // partial updates never re-animate anything.
                            modifier = Modifier.animateItem(
                                fadeInSpec = spring(
                                    Motion.EffectsDamping, Motion.EffectsStiffnessDefault
                                ),
                                placementSpec = spring(
                                    Motion.SpatialDamping, Motion.SpatialStiffnessDefault
                                ),
                                fadeOutSpec = tween(Motion.DurationFast)
                            )
                        )
                    }
                }
            }
        }

        // Genre grid page (ONE mixed list) and the View More catalog (one
        // mixed row per genre): full-page overlays in THIS window's root Box
        // — the b5cdf99 lesson says page content never rides a Dialog window.
        // Composed BEFORE the drawer block so tapping a card stacks the
        // EpisodeDrawer on top. The catalog hides while a genre page is open
        // and reappears on its back gesture (grid → catalog → home).
        uiState.activeCategory?.let { category ->
            CategoryPage(
                category = category,
                cards = uiState.categoryCards,
                isLoading = uiState.isCategoryLoading,
                failed = uiState.categoryFailed,
                showPosters = viewModel.engine.showPostersInResults,
                isLoadingMore = uiState.isCategoryLoadingMore,
                hasMore = uiState.categoryHasMore,
                onLoadMore = { viewModel.loadMoreCategory() },
                onBack = { viewModel.closeCategory() },
                onRefresh = { viewModel.loadCategory(category, force = true) },
                onOpen = { viewModel.openEpisodeDrawer(it) }
            )
        }
        uiState.activeDramaRegion?.let { region ->
            com.anonrode.downloader.ui.components.AsianDramaExplorer(
                region = region,
                era = uiState.activeDramaEra,
                statusFilter = uiState.activeDramaStatus,
                activeGenre = uiState.activeDramaGenre,
                cards = uiState.dramaCards,
                isLoading = uiState.isDramaLoading,
                failed = uiState.dramaFailed,
                showPosters = viewModel.engine.showPostersInResults,
                isLoadingMore = uiState.isDramaLoadingMore,
                hasMore = uiState.dramaHasMore,
                onLoadMore = { viewModel.loadMoreDrama() },
                onBack = { viewModel.closeAsianDramaHub() },
                onSelectEra = { viewModel.selectDramaEra(it) },
                onSelectStatus = { viewModel.selectDramaStatus(it) },
                onSelectGenre = { viewModel.selectDramaGenre(it) },
                onRefresh = { viewModel.refreshDramaFeed() },
                onOpen = { viewModel.openEpisodeDrawer(it) }
            )
        }
        if (uiState.catalogOpen && uiState.activeCategory == null && uiState.activeDramaRegion == null) {
            CatalogPage(
                catalogRows = uiState.catalogRows,
                showPosters = viewModel.engine.showPostersInResults,
                onBack = { viewModel.closeCatalog() },
                onOpenGenre = { viewModel.openCategory(it) },
                onEnsureGenre = { viewModel.ensureCategoryCards(it) },
                onOpen = { viewModel.openEpisodeDrawer(it) }
            )
        }

        // Episode Drawer Modal
        uiState.activeShowForDrawer?.let { currentShow ->
            EpisodeDrawer(
                show = currentShow,
                viewModel = viewModel,
                onDismiss = { viewModel.closeEpisodeDrawer() }
            )
        }

        // (The torrent file-picker host moved to the MainActivity root: a
        // magnet that starts while DownloadsScreen or the share sheet is on
        // stage used to find no dialog composed, stall 60s, and fall back to
        // the WHOLE torrent.)
    }
}

/**
 * Trending-on-open section (feature request): horizontally scrolling poster
 * row — LEFT TO RIGHT, not a vertical list, per the user's explicit wording.
 * Occupies the blank-query landing area; hides the moment a search starts.
 */
/** Reserve-space height for ONE trending-card row (poster 124dp @ 2:3 =
 *  186 + 4 gap + two 17lh title lines + 2 + a 10sp site line ≈ 240). Used
 *  by BOTH the TrendingSection spinner/empty boxes and the catalog's
 *  per-row placeholder (v3.1.6 design pass: placeholders were 210dp/176dp
 *  against a ~229dp real row — content jumped ~50dp when data landed). */
private val TRENDING_ROW_H = 240.dp

/** One cinematic bottom vignette on every poster face (trending card,
 *  genre tile, grid cell) — depth for flat art rows and a consistent
 *  language across all three. Poster-only, so it is theme-independent. */
@Composable
private fun BoxScope.PosterScrim() {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.45f)
                )
            )
    )
}

@Composable
private fun TrendingSection(
    modifier: Modifier = Modifier,
    items: List<ShowCard>,
    isLoading: Boolean,
    failed: Boolean,
    showPosters: Boolean,
    isLoadingMore: Boolean = false,
    hasMore: Boolean = true,
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (ShowCard) -> Unit
) {
    val rowState = rememberLazyListState()

    val shouldLoadMore by remember(items.size, isLoading, isLoadingMore, hasMore) {
        derivedStateOf {
            if (!hasMore || isLoading || isLoadingMore || items.isEmpty()) return@derivedStateOf false
            val layoutInfo = rowState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.LocalFireDepartment,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = "Trending Now",
                color = TextPrimary,
                fontSize = Type.sectionTitle.fontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (items.isNotEmpty() && !isLoading) {
                // v3.1.6: force-refresh is a first-class affordance, not a
                // failure consolation — the cache means a painted row can
                // always be pushed to crawl again with one tap. Design pass:
                // no explicit size, so IconButton keeps its 48dp touch box
                // (36dp was under the line) around the small 18dp glyph.
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh trending",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (failed && items.isEmpty() && !isLoading) {
                Text(
                    text = "Retry",
                    color = AccentPrimary,
                    fontSize = Type.body.fontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(Radius.full))
                        .clickable { onRetry() }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        when {
            isLoading && items.isEmpty() -> {
                // Skeleton, not a spinner (UI research round): the row's real
                // shape is on screen while the sources crawl, and the box
                // keeps TRENDING_ROW_H so nothing shifts when cards land.
                Box(modifier = Modifier.fillMaxWidth().height(TRENDING_ROW_H)) {
                    TrendingRowSkeleton()
                }
            }
            items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(TRENDING_ROW_H),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Trending is unavailable right now", color = TextSecondary, fontSize = Type.rowTitle.fontSize)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text("Tap Retry to load it again", color = TextMuted, fontSize = Type.label.fontSize)
                    }
                }
            }
            else -> {
                // Horizontal carousel: LazyRow scrolls left↔right by design.
                LazyRow(
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    contentPadding = PaddingValues(end = Spacing.lg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items, key = { it.site + "|" + it.url }) { show ->
                        TrendingCard(
                            show = show,
                            showPosters = showPosters,
                            onClick = { onOpen(show) },
                            // New cards fade in as sites answer; nothing
                            // already on screen re-animates (per-key state).
                            modifier = Modifier.animateItem(
                                fadeInSpec = spring(
                                    Motion.EffectsDamping, Motion.EffectsStiffnessDefault
                                ),
                                placementSpec = spring(
                                    Motion.SpatialDamping, Motion.SpatialStiffnessDefault
                                ),
                                fadeOutSpec = tween(Motion.DurationFast)
                            )
                        )
                    }
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(TRENDING_ROW_H),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = AccentPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
                // (§4.3: the "Tap a title to pick episodes · or search above"
                //  helper line under Trending Now is deleted outright.)
            }
        }
    }
}

@Composable
private fun TrendingCard(
    show: ShowCard,
    showPosters: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // §4.2 title sanitizer: raw scraper suffixes ("| Download …"), (YYYY)
    // tokens and Season markers are stripped from the face title; (YYYY) and
    // "Season X" move into the muted subtitle so grid rows align.
    val faceTitle = remember(show.title) { TitleFormatting.cleanPosterTitle(show.title) }
    val seasonPart = remember(show.title) {
        val (_, sub) = TitleFormatting.splitYearAndSeason(show.title)
        sub?.split(" · ")?.firstOrNull { it.startsWith("Season", ignoreCase = true) }
    }
    val yearPart = show.year.trim()
    val subtitleLine = remember(seasonPart, yearPart) {
        listOfNotNull(yearPart.ifBlank { null }, seasonPart).joinToString(" · ")
    }
    Column(
        modifier = modifier
            .width(124.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Poster geometry unified to 2:3 across every card dialect.
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .background(tileColor(faceTitle))
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
        ) {
            if (showPosters) {
                PosterImageOrFallback(
                    posterUrl = show.posterUrl,
                    showTitle = faceTitle,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(Radius.md),
                    fallbackIcon = Icons.Rounded.PlayArrow,
                    showFallbackTitle = true
                )
            } else {
                PosterFallbackWell(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(Radius.md),
                    icon = Icons.Rounded.PlayArrow,
                    title = faceTitle
                )
            }
            PosterScrim()
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        // §4.2: fixed 2-line title — every card in a grid row aligns.
        Text(
            text = faceTitle,
            color = TextPrimary,
            fontSize = Type.body.fontSize,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitleLine.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitleLine,
                color = TextSecondary,
                fontSize = Type.micro.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Genre tiles under the trending row (blank-query landing only), §4.3:
 * a WRAPPING 3-per-row grid of sleek 2:1 horizontal-gradient category cards
 * with bold typography and a per-category icon. Posters are gone from this
 * surface — eliminating the broken "A" poster and the duplicate artwork the
 * View More tile used to inherit. The tiles NAVIGATE — they are not filters.
 */
private val GENRE_POSTER_PREVIEWS: Map<String, Pair<String, String>> = mapOf(
    "Action" to Pair(
        "https://image.tmdb.org/t/p/w300/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
        "https://image.tmdb.org/t/p/w300/mKOBErGAKvQAh95Nq1t71v9gK6z.jpg"
    ),
    "Romance" to Pair(
        "https://image.tmdb.org/t/p/w300/2vP9kivc871s9s88qXzP8xJ87q0.jpg",
        "https://image.tmdb.org/t/p/w300/o70rO8q83uD1e5Gz4b9z1s9q3r5.jpg"
    ),
    "Comedy" to Pair(
        "https://image.tmdb.org/t/p/w300/gKkl37BQuKTanygYQG1pyYgLVgf.jpg",
        "https://image.tmdb.org/t/p/w300/qJ2tW6WMUDux911r6m7haRef0WH.jpg"
    ),
    "Horror" to Pair(
        "https://image.tmdb.org/t/p/w300/bShgiqB0Qy1QZc7d3d7r0.jpg",
        "https://image.tmdb.org/t/p/w300/5qHoazZiaKoJnmeebOnjhq8Z4.jpg"
    ),
    "Sci-Fi" to Pair(
        "https://image.tmdb.org/t/p/w300/6KErczPBROQty7QoIsxWZaEAY8j.jpg",
        "https://image.tmdb.org/t/p/w300/gEU2QniE6EwfNDRA5reOqwqq9JS.jpg"
    ),
    "Thriller" to Pair(
        "https://image.tmdb.org/t/p/w300/d5NXSklXo0qyIYkgV94XAgMIckC.jpg",
        "https://image.tmdb.org/t/p/w300/vQWk5YBFWF4bZaofAbv0tShwBvQ.jpg"
    ),
    "Anime" to Pair(
        "https://image.tmdb.org/t/p/w300/9k9h9cq04P3s1G0a2x8Z4.jpg",
        "https://image.tmdb.org/t/p/w300/hE24GYddaxB9MVZl1CaiI86M3kp.jpg"
    )
)

/**
 * Browse by Genre grid: compact 2-column cards (H=80dp, R=14dp) with 2-poster
 * mini-stacks on the left and bold typography.
 */
@Composable
private fun CategoryTilesGrid(
    onOpen: (CategoryFeed.Category) -> Unit,
    onMore: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Browse by Genre",
            color = TextPrimary,
            fontSize = Type.sectionTitle.fontSize,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        val allCategories = CategoryFeed.CATEGORIES
        val items = allCategories.map { it to false } + listOf(CategoryFeed.Category(label = "All Genres") to true)
        val rows = items.chunked(2)

        rows.forEach { rowPairs ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowPairs.forEach { (cat, isMore) ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (isMore) {
                            ViewMoreTileCard(onClick = onMore)
                        } else {
                            GenreTileCard(
                                category = cat,
                                onClick = { onOpen(cat) }
                            )
                        }
                    }
                }
                if (rowPairs.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 2-column View More tile: H=80dp, R=14dp with explore compass icon. */
@Composable
private fun ViewMoreTileCard(onClick: () -> Unit) {
    val isDark = AnonTheme.colors.isDark
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isDark) Color(0xFF141822)
                else Color(0xFFFFFFFF)
            )
            .border(
                width = 1.dp,
                color = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0x140F172A),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier.size(width = 56.dp, height = 62.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AccentPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Explore,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ALL GENRES",
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Browse catalog ▾",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** 2-column genre card: H=80dp, R=14dp with 2-poster mini-stack (56×62dp) on the left. */
@Composable
private fun GenreTileCard(
    category: CategoryFeed.Category,
    onClick: () -> Unit
) {
    val isDark = AnonTheme.colors.isDark
    val posters = GENRE_POSTER_PREVIEWS[category.label]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isDark) Color(0xFF141822)
                else Color(0xFFFFFFFF)
            )
            .border(
                width = 1.dp,
                color = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0x140F172A),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 2-poster mini-stack container (56×62 dp)
            Box(
                modifier = Modifier.size(width = 56.dp, height = 62.dp)
            ) {
                // Back poster (40×54 dp, rotated 6 deg, alpha 0.70)
                Box(
                    modifier = Modifier
                        .offset(x = 12.dp, y = 4.dp)
                        .size(width = 40.dp, height = 54.dp)
                        .graphicsLayer { rotationZ = 6f }
                        .alpha(0.70f)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    val backUrl = posters?.second.orEmpty()
                    if (backUrl.isNotBlank()) {
                        AsyncImage(
                            model = backUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        PosterFallbackWell(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(6.dp),
                            icon = genreIconFor(category.label),
                            iconSize = 16.dp
                        )
                    }
                }

                // Front poster (44×60 dp, 1dp border, corner radius 6dp)
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            1.dp,
                            if (isDark) Color.White.copy(alpha = 0.12f) else Color(0x140F172A),
                            RoundedCornerShape(6.dp)
                        )
                ) {
                    val frontUrl = posters?.first.orEmpty()
                    if (frontUrl.isNotBlank()) {
                        AsyncImage(
                            model = frontUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        PosterFallbackWell(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(6.dp),
                            icon = genreIconFor(category.label),
                            iconSize = 18.dp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.label.uppercase(),
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Explore ▾",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** §4.3 category icon mapping (keyword → rounded icon; Movie fallback). */
private fun genreIconFor(label: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    label.contains("Action", true) -> Icons.Rounded.Bolt
    label.contains("Comedy", true) -> Icons.Rounded.SentimentVerySatisfied
    label.contains("Horror", true) -> Icons.Rounded.DarkMode
    label.contains("Romance", true) -> Icons.Rounded.Favorite
    label.contains("Sci", true) -> Icons.Rounded.RocketLaunch
    label.contains("Thriller", true) -> Icons.Rounded.Visibility
    label.contains("Anime", true) -> Icons.Rounded.AutoAwesome
    label.contains("Drama", true) -> Icons.Rounded.TheaterComedy
    else -> Icons.Rounded.Movie
}

/**
 * Full-page genre view (v3.1.6 redesign): ONE mixed grid — every candidate
 * site's confirmed cards interleaved round-robin, three posters per row,
 * scroll down only. No site names anywhere on the page (the drawer still
 * shows provenance after a tap). Cards use the trending visual language at
 * the grid cell's own width. Root-level overlay in the activity's own
 * window — never a Dialog window.
 */
@Composable
private fun CategoryPage(
    category: CategoryFeed.Category,
    cards: List<ShowCard>,
    isLoading: Boolean,
    failed: Boolean,
    showPosters: Boolean,
    isLoadingMore: Boolean = false,
    hasMore: Boolean = true,
    onLoadMore: () -> Unit = {},
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (ShowCard) -> Unit
) {
    BackHandler(onBack = onBack)

    val gridState = rememberLazyGridState()

    val shouldLoadMore by remember(cards.size, isLoading, isLoadingMore, hasMore) {
        derivedStateOf {
            if (!hasMore || isLoading || isLoadingMore || cards.isEmpty()) return@derivedStateOf false
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 6
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .background(SurfaceElevated, CircleShape)
                    .border(1.dp, BorderHairline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.label.uppercase(),
                    fontSize = Type.screenTitle.fontSize,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (cards.isNotEmpty())
                        "${cards.size} titles, every source mixed"
                    else
                        "Latest ${category.label.lowercase()} posts",
                    fontSize = Type.caption.fontSize,
                    color = TextMuted
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .background(SurfaceElevated, CircleShape)
                    .border(1.dp, BorderHairline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        when {
            isLoading && cards.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text("Loading ${category.label.lowercase()}...", color = TextSecondary, fontSize = Type.body.fontSize)
                }
            }
            cards.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${category.label} is unavailable right now", color = TextSecondary, fontSize = Type.rowTitle.fontSize)
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = if (failed) "All sources timed out" else "No confirmed ${category.label.lowercase()} posts found",
                        color = TextMuted,
                        fontSize = Type.label.fontSize
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    TextButton(onClick = onRefresh) {
                        Text("Retry", color = AccentPrimary, fontWeight = FontWeight.Bold, fontSize = Type.body.fontSize)
                    }
                }
            }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                gridItems(cards, key = { it.url }) { show ->
                    GridPosterCard(
                        show = show,
                        showPosters = showPosters,
                        onClick = { onOpen(show) },
                        // The grid streams in per-site partials — cards fade
                        // in as they arrive, painted ones never move.
                        modifier = Modifier.animateItem(
                            fadeInSpec = spring(
                                Motion.EffectsDamping, Motion.EffectsStiffnessDefault
                            ),
                            placementSpec = spring(
                                Motion.SpatialDamping, Motion.SpatialStiffnessDefault
                            ),
                            fadeOutSpec = tween(Motion.DurationFast)
                        )
                    )
                }
                if (isLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.md),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = AccentPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Grid cell of the genre page: the trending card's language (§4.2 sanitized
 *  title + (YYYY)·Season subtitle, gradient-fallback poster, 2-line fixed
 *  title) sized to the cell — three across on every phone, never a fixed dp
 *  width that overflows a small one. */
@Composable
private fun GridPosterCard(
    show: ShowCard,
    showPosters: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val faceTitle = remember(show.title) { TitleFormatting.cleanPosterTitle(show.title) }
    val seasonPart = remember(show.title) {
        val (_, sub) = TitleFormatting.splitYearAndSeason(show.title)
        sub?.split(" · ")?.firstOrNull { it.startsWith("Season", ignoreCase = true) }
    }
    val yearPart = show.year.trim()
    val subtitleLine = remember(seasonPart, yearPart) {
        listOfNotNull(yearPart.ifBlank { null }, seasonPart).joinToString(" · ")
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .background(tileColor(faceTitle))
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
        ) {
            if (showPosters) {
                PosterImageOrFallback(
                    posterUrl = show.posterUrl,
                    showTitle = faceTitle,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(Radius.md),
                    fallbackIcon = Icons.Rounded.PlayArrow,
                    showFallbackTitle = true
                )
            } else {
                PosterFallbackWell(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(Radius.md),
                    icon = Icons.Rounded.PlayArrow,
                    title = faceTitle
                )
            }
            PosterScrim()
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        // §4.2: fixed 2-line title so every cell in a row aligns.
        Text(
            text = faceTitle,
            color = TextPrimary,
            fontSize = Type.caption.fontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitleLine.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitleLine,
                color = TextSecondary,
                fontSize = Type.micro.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The "View More" catalog (v3.1.6): the old page shape, transposed — each
 * horizontal row is a GENRE (headers name genres, never sites), cards mixed
 * across sources. Rows lazy-load as they scroll into view ([onEnsureGenre])
 * so opening this page never crawls six genres at once; a tapped header
 * opens that genre's full mixed grid.
 */
@Composable
private fun CatalogPage(
    catalogRows: Map<String, List<ShowCard>>,
    showPosters: Boolean,
    onBack: () -> Unit,
    onOpenGenre: (CategoryFeed.Category) -> Unit,
    onEnsureGenre: (CategoryFeed.Category) -> Unit,
    onOpen: (ShowCard) -> Unit
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .background(SurfaceElevated, CircleShape)
                    .border(1.dp, BorderHairline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Text(
                text = "ALL GENRES",
                fontSize = Type.screenTitle.fontSize,
                fontWeight = FontWeight.Black,
                color = TextPrimary,
                letterSpacing = 1.sp
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            contentPadding = PaddingValues(bottom = Spacing.xl)
        ) {
            items(CategoryFeed.CATEGORIES, key = { it.label }) { category ->
                LaunchedEffect(category.label) { onEnsureGenre(category) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .clickable { onOpenGenre(category) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = category.label.uppercase(),
                            color = TextPrimary,
                            fontSize = Type.rowTitle.fontSize,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "SEE ALL",
                            color = AccentPrimary,
                            fontSize = Type.micro.fontSize,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    val cards = catalogRows[category.label] ?: emptyList()
                    if (cards.isEmpty()) {
                        // Same skeleton as the trending row (UI research round):
                        // the row's real geometry stands in for the spinner, and
                        // the fixed box keeps the page from jumping when a
                        // genre's cards land.
                        Box(modifier = Modifier.fillMaxWidth().height(TRENDING_ROW_H)) {
                            TrendingRowSkeleton()
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            contentPadding = PaddingValues(horizontal = Spacing.lg),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(cards.take(12), key = { it.url }) { show ->
                                TrendingCard(
                                    show = show,
                                    showPosters = showPosters,
                                    onClick = { onOpen(show) },
                                    // Rows fill as their genre loads — cards
                                    // glide in rather than snapping.
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = spring(
                                            Motion.EffectsDamping, Motion.EffectsStiffnessDefault
                                        ),
                                        placementSpec = spring(
                                            Motion.SpatialDamping, Motion.SpatialStiffnessDefault
                                        ),
                                        fadeOutSpec = tween(Motion.DurationFast)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShowCardItem(
    show: ShowCard,
    showPosters: Boolean,
    onClick: () -> Unit,
    /** Oracle caption for LIVE-proven cards ("✓ 220 MB", "✓ 24 eps · 450 MB/ep").
     *  Null for every other state — the card renders exactly as it did before
     *  the oracle existed (badge the winners, never the losers). */
    verifiedCaption: String? = null,
    /** Caller-owned modifier (list item animation rides here). */
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.lg))
            .clickable { onClick() }
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // §4.2: the search row's face title is sanitized too.
        val faceTitle = remember(show.title) { TitleFormatting.cleanPosterTitle(show.title) }
        PosterTile(show = show, showPosters = showPosters, faceTitle = faceTitle)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = faceTitle,
                color = TextPrimary,
                fontSize = Type.itemTitle.fontSize,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = specLine(show),
                color = TextSecondary,
                fontSize = Type.body.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // A real oracle caption REPLACES the hardcoded "✓ 1080p"
                // guess — the verified bytes are strictly more honest. With
                // no verdict, the row is unchanged from before the oracle.
                // (v3.1.6 design pass: the caption is the only LONG badge —
                // "✓ 24 eps · 450 MB/ep" beside a 10-char site name measured
                // past the 218dp text column on a 360dp phone. As the row's
                // only shrinkable child it now ellipsizes instead of being
                // clipped off the card edge — the leftMetric pattern above.)
                if (verifiedCaption != null) {
                    CardBadge(
                        verifiedCaption,
                        accent = false,
                        verified = true,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else {
                    secondaryBadge(show)?.let { CardBadge(it, accent = false) }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = leftMetric(show),
                    color = TextSecondary,
                    fontSize = Type.body.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(StatusSuccess)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = rightMetric(show),
                        color = StatusSuccess,
                        fontSize = Type.body.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterTile(show: ShowCard, showPosters: Boolean, faceTitle: String) {
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 106.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(tileColor(faceTitle))
    ) {
        if (showPosters) {
            PosterImageOrFallback(
                posterUrl = show.posterUrl,
                showTitle = faceTitle,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(Radius.md),
                fallbackIcon = Icons.Rounded.PlayArrow,
                showFallbackTitle = false
            )
        } else {
            PosterFallbackWell(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(Radius.md),
                icon = Icons.Rounded.PlayArrow,
                title = null
            )
        }
    }
}

// ─── (Removed §4.1: InitialGlyph single-letter fallbacks ("A", "P", "M") —
// every poster surface now falls back to the stylized gradient well with the
// media icon + centered title, via PosterImageOrFallback/PosterFallbackWell.)

@Composable
private fun CardBadge(
    text: String,
    accent: Boolean,
    verified: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(SurfaceElevated)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.sm))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    ) {
        Text(
            text = text,
            color = when {
                verified -> StatusSuccess
                accent -> AccentPrimary
                else -> TextSecondary
            },
            fontSize = Type.caption.fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Poster fallback palette, per theme (UI research round): the old dark-only
// set made six near-black bricks on the light theme's white page. Same
// hue-family order, so a title keeps its "colour" across themes.
private val TILE_COLORS_DARK = listOf(
    Color(0xFF3A1C1C), Color(0xFF2A2A10), Color(0xFF20303A),
    Color(0xFF241A33), Color(0xFF14301F), Color(0xFF33231A)
)
private val TILE_COLORS_LIGHT = listOf(
    Color(0xFFF3E3E3), Color(0xFFF1EFDC), Color(0xFFDDE9F0),
    Color(0xFFE7E0F2), Color(0xFFDDEBE1), Color(0xFFF2E5DC)
)

@Composable
private fun tileColor(title: String): Color {
    val palette = if (AnonTheme.colors.isDark) TILE_COLORS_DARK else TILE_COLORS_LIGHT
    val idx = ((title.hashCode() % palette.size) + palette.size) % palette.size
    return palette[idx]
}

private fun specLine(show: ShowCard): String {
    val parts = buildList {
        if (show.category.isNotBlank() && !show.category.startsWith("⭐") && !show.category.startsWith("🛡️")) {
            add(show.category)
        }
        if (show.year.isNotBlank()) add(show.year)
    }
    return if (parts.isEmpty()) "Direct" else parts.joinToString(" · ")
}

private fun secondaryBadge(show: ShowCard): String? = when {
    show.category.contains("Anime", ignoreCase = true) -> "SUB · DUB"
    show.site.equals("torrents", ignoreCase = true) -> null
    else -> "✓ 1080p"
}

private fun leftMetric(show: ShowCard): String = when {
    show.site.equals("torrents", ignoreCase = true) -> show.category
    show.totalEpisodes > 1 -> "${show.totalEpisodes} Episodes"
    show.category.contains("Movie", ignoreCase = true) -> "Single film"
    show.totalEpisodes == 1 -> "1 Episode"
    else -> "Available"
}

private fun rightMetric(show: ShowCard): String = when (show.site.lowercase()) {
    "nkiri", "9jarocks", "rocks" -> "Very Fast"
    "pluto", "nepu" -> "Fast"
    "anitaku" -> "Available"
    "dramakey", "dramarain", "asianc", "naijavault", "naijaprey" -> "Normal"
    "torrents" -> "P2P Magnet"
    else -> "Available"
}

@Composable
private fun AsianDramaHeroSection(
    onOpenKdrama: () -> Unit,
    onOpenCdrama: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ASIAN DRAMAS",
                fontSize = Type.sectionTitle.fontSize,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Modern & Historical",
                fontSize = 11.sp,
                color = TextMuted
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // K-Drama Hero Card: rich photographic backdrop + subtle violet accent border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(
                        if (AnonTheme.colors.isDark) Color(0xFF141822)
                        else Color(0xFFFFFFFF)
                    )
                    .border(
                        1.dp,
                        if (AnonTheme.colors.isDark) Color(0x338B5CF6) else Color(0x337C3AED),
                        RoundedCornerShape(Radius.md)
                    )
                    .clickable(onClick = onOpenKdrama),
                contentAlignment = Alignment.CenterStart
            ) {
                // Subtle photographic backdrop
                AsyncImage(
                    model = "https://image.tmdb.org/t/p/w500/2vP9kivc871s9s88qXzP8xJ87q0.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(if (AnonTheme.colors.isDark) 0.32f else 0.22f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                if (AnonTheme.colors.isDark) listOf(Color(0xF2141822), Color(0xD91C1B2E), Color(0x66090B10))
                                else listOf(Color(0xF8FFFFFF), Color(0xE6EDE9FE), Color(0x99F8F9FA))
                            )
                        )
                )
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "K-DRAMA",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SurfaceElevated)
                                .border(1.dp, BorderHairline, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("HUB", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        text = "Modern · Sageuk",
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Explore catalog",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // C-Drama Hero Card: rich photographic backdrop + subtle pink accent border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(
                        if (AnonTheme.colors.isDark) Color(0xFF141822)
                        else Color(0xFFFFFFFF)
                    )
                    .border(
                        1.dp,
                        if (AnonTheme.colors.isDark) Color(0x33EC4899) else Color(0x33DB2777),
                        RoundedCornerShape(Radius.md)
                    )
                    .clickable(onClick = onOpenCdrama),
                contentAlignment = Alignment.CenterStart
            ) {
                // Subtle photographic backdrop
                AsyncImage(
                    model = "https://image.tmdb.org/t/p/w500/vQWk5YBFWF4bZaofAbv0tShwBvQ.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(if (AnonTheme.colors.isDark) 0.32f else 0.22f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                if (AnonTheme.colors.isDark) listOf(Color(0xF2141822), Color(0xD92A1B24), Color(0x66090B10))
                                else listOf(Color(0xF8FFFFFF), Color(0xE6FCE7F3), Color(0x99F8F9FA))
                            )
                        )
                )
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "C-DRAMA",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SurfaceElevated)
                                .border(1.dp, BorderHairline, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("HUB", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        text = "Modern · Costume",
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Explore catalog",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

