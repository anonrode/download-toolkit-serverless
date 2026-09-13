package com.anonrode.downloader.util

import android.content.Context
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
 *    [MAX_FILE_BYTES] it rolls to .1/.2; days older than the user-chosen
 *    retention window (default 7, see [configureRetention]) are deleted.
 *  - Categories tag each line so a shared log can be filtered visually:
 *    USER (what the user did), ENGINE (state machine), RESOLVE (cracking),
 *    NET (every HTTP request), BACKEND (yt-dlp/aria2c/Turbo), ERROR, and
 *    TRACE (expected-noise diagnostics, written only in verbose mode).
 */
object DebugLog {
    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var logDir: File? = null

    // SimpleDateFormat is NOT thread-safe (format() mutates an internal
    // Calendar): log() formats on the CALLER's thread while dozens of IO
    // threads log concurrently, which can throw ArrayIndexOutOfBoundsException
    // mid-format and corrupt the line. One instance per thread instead of one
    // shared instance.
    private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** User-configurable retention (Settings > Diagnostics); default 7 days. */
    @Volatile
    private var keepDays: Int = 7

    /** Gate for [trace] — expected-noise lines stay out of shared logs
     *  unless explicitly turned on for a debugging session. */
    @Volatile
    private var verbose: Boolean = false

    fun setVerbose(on: Boolean) {
        verbose = on
    }

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ActivityLog").apply { isDaemon = true }
    }

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        purgeOldDays()
        // Version banner lets a shared log identify which build produced it
        // (a log from an old APK was once misdiagnosed as a live bug).
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        // Version banner lets a shared log identify which build produced it
        // (a log from an old APK was once misdiagnosed as a live bug). The
        // Android version + device model joined it after the regex-engine
        // incidents: "JVM compiles it, the phone rejects it" is only
        // diagnosable if the log says WHICH engine ran.
        engine(
            "=== app started (v$version) android=${android.os.Build.VERSION.SDK_INT}" +
                " (${android.os.Build.VERSION.RELEASE}) ${android.os.Build.MANUFACTURER}" +
                " ${android.os.Build.MODEL} ==="
        )
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) engine("verbose logging re-enabled")
    }

    /** Absolute path of today's log file. */
    fun currentLogFile(): File? {
        val dir = logDir ?: return null
        return File(dir, "app-${dayFormat.get().format(Date())}.txt")
    }

    fun allLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("app-") }?.sortedByDescending { it.name } ?: emptyList()
    }

    /**
     * Log files inside the retention window, oldest first (including same-day
     * rolls). This is what the Share button sends: sharing only today's file
     * made the log look like it auto-cleared every 24 hours even though
     * retention kept 7 days on disk (user-reported).
     *
     * Name-descending order is newest-first across days AND within a day
     * (app-….txt is the current roll, .1 the oldest), so reversing yields
     * chronological order.
     */
    fun retainedLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        return dir.listFiles { f -> f.name.startsWith("app-") && f.lastModified() >= cutoff }
            ?.sortedByDescending { it.name }
            ?.asReversed()
            ?: emptyList()
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

    /** Expected-noise diagnostics (template fallbacks, skipped steps):
     *  only written when verbose logging is on, so they can't drown real
     *  errors in the shared log. */
    fun trace(msg: String) {
        if (verbose) log("TRACE", msg)
    }

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
        // The cause chain goes FIRST and is never truncated: the top-level
        // trace of wrapper errors (ExceptionInInitializerError & friends) runs
        // 30+ frames, and the old 2000-char cut dropped the "Caused by:" lines
        // — the ONLY text naming the real root cause. Both v3.1.x regex crashes
        // were diagnosed blind for exactly this reason.
        val chain = StringBuilder()
        run {
            var c: Throwable? = throwable.cause
            var depth = 0
            while (c != null && c !== c.cause && depth < 8) {
                chain.append("CAUSE ").append(depth + 1).append(": ")
                    .append(c.javaClass.name).append(": ").append(c.message).append('\n')
                for (f in c.stackTrace.take(8)) chain.append("    at ").append(f).append('\n')
                c = c.cause
                depth++
            }
        }
        val line = "${timeFormat.get().format(Date())} [ERROR] CRASH ${throwable.javaClass.name}: ${throwable.message}\n" +
            chain.toString() +
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
        val line = "${timeFormat.get().format(Date())} [$category] $msg\n"
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
        var f = File(dir, "app-${dayFormat.get().format(Date())}.txt")
        // Roll within a day if a single file grows past the cap.
        if (f.length() > MAX_FILE_BYTES) {
            var roll = 1
            while (roll < 9 && File(dir, "${f.nameWithoutExtension}.$roll").length() > MAX_FILE_BYTES) roll++
            f = File(dir, "${f.nameWithoutExtension}.$roll")
        }
        return f
    }

    /** Applies a user-chosen retention window and purges immediately, so
     *  lowering the setting frees the space right away instead of waiting
     *  for the next app start. */
    fun configureRetention(days: Int) {
        keepDays = days.coerceIn(1, 90)
        purgeOldDays()
    }

    private fun purgeOldDays() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        dir.listFiles { f -> f.name.startsWith("app-") && f.lastModified() < cutoff }?.forEach { f ->
            try { f.delete() } catch (_: Exception) {}
        }
    }
}
