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
                            color = if (isSelected) Color.Black else TextSecondary
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
                            .background(if (isSelected) SurfaceCardHover else SurfaceElevated)
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

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Horizontal Genre Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                genres.forEach { genre ->
                    val isSelected = (activeGenre == null && genre.id == "all") || (activeGenre?.id == genre.id)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.full))
                            .background(if (isSelected) AccentPrimary else SurfaceCard)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) AccentPrimary else BorderHairline,
                                shape = RoundedCornerShape(Radius.full)
                            )
                            .clickable {
                                onSelectGenre(if (genre.id == "all") null else genre)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = genre.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.Black else TextSecondary
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
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SurfaceCard)
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.sm))
        ) {
            if (showPosters && show.posterUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = show.posterUrl,
                    contentDescription = show.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SurfaceElevated)
                        )
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = show.title.take(1),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = show.title.take(1),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                }
            }

            // Status badge on top-right if available
            val isComplete = show.title.contains("(Complete)", ignoreCase = true) ||
                show.tags.any { it.equals("Completed", ignoreCase = true) }
            val isOngoing = show.title.contains("Added", ignoreCase = true) ||
                show.tags.any { it.equals("Ongoing", ignoreCase = true) }

            if (isComplete || isOngoing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
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

            // Site pill at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = show.site.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        Text(
            text = show.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp
        )
    }
}
