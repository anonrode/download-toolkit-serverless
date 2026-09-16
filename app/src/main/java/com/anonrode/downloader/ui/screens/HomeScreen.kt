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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.anonrode.downloader.R
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.pipeline.VerdictPolicy
import com.anonrode.downloader.providers.CategoryFeed
import com.anonrode.downloader.ui.components.SearchListSkeleton
import com.anonrode.downloader.ui.components.TrendingRowSkeleton
import com.anonrode.downloader.ui.theme.*
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
                // Proportionality rule: beside a text lockup the mark's side
                // matches the full height of the two-line block (22sp title
                // line + 2dp + 11sp subtitle ≈ 41dp), and the corner radius
                // tracks the side at ~27% (was 9dp on 34dp) so the tile keeps
                // the same roundness at any size.
                Image(
                    painter = painterResource(R.drawable.ic_anon_mark),
                    contentDescription = "AnonRode Downloader logo",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
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
                        text = "100% Serverless • Native Multi-Provider Engine",
                        fontSize = Type.caption.fontSize,
                        color = TextMuted,
                        maxLines = 2,
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
                                modifier = Modifier.size(18.dp)
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
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onFilterSelected(key) },
                        label = {
                            Text(
                                text = label,
                                fontSize = Type.label.fontSize,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) BackgroundDark else TextSecondary
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentPrimary,
                            containerColor = SurfaceCard
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = BorderHairline,
                            selectedBorderColor = AccentPrimary
                        ),
                        shape = RoundedCornerShape(Radius.full)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.md))
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
                Column(modifier = Modifier.weight(1f)) {
                    TrendingSection(
                        items = uiState.trending,
                        isLoading = uiState.isTrendingLoading,
                        failed = uiState.trendingFailed,
                        showPosters = viewModel.engine.showPostersInResults,
                        onRefresh = { viewModel.loadTrending(force = true) },
                        onRetry = { viewModel.loadTrending(force = true) },
                        onOpen = { viewModel.openEpisodeDrawer(it) }
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    CategoryTilesGrid(
                        tiles = uiState.genreTiles,
                        showPosters = viewModel.engine.showPostersInResults,
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
                onBack = { viewModel.closeCategory() },
                onRefresh = { viewModel.loadCategory(category, force = true) },
                onOpen = { viewModel.openEpisodeDrawer(it) }
            )
        }
        if (uiState.catalogOpen && uiState.activeCategory == null) {
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
private fun PosterScrim() {
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
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (ShowCard) -> Unit
) {
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
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
                Text(
                    text = "Tap a title to pick episodes · or search above",
                    color = TextMuted,
                    fontSize = Type.label.fontSize
                )
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
    Column(
        modifier = modifier
            .width(124.dp)
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Poster geometry unified to 2:3 across every card dialect
                // (v3.1.6 design pass — the old 124×176 was a third ratio).
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .background(tileColor(show.title))
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
        ) {
            if (showPosters && show.posterUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = show.posterUrl,
                    contentDescription = show.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { InitialGlyph(show.title) },
                    error = { InitialGlyph(show.title) }
                )
            } else {
                InitialGlyph(show.title)
            }
            PosterScrim()
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = show.title,
            color = TextPrimary,
            fontSize = Type.body.fontSize,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = show.site.uppercase(),
            color = AccentPrimary,
            fontSize = Type.micro.fontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Genre poster tiles under the trending row (blank-query landing only),
 * v3.1.6: a WRAPPING grid, three per row, dropping down — the sideways
 * LazyRow is gone (user: "browse my genre shouldnt be scrollable left and
 * right, it should drop down… 3 per row"). Same poster-card language as
 * before (live top-post artwork, colored initial-glyph fallback, the row
 * never disappears over artwork); the trailing View More tile opens the
 * per-genre catalog. The tiles NAVIGATE — they are not filters.
 */
@Composable
private fun CategoryTilesGrid(
    tiles: List<CategoryFeed.GenreTile>,
    showPosters: Boolean,
    onOpen: (CategoryFeed.Category) -> Unit,
    onMore: () -> Unit
) {
    val posters = tiles.associate { it.category.label to it.posterUrl }
    // No horizontal padding here: the landing Column already owns the gutter
    // (v3.1.6 design pass — a second Spacing.lg here inset this section 32dp
    // while Trending sat at 16dp, so the two headers never lined up).
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Browse by Genre",
            color = TextPrimary,
            fontSize = Type.sectionTitle.fontSize,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        CategoryFeed.CATEGORIES.chunked(3).forEach { rowCats ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowCats.forEachIndexed { i, category ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (i == 0) 0.dp else Spacing.sm)
                    ) {
                        GenreTileCard(
                            label = category.label,
                            posterUrl = posters[category.label] ?: "",
                            showPosters = showPosters,
                            onClick = { onOpen(category) }
                        )
                    }
                }
                // Keep columns aligned when the last row is partial.
                repeat(3 - rowCats.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                ViewMoreTileCard(onClick = onMore)
            }
            Spacer(modifier = Modifier.weight(2f))
        }
    }
}

/** The 7th cell: opens the per-genre catalog. Deliberately NOT a poster —
 *  a dashed accent frame + plus glyph, so it reads as "more of this" and
 *  never masquerades as a genre with missing artwork. */
@Composable
private fun ViewMoreTileCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .border(1.5.dp, AccentPrimary, RoundedCornerShape(Radius.md)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "View More",
                    color = AccentPrimary,
                    fontSize = Type.label.fontSize,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun GenreTileCard(
    label: String,
    posterUrl: String,
    showPosters: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .background(tileColor(label))
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
        ) {
            if (showPosters && posterUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = posterUrl,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { InitialGlyph(label) },
                    error = { InitialGlyph(label) }
                )
            } else {
                InitialGlyph(label)
            }
            PosterScrim()
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = Type.body.fontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
    onBack: () -> Unit,
    onRefresh: () -> Unit,
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
            }
        }
    }
}

