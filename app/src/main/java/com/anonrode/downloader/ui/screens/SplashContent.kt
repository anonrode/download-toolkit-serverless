package com.anonrode.downloader.ui.screens

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.ui.theme.SplashBackground
import com.anonrode.downloader.ui.theme.SplashElevated
import com.anonrode.downloader.ui.theme.SplashOnBackground
import kotlin.math.roundToInt

/**
 * The cold-start splash: the ANONRODE DOWNLOADER identity assembling itself
 * out of three falling chevrons (the user-approved rev-6 physics study,
 * see [SplashMotion]). Every frame asks the pure engine for a pose and paints
 * it — the mockup's rAF loop ported 1:1, so the motion IS the verified one.
 *
 * cutMs: [SplashMotion.FULL_MS] on the very first install (the cinematic),
 * [SplashMotion.QUICK_MS] on warm starts (MainActivity decides). Devices with
 * the system animation scale at 0 render the final pose statically.
 *
 * Sizing is screen-relative on purpose — phone widths vary a lot. v3.1.5
 * shipped two sizing mistakes the device caught ("the logo is literally
 * going to the edge of the splash screen"): (1) a 150.dp FLOOR on the mark
 * size — 42% of width only ever SHRINKS below 150dp, and the floor then
 * INFLATES the lockup to ~47% of a small phone's width; (2) the wordmark
 * used SP, which multiplies by the user's system font-scale setting, so a
 * large-font device scaled the 30sp ANONRODE well past the mockup's design
 * while the dp-based mark stayed fixed — the lockup sprawled edge to edge.
 * Now: the mark is simply 42% of width capped at 216dp (proportional, never
 * inflated, never tiny on a big phone), and the type divides its sp by
 * fontScale so the cinematic lockup renders identically on every device
 * regardless of the reading-size preference. The "100% SERVERLESS" tagline
 * was removed by user decision.
 */
