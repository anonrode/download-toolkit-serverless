package com.anonrode.downloader.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
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

            // Save to Downloads folder if accessible
            try {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val anonDir = File(dlDir, "Anon")
                if (!anonDir.exists()) anonDir.mkdirs()
                val crashFile = File(anonDir, "anon_crash.txt")
                crashFile.appendText(logMessage)
            } catch (_: Exception) {}

            // Save to internal app files directory as fallback
            appContext?.let { ctx ->
                try {
                    val internalCrash = File(ctx.filesDir, "anon_crash.txt")
                    internalCrash.appendText(logMessage)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        defaultHandler?.uncaughtException(thread, throwable)
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
        val sb = StringBuilder()
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
        for (f in candidates) {
            try {
                if (f.exists() && f.length() > 0) {
                    sb.append("===== ").append(f.absolutePath).append(" =====\n")
                    val text = f.readText()
                    // tail-first: the newest crash is the one being diagnosed
                    sb.append(
                        if (text.length > 512_000) text.substring(text.length - 512_000) else text
                    ).append('\n')
                }
            } catch (_: Exception) {}
        }
        return sb.toString()
    }
}
