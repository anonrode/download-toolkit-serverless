package com.anonrode.downloader.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
        "torrents" to "🧲 Torrents (TPB)",
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
                Image(
                    painter = painterResource(R.drawable.ic_anon_mark),
                    contentDescription = "AnonRode Downloader logo",
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ANONRODE",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        text = "100% Serverless • Native Multi-Provider Engine",
                        fontSize = 11.sp,
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
                                fontSize = 14.sp,
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
                                fontSize = 14.sp,
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
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text("Download", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Filter Chips Carousel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                filters.forEach { (key, label) ->
                    val isSelected = uiState.selectedFilter == key
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onFilterSelected(key) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
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
            }
            if (uiState.isSearching && visibleResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text("Streaming search across all providers...", color = TextSecondary, fontSize = 13.sp)
                    }
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
                            fontSize = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text("Check your connection and try again", color = TextMuted, fontSize = 12.sp)
                        // The hint said "try again" but the only way to try
                        // again was perturbing the query — mirror the
                        // trending panel and offer the retry as a button.
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        TextButton(onClick = { viewModel.search() }) {
                            Text("Retry", color = AccentPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            } else if (visibleResults.isEmpty() && uiState.query.trim().length >= 2 && !uiState.isSearching) {
                // Note: keyed off visibleResults, not the raw crawl — when
                // every returned card was proven dead, the honest message is
                // "No results", not a blank landing page under a live query.
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No results found for \"${uiState.query}\"", color = TextMuted, fontSize = 14.sp)
                }
            } else if (visibleResults.isEmpty()) {
                // Blank-query state: the trending row IS the landing content
                // (feature request: show what's trending on open, scrolling
                // left to right, not top to bottom). Category chips sit
                // directly under it (feature request: "sections like Action
                // below trending, tapping brings up 3 per-site rows") —
                // they belong to the landing state only, never over results.
                Column(modifier = Modifier.weight(1f)) {
                    TrendingSection(
                        items = uiState.trending,
                        isLoading = uiState.isTrendingLoading,
                        failed = uiState.trendingFailed,
                        showPosters = viewModel.engine.showPostersInResults,
                        onRetry = { viewModel.loadTrending(force = true) },
                        onOpen = { viewModel.openEpisodeDrawer(it) }
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    CategoryTilesRow(
                        tiles = uiState.genreTiles,
                        showPosters = viewModel.engine.showPostersInResults,
                        onOpen = { category -> viewModel.openCategory(category) }
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
                            onClick = { viewModel.openEpisodeDrawer(show) }
                        )
                    }
                }
            }
        }

        // Category page: a full-page overlay in THIS window's root Box — the
        // b5cdf99 lesson says page-level content never rides a Dialog window.
        // Composed BEFORE the drawer block so tapping a row card stacks the
        // EpisodeDrawer on top, exactly like it does over the landing state.
        uiState.activeCategory?.let { category ->
            CategoryPage(
                category = category,
                rows = uiState.categoryRows,
                isLoading = uiState.isCategoryLoading,
                failed = uiState.categoryFailed,
                showPosters = viewModel.engine.showPostersInResults,
                onBack = { viewModel.closeCategory() },
                onRetry = { viewModel.loadCategory(category, force = true) },
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
@Composable
private fun TrendingSection(
    modifier: Modifier = Modifier,
    items: List<ShowCard>,
    isLoading: Boolean,
    failed: Boolean,
    showPosters: Boolean,
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
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (failed && items.isEmpty() && !isLoading) {
                Text(
                    text = "Retry",
                    color = AccentPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.full))
                        .clickable { onRetry() }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        when {
            isLoading && items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text("Loading trending...", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Trending is unavailable right now", color = TextSecondary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text("Tap Retry to load it again", color = TextMuted, fontSize = 12.sp)
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
                            onClick = { onOpen(show) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
                Text(
                    text = "Tap a title to pick episodes · or search above",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun TrendingCard(
    show: ShowCard,
    showPosters: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
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
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = show.title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = show.site.uppercase(),
            color = AccentPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Display names for the CategoryFeed row headers (mirrors the filter chips). */
private val CATEGORY_SITE_LABELS = mapOf(
    "nkiri" to "NKiri",
    "9jarocks" to "9jaRocks",
    "naijaprey" to "NaijaPrey",
    "naijavault" to "NaijaVault"
)

/**
 * Genre poster tiles under the trending row (blank-query landing only). Same
 * 124dp poster-card geometry as the trending cards; the artwork is that
 * genre's current top post (CategoryFeed.tilePosters), and a missing poster
 * degrades to the app's colored initial-glyph tile with the genre name —
 * the row itself is static structure and never disappears over artwork.
 * The tiles NAVIGATE (openCategory); they are not filters.
 */
@Composable
private fun CategoryTilesRow(
    tiles: List<CategoryFeed.GenreTile>,
    showPosters: Boolean,
    onOpen: (CategoryFeed.Category) -> Unit
) {
    val posters = tiles.associate { it.category.label to it.posterUrl }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Browse by Genre",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            contentPadding = PaddingValues(end = Spacing.lg),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(CategoryFeed.CATEGORIES, key = { it.label }) { category ->
                GenreTileCard(
                    label = category.label,
                    posterUrl = posters[category.label] ?: "",
                    showPosters = showPosters,
                    onClick = { onOpen(category) }
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
            .width(124.dp)
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
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
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Full-page genre view: up to three stacked rows, each one site's latest
 * posts for the genre ("Action on NKiri", "Action on 9jaRocks", …). Cards
 * are the trending cards verbatim; a tap opens the same EpisodeDrawer
 * (it composes above this page in HomeScreen's root Box). Rendered as a
 * root-level overlay in the activity's own window — never a Dialog window.
 */
@Composable
private fun CategoryPage(
    category: CategoryFeed.Category,
    rows: List<CategoryFeed.CategoryRow>,
    isLoading: Boolean,
    failed: Boolean,
    showPosters: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (rows.isNotEmpty()) "LATEST ${rows.size} SITE${if (rows.size > 1) "S" else ""}" else "Latest genre posts, by site",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }
        when {
            isLoading && rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text("Loading ${category.label.lowercase()}...", color = TextSecondary, fontSize = 13.sp)
                }
            }
            rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${category.label} is unavailable right now", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = if (failed) "All sources timed out" else "No ${category.label.lowercase()} posts found",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    TextButton(onClick = onRetry) {
                        Text("Retry", color = AccentPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                contentPadding = PaddingValues(bottom = Spacing.xl)
            ) {
                items(rows, key = { it.site }) { row ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${category.label} on ${CATEGORY_SITE_LABELS[row.site] ?: row.site.uppercase()}",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = Spacing.lg)
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            contentPadding = PaddingValues(horizontal = Spacing.lg),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(row.items, key = { it.url }) { show ->
                                TrendingCard(
                                    show = show,
                                    showPosters = showPosters,
                                    onClick = { onOpen(show) }
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
    verifiedCaption: String? = null
) {
    Row(
        modifier = Modifier
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
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = specLine(show),
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CardBadge(show.site.uppercase(), accent = true)
                // A real oracle caption REPLACES the hardcoded "✓ 1080p"
                // guess — the verified bytes are strictly more honest. With
                // no verdict, the row is unchanged from before the oracle.
                if (verifiedCaption != null) {
                    CardBadge(verifiedCaption, accent = false, verified = true)
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
                    fontSize = 13.sp,
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
                        fontSize = 13.sp,
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            color = Color.White.copy(alpha = 0.18f),
            fontSize = 34.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun CardBadge(text: String, accent: Boolean, verified: Boolean = false) {
    Box(
        modifier = Modifier
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
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val TILE_COLORS = listOf(
    Color(0xFF3A1C1C), Color(0xFF2A2A10), Color(0xFF20303A),
    Color(0xFF241A33), Color(0xFF14301F), Color(0xFF33231A)
)
private fun tileColor(title: String): Color {
    val idx = ((title.hashCode() % TILE_COLORS.size) + TILE_COLORS.size) % TILE_COLORS.size
    return TILE_COLORS[idx]
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
