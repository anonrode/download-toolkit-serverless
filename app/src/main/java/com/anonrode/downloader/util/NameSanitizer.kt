package com.anonrode.downloader.util

/**
 * THE name standard for everything this app writes to disk — folder names
 * (show titles, `Social/<platform>`), saved video file names, and the IG
 * muxer caption label all flow through here, so "one file per site, another
 * name shape" cannot happen again.
 *
 * Two layers, applied in this order, both pure and idempotent:
 *
 *  1. [cleanTitle] — NOISE layer (display-usable): decodes HTML entities,
 *     removes site-decoration segments and taxonomy tags. Sites pack their
 *     search-card titles with promo junk ("The Pitt S02 _Episode 15 Added_
 *     _ TV Series"); the drawer then appends the real label with " - ".
 *     The saved name should read like a title, not an ad:
 *     "The Pitt S02 _Episode 15 Added_ _ TV Series - Episode 1"
 *       -> "The Pitt S02 Episode 1".
 *  2. [safeComponent] — SAFETY layer (filesystem): keeps native script
 *     (Hangul/CJK/accented names are NOT underscore-mangled anymore),
 *     replaces only what filesystems/MTP actually forbid, guards traversal,
 *     Windows reserved names, trailing dots, and the 255-byte-per-component
 *     limit (capped in BYTES, cut at codepoint boundaries).
 *
 * [savedName] = safeComponent(cleanTitle(raw)) is what every enqueue/
 * folder path must use. UI display strings are deliberately left raw —
 * cleaning happens at the moment a name is written to disk.
 */
object NameSanitizer {

    /**
     * Group-content noise: a bracketed/underscore-decorated segment is
     * dropped when its text is site noise — promo wording, an "episode N
     * added" update note, taxonomy tags, format/piracy flags. Matching is
     * anchored to the FULL group text (no substring fishing inside
     * otherwise-legit parentheticals like "(2019)" or "(Original Mix)").
     */
    // ENGINE RULE (v3.1.x saga, root cause CONFIRMED by the phone's own
    // Downloads/Anon/anon_crash.txt): on Android, java.util.regex delegates to
    // ICU (com.android.icu.util.regex.PatternNative) — NOT the desktop JVM
    // engine every unit test and CI tier compiles against. Every download
    // crash since v3.1.1 was ONE character: a literal-brace pattern ending in
    // an UNESCAPED `}` (`\{([^{}]{1,60})}`) — literal to the JVM,
    // "Syntax error in regexp pattern near index 16" to ICU — thrown in
    // <clinit>, so the first download tap of the session died with
    // ExceptionInInitializerError. The earlier `(?i)` and `(?<=)` theories
    // were both wrong (the engine died at this line before reaching them).
    // Portable whitelist for ALL main-source regexes:
    //  - literal braces: ALWAYS write \{ and \} (an unmatched `}` outside [...]
    //    is banned even mid-string);
    //  - inline flags only via RegexOption (no (?i)/(?u) inside pattern text);
    //  - avoid lookbehind (?<=/(?<!, named groups, atomic (??>, possessive
    //    *+ ++ ?+, \p{ classes — defensive only: each is proven to bite at
    //    least one supported engine and every one has a capture-group equivalent;
    //  - lookahead (?= (?! is fine (universally supported, repo precedent).
    // Enforced twice: ktregexflags.py static gate + RegexEngineCompatTest, the
    // CI Tier-2 suite that compiles EVERY pattern against the REAL engine.
    private val NOISE_GROUP = Regex(
        """^[\s.!-]*(added?|updated?|new episode.*|now streaming.*|coming soon.*|""" +
            """(full )?(movie|episode|hd|4k|uhd|cam|ts|scr|web-?rip|dvd-?rip|blu-?ray)( .*)?|""" +
            """watch( online| free)?( hd)?( in high quality)?|online( now| free)?|free( download| watch)?|""" +
            """complete|completed|full (season|series)|s\d+\s*complete|""" +
            """tv series|series|dorama|k-?drama|anime|movie series|""" +
            """episode \d+( added| new| update[d]?)?|episode added|season \d+ added|\d+(st|nd|rd|th) season added|""" +
            """episode\s*\d+\s*[-–—~]\s*\d+.*|ep\s*\d+\s*[-–—~]\s*\d+.*|""" +
            """english\s*(subtitles?|subbed|subs?)|eng\s*(subtitles?|subbed|subs?)|subbed|dual audio|multi sub.*)[\s.!-]*$""",
        RegexOption.IGNORE_CASE
    )

