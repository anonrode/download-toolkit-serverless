package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.ShowCard

/**
 * Surgical explicit content filter.
 *
 * Designed to filter out hardcore porn, adult erotica, nude leaks, sex tapes,
 * JAV, hentai, 18+ adult OTT series (Ullu, Kooku, Vivamax), and adult taxonomy
 * posts from browse feeds (Trending, Genre grids) while STRICTLY PRESERVING
 * legitimate mainstream cinema and series that carry 18+, R, TV-MA, or Unrated
 * ratings (e.g. Deadpool, Game of Thrones, The Boys, Fifty Shades of Grey,
 * Sex Education, Adult Beginners, Saw X).
 *
 * Search queries bypass this filter completely per user requirement.
 */
object ExplicitContentFilter {

    /**
     * Mainstream titles or franchises that contain substrings like 'xxx'
     * but are legitimate non-pornographic studio releases.
     */
    private val MAINSTREAM_WHITELIST = Regex(
        """\bxxx(?::\s*|\s+)(?:return of\s+)?xander\s*cage\b|\bxxx(?::\s*|\s+)state of the union\b|\bxxx\s*\((?:19\d\d|2002)\)|\byoung\s*adult\b|\badult\s*beginners?\b|\bthe\s*adults\b|\bgame\s*of\s*thrones\b|\bthe\s*boys\b|\bsaw\s*x\b|\bjourney\s*of\s*love\b|\baction\s*hero\b|\blove\s*story\b|\bsungkyunkwan\s*scandal\b|\b(?:a\s+)?streetcar\s*named\s*desire\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The mainstream ABC show "Scandal" only: the word 'scandal' may head a
     * title when everything after it is season/episode/year/quality metadata
     * OR nothing but punctuation/space ("Scandal!", "Scandal:").
     * Adult Ullu-style titles that put a real word after 'scandal'
     * ("Scandal Queen Season 2") stay blocklisted — the broad whitelist form
     * used before let the filter exempt its own must-block titles.
     * Matched with .matches(), so the show name must lead the title; the
     * trailing separator-star sits OUTSIDE the metadata group because the
     * tokens inside it all demand at least one real token — a bare
     * punctuation tail would otherwise fail the full match and wrongly block
     * the show.
     */
    private val SCANDAL_SHOW_WHITELIST = Regex(
        """(?:the\s+)?scandal(?:(?:[\s\-–—:.|,+!()\[\]]*(?:(?:19|20)\d{2}|\d{1,3}|s\d{1,2}(?:e\d{1,3})?|seasons?|episodes?|complete|extended|standard|unrated|1080p|1080i|720p|720i|480p|web-?dl|web-?rip|hdtv|hdrip|hevc|x26[45]|nf|amzn|mkv|mp4|avi))+[\s\-–—:.|,+!()\[\]]*)?[\s\-–—:.|,+!()\[\]]*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * URL patterns representing adult categories, 18+ sections, or adult OTT platforms.
     */
    private val EXPLICIT_URL_REGEX = Regex(
        """/(?:18-section|18-plus|18plus|\+18|full-adult-video|adult|adult-movies?|erotic|erotica|erotic-stories|uncut|xxx|nsfw)/""" +
            // TRIAGE (B): the bare "18" alternative here also matched generic
            // numeric CMS ids (e.g. ".../item-18" = "Series Episode 18"), a
            // mainstream false positive the fix was never meant to catch —
            // only the explicit 18plus/+18 slug forms stay.
            """|[-_](?:18plus|\+18|xxx|uncut|adult)(?:-movie|-series|-video)?/?$""" +
            """|[-_]adult-(?:movie|series|video)/?$""" +
            """|/(?:ullu|kooku|voovi|primeplay|hotshots|cineprime|hunters|bigshots|moodx)/""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Surgical regex matching adult/pornographic title indicators.
     */
    private val EXPLICIT_TITLE_REGEX = Regex(
        """\b(?:""" +
            """xxx|""" +
            """porn(?:o|ography|star)?|""" +
            """hentai|""" +
            """jav(?:hd)?|""" +
            """erotica?|""" +
            """adults?|""" +
            """uncut|""" +
            """softcore|""" +
            """nsfw|""" +
            """lust(?:ful)?|""" +
            """sinful|""" +
            """desires?|""" +
            """scandals?|""" +
            """x-?rated|""" +
            """brazzers|""" +
            """naughty\s*america|""" +
            """bangbros|""" +
            """reality\s*kings|""" +
            """blacked|""" +
            """tushy|""" +
            """vixen|""" +
            """onlyfans\s*leak\w*|""" +
            """leaked\s*nudes?|""" +
            """nude\s*leaks?|""" +
            """celebrity\s*nudes?|""" +
            """sex\s*tape|""" +
            """sextape|""" +
            """hardcore\s*sex|""" +
            """uncensored\s*hentai|""" +
            """gangbang|""" +
            """blowjob|""" +
            """creampie|""" +
            """deepthroat|""" +
            """masturbat\w*|""" +
            """dildo|""" +
            """camgirl|""" +
            """chaturbate|""" +
            """threesomes?|""" +
            """ullu|""" +
            """kooku|""" +
            """voovi|""" +
            """primeplay|""" +
            """hotshots|""" +
            """cineprime|""" +
            """bigshots|""" +
            """moodx|""" +
            """hunters|""" +
            """charmsukh|""" +
            """palang\s*tod|""" +
            """siskiyaan|""" +
            """kavita\s*bhabhi|""" +
            """riti\s*riwaj|""" +
            """jalebi\s*bai|""" +
            """dunali|""" +
            """hotwife|""" +
            """cuckold|""" +
            """swinger|""" +
            """sensual\s*massage|""" +
            """erotic\s*(?:story|stories|positions?)|""" +
            """sex\s*positions?|""" +
            """bedroom\s*positions?|""" +
            """18\+\s*(?:adult|porn|sex|erotic)|""" +
            """adult\s*18\+|""" +
            """adult\s*(?:film|movie|video|content)|""" +
            """(?:nude|sex)\s*(?:\w+\s+)?scenes?|""" +
            """(?:leaked\s+)?nudes?\s*(?:pack|collection|leak|tape|video|pics?)""" +
        """)\b|""" +
        """(?:\[|\(|\b)(?:\+18|18\+|18\s*plus)(?:\]|\)|\b)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Exact normalized taxonomy term names that represent adult/porn categories.
     * Normalized by lowercase and removing non-alphanumeric characters.
     */
    private val ADULT_NORMALIZED_TERMS = setOf(
        "porn", "pornography", "adult", "adults", "erotica", "erotic", "hentai",
        "jav", "javhd", "xxx", "nsfw", "softcore", "hardcore", "xrated", "fulladultvideo",
        "18section", "18movies", "adultmovies", "eroticmovies", "18webseries", "18",
        "ullu", "kooku", "voovi", "primeplay", "hotshots", "hunters", "uncut",
        "lust", "sinful", "desire", "scandal"
    )

    /**
     * Returns true if the title or associated category terms indicate explicit
     * pornographic/adult content.
     */
    fun isExplicit(title: String, categories: Collection<String>? = null): Boolean {
        if (MAINSTREAM_WHITELIST.containsMatchIn(title)) return false
        if (SCANDAL_SHOW_WHITELIST.matches(title.trim())) return false
        if (EXPLICIT_TITLE_REGEX.containsMatchIn(title)) return true

        if (!categories.isNullOrEmpty()) {
            for (cat in categories) {
                val rawLower = cat.lowercase().trim()
                if (rawLower.contains("18+") || rawLower.contains("+18") ||
                    rawLower.contains("18-plus") || rawLower.contains("18 plus") ||
                    rawLower.contains("full adult video") || rawLower.contains("18-section") ||
                    rawLower.contains("18 section") || rawLower.contains("sex tape") ||
                    rawLower.contains("leaked nudes")
                ) {
                    return true
                }
                if (rawLower.contains("adult") && !rawLower.contains("young adult") && !rawLower.contains("adult beginner")) {
                    return true
                }
                if (rawLower.contains("erotic") || rawLower.contains("porn") ||
                    rawLower.contains("hentai") || rawLower.contains("jav") ||
                    rawLower.contains("softcore") || rawLower.contains("hardcore") ||
                    rawLower.contains("x-rated") || rawLower.contains("nsfw") ||
                    rawLower.contains("uncut") || rawLower.contains("ullu") ||
                    rawLower.contains("kooku") || rawLower.contains("voovi") ||
                    rawLower.contains("primeplay") || rawLower.contains("hotshots") ||
                    rawLower.contains("hunters") || rawLower.contains("sinful") ||
                    rawLower.contains("desire") || rawLower.contains("scandal") ||
                    rawLower.contains("lust")
                ) {
                    return true
                }
                val clean = rawLower.filter { it.isLetterOrDigit() }
                if (clean in ADULT_NORMALIZED_TERMS) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Checks if a [ShowCard] represents explicit content based on its URL,
     * title, and associated taxonomy tags.
     */
    fun isExplicit(card: ShowCard): Boolean {
        // Mainstream whitelist check takes precedence
        if (MAINSTREAM_WHITELIST.containsMatchIn(card.title)) return false
        // Canonical "Scandal" series (title + metadata only) — the adult
        // 'Scandal <word>' compounds must NOT reach this whitelist.
        if (SCANDAL_SHOW_WHITELIST.matches(card.title.trim())) return false

        // 1. Fast URL check (blocks 18-section, -18/ slugs, adult directories, /adult/, /erotic/, /uncut/)
        if (card.url.isNotBlank() && EXPLICIT_URL_REGEX.containsMatchIn(card.url)) return true

        // 2. Aggregate tags: card.genres + card.tags + card.category (if not generic Drama/Movies)
        val allCats = (card.genres + card.tags + listOf(card.category)).filter {
            it.isNotBlank() && it != "Drama" && it != "Movies"
        }
        return isExplicit(card.title, if (allCats.isNotEmpty()) allCats else null)
    }

    /**
     * Returns a list excluding any cards identified as explicit.
     */
    fun filterSafe(cards: List<ShowCard>): List<ShowCard> =
        cards.filterNot { isExplicit(it) }
}
