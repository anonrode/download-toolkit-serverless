@file:OptIn(ExperimentalMaterial3Api::class)

package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.util.UpdateCheckResult
import com.anonrode.downloader.util.UpdateChecker
import com.anonrode.downloader.viewmodel.MainViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live state of the manual "Check for Updates" row.  Internal (not
 *  private) because [SettingsState.updateState] exposes it as `internal`,
 *  and Kotlin refuses a less-visible type behind a more-visible property. */
internal sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val latestTag: String, val url: String) : UpdateUiState
    data object UpToDate : UpdateUiState
    data object Error : UpdateUiState
}

/**
 * Holder for the full set of mutable settings knobs.  Lifted out of the
 * single giant `SettingsSheet` so each per-category composable can subscribe
 * to only the slice it needs, and so a `LazyColumn` host can compose only
 * the visible categories at a time.  This is the structural fix for the
 * slow first-open: previously every row was built eagerly on sheet-show.
 */
internal class SettingsState(
    initialThemeMode: String,
    val viewModel: MainViewModel
) {
    var themeMode by mutableStateOf(initialThemeMode)
    var maxConcurrent by mutableStateOf(viewModel.engine.maxConcurrentDownloads)
    var sockets by mutableStateOf(viewModel.engine.parallelSocketsPerFile)
    var quality by mutableStateOf(viewModel.engine.defaultQuality)
    var autoOrganize by mutableStateOf(viewModel.engine.autoOrganizeByShow)
    var instantSocial by mutableStateOf(viewModel.engine.instantSocialDownload)
    var wifiOnlyTorrents by mutableStateOf(viewModel.engine.downloadTorrentsWifiOnly)
    var showPosters by mutableStateOf(viewModel.engine.showPostersInResults)
    var storageGuard by mutableStateOf(viewModel.engine.storageGuardGb.toFloat())

    var stallTimeout by mutableStateOf(viewModel.engine.stallTimeoutSec)
    var magnetRetries by mutableStateOf(viewModel.engine.magnetMaxAttempts)
    var ytdlpRetries by mutableStateOf(viewModel.engine.ytdlpMaxAttempts)
    var hlsFragments by mutableStateOf(viewModel.engine.hlsFragmentConcurrency)
    var speedLimit by mutableStateOf(viewModel.engine.globalSpeedLimitKbs)
    var torrentPeers by mutableStateOf(viewModel.engine.torrentPeers)
    var torrentPrivacy by mutableStateOf(viewModel.engine.torrentPrivacyMode)
    var wifiOnlyAll by mutableStateOf(viewModel.engine.wifiOnlyAll)
    var clipboardDetect by mutableStateOf(viewModel.engine.clipboardDetect)
    var completionNotifications by mutableStateOf(viewModel.engine.completionNotifications)
    var debugLogging by mutableStateOf(viewModel.engine.debugLogging)
    var logRetention by mutableStateOf(viewModel.engine.logRetentionDays)
    // Seal-parity subtitle settings (2026-09-14): drive yt-dlp caption
    // download + embed; the language also pre-selects subtitle tracks in
    // the built-in player (MKV/embedded subs show up automatically).
    var downloadSubs by mutableStateOf(viewModel.engine.downloadSubtitles)
    var subLang by mutableStateOf(viewModel.engine.subtitleLanguage)

    var isUpdatingYtDlp by mutableStateOf(false)
    var isSyncingRules by mutableStateOf(false)
    var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)

    fun snapshot() = SettingsStateSnapshot(
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
        logRetention = logRetention,
        downloadSubs = downloadSubs,
        subLang = subLang
    )

    /** Instant-apply: every control persists the moment the user changes it —
     *  there is no "Save Preferences" gate anymore. Routes the full snapshot
     *  through the engine's tested saveAllSettings path (SharedPreferences
     *  .apply() is async, so this never blocks the UI thread). Sliders call
     *  this from onValueChangeFinished so a drag writes once, on release. */
    fun persist() {
        val s = snapshot()
        viewModel.saveSettings(
            maxConcurrent = s.maxConcurrent,
            parallelSockets = s.parallelSockets,
            quality = s.quality,
            autoOrganize = s.autoOrganize,
            storageGuard = s.storageGuard,
            wifiOnlyTorrents = s.wifiOnlyTorrents,
            instantSocial = s.instantSocial,
            showPosters = s.showPosters,
            stallTimeout = s.stallTimeout,
            magnetRetries = s.magnetRetries,
            ytdlpRetries = s.ytdlpRetries,
            hlsFragments = s.hlsFragments,
            speedLimit = s.speedLimit,
            peers = s.peers,
            privacyMode = s.privacyMode,
            wifiAll = s.wifiAll,
            clipboard = s.clipboard,
            notifications = s.notifications,
            debugLog = s.debugLog,
            logRetention = s.logRetention,
            downloadSubs = s.downloadSubs,
            subLang = s.subLang
        )
    }
}

