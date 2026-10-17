package com.anonrode.downloader.util

import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.net.HttpClient
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * OTA fetch diagnosability (Phase 0): every remote rules/update fetch records
 * WHERE it died — DNS / TCP / TLS / HTTP status / parse / signature verify —
 * plus elapsed ms, resolved IPs (when obtainable), and the winning source.
 *
 * Why this exists: app v3.2.9 (log share-31) showed only "could not reach
 * GitHub", with `HttpClient.lastFailure` holding the real cause that nothing
 * ever read. Two channels record events now:
 *
 *  1. DebugLog (local journal) — EVERY attempt, immediately.
 *  2. RemoteLogger (the /api/logs/client-error sink carried inside the OTA
 *     rules) — one aggregate event per TOTAL sync failure with the per-source
 *     breakdown. NOTE the chicken-and-egg: a device that never received OTA
 *     rules has a BLANK sink URL and RemoteLogger is a silent no-op — exactly
 *     the blocked device this diagnostics is for. So when the OTA sink is
 *     blank, events are ALSO posted directly to [FALLBACK_SINK] (the same box,
 *     same endpoint, same AES envelope RemoteLogger posts — ciphertext the
 *     server cannot read; scripts/read_client_errors.py decrypts it). The
 *     direct fallback is hard-capped per session: it must never turn a
 *     deliverability problem into a data-usage problem.
 *
 * Stage classification is message/class-based over the SAME exception OkHttp
 * throws at each phase of the connect lifecycle, so no network code had to
 * change to observe it.
 */
object OtaFetchDiagnostics {

    /** Hardcoded last-resort telemetry sink: same box that already serves the
     *  log/telemetry pipeline (see scripts/read_client_errors.py). Used ONLY
     *  when the OTA-configured sink is blank (no rules ever arrived). */
    const val FALLBACK_SINK = "http://68.155.146.145/api/logs/client-error"

    // Stage constants (stable wire strings — server-side dashboards key on them).
    const val STAGE_DNS = "DNS"
    const val STAGE_TCP = "TCP"
    const val STAGE_TLS = "TLS"
    const val STAGE_HTTP = "HTTP_STATUS"
    const val STAGE_PARSE = "PARSE"
    const val STAGE_VERIFY = "VERIFY"
    const val STAGE_UNKNOWN = "OTHER"

