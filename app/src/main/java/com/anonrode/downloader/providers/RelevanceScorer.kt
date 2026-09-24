package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import java.util.regex.Pattern

object RelevanceScorer {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "s01", "s02", "complete", "season"
    )

    private val COLLECTION_RE = Pattern.compile(
        """\b(collection|complete|all[\s-]*parts?|anthology|1[\s-]*[-–][\s-]*\d)\b""",
        Pattern.CASE_INSENSITIVE
    )

    private val YEAR_RANGE_RE = Pattern.compile(
        """\b(?:19|20)\d{2}\s*[-–]\s*(?:19|20)\d{2}\b"""
    )

    private val ORDER_KW_RE = Pattern.compile(
        """\b(?:part|chapter|chap|vol|volume|season|episode|ep)\s*(\d{1,2})\b""",
        Pattern.CASE_INSENSITIVE
    )

    private const val RELEVANCE_MIN = 0.50

    fun tokenize(text: String): Set<String> {
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        return tokens.filter { it !in STOP_WORDS && it.length > 1 }.toSet()
    }

    fun score(query: String, title: String): Double {
        val qClean = query.trim().lowercase()
        val tClean = title.trim().lowercase()
        val q = tokenize(query)
        if (q.isEmpty()) {
            return if (qClean.isNotEmpty() && tClean.contains(qClean)) 1.0 else 0.0
        }
        val t = tokenize(title)
        if (t.isEmpty()) return 0.0

        val overlap = (q.intersect(t).size).toDouble() / q.size.toDouble()

        var finalScore = overlap
        if (tClean.contains(qClean)) {
            finalScore = (finalScore + 0.5).coerceAtMost(1.0)
        }
        return finalScore
    }

    private val EPISODE_MARKER = Pattern.compile(
        """(?i)\b(?:(?:episode|ep\.?)\s*\d+|s\d+e\d+|e\d+)\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val PART_OR_CHAPTER_MARKER = Pattern.compile(
        """(?i)\b(?:part|chapter)\s*\d+\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val SEASON_MARKER = Pattern.compile("""(?i)\bseason\s*(\d+)\b""", Pattern.CASE_INSENSITIVE)
    private val EPISODE_URL = Pattern.compile(
        """(?i)/watch/(?:tv|movie)/\d+/\d+/\d+(?:/|$)""",
        Pattern.CASE_INSENSITIVE
    )
    private val SEASON_HUB_URL = Pattern.compile(
        """(?i)/watch/(?:tv|movie)/\d+/\d+(?:/|$)""",
        Pattern.CASE_INSENSITIVE
    )
    private val TV_SHOW_URL = Pattern.compile(
        """(?i)/watch/tv/\d+(?:[?#]|$)""",
        Pattern.CASE_INSENSITIVE
    )
    private val MOVIE_URL = Pattern.compile(
        """(?i)/watch/movie/\d+(?:[?#]|$)""",
        Pattern.CASE_INSENSITIVE
    )
    private val YEAR_MARKER = Pattern.compile("""\b(?:19|20)\d{2}\b""")

    private enum class ResultKind(val rank: Int) {
        TV_SHOW(0),
        SEASON_HUB(1),
        MOVIE(2),
        UNKNOWN(3),
        EPISODE(4)
    }

    private fun classify(item: ShowCard): ResultKind {
        if (EPISODE_URL.matcher(item.url).find() || EPISODE_MARKER.matcher(item.title).find()) {
            return ResultKind.EPISODE
        }
        val isMovie = MOVIE_URL.matcher(item.url).find() || item.category.contains("movie", ignoreCase = true)
        if (PART_OR_CHAPTER_MARKER.matcher(item.title).find() && !isMovie) {
            return ResultKind.EPISODE
        }
        if (SEASON_HUB_URL.matcher(item.url).find() || SEASON_MARKER.matcher(item.title).find()) {
            return ResultKind.SEASON_HUB
        }
        if (TV_SHOW_URL.matcher(item.url).find() ||
            item.category.contains("tv", ignoreCase = true) ||
            item.category.contains("series", ignoreCase = true) ||
            item.category.contains("show", ignoreCase = true)
        ) return ResultKind.TV_SHOW
        if (isMovie) {
            return ResultKind.MOVIE
        }
        return ResultKind.UNKNOWN
    }

    private fun showKey(item: ShowCard): String {
        val kind = classify(item)
        val base = EPISODE_MARKER.matcher(item.title).replaceAll(" ")
            .let { PART_OR_CHAPTER_MARKER.matcher(it).replaceAll(" ") }
            .replace(Regex("""(?i)\bseason\s*\d+\b"""), " ")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in STOP_WORDS }
            .joinToString(" ")
        val titleYear = YEAR_MARKER.matcher(item.title).let { m -> if (m.find()) m.group() else "" }
        val year = item.year.ifBlank { titleYear }
        val season = SEASON_MARKER.matcher(item.title).let { m -> if (m.find()) m.group(1) else "" }
        val suffix = listOf(year, season).filter { it.isNotBlank() }.joinToString("|")
        return "${item.site.lowercase()}|$kind|$base|$suffix"
    }

    /**
     * Collapse only proven duplicates of the same non-episode content within
     * one provider. Episodes are never collapsed: when a series exists they
     * remain available underneath it, and providers that only return episodes
     * keep all of their results.
     */
    private fun preferShows(items: List<Pair<ShowCard, Double>>): List<ShowCard> {
        val seenContent = mutableSetOf<String>()
        return items.map { it.first }.filter { card ->
            if (classify(card) == ResultKind.EPISODE) return@filter true
            seenContent.add(showKey(card))
        }
    }

    fun filterAndSort(query: String, items: List<ShowCard>): List<ShowCard> {
        val validItems = items.filter { item ->
            com.anonrode.downloader.security.TorrentSecurityShield.checkNegativeFilters(item.title, query).first
        }

        val scored = validItems.mapNotNull { item ->
            val sc = score(query, item.title)
            if (sc >= RELEVANCE_MIN) Pair(item, sc) else null
        }

        val sorted = scored.sortedWith(
            compareBy<Pair<ShowCard, Double>> { classify(it.first).rank }
                .thenByDescending { it.second }
                .thenBy { orderKey(it.first) }
        )

        return preferShows(sorted).distinctBy { it.url }
    }

    private fun orderKey(item: ShowCard): Int {
        val combined = "${item.title} ${item.url}".lowercase()
        val isCollection = COLLECTION_RE.matcher(combined).find() || YEAR_RANGE_RE.matcher(combined).find()
        if (isCollection) return 0

        val m = ORDER_KW_RE.matcher(item.title.lowercase())
        if (m.find()) {
            return m.group(1)?.toIntOrNull() ?: 9999
        }
        return 9999
    }
}
