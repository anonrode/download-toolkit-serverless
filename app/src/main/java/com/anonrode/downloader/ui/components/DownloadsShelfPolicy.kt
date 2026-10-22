package com.anonrode.downloader.ui.components

/**
 * Pure visibility policy for the Downloads "CONTINUE & MEDIA" shelf
 * (owner directive §2.2): the shelf carries completed media, so it only
 * renders under the All and Done filters — never while the user is focused
 * on transfers (Active / Paused / Failed), and never when empty.
 *
 * Kept out of the Composable so the rule is unit-testable without Android.
 */
object DownloadsShelfPolicy {

    const val FILTER_ALL = "all"
    const val FILTER_ACTIVE = "active"
    const val FILTER_PAUSED = "paused"
    const val FILTER_DONE = "done"
    const val FILTER_FAILED = "failed"

    fun isShelfVisible(
        statusFilter: String,
        hasSeriesContinue: Boolean,
        hasShelfTiles: Boolean
    ): Boolean {
        if (statusFilter != FILTER_ALL && statusFilter != FILTER_DONE) return false
        return hasSeriesContinue || hasShelfTiles
    }
}
