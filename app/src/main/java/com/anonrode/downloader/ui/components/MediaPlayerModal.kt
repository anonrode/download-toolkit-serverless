package com.anonrode.downloader.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.anonrode.downloader.R
import com.anonrode.downloader.ui.theme.Spacing
import com.anonrode.downloader.ui.theme.StatusError
import kotlinx.coroutines.delay
import java.io.File

/**
 * The public context the player needs beyond the single file being opened.
 * [queuePeerPaths] is the Next/Previous list (Downloads screen order);
 * [onPlayFile] asks the parent to point its active task at a peer path.
 */
data class MediaPlayerContext(
    val filePath: String,
    val title: String,
    val queuePeerPaths: List<String> = emptyList(),
    val onPlayFile: (String) -> Unit = {}
)

private val SIDECAR_SUBTITLE_EXTS = listOf("srt", "vtt")
private val AUDIO_EXTS = listOf("mp3", "m4a", "aac", "wav", "flac", "opus", "ogg")
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

// The player is a permanently BLACK canvas regardless of app theme — the
// theme tokens invert on it in Light mode, so the palette is pinned here.
private val PlayerAccent = Color(0xFFFFFFFF)
private val PlayerTextSecondary = Color(0xFF94A3B8)
private val PlayerSurface = Color(0xFF101216)
private val PlayerSurfaceElevated = Color(0xFF181B22)

/**
 * Full-screen in-app player, rebuilt around one rule: ONE ExoPlayer instance
 * for the whole modal lifetime. Switching files (Next/Previous/auto-advance)
 * calls setMediaItem() on the same player instead of recreating it — the
 * PlayerView stays attached the entire time, which removes the released-
 * player-while-attached crash the old per-file player had, and switches are
 * instant with no surface teardown.
 *
 * The UI is deliberately basic: one layout for every orientation — black
 * surface, letterboxed video, tap anywhere to toggle controls, auto-hide
 * after 3s. Transport (prev / -10s / play / +10s / next), a seek bar, and
 * four chips: speed (tap to cycle), audio track, subtitles, and a
 * display-framing cycle (Fit -> Crop -> Stretch). No PiP, no
 * brightness/volume sliders — hardware keys and the system handle those.
 */
@OptIn(UnstableApi::class)
@Composable
fun MediaPlayerModal(
    filePath: String,
    title: String,
    onDismiss: () -> Unit
) {
    MediaPlayerModalImpl(
        ctx = MediaPlayerContext(filePath = filePath, title = title),
        onDismiss = onDismiss
    )
}