    /** Decorations sites actually use: `_text_`, [text], (text), {text}. */
    private val DECORATIONS = listOf(
        Regex("""_([^_]{1,60})_"""),
        Regex("""\[([^\[\]]{1,60})]"""),
        Regex("""\(([^()]{1,60})\)"""),
        // BOTH braces escaped. A trailing unescaped `}` compiles on the
        // desktop JVM (literal) but the phone's ICU-backed engine rejects an
        // unmatched `}` — THIS line (index 16) is what threw on every device
        // download since v3.1.1 (anon_crash.txt: PatternSyntaxException in
        // com.android.icu.util.regex.PatternNative at <clinit>). `]` as a
        // literal (line above) is fine on both engines; `}` is NOT.
        Regex("""\{([^{}]{1,60})\}""")
    )

    /**
     * Year/date parentheticals are INFORMATION, not noise: "(2019)",
     * "(2024-03-15)". They survive even though they sit in the decoration
     * shape (the NOISE_GROUP anchor already refuses them — listed here as
     * the explicit exemption guard).
     */
    private val YEARISH = Regex("""^\s*\d{4}([-.]\d{1,2}){0,2}\s*$""")

    /**
     * Episode-label join: the drawer composes "Show - Episode 3"; once the
     * junk is gone the hyena separator should not survive into the saved
     * name either — but ONLY when the dash introduces an episode token
     * (never touch the real dash in "Dr. Dolittle - 2001").
     */
    private val DASH_EPISODE_JOIN = Regex(
        """\s+[-–—]\s+(?=(episode|ep\.?\s*\d+|part|ch(apter)?|s\d{1,2}e\d{1,3})\b)""",
        RegexOption.IGNORE_CASE
    )

    /** Multiple "Episode N" tokens after cleaning: the LAST one is the
     *  drawer-appended real label — the earlier ones are leftovers. */
    private val EPISODE_TOKEN = Regex("""\b(episode|ep)\.?\s*\d+\b""", RegexOption.IGNORE_CASE)

    /**
     * Unbracketed noise phrases. Sites decorate fields with single '_'
     * separators that never pair up ("_Episode 15 Added_ _ TV Series" leaves
     * "_ TV Series" unpaired once the first group is removed), so the common
     * taxonomy/promo phrases are cut too. Mitigation against over-cutting
     * real titles (2026-09-13): position IS the gate —
     *  - [sepBare] cuts a phrase only when a site hangs it off a decoration
     *    separator (`_`, ` - `, ` | `, en/em-dash): "Show _ Free Download"
     *    loses the tail, "The Online Class" keeps the word mid-phrase;
     *  - [endBare] additionally cuts at the string END, but the end vocabulary
     *    is deliberately restricted to multi-word phrases — nothing that can
     *    plausibly be the last words of a real title ("Last Online",
     *    "The Watch", "Born Free" survive; "…Full Movie" / "…Watch Online"
     *    trailing the scraped `<title>` is the case this exists for).
     * No length heuristic: a legitimate cut can remove MORE than half of a
     * short title ("Show - Watch Online"), so mass-based reverts fired exactly
     * when the cleaner was right.
     */
    private fun sepBare(alts: String) =
        Regex("""(?:_|\s[-–—|])\s*\b(?:$alts)\b_?""", RegexOption.IGNORE_CASE)
    private fun endBare(alts: String) =
        // no "(?<=\S)": lookbehinds are in the banned class (ENGINE RULE) —
        // the match already requires a separating \s before the phrase, which
        // carries the intended meaning (a phrase that IS the whole string has
        // no leading \s and is never cut).
        Regex("""\s\b(?:$alts)\s*$""", RegexOption.IGNORE_CASE)
    private val BARE_NOISE_PHRASES = listOf(
        sepBare("""(?:korean|chinese|c|thai|japanese|j|bl|asian|filipino)?\s*(?:drama|dorama)"""),
        sepBare("""tv series|k-?drama|dorama|web series|movie series|anime series"""),
        sepBare("""ep(isode)?\.?\s*\d+\s+(added|new|updat(e|ed))|ep(isode)?\s+added"""),
        sepBare("""watch( online)?( hd| in hd)?|online( now)?|free (download|watch)|full (movie|episode|hd)"""),
        sepBare("""english\s*(?:subtitles?|subbed|subs?)|eng\s*(?:subtitles?|subbed|subs?)|dual\s*audio|multi\s*audio|subbed"""),
        endBare("""tv series|k-?drama|web series|movie series|anime series|""" +
            """ep(isode)?\.?\s*\d+\s+(added|new|updat(e|ed))|ep(isode)?\s+added|""" +
            """watch online( hd)?|online hd|free (download|watch)|full (movie|episode|hd)|""" +
            """english\s*(?:subtitles?|subbed|subs?)|eng\s*(?:subtitles?|subbed|subs?)|dual\s*audio|multi\s*audio|subbed|sub""")
    )

