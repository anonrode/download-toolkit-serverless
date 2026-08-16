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
                    "Exception: ${throwable::class.java.name}: ${throwable.message}\n\n" +
                    "Stacktrace:\n$stackTrace\n" +
                    "========================================\n\n"

            Log.e("CrashHandler", logMessage)

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
}
