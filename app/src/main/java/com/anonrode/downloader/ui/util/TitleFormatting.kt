package com.anonrode.downloader.ui.util

/**
 * Pure presentation-side title formatting helpers for the UI overhaul
 * (spec §2.6, §3.2, §4.2, §5.3). No Compose, no Android imports — plain
 * Kotlin so JVM unit tests can pin every rule without Robolectric.
 *
 * These helpers NEVER mutate stored data: every consumer applies them at
 * render time on strings the pipeline already produced. The engine-side
 * file naming keeps its own (NameSanitizer) paths untouched.
 */
object TitleFormatting {

    // ── §3.2 SxxExx extraction ────────────────────────────────────────────────
    /** `S01E01`-style tags (1-2 season digits, 1-3 episode digits). */
    private val seasonEpisodeTag = Regex("""S\d{1,2}E\d{1,3}""", RegexOption.IGNORE_CASE)

    /**
     * Extracts the first `S\d+E\d+` tag from a raw title and returns
     * `(cleanTitle, tag)`. The tag is meant to move onto the thumbnail badge
     * (§3.2); `null` when the title carries no tag.
     */
    fun extractSeasonEpisodeTag(rawTitle: String?): Pair<String, String>? {
        val raw = rawTitle?.trim().orEmpty()
        if (raw.isBlank()) return null
        val match = seasonEpisodeTag.find(raw) ?: return null
        val tag = match.value.uppercase()
        val cleaned = collapseSpaces(raw.removeRange(match.range)).trim(' ', '-', '.', ':', '\u2013', '\u2014')
        return cleaned to tag
    }

    // ── §5.3 trailing bracketed extractor id ────────────────────────────────
    /** Trailing `[Defo4HhjcWO]`-style yt-dlp extractor ids, 8-20 id chars. */
    private val trailingYtDlpId = Regex("""\s*\[[A-Za-z0-9_-]{8,20}\]\s*$""")

    /** Strips ONE trailing bracketed id (and leftover whitespace): the face
     *  title for social downloads renders "Video by lolita", never the id. */
    fun stripTrailingYtDlpId(rawTitle: String?): String {
        var s = rawTitle?.trim().orEmpty()
        // Repeat: a (rare) double-suffixed title sheds both brackets.
        var previous: String
        do {
            previous = s
            s = trailingYtDlpId.replace(s, "").trim()
        } while (s != previous && s.isNotBlank())
        return s
    }

    // ── §2.6 episode title sanitizer ────────────────────────────────────────
    /**
     * Raw scraper labels that carry no episode name: bare numbers, quality
     * tags, action words ("Download", "Watch") and whitespace. Titles made
     * up ONLY of these (or blank) become "Episode N".
     */
    private val nonInformativeEpisodeTitle =
        Regex("""^(download|link|watch|stream|episode|part|mp4|mkv|hd|\s*|\d+)+$""", RegexOption.IGNORE_CASE)

    /** Poster-title scraper suffixes: "| Download Comedy Special" & kin. */
    private val trailingDownloadSuffix = Regex(
        """(\s*\|\s*download.*|\s*-\s*download.*|\s*free download.*)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns the human episode title, or `"Episode ${index + 1}"` /
     * `"Season $seasonNumber · Episode ${index + 1}"` when the source never
     * supplied an informative one. Never renders "01 Download".
     *
     * [index] is the episode's ZERO-BASED ordinal; callers holding a real
     * `episodeNum` pass `episodeNum - 1` so the fallback numbering reflects
     * the actual episode number instead of list position.
     */
    fun sanitizeEpisodeTitle(
        rawTitle: String?,
        index: Int,
        seasonNumber: Int? = null
    ): String {
        val stripped = stripTrailingYtDlpId(
            trailingDownloadSuffix.replace(rawTitle.orEmpty(), "").trim()
        )
        val downloadAnchorMatch = Regex("""(?i)^(episode\s*)?download(\s+\d{3,4}p|\s+mp4|\s+mkv)?$""").matches(stripped)
        val informative = stripped.isNotBlank() && !downloadAnchorMatch && !nonInformativeEpisodeTitle.matches(stripped)
        val display = if (informative) stripped else ""
        return if (display.isNotBlank()) {
            display
        } else if (seasonNumber != null && seasonNumber > 0) {
            "S%02dE%02d".format(seasonNumber, index + 1)
        } else if (index == 0) {
            "Full Movie"
        } else {
            "Episode ${index + 1}"
        }
    }

    // ── §4.2 grid title + (YYYY) / Season X subtitle ─────────────────────────
    private val yearToken = Regex("""[\(\[]\s*((?:19|20)\d{2})\s*[\)\]]""")
    private val seasonToken = Regex("""\bSeason\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val subrangeToken = Regex("""\bS(\d{1,2})(?:E\d{1,3})?\b""", RegexOption.IGNORE_CASE)

    /**
     * Splits a raw poster title into `(cleanTitle, subtitle)`:
     * a `(2021)` / `[2021]` year and a `Season 6` marker move into the
     * muted subtitle ("2021 · Season 6"); poster titles that reflow unevenly
     * are sanitized in place of the raw scraper text. Collapse double spaces
     * and dangling separators so the 2-line card grid aligns.
     */
    fun splitYearAndSeason(rawTitle: String?): Pair<String, String?> {
        var s = rawTitle?.trim().orEmpty()
        if (s.isBlank()) return "" to null
        val subtitleParts = mutableListOf<String>()

        yearToken.find(s)?.let { m ->
            subtitleParts.add(m.groupValues[1])
            s = s.removeRange(m.range)
        }
        seasonToken.find(s)?.let { m ->
            subtitleParts.add("Season ${m.groupValues[1]}")
            s = s.removeRange(m.range)
        }
        // A bare "S02"/"S02E05" marker on a poster title is the same signal
        // as "Season 2" — take it only when no Season token already matched.
        if (subtitleParts.none { it.startsWith("Season") }) {
            subrangeToken.find(s)?.let { m ->
                val num = m.groupValues[1].trimStart('0').ifEmpty { "0" }
                subtitleParts.add("Season $num")
                s = s.removeRange(m.range)
            }
        }
        s = collapseSpaces(s).trim(' ', '-', '.', ':', '|', '\u2013', '\u2014')
        return s to subtitleParts.joinToString(" · ").ifBlank { null }
    }

    /** Collapses whitespace runs to single spaces (draw-phase lookup none). */
    fun collapseSpaces(raw: String): String = raw.replace(Regex("""\s+"""), " ")

    /** §4.2 one-shot: strips download suffixes + trailing ids for card faces
     *  that show a plain title (no subtitle available). */
    fun cleanPosterTitle(rawTitle: String?): String {
        val (title, _) = splitYearAndSeason(
            trailingDownloadSuffix.replace(rawTitle.orEmpty(), "").trim()
        )
        return title.ifBlank { rawTitle?.trim().orEmpty() }
    }
}
