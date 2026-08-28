package com.anonrode.downloader.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.ui.components.DownloadsSorter
import com.anonrode.downloader.ui.components.MediaPlayerContext
import com.anonrode.downloader.ui.components.MediaPlayerModal
import com.anonrode.downloader.ui.components.downloadsStats
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel
import java.io.File

private const val PREF_SORT = "downloader_settings"
private const val PREF_SORT_KEY = "pref_downloads_sort"

@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val tasks by viewModel.engine.tasks.collectAsState()
    val context = LocalContext.current
    var activePlaybackTask by remember { mutableStateOf<DownloadTask?>(null) }

    // Read the persisted sort mode at composition. Default is "date" so
    // a fresh install matches the prototype's default tab.  A missing key
    // is treated the same as "date" so an upgrade from the old build lands
    // in the familiar date-grouped view.
    val initialSort = remember {
        val raw = context.getSharedPreferences(PREF_SORT, Context.MODE_PRIVATE)
            .getString(PREF_SORT_KEY, DownloadsSorter.SORT_DATE)
        if (raw != null && raw in DownloadsSorter.ALL_MODES) raw else DownloadsSorter.SORT_DATE
    }
    var sortMode by remember { mutableStateOf(initialSort) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // The Next/Previous queue the player steps through: every COMPLETED
    // task's path, in screen order. Keyed on a cheap structural signature
    // (the paths themselves) instead of the task list, because the list
    // re-emits on every progress tick while the completed set only changes
    // when a download finishes or is deleted. No File.exists() here — that
    // was disk I/O on the main thread on EVERY tick; the player now skips
    // missing peers itself when stepping.
    val completedSig = remember(tasks) {
        buildString {
            tasks.forEach { if (it.status == TaskStatus.COMPLETED) append(it.filePath).append('\n') }
        }
    }
    val completedQueuePaths = remember(completedSig) {
        viewModel.engine.tasks.value
            .filter { it.status == TaskStatus.COMPLETED }
            .map { it.filePath }
    }

    activePlaybackTask?.let { task ->
        MediaPlayerModal(
            ctx = MediaPlayerContext(
                filePath = task.filePath,
                title = task.episodeTitle,
                queuePeerPaths = completedQueuePaths,
                onPlayFile = { path ->
                    // Read the freshest snapshot (NOT the composed `tasks`,
                    // which can lag) and never null the state on a miss —
                    // a miss used to close the player mid-Next.
                    viewModel.engine.tasks.value
                        .firstOrNull { it.filePath == path }
                        ?.let { activePlaybackTask = it }
                }
            ),
            onDismiss = { activePlaybackTask = null }
        )
    }

    // Build age-override map so "Date added" groups use the task's position
    // in engine.tasks (newer = tail) as a proxy for recency. The data class
    // has no enqueue timestamp; this preserves the visual grouping without
    // touching the model.
    //
    // Keyed on a structural signature (id:status per row) rather than the
    // task list itself: progress ticks mutate downloadedBytes constantly but
    // never the structure, so the sort + grouping no longer re-runs several
    // times per second during downloads.
    val structureSig = remember(tasks) {
        buildString { tasks.forEach { append(it.id).append(':').append(it.status.name).append('|') } }
    }
    val groups = remember(structureSig, sortMode) {
        val snapshot = viewModel.engine.tasks.value
        if (snapshot.isEmpty()) emptyList()
        else {
            // Re-seed age overrides whenever the structure actually changes;
            // ids no longer present are dropped by the next clear.
            DownloadsSorter.clearAgeOverrides()
            snapshot.forEachIndexed { index, task ->
                // 14 days for the oldest entry, 0 for the newest, 1-day
                // increments in between. Buckets fall out of those values
                // without any clock dependency.
                val daysAgo = ((snapshot.size - 1 - index).toLong()).coerceAtLeast(0L)
                DownloadsSorter.setAgeOverride(task.id, daysAgo)
            }
            DownloadsSorter.sortDownloads(snapshot, sortMode)
        }
    }

    // The sort above is keyed on structure (ids + statuses) so it doesn't
    // re-run on every progress tick — but that also freezes the task OBJECTS
    // captured in the snapshot, so cards would render stale byte counts
    // between structural changes (the "notification moves, app doesn't"
    // bug). Remap the grouped ids onto the freshest objects each
    // composition: O(n) map, no re-sort, no age-override churn.
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val liveGroups = remember(groups, tasks) {
        groups.map { (header, items) -> header to items.mapNotNull { tasksById[it.id] } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
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
                    text = "DOWNLOADS & MEDIA",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${tasks.size} Total Items",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            // Sort menu: four modes from the prototype, persisted in
            // SharedPreferences. Tapping a mode writes the pref and
            // re-groups the list immediately.
            Box {
                SortChip(
                    label = sortModeLabel(sortMode),
                    onClick = { sortMenuOpen = true }
                )
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    DownloadsSorter.ALL_MODES.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = sortModeLabel(mode),
                                    fontWeight = if (mode == sortMode) FontWeight.Bold else FontWeight.Normal,
                                    color = if (mode == sortMode) AccentPrimary else TextPrimary
                                )
                            },
                            onClick = {
                                sortMode = mode
                                sortMenuOpen = false
                                context.getSharedPreferences(PREF_SORT, Context.MODE_PRIVATE)
                                    .edit().putString(PREF_SORT_KEY, mode).apply()
                            }
                        )
                    }
                }
            }
        }

        // Stats strip: only on the populated state; the empty state has
        // its own centred message instead.
        if (tasks.isNotEmpty()) {
            StatsStrip(tasks = tasks)
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text("No active or completed downloads", color = TextSecondary, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.fillMaxSize()
            ) {
                liveGroups.forEach { (header, items) ->
                    item(key = "h-$header") {
                        Text(
                            text = "$header · ${items.size}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xxs)
                        )
                    }
                    items(items, key = { it.id }) { task ->
                        DownloadCard(
                            task = task,
                            context = context,
                            onPlay = { activePlaybackTask = task },
                            onPause = { viewModel.engine.pause(task.id) },
                            onRetry = { viewModel.engine.retry(task.id) },
                            onCancel = { viewModel.engine.cancel(task.id) }
                        )
                    }
                }
            }
        }
    }
}

