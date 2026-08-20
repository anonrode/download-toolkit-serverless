package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.viewmodel.MainViewModel
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: MainViewModel,
    themeMode: String = "dark",
    onThemeChanged: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentThemeMode by remember(themeMode) { mutableStateOf(themeMode) }
    var maxConcurrent by remember { mutableStateOf(viewModel.engine.maxConcurrentDownloads) }
    var sockets by remember { mutableStateOf(viewModel.engine.parallelSocketsPerFile) }
    var quality by remember { mutableStateOf(viewModel.engine.defaultQuality) }
    var autoOrganize by remember { mutableStateOf(viewModel.engine.autoOrganizeByShow) }
    var instantSocial by remember { mutableStateOf(viewModel.engine.instantSocialDownload) }
    var wifiOnlyTorrents by remember { mutableStateOf(viewModel.engine.downloadTorrentsWifiOnly) }
    var showPosters by remember { mutableStateOf(viewModel.engine.showPostersInResults) }
    var storageGuard by remember { mutableStateOf(viewModel.engine.storageGuardGb.toFloat()) }

    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var isSyncingRules by remember { mutableStateOf(false) }

    val rulesVersion by DynamicRulesManager.version.collectAsState()

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
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
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

            Spacer(modifier = Modifier.height(Spacing.md))

            // SECTION 1: Self-Healing & Over-The-Air (GitHub)
            SettingsCategoryHeader(title = "Self-Healing & Core Updates")
            SettingsCard {
                // Scraper Rules Sync
                SettingsActionRow(
                    icon = Icons.Rounded.CloudDownload,
                    title = "Sync Scraper & Site Logic",
                    subtitle = "Version: $rulesVersion",
                    action = {
                        if (isSyncingRules) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentPrimary)
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    isSyncingRules = true
                                    scope.launch {
                                        val (ok, ver) = DynamicRulesManager.syncFromGitHub(context)
                                        isSyncingRules = false
                                        if (ok) {
                                            Toast.makeText(context, "Synced fresh logic: $ver", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Sync error: $ver", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceElevated, contentColor = AccentPrimary)
                            ) {
                                Text("Sync Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // yt-dlp Core Binary Updater
                SettingsActionRow(
                    icon = Icons.Rounded.Refresh,
                    title = "yt-dlp Core Engine",
                    subtitle = "Over-the-air extractor fixes for IG/TikTok/FB",
                    action = {
                        if (isUpdatingYtDlp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentPrimary)
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    isUpdatingYtDlp = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                                            withContext(Dispatchers.Main) {
                                                isUpdatingYtDlp = false
                                                Toast.makeText(context, "Core update: $status", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (t: Throwable) {
                                            withContext(Dispatchers.Main) {
                                                isUpdatingYtDlp = false
                                                Toast.makeText(context, "Core is up to date", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceElevated, contentColor = AccentPrimary)
                            ) {
                                Text("Update Core", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // SECTION: Appearance & Theme
            SettingsCategoryHeader(title = "Appearance & Theme")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(SurfaceElevated, RoundedCornerShape(Radius.sm)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (currentThemeMode) {
                                    "light" -> Icons.Rounded.LightMode
                                    "dark" -> Icons.Rounded.DarkMode
                                    else -> Icons.Rounded.BrightnessAuto
                                },
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "App Theme",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = when (currentThemeMode) {
                                    "light" -> "Light Mode"
                                    "dark" -> "Dark Mode (OLED)"
                                    else -> "System Default"
                                },
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        listOf("Dark" to "dark", "Light" to "light", "Auto" to "system").forEach { (label, mode) ->
                            val isSel = currentThemeMode.equals(mode, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(if (isSel) AccentPrimary else SurfaceElevated)
                                    .clickable {
                                        currentThemeMode = mode
                                        onThemeChanged(mode)
                                    }
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) (if (AnonTheme.colors.isDark) Color.Black else Color.White) else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // SECTION 2: General & Automation
            SettingsCategoryHeader(title = "General & Automation")
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Auto-Organize Folders",
                    subtitle = "Creates /Download/Anon/<ShowName>/ structure",
                    checked = autoOrganize,
                    onCheckedChange = { autoOrganize = it }
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                SettingsSwitchRow(
                    icon = Icons.Rounded.FlashOn,
                    title = "Instant Social Download",
                    subtitle = "1-tap download when shared from Instagram/TikTok",
                    checked = instantSocial,
                    onCheckedChange = { instantSocial = it }
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                SettingsSwitchRow(
                    icon = Icons.Rounded.Image,
                    title = "Show Posters in Results",
                    subtitle = "Off saves data: results show a lettered tile instead",
                    checked = showPosters,
                    onCheckedChange = {
                        showPosters = it
                        // Applied immediately (its own persisted setter), so the
                        // next search reflects it without waiting for Save.
                        viewModel.engine.setShowPosters(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // SECTION 3: Engine & Multi-Socket Networking
            SettingsCategoryHeader(title = "Engine & Performance")
            SettingsCard {
                // Aria2 Sockets Slider
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("Aria2c Parallel Sockets", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("High-speed segmented CDN connections", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text("$sockets conns", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }

                    Slider(
                        value = sockets.toFloat(),
                        onValueChange = { sockets = it.toInt() },
                        valueRange = 1f..16f,
                        steps = 15,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // Max Concurrent Tasks
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Layers, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("Max Concurrent Downloads", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Parallel batch download queue limit", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text("$maxConcurrent tasks", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }

                    Slider(
                        value = maxConcurrent.toFloat(),
                        onValueChange = { maxConcurrent = it.toInt() },
                        valueRange = 1f..5f,
                        steps = 3,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // Storage Guard
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Security, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("Storage Protection Guard", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Auto-pause downloads if disk free space is low", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text("${String.format("%.1f", storageGuard)} GB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }

                    Slider(
                        value = storageGuard,
                        onValueChange = { storageGuard = it },
                        valueRange = 0.5f..5.0f,
                        steps = 9,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // Wi-Fi Only for Torrents
                SettingsSwitchRow(
                    icon = Icons.Rounded.Wifi,
                    title = "Download Torrents Only on Wi-Fi",
                    subtitle = "Protects cellular mobile data balance",
                    checked = wifiOnlyTorrents,
                    onCheckedChange = { wifiOnlyTorrents = it }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // SECTION 4: Media & Formats
            SettingsCategoryHeader(title = "Media & Quality Formats")
            SettingsCard {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Column {
                            Text("Preferred Stream Resolution", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Default video quality for drama & anime streams", fontSize = 11.sp, color = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        listOf("480p", "720p", "1080p").forEach { q ->
                            val isSel = quality == q
                            FilterChip(
                                selected = isSel,
                                onClick = { quality = q },
                                label = { Text(q, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentPrimary,
                                    selectedLabelColor = BackgroundDark,
                                    containerColor = SurfaceElevated
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // SECTION 5: Storage & About
            SettingsCategoryHeader(title = "Storage & About")
            SettingsCard {
                SettingsActionRow(
                    icon = Icons.Rounded.Storage,
                    title = "Device Storage",
                    subtitle = "${uiState.freeStorageGb} GB free of ${uiState.totalStorageGb} GB total",
                    action = {}
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                SettingsActionRow(
                    icon = Icons.Rounded.Info,
                    title = "Anonrode v3.1.0",
                    subtitle = "Serverless 100% On-Device Engine (libaria2c + yt-dlp)",
                    action = {}
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            // Save Changes Button
            Button(
                onClick = {
                    viewModel.saveSettings(
                        maxConcurrent = maxConcurrent,
                        parallelSockets = sockets,
                        quality = quality,
                        autoOrganize = autoOrganize,
                        storageGuard = storageGuard.toDouble(),
                        wifiOnlyTorrents = wifiOnlyTorrents,
                        instantSocial = instantSocial,
                        showPosters = showPosters
                    )
                    Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BackgroundDark)
            ) {
                Text("Save Preferences", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SettingsCategoryHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, BorderHairline, RoundedCornerShape(Radius.lg)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        content = content
    )
}

@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(subtitle, fontSize = 11.sp, color = TextMuted)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary, checkedTrackColor = SurfaceElevated)
        )
    }
}

@Composable
fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(subtitle, fontSize = 11.sp, color = TextMuted)
            }
        }

        action()
    }
}

@Composable
fun SettingsSliderThumb() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(AccentPrimary)
            .border(2.dp, SurfaceCard, CircleShape)
    )
}
