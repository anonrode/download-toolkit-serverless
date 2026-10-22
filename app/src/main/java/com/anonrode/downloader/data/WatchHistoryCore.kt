package com.anonrode.downloader.data

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.util.SeasonEpisode
import com.anonrode.downloader.util.ShowIdentity
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One watch-history entry.
 *
 * `id` is the file path while the file exists; if a watched file is deleted,
 * the record survives with `filePath = ""` and its id becomes a stable
 * show/season/episode key so the episode still reads as Watched.
 */
@Serializable
data class WatchRecord(
    val id: String,
    val showKey: String,
    val showTitle: String,
    /** [KIND_SERIES] or [KIND_SOCIAL]. */
    val kind: String,
    /** Absolute path; "" once the file has been deleted after watching. */
    val filePath: String = "",
    /** Episode source URL — matches the drawer's EpisodeItem.url. */
    val sourceUrl: String = "",
    /** Series page URL, for opening the EpisodeDrawer. */
    val seriesUrl: String = "",
    val site: String = "",
    val posterUrl: String = "",
    val episodeTitle: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** True once playback finished (>= 92% or STATE_ENDED); permanent. */
    val isWatched: Boolean = false,
    val updatedAtMs: Long = 0L
)

/** What the Continue card should do for a show. */
enum class ContinueState { RESUME, UP_NEXT, FETCH_NEXT }

/**
 * Resolved state for one show in the Continue & Media shelf.
 * `task` is the file to play for RESUME/UP_NEXT; null for FETCH_NEXT.
 */
data class SeriesContinueCard(
    val state: ContinueState,
    val showKey: String,
    val showTitle: String,
    val record: WatchRecord,
    val task: DownloadTask?,
    val seriesUrl: String,
    val site: String,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long
)

/**
 * Pure watch-history rules shared by the Android store and JVM tests.
 *
 * All decisions live here; [WatchHistoryStore] only adds persistence,
 * a StateFlow and context bootstrapping.
 */
object WatchHistoryCore {

    const val MAX_RECORDS = 400
    const val KIND_SERIES = "series"
    const val KIND_SOCIAL = "social"

    /** Below this, an open/close is treated as an accidental tap. */
    const val MIN_WATCH_MS = 20_000L
    /** Very short clips are always recorded, even under [MIN_WATCH_MS]. */
    const val SHORT_CLIP_MS = 30_000L
    /** Treated as fully watched at/above this fraction of duration. */
    const val WATCHED_FRACTION = 0.92
    /** Or when this little runtime is left. */
    const val NEAR_END_MS = 15_000L

    // ---------------------------------------------------------------- seeding

    /** Builds a record seed from a completed task; null if not playable. */
    fun seedFromTask(task: DownloadTask): WatchRecord? {
        if (task.status != TaskStatus.COMPLETED || task.filePath.isBlank()) return null
        val title = task.showTitle.ifBlank { "Unknown" }
        return WatchRecord(
            id = task.filePath,
            showKey = ShowIdentity.normalizeShowKey(title),
            showTitle = title,
            kind = kindForTitle(task.showTitle),
            filePath = task.filePath,
            sourceUrl = task.sourceUrl,
            seriesUrl = task.seriesUrl,
            site = task.site,
            posterUrl = task.posterUrl,
            episodeTitle = task.episodeTitle,
            season = SeasonEpisode.seasonOf(task),
            episode = SeasonEpisode.episodeOf(task),
            updatedAtMs = 0L
        )
    }

    fun kindForTitle(showTitle: String): String =
        if (showTitle.startsWith("Social/", ignoreCase = true) || showTitle == "Direct Downloads") {
            KIND_SOCIAL
        } else {
            KIND_SERIES
        }

    // -------------------------------------------------------------- recording

