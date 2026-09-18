package com.anonrode.downloader.pipeline

import android.content.Context
import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.json.JSONObject
import java.io.File

/**
 * Persistent per-host health ledger.
 *
 * The app used to rediscover dead hosts from scratch every session (the
 * jisooido/jiminido lesson) and hammered sites that had already refused us.
 * This ledger remembers success rates per host with an exponential backoff
 * window, seeded by the playbook's knownDead list so fresh installs skip
 * known corpses instantly. Data stays on-device — nothing is uploaded.
 */
object HostHealth {

    private data class Rec(
        var ok: Long = 0,
        var fail: Long = 0,
        var consecutiveFails: Int = 0,
        var lastOkMs: Long = 0,
        var lastFailMs: Long = 0,
        var rate429: Int = 0,
        var lastReason: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ok", ok).put("fail", fail)
            .put("consecFail", consecutiveFails)
            .put("lastOk", lastOkMs).put("lastFail", lastFailMs)
            .put("rate429", rate429)
            .apply { if (lastReason != null) put("lastReason", lastReason) }

        companion object {
            fun from(o: JSONObject) = Rec(
                ok = o.optLong("ok"), fail = o.optLong("fail"),
                consecutiveFails = o.optInt("consecFail"),
                lastOkMs = o.optLong("lastOk"), lastFailMs = o.optLong("lastFail"),
                rate429 = o.optInt("rate429"),
                lastReason = o.optString("lastReason").takeIf { it.isNotBlank() }
            )
        }
    }

