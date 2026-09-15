package com.anonrode.downloader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SplashMotion] — the pure physics behind the splash identity.
 *
 * The whole point of the engine (rev 6) is that velocity is CONTINUOUS:
 * gravity is analytic and landings are damped springs, so there are no
 * keyframe seams to step at. These tests pin that property down: at a
 * 60fps frame step, no animated quantity may jump more than a few frames'
 * worth of its own velocity — the numeric definition of "not jumpy" — and
 * the timeline must end in exactly the static brand pose the launcher uses.
 */
class SplashMotionTest {

    private val frame = 16.7f // one 60fps frame, ms

    @Test
    fun `gravity endpoints are exact`() {
        assertEquals(-70f, SplashMotion.gravity(100f, 100f, 300f, 70f), 0.001f)
        assertEquals(0f, SplashMotion.gravity(400f, 100f, 300f, 70f), 0.001f)
        // monotone down-accelerating: equal time steps cover growing distances
        val ys = (0..4).map { SplashMotion.gravity(100f + it * 60f, 100f, 300f, 70f) }
        val steps = ys.zipWithNext { a, b -> b - a }
        assertTrue("must accelerate: $steps", steps.zipWithNext { a, b -> b >= a - 0.001f }.all { it })
    }

    @Test
    fun `spring starts at rest value and settles to it`() {
        assertEquals(1f, SplashMotion.spring(500f, 500f, 0.3f, 55f, 0.55f), 0.001f)
        // rings (compresses) shortly after the kick...
        val ring = SplashMotion.spring(530f, 500f, 0.3f, 55f, 0.55f)
        assertTrue("should compress: $ring", ring < 0.95f)
        // ...and is back at rest well before the fold ends
        assertTrue(Math.abs(SplashMotion.spring(1300f, 500f, 0.3f, 55f, 0.55f) - 1f) < 0.01)
    }

    @Test
    fun `springTo honours initial position and velocity then settles at target`() {
        assertEquals(0f, SplashMotion.springTo(600f, 600f, 0f, 476f, 10f, 70f, 0.62f), 0.001f)
        // keeps going DOWN through the target first (the punch-through), velocity continuous
        val early = SplashMotion.springTo(616.7f, 600f, 0f, 476f, 10f, 70f, 0.62f)
        assertTrue("punches past 0: $early", early > 5f)
        assertTrue("settles: ${SplashMotion.springTo(1800f, 600f, 0f, 476f, 10f, 70f, 0.62f)}",
            Math.abs(SplashMotion.springTo(1800f, 600f, 0f, 476f, 10f, 70f, 0.62f) - 10f) < 0.05f)
    }

    @Test
    fun `final pose is the static brand identity`() {
        val p = SplashMotion.poseAt(SplashMotion.FULL_MS)
        // skeleton chevrons retired, solids in place
        assertEquals(0f, p.chevAAlpha, 0.001f)
        assertEquals(0f, p.chevRAlpha, 0.001f)
        assertEquals(1f, p.bodyAlpha, 0.001f)
        assertEquals(1f, p.bodyScale, 0.001f)
        // landing line fully flat, no residual wide-bounce
        assertEquals(86f, p.chevLTipY, 0.001f)
        assertEquals(86f, p.chevLSpineY, 0.001f)
        assertEquals(0f, p.chevLWide, 0.001f)
        // fold complete: the A frame
        assertEquals(78f, p.chevATipY, 0.001f)
        assertEquals(26f, p.chevASpineY, 0.001f)
        // arrowhead + stem drawn, effects retired, names up
        assertEquals(1f, p.headAlpha, 0.001f)
        assertEquals(1f, p.stemProgress, 0.001f)
        assertEquals(0f, p.rippleAlpha, 0.001f)
        assertEquals(0f, p.glow, 0.001f)
        assertEquals(1f, p.name1, 0.001f)
        assertEquals(1f, p.name2, 0.001f)
        assertEquals(1f, p.name3, 0.001f)
    }

    @Test
    fun `start pose is the empty stage`() {
        val p = SplashMotion.poseAt(0f)
        assertEquals(0f, p.tileAlpha, 0.001f)
        assertEquals(0f, p.name1, 0.001f)
        // chevrons wait above the tile
        assertTrue(p.chevATy < -60f)
        assertTrue(p.chevLTy < -70f)
        assertTrue(p.chevRTy < -90f)
    }