/** Grid cell of the genre page: the trending card's language (colored
 *  ground, Crop poster, initial-glyph fallback, one-line bold title) sized
 *  to the cell — three across on every phone, never a fixed dp width that
 *  overflows a small one. */
@Composable
private fun GridPosterCard(
    show: ShowCard,
    showPosters: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .background(tileColor(show.title))
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
        ) {
            if (showPosters && show.posterUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = show.posterUrl,
                    contentDescription = show.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { InitialGlyph(show.title) },
                    error = { InitialGlyph(show.title) }
                )
            } else {
                InitialGlyph(show.title)
            }
            PosterScrim()
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = show.title,
            color = TextPrimary,
            fontSize = Type.caption.fontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(TRENDING_ROW_H),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AccentPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
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
        PosterTile(show = show, showPosters = showPosters)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = show.title,
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
                CardBadge(show.site.uppercase(), accent = true)
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
private fun PosterTile(show: ShowCard, showPosters: Boolean) {
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 106.dp)
            .clip(shape)
            .background(tileColor(show.title))
    ) {
        if (showPosters && show.posterUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = show.posterUrl,
                contentDescription = show.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { InitialGlyph(show.title) },
                error = { InitialGlyph(show.title) }
            )
        } else {
            InitialGlyph(show.title)
        }
    }
}

@Composable
private fun InitialGlyph(title: String) {
    // Theme-aware glyph (UI research round): white@18% over the LIGHT tile
    // palette is invisible — light tiles carry an ink glyph of similar
    // subtlety, dark tiles keep the white one.
    val glyphColor = if (AnonTheme.colors.isDark) Color.White.copy(alpha = 0.18f)
        else Color.Black.copy(alpha = 0.20f)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            color = glyphColor,
            fontSize = Type.displayPoster.fontSize,
            fontWeight = FontWeight.Black
        )
    }
}

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
    return if (parts.isEmpty()) "${show.site.uppercase()} Direct" else parts.joinToString(" · ")
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
