package com.anonrode.downloader.providers

import com.anonrode.downloader.data.net.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

data class AnimeOracleEntry(
    val canonicalTitle: String,
    val englishTitle: String?,
    val romajiTitle: String?,
    val synonyms: List<String> = emptyList()
)

/**
 * Kitsu title oracle used by the anime provider to widen a search
 * (romaji/English/canonical variants).
 *
 * Regression this guards (device logs 2026-10-19/20): `oracle: UNREACHABLE`
 * and multi-second search stalls while typing. The oracle runs on every
 * keystroke, and the old code issued one Kitsu request per call with no
 * negative memory and no coalescing — a slow/limited Kitsu turned into a
 * queue of blocking calls. Now:
 *  - negative results are cached for 10 minutes (no re-request per keystroke),
 *  - concurrent identical lookups share ONE in-flight request,
 *  - positive results keep the existing 256-entry LRU.
 */
object AnimeOracle {

    private const val MAX_CACHE_ENTRIES = 256
    private const val MAX_MISS_ENTRIES = 256
    private const val KITSU_TIMEOUT_MS = 5000L
    private const val MISS_TTL_MS = 10 * 60 * 1000L

    private val lruCache: MutableMap<String, AnimeOracleEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, AnimeOracleEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimeOracleEntry>?): Boolean {
                return size > MAX_CACHE_ENTRIES
            }
        }
    )

    /** key -> epoch millis until which the lookup is known to miss. */
    private val misses: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(MAX_MISS_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > MAX_MISS_ENTRIES
            }
        }
    )

    /** Concurrent identical lookups share one network call. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<AnimeOracleEntry?>>()

    suspend fun resolve(query: String): AnimeOracleEntry? {
        val trimmed = query.trim()
        if (trimmed.length < 2) return null
        val cacheKey = trimmed.lowercase()

        lruCache[cacheKey]?.let { return it }
        synchronized(misses) {
            val until = misses[cacheKey]
            if (until != null) {
                if (until > System.currentTimeMillis()) return null
                misses.remove(cacheKey)
            }
        }

        val mine = CompletableDeferred<AnimeOracleEntry?>()
        val existing = inFlight.putIfAbsent(cacheKey, mine)
        if (existing != null) return existing.await()

        return try {
            val result = fetchEntry(trimmed)
            if (result != null) {
                lruCache[cacheKey] = result
            } else {
                synchronized(misses) { misses[cacheKey] = System.currentTimeMillis() + MISS_TTL_MS }
            }
            mine.complete(result)
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            mine.complete(null)
            throw cancelled
        } catch (_: Exception) {
            // Transport failures are cached as a miss too: a rate-limited
            // Kitsu must not be retried on the next keystroke.
            synchronized(misses) { misses[cacheKey] = System.currentTimeMillis() + MISS_TTL_MS }
            mine.complete(null)
            null
        } finally {
            inFlight.remove(cacheKey, mine)
        }
    }

    private suspend fun fetchEntry(trimmed: String): AnimeOracleEntry? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(KITSU_TIMEOUT_MS) {
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

                AnimeOracleEntry(
                    canonicalTitle = canonical,
                    englishTitle = en,
                    romajiTitle = enJp,
                    synonyms = abbrevList
                )
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

    internal fun clearForTest() {
        lruCache.clear()
        synchronized(misses) { misses.clear() }
        inFlight.clear()
    }
}
