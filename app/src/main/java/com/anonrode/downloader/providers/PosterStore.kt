package com.anonrode.downloader.providers

import android.content.Context
import com.anonrode.downloader.data.models.ShowCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Process-wide network flag, refreshed from the app's NetworkObserver.
 *  PosterStore/enrich/request factories read it to avoid network attempts
 *  while offline (cached art only). */
object PosterNetwork {
    @Volatile
    var isOnline: Boolean = true
}

/** One genre label's last-good artwork plus its retry bookkeeping. */
@Serializable
data class TilePosterRecord(
    val urls: List<String> = emptyList(),
    val savedAtMs: Long = 0L,
    val lastAttemptMs: Long = 0L,
    val nextRetryAtMs: Long = 0L,
    val failures: Int = 0
)

/** A card key's last SUCCESSFULLY LOADED poster URL. */
@Serializable
data class CardPosterRecord(
    val url: String = "",
    val savedAtMs: Long = 0L
)

@Serializable
data class PosterSnapshot(
    val tiles: Map<String, TilePosterRecord> = emptyMap(),
    val cards: Map<String, CardPosterRecord> = emptyMap()
)

/**
 * Persistent last-good poster memory (filesDir/poster_store.json).
 *
 * Two jobs:
 *  1. Genre tiles keep up to two REAL poster URLs per label across launches,
 *     so a failed refresh can never blank a tile that once had art.
 *  2. Cards keep the last URL whose bytes actually loaded, keyed site|url,
 *     so a later refresh that returns a dead/blank URL still has a cached
 *     fallback to paint.
 *
 * Same storage contract as FeedCache: tolerant kotlinx JSON, atomic
 * tmp+rename, corrupt file deletes itself. Writes are serialized on IO and
 * coalesced through a conflated channel; [flush] is available when a caller
 * must guarantee the bytes hit disk (tile success).
 */
object PosterStore {

    private const val FILE_NAME = "poster_store.json"
    private const val MAX_CARDS = 2000
    private const val MAX_LOADED_URLS = 2048

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val loadedLock = Any()
    private val writeMutex = Mutex()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    /** Invalidates queued writers across init/reset (test isolation and the
     *  real init path); a stale writer must never overwrite the new file. */
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var file: File? = null

    @Volatile
    private var writerStarted = false

    @Volatile
    var snapshot: PosterSnapshot = PosterSnapshot()
        private set

    /** URLs whose bytes loaded at least once this process (memory-cache
     *  gating for the All-Genres montage: no fresh requests). */
    private val loadedUrls = LinkedHashSet<String>()

