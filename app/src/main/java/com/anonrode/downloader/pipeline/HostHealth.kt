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
        var lastRate429Ms: Long = 0,
        var lastReason: String? = null,
        // Millisecond timestamps cannot order two events in the same tick;
        // this flag records which one happened LAST (persisted so a restart
        // does not resurrect a rejection the host already answered).
        var lastEventOk: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ok", ok).put("fail", fail)
            .put("consecFail", consecutiveFails)
            .put("lastOk", lastOkMs).put("lastFail", lastFailMs)
            .put("rate429", rate429)
            .put("lastRate429", lastRate429Ms)
            .put("lastEventOk", lastEventOk)
            .apply { if (lastReason != null) put("lastReason", lastReason) }

        companion object {
            fun from(o: JSONObject) = Rec(
                ok = o.optLong("ok"), fail = o.optLong("fail"),
                consecutiveFails = o.optInt("consecFail"),
                lastOkMs = o.optLong("lastOk"), lastFailMs = o.optLong("lastFail"),
                rate429 = o.optInt("rate429"),
                lastRate429Ms = o.optLong("lastRate429"),
                lastReason = o.optString("lastReason").takeIf { it.isNotBlank() },
                // Legacy records carry only the timestamps.
                lastEventOk = o.optBoolean("lastEventOk", o.optLong("lastOk") > o.optLong("lastFail"))
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

    private val EXEMPT_HOST_SUFFIXES = listOf(
        "x.com", "twitter.com", "instagram.com", "tiktok.com",
        "youtube.com", "youtu.be", "facebook.com", "fb.watch",
        "reddit.com", "googlevideo.com", "google.com"
    )

    private fun isExemptHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return EXEMPT_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /** Compiled dead seeds: hosts confirmed dead from the field (dated
     *  evidence) and skipped WITHOUT burning a request. Distinct from the
     *  OTA knownDead list: the cold-start data.vidsrcme.ru wave
     *  (2026-10-18 activity-log audit §3.3 — ~30 ConnectExceptions per cold
     *  start on every fleet device, 213 failures logged) cannot wait for a
     *  payload rev, so the evidence ships in the APK. A seed stays for the
     *  life of this build by design — the host is TCP-refused fleet-wide and
     *  from desktop networks; removal takes a build. */
    private val COMPILED_DEAD_SEEDS = listOf(
        "data.vidsrcme.ru" // 2026-10-18 — dead since 10-17; host probes must never fire
    )

    private fun isCompiledDeadSeed(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return COMPILED_DEAD_SEEDS.any { h == it || h.endsWith(".$it") }
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
            ok++; consecutiveFails = 0; lastRate429Ms = 0L
            lastOkMs = System.currentTimeMillis()
            lastEventOk = true
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
        var changed = false
        records.compute(h) { _, v -> (v ?: return@compute v).apply {
            if (consecutiveFails > 0 || lastRate429Ms > 0L || !lastEventOk) {
                consecutiveFails = 0
                lastRate429Ms = 0L
                lastEventOk = true
                changed = true
            }
            lastOkMs = System.currentTimeMillis()
        } }
        if (changed) persist()
    }

    /**
     * Rules-rev state invalidation (Firefox Remote Settings semantics —
     * scratch/ota_rule_engine_design.md §3.7): a new OTA rules version
     * republishes known-dead lists and pipelines, so every parked/dead
     * backoff verdict in this ledger was formed against a DIFFERENT payload
     * than the one about to run. Such verdicts are stale by construction —
     * the share-31 massacre (2,189 fans-out pre-skipped by backoffs earned
     * under old rules) is the live proof. Drops only the backoff JUDGMENT;
     * ok/fail history (and [hasProvenLocker] credentials) and 429 cooldowns
     * are fresh evidence, not stale, so they stay. Called by
     * DynamicRulesManager on an actual version change at activation.
     *
     * @return count of hosts that had an active verdict cleared.
     */
    fun clearBackoffsOnNewRules(): Int {
        var cleared = 0
        for (h in records.keys) {
            records.compute(h) { _, v ->
                if (v == null) return@compute null
                if (v.consecutiveFails > 0) {
                    cleared++
                    v.consecutiveFails = 0
                    v.lastFailMs = 0L
                }
                v
            }
        }
        if (cleared > 0) persist()
        return cleared
    }

    /**
     * P2-7 — diff-based state invalidation (plan slice 7; the scoped
     * refinement of [clearBackoffsOnNewRules]). Clears the backoff JUDGMENT
     * only for hosts covered by [changedTargets] — the set of knownDead /
     * lockerHosts / hostPolicy / pipeline targets whose policy CHANGED or was
     * REMOVED between the previously active payload and the new one. A truly
     * dead host whose policy did not change keeps its live backoff, so an
     * unrelated update never re-hammers it; hosts REMOVED from knownDead /
     * lockerHosts are inside the changed set by construction, which is
     * exactly the confirmed "remembers-dead-after-fix" bug this fixes.
     *
     * Matching is deliberately shard-based (contains in either direction):
     * the playbook addresses hosts by fragments ("jisooido", "megap.")
     * everywhere else in this file's consumers, so a ledger record
     * "cdn.jisooido.net" must match the changed fragment "jisooido". Only
     * the judgment clears — ok/fail history and 429 cooldowns stay, and the
     * exempt-host rule does not apply (this is playbook-scoped, not a
     * failure recording path).
     *
     * @return count of hosts that had an active verdict cleared.
     */
    fun clearBackoffsFor(changedTargets: Set<String>): Int {
        if (changedTargets.isEmpty()) return 0
        val targets = changedTargets.map { it.lowercase().trim().removePrefix("*.") }.filter { it.isNotBlank() }
        if (targets.isEmpty()) return 0
        var cleared = 0
        for (h in records.keys) {
            val hit = targets.any { t -> h.contains(t) || t.contains(h) }
            if (!hit) continue
            records.compute(h) { _, v ->
                if (v == null) return@compute null
                if (v.consecutiveFails > 0) {
                    cleared++
                    v.consecutiveFails = 0
                    v.lastFailMs = 0L
                }
                v
            }
        }
        if (cleared > 0) persist()
        return cleared
    }

    fun recordFail(hostOrUrl: String, rateLimited: Boolean = false, reason: String? = null) {
        val h = hostOf(hostOrUrl)
        if (h.isBlank() || isExemptHost(h)) return
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
            if (rateLimited) {
                rate429++
                lastRate429Ms = System.currentTimeMillis()
            }
            if (reason != null && reason.isNotBlank()) {
                lastReason = reason
            }
            val provenRecently = lastOkMs > 0 &&
                System.currentTimeMillis() - lastOkMs < 10 * 60_000L
            if (!(isTimeout && provenRecently)) {
                consecutiveFails = (consecutiveFails + 1).coerceAtMost(20)
            }
            lastFailMs = System.currentTimeMillis()
            lastEventOk = false
        } }
        persist()
    }

    /** Last recorded failure reason for this host, if any. */
    fun lastReason(urlOrHost: String): String? = records[hostOf(urlOrHost)]?.lastReason

    /** False when this URL's host is playbook-known-dead or currently inside
     *  its backoff window. Callers skip the host WITHOUT burning a request. */
    fun isUsable(urlOrHost: String): Boolean {
        val h = hostOf(urlOrHost)
        if (isExemptHost(h)) return true
        if (isCompiledDeadSeed(h)) return false
        if (DynamicRulesManager.isKnownDead(urlOrHost)) return false
        val r = records[h] ?: return true
        val sinceLastFail = System.currentTimeMillis() - r.lastFailMs
        val sinceLast429 = System.currentTimeMillis() - r.lastRate429Ms
        // If the host explicitly returned HTTP 429 (Rate Limited), honor a minimum
        // 30s cooldown immediately so we do not hammer the server into a hard ban.
        if (r.lastRate429Ms > 0L && sinceLast429 < 30_000L) return false
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
        val h = hostOf(urlOrHost)
        if (isExemptHost(h)) return 0L
        val r = records[h] ?: return 0L
        val elapsed = System.currentTimeMillis() - r.lastFailMs
        val sinceLast429 = System.currentTimeMillis() - r.lastRate429Ms
        if (r.lastRate429Ms > 0L && sinceLast429 < 30_000L) {
            return maxOf(0L, 30_000L - sinceLast429)
        }
        if (r.consecutiveFails < 3) return 0L
        val window = backoffWindowMs(r.consecutiveFails)
        return maxOf(0L, window - elapsed)
    }

    /** True when this host has successfully served at least one stream —
     *  evidence-based proof it is a working locker. The playbook seeds known
     *  hosts, but any host that proves itself in the field is treated as
     *  known from then on (LockerRegistry.classify), no OTA needed. */
    fun hasProvenLocker(hostOrUrl: String): Boolean =
        (records[hostOf(hostOrUrl)]?.ok ?: 0L) >= 1L

    /**
     * Session hard block (investigation 11 R1 / fix row 10): the host's most
     * recent transport verdict was a NAMED edge rejection — HTTP 403 or 436
     * (Cloudflare-style bot walls; recorded by HttpClient's
     * HttpStatusCodeException branch as reason "HTTP <code> ...", and equally
     * by resolvers whose failure text embeds the code). Distinct from
     * [isUsable]'s consecutive-fails backoff (which a single failure cannot
     * trigger): one 436 must skip the host for the session window NOW, or the
     * resolver chain re-fights the same rejection until the task dies
     * ("resolver chain EMPTY", mayonaka-punch 5× 436 in one resolve).
     *
     * TTL-bounded (15 min, persisted via the same lastFailMs field so the
     * ledger needs no new schema) and overruled by any later success:
     * recordOk/clearIfAlive move lastOkMs past lastFailMs. Exempt hosts
     * (social/video app hosts) never block.
     */
    fun hardBlockActive(hostOrUrl: String): Boolean {
        val h = hostOf(hostOrUrl)
        if (h.isBlank() || isExemptHost(h)) return false
        val r = records[h] ?: return false
        val reason = r.lastReason?.lowercase() ?: return false
        if (HARD_BLOCK_REASONS.none { reason.contains(it) }) return false
        val sinceFail = System.currentTimeMillis() - r.lastFailMs
        if (sinceFail < 0L || sinceFail >= HARD_BLOCK_TTL_MS) return false
        // A proven sign of life AFTER the rejection lifts the block. The
        // boolean (not lastOkMs <= lastFailMs) keeps same-millisecond
        // ordering correct in both directions.
        return !r.lastEventOk
    }

    private const val HARD_BLOCK_TTL_MS = 15 * 60_000L
    private val HARD_BLOCK_REASONS = listOf("http 403", "http 436")

    /** Debug summary for the activity log (bounded to the hottest entries). */
    fun snapshotForLog(limit: Int = 12): String {
        return records.entries.take(limit).joinToString(", ") { (k, v) ->
            "$k(ok=${v.ok},fail=${v.fail},429=${v.rate429})"
        }
    }

    private val persistLock = Any()

    private fun persist() {
        synchronized(persistLock) {
            try {
                val f = file ?: return
                val obj = JSONObject()
                records.entries.take(500).forEach { (k, v) -> obj.put(k, v.toJson()) }
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(obj.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (_: Exception) {}
        }
    }
}
