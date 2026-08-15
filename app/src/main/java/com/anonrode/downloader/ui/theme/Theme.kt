package com.anonrode.downloader.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BackgroundDark = Color(0xFF000000)
val SurfaceCard = Color(0xFF101216)
val SurfaceElevated = Color(0xFF181B22)
val BorderHairline = Color(0xFF1F232D)

val AccentPrimary = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

val StatusSuccess = Color(0xFF10B981)
val StatusError = Color(0xFFEF4444)
val StatusWarning = Color(0xFFF59E0B)

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color(0xFF000000),
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = TextPrimary,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = BackgroundDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = BorderHairline
)

@Composable
fun AnonDownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