    private val tmdbRe = Regex(
        """^(https?://image\.tmdb\.org/t/p/)(original|w\d+)(/.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun init(context: Context) = init(File(context.filesDir, FILE_NAME))

    /** Testable file overload (no Android Context needed). */
    fun init(file: File) {
        generation.incrementAndGet()
        this.file = file
        synchronized(lock) { snapshot = read(file) }
        synchronized(loadedLock) {
            loadedUrls.clear()
            // Card entries are written only by markLoaded(), so they are
            // genuinely loaded bytes. Tile URLs are metadata successes and
            // are NOT assumed loaded; their onSuccess callbacks add them.
            snapshot.cards.values.mapNotNullTo(loadedUrls) { it.url.takeIf(String::isNotBlank) }
        }
        startWriter()
    }

    private fun startWriter() {
        if (writerStarted) return
        writerStarted = true
        scope.launch {
            for (ignored in writeSignal) {
                writeMutex.withLock { writeNow() }
            }
        }
    }

    internal fun read(f: File): PosterSnapshot = try {
        if (!f.exists()) PosterSnapshot()
        else json.decodeFromString(PosterSnapshot.serializer(), f.readText())
    } catch (_: Throwable) {
        try {
            f.delete()
        } catch (_: Throwable) {
        }
        PosterSnapshot()
    }

    private fun writeNow() {
        val gen = generation.get()
        val f = file ?: return
        val snap = synchronized(lock) { snapshot }
        if (gen != generation.get()) return
        try {
            val parent = f.parentFile ?: return
            if (!parent.exists()) parent.mkdirs()
            val tmp = File(parent, "${f.name}.tmp")
            tmp.writeText(json.encodeToString(PosterSnapshot.serializer(), snap))
            // A reset/init may have landed while the file was being written.
            if (gen != generation.get()) return
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Throwable) {
            // A poster cache must never take the app down.
        }
    }

    private fun scheduleWrite() {
        writeSignal.trySend(Unit)
    }

    /** Guarantees the current snapshot is on disk before returning. */
    suspend fun flush() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock { writeNow() }
        }
    }

    // ── Genre tiles ────────────────────────────────────────────────────────

    fun tileRecord(label: String): TilePosterRecord? =
        synchronized(lock) { snapshot.tiles[label] }

    fun tileUrls(label: String): List<String> =
        tileRecord(label)?.urls?.filter { it.isNotBlank() }.orEmpty()

    fun tileSavedAt(label: String): Long = tileRecord(label)?.savedAtMs ?: 0L

    fun tileFailures(label: String): Int = tileRecord(label)?.failures ?: 0

    fun nextTileRetryAt(label: String): Long = tileRecord(label)?.nextRetryAtMs ?: 0L

    /**
     * Stores a successful live result for ONE label. Blank slots never
     * overwrite a previous non-blank slot, other labels are untouched, and
     * an all-blank result is a no-op. Returns the merged URL list.
     */
    fun saveTileSuccess(label: String, urls: List<String>): List<String> {
        val clean = urls.map { normalizePosterUrl(it) }.filter { it.isNotBlank() }.distinct().take(2)
        if (clean.isEmpty()) return tileUrls(label)
        val merged = synchronized(lock) {
            val old = snapshot.tiles[label]?.urls.orEmpty()
            val out = (0 until maxOf(clean.size, old.size)).mapNotNull { i ->
                clean.getOrNull(i)?.takeIf { it.isNotBlank() }
                    ?: old.getOrNull(i)?.takeIf { it.isNotBlank() }
            }.distinct()
            val now = System.currentTimeMillis()
            snapshot = snapshot.copy(
                tiles = snapshot.tiles + (label to TilePosterRecord(
                    urls = out,
                    savedAtMs = now,
                    lastAttemptMs = now,
                    nextRetryAtMs = 0L,
                    failures = 0
                ))
            )
            out
        }
        scheduleWrite()
        return merged
    }

    /** Records one failed attempt; keeps whatever art is already stored. */
    fun recordTileFailure(label: String, failures: Int, nextRetryAtMs: Long) {
        synchronized(lock) {
            val old = snapshot.tiles[label] ?: TilePosterRecord()
            snapshot = snapshot.copy(
                tiles = snapshot.tiles + (label to old.copy(
                    lastAttemptMs = System.currentTimeMillis(),
                    nextRetryAtMs = nextRetryAtMs,
                    failures = failures
                ))
            )
        }
        scheduleWrite()
    }

    // ── Cards ──────────────────────────────────────────────────────────────

    fun cardKey(site: String, url: String): String =
        site.trim().lowercase() + "|" + url.trim()

    fun lastGoodFor(show: ShowCard): String = lastGoodFor(show.site, show.url)

    fun lastGoodFor(site: String, url: String): String =
        synchronized(lock) { snapshot.cards[cardKey(site, url)]?.url.orEmpty() }

    fun markLoaded(show: ShowCard, posterUrl: String) =
        markLoaded(show.site, show.url, posterUrl)

    fun markLoaded(site: String, url: String, posterUrl: String) {
        val clean = normalizePosterUrl(posterUrl)
        if (clean.isBlank()) return
        val key = cardKey(site, url)
        var changed = false
        synchronized(lock) {
            if (snapshot.cards[key]?.url != clean) {
                val now = System.currentTimeMillis()
                val next = snapshot.cards + (key to CardPosterRecord(clean, now))
                snapshot = snapshot.copy(cards = trimCards(next))
                changed = true
            }
        }
        markUrlLoaded(clean)
        if (changed) scheduleWrite()
    }

    private fun trimCards(cards: Map<String, CardPosterRecord>): Map<String, CardPosterRecord> {
        if (cards.size <= MAX_CARDS) return cards
        val drop = cards.size - MAX_CARDS
        return cards.entries
            .sortedBy { it.value.savedAtMs }
            .drop(drop)
            .associate { it.key to it.value }
    }

    fun markUrlLoaded(url: String) {
        val key = normalizePosterUrl(url)
        if (key.isBlank()) return
        synchronized(loadedLock) {
            loadedUrls.remove(key)
            loadedUrls.add(key)
            while (loadedUrls.size > MAX_LOADED_URLS) {
                val it = loadedUrls.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                } else break
            }
        }
    }

    fun isLoaded(url: String): Boolean {
        val key = normalizePosterUrl(url)
        if (key.isBlank()) return false
        return synchronized(loadedLock) { loadedUrls.contains(key) }
    }

    /**
     * Visible-state enrichment: a blank incoming URL falls back to the last
     * loaded URL; while offline the last loaded URL is promoted to primary
     * so the cold start paints entirely from Coil's disk cache.
     */
    fun enrich(cards: List<ShowCard>, offline: Boolean = false): List<ShowCard> = cards.map { card ->
        val last = lastGoodFor(card)
        val incoming = normalizePosterUrl(card.posterUrl)
        val chosen = when {
            incoming.isBlank() -> last
            offline && last.isNotBlank() -> last
            else -> incoming
        }
        if (chosen == card.posterUrl) card else card.copy(posterUrl = chosen)
    }

    /**
     * Disk-state variant: only URLs whose bytes have loaded (or the previous
     * good one) are written to FeedCache. A dead-but-nonblank URL from a
     * failed refresh therefore never replaces the cached good poster.
     */
    fun persistable(cards: List<ShowCard>): List<ShowCard> = cards.map { card ->
        val last = lastGoodFor(card)
        val incoming = normalizePosterUrl(card.posterUrl)
        val chosen = when {
            incoming.isBlank() -> last
            incoming == last -> incoming
            isLoaded(incoming) -> incoming
            else -> last.ifBlank { incoming }
        }
        if (chosen == card.posterUrl) card else card.copy(posterUrl = chosen)
    }

    /**
     * Canonical poster URL: trimmed, fragment-free, and TMDB URLs collapsed
     * to w342 (the fleet's display size). Non-TMDB scraped URLs are untouched.
     */
    fun normalizePosterUrl(url: String): String {
        val clean = url.trim().substringBefore('#')
        if (clean.isBlank()) return ""
        val match = tmdbRe.find(clean) ?: return clean
        val prefix = match.groupValues[1]
        val size = match.groupValues[2]
        val path = match.groupValues[3]
        return if (size.equals("w342", ignoreCase = true)) clean else "${prefix}w342$path"
    }

    // ── Test hooks ─────────────────────────────────────────────────────────

    internal fun resetForTest() {
        generation.incrementAndGet()
        synchronized(lock) { snapshot = PosterSnapshot() }
        synchronized(loadedLock) { loadedUrls.clear() }
    }

    internal fun setSnapshotForTest(next: PosterSnapshot) {
        synchronized(lock) { snapshot = next }
    }
}
