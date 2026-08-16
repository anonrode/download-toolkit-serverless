package com.anonrode.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$cleanPlatform Video",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = url,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

            Spacer(modifier = Modifier.height(Spacing.md))

            // Format Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                FilterChip(
                    selected = !audioOnly,
                    onClick = { audioOnly = false },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Video (MP4)")
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPrimary,
                        selectedLabelColor = BgBase
                    ),
                    modifier = Modifier.weight(1f)
                )

                FilterChip(
                    selected = audioOnly,
                    onClick = { audioOnly = true },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Audiotrack, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Audio Only (MP3)")
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPrimary,
                        selectedLabelColor = BgBase
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Button(
                onClick = {
                    viewModel.engine.enqueue(
                        showTitle = "Social/$cleanPlatform",
                        episodeNum = 1,
                        episodeTitle = "$cleanPlatform Video",
                        sourceUrl = url,
                        isDirect = false,
                        backend = "yt-dlp",
                        parallelSockets = 1,
                        audioOnly = audioOnly
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BgBase)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text("Download Now", fontWeight = FontWeight.Bold)
            }
        }
    }
}
