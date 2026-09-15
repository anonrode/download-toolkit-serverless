package com.anonrode.downloader.providers

import android.content.Context
import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk memory for the home feeds (v3.1.6 — user: "close it and open again,
 * the last stuff will still be there… it will refresh when it gets the
 * updated stuff").
 *
 * Every group (trending row, each genre's mixed card list, genre tile
 * posters) carries its OWN timestamp: a 30-minute-fresh group is painted
 * from disk with ZERO network, a stale one paints instantly and refetches
 * silently in the background, a manual refresh always forces. That shape is
 * what kills both the blank screen AND the awkward spinner without ever
 * showing a row that's days old without a refetch attempt behind it.
 *
 * Storage follows the proven DownloadRepository pattern verbatim:
 * filesDir JSON, tolerant kotlinx config, atomic tmp+rename, corrupt file
 * deletes itself on read (a broken cache must never brick the home screen),
 * all IO on Dispatchers.IO behind a Mutex.
 */
@Serializable
data class CategoryEntry(
    val savedAtMs: Long = 0L,
    val cards: List<ShowCard> = emptyList()
)

@Serializable
data class TilePoster(
    val label: String,
    val posterUrl: String
)

@Serializable
data class FeedSnapshot(
    val trending: List<ShowCard> = emptyList(),
    val trendingAtMs: Long = 0L,
    val categories: Map<String, CategoryEntry> = emptyMap(),
    val tiles: List<TilePoster> = emptyList(),
    val tilesAtMs: Long = 0L
) {
    fun trendingAgeMs(nowMs: Long): Long = ageOf(trendingAtMs, nowMs)
    fun categoryAgeMs(label: String, nowMs: Long): Long =
        ageOf(categories[label]?.savedAtMs ?: 0L, nowMs)
    fun tilesAgeMs(nowMs: Long): Long = ageOf(tilesAtMs, nowMs)

    companion object {
        /** Never-fresh sentinel for "no entry" so callers branch on one value. */
        const val NEVER: Long = Long.MAX_VALUE
        fun ageOf(savedAtMs: Long, nowMs: Long): Long =
            if (savedAtMs <= 0L) NEVER else (nowMs - savedAtMs).coerceAtLeast(0L)
    }
}

object FeedCache {

    const val FRESH_MS = 30L * 60_000L

    private val json = Json {
        ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    @Volatile private var file: File? = null
    @Volatile var snapshot: FeedSnapshot = FeedSnapshot()
        private set

    fun init(context: Context) {
        val f = File(context.filesDir, "feed_cache.json")
        file = f
        snapshot = read(f)
    }

    internal fun read(f: File): FeedSnapshot = try {
        if (!f.exists()) FeedSnapshot()
        else json.decodeFromString(FeedSnapshot.serializer(), f.readText())
    } catch (_: Throwable) {
        try { f.delete() } catch (_: Throwable) {}
        FeedSnapshot()
    }

    fun isTrendingFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        snapshot.trending.isNotEmpty() && snapshot.trendingAgeMs(nowMs) <= FRESH_MS

    fun isCategoryFresh(label: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        (snapshot.categories[label]?.cards?.isNotEmpty() == true) &&
            snapshot.categoryAgeMs(label, nowMs) <= FRESH_MS

    fun isTilesFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        snapshot.tiles.isNotEmpty() && snapshot.tilesAgeMs(nowMs) <= FRESH_MS

    fun categoryCards(label: String): List<ShowCard> =
        snapshot.categories[label]?.cards ?: emptyList()

    fun saveTrending(cards: List<ShowCard>) =
        mutate { it.copy(trending = cards, trendingAtMs = System.currentTimeMillis()) }

    fun saveCategory(label: String, cards: List<ShowCard>) = mutate {
        it.copy(categories = it.categories + (label to CategoryEntry(System.currentTimeMillis(), cards)))
    }

    fun saveTiles(tiles: List<TilePoster>) =
        mutate { it.copy(tiles = tiles, tilesAtMs = System.currentTimeMillis()) }

    private fun mutate(block: (FeedSnapshot) -> FeedSnapshot) {
        val f = file ?: return
        val next = block(snapshot)
        snapshot = next
        scope.launch {
            mutex.withLock {
                try {
                    val tmp = File(f.parentFile, "${f.name}.tmp")
                    tmp.writeText(json.encodeToString(FeedSnapshot.serializer(), next))
                    tmp.renameTo(f)
                } catch (_: Throwable) {}
            }
        }
    }
}