@OptIn(UnstableApi::class)
@Composable
fun MediaPlayerModal(
    ctx: MediaPlayerContext,
    onDismiss: () -> Unit
) {
    MediaPlayerModalImpl(ctx = ctx, onDismiss = onDismiss)
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPlayerModalImpl(
    ctx: MediaPlayerContext,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val componentActivity = context as? ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current

    // Listeners are registered once against the single player; they read the
    // latest ctx/onDismiss through these holders so a file switch never has
    // to re-register anything.
    val currentCtx by rememberUpdatedState(ctx)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val file = remember(ctx.filePath) { File(ctx.filePath) }

    // Missing file (deleted after completion): skip forward to the next
    // playable peer; only close when nothing is left. Checked in an effect,
    // never during composition.
    LaunchedEffect(ctx.filePath) {
        if (!file.exists()) {
            val c = currentCtx
            val next = c.queuePeerPaths
                .drop(c.queuePeerPaths.indexOf(c.filePath) + 1)
                .firstOrNull { File(it).exists() }
            if (next != null) c.onPlayFile(next) else currentOnDismiss()
        }
    }

    // The modal draws edge-to-edge black; system-bar icons must stay LIGHT
    // regardless of the app theme while it is up. On dismiss, restore the
    // theme-appropriate style MainActivity applied before.
    val prefs: SharedPreferences? = remember {
        runCatching { context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE) }
            .getOrNull()
    }
    DisposableEffect(componentActivity) {
        componentActivity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        onDispose {
            // Drop any fullscreen rotation and bring the system bars back so
            // the rest of the app isn't stuck in landscape.
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            val theme = prefs?.getString("pref_theme_mode", "dark") ?: "dark"
            val restoreStyle = if (theme.equals("light", ignoreCase = true)) {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            } else {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            }
            componentActivity?.enableEdgeToEdge(
                statusBarStyle = restoreStyle,
                navigationBarStyle = restoreStyle
            )
        }
    }

    val ext = file.extension.lowercase()
    val isAudio = ext in AUDIO_EXTS

    // ---- Persisted prefs ----
    val initialSpeed = remember { MediaPlayerPrefs.getPlaybackSpeed(context) }
    val initialSubtitle = remember { MediaPlayerPrefs.getSubtitleTrack(context) }

    // ---- UI state ----
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(initialSpeed) }
    var audioTrackLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentAudioLabel by remember { mutableStateOf<String?>(null) }
    var subtitleOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSubtitleLabel by remember { mutableStateOf<String?>(null) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var tracksLoaded by remember { mutableStateOf(false) }
    // Fullscreen: rotate to landscape + hide system bars, YouTube-style.
    var isFullscreen by remember { mutableStateOf(false) }
    // Display framing cycle: FIT (letterbox, default) -> ZOOM (center-crop
    // to fill) -> STRETCH (FILL — distorts the aspect to fill the frame)
    // -> back to FIT. Display-only: the file is never re-encoded or
    // touched.
    // Session-only, no persistence. AspectRatioFrameLayout re-measures on
    // every layout pass, so rotation reframes automatically — no cached
    // dimensions on this side.
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // ---- Player + session: ONE instance for the modal's whole lifetime ----
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(DefaultDataSource.Factory(context))
            )
            .setTrackSelector(DefaultTrackSelector(context))
            .build()
            .apply {
                playWhenReady = true
                playbackParameters = PlaybackParameters(initialSpeed)
            }
    }
    val mediaSession = remember {
        // Scoped to the modal lifetime — the OS picks it up for media-button
        // / Bluetooth intents; no persistent notification needed.
        MediaSession.Builder(context, exoPlayer).build()
    }

    // MediaItem for the current file. Embedded subtitle tracks (MKV multi-
    // language subs) and sidecar .srt/.vtt siblings are attached together as
    // SubtitleConfigurations so ExoPlayer treats them uniformly.
    val mediaItem = remember(ctx.filePath) {
        val builder = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(ctx.filePath)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ctx.title)
                    .setArtist(file.parentFile?.name ?: "")
                    .build()
            )
        val sidecars = SIDECAR_SUBTITLE_EXTS.mapNotNull { extName ->
            val sibling = File(file.parentFile, "${file.nameWithoutExtension}.$extName")
            if (sibling.exists() && sibling.canRead()) {
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sibling))
                    .setMimeType(if (extName == "vtt") "text/vtt" else "application/x-subrip")
                    .setLanguage("und")
                    .setLabel(sibling.name)
                    .setSelectionFlags(0)
                    .build()
            } else null
        }
        if (sidecars.isNotEmpty()) builder.setSubtitleConfigurations(sidecars)
        builder.build()
    }

    // File switch: point the SAME player at the new item. PlayerView never
    // detaches, so Next/Previous is a seamless in-place swap. Per-file UI
    // state resets here so nothing leaks across files.
    var lastLoadedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaItem) {
        // The player still holds the PREVIOUS item at this point, so its
        // position/duration are the leaving file's — persist them before the
        // swap overwrites them.
        val leaving = lastLoadedPath
        if (leaving != null && leaving != ctx.filePath) {
            runCatching {
                PlaybackPositions.save(context, leaving, exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
        // Resume where the user left off the last time this file was open;
        // 0 = start fresh (no saved position, or watched to the end).
        val resumeMs = PlaybackPositions.get(context, ctx.filePath)?.positionMs ?: 0L
        if (resumeMs > 0) exoPlayer.setMediaItem(mediaItem, resumeMs) else exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        lastLoadedPath = ctx.filePath
        currentPosition = resumeMs
        duration = 0L
        isPlaying = true
        tracksLoaded = false
        playerError = null
        audioTrackLabels = emptyList()
        currentAudioLabel = null
        subtitleOptions = emptyList()
        currentSubtitleLabel = initialSubtitle
    }

    // One listener for the player's lifetime.
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        duration = exoPlayer.duration.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> {
                        // Watched to the end: drop the saved position so a
                        // reopen starts from the beginning.
                        PlaybackPositions.clear(context, currentCtx.filePath)
                        // Auto-advance to the next playable peer; missing
                        // files are skipped. At the true end the player just
                        // sits — no dismiss, the user can replay or close.
                        val c = currentCtx
                        val next = c.queuePeerPaths
                            .drop(c.queuePeerPaths.indexOf(c.filePath) + 1)
                            .firstOrNull { File(it).exists() }
                        if (next != null) c.onPlayFile(next)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Escape hatch for unsupported codecs / broken streams:
                // hand the file to the system player, then close.
                playerError = error.errorCodeName
                playExternal(context, File(currentCtx.filePath))
                currentOnDismiss()
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Audio tracks for the picker.
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val labels = audioGroups.map { g ->
                    val fmt = g.mediaTrackGroup.getFormat(0)
                    buildString {
                        fmt.language?.let { append(it.uppercase()) }
                        val br = fmt.bitrate
                        if (br > 0) append(" • ").append(br / 1000).append(" kbps")
                        val ch = fmt.channelCount
                        if (ch > 0) append(" • ").append(ch).append("ch")
                    }.ifBlank { "Track ${g.mediaTrackGroup.getFormat(0).id}" }
                }
                audioTrackLabels = labels
                if (currentAudioLabel == null && audioGroups.isNotEmpty()) {
                    currentAudioLabel = labels.firstOrNull()
                }

                // Subtitle tracks (embedded + sidecar both surface here).
                val subGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val subLabels = mutableListOf("Off")
                subGroups.forEach { g ->
                    val fmt = g.mediaTrackGroup.getFormat(0)
                    subLabels += fmt.label
                        ?: fmt.language?.uppercase()
                        ?: "Track ${fmt.id}"
                }
                subtitleOptions = subLabels
                // Apply the remembered subtitle pick once per file.
                if (currentSubtitleLabel != null && !tracksLoaded) {
                    applySubtitleByLabel(exoPlayer, tracks, currentSubtitleLabel!!, enable = true)
                }
                tracksLoaded = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Progress polling — ExoPlayer has no Compose-friendly state Flow.
    LaunchedEffect(exoPlayer) {
        var saveTick = 0
        while (true) {
            try {
                if (exoPlayer.isPlaying) {
                    currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val d = exoPlayer.duration
                    if (d > 0) duration = d
                    // Persist the position every ~5s of playback so a crash
                    // or app kill loses at most a few seconds of progress.
                    if (++saveTick % 10 == 0) {
                        PlaybackPositions.save(context, currentCtx.filePath, currentPosition, duration)
                    }
                }
            } catch (_: Exception) { /* race during teardown */ }
            delay(500)
        }
    }

    // Auto-hide controls after 3s of playback.
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    // Fullscreen toggle: SENSOR_LANDSCAPE (not USER_LANDSCAPE) so the tap
    // works even with system rotation locked. MainActivity declares
    // orientation in configChanges, so the rotation does not recreate the
    // activity or disturb the player.
    LaunchedEffect(isFullscreen, activity) {
        val act = activity ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (isFullscreen) {
            showControls = true
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Release exactly once: when the modal leaves composition or the host
    // lifecycle is destroyed. The player is never released mid-life (file
    // switches reuse it), so there is no released-player race anywhere.
    DisposableEffect(lifecycleOwner, exoPlayer) {
        // Persist the position before any teardown: currentPosition throws
        // once the player is released, and a kill right after backgrounding
        // must not lose the last watched minute.
        val savePosition = {
            runCatching {
                PlaybackPositions.save(context, currentCtx.filePath, exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                    savePosition()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    savePosition()
                    try { exoPlayer.release() } catch (_: Exception) {}
                    try { mediaSession.release() } catch (_: Exception) {}
                }
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            savePosition()
            try { exoPlayer.release() } catch (_: Exception) {}
            try { mediaSession.release() } catch (_: Exception) {}
        }
    }

    // Next/Previous: step through peers, skipping files that no longer
    // exist. The parent's onPlayFile just repoints its active task; this
    // modal is recomposed with the new path and the same player swaps media.
    val hasNext = remember(ctx.queuePeerPaths, ctx.filePath) {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        ctx.queuePeerPaths.drop(idx + 1).any { File(it).exists() }
    }
    val hasPrev = remember(ctx.queuePeerPaths, ctx.filePath) {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        idx > 0 && ctx.queuePeerPaths.take(idx).any { File(it).exists() }
    }
    val playNext: () -> Unit = {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        val next = ctx.queuePeerPaths.drop(idx + 1).firstOrNull { File(it).exists() }
        if (next != null) ctx.onPlayFile(next)
    }
    val playPrev: () -> Unit = {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        val prev = if (idx > 0) ctx.queuePeerPaths.take(idx).lastOrNull { File(it).exists() } else null
        if (prev != null) ctx.onPlayFile(prev)
    }

    // ---- UI: one layout for every orientation ----
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        // This Dialog is its OWN window. Edge-to-edge fitting and bar hiding
        // applied to the activity's window never reach it, so the dialog
        // stayed inset inside the system bars and the app UI behind (white
        // in light theme) showed through at the edges. Take the dialog's
        // window (exposed by the dialog's root layout) and make IT
        // edge-to-edge + immersive: content draws behind the bars, and the
        // bars hide while the player is up — a swipe from an edge reveals
        // them transiently. The window dies with the dialog, so there is
        // nothing to restore here; the activity-side restore above covers
        // the window that survives.
        val dialogWindow = (LocalView.current as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.let { win ->
                WindowCompat.setDecorFitsSystemWindows(win, false)
                win.statusBarColor = android.graphics.Color.TRANSPARENT
                win.navigationBarColor = android.graphics.Color.TRANSPARENT
                val controller = WindowInsetsControllerCompat(win, win.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {}
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showControls = !showControls
                }
        ) {
            // Render surface. PlayerView is created ONCE and holds the same
            // player for the modal's lifetime — update only syncs state the
            // Compose side can change.
            AndroidView(
                factory = { viewCtx ->
                    // media3-ui has no programmatic setter for surface_type —
                    // it is only read from XML at construction — so the view
                    // comes from a layout resource pinned to TextureView
                    // instead of the default SurfaceView: a SurfaceView
                    // punches a hole through this Dialog's window and its
                    // surface sits BEHIND the window, so the letterbox area
                    // (transparent) let the app UI bleed through around the
                    // video in fullscreen. TextureView renders in the normal
                    // view hierarchy with no hole-punch, so the black
                    // background below actually covers the whole modal. Fine
                    // here because playback is local files only (no DRM).
                    (android.view.LayoutInflater.from(viewCtx)
                        .inflate(R.layout.player_modal_texture_view, null) as PlayerView).apply {
                        useController = false
                        player = exoPlayer
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    // Live-apply the Fit/Crop cycle; also re-asserted after
                    // any recomposition so the mode never drifts.
                    view.resizeMode = resizeMode
                },
                modifier = if (isAudio) Modifier.size(1.dp) else Modifier.fillMaxSize()
            )

            if (isAudio) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(PlayerSurfaceElevated, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = PlayerAccent,
                            modifier = Modifier.size(60.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    Text(
                        text = ctx.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Spacing.xl)
                    )
                    Text(
                        text = "Audio • " + ext.uppercase(),
                        color = PlayerTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            playerError?.let { err ->
                Text(
                    text = "Player error: $err",
                    color = StatusError,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = Spacing.xxxl)
                )
            }

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // ---- Top bar: close | title | fullscreen, external ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerCircleButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Close",
                            onClick = onDismiss
                        )
                        Text(
                            text = ctx.title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = Spacing.md)
                        )
                        if (!isAudio) {
                            PlayerCircleButton(
                                icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                                onClick = { isFullscreen = !isFullscreen }
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                        }
                        PlayerCircleButton(
                            icon = Icons.Rounded.OpenInNew,
                            contentDescription = "Play in external app",
                            onClick = {
                                playExternal(context, file)
                                onDismiss()
                            }
                        )
                    }

                    // ---- Center transport ----
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerCircleButton(
                            icon = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            onClick = playPrev,
                            enabled = hasPrev,
                            size = 44.dp,
                            iconSize = 24.dp
                        )
                        PlayerCircleButton(
                            icon = Icons.Rounded.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            onClick = {
                                val target = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            },
                            size = 48.dp,
                            iconSize = 28.dp
                        )
                        IconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(68.dp)
                                .background(PlayerAccent, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        PlayerCircleButton(
                            icon = Icons.Rounded.Forward10,
                            contentDescription = "Forward 10 seconds",
                            onClick = {
                                val target = (exoPlayer.currentPosition + 10_000).coerceAtMost(exoPlayer.duration)
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            },
                            size = 48.dp,
                            iconSize = 28.dp
                        )
                        PlayerCircleButton(
                            icon = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            onClick = playNext,
                            enabled = hasNext,
                            size = 44.dp,
                            iconSize = 24.dp
                        )
                    }

                    // ---- Bottom: seek, then speed / audio / subtitles ----
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDuration(currentPosition),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatDuration(duration),
                                color = PlayerTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Slider(
                            value = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                            onValueChange = { frac ->
                                val target = (frac * duration).toLong()
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = PlayerAccent,
                                activeTrackColor = PlayerAccent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            PlayerChip(
                                label = formatSpeed(playbackSpeed),
                                selected = playbackSpeed != 1.0f,
                                onClick = {
                                    val nextSpeed = PLAYBACK_SPEEDS[
                                        (PLAYBACK_SPEEDS.indexOf(playbackSpeed) + 1) % PLAYBACK_SPEEDS.size
                                    ]
                                    playbackSpeed = nextSpeed
                                    exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
                                    MediaPlayerPrefs.setPlaybackSpeed(context, nextSpeed)
                                },
                                leading = Icons.Rounded.Speed
                            )
                            PlayerChip(
                                label = currentAudioLabel ?: "Audio",
                                selected = false,
                                onClick = { showAudioSheet = true },
                                leading = Icons.Filled.GraphicEq
                            )
                            PlayerChip(
                                label = currentSubtitleLabel ?: "Subtitles",
                                selected = currentSubtitleLabel != null,
                                onClick = { showSubtitleSheet = true },
                                leading = if (currentSubtitleLabel == null) Icons.Rounded.SubtitlesOff else Icons.Filled.Subtitles
                            )
                            PlayerChip(
                                label = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                                    else -> "Fit"
                                },
                                selected = resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                onClick = {
                                    // Fit -> Crop -> Stretch -> Fit. Stretch is
                                    // Media3's FILL mode: it distorts the aspect
                                    // ratio to fill the frame — offered because
                                    // sometimes filling the screen beats black
                                    // bars. Display-only: the file is never
                                    // re-encoded or touched.
                                    resizeMode = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                leading = Icons.Rounded.AspectRatio
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAudioSheet) {
        BottomChoiceSheet(
            title = "Audio Track",
            options = audioTrackLabels,
            selected = currentAudioLabel,
            onPick = { label ->
                currentAudioLabel = label
                val groups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val matchIdx = audioTrackLabels.indexOf(label)
                if (matchIdx in groups.indices) {
                    val group = groups[matchIdx].mediaTrackGroup
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group, 0))
                        .build()
                }
                showAudioSheet = false
            },
            onDismiss = { showAudioSheet = false }
        )
    }
    if (showSubtitleSheet) {
        BottomChoiceSheet(
            title = "Subtitles",
            options = subtitleOptions,
            selected = currentSubtitleLabel,
            onPick = { label ->
                val off = label == "Off"
                currentSubtitleLabel = if (off) null else label
                applySubtitleByLabel(exoPlayer, exoPlayer.currentTracks, label, enable = !off)
                MediaPlayerPrefs.setSubtitleTrack(context, if (off) null else label)
                showSubtitleSheet = false
            },
            onDismiss = { showSubtitleSheet = false }
        )
    }
}

private fun formatSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

private fun applySubtitleByLabel(
    player: ExoPlayer,
    tracks: Tracks,
    label: String,
    enable: Boolean
) {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    if (textGroups.isEmpty()) return
    val builder = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enable)
    if (enable) {
        val matchIdx = textGroups.indexOfFirst { g ->
            val fmt = g.mediaTrackGroup.getFormat(0)
            fmt.label == label ||
                (fmt.label == null && fmt.language?.uppercase() == label) ||
                ("Track ${fmt.id}" == label)
        }
        if (matchIdx < 0) return
        val group = textGroups[matchIdx].mediaTrackGroup
        builder.setOverrideForType(TrackSelectionOverride(group, 0))
    }
    player.trackSelectionParameters = builder.build()
}

/** One circle-button style for the whole player. */
@Composable
private fun PlayerCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Compact action chip for the bottom row (speed / audio / subtitles / fit-crop). */
@Composable
private fun PlayerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: ImageVector
) {
    val bg = if (selected) PlayerAccent else Color.White.copy(alpha = 0.10f)
    val fg = if (selected) Color.Black else Color.White
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            leading,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BottomChoiceSheet(
    title: String,
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(PlayerSurface)
                    .padding(Spacing.lg)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { opt ->
                        val isSelected = opt == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) PlayerSurfaceElevated else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onPick(opt) }
                                )
                                .padding(horizontal = Spacing.md, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = opt,
                                color = if (isSelected) PlayerAccent else Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun playExternal(context: Context, file: File) {
    try {
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            in AUDIO_EXTS -> "audio/*"
            "mp4", "mkv", "avi", "mov", "webm", "ts" -> "video/*"
            else -> "*/*"
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Play with"))
    } catch (_: Exception) { /* external player unavailable */ }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(java.util.Locale.US, h, m, s)
    } else {
        "%02d:%02d".format(java.util.Locale.US, m, s)
    }
}
