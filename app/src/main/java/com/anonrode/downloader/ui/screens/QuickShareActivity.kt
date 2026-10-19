package com.anonrode.downloader.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.anonrode.downloader.AnonApp
import com.anonrode.downloader.data.router.ParsedUrl
import com.anonrode.downloader.data.router.UrlRouter
import com.anonrode.downloader.ui.components.SegmentBar
import com.anonrode.downloader.ui.components.SegmentCell
import com.anonrode.downloader.ui.theme.*
import com.anonrode.downloader.ui.util.confirmHaptic
import com.anonrode.downloader.ui.util.pressScale

class QuickShareActivity : ComponentActivity() {

    // API 26-29 path: WRITE_EXTERNAL_STORAGE is a normal runtime permission.
    // API 30+ has no runtime dialog — "All files access" is Settings-only,
    // handled below with a toast directing the user there.
    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // The sheet is still open and its Download guard just refused —
            // without this hint the user had to guess they may tap again.
            Toast.makeText(this, "Storage permission granted — tap Download again", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Storage permission required to download", Toast.LENGTH_SHORT).show()
        }
    }

    // API 30+ has no runtime dialog; deep-link into the exact Settings page
    // (the toast alone said "enable it in Settings" and left the walking to
    // the user — and the result code is meaningless here, so re-check on
    // return and tell them to tap Download again).
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Storage access granted — tap Download again", Toast.LENGTH_SHORT).show()
        }
    }

    // Gate every download on storage access. Without it the engine guard
    // rejects the write with a confusing IO error (tester report): on
    // API 30+ "All files access" must be enabled in Settings; on API 26-29
    // a normal permission prompt is raised.
    private fun ensureStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                true
            } else {
                Toast.makeText(
                    this,
                    "Enable 'All files access' for Anonrode in Settings",
                    Toast.LENGTH_SHORT
                ).show()
                try {
                    manageStorageLauncher.launch(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    // Some OEMs drop the All-files-access action — fall back
                    // to the app's own info page (same as MainActivity).
                    try {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            }
                        )
                    } catch (_: Exception) {}
                }
                false
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = extractSharedUrl(intent)
        if (sharedText.isBlank()) {
            Toast.makeText(this, "No valid link found in share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val parsed = UrlRouter.parse(sharedText)
        // A share with NO URL in it (a caption, a lyric, a title) parses to a
        // SearchQuery. The download paths cannot handle a query: the sheet
        // used to show the text as a monospace "URL preview" with a live
        // Download button, and the engine would enqueue the prose as a URL —
        // a doomed task with confusing failures after retries.
        if (parsed is ParsedUrl.SearchQuery) {
            Toast.makeText(
                this,
                "Shared text contains no link — copy the URL (starting with http) and share again",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        val prefs = getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        val isInstant = prefs.getBoolean("pref_instant_social", false)

        if (isInstant) {
            handleInstantDownload(parsed, sharedText)
            finish()
            return
        }

        val themeMode = prefs.getString("pref_theme_mode", "dark") ?: "dark"

        setContent {
            AnonDownloaderTheme(themeMode = themeMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { finish() },
                    // Seal-style: the share sheet docks to the bottom edge at full
                    // width — a centered floating card crammed every control into
                    // a phone-width dialog and felt tight (user-reported).
                    contentAlignment = Alignment.BottomCenter
                ) {
                    QuickShareCard(
                        parsedUrl = parsed,
                        rawUrl = sharedText,
                        defaultQuality = (application as? AnonApp)?.engine?.defaultQuality ?: "720p",
                        onDismiss = { finish() },
                        onDownload = { quality, audioOnly, makeInstant, engineOverride ->
                            if (!ensureStoragePermission()) {
                                false
                            } else {
                                val appEngine = (application as? AnonApp)?.engine
                                if (makeInstant) {
                                    // engine.setInstantSocial is the ONE writer of
                                    // pref_instant_social (it persists the pref and
                                    // sets the field in one step). The direct
                                    // prefs.edit() + field poke that used to live
                                    // here was a second, racing write path — the
                                    // dual-path asymmetry the Settings snapshot
                                    // could clobber.
                                    appEngine?.setInstantSocial(true)
                                } else if (appEngine != null && appEngine.instantSocialDownload) {
                                    // Un-checking here must be able to turn instant
                                    // mode OFF — it is settable from SocialModal in
                                    // the SAME process, so the sheet's state could
                                    // be stale-true while this sheet is showing.
                                    appEngine.setInstantSocial(false)
                                }
                                dispatchDownload(parsed, sharedText, quality, audioOnly, engineOverride)
                                Toast.makeText(this@QuickShareActivity, "Download queued in background", Toast.LENGTH_SHORT).show()
                                finish()
                                true
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleInstance: a second share arrives here with the fresh intent.
        // Re-run the whole flow so the sheet shows the new link instead of
        // silently displaying the stale first-share content.
        setIntent(intent)
        recreate()
    }

    private fun extractSharedUrl(intent: Intent?): String {
        if (intent == null) return ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).text else null }?.toString()
            ?: intent.dataString
            ?: ""

        // Extract first HTTP/HTTPS URL from text (e.g. from Instagram captions)
        val matcher = java.util.regex.Pattern.compile("""https?://[^\s"'<>]+""").matcher(text)
        return if (matcher.find()) matcher.group(0) else text.trim()
    }

    private fun handleInstantDownload(parsed: ParsedUrl, rawUrl: String) {
        if (!ensureStoragePermission()) return
        // Honor the user's configured default quality instead of a hardcoded
        // 720p — the in-app SocialModal already seeds from defaultQuality.
        val q = (application as? AnonApp)?.engine?.defaultQuality ?: "720p"
        dispatchDownload(parsed, rawUrl, quality = q, audioOnly = false)
        // User directive 2026-10-15 — presentation never paints the drama site
        // name; "Drama" says what the download is while the site word stays in
        // the model + journal. Social platforms keep their platform label.
        val label = when (parsed) {
            is ParsedUrl.SocialUrl -> parsed.platform
            is ParsedUrl.DramaUrl -> "Drama"
            else -> "Media"
        }
        Toast.makeText(this, "$label download started in background", Toast.LENGTH_SHORT).show()
    }

    private fun dispatchDownload(parsed: ParsedUrl, rawUrl: String, quality: String, audioOnly: Boolean, engineOverride: String = "auto") {
        val app = application as? AnonApp ?: return
        val engine = app.engine
        val backend = if (engineOverride != "auto") engineOverride else when (parsed) {
            is ParsedUrl.SocialUrl -> "yt-dlp"
            is ParsedUrl.MagnetUrl -> "aria2c"
            is ParsedUrl.DirectMediaUrl -> "aria2c"
            is ParsedUrl.DramaUrl -> "aria2c"
            else -> "yt-dlp"
        }

        when (parsed) {
            is ParsedUrl.SocialUrl -> {
                engine.enqueue(
                    showTitle = "Social/${parsed.platform}",
                    episodeNum = 1,
                    episodeTitle = "${parsed.platform} Video",
                    sourceUrl = parsed.cleanUrl,
                    isDirect = false,
                    backend = backend,
                    parallelSockets = engine.parallelSocketsPerFile,
                    audioOnly = audioOnly,
                    quality = if (audioOnly) null else quality
                )
            }
            is ParsedUrl.MagnetUrl -> {
                engine.enqueue(
                    showTitle = "Torrents",
                    episodeNum = 1,
                    episodeTitle = parsed.title,
                    sourceUrl = parsed.magnet,
                    isDirect = true,
                    backend = backend,
                    parallelSockets = engine.parallelSocketsPerFile
                )
            }
            is ParsedUrl.DirectMediaUrl -> {
                engine.enqueue(
                    showTitle = "Direct Downloads",
                    episodeNum = 1,
                    episodeTitle = parsed.filename,
                    sourceUrl = parsed.url,
                    isDirect = true,
                    backend = backend,
                    parallelSockets = engine.parallelSocketsPerFile
                )
            }
            is ParsedUrl.DramaUrl -> {
                engine.enqueue(
                    showTitle = parsed.showCard.title,
                    episodeNum = 1,
                    episodeTitle = parsed.showCard.title,
                    sourceUrl = parsed.showCard.url,
                    isDirect = false,
                    backend = backend,
                    parallelSockets = engine.parallelSocketsPerFile,
                    quality = if (audioOnly) null else quality
                )
            }
            else -> {
                engine.enqueue(
                    showTitle = "Shared Media",
                    episodeNum = 1,
                    episodeTitle = "Shared Media",
                    sourceUrl = rawUrl,
                    isDirect = false,
                    backend = backend,
                    parallelSockets = engine.parallelSocketsPerFile,
                    audioOnly = audioOnly,
                    quality = if (audioOnly) null else quality
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * The share sheet.
 *
 * One decision per glance, per the canonical monochrome system: WHO is
 * downloading (identity header), WHAT is being downloaded (media card with
 * poster/title/source), and HOW (three uniform segment bars + the instant
 * switch), closed by ONE confident primary action. The old sheet stacked six
 * differently-styled control groups with violet-tinted fills — every group
 * competing; the redesign speaks the same selection language everywhere
 * (selected cell = AccentPrimary fill + BackgroundDark ink, everywhere).
 * ------------------------------------------------------------------------- */
@Composable
fun QuickShareCard(
    parsedUrl: ParsedUrl,
    rawUrl: String,
    // Seeded from the engine (see call site): the in-app SocialModal already
    // respects the configured default — a hardcoded 720p here made the same
    // feature offer two different qualities from two entry points.
    defaultQuality: String = "720p",
    onDismiss: () -> Unit,
    onDownload: (quality: String, audioOnly: Boolean, makeInstant: Boolean, engineOverride: String) -> Boolean
) {
    var audioOnly by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf(defaultQuality) }
    var alwaysInstant by remember { mutableStateOf(false) }
    var engineOverride by remember { mutableStateOf("auto") }
    // Guards against double-tap re-enqueue during the dismissal animation.
    var enqueued by remember { mutableStateOf(false) }

    // User directive 2026-10-15 — the drama source site never paints (the show
    // itself is the card's headline + poster below); a neutral kind word takes
    // its place here and as the source-host fallback.
    val platformName = when (parsedUrl) {
        is ParsedUrl.SocialUrl -> parsedUrl.platform
        is ParsedUrl.DramaUrl -> "Drama"
        is ParsedUrl.MagnetUrl -> "Torrent"
        is ParsedUrl.DirectMediaUrl -> "Direct Media"
        else -> "Web"
    }

    // What is being downloaded — the user's own words for it, not the
    // router's. Never fabricated beyond what the share actually carries.
    val mediaTitle = when (parsedUrl) {
        is ParsedUrl.DramaUrl -> parsedUrl.showCard.title
        is ParsedUrl.MagnetUrl -> parsedUrl.title
        is ParsedUrl.DirectMediaUrl -> parsedUrl.filename
        is ParsedUrl.SocialUrl -> "${parsedUrl.platform} video"
        else -> "Shared media"
    }
    val posterUrl = (parsedUrl as? ParsedUrl.DramaUrl)?.showCard?.posterUrl.orEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderHairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Full-screen theme now sizes this card to the real screen
                // height — the content can exceed small screens, so the sheet
                // scrolls internally (the drag handle stays at the top edge).
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xl)
        ) {
            // Drag handle — signals a bottom sheet, matches Seal's share UI.
            Box(
                modifier = Modifier
                    .padding(bottom = Spacing.md)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(BorderHairline)
                    .align(Alignment.CenterHorizontally)
            )

            // ---- Identity: WHO is about to download ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // The app mark: ink-on-accent square, the one saturated block
                // on the sheet — identity, not decoration.
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
                        text = "Anonrode",
                        fontSize = Type.rowTitle.fontSize,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Shared link · $platformName",
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

            // ---- Media: WHAT is being downloaded ----
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
                // 64dp thumb: the real poster when the router has one, else a
                // type tile. 1dp white-10% outline — images read as objects.
                val thumbShape = RoundedCornerShape(Radius.sm)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(thumbShape)
                        .background(SurfaceElevated)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), thumbShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (posterUrl.isNotBlank()) {
                        coil.compose.AsyncImage(
                            model = posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = when (parsedUrl) {
                                is ParsedUrl.MagnetUrl -> Icons.Rounded.Bolt
                                is ParsedUrl.DirectMediaUrl -> Icons.Rounded.Videocam
                                is ParsedUrl.DramaUrl -> Icons.Rounded.Videocam
                                else -> Icons.Rounded.Link
                            },
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaTitle,
                        fontSize = Type.rowTitle.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // User directive 2026-10-15 — the meta line identifies by
                        // KIND ("Drama · 720p"), never by the parsed URL's host;
                        // the neutral platformName already covers the fallback
                        // role the old sourceHost derivation tried to play.
                        text = "$platformName · ${if (audioOnly) "Audio (MP3)" else selectedQuality}",
                        fontSize = Type.caption.fontSize,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = rawUrl,
                        // A URL is data, not decoration — the one place mono
                        // is the honest face (and shrink-on-overflow beats
                        // truncating the middle of an artifact).
                        fontSize = Type.micro.fontSize,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // ---- Format: Video / Audio ----
            SegmentBar {
                SegmentCell(
                    selected = !audioOnly,
                    onClick = { audioOnly = false },
                    icon = Icons.Rounded.Videocam,
                    label = "Video",
                    modifier = Modifier.weight(1f)
                )
                SegmentCell(
                    selected = audioOnly,
                    onClick = { audioOnly = true },
                    icon = Icons.Rounded.Audiotrack,
                    label = "Audio",
                    modifier = Modifier.weight(1f)
                )
            }

            // ---- Download engine ----
            Spacer(modifier = Modifier.height(Spacing.sm))
            SegmentBar {
                SegmentCell(
                    selected = engineOverride == "auto",
                    onClick = { engineOverride = "auto" },
                    icon = Icons.Rounded.Settings,
                    label = "Auto",
                    modifier = Modifier.weight(1f)
                )
                SegmentCell(
                    selected = engineOverride == "aria2c",
                    onClick = { engineOverride = "aria2c" },
                    icon = Icons.Rounded.Bolt,
                    label = "Fast",
                    modifier = Modifier.weight(1f)
                )
                SegmentCell(
                    selected = engineOverride == "yt-dlp",
                    onClick = { engineOverride = "yt-dlp" },
                    icon = Icons.Rounded.PlayArrow,
                    label = "Extractor",
                    modifier = Modifier.weight(1f)
                )
            }

            // ---- Quality (video only) ----
            AnimatedVisibility(
                visible = !audioOnly,
                enter = fadeIn(animationSpec = tween(Motion.DurationNormal)) +
                        expandVertically(animationSpec = tween(Motion.DurationNormal)),
                exit = fadeOut(animationSpec = tween(Motion.DurationNormal)) +
                        shrinkVertically(animationSpec = tween(Motion.DurationNormal))
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
                    .clip(RoundedCornerShape(Radius.md))
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

            // ---- The one action (tap outside or ✕ dismisses) ----
            val dlSrc = remember { MutableInteractionSource() }
            // Commit thump (Haptics verb) — fired only when a task queued.
            val hapticView = LocalView.current
            Button(
                onClick = {
                    if (enqueued) return@Button
                    enqueued = true
                    // The storage permission check can block the download
                    // (sheet stays open): only keep the double-tap guard
                    // armed when a task was actually queued, so the user
                    // can retry after granting permission.
                    val queued = onDownload(selectedQuality, audioOnly, alwaysInstant, engineOverride)
                    if (!queued) {
                        enqueued = false
                    } else {
                        hapticView.confirmHaptic()
                    }
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
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
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