private fun sortModeLabel(mode: String): String = when (mode) {
    DownloadsSorter.SORT_LIBRARY -> "By show (Library)"
    DownloadsSorter.SORT_STATUS -> "By status"
    DownloadsSorter.SORT_SIZE -> "By size"
    else -> "Date added"
}

@Composable
private fun SortChip(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.full))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.full))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(
            text = "▾",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatsStrip(tasks: List<DownloadTask>) {
    val stats = remember(tasks) { downloadsStats(tasks) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        StatChip(label = "files", value = stats.files)
        StatChip(label = "done", value = stats.done)
        StatChip(label = "active", value = stats.active)
        // Failed chip only renders when there are failures — a zero
        // failure count is the expected steady state, not interesting.
        if (stats.failed > 0) {
            StatChip(label = "failed", value = stats.failed, accentError = true)
        }
    }
}

@Composable
private fun StatChip(label: String, value: Int, accentError: Boolean = false) {
    val accent = if (accentError) StatusError else AccentPrimary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(SurfaceElevated)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.xs))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    ) {
        Text(
            text = value.toString(),
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
fun DownloadCard(
    task: DownloadTask,
    context: Context,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val isCompleted = task.status == TaskStatus.COMPLETED
    val isDownloading = task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.RESOLVING
    val isPaused = task.status == TaskStatus.PAUSED
    val isFailed = task.status == TaskStatus.FAILED

    // FAILED cards get a subtle error tint on the surface + border, so they read
    // as failed at a glance (not just via the badge).
    val cardSurface = if (isFailed) StatusError.copy(alpha = 0.06f) else SurfaceCard
    val cardBorder = if (isFailed) StatusError.copy(alpha = 0.45f) else BorderHairline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(cardSurface)
            .border(1.dp, cardBorder, RoundedCornerShape(Radius.lg))
            .clickable(enabled = isCompleted, onClick = onPlay)
            .padding(Spacing.lg)
    ) {
        Column {
            // Title & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.episodeTitle,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        text = if (task.showTitle != "Direct Downloads") task.showTitle else "Direct Media",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                StatusBadge(status = task.status)
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            val totalKnown = task.totalBytes > 0
            val isDownloadingNow = task.status == TaskStatus.DOWNLOADING

            val targetProgress = when {
                totalKnown -> (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
                task.downloadedBytes in 1..100 -> (task.downloadedBytes.toFloat() / 100f).coerceIn(0f, 1f)
                else -> 0f
            }
            val animatedProgress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = 300),
                label = "progress"
            )
            val pctInt = (animatedProgress * 100).toInt()

            if (isCompleted) {
                // ---- COMPLETED: ext chip + size + Play / Share / Delete ----
                val sizeText = if (task.totalBytes > 0) formatBytes(task.totalBytes) else formatBytes(task.downloadedBytes)
                val extText = File(task.filePath).name.substringAfterLast('.').uppercase()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.xs))
                                .background(SurfaceElevated)
                                .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                        ) {
                            Text(text = extText, color = TextPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        // Quality/resolution chip: the real stream resolution
                        // for HLS (parsed from the master's RESOLUTION during
                        // preflight), the requested quality otherwise.
                        val qualityLabel = task.resolution ?: task.quality
                        if (qualityLabel != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.xs))
                                    .background(SurfaceElevated)
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                            ) {
                                Text(text = qualityLabel, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(
                            text = "$sizeText • Tap to Play In-App",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        IconButton(
                            onClick = onPlay,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AccentPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = BackgroundDark, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(
                            onClick = { shareMedia(context, task.filePath) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated)
                                    .border(1.dp, BorderHairline, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "Share", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated)
                                    .border(1.dp, BorderHairline, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Honest verification note (if any): the engine could not fully
                // prove the file (unknown container verified by decoder, size
                // vs stream estimate, or nothing at all) — amber tells the user
                // to check it plays before keeping.
                if (task.validationNote != null) {
                    Text(
                        text = task.validationNote,
                        color = StatusWarning,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.xs, top = 2.dp)
                    )
                }
            } else {
                // ---- ACTIVE / QUEUED / PAUSED / FAILED ----
                // Seal-Style Visual Segment Progress Bar (frozen at 0 for QUEUED;
                // indeterminate sweep while downloading with an unknown total)
                // Unknown-total + any in-flight state: indeterminate sweep so
                // the user has a visual signal during the engine's RESOLVING
                // phase and during yt-dlp / CDN downloads that don't emit a
                // percentage.  PAUSED / COMPLETED / FAILED fall through to the
                // static-fill branch (sweep would lie about progress).
                val indeterminate = !totalKnown && task.downloadedBytes > 0 &&
                    !isPaused && !isCompleted && !isFailed
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(SurfaceElevated)
                ) {
                    if (!indeterminate) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = animatedProgress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(Radius.xs))
                                .background(
                                    if (isFailed) Brush.horizontalGradient(listOf(StatusError, StatusError))
                                    else Brush.horizontalGradient(listOf(AccentViolet, AccentPink, AccentPrimary))
                                )
                        )
                    } else {
                        // Indeterminate sweep (only reachable while DOWNLOADING
                        // with an unknown total — CDN without Content-Length,
                        // HLS before the variant is measured): a dead 0% bar
                        // next to "100.7 MB" told the user nothing, so a sliding
                        // segment signals "in flight" without pretending to know
                        // a percentage.
                        val sweepWidth = 64.dp
                        val sweep by rememberInfiniteTransition(label = "indeterminate").animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
                            label = "indeterminateValue"
                        )
                        Box(
                            modifier = Modifier
                                .width(sweepWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(Radius.xs))
                                .offset(x = (maxWidth - sweepWidth) * sweep)
                                .background(Brush.horizontalGradient(listOf(AccentViolet, AccentPink, AccentPrimary)))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Show byte progress as soon as ANY bytes have landed.  The
                // old 100-byte threshold left a 100-byte dead zone at the
                // start of every download (the card would show "Starting..."
                // until the engine's updateProgress crossed 100 bytes,
                // which on slow CDN links took several seconds and read
                // as a hang).  For unknown-total downloads we only show
                // the downloaded count, not "0 B / 0 B".
                val sizeStr = if (task.totalBytes > 0) {
                    // Clamp the downloaded side to the total: aria2c's no-total
                    // wire bytes can overshoot the inferred total (re-downloaded
                    // pieces + chunk overhead), and "100.7 MB / 84.1 MB" read
                    // as a bug. The progress bar already clamps to 100%.
                    formatBytes(minOf(task.downloadedBytes, task.totalBytes)) + " / " + formatBytes(task.totalBytes)
                } else if (task.downloadedBytes > 0) {
                    formatBytes(task.downloadedBytes)
                } else {
                    ""
                }

                // Speed is only rendered while the task is actually transferring;
                // paused/queued cards never show a stale "0.0 MB/s".
                val speedMb = task.speedBytesPerSec / (1024.0 * 1024.0)
                val speedStr = when {
                    speedMb >= 0.1 -> "%.1f MB/s".format(java.util.Locale.US, speedMb)
                    task.speedBytesPerSec >= 1024.0 -> "%.0f KB/s".format(java.util.Locale.US, task.speedBytesPerSec / 1024.0)
                    else -> null
                }
                val etaStr = if (task.etaSeconds > 0) formatEta(task.etaSeconds) else null
                // "Estimating..." applies to any in-flight state where we
                // have downloaded bytes but no total + no ETA.  Previously
                // this was gated on (status==DOWNLOADING && speed<1024),
                // which suppressed the label during the engine's 18s
                // of fallback attempts (RESOLVING) and during the first
                // 2s of fast downloads where the speed sample hadn't
                // landed yet.  Showing "Estimating..." the moment any
                // bytes have arrived gives the user a signal that the
                // download is in flight even when the backend (yt-dlp
                // on a segmented CDN) can't emit a percentage.
                val estimating = task.downloadedBytes > 0 &&
                    task.status != TaskStatus.PAUSED &&
                    task.status != TaskStatus.COMPLETED &&
                    (task.totalBytes > task.downloadedBytes || task.totalBytes <= 0L)

                val progressText = when (task.status) {
                    TaskStatus.QUEUED -> "Queued • Waiting to start"
                    TaskStatus.RESOLVING -> {
                        // Bytes already landing during the engine's fallback
                        // chain (aria2c -> turbo -> yt-dlp) while the status
                        // is still RESOLVING: surface the real byte count
                        // instead of a stale "Resolving stream link..." that
                        // reads as a hang (dramakey/HLS symptom).
                        if (estimating && sizeStr.isNotBlank()) "$sizeStr • Estimating..."
                        else "Resolving stream link..."
                    }
                    TaskStatus.VALIDATING -> "Checking downloaded file..."
                    TaskStatus.PAUSED -> {
                        if (task.totalBytes > 0) "Paused at $pctInt% • $sizeStr"
                        else if (sizeStr.isNotBlank()) "Paused at $sizeStr"
                        else "Paused"
                    }
                    TaskStatus.DOWNLOADING -> {
                        // With an unknown total (e.g. HLS) show size + speed, no percentage.
                        val parts = mutableListOf<String>()
                        // Real stream resolution first when known (HLS masters
                        // carry RESOLUTION; the requested quality falls back
                        // only when resolution was never parsed).
                        (task.resolution ?: task.quality)?.let { parts += it }
                        if (task.totalBytes > 0) parts += "$pctInt%"
                        if (sizeStr.isNotBlank()) parts += sizeStr
                        speedStr?.let { parts += it }
                        etaStr?.let { parts += it }
                        if (estimating && etaStr == null) parts += "Estimating..."
                        parts.joinToString(" • ").ifBlank { "Starting..." }
                    }
                    TaskStatus.FAILED -> task.errorMessage ?: "Download failed"
                    else -> "Queued • Waiting to start"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = progressText,
                        color = when {
                            isFailed -> StatusError
                            task.status == TaskStatus.RESOLVING || task.status == TaskStatus.VALIDATING -> AccentPrimary
                            else -> TextMuted
                        },
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (isDownloading) {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }
                        } else if (isPaused || isFailed) {
                            IconButton(onClick = onRetry) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(AccentPrimary)
                                ) {
                                    Icon(
                                        imageVector = if (isFailed) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isFailed) "Retry" else "Resume",
                                        tint = BackgroundDark,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .align(Alignment.Center)
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = TextMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: TaskStatus) {
    val (text, bg, fg) = when (status) {
        TaskStatus.QUEUED -> Triple("QUEUED", SurfaceElevated, TextMuted)
        TaskStatus.RESOLVING -> Triple("RESOLVING", SurfaceElevated, AccentPrimary)
        TaskStatus.DOWNLOADING -> Triple("DOWNLOADING", StatusSuccess.copy(alpha = 0.15f), StatusSuccess)
        TaskStatus.VALIDATING -> Triple("CHECKING", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        TaskStatus.PAUSED -> Triple("PAUSED", SurfaceElevated, TextSecondary)
        TaskStatus.COMPLETED -> Triple("DONE", StatusSuccess.copy(alpha = 0.15f), StatusSuccess)
        TaskStatus.FAILED -> Triple("FAILED", StatusError.copy(alpha = 0.15f), StatusError)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.full))
            .background(bg)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
    ) {
        Text(text = text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

fun shareMedia(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) return
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "mp3", "m4a", "aac", "wav", "flac", "opus", "ogg" -> "audio/*"
            "mp4", "mkv", "avi", "mov", "webm", "ts" -> "video/*"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            else -> "*/*"
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Share Media"))
    } catch (_: Exception) {}
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(java.util.Locale.US, gb)
        mb >= 1.0 -> "%.1f MB".format(java.util.Locale.US, mb)
        kb >= 1.0 -> "%.1f KB".format(java.util.Locale.US, kb)
        else -> "$bytes B"
    }
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return ""
    val m = seconds / 60
    val s = seconds % 60
    val h = m / 60
    return if (h > 0) {
        "%02d:%02d:%02d left".format(java.util.Locale.US, h, m % 60, s)
    } else {
        "%02d:%02d left".format(java.util.Locale.US, m, s)
    }
}
