package com.anonrode.downloader.engine

import kotlin.math.pow

/**
 * HLS Monotonic Stream Reconciliation.
 * Blends preflight size estimate with runtime segment observations using
 * dynamic weight w(i) = (i / N)^1.5 and enforces a tail reserve floor
 * so progress never leaps backward, skips to 50%+ on startup, or jumps to 100% prematurely.
 */
class HlsMonotonicProgressReconciler(
    private var estimatedTotal: Long,
    val segmentCount: Int
) {
    private var preflightTotal: Long = estimatedTotal
    private var lastReportedBytes: Long = 0L
    private var lastReportedPct: Double = 0.0
    private var workingTotal: Long = estimatedTotal

    @Synchronized
    fun update(currentSeg: Int, landedBytes: Long): Triple<Long, Long, Double> {
        if (segmentCount <= 0) {
            lastReportedBytes = maxOf(lastReportedBytes, landedBytes)
            val tot = if (workingTotal > 0L) maxOf(workingTotal, lastReportedBytes) else -1L
            val pct = if (tot > 0L) (lastReportedBytes.toDouble() / tot * 100.0).coerceIn(0.0, 99.9) else 0.0
            lastReportedPct = maxOf(lastReportedPct, pct)
            return Triple(lastReportedBytes, tot, lastReportedPct)
        }

        val ratio = (currentSeg.toDouble() / segmentCount.toDouble()).coerceIn(0.001, 1.0)
        val projectedTotal = (landedBytes.toDouble() / ratio).toLong()

        if (preflightTotal <= 0L && projectedTotal > 0L) {
            preflightTotal = projectedTotal
            workingTotal = projectedTotal
        }

        // Dynamic weight: w(i) = (i / N)^1.5
        val w = ratio.pow(1.5)
        val blendedTotal = ((1.0 - w) * preflightTotal + w * projectedTotal).toLong()

        val remainingSegs = segmentCount - currentSeg
        if (remainingSegs > 0) {
            val avgLandedSeg = if (currentSeg > 0) landedBytes.toDouble() / currentSeg else 0.0
            val tailReserve = maxOf(1024L, (avgLandedSeg * remainingSegs * 0.8).toLong())
            workingTotal = maxOf(blendedTotal, landedBytes + tailReserve)
        } else {
            workingTotal = landedBytes
        }

        val rawPct = if (workingTotal > 0L) (landedBytes.toDouble() / workingTotal * 100.0) else 0.0
        val clampedPct = if (currentSeg < segmentCount) {
            minOf(99.9, maxOf(lastReportedPct, rawPct))
        } else {
            100.0
        }

        lastReportedPct = clampedPct
        lastReportedBytes = maxOf(lastReportedBytes, landedBytes)

        return Triple(lastReportedBytes, workingTotal, lastReportedPct)
    }

    fun getWorkingTotal(): Long = workingTotal
    fun getLastReportedBytes(): Long = lastReportedBytes
    fun getLastReportedPct(): Double = lastReportedPct
}

/**
 * Smooth, flicker-free ETA calculator using a 3-second sliding window
 * and an exponential moving average with clamped speed floor.
 */
class FlickerFreeEtaCalculator(initialTimeMs: Long = System.currentTimeMillis()) {
    private val history = mutableListOf<Pair<Long, Long>>() // timestampMs, bytes
    private var smoothEta = -1.0
    private var lastUpdateMs = initialTimeMs

    @Synchronized
    fun update(
        currentBytes: Long,
        totalBytes: Long,
        currentSpeedBps: Double,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        if (totalBytes <= 0L || currentBytes >= totalBytes) return 0L
        val remBytes = totalBytes - currentBytes
        val fallbackEta = if (currentSpeedBps > 0.0) maxOf(1L, (remBytes / currentSpeedBps).toLong()) else -1L

        if (currentSpeedBps < 1024.0) {
            return if (smoothEta > 0) Math.round(smoothEta) else fallbackEta
        }

        history.add(Pair(nowMs, currentBytes))
        val cutoff = nowMs - 3000L
        history.removeAll { it.first < cutoff }

        val winSpeed = if (history.size >= 2) {
            val dt = (history.last().first - history.first().first) / 1000.0
            val db = history.last().second - history.first().second
            if (dt > 0.1) db / dt else currentSpeedBps
        } else {
            currentSpeedBps
        }

        if (winSpeed < 1024.0) {
            return if (smoothEta > 0) Math.round(smoothEta) else fallbackEta
        }

        val rawEta = remBytes / winSpeed

        if (smoothEta < 0.0) {
            smoothEta = rawEta
        } else {
            val beta = 0.15
            smoothEta = (beta * rawEta) + ((1.0 - beta) * smoothEta)
        }

        lastUpdateMs = nowMs
        return maxOf(1L, Math.round(smoothEta))
    }

    fun getSmoothEta(): Long = if (smoothEta > 0) Math.round(smoothEta) else -1L
}
