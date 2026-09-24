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
        """(?i)\b(?:episode|ep\.?|s\d+e\d+|e\d+|part|chapter)\s*\d+\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val SEASON_MARKER = Pattern.compile("""(?i)\bseason\s*(\d+)\b""", Pattern.CASE_INSENSITIVE)
    private val EPISODE_URL = Pattern.compile(
        """(?i)/watch/(?:tv|movie)/\d+/\d+(?:/\d+)?(?:/|$)""",
        Pattern.CASE_INSENSITIVE
    )
    private val YEAR_MARKER = Pattern.compile("""\b(?:19|20)\d{2}\b""")

    private fun isEpisodeCard(item: ShowCard): Boolean {
        val text = "${item.title} ${item.url}"
        return EPISODE_MARKER.matcher(text).find() ||
            EPISODE_URL.matcher(item.url).find()
    }

    private fun showKey(item: ShowCard): String {
        val base = EPISODE_MARKER.matcher(item.title).replaceAll(" ")
            .replace(Regex("""(?i)\bseason\s*\d+\b"""), " ")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in STOP_WORDS }
            .joinToString(" ")
        val year = YEAR_MARKER.matcher(item.title).let { m -> if (m.find()) m.group() else "" }
        val season = SEASON_MARKER.matcher(item.title).let { m -> if (m.find()) m.group(1) else "" }
        val suffix = listOf(year, season).filter { it.isNotBlank() }.joinToString("|")
        return if (suffix.isNotBlank()) "$base|$suffix" else base
    }

    private fun preferShows(items: List<Pair<ShowCard, Double>>): List<ShowCard> {
        val groups = LinkedHashMap<String, MutableList<Pair<ShowCard, Double>>>()
        items.forEach { grouped ->
            groups.getOrPut(showKey(grouped.first)) { mutableListOf() }.add(grouped)
        }
        val out = mutableListOf<ShowCard>()
        groups.values.forEach { group ->
            val shows = group.filterNot { isEpisodeCard(it.first) }
            if (shows.isNotEmpty()) {
                out += shows.maxByOrNull { it.second }!!.first
            } else {
                out += group.map { it.first }
            }
        }
        return out
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
            compareByDescending<Pair<ShowCard, Double>> { it.second }
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
