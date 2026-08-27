package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDrawer(
    show: ShowCard,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val episodes = uiState.drawerEpisodes
    val isLoading = uiState.isEpisodesLoading
    val context = LocalContext.current

    var selectedEpisodes by remember(episodes) { mutableStateOf(setOf<EpisodeItem>()) }
    var rangeText by remember { mutableStateOf("") }
    var enqueued by remember { mutableStateOf(false) }

    // Parse range string (e.g. "1-5, 8, 10-12", "all", "none")
    fun applyRange(input: String) {
        val clean = input.trim()
        if (clean.isBlank() || clean.equals("none", ignoreCase = true) || clean.equals("clear", ignoreCase = true) || clean.equals("deselect", ignoreCase = true)) {
            selectedEpisodes = emptySet()
            return
        }
        if (clean.equals("all", ignoreCase = true) || clean.equals("*", ignoreCase = true)) {
            selectedEpisodes = episodes.toSet()
            return
        }
        val targetNums = mutableSetOf<Int>()
        val parts = clean.split(',', ';', ' ')
        for (part in parts) {
            val p = part.trim()
            if (p.isBlank()) continue
            if (p.contains('-')) {
                val start = p.substringBefore('-').trim().toIntOrNull()
                val end = p.substringAfter('-').trim().toIntOrNull()
                if (start != null && end != null) {
                    val rMin = minOf(start, end)
                    val rMax = maxOf(start, end)
                    targetNums.addAll(rMin..rMax)
                }
            } else {
                p.toIntOrNull()?.let { targetNums.add(it) }
            }
        }
        selectedEpisodes = episodes.filter { it.episodeNum in targetNums }.toSet()
    }

    // Dynamic Season Grouping for 1-Tap Filter Chips
    val seasonGroups = remember(episodes) {
        val groups = mutableMapOf<Int, MutableList<EpisodeItem>>()
        for (ep in episodes) {
            val seasonMatch = Regex("S([0-9]{1,2})", RegexOption.IGNORE_CASE).find(ep.title)
            val sNum = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (ep.episodeNum >= 100) ep.episodeNum / 100 else 1
            groups.getOrPut(sNum) { mutableListOf() }.add(ep)
        }
        groups.toSortedMap()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(BorderHairline)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.md)
        ) {
            // Header Title & Close
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = show.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${show.site.uppercase()} • ${episodes.size} Total Episodes",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(SurfaceCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                // 1-Tap Batch Season Selector Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    // All Chip
                    val isAllSelected = selectedEpisodes.size == episodes.size
                    FilterChip(
                        selected = isAllSelected,
                        onClick = {
                            selectedEpisodes = if (isAllSelected) emptySet() else episodes.toSet()
                        },
                        label = { Text("All (${episodes.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentPrimary,
                            selectedLabelColor = BackgroundDark,
                            containerColor = SurfaceCard,
                            labelColor = TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = BorderHairline,
                            selectedBorderColor = AccentPrimary,
                            enabled = true,
                            selected = isAllSelected
                        )
                    )

                    // Individual Season Chips
                    if (seasonGroups.size > 1) {
                        for ((sNum, sEps) in seasonGroups) {
                            val isSeasonSelected = sEps.all { it in selectedEpisodes }
                            FilterChip(
                                selected = isSeasonSelected,
                                onClick = {
                                    selectedEpisodes = if (isSeasonSelected) {
                                        selectedEpisodes - sEps.toSet()
                                    } else {
                                        selectedEpisodes + sEps.toSet()
                                    }
                                },
                                label = { Text("Season $sNum (${sEps.size})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentViolet,
                                    selectedLabelColor = Color.White,
                                    containerColor = SurfaceCard,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = BorderHairline,
                                    selectedBorderColor = AccentViolet,
                                    enabled = true,
                                    selected = isSeasonSelected
                                )
                            )
                        }
                    }

                    // Clear / Invert Chip
                    if (selectedEpisodes.isNotEmpty()) {
                        FilterChip(
                            selected = false,
                            onClick = { selectedEpisodes = emptySet() },
                            label = { Text("Clear (${selectedEpisodes.size})", fontSize = 11.sp, color = StatusError) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = SurfaceCard),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = StatusError.copy(alpha = 0.4f),
                                enabled = true,
                                selected = false
                            )
                        )
                    }
                }

                // Range Selector Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = rangeText,
                        onValueChange = {
                            rangeText = it
                            applyRange(it)
                        },
                        placeholder = { Text("Range (e.g. 1-5, 8, 10)", color = TextMuted, fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderHairline,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(Radius.md)
                    )

                    Button(
                        onClick = {
                            if (enqueued) return@Button
                            // Never silently queue the whole show: an empty
                            // selection is a tap mistake, not a request for
                            // every episode (40+ items, tens of GB on mobile).
                            if (selectedEpisodes.isEmpty()) {
                                Toast.makeText(context, "Select at least one episode first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            enqueued = true
                            // Respect the range the user typed: the field feeds
                            // selectedEpisodes, so queue exactly those.
                            val sorted = selectedEpisodes.sortedBy { it.episodeNum }
                            for (ep in sorted) {
                                viewModel.engine.enqueue(
                                    showTitle = show.title,
                                    episodeNum = ep.episodeNum,
                                    episodeTitle = "${show.title} - ${ep.title}",
                                    sourceUrl = ep.url,
                                    isDirect = false,
                                    backend = "aria2c",
                                    site = ep.site.ifBlank { show.site },
                                    parallelSockets = viewModel.engine.parallelSocketsPerFile
                                )
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = BackgroundDark
                        ),
                        shape = RoundedCornerShape(Radius.md),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
                    ) {
                        Text(
                            if (selectedEpisodes.isNotEmpty()) "Download (${selectedEpisodes.size})" else "All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Episodes List
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = AccentPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text("Scraping episode locker streams...", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            } else if (episodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (uiState.episodesError != null) {
                            Text(
                                text = uiState.episodesError ?: "No stream links found",
                                color = StatusError,
                                fontSize = 13.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text("Close and try again", color = TextMuted, fontSize = 11.sp)
                        } else {
                            Text("No stream links found for this title.", color = TextMuted, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(episodes, key = { it.url }) { ep ->
                        val isSelected = ep in selectedEpisodes
                        EpisodeRow(
                            episode = ep,
                            isSelected = isSelected,
                            onToggle = {
                                selectedEpisodes = if (isSelected) {
                                    selectedEpisodes - ep
                                } else {
                                    selectedEpisodes + ep
                                }
                            },
                            onDownloadSingle = {
                                viewModel.engine.enqueue(
                                    showTitle = show.title,
                                    episodeNum = ep.episodeNum,
                                    episodeTitle = "${show.title} - ${ep.title}",
                                    sourceUrl = ep.url,
                                    isDirect = false,
                                    backend = "aria2c",
                                    site = ep.site.ifBlank { show.site },
                                    parallelSockets = viewModel.engine.parallelSocketsPerFile
                                )
                                onDismiss()
                            }
                        )
                    }
                }
            }

            // Sticky Bottom Floating Batch Action Bar
            AnimatedVisibility(
                visible = selectedEpisodes.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                    shape = RoundedCornerShape(Radius.lg),
                    color = SurfaceCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderHairline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${selectedEpisodes.size} Selected",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ready to queue",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = {
                                // Double-tap guard: the dismiss animation takes
                                // ~300ms and a second tap would re-enqueue every
                                // selected episode (duplicate tasks, same filePath).
                                if (enqueued) return@Button
                                enqueued = true
                                val sorted = selectedEpisodes.sortedBy { it.episodeNum }
                                for (ep in sorted) {
                                    viewModel.engine.enqueue(
                                        showTitle = show.title,
                                        episodeNum = ep.episodeNum,
                                        episodeTitle = "${show.title} - ${ep.title}",
                                        sourceUrl = ep.url,
                                        isDirect = false,
                                        backend = "aria2c",
                                        site = ep.site.ifBlank { show.site },
                                        parallelSockets = viewModel.engine.parallelSocketsPerFile
                                    )
                                }
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = BackgroundDark
                            ),
                            shape = RoundedCornerShape(Radius.md)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Download (${selectedEpisodes.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeRow(
    episode: EpisodeItem,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onDownloadSingle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (isSelected) SurfaceCard else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) AccentPrimary.copy(alpha = 0.5f) else BorderHairline.copy(alpha = 0.4f),
                RoundedCornerShape(Radius.md)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentPrimary,
                    uncheckedColor = TextMuted,
                    checkmarkColor = BackgroundDark
                ),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(Spacing.sm))

            Text(
                text = episode.title,
                color = if (isSelected) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onDownloadSingle,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DownloadForOffline,
                contentDescription = "Download Single",
                tint = if (isSelected) AccentPrimary else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
