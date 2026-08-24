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
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.util.UpdateCheckResult
import com.anonrode.downloader.util.UpdateChecker
import com.anonrode.downloader.viewmodel.MainViewModel
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live state of the manual "Check for Updates" row. */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val latestTag: String, val url: String) : UpdateUiState
    data object UpToDate : UpdateUiState
    data object Error : UpdateUiState
}

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

    // Tier-A settings state
    var stallTimeout by remember { mutableStateOf(viewModel.engine.stallTimeoutSec) }
    var magnetRetries by remember { mutableStateOf(viewModel.engine.magnetMaxAttempts) }
    var ytdlpRetries by remember { mutableStateOf(viewModel.engine.ytdlpMaxAttempts) }
    var hlsFragments by remember { mutableStateOf(viewModel.engine.hlsFragmentConcurrency) }
    var speedLimit by remember { mutableStateOf(viewModel.engine.globalSpeedLimitKbs) }
    var torrentPeers by remember { mutableStateOf(viewModel.engine.torrentPeers) }
    var torrentPrivacy by remember { mutableStateOf(viewModel.engine.torrentPrivacyMode) }
    var wifiOnlyAll by remember { mutableStateOf(viewModel.engine.wifiOnlyAll) }
    var clipboardDetect by remember { mutableStateOf(viewModel.engine.clipboardDetect) }
    var completionNotifications by remember { mutableStateOf(viewModel.engine.completionNotifications) }
    var debugLogging by remember { mutableStateOf(viewModel.engine.debugLogging) }
    var logRetention by remember { mutableStateOf(viewModel.engine.logRetentionDays) }

    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var isSyncingRules by remember { mutableStateOf(false) }

    val rulesVersion by DynamicRulesManager.version.collectAsState()

    // Live storage figures: the ViewModel only refreshes at app start, so
    // re-read free/total storage each time the sheet opens.
    LaunchedEffect(Unit) {
        viewModel.refreshStorageInfo()
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

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // Wi-Fi Only for ALL Downloads
                SettingsSwitchRow(
                    icon = Icons.Rounded.WifiOff,
                    title = "Download Only on Wi-Fi (All)",
                    subtitle = "Gates every download to Wi-Fi, not just torrents",
                    checked = wifiOnlyAll,
                    onCheckedChange = { wifiOnlyAll = it }
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

            // Manual update check against GitHub Releases (public repo, no
            // token). Never runs at startup — only when the row is tapped.
            var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
            val runUpdateCheck: () -> Unit = {
                updateState = UpdateUiState.Checking
                scope.launch {
                    updateState = when (val r = UpdateChecker.check()) {
                        is UpdateCheckResult.Available -> UpdateUiState.Available(r.latestTag, r.releaseUrl)
                        is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                        UpdateCheckResult.Error -> UpdateUiState.Error
                    }
                }
            }

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
                    title = "Anonrode v${BuildConfig.VERSION_NAME}",
                    subtitle = "Serverless 100% On-Device Engine (libaria2c + yt-dlp)",
                    action = {}
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                val checkState = updateState
                SettingsActionRow(
                    icon = Icons.Rounded.SystemUpdate,
                    title = "Check for Updates",
                    subtitle = when (checkState) {
                        is UpdateUiState.Available -> "v${checkState.latestTag} is available — tap to open"
                        UpdateUiState.UpToDate -> "You're on the latest build"
                        UpdateUiState.Checking -> "Contacting GitHub…"
                        UpdateUiState.Error -> "Couldn't reach GitHub — tap to retry"
                        UpdateUiState.Idle -> "Compares this build against GitHub Releases"
                    },
                    action = {
                        when (checkState) {
                            is UpdateUiState.Available -> {
                                Text(
                                    text = "Open →",
                                    color = AccentPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.xs))
                                        .clickable { UpdateChecker.openInBrowser(context, checkState.url) }
                                        .padding(horizontal = Spacing.xs, vertical = 4.dp)
                                )
                            }
                            UpdateUiState.UpToDate -> {
                                Text("Up to date", color = TextMuted, fontSize = 11.sp)
                            }
                            UpdateUiState.Checking -> {
                                Text("Checking…", color = TextMuted, fontSize = 11.sp)
                            }
                            UpdateUiState.Error -> {
                                Text(
                                    text = "Retry",
                                    color = StatusWarning,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.xs))
                                        .clickable { runUpdateCheck() }
                                        .padding(horizontal = Spacing.xs, vertical = 4.dp)
                                )
                            }
                            UpdateUiState.Idle -> {
                                Text(
                                    text = "Check",
                                    color = AccentPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.xs))
                                        .clickable { runUpdateCheck() }
                                        .padding(horizontal = Spacing.xs, vertical = 4.dp)
                                )
                            }
                        }
                    }
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                SettingsActionRow(
                    icon = Icons.Rounded.OpenInNew,
                    title = "View Releases on GitHub",
                    subtitle = "All builds, release notes and APKs",
                    action = {
                        Text(
                            text = "Open",
                            color = AccentPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.xs))
                                .clickable { UpdateChecker.openInBrowser(context, UpdateChecker.RELEASES_PAGE) }
                                .padding(horizontal = Spacing.xs, vertical = 4.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            // SECTION 6: Network & Privacy
            SettingsCategoryHeader(title = "Network & Privacy")
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Rounded.ContentPaste,
                    title = "Auto-Detect Clipboard Links",
                    subtitle = "Shows a snippet when a URL or magnet is copied",
                    checked = clipboardDetect,
                    onCheckedChange = { clipboardDetect = it }
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                SettingsSwitchRow(
                    icon = Icons.Rounded.Notifications,
                    title = "Completion Notifications",
                    subtitle = "Post a notification when a download finishes",
                    checked = completionNotifications,
                    onCheckedChange = { completionNotifications = it }
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // Speed Limit Slider
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
                                Text("Global Speed Limit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Per-task transfer cap (0 = unlimited)", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text(if (speedLimit > 0) "${speedLimit} KB/s" else "∞", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }
                    Slider(
                        value = speedLimit.toFloat(),
                        onValueChange = { speedLimit = it.toInt() },
                        valueRange = 0f..50000f,
                        steps = 19,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // SECTION 7: Torrents & Peer Limits
            SettingsCategoryHeader(title = "Torrents & Peer Limits")
            SettingsCard {
                // Torrent Peer Slider
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Hub, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("Peer Connections", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(if (torrentPeers == -1) "Auto (RAM-detected) — more = faster, $ battery" else "Torrent file-sharing limit", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text(if (torrentPeers == -1) "Auto" else "$torrentPeers", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }
                    Slider(
                        value = if (torrentPeers == -1) 0f else torrentPeers.toFloat(),
                        onValueChange = { torrentPeers = if (it <= 0f) -1 else it.toInt() },
                        valueRange = 0f..500f,
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

                // Torrent Privacy Mode (qBittorrent anonymous-mode lessons):
                // no DHT/LPD/PEX, encrypted peers, near-zero upload, random port.
                SettingsSwitchRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = "Torrent Privacy Mode",
                    subtitle = "Hides you from peer discovery (DHT/PEX/LPD off), encrypts peer links, upload ~0. Trackers only — some dead swarms won't start",
                    checked = torrentPrivacy,
                    onCheckedChange = { torrentPrivacy = it }
                )

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // Stall timeout
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Timer, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("Stall Timeout", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Abandon stalled download after N seconds", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text("${stallTimeout}s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }
                    Slider(
                        value = stallTimeout.toFloat(),
                        onValueChange = { stallTimeout = it.toInt() },
                        valueRange = 15f..300f,
                        steps = 18,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // Magnet & yt-dlp retry sliders
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Replay, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("Download Retry Count", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Magnet / yt-dlp retries before giving up", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text("$magnetRetries / $ytdlpRetries", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text("Magnet retries", fontSize = 10.sp, color = TextMuted)
                    Slider(
                        value = magnetRetries.toFloat(),
                        onValueChange = { magnetRetries = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                    Text("yt-dlp retries", fontSize = 10.sp, color = TextMuted)
                    Slider(
                        value = ytdlpRetries.toFloat(),
                        onValueChange = { ytdlpRetries = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // HLS fragments
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FeaturedPlayList, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("HLS Fragment Concurrency", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Parallel HLS segments per stream", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text("$hlsFragments", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }
                    Slider(
                        value = hlsFragments.toFloat(),
                        onValueChange = { hlsFragments = it.toInt() },
                        valueRange = 1f..16f,
                        steps = 14,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // SECTION 8: Diagnostics
            SettingsCategoryHeader(title = "Diagnostics")
            SettingsCard {
                // Activity-log retention: how many days of journal files are
                // kept before auto-deletion (default 7). Lowering it purges
                // immediately on save.
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text("Keep Activity Logs", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Days of history before old log files are deleted", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Text("$logRetention days", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                    }
                    Slider(
                        value = logRetention.toFloat(),
                        onValueChange = { logRetention = it.toInt().coerceIn(1, 30) },
                        valueRange = 1f..30f,
                        steps = 29,
                        thumb = { SettingsSliderThumb() },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

                // The activity journal is always on (daily rotating files under
                // filesDir/logs); this row hands the current day's file to any
                // share target so misbehavior can be diagnosed from it alone.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val file = com.anonrode.downloader.util.DebugLog.currentLogFile()
                            if (file == null || !file.exists()) {
                                Toast.makeText(context, "No activity log yet", Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    // Redact token/secret values before sharing — the log
                                    // contains ?token=, ?pt=, ?download_token=, ?expiry= URLs
                                    // that are sensitive (session-bound stream tokens).
                                    val raw = file.readText()
                                    val redacted = raw.replace(
                                        Regex("""[?&](token|download_token|pt|expiry|expires)=[^\s&]+""")
                                    ) { match ->
                                        "${match.value.substringBefore('=')}=***REDACTED***"
                                    }
                                    val shareFile = File(context.cacheDir, "activity-log-share.txt")
                                    shareFile.writeText(redacted)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", shareFile
                                    )
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, "Share activity log")
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.BugReport,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Share Activity Log", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Everything the app did — send it for diagnosis", fontSize = 11.sp, color = TextMuted)
                    }
                    Text("SHARE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
                }
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
                        showPosters = showPosters,
                        stallTimeout = stallTimeout,
                        magnetRetries = magnetRetries,
                        ytdlpRetries = ytdlpRetries,
                        hlsFragments = hlsFragments,
                        speedLimit = speedLimit,
                        peers = torrentPeers,
                        privacyMode = torrentPrivacy,
                        wifiAll = wifiOnlyAll,
                        clipboard = clipboardDetect,
                        notifications = completionNotifications,
                        debugLog = debugLogging,
                        logRetention = logRetention
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
