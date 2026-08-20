package com.anonrode.downloader.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
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
import com.anonrode.downloader.ui.components.MediaPlayerModal
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel
import java.io.File

@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val tasks by viewModel.engine.tasks.collectAsState()
    val context = LocalContext.current
    var activePlaybackTask by remember { mutableStateOf<DownloadTask?>(null) }

    activePlaybackTask?.let { task ->
        MediaPlayerModal(
            filePath = task.filePath,
            title = task.episodeTitle,
            onDismiss = { activePlaybackTask = null }
        )
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

            Column {
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
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

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
                items(tasks, key = { it.id }) { task ->
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

            val targetProgress = when {
                task.totalBytes > 0 -> (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
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
                                .padding(horizontal = Spacing.sm, vertical = 2.dp)
                        ) {
                            Text(text = extText, color = TextPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
            } else {
                // ---- ACTIVE / QUEUED / PAUSED / FAILED ----
                // Seal-Style Visual Segment Progress Bar (frozen at 0 for QUEUED)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(SurfaceElevated)
                ) {
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
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                val sizeStr = if (task.totalBytes > 0) {
                    formatBytes(task.downloadedBytes) + " / " + formatBytes(task.totalBytes)
                } else if (task.downloadedBytes > 100) {
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
                val estimating = task.status == TaskStatus.DOWNLOADING && task.speedBytesPerSec < 1024.0 && task.totalBytes > task.downloadedBytes

                val progressText = when (task.status) {
                    TaskStatus.QUEUED -> "Queued • Waiting to start"
                    TaskStatus.RESOLVING -> "Resolving stream link..."
                    TaskStatus.VALIDATING -> "Checking downloaded file..."
                    TaskStatus.PAUSED -> {
                        if (task.totalBytes > 0) "Paused at $pctInt% • $sizeStr"
                        else if (sizeStr.isNotBlank()) "Paused at $sizeStr"
                        else "Paused"
                    }
                    TaskStatus.DOWNLOADING -> {
                        // With an unknown total (e.g. HLS) show size + speed, no percentage.
                        val parts = mutableListOf<String>()
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
            .padding(horizontal = Spacing.sm, vertical = 2.dp)
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
