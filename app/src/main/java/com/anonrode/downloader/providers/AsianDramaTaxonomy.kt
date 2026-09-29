package com.anonrode.downloader.providers

import com.anonrode.downloader.data.models.ShowCard

enum class DramaRegion(val label: String, val tag: String) {
    KDRAMA("K-Drama", "korean"),
    CDRAMA("C-Drama", "chinese")
}

enum class DramaEra(val label: String, val tag: String) {
    MODERN("Modern", "modern"),
    HISTORICAL("Historical", "historical")
}

enum class DramaStatusFilter(val label: String) {
    ALL("All"),
    COMPLETED("Completed"),
    ONGOING("Ongoing")
}

data class DramaGenre(
    val id: String,
    val label: String,
    val era: DramaEra,
    val region: DramaRegion,
    val queryTerms: Map<String, String> = emptyMap(),
    val aliases: Set<String> = emptySet()
)

data class DramaClassification(
    val region: DramaRegion,
    val era: DramaEra,
    val status: DramaStatusFilter,
    val genres: Set<String>
)

object AsianDramaTaxonomy {

    // Curated sub-genres for K-Drama Modern
    val KDRAMA_MODERN_GENRES = listOf(
        DramaGenre("all", "All", DramaEra.MODERN, DramaRegion.KDRAMA),
        DramaGenre("romance", "Romance & Rom-Com", DramaEra.MODERN, DramaRegion.KDRAMA, aliases = setOf("romance", "romcom", "romantic", "love")),
        DramaGenre("thriller", "Thriller & Crime", DramaEra.MODERN, DramaRegion.KDRAMA, aliases = setOf("thriller", "crime", "investigation", "mystery", "police", "prosecutor")),
        DramaGenre("action", "Action & Revenge", DramaEra.MODERN, DramaRegion.KDRAMA, aliases = setOf("action", "revenge", "fight")),
        DramaGenre("comedy", "Comedy & Life", DramaEra.MODERN, DramaRegion.KDRAMA, aliases = setOf("comedy", "sitcom", "slice of life", "life")),
        DramaGenre("fantasy", "Fantasy & Supernatural", DramaEra.MODERN, DramaRegion.KDRAMA, aliases = setOf("fantasy", "supernatural", "sci-fi", "time travel")),
        DramaGenre("anime", "Anime & Aeni", DramaEra.MODERN, DramaRegion.KDRAMA, aliases = setOf("anime", "animation", "aeni", "manhwa"))
    )

    // Curated sub-genres for K-Drama Historical (Sageuk)
    val KDRAMA_HISTORICAL_GENRES = listOf(
        DramaGenre("all", "All", DramaEra.HISTORICAL, DramaRegion.KDRAMA),
        DramaGenre("sageuk_romance", "Sageuk Romance", DramaEra.HISTORICAL, DramaRegion.KDRAMA, aliases = setOf("romance", "romantic", "love")),
        DramaGenre("palace", "Palace & Politics", DramaEra.HISTORICAL, DramaRegion.KDRAMA, aliases = setOf("politics", "palace", "historical", "dynasty", "king", "queen", "prince")),
        DramaGenre("martial", "Action & Martial Arts", DramaEra.HISTORICAL, DramaRegion.KDRAMA, aliases = setOf("action", "martial arts", "sword", "warrior")),
        DramaGenre("supernatural", "Supernatural Sageuk", DramaEra.HISTORICAL, DramaRegion.KDRAMA, aliases = setOf("supernatural", "fantasy", "ghost", "zombie"))
    )

    // Curated sub-genres for C-Drama Modern
    val CDRAMA_MODERN_GENRES = listOf(
        DramaGenre("all", "All", DramaEra.MODERN, DramaRegion.CDRAMA),
        DramaGenre("romance", "Urban & Romance", DramaEra.MODERN, DramaRegion.CDRAMA, aliases = setOf("romance", "urban", "workplace", "ceo", "love")),
        DramaGenre("youth", "Youth & E-Sports", DramaEra.MODERN, DramaRegion.CDRAMA, aliases = setOf("youth", "school", "campus", "esports", "gaming")),
        DramaGenre("thriller", "Thriller & Suspense", DramaEra.MODERN, DramaRegion.CDRAMA, aliases = setOf("thriller", "suspense", "crime", "mystery")),
        DramaGenre("scifi", "Sci-Fi & Fantasy", DramaEra.MODERN, DramaRegion.CDRAMA, aliases = setOf("sci-fi", "fantasy", "time travel", "loop")),
        DramaGenre("donghua", "Donghua (Anime)", DramaEra.MODERN, DramaRegion.CDRAMA, aliases = setOf("donghua", "animation", "anime", "3d animation"))
    )

