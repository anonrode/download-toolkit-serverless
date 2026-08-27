package com.anonrode.downloader.ui.components

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.anonrode.downloader.ui.theme.AccentPrimary
import com.anonrode.downloader.ui.theme.BackgroundDark
import com.anonrode.downloader.ui.theme.Spacing
import com.anonrode.downloader.ui.theme.StatusError
import com.anonrode.downloader.ui.theme.SurfaceCard
import com.anonrode.downloader.ui.theme.SurfaceElevated
import com.anonrode.downloader.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.io.File

/**
 * The public context the player needs to know about beyond the single file
 * being opened. Kept as a data class so adding fields (resume position, audio
 * track override, etc.) doesn't churn the call site.
 */
data class MediaPlayerContext(
    val filePath: String,
    val title: String,
    /** Other file paths the user can step through with Next/Previous. Order
     *  matches the Downloads screen so the queue feels familiar. The modal
     *  does not own this list — it asks the parent to play by file path so
     *  the parent can update its own state (activePlaybackTask). */
    val queuePeerPaths: List<String> = emptyList(),
    /** Parent-driven: modal calls this to switch the active file, then
     *  triggers its own dismiss so the parent re-opens with the new path. */
    val onPlayFile: (String) -> Unit = {}
)

private val SIDECAR_SUBTITLE_EXTS = listOf("srt", "vtt")