@Composable
fun SplashContent(cutMs: Float = SplashMotion.FULL_MS) {
    val context = LocalContext.current
    val reduced = remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (_: Throwable) { false }
    }

    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced, cutMs) {
        if (reduced) { t = cutMs; return@LaunchedEffect }
        var last = 0L
        while (t < cutMs) {
            withFrameMillis { ms ->
                if (last != 0L) t += (ms - last).coerceAtMost(50L)
                last = ms
            }
        }
        t = cutMs
    }
    val pose = remember(t) { SplashMotion.poseAt(t, cutMs) }

    // One scale factor drives mark + type together on every screen size.
    // 42% of width, capped at the mockup's design size — NO floor: a floor
    // can only ever inflate the logo on small screens (v3.1.5 bug).
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val markSize = (screenWidthDp * 0.42f).coerceAtMost(216f).roundToInt().dp
    val typeFactor = markSize.value / 216f
    // The splash lockup is brand art, not body copy: it must NOT scale with
    // the user's accessibility font size (that's what pushed v3.1.5's wordmark
    // edge-to-edge on large-font devices). Dividing the sp by fontScale pins
    // the on-screen size to the design; real text elsewhere still respects it.
    val fontScale = LocalDensity.current.fontScale.takeIf { it > 0.1f } ?: 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(modifier = Modifier.size(markSize)) {
                val s = size.minDimension / 120f
                withTransform({ scale(s, s, pivot = Offset.Zero) }) {
                    // one cyan breath behind the tile (the punch glow)
                    if (pose.glow > 0.001f) {
                        drawRoundRect(
                            color = Color(0xFF22D3EE),
                            topLeft = Offset(-6f, -6f),
                            size = Size(132f, 132f),
                            cornerRadius = CornerRadius(30f, 30f),
                            alpha = pose.glow * 0.30f
                        )
                    }
                    // squircle tile — the approved "squircle · ink" surface
                    if (pose.tileAlpha > 0.001f) {
                        withTransform({
                            translate(60f, 60f)
                            scale(pose.tileScale, pose.tileScale, pivot = Offset.Zero)
                            translate(-60f, -60f)
                        }) {
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF22D3EE), Color(0xFF0E7490)),
                                    start = Offset(6f, 6f), end = Offset(114f, 114f)
                                ),
                                topLeft = Offset(6f, 6f),
                                size = Size(108f, 108f),
                                cornerRadius = CornerRadius(24f, 24f),
                                alpha = pose.tileAlpha
                            )
                        }
                    }
                    val ink = Color(0xFF04121A)

                    // folding chevron: lands on its vertex, arms swing up into the A
                    if (pose.chevAAlpha > 0.001f) {
                        withTransform({
                            translate(0f, pose.chevATy)
                            translate(60f, 44f)
                            scale(pose.chevASx, pose.chevASy, pivot = Offset.Zero)
                            translate(-60f, -44f)
                        }) {
                            drawPath(
                                Path().apply {
                                    moveTo(38f, pose.chevATipY)
                                    lineTo(60f, pose.chevASpineY)
                                    lineTo(82f, pose.chevATipY)
                                },
                                color = ink,
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
                        withTransform({ translate(0f, pose.chevRTy) }) {
                            drawPath(
                                Path().apply {
                                    moveTo(44f, 74f); lineTo(60f, 86f); lineTo(76f, 74f)
                                },
                                color = ink,
                                alpha = pose.chevRAlpha,
                                style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                    // the A body (silhouette-matched cross-dissolve)
                    if (pose.bodyAlpha > 0.001f) {
                        withTransform({
                            translate(60f, 52f)
                            scale(pose.bodyScale, pose.bodyScale, pivot = Offset.Zero)
                            translate(-60f, -52f)
                        }) {
                            drawPath(
                                Path().apply {
                                    moveTo(60f, 26f); lineTo(84f, 78f); lineTo(72f, 78f)
                                    lineTo(60f, 48f); lineTo(48f, 78f); lineTo(36f, 78f); close()
                                },
                                color = ink,
                                alpha = pose.bodyAlpha
                            )
                        }
                    }
                    // flattening chevron -> landing line
                    if (pose.chevLAlpha > 0.001f) {
                        withTransform({
                            translate(0f, pose.chevLTy)
                            translate(60f, 86f)
                            scale(pose.chevLSx, pose.chevLSy, pivot = Offset.Zero)
                            translate(-60f, -86f)
                        }) {
                            drawPath(
                                Path().apply {
                                    moveTo(42f - pose.chevLWide, pose.chevLTipY)
                                    lineTo(60f, pose.chevLSpineY)
                                    lineTo(78f + pose.chevLWide, pose.chevLTipY)
                                },
                                color = ink,
                                alpha = pose.chevLAlpha,
                                style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                    // one white ripple off the landing line
                    if (pose.rippleAlpha > 0.001f) {
                        withTransform({
                            translate(60f, 86f)
                            scale(pose.rippleScale, pose.rippleScale, pivot = Offset.Zero)
                            translate(-60f, -86f)
                        }) {
                            drawOval(
                                color = Color.White,
                                topLeft = Offset(44f, 81f),
                                size = Size(32f, 10f),
                                alpha = pose.rippleAlpha,
                                style = Stroke(width = 2.5f)
                            )
                        }
                    }
                    // stem draws in behind the arrowhead handoff
                    if (pose.stemProgress > 0.001f) {
                        drawPath(
                            Path().apply { moveTo(60f, 82f); lineTo(60f, 94f) },
                            color = ink,
                            style = Stroke(
                                width = 7f,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(16f, 16f), phase = 16f * (1f - pose.stemProgress)
                                )
                            )
                        )
                    }
                    if (pose.headAlpha > 0.001f) {
                        withTransform({
                            translate(60f, 92f)
                            scale(pose.headScale, pose.headScale, pivot = Offset.Zero)
                            translate(-60f, -92f)
                        }) {
                            drawPath(
                                Path().apply { moveTo(51f, 87f); lineTo(60f, 96f); lineTo(69f, 87f) },
                                color = ink,
                                alpha = pose.headAlpha,
                                style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }
            }

            Text(
                text = "ANONRODE",
                color = SplashOnBackground.copy(alpha = pose.name1),
                fontSize = (30f * typeFactor / fontScale).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (3f * typeFactor / fontScale).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .offset(y = ((1f - pose.name1) * 10f * typeFactor).dp)
                    .padding(top = (14f * typeFactor).dp)
            )
            Text(
                text = "DOWNLOADER",
                color = Color(0xFF22D3EE).copy(alpha = pose.name2),
                fontSize = (11f * typeFactor / fontScale).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (6f * typeFactor / fontScale).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .offset(y = ((1f - pose.name2) * 8f * typeFactor).dp)
                    .padding(top = (7f * typeFactor).dp)
            )
        }

        LinearProgressIndicator(
            color = SplashOnBackground,
            trackColor = SplashElevated,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // system nav clearance — the fixed 48dp lift only
                // *touches* a classic 3-button bar; on gesture-nav devices
                // the pill overlapped the indicator
                .navigationBarsPadding()
                .padding(bottom = 48.dp)
                .height(3.dp)
                .width(120.dp)
        )
    }
}
