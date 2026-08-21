package com.anonrode.downloader.util

import android.content.Context
import java.io.File

/**
 * Minimal on-device debug log. When enabled (pref_debug_logging), key engine
 * events are appended to cacheDir/debug_log.txt so users can share them for
 * diagnosis. No-op when disabled — zero cost in normal operation.
 */
object DebugLog {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.cacheDir, "debug_log.txt")
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) write("--- debug logging enabled ---")
    }

    fun write(msg: String) {
        if (!enabled) return
        try {
            val f = file ?: return
            val line = "${System.currentTimeMillis()} ${msg}\n"
            synchronized(this) {
                f.appendText(line)
            }
        } catch (_: Exception) {}
    }
}