    /**
     * Records progress for [seed]'s file.
     *
     * - Ignores brief accidental opens (< [MIN_WATCH_MS]) unless a record
     *   already exists for this file, or the clip is very short
     *   ([SHORT_CLIP_MS]) — the directive's explicit exemptions.
     * - Marks watched at >= [WATCHED_FRACTION] or within [NEAR_END_MS] of the
     *   end. A previously watched record never un-watches.
     */
    fun recordProgress(
        records: List<WatchRecord>,
        seed: WatchRecord,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long
    ): List<WatchRecord> {
        val existing = records.firstOrNull { it.id == seed.id }
        val isShortClip = durationMs in 1..SHORT_CLIP_MS
        if (existing == null && positionMs < MIN_WATCH_MS && !isShortClip) return records

        val safePosition = positionMs.coerceAtLeast(0L)
        val base = (existing ?: seed).copy(
            showKey = seed.showKey,
            showTitle = seed.showTitle,
            kind = seed.kind,
            filePath = seed.filePath,
            sourceUrl = seed.sourceUrl.ifBlank { existing?.sourceUrl.orEmpty() },
            seriesUrl = seed.seriesUrl.ifBlank { existing?.seriesUrl.orEmpty() },
            site = seed.site.ifBlank { existing?.site.orEmpty() },
            posterUrl = seed.posterUrl.ifBlank { existing?.posterUrl.orEmpty() },
            episodeTitle = seed.episodeTitle.ifBlank { existing?.episodeTitle.orEmpty() },
            season = if (seed.season > 0) seed.season else existing?.season ?: 0,
            episode = if (seed.episode > 0) seed.episode else existing?.episode ?: 0
        )
        val effectiveDuration = durationMs.coerceAtLeast(base.durationMs)
        val reachedEnd = effectiveDuration > 0L && (
            safePosition.toDouble() / effectiveDuration.toDouble() >= WATCHED_FRACTION ||
                effectiveDuration - safePosition <= NEAR_END_MS
            )
        val updated = base.copy(
            positionMs = safePosition,
            durationMs = effectiveDuration,
            isWatched = base.isWatched || reachedEnd,
            updatedAtMs = nowMs
        )
        return trimUpsert(records, updated)
    }

    /** Permanent completion: always watched, position pinned to the end. */
    fun markEnded(
        records: List<WatchRecord>,
        seed: WatchRecord,
        durationMs: Long,
        nowMs: Long
    ): List<WatchRecord> {
        val existing = records.firstOrNull { it.id == seed.id }
        val base = (existing ?: seed)
        val updated = base.copy(
            showKey = seed.showKey,
            showTitle = seed.showTitle,
            kind = seed.kind,
            filePath = seed.filePath.ifBlank { base.filePath },
            sourceUrl = seed.sourceUrl.ifBlank { base.sourceUrl },
            seriesUrl = seed.seriesUrl.ifBlank { base.seriesUrl },
            site = seed.site.ifBlank { base.site },
            posterUrl = seed.posterUrl.ifBlank { base.posterUrl },
            episodeTitle = seed.episodeTitle.ifBlank { base.episodeTitle },
            season = if (seed.season > 0) seed.season else base.season,
            episode = if (seed.episode > 0) seed.episode else base.episode,
            positionMs = durationMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(base.durationMs),
            isWatched = true,
            updatedAtMs = nowMs
        )
        return trimUpsert(records, updated)
    }

    /** Sorted (newest first) and capped at [MAX_RECORDS]. */
    fun trim(records: List<WatchRecord>): List<WatchRecord> =
        records.sortedByDescending { it.updatedAtMs }.take(MAX_RECORDS)

    private fun trimUpsert(records: List<WatchRecord>, updated: WatchRecord): List<WatchRecord> =
        trim(listOf(updated) + records.filterNot { it.id == updated.id })

    // ------------------------------------------------------------ continue cards

    /**
     * Resolves up to [limit] Continue cards, most recently watched show first.
     *
     * Per show: the newest record decides.
     *  A) Mid-episode (not watched, position > 0, file exists) -> RESUME.
     *  B) Finished (or file deleted) and a later COMPLETED episode exists on
     *     disk -> UP_NEXT pointing at that task.
     *  C) Finished, nothing later on disk, but the series URL is known ->
     *     FETCH_NEXT (open the drawer with Remaining preselected).
     */
    fun resolveContinueCards(
        records: List<WatchRecord>,
        tasks: List<DownloadTask>,
        limit: Int = 3,
        fileExists: (String) -> Boolean = { File(it).isFile }
    ): List<SeriesContinueCard> {
        val seriesRecords = records.filter { it.kind == KIND_SERIES }
        val byShow = seriesRecords.groupBy { it.showKey }
        val orderedShows = byShow.entries
            .sortedByDescending { entry -> entry.value.maxOfOrNull { it.updatedAtMs } ?: 0L }
            .take(limit)

        return orderedShows.mapNotNull { (showKey, showRecords) ->
            val last = showRecords.maxByOrNull { it.updatedAtMs } ?: return@mapNotNull null
            val completedForShow = tasks
                .filter {
                    it.status == TaskStatus.COMPLETED &&
                        it.filePath.isNotBlank() &&
                        ShowIdentity.normalizeShowKey(it.showTitle.ifBlank { "Unknown" }) == showKey
                }
                .sortedWith(ShowIdentity.episodeOrder)

            val lastCoord = SeasonEpisode.sortKey(last.season, last.episode)
            val nextTask = completedForShow.firstOrNull { task ->
                val coord = SeasonEpisode.sortKey(SeasonEpisode.seasonOf(task), SeasonEpisode.episodeOf(task))
                coord > lastCoord && !isWatchedCoord(showRecords, SeasonEpisode.seasonOf(task), SeasonEpisode.episodeOf(task))
            }

            val midEpisode = !last.isWatched && last.positionMs > 0L &&
                last.filePath.isNotBlank() && fileExists(last.filePath)

            when {
                midEpisode -> SeriesContinueCard(
                    state = ContinueState.RESUME,
                    showKey = showKey,
                    showTitle = last.showTitle,
                    record = last,
                    task = completedForShow.firstOrNull { it.filePath == last.filePath },
                    seriesUrl = last.seriesUrl,
                    site = last.site,
                    season = last.season,
                    episode = last.episode,
                    positionMs = last.positionMs,
                    durationMs = last.durationMs
                )

                nextTask != null -> {
                    val nextSeason = SeasonEpisode.seasonOf(nextTask)
                    val nextEpisode = SeasonEpisode.episodeOf(nextTask)
                    SeriesContinueCard(
                        state = ContinueState.UP_NEXT,
                        showKey = showKey,
                        showTitle = last.showTitle,
                        record = last,
                        task = nextTask,
                        seriesUrl = nextTask.seriesUrl.ifBlank { last.seriesUrl },
                        site = nextTask.site.ifBlank { last.site },
                        season = nextSeason,
                        episode = nextEpisode,
                        positionMs = 0L,
                        durationMs = 0L
                    )
                }

                last.seriesUrl.isNotBlank() && last.episode > 0 -> SeriesContinueCard(
                    state = ContinueState.FETCH_NEXT,
                    showKey = showKey,
                    showTitle = last.showTitle,
                    record = last,
                    task = null,
                    seriesUrl = last.seriesUrl,
                    site = last.site,
                    season = last.season,
                    episode = last.episode,
                    positionMs = 0L,
                    durationMs = 0L
                )

                else -> null
            }
        }
    }