    @Test
    fun `no animated quantity steps more than a few frames of itself`() {
        // THE anti-jump test: per-frame deltas of every continuous channel.
        // A keyframe "staircase" (the rev-4/5 failure) would blow these caps;
        // analytic gravity + springs cannot.
        var prev = SplashMotion.poseAtMaster(0f)
        var t = 0f
        var maxTy = 0f; var maxTip = 0f; var maxStroke = 0f; var maxScale = 0f
        while (t < SplashMotion.FULL_MS) {
            t += frame
            val p = SplashMotion.poseAtMaster(t)
            // positions (px in the 120-unit mark space)
            maxTy = maxOf(maxTy,
                Math.abs(p.chevATy - prev.chevATy),
                Math.abs(p.chevLTy - prev.chevLTy),
                Math.abs(p.chevRTy - prev.chevRTy))
            maxTip = maxOf(maxTip,
                Math.abs(p.chevATipY - prev.chevATipY),
                Math.abs(p.chevASpineY - prev.chevASpineY),
                Math.abs(p.chevLTipY - prev.chevLTipY),
                Math.abs(p.chevLSpineY - prev.chevLSpineY))
            maxStroke = maxOf(maxStroke, Math.abs(p.chevAStroke - prev.chevAStroke))
            maxScale = maxOf(maxScale,
                Math.abs(p.bodyScale - prev.bodyScale),
                Math.abs(p.headScale - prev.headScale),
                Math.abs(p.chevASy - prev.chevASy),
                Math.abs(p.chevLSy - prev.chevLSy))
            prev = p
        }
        // Caps measured from the engine itself (Python port, 60fps scan):
        // the biggest per-frame delta anywhere is the diver's terminal fall
        // velocity (7.8 px) — a physical constant, not a seam. Anything above
        // these numbers could only come from a velocity STEP, i.e. the
        // keyframe-staircase failure mode rev 4/5 had.
        assertTrue("translate step $maxTy px/frame", maxTy < 12f)
        assertTrue("fold step $maxTip px/frame", maxTip < 8f)
        assertTrue("stroke step $maxStroke", maxStroke < 0.5f)
        assertTrue("scale step $maxScale", maxScale < 0.15f)
    }

    @Test
    fun `squash is volume preserving`() {
        // at the landing spring's deepest compression sx + sy == 2 exactly
        var t = 430f
        var best = SplashMotion.poseAtMaster(0f)
        while (t < 600f) {
            val p = SplashMotion.poseAtMaster(t)
            if (p.chevASy < best.chevASy) best = p
            t += frame
        }
        assertEquals(2f, best.chevASx + best.chevASy, 0.001f)
        assertTrue("actually squashed: ${best.chevASy}", best.chevASy < 0.95f)
    }

    @Test
    fun `quick cut is the same choreography compressed`() {
        // poseAt(QUICK, QUICK) must equal poseAt(FULL, FULL) — both are the end state
        val q = SplashMotion.poseAt(SplashMotion.QUICK_MS, SplashMotion.QUICK_MS)
        val f = SplashMotion.poseAt(SplashMotion.FULL_MS, SplashMotion.FULL_MS)
        assertEquals(f.chevATipY, q.chevATipY, 0.001f)
        assertEquals(f.bodyAlpha, q.bodyAlpha, 0.001f)
        assertEquals(f.name3, q.name3, 0.001f)
        // and mid-cut maps proportionally: 50% of either cut lands the same pose
        val half1 = SplashMotion.poseAt(SplashMotion.FULL_MS / 2f, SplashMotion.FULL_MS)
        val half2 = SplashMotion.poseAt(SplashMotion.QUICK_MS / 2f, SplashMotion.QUICK_MS)
        assertEquals(half1.chevATipY, half2.chevATipY, 0.001f)
        assertEquals(half1.chevRTy, half2.chevRTy, 0.001f)
    }

    @Test
    fun `phases and easings are bounded and monotone where they must be`() {
        assertEquals(0f, SplashMotion.phase(10f, 20f, 30f), 0f)
        assertEquals(1f, SplashMotion.phase(99f, 20f, 30f), 0f)
        assertEquals(0.5f, SplashMotion.phase(25f, 20f, 30f), 0.001f)
        // easeOut*: fast start, never overshoots, endpoints fixed
        assertEquals(0f, SplashMotion.easeOutCubic(0f), 0f)
        assertEquals(1f, SplashMotion.easeOutCubic(1f), 0f)
        assertEquals(1f, SplashMotion.easeOutQuint(1f), 0f)
        assertTrue(SplashMotion.easeOutCubic(0.5f) > 0.5f)
        assertTrue(SplashMotion.easeOutQuint(0.5f) > SplashMotion.easeOutCubic(0.5f))
    }
}