    /** Max direct-to-FALLBACK_SINK posts per process session. */
    private const val MAX_FALLBACK_SINK_POSTS = 3L
    /** Min spacing between direct fallback posts (burst guard). */
    private const val FALLBACK_SINK_MIN_INTERVAL_MS = 30_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ota-fetch-diagnostics").apply { isDaemon = true }
    }
    private val fallbackPosts = AtomicLong(0)
    @Volatile private var lastFallbackPostMs = 0L

    /** One remote fetch attempt against one source. */
    data class Attempt(
        val source: String,       // short label, e.g. "raw.githubusercontent"
        val url: String,          // full URL as fetched (cache-buster included)
        val stage: String,        // STAGE_* — the phase that failed
        val errorClass: String,   // exception class, or "" when only a code is known
        val detail: String,       // exception message / HTTP code line (truncated)
        val elapsedMs: Long,
        val httpCode: Int? = null // when the failure was an HTTP status
    )

    /** Maps a transport exception phase onto a stable stage string. Pure. */
    fun classify(e: Throwable): String {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            when (t) {
                is java.net.UnknownHostException -> return STAGE_DNS
                is java.net.ConnectException -> return STAGE_TCP
                is java.net.SocketTimeoutException -> {
                    // Socket-level stall, connect or read phase — the message
                    // distinguishes them ("connect timed out" vs "Read timed
                    // out") for the DebugLog detail; for stage purposes both
                    // are TCP-layer failures, before or after HTTP status.
                    return STAGE_TCP
                }
                is javax.net.ssl.SSLException,
                is javax.net.ssl.SSLPeerUnverifiedException -> return STAGE_TLS
                else -> {
                    val msg = t.message?.lowercase() ?: ""
                    if (msg.startsWith("http ")) return STAGE_HTTP
                    if (msg.contains("cleartext not permitted") || msg.contains("cleartext")) return STAGE_TLS
                    if (msg.contains("unable to resolve host")) return STAGE_DNS
                    if (msg.contains("network is unreachable") || msg.contains("connection reset") ||
                        msg.contains("connection refused") || msg.contains("software caused connection")
                    ) return STAGE_TCP
                }
            }
            t = t.cause
            depth++
        }
        return STAGE_UNKNOWN
    }

    /** Extracts the HTTP status from an IOException("HTTP 403 for ...")
     *  thrown by HttpClient.getText, when present. Pure. */
    fun httpCodeOf(e: Throwable): Int? {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val msg = t.message ?: ""
            if (msg.startsWith("HTTP ")) {
                msg.split(" ").getOrNull(1)?.toIntOrNull()?.let { return it }
            }
            t = t.cause
            depth++
        }
        return null
    }

    /** User-facing stage fragment for toasts ("DNS", "connect", "TLS",
     *  "HTTP 403"). Kept short: it is embedded in the sync-failure toast. */
    fun friendlyStage(stage: String, httpCode: Int? = null): String = when (stage) {
        STAGE_DNS -> "DNS"
        STAGE_TCP -> "connect"
        STAGE_TLS -> "TLS"
        STAGE_HTTP -> "HTTP ${httpCode ?: '?'}"
        STAGE_PARSE -> "payload invalid"
        STAGE_VERIFY -> "signature/decrypt"
        else -> "network"
    }

    /** Local-journal record of one attempt. Never throws, never blocks. */
    fun noteAttempt(source: String, url: String, stage: String, errorClass: String, detail: String, elapsedMs: Long, httpCode: Int? = null) {
        try {
            DebugLog.net(
                "OTA[${source}] ${stage}" +
                    (httpCode?.let { " HTTP $it" } ?: "") +
                    " ${errorClass.ifBlank { "-" }} ${detail.take(160)} after ${elapsedMs}ms ${url.take(140)}"
            )
        } catch (_: Exception) {}
    }

    /** DNS truth for a hostname through the exact client pipeline the fetch
     *  used (system-DNS race + DoH fallback + SSRF filter), for failure
     *  evidence: which IPs the carrier handed us and whether DoH answered.
     *  Bounded by BoundedDnsScheduler (3s) + bootstrap DoH (4s); safe to call
     *  on failure paths only. */
    fun snapshotDns(hostname: String): Pair<String?, Boolean> {
        return try {
            val snap = HttpClient.otaDnsSnapshot(hostname)
            Pair(snap.resolved, snap.dohEntry)
        } catch (_: Exception) {
            Pair(null, false)
        }
    }

    /**
     * Aggregate telemetry for a TOTAL sync failure (all sources exhausted).
     * Posts one encrypted event to the OTA-configured client-error sink via
     * [RemoteLogger]; when that sink is blank (the never-got-rules case)
     * additionally posts the SAME envelope straight to [FALLBACK_SINK],
     * session-capped and rate-limited. Fire-and-forget; never throws.
     *
     * Event shape inside the cipher (scripts/read_client_errors.py sees it):
     *   error_type: "ota_delivery_failure"
     *   message:    human line naming the last failing stage
     *   context:    { kind, sources_tried, last_stage, last_http_code,
     *                 last_error_class, last_error_detail, total_ms,
     *                 per_source: "label=stage(code)@Nms | ...",
     *                 last_dns_ips, last_dns_doh, app_version }
     */
    fun reportTotalFailure(
        kind: String,
        attempts: List<Attempt>,
        totalMs: Long,
        dnsEvidenceHost: String? = null
    ) {
        if (attempts.isEmpty()) return
        val last = attempts.last()
        val perSource = attempts.joinToString("|") { a ->
            "${a.source}=${a.stage}${a.httpCode?.let { "($it)" } ?: ""}@${a.elapsedMs}ms"
        }
        var dnsIps: String? = null
        var dnsDoh: Boolean? = null
        if (dnsEvidenceHost != null) {
            val (ips, doh) = snapshotDns(dnsEvidenceHost)
            dnsIps = ips
            dnsDoh = doh
        }
        val stageName = friendlyStage(last.stage, last.httpCode)
        val message = "Could not reach $kind ($stageName) after ${attempts.size} source(s)"

        try {
            DebugLog.error("OTA DIAGNOSTICS $kind: $message; per_source=$perSource; dns=$dnsIps doh=$dnsDoh [${last.detail.take(200)}]")
        } catch (_: Exception) {}

        val context = mapOf<String, Any?>(
            "kind" to kind,
            "sources_tried" to attempts.size,
            "last_stage" to last.stage,
            "last_http_code" to last.httpCode,
            "last_error_class" to last.errorClass,
            "last_error_detail" to last.detail.take(200),
            "total_ms" to totalMs,
            "per_source" to perSource,
            "last_dns_ips" to dnsIps,
            "last_dns_doh" to dnsDoh,
            "app_version" to BuildConfig.VERSION_NAME
        )

        val otaSinkAlive = try {
            com.anonrode.downloader.data.rules.DynamicRulesManager.getRemoteLoggingUrl().isNotBlank()
        } catch (_: Exception) { false }

        if (otaSinkAlive) {
            // Normal path: the existing encrypted client-error pipeline.
            try {
                RemoteLogger.report(
                    type = "ota_delivery_failure",
                    message = message,
                    context = context
                )
            } catch (_: Exception) {}
        } else {
            // The blocked device case: no OTA rules → no sink configured.
            // Post the same AES envelope directly, capped.
            postFallbackDirect(message, context)
        }
    }

    /** One aggregate telemetry event for a SUCCESSFUL sync (delivery-chain
     *  evidence: which mirror carries the fleet). Routed only through
     *  RemoteLogger when the OTA sink exists — never the direct fallback,
     *  a success needs no emergency circuit. */
    fun reportSuccess(kind: String, source: String, version: String, elapsedMs: Long, tried: Int) {
        try {
            DebugLog.net("OTA DIAGNOSTICS $kind OK source=$source ver=$version after=${elapsedMs}ms tried=$tried")
        } catch (_: Exception) {}
        val sinkAlive = try {
            com.anonrode.downloader.data.rules.DynamicRulesManager.getRemoteLoggingUrl().isNotBlank()
        } catch (_: Exception) { false }
        if (!sinkAlive) return
        try {
            RemoteLogger.report(
                type = "ota_delivery_success",
                message = "$kind delivered via $source (tried $tried source(s))",
                context = mapOf(
                    "kind" to kind,
                    "source" to source,
                    "version" to version,
                    "elapsed_ms" to elapsedMs,
                    "sources_tried" to tried
                )
            )
        } catch (_: Exception) {}
    }

    /** Mirrors RemoteLogger's envelope format byte-for-byte ({"v":1,...} AES-
     *  128-CBC with fresh IV, key = the OTA rules key) so the same reader
     *  script decrypts both, then POSTs it to [FALLBACK_SINK]. Serializable
     *  evidence: THIS is the only telemetry path left when GitHub is fully
     *  unreachable on the device. */
    private fun postFallbackDirect(message: String, context: Map<String, Any?>) {
        try {
            val n = fallbackPosts.incrementAndGet()
            if (n > MAX_FALLBACK_SINK_POSTS) return
            val now = System.currentTimeMillis()
            if (now - lastFallbackPostMs < FALLBACK_SINK_MIN_INTERVAL_MS) return

            val plain = JSONObject().apply {
                put("timestamp", now / 1000.0)
                put("client_id", "ota-diag")
                put("client_version", BuildConfig.VERSION_NAME)
                put("app_version_code", BuildConfig.VERSION_CODE)
                put("error_type", "ota_delivery_failure")
                put("message", message.take(300))
                val ctx = JSONObject()
                for ((k, v) in context) {
                    if (v == null) continue
                    ctx.put(k.take(60), v.toString().take(200))
                }
                put("context", ctx)
            }.toString()

            val key = com.anonrode.downloader.data.rules.DynamicRulesManager.telemetryCipherKey()
            val iv = Random.Default.nextBytes(ByteArray(16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val envelope = JSONObject().apply {
                put("v", 1)
                put("alg", "aes-128-cbc")
                put("iv", iv.joinToString("") { "%02x".format(it) })
                put("payload", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
            }.toString()

            lastFallbackPostMs = now
            executor.execute {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(FALLBACK_SINK).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("User-Agent", "AnonDownloader/${BuildConfig.VERSION_NAME}")
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w -> w.write(envelope) }
                    DebugLog.net("OTA DIAGNOSTICS posted fallback report (code ${conn.responseCode})")
                } catch (_: Exception) {
                    // The sink being unreachable too is itself evidence, but
                    // diagnostics must never become a crash source.
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }
}
