package com.anonrode.downloader.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

object Radius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val pill = 999.dp
    val full = 999.dp   // alias: fully-rounded (chips, circular controls)
}

object Motion {
    const val DurationFast = 200
    const val DurationNormal = 350
    const val DurationSlow = 500

    /** Nav exit duration (Nav speed contract, ui-proposal-downloads-v6-drawer.html):
     *  screen-level transitions cap at 200ms entrance / 120ms exit — one third
     *  of the old 500ms wait, same easing curve. */
    const val DurationNavExit = 120

    /** The app's one curve: press-scale and settings nav both ride it.
     *  (Was duplicated as PressScale's inline bezier and SettingsSheet's
     *  private SettingsEasing; byte-identical, and M3's standard/emphasized
     *  cubic-bezier verbatim.) */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Press personality: the scale tween under the finger, and the two named
     *  scale factors — 0.96 lone button/card · 0.97 wide shared surfaces. */
    const val DurationPress = 140
    const val PressScaleDefault = 0.96f
    const val PressScaleLarge = 0.97f

    // M3 "standard" spring parameters (copied from material3's
    // StandardMotionTokens — MotionScheme itself only exists from
    // material3 1.5.0-alpha, so the numbers live here as constants).
    // spatial = position/size changes; effects = color/opacity.
    const val SpatialDamping = 0.9f
    const val SpatialStiffnessFast = 1400f
    const val SpatialStiffnessDefault = 700f
    const val SpatialStiffnessSlow = 300f
    const val EffectsDamping = 1.0f
    const val EffectsStiffnessDefault = 1600f
    const val EffectsStiffnessFast = 3800f

    /** Skeleton shimmer sweep period (one full traverse). */
    const val DurationSkeleton = 1200

    /** Feedback-pulse periods (the "still working" indicators). LivePulse is
     *  the FULL cycle — the live dot animates the half (÷2) with Reverse. */
    const val DurationLivePulse = 1600
    const val DurationIndeterminate = 1100
    const val DurationScrapeSweep = 1500

    /** Shared list-item motion — the one Modifier.animateItem spec triple
     *  (was copy-pasted inline at every Home list; Downloads rode none).
     *  ItemPlacement is IntOffset-typed on purpose: animateItem's
     *  placementSpec parameter is FiniteAnimationSpec<IntOffset> and the
     *  generic is invariant, so a Float-typed spec would not compile. */
    val ItemFadeIn: FiniteAnimationSpec<Float> = spring(EffectsDamping, EffectsStiffnessDefault)
    val ItemPlacement: FiniteAnimationSpec<IntOffset> = spring(SpatialDamping, SpatialStiffnessDefault)
    val ItemFadeOut: FiniteAnimationSpec<Float> = tween(DurationFast)
}

/**
 * Type scale (UI research round, ui-polish-research.md §1.2).
 *
 * Ten roles, one meaning per size — the app previously used twelve distinct
 * sizes with no rule about which meant what, so "13sp" was simultaneously a
 * spec line, a progress readout and a settings row label. Sizes here are the
 * ones already on screen; only 9sp and 17sp were eliminated (both were
 * one-off strays).
 *
 * Call sites use the SIZE via `Type.body.fontSize` (a TextUnit, so it drops into
 * the existing `fontSize =` argument with no other change); the weight and
 * lineHeight on each role are the intended pairing and get adopted when a
 * site is rewritten to `style = Type.body`. Keeping the two steps separate is
 * what makes the bulk migration mechanical: `fontSize = N.sp` →
 * `Type.x.fontSize` cannot change layout, while `style =` can.
 */
object Type {
    /** Poster initial glyph only. Decorative, never body copy. */
    val displayPoster = TextStyle(
        fontSize = 34.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
    )

    /** The ANONRODE lockup. */
    val brand = TextStyle(
        fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Black, letterSpacing = 1.0.sp
    )

    /** Every page title (screen headers, drawer show title, sheet titles). */
    val screenTitle = TextStyle(
        fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Black, letterSpacing = 1.0.sp
    )

    /** Primary content rows and dialog titles. */
    val itemTitle = TextStyle(
        fontSize = 16.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold
    )

    /** In-page section headers ("Trending Now", "Browse by Genre"). */
    val sectionTitle = TextStyle(
        fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Bold
    )

    /** Card titles, list-row titles, catalog genre headers. */
    val rowTitle = TextStyle(
        fontSize = 14.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    )

    /** The default reading size: specs, metrics, settings rows, dialog bodies. */
    val body = TextStyle(
        fontSize = 13.sp, lineHeight = 17.sp
    )

    /** Chips, placeholders, subtitles, stat values, sheet copy. */
    val label = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp
    )

    /** Badges, settings subtitles, nav-bar labels, group headers. */
    val caption = TextStyle(
        fontSize = 11.sp, lineHeight = 15.sp,
        letterSpacing = 0.4.sp
    )

    /** Uppercase micro-labels only (site tag, SEE ALL, STORY, badges). */
    val micro = TextStyle(
        fontSize = 10.sp, lineHeight = 13.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp
    )
}
