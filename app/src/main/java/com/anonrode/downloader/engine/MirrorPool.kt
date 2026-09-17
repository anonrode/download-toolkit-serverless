package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import java.net.URI

/**
 * Sequential Mirror Fallback Pool (R2).
 *
 * Coordinates candidate mirror sources across the download lifecycle:
 *  - Non-destructive candidate retention (eliminating premature 6-candidate caps).
 *  - Cooldown-aware ordering: routes around 429/503 origins in backoff,
 *    prioritizing live alternative origins without blacklisting.
 *  - Failover arbitration: enables clean rotation to secondary mirrors on
 *    transfer failure while preventing byte corruption across distinct locker encodings.
 */
object MirrorPool {
    const val MAX_SOURCES = 32

    data class CandidateMirror(
        val url: String,
        val host: String,
        val isCoolingDown: Boolean = false,
        val remainingCooldownMs: Long = 0L
    )

    fun sources(primary: String, alternatives: List<String>): List<String> =
        (sequenceOf(primary) + alternatives.asSequence())
            .filter { url ->
                try {
                    val uri = URI(url)
                    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
                } catch (_: Exception) { false }
            }
            .distinct()
            .take(MAX_SOURCES)
            .toList()

    /**
     * Returns eligible mirrors in priority order.
     *
     * Invariants:
     *  - Without byte-identity proof across lockers, an existing partial file
     *    belongs only to its selected source unless [forceRotate] is requested.
     *  - Non-cooling-down origins are prioritized before origins currently in
     *    429/503 cooldown windows.
     */
    fun eligible(
        primary: String,
        alternatives: List<String>,
        selected: String,
        hasPartial: Boolean,
        forceRotate: Boolean = false
    ): List<String> {
        val all = sources(primary, alternatives)
        val current = selected.takeIf { it in all } ?: primary
        if (hasPartial && !forceRotate) {
            return listOf(current)
        }
        val pool = if (forceRotate) all.filter { it != current } else all
        return prioritize(pool, preferredFirst = current.takeIf { !forceRotate })
    }

    /**
     * Prioritizes candidates by placing non-cooling-down origins first.
     */
    fun prioritize(urls: List<String>, preferredFirst: String? = null): List<String> {
        val distinct = urls.distinct()
        return distinct.sortedWith(
            compareBy(
                { it != preferredFirst },
                { HttpClient.remainingCooldownMs(it) > 0L },
                { HttpClient.remainingCooldownMs(it) }
            )
        )
    }

    /**
     * Finds the next usable mirror after a failure on [failedUrl].
     */
    fun nextMirror(primary: String, alternatives: List<String>, failedUrl: String): String? {
        val all = sources(primary, alternatives)
        val remaining = all.filter { it != failedUrl }
        return prioritize(remaining).firstOrNull()
    }
}
