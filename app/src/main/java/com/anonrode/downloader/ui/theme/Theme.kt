package com.anonrode.downloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

data class AnonColors(
    val isDark: Boolean,
    val background: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val borderHairline: Color,
    val accentPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentViolet: Color,
    val accentPink: Color,
    val statusSuccess: Color,
    val statusError: Color,
    val statusWarning: Color
)

val DarkAnonColors = AnonColors(
    isDark = true,
    background = Color(0xFF000000),
    surfaceCard = Color(0xFF101216),
    surfaceElevated = Color(0xFF181B22),
    borderHairline = Color(0xFF1F232D),
    accentPrimary = Color(0xFFFFFFFF),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    accentViolet = Color(0xFF8B5CF6),
    accentPink = Color(0xFFEC4899),
    statusSuccess = Color(0xFF10B981),
    statusError = Color(0xFFEF4444),
    statusWarning = Color(0xFFF59E0B)
)

val LightAnonColors = AnonColors(
    isDark = false,
    background = Color(0xFFF8FAFC),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF1F5F9),
    borderHairline = Color(0xFFE2E8F0),
    accentPrimary = Color(0xFF0F172A),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF94A3B8),
    accentViolet = Color(0xFF7C3AED),
    accentPink = Color(0xFFDB2777),
    statusSuccess = Color(0xFF059669),
    statusError = Color(0xFFDC2626),
    statusWarning = Color(0xFFD97706)
)

val LocalAnonColors = staticCompositionLocalOf { DarkAnonColors }

object AnonTheme {
    val colors: AnonColors
        @Composable
        get() = LocalAnonColors.current
}

// Backward-compatible dynamic accessors for Compose call sites
val BackgroundDark: Color @Composable get() = AnonTheme.colors.background
val SurfaceCard: Color @Composable get() = AnonTheme.colors.surfaceCard
val SurfaceElevated: Color @Composable get() = AnonTheme.colors.surfaceElevated
val BorderHairline: Color @Composable get() = AnonTheme.colors.borderHairline
val AccentPrimary: Color @Composable get() = AnonTheme.colors.accentPrimary
val TextPrimary: Color @Composable get() = AnonTheme.colors.textPrimary
val TextSecondary: Color @Composable get() = AnonTheme.colors.textSecondary
val TextMuted: Color @Composable get() = AnonTheme.colors.textMuted
val AccentViolet: Color @Composable get() = AnonTheme.colors.accentViolet
val AccentPink: Color @Composable get() = AnonTheme.colors.accentPink
val StatusSuccess: Color @Composable get() = AnonTheme.colors.statusSuccess
val StatusError: Color @Composable get() = AnonTheme.colors.statusError
val StatusWarning: Color @Composable get() = AnonTheme.colors.statusWarning

private val DarkMaterialColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF181B22),
    onPrimaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF101216),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF1F232D)
)

private val LightMaterialColorScheme = lightColorScheme(
    primary = Color(0xFF0F172A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF1F5F9),
    onPrimaryContainer = Color(0xFF0F172A),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0)
)

@Composable
fun AnonDownloaderTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode.lowercase()) {
        "light" -> false
        "system" -> isSystemDark
        else -> true
    }

    val anonColors = if (isDark) DarkAnonColors else LightAnonColors
    val materialScheme = if (isDark) DarkMaterialColorScheme else LightMaterialColorScheme

    CompositionLocalProvider(LocalAnonColors provides anonColors) {
        MaterialTheme(
            colorScheme = materialScheme,
            content = content
        )
    }
}