    /**
     * Noise + whitespace/entity normalization for a scraped title.
     * [stripNoise]=false skips ONLY the bare-phrase layer (user prose like
     * IG captions or a shared filename must never have words deleted — a
     * caption may legally contain "online", "movie", "free" as content);
     * entity decoding, decoration-group cuts, and whitespace hygiene still
     * run, so the no-junk guarantee keeps its teeth for genuine artifacts.
     */
    fun cleanTitle(raw: String, stripNoise: Boolean = true): String {
        var s = org.jsoup.parser.Parser.unescapeEntities(raw, false)
        s = s.replace('\u00A0', ' ')
        for (dec in DECORATIONS) {
            s = dec.replace(s) { m ->
                val group = m.groupValues[1]
                when {
                    YEARISH.matches(group.trim()) -> m.value   // keep "(2019)"
                    NOISE_GROUP.matches(group.trim()) -> " "   // drop decoration
                    else -> m.value                            // keep legit content
                }
            }
        }
        if (stripNoise) {
            s = s.replace(Regex("""\bep(isode)?s?\.?\s*\d+\s*[-–—~]\s*\d+\b(\s*complete)?""", RegexOption.IGNORE_CASE), " ")
            for (p in BARE_NOISE_PHRASES) s = p.replace(s, " ")
            // isolated "_" field separators left by the cuts
            s = s.replace(Regex("""\s+_\s*"""), " ")
        }
        // duplicate episode tokens: keep the LAST (the real drawer label)
        val eps = EPISODE_TOKEN.findAll(s).toList()
        if (eps.size >= 2) {
            val sb = StringBuilder(s)
            for (e in eps.dropLast(1).asReversed()) {
                sb.replace(e.range.first, e.range.last + 1, " ")
            }
            s = sb.toString()
        }
        s = DASH_EPISODE_JOIN.replace(s, " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        // dangling separators left behind by removed groups (no (?u) flag:
        // these classes contain no case-relevant letters, and embedded flags
        // are the <clinit> crash vector — see ENGINE RULE above)
        s = s.replace(Regex("""^[\s._\-–—]+"""), "")
        s = s.replace(Regex("""[\s._\-–—]+$"""), "")
        return s.trim()
    }

    /**
     * Filesystem-safe single path component. Native script is KEPT — only
     * chars that Windows/MTP/ext4 actually refuse, or control/zero-width
     * junk, are replaced ('_'). Cap is in CODE POINTS for maxChars plus a
     * hard 240-UTF-8-byte ceiling (leaves headroom under the 255-byte
     * per-component limit for a " (1)" suffix or the extension).
     */
    fun safeComponent(raw: String, maxChars: Int = 80): String {
        var s = raw
        s = s.replace(Regex("""[\u0000-\u001F\u007F]"""), "_")          // control chars
        s = s.replace(Regex("""[\u200B\u200C\u200D\u200E\u200F\uFEFF]"""), "") // zero-width/bidi marks
        s = s.replace(Regex("""[\\/:*?"<>|]"""), "_")                    // Windows-forbidden
        s = s.replace(Regex("""[\s]+"""), " ").trim()
        s = s.trimStart('.').trimEnd('.', ' ', '_')
        if (s.equals("..", ignoreCase = true) || s.isBlank()) s = "Download"
        if (s.uppercase() in RESERVED_NAMES) s = "_$s"
        if (s.length > maxChars) s = s.take(maxChars)
        // byte ceiling: cut at a codepoint boundary, then re-trim edges
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_COMPONENT_BYTES) {
            var cut = 0
            var byteCount = 0
            for (cp in s.codePoints()) {
                val cpBytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (byteCount + cpBytes > MAX_COMPONENT_BYTES) break
                cut += Character.charCount(cp)
                byteCount += cpBytes
            }
            s = s.substring(0, cut)
        }
        s = s.trimEnd('.', ' ', '_')
        return if (s.isBlank()) "Download" else s
    }

