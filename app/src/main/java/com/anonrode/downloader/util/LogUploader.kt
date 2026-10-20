package com.anonrode.downloader.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.anonrode.downloader.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/**
 * The activity-journal pipeline to our own server: the FULL log ships to
 * /api/v1/telemetry/full-log and, once the server has it, the phone-side
 * copy is cleared. Replaces the old once-a-day-only uploader (2026-10-15
 * directive: chunked cadence + self-identifying payload + clear-after-send).
 *
 * Triggers (any of):
 *  - **Size**: the journal crossing 1 MB ships immediately — a chatty device
 *    no longer waits a full day for its log to land on the server. Debounced
 *    to one attempt per [GREW_DEBOUNCE_MS] and runs on a dedicated executor,
 *    never on the logging thread.
 *  - **First open of the day**: the original once/24h safety net, kept — a
 *    quiet device under 1 MB would otherwise never upload again.
 *  - **Crash-first**: on an uncaught exception a bounded snapshot (≤1 MB
 *    journal + crash reports) attempts a short-timeout synchronous POST
 *    while the process is dying (see [onFatalCrash]) — the only way evidence
 *    survives a device that uninstalls before the next trigger. Own send+clear
 *    cycle, not gated by the daily stamp.
 *  - **Pending retry**: a failed attempt persists a pending flag + its seq
 *    and schedules 1/2/5/15/30/60-minute retries while the process lives.
 *    A network-available callback and the next foreground resume attempt
 *    immediately; app-open bypasses the daily gate while pending. This is
 *    the fix for the 2026-10-19 13:52/14:07 backlog: failures used to wait
 *    for a 1 MB crossing or the next process start.
 *
 * Clear-after-send correctness — uploads are WATERMARKED SNAPSHOTS:
 * every file's byte length is captured before reading, that exact range is
 * gzipped, and only a 2xx response truncates each file to exactly its
 * snapshotted length (fully-sent files are deleted). Lines appended while
 * the upload is in flight — the app never stops logging — survive to the next
 * cycle; a failed, timed-out or refused send clears nothing.
 *
 * Dedupe: the pending seq ([PENDING_SEQ_KEY]) is committed BEFORE the POST
 * and is REUSED by every retry until a 2xx arrives. A response-lost retry
 * therefore re-sends the SAME seq and the server overwrites its copy instead
 * of storing a duplicate; only a 2xx advances [SEQ_KEY] (last acknowledged).
 *
 * Identity: every upload's first line self-identifies (`# anon-log v2 |
 * id=… | seq=… | build=… | device=… | android=…`), so the payload stays
 * attributable even when headers and artifact get separated. The public IP is
 * deliberately NOT sent by the client (it usually can't know its real one:
 * carrier NAT/VPN) — the endpoint records the connection's actual source
 * address into logs/clients/<id>/index.jsonl on every upload.
 *
 * Discipline carried over from the daily uploader: token redaction identical
 * to the manual share (full events, never a live credential), uploads ride
 * any connection (user directive, 2026-10-15 — not Wi-Fi-gated), the same
 * Settings kill-switch as the fatal reports, and EVERY failure path is
 * silent to the user. Offline convergence cap: a device that stays offline
 * never holds more than [MAX_RETAINED_BYTES] of journal — the oldest day
 * files are pruned, and only ever AFTER an upload attempt actually ran.
 */
object LogUploader {

    private const val SEND_THRESHOLD_BYTES = 1L * 1024 * 1024   // journal ≥ 1 MB ships now
    private const val MAX_RETAINED_BYTES = 4L * 1024 * 1024     // offline convergence cap
    private const val PRUNE_TO_BYTES = 3L * 1024 * 1024
    private const val MAX_SNAPSHOT_BYTES = 4L * 1024 * 1024     // payload snapshot cap
    private const val CRASH_SNAPSHOT_BYTES = 1L * 1024 * 1024   // bounded crash-path payload
    private const val DAILY_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val GREW_DEBOUNCE_MS = 10L * 60 * 1000
    private const val RETRY_TRIGGER_DEBOUNCE_MS = 30_000L