internal data class SettingsStateSnapshot(
    val maxConcurrent: Int,
    val parallelSockets: Int,
    val quality: String,
    val autoOrganize: Boolean,
    val storageGuard: Double,
    val wifiOnlyTorrents: Boolean,
    val instantSocial: Boolean,
    val showPosters: Boolean,
    val stallTimeout: Int,
    val magnetRetries: Int,
    val ytdlpRetries: Int,
    val hlsFragments: Int,
    val speedLimit: Int,
    val peers: Int,
    val privacyMode: Boolean,
    val wifiAll: Boolean,
    val clipboard: Boolean,
    val notifications: Boolean,
    val debugLog: Boolean,
    val logRetention: Int,
    val downloadSubs: Boolean,
    val subLang: String
)

@Composable
private fun rememberSettingsState(
    viewModel: MainViewModel,
    initialThemeMode: String
): SettingsState = remember(viewModel) {
    // NOT keyed on initialThemeMode anymore: every theme change re-passes
    // that parameter (MainScaffold), which used to RECREATE the whole state
    // from the engine's still-STALE fields — inside the 500 ms save-debounce
    // window a toggled switch visibly snapped back to its old value even
    // though the new one was about to persist. themeMode is now synced in
    // SettingsScreen via LaunchedEffect instead; all other fields keep their
    // live values across a theme flip.
    SettingsState(initialThemeMode = initialThemeMode, viewModel = viewModel)
}

