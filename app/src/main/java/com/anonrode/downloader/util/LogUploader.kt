package com.anonrode.downloader.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Daily auto-upload of the activity journal to our own server — the "logs on
 * the server" half of the pre-launch ask, with every discipline enforced:
 *
 *  - **The same content the manual share produces** (one contract): token
 *    redaction, oldest log files dropped until ≤8 MB, crash reports appended.
 *  - gzip POST to `/api/v1/telemetry/full-log` on the SAME origin the fatal
 *    reports use (OTA-routed), with `X-Client-Id` (the anonymous diagnostics
 *    id) + `X-Log-Date` headers — exactly what the endpoint validates.
 *  - **Any connection (user directive, 2026-10-15)**: uploads ride normal
 *    mobile data too — the once/day, redacted, ≤8 MB payload makes the cost
 *    a rounding error next to one video thumbnail, and journal visibility
 *    was never something to hold hostage for a clean Wi-Fi.
 *  - **Once per day**, gated by the same kill-switch as the fatal reports,
 *    off-thread, silent no-op on any failure — a failed journal upload must
 *    never matter to the person using the app.
 */
object LogUploader {

    private const val CAP_BYTES = 8L * 1024 * 1024
    private const val STAMP_KEY = "pref_log_upload_last"

    fun maybeUploadDaily(context: Context) {
        try {
            if (!RemoteLogger.isEnabled(context)) return

            val prefs = context.getSharedPreferences("anon_serverless_prefs", Context.MODE_PRIVATE)
            val last = prefs.getLong(STAMP_KEY, 0L)
            val now = System.currentTimeMillis()
            if (now - last < 24L * 60 * 60 * 1000) return

            if (!hasInternet(context)) {
                DebugLog.user("log upload: skipped — waiting for a usable connection")
                return
            }
            val origin = RemoteLogger.sinkOrigin() ?: return

            val dayFiles = DebugLog.retainedLogFiles()
            if (dayFiles.isEmpty()) return

            val gz = buildGz(context, dayFiles) ?: return
            val uploaded = post(context, origin, gz, logDate(dayFiles))
            if (uploaded) {
                prefs.edit().putLong(STAMP_KEY, now).apply()
                DebugLog.user("activity log uploaded to server (${gz.size / 1024} KB gz)")
            } else {
                DebugLog.error("log upload failed — retrying tomorrow")
            }
        } catch (t: Throwable) {
            DebugLog.error("log upload error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun hasInternet(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        false
    }

    private fun logDate(files: List<File>): String =
        files.lastOrNull()?.name?.removePrefix("app-")?.removeSuffix(".txt") ?: "unknown"

    /** Identical redaction contract to the manual share: token params blanked. */
    private fun redact(line: String): String = line.replace(
        Regex("""[?&](token|download_token|pt|expiry|expires)=[^\s&]+""")
    ) { m -> "${m.value.substringBefore('=')}=***REDACTED***" }

    private fun buildGz(context: Context, files: List<File>): ByteArray? = try {
        var total = files.sumOf { it.length() }
        var from = 0
        while (from < files.size - 1 && total < Long.MAX_VALUE && total > CAP_BYTES) {
            total -= files[from].length()
            from++
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { w ->
            if (from > 0) w.write("(oldest $from log files omitted to keep the upload under 8 MB)\n\n")
            for (f in files.subList(from, files.size)) {
                w.write("===== ${f.name} =====\n")
                f.forEachLine(Charsets.UTF_8) { line ->
                    w.write(redact(line)); w.write("\n")
                }
                w.write("\n")
            }
            val crashTxt = CrashHandler.crashReportsText(context)
            if (crashTxt.isNotBlank()) {
                w.write("\n===== CRASH REPORTS =====\n")
                crashTxt.lineSequence().forEach { line -> w.write(redact(line)); w.write("\n") }
            }
        }
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun post(context: Context, origin: String, gz: ByteArray, logDate: String): Boolean {
        val conn = URL("$origin/api/v1/telemetry/full-log").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(gz.size)
            conn.setRequestProperty("Content-Type", "application/gzip")
            conn.setRequestProperty("X-Client-Id", RemoteLogger.clientIdForUpload(context))
            conn.setRequestProperty("X-Log-Date", logDate)
            OutputStreamWriter(conn.outputStream, Charsets.ISO_8859_1).use { w ->
                w.write(String(gz, Charsets.ISO_8859_1))
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
