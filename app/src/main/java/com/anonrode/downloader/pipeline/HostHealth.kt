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
        var rate429: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ok", ok).put("fail", fail)
            .put("consecFail", consecutiveFails)
            .put("lastOk", lastOkMs).put("lastFail", lastFailMs)
            .put("rate429", rate429)

        companion object {
            fun from(o: JSONObject) = Rec(
                ok = o.optLong("ok"), fail = o.optLong("fail"),
                consecutiveFails = o.optInt("consecFail"),
                lastOkMs = o.optLong("lastOk"), lastFailMs = o.optLong("lastFail"),
                rate429 = o.optInt("rate429")
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
     *  30s, 1m, 2m, 4m ... capped at 1h. */
    private fun backoffWindowMs(consecutiveFails: Int): Long =
        if (consecutiveFails <= 0) 0L
        else minOf(30_000L shl (consecutiveFails - 1).coerceAtMost(11), 3_600_000L)

    fun recordOk(hostOrUrl: String, latencyMs: Long = 0) {
        val h = hostOf(hostOrUrl)
        if (h.isBlank()) return
        records.compute(h) { _, v -> (v ?: Rec()).apply {
            ok++; consecutiveFails = 0; lastOkMs = System.currentTimeMillis()
        } }
        persist()
    }

    fun recordFail(hostOrUrl: String, rateLimited: Boolean = false) {
        val h = hostOf(hostOrUrl)
        if (h.isBlank()) return
        // A USER-INITIATED cancellation (search typing, task pause) surfaces
        // as an IOException: Canceled via HttpClient.lastFailure. That is NOT
        // a host failure — recording it poisoned nepu.gd with a 60s backoff
        // every time the user typed fast in search (live-verified).
        val lastFail = com.anonrode.downloader.data.net.HttpClient.lastFailure ?: ""
        if (lastFail.contains("Canceled", ignoreCase = true) ||
            lastFail.contains("CancellationException", ignoreCase = true) ||
            lastFail.contains("abort", ignoreCase = true)) {
            return
        }
        records.compute(h) { _, v -> (v ?: Rec()).apply {
            fail++
            if (rateLimited) rate429++
            consecutiveFails = (consecutiveFails + 1).coerceAtMost(20)
            lastFailMs = System.currentTimeMillis()
        } }
        persist()
    }

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
