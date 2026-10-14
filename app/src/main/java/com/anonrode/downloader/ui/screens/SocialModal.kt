package com.anonrode.downloader.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.components.SegmentBar
import com.anonrode.downloader.ui.components.SegmentCell
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.ui.util.pressScale
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
    // Seed from the engine so an already-enabled instant setting is reflected;
    // the engine field is the source of truth for both this modal and QuickShare.
    var alwaysInstant by remember { mutableStateOf(viewModel.engine.instantSocialDownload) }
    // Guards against double-tap re-enqueue during the dismissal animation.
    var enqueued by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            // ---- Identity: WHO is downloading (mirrors the rebuilt
            //      QuickShare sheet — ink-on-accent mark, no violet badge). ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(AccentPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = BackgroundDark,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Anon Downloader",
                        fontSize = Type.rowTitle.fontSize,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Shared link · $cleanPlatform",
                        fontSize = Type.caption.fontSize,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(44.dp)
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

            Spacer(modifier = Modifier.height(Spacing.md))

            // ---- Media: the shared artifact, URL shown as data ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.lg))
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(SurfaceElevated)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(Radius.sm)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cleanPlatform.take(2).uppercase(),
                        fontSize = Type.label.fontSize,
                        fontWeight = FontWeight.Black,
                        color = TextSecondary
                    )
                }
                Text(
                    text = url,
                    fontSize = Type.micro.fontSize,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // ---- Format ----
            SegmentBar {
                SegmentCell(
                    selected = !audioOnly,
                    onClick = { audioOnly = false },
                    icon = Icons.Rounded.Videocam,
                    label = "Video (MP4)",
                    modifier = Modifier.weight(1f)
                )
                SegmentCell(
                    selected = audioOnly,
                    onClick = { audioOnly = true },
                    icon = Icons.Rounded.Audiotrack,
                    label = "Audio (MP3)",
                    modifier = Modifier.weight(1f)
                )
            }

            // ---- Quality (video only) ----
            AnimatedVisibility(
                visible = !audioOnly,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SegmentBar {
                        listOf("480p", "720p", "1080p", "Best").forEach { q ->
                            SegmentCell(
                                selected = selectedQuality == q,
                                onClick = { selectedQuality = q },
                                label = q,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // ---- Instant: the skip-this-dialog switch ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { alwaysInstant = !alwaysInstant }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download instantly",
                        fontSize = Type.body.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Skip this dialog next time",
                        fontSize = Type.caption.fontSize,
                        color = TextMuted
                    )
                }
                Switch(
                    checked = alwaysInstant,
                    onCheckedChange = { alwaysInstant = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BackgroundDark,
                        checkedTrackColor = StatusSuccess
                    )
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // ---- The one action ----
            val dlSrc = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    if (enqueued) return@Button
                    enqueued = true
                    // Sync BOTH directions, persisting through the engine
                    // (which writes the pref). The old one-way `if
                    // (alwaysInstant)` meant a checked-then-unchecked box
                    // silently left a previously-enabled instant mode ON —
                    // the uncheck "vanished" and the next share skipped this
                    // dialog entirely.
                    if (alwaysInstant != viewModel.engine.instantSocialDownload) {
                        viewModel.engine.setInstantSocial(alwaysInstant)
                    }
                    viewModel.engine.enqueue(
                        showTitle = "Social/$cleanPlatform",
                        episodeNum = 1,
                        episodeTitle = "$cleanPlatform Video",
                        sourceUrl = url,
                        isDirect = false,
                        backend = "yt-dlp",
                        parallelSockets = viewModel.engine.parallelSocketsPerFile,
                        audioOnly = audioOnly,
                        quality = if (audioOnly) null else selectedQuality
                    )
                    onDismiss()
                },
                enabled = !enqueued,
                interactionSource = dlSrc,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .pressScale(dlSrc),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BackgroundDark)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = if (audioOnly) "Download audio" else "Download ${selectedQuality}",
                    fontWeight = FontWeight.Bold,
                    fontSize = Type.sectionTitle.fontSize
                )
            }
        }
    }
}
