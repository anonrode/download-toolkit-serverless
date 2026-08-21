package com.anonrode.downloader.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
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
import com.anonrode.downloader.AnonApp
import com.anonrode.downloader.data.router.ParsedUrl
import com.anonrode.downloader.data.router.UrlRouter
import com.anonrode.downloader.ui.theme.*

class QuickShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = extractSharedUrl(intent)
        if (sharedText.isBlank()) {
            Toast.makeText(this, "No valid link found in share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val parsed = UrlRouter.parse(sharedText)
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
                        onDismiss = { finish() },
                        onDownload = { quality, audioOnly, makeInstant ->
                            if (makeInstant) {
                                prefs.edit().putBoolean("pref_instant_social", true).apply()
                                (application as? AnonApp)?.engine?.instantSocialDownload = true
                            }
                            dispatchDownload(parsed, sharedText, quality, audioOnly)
                            Toast.makeText(this@QuickShareActivity, "🚀 Download queued in background", Toast.LENGTH_SHORT).show()
                            finish()
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
        dispatchDownload(parsed, rawUrl, quality = "720p", audioOnly = false)
        val label = when (parsed) {
            is ParsedUrl.SocialUrl -> parsed.platform
            is ParsedUrl.DramaUrl -> parsed.site
            else -> "Media"
        }
        Toast.makeText(this, "🚀 $label download started in background", Toast.LENGTH_SHORT).show()
    }

    private fun dispatchDownload(parsed: ParsedUrl, rawUrl: String, quality: String, audioOnly: Boolean) {
        val app = application as? AnonApp ?: return
        val engine = app.engine

        when (parsed) {
            is ParsedUrl.SocialUrl -> {
                engine.enqueue(
                    showTitle = "Social/${parsed.platform}",
                    episodeNum = 1,
                    episodeTitle = "${parsed.platform} Video",
                    sourceUrl = parsed.cleanUrl,
                    isDirect = false,
                    backend = "yt-dlp",
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
                    backend = "aria2c",
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
                    backend = "aria2c",
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
                    backend = "aria2c",
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
                    backend = "yt-dlp",
                    parallelSockets = engine.parallelSocketsPerFile,
                    audioOnly = audioOnly,
                    quality = if (audioOnly) null else quality
                )
            }
        }
    }
}

@Composable
fun QuickShareCard(
    parsedUrl: ParsedUrl,
    rawUrl: String,
    onDismiss: () -> Unit,
    onDownload: (quality: String, audioOnly: Boolean, makeInstant: Boolean) -> Unit
) {
    var audioOnly by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf("720p") }
    var alwaysInstant by remember { mutableStateOf(false) }
    // Guards against double-tap re-enqueue during the dismissal animation.
    var enqueued by remember { mutableStateOf(false) }

    val platformName = when (parsedUrl) {
        is ParsedUrl.SocialUrl -> parsedUrl.platform
        is ParsedUrl.DramaUrl -> parsedUrl.site.replaceFirstChar { it.uppercase() }
        is ParsedUrl.MagnetUrl -> "Torrent"
        is ParsedUrl.DirectMediaUrl -> "Direct Media"
        else -> "Web"
    }

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
                .padding(horizontal = Spacing.xl)
                .padding(top = Spacing.lg, bottom = Spacing.xxl)
        ) {
            // Drag handle — signals a bottom sheet, matches Seal's share UI.
            Box(
                modifier = Modifier
                    .padding(bottom = Spacing.md)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BorderHairline)
                    .align(Alignment.CenterHorizontally)
            )
            // ---- Header: source label -> title -> close ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(AccentViolet, CircleShape)
                        )
                        Text(
                            text = platformName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            color = TextMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Quick Download",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
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

            Spacer(modifier = Modifier.height(Spacing.xl))

            // ---- URL preview ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = rawUrl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // ---- Format: Video / Audio segmented control ----
            SectionLabel(text = "Format")
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                    .padding(Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                FormatSegment(
                    selected = !audioOnly,
                    onClick = { audioOnly = false },
                    icon = Icons.Rounded.Videocam,
                    label = "Video",
                    modifier = Modifier.weight(1f)
                )
                FormatSegment(
                    selected = audioOnly,
                    onClick = { audioOnly = true },
                    icon = Icons.Rounded.Audiotrack,
                    label = "Audio",
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
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    SectionLabel(text = "Quality")
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        listOf("480p", "720p", "1080p", "Best").forEach { q ->
                            val isSelected = selectedQuality.equals(q, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(
                                        if (isSelected) AccentViolet else SurfaceCard,
                                        RoundedCornerShape(Radius.md)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) AccentViolet else BorderHairline,
                                        shape = RoundedCornerShape(Radius.md)
                                    )
                                    .clickable { selectedQuality = q },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = q,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // ---- Behavior: always download instantly ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(Radius.md))
                    .clickable { alwaysInstant = !alwaysInstant }
                    .padding(start = Spacing.xs, end = Spacing.md, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = alwaysInstant,
                    onCheckedChange = { alwaysInstant = it },
                    colors = CheckboxDefaults.colors(checkedColor = AccentViolet)
                )
                Text(
                    text = "Always download instantly",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Skip this dialog",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            // ---- Actions ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderHairline)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = {
                        if (enqueued) return@Button
                        enqueued = true
                        onDownload(selectedQuality, audioOnly, alwaysInstant)
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .height(52.dp),
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet)
                ) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text("Download", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = TextMuted
    )
}

@Composable
private fun FormatSegment(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    // Weight must come from the caller's RowScope — a composable cannot apply
    // it to itself.
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (selected) AccentViolet.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AccentViolet else TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) AccentViolet else TextSecondary
        )
    }
}
