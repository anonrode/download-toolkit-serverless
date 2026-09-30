package com.anonrode.downloader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.providers.AsianDramaTaxonomy
import com.anonrode.downloader.providers.DramaEra
import com.anonrode.downloader.providers.DramaGenre
import com.anonrode.downloader.providers.DramaRegion
import com.anonrode.downloader.providers.DramaStatusFilter
import com.anonrode.downloader.ui.theme.*

@Composable
fun AsianDramaExplorer(
    region: DramaRegion,
    era: DramaEra,
    statusFilter: DramaStatusFilter,
    activeGenre: DramaGenre?,
    cards: List<ShowCard>,
    isLoading: Boolean,
    failed: Boolean,
    showPosters: Boolean,
    onBack: () -> Unit,
    onSelectEra: (DramaEra) -> Unit,
    onSelectStatus: (DramaStatusFilter) -> Unit,
    onSelectGenre: (DramaGenre?) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (ShowCard) -> Unit
) {
    BackHandler(onBack = onBack)

    val genres = remember(region, era) {
        AsianDramaTaxonomy.genresFor(region, era)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SurfaceElevated)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = region.label.uppercase(),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.full))
                                .background(AccentPrimary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${cards.size} shows",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentPrimary
                            )
                        }
                    }
                    Text(
                        text = if (region == DramaRegion.KDRAMA) "Korean Dramas & Series" else "Chinese Dramas & Series",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SurfaceElevated)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = if (isLoading) AccentPrimary else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Era Selector: Modern vs Historical
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceCard)
                    .padding(4.dp)
            ) {
                val eras = listOf(DramaEra.MODERN, DramaEra.HISTORICAL)
                eras.forEach { itemEra ->
                    val isSelected = itemEra == era
                    val eraLabel = if (itemEra == DramaEra.MODERN) {
                        "Modern"
                    } else {
                        if (region == DramaRegion.KDRAMA) "Historical (Sageuk)" else "Historical (Costume)"
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(if (isSelected) AccentPrimary else Color.Transparent)
                            .clickable { onSelectEra(itemEra) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = eraLabel,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) AnonTheme.colors.background else TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Status Filter Row: All | Completed | Ongoing
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STATUS:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    modifier = Modifier.padding(end = 4.dp)
                )
                DramaStatusFilter.values().forEach { status ->
                    val isSelected = status == statusFilter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.full))
                            .background(if (isSelected) SurfaceCard else SurfaceElevated)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) AccentPrimary else BorderHairline,
                                shape = RoundedCornerShape(Radius.full)
                            )
                            .clickable { onSelectStatus(status) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = status.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) TextPrimary else TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Main Content Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Spacing.md)
            ) {
                if (isLoading && cards.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = AccentPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = "Loading ${region.label} titles…",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else if (failed && cards.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = "Unable to load dramas from providers.",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        TextButton(onClick = onRefresh) {
                            Text("Retry", color = AccentPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (cards.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No titles found matching this filter.",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        TextButton(onClick = {
                            onSelectStatus(DramaStatusFilter.ALL)
                            onSelectGenre(null)
                        }) {
                            Text("Reset filters", color = AccentPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        contentPadding = PaddingValues(bottom = Spacing.xxl),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(cards, key = { it.url }) { show ->
                            DramaCardItem(
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
private fun InitialGlyph(title: String) {
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

@Composable
private fun DramaCardItem(
    show: ShowCard,
    showPosters: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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

            // Status badge on top-right if available
            val isComplete = show.title.contains("(Complete)", ignoreCase = true) ||
                show.tags.any { it.equals("Completed", ignoreCase = true) }
            val isOngoing = show.title.contains("Added", ignoreCase = true) ||
                show.tags.any { it.equals("Ongoing", ignoreCase = true) }

            if (isComplete || isOngoing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.xs)
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(if (isComplete) Color(0xCC059669) else Color(0xCC0284C7))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isComplete) "END" else "AIRING",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
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
    }
}
