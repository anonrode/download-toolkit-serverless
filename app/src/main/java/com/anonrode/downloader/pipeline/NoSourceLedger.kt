package com.anonrode.downloader.pipeline

import android.content.Context

/**
 * Titles with NO playable source on any nepu platform — hidden from search.
 *
 * Seeds are NOT guesses: each entry was verified by the multi-episode ×
 * multi-platform absence probe of 2026-10-16 (scratch/nepu100/
 * absence_verdicts.md — every embed surface refuses at S1E1..E3 + deep
 * seasons, viewer-rendered pages included), after the S01E01-only test was
 * independently re-tested. Covered titles ship as data (this list), not
 * compiled belief: [clear] runs on every OTA rules activation, so the day a
 * payload adds a platform that carries one of these, it reappears in search
 * automatically — same rev-stamped invalidation doctrine as HostHealth.
 */
object NoSourceLedger {

    private val SEEDED_ABSENT = setOf(
        "wedding ring",          // tmdb 100748 — all 5 platforms refuse, S1+S2
        "street football",       // tmdb 65141 — all refuse, deep seasons
        "holby city"             // tmdb 1028 — all refuse, deep seasons (own page confirms catalog absence)
    )

    private var prefs: android.content.SharedPreferences? = null

    fun attach(context: Context) {
        prefs = try {
            context.getSharedPreferences("no_source_ledger", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    /** Manual recordings (per-title timestamp, TTL'd) — the seeds above are
     *  permanent until a rules change clears the whole ledger. */
    fun recordUnresolved(title: String) {
        val key = norm(title)
        if (key.isBlank() || key in SEEDED_ABSENT) return
        try {
            prefs?.edit()?.putLong(key, System.currentTimeMillis())?.apply()
        } catch (_: Exception) {}
    }

    fun isHidden(title: String): Boolean {
        val key = norm(title)
        if (key in SEEDED_ABSENT) return true
        val ts = try { prefs?.getLong(key, 0L) } catch (_: Exception) { 0L } ?: 0L
        return ts > 0L && System.currentTimeMillis() - ts < TTL_MS
    }

    /** Rev-stamped invalidation — same doctrine as
     *  [HostHealth.clearBackoffsOnNewRules]: a new payload may add platforms
     *  or sources, so every hidden citation gets re-judged. */
    fun clear() {
        try {
            prefs?.edit()?.clear()?.apply()
        } catch (_: Exception) {}
    }

    private const val TTL_MS = 48L * 60 * 60 * 1000

    private fun norm(t: String): String = t.lowercase().trim()
}
