package com.anonrode.downloader.ui.theme

import androidx.compose.ui.unit.dp

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
}
