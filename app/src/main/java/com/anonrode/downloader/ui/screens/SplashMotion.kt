package com.anonrode.downloader.ui.screens

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The splash identity animation as PURE physics — no keyframe ladders.
 *
 * Ported 1:1 from the user-approved rev-6 mockup
 * (ANON TOOLS/designs/2026-09-14-round/splash-motion.html), whose seams were
 * numerically verified there: CSS percentage keyframes interpolate LINEARLY
 * between declared frames, so velocity steps at every seam no matter how many
 * frames are added — that was the "jumpy fold" the user kept rejecting.
 * Solving the motion per frame instead makes velocity continuous BY
 * CONSTRUCTION: gravity falls are analytic ½gt², every landing is a damped
 * harmonic spring (C¹ in value AND velocity), and the diver's punch spring is
 * fed the fall's exact impact velocity (seam checked 466→476 px/s).
 *
 * The story the pose tells: three chevrons (the "stream") fall; the last one
 * folds open into the A (arms up to the apex, spine to the landing line),
 * the middle flattens into the line, and the top carries its momentum through
 * the floor and hands off to the mark's arrowhead. The brand assembles out of
 * a download.
 *
 * This object has no Android imports on purpose: [poseAt] is a pure function
 * of time, unit-testable on the JVM (SplashMotionTest) and drawn per frame by
 * SplashContent's Canvas at the device's own refresh rate.
 */
object SplashMotion {

    /** Master durations of the two cuts (ms). */
    const val FULL_MS = 1700f
    const val QUICK_MS = 950f

    fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    /** Normalised progress of [t] inside window a..b. */
    fun phase(t: Float, a: Float, b: Float): Float = clamp01((t - a) / (b - a))

    fun easeOutCubic(p: Float): Float { val q = 1f - p; return 1f - q * q * q }

    fun easeOutQuint(p: Float): Float { val q = 1f - p; return 1f - q * q * q * q * q }

    /** Free fall from rest, distance h over dur ms: y goes -h -> 0, ½gt². */
    fun gravity(t: Float, t0: Float, dur: Float, h: Float): Float {
        val s = phase(t, t0, t0 + dur)
        return -h + h * s * s
    }

    /**
     * Damped harmonic spring starting AT rest value 1, kicked by a compression:
     * x(t) = 1 − A·e^(−ζωt)·sin(ω_d t). Continuous in value and velocity —
     * the landing can never "step". ζ<1 keeps it ringing briefly (rubber, not jelly).
     */
    fun spring(t: Float, t0: Float, amp: Float, omega: Float, zeta: Float): Float {
        val dt = (t - t0) / 1000f
        if (dt <= 0f) return 1f
        val wd = omega * sqrt(1f - zeta * zeta)
        return 1f - amp * exp(-zeta * omega * dt) * sin(wd * dt)
    }

    /**
     * Spring toward [target] from position [x0] with initial velocity [v0]
     * (px/s). Used for the diver: it enters at the floor with the velocity the
     * gravity fall already gave it, punches past, and rings down to rest.
     */
    fun springTo(
        t: Float, t0: Float, x0: Float, v0: Float,
        target: Float, omega: Float, zeta: Float
    ): Float {
        val dt = (t - t0) / 1000f
        if (dt <= 0f) return x0
        val wd = omega * sqrt(1f - zeta * zeta)
        val c1 = x0 - target
        val c2 = (v0 + zeta * omega * c1) / wd
        val e = exp(-zeta * omega * dt)
        return target + e * (c1 * cos(wd * dt) + c2 * sin(wd * dt))
    }

    private fun cos(x: Float): Float = kotlin.math.cos(x.toDouble()).toFloat()

    /** Timeline beats (ms, FULL master). */
    private const val TILE_END = 250f
    private const val A_START = 60f
    private const val A_IMPACT = 430f
    private const val A_FOLD_END = 760f
    private const val A_FADE_END = 850f
    private const val SOLID_START = 650f
    private const val SOLID_END = 830f
    private const val BODY_IN_START = 720f
    private const val BODY_IN_END = 760f
    private const val L_START = 150f
    private const val L_IMPACT = 500f
    private const val L_FLAT_END = 800f
    private const val L_WIDE_END = 920f
    private const val R_START = 180f
    private const val R_IMPACT = 600f
    private const val R_FADE_START = 820f
    private const val R_FADE_END = 940f
    private const val HEAD_START = 780f
    private const val HEAD_END = 930f
    private const val STEM_START = 740f
    private const val STEM_END = 900f
    private const val RIPPLE_START = 800f
    private const val RIPPLE_END = 1120f
    private const val GLOW_START = 790f
    private const val GLOW_END = 1150f
    private const val N1_START = 950f
    private const val N2_START = 1080f
    private const val N3_START = 1200f
    private const val NAME_DUR = 320f

    /** Every animated value of the mark + wordmark at one instant. */
    data class Pose(
        val tileAlpha: Float, val tileScale: Float,
        val chevATipY: Float, val chevASpineY: Float, val chevAStroke: Float,
        val chevAAlpha: Float, val chevATy: Float, val chevASx: Float, val chevASy: Float,
        val bodyAlpha: Float, val bodyScale: Float,
        val chevLTipY: Float, val chevLSpineY: Float, val chevLWide: Float,
        val chevLAlpha: Float, val chevLTy: Float, val chevLSx: Float, val chevLSy: Float,
        val chevRTy: Float, val chevRAlpha: Float,
        val headAlpha: Float, val headScale: Float,
        val stemProgress: Float,
        val rippleAlpha: Float, val rippleScale: Float,
        val glow: Float,
        val name1: Float, val name2: Float, val name3: Float,
    )

