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
    background = Color(0xFFF4F7FA),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFE9EFF5),
    borderHairline = Color(0xFFD8E0E8),
    accentPrimary = Color(0xFF07131A),
    textPrimary = Color(0xFF07131A),
    textSecondary = Color(0xFF475569),
    // #55627A (was #64748B): 64748B computed only 4.34:1 on surfaceElevated
    // (the pair ships as the QUEUED chip) — under the 4.5:1 floor for the
    // 9-11sp chip labels that use muted-on-elevated. #55627A is 5.6:1 there
    // and visually near-identical on the #F4F7FA background.
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

/**
 * Dual-Theme Brand Tokens for the Splash Screen:
 * - Bare Dark ("White No Tile A"): Pure #000000 surface, #FFFFFF monogram, #22D3EE subtitle.
 * - Minimal Light ("Light Surface A"): Porcelain #F4F7FA surface, #07131A deep ink monogram, #0E7490 subtitle.
 * Paired 1:1 with res/values/colors.xml and res/values-night/colors.xml for zero-flicker starting window handoff.
 */
data class SplashThemeColors(
    val background: Color,
    val glyph: Color,
    val title: Color,
    val subtitle: Color,
    val progressTrack: Color,
    val progressFill: Color,
)

val DarkSplashColors = SplashThemeColors(
    background = Color(0xFF000000),
    glyph = Color(0xFFFFFFFF),
    title = Color(0xFFFFFFFF),
    subtitle = Color(0xFF22D3EE),
    progressTrack = Color(0xFF181B22),
    progressFill = Color(0xFFFFFFFF)
)

val LightSplashColors = SplashThemeColors(
    background = Color(0xFFF4F7FA),
    glyph = Color(0xFF07131A),
    title = Color(0xFF0A1620),
    subtitle = Color(0xFF0E7490),
    progressTrack = Color(0xFFD5DEE7),
    progressFill = Color(0xFF07131A)
)

// Backward-compatible fallback accessors
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
    primary = Color(0xFF07131A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9EFF5),
    onPrimaryContainer = Color(0xFF07131A),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF07131A),
    surface = Color(0xFFF4F7FA),
    onSurface = Color(0xFF07131A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFD8E0E8),
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
            typography = AnonTypography,
            content = content
        )
    }
}

/**
 * M3 component defaults, mapped onto this app's scale (UI research round).
 *
 * Why this is needed at all: the app sets `fontSize` on almost every `Text`,
 * but Material 3 components style their own internal text from the typography,
 * so without this the two halves of the UI disagree. The visible consequences
 * were AlertDialog titles at 24sp — bigger than the page title above them — and
 * DropdownMenuItem labels at 16sp against 13sp everywhere else.
 *
 * Blast radius, enumerated before applying (tools/typolist.py, which paren-
 * matches every `Text(` and skips any call that sets `fontSize` or `style`):
 *   - 18 `Text` calls inherit the default. Nearly all sit inside stock buttons;
 *     the rest are dialog action labels ("Cancel all", "Keep file", "Retry"…).
 *   - 26 Button/TextButton/FilledTonalButton labels -> labelLarge (14sp -> 12sp),
 *     which is the size this app's own in-card actions already use, so the
 *     buttons stop being the odd ones out.
 *   - 1 DropdownMenuItem (the Downloads sort menu) -> bodyLarge (16sp -> 13sp),
 *     the reported defect.
 *   - NavigationBarItem labels (11sp), the nav badge (10sp), FilterChip labels
 *     (11-12sp) and the one TopAppBar title all set their own size, so the
 *     titleLarge/labelMedium/labelSmall mappings change nothing today — they
 *     are here so a future bare Material component lands on the scale instead
 *     of on the baseline.
 *
 * Judgement call worth naming: stock button labels get smaller (14 -> 12sp).
 * That is deliberate — consistency with the app's own chips and action rows —
 * and buttons keep their 40dp minimum height, so nothing shrinks but the type.
 * If it reads too small on device, `labelLarge = Type.rowTitle` restores 14sp
 * without touching anything else.
 */
val AnonTypography = Typography(
    headlineSmall = Type.itemTitle,   // AlertDialog titles (was 24sp)
    titleLarge = Type.screenTitle,
    titleMedium = Type.itemTitle,
    titleSmall = Type.rowTitle,
    bodyLarge = Type.body,            // DropdownMenuItem labels (was 16sp)
    bodyMedium = Type.body,
    bodySmall = Type.label,
    labelLarge = Type.label,          // every Button/TextButton label
    labelMedium = Type.caption,
    labelSmall = Type.micro
)
