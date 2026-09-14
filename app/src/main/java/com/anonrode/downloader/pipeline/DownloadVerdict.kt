package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.models.ShowCard

/**
 * The search-verify oracle's verdict vocabulary (2026-09-14 design, doctrine
 * below is the product spec, not implementation detail):
 *
 *  - [Live]  — the card's chain cracked AND the terminal URL answered the
 *    no-redirect Range: bytes=0-0 probe with real file bytes. This is the
 *    ONLY state allowed to badge ("✓ 220 MB"); its payload (direct URL,
 *    episode count, size) is what makes the drawer and first bytes instant.
 *  - [Unreachable] — we could not prove anything: timeout, 429/5xx, TLS,
 *    Cloudflare challenge, HTML-at-terminal, parse failure, budget stop.
 *    Renders as a COMPLETELY NORMAL card (user decision 2026-09-14: badge
 *    winners, never losers) and stays tappable. Uncertainty is visibility-
 *    preserving; this state is a non-event for the user.
 *  - [Dead] — PROVEN dead, the only state hidden from results: the final
 *    direct URL answered 404/410 twice (2s backoff between), or the show
 *    page itself 404/410'd. Never on mere absence of a verdict, never on a
 *    challenge page, never on a parse failure.
 *
 * Memory-only by design (matches ResolveCache's stance: nothing about what
 * the user searched is written to disk).
 */
sealed class Verdict {
    data class Live(
        val directUrl: String,
        val totalBytes: Long?,
        val episodeCount: Int,
        val verifiedAtMs: Long
    ) : Verdict()

    data class Unreachable(val reason: String, val checkedAtMs: Long) : Verdict()

    data class Dead(val reason: String, val checkedAtMs: Long) : Verdict()
}

/**
 * Pure policy — keys, freshness, reveal filtering and the caption string.
 * No Android, no network: the entire doctrine is JVM-testable here and the
 * verifier + UI are dumb executors of it.
 */
object VerdictPolicy {

    /** Live = "the chain was alive 22 minutes ago". Longer than the ~10-min
     *  ResolveCache token window on purpose: the caption claims the SHOW is
     *  downloadable, not that a specific token is unexpired — download paths
     *  always re-mint (invalidate-first resolveStreamUrl untouched). Measured
     *  2026-09-14: vault temp tokens rotate within seconds, so any TTL is a
     *  freshness indicator, never a byte guarantee. */
    const val LIVE_TTL_MS = 30 * 60_000L

    /** A proven-dead final URL stays hidden a day; 404s rarely resurrect,
     *  and re-verification on a fresh search overrides early anyway. */
    const val DEAD_TTL_MS = 24 * 60 * 60_000L

    /** Unreachable is not a verdict the user sees — but don't re-spend
     *  budget on the same hopeless card within this window. */
    const val UNREACHABLE_RETRY_MS = 5 * 60_000L

    /** preResolved hand-off window: startTask may skip re-cracking only for
     *  a verdict this fresh (measured: tokens age in minutes; the engine's
     *  401/403/404 self-heal re-resolve is the safety net beyond this). */
    const val PRERESOLVED_MAX_AGE_MS = 120_000L

    /** Cache key: lowercase scheme+host (DNS-insensitive), drop the fragment
     *  (pure client-side), KEEP path+query case and content (tokens live
     *  there — lowercasing them would collide distinct verdicts). */
    fun keyFor(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val noFrag = trimmed.substringBefore('#')
        val sep = noFrag.indexOf("://")
        if (sep < 0) return noFrag
        val scheme = noFrag.substring(0, sep).lowercase()
        val rest = noFrag.substring(sep + 3)
        val slash = rest.indexOf('/')
        val host = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
        val tail = if (slash < 0) "" else rest.substring(slash)
        return "$scheme://$host$tail"
    }

    fun ageMs(v: Verdict, now: Long): Long = now - when (v) {
        is Verdict.Live -> v.verifiedAtMs
        is Verdict.Unreachable -> v.checkedAtMs
        is Verdict.Dead -> v.checkedAtMs
    }

    /** True when a stored verdict is still authoritative (else re-verify). */
    fun isFresh(v: Verdict, now: Long): Boolean = when (v) {
        is Verdict.Live -> now - v.verifiedAtMs < LIVE_TTL_MS
        is Verdict.Dead -> now - v.checkedAtMs < DEAD_TTL_MS
        is Verdict.Unreachable -> now - v.checkedAtMs < UNREACHABLE_RETRY_MS
    }

    fun isPreresolvable(v: Verdict?, now: Long): Boolean =
        v is Verdict.Live && now - v.verifiedAtMs <= PRERESOLVED_MAX_AGE_MS && v.totalBytes != null

    /** The one hide rule, as a function: ONLY a fresh Dead disappears.
     *  Absence of a verdict is NEVER absence of a card. */
    fun hideFromResults(v: Verdict?, now: Long): Boolean =
        v is Verdict.Dead && now - v.checkedAtMs < DEAD_TTL_MS

    /** Badge the winners: caption for a LIVE card, null for everything else.
     *  Movie: "✓ 220 MB". Series: "✓ 24 eps · 450 MB/ep" (count learned free
     *  from the drawer page the verification had to open anyway). */
    fun captionFor(v: Verdict?): String? {
        val live = v as? Verdict.Live ?: return null
        val size = live.totalBytes?.let { formatBytes(it) }
        val eps = if (live.episodeCount > 1) "${live.episodeCount} eps" else null
        return when {
            eps != null && size != null -> "✓ $eps · $size/ep"
            eps != null -> "✓ $eps"
            size != null -> "✓ $size"
            else -> "✓"
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        else -> (bytes / 1_000_000L).coerceAtLeast(1L).toString() + " MB"
    }

    /** Reveal filter: keep every card the doctrine doesn't hide. Pure, so
     *  the "cards are only removed by PROOF" rule has a direct test. */
    fun visible(ranked: List<ShowCard>, verdicts: Map<String, Verdict>, now: Long): List<ShowCard> =
        ranked.filter { !hideFromResults(verdicts[keyFor(it.url)], now) }

    /** The user's priority rule (2026-09-14): verified cards come FIRST —
     *  LIVE cards float to the top in rank order, everything else keeps its
     *  rank underneath, Dead is gone. Stable within both halves, so a card
     *  moves at most once (when its verdict lands) — never jitter. */
    fun visibleOrdered(
        ranked: List<ShowCard>,
        verdicts: Map<String, Verdict>,
        now: Long
    ): List<ShowCard> {
        val live = ArrayList<ShowCard>(ranked.size)
        val rest = ArrayList<ShowCard>(ranked.size)
        for (card in ranked) {
            val v = verdicts[keyFor(card.url)]
            if (hideFromResults(v, now)) continue
            if (v is Verdict.Live && isFresh(v, now)) live.add(card) else rest.add(card)
        }
        live.addAll(rest)
        return live
    }
}
