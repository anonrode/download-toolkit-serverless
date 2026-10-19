@file:OptIn(ExperimentalMaterial3Api::class)

package com.anonrode.downloader.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.ui.components.MediaPlayerPrefs
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.ui.util.pressScale
import com.anonrode.downloader.util.YtDlpCore
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
    var filterExplicit by mutableStateOf(viewModel.engine.filterExplicitContent)

    var isUpdatingYtDlp by mutableStateOf(false)
    var isSyncingRules by mutableStateOf(false)
    var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)

    /**
     * Re-read every engine-backed field (2026-10 fix): this state object is
     * remembered for the whole Settings screen lifetime, but other surfaces —
     * QuickShareActivity's instant-social toggle, SocialModal, OTA-applied
     * engine changes — can move engine fields while it sits in memory. Without
     * this re-read, the next persist() wrote the STALE captured snapshot back
     * and clobbered them (pref_instant_social was the live repro: QuickShare
     * ON, return to Settings, flip any control → instant mode silently OFF).
     * Runs on ON_RESUME, where the racing surfaces (separate activities) have
     * already paused this one, so there is no in-flight edit to clobber.
     */
    fun refreshFromEngine() {
        maxConcurrent = viewModel.engine.maxConcurrentDownloads
        sockets = viewModel.engine.parallelSocketsPerFile
        quality = viewModel.engine.defaultQuality
        autoOrganize = viewModel.engine.autoOrganizeByShow
        instantSocial = viewModel.engine.instantSocialDownload
        wifiOnlyTorrents = viewModel.engine.downloadTorrentsWifiOnly
        showPosters = viewModel.engine.showPostersInResults
        storageGuard = viewModel.engine.storageGuardGb.toFloat()
        stallTimeout = viewModel.engine.stallTimeoutSec
        magnetRetries = viewModel.engine.magnetMaxAttempts
        ytdlpRetries = viewModel.engine.ytdlpMaxAttempts
        hlsFragments = viewModel.engine.hlsFragmentConcurrency
        speedLimit = viewModel.engine.globalSpeedLimitKbs
        torrentPeers = viewModel.engine.torrentPeers
        torrentPrivacy = viewModel.engine.torrentPrivacyMode
        wifiOnlyAll = viewModel.engine.wifiOnlyAll
        clipboardDetect = viewModel.engine.clipboardDetect
        completionNotifications = viewModel.engine.completionNotifications
        debugLogging = viewModel.engine.debugLogging
        logRetention = viewModel.engine.logRetentionDays
        downloadSubs = viewModel.engine.downloadSubtitles
        subLang = viewModel.engine.subtitleLanguage
        filterExplicit = viewModel.engine.filterExplicitContent
    }

    // yt-dlp core transparency (user directive: the sync button must work and
    // the nightly channel must be reachable): the installed extractor version
    // is surfaced right in the row; the channel choice rides YtDlpCore.
    var ytdlpVersion by mutableStateOf<String?>(null)

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
        subLang = subLang,
        filterExplicit = filterExplicit
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
            subLang = s.subLang,
            filterExplicit = s.filterExplicit
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
    val subLang: String,
    val filterExplicit: Boolean
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
 *  chrome so the user can navigate to it from the bottom nav. */
/** The five settings drawers (approved re-group, 2026-10-14). Pure
 *  re-grouping: every existing control renders inside exactly one of these —
 *  none added except the two player defaults that already had stored prefs,
 *  and the remote-reporting switch that wires RemoteLogger's real kill-switch.
 *  §4.5: the numeric `count` badge property is GONE — the pills read as
 *  unread notifications; rows end in a clean chevron. */
internal enum class SettingsDrawer(val label: String, val sub: String) {
    PLAYBACK("Playback", "Quality · subtitles · player defaults"),
    DOWNLOADS("Downloads", "Parallelism · organize · limits"),
    CONNECTIONS("Connections", "Stall · retries · torrents · Wi-Fi"),
    UPDATES("Updates & Reliability", "Site logic · yt-dlp core · reporting"),
    LOGS("Privacy & Logs", "Content filter · logging · diagnostics")
}

