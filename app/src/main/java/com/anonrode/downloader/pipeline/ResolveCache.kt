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

    /**
     * Poison list: a set of resolved stream URLs that the engine has proven
     * to be dead (e.g. wildshare returned an HTML error page on what should
     * have been a .mkv). Resolver lookups skip poisoned URLs and the
     * next resolveStreamUrl call never returns them. Entries expire after
     * the same TTL as the cache so a token-rotation cycle can revive a host
     * later (e.g. wildshare's tokens rotate every 30 min).
     *
     * The bug this fixes: without poisoning, the engine's "refresh token"
     * fallback (DownloadEngine.kt around L1667) calls resolveStreamUrl on
     * the SAME episode URL, gets the SAME dead wildshare URL back from the
     * cache, and re-downloads an HTML error page. Wildshare validation
     * showed 11 of 11 episodes for The Pitt S02 returning "URL serves an
     * HTML/error page" (app-2026-09-01, 11 errors back-to-back) — every
     * retry was a no-op because the resolver kept handing back the same
     * dead URL.
     */
    private val poisoned = ConcurrentHashMap<String, Long>()

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

    /**
     * Mark a resolved URL as dead: the resolver will not return it again
     * until [clearPoison] is called or the TTL expires (tokenTtlMinutes,
     * normally 10 minutes — long enough that the user sees a real failure
     * message instead of a tight retry loop on a dead host, short enough
     * that a recovered host can be rediscovered). Idempotent: a second call
     * just extends the TTL to the new value.
     */
    fun poison(resolvedUrl: String, ttlMs: Long = DynamicRulesManager.getTokenTtlMs()) {
        if (resolvedUrl.isBlank()) return
        poisoned[resolvedUrl] = System.currentTimeMillis() + ttlMs
        if (poisoned.size > 256) {
            val now = System.currentTimeMillis()
            poisoned.entries.removeIf { now >= it.value }
        }
    }

    /** True when this URL is in the poison list and the TTL has not expired. */
    fun isPoisoned(resolvedUrl: String): Boolean {
        val until = poisoned[resolvedUrl] ?: return false
        if (System.currentTimeMillis() >= until) {
            poisoned.remove(resolvedUrl)
            return false
        }
        return true
    }

    /** Used by tests; production code never needs to call this. */
    fun clearPoison() = poisoned.clear()

    fun clear() {
        map.clear()
        poisoned.clear()
    }
}
