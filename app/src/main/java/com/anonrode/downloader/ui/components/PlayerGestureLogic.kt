package com.anonrode.downloader.ui.components

import kotlin.math.abs

/**
 * Pure gesture math for the player overlay (directive §5.3). Kept out of the
 * composable so CI can verify the tap zones and clamps without a device.
 */
internal enum class PlayerTapZone { REWIND, PLAY_PAUSE, FORWARD }

/**
 * Left 35% / center 30% / right 35%. A non-positive width can only happen
 * before the first layout pass; treat it as the center so no seek fires.
 */
internal fun playerTapZone(x: Float, width: Float): PlayerTapZone {
    if (width <= 0f) return PlayerTapZone.PLAY_PAUSE
    val fraction = x / width
    return when {
        fraction < 0.35f -> PlayerTapZone.REWIND
        fraction > 0.65f -> PlayerTapZone.FORWARD
        else -> PlayerTapZone.PLAY_PAUSE
    }
}

/** Seek target clamped into the media, or at zero when the duration is unknown. */
internal fun clampedSeekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val raw = positionMs + deltaMs
    return if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
}

/**
 * Brightness after a vertical drag: up (negative dragPx) brightens. Never
 * goes fully black — 0.02 keeps the video visible, matching the window floor.
 */
internal fun brightnessPercentAfterDrag(
    startPercent: Float,
    dragPx: Float,
    heightPx: Float
): Float {
    if (heightPx <= 0f) return startPercent.coerceIn(2f, 100f)
    return (startPercent - (dragPx / heightPx) * 100f).coerceIn(2f, 100f)
}

/** HUD caption for a double-tap seek, e.g. "-10s" / "+10s". */
internal fun formatSeekHud(deltaMs: Long): String {
    val sign = if (deltaMs < 0L) "-" else "+"
    return "$sign${abs(deltaMs) / 1000L}s"
}