private val SettingsEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

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

    // Drawer navigation: null = hub. The top-bar back arrow and system back
    // both close the open drawer first — system back never leaps out of
    // Settings while the user is inside a drawer.
    var openDrawerName by rememberSaveable { mutableStateOf<String?>(null) }
    val currentDrawer = openDrawerName?.let { name ->
        SettingsDrawer.entries.firstOrNull { it.name == name }
    }
    BackHandler(enabled = currentDrawer != null) { openDrawerName = null }

    // The hub breathes wider (24dp gutter) than the drawer pages (16dp): six
    // quiet cards at 16dp read as edge-hugging on a phone, which is exactly
    // the device-reported complaint this fixes.
    val hubGutter = if (currentDrawer == null) Spacing.xl else Spacing.lg

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
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshStorageInfo()
                    // External surfaces (QuickShare/SocialModal/OTA) may have
                    // moved engine fields while this state sat in memory — the
                    // stale snapshot must not clobber them on the next persist.
                    state.refreshFromEngine()
                }
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
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BackgroundDark)
                .statusBarsPadding()
        ) {
            // Header: the back arrow closes a drawer first — the only way out
            // of Settings itself stays on the hub (onBack).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (currentDrawer != null) openDrawerName = null else onBack() }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = if (currentDrawer != null) "Back to Settings" else "Back to Search",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = currentDrawer?.label?.uppercase() ?: "SETTINGS",
                    fontWeight = FontWeight.Black,
                    fontSize = Type.screenTitle.fontSize,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
            }

            AnimatedContent(
                targetState = currentDrawer,
                transitionSpec = {
                    val forward = targetState != null
                    // Nav speed contract (ui-proposal-downloads-v6-drawer.html):
                    // the 500ms slide+fade read as slow motion before the page
                    // appeared. Entrance caps at DurationFast (200ms), exit at
                    // DurationNavExit (120ms) — same SettingsEasing curve, one
                    // third of the wait; the destination's first frame paints
                    // immediately and the animation only decorates it.
                    (slideInHorizontally(
                        animationSpec = tween(Motion.DurationFast, easing = SettingsEasing)
                    ) { full -> if (forward) full else -full / 3 } +
                        fadeIn(tween(Motion.DurationFast, easing = SettingsEasing))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(Motion.DurationNavExit, easing = SettingsEasing)
                        ) { full -> if (forward) -full / 3 else full } +
                            fadeOut(tween(Motion.DurationNavExit, easing = SettingsEasing)))
                },
                label = "settingsDrawer"
            ) { drawer ->
                // Footer pinning, scroll-safe (vertical-rhythm fix, 2026-10-14):
                // each page is an OUTER bounded Column (fillMaxSize) whose
                // scroll lane is a weight(1f) child and whose footer caption is
                // pinned below the lane. The other way round — Spacer(weight(1f))
                // INSIDE a self-scrolling column — collapses to 0dp, because a
                // scroll container measures children against an unbounded max
                // height. Here the weight is resolved by the bounded outer
                // column, so the lane gets exactly "the height left over after
                // the footer"; it scrolls only when the cards overflow that
                // height, and on short content the shortfall lands INSIDE the
                // lane (above the caption) instead of under a floating footer.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // One gutter for cards AND caption (24dp hub / 16dp
                        // drawer, hubGutter above) so the caption centers on
                        // the same grid as the cards.
                        .padding(start = hubGutter, end = hubGutter)
                        // The Scaffold below opts out of window insets
                        // (WindowInsets(0.dp)), so the pinned caption claims the
                        // navigation-bar inset itself. On the page column, not
                        // the footer, so the scroll lane also clips above the
                        // bar instead of showing rows behind the gesture area.
                        .navigationBarsPadding()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // Exactly 20dp between the 56dp back-row zone above
                            // and the first card. Chained BEFORE verticalScroll
                            // so it is a fixed viewport inset — scrolled content
                            // vanishes at the 20dp line instead of jamming
                            // against the header.
                            .padding(top = 20.dp)
                            .verticalScroll(rememberScrollState()),
                        // One uniform 12dp beat between every direct child
                        // (Arithmetic: 12 = Spacing.md). Was looser elsewhere:
                        // 16dp under the theme card, 8dp between hub entries,
                        // plus ad-hoc Spacer(12dp) group separators on the
                        // drawer pages — all removed; the column now owns the
                        // gap, so header/card pairs must sit back-to-back here.
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (drawer) {
                            SettingsDrawer.PLAYBACK -> SettingsPlaybackSection(state = state)
                            SettingsDrawer.DOWNLOADS -> SettingsDownloadsSection(state = state)
                            SettingsDrawer.CONNECTIONS -> SettingsConnectionsSection(state = state)
                            SettingsDrawer.UPDATES -> SettingsUpdatesSection(
                                state = state,
                                rulesVersion = rulesVersion,
                                scope = scope
                            )
                            SettingsDrawer.LOGS -> SettingsLogsSection(state = state)
                            null -> SettingsHub(
                                state = state,
                                onThemeChanged = onThemeChanged,
                                onOpen = { drawer -> openDrawerName = drawer.name }
                            )
                        }
                    }
                    SettingsPinnedFooter(uiState = uiState)
                }
            }
        }
    }
}

