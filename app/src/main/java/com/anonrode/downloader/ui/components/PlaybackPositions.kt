package com.anonrode.downloader.ui.components

import android.content.Context
import java.io.File

/**
 * Per-file "last played position" for the in-app player, so a reopened file
 * resumes where it left off instead of starting over.
 *
 * Kept in its own prefs file (not downloader_settings): entries are keyed by
 * absolute file path — one per file ever played — and would pollute the
 * user-facing settings file. Values are "positionMs:durationMs"; the stored
 * duration lets [save] decide "watched to the end" without asking the player.
 */
object PlaybackPositions {
    private const val PREFS = "player_positions"

    /** Shorter than this is "just started" — not worth resuming. */
    private const val MIN_SAVE_MS = 30_000L

    /** Closer than this to the end counts as finished — next open starts fresh. */
    private const val NEAR_END_MS = 15_000L

    /** Soft cap before entries for deleted files are pruned. */
    private const val MAX_ENTRIES = 200

    data class Entry(val positionMs: Long, val durationMs: Long)

    fun get(context: Context, filePath: String): Entry? = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(filePath, null) ?: return null
        val parts = raw.split(':')
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (pos < MIN_SAVE_MS) null else Entry(pos, dur)
    } catch (_: Throwable) {
        null
    }

    /**
     * Records the position — or removes the entry when the position says
     * "just started" or "watched to the end"; both mean the next open should
     * start from the beginning.
     */
    fun save(context: Context, filePath: String, positionMs: Long, durationMs: Long) {
        if (filePath.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val nearEnd = durationMs > 0 && (durationMs - positionMs) < NEAR_END_MS
            val edit = prefs.edit()
            if (positionMs < MIN_SAVE_MS || nearEnd) {
                edit.remove(filePath)
            } else {
                edit.putString(filePath, "$positionMs:$durationMs")
                if (prefs.all.size > MAX_ENTRIES) {
                    prefs.all.keys.filter { !File(it).exists() }.forEach { edit.remove(it) }
                }
            }
            edit.apply()
        } catch (_: Throwable) {
            /* positions are a nicety — never let them break playback */
        }
    }

    fun clear(context: Context, filePath: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(filePath).apply()
        } catch (_: Throwable) {}
    }
}
