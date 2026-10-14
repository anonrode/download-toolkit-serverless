package com.anonrode.downloader.util

import android.content.Context
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Fatal-only remote error reporting (Track 6 of the pre-launch plan).
 *
 * Discipline, fixed in the pre-launch decision: this is NOT an activity
 * logger. Exactly two event classes ever leave the device —
 *   1. a fatal engine crash (uncaught exception, hooked from [CrashHandler]), and
 *   2. a stall-watchdog timeout dump (a task the engine gave up on, hooked
 *      from DownloadEngine's MAX_STALL_KILLS give-up path), the only two
 *      states that previously required the user to manually collect and send
 *      activity-log text files.
 *
 * Budget rules enforced here, not at call sites:
 *  - wire payload hard-capped at ~4 KB; context strings are truncated
 *    per-field and the stack truncates first when the wire still overflows;
 *  - one HTTP attempt, 10s timeouts, no retry queue — an unreachable sink
 *    costs nothing and is never retried;
 *  - the endpoint comes from OTA rules ("remote_logging_url" in
 *    scraper_rules.json) read FRESH per report, so relocating the sink
 *    redirects every client with zero app rebuilds; blank/missing URL or
 *    any transport error is a silent no-op — reporting must never affect
 *    the app;
 *  - crash reports are sent synchronously because the process dies when the
 *    uncaught-exception handler returns; watchdog dumps go through a single
 *    daemon thread and are fire-and-forget.
 *
 * Confidentiality: bodies are AES-128-CBC encrypted with the OTA rules key
 * (the one that already ships in the APK for scraper_rules) and a fresh
 * random IV per report, so a server operator, datacenter tech, or anyone
 * sniffing the wire reads ciphertext, not user diagnostics. The server is a
 * DUMB SINK — it stores the opaque envelope and can never see inside. Only
 * holders of the key (us) can read reports back, via
 * scripts/read_client_errors.py. This is the same obfuscation-grade
 * protection as the OTA rules themselves: it stops reading, not a
 * determined reverse engineer who extracts the key from the APK. */
object RemoteLogger {

    private const val MAX_PAYLOAD_BYTES = 4_096
    private const val MAX_MESSAGE_CHARS = 300
    private const val MAX_STACK_CHARS = 1_600
    private const val MAX_CONTEXT_VALUE_CHARS = 200
    private const val MAX_REPORTS_PER_SESSION = 24L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "remote-error-logger").apply { isDaemon = true }
    }
    private var appContext: Context? = null
    private val sessionReports = AtomicLong(0)

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private const val PREF_REMOTE_REPORTING = "pref_remote_reporting"

    /** User kill-switch surfaced in Settings → Updates & Reliability. Default
     *  ON (the payload is fatal-only, encrypted and ≤4 KB by construction) —
     *  the toggle exists so a private-minded user can end it in one tap. */
    fun isEnabled(context: Context): Boolean = try {
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_REMOTE_REPORTING, true)
    } catch (_: Exception) {
        true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_REMOTE_REPORTING, enabled).apply()
        } catch (_: Exception) {}
    }

    /** Stable, meaningless per-install id: generated locally on first report,
     *  stored in app-private prefs. Deliberately NOT any device identifier —
     *  the server's rate buckets need a grouping key, not an identity, and a
     *  identifier-free id keeps the encrypted reports anonymous to everyone
     *  who cannot decrypt them. */
    private fun clientId(): String {
        val ctx = appContext ?: return "unknown"
        return try {
            val prefs = ctx.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            prefs.getString("pref_diagnostics_client_id", null) ?: run {
                val fresh = UUID.randomUUID().toString()
                prefs.edit().putString("pref_diagnostics_client_id", fresh).apply()
                fresh
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    /** The OTA-configured sink; an http(s) URL only (a malformed payload
     *  value must never reach URL.openConnection as "ws://"/"file://"/junk). */
    private fun sinkUrl(): String? {
        val url = DynamicRulesManager.getRemoteLoggingUrl()
        return if (url.startsWith("https://") || url.startsWith("http://")) url else null
    }

    /** Origin of the OTA-configured sink (scheme + host) — the base for the
     *  sibling endpoints on the same server, e.g. the full-log upload. */
    internal fun sinkOrigin(): String? = sinkUrl()?.let {
        try {
            val u = java.net.URL(it)
            "${u.protocol}://${u.host}${if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""}"
        } catch (_: Exception) { null }
    }

    /** Same anonymous per-install id the fatal reports use (server-side rate
     *  buckets / full-log folder naming). */
    internal fun clientIdForUpload(context: Context): String = clientId()

    fun report(
        type: String,
        message: String,
        context: Map<String, Any?> = emptyMap(),
        stackTrace: String? = null,
        waitForDelivery: Boolean = false
    ) {
        // Session cap: a pathological crash loop or a stall storm must not
        // stream data; 24 ~4 KB reports ≈ 96 KB worst case, then silence.
        if (sessionReports.incrementAndGet() > MAX_REPORTS_PER_SESSION) return

        val dispatch: () -> Unit = {
            try {
                dispatchNow(type, message, context, stackTrace)
            } catch (_: Exception) {
                // Reporting failing (no network, bad sink) must be invisible.
            }
        }
        try {
            if (waitForDelivery) {
                dispatch()
            } else {
                executor.execute(dispatch)
            }
        } catch (_: Exception) {}
    }

    private fun dispatchNow(
        type: String,
        message: String,
        context: Map<String, Any?>,
        stackTrace: String?
    ) {
        val endpoint = sinkUrl() ?: return
        val ctx = appContext ?: return
        if (!isEnabled(ctx)) return

        val plain = buildPlainJson(type, message, context, stackTrace) ?: return
        val wire = encryptEnvelope(plain)
        val wireBytes = wire.toByteArray(Charsets.UTF_8)
        if (wireBytes.size > MAX_PAYLOAD_BYTES) {
            // Only reachable on absurd stack lengths; drop the stack and try
            // once more rather than abandoning the report.
            val trimmed = buildPlainJson(type, message, context, null) ?: return
            val slim = encryptEnvelope(trimmed)
            if (slim.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return
            postWire(endpoint, slim)
        } else {
            postWire(endpoint, wire)
        }
    }

    /** Plain diagnostics JSON — everything here is inside the cipher. */
    private fun buildPlainJson(
        type: String,
        message: String,
        context: Map<String, Any?>,
        stackTrace: String?
    ): String {
        val payload = JSONObject()
        payload.put("timestamp", System.currentTimeMillis() / 1000.0)
        payload.put("client_id", clientId())
        payload.put("client_version", BuildConfig.VERSION_NAME)
        payload.put("app_version_code", BuildConfig.VERSION_CODE)
        payload.put("error_type", type.take(120))
        payload.put("message", message.take(MAX_MESSAGE_CHARS))
        if (!stackTrace.isNullOrBlank()) {
            payload.put("stack_trace", stackTrace.take(MAX_STACK_CHARS))
        }
        if (context.isNotEmpty()) {
            val ctx = JSONObject()
            for ((key, value) in context) {
                if (value == null) continue
                ctx.put(key.take(60), truncateValue(value))
            }
            payload.put("context", ctx)
        }
        return payload.toString()
    }

    /** Fresh random IV per envelope (fixed-IV CBC would leak equalities
     *  between reports — the exact mistake the OTA pipeline retired). */
    private fun encryptEnvelope(plain: String): String {
        val iv = Random.Default.nextBytes(ByteArray(16))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(DynamicRulesManager.telemetryCipherKey(), "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val envelope = JSONObject()
        envelope.put("v", 1)
        envelope.put("alg", "aes-128-cbc")
        envelope.put("iv", iv.joinToString("") { "%02x".format(it) })
        envelope.put("payload", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
        return envelope.toString()
    }

    private fun postWire(endpoint: String, wire: String) {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "AnonDownloader/${BuildConfig.VERSION_NAME}")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(wire)
            }
            // Ignored beyond draining the response in one bounded read.
            conn.responseCode
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Strings/numbers/booleans pass through (truncated); anything else is
     *  coerced to its toString so a lazy caller can never blow the budget. */
    private fun truncateValue(value: Any): Any = when (value) {
        is Boolean -> value
        is Int, is Long -> value
        is Float, is Double -> value
        else -> value.toString().take(MAX_CONTEXT_VALUE_CHARS)
    }
}