    // Curated sub-genres for C-Drama Historical (Costume/Period)
    val CDRAMA_HISTORICAL_GENRES = listOf(
        DramaGenre("all", "All", DramaEra.HISTORICAL, DramaRegion.CDRAMA),
        DramaGenre("palace", "Palace Romance", DramaEra.HISTORICAL, DramaRegion.CDRAMA, aliases = setOf("palace", "romance", "costume", "emperor", "empress")),
        DramaGenre("wuxia", "Wuxia (Martial Heroes)", DramaEra.HISTORICAL, DramaRegion.CDRAMA, aliases = setOf("wuxia", "martial arts", "sect", "swordsman")),
        DramaGenre("xianxia", "Xianxia (Gods & Cultivation)", DramaEra.HISTORICAL, DramaRegion.CDRAMA, aliases = setOf("xianxia", "cultivation", "immortal", "deity", "demon")),
        DramaGenre("court", "Court Cases & Mystery", DramaEra.HISTORICAL, DramaRegion.CDRAMA, aliases = setOf("mystery", "court", "investigation", "judge")),
        DramaGenre("republican", "Republican Era", DramaEra.HISTORICAL, DramaRegion.CDRAMA, aliases = setOf("republican", "military", "warlord", "1920s", "1930s"))
    )

    fun genresFor(region: DramaRegion, era: DramaEra): List<DramaGenre> {
        return when (region) {
            DramaRegion.KDRAMA -> if (era == DramaEra.MODERN) KDRAMA_MODERN_GENRES else KDRAMA_HISTORICAL_GENRES
            DramaRegion.CDRAMA -> if (era == DramaEra.MODERN) CDRAMA_MODERN_GENRES else CDRAMA_HISTORICAL_GENRES
        }
    }
}

object DramaTagClassifier {

    /**
     * Canonical anchor dictionary for top classic titles ensuring 100% precision.
     */
    private val ANCHOR_TITLES = mapOf(
        "the untamed" to DramaClassification(DramaRegion.CDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("wuxia", "xianxia", "palace")),
        "word of honor" to DramaClassification(DramaRegion.CDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("wuxia")),
        "till the end of the moon" to DramaClassification(DramaRegion.CDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("xianxia", "palace")),
        "love between fairy and devil" to DramaClassification(DramaRegion.CDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("xianxia", "palace")),
        "joy of life" to DramaClassification(DramaRegion.CDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("wuxia", "court")),
        "the blood of youth" to DramaClassification(DramaRegion.CDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("wuxia")),
        "hidden love" to DramaClassification(DramaRegion.CDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("romance", "youth")),
        "reset" to DramaClassification(DramaRegion.CDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("thriller", "scifi")),
        "falling into your smile" to DramaClassification(DramaRegion.CDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("youth", "romance")),
        "meet yourself" to DramaClassification(DramaRegion.CDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("romance")),

        // K-Dramas
        "mr queen" to DramaClassification(DramaRegion.KDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("sageuk_romance", "palace")),
        "mr. queen" to DramaClassification(DramaRegion.KDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("sageuk_romance", "palace")),
        "the red sleeve" to DramaClassification(DramaRegion.KDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("sageuk_romance", "palace")),
        "kingdom" to DramaClassification(DramaRegion.KDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("supernatural", "martial")),
        "under the queens umbrella" to DramaClassification(DramaRegion.KDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("palace")),
        "alchemy of souls" to DramaClassification(DramaRegion.KDRAMA, DramaEra.HISTORICAL, DramaStatusFilter.COMPLETED, setOf("supernatural", "martial")),
        "the glory" to DramaClassification(DramaRegion.KDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("thriller", "action")),
        "vincenzo" to DramaClassification(DramaRegion.KDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("action", "thriller", "comedy")),
        "crash landing on you" to DramaClassification(DramaRegion.KDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("romance", "comedy")),
        "queen of tears" to DramaClassification(DramaRegion.KDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("romance", "comedy")),
        "solo leveling" to DramaClassification(DramaRegion.KDRAMA, DramaEra.MODERN, DramaStatusFilter.COMPLETED, setOf("anime", "action"))
    )

