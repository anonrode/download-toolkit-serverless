package com.anonrode.downloader.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
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
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                    contentAlignment = Alignment.Center
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
                    audioOnly = audioOnly
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
                    parallelSockets = 16
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
                    parallelSockets = engine.parallelSocketsPerFile
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
                    audioOnly = audioOnly
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

    val platformName = when (parsedUrl) {
        is ParsedUrl.SocialUrl -> parsedUrl.platform
        is ParsedUrl.DramaUrl -> parsedUrl.site.replaceFirstChar { it.uppercase() }
        is ParsedUrl.MagnetUrl -> "Torrent"
        is ParsedUrl.DirectMediaUrl -> "Direct Media"
        else -> "Web"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderHairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header with badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(AccentViolet.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = platformName,
                            color = AccentViolet,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Quick Download",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .background(SurfaceCard, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // URL Preview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = rawUrl,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Format Toggle (Video vs Audio)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !audioOnly,
                    onClick = { audioOnly = false },
                    label = { Text("Video (MP4)") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Videocam, null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentViolet.copy(alpha = 0.2f),
                        selectedLabelColor = AccentViolet,
                        selectedLeadingIconColor = AccentViolet
                    )
                )

                FilterChip(
                    selected = audioOnly,
                    onClick = { audioOnly = true },
                    label = { Text("Audio (MP3)") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Audiotrack, null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPink.copy(alpha = 0.2f),
                        selectedLabelColor = AccentPink,
                        selectedLeadingIconColor = AccentPink
                    )
                )
            }

            if (!audioOnly) {
                Spacer(modifier = Modifier.height(12.dp))
                // Quality Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("480p", "720p", "1080p", "Best").forEach { q ->
                        val isSel = selectedQuality.equals(q, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AccentViolet else SurfaceCard)
                                .clickable { selectedQuality = q }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = q,
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Remember instant download checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { alwaysInstant = !alwaysInstant },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = alwaysInstant,
                    onCheckedChange = { alwaysInstant = it },
                    colors = CheckboxDefaults.colors(checkedColor = AccentViolet)
                )
                Text(
                    text = "Always download instantly (skip this dialog)",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onDownload(selectedQuality, audioOnly, alwaysInstant) },
                    modifier = Modifier.weight(1.5f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet)
                ) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