/**
 * Full-screen in-app video/audio player. Backed by Media3 ExoPlayer with HLS
 * support, sidecar + embedded subtitle loading, audio focus handed off to the
 * platform via Player.setAudioAttributes(handleAudioFocus = true), and a
 * MediaSession that surfaces the playback to the OS media controls for the
 * lifetime of the modal.
 *
 * Auto-hides controls after 3.5s of play inactivity, preserves the original
 * window brightness on entry and restores it on dismiss, and falls back to
 * the system external player via ACTION_VIEW if ExoPlayer reports an error
 * (unsupported codec, broken HLS, etc.) — same escape hatch the previous
 * VideoView build had.
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

    val file = remember(ctx.filePath) { File(ctx.filePath) }
    // Guard against missing-file playback: dismiss the modal when the file is
    // gone (deleted after download cancellation). Do it in a LaunchedEffect
    // rather than directly during composition, which would write state during
    // snapshot-apply and risk a crash.
    LaunchedEffect(file.exists()) {
        if (!file.exists()) onDismiss()
    }
    if (!file.exists()) return

    // The modal's body is a full-screen Color.Black (video) or a centered
    // placeholder on the same background; the system bars are visible at
    // the top and bottom and the icons need to be LIGHT to stay readable
    // regardless of the app's theme.  When the modal dismisses, restore
    // the theme-aware style MainActivity previously applied (so light-mode
    // users go back to dark icons on near-white bars, dark-mode users go
    // back to light icons on the dark surface).
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
            // Leaving the modal: drop any fullscreen rotation and bring the
            // system bars back so the rest of the app isn't stuck in landscape.
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
    val isAudio = ext in listOf("mp3", "m4a", "aac", "wav", "flac", "opus", "ogg")

    // ---- Persisted prefs ----
    val initialSpeed = remember { MediaPlayerPrefs.getPlaybackSpeed(context) }
    val initialVolume = remember { MediaPlayerPrefs.getMediaVolume(context) }
    val initialBrightness = remember { MediaPlayerPrefs.getWindowBrightness(context) }
    val initialSubtitle = remember { MediaPlayerPrefs.getSubtitleTrack(context) }

    // ---- UI state ----
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(initialSpeed) }
    var mediaVolume by remember { mutableFloatStateOf(initialVolume) }
    var windowBrightness by remember { mutableFloatStateOf(initialBrightness) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var audioTrackLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentAudioLabel by remember { mutableStateOf<String?>(null) }
    var subtitleOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSubtitleLabel by remember { mutableStateOf<String?>(null) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var tracksLoaded by remember { mutableStateOf(false) }
    // Fullscreen (landscape) mode: rotates the device and hides the system
    // bars so the video fills the whole screen, YouTube-style.
    var isFullscreen by remember { mutableStateOf(false) }

    // Fullscreen toggle: rotate to landscape and hide the status/navigation
    // bars so the video fills the whole screen; restore both when toggled off.
    // SENSOR_LANDSCAPE (not USER_LANDSCAPE) so an explicit fullscreen tap works
    // even when the user has system rotation locked to portrait.
    LaunchedEffect(isFullscreen, activity) {
        val act = activity ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (isFullscreen) {
            // Entering fullscreen from the portrait layout: make sure the
            // overlay controls are visible (portrait mode never uses them,
            // so showControls may be stale-false).
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

    // Track the original window brightness so we can restore it on dismiss
    // rather than leaving the slider's last value applied.
    val originalBrightness = remember {
        activity?.window?.attributes?.screenBrightness ?: -1f
    }

    // ---- Player + Session (one instance per file) ----
    val exoPlayer = remember(ctx.filePath) {
        ExoPlayer.Builder(context)
            // Player.setAudioAttributes(handleAudioFocus = true) hands the
            // platform the AudioFocusRequest so music apps duck and resume
            // correctly — no need to also wire AudioManager directly.
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
                // setPlaybackParameters uses ExoPlayer's speed-change path
                // which preserves pitch by default; the docs note this
                // explicitly so a future reader doesn't try to "fix" it
                // with a manual tempo post-process.
                playbackParameters = PlaybackParameters(initialSpeed)
                volume = initialVolume
            }
    }

    val mediaSession = remember(ctx.filePath) {
        // No MediaSessionService is registered — this session is scoped to
        // the modal lifetime, so the system picks it up for transient
        // media-button / Bluetooth intents but no persistent notification
        // is required.
        MediaSession.Builder(context, exoPlayer).build()
    }

    // Build the MediaItem for the playing file. Embedded subtitle tracks
    // (multi-language subs inside an MKV) and sidecar .srt/.vtt siblings
    // are both attached as SubtitleConfigurations on the same MediaItem, so
    // ExoPlayer treats them uniformly.
    val mediaItem = remember(ctx.filePath) {
        val baseUri = Uri.fromFile(file)
        val builder = MediaItem.Builder()
            .setUri(baseUri)
            .setMediaId(ctx.filePath)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ctx.title)
                    .setArtist(file.parentFile?.name ?: "")
                    .build()
            )
        SIDECAR_SUBTITLE_EXTS.forEach { extName ->
            val sibling = File(file.parentFile, "${file.nameWithoutExtension}.$extName")
            if (sibling.exists() && sibling.canRead()) {
                val srtConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sibling))
                    .setMimeType(if (extName == "vtt") "text/vtt" else "application/x-subrip")
                    .setLanguage("und")
                    .setLabel(sibling.name)
                    .setSelectionFlags(0)
                    .build()
                builder.setSubtitleConfigurations(listOf(srtConfig))
            }
        }
        builder.build()
    }

    // Apply the MediaItem and seed the remembered subtitle preference.
    LaunchedEffect(ctx.filePath, mediaItem) {
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        // Fresh per-file state: a Next/Previous switch reuses this same
        // modal composition (the parent just points ctx.filePath at the
        // peer), so stale position/duration/track state from the previous
        // file must not leak into the new one.
        currentPosition = 0L
        duration = 0L
        isPlaying = true
        tracksLoaded = false
        playerError = null
        audioTrackLabels = emptyList()
        currentAudioLabel = null
        subtitleOptions = emptyList()
        currentSubtitleLabel = initialSubtitle
    }

    // Wire the player listener. onPlayerError is the escape hatch — it routes
    // the same way the old VideoView setOnErrorListener did: hand off to the
    // system external player, then dismiss.
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
                        // Auto-advance to the next peer in the queue if there
                        // is one. If we're at the end, the modal just sits
                        // and the user can replay, close, or restart manually.
                        // No onDismiss here — the parent swaps the file in
                        // place (dismissing would close the player).
                        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
                        val nextIdx = idx + 1
                        if (idx >= 0 && nextIdx < ctx.queuePeerPaths.size) {
                            ctx.onPlayFile(ctx.queuePeerPaths[nextIdx])
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Fallback to external player on any decoder / extractor
                // failure — unsupported codec, broken HLS, etc. Same pattern
                // the previous setOnErrorListener used.
                playerError = error.errorCodeName
                playExternal(context, file)
                onDismiss()
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Enumerate audio tracks for the picker.
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
                // Default: first audio track on first play.
                if (currentAudioLabel == null && audioGroups.isNotEmpty()) {
                    val first = audioGroups[0]
                    val group = first.mediaTrackGroup
                    val params = exoPlayer.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group, 0))
                        .build()
                    exoPlayer.trackSelectionParameters = params
                    currentAudioLabel = labels.firstOrNull()
                }

                // Enumerate subtitle tracks. Embedded tracks show up as
                // TRACK_TYPE_TEXT groups; sidecar subtitles do too because
                // they were attached as SubtitleConfigurations above.
                val subGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val subLabels = mutableListOf<String>()
                subLabels += "Off"
                subGroups.forEach { g ->
                    val fmt = g.mediaTrackGroup.getFormat(0)
                    val name = fmt.label
                        ?: fmt.language?.uppercase()
                        ?: "Track ${fmt.id}"
                    subLabels += name
                }
                subtitleOptions = subLabels
                // Apply remembered subtitle track once we know the available
                // groups. Default is off, per the spec.
                if (currentSubtitleLabel != null && !tracksLoaded) {
                    applySubtitleByLabel(
                        exoPlayer,
                        tracks,
                        currentSubtitleLabel!!,
                        enable = true
                    )
                }
                tracksLoaded = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Progress polling. ExoPlayer exposes isPlaying/currentPosition/duration
    // on the public Player API; we poll because there's no Compose-friendly
    // state Flow for it.
    LaunchedEffect(exoPlayer) {
        while (true) {
            try {
                if (exoPlayer.isPlaying) {
                    currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val d = exoPlayer.duration
                    if (d > 0) duration = d
                }
            } catch (_: Exception) { /* ignore — race during teardown */ }
            delay(500)
        }
    }

    // Auto-hide controls after 3.5s of playback.
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500)
            showControls = false
        }
    }

    // Apply window brightness on every change; persist the new value so the
    // user doesn't have to re-set it next time. -1f means "follow system";
    // we don't actually write that to the Window here because the default
    // before any change is to follow system, so the slider starts there.
    LaunchedEffect(windowBrightness) {
        activity?.window?.let { win ->
            val lp = win.attributes
            lp.screenBrightness = if (windowBrightness < 0f) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                windowBrightness
            }
            win.attributes = lp
        }
        MediaPlayerPrefs.setWindowBrightness(context, windowBrightness)
    }

    // Lifecycle: release player + media session when the host lifecycle goes
    // below STARTED. Audio focus is already handled by
    // setAudioAttributes(handleAudioFocus = true), so we don't also touch
    // AudioManager here.
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_DESTROY -> {
                    exoPlayer.release()
                    mediaSession.release()
                }
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { exoPlayer.release() } catch (_: Exception) {}
            try { mediaSession.release() } catch (_: Exception) {}
            // Restore the original window brightness on dismiss so the user's
            // global brightness preference isn't permanently clobbered.
            activity?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = if (originalBrightness < 0f) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    originalBrightness
                }
                win.attributes = lp
            }
        }
    }

    // Navigation helpers: step to the next/prev file in the queue by asking
    // the parent to point its active playback task at the peer. The modal
    // must NOT dismiss afterwards: the parent's onDismiss nulls the same
    // state onPlayFile just wrote, so dismiss-then-reopen never reopened —
    // Next/Previous simply exited the video (user-reported). Instead the
    // parent recomposes this modal with the new ctx.filePath and the
    // remember(ctx.filePath) player swaps in place.
    val playNext: () -> Unit = {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        val nextIdx = idx + 1
        if (idx >= 0 && nextIdx < ctx.queuePeerPaths.size) {
            ctx.onPlayFile(ctx.queuePeerPaths[nextIdx])
        }
    }
    val playPrev: () -> Unit = {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        if (idx > 0) {
            ctx.onPlayFile(ctx.queuePeerPaths[idx - 1])
        }
    }

    // ---- UI ----
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
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
            if (isAudio) {
                // Audio files have no video surface. Keep the same audio
                // visualizer placeholder the previous build had.
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(SurfaceElevated, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = AccentPrimary,
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
                        text = "Audio Playback • " + ext.uppercase(),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            // Player error banner — only shown if the listener caught a
            // recoverable-but-non-fatal error before we routed to the
            // external player.
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

            if (isAudio || isFullscreen) {
            // ExoPlayer render surface. PlayerView owns the SurfaceView and
            // exposes resizeMode for Fit / Fill / Zoom; we suppress its
            // default controller so our Compose overlay is the only UI.
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                        resizeMode = resizeMode
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.resizeMode = resizeMode
                },
                modifier = if (isAudio) Modifier.size(1.dp) else Modifier.fillMaxSize()
            )

            // Animated Overlay Controls
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    // ---- Top Bar ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }

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

                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            if (!isAudio) {
                                IconButton(
                                    onClick = { isFullscreen = !isFullscreen },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(
                                        if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                        contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isAudio) {
                                IconButton(
                                    onClick = {
                                        activity?.let { act ->
                                            try {
                                                val params = PictureInPictureParams.Builder()
                                                    .setAspectRatio(Rational(16, 9))
                                                    .build()
                                                act.enterPictureInPictureMode(params)
                                            } catch (_: Exception) { /* PiP denied; ignore */ }
                                        }
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Rounded.PictureInPicture,
                                        contentDescription = "PiP",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    playExternal(context, file)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Rounded.OpenInNew,
                                    contentDescription = "External Player",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // ---- Center Transport ----
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = playPrev,
                            enabled = ctx.queuePeerPaths.indexOf(ctx.filePath) > 0,
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val target = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                Icons.Rounded.Replay10,
                                contentDescription = "Rewind 10s",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(64.dp)
                                .background(AccentPrimary, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = BackgroundDark,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val target = (exoPlayer.currentPosition + 10_000).coerceAtMost(exoPlayer.duration)
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                Icons.Rounded.Forward10,
                                contentDescription = "Forward 10s",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = playNext,
                            enabled = ctx.queuePeerPaths.indexOf(ctx.filePath) in 0 until ctx.queuePeerPaths.lastIndex,
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // ---- Bottom Controls ----
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
                                color = TextSecondary,
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
                                thumbColor = AccentPrimary,
                                activeTrackColor = AccentPrimary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        // Chip row: speed / audio / subtitles / aspect.
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            items(PLAYBACK_SPEEDS) { sp ->
                                Chip(
                                    label = formatSpeed(sp),
                                    selected = playbackSpeed == sp,
                                    onClick = {
                                        playbackSpeed = sp
                                        exoPlayer.playbackParameters = PlaybackParameters(sp)
                                        MediaPlayerPrefs.setPlaybackSpeed(context, sp)
                                    }
                                )
                            }
                            item {
                                Chip(
                                    label = currentAudioLabel ?: "Audio",
                                    selected = false,
                                    onClick = { showAudioSheet = true },
                                    leading = {
                                        Icon(
                                            Icons.Filled.GraphicEq,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                            item {
                                Chip(
                                    label = currentSubtitleLabel ?: "Subs",
                                    selected = currentSubtitleLabel != null,
                                    onClick = { showSubtitleSheet = true },
                                    leading = {
                                        Icon(
                                            if (currentSubtitleLabel == null) Icons.Rounded.SubtitlesOff else Icons.Filled.Subtitles,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                            item {
                                Chip(
                                    label = ASPECT_LABELS[resizeMode] ?: "Fit",
                                    selected = false,
                                    onClick = {
                                        resizeMode = when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    },
                                    leading = {
                                        Icon(
                                            Icons.Rounded.AspectRatio,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        // Volume + brightness sliders side-by-side.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = when {
                                        mediaVolume <= 0f -> Icons.Rounded.VolumeOff
                                        mediaVolume < 0.5f -> Icons.Rounded.VolumeDown
                                        else -> Icons.Rounded.VolumeUp
                                    },
                                    contentDescription = "Volume",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Slider(
                                    value = mediaVolume,
                                    onValueChange = {
                                        mediaVolume = it
                                        exoPlayer.volume = it
                                        MediaPlayerPrefs.setMediaVolume(context, it)
                                    },
                                    valueRange = 0f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentPrimary,
                                        activeTrackColor = AccentPrimary,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = Spacing.xs)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    Icons.Rounded.Brightness6,
                                    contentDescription = "Brightness",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Slider(
                                    value = if (windowBrightness < 0f) 0f else windowBrightness,
                                    onValueChange = { v ->
                                        windowBrightness = v
                                    },
                                    valueRange = 0f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentPrimary,
                                        activeTrackColor = AccentPrimary,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = Spacing.xs)
                                )
                            }
                        }
                    }
                }
            }
            } else {
                // Portrait video layout: the picture sits in a 16:9 panel at
                // the top (tap to play/pause) and every control lives below
                // it, always visible — the overlay-on-letterbox design was
                // unusable in portrait (user-reported). Fullscreen and audio
                // keep the overlay experience above.
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { viewCtx ->
                                PlayerView(viewCtx).apply {
                                    useController = false
                                    player = exoPlayer
                                    resizeMode = resizeMode
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            update = { view ->
                                view.resizeMode = resizeMode
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Text(
                            text = ctx.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Video • ${ext.uppercase()}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(end = Spacing.sm)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                IconButton(
                                    onClick = { isFullscreen = !isFullscreen },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Rounded.Fullscreen,
                                        contentDescription = "Fullscreen",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    IconButton(
                                        onClick = {
                                            activity?.let { act ->
                                                try {
                                                    val params = PictureInPictureParams.Builder()
                                                        .setAspectRatio(Rational(16, 9))
                                                        .build()
                                                    act.enterPictureInPictureMode(params)
                                                } catch (_: Exception) { /* PiP denied; ignore */ }
                                            }
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Rounded.PictureInPicture,
                                            contentDescription = "PiP",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        playExternal(context, file)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Rounded.OpenInNew,
                                        contentDescription = "External Player",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                                }
                            }
                        }

                        // Transport
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = playPrev,
                                enabled = ctx.queuePeerPaths.indexOf(ctx.filePath) > 0,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val target = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Rounded.Replay10,
                                    contentDescription = "Rewind 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                },
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(AccentPrimary, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = BackgroundDark,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val target = (exoPlayer.currentPosition + 10_000).coerceAtMost(exoPlayer.duration)
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Rounded.Forward10,
                                    contentDescription = "Forward 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            IconButton(
                                onClick = playNext,
                                enabled = ctx.queuePeerPaths.indexOf(ctx.filePath) in 0 until ctx.queuePeerPaths.lastIndex,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Rounded.SkipNext,
                                    contentDescription = "Next",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Seek
                        Column {
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
                                    color = TextSecondary,
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
                                    thumbColor = AccentPrimary,
                                    activeTrackColor = AccentPrimary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Chips: speed / audio / subtitles / aspect
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            items(PLAYBACK_SPEEDS) { speed ->
                                Chip(
                                    label = formatSpeed(speed),
                                    selected = playbackSpeed == speed,
                                    onClick = {
                                        playbackSpeed = speed
                                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                                        MediaPlayerPrefs.setPlaybackSpeed(context, speed)
                                    }
                                )
                            }
                            item {
                                Chip(
                                    label = currentAudioLabel ?: "Audio",
                                    selected = false,
                                    onClick = { showAudioSheet = true },
                                    leading = {
                                        Icon(
                                            Icons.Filled.GraphicEq,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                            item {
                                Chip(
                                    label = currentSubtitleLabel ?: "Subs",
                                    selected = currentSubtitleLabel != null,
                                    onClick = { showSubtitleSheet = true },
                                    leading = {
                                        Icon(
                                            if (currentSubtitleLabel == null) Icons.Rounded.SubtitlesOff else Icons.Filled.Subtitles,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                            item {
                                Chip(
                                    label = ASPECT_LABELS[resizeMode] ?: "Fit",
                                    selected = false,
                                    onClick = {
                                        resizeMode = when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    },
                                    leading = {
                                        Icon(
                                            Icons.Rounded.AspectRatio,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }

                        // Volume + brightness
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = when {
                                        mediaVolume <= 0f -> Icons.Rounded.VolumeOff
                                        mediaVolume < 0.5f -> Icons.Rounded.VolumeDown
                                        else -> Icons.Rounded.VolumeUp
                                    },
                                    contentDescription = "Volume",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Slider(
                                    value = mediaVolume,
                                    onValueChange = {
                                        mediaVolume = it
                                        exoPlayer.volume = it
                                        MediaPlayerPrefs.setMediaVolume(context, it)
                                    },
                                    valueRange = 0f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentPrimary,
                                        activeTrackColor = AccentPrimary,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = Spacing.xs)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    Icons.Rounded.Brightness6,
                                    contentDescription = "Brightness",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Slider(
                                    value = if (windowBrightness < 0f) 0f else windowBrightness,
                                    onValueChange = { v ->
                                        windowBrightness = v
                                    },
                                    valueRange = 0f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentPrimary,
                                        activeTrackColor = AccentPrimary,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = Spacing.xs)
                                )
                            }
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
                applySubtitleByLabel(
                    exoPlayer,
                    exoPlayer.currentTracks,
                    label,
                    enable = !off
                )
                MediaPlayerPrefs.setSubtitleTrack(context, if (off) null else label)
                showSubtitleSheet = false
            },
            onDismiss = { showSubtitleSheet = false }
        )
    }
}

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private val ASPECT_LABELS = mapOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
)

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
        // When the user picked a specific track, force the override so
        // ExoPlayer doesn't fall back to its own first-track default.
        // When they picked "Off", disabling the type alone is enough —
        // an earlier override on a disabled type is never selected, so
        // we don't have to also clear it.
        builder.setOverrideForType(TrackSelectionOverride(group, 0))
    }
    player.trackSelectionParameters = builder.build()
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val bg = if (selected) AccentPrimary else Color.Black.copy(alpha = 0.6f)
    val fg = if (selected) BackgroundDark else Color.White
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (leading != null) {
            leading()
        }
        Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                    .background(SurfaceCard)
                    .padding(Spacing.lg)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )
                options.forEach { opt ->
                    val isSelected = opt == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) SurfaceElevated else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onPick(opt) }
                            )
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = opt,
                            color = if (isSelected) AccentPrimary else Color.White,
                            fontSize = 14.sp
                        )
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
            "mp3", "m4a", "aac", "wav", "flac", "opus", "ogg" -> "audio/*"
            "mp4", "mkv", "avi", "mov", "webm", "ts" -> "video/*"
            else -> "*/*"
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Play with"))
    } catch (_: Exception) { /* external player unavailable; ignore */ }
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
