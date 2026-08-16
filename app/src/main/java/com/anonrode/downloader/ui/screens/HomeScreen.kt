package com.anonrode.downloader.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSocial: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val tasks by viewModel.engine.tasks.collectAsState()
    val activeTasks = remember(tasks) {
        tasks.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }
    }
    val context = LocalContext.current

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

    LaunchedEffect(Unit) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!item.isNullOrBlank() && (item.startsWith("http://") || item.startsWith("https://") || item.startsWith("magnet:?"))) {
                clipboardSnippet = item
            }
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ANONRODE",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "100% Serverless • Native Multi-Provider Engine",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // Downloads screen shortcut with live badge
                    IconButton(
                        onClick = onOpenDownloads,
                        modifier = Modifier
                            .size(40.dp)
                            .background(SurfaceCard, CircleShape)
                    ) {
                        BadgedBox(
                            badge = {
                                if (activeTasks.isNotEmpty()) {
                                    Badge(
                                        containerColor = AccentPrimary,
                                        contentColor = BackgroundDark
                                    ) {
                                        Text("${activeTasks.size}", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Downloads",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Settings modal shortcut
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .background(SurfaceCard, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Search Bar
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChanged(it) },
                placeholder = {
                    Text(
                        "Search series, anime, movies, torrents...",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (uiState.query.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.onQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { viewModel.search() },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(32.dp)
                                    .background(AccentPrimary, CircleShape)
                            ) {
                                Icon(Icons.Rounded.ArrowForward, contentDescription = "Search", tint = BackgroundDark, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(Radius.full),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceCard,
                    unfocusedContainerColor = SurfaceCard,
                    focusedBorderColor = AccentPrimary,
                    unfocusedBorderColor = BorderHairline,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Smart Clipboard Banner
            if (!clipboardSnippet.isNullOrBlank() && uiState.query.isBlank()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(SurfaceElevated)
                        .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                        .clickable {
                            val snippet = clipboardSnippet!!
                            clipboardSnippet = null
                            viewModel.handlePastedInput(snippet) { platform, url ->
                                onOpenSocial(platform, url)
                            }
                        }
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
                            text = "Paste link: ${clipboardSnippet!!.take(35)}...",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text("Download", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

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
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Live Search Results
            if (uiState.isSearching && uiState.searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text("Streaming search across all providers...", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            } else if (uiState.searchResults.isEmpty() && uiState.query.isNotBlank() && !uiState.isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No results found for "${uiState.query}"", color = TextMuted, fontSize = 14.sp)
                }
            } else if (uiState.searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text("Search dramas, anime, torrents or paste a link", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.searchResults, key = { it.url }) { show ->
                        ShowCardItem(
                            show = show,
                            showPosters = viewModel.engine.showPostersInResults,
                            onClick = { viewModel.openEpisodeDrawer(show) }
                        )
                    }
                }
            }
        }

        // Episode Drawer Modal
        if (uiState.activeShowForDrawer != null) {
            EpisodeDrawer(
                show = uiState.activeShowForDrawer!!,
                viewModel = viewModel,
                onDismiss = { viewModel.closeEpisodeDrawer() }
            )
        }
    }
}

@Composable
fun ShowCardItem(
    show: ShowCard,
    showPosters: Boolean,
    onClick: () -> Unit
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
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = specLine(show),
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CardBadge(show.site.uppercase(), accent = true)
                secondaryBadge(show)?.let { CardBadge(it, accent = false) }
            }

            Spacer(modifier = Modifier.height(10.dp))
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
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(StatusSuccess)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
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
private fun CardBadge(text: String, accent: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(SurfaceElevated)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.sm))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = if (accent) AccentPrimary else TextSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
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

// Clean specLine that shows Category and Year without repeating the site name
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
