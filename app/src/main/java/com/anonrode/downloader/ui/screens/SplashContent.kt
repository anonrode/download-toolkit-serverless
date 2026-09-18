package com.anonrode.downloader.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.DarkSplashColors
import com.anonrode.downloader.ui.theme.LightSplashColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The cold-start splash: the ANONRODE DOWNLOADER identity assembling itself
 * out of three falling chevrons (the user-approved rev-6 physics study,
 * see [SplashMotion]). Every frame asks the pure engine for a pose and paints
 * it — the mockup's rAF loop ported 1:1, so the motion IS the verified one.
 *
 * Dual-Theme Architecture:
 * - Bare Dark ("White No Tile A"): Pure #000000 surface, #FFFFFF monogram, #22D3EE subtitle.
 * - Minimal Light ("Light Surface A"): Porcelain #F4F7FA surface, #07131A deep ink monogram, #0E7490 subtitle.
 *
 * Zero-Jank Render Optimizations:
 * 1. Zero Recomposition for Text: Wordmark translations and opacity are driven by
 *    [graphicsLayer] on the RenderThread, avoiding continuous font layout/measurement.
 * 2. Zero Draw Allocations: [Path] instances are pre-allocated and reused with .reset()
 *    to eliminate Dalvik/ART GC pauses.
 * 3. Settle Beat & Silky Exit: After the assembly completes at cutMs, the completed
 *    mark is held for 150ms before smoothly fading out over 250ms via FastOutSlowInEasing,
 *    unveiling the already-composed HomeScreen underneath.
 *
 * cutMs: [SplashMotion.FULL_MS] on the very first install (the cinematic),
 * [SplashMotion.QUICK_MS] on warm starts (MainActivity decides).
 */
