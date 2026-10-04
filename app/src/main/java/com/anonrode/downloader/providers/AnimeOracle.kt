package com.anonrode.downloader.providers

import com.anonrode.downloader.data.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap

data class AnimeOracleEntry(
    val canonicalTitle: String,
    val englishTitle: String?,
    val romajiTitle: String?,
    val synonyms: List<String> = emptyList()
)

object AnimeOracle {

    private const val MAX_CACHE_ENTRIES = 256
    private const val KITSU_TIMEOUT_MS = 3500L

    private val lruCache: MutableMap<String, AnimeOracleEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, AnimeOracleEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimeOracleEntry>?): Boolean {
                return size > MAX_CACHE_ENTRIES
            }
        }
    )

    suspend fun resolve(query: String): AnimeOracleEntry? {
        val trimmed = query.trim()
        if (trimmed.length < 2) return null
        val cacheKey = trimmed.lowercase()

        lruCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(KITSU_TIMEOUT_MS) {
                try {
                    val encoded = URLEncoder.encode(trimmed, "UTF-8")
                    val url = "https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=1"
                    val headers = mapOf(
                        "Accept" to "application/vnd.api+json",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    )
                    val body = HttpClient.getText(
                        url = url,
                        headers = headers,
                        tag = "anime-oracle",
                        permissive = true
                    ) ?: return@withTimeoutOrNull null

                    val json = JSONObject(body)
                    val data = json.optJSONArray("data") ?: return@withTimeoutOrNull null
                    if (data.length() == 0) return@withTimeoutOrNull null

                    val first = data.optJSONObject(0) ?: return@withTimeoutOrNull null
                    val attrs = first.optJSONObject("attributes") ?: return@withTimeoutOrNull null
                    val canonical = attrs.optString("canonicalTitle").trim()
                    if (canonical.isBlank()) return@withTimeoutOrNull null

                    val titles = attrs.optJSONObject("titles")
                    val en = titles?.optString("en")?.trim()?.ifBlank { null }
                    val enJp = titles?.optString("en_jp")?.trim()?.ifBlank { null }

                    val abbrevList = mutableListOf<String>()
                    val abbrevs = attrs.optJSONArray("abbreviatedTitles")
                    if (abbrevs != null) {
                        for (i in 0 until abbrevs.length()) {
                            val ab = abbrevs.optString(i).trim()
                            if (ab.isNotBlank()) abbrevList.add(ab)
                        }
                    }

                    val entry = AnimeOracleEntry(
                        canonicalTitle = canonical,
                        englishTitle = en,
                        romajiTitle = enJp,
                        synonyms = abbrevList
                    )
                    lruCache[cacheKey] = entry
                    entry
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    suspend fun expandTerms(coreQuery: String): List<String> {
        val trimmed = coreQuery.trim()
        if (trimmed.isBlank()) return emptyList()

        val candidates = mutableListOf<String>()
        candidates.add(trimmed)

        val oracle = resolve(trimmed)
        if (oracle != null) {
            oracle.romajiTitle?.let { if (it.isNotBlank()) candidates.add(it) }
            oracle.englishTitle?.let { if (it.isNotBlank()) candidates.add(it) }
            if (oracle.canonicalTitle.isNotBlank()) candidates.add(oracle.canonicalTitle)
        }

        val seen = mutableSetOf<String>()
        val distinctTerms = mutableListOf<String>()
        for (item in candidates) {
            val norm = item.trim().lowercase()
            if (norm.length >= 2 && seen.add(norm)) {
                distinctTerms.add(item.trim())
            }
        }
        return distinctTerms
    }
}