    private val HISTORICAL_KEYWORDS = setOf(
        "historical", "costume", "period", "sageuk", "joseon", "goryeo", "dynasty",
        "wuxia", "xianxia", "palace", "emperor", "empress", "concubine", "imperial",
        "crown prince", "king", "queen", "swordsman", "cultivation", "immortal",
        "deity", "sect", "ancient", "republican"
    )

    private val ANIME_KEYWORDS = setOf(
        "donghua", "anime", "animation", "aeni", "animated", "3d animation"
    )

    fun cleanLookupKey(title: String): String =
        title.lowercase()
            .replace(Regex("""\[.*?\]|\(.*?\)|season\s*\d+|episode\s*\d+.*"""), "")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    fun classify(card: ShowCard, defaultRegion: DramaRegion): DramaClassification {
        val cleanKey = cleanLookupKey(card.title)
        ANCHOR_TITLES[cleanKey]?.let { return it }

        // 1. Region
        val region = determineRegion(card, defaultRegion)

        // 2. Airing Status
        val status = determineStatus(card)

        // 3. Era
        val era = determineEra(card)

        // 4. Genres
        val genres = mutableSetOf<String>()
        val combinedText = "${card.title} ${card.tags.joinToString(" ")}".lowercase()

        for (genre in AsianDramaTaxonomy.genresFor(region, era)) {
            if (genre.id == "all") continue
            for (alias in genre.aliases) {
                val pattern = Regex("(?i)\\b" + Regex.escape(alias) + "\\b")
                if (pattern.containsMatchIn(combinedText)) {
                    genres.add(genre.id)
                    break
                }
            }
        }

        return DramaClassification(region, era, status, genres)
    }

    private fun determineRegion(card: ShowCard, defaultRegion: DramaRegion): DramaRegion {
        val lower = "${card.title} ${card.tags.joinToString(" ")} ${card.url}".lowercase()
        if (lower.contains("chinese") || lower.contains("c-drama") || lower.contains("cdrama") ||
            lower.contains("wuxia") || lower.contains("xianxia") || lower.contains("donghua")
        ) {
            return DramaRegion.CDRAMA
        }
        if (lower.contains("korean") || lower.contains("k-drama") || lower.contains("kdrama") ||
            lower.contains("sageuk") || lower.contains("joseon") || lower.contains("aeni")
        ) {
            return DramaRegion.KDRAMA
        }
        return defaultRegion
    }

    private fun determineStatus(card: ShowCard): DramaStatusFilter {
        val text = "${card.title} ${card.tags.joinToString(" ")}".lowercase()
        if (text.contains("(complete)") || text.contains("[complete]") ||
            text.contains("completed") || text.contains("status: completed")
        ) {
            return DramaStatusFilter.COMPLETED
        }
        if (text.contains("ongoing") || text.contains("status: ongoing") ||
            Regex("""\bepisode\s*\d+\s*[-–]\s*\d+\s*added\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            return DramaStatusFilter.ONGOING
        }
        return DramaStatusFilter.ALL
    }

    private fun determineEra(card: ShowCard): DramaEra {
        val lower = "${card.title} ${card.tags.joinToString(" ")} ${card.url}".lowercase()
        for (kw in HISTORICAL_KEYWORDS) {
            val pattern = Regex("(?i)\\b" + Regex.escape(kw) + "\\b")
            if (pattern.containsMatchIn(lower)) return DramaEra.HISTORICAL
        }
        return DramaEra.MODERN
    }

    fun matchesFilter(
        card: ShowCard,
        region: DramaRegion,
        era: DramaEra,
        statusFilter: DramaStatusFilter,
        genreFilter: DramaGenre?
    ): Boolean {
        val classification = classify(card, region)

        if (classification.region != region) return false
        if (classification.era != era) return false

        if (statusFilter != DramaStatusFilter.ALL &&
            classification.status != DramaStatusFilter.ALL &&
            classification.status != statusFilter
        ) {
            return false
        }

        if (genreFilter != null && genreFilter.id != "all") {
            if (!classification.genres.contains(genreFilter.id)) return false
        }

        return true
    }
}