    /**
     * Pose at [tMs] of a cut whose total length is [cutMs] (the whole timeline
     * compresses proportionally: the QUICK cut is the same physics faster, not
     * a different choreography).
     */
    fun poseAt(tMs: Float, cutMs: Float = FULL_MS): Pose = poseAtMaster(tMs * FULL_MS / cutMs)

    /** Pose on the FULL 1700 ms master timeline. */
    fun poseAtMaster(t: Float): Pose {
        // tile pop
        val tp = easeOutCubic(phase(t, 0f, TILE_END))

        // chevA: fall -> spring squash + fold as ONE gesture
        val aFall = gravity(t, A_START, A_IMPACT - A_START, 70f)
        val ay = if (t >= A_IMPACT) 0f else aFall
        val aSquash = spring(t, A_IMPACT, 0.30f, 55f, 0.55f)
        val aRamp = clamp01((t - A_IMPACT) / 40f)
        val aSy = 1f + (aSquash - 1f) * aRamp
        val aSx = 2f - aSy
        val afp = easeOutCubic(phase(t, A_IMPACT, A_FOLD_END))
        val aTipY = 30f + 48f * afp
        val aSpineY = 44f - 18f * afp
        val aStroke = 9f + 3f * afp
        val aAlpha = 1f - phase(t, A_FOLD_END, A_FADE_END)

        // bodyA solidifies as a silhouette-matched cross-dissolve
        val so = easeOutCubic(phase(t, SOLID_START, SOLID_END))
        val bodyAlpha = so * phase(t, BODY_IN_START, BODY_IN_END)
        val bodyScale = 0.94f + 0.06f * so

        // chevL: fall -> squash, flatten into the landing line
        val lFall = gravity(t, L_START, L_IMPACT - L_START, 80f)
        val ly = if (t >= L_IMPACT) 0f else lFall
        val lSquash = spring(t, L_IMPACT, 0.26f, 58f, 0.50f)
        val lRamp = clamp01((t - L_IMPACT) / 40f)
        val lSy = 1f + (lSquash - 1f) * lRamp
        val lSx = 2f - lSy
        val lfp = easeOutCubic(phase(t, L_IMPACT, L_FLAT_END))
        val lTipY = 64f + 22f * lfp
        val lSpineY = 76f + 10f * lfp
        val lWide = 2f * sin(Math.PI.toFloat() * phase(t, L_FLAT_END, L_WIDE_END))
        val lAlpha = if (t < 8f) 0f else 1f

        // chevR diver: fall, punch THROUGH the floor with its real impact
        // velocity, ring down onto the arrowhead point, hand off.
        val rDur = (R_IMPACT - R_START) / 1000f
        val rFall = gravity(t, R_START, R_IMPACT - R_START, 100f)
        val ry = if (t < R_IMPACT) rFall
        else springTo(t, R_IMPACT, 0f, 2f * 100f / rDur, 10f, 70f, 0.62f)
        val rAlpha = 1f - easeOutCubic(phase(t, R_FADE_START, R_FADE_END))

        // handoff: arrowhead + stem draw
        val ho = easeOutQuint(phase(t, HEAD_START, HEAD_END))
        val stemProgress = easeOutQuint(phase(t, STEM_START, STEM_END))

        // one ripple, one cyan breath
        val rp = phase(t, RIPPLE_START, RIPPLE_END)
        val rippleAlpha = 0.55f * sin(Math.PI.toFloat() * rp)
        val rippleScale = 0.1f + 1.15f * easeOutCubic(rp)
        val glow = sin(Math.PI.toFloat() * phase(t, GLOW_START, GLOW_END)) * 0.85f

        // wordmark tiers, staggered easeOutQuint rises
        val n1 = easeOutQuint(phase(t, N1_START, N1_START + NAME_DUR))
        val n2 = easeOutQuint(phase(t, N2_START, N2_START + NAME_DUR))
        val n3 = easeOutQuint(phase(t, N3_START, N3_START + NAME_DUR))

        return Pose(
            tileAlpha = tp, tileScale = 0.94f + 0.06f * tp,
            chevATipY = aTipY, chevASpineY = aSpineY, chevAStroke = aStroke,
            chevAAlpha = aAlpha, chevATy = ay, chevASx = aSx, chevASy = aSy,
            bodyAlpha = bodyAlpha, bodyScale = bodyScale,
            chevLTipY = lTipY, chevLSpineY = lSpineY, chevLWide = lWide,
            chevLAlpha = lAlpha, chevLTy = ly, chevLSx = lSx, chevLSy = lSy,
            chevRTy = ry, chevRAlpha = rAlpha,
            headAlpha = ho, headScale = 0.7f + 0.3f * ho,
            stemProgress = stemProgress,
            rippleAlpha = rippleAlpha, rippleScale = rippleScale,
            glow = glow,
            name1 = n1, name2 = n2, name3 = n3,
        )
    }
}