/** Tabbed host.  Same content as the sheet, just without the bottom-sheet
 *  chrome so the user can navigate to it from the bottom nav.  Uses a
 *  LazyColumn so off-screen categories do not compose at all — the structural
 *  fix for the "first open is slow" symptom that the sheet had. */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    themeMode: String,
    onThemeChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rulesVersion by DynamicRulesManager.version.collectAsState()

    val state = rememberSettingsState(viewModel, themeMode)

    // themeMode flows INTO the state (instead of recreating it — see
    // rememberSettingsState). Covers changes made elsewhere too (Android
    // Auto night flip), not just the chips in this screen.
    LaunchedEffect(themeMode) { state.themeMode = themeMode }

    // Advanced starts collapsed (UI research round): retry counts, log
    // retention and the OTA/diagnostic tools are the controls almost nobody
    // opens weekly, and folding them away removes roughly 40% of the scroll
    // length. rememberSaveable so a rotation or a tab switch doesn't re-hide
    // a section the user just opened.
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Two jobs on one observer:
    //  - flush the debounced settings write when the app backgrounds / the
    //    screen leaves (the "flip a switch and immediately leave" window,
    //    ON_PAUSE covering OS process kill);
    //  - re-read the storage figures on EVERY resume. The old
    //    LaunchedEffect(Unit) fired exactly once — MainScaffold keeps all
    //    three tabs composed, so the screen never "re-enters composition"
    //    and the GB numbers went stale after any download or deletion.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.flushPendingSettings()
                Lifecycle.Event.ON_RESUME -> viewModel.refreshStorageInfo()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushPendingSettings()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        // The outer MainScaffold already excludes the system bars around the
        // tab content; the default here folded them into `padding` AGAIN
        // (dead band above "Settings", extra nav-height gap at the bottom).
        // The TopAppBar still self-insets the status bar — that one stays
        // correct because the inner content now starts at the very top.
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Black,
                        fontSize = Type.screenTitle.fontSize,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back to Search",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        // The back arrow is always visible: this is the only way to reach
        // Settings in the new design, but a deep link / external launch that
        // lands on Settings still needs a way out that doesn't exit the app.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BackgroundDark)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
                contentPadding = PaddingValues(bottom = Spacing.xxl)
            ) {
                // Order is frequency of use, not developer concern (UI research
                // round): the behaviour settings people touch weekly come
                // first, the once-a-year OTA/diagnostic tools fold into
                // Advanced, and About goes last — it used to sit between Media
                // and Network, which made the page read as unordered.
                item {
                    SettingsGeneralSection(state = state)
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
                item {
                    SettingsAppearanceSection(
                        state = state,
                        onThemeChanged = onThemeChanged
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
                item {
                    SettingsEngineSection(state = state)
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
                item {
                    SettingsMediaSection(state = state)
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
                item {
                    SettingsTorrentsSection(state = state)
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
                item {
                    SettingsNetworkSection(state = state)
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
                item {
                    SettingsAdvancedSection(
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = !advancedExpanded },
                        state = state,
                        rulesVersion = rulesVersion,
                        scope = scope
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
                item {
                    SettingsAboutSection(
                        state = state,
                        uiState = uiState,
                        viewModel = viewModel,
                        scope = scope,
                        onDismiss = onBack
                    )
                    Spacer(modifier = Modifier.height(Spacing.xl))
                    // No Save button: every control instant-persists via
                    // SettingsState.persist (see the sheet host above).
                }
            }
        }
    }
}

// ============================================================
//                     SECTION COMPOSABLES
// ============================================================

@Composable
internal fun SettingsAdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    state: SettingsState,
    rulesVersion: String,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.DurationFast),
        label = "advancedChevron"
    )
    // Same visual language as SettingsCategoryHeader (uppercase caption,
    // muted, tracked) plus a chevron — this row IS the disclosure control, so
    // it is one ≥48dp tap target rather than a label with a small icon.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ADVANCED",
            fontSize = Type.caption.fontSize,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
                .padding(start = Spacing.xs)
        )
        Text(
            text = if (expanded) "HIDE" else "SYNC · LOGS · DIAGNOSTICS",
            fontSize = Type.micro.fontSize,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp
        )
        Icon(
            imageVector = Icons.Rounded.ArrowDropDown,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp).rotate(chevronRotation)
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column {
            Spacer(modifier = Modifier.height(Spacing.sm))
            SettingsSelfHealingSection(
                state = state,
                rulesVersion = rulesVersion,
                scope = scope
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            SettingsDiagnosticsSection(state = state)
        }
    }
}

@Composable
internal fun SettingsSelfHealingSection(
    state: SettingsState,
    rulesVersion: String,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    SettingsCategoryHeader(title = "Self-Healing & Core Updates")
    SettingsCard {
        SettingsActionRow(
            icon = Icons.Rounded.CloudDownload,
            title = "Sync Scraper & Site Logic",
            subtitle = "Version: $rulesVersion",
            action = {
                if (state.isSyncingRules) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentPrimary)
                } else {
                    FilledTonalButton(
                        onClick = {
                            state.isSyncingRules = true
                            scope.launch {
                                val (ok, ver) = DynamicRulesManager.syncFromGitHub(context)
                                state.isSyncingRules = false
                                if (ok) {
                                    Toast.makeText(context, "Synced fresh logic: $ver", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Sync error: $ver", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceElevated, contentColor = AccentPrimary)
                    ) {
                        Text("Sync Now", fontSize = Type.caption.fontSize, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        SettingsActionRow(
            icon = Icons.Rounded.Refresh,
            title = "yt-dlp Core Engine",
            subtitle = "Over-the-air extractor fixes for IG/TikTok/FB",
            action = {
                if (state.isUpdatingYtDlp) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentPrimary)
                } else {
                    FilledTonalButton(
                        onClick = {
                            state.isUpdatingYtDlp = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                                    withContext(Dispatchers.Main) {
                                        state.isUpdatingYtDlp = false
                                        // Match on .name (never a guessed
                                        // constant) so this compiles against
                                        // every library version's enum.
                                        val msg = when (status?.name) {
                                            "UP_TO_DATE", "NO_UPDATE", "UNDEFINED" -> "Core is already up to date"
                                            "NEW_VERSION_AVAILABLE" -> "Core updated to the latest build"
                                            "ERROR" -> "Core update failed (yt-dlp reported an error)"
                                            null -> "Core update finished — the library reported no status"
                                            else -> "Core update: $status"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                } catch (t: Throwable) {
                                    withContext(Dispatchers.Main) {
                                        state.isUpdatingYtDlp = false
                                        // The old catch said "Core is up to
                                        // date" for EVERY failure — a dead
                                        // network or a failed download wore
                                        // the costume of good news.
                                        Toast.makeText(
                                            context,
                                            "Core update failed: ${t.message ?: t.javaClass.simpleName}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceElevated, contentColor = AccentPrimary)
                    ) {
                        Text("Update Core", fontSize = Type.caption.fontSize, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }
}

@Composable
internal fun SettingsAppearanceSection(
    state: SettingsState,
    onThemeChanged: (String) -> Unit
) {
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
                        imageVector = when (state.themeMode) {
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
                        fontSize = Type.rowTitle.fontSize,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = when (state.themeMode) {
                            "light" -> "Light Mode"
                            "dark" -> "Dark Mode (OLED)"
                            else -> "System Default"
                        },
                        fontSize = Type.caption.fontSize,
                        color = TextSecondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf("Dark" to "dark", "Light" to "light", "Auto" to "system").forEach { (label, mode) ->
                    val isSel = state.themeMode.equals(mode, ignoreCase = true)
                    // selectable() + RadioButton role: the chips used to be
                    // raw clickable Boxes — TalkBack announced no selection
                    // between the three. minimumInteractiveComponentSize
                    // grows the ~24dp visual into the 48dp hit minimum.
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(if (isSel) AccentPrimary else SurfaceElevated)
                            .selectable(
                                selected = isSel,
                                role = Role.RadioButton,
                                onClick = {
                                    state.themeMode = mode
                                    onThemeChanged(mode)
                                }
                            )
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = Type.caption.fontSize,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) (if (AnonTheme.colors.isDark) Color.Black else Color.White) else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsGeneralSection(
    state: SettingsState
) {
    SettingsCategoryHeader(title = "Downloads & Automation")
    SettingsCard {
        SettingsSwitchRow(
            icon = Icons.Rounded.FolderOpen,
            title = "Auto-Organize Folders",
            subtitle = "Creates /Download/Anon/<ShowName>/ structure",
            checked = state.autoOrganize,
            onCheckedChange = { state.autoOrganize = it; state.persist() }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        SettingsSwitchRow(
            icon = Icons.Rounded.FlashOn,
            title = "Instant Social Download",
            subtitle = "1-tap download when shared from Instagram/TikTok",
            checked = state.instantSocial,
            onCheckedChange = { state.instantSocial = it; state.persist() }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        SettingsSwitchRow(
            icon = Icons.Rounded.Image,
            title = "Show Posters in Results",
            subtitle = "Off saves data: results show a lettered tile instead",
            checked = state.showPosters,
            onCheckedChange = {
                state.showPosters = it
                // Instant-apply: persisted + live in one write, so the next
                // search reflects it immediately.
                state.persist()
            }
        )
    }
}

@Composable
internal fun SettingsEngineSection(state: SettingsState) {
    SettingsCategoryHeader(title = "Engine & Performance")
    SettingsCard {
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
                        Text("Aria2c Parallel Sockets", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("High-speed segmented CDN connections", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${state.sockets} conns", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }

            Slider(
                value = state.sockets.toFloat(),
                onValueChange = { state.sockets = it.toInt() },
                onValueChangeFinished = { state.persist() },
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
                        Text("Max Concurrent Downloads", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Parallel batch download queue limit", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${state.maxConcurrent} tasks", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }

            Slider(
                value = state.maxConcurrent.toFloat(),
                onValueChange = { state.maxConcurrent = it.toInt() },
                onValueChangeFinished = { state.persist() },
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
                        Text("Storage Protection Guard", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Auto-pause downloads if disk free space is low", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${String.format("%.1f", state.storageGuard)} GB", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }

            Slider(
                value = state.storageGuard,
                onValueChange = { state.storageGuard = it },
                onValueChangeFinished = { state.persist() },
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

        SettingsSwitchRow(
            icon = Icons.Rounded.Wifi,
            title = "Download Torrents Only on Wi-Fi",
            subtitle = "Protects cellular mobile data balance",
            checked = state.wifiOnlyTorrents,
            onCheckedChange = { state.wifiOnlyTorrents = it; state.persist() }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        SettingsSwitchRow(
            icon = Icons.Rounded.WifiOff,
            title = "Download Only on Wi-Fi (All)",
            subtitle = "Gates every download to Wi-Fi, not just torrents",
            checked = state.wifiOnlyAll,
            onCheckedChange = { state.wifiOnlyAll = it; state.persist() }
        )
    }
}

@Composable
internal fun SettingsMediaSection(state: SettingsState) {
    SettingsCategoryHeader(title = "Media & Quality Formats")
    SettingsCard {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Column {
                    Text("Preferred Stream Resolution", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("Default video quality for drama & anime streams", fontSize = Type.caption.fontSize, color = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                listOf("480p", "720p", "1080p").forEach { q ->
                    val isSel = state.quality == q
                    FilterChip(
                        selected = isSel,
                        onClick = {
                            state.quality = q
                            // Instant-apply: the very next download picks up
                            // the new resolution without a Save tap.
                            state.persist()
                        },
                        label = { Text(q, fontSize = Type.label.fontSize, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
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

    SettingsCard {
        SettingsSwitchRow(
            icon = Icons.Rounded.Subtitles,
            title = "Download Subtitles",
            subtitle = "Fetches captions for YouTube & social video downloads and embeds them in the file",
            checked = state.downloadSubs,
            onCheckedChange = { state.downloadSubs = it; state.persist() }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Translate, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Column {
                    Text("Subtitle Language", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    // 2026-09-14: the same code also drives the player's
                    // AUTO-selection of an embedded/subtitle track, so an
                    // English-subbed MKV shows English without a manual pick.
                    Text("Also picks which embedded track the player shows by default", fontSize = Type.caption.fontSize, color = TextMuted)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                SUBTITLE_LANGS.forEach { (code, label) ->
                    val isSel = state.subLang == code
                    FilterChip(
                        selected = isSel,
                        onClick = {
                            state.subLang = code
                            state.persist()
                        },
                        label = { Text(label, fontSize = Type.label.fontSize, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentPrimary,
                            selectedLabelColor = BackgroundDark,
                            containerColor = SurfaceElevated
                        )
                    )
                }
            }
        }
    }
}

/** Language codes fed to yt-dlp --sub-langs and matched against embedded
 *  track languages in the player ("all" is a valid yt-dlp selector). */
internal val SUBTITLE_LANGS = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "pt" to "Portuguese",
    "ar" to "Arabic", "hi" to "Hindi", "zh" to "Chinese", "ja" to "Japanese",
    "ko" to "Korean", "all" to "All languages"
)

@Composable
internal fun SettingsAboutSection(
    state: SettingsState,
    uiState: com.anonrode.downloader.viewmodel.HomeUiState,
    viewModel: MainViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    SettingsCategoryHeader(title = "Storage & About")

    // Manual update check against GitHub Releases (public repo, no
    // token). Never runs at startup — only when the row is tapped.
    val runUpdateCheck: () -> Unit = {
        state.updateState = UpdateUiState.Checking
        scope.launch {
            state.updateState = when (val r = UpdateChecker.check()) {
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

        val checkState = state.updateState
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
                            fontSize = Type.caption.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            // 11sp text + 12dp padding is a ~39dp target; the
                            // modifier raises it to the 48dp minimum without
                            // changing how the pill looks (same idiom as the
                            // Downloads sort chips).
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.xs))
                                .clickable { UpdateChecker.openInBrowser(context, checkState.url) }
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = Spacing.sm, vertical = Spacing.md)
                        )
                    }
                    UpdateUiState.UpToDate -> {
                        Text("Up to date", color = TextMuted, fontSize = Type.caption.fontSize)
                    }
                    UpdateUiState.Checking -> {
                        Text("Checking…", color = TextMuted, fontSize = Type.caption.fontSize)
                    }
                    UpdateUiState.Error -> {
                        Text(
                            text = "Retry",
                            color = StatusWarning,
                            fontSize = Type.caption.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.xs))
                                .clickable { runUpdateCheck() }
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = Spacing.sm, vertical = Spacing.md)
                        )
                    }
                    UpdateUiState.Idle -> {
                        Text(
                            text = "Check",
                            color = AccentPrimary,
                            fontSize = Type.caption.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.xs))
                                .clickable { runUpdateCheck() }
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = Spacing.sm, vertical = Spacing.md)
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
                    fontSize = Type.caption.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .clickable { UpdateChecker.openInBrowser(context, UpdateChecker.RELEASES_PAGE) }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.md)
                )
            }
        )
    }
}

@Composable
internal fun SettingsNetworkSection(state: SettingsState) {
    SettingsCategoryHeader(title = "Network & Privacy")
    SettingsCard {
        SettingsSwitchRow(
            icon = Icons.Rounded.ContentPaste,
            title = "Auto-Detect Clipboard Links",
            subtitle = "Shows a snippet when a URL or magnet is copied",
            checked = state.clipboardDetect,
            onCheckedChange = { state.clipboardDetect = it; state.persist() }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        SettingsSwitchRow(
            icon = Icons.Rounded.Notifications,
            title = "Completion Notifications",
            subtitle = "Post a notification when a download finishes",
            checked = state.completionNotifications,
            onCheckedChange = { state.completionNotifications = it; state.persist() }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

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
                        Text("Global Speed Limit", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Per-task transfer cap (0 = unlimited)", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text(if (state.speedLimit > 0) "${state.speedLimit} KB/s" else "∞", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }
            Slider(
                value = state.speedLimit.toFloat(),
                onValueChange = { state.speedLimit = it.toInt() },
                onValueChangeFinished = { state.persist() },
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
}

@Composable
internal fun SettingsTorrentsSection(state: SettingsState) {
    SettingsCategoryHeader(title = "Torrents & Peer Limits")
    SettingsCard {
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
                        Text("Peer Connections", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(if (state.torrentPeers == -1) "Auto (RAM-detected) — more = faster, $ battery" else "Torrent file-sharing limit", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text(if (state.torrentPeers == -1) "Auto" else "${state.torrentPeers}", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }
            Slider(
                value = if (state.torrentPeers == -1) 0f else state.torrentPeers.toFloat(),
                onValueChange = { state.torrentPeers = if (it <= 0f) -1 else it.toInt() },
                onValueChangeFinished = { state.persist() },
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

        SettingsSwitchRow(
            icon = Icons.Rounded.VisibilityOff,
            title = "Torrent Privacy Mode",
            subtitle = "Hides you from peer discovery (DHT/PEX/LPD off), encrypts peer links, upload ~0. Trackers only — some dead swarms won't start",
            checked = state.torrentPrivacy,
            onCheckedChange = { state.torrentPrivacy = it; state.persist() }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

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
                        Text("Stall Timeout", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Abandon stalled download after N seconds", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${state.stallTimeout}s", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }
            Slider(
                value = state.stallTimeout.toFloat(),
                onValueChange = { state.stallTimeout = it.toInt() },
                onValueChangeFinished = { state.persist() },
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
                        Text("Download Retry Count", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Magnet / yt-dlp retries before giving up", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${state.magnetRetries} / ${state.ytdlpRetries}", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text("Magnet retries", fontSize = Type.micro.fontSize, color = TextMuted)
            Slider(
                value = state.magnetRetries.toFloat(),
                onValueChange = { state.magnetRetries = it.toInt() },
                onValueChangeFinished = { state.persist() },
                valueRange = 1f..10f,
                steps = 8,
                thumb = { SettingsSliderThumb() },
                colors = SliderDefaults.colors(
                    thumbColor = AccentPrimary,
                    activeTrackColor = AccentPrimary,
                    inactiveTrackColor = SurfaceElevated
                )
            )
            Text("yt-dlp retries", fontSize = Type.micro.fontSize, color = TextMuted)
            Slider(
                value = state.ytdlpRetries.toFloat(),
                onValueChange = { state.ytdlpRetries = it.toInt() },
                onValueChangeFinished = { state.persist() },
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
                        Text("HLS Fragment Concurrency", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Parallel HLS segments per stream", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${state.hlsFragments}", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }
            Slider(
                value = state.hlsFragments.toFloat(),
                onValueChange = { state.hlsFragments = it.toInt() },
                onValueChangeFinished = { state.persist() },
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
}

@Composable
internal fun SettingsDiagnosticsSection(state: SettingsState) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    SettingsCategoryHeader(title = "Diagnostics")
    SettingsCard {
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
                        Text("Keep Activity Logs", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Days of history before old log files are deleted", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${state.logRetention} days", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }
            Slider(
                value = state.logRetention.toFloat(),
                onValueChange = { state.logRetention = it.toInt().coerceIn(1, 30) },
                onValueChangeFinished = { state.persist() },
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    // Share the WHOLE retention window, not just today's file —
                    // sharing only today made the log look like it auto-cleared
                    // every 24h (user-reported). Oldest first, with a header per
                    // day file so a reader can tell them apart.
                    //
                    // The build (up to 8 MB of File reads + a regex pass + a
                    // cache write) runs on Dispatchers.IO: it used to run
                    // inline in the click handler — a multi-second UI freeze
                    // / ANR risk on low-end devices with zero feedback.
                    shareScope.launch {
                        val files = com.anonrode.downloader.util.DebugLog.retainedLogFiles()
                        if (files.isEmpty()) {
                            Toast.makeText(context, "No activity log yet", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                // Keep the share bounded: on heavy logging days drop
                                // the OLDEST files until the total fits (recent
                                // entries matter most for diagnosis).
                                val cap = 8L * 1024 * 1024
                                var total = files.sumOf { it.length() }
                                var from = 0
                                while (from < files.size - 1 && total > cap) {
                                    total -= files[from].length()
                                    from++
                                }
                                val shareFile = File(context.cacheDir, "activity-log-share.txt")
                                val tokenRegex = Regex("""[?&](token|download_token|pt|expiry|expires)=[^\s&]+""")
                                fun redact(line: String): String = line.replace(tokenRegex) { match ->
                                    "${match.value.substringBefore('=')}=***REDACTED***"
                                }

                                shareFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                                    if (from > 0) {
                                        writer.write("(oldest $from log files omitted to keep the share under 8 MB)\n\n")
                                    }
                                    for (f in files.subList(from, files.size)) {
                                        writer.write("===== ${f.name} =====\n")
                                        f.forEachLine(Charsets.UTF_8) { line ->
                                            writer.write(redact(line))
                                            writer.write("\n")
                                        }
                                        writer.write("\n")
                                    }
                                    // Crash reports carry the full untruncated
                                    // "Caused by:" chain the log's CRASH line cuts.
                                    val crashTxt = com.anonrode.downloader.util.CrashHandler
                                        .crashReportsText(context)
                                    if (crashTxt.isNotBlank()) {
                                        writer.write("\n===== CRASH REPORTS =====\n")
                                        crashTxt.lineSequence().forEach { line ->
                                            writer.write(redact(line))
                                            writer.write("\n")
                                        }
                                    }
                                }
                                androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", shareFile
                                )
                            }
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
                Text("Share Activity Log", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("Everything the app did — send it for diagnosis", fontSize = Type.caption.fontSize, color = TextMuted)
            }
            Text("SHARE", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
        }
    }
}

@Composable
fun SettingsCategoryHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = Type.caption.fontSize,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
        letterSpacing = 0.8.sp,
        // Nine categories on a long scrolling page: without heading semantics
        // a screen-reader user gets no landmarks to jump between sections.
        modifier = Modifier
            .semantics { heading() }
            .padding(start = Spacing.xs, bottom = Spacing.xs)
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
            // toggleable(role = Switch) instead of plain clickable: tapping
            // the row toggled correctly, but the row region exposed NO
            // checked state to TalkBack (the Switch child alone owned it,
            // split from the label users actually read). toggleable carries
            // the Checked semantics — selectable is the RadioButton-side
            // modifier.
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { onCheckedChange(it) }
            )
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column {
                Text(title, fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(subtitle, fontSize = Type.caption.fontSize, color = TextMuted)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // The old checkedTrackColor (SurfaceElevated) was nearly the
            // same paint as the default unchecked track — on/off hung
            // entirely on thumb position. An accent-tinted track restores
            // the color cue for sighted users; semantics above restore it
            // for TalkBack.
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentPrimary,
                checkedTrackColor = AccentPrimary.copy(alpha = 0.35f)
            )
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
                Text(title, fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(subtitle, fontSize = Type.caption.fontSize, color = TextMuted)
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