    /** THE one-liner every save path calls: noise-clean, then make safe.
     *  stripNoise=false for USER PROSE (captions, shared filenames). */
    fun savedName(raw: String, maxChars: Int = 80, stripNoise: Boolean = true): String =
        safeComponent(cleanTitle(raw, stripNoise), maxChars)

    /**
     * Cleans a raw scraped show title for directory creation.
     * Drops batch range noise ("Episode 1 - 13", "(Complete)", "Episode Added", etc.),
     * strips trailing episode markers, and normalizes colons to " - ".
     */
    fun cleanShowFolder(raw: String): String {
        var s = cleanTitle(raw, stripNoise = true)
        s = s.replace(Regex("""\s*:\s*"""), " - ")
        s = s.replace(Regex("""[\s_|\-]+ep(isode)?\.?\s*\d+\s*$""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""\bep(isode)?\.?\s*\d+\s*$""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = s.replace(Regex("""[\s._\-–—]+$"""), "").trim()
        return safeComponent(if (s.isBlank()) raw else s, maxChars = 80)
    }

    /**
     * Formats an episode file title: "{BaseShow} S{SS}E{EE}".
     * If episodeNum <= 0, or if the link is a single/multi-part movie, returns the clean show name.
     */
    fun formatEpisodeTitle(showTitle: String, episodeNum: Int, rawEpisodeLabel: String = ""): String {
        val cleanShow = cleanShowFolder(showTitle)
        if (episodeNum <= 0) {
            return safeComponent(cleanShow, maxChars = 80)
        }

        val rawClean = rawEpisodeLabel.trim()
        val partMatch = Regex("""\b(?:part|cd)\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(rawClean)
        val isMovieLabel = rawClean.matches(
            Regex("""^(full\s*movie|movie|download(\s*\d+)?|server\s*\d+|mirror\s*\d+|link\s*\d+|watch\s*now|watch\s*movie)$""", RegexOption.IGNORE_CASE)
        ) || rawClean.contains("Full Movie", ignoreCase = true) || rawClean.equals("Movie", ignoreCase = true)

        val seasonMatch = Regex(
            """\b(?:season\s*(\d+)|s(\d{1,2})|(\d+)(?:st|nd|rd|th)\s*season)\b""",
            RegexOption.IGNORE_CASE
        ).find(cleanShow)

        val explicitSm = Regex("""\bS(\d{1,2})[-_]?E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(rawClean)
        val epMatch = Regex("""\b(?:episode|ep|e)\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(rawClean)

        // Single movie / multi-part movie detection:
        // No season in show title, no explicit season/episode in raw label,
        // and either labelled as a movie/server link or episodeNum <= 1 with no episode token.
        if (seasonMatch == null && explicitSm == null && (isMovieLabel || (partMatch != null) || (epMatch == null && episodeNum <= 1 && !rawClean.contains("Episode", ignoreCase = true)))) {
            return if (partMatch != null) {
                safeComponent("$cleanShow Part ${partMatch.groupValues[1]}", maxChars = 80)
            } else {
                safeComponent(cleanShow, maxChars = 80)
            }
        }

        var baseShow = cleanShow.replace(
            Regex("""\s*\b(?:season\s*\d+|s\d{1,2}|\d+(?:st|nd|rd|th)\s*season)\b""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        baseShow = baseShow.replace(Regex("""[\s._\-–—]+$"""), "").trim()
        if (baseShow.isBlank()) baseShow = cleanShow

        // Season number: prioritize explicit SxxExx in raw label, then show title match, then episodeNum / 100
        val seasonNum = explicitSm?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: seasonMatch?.let { m ->
                (m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }).toIntOrNull()
            }
            ?: if (episodeNum >= 100) (episodeNum / 100) else 1

        val finalEpNum = explicitSm?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (episodeNum >= 100) (episodeNum % 100) else episodeNum

        val s = seasonNum.toString().padStart(2, '0')
        val e = finalEpNum.toString().padStart(2, '0')
        return safeComponent("$baseShow S${s}E${e}", maxChars = 80)
    }

    private const val MAX_COMPONENT_BYTES = 240

    /**
     * Windows-reserved device names: a file/folder named CON, PRN, AUX,
     * NUL, COM1-9 or LPT1-9 is un-creatable or unmountable on Windows/MTP
     * sync, which is how a phone's Downloads folder reaches a PC.
     */
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
}
