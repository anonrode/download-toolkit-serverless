package com.anonrode.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    var selectedEpisodes by remember(episodes) { mutableStateOf(setOf<EpisodeItem>()) }
    var rangeText by remember { mutableStateOf("") }

    // Parse range string (e.g. "1-5, 8, 10-12")
    fun applyRange(input: String) {
        val clean = input.trim()
        if (clean.isBlank()) {
            selectedEpisodes = emptySet()
            return
        }
        val targetNums = mutableSetOf<Int>()
        val parts = clean.split(',', ';', ' ')
        for (part in parts) {
            val p = part.trim()
            if (p.contains('-')) {
                val start = p.substringBefore('-').toIntOrNull()
                val end = p.substringAfter('-').toIntOrNull()
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
                .padding(bottom = Spacing.xxl)
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
                        text = "${show.site.uppercase()} • Direct Stream",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .background(SurfaceCard, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (episodes.isNotEmpty()) {
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
                        shape = RoundedCornerShape(Radius.md),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard,
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderHairline,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    )

                    Button(
                        onClick = {
                            if (selectedEpisodes.size == episodes.size) {
                                selectedEpisodes = emptySet()
                                rangeText = ""
                            } else {
                                selectedEpisodes = episodes.toSet()
                                rangeText = "1-${episodes.size}"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedEpisodes.size == episodes.size) AccentPrimary else SurfaceCard,
                            contentColor = if (selectedEpisodes.size == episodes.size) BackgroundDark else TextPrimary
                        ),
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            text = if (selectedEpisodes.size == episodes.size) "Deselect" else "All",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Batch Download Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedEpisodes.isEmpty()) "${episodes.size} Episodes" else "${selectedEpisodes.size}/${episodes.size} Selected",
                        fontSize = 12.sp,
                        color = if (selectedEpisodes.isEmpty()) TextMuted else AccentPrimary,
                        fontWeight = if (selectedEpisodes.isEmpty()) FontWeight.Normal else FontWeight.Bold
                    )

                    if (selectedEpisodes.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.downloadAllEpisodes(selectedEpisodes.toList().sortedBy { it.episodeNum })
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BackgroundDark),
                            shape = RoundedCornerShape(Radius.full),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadForOffline,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Download Selected (${selectedEpisodes.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                viewModel.downloadAllEpisodes(episodes)
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AccentPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadForOffline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Download All (${episodes.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentPrimary, strokeWidth = 2.dp)
                }
            } else if (episodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.episodesError ?: "No episodes found on this page",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(episodes) { ep ->
                        val isChecked = ep in selectedEpisodes
                        EpisodeRowItem(
                            episode = ep,
                            isChecked = isChecked,
                            onToggleSelect = {
                                selectedEpisodes = if (isChecked) selectedEpisodes - ep else selectedEpisodes + ep
                            },
                            onDownload = {
                                viewModel.downloadEpisode(ep)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeRowItem(
    episode: EpisodeItem,
    isChecked: Boolean,
    onToggleSelect: () -> Unit,
    onDownload: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (isChecked) SurfaceElevated else SurfaceCard)
            .border(1.dp, if (isChecked) AccentPrimary else BorderHairline, RoundedCornerShape(Radius.md))
            .clickable { onToggleSelect() }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = AccentPrimary,
                        uncheckedColor = BorderHairline,
                        checkmarkColor = BackgroundDark
                    ),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Column {
                    Text(
                        text = episode.title,
                        color = if (isChecked) AccentPrimary else TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = "Direct Fast Stream",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceElevated, CircleShape)
                    .border(1.dp, BorderHairline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Download Single",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
