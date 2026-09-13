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
    // #A8B8CE (was #94A3B8, identical to textMuted): the two-tier text
    // hierarchy every screen leans on silently collapsed in dark mode.
    // Contrast: 9.0:1 on surfaceCard, 8.9:1 on background.
    textSecondary = Color(0xFFA8B8CE),
    textMuted = Color(0xFF94A3B8),
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
    // #55627A (was #64748B): 64748B computed only 4.34:1 on surfaceElevated
    // (the pair ships as the QUEUED chip) — under the 4.5:1 floor for the
    // 9-11sp chip labels that use muted-on-elevated. #55627A is 5.6:1 there
    // and visually near-identical on the #F8FAFC background.
    textMuted = Color(0xFF55627A),
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
val BackgroundDark: Color @Composable get() = AnonTheme.colors.backgroundval SurfaceCard: Color @Composable get() = AnonTheme.colors.surfaceCard
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

/**
 * PINNED splash colors — deliberately NOT theme-aware. The system splash
 * window (styles.xml @color/splash_background) is black on every device, so
 * the Compose splash must be black too: with theme-aware colors a light-theme
 * device got black system-splash -> WHITE compose splash -> light app, a
 * visible mid-handover flash. These constants are the contract with
 * styles.xml; the only theme transition is the final one into the app.
 */
val SplashBackground = Color(0xFF000000)
val SplashOnBackground = Color(0xFFFFFFFF)
val SplashMuted = Color(0xFF94A3B8)
val SplashElevated = Color(0xFF181B22)

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
    outline = Color(0xFF1F232D),
    // Explicit secondary/tertiary/error: left to the baseline these are
    // Material's own M3-purple/M3-red, which clash with the app's violet/
    // pink/status tokens the moment any M3 component touches those roles
    // (AlertDialog icons, chip fallbacks, error fields).
    secondary = Color(0xFFB7A6FA),
    onSecondary = Color(0xFF241443),
    secondaryContainer = Color(0xFF3A2A6B),
    onSecondaryContainer = Color(0xFFE4DBFD),
    tertiary = Color(0xFFF2A6CD),
    onTertiary = Color(0xFF400B24),
    tertiaryContainer = Color(0xFF5D1637),
    onTertiaryContainer = Color(0xFFFBD5E6),
    error = Color(0xFFF87171),
    onError = Color(0xFF3F0909),
    errorContainer = Color(0xFF5F1A1A),
    onErrorContainer = Color(0xFFFDCFCF)
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
    outline = Color(0xFFE2E8F0),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE4FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFFDB2777),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFCE4EF),
    onTertiaryContainer = Color(0xFF500D2E),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A)
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
