package com.anonrode.downloader.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logMessage = "=== ANONRODE CRASH LOG ($timeStamp) ===\n" +
                    "Thread: ${thread.name} (id=${thread.id})\n" +
                    "Device: android ${android.os.Build.VERSION.SDK_INT}" +
                    " (${android.os.Build.VERSION.RELEASE})" +
                    " ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "Exception: ${throwable::class.java.name}: ${throwable.message}\n\n" +
                    "Stacktrace:\n$stackTrace\n" +
                    "========================================\n\n"

            Log.e("CrashHandler", logMessage)

            // The activity journal (shared via Settings -> Share Activity Log)
            // must carry the crash too — a log that just ends mid-line was
            // undiagnosable (user-reported log ended abruptly with no trace).
            DebugLog.crashLog(throwable)

            // Crash-first full-log upload (2026-10-15 directive): the crash
            // trace is in the journal now, so ship the WHOLE thing immediately
            // — bounded snapshot (≤1 MB journal + crash reports), short
            // timeouts, synchronous because the process dies when this handler
            // returns. A device that uninstalls right after a fatal crash
            // still gets its evidence onto the server; the size/day triggers
            // could never guarantee that. Send-and-clear cycle of its own,
            // user kill-switch respected inside; anything it can't do is a
            // silent no-op and the day/size triggers pick the evidence up on
            // the next launch.
            appContext?.let { ctx ->
                try {
                    LogUploader.onFatalCrash(ctx)
                } catch (_: Throwable) {}
            }

            // Track 6: the same crash snapshot to the OTA-routed sink, so a
            // fleet-wide fatality is visible without waiting for users to
            // manually share log files. Synchronous on purpose: the process
            // dies the moment this handler returns, so an async dispatch
            // would never run. Caps itself at ~4 KB; silent no-op when the
            // sink is unconfigured or unreachable.
            RemoteLogger.report(
                type = "fatal_crash",
                message = "${throwable::class.java.name}: ${throwable.message ?: "no message"}",
                context = mapOf(
                    "thread" to thread.name,
                    "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    "api_level" to android.os.Build.VERSION.SDK_INT
                ),
                stackTrace = stackTrace,
                waitForDelivery = true
            )

            // Save to Downloads folder if accessible
            try {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val anonDir = File(dlDir, "Anon")
                if (!anonDir.exists()) anonDir.mkdirs()
                val crashFile = File(anonDir, "anon_crash.txt")
                appendCrashEntry(crashFile, logMessage)
            } catch (_: Exception) {}

            // Save to internal app files directory as fallback
            appContext?.let { ctx ->
                try {
                    val internalCrash = File(ctx.filesDir, "anon_crash.txt")
                    appendCrashEntry(internalCrash, logMessage)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        defaultHandler?.uncaughtException(thread, throwable)
    }

    // ---- crash-file hygiene (2026-10-15 device evidence) -------------------
    // anon_crash.txt had grown so large that its 512 KB share tail flood(ed)
    // the activity journal: one StackOverflowError recursion (the ~969-frame
    // resolve cycle) re-crashed on every launch and every copy appended the
    // full fat trace again, so the tail the journal reads carried nothing but
    // repeats and the real history was pushed out. Two guards close that
    // class:
    //   1. repeat folding — an identical crash (same exception line + first
    //      3 stack lines) appended right after itself adds one small
    //      "REPEATED xN" counter line instead of another full trace;
    //   2. hard cap — a file over 256 KB is rewritten to a truncation marker
    //      + the NEWEST 128 KB, so no crash storm can make the file
    //      unbounded. The snapshot/watermark contract is untouched:
    //      snapshotCrashFiles still pins lengths first and applyCrashWatermark
    //      still clears only after a confirmed upload.
    internal const val CRASH_FILE_MAX_BYTES = 256 * 1024
    internal const val CRASH_FILE_KEEP_BYTES = 128 * 1024
    private const val SIGNATURE_TAIL_BYTES = 128 * 1024
    private const val ENTRY_HEADER = "=== ANONRODE CRASH LOG ("
    private const val REPEAT_MARKER = "=== ANONRODE CRASH LOG REPEATED x"
    private const val TRUNCATION_MARKER =
        "=== ANONRODE CRASH LOG TRUNCATED: older entries dropped, newest 128 KB kept ===\n"
    private val REPEAT_COUNT_RE = Regex("""REPEATED x(\d+)""")

    /**
     * Signature of one crash entry: the "Exception:" line plus the first
     * three stack lines — stable across re-crashes at the same site (thread
     * id / timestamp / device block are ignored), different for genuinely
     * different crashes. Pure string math (JVM-testable).
     */
    internal fun crashSignature(crashEntry: String): String {
        val exceptionLine = crashEntry.lineSequence()
            .firstOrNull { it.startsWith("Exception: ") } ?: return ""
        val stackLines = crashEntry.lineSequence()
            .dropWhile { !it.startsWith("Stacktrace:") }
            .drop(1)
            .filter { it.isNotBlank() && !it.startsWith("===") }
            .take(3)
            .toList()
        return (listOf(exceptionLine) + stackLines).joinToString("\n")
    }

    /** Last full entry in [text] — from the trailing [ENTRY_HEADER] on; the
     *  whole text when no header is present. REPEATED/TRUNCATED lines can
     *  never match the header ("LOG (" vs "LOG REPEATED/TRUNCATED"). */
    private fun lastEntryText(text: String): String {
        val idx = text.lastIndexOf(ENTRY_HEADER)
        return if (idx >= 0) text.substring(idx) else text
    }

    /** Newest [maxBytes] of [file] as text, aligned to a line boundary
     *  (a partial first line from the mid-file cut is dropped); "" when
     *  unreadable. */
    private fun readTailText(file: File, maxBytes: Int): String {
        return try {
            val len = file.length()
            if (len <= 0) return ""
            val start = if (len > maxBytes) len - maxBytes else 0L
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                val text = String(buf, Charsets.UTF_8)
                if (start > 0) text.substringAfter('\n', text) else text
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Appends [logMessage] to [file], folding an identical immediate repeat
     * into a single "REPEATED xN" counter line, then enforces
     * [CRASH_FILE_MAX_BYTES]. File-only (no Android APIs) so the dedupe/cap
     * logic stays JVM-testable; callers keep their own try/catch.
     */
    internal fun appendCrashEntry(file: File, logMessage: String) {
        val signature = crashSignature(logMessage)
        val lastBlock = lastEntryText(readTailText(file, SIGNATURE_TAIL_BYTES))
        if (signature.isNotBlank() && signature == crashSignature(lastBlock)) {
            val repeats = REPEAT_COUNT_RE.findAll(lastBlock).count()
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("$REPEAT_MARKER${repeats + 2} ($timeStamp) ===\n")
            enforceCrashFileCap(file)
            return
        }
        file.appendText(logMessage)
        enforceCrashFileCap(file)
    }

    /** Keeps a crash file at or below the cap: truncation marker + the newest
     *  [CRASH_FILE_KEEP_BYTES], aligned to a line boundary. */
    internal fun enforceCrashFileCap(file: File) {
        try {
            if (file.length() <= CRASH_FILE_MAX_BYTES) return
            val keep = readTailText(file, CRASH_FILE_KEEP_BYTES)
            file.writeText(TRUNCATION_MARKER + keep)
        } catch (_: Exception) {}
    }

    /**
     * Contents of the crash report files this handler writes (public Downloads
     * copy first, internal fallback second). Unlike the activity-log CRASH
     * line, these carry the FULL untruncated printStackTrace — including the
     * "Caused by:" chain that the v3.1.x incidents were diagnosed blind
     * without. Settings -> Share Activity Log appends this so one share is
     * always enough to root-cause a device crash.
     */
    fun crashReportsText(context: Context): String {
        return crashReportsTextFor(snapshotCrashFiles(context))
    }

    // ---- upload snapshot / watermark contract -----------------------------
    // The full-log pipeline (LogUploader) ships crash reports alongside the
    // activity journal and clears the local files once the server confirms.
    // Same rule as the journal: capture lengths FIRST, ship exactly that,
    // truncate on confirmation only.

    /** One crash file frozen at a known length. */
    data class CrashSnap(val file: File, val length: Long)

    /** Captures the crash-report files (Downloads copy first, internal
     *  fallback second) with their current lengths — the upload extent and
     *  the clear extent are the same numbers by construction. */
    fun snapshotCrashFiles(context: Context): List<CrashSnap> {
        val candidates = listOfNotNull(
            try {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Anon"
                ).let { File(it, "anon_crash.txt") }
            } catch (_: Exception) {
                null
            },
            File(context.filesDir, "anon_crash.txt")
        )
        return candidates.mapNotNull { f ->
            try {
                if (f.exists() && f.length() > 0) CrashSnap(f, f.length()) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Crash text bounded to the captured [snaps] — identical format to
     * [crashReportsText], but a crash appended while an upload was in flight
     * can never be read into the payload AND then cleared unsent: the
     * watermark range is fixed at snapshot time. Tail-capped per file
     * (512 KB) exactly like the manual share.
     */
    fun crashReportsTextFor(snaps: List<CrashSnap>): String {
        val sb = StringBuilder()
        for (snap in snaps) {
            try {
                val f = snap.file
                if (!f.exists() || snap.length <= 0) continue
                val len = minOf(f.length(), snap.length)
                sb.append("===== ").append(f.absolutePath).append(" =====\n")
                sb.append(readBoundedText(f, len)).append('\n')
            } catch (_: Exception) {}
        }
        return sb.toString()
    }

    /** Newest [TAIL_CAP] bytes of the first [upTo] bytes of the file. */
    private fun readBoundedText(f: File, upTo: Long, tailCap: Long = 512_000): String {
        val len = minOf(f.length(), upTo)
        if (len <= 0) return ""
        val start = if (len > tailCap) len - tailCap else 0L
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
    }

    /**
     * Clear-after-send for crash reports (server 2xx required): both copies
     * are deleted outright. The upload carried the newest 512 KB per file —
     * the full untruncated stack of the newest crashes, the part diagnosis
     * runs on — and anything older than that tail was already beyond the
     * shareable/shipable cap, so nothing diagnosable is lost, and "after it
     * uploads, the phone is clean" stays literally true.
     */
    fun applyCrashWatermark(snaps: List<CrashSnap>) {
        for (snap in snaps) {
            try { snap.file.delete() } catch (_: Exception) {}
        }
    }
}
