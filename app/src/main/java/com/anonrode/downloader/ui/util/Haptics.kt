package com.anonrode.downloader.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptic feedback helpers (UI research round). Compose 1.7 exposes only
 * `HapticFeedbackType.LongPress`/`TextHandleMove` — the richer set (Confirm,
 * Reject, SegmentTick…) is 1.8.0+ and would NOT compile on this BOM — so the
 * calls go straight to the platform, which is exactly what Compose's own
 * AndroidHapticFeedback delegates to anyway.
 *
 * The default flags are used on purpose: `performHapticFeedback` then
 * respects the user's system haptics setting. Never pass
 * FLAG_IGNORE_GLOBAL_SETTING/FLAG_IGNORE_VIEW_SETTING — the app must not be
 * the one that buzzes after the user turned haptics off.
 *
 * API-30+ constants are guarded with Build.VERSION.SDK_INT: they inline
 * fine, but unguarded use trips Lint's NewApi at minSdk 26.
 */

/** Light acknowledgement tick — per-item selection toggles. */
fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

/** Commit/confirm thump — queueing downloads, accepting a primary action. */
fun View.confirmHaptic() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
    else HapticFeedbackConstants.CLOCK_TICK
)

/** Weighty reject — destructive confirmations accepted (delete/cancel all). */
fun View.rejectHaptic() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
    else HapticFeedbackConstants.LONG_PRESS
)
