package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.rules.DynamicRulesManager

/**
 * Hidden, OTA-controlled Nollywood / Nigerian-content suppression.
 *
 * Rules (live-validated against all supported sites, 2026-10-18 —
 * scratch/live_nollywood_all_sites_report.md):
 *
 *  R1 title     : whole-word term in the TITLE — any site. The workhorse:
 *                 22/22 Nollywood titles caught on 9jarocks, 0 collateral on
 *                 its Korean catalog. Also the only reliable rule for sites
 *                 whose cards carry neither tags nor a meaningful category
 *                 (nkiri titles literally say "Nollywood"/"Yoruba").
 *  R2 taxonomy  : term match in TAGS or GENRES only — the client-injected
 *                 `category` literal is NEVER matched (naijavault stamps
 *                 "Nollywood & Series" on every search card; matching it
 *                 suppressed 8/8 Korean results in the live dry-run while its
 *                 real taxonomy tags stayed specific). Scoped to
 *                 [DynamicRulesManager.ContentPolicy.nollywoodSites] when that
 *                 list is NON-empty; global when empty (default: global —
 *                 tags proved specific on every site tested).
 *  R3 site-only : every card from nollywoodOnlySites. Brand-level kill switch,
 *                 default EMPTY (live data: it would kill 90-100% of the Naija
 *                 home feeds, which carry plenty of non-Nollywood content).
 *
 * The switch is `contentPolicy.suppressNollywood` in the signed OTA payload:
 * absent/false => every method here is a pure pass-through (zero cost, zero
 * risk for the fleet). There is deliberately NO Settings UI — the payload
 * decides, and flipping it off restores everything on the next sync.
 * Downloads, playback, history and pasted links are never filtered.
 */
object NollywoodFilter {

    /** Compiled floor; the payload's nollywoodTerms overrides when non-empty. */
    private val DEFAULT_TERMS = listOf("nollywood", "yoruba", "naija", "igbo", "hausa")

    private fun termRegex(terms: List<String>): Regex =
        Regex(
            """\b(?:""" + terms.joinToString("|") { Regex.escape(it) } + """)\b""",
            RegexOption.IGNORE_CASE
        )

    // One-entry cache: the regex used to be recompiled per card on feed
    // surfaces. The volatile pair can race benignly — a lost race just
    // recomputes the same Regex from the same key, so no lock is needed.
    @Volatile private var cachedTermKey: List<String>? = null
    @Volatile private var cachedTermRegex: Regex? = null

    private fun termRegexCached(terms: List<String>): Regex {
        val key = terms.ifEmpty { DEFAULT_TERMS }
        cachedTermKey?.let { if (it == key) return cachedTermRegex!! }
        val re = termRegex(key)
        cachedTermKey = key
        cachedTermRegex = re
        return re
    }

    private fun matchesTerm(text: String?, re: Regex): Boolean =
        !text.isNullOrEmpty() && re.containsMatchIn(text)

    private fun activeTerms(): List<String> {
        val policy = DynamicRulesManager.getContentPolicy()
        return policy.nollywoodTerms.ifEmpty { DEFAULT_TERMS }
    }

    /** Title-only predicate for card-less surfaces (e.g. genre tiles whose
     *  poster pick only has a title string). */
    fun isNollywoodTitle(title: String): Boolean {
        val policy = DynamicRulesManager.getContentPolicy()
        if (!policy.suppressNollywood) return false
        return matchesTerm(title, termRegexCached(policy.nollywoodTerms))
    }

    fun isNollywood(card: ShowCard): Boolean {
        val policy = DynamicRulesManager.getContentPolicy()
        if (!policy.suppressNollywood) return false
        val site = card.site.lowercase()

        // R3: brand-level kill list (default empty).
        if (policy.nollywoodOnlySites.any { it.equals(site, ignoreCase = true) }) return true

        val re = termRegexCached(policy.nollywoodTerms)

        // R1: the title says it — any site.
        if (matchesTerm(card.title, re)) return true

        // R2: taxonomy tags/genres only (never the injected category literal).
        // Scoped to nollywoodSites when that list is non-empty; global when empty.
        if (policy.nollywoodSites.isNotEmpty() &&
            policy.nollywoodSites.none { it.equals(site, ignoreCase = true) }
        ) {
            return false
        }
        if (card.tags.any { matchesTerm(it, re) }) return true
        if (card.genres.any { matchesTerm(it, re) }) return true
        return false
    }

    /** The single entry point used by every feed / search choke point. */
    fun filterSafe(cards: List<ShowCard>): List<ShowCard> {
        if (!DynamicRulesManager.getContentPolicy().suppressNollywood) return cards
        return cards.filterNot { isNollywood(it) }
    }
}
