package com.anonrode.downloader.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.anonrode.downloader.ui.theme.BorderHairline
import com.anonrode.downloader.ui.theme.Motion
import com.anonrode.downloader.ui.theme.Radius
import com.anonrode.downloader.ui.theme.Spacing
import com.anonrode.downloader.ui.theme.SurfaceCard
import com.anonrode.downloader.ui.theme.SurfaceElevated

/**
 * Skeleton placeholders (UI research round — the app had zero, so slow
 * sources read as "broken spinner"). Every skeleton occupies the SAME box as
 * the real content it stands in for, so nothing shifts when data lands
 * (the TRENDING_ROW_H rule, applied per surface).
 *
 * All APIs here were verified against compose 1.7.0 / material3 1.3.0
 * (BOM 2024.09.00) before use — this project's CI is the first compiler.
 * `Modifier.shimmer`/`Modifier.placeholder` do NOT exist in these versions;
 * the sweep is hand-rolled and painted inside drawBehind so the animated
 * value is read in the DRAW phase — no per-frame recomposition of list items.
 */

/** True when the user disabled system animations ("Animator duration scale"
 *  off): the same Settings.Global value Compose's own recomposer reads.
 *  Skeletons then render as flat rectangles instead of sweeping. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Throwable) {
            false
        }
    }
}

/** One shimmer transition per SCREEN (not per item). Returns the 0..1 sweep
 *  position; frozen at 0 when reduced motion is on. */
@Composable
fun rememberShimmerX(reduceMotion: Boolean): Float {
    if (reduceMotion) return 0f
    val t = rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.DurationSkeleton, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonSweep"
    )
    return x
}

/** A skeleton rectangle with the sweep highlight. Purely decorative:
 *  cleared from the semantics tree so TalkBack never announces it. */
@Composable
fun SkeletonBox(
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(Radius.sm),
    x: Float,
    base: Color = SurfaceElevated,
    highlight: Color = SurfaceCard
) {
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                val w = size.width
                if (w <= 0f) return@drawBehind
                val sweep = w * 0.55f
                val start = (x * (w + sweep)) - sweep
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(start, 0f),
                        end = Offset(start + sweep, 0f),
                        tileMode = TileMode.Clamp
                    )
                )
            }
            .clearAndSetSemantics { }
    )
}

/** Ready-made trending-row placeholder (3 cards, same paddings/gaps as the
 *  real LazyRow) — drop-in replacement for the loading spinner. */
@Composable
fun TrendingRowSkeleton() {
    val x = rememberShimmerX(rememberReduceMotion())
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = PaddingValues(end = Spacing.lg),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(SKELETON_ROW_IDS) { TrendingCardSkeleton(x) }
    }
}

private val SKELETON_ROW_IDS = listOf(1, 2, 3)

/** Ready-made search-results placeholder (3 rows) — replaces the centered
 *  spinner so the list shape is visible while sources crawl. */
@Composable
fun SearchListSkeleton() {
    val x = rememberShimmerX(rememberReduceMotion())
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        repeat(3) { SearchRowSkeleton(x) }
    }
}

/** Trending-card skeleton: the real card's exact geometry (124dp wide poster
 *  at 2:3, two title lines, one site line) so the row never changes height. */
@Composable
fun TrendingCardSkeleton(x: Float) {
    // SurfaceElevated/SurfaceCard are @Composable getters, so they can only be
    // read in a composable body — not inside the drawBehind lambda below.
    val base = SurfaceElevated
    val highlight = SurfaceCard
    Column(modifier = Modifier.width(124.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .background(base)
                .drawBehind {
                    val w = size.width
                    if (w > 0f) {
                        val sweep = w * 0.55f
                        val start = (x * (w + sweep)) - sweep
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(base, highlight, base),
                                start = Offset(start, 0f),
                                end = Offset(start + sweep, 0f),
                                tileMode = TileMode.Clamp
                            )
                        )
                    }
                }
                .border(
                    1.dp, BorderHairline, RoundedCornerShape(Radius.md)
                )
                .clearAndSetSemantics { }
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        SkeletonBox(modifier = Modifier.width(96.dp).height(10.dp), x = x)
        Spacer(modifier = Modifier.height(3.dp))
        SkeletonBox(modifier = Modifier.width(72.dp).height(10.dp), x = x)
        Spacer(modifier = Modifier.height(Spacing.xs))
        SkeletonBox(modifier = Modifier.width(40.dp).height(8.dp), x = x)
    }
}

/** Search-result row skeleton: mirrors ShowCardItem's 74×106 poster + text
 *  column, so the list height is identical before and after data lands. */
@Composable
fun SearchRowSkeleton(x: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceCard)
            .border(
                1.dp, BorderHairline, RoundedCornerShape(Radius.lg)
            )
            .padding(Spacing.md)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SkeletonBox(
            modifier = Modifier.size(width = 74.dp, height = 106.dp),
            shape = RoundedCornerShape(Radius.md),
            x = x
        )
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp), x = x)
            Spacer(modifier = Modifier.height(Spacing.sm))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.55f).height(11.dp), x = x)
            Spacer(modifier = Modifier.height(Spacing.lg))
            SkeletonBox(modifier = Modifier.width(64.dp).height(18.dp), x = x)
        }
    }
}

/** Episode-row skeleton: the drawer's own 44dp row rhythm (20dp square + one
 *  bar), so the list box keeps its height when the scrape lands. */
@Composable
fun EpisodeRowSkeleton(x: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SkeletonBox(
            modifier = Modifier.size(20.dp),
            shape = RoundedCornerShape(Radius.xs),
            x = x
        )
        SkeletonBox(modifier = Modifier.fillMaxWidth(0.62f).height(11.dp), x = x)
    }
}

/** Ready-made episode/playlist placeholder: the drawer and the playlist sheet
 *  both read as "a list is coming" rather than a lone spinner. */
@Composable
fun EpisodeListSkeleton(rows: Int = 6) {
    val x = rememberShimmerX(rememberReduceMotion())
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rows) { EpisodeRowSkeleton(x) }
    }
}
