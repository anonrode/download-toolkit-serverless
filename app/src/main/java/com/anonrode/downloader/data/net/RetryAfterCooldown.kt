package com.anonrode.downloader.data.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-origin Retry-After cooldown, recorded ONLY on an actually-observed 429
 * or 503 response (never on speculation, timeouts, or other codes).
 *
 * Design constraints, each mirroring a stated invariant:
 *  - Per-origin, never per-host-blacklist: the entry is a short bounded
 *    cooldown that expires on its own; there is no permanent mark and a host
 *    is fully usable again the moment the window lapses.
 *  - Origin isolation: key is the parsed URL origin (scheme://host:port).
 *    A cooldown on one origin never gates any other origin — including other
 *    schemes/ports on the same hostname.
 *  - [HttpClient.lastFailure] is a GLOBAL diagnostic journal and is
 *    deliberately NOT consulted for cooldown decisions (it is stale for
 *    concurrent resolvers with their own clients — the same misattribution
 *    HostHealth.recordFail already refuses).
 *  - Bounded memory: at most [MAX_ENTRIES] origins stored; eviction drops the
 *    soonest-expiring entry (one full scan, bounded cost, no allocation-heavy
 *    heap — the map is tiny by construction).
 *  - Bounded wait: a server-specified Retry-After is honored up to
 *    [MAX_COOLDOWN_MS]; anything larger (or unparseable-with-value) clamps to
 *    the cap, and a missing header falls back to [DEFAULT_COOLDOWN_MS].
 *  - The clock is injected so expiry/origin-isolation/invalid-date behavior
 *    is fully JVM-testable without real time.
 *
 * Pure JVM class (no Android imports).
 */
internal class RetryAfterCooldown(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val defaultCooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxCooldownMs: Long = MAX_COOLDOWN_MS
) {
    data class Entry(val untilMs: Long)

    private val byOrigin = ConcurrentHashMap<String, Entry>(64)
    private val evictions = AtomicLong(0)

    /**
     * True when [url]'s origin is inside its cooldown window. Fail-open on
     * any parsing trouble: a cooldown must never block traffic by accident.
     */
    fun isCoolingDown(url: String, nowMs: Long = clock()): Boolean {
        val origin = originOf(url) ?: return false
        val e = byOrigin[origin] ?: return false
        if (nowMs >= e.untilMs) {
            // Expired: opportunistic removal (benign if another thread replaced it).
            byOrigin.remove(origin, e)
            return false
        }
        return true
    }

    /** Remaining cooldown ms for [url]'s origin (0 when usable). */
    fun remainingMs(url: String, nowMs: Long = clock()): Long {
        val origin = originOf(url) ?: return 0L
        val e = byOrigin[origin] ?: return 0L
        return maxOf(0L, e.untilMs - nowMs)
    }

    /**
     * Record a cooldown for [url]'s origin from an actual 429/503 response.
     * [retryAfterHeader] is the raw `Retry-After` value (seconds form or
     * HTTP-date form). Unparseable header -> [defaultCooldownMs]; value/clip
     * above [maxCooldownMs] -> clamped; absent -> [defaultCooldownMs].
     * Keeps the later expiry when concurrent responses disagree. Waiters
     * have their own total time budget even if fresh responses extend it.
     */
    fun record(url: String, retryAfterHeader: String? = null, nowMs: Long = clock()) {
        val origin = originOf(url) ?: return
        val requested = parseRetryAfterMs(retryAfterHeader, nowMs) ?: defaultCooldownMs
        val until = nowMs + requested.coerceIn(0L, maxCooldownMs)
        byOrigin.merge(origin, Entry(until)) { old, new ->
            if (old.untilMs >= new.untilMs) old else new
        }
        evictIfNeeded(nowMs)
    }

    /** Test/debug visibility. */
    fun trackedOrigins(): Int = byOrigin.size

    /**
     * Cancellable coroutine wait until [url]'s origin leaves its cooldown.
     * Synchronous APIs never call this (they fail fast via [remainingMs]);
     * coroutine callers may await instead of burning a request. Cancellation
     * propagates through [kotlinx.coroutines.delay] immediately.
     */
    suspend fun waitUntilUsable(url: String) {
        kotlinx.coroutines.withTimeoutOrNull(maxCooldownMs) {
            while (true) {
                val ms = remainingMs(url)
                if (ms <= 0L) return@withTimeoutOrNull
                kotlinx.coroutines.delay(ms.coerceAtMost(maxCooldownMs))
            }
        }
    }

    fun evictedCount(): Long = evictions.get()

    /** Drop every entry (tests, and a manual "start clean" path). */
    fun clear() = byOrigin.clear()

    /**
     * Cooldown ms requested by a Retry-After value, or null when absent or
     * unparseable (caller then applies the default). Accepts:
     *  - delay-seconds: "12" -> 12_000 (negative/garbage -> null)
     *  - HTTP-date: "Wed, 21 Oct 2026 07:28:00 GMT" -> date - now, when in
     *    the future; a past date means "no wait" and maps to 0, not null
     *    (the server DID answer us; treating it as unparseable would impose
     *    the default anyway, but 0 is the honest reading).
     */
    internal fun parseRetryAfterMs(raw: String?, nowMs: Long): Long? {
        val v = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        if (v.all { it in '0'..'9' }) {
            val seconds = v.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
        }
        val at = tryParseHttpDate(v) ?: return null
        if (at <= nowMs) return 0L
        return if (nowMs < 0L && at > Long.MAX_VALUE + nowMs) Long.MAX_VALUE else at - nowMs
    }

    private fun tryParseHttpDate(value: String): Long? = try {
        java.time.ZonedDateTime.parse(
            value,
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        ).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private fun evictIfNeeded(nowMs: Long) {
        if (byOrigin.size <= MAX_ENTRIES) return
        // Drop expired entries first; if still over, evict soonest-expiring.
        byOrigin.entries.removeIf { it.value.untilMs <= nowMs }
        var dropped = 0
        while (byOrigin.size > MAX_ENTRIES) {
            byOrigin.entries.minByOrNull { it.value.untilMs }?.let { byOrigin.remove(it.key) }
            if (++dropped > MAX_ENTRIES) break // paranoid bound against races
        }
        evictions.incrementAndGet()
    }

    companion object {
        /** Fallback when a 429/503 carries no usable Retry-After. */
        const val DEFAULT_COOLDOWN_MS = 15_000L
        /** Never park an origin longer than this, whatever the header claims. */
        const val MAX_COOLDOWN_MS = 120_000L
        /** Storage bound for tracked origins. */
        const val MAX_ENTRIES = 64

        /**
         * Canonical origin extraction for cooldown keys. Uses the same
         * non-lenient HttpUrl parse path as every other security gate
         * (HttpUrl canonicalizes userinfo, so "https://evil@a.com/" keys on
         * a.com, the host the request REALLY connects to). Null on unparseable
         * input -> caller fail-opens (no cooldown).
         */
        fun originOf(url: String): String? {
            return try {
                val parsed = okhttp3.Request.Builder().url(url.trim()).build().url
                val port = parsed.port
                val defaultPort = parsed.scheme == "https" && port == 443 ||
                    parsed.scheme == "http" && port == 80
                "${parsed.scheme}://${parsed.host}${if (defaultPort) "" else ":$port"}"
            } catch (_: Exception) {
                null
            }
        }
    }
}
