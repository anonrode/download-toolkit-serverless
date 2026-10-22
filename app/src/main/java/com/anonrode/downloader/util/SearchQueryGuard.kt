package com.anonrode.downloader.util

/**
 * Row 12 — bounded, debounced search input. Pure JVM logic (no Android
 * imports) so the rules below are unit-testable without a device.
 *
 * The field stays editable, but a dictated multi-paragraph paste must never
 * reach every provider: [isOversized] measures the RAW input (whitespace
 * collapsed, trimmed) BEFORE the display cap, and [decide] turns that into
 * the cancel/fan-out contract the ViewModel executes. Measuring after
 * [normalize] would be dead code — `take(MAX)` guarantees the result can
 * never exceed the cap, which is exactly how the old oversize branch went
 * silent.
 */
object SearchQueryGuard {

    /** Hard cap for the query text that is displayed and sent. */
    const val MAX = 120

    /** Shorter inputs clear the search instead of hitting providers. */
    const val MIN = 2

    /** Keystroke debounce; do not change without re-measuring the crawl. */
    const val DEBOUNCE_MS = 350L

    private val WHITESPACE = Regex("""\s+""")

    /** Collapse whitespace runs to one space, trim, then cap for display. */
    fun normalize(raw: String): String = collapse(raw).take(MAX)

    /**
     * True when the whitespace-collapsed, trimmed RAW input is longer than
     * [MAX]. Callers use this to refuse fan-out; the field text itself is
     * still [normalize]d (capped) so the user sees what was accepted.
     */
    fun isOversized(raw: String): Boolean = collapse(raw).length > MAX

    /**
     * Pure decision for `onQueryChanged`:
     *  - [Decision.Refuse]   oversized paste -> keep capped text, cancel
     *                        in-flight searches, send NOTHING to providers.
     *  - [Decision.Debounce] 2..120 chars -> wait [DEBOUNCE_MS], then search.
     *  - [Decision.Clear]    0..1 chars -> cancel pending work and results.
     */
    fun decide(raw: String): Decision {
        val collapsed = collapse(raw)
        return when {
            collapsed.length > MAX -> Decision.Refuse(display = collapsed.take(MAX))
            collapsed.length >= MIN -> Decision.Debounce(query = collapsed.take(MAX))
            else -> Decision.Clear
        }
    }

    /**
     * Dedupe key for an active submission: case-insensitive query, scoped by
     * the selected provider filter. Same shape the ViewModel has always used.
     */
    fun submissionKey(query: String, filter: String): String =
        "${normalize(query).lowercase()}::$filter"

    private fun collapse(raw: String): String = WHITESPACE.replace(raw, " ").trim()

    sealed interface Decision {
        /** Capped field text; cancel in-flight searches, no provider fan-out. */
        data class Refuse(val display: String) : Decision

        /** Search [query] after [DEBOUNCE_MS]. */
        data class Debounce(val query: String) : Decision

        /** Below [MIN]: cancel pending searches and clear results. */
        data object Clear : Decision
    }
}
