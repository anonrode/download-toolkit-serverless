package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.anonrode.downloader.data.models.EpisodeItem
import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.ui.components.EpisodeListSkeleton
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel
import com.anonrode.downloader.ui.util.confirmHaptic
import com.anonrode.downloader.ui.util.tick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Haptic engine handle (UI research round): queueing a download is the
    // app's main commitment moment, and multi-select needs per-item
    // acknowledgement. Default flags only — the system haptics toggle wins.
    val hapticView = LocalView.current

    var selectedEpisodes by remember(episodes) { mutableStateOf(setOf<EpisodeItem>()) }
    var rangeText by remember { mutableStateOf("") }
    var enqueued by remember { mutableStateOf(false) }
    var isEnqueuing by remember { mutableStateOf(false) }

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
        // While TYPING a range the intermediate states are meaningless, not
        // a command: "10-" (end not typed yet) or pure garbage must not wipe
        // the existing selection — that made the checkboxes flicker with
        // every keystroke. Only a parsed non-empty set re-marks the list
        // (clearing stays available via "none"/backspace-to-empty/above).
        if (targetNums.isEmpty()) return
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
                        fontSize = Type.screenTitle.fontSize,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${show.site.uppercase()} • ${episodes.size} Total Episodes",
                        fontSize = Type.label.fontSize,
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

            // PL-6: the story blurb — every provider already parses it
            // (ShowDetails.synopsis) and the UI dropped it on the floor until
            // now, so this costs ZERO new network. It lives in the drawer
            // header rather than a second sheet because this is exactly where
            // the user decides what to download; collapsed to three lines so
            // it can never push the episode list off-screen.
            if (uiState.drawerSynopsis.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                SynopsisBlock(uiState.drawerSynopsis)
            }

            if (episodes.isNotEmpty()) {
                // 1-Tap Batch Season Selector Chips Row — 8dp gaps (design
                // pass: interactive chips need mis-touch breathing room).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // All Chip
                    val isAllSelected = selectedEpisodes.size == episodes.size
                    FilterChip(
                        selected = isAllSelected,
                        onClick = {
                            selectedEpisodes = if (isAllSelected) emptySet() else episodes.toSet()
                        },
                        label = { Text("All (${episodes.size})", fontSize = Type.caption.fontSize, fontWeight = FontWeight.SemiBold) },
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
                                label = { Text("Season $sNum (${sEps.size})", fontSize = Type.caption.fontSize) },
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
                            label = { Text("Clear (${selectedEpisodes.size})", fontSize = Type.caption.fontSize, color = StatusError) },
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
                        placeholder = { Text("Range (e.g. 1-5, 8, 10)", color = TextMuted, fontSize = Type.caption.fontSize) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
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
                            if (enqueued || isEnqueuing) return@Button
                            // Never silently queue the whole show: an empty
                            // selection is a tap mistake, not a request for
                            // every episode (40+ items, tens of GB on mobile).
                            if (selectedEpisodes.isEmpty()) {
                                Toast.makeText(context, "Select at least one episode first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isEnqueuing = true
                            enqueued = true
                            // Respect the range the user typed: the field feeds
                            // selectedEpisodes, so queue exactly those.
                            val toEnqueue = selectedEpisodes.toList()
                            viewModel.viewModelScope.launch(Dispatchers.Default) {
                                try {
                                    val sorted = toEnqueue.sortedBy { it.episodeNum }
                                    val cleanFolder = com.anonrode.downloader.util.NameSanitizer.cleanShowFolder(show.title)
                                    for (ep in sorted) {
                                        val cleanEpTitle = com.anonrode.downloader.util.NameSanitizer.formatEpisodeTitle(
                                            showTitle = show.title,
                                            episodeNum = ep.episodeNum,
                                            rawEpisodeLabel = ep.title
                                        )
                                        viewModel.engine.enqueue(
                                            showTitle = cleanFolder,
                                            episodeNum = ep.episodeNum,
                                            episodeTitle = cleanEpTitle,
                                            sourceUrl = ep.url,
                                            mirrorUrls = ep.mirrorUrls,
                                            isDirect = false,
                                            backend = "aria2c",
                                            site = ep.site.ifBlank { show.site },
                                            parallelSockets = viewModel.engine.parallelSocketsPerFile,
                                            verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier.verifiedDirect(ep.url)
                                        )
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) {
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        enabled = !isEnqueuing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = BackgroundDark
                        ),
                        shape = RoundedCornerShape(Radius.md),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
                    ) {
                        if (isEnqueuing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = BackgroundDark
                            )
                        } else {
                            Text("Download", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Episodes List
            if (isLoading) {
                // Skeleton rows in the drawer's own 44dp rhythm, with the honest
                // one-line status kept above them (UI research round): a
                // multi-hop locker scrape outlasts a spinner's usefulness, and
                // the copy is what says "this is still working".
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Scraping episode locker streams…",
                        color = TextSecondary,
                        fontSize = Type.label.fontSize
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    EpisodeListSkeleton()
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
                                fontSize = Type.body.fontSize,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text("Close and try again", color = TextMuted, fontSize = Type.caption.fontSize)
                        } else {
                            Text("No stream links found for this title.", color = TextMuted, fontSize = Type.body.fontSize)
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
                    // Combined posts expand into MULTIPLE episodes that share
                    // one anchor URL (RulesPipeline: "same anchor URL reused —
                    // never an invented one"), and episodeNum uniqueness is
                    // only re-stamped on grouped pages — so a url- or
                    // num-only key can still collide and crash LazyColumn
                    // with "Key was already used". Index-composed keys cannot.
                    itemsIndexed(episodes, key = { i, ep -> "${ep.episodeNum}|$i" }) { _, ep ->
                        val isSelected = ep in selectedEpisodes
                        EpisodeRow(
                            episode = ep,
                            isSelected = isSelected,
                            onToggle = {
                                hapticView.tick()
                                selectedEpisodes = if (isSelected) {
                                    selectedEpisodes - ep
                                } else {
                                    selectedEpisodes + ep
                                }
                            },
                            onDownloadSingle = {
                                if (!enqueued && !isEnqueuing) {
                                    enqueued = true
                                    hapticView.confirmHaptic()
                                    viewModel.engine.enqueue(
                                        showTitle = com.anonrode.downloader.util.NameSanitizer.cleanShowFolder(show.title),
                                        episodeNum = ep.episodeNum,
                                        episodeTitle = com.anonrode.downloader.util.NameSanitizer.formatEpisodeTitle(
                                            showTitle = show.title,
                                            episodeNum = ep.episodeNum,
                                            rawEpisodeLabel = ep.title
                                        ),
                                        sourceUrl = ep.url,
                                        mirrorUrls = ep.mirrorUrls,
                                        isDirect = false,
                                        backend = "aria2c",
                                        site = ep.site.ifBlank { show.site },
                                        parallelSockets = viewModel.engine.parallelSocketsPerFile,
                                        verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier.verifiedDirect(ep.url)
                                    )
                                    onDismiss()
                                }
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
                                fontSize = Type.body.fontSize,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ready to queue",
                                color = TextSecondary,
                                fontSize = Type.caption.fontSize
                            )
                        }

                        Button(
                            onClick = {
                                // Double-tap guard: the dismiss animation takes
                                // ~300ms and a second tap would re-enqueue every
                                // selected episode (duplicate tasks, same filePath).
                                if (enqueued || isEnqueuing) return@Button
                                isEnqueuing = true
                                enqueued = true
                                hapticView.confirmHaptic()
                                val toEnqueue = selectedEpisodes.toList()
                                viewModel.viewModelScope.launch(Dispatchers.Default) {
                                    try {
                                        val sorted = toEnqueue.sortedBy { it.episodeNum }
                                        val cleanFolder = com.anonrode.downloader.util.NameSanitizer.cleanShowFolder(show.title)
                                        for (ep in sorted) {
                                            val cleanEpTitle = com.anonrode.downloader.util.NameSanitizer.formatEpisodeTitle(
                                                showTitle = show.title,
                                                episodeNum = ep.episodeNum,
                                                rawEpisodeLabel = ep.title
                                            )
                                            viewModel.engine.enqueue(
                                                showTitle = cleanFolder,
                                                episodeNum = ep.episodeNum,
                                                episodeTitle = cleanEpTitle,
                                                sourceUrl = ep.url,
                                                mirrorUrls = ep.mirrorUrls,
                                                isDirect = false,
                                                backend = "aria2c",
                                                site = ep.site.ifBlank { show.site },
                                                parallelSockets = viewModel.engine.parallelSocketsPerFile,
                                                verifiedDirectUrl = com.anonrode.downloader.pipeline.ResultVerifier.verifiedDirect(ep.url)
                                            )
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            onDismiss()
                                        }
                                    }
                                }
                            },
                            enabled = !isEnqueuing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = BackgroundDark
                            ),
                            shape = RoundedCornerShape(Radius.md)
                        ) {
                            if (isEnqueuing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = BackgroundDark
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text("Queueing...", fontWeight = FontWeight.Bold, fontSize = Type.label.fontSize)
                            } else {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text("Download (${selectedEpisodes.size})", fontWeight = FontWeight.Bold, fontSize = Type.label.fontSize)
                            }
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
    // Feature #26: details preview BEFORE enqueue — an info toggle that shows
    // which locker will serve this episode and the filename its URL carries,
    // so a wrong-quality or dead-looking link is visible before a task is
    // queued. Zero network: every field is parsed from the URL already in
    // hand — the real resolve still happens exactly once, at engine start.
    // rememberSaveable, not remember: rows are disposed as the LazyColumn
    // scrolls, and a plain remember silently collapses the panel the user
    // just opened the moment it recycles off-screen.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (lockerHost, lockerFile) = remember(episode.url) { lockerPreview(episode.url) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (isSelected || expanded) SurfaceCard else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) AccentPrimary.copy(alpha = 0.5f) else BorderHairline.copy(alpha = 0.4f),
                RoundedCornerShape(Radius.md)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                    )
                    // No size() override (design pass): shrinking the box to
                    // 24dp shrank the touch target too; the row's own click
                    // still covers the common path.
                )

                Spacer(modifier = Modifier.width(Spacing.sm))

                Text(
                    text = episode.title,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    fontSize = Type.body.fontSize,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 48dp (was 40) with an 8dp gap to the download button: two
            // adjacent controls below the Android minimum with zero spacing
            // is how a mis-tap enqueues the wrong action.
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "Preview details",
                    tint = if (expanded) AccentPrimary else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

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

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PreviewChip(label = "locker", value = lockerHost)
                    if (lockerFile.isNotBlank()) {
                        PreviewChip(
                            label = "file",
                            value = if (lockerFile.length > 34) lockerFile.takeLast(34) else lockerFile,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                if (episode.sizeText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Site-reported size: ${episode.sizeText}",
                        color = TextSecondary,
                        fontSize = Type.label.fontSize
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Queue now, or check this link on a browser first.",
                    color = TextMuted,
                    fontSize = Type.micro.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Parse (host, file name) from a locker URL without touching the network.
 *  The name comes from the last path segment; query strings (tokens, ?pt=,
 *  &e=…) are never part of the name. */
private fun lockerPreview(url: String): Pair<String, String> {
    val host = try {
        java.net.URI(url.substringBefore('#')).host ?: "—"
    } catch (_: Exception) {
        url.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "—" }
    }
    val seg = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val name = try {
        java.net.URLDecoder.decode(seg, "UTF-8")
    } catch (_: Exception) {
        seg
    }
    return host to name
}

/** PL-6 story blurb: three-line clamp with one expand toggle. The toggle
 *  renders only for text that plausibly overflows the clamp (140 chars ≈
 *  three 12sp/17lh lines on a 360dp phone — a cheap heuristic; a long text
 *  may show the toggle without strictly needing it, never the reverse). The
 *  copy rides the same card language as the rest of the drawer. */
@Composable
private fun SynopsisBlock(synopsis: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Text(
            text = "STORY",
            color = TextMuted,
            fontSize = Type.micro.fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = synopsis,
            color = TextSecondary,
            fontSize = Type.label.fontSize,
            lineHeight = 17.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
        if (synopsis.length > 140) {
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                text = if (expanded) "Show less" else "Read more",
                color = AccentPrimary,
                fontSize = Type.label.fontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable { expanded = !expanded }
                    .padding(vertical = Spacing.xs)
            )
        }
    }
}

@Composable
private fun PreviewChip(label: String, value: String, modifier: Modifier = Modifier) {    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            // SurfaceElevated, not a background token with alpha (design
            // pass): BackgroundDark.copy(0.5f) inverted into a washed-out
            // half-white chip the moment the light theme renders it.
            .background(SurfaceElevated)
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label.uppercase(), color = TextMuted, fontSize = Type.micro.fontSize, fontWeight = FontWeight.Bold)
        Text(
            value,
            color = TextSecondary,
            fontSize = Type.caption.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
