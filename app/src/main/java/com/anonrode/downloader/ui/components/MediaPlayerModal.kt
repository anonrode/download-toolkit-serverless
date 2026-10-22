package com.anonrode.downloader.ui.components

import com.anonrode.downloader.ui.theme.Type

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.anonrode.downloader.R
import com.anonrode.downloader.data.WatchHistoryStore
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.providers.SubtitleSurfaces
import com.anonrode.downloader.ui.theme.Motion
import com.anonrode.downloader.ui.theme.Radius
import com.anonrode.downloader.ui.theme.Spacing
import com.anonrode.downloader.data.WatchHistoryCore
import com.anonrode.downloader.ui.util.tick
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/**
 * The public context the player needs beyond the single file being opened.
 * [queuePeerPaths] is the Next/Previous list — the completed files of the
 * SAME show, in episode order (DownloadsSorter.playerQueueFor);
 * [onPlayFile] asks the parent to point its active task at a peer path.
 */
data class MediaPlayerContext(
    val filePath: String,
    val title: String,
    val queuePeerPaths: List<String> = emptyList(),
    val onPlayFile: (String) -> Unit = {},
    /** Resolves the task metadata backing a file path (watch history). */
    val taskForPath: (String) -> DownloadTask? = { null },
    /** Opens the EpisodeDrawer with Remaining preselected (end-of-episode). */
    val openRemaining: ((title: String, seriesUrl: String, site: String) -> Unit)? = null
)

/**
 * PiP bridge (mini player): MainActivity overrides the PLATFORM
 * Activity.onPictureInPictureModeChanged callback — the one API that is
 * guaranteed present at minSdk 26 and dispatched on every OS version —
 * and writes the state here; the player reads it and strips all chrome
 * while the activity renders inside the pip bubble. Written via a
 * Compose snapshot state so any recomposition observing it re-renders.
 */
internal object PlayerPipState {
    var inPip by mutableStateOf(false)
        internal set
}

private val SIDECAR_SUBTITLE_EXTS = listOf("srt", "vtt", "ass", "ssa")
private val AUDIO_EXTS = listOf("mp3", "m4a", "aac", "wav", "flac", "opus", "ogg")
/** Still images (last-resort Instagram photo fallbacks) are NOT playable
 *  media: handing an .mp4-named-jpg or a .jpg to ExoPlayer renders the
 *  "error" screen the user got. They render in the same overlay as a zoomed
 *  picture instead of entering the player pipeline at all. */
private val IMAGE_EXTS = listOf("jpg", "jpeg", "png", "webp", "gif")
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * Persisted value of an explicit sheet "Off" (trace 2026-10-21 d6 / matrix
 * row 13). Stored verbatim under MediaPlayerPrefs' subtitle-track string
 * key, it is distinguishable from a MISSING key: null/absent = "never
 * chose" (auto-pick allowed to enable a preferred-language track), the
 * sentinel = "the user turned subs OFF" (a decision that must survive
 * modal reopen — auto-pick never re-enables while it is stored; a later
 * user pick overwrites it with the real label). No parser or library ever
 * emits this string as a format label, so it cannot collide.
 */
internal const val SUBTITLE_PREF_OFF = "__subtitles_off__"

/** Pure derivation of the stored subtitle pref: does it hold the user's
 *  explicit OFF decision (true) or a real label / nothing (false)? Kept
 *  off Compose so the persistence contract is JVM-testable. */
internal fun subtitlePrefHoldsUserOff(stored: String?): Boolean =
    stored == SUBTITLE_PREF_OFF

/**
 * One candidate embedded/sidecar subtitle track, flattened to the fields
 * the auto-pick rule needs (kept away from Media3 types so the rule is
 * JVM-testable without Robolectric).
 */
data class SubTrackInfo(val language: String?, val label: String?, val isDefault: Boolean)

// 639-1 codes (yt-dlp / settings) -> every spelling a container track may
// carry: 639-2/T, the obsolete 639-2/B bibliographic codes MKVs love
// ("fre"/"chi"), and the plain English name labels fansubs write.
private val LANG_ALIASES = mapOf(
    "en" to setOf("en", "eng", "english"),
    "es" to setOf("es", "spa", "spanish", "espanol", "castellano"),
    "fr" to setOf("fr", "fra", "fre", "french", "francais", "français"),
    "pt" to setOf("pt", "por", "portuguese", "portugues", "português"),
    "ar" to setOf("ar", "ara", "arabic", "العربية"),
    "hi" to setOf("hi", "hin", "hindi"),
    "zh" to setOf("zh", "zho", "chi", "chinese", "mandarin", "中文", "国语"),
    "ja" to setOf("ja", "jpn", "japanese", "日本語"),
    "ko" to setOf("ko", "kor", "korean", "한국어")
)

/**
 * Which subtitle track to switch ON automatically when the user never
 * picked one (2026-09-14: "the player should read the subtitles for MKV
 * videos" — embedded tracks already existed but ExoPlayer leaves text
 * OFF until told, so subs were invisible unless the user hunted the
 * sheet). Order of trust: the user's preferred language wins over the
 * container's own DEFAULT flag; a language match beats everything.
 * Returns an index into [tracks], or -1 for "stay off".
 */