    private const val FILE_NAME = "host_health.json"
    private val records = java.util.concurrent.ConcurrentHashMap<String, Rec>()

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        try {
            val f = file ?: return
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                records[k] = Rec.from(obj.optJSONObject(k) ?: continue)
            }
        } catch (_: Exception) {
            // Corrupt ledger -> start empty; health rebuilds as downloads run.
            records.clear()
        }
    }

    private fun hostOf(urlOrHost: String): String = try {
        val u = urlOrHost.trim()
        if (u.contains("://"))
            java.net.URI(u.substringBefore('#')).host?.lowercase() ?: u.lowercase()
        else u.lowercase()
    } catch (_: Exception) {
        urlOrHost.lowercase().substringBefore('/')
    }

    /** Exponential backoff window after consecutive failures:
     *  30s, 1m, 2m — capped at 2m.
     *
     *  The old 1h cap was a disaster with a queue (the 2026-09-02 Suits
     *  batch): 18 staggered tasks all resolve through the SAME locker host,
     *  every real request timeout bumped consecutiveFails again, and the
     *  window ratcheted 15m → 31m → 59m while tasks retried every 10s. To
     *  the user a 1h park is indistinguishable from a dead host. Two minutes
     *  still rides out a transient outage and lets a parked batch visibly
     *  recover. */
    private fun backoffWindowMs(consecutiveFails: Int): Long =
        if (consecutiveFails <= 0) 0L
        else minOf(30_000L shl (consecutiveFails - 1).coerceAtMost(2), 120_000L)

    fun recordOk(hostOrUrl: String, latencyMs: Long = 0) {
        val h = hostOf(hostOrUrl)
        if (h.isBlank()) return
        records.compute(h) { _, v -> (v ?: Rec()).apply {
            ok++; consecutiveFails = 0; lastOkMs = System.currentTimeMillis()
        } }
        persist()
    }

    /**
     * Auto-clears a host's "dead" state on the first sign of life. Distinct
     * from [recordOk] so it can be called BEFORE the response is read (just
     * a 2xx status code is enough — the 50%+ false positive rate on hosts
     * like vdl.np-downloader.com, where the prior 60s backoff blocked the
     * next attempt that would have succeeded, all came from skipping this
     * clear-on-2xx path). The 2xx must be on a request that actually reached
     * the host's response handler — connection-timeout and DNS failures do
     * not produce a 2xx and so do not qualify.
     *
     * Concurrent recordOk() calls are still safe: this method only shortens
     * consecutiveFails and updates lastOkMs; recordOk is the canonical writer
     * for the full Rec state.
     */
    fun clearIfAlive(hostOrUrl: String) {
        val h = hostOf(hostOrUrl)
        if (h.isBlank()) return
        records.compute(h) { _, v -> (v ?: return@compute v).apply {
            if (consecutiveFails > 0) {
                consecutiveFails = 0
            }
            lastOkMs = System.currentTimeMillis()
        } }
        persist()
    }

    fun recordFail(hostOrUrl: String, rateLimited: Boolean = false, reason: String? = null) {
        val h = hostOf(hostOrUrl)
        if (h.isBlank()) return
        // A USER-INITIATED cancellation (search typing, task pause) surfaces
        // as an IOException: Canceled. That is NOT a host failure — recording
        // it poisoned nepu.gd with a 60s backoff every time the user typed
        // fast in search (live-verified). The reason comes from the CALLER
        // (the resolver's per-attempt failure), never the global
        // HttpClient.lastFailure, which is stale for resolvers with their own
        // OkHttp clients and would misattribute both ways.
        val failure = reason ?: ""
        if (failure.contains("Canceled", ignoreCase = true) ||
            failure.contains("CancellationException", ignoreCase = true) ||
            failure.contains("abort", ignoreCase = true)) {
            return
        }
        // A TIMEOUT is not proof of death: a loaded locker under a burst of 18
        // queued tasks will time out one request while serving the next one
        // fine (the 2026-09-02 Suits disaster — loadedfiles.net completed
        // downloads minutes before and after the "dead" verdict). If the host
        // has served us within the last 10 minutes, a timeout only PAUSES the
        // backoff clock; it must never ratchet consecutiveFails up, or one
        // busy period flags the whole host dead and mass-parks the queue.
        val isTimeout = failure.contains("timeout", ignoreCase = true) ||
            failure.contains("timed out", ignoreCase = true)
        records.compute(h) { _, v -> (v ?: Rec()).apply {
            fail++
            if (rateLimited) rate429++
            if (reason != null && reason.isNotBlank()) {
                lastReason = reason
            }
            val provenRecently = lastOkMs > 0 &&
                System.currentTimeMillis() - lastOkMs < 10 * 60_000L
            if (!(isTimeout && provenRecently)) {
                consecutiveFails = (consecutiveFails + 1).coerceAtMost(20)
            }
            lastFailMs = System.currentTimeMillis()
        } }
        persist()
    }

    /** Last recorded failure reason for this host, if any. */
    fun lastReason(urlOrHost: String): String? = records[hostOf(urlOrHost)]?.lastReason

    /** False when this URL's host is playbook-known-dead or currently inside
     *  its backoff window. Callers skip the host WITHOUT burning a request. */
    fun isUsable(urlOrHost: String): Boolean {
        if (DynamicRulesManager.isKnownDead(urlOrHost)) return false
        val r = records[hostOf(urlOrHost)] ?: return true
        val sinceLastFail = System.currentTimeMillis() - r.lastFailMs
        // Backoff only after >= 3 CONSECUTIVE hard failures: a single hiccup
        // (one 404, one timeout) must not gate a host for 30s+ — search
        // cancellations and flaky single requests used to kill hosts
        // (live-verified: nepu.gd backoff after fast search typing).
        if (r.consecutiveFails < 3) return true
        return sinceLastFail >= backoffWindowMs(r.consecutiveFails)
    }

    /** Milliseconds until [isUsable] becomes true again — 0 when the host is
     *  usable or has no record. Powers the cooldown-parking message and its
     *  auto-retry loop (the engine parks a task while this is > 0). */
    fun remainingBackoffMs(urlOrHost: String): Long {
        val r = records[hostOf(urlOrHost)] ?: return 0L
        if (r.consecutiveFails < 3) return 0L
        val elapsed = System.currentTimeMillis() - r.lastFailMs
        val window = backoffWindowMs(r.consecutiveFails)
        return maxOf(0L, window - elapsed)
    }

    /** True when this host has successfully served at least one stream —
     *  evidence-based proof it is a working locker. The playbook seeds known
     *  hosts, but any host that proves itself in the field is treated as
     *  known from then on (LockerRegistry.classify), no OTA needed. */
    fun hasProvenLocker(hostOrUrl: String): Boolean =
        (records[hostOf(hostOrUrl)]?.ok ?: 0L) >= 1L

    /** Debug summary for the activity log (bounded to the hottest entries). */
    fun snapshotForLog(limit: Int = 12): String {
        return records.entries.take(limit).joinToString(", ") { (k, v) ->
            "$k(ok=${v.ok},fail=${v.fail},429=${v.rate429})"
        }
    }

    private fun persist() {
        try {
            val f = file ?: return
            val obj = JSONObject()
            records.entries.take(500).forEach { (k, v) -> obj.put(k, v.toJson()) }
            f.writeText(obj.toString())
        } catch (_: Exception) {}
    }
}
