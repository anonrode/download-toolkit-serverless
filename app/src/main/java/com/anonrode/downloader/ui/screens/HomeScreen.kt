package com.anonrode.downloader.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSocialModal: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val tasks by viewModel.engine.tasks.collectAsState()
    val activeTasks = remember(tasks) {
        tasks.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }
    }
    val context = LocalContext.current

    val filters = listOf(
        "all" to "All Providers",
        "nkiri" to "NKiri",
        "dramakey" to "DramaKey",
        "asianc" to "MyAsianTV / AsianC",
        "anitaku" to "Anime (Anitaku)",
        "pluto" to "Movies (Pluto)",
        "9jarocks" to "9jaRocks",
        "dramarain" to "DramaRain"
    )

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
                        text = "ANON DOWNLOADER",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "100% Serverless • Native Multi-Provider Engine",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    IconButton(
                        onClick = onOpenDownloads,
                        modifier = Modifier
                            .size(40.dp)
                            .background(SurfaceElevated, CircleShape)
                            .border(1.dp, BorderHairline, CircleShape)
                    ) {
                        BadgedBox(
                            badge = {
                                if (activeTasks.isNotEmpty()) {
                                    Badge(
                                        containerColor = StatusSuccess,
                                        contentColor = Color.Black
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

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .background(SurfaceElevated, CircleShape)
                            .border(1.dp, BorderHairline, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Search Capsule
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(Radius.xl))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.xl))
                    .padding(horizontal = Spacing.md),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(Spacing.sm))

                    TextField(
                        value = uiState.query,
                        onValueChange = { viewModel.onQueryChanged(it) },
                        placeholder = {
                            Text(
                                "Search or paste any Drama / Social link...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = TextPrimary,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val input = uiState.query
                            if (input.startsWith("http")) {
                                viewModel.handlePastedInput(input, onOpenSocialModal)
                            } else {
                                viewModel.search()
                            }
                        }),
                        modifier = Modifier.weight(1f)
                    )

                    if (uiState.query.isNotBlank()) {
                        IconButton(
                            onClick = { viewModel.onQueryChanged("") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        // Paste from Clipboard
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (clip.isNotBlank()) {
                                    viewModel.handlePastedInput(clip, onOpenSocialModal)
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = "Paste",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filters) { (key, label) ->
                    val isSelected = uiState.selectedFilter == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(if (isSelected) AccentPrimary else SurfaceCard)
                            .border(1.dp, if (isSelected) AccentPrimary else BorderHairline, RoundedCornerShape(Radius.pill))
                            .clickable { viewModel.onFilterSelected(key) }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.Black else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Search Results or Interactive Empty State
            if (uiState.isSearching && uiState.searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = AccentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = "Streaming live results across all providers...",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (uiState.searchError != null && uiState.searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.searchError ?: "No results found",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else if (uiState.searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.MovieFilter,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = "Search drama title or paste any video URL",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.searchResults) { show ->
                        ShowCardItem(
                            show = show,
                            onClick = { viewModel.openEpisodeDrawer(show) }
                        )
                    }
                }
            }
        }

        // Mini Floating Download Progress Bar
        if (activeTasks.isNotEmpty()) {
            val task = activeTasks.first()
            val progress = if (task.totalBytes > 0) task.downloadedBytes.toFloat() / task.totalBytes.toFloat() else 0f

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.lg)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(SurfaceElevated)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.lg))
                    .clickable { onOpenDownloads() }
                    .padding(Spacing.md)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = StatusSuccess,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.episodeTitle,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        LinearProgressIndicator(
                            progress = { progress },
                            color = StatusSuccess,
                            trackColor = Color(0xFF232836),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = StatusSuccess,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ShowCardItem(
    show: ShowCard,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(Radius.md))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
            .clickable { onClick() }
    ) {
        if (show.posterUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(show.posterUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = show.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f)),
                        startY = 150f
                    )
                )
        )

        // Site Badge
        Box(
            modifier = Modifier
                .padding(Spacing.sm)
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(Radius.pill))
                .background(Color.Black.copy(alpha = 0.75f))
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.pill))
                .padding(horizontal = Spacing.sm, vertical = 2.dp)
        ) {
            Text(
                text = show.site.uppercase(),
                color = TextPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black
            )
        }

        // Title and Category
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.md)
        ) {
            Text(
                text = show.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (show.category.isNotBlank()) {
                Text(
                    text = show.category,
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}
