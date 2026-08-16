package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard
import java.util.regex.Pattern

object RelevanceScorer {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "s01", "s02", "complete", "season", "episode", "ep", "korean", "drama", "series"
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
        val q = tokenize(query)
        if (q.isEmpty()) return 1.0
        val t = tokenize(title)
        if (t.isEmpty()) return 0.0

        val overlap = (q.intersect(t).size).toDouble() / q.size.toDouble()
        val qClean = query.trim().lowercase()
        val tClean = title.trim().lowercase()

        var finalScore = overlap
        if (tClean.contains(qClean)) {
            finalScore = (finalScore + 0.5).coerceAtMost(1.0)
        }
        return finalScore
    }

    fun filterAndSort(query: String, items: List<ShowCard>): List<ShowCard> {
        val validItems = items.filter { item ->
            com.anonrode.downloader.security.TorrentSecurityShield.checkNegativeFilters(item.title, query).first
        }

        val scored = validItems.mapNotNull { item ->
            if (item.site.equals("pluto", ignoreCase = true) ||
                item.site.equals("dramakey", ignoreCase = true) ||
                item.site.equals("dramarain", ignoreCase = true)) {
                return@mapNotNull Pair(item, 1.0)
            }
            val sc = score(query, item.title)
            if (sc >= RELEVANCE_MIN) Pair(item, sc) else null
        }

        val sorted = scored.sortedWith(
            compareByDescending<Pair<ShowCard, Double>> { it.second }
                .thenBy { orderKey(it.first) }
        )

        return sorted.map { it.first }.distinctBy { it.url }
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