internal fun autoPickSubtitleIndex(tracks: List<SubTrackInfo>, preferredLang: String): Int {
    if (tracks.isEmpty()) return -1
    val pref = preferredLang.trim().lowercase()
    if (pref == "all") {
        // Any language is fine: the muxer's DEFAULT track first, else #1.
        tracks.indexOfFirst { it.isDefault }.let { if (it >= 0) return it }
        return 0
    }
    val aliases = LANG_ALIASES[pref] ?: setOf(pref)
    fun matches(info: SubTrackInfo): Boolean {
        val lang = info.language?.trim()?.lowercase()
        if (lang != null && lang.isNotEmpty() && aliases.any { lang == it || lang.startsWith("$it-") }) return true
        val label = info.label?.trim()?.lowercase() ?: return false
        // "English", "eng", "English (CC)", "movie_en.srt" — compare whole
        // alphanumeric tokens, never a bare substring ("Frederick" must not
        // match "fr"). Char-code tokenizer, not a \p{...} regex: the device
        // regex engine is stricter than the JVM's (crash saga, 2026-09-13).
        val tokens = ArrayList<String>(4)
        val cur = StringBuilder()
        for (ch in label) {
            if (Character.isLetterOrDigit(ch)) cur.append(ch)
            else {
                if (cur.isNotEmpty()) { tokens.add(cur.toString()); cur.setLength(0) }
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        return tokens.any { aliases.contains(it) } ||
            aliases.any { label == it || label.startsWith("$it ") || label.contains(" $it ") }
    }
    // 1) preferred language AND default-flagged; 2) preferred language;
    // 3) no match at all -> OFF (showing Vietnamese when the user said
    // English is how players get untrusted).
    tracks.indexOfFirst { it.isDefault && matches(it) }.let { if (it >= 0) return it }
    tracks.indexOfFirst { matches(it) }.let { if (it >= 0) return it }
    return -1
}

// The player is a permanently BLACK canvas regardless of app theme — the
// theme tokens invert on it in Light mode, so the palette is pinned here.
private val PlayerAccent = Color(0xFFFFFFFF)
private val PlayerTextSecondary = Color(0xFF94A3B8)
private val PlayerSurface = Color(0xFF101216)
private val PlayerSurfaceElevated = Color(0xFF181B22)

/** Full language names some sidecar writers use ("movie.english.srt"). */
private val SIDECAR_LANGUAGE_NAMES = mapOf(
    "english" to "en", "spanish" to "es", "french" to "fr", "portuguese" to "pt",
    "arabic" to "ar", "hindi" to "hi", "chinese" to "zh", "japanese" to "ja",
    "korean" to "ko", "german" to "de", "italian" to "it", "russian" to "ru",
    "turkish" to "tr", "indonesian" to "id", "vietnamese" to "vi", "thai" to "th"
)

/** "movie.en.srt" next to "movie.mkv" -> "en"; "movie.english.srt" -> "en";
 *  "movie.final.srt" -> "und". Region tags are dropped ("pt-BR" -> "pt")
 *  because Format.language wants a bare 639 code, not an IETF tag. Pure so
 *  the tag rule is JVM-testable. */
internal fun sidecarLanguageHint(fileName: String, videoStem: String): String {
    val stem = fileName.substringAfterLast('/').substringBeforeLast('.')
    if (videoStem.isEmpty() || !stem.startsWith(videoStem)) return "und"
    val tag = stem.removePrefix(videoStem).trim('.', '_', '-', ' ')
        .substringBefore('-')
        .lowercase()
    if (Regex("^[a-zA-Z]{2,3}$").matches(tag)) return tag
    return SIDECAR_LANGUAGE_NAMES[tag] ?: "und"
}

/**
 * How a sidecar should be attached. Engines write `<stem>.<lang>.srt/.vtt/…`
 * but mirrors occasionally mislabel the container: a `.vtt` that is really
 * SRT (no WEBVTT header) and an `.srt` that is really WebVTT. ExoPlayer's
 * parsers are strict about the header, so:
 *  - `.vtt` without WEBVTT -> attach as SubRip (content is SRT);
 *  - `.srt` with WEBVTT   -> convert to SRT text and attach the conversion.
 */
internal data class SidecarSpec(val mimeType: String, val convertVttToSrt: Boolean)

internal fun sidecarSpec(fileExtension: String, head: String): SidecarSpec {
    val ext = fileExtension.lowercase()
    val vtt = SubtitleSurfaces.looksLikeVtt(head)
    return when {
        ext == "vtt" && !vtt -> SidecarSpec("application/x-subrip", false)
        ext == "vtt" -> SidecarSpec("text/vtt", false)
        ext == "srt" && vtt -> SidecarSpec("application/x-subrip", true)
        ext == "ass" || ext == "ssa" -> SidecarSpec("application/x-ssa", false)
        ext == "srt" -> SidecarSpec("application/x-subrip", false)
        else -> SidecarSpec("application/x-subrip", false)
    }
}

/**
 * Attach policy when the `.srt`-named WebVTT sidecar fails its vttToSrt
 * conversion (trace 2026-10-21 d3). The old fallback attached the ORIGINAL
 * file under the guessed SubRip mime — but the head IS a WEBVTT header, the
 * SubRip parser rejects it unconditionally, and the track shipped as a
 * silent zero-cue entry (listed, selectable, never a cue). Re-sniff instead:
 * the head is the truth-resolver — return the real WebVTT mime to attach the
 * original under, or null to DROP the track (an unreadable/garbage body
 * would only re-produce the dead track at parse time). Never x-subrip.
 */
internal fun failedConversionMime(head: String): String? =
    if (SubtitleSurfaces.looksLikeVtt(head)) "text/vtt" else null

/**
 * Quality/release tokens that distinguish sibling downloads of the SAME
 * content. Deliberately conservative: everything here is unambiguous
 * release jargon never carried by a real show title, so a stripped token
 * can never merge two different episodes/shows. ("hd"/"web" style tokens
 * are NOT stripped — they occur inside genuine titles.)
 */
private val QUALITY_VARIANT_TOKENS = setOf(
    "480p", "540p", "576p", "720p", "900p", "1080p", "1440p", "2160p", "4320p",
    "4k", "8k", "uhd", "hdr", "hdr10", "hdr10plus",
    "x264", "x265", "h264", "h265", "hevc", "avc",
    "aac", "ac3", "eac3", "flac", "webrip",
    "60fps", "30fps", "25fps", "50fps"
)

/**
 * Content identity of a playable file for cross-variant position migration
 * (trace 2026-10-21 C6 / d5): the file stem tokenized on non-alphanumerics,
 * lowercased, with quality/codec/release tokens dropped. Two paths with
 * equal keys are quality variants of the same episode ("Show S01E05" vs
 * "Show S01E05 1080p", "Show.S01E05.x265" vs "Show.S01E05.720p") and may
 * share a playback position; different episodes or shows never collide
 * (S01E05 vs S01E06 differ, "Web Therapy S01E05" keeps its "web"). Season
 * and episode markers are KEPT — this is not DownloadsSorter's
 * normalizeShowKey, which deliberately erases them for show grouping.
 * Pure, JVM-testable.
 */
internal fun playbackContentKey(filePath: String): String {
    val name = filePath.substringAfterLast('/')
    val stem = if (name.contains('.')) name.substringBeforeLast('.') else name
    val out = StringBuilder()
    var cur = StringBuilder()
    fun flush() {
        if (cur.isEmpty()) return
        val token = cur.toString()
        cur = StringBuilder()
        if (token !in QUALITY_VARIANT_TOKENS) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(token)
        }
    }
    for (ch in stem.lowercase()) {
        if (Character.isLetterOrDigit(ch)) cur.append(ch) else flush()
    }
    flush()
    return out.toString()
}

/** First bytes of a sidecar, BOM tolerated by the callers. */
internal fun readSubtitleHead(file: File, maxBytes: Int = 512): String = try {
    file.inputStream().use { input ->
        val buffer = ByteArray(maxBytes)
        val n = input.read(buffer)
        if (n <= 0) "" else String(buffer, 0, n, Charsets.UTF_8)
    }
} catch (_: Throwable) {
    ""
}

/**
 * Sibling-sidecar discovery rule (pure, JVM-tested). Accepts every shape
 * written next to `<stem>.<video-ext>`:
 *  - exact name, no language segment: "Show.srt" (VLC-style manual copies,
 *    third-party downloads — invisible to the old filter, which required a
 *    dot AFTER the stem);
 *  - one dotted tag segment: "Show.en.srt", "Show.pt-BR.srt" (engine +
 *    yt-dlp shape), "Show.en-2.srt" (collision rename);
 *  - ANY letter case in stem/tag/extension ("SHOW.EN.SRT", "Show.VTT") —
 *    FAT/FUSE case-preserving volumes mix case freely and the old
 *    case-sensitive endsWith hid those files.
 * The match stays anchored: everything after the stem must be at most
 * [stem].[one tag].[subtitle ext] — "Showtime.en.srt" never matches stem
 * "Show" (the old prefix filter did match it, a false positive this rule
 * also removes).
 */
internal fun isSidecarSubtitleSibling(name: String, videoStem: String): Boolean {
    if (videoStem.isEmpty() || !name.startsWith(videoStem, ignoreCase = true)) return false
    // Everything after the stem must start with the first dot separator —
    // "Showcase.srt" starts with "Show" but is a different word, not a
    // "<stem>.<...>" sibling.
    val tail = name.substring(videoStem.length)
    if (!tail.startsWith(".")) return false
    val segments = tail.split('.').filter { it.isNotEmpty() }
    val ext = segments.lastOrNull()?.lowercase() ?: return false
    if (ext !in SIDECAR_SUBTITLE_EXTS) return false
    return segments.size == 1 || (segments.size == 2 && segments[0].isNotEmpty())
}

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
 * five chips on the bottom row: speed (tap to cycle), the Rotate chip
 * (landscape <-> portrait, hidden for audio files), subtitles
 * (auto-enabled from the Settings language — embedded MKV tracks and
 * .srt/.vtt/.ass/.ssa sidecars), the display-framing cycle
 * (Fit -> Crop -> Stretch), and the audio-track picker;
 * the top bar carries a Mini-player button that shrinks the video into
 * picture-in-picture (2026-09-14 user spec: rotation moved from the old
 * top-bar fullscreen toggle to the chip row; the PiP slot took its place.
 * 2026-09-15 user spec: audio and rotation swapped slots — rotate sits
 * second now, the audio picker closes the row).
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

    /** Directive §5.1: mirror playback into the persistent watch history.
     *  Called on the 5 s poll, on file switch, on stop and on dismiss. */
    fun recordWatchHistory(path: String, positionMs: Long, durationMs: Long) {
        if (path.isBlank() || durationMs <= 0L) return
        val task = currentCtx.taskForPath(path)
        WatchHistoryStore.recordProgress(
            task = task,
            filePath = path,
            title = task?.episodeTitle ?: currentCtx.title,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs
        )
    }

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
        // The overlay shares the ACTIVITY's one window (no dialog window any
        // more), so hiding the bars here is what makes the player immersive
        // from frame one — this used to live on the dialog's own window.
        // A swipe from an edge reveals them transiently; the Fullscreen
        // toggle below re-asserts the same policy for rotation.
        activity?.let { act ->
            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // Drop any fullscreen rotation and bring the system bars back so
            // the rest of the app isn't stuck in landscape.
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            val theme = prefs?.getString("pref_theme_mode", "dark") ?: "dark"
            // Resolve the EFFECTIVE dark state for the restore: the old
            // light-vs-else check restored dark system bars for "system"/"auto"
            // even when the device is in light mode. Read live here (plain
            // context read, not a composable accessor) so the restore matches
            // whatever the device is in at dismiss time.
            val t = theme.lowercase()
            val systemDark = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val effectiveDark = when (t) {
                "light" -> false
                "system", "auto" -> systemDark
                else -> true
            }
            val restoreStyle = if (effectiveDark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            }
            componentActivity?.enableEdgeToEdge(
                statusBarStyle = restoreStyle,
                navigationBarStyle = restoreStyle
            )
        }
    }

    val ext = file.extension.lowercase()
    val isAudio = ext in AUDIO_EXTS

    // A saved still (Instagram last-resort path) never reaches the player:
    // it gets an image viewer in the same overlay chrome. ExoPlayer on a
    // JPEG produced exactly the "shows like error" the user reported.
    if (ext in IMAGE_EXTS) {
        MediaImageStill(filePath = ctx.filePath, title = ctx.title, onDismiss = onDismiss)
        return
    }

    // ---- Persisted prefs ----
    val storedSubtitlePref = remember { MediaPlayerPrefs.getSubtitleTrack(context) }
    val initialSpeed = remember { MediaPlayerPrefs.getPlaybackSpeed(context) }
    // The sentinel is a decision ("user chose Off"), shown as no selection —
    // never as a literal label in the sheet or chip.
    val initialSubtitle = storedSubtitlePref?.takeUnless { subtitlePrefHoldsUserOff(it) }
    val initialSavedVolume = remember {
        (MediaPlayerPrefs.getMediaVolume(context) * 100).toInt().coerceIn(0, 100)
    }
    // Brightness gesture (directive §5.3): starts from the persisted player
    // level, or the system level on first use. The window override is applied
    // only while the player is open and cleared on dispose; the pref carries
    // the choice into the next session.
    val initialBrightnessPercent = remember {
        val saved = MediaPlayerPrefs.getWindowBrightness(context)
        if (saved in 0.05f..1f) {
            saved * 100f
        } else {
            val system = runCatching {
                android.provider.Settings.System.getInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                )
            }.getOrDefault(255)
            (system / 255f * 100f).coerceIn(5f, 100f)
        }
    }
    val applyWindowBrightness: (Float) -> Unit = { fraction ->
        activity?.let { act ->
            runCatching {
                val attrs = act.window.attributes
                attrs.screenBrightness = fraction.coerceIn(0.05f, 1f)
                act.window.attributes = attrs
            }
        }
    }
    LaunchedEffect(activity) {
        val saved = MediaPlayerPrefs.getWindowBrightness(context)
        if (saved in 0.05f..1f) applyWindowBrightness(saved)
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.let { act ->
                runCatching {
                    val attrs = act.window.attributes
                    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    act.window.attributes = attrs
                }
            }
        }
    }

    // ---- UI state ----
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var currentVolumePercent by remember { mutableIntStateOf(initialSavedVolume) }
    var allowVolumeBoost by remember {
        mutableStateOf(MediaPlayerPrefs.getAllowVolumeBoost(context))
    }
    val loudnessEnhancer = remember { PlayerLoudnessEnhancer() }
    var isVolumeDragging by remember { mutableStateOf(false) }
    var showVolumeHud by remember { mutableStateOf(false) }
    var volumeHudTick by remember { mutableIntStateOf(0) }
    var overdragDistance by remember { mutableFloatStateOf(0f) }
    var boostDetentBroken by remember { mutableStateOf(false) }
    // Gesture HUD (directive §5.3): one transient pill shared by double-tap
    // seek, center play/pause, left-half brightness and hold-to-boost speed.
    var gestureHudText by remember { mutableStateOf<String?>(null) }
    // Retained so the fade-out animates a real caption instead of collapsing
    // to an empty pill the moment gestureHudText nulls.
    var gestureHudLastText by remember { mutableStateOf("") }
    var gestureHudIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureHudTick by remember { mutableIntStateOf(0) }
    var isBrightnessDragging by remember { mutableStateOf(false) }
    var isSpeedBoosting by remember { mutableStateOf(false) }
    var brightnessPercent by remember { mutableFloatStateOf(initialBrightnessPercent) }
    // Seek-drag ownership: while the thumb is held, the finger (dragFrac) IS
    // the value. The 500 ms poll otherwise yanks the thumb back to the
    // player's real (pre-seek, keyframe-snapped) position mid-drag, and
    // seeking on every pixel value thrashes the decoder.
    var isDragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    // Auto-hide reset counter: the timer must restart on EVERY control
    // interaction, not only when showControls/isPlaying flip — otherwise
    // tapping +10s two seconds in loses the overlay mid-interaction.
    var interactionTick by remember { mutableIntStateOf(0) }
    val touchControls: () -> Unit = { interactionTick++ }
    var isBuffering by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(initialSpeed) }
    var audioTrackLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentAudioLabel by remember { mutableStateOf<String?>(null) }
    var subtitleOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSubtitleLabel by remember { mutableStateOf<String?>(null) }
    // Auto-pick (2026-09-14): embedded MKV subs must APPEAR without the
    // user hunting the sheet — the first file with a preferred-language
    // track switches it on. Once the user has driven the sheet in this
    // session (including "Off"), we never auto-touch again. A persisted
    // Off (sentinel) IS such a decision: it opens the modal pre-decided
    // so auto-pick cannot silently re-enable across sessions (d6).
    var subtitleUserDecided by remember { mutableStateOf(subtitlePrefHoldsUserOff(storedSubtitlePref)) }
    // Direct pref read via AppSettings.subtitleLanguage(context) (F-08
    // consolidation): the full AppSettings.load() snapshot was only ever
    // consulted for this single field here — same pref key, same "en" default.
    val preferredSubLang = remember {
        runCatching { com.anonrode.downloader.data.settings.AppSettings.subtitleLanguage(context) }
            .getOrDefault("en")
    }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showEpisodesSheet by remember { mutableStateOf(false) }
    // Subscribed (not .value) so the episode list keeps its Playing/Watched
    // prefixes current while the modal stays open.
    val watchRecords by WatchHistoryStore.recordsFlow.collectAsState()
    var tracksLoaded by remember { mutableStateOf(false) }
    // Rotation state (user-driven via the Rotate chip): true = force
    // SENSOR_LANDSCAPE, false = force PORTRAIT. The system bars are hidden
    // for the modal's whole life either way — rotation and immersion are
    // separate decisions now (2026-09-14: the old single "Fullscreen"
    // toggle conflated them; its button slot became the PiP mini-player).
    var isLandscape by remember { mutableStateOf(false) }
    // True while the activity is in picture-in-picture (mini-player): all
    // chrome must be out of the pip frame, only the video surface renders.
    // Observed through PlayerPipState (see its doc) — MainActivity's
    // platform callback is the only writer.
    val inPip = PlayerPipState.inPip
    // Display framing cycle: FIT (letterbox, default) -> ZOOM (center-crop
    // to fill) -> STRETCH (FILL — distorts the aspect to fill the frame)
    // -> back to FIT. Display-only: the file is never re-encoded or
    // touched.
    // Session-only, no persistence. AspectRatioFrameLayout re-measures on
    // every layout pass, so rotation reframes automatically — no cached
    // dimensions on this side.
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isVerticalVideo by remember { mutableStateOf(false) }

    // Keep the screen awake ONLY while actively playing media. When paused,
    // buffering stalls, or the modal is dismissed, the flag is cleanly cleared so
    // the device's normal screen timeout applies and battery is not wasted.
    DisposableEffect(isPlaying, activity) {
        val window = activity?.window
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
                volume = (initialSavedVolume / 100f).coerceIn(0f, 1f)
                // Persisted explicit Off must hold on a FRESH player too: the
                // library's own default-track rules would light a default-
                // flagged embedded text track the moment tracks resolve, so
                // the text renderer starts disabled (tracksLoaded handler
                // then never re-enables — subtitleUserDecided opens true).
                if (subtitlePrefHoldsUserOff(storedSubtitlePref)) {
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            }
    }
    val mediaSession = remember {
        // Scoped to the modal lifetime — the OS picks it up for media-button
        // / Bluetooth intents; no persistent notification needed. Media3 rejects
        // an empty/default session ID when two modal compositions overlap.
        MediaSession.Builder(context, exoPlayer)
            .setId("anonrode-player-${UUID.randomUUID()}")
            .build()
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
        // Subtitle sidecars found by [isSidecarSubtitleSibling]: yt-dlp writes
        // "Name.en.srt" (language-tagged), the engine's move-along renames
        // them to the sanitized video stem + lang, and third-party writers
        // (manual VLC-style copies) use exact "Name.srt" — all three shapes
        // plus any letter case in stem/tag/ext now discover. A stem-anchored
        // rule (not plain prefix) so "Showtime.en.srt" is never a sidecar of
        // "Show.mkv". Language still via sidecarLanguageHint.
        val videoStem = file.nameWithoutExtension
        val sidecarFiles = file.parentFile?.listFiles { s ->
            s.isFile && s.canRead() && isSidecarSubtitleSibling(s.name, videoStem)
        }?.sortedBy { it.name } ?: emptyList()
        val sidecars = sidecarFiles.mapNotNull { sibling ->
            val extName = sibling.extension.lowercase()
            val head = readSubtitleHead(sibling)
            val spec = sidecarSpec(extName, head)
            // Mislabeled container: an .srt that is really WebVTT is
            // converted (headered VTT is rejected by the SubRip parser);
            // an .vtt that is really SRT is attached as SubRip as-is.
            // Conversion failure never falls back to the original under the
            // guessed SubRip mime — that head is WebVTT, the SubRip parser
            // rejects it, and the track ships as a silent zero-cue entry.
            // [failedConversionMime] re-sniffs: attach the original as real
            // WebVTT when it still reads as one (e.g. the cache write was
            // the failing step — the original itself is fine), else drop
            // the track entirely; the drop is logged.
            val attach: Pair<File, String> = if (spec.convertVttToSrt) {
                try {
                    val converted = SubtitleSurfaces.vttToSrt(sibling.readText())
                    val outDir = File(context.cacheDir, "player_subs").apply { mkdirs() }
                    val convertedFile = File(outDir, sibling.name.substringBeforeLast('.') + ".converted.srt").apply {
                        writeText(converted)
                    }
                    convertedFile to spec.mimeType
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "MediaPlayerModal",
                        "sidecar vtt->srt conversion failed for " +
                            "${sibling.name} (${t.javaClass.simpleName}: ${t.message}) — re-sniffing"
                    )
                    val rescueMime = failedConversionMime(head)
                    if (rescueMime == null) {
                        android.util.Log.w(
                            "MediaPlayerModal",
                            "sidecar ${sibling.name} dropped: unreadable body would present a dead track"
                        )
                        return@mapNotNull null
                    }
                    sibling to rescueMime
                }
            } else sibling to spec.mimeType
            val (attachedFile, attachMime) = attach
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(attachedFile))
                .setMimeType(attachMime)
                // "movie.en.srt" carries a real language tag: feeding it
                // lets the auto-pick rule below treat a sidecar exactly
                // like an embedded EN track instead of "und".
                .setLanguage(sidecarLanguageHint(sibling.name, videoStem))
                .setLabel(sibling.name)
                .setSelectionFlags(0)
                .build()
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
                // coerceAtLeast(0): the leaving item may never have reached
                // READY (duration still C.TIME_UNSET = -1); saving -1 poisoned
                // that file's future "watched to the end" computations.
                PlaybackPositions.save(
                    context, leaving,
                    exoPlayer.currentPosition.coerceAtLeast(0L),
                    exoPlayer.duration.coerceAtLeast(0L)
                )
                recordWatchHistory(
                    leaving,
                    exoPlayer.currentPosition.coerceAtLeast(0L),
                    exoPlayer.duration.coerceAtLeast(0L)
                )
            }
        }
        // Resume where the user left off the last time this file was open;
        // 0 = start fresh (no saved position, or watched to the end).
        // Quality-variant caveat (trace C6): a quality switch of the SAME
        // episode re-points to a different FILE (the variant tag changes the
        // stem), so this file's own lookup misses and playback used to
        // restart at 0:00 while the leaving position was saved under the old
        // path. When only quality/codec tokens separate the two names, the
        // leaving position migrates; the 5s persistence poll then records it
        // under the new path like any normal watch. Nothing here removes the
        // old entry — reopening either variant later still resumes sensibly.
        val savedForNew = PlaybackPositions.get(context, ctx.filePath)
        val resumeMs = when {
            savedForNew != null -> savedForNew.positionMs
            leaving != null && leaving != ctx.filePath &&
                playbackContentKey(leaving) == playbackContentKey(ctx.filePath) ->
                PlaybackPositions.get(context, leaving)?.positionMs ?: 0L
            else -> 0L
        }
        if (resumeMs > 0) exoPlayer.setMediaItem(mediaItem, resumeMs) else exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        lastLoadedPath = ctx.filePath
        currentPosition = resumeMs
        duration = 0L
        isPlaying = true
        isBuffering = false
        isDragging = false
        tracksLoaded = false
        audioTrackLabels = emptyList()
        currentAudioLabel = null
        subtitleOptions = emptyList()
        currentSubtitleLabel = initialSubtitle
        if (currentVolumePercent > 100) {
            currentVolumePercent = 100
            exoPlayer.volume = 1.0f
            loudnessEnhancer.setBoostPercent(100, allowVolumeBoost)
        }
    }

    // One listener for the player's lifetime.
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) {
                    loudnessEnhancer.attach(audioSessionId)
                    loudnessEnhancer.setBoostPercent(currentVolumePercent, allowVolumeBoost)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                when (playbackState) {
                    Player.STATE_READY -> {
                        duration = exoPlayer.duration.coerceAtLeast(0L)
                        val sid = exoPlayer.audioSessionId
                        if (sid > 0) {
                            loudnessEnhancer.attach(sid)
                            loudnessEnhancer.setBoostPercent(currentVolumePercent, allowVolumeBoost)
                        }
                    }
                    Player.STATE_ENDED -> {
                        // Directive §5.1: record the completion BEFORE the
                        // position entry is cleared, so the episode stays
                        // permanently Watched and Up Next advances.
                        val endedPath = currentCtx.filePath
                        val endedTask = currentCtx.taskForPath(endedPath)
                        WatchHistoryStore.markEnded(
                            task = endedTask,
                            filePath = endedPath,
                            title = endedTask?.episodeTitle ?: currentCtx.title,
                            durationMs = exoPlayer.duration.coerceAtLeast(0L)
                        )
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

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                isVerticalVideo = videoSize.height > videoSize.width
            }

            override fun onPlayerError(error: PlaybackException) {
                // Escape hatch for unsupported codecs / broken streams:
                // hand the file to the system player, then close. The dialog
                // is gone the same frame, so an in-content error Text could
                // never be READ (it rendered for exactly zero frames before)
                // — the toast is what the user actually sees.
                android.widget.Toast.makeText(
                    context,
                    "Playback failed (${error.errorCodeName}) — opening external player",
                    android.widget.Toast.LENGTH_LONG
                ).show()
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
                if (!tracksLoaded) {
                    val remembered = currentSubtitleLabel
                    if (remembered != null &&
                        applySubtitleByLabel(exoPlayer, tracks, remembered, enable = true)
                    ) {
                        // Remembered pick applied (once per file).
                    } else if (!subtitleUserDecided) {
                        if (remembered != null) currentSubtitleLabel = null
                        // Nothing chosen by the user in this session (and
                        // none persisted): MKV/MP4 embedded tracks surface
                        // here DISABLED by ExoPlayer default — auto-enable
                        // the preferred-language one so subtitles "just
                        // read" (2026-09-14 request). Candidates are kept in
                        // ONE list shared by the pick and the label: SubRip
                        // sidecars (converted by the downloader, position-free
                        // → always render at the bottom) outrank embedded ASS
                        // tracks whose release-group typesetting can carry
                        // top-positioned cues (Break Clause 2026-10-15:
                        // dialogue jumped to the screen top).
                        val candidates = subGroups
                            .sortedByDescending {
                                (it.mediaTrackGroup.getFormat(0).label ?: "")
                                    .endsWith(".srt", ignoreCase = true)
                            }
                        val pick = autoPickSubtitleIndex(
                            candidates.map { g ->
                                val f = g.mediaTrackGroup.getFormat(0)
                                SubTrackInfo(
                                    language = f.language,
                                    label = f.label,
                                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
                                )
                            },
                            preferredSubLang
                        )
                        if (pick >= 0) {
                            // The label MUST come from the same sorted list
                            // `pick` indexes into — reading the unsorted sheet
                            // list desynced pick and override whenever an
                            // embedded track preceded the .srt sidecar.
                            val f = candidates[pick].mediaTrackGroup.getFormat(0)
                            val label = f.label ?: f.language?.uppercase() ?: "Track ${f.id}"
                            if (label in subLabels) {
                                currentSubtitleLabel = label
                                applySubtitleByLabel(exoPlayer, tracks, label, enable = true)
                            }
                        }
                    }
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
                        recordWatchHistory(currentCtx.filePath, currentPosition, duration)
                    }
                }
            } catch (_: Exception) { /* race during teardown */ }
            delay(500)
        }
    }

    // Auto-hide controls after 3s of playback. Keyed on interactionTick as
    // well: any control touch re-arms the countdown (the old keys only fired
    // when the overlay appeared or playback started/stopped, so interacting
    // with the transport let the overlay fade out mid-use).
    LaunchedEffect(showControls, isPlaying, interactionTick) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    // Auto-hide volume HUD 1.2s after gesture ends.
    LaunchedEffect(showVolumeHud, isVolumeDragging, volumeHudTick) {
        if (showVolumeHud && !isVolumeDragging) {
            delay(1200)
            showVolumeHud = false
        }
    }

    // Keep the last caption for the exit animation.
    LaunchedEffect(gestureHudText) {
        gestureHudText?.let { gestureHudLastText = it }
    }

    // Gesture HUD: lingers briefly after the gesture, but never disappears
    // mid-hold — a brightness drag or 2x press keeps it on screen.
    LaunchedEffect(gestureHudText, gestureHudTick, isBrightnessDragging, isSpeedBoosting) {
        if (gestureHudText != null && !isBrightnessDragging && !isSpeedBoosting) {
            delay(900)
            gestureHudText = null
        }
    }

    // Rotation (Rotate chip): SENSOR_LANDSCAPE (not USER_LANDSCAPE) so the
    // tap works even with system rotation locked; PORTRAIT is a hard lock
    // so "rotate to portrait" means portrait, not "whatever the sensor
    // says" on a sideways phone. MainActivity declares orientation in
    // configChanges, so neither flip recreates the activity or disturbs
    // the player.
    LaunchedEffect(isLandscape, activity) {
        val act = activity ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (isLandscape) {
            showControls = true
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // The overlay is immersive for its WHOLE lifetime, not just one
            // mode — the old dialog hid the bars via its own window; here
            // the only equivalent is re-hiding (running show() on first
            // composition would also race the DisposableEffect above and
            // leave the bars up during playback).
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Returning from the pip bubble (tap to expand / close → app): bring
    // the controls up so the user lands on a usable player, not a bare
    // video with a hidden overlay.
    LaunchedEffect(inPip) {
        if (!inPip) showControls = true
    }

    // Enter mini-player: aspect ratio follows the video (falls back to the
    // frame's own ratio, clamped to the range Android accepts — an
    // out-of-range Rational throws IllegalArgumentException on some OEMs).
    val enterPip: () -> Unit = {
        activity?.let { act ->
            runCatching {
                val vs = exoPlayer.videoSize
                val width = if (vs.width > 0) vs.width else 16
                val height = if (vs.height > 0) vs.height else 9
                var ratio = width.toFloat() / height.toFloat()
                if (ratio < 1f / 2.39f) ratio = 1f / 2.39f
                if (ratio > 2.39f) ratio = 2.39f
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational((ratio * 100).toInt(), 100))
                    .build()
                act.enterPictureInPictureMode(params)
            }
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
                recordWatchHistory(
                    currentCtx.filePath,
                    exoPlayer.currentPosition.coerceAtLeast(0L),
                    exoPlayer.duration.coerceAtLeast(0L)
                )
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
                    try { loudnessEnhancer.release() } catch (_: Exception) {}
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
            try { loudnessEnhancer.release() } catch (_: Exception) {}
            try { exoPlayer.release() } catch (_: Exception) {}
            try { mediaSession.release() } catch (_: Exception) {}
        }
    }

    // Next/Previous: step through peers, skipping files that no longer
    // exist. The parent's onPlayFile just repoints its active task; this
    // modal is recomposed with the new path and the same player swaps media.
    val hasNext = remember(ctx.queuePeerPaths, ctx.filePath) {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        idx >= 0 && ctx.queuePeerPaths.drop(idx + 1).any { File(it).exists() }
    }
    val hasPrev = remember(ctx.queuePeerPaths, ctx.filePath) {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        idx > 0 && ctx.queuePeerPaths.take(idx).any { File(it).exists() }
    }
    val playNext: () -> Unit = {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        val next = if (idx >= 0) {
            ctx.queuePeerPaths.drop(idx + 1).firstOrNull { File(it).exists() }
        } else null
        if (next != null) ctx.onPlayFile(next)
    }
    val playPrev: () -> Unit = {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        val prev = if (idx > 0) ctx.queuePeerPaths.take(idx).lastOrNull { File(it).exists() } else null
        if (prev != null) ctx.onPlayFile(prev)
    }

    // Directive §5.2: the explicit Next label + the end-of-episode card.
    val nextPeerPath = remember(ctx.queuePeerPaths, ctx.filePath) {
        val idx = ctx.queuePeerPaths.indexOf(ctx.filePath)
        if (idx < 0) null
        else ctx.queuePeerPaths.drop(idx + 1).firstOrNull { File(it).exists() }
    }
    val nextTag = remember(nextPeerPath) {
        nextPeerPath?.let { episodeTagFromPath(it) ?: File(it).nameWithoutExtension.take(16) }
    }
    var upNextDismissed by remember(ctx.filePath) { mutableStateOf(false) }
    val upNextRemainingMs = if (duration > 0L) duration - currentPosition else 0L
    val upNextSeriesTask = ctx.taskForPath(ctx.filePath)
    val hasSeriesContinuation = !upNextSeriesTask?.seriesUrl.isNullOrBlank()
    // A standalone movie must not advertise "next episode": only offer the
    // card when there is a downloaded peer or a known series to fetch from.
    val showUpNextCard = !inPip && !upNextDismissed && duration > 60_000L &&
        upNextRemainingMs in 1L..12_000L && (nextPeerPath != null || hasSeriesContinuation)

    // ---- UI: one layout for every orientation ----
    // ROOT-LEVEL OVERLAY, not a Dialog. The player used to live in its own
    // Compose Dialog WINDOW; every window-level tactic tried over six
    // complaint rounds (platformDefaultWidth=false, forced MATCH_PARENT +
    // FLAG_LAYOUT_NO_LIMITS, per-config re-asserts) still rendered on device
    // as a ~75% content-sized square with the app showing around its edges,
    // because a Dialog window is sized by the platform/OEM, re-clamped on
    // recomposition, and never reliably obeyable. Hosted at MainActivity's
    // root (after MainScaffold, so it is the topmost child of the activity's
    // ONE window), a fillMaxSize Box is fullscreen BY CONSTRUCTION — there is
    // no second window left to shrink. System bars stay managed by the
    // activity-window effects above (edge-to-edge dark + immersive). The
    // audio/track/subtitle sheets are still separate dialog windows — they
    // stack over this overlay fine.
    BackHandler { onDismiss() }

    val view = LocalView.current
    val density = LocalDensity.current
    val detentThresholdPx = remember(density) { with(density) { 42.dp.toPx() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(inPip) {
                if (inPip) return@pointerInput
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    },
                    onDoubleTap = { offset ->
                        // Directive §5.3: left/right 35% seek ∓10s, center 30%
                        // toggles play/pause. The HUD pill reports the action.
                        // Do not hide already-visible controls: a seek while
                        // the overlay is open must keep the seek bar visible.
                        showVolumeHud = false
                        when (playerTapZone(offset.x, size.width.toFloat())) {
                            PlayerTapZone.REWIND -> {
                                val target = clampedSeekTarget(
                                    exoPlayer.currentPosition,
                                    exoPlayer.duration,
                                    -10_000L
                                )
                                exoPlayer.seekTo(target)
                                currentPosition = target
                                gestureHudIcon = Icons.Rounded.FastRewind
                                gestureHudText = formatSeekHud(-10_000L)
                                touchControls()
                            }
                            PlayerTapZone.FORWARD -> {
                                val target = clampedSeekTarget(
                                    exoPlayer.currentPosition,
                                    exoPlayer.duration,
                                    10_000L
                                )
                                exoPlayer.seekTo(target)
                                currentPosition = target
                                gestureHudIcon = Icons.Rounded.FastForward
                                gestureHudText = formatSeekHud(10_000L)
                                touchControls()
                            }
                            PlayerTapZone.PLAY_PAUSE -> {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                    gestureHudIcon = Icons.Rounded.Pause
                                    gestureHudText = "Paused"
                                } else {
                                    exoPlayer.play()
                                    gestureHudIcon = Icons.Rounded.PlayArrow
                                    gestureHudText = "Playing"
                                }
                                touchControls()
                            }
                        }
                        gestureHudTick++
                    },
                    onLongPress = {
                        // Hold for 2x: the user's chosen speed is untouched —
                        // release restores it from playbackSpeed.
                        showVolumeHud = false
                        isSpeedBoosting = true
                        exoPlayer.playbackParameters = PlaybackParameters(2.0f)
                        view.tick()
                        gestureHudIcon = Icons.Rounded.Speed
                        gestureHudText = "2.0× Speed"
                        gestureHudTick++
                        touchControls()
                    },
                    onPress = {
                        // Runs for every press; only a hold that set the boost
                        // needs restoring, and tryAwaitRelease also covers a
                        // cancelled gesture (finger leaves the screen).
                        tryAwaitRelease()
                        if (isSpeedBoosting) {
                            isSpeedBoosting = false
                            exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
                            gestureHudText = null
                        }
                    }
                )
            }
            .pointerInput(allowVolumeBoost, inPip) {
                if (inPip) return@pointerInput
                // Dead-zone accumulators (gesture privacy: nothing scrubs the
                // mix until the drag is deliberately past the threshold).
                var volumeArmed = false
                var pendingDeadZonePx = 0f
                var brightnessArmed = false
                var pendingBrightnessPx = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        // System-gesture zones never start a volume drag:
                        // left/right 28dp = back gesture ("trying to leave the
                        // screen"), top 44dp = notification-shade pull. The
                        // complaint on 2026-10-15 was the surface eating both.
                        val edgePx = with(density) { 28.dp.toPx() }
                        val shadePx = with(density) { 44.dp.toPx() }
                        if (offset.x >= edgePx && offset.x <= size.width - edgePx && offset.y >= shadePx) {
                            if (offset.x >= size.width / 2f) {
                                isVolumeDragging = true
                                showVolumeHud = true
                                // One HUD slot: a lingering seek/brightness
                                // caption must not sit under the volume pill.
                                gestureHudText = null
                                volumeHudTick++
                                overdragDistance = 0f
                                boostDetentBroken = (currentVolumePercent > 100)
                                showControls = false
                            } else {
                                // Left half = brightness (directive §5.3),
                                // same dead zone as volume so accidental
                                // vertical wobble never dims the screen.
                                isBrightnessDragging = true
                                showVolumeHud = false
                                brightnessArmed = false
                                pendingBrightnessPx = 0f
                                gestureHudIcon = Icons.Rounded.BrightnessMedium
                                gestureHudText = "${brightnessPercent.toInt()}%"
                                gestureHudTick++
                                showControls = false
                            }
                        }
                    },
                    onDragEnd = {
                        isVolumeDragging = false
                        overdragDistance = 0f
                        boostDetentBroken = false
                        volumeArmed = false
                        pendingDeadZonePx = 0f
                        if (isBrightnessDragging) {
                            isBrightnessDragging = false
                            runCatching {
                                MediaPlayerPrefs.setWindowBrightness(
                                    context,
                                    brightnessPercent / 100f
                                )
                            }
                            gestureHudTick++
                        }
                        if (currentVolumePercent <= 100) {
                            MediaPlayerPrefs.setMediaVolume(context, currentVolumePercent / 100f)
                        }
                    },
                    onDragCancel = {
                        isVolumeDragging = false
                        overdragDistance = 0f
                        boostDetentBroken = false
                        volumeArmed = false
                        pendingDeadZonePx = 0f
                        if (isBrightnessDragging) {
                            isBrightnessDragging = false
                            gestureHudTick++
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (isBrightnessDragging) {
                            if (!brightnessArmed) {
                                pendingBrightnessPx += kotlin.math.abs(dragAmount)
                                if (pendingBrightnessPx < with(density) { 22.dp.toPx() }) {
                                    change.consume()
                                    return@detectVerticalDragGestures
                                }
                                brightnessArmed = true
                            }
                            change.consume()
                            val next = brightnessPercentAfterDrag(
                                brightnessPercent,
                                dragAmount,
                                size.height.toFloat()
                            )
                            if (next != brightnessPercent) {
                                brightnessPercent = next
                                applyWindowBrightness(next / 100f)
                                gestureHudText = "${next.toInt()}%"
                                gestureHudTick++
                            }
                            return@detectVerticalDragGestures
                        }
                        if (!isVolumeDragging) return@detectVerticalDragGestures
                        // Dead zone: the first ~22dp of a vertical drag arm the
                        // gesture instead of scrubbing volume — tiny accidental
                        // movements (going for the shade, hunting a nav edge)
                        // no longer fire the HUD or shift the mix.
                        if (!volumeArmed) {
                            pendingDeadZonePx += kotlin.math.abs(dragAmount)
                            if (pendingDeadZonePx < with(density) { 22.dp.toPx() }) {
                                change.consume()
                                return@detectVerticalDragGestures
                            }
                            volumeArmed = true
                        }
                        change.consume()
                        volumeHudTick++
                        showVolumeHud = true

                        // Upward drag: dragAmount is negative. Full screen height
                        // = 100 points (was 120 — the hypersensitivity complaint).
                        val delta = (-dragAmount / size.height) * 100f
                        val oldVol = currentVolumePercent

                        if (oldVol < 100) {
                            boostDetentBroken = false
                            overdragDistance = 0f
                            val candidate = (oldVol + delta).coerceIn(0f, 100f)
                            val newVol = candidate.toInt()
                            if (newVol != oldVol) {
                                currentVolumePercent = newVol
                                exoPlayer.volume = newVol / 100f
                                loudnessEnhancer.setBoostPercent(newVol, allowVolumeBoost)
                            }
                        } else if (oldVol == 100) {
                            if (delta < 0) {
                                boostDetentBroken = false
                                overdragDistance = 0f
                                val newVol = (100f + delta).coerceIn(0f, 100f).toInt()
                                currentVolumePercent = newVol
                                exoPlayer.volume = newVol / 100f
                                loudnessEnhancer.setBoostPercent(newVol, allowVolumeBoost)
                            } else {
                                if (!allowVolumeBoost) {
                                    currentVolumePercent = 100
                                } else if (!boostDetentBroken) {
                                    overdragDistance += -dragAmount
                                    if (overdragDistance >= detentThresholdPx) {
                                        boostDetentBroken = true
                                        view.tick()
                                        currentVolumePercent = 101
                                        exoPlayer.volume = 1.0f
                                        loudnessEnhancer.setBoostPercent(101, allowVolumeBoost)
                                    }
                                } else {
                                    val newVol = (100f + delta).coerceIn(100f, 150f).toInt()
                                    if (newVol != oldVol) {
                                        currentVolumePercent = newVol
                                        exoPlayer.volume = 1.0f
                                        loudnessEnhancer.setBoostPercent(newVol, allowVolumeBoost)
                                    }
                                }
                            }
                        } else {
                            if (!allowVolumeBoost) {
                                currentVolumePercent = 100
                                exoPlayer.volume = 1.0f
                                loudnessEnhancer.setBoostPercent(100, false)
                            } else {
                                val candidate = oldVol + delta
                                if (candidate <= 100f) {
                                    boostDetentBroken = false
                                    overdragDistance = 0f
                                    view.tick()
                                    currentVolumePercent = 100
                                    exoPlayer.volume = 1.0f
                                    loudnessEnhancer.setBoostPercent(100, allowVolumeBoost)
                                } else {
                                    val newVol = candidate.coerceIn(101f, 150f).toInt()
                                    if (newVol != oldVol) {
                                        currentVolumePercent = newVol
                                        exoPlayer.volume = 1.0f
                                        loudnessEnhancer.setBoostPercent(newVol, allowVolumeBoost)
                                    }
                                }
                            }
                        }
                    }
                )
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
                        keepScreenOn = isPlaying
                        setBackgroundColor(android.graphics.Color.BLACK)
                        configureSubtitles(this, isLandscape, showControls, resizeMode, isVerticalVideo)
                    }
                },
                update = { view ->
                    // Live-apply the Fit/Crop cycle; also re-asserted after
                    // any recomposition so the mode never drifts.
                    view.resizeMode = resizeMode
                    view.keepScreenOn = isPlaying
                    configureSubtitles(view, isLandscape, showControls, resizeMode, isVerticalVideo)
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
                        fontSize = Type.screenTitle.fontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Spacing.xl)
                    )
                    Text(
                        text = "Audio • " + ext.uppercase(),
                        color = PlayerTextSecondary,
                        fontSize = Type.label.fontSize
                    )
                }
            }

            // Buffering line across the top (YouTube-style): while
            // STATE_BUFFERING the seek bar would otherwise sit frozen at the
            // old position or a dead 0% and the clock reads 0:00 — this says
            // "in flight, no percentage yet" without pretending.
            if (isBuffering && !inPip) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    color = PlayerAccent,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
            }

            // Volume & Boost HUD Pill — fade on the app's nav tokens, not the
            // stock spring defaults every other surface here has moved off.
            AnimatedVisibility(
                visible = showVolumeHud && gestureHudText == null && !inPip,
                enter = fadeIn(animationSpec = tween(Motion.DurationFast)),
                exit = fadeOut(animationSpec = tween(Motion.DurationNavExit)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
            ) {
                VolumeHudPill(
                    volume = currentVolumePercent,
                    isBoostAllowed = allowVolumeBoost,
                    isDetentActive = (currentVolumePercent == 100 && !boostDetentBroken && allowVolumeBoost && isVolumeDragging)
                )
            }

            // Gesture HUD (seek / play-pause / brightness / 2x). Sits in the
            // same slot as the volume pill because no two gestures run at
            // once; the icon and caption are the whole feedback.
            AnimatedVisibility(
                visible = gestureHudText != null && !inPip,
                enter = fadeIn(animationSpec = tween(Motion.DurationFast)),
                exit = fadeOut(animationSpec = tween(Motion.DurationNavExit)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
            ) {
                GestureHudPill(
                    icon = gestureHudIcon ?: Icons.Rounded.PlayArrow,
                    label = gestureHudText ?: gestureHudLastText
                )
            }

            AnimatedVisibility(
                visible = showControls && !inPip,
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
                    // The dialog extends behind the system bars and the bars
                    // are hidden (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE), so a
                    // fixed top inset is the predictable thing — statusBarsPadding
                    // would jump when the user swipes to reveal the bars and
                    // would also push the row under the camera notch on devices
                    // that have one.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.md, end = Spacing.md, top = 24.dp, bottom = Spacing.sm),
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
                            fontSize = Type.rowTitle.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = Spacing.md)
                        )
                        if (ctx.queuePeerPaths.size > 1) {
                            // Directive §5.2: Episodes (N) — every downloaded
                            // peer, with playing/watched/time-left state.
                            PlayerCircleButton(
                                icon = Icons.Rounded.VideoLibrary,
                                contentDescription = "Episodes (${ctx.queuePeerPaths.size})",
                                onClick = { touchControls(); showEpisodesSheet = true }
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
                        }
                        if (!isAudio) {
                            // 2026-09-14 (user spec): this slot used to be
                            // the fullscreen rotation toggle. Rotation moved
                            // to the Rotate chip beside Fit; this button now
                            // shrinks the video into a floating mini-player
                            // (picture-in-picture) — the YouTube gesture of
                            // leaving a video while doing something else.
                            PlayerCircleButton(
                                icon = Icons.Rounded.PictureInPictureAlt,
                                contentDescription = "Mini player",
                                onClick = { touchControls(); enterPip() }
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
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
                        // The Next pill is wider than the circle it replaces;
                        // tighten the gaps so the transport still fits a
                        // 360dp portrait phone.
                        horizontalArrangement = Arrangement.spacedBy(
                            if (hasNext && nextTag != null) Spacing.sm else Spacing.lg
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerCircleButton(
                            icon = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            onClick = { touchControls(); playPrev() },
                            enabled = hasPrev,
                            size = 44.dp,
                            iconSize = 24.dp
                        )
                        PlayerCircleButton(
                            icon = Icons.Rounded.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            onClick = {
                                touchControls()
                                val target = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            },
                            size = 48.dp,
                            iconSize = 28.dp
                        )
                        IconButton(
                            onClick = {
                                touchControls()
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
                                touchControls()
                                // Guards TIME_UNSET (-1): before READY the old
                                // coerceAtMost(duration) yielded -1, and
                                // seekTo(-1) clamps to 0 — the user was thrown
                                // to the START by trying to go FORWARD.
                                val d = exoPlayer.duration
                                val target = (exoPlayer.currentPosition + 10_000)
                                    .let { if (d > 0) it.coerceAtMost(d) else it }
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            },
                            size = 48.dp,
                            iconSize = 28.dp
                        )
                        if (hasNext && nextTag != null) {
                            // Directive §5.2: an explicit "Next: E05" chip so
                            // the user always sees what is queued next.
                            PlayerNextPill(
                                nextTag = nextTag,
                                onClick = { touchControls(); playNext() }
                            )
                        } else {
                            PlayerCircleButton(
                                icon = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                onClick = { touchControls(); playNext() },
                                enabled = hasNext,
                                size = 44.dp,
                                iconSize = 24.dp
                            )
                        }
                    }

                    // ---- Bottom: seek, then speed / audio / subtitles ----
                    // Symmetric to the top row: fixed bottom inset instead of
                    // navigationBarsPadding, so the layout doesn't jump when
                    // the user swipes the bars into view.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                // Follow the finger while scrubbing so the
                                // readout matches the thumb, not the clock.
                                text = formatDuration(
                                    if (isDragging) (dragFrac * duration).toLong() else currentPosition
                                ),
                                color = Color.White,
                                fontSize = Type.label.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatDuration(duration),
                                color = PlayerTextSecondary,
                                fontSize = Type.label.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Slider(
                            value = when {
                                isDragging -> dragFrac
                                duration > 0 -> (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                else -> 0f
                            },
                            onValueChange = { frac ->
                                isDragging = true
                                dragFrac = frac
                                touchControls()
                            },
                            // ONE seek when the finger lifts — not one per
                            // pixel — and the poll (which may land mid-drag)
                            // cannot yank the thumb, because while isDragging
                            // the value above ignores currentPosition.
                            onValueChangeFinished = {
                                if (duration > 0) {
                                    val target = (dragFrac * duration).toLong()
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                }
                                isDragging = false
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
                            modifier = Modifier
                                .fillMaxWidth()
                                // Audio labels are real strings ("RUS • 192 kbps • 5.1ch");
                                // without scroll the fixed four-chip row pushed the
                                // Subtitles/Fit chips off the right edge on a 360dp phone.
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            PlayerChip(
                                label = formatSpeed(playbackSpeed),
                                selected = playbackSpeed != 1.0f,
                                onClick = {
                                    touchControls()
                                    val nextSpeed = PLAYBACK_SPEEDS[
                                        (PLAYBACK_SPEEDS.indexOf(playbackSpeed) + 1) % PLAYBACK_SPEEDS.size
                                    ]
                                    playbackSpeed = nextSpeed
                                    exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
                                    MediaPlayerPrefs.setPlaybackSpeed(context, nextSpeed)
                                },
                                leading = Icons.Rounded.Speed
                            )
                            if (!isAudio) {
                                // Rotation toggle — swapped from the row's END
                                // to the second slot (2026-09-15 user: "swap
                                // the position of audio track with the
                                // portrait/landscape mode"): framing is the
                                // second-most-touched control during playback
                                // and the audio picker usually rides at the
                                // far end where the sheet scroll can reach it.
                                PlayerChip(
                                    label = if (isLandscape) "Portrait" else "Landscape",
                                    selected = isLandscape,
                                    onClick = {
                                        touchControls()
                                        isLandscape = !isLandscape
                                    },
                                    leading = Icons.Rounded.ScreenRotation
                                )
                            }
                            PlayerChip(
                                label = currentSubtitleLabel ?: "Subtitles",
                                selected = currentSubtitleLabel != null,
                                onClick = { touchControls(); showSubtitleSheet = true },
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
                                    touchControls()
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
                            PlayerChip(
                                label = currentAudioLabel ?: "Audio",
                                selected = false,
                                onClick = { touchControls(); showAudioSheet = true },
                                leading = Icons.Filled.GraphicEq
                            )
                            PlayerChip(
                                label = if (allowVolumeBoost) {
                                    if (currentVolumePercent > 100) "$currentVolumePercent% Boost" else "Boost On"
                                } else "Boost Off",
                                selected = allowVolumeBoost,
                                onClick = {
                                    touchControls()
                                    val next = !allowVolumeBoost
                                    allowVolumeBoost = next
                                    MediaPlayerPrefs.setAllowVolumeBoost(context, next)
                                    if (!next && currentVolumePercent > 100) {
                                        currentVolumePercent = 100
                                        exoPlayer.volume = 1.0f
                                        loudnessEnhancer.setBoostPercent(100, false)
                                    }
                                    showVolumeHud = true
                                    volumeHudTick++
                                    view.tick()
                                },
                                leading = Icons.Filled.GraphicEq
                            )
                            // (the Rotate chip moved to slot 2 in this row per
                            // the 2026-09-15 audio/rotation swap — Audio now
                            // closes the row where Rotate used to sit)
                        }
                    }
                }
            }

            // ── Floating Up-Next card (directive §5.2): appears in the last
            // 12 seconds. Plays the next downloaded file, or routes to the
            // drawer with Remaining preselected when the queue is exhausted.
            if (showUpNextCard) {
                PlayerUpNextCard(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 96.dp),
                    nextTag = nextTag.takeIf { nextPeerPath != null },
                    actionLabel = if (nextPeerPath != null) "▶ Play now" else "⬇ Download Next Episodes",
                    onAction = {
                        touchControls()
                        if (nextPeerPath != null) {
                            // Explicit "Play now" advances before STATE_ENDED.
                            // Treat it as completion of the current episode so
                            // the history does not leave it mid-RESUME.
                            val currentPath = currentCtx.filePath
                            val currentTask = currentCtx.taskForPath(currentPath)
                            val dur = if (duration > 0L) duration
                                else exoPlayer.duration.coerceAtLeast(0L)
                            WatchHistoryStore.markEnded(
                                task = currentTask,
                                filePath = currentPath,
                                title = currentTask?.episodeTitle ?: currentCtx.title,
                                durationMs = dur
                            )
                            PlaybackPositions.clear(context, currentPath)
                            ctx.onPlayFile(nextPeerPath)
                        } else {
                            upNextSeriesTask?.let { t ->
                                ctx.openRemaining?.invoke(t.showTitle, t.seriesUrl, t.site)
                            }
                            onDismiss()
                        }
                    },
                    onDismiss = { upNextDismissed = true }
                )
            }
        }
        // (formerly the Dialog's closing brace — the overlay Box now closes
        // directly before the choice sheets)
        if (showAudioSheet) {
            AudioOptionsSheet(
                title = "Audio Settings",
                trackOptions = audioTrackLabels,
                selectedTrack = currentAudioLabel,
                allowVolumeBoost = allowVolumeBoost,
                currentVolumePercent = currentVolumePercent,
                onToggleVolumeBoost = { next ->
                    allowVolumeBoost = next
                    MediaPlayerPrefs.setAllowVolumeBoost(context, next)
                    if (!next && currentVolumePercent > 100) {
                        currentVolumePercent = 100
                        exoPlayer.volume = 1.0f
                        loudnessEnhancer.setBoostPercent(100, false)
                    }
                    showVolumeHud = true
                    volumeHudTick++
                    view.tick()
                },
                onPickTrack = { label ->
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
    if (showEpisodesSheet) {
        // Directive §5.2 in-player episode list: tag, title, watch state,
        // size; tapping switches playback through the existing onPlayFile.
        val byLabel = LinkedHashMap<String, String>()
        ctx.queuePeerPaths.forEach { p ->
            val tag = episodeTagFromPath(p) ?: File(p).nameWithoutExtension
            val rec = watchRecords.firstOrNull { it.filePath == p }
            val prefix = when {
                p == ctx.filePath -> "▶ Playing · "
                rec?.isWatched == true -> "✓ Watched · "
                rec != null && rec.positionMs > 0L ->
                    WatchHistoryCore.formatTimeLeft(rec.positionMs, rec.durationMs) + " · "
                else -> ""
            }
            val sizeMb = runCatching {
                "%.0f MB".format(File(p).length() / (1024.0 * 1024.0))
            }.getOrDefault("")
            val episodeTitle = ctx.taskForPath(p)?.episodeTitle?.takeIf { it.isNotBlank() }
                ?: File(p).nameWithoutExtension
            val baseLabel = "$prefix$tag · ${episodeTitle.take(30)} · $sizeMb"
            var label = baseLabel
            var suffix = 2
            while (byLabel.containsKey(label)) {
                label = "$baseLabel ($suffix)"
                suffix++
            }
            byLabel[label] = p
        }
        BottomChoiceSheet(
            title = "Episodes (${ctx.queuePeerPaths.size})",
            options = byLabel.keys.toList(),
            selected = byLabel.entries.firstOrNull { it.value == ctx.filePath }?.key,
            onPick = { label ->
                val path = byLabel[label]
                if (path != null && path != ctx.filePath) ctx.onPlayFile(path)
                showEpisodesSheet = false
            },
            onDismiss = { showEpisodesSheet = false }
        )
    }

    if (showSubtitleSheet) {
        BottomChoiceSheet(
            title = "Subtitles",
            options = subtitleOptions,
            selected = currentSubtitleLabel,
            onPick = { label ->
                val off = label == "Off"
                subtitleUserDecided = true
                currentSubtitleLabel = if (off) null else label
                applySubtitleByLabel(exoPlayer, exoPlayer.currentTracks, label, enable = !off)
                // Off persists as the DECISION sentinel (d6): null once meant
                // "never chose", so the next session re-ran auto-pick and
                // silently re-enabled subs the user had turned off. Any real
                // pick overwrites the sentinel with its label — re-picking a
                // track restores auto-pick trust exactly as before.
                MediaPlayerPrefs.setSubtitleTrack(
                    context,
                    if (off) SUBTITLE_PREF_OFF else label
                )
                showSubtitleSheet = false
            },
            onDismiss = { showSubtitleSheet = false }
        )
    }
}

private fun formatSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

/** Extracts "S01E05" / "E05" from a downloaded file name, for the player's
 *  Next label and episode list (directive §5.2). */
private fun episodeTagFromPath(path: String): String? {
    val name = File(path).nameWithoutExtension
    Regex("""(?i)\bS(\d{1,2})[\s._-]?E(\d{1,3})\b""").find(name)?.let { m ->
        return "S%02dE%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
    Regex("""(?i)\b(?:E|EP|EPISODE)[\s._-]?(\d{1,3})\b""").find(name)?.let { m ->
        return "E%02d".format(m.groupValues[1].toInt())
    }
    return null
}

private fun applySubtitleByLabel(
    player: ExoPlayer,
    tracks: Tracks,
    label: String,
    enable: Boolean
): Boolean {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    if (textGroups.isEmpty()) return false
    val builder = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enable)
    if (enable) {
        val matchIdx = textGroups.indexOfFirst { g ->
            val fmt = g.mediaTrackGroup.getFormat(0)
            fmt.label == label ||
                (fmt.label == null && fmt.language?.uppercase() == label) ||
                ("Track ${fmt.id}" == label)
        }
        if (matchIdx < 0) return false
        val group = textGroups[matchIdx].mediaTrackGroup
        builder.setOverrideForType(TrackSelectionOverride(group, 0))
    }
    player.trackSelectionParameters = builder.build()
    return true
}

/**
 * Overhauls subtitle typography, styling, and aspect-ratio dynamic positioning.
 *
 * 1. Re-parents [SubtitleView] into [AspectRatioFrameLayout] (`exo_content_frame`) so its
 *    coordinate space matches the active video frame across all resize modes (FIT, ZOOM, FILL)
 *    and video aspect ratios (16:9, 21:9, 4:3, vertical). In letterboxed FIT mode, this prevents
 *    subtitles from falling into the black bottom void of the phone screen.
 * 2. Modern intentional typography (Netflix / Apple TV aesthetic):
 *    - Crisp Roboto Medium / sans-serif-medium bold typeface
 *    - High-contrast black outline (EDGE_TYPE_OUTLINE) so text remains readable against any video frame
 *    - Completely transparent background (no boxy clunky artifacts)
 *    - Strips distorted embedded styling and font sizes
 * 3. Dynamic responsive text sizing:
 *    - Portrait: 0.068f of video frame height (clear and comfortable at arm's length)
 *    - Landscape: 0.054f of video frame height (cinematic streaming standard)
 * 4. Collision-aware dynamic bottom clearance:
 *    - When playback controls are visible AND the video touches the bottom of the screen
 *      (landscape, cropped/filled zoom, or portrait vertical video), lift the subtitle above
 *      the seekbar and control chips (0.22f). When controls auto-hide or in portrait FIT mode
 *      (where the horizontal video sits comfortably in the upper/center screen), settle at cinema baseline (0.065f).
 */
@OptIn(UnstableApi::class)
private fun configureSubtitles(
    playerView: PlayerView,
    isLandscape: Boolean,
    showControls: Boolean,
    resizeMode: Int,
    isVerticalVideo: Boolean
) {
    val subView = playerView.subtitleView ?: return
    val contentFrame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)

    if (contentFrame != null && subView.parent != contentFrame) {
        (subView.parent as? ViewGroup)?.removeView(subView)
        contentFrame.addView(
            subView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    val customStyle = CaptionStyleCompat(
        android.graphics.Color.WHITE,
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        android.graphics.Color.argb(220, 0, 0, 0),
        Typeface.create("sans-serif-medium", Typeface.BOLD)
    )
    subView.setStyle(customStyle)
    subView.setApplyEmbeddedStyles(false)
    subView.setApplyEmbeddedFontSizes(false)

    val textFraction = if (isLandscape) 0.054f else 0.068f
    subView.setFractionalTextSize(textFraction)

    val controlsOverlapVideo = showControls && (isLandscape || resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT || isVerticalVideo)
    val bottomPadding = if (controlsOverlapVideo) 0.22f else 0.065f
    subView.setBottomPaddingFraction(bottomPadding)
}

/** One circle-button style for the whole player. The OUTER box owns the hit
 *  area and is forced to the 48dp Android minimum; the inner box is the
 *  visual circle at the requested size. (The raw `clickable` this composes
 *  has no IconButton auto-bump, so 40dp corners used to mean 40dp targets.) */
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
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // v3.1.6 design pass: every control answers the tap. The old
                // null indication left Close/PiP/skip with zero feedback
                // (the pro-rules "tap feedback" line). The clip above keeps
                // the ripple inside the circle.
                indication = ripple(),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.50f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.40f),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/** Compact action chip for the bottom row (speed / audio / subtitles / fit-crop).
 *  Same trick as PlayerCircleButton: the pill keeps its ~38dp visual height,
 *  the wrapping box guarantees a 48dp-tall hit area. */
@Composable
private fun PlayerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: ImageVector
) {
    val bg = if (selected) PlayerAccent else Color.Black.copy(alpha = 0.55f)
    val fg = if (selected) Color.Black else Color.White
    val borderCol = if (selected) PlayerAccent else Color.White.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(50))
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
            fontSize = Type.caption.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
                    fontSize = Type.itemTitle.fontSize,
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
                                    indication = ripple(),
                                    onClick = { onPick(opt) }
                                )
                                .padding(horizontal = Spacing.md, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = opt,
                                color = if (isSelected) PlayerAccent else Color.White,
                                fontSize = Type.rowTitle.fontSize,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                // v3.1.6 design pass: the selected row used to
                                // differ only by a faint fill — a color-only
                                // signal. The check makes the state explicit.
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = PlayerAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
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

@Composable
private fun AudioOptionsSheet(
    title: String,
    trackOptions: List<String>,
    selectedTrack: String?,
    allowVolumeBoost: Boolean,
    currentVolumePercent: Int,
    onToggleVolumeBoost: (Boolean) -> Unit,
    onPickTrack: (String) -> Unit,
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
                    fontSize = Type.itemTitle.fontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )

                // Volume Boost Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PlayerSurfaceElevated)
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = "150% Volume Boost",
                                color = if (allowVolumeBoost) Color(0xFFFFA000) else Color.White,
                                fontSize = Type.rowTitle.fontSize,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (currentVolumePercent > 100) {
                                Text(
                                    text = "($currentVolumePercent%)",
                                    color = Color(0xFFFFA000),
                                    fontSize = Type.caption.fontSize,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Amplifies quiet dialogue tracks on phone speakers",
                            color = PlayerTextSecondary,
                            fontSize = Type.caption.fontSize
                        )
                    }
                    Switch(
                        checked = allowVolumeBoost,
                        onCheckedChange = onToggleVolumeBoost,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color(0xFFFFA000),
                            uncheckedThumbColor = PlayerTextSecondary,
                            uncheckedTrackColor = PlayerSurface
                        )
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                if (trackOptions.isNotEmpty()) {
                    Text(
                        text = "Tracks",
                        color = PlayerTextSecondary,
                        fontSize = Type.caption.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        trackOptions.forEach { opt ->
                            val isSelected = opt == selectedTrack
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) PlayerSurfaceElevated else Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(),
                                        onClick = { onPickTrack(opt) }
                                    )
                                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = opt,
                                    color = if (isSelected) PlayerAccent else Color.White,
                                    fontSize = Type.rowTitle.fontSize,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = PlayerAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayerNextPill(nextTag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.SkipNext,
            contentDescription = "Next: $nextTag",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "Next: $nextTag",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * End-of-episode card (directive §5.2). With a downloaded peer it offers
 * Play now; with only a known series it offers the fetch-next route. The
 * player places it bottom-end over the video — the composable itself owns no
 * alignment so the render harness can place it anywhere.
 */
@Composable
internal fun PlayerUpNextCard(
    modifier: Modifier = Modifier,
    nextTag: String?,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6141419))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = if (nextTag != null) {
                    "UP NEXT · $nextTag"
                } else {
                    "Finished downloaded episodes"
                },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (nextTag != null) {
                    "Plays automatically when this ends"
                } else {
                    "No more downloaded episodes"
                },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = actionLabel,
            color = PlayerAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(onClick = onAction)
                .padding(horizontal = 4.dp, vertical = 6.dp)
        )
        Text(
            text = "✕",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(start = 6.dp, end = 2.dp)
        )
    }
}

@Composable
internal fun GestureHudPill(
    icon: ImageVector,
    label: String
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xDD12141A))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PlayerAccent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = Type.rowTitle.fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun VolumeHudPill(
    volume: Int,
    isBoostAllowed: Boolean,
    isDetentActive: Boolean
) {
    // Fixed-geometry capsule (the HUD jank fix): the old pill sized itself
    // to its label, so every state change — "80%" → "100% (Pull to Boost)"
    // — re-measured a wrap-width Row and the capsule jumped width/position
    // mid-drag. Nothing in here is intrinsic-width anymore: one constant
    // capsule, percent-only text, and the boost/state vocabulary moved into
    // color (white = normal, amber = boost realm, amber border = sitting on
    // the boost edge at 100 with the gesture still held).
    val isBoost = volume > 100
    val boostAmber = Color(0xFFE8B04B)
    val capsuleBg = Color(0xDD12141A)
    val borderColor = when {
        isBoost || isDetentActive -> boostAmber.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.16f)
    }

    val icon = when {
        isBoost -> Icons.Filled.GraphicEq
        volume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
        volume <= 50 -> Icons.AutoMirrored.Filled.VolumeDown
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }

    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(capsuleBg)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isBoost) boostAmber else Color.White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "$volume%",
                color = if (isBoost) boostAmber else Color.White,
                fontSize = Type.rowTitle.fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            if (isBoost) {
                // 100% nominal base, amber segment rides on top for the
                // boost excess (up to the +50% cap).
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.67f)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.7f))
                )
                val boostFraction = ((volume - 100) / 50f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth((0.67f + (0.33f * boostFraction)).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(boostAmber)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((volume / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }
    }
}

@UnstableApi
internal class PlayerLoudnessEnhancer {
    private var enhancer: LoudnessEnhancer? = null
    private var currentSessionId: Int = -1

    var isAvailable: Boolean = false
        private set

    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        release()
        runCatching {
            enhancer = LoudnessEnhancer(audioSessionId).apply {
                enabled = false
            }
            currentSessionId = audioSessionId
            isAvailable = true
        }.onFailure {
            enhancer = null
            currentSessionId = -1
            isAvailable = false
        }
    }

    fun setBoostPercent(percent: Int, allowed: Boolean) {
        val e = enhancer ?: return
        if (!allowed || percent <= 100) {
            if (e.enabled) {
                runCatching { e.enabled = false }
            }
        } else {
            val boostFraction = ((percent - 100).coerceIn(1, 50)) / 50f
            val targetGainmB = (boostFraction * 4000f).toInt()
            runCatching {
                if (!e.enabled) e.enabled = true
                e.setTargetGain(targetGainmB)
            }
        }
    }

    fun release() {
        runCatching {
            enhancer?.enabled = false
            enhancer?.release()
        }
        enhancer = null
        currentSessionId = -1
        isAvailable = false
    }
}

/**
 * ImageView fallback inside the player overlay: chrome (black stage, title
 * row, close) identical to the video player, but the body is a Coil image
 * instead of an ExoPlayer surface. This is where the Instagram last-resort
 * stills land — the successful path now always muxes them into a video, so
 * this viewer only ever sees genuinely-unmuxable photo saves and must show
 * the picture, never a player error.
 */
@Composable
private fun MediaImageStill(
    filePath: String,
    title: String,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
    ) {
        AsyncImage(
            model = java.io.File(filePath),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = "Photo • tap anywhere to close",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

