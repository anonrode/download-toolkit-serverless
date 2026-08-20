package com.anonrode.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialModal(
    platform: String,
    url: String,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    var audioOnly by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf(viewModel.engine.defaultQuality) }
    var alwaysInstant by remember { mutableStateOf(false) }

    val cleanPlatform = platform.replace(Regex("(?i)video"), "").trim().ifEmpty { "Social" }

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
            // Header with badge & Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .background(AccentViolet.copy(alpha = 0.15f), RoundedCornerShape(Radius.sm))
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    ) {
                        Text(
                            text = cleanPlatform,
                            color = AccentViolet,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Quick Download",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
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

            Spacer(modifier = Modifier.height(Spacing.sm))

            // URL Preview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                    .padding(Spacing.md)
            ) {
                Text(
                    text = url,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Unified Segmented Control (Video vs Audio)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.full))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.full))
                    .padding(Spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                // Video Tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.full))
                        .background(if (!audioOnly) AccentPrimary else Color.Transparent)
                        .clickable { audioOnly = false }
                        .padding(vertical = Spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = null,
                            tint = if (!audioOnly) BackgroundDark else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Video (MP4)",
                            fontSize = 12.5.sp,
                            fontWeight = if (!audioOnly) FontWeight.Bold else FontWeight.Normal,
                            color = if (!audioOnly) BackgroundDark else TextSecondary
                        )
                    }
                }

                // Audio Tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.full))
                        .background(if (audioOnly) AccentPrimary else Color.Transparent)
                        .clickable { audioOnly = true }
                        .padding(vertical = Spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Audiotrack,
                            contentDescription = null,
                            tint = if (audioOnly) BackgroundDark else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Audio Only (MP3)",
                            fontSize = 12.5.sp,
                            fontWeight = if (audioOnly) FontWeight.Bold else FontWeight.Normal,
                            color = if (audioOnly) BackgroundDark else TextSecondary
                        )
                    }
                }
            }

            if (!audioOnly) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                // Quality Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    listOf("480p", "720p", "1080p", "Best").forEach { q ->
                        val isSel = selectedQuality.equals(q, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(if (isSel) AccentViolet else SurfaceCard)
                                .border(1.dp, if (isSel) AccentViolet else BorderHairline, RoundedCornerShape(Radius.sm))
                                .clickable { selectedQuality = q }
                                .padding(vertical = Spacing.sm),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = q,
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Remember instant download toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { alwaysInstant = !alwaysInstant },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = alwaysInstant,
                    onCheckedChange = { alwaysInstant = it },
                    colors = CheckboxDefaults.colors(checkedColor = AccentViolet)
                )
                Text(
                    text = "Always download instantly (skip this dialog)",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            Button(
                onClick = {
                    if (alwaysInstant) {
                        viewModel.engine.instantSocialDownload = true
                    }
                    viewModel.engine.enqueue(
                        showTitle = "Social/$cleanPlatform",
                        episodeNum = 1,
                        episodeTitle = "$cleanPlatform Video",
                        sourceUrl = url,
                        isDirect = false,
                        backend = "yt-dlp",
                        parallelSockets = viewModel.engine.parallelSocketsPerFile,
                        audioOnly = audioOnly
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BackgroundDark)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text("Download Now", fontWeight = FontWeight.Bold)
            }
        }
    }
}