    private const val PREFS = "anon_serverless_prefs"
    private const val STAMP_KEY = "pref_log_upload_last"
    private const val SEQ_KEY = "pref_log_upload_seq"
    private const val PENDING_ACTIVE_KEY = "pref_log_upload_pending"
    private const val PENDING_SEQ_KEY = "pref_log_upload_pending_seq"
    private const val PENDING_DATE_KEY = "pref_log_upload_pending_date"
    private const val RETRY_ATTEMPT_KEY = "pref_log_upload_retry_attempt"
    private const val RETRY_AT_KEY = "pref_log_upload_retry_at"

    /** 1, 2, 5, 15, 30, 60 minutes; the last value repeats. */
    private val RETRY_BACKOFF_MS = longArrayOf(
        60_000L, 120_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "log-uploader").apply { isDaemon = true }
    }
    private val retryExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "log-uploader-retry").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)
    private val scheduledRetry = AtomicReference<ScheduledFuture<*>?>(null)

    @Volatile
    private var lastGrewAttemptAt = 0L

    @Volatile
    private var lastRetryTriggerAt = 0L

    @Volatile
    private var installed = false

    /** Debug-only test seam used by the debug source-set receiver. */
    @Volatile
    internal var debugForceFailOnce: Boolean = false

    /** Called from AnonApp.onCreate — hooks the 1 MB size trigger, network
     *  availability, and foreground resumes onto the journal. Cheap: only
     *  registers observers. */
    fun install(context: Context) {
        DebugLog.sizeWatch = { totalBytes -> onJournalGrew(context, totalBytes) }
        if (installed) return
        installed = true
        try {
            val app = context.applicationContext as? Application ?: return
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) {
                    onForegrounded(context)
                }
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        } catch (_: Throwable) {
            // Lifecycle registration must never make app startup fragile.
        }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onConnectivityReturned(context)
                }
            })
        } catch (_: Throwable) {
            // ACCESS_NETWORK_STATE / API availability failure: the scheduled
            // retry and next app-open still converge.
        }
    }

    /** App-open entry (AnonApp init coroutine): the once-per-day safety net.
     *  Uploads only when this device hasn't in 24h and has anything unsent;
     *  every other case is a silent no-op. A pending failed upload bypasses
     *  the daily gate — otherwise a failure right after a successful send
     *  would wait another 24h. */
    fun onAppOpen(context: Context) {
        submit { uploadOnce(context, enforceDailyGate = true, crashBounded = false) }
    }

    /** Foreground-resume hook (registered by [install]): a pending upload
     *  retries when the user returns to the app, not 24h later. */
    private fun onForegrounded(context: Context) {
        if (!hasPending(context)) return
        val now = System.currentTimeMillis()
        if (now - lastRetryTriggerAt < RETRY_TRIGGER_DEBOUNCE_MS) return
        lastRetryTriggerAt = now
        submit { uploadOnce(context, enforceDailyGate = false, crashBounded = false) }
    }

    /** Network-available hook: the fastest convergence path after an outage. */
    private fun onConnectivityReturned(context: Context) {
        if (!hasPending(context)) return
        val now = System.currentTimeMillis()
        if (now - lastRetryTriggerAt < RETRY_TRIGGER_DEBOUNCE_MS) return
        lastRetryTriggerAt = now
        submit { uploadOnce(context, enforceDailyGate = false, crashBounded = false) }
    }

    /** 1 MB crossing — called on DebugLog's writer thread with the live total,
     *  so everything here is cheap: threshold + debounce + hand-off. The real
     *  work (prefs, network, truncation) runs on the uploader executor. */
    fun onJournalGrew(context: Context, totalBytes: Long) {
        if (totalBytes < SEND_THRESHOLD_BYTES) return
        val now = System.currentTimeMillis()
        if (now - lastGrewAttemptAt < GREW_DEBOUNCE_MS) return
        lastGrewAttemptAt = now
        submit { uploadOnce(context, enforceDailyGate = false, crashBounded = false) }
    }

    /** Uncaught-exception path (CrashHandler): synchronous, bounded, on the
     *  dying thread. Worst case adds ~18 s to a crash the user already
     *  experienced; evidence that survives an immediate uninstall wins. */
    fun onFatalCrash(context: Context) = try {
        uploadOnce(context, enforceDailyGate = false, crashBounded = true)
    } catch (_: Throwable) {
        false
    }

    private fun submit(task: () -> Unit) {
        // One upload at a time; overlapping triggers collapse into the run in flight.
        if (!inFlight.compareAndSet(false, true)) return
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                try { DebugLog.error("log upload error: ${t.message ?: t.javaClass.simpleName}") } catch (_: Throwable) {}
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun hasPending(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PENDING_ACTIVE_KEY, false)
    } catch (_: Throwable) {
        false
    }

    /** One send-and-maybe-clear cycle. Returns true only when the server
     *  accepted the snapshot AND the local watermark was applied. */
    private fun uploadOnce(
        context: Context,
        enforceDailyGate: Boolean,
        crashBounded: Boolean
    ): Boolean {
        if (!RemoteLogger.isEnabled(context)) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(PENDING_ACTIVE_KEY, false)
        if (enforceDailyGate && !pending) {
            val last = prefs.getLong(STAMP_KEY, 0L)
            if (System.currentTimeMillis() - last < DAILY_INTERVAL_MS) return false
        }

        if (!hasInternet(context)) {
            // Device offline: converge storage (the cap only ever trims after
            // an attempt ran — never instead of one), then wait for the
            // network callback / scheduled retry / next app open.
            markPending(context, prefs)
            if (DebugLog.totalJournalBytes() > MAX_RETAINED_BYTES) {
                DebugLog.pruneOldest(PRUNE_TO_BYTES)
            } else {
                DebugLog.user("log upload: skipped — waiting for a usable connection")
            }
            if (!crashBounded) scheduleRetry(context, prefs, 0L)
            return false
        }

        val origin = RemoteLogger.sinkOrigin()
        if (origin == null) {
            DebugLog.error("log upload failed — no sink origin in OTA rules yet; retrying")
            markPending(context, prefs)
            if (!crashBounded) scheduleRetry(context, prefs, 0L)
            return false
        }

        val journal = DebugLog.snapshotJournal(if (crashBounded) CRASH_SNAPSHOT_BYTES else MAX_SNAPSHOT_BYTES)
        val crashes = CrashHandler.snapshotCrashFiles(context)
        if (journal.isEmpty() && crashes.isEmpty()) {
            clearPending(prefs)
            return false
        }

        // Reuse the pending seq until the server acknowledges it. The seq is
        // committed BEFORE the wire so a response-lost retry resolves to the
        // same server-side artifact (overwrite), not a duplicate.
        val pendingSeq = prefs.getLong(PENDING_SEQ_KEY, 0L)
        val seq = if (pending && pendingSeq > 0L) pendingSeq else prefs.getLong(SEQ_KEY, 0L) + 1L
        val logDate = if (pending && pendingSeq > 0L) {
            prefs.getString(PENDING_DATE_KEY, null) ?: defaultLogDate(journal)
        } else {
            defaultLogDate(journal)
        }

        val gz = buildGz(context, seq, logDate, journal, crashes) ?: run {
            DebugLog.error("log upload failed — could not build snapshot (seq=$seq); retrying")
            markPending(context, prefs, seq, logDate)
            if (!crashBounded) scheduleRetry(context, prefs, 0L)
            return false
        }

        // Persist the identity before the wire. If the process dies after the
        // server stores it but before the response, the next run reuses seq.
        prefs.edit()
            .putBoolean(PENDING_ACTIVE_KEY, true)
            .putLong(PENDING_SEQ_KEY, seq)
            .putString(PENDING_DATE_KEY, logDate)
            .commit()

        val result = post(context, origin, gz, logDate, seq, shortTimeouts = crashBounded)

        if (result.ok) {
            prefs.edit()
                .putLong(STAMP_KEY, System.currentTimeMillis())
                .putLong(SEQ_KEY, seq)
                .putBoolean(PENDING_ACTIVE_KEY, false)
                .remove(PENDING_SEQ_KEY)
                .remove(PENDING_DATE_KEY)
                .putInt(RETRY_ATTEMPT_KEY, 0)
                .remove(RETRY_AT_KEY)
                .commit()
            cancelScheduledRetry()
            DebugLog.applyWatermark(journal)
            CrashHandler.applyCrashWatermark(crashes)
            DebugLog.user("activity log sent and cleared (seq=$seq, ${gz.size / 1024} KB gz)")
        } else {
            DebugLog.error(
                "log upload failed — nothing cleared (seq=$seq, status=${result.status ?: "none"}, " +
                    "err=${result.error ?: "-"}); retrying"
            )
            if (DebugLog.totalJournalBytes() > MAX_RETAINED_BYTES) {
                DebugLog.pruneOldest(PRUNE_TO_BYTES)
            }
            if (!crashBounded) scheduleRetry(context, prefs, result.retryAfterMs ?: 0L)
        }
        return result.ok
    }

    private fun defaultLogDate(journal: List<DebugLog.JournalSnap>): String =
        journal.lastOrNull()?.file?.name
            ?.removePrefix("app-")?.removeSuffix(".txt")
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun markPending(
        context: Context,
        prefs: android.content.SharedPreferences,
        seq: Long = 0L,
        logDate: String? = null
    ) {
        val edit = prefs.edit().putBoolean(PENDING_ACTIVE_KEY, true)
        if (seq > 0L) edit.putLong(PENDING_SEQ_KEY, seq)
        if (!logDate.isNullOrBlank()) edit.putString(PENDING_DATE_KEY, logDate)
        edit.commit()
    }

    private fun clearPending(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putBoolean(PENDING_ACTIVE_KEY, false)
            .remove(PENDING_SEQ_KEY)
            .remove(PENDING_DATE_KEY)
            .putInt(RETRY_ATTEMPT_KEY, 0)
            .remove(RETRY_AT_KEY)
            .commit()
        cancelScheduledRetry()
    }

    private fun backoffFor(attempt: Int): Long =
        RETRY_BACKOFF_MS[attempt.coerceIn(0, RETRY_BACKOFF_MS.size - 1)]

    /** Schedules the next pending retry. A server Retry-After can only make
     *  the wait LONGER; the local ladder still grows on repeated failures. */
    private fun scheduleRetry(
        context: Context,
        prefs: android.content.SharedPreferences,
        serverDelayMs: Long
    ) {
        val attempt = prefs.getInt(RETRY_ATTEMPT_KEY, 0)
        val delay = maxOf(backoffFor(attempt), serverDelayMs.coerceAtLeast(0L))
        prefs.edit()
            .putInt(RETRY_ATTEMPT_KEY, attempt + 1)
            .putLong(RETRY_AT_KEY, System.currentTimeMillis() + delay)
            .commit()
        val next = retryExecutor.schedule(
            {
                submit { uploadOnce(context, enforceDailyGate = false, crashBounded = false) }
            },
            delay,
            TimeUnit.MILLISECONDS
        )
        scheduledRetry.getAndSet(next)?.cancel(false)
    }

    private fun cancelScheduledRetry() {
        scheduledRetry.getAndSet(null)?.cancel(false)
    }

    /** Debug-only, called by the debug source-set receiver: trigger a cycle
     *  now (bypassing the daily gate) without waiting for a trigger. */
    internal fun debugUploadNow(context: Context) {
        submit { uploadOnce(context, enforceDailyGate = false, crashBounded = false) }
    }

    /** Debug-only, no secrets: pending/seq/retry state for proof logs. */
    internal fun debugStatus(context: Context): String = try {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        "pending=${p.getBoolean(PENDING_ACTIVE_KEY, false)} " +
            "ackedSeq=${p.getLong(SEQ_KEY, 0L)} " +
            "pendingSeq=${p.getLong(PENDING_SEQ_KEY, 0L)} " +
            "attempt=${p.getInt(RETRY_ATTEMPT_KEY, 0)} " +
            "retryAt=${p.getLong(RETRY_AT_KEY, 0L)}"
    } catch (_: Throwable) {
        "pending=unknown"
    }

    private fun hasInternet(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        false
    }

    /** Identical redaction contract to the manual share: token params blanked. */
    private fun redact(line: String): String = line.replace(
        Regex("""[?&](token|download_token|pt|expiry|expires)=[^\s&]+""")
    ) { m -> "${m.value.substringBefore('=')}=***REDACTED***" }

    /**
     * Builds the gz snapshot: identity header first, then every journaled
     * file bounded to its snapshotted length (exactly the watermark tail —
     * content appended during this build belongs to the next cycle by
     * construction), then the crash-report files captured in [crashes]
     * (tail-capped like the manual share). Frozen byte ranges throughout, so
     * the writer thread appending concurrently can never leak into a later
     * truncation.
     */
    private fun buildGz(
        context: Context,
        seq: Long,
        logDate: String,
        journal: List<DebugLog.JournalSnap>,
        crashes: List<CrashHandler.CrashSnap>
    ): ByteArray? = try {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(identityLine(context, seq, logDate))
            w.write("\n")
            for (snap in journal) {
                w.write("===== ${snap.file.name} =====\n")
                boundedLines(snap.file, snap.length).forEach { line ->
                    w.write(redact(line)); w.write("\n")
                }
                w.write("\n")
            }
            if (crashes.isNotEmpty()) {
                val crashTxt = CrashHandler.crashReportsTextFor(crashes)
                if (crashTxt.isNotBlank()) {
                    w.write("\n===== CRASH REPORTS =====\n")
                    crashTxt.lineSequence().forEach { line -> w.write(redact(line)); w.write("\n") }
                }
            }
        }
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun identityLine(context: Context, seq: Long, logDate: String): String {
        val sent = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
        return buildString {
            append("# anon-log v2 | id=").append(RemoteLogger.clientIdForUpload(context))
            append(" | seq=").append(seq)
            append(" | build=").append(BuildConfig.VERSION_NAME)
                .append('(').append(BuildConfig.VERSION_CODE).append(')')
            append(" | device=").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL)
            append(" | android=").append(android.os.Build.VERSION.SDK_INT)
                .append('/').append(android.os.Build.VERSION.RELEASE)
            append(" | upto=").append(logDate)
            append(" | sent=").append(sent)
        }
    }

    /** Reads EXACTLY [length] bytes of [f] as lines — the snapshot contract. */
    private fun boundedLines(f: File, length: Long): Sequence<String> {
        val raw = f.inputStream().buffered()
        val limited = object : InputStream() {
            private var remaining = length
            override fun read(): Int {
                if (remaining <= 0) return -1
                val b = raw.read()
                if (b >= 0) remaining--
                return b
            }
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = raw.read(buf, off, minOf(len.toLong(), remaining).toInt())
                if (n > 0) remaining -= n
                return n
            }
            override fun close() = raw.close()
        }
        return limited.bufferedReader(Charsets.UTF_8).lineSequence()
    }

    private data class PostResult(
        val ok: Boolean,
        val status: Int?,
        val retryAfterMs: Long?,
        val error: String?
    )

    private fun post(
        context: Context,
        origin: String,
        gz: ByteArray,
        logDate: String,
        seq: Long,
        shortTimeouts: Boolean
    ): PostResult {
        if (debugForceFailOnce) {
            debugForceFailOnce = false
            return PostResult(false, null, null, "debug_forced")
        }
        val conn = try {
            URL("$origin/api/v1/telemetry/full-log").openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return PostResult(false, null, null, t.javaClass.simpleName)
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = if (shortTimeouts) 6_000 else 15_000
            conn.readTimeout = if (shortTimeouts) 12_000 else 30_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(gz.size)
            conn.setRequestProperty("Content-Type", "application/gzip")
            conn.setRequestProperty("X-Client-Id", RemoteLogger.clientIdForUpload(context))
            conn.setRequestProperty("X-Log-Date", logDate)
            conn.setRequestProperty("X-Log-Seq", seq.toString())
            conn.setRequestProperty(
                "X-Log-Build",
                "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            )
            conn.setRequestProperty("User-Agent", "AnonDownloader/${BuildConfig.VERSION_NAME}")
            conn.outputStream.use { os ->
                os.write(gz)
                os.flush()
            }
            val code = conn.responseCode
            val retryAfter = parseRetryAfterMs(conn.getHeaderField("Retry-After"))
            PostResult(code in 200..299, code, retryAfter, null)
        } catch (t: Throwable) {
            PostResult(false, null, null, t.javaClass.simpleName)
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Retry-After is seconds on this endpoint; tolerate an HTTP-date value
     *  by falling back to the local ladder. */
    private fun parseRetryAfterMs(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val seconds = text.toLongOrNull() ?: return null
        return (seconds * 1000L).coerceIn(0L, 24L * 60L * 60L * 1000L)
    }
}
