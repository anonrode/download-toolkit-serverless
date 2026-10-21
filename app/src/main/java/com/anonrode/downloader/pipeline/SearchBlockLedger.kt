package com.anonrode.downloader.pipeline

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-site SEARCH block ledger keyed on the raw query (RC-4, 2026-10-21).
 *
 * nepu.gd returns HTTP 451 "Unavailable For Legal Reasons" for specific
 * TITLE keywords ("servamp", "the promised neverland", "demons ascension"
 * reproduced live from the workstation 2026-10-21) while generic queries
 * return 200. Before this ledger, every keystroke re-fanned the blocked
 * query through the whole search+verify machinery (30 empty searches in 23 s
 * per keystroke storm, share-43), and each 451 re-planted route-rotation and
 * cooldown state for the origin.
 *
 * A block is SITE-scoped (other providers still answer the same title) and
 * TTL'd (the upstream block list is dynamic: fight/horror/comedy 451'd on
 * the phone on 10-19 and returned 200 from a different edge on 10-21).
 * In-memory only, same doctrine as ResolveCache: nothing persisted, the TTL
 * is the self-heal. Recorded ONLY for genuine legal-block responses (the
 * HTTP 451 status in the failure chain) — never for transport noise, so a
 * flaky network cannot silence a provider.
 */
object SearchBlockLedger {

    private data class Entry(val untilMs: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    /** How long a 451-blocked (site, query) pair skips its own fan-out. */
    const val TTL_MS = 12L * 60 * 60 * 1000

    /** Bound so a pathological typing storm cannot grow the map unbounded. */
    private const val MAX_ENTRIES = 512

    /** True when the failure chain carries a real legal block. */
    fun isLegalBlockMessage(message: String?): Boolean =
        message != null && Regex("""HTTP\s*451\b""", RegexOption.IGNORE_CASE).containsMatchIn(message)

    /** Record a legal-block hit for this (site, query) pair. */
    fun recordBlocked(site: String, query: String) {
        val key = keyOf(site, query) ?: return
        if (map.size >= MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now >= it.value.untilMs }
            if (map.size >= MAX_ENTRIES) map.clear()
        }
        map[key] = Entry(System.currentTimeMillis() + TTL_MS)
    }

    /** True while this (site, query) pair should skip its own fan-out. */
    fun isBlocked(site: String, query: String): Boolean {
        val key = keyOf(site, query) ?: return false
        val e = map[key] ?: return false
        if (System.currentTimeMillis() >= e.untilMs) {
            map.remove(key)
            return false
        }
        return true
    }

    /** Test/diagnostic reset. */
    fun clear() = map.clear()

    private fun keyOf(site: String, query: String): String? {
        val s = site.trim().lowercase()
        val q = query.trim().lowercase()
        if (s.isEmpty() || q.isEmpty()) return null
        return "$s|$q"
    }
}
