package com.anonrode.downloader.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.core.content.FileProvider
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel
import java.io.File

@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val tasks by viewModel.engine.tasks.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0: Active, 1: Completed

    val activeTasks = remember(tasks) {
        tasks.filter { it.status != TaskStatus.COMPLETED }
    }
    val completedTasks = remember(tasks) {
        tasks.filter { it.status == TaskStatus.COMPLETED }
    }

    val context = LocalContext.current

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
                        .size(36.dp)
                        .background(SurfaceCard, CircleShape)
                        .border(1.dp, BorderHairline, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                Text(
                    text = "Downloads Hub",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // Storage Status Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                    .padding(Spacing.md)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SdCard,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = "Device Storage",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "${uiState.freeStorageGb} GB Free / ${uiState.totalStorageGb} GB",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Tabs (Active / Completed)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceCard)
                    .padding(Spacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(if (selectedTab == 0) SurfaceElevated else Color.Transparent)
                        .clickable { selectedTab = 0 }
                        .padding(vertical = Spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Active (${activeTasks.size})",
                        color = if (selectedTab == 0) TextPrimary else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(if (selectedTab == 1) SurfaceElevated else Color.Transparent)
                        .clickable { selectedTab = 1 }
                        .padding(vertical = Spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Completed (${completedTasks.size})",
                        color = if (selectedTab == 1) TextPrimary else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            val currentList = if (selectedTab == 0) activeTasks else completedTasks

            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedTab == 0) "No active downloads" else "No completed downloads yet",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    contentPadding = PaddingValues(bottom = Spacing.xxl),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(currentList, key = { it.id }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onPause = { viewModel.engine.pause(task.id) },
                            onCancel = { viewModel.engine.cancel(task.id) },
                            onRetry = { viewModel.engine.retry(task.id) },
                            onPlay = {
                                val file = File(task.filePath)
                                if (file.exists()) {
                                    try {
                                        val uri: Uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "video/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No video player installed", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "File does not exist on disk", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit
) {
    val progress = if (task.totalBytes > 0) task.downloadedBytes.toFloat() / task.totalBytes.toFloat() else 0f
    val speedMb = task.speedBytesPerSec / (1024 * 1024)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.episodeTitle,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(Spacing.sm))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (task.status) {
                        TaskStatus.COMPLETED -> {
                            IconButton(
                                onClick = onPlay,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(StatusSuccess.copy(alpha = 0.2f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play",
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        TaskStatus.FAILED -> {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(SurfaceElevated, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Retry",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        TaskStatus.DOWNLOADING -> {
                            IconButton(
                                onClick = onPause,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(SurfaceElevated, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = "Pause",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        TaskStatus.PAUSED -> {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(SurfaceElevated, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Resume",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        TaskStatus.QUEUED -> {
                            Text("Queued", color = TextMuted, fontSize = 11.sp)
                        }
                        TaskStatus.RESOLVING -> {
                            Text("Resolving...", color = TextSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.width(Spacing.xs))

                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            if (task.status == TaskStatus.FAILED) {
                Text(
                    text = "Failed: ${task.errorMessage ?: "Download failed"}",
                    color = StatusError,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    color = if (task.status == TaskStatus.COMPLETED) StatusSuccess else AccentPrimary,
                    trackColor = Color(0xFF1E2330),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )

                Spacer(modifier = Modifier.height(Spacing.xs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (task.status == TaskStatus.COMPLETED) "Completed" else "${(progress * 100).toInt()}%",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    if (task.status == TaskStatus.DOWNLOADING && speedMb > 0.05) {
                        Text(
                            text = String.format("%.1f MB/s", speedMb),
                            color = StatusSuccess,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
