package com.anonrode.downloader.util

import android.content.Context
import com.anonrode.downloader.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-on activity journal: literally everything — every user action and
 * every engine/network/backend event — appended to a daily file under
 * filesDir/logs/ so any misbehavior can be diagnosed after the fact from the
 * file alone.
 *
 * Design notes:
 *  - Writes go through a single background thread: callers never block on I/O,
 *    and lines from concurrent coroutines stay ordered.
 *  - Rotation: one file per day (app-YYYY-MM-DD.txt); when a day file passes
 *    [MAX_FILE_BYTES] it rolls to .1/.2; days older than [KEEP_DAYS] are
 *    deleted on init. Worst case disk use is a few MB.
 *  - Categories tag each line so a shared log can be filtered visually:
 *    USER (what the user did), ENGINE (state machine), RESOLVE (cracking),
 *    NET (every HTTP request), BACKEND (yt-dlp/aria2c/Turbo), ERROR.
 */
object DebugLog {
    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var logDir: File? = null

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val KEEP_DAYS = 3

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ActivityLog").apply { isDaemon = true }
    }

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        purgeOldDays()
        engine("=== app started (v${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE}) ===")
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) engine("verbose logging re-enabled")
    }

    /** Absolute path of today's log file (for the Share button in Settings). */
    fun currentLogFile(): File? {
        val dir = logDir ?: return null
        return File(dir, "app-${dayFormat.format(Date())}.txt")
    }

    fun allLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("app-") }?.sortedByDescending { it.name } ?: emptyList()
    }

    // ---- category helpers -------------------------------------------------

    /** What the user did: searches, enqueues, pauses, cancels, setting changes. */
    fun user(msg: String) = log("USER", msg)

    /** Engine state-machine events: queueing, transitions, watchdog kills. */
    fun engine(msg: String) = log("ENGINE", msg)

    /** Resolver attempts and outcomes during link cracking. */
    fun resolve(msg: String) = log("RESOLVE", msg)

    /** Network requests (one line per HTTP call). */
    fun net(msg: String) = log("NET", msg)

    /** Backend lifecycle: yt-dlp/aria2c/Turbo starts, exits, kills, output. */
    fun backend(msg: String) = log("BACKEND", msg)

    /** Errors and failures with their reason. */
    fun error(msg: String) = log("ERROR", msg)

    /** Legacy entry point kept for existing callers. */
    fun write(msg: String) = engine(msg)

    /**
     * Synchronous crash flush: the background executor may not deliver before
     * the process dies, so this writes directly to today's file and returns.
     * Call this only from an uncaught-exception handler — never from the main
     * log path, which is async for a reason.
     */
    fun crashLog(throwable: Throwable) {
        val dir = logDir ?: return
        val line = "${timeFormat.format(Date())} [ERROR] CRASH ${throwable.javaClass.name}: ${throwable.message}\n" +
            throwable.stackTraceToString().take(2000) + "\n"
        try {
            val f = targetFile(dir)
            FileWriter(f, true).use { it.write(line) }
        } catch (_: Exception) {}
    }

    // ---- core -------------------------------------------------------------

    private fun log(category: String, msg: String) {
        if (!enabled) return
        val dir = logDir ?: return
        val line = "${timeFormat.format(Date())} [$category] $msg\n"
        writer.execute {
            try {
                val f = targetFile(dir)
                synchronized(this@DebugLog) {
                    FileWriter(f, true).use { it.write(line) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun targetFile(dir: File): File {
        var f = File(dir, "app-${dayFormat.format(Date())}.txt")
        // Roll within a day if a single file grows past the cap.
        if (f.length() > MAX_FILE_BYTES) {
            var roll = 1
            while (roll < 9 && File(dir, "${f.nameWithoutExtension}.$roll").length() > MAX_FILE_BYTES) roll++
            f = File(dir, "${f.nameWithoutExtension}.$roll")
        }
        return f
    }

    private fun purgeOldDays() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        dir.listFiles { f -> f.name.startsWith("app-") && f.lastModified() < cutoff }?.forEach { f ->
            try { f.delete() } catch (_: Exception) {}
        }
    }
}