/** Theme tri-cell: clipped VERBATIM from the old Appearance section (same
 *  selected-state, same callback) and promoted to the hub — the one control
 *  people change at arm's length never belongs two taps deep. */
@Composable
private fun SettingsThemeControl(
    state: SettingsState,
    onThemeChanged: (String) -> Unit
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Card floor, rhythm contract: 76dp (tri-cell control row).
                // The 12dp vertical padding rides INSIDE the floor, so the
                // tri-cell children center across the remaining 52dp — enough
                // for the 48dp chip touch targets — and the card is exactly
                // 76dp at default font scale, growing only if content does.
                .heightIn(min = 76.dp)
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
                listOf(
                    Triple("Dark", "dark", Icons.Rounded.DarkMode),
                    Triple("Light", "light", Icons.Rounded.LightMode),
                    Triple("Auto", "system", Icons.Rounded.BrightnessAuto)
                ).forEach { (label, mode, icon) ->
                    val isSel = state.themeMode.equals(mode, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(if (isSel) AccentPrimary else SurfaceElevated)
                            .border(
                                width = 1.dp,
                                color = if (isSel) AccentPrimary else BorderHairline.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(Radius.sm)
                            )
                            .selectable(
                                selected = isSel,
                                role = Role.RadioButton,
                                onClick = {
                                    state.themeMode = mode
                                    onThemeChanged(mode)
                                }
                            )
                            .padding(horizontal = Spacing.sm + 2.dp, vertical = Spacing.xs + 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (isSel) (if (AnonTheme.colors.isDark) Color.Black else Color.White) else TextSecondary
                            )
                            Text(
                                text = label,
                                fontSize = Type.caption.fontSize,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) (if (AnonTheme.colors.isDark) Color.Black else Color.White) else TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The hub: theme, then the drawer entry cards — every entry on one 64dp
 *  floor with a uniform 12dp beat (content column's spacedBy). The version/
 *  storage caption used to close this column after a Spacer ladder; it moved
 *  to [SettingsPinnedFooter] below the scroll lane, so the dead space after
 *  the last card now lives INSIDE the lane instead of under a floating
 *  footer. */
@Composable
private fun SettingsHub(
    state: SettingsState,
    onThemeChanged: (String) -> Unit,
    onOpen: (SettingsDrawer) -> Unit
) {
    SettingsThemeControl(state = state, onThemeChanged = onThemeChanged)

    // (Spacer(Spacing.lg) removed — the content column runs
    // Arrangement.spacedBy(12.dp): one uniform 12dp beat between cards.)

    // NOTE: Accent*/Status* tokens are @Composable getters — their when()
    // must run in composable scope, never inside a plain lambda.
    SettingsDrawer.entries.forEach { drawer ->
        val drawerIcon = when (drawer) {
            SettingsDrawer.PLAYBACK -> Icons.Rounded.PlayArrow
            SettingsDrawer.DOWNLOADS -> Icons.Rounded.Download
            SettingsDrawer.CONNECTIONS -> Icons.Rounded.Hub
            SettingsDrawer.UPDATES -> Icons.Rounded.SystemUpdate
            SettingsDrawer.LOGS -> Icons.Rounded.BugReport
        }
        val chipColor = when (drawer) {
            SettingsDrawer.PLAYBACK -> AccentViolet
            SettingsDrawer.DOWNLOADS -> StatusSuccess
            // Token, not the old raw cyan: the palette has no teal and the
            // hub must not mint a new hue — the Connections icon is neutral.
            SettingsDrawer.CONNECTIONS -> AccentPrimary
            SettingsDrawer.UPDATES -> StatusWarning
            SettingsDrawer.LOGS -> StatusError
        }
        val src = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(src)
                .clip(RoundedCornerShape(Radius.lg))
                .background(SurfaceCard)
                .border(1.dp, BorderHairline, RoundedCornerShape(Radius.lg))
                .clickable(interactionSource = src, indication = null) { onOpen(drawer) }
                // Entry floor, rhythm contract: 64dp = 40dp icon box + two
                // text lines (18sp title + 15sp caption ≈ 33dp — the icon box
                // is the tallest child). The 12dp vertical padding rides
                // INSIDE the floor so children center across the remaining
                // 40dp, and the entry is exactly 64dp at default font scale,
                // growing only if scaled text pushes past the floor.
                .heightIn(min = 64.dp)
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(chipColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = drawerIcon,
                    contentDescription = null,
                    tint = chipColor,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = drawer.label,
                    fontSize = Type.rowTitle.fontSize,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = drawer.sub,
                    fontSize = Type.caption.fontSize,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // §4.5: clean chevron only — the numeric count pill is gone.
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        // (Trailing Spacer(Spacing.sm) removed — the content column's
        // spacedBy(12.dp) owns the beat between entries.)
    }

    SettingsAboutSection(state = state)
}

/** About area — phase-1 SKELETON of the v3.3.0 update flow: a version row and
 *  a manual check wired to the EXISTING [UpdateChecker] (the Updates drawer's
 *  "App Updates" card remains the full-featured entry until the phase-2
 *  UpdateManager replaces both with the in-app install flow + diagnostics
 *  rows). Reuses [SettingsState.updateState] so both surfaces show one result. */
@Composable
private fun SettingsAboutSection(state: SettingsState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Same check/state mapping as SettingsUpdatesSection — never runs at
    // startup, only when the row is tapped.
    val runCheck: () -> Unit = {
        state.updateState = UpdateUiState.Checking
        scope.launch {
            state.updateState = when (val r = UpdateChecker.check()) {
                is UpdateCheckResult.Available -> UpdateUiState.Available(r.latestTag, r.releaseUrl)
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                UpdateCheckResult.Error -> UpdateUiState.Error
            }
        }
    }

    SettingsCategoryHeader(title = "About")
    SettingsCard {
        SettingsActionRow(
            icon = Icons.Rounded.Info,
            title = "Anonrode",
            subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            action = { }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        val checkState = state.updateState
        SettingsActionRow(
            icon = Icons.Rounded.SystemUpdate,
            title = "Check for updates",
            subtitle = when (checkState) {
                is UpdateUiState.Available -> "v${checkState.latestTag} is available — tap to open"
                UpdateUiState.UpToDate -> "You're on the latest build"
                UpdateUiState.Checking -> "Contacting GitHub…"
                UpdateUiState.Error -> "Couldn't reach GitHub — tap to retry"
                UpdateUiState.Idle -> "Compares this build against GitHub Releases"
            },
            action = {
                when (checkState) {
                    UpdateUiState.Checking -> Text("Checking…", color = TextMuted, fontSize = Type.caption.fontSize)
                    is UpdateUiState.Available -> Text(
                        text = "Open →",
                        color = AccentPrimary,
                        fontSize = Type.caption.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.xs))
                            .clickable { UpdateChecker.openInBrowser(context, checkState.url) }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.md)
                    )
                    else -> Text(
                        text = if (checkState is UpdateUiState.Error) "Retry" else "Check",
                        color = if (checkState is UpdateUiState.Error) StatusWarning else AccentPrimary,
                        fontSize = Type.caption.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.xs))
                            .clickable { runCheck() }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.md)
                    )
                }
            }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        // Repackage diagnostic (workstream C): this install's signing cert vs
        // the optional OTA pin. Never blocks; a debug build legitimately
        // mismatches a release pin and says so.
        val verdict = remember { com.anonrode.downloader.util.IntegrityCheck.evaluate(context) }
        val isDebugBuild = remember { com.anonrode.downloader.util.IntegrityCheck.isDebugBuild(context) }
        SettingsActionRow(
            icon = Icons.Rounded.VerifiedUser,
            title = "App signature",
            subtitle = when {
                isDebugBuild -> "Debug build — pin comparison not applicable"
                verdict == com.anonrode.downloader.util.IntegrityCheck.Verdict.OK ->
                    "Verified — matches the signed release"
                verdict == com.anonrode.downloader.util.IntegrityCheck.Verdict.MISMATCH ->
                    "Does not match the release signature"
                verdict == com.anonrode.downloader.util.IntegrityCheck.Verdict.NO_PIN ->
                    "No signature pin published yet"
                else -> "Couldn't read the app signature"
            },
            action = { }
        )
    }
}

/**
 * Pinned page footer — shared by the hub AND every drawer page, so no page on
 * this screen ends in dead space under a floating caption.
 *
 * Rhythm contract (2026-10-14 vertical-rhythm fix), top to bottom:
 *   scroll lane [weight(1f)]   → any shortfall between the last card and the
 *                                  caption resolves HERE, inside the lane,
 *                                  never below it;
 *   + 12.dp (Spacing.md)       → lane-to-caption breathing, so fully-scrolled
 *                                  content never abuts the caption;
 *   + caption line             → 11sp (Type.caption), centered, same string
 *                                  source as the old in-hub footer;
 *   + 24.dp (Spacing.xl)       → below the caption;
 *   + navigationBarsPadding    → claimed by the page column above, so the
 *                                  24dp is measured from the top of the
 *                                  navigation-bar inset — the caption always
 *                                  clears the gesture bar.
 * Color note: TextMuted token at its natural weight — the old dim-slate
 * literal (0xFF3D4453) sat at ~2.3:1 contrast, illegible for 11sp text; the
 * token reads as the same "quiet metadata" without inventing a ninth color.
 */
@Composable
private fun SettingsPinnedFooter(uiState: com.anonrode.downloader.viewmodel.HomeUiState) {
    Text(
        text = "Anonrode v${BuildConfig.VERSION_NAME} · " +
            "${uiState.freeStorageGb} GB free of ${uiState.totalStorageGb} GB",
        fontSize = Type.caption.fontSize,
        color = TextMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.xl),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

/** Playback drawer: the old Media & Quality section (quality chips, subtitles,
 *  language — verbatim controls) plus the player defaults that previously had
 *  stored prefs but no home on this screen. */
@Composable
internal fun SettingsPlaybackSection(state: SettingsState) {
    val context = LocalContext.current

    // Player defaults live outside SettingsState (they persist straight into
    // MediaPlayerPrefs — same file the player reads at open).
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(MediaPlayerPrefs.getPlaybackSpeed(context)) }
    var allowBoost by rememberSaveable { mutableStateOf(MediaPlayerPrefs.getAllowVolumeBoost(context)) }

    SettingsCategoryHeader(title = "Video")
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

    SettingsCategoryHeader(title = "Subtitles")
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

    SettingsCategoryHeader(title = "Player Defaults")
    SettingsCard {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Column {
                    Text("Default Playback Speed", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("The player opens at this speed", fontSize = Type.caption.fontSize, color = TextMuted)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { s ->
                    val isSel = playbackSpeed == s
                    FilterChip(
                        selected = isSel,
                        onClick = {
                            playbackSpeed = s
                            MediaPlayerPrefs.setPlaybackSpeed(context, s)
                        },
                        label = { Text("${s}×", fontSize = Type.label.fontSize, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
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
            icon = Icons.Rounded.VolumeUp,
            title = "Allow 150% Volume Boost",
            subtitle = "Enables the loudness-enhancer boost above 100% in the player",
            checked = allowBoost,
            onCheckedChange = {
                allowBoost = it
                MediaPlayerPrefs.setAllowVolumeBoost(context, it)
            }
        )
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
                                    // QA-V3 F5: a manual sync must behave like the
                                    // auto path — refresh the sync clock, then run
                                    // the post-activation hooks so announcements/
                                    // gates/engagement picked up by this payload
                                    // render immediately (docs promise this button
                                    // doubles as the render check).
                                    context.getSharedPreferences(
                                        com.anonrode.downloader.AnonApp.PREFS_SERVERLESS,
                                        android.content.Context.MODE_PRIVATE
                                    ).edit()
                                        .putLong(
                                            com.anonrode.downloader.AnonApp.KEY_LAST_RULES_SYNC,
                                            System.currentTimeMillis()
                                        )
                                        .apply()
                                    (context.applicationContext as? com.anonrode.downloader.AnonApp)
                                        ?.runPostActivationHooks()
                                    Toast.makeText(context, "Synced fresh logic: $ver", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Sync error: $ver", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AccentPrimary.copy(alpha = 0.16f),
                            contentColor = AccentPrimary
                        ),
                        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(Radius.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Sync Now", fontSize = Type.caption.fontSize, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        // Core channel: MASTER (nightly) gets YouTube's bot-check/client fixes
        // weeks before a tagged release ships them (upstream checked 2026-10-14:
        // latest STABLE was 2026.08.19 — two months of player-client maintenance
        // behind master). The choice persists and drives BOTH this button and
        // the engine's daily auto-update (YtDlpCore), with an automatic
        // cross-channel fallback whenever the chosen channel errors out.
        var ytdlpChannel by rememberSaveable { mutableStateOf(YtDlpCore.savedChannelName(context)) }
        LaunchedEffect(Unit) {
            state.ytdlpVersion = YtDlpCore.currentVersion(context)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Core channel",
                color = TextSecondary,
                fontSize = Type.caption.fontSize,
                modifier = Modifier.weight(1f)
            )
            listOf("master" to "Nightly", "stable" to "Stable").forEach { (name, label) ->
                val selected = ytdlpChannel == name
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.full))
                        .background(if (selected) AccentPrimary else SurfaceElevated)
                        .border(1.dp, if (selected) AccentPrimary else BorderHairline, RoundedCornerShape(Radius.full))
                        .clickable {
                            if (!selected) {
                                ytdlpChannel = name
                                YtDlpCore.setChannel(context, name)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) BackgroundDark else TextSecondary,
                        fontSize = Type.caption.fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(Spacing.xs))
            }
        }

        SettingsActionRow(
            icon = Icons.Rounded.Refresh,
            title = "yt-dlp Core Engine",
            subtitle = state.ytdlpVersion?.let { "Installed version: $it • IG/TikTok/FB fixes" }
                ?: "Over-the-air extractor fixes for IG/TikTok/FB",
            action = {
                if (state.isUpdatingYtDlp) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentPrimary)
                } else {
                    FilledTonalButton(
                        onClick = {
                            state.isUpdatingYtDlp = true
                            scope.launch(Dispatchers.IO) {
                                // YtDlpCore: saved channel first, automatic
                                // fallback to the other channel on error, and
                                // the post-update version in the toast. The
                                // old inline call reported a guessed message
                                // and never confirmed what actually landed.
                                val outcome = YtDlpCore.updateNow(context)
                                withContext(Dispatchers.Main) {
                                    state.isUpdatingYtDlp = false
                                    state.ytdlpVersion = outcome.version
                                    Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AccentPrimary.copy(alpha = 0.16f),
                            contentColor = AccentPrimary
                        ),
                        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(Radius.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Update Core", fontSize = Type.caption.fontSize, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }
}

/** Downloads drawer: parallelism/limits (sliders lifted verbatim from the old
 *  Engine + Network sections) and the download-flow switches from General +
 *  Network — all instant-persisting exactly as before. */
@Composable
internal fun SettingsDownloadsSection(state: SettingsState) {
    SettingsCategoryHeader(title = "Tasks & Speed")
    SettingsCard {
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

        // Per-file parallel sockets — the control the 2026-10-14 re-group
        // dropped (state/snapshot/persist plumbing survived, so the setting
        // could only change out-of-band — audit F-5). Restored verbatim from
        // the old Engine & Performance slider; writes through the same
        // snapshot → persist() path as every neighboring row.
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
                        Text("Parallel Sockets (Turbo & Aria2c)", fontSize = Type.body.fontSize, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("High-speed segmented CDN connections (1–16)", fontSize = Type.caption.fontSize, color = TextMuted)
                    }
                }
                Text("${state.sockets} conns", fontSize = Type.label.fontSize, fontWeight = FontWeight.Bold, color = AccentPrimary)
            }
            Slider(
                value = state.sockets.toFloat(),
                onValueChange = { state.sockets = it.toInt() },
                onValueChangeFinished = { state.persist() },
                valueRange = 1f..16f,
                // 16 integer stops = 14 intermediate steps (the old 15 was
                // off-by-one and let the thumb land on non-integer sockets).
                steps = 14,
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
    }

    SettingsCategoryHeader(title = "Behavior & Intake")
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
                state.persist()
            }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

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
    }
}

/** Privacy & Logs drawer: content filter, the debug switch (state existed but
 *  had no UI anywhere — an orphan now homed), then the verbatim Diagnostics
 *  card (retention + log share). */
@Composable
internal fun SettingsLogsSection(state: SettingsState) {
    SettingsCategoryHeader(title = "Privacy")
    SettingsCard {
        SettingsSwitchRow(
            icon = Icons.Rounded.Shield,
            title = "Filter Explicit Content",
            subtitle = "Hide adult, nude, and x-rated titles from Trending and Genre feeds (search is never filtered)",
            checked = state.filterExplicit,
            onCheckedChange = {
                state.filterExplicit = it
                state.persist()
            }
        )

        HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(horizontal = Spacing.md))

        SettingsSwitchRow(
            icon = Icons.Rounded.BugReport,
            title = "Debug Logging",
            subtitle = "Verbose engine internals in the activity log (off = normal level)",
            checked = state.debugLogging,
            onCheckedChange = { state.debugLogging = it; state.persist() }
        )
    }

    SettingsDiagnosticsSection(state = state)
}

// Engine & Performance section retired 2026-10-14: its sliders live in the
// Downloads drawer (parallel downloads / per-file sockets / storage guard) —
// per-file sockets only actually re-landed there 2026-10-18 (the re-group
// shipped without it, leaving the state plumbing unreachable; audit F-5). Its
// Wi-Fi gates moved to the Connections drawer (see SettingsConnectionsSection).

// Media & Quality section retired 2026-10-14: quality chips, subtitle
// download and language moved to the Playback drawer (SettingsPlaybackSection).

/** Language codes fed to yt-dlp --sub-langs and matched against embedded
 *  track languages in the player ("all" is a valid yt-dlp selector). */
internal val SUBTITLE_LANGS = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "pt" to "Portuguese",
    "ar" to "Arabic", "hi" to "Hindi", "zh" to "Chinese", "ja" to "Japanese",
    "ko" to "Korean", "all" to "All languages"
)

/** Updates & Reliability drawer: the self-healing card (sync + yt-dlp core,
 *  verbatim), the remote-reporting kill-switch wired to RemoteLogger's real
 *  pref, and the app-update rows lifted verbatim from the old About section
 *  (its device-storage/version rows became the hub footer). */
@Composable
internal fun SettingsUpdatesSection(
    state: SettingsState,
    rulesVersion: String,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    var remoteReporting by rememberSaveable {
        mutableStateOf(com.anonrode.downloader.util.RemoteLogger.isEnabled(context))
    }

    SettingsSelfHealingSection(
        state = state,
        rulesVersion = rulesVersion,
        scope = scope
    )

    SettingsCategoryHeader(title = "Remote Error Reporting")
    SettingsCard {
        SettingsSwitchRow(
            icon = Icons.Rounded.CloudUpload,
            title = "Send Fatal Error Reports",
            subtitle = "Fatal crashes & stall dumps only — AES-encrypted, capped at 4 KB, sent to our own server. The server stores ciphertext it cannot read.",
            checked = remoteReporting,
            onCheckedChange = {
                remoteReporting = it
                com.anonrode.downloader.util.RemoteLogger.setEnabled(context, it)
            }
        )
    }

    SettingsCategoryHeader(title = "App Updates")
    SettingsCard {
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

// Network & Privacy section retired 2026-10-14: clipboard detection and
// completion notifications moved to the Downloads drawer, the global speed
// limit slider too (SettingsDownloadsSection).

/** Connections drawer: the wire — retries, stall behavior, HLS, torrents and
 *  the Wi-Fi gates (Wi-Fi switches lifted verbatim from the old Engine
 *  section; everything else is the retired Torrents section verbatim). */
@Composable
internal fun SettingsConnectionsSection(state: SettingsState) {
    SettingsCategoryHeader(title = "Torrents & Peers")
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
    }

    SettingsCategoryHeader(title = "Retry & Stall")
    SettingsCard {
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
    }

    SettingsCategoryHeader(title = "Streams & Data")
    SettingsCard {
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

// Network & Privacy section retired 2026-10-14: clipboard detection, completion
// notifications and the global speed limit moved to the Downloads drawer
// (SettingsDownloadsSection) — filter moves to Privacy & Logs.

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
