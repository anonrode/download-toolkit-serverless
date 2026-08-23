package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.rules.DynamicRulesManager
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory resolution cache: episode URL -> resolved stream URL, with a TTL
 * derived from the playbook's tokenTtlMinutes (locker tokens expire in
 * minutes — caching beyond that hands users 403s). Memory-only by design:
 * nothing about a user's watch/download history is persisted to disk.
 *
 * A mid-download 403 simply misses/invalidate()s here, and the next resolve
 * mints a fresh token.
 */
object ResolveCache {

    private data class Entry(val url: String, val expiresAtMs: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun keyFor(episodeUrl: String, quality: String): String = "$quality::${episodeUrl.trim()}"

    fun get(key: String): String? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() >= e.expiresAtMs) {
            map.remove(key)
            return null
        }
        return e.url
    }

    fun put(key: String, resolvedUrl: String) {
        val ttl = DynamicRulesManager.getTokenTtlMs()
        map[key] = Entry(resolvedUrl, System.currentTimeMillis() + ttl)
        // Bound memory: drop expired entries once the map gets large.
        if (map.size > 256) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now >= it.value.expiresAtMs }
        }
    }

    fun invalidate(key: String) {
        map.remove(key)
    }

    fun clear() = map.clear()
}