@Composable
fun SplashContent(
    cutMs: Float = SplashMotion.FULL_MS,
    isDark: Boolean = true,
    onSplashFinished: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = if (isDark) DarkSplashColors else LightSplashColors

    val reduced = remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (_: Throwable) { false }
    }

    val exitAlpha = remember { Animatable(1f) }
    var t by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(reduced, cutMs) {
        if (reduced) {
            t = cutMs
            delay(300L)
            onSplashFinished()
            return@LaunchedEffect
        }
        var last = 0L
        while (t < cutMs) {
            withFrameMillis { ms ->
                if (last != 0L) t += (ms - last).coerceAtMost(50L)
                last = ms
            }
        }
        t = cutMs

        // 150ms pristine settle beat: eye registers completed mark
        delay(150L)

        // 250ms hardware fade-out: smoothly reveals home screen
        exitAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
        )
        onSplashFinished()
    }

    val pose = remember(t) { SplashMotion.poseAt(t, cutMs) }

    // Pre-allocated reusable paths to guarantee 0 allocations per draw frame
    val chevAPath = remember { Path() }
    val chevRPath = remember { Path() }
    val chevLPath = remember { Path() }
    val bodyPath = remember { Path() }
    val stemPath = remember { Path() }
    val headPath = remember { Path() }

    // One scale factor drives mark + type together on every screen size.
    // 42% of width, capped at 216dp.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val markSize = (screenWidthDp * 0.42f).coerceAtMost(216f).roundToInt().dp
    val typeFactor = markSize.value / 216f
    val fontScale = LocalDensity.current.fontScale.takeIf { it > 0.1f } ?: 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha.value }
            .background(colors.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(modifier = Modifier.size(markSize)) {
                val s = size.minDimension / 120f
                withTransform({ scale(s, s, pivot = Offset.Zero) }) {
                    val glyphColor = colors.glyph

                    // folding chevron: lands on its vertex, arms swing up into the A
                    if (pose.chevAAlpha > 0.001f) {
                        chevAPath.reset()
                        chevAPath.moveTo(38f, pose.chevATipY)
                        chevAPath.lineTo(60f, pose.chevASpineY)
                        chevAPath.lineTo(82f, pose.chevATipY)

                        withTransform({
                            translate(0f, pose.chevATy)
                            translate(60f, 44f)
                            scale(pose.chevASx, pose.chevASy, pivot = Offset.Zero)
                            translate(-60f, -44f)
                        }) {
                            drawPath(
                                path = chevAPath,
                                color = glyphColor,
                                alpha = pose.chevAAlpha,
                                style = Stroke(
                                    width = pose.chevAStroke,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // the diver: punches through the floor, rings down, hands off
                    if (pose.chevRAlpha > 0.001f) {
                        chevRPath.reset()
                        chevRPath.moveTo(44f, 74f)
                        chevRPath.lineTo(60f, 86f)
                        chevRPath.lineTo(76f, 74f)

                        withTransform({ translate(0f, pose.chevRTy) }) {
                            drawPath(
                                path = chevRPath,
                                color = glyphColor,
                                alpha = pose.chevRAlpha,
                                style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }

                    // the A body (silhouette-matched cross-dissolve)
                    if (pose.bodyAlpha > 0.001f) {
                        bodyPath.reset()
                        bodyPath.moveTo(60f, 26f)
                        bodyPath.lineTo(84f, 78f)
                        bodyPath.lineTo(72f, 78f)
                        bodyPath.lineTo(60f, 48f)
                        bodyPath.lineTo(48f, 78f)
                        bodyPath.lineTo(36f, 78f)
                        bodyPath.close()

                        withTransform({
                            translate(60f, 52f)
                            scale(pose.bodyScale, pose.bodyScale, pivot = Offset.Zero)
                            translate(-60f, -52f)
                        }) {
                            drawPath(
                                path = bodyPath,
                                color = glyphColor,
                                alpha = pose.bodyAlpha
                            )
                        }
                    }

                    // flattening chevron -> landing line
                    if (pose.chevLAlpha > 0.001f) {
                        chevLPath.reset()
                        chevLPath.moveTo(42f - pose.chevLWide, pose.chevLTipY)
                        chevLPath.lineTo(60f, pose.chevLSpineY)
                        chevLPath.lineTo(78f + pose.chevLWide, pose.chevLTipY)

                        withTransform({
                            translate(0f, pose.chevLTy)
                            translate(60f, 86f)
                            scale(pose.chevLSx, pose.chevLSy, pivot = Offset.Zero)
                            translate(-60f, -86f)
                        }) {
                            drawPath(
                                path = chevLPath,
                                color = glyphColor,
                                alpha = pose.chevLAlpha,
                                style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }

                    // dynamic ripple off the landing line
                    if (pose.rippleAlpha > 0.001f) {
                        withTransform({
                            translate(60f, 86f)
                            scale(pose.rippleScale, pose.rippleScale, pivot = Offset.Zero)
                            translate(-60f, -86f)
                        }) {
                            drawOval(
                                color = glyphColor,
                                topLeft = Offset(44f, 81f),
                                size = Size(32f, 10f),
                                alpha = pose.rippleAlpha * 0.7f,
                                style = Stroke(width = 2.5f)
                            )
                        }
                    }

                    // stem draws in behind the arrowhead handoff
                    if (pose.stemProgress > 0.001f) {
                        stemPath.reset()
                        stemPath.moveTo(60f, 82f)
                        stemPath.lineTo(60f, 94f)

                        drawPath(
                            path = stemPath,
                            color = glyphColor,
                            style = Stroke(
                                width = 7f,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(16f, 16f), phase = 16f * (1f - pose.stemProgress)
                                )
                            )
                        )
                    }

                    // arrowhead
                    if (pose.headAlpha > 0.001f) {
                        headPath.reset()
                        headPath.moveTo(51f, 87f)
                        headPath.lineTo(60f, 96f)
                        headPath.lineTo(69f, 87f)

                        withTransform({
                            translate(60f, 92f)
                            scale(pose.headScale, pose.headScale, pivot = Offset.Zero)
                            translate(-60f, -92f)
                        }) {
                            drawPath(
                                path = headPath,
                                color = glyphColor,
                                alpha = pose.headAlpha,
                                style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }
            }

            // Wordmark with zero-recomposition graphicsLayer animations
            Text(
                text = "ANONRODE",
                color = colors.title,
                fontSize = (30f * typeFactor / fontScale).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (3f * typeFactor / fontScale).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(top = (14f * typeFactor).dp)
                    .graphicsLayer {
                        alpha = pose.name1
                        translationY = (1f - pose.name1) * 10f * typeFactor * density
                    }
            )
            Text(
                text = "DOWNLOADER",
                color = colors.subtitle,
                fontSize = (11f * typeFactor / fontScale).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (6f * typeFactor / fontScale).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(top = (7f * typeFactor).dp)
                    .graphicsLayer {
                        alpha = pose.name2
                        translationY = (1f - pose.name2) * 8f * typeFactor * density
                    }
            )
        }

        // Deterministic, 60fps/120fps frame-synced progress line:
        // fills smoothly from 0% to 100% in exact lockstep with (t / cutMs),
        // matching the hardware refresh rate of the identity physics canvas.
        // Bounded clearance: 24dp above navigationBarsPadding() so it cleanly
        // clears the Android gesture navigation pill on all device formats.
        val progress = if (cutMs > 0f) (t / cutMs).coerceIn(0f, 1f) else 1f
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .width(120.dp)
                .height(3.dp)
        ) {
            val h = size.height
            val w = size.width
            val r = h / 2f
            drawRoundRect(
                color = colors.progressTrack,
                size = size,
                cornerRadius = CornerRadius(r, r)
            )
            if (progress > 0.001f) {
                drawRoundRect(
                    color = colors.progressFill,
                    size = Size(w * progress, h),
                    cornerRadius = CornerRadius(r, r)
                )
            }
        }
    }
}
