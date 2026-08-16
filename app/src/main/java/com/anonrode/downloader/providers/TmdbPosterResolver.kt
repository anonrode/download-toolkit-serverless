package com.anonrode.downloader.providers

import com.anonrode.downloader.data.net.HttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object TmdbPosterResolver {

    // In-memory cache for fast repeated queries
    private val posterCache = ConcurrentHashMap<String, String>()

    // Optional user-configured TMDB API key (configured in Settings or passed at runtime)
    var apiKey: String = ""

    suspend fun resolvePoster(rawTitle: String): String? {
        val clean = cleanTitleForSearch(rawTitle)
        if (clean.isBlank()) return null

        posterCache[clean]?.let { return it }

        val keyToUse = apiKey.ifBlank { "" }
        if (keyToUse.isBlank()) return null

        try {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$keyToUse&query=$encoded"
            val jsonStr = HttpClient.getText(url) ?: return null
            val root = JSONObject(jsonStr)
            val results = root.optJSONArray("results") ?: return null

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val posterPath = item.optString("poster_path")
                if (posterPath.isNotBlank() && posterPath != "null") {
                    val fullUrl = "https://image.tmdb.org/t/p/w342$posterPath"
                    posterCache[clean] = fullUrl
                    return fullUrl
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun cleanTitleForSearch(title: String): String {
        return title
            .replace(Regex("""(?i)(s\d{1,2}|season\s*\d+|episode\s*\d+|complete|download|korean\s*drama|asian\s*drama|hollywood|nollywood|movie|series)"""), "")
            .replace(Regex("""\((?:19|20)\d{2}\)"""), "")
            .replace(Regex("""[\[\]\(\)\|\-_]+"""), " ")
            .trim()
    }
}
