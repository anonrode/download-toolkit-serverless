package com.anonrode.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    var maxConcurrent by remember { mutableIntStateOf(viewModel.engine.maxConcurrentDownloads) }
    var parallelSockets by remember { mutableIntStateOf(viewModel.engine.parallelSocketsPerFile) }
    var quality by remember { mutableStateOf(viewModel.engine.defaultQuality) }
    var autoOrganize by remember { mutableStateOf(viewModel.engine.autoOrganizeByShow) }

    val qualities = listOf("480p", "720p", "1080p")

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
                Text(
                    text = "Download Engine Settings",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

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

            // Parallel Sockets per File
            Text(
                text = "Parallel Sockets per File: $parallelSockets",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Fast CDNs use up to 16 connections; file lockers auto-throttle to 1",
                fontSize = 11.sp,
                color = TextMuted
            )
            Slider(
                value = parallelSockets.toFloat(),
                onValueChange = { parallelSockets = it.toInt() },
                valueRange = 1f..16f,
                steps = 15,
                colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Max Concurrent Downloads
            Text(
                text = "Concurrent Active Downloads: $maxConcurrent",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Slider(
                value = maxConcurrent.toFloat(),
                onValueChange = { maxConcurrent = it.toInt() },
                valueRange = 1f..5f,
                steps = 4,
                colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Preferred Resolution
            Text(
                text = "Preferred Quality",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                qualities.forEach { q ->
                    val isSelected = quality == q
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(if (isSelected) AccentPrimary else SurfaceCard)
                            .border(1.dp, if (isSelected) AccentPrimary else BorderHairline, RoundedCornerShape(Radius.pill))
                            .clickable { quality = q }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    ) {
                        Text(
                            text = q,
                            color = if (isSelected) Color.Black else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Storage Folder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Auto-Organize Shows", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Creates show subfolders in /Download/Anon", fontSize = 11.sp, color = TextMuted)
                }
                Switch(
                    checked = autoOrganize,
                    onCheckedChange = { autoOrganize = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentPrimary)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            Button(
                onClick = {
                    viewModel.saveSettings(maxConcurrent, parallelSockets, quality, autoOrganize)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Save Preferences", fontWeight = FontWeight.Bold)
            }
        }
    }
}
