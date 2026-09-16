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
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.anonrode.downloader.pipeline.PlaylistPicker
import com.anonrode.downloader.ui.components.QualityChipRow
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel

/**
 * YouTube playlist picker (2026-09-15) — the Seal-beating selection surface.
 *
 * Seal offers a checkbox list + select-all + an index-range dialog. This adds
 * what Seal doesn't have: a search filter for long lists, invert-selection,
 * ALREADY-ON-DEVICE detection (badged, skipped by default — re-downloading 8
 * of 12 songs is never the intent), an honest ≈size estimate from durations
 * x the chosen quality, and an audio-only batch mode for podcast/album lists.
 * Confirm enqueues ONE grouped show (playlist title, episode = position), so
 * the Downloads group view and the player's episode-ordered Next walk the
 * playlist like a series.
 *
 * Same sheet language as SocialModal (ModalBottomSheet at the root, theme
 * tokens throughout). A watch URL that carries ?list= pre-selects its own
 * video, so "download this but let me grab the rest too" is one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val state by viewModel.playlistState.collectAsState()
    val tasks by viewModel.engine.tasks.collectAsState()
    val meta = state.meta

    var query by remember { mutableStateOf("") }
    var rangeSpec by remember { mutableStateOf("") }
    var rangeError by remember { mutableStateOf<String?>(null) }
    var audioOnly by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf(viewModel.engine.defaultQuality) }
    val selected = remember(state.meta) { mutableStateListOf<Int>() }
    var skipDownloaded by remember(state.meta) { mutableStateOf(true) }

    // A watch URL inside a playlist (the shape YouTube's own Share button
    // produces) pre-selects THAT video — "this one, and let me grab the
    // rest too" is one tap. Runs only when the metadata (re)loads, so it
    // never stomps the user's later checkbox edits.
    LaunchedEffect(meta) {
        val m = meta ?: return@LaunchedEffect
        val vid = state.parsed?.videoId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        m.entries.forEachIndexed { i, e -> if (e.videoId == vid) selected.add(i + 1) }
    }

    val downloaded = remember(meta, tasks) {
        meta?.let { PlaylistPicker.downloadedIds(tasks) } ?: emptySet()
    }
    val filtered = remember(meta, query) {
        meta?.let { PlaylistPicker.filterIndices(it.entries, query) } ?: emptyList()
    }
    // What "Download" would actually queue: selection minus the downloaded
    // ones when the skip toggle is on.
    val effective = remember(selected.toList(), downloaded, skipDownloaded, meta) {
        selected.filter { idx ->
            if (!skipDownloaded) return@filter true
            val e = meta?.entries?.getOrNull(idx - 1) ?: return@filter true
            e.videoId !in downloaded
        }
    }
    val estimate = remember(meta, effective, audioOnly, selectedQuality) {
        meta?.let { PlaylistPicker.estimateBytes(it.entries, effective, selectedQuality, audioOnly) } ?: 0L
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {

            if (state.loading) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = AccentPrimary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(Spacing.md))
                    Text("Reading the playlist…", fontSize = Type.rowTitle.fontSize, color = TextSecondary)
                    Text(
                        "one metadata call, no video pages",
                        fontSize = Type.caption.fontSize,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(Spacing.lg))
                }
                return@Column
            }

            if (meta == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = state.error ?: "Playlist unavailable.",
                        fontSize = Type.body.fontSize,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = onDismiss) { Text("Close") }
                        Button(
                            onClick = { state.url?.let { viewModel.openPlaylist(it) } },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BackgroundDark)
                        ) { Text("Retry", fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
                return@Column
            }

            val totalSec = remember(meta) { meta.entries.sumOf { it.durationSec } }
            // ---- header ----
            Text(
                text = meta.title.ifBlank { "YouTube playlist" },
                fontSize = Type.screenTitle.fontSize,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = buildString {
                    meta.uploader.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                    append("${meta.entries.size} videos · ${PlaylistPicker.formatDuration(totalSec)} total")
                },
                fontSize = Type.label.fontSize,
                color = TextSecondary
            )
            if (state.parsed?.kind == PlaylistPicker.ListKind.MIX) {
                Spacer(Modifier.height(Spacing.xs))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(AccentViolet.copy(alpha = 0.12f))
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                ) {
                    Text(
                        "Auto-mix — YouTube regenerates these lists, so entries can differ from the app's order.",
                        fontSize = Type.caption.fontSize,
                        color = AccentViolet
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))

            // ---- search + bulk ops ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter by title…", fontSize = Type.label.fontSize, color = TextMuted) },
                    textStyle = LocalTextStyle.current.copy(fontSize = Type.body.fontSize),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.md),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = BorderHairline,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                TextButton(onClick = {
                    selected.clear()
                    selected.addAll(filtered)
                }) { Text("Select all", fontSize = Type.label.fontSize, color = AccentPrimary) }
                TextButton(onClick = {
                    val flipped = filtered.filter { it !in selected }
                    selected.clear()
                    selected.addAll(flipped)
                }) { Text("Invert", fontSize = Type.label.fontSize, color = AccentPrimary) }
            }

            // ---- range spec ----
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = rangeSpec,
                    onValueChange = { rangeSpec = it; rangeError = null },
                    placeholder = { Text("Range e.g. 1-5,8,10-12", fontSize = Type.label.fontSize, color = TextMuted) },
                    textStyle = LocalTextStyle.current.copy(fontSize = Type.body.fontSize),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.md),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = BorderHairline,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                TextButton(onClick = {
                    PlaylistPicker.parseRanges(rangeSpec, meta.entries.size)?.let {
                        selected.clear(); selected.addAll(it)
                    } ?: run { rangeError = "Use 1-based numbers like 1-5,8 (max ${meta.entries.size})." }
                }) { Text("Apply", fontSize = Type.label.fontSize, color = AccentPrimary) }
            }
            rangeError?.let {
                Text(it, fontSize = Type.caption.fontSize, color = StatusError)
            }

            // ---- mode: audio toggle, then the quality row FULL WIDTH below.
            // (v3.1.6 design pass: sharing one Row with the ~160dp audio chip
            // left the four quality chips ~34dp each — "1080p" clipped on
            // 360-400dp phones. SocialModal already keeps them on separate
            // rows; this sheet now matches.)
            Spacer(Modifier.height(Spacing.xs))
            FilterChip(
                selected = audioOnly,
                onClick = { audioOnly = !audioOnly },
                label = { Text("Audio only (MP3)", fontSize = Type.label.fontSize) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentPrimary,
                    selectedLabelColor = BackgroundDark
                )
            )
            if (!audioOnly) {
                Spacer(Modifier.height(Spacing.sm))
                QualityChipRow(
                    options = listOf("480p", "720p", "1080p", "Best"),
                    selected = selectedQuality,
                    onSelect = { selectedQuality = it }
                )
            }
            val downloadedInList = remember(meta, downloaded) {
                meta.entries.count { it.videoId in downloaded }
            }
            if (downloadedInList > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { skipDownloaded = !skipDownloaded }
                ) {
                    Checkbox(
                        checked = skipDownloaded,
                        onCheckedChange = { skipDownloaded = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                    )
                    Text(
                        "Skip $downloadedInList already-on-device video${if (downloadedInList > 1) "s" else ""}",
                        fontSize = Type.label.fontSize,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))

            // ---- entries ----
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 200.dp, max = 440.dp)
            ) {
                items(filtered, key = { it }) { idx ->
                    val entry = meta.entries[idx - 1]
                    val isDownloaded = entry.videoId in downloaded
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .clickable {
                                if (selected.contains(idx)) selected.remove(idx) else selected.add(idx)
                            }
                            .padding(vertical = Spacing.xs, horizontal = Spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 78.dp, height = 44.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(SurfaceCard),
                            contentAlignment = Alignment.Center
                        ) {
                            if (entry.thumbnailUrl.isNotBlank()) {
                                SubcomposeAsyncImage(
                                    model = entry.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            entry.durationSec.takeIf { it > 0 }?.let { d ->
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.72f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        PlaylistPicker.formatDuration(d),
                                        fontSize = Type.micro.fontSize,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$idx · ${entry.title}",
                                fontSize = Type.body.fontSize,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isDownloaded) {
                                Text(
                                    "✓ on device",
                                    fontSize = Type.caption.fontSize,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusSuccess
                                )
                            }
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Checkbox(
                            checked = selected.contains(idx),
                            onCheckedChange = {
                                if (it) selected.add(idx) else selected.remove(idx)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                        )
                    }
                }
                if (filtered.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            "No entry matches \"$query\".",
                            fontSize = Type.label.fontSize,
                            color = TextMuted,
                            modifier = Modifier.padding(vertical = Spacing.md)
                        )
                    }
                }
            }

            // ---- confirm ----
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = {
                    if (effective.isEmpty()) return@Button
                    viewModel.confirmPlaylist(
                        indices = effective.sorted(),
                        audioOnly = audioOnly,
                        quality = if (audioOnly) null else selectedQuality
                    )
                    onDismiss()
                },
                enabled = effective.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BackgroundDark)
            ) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = buildString {
                        append("Download ${effective.size}")
                        if (estimate > 0) append(" · ≈${PlaylistPicker.formatBytes(estimate)}")
                        if (audioOnly) append(" · MP3")
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = Type.rowTitle.fontSize
                )
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}
