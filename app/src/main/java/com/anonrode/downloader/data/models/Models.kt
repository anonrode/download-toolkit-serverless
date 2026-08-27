package com.anonrode.downloader.data.models

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    VALIDATING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Serializable
data class ShowCard(
    val title: String,
    val url: String,
    val posterUrl: String = "",
    val site: String,
    val category: String = "Drama",
    val year: String = "",
    val totalEpisodes: Int = 0
)

@Serializable
data class EpisodeItem(
    val title: String,
    val url: String,
    val episodeNum: Int,
    val site: String,
    val sizeText: String = "",
    val isDownloaded: Boolean = false
)

@Serializable
data class ShowDetails(
    val show: ShowCard,
    val synopsis: String = "",
    val episodes: List<EpisodeItem> = emptyList()
)

@Serializable
data class DownloadRecipe(
    val directUrl: String,
    val filename: String,
    val headers: Map<String, String> = emptyMap(),
    val backend: String = "aria2c",
    val parallelSockets: Int = 16
)

@Serializable
data class DownloadTask(
    val id: String,
    val showTitle: String,
    val episodeNum: Int,
    val episodeTitle: String,
    val directUrl: String,
    val sourceUrl: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val etaSeconds: Long = 0L,
    val status: TaskStatus = TaskStatus.QUEUED,
    val filePath: String = "",
    val backend: String = "aria2c",
    val parallelSockets: Int = 16,
    val site: String = "",
    val headers: Map<String, String> = emptyMap(),
    val audioOnly: Boolean = false,
    val errorMessage: String? = null,
    /** True only when the USER explicitly paused (pause button/notification).
     *  Every auto-resume path — network reconnect, storage self-heal, host
     *  cooldown — must skip tasks carrying this flag; only an explicit resume
     *  (retry) clears it. System parks (connectivity drop, Wi-Fi gate, storage
     *  limit, host backoff) leave it false so genuinely interrupted downloads
     *  still auto-recover. Persisted like any other field and preserved by
     *  parkForRestore, so a user pause survives full app restarts. */
    val userPaused: Boolean = false,
    val quality: String? = null,
    /** Real stream resolution extracted from the HLS master playlist
     *  (e.g. "1080p", "720p"). Populated during preflight when the master
     *  declares RESOLUTION attributes; null for direct files and unknown. */
    val resolution: String? = null,
    /** Honest warning shown on COMPLETED cards when the file could not be
     *  fully verified (e.g. no recognizable container signature but the
     *  platform decoder accepted it, or size fell short of the HLS
     *  estimate). Null = verified clean. */
    val validationNote: String? = null
)