    // ------------------------------------------------------------- deletions

    /**
     * Applies file deletion to history.
     *
     * - Watched-only deletion (`clearShowHistory = false`): watched records
     *   whose file was deleted survive with `filePath = ""`, preserving
     *   source/season/episode so the episode still badges Watched and Up Next
     *   still knows where the user stopped. Non-watched records for deleted
     *   paths are dropped (their file is gone and they were not finished).
     * - Entire-show deletion (`clearShowHistory = true`): every record whose
     *   showKey is in [deletedShowKeys] is removed.
     */
    fun onFilesDeleted(
        records: List<WatchRecord>,
        deletedPaths: Set<String>,
        deletedShowKeys: Set<String> = emptySet(),
        clearShowHistory: Boolean
    ): List<WatchRecord> = records.mapNotNull { record ->
        if (clearShowHistory && record.showKey in deletedShowKeys) return@mapNotNull null
        if (record.filePath.isNotBlank() && record.filePath in deletedPaths) {
            if (record.isWatched) {
                record.copy(id = deletedId(record), filePath = "")
            } else {
                null
            }
        } else {
            record
        }
    }

    /** Stable id for a record whose file is gone. */
    fun deletedId(record: WatchRecord): String {
        val season = record.season
        val episode = record.episode
        return if (season > 0 || episode > 0) {
            "${record.showKey}#S${season}E${episode}"
        } else {
            "${record.showKey}#${record.episodeTitle.ifBlank { "episode" }}"
        }
    }

    // --------------------------------------------------------------- matching

    /** True when [records] already marks this episode as watched. */
    fun isWatchedEpisode(
        records: List<WatchRecord>,
        sourceUrl: String,
        season: Int,
        episode: Int
    ): Boolean = records.any { record ->
        record.isWatched && (
            (sourceUrl.isNotBlank() && record.sourceUrl == sourceUrl) ||
                (season > 0 && record.season == season && record.episode == episode)
            )
    }

    private fun isWatchedCoord(records: List<WatchRecord>, season: Int, episode: Int): Boolean =
        records.any { it.isWatched && it.season == season && it.episode == episode }

    // ---------------------------------------------------------------- helpers

    /** "23m left", "<1m left", or "Resume" when the duration is unknown. */
    fun formatTimeLeft(positionMs: Long, durationMs: Long): String {
        if (durationMs <= 0L) return "Resume"
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        if (left < 60_000L) return "<1m left"
        return "${left / 60_000L}m left"
    }

    /**
     * Moves the most recently watched social tile to index 0, keeping every
     * other item in its existing relative order (stable).
     */
    fun orderShelfWithLastWatched(
        shelf: List<DownloadTask>,
        records: List<WatchRecord>
    ): List<DownloadTask> {
        if (shelf.isEmpty()) return shelf
        val latest = records
            .filter { it.kind == KIND_SOCIAL && it.filePath.isNotBlank() }
            .maxByOrNull { it.updatedAtMs } ?: return shelf
        val index = shelf.indexOfFirst { it.filePath.isNotBlank() && it.filePath == latest.filePath }
        if (index <= 0) return shelf
        val mutable = shelf.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(0, item)
        return mutable
    }
}
