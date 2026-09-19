package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.ShowCard

/**
 * Surgical explicit content filter.
 *
 * Designed to filter out hardcore porn, adult erotica, nude leaks, sex tapes,
 * JAV, hentai, and adult taxonomy posts from browse feeds (Trending, Genre
 * grids) while STRICTLY PRESERVING legitimate mainstream cinema and series that
 * carry 18+, R, TV-MA, or Unrated ratings (e.g. Deadpool, Game of Thrones,
 * The Boys, Fifty Shades of Grey, Sex Education, Adult Beginners, Saw X).
 *
 * Search queries bypass this filter completely per user requirement.
 */
object ExplicitContentFilter {

    /**
     * Mainstream titles or franchises that contain substrings like 'xxx'
     * but are legitimate non-pornographic studio releases.
     */
    private val MAINSTREAM_WHITELIST = Regex(
        """\bxxx(?::\s*|\s+)(?:return of\s+)?xander\s*cage\b|\bxxx(?::\s*|\s+)state of the union\b|\bxxx\s*\((?:19\d\d|2002)\)""",
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
            """erotica|""" +
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
            """18\+\s*(?:adult|porn|sex|erotic)|""" +
            """adult\s*18\+|""" +
            """adult\s*(?:film|movie|video|content)|""" +
            """(?:nude|sex)\s*(?:\w+\s+)?scenes?|""" +
            """(?:leaked\s+)?nudes?\s*(?:pack|collection|leak|tape|video|pics?)""" +
        """)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Exact normalized taxonomy term names that represent adult/porn categories.
     * Normalized by lowercase and removing non-alphanumeric characters.
     */
    private val ADULT_NORMALIZED_TERMS = setOf(
        "porn", "pornography", "adult", "adults", "erotica", "erotic", "hentai",
        "jav", "javhd", "xxx", "nsfw", "softcore", "hardcore", "xrated", "fulladultvideo",
        "18section", "18movies"
    )

    /**
     * Returns true if the title or associated category terms indicate explicit
     * pornographic/adult content.
     */
    fun isExplicit(title: String, categories: Collection<String>? = null): Boolean {
        if (MAINSTREAM_WHITELIST.containsMatchIn(title)) return false
        if (EXPLICIT_TITLE_REGEX.containsMatchIn(title)) return true

        if (!categories.isNullOrEmpty()) {
            for (cat in categories) {
                val rawLower = cat.lowercase().trim()
                if (rawLower.contains("[+18] section") || rawLower.contains("full adult video") ||
                    rawLower.contains("18+ adult") || rawLower.contains("adult 18+") ||
                    rawLower.contains("sex tape") || rawLower.contains("leaked nudes")
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
     * Checks if a [ShowCard] represents explicit content based on its title
     * and category property.
     */
    fun isExplicit(card: ShowCard): Boolean {
        val cats = if (card.category.isNotBlank() && card.category != "Drama" && card.category != "Movies") {
            listOf(card.category)
        } else {
            null
        }
        return isExplicit(card.title, cats)
    }

    /**
     * Returns a list excluding any cards identified as explicit.
     */
    fun filterSafe(cards: List<ShowCard>): List<ShowCard> =
        cards.filterNot { isExplicit(it) }
}
