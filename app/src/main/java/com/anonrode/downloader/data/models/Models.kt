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
    val totalEpisodes: Int = 0,
    val tags: List<String> = emptyList(),
    val genres: List<String> = emptyList()
)

@Serializable
data class EpisodeItem(
    val title: String,
    val url: String,
    val episodeNum: Int,
    val site: String,
    val sizeText: String = "",
    val isDownloaded: Boolean = false,
    val mirrorUrls: List<String> = emptyList(),
    /** Authoritative season number; 0 = legacy row (derive from episodeNum). */
    val season: Int = 0,
    /** Authoritative episode number inside [season]; 0 = legacy row. */
    val episode: Int = 0
)

@Serializable
data class ShowDetails(
    val show: ShowCard,
    val synopsis: String = "",
    val episodes: List<EpisodeItem> = emptyList()
)

@Serializable
data class SubtitleTrack(
    /** Absolute http(s) URL of the subtitle file (vtt/srt/ass). */
    val url: String,
    /** BCP-47-ish language tag from the source ("en", "pt-BR", "und"). */
    val lang: String = "",
    /** "vtt" | "srt" | "ass" | "" (inferred from url/extension by the engine). */
    val kind: String = ""
)

@Serializable
data class DownloadRecipe(
    val directUrl: String,
    val filename: String,
    val headers: Map<String, String> = emptyMap(),
    val backend: String = "aria2c",
    val parallelSockets: Int = 16,
    /** Soft subtitle tracks the resolver proved for this episode. The engine
     *  fetches them as sidecars after the video completes; a subtitle
     *  failure never fails the video. Empty = no tracks known. */
    val subtitles: List<SubtitleTrack> = emptyList()
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
    val validationNote: String? = null,
    /** Epoch ms when the task was enqueued. Powers the "Date added" sort
     *  buckets with real clock ages. 0 = task persisted by an older build —
     *  the UI falls back to list-position age for those (the list is
     *  newest-first, so position still orders them correctly). */
    val createdAt: Long = 0L,
    val mirrorUrls: List<String> = emptyList(),
    val selectedMirrorUrl: String = "",
    /** Series page URL this episode belongs to ("" for direct/one-off
     *  downloads). Stamped by the episode drawer at enqueue time; powers the
     *  "Download Remaining (X left)" chip on the Downloads screen. */
    val seriesUrl: String = "",
    /** Total episode count the drawer knew for the series at enqueue time
     *  (0 = unknown: direct downloads or tasks persisted by older builds).
     *  Only a card-level hint — the drawer recomputes the exact un-downloaded
     *  set live from the provider when the chip opens it. */
    val seriesTotal: Int = 0,
    /** Poster art for the Downloads card thumbnail (v6 mock frame 1):
     *  stamped by the episode drawer at enqueue time from the series poster.
     *  "" for direct/social/torrent downloads, which keep the initials tile.
     *  Serialized like any other field; journals persisted by older builds
     *  simply load as "" (initials fallback). */
    val posterUrl: String = "",
    /** Authoritative season number; 0 = legacy task (derive from episodeNum). */
    val season: Int = 0,
    /** Authoritative episode number inside [season]; 0 = legacy task. */
    val episode: Int = 0,
    /** Display-only speed for card faces: bytes/s over a trailing ~4 s
     *  window computed by DownloadRepository.updateProgress — the raw
     *  backend value (250 ms EMA on Turbo, verbatim parsed line on yt-dlp/
     *  aria) stays in [speedBytesPerSec] with UNCHANGED semantics because
     *  the engine's progress watchdog reads it for stall detection.
     *  Null while the window is still warming up (~3 s): a UI reading this
     *  field renders "Starting…" instead of a ramping or poisoned first
     *  sample (the aria/yt-dlp resume burst that once printed 2.4 TB/s
     *  cannot reach any card through this field). */
    val displaySpeedBytesPerSec: Double? = null,
    /** Display-only ETA (seconds) derived from the same windowed speed;
     *  null means "no window reading — show the raw sticky [etaSeconds]
     *  fallback" (merges, unknown totals, stalls). */
    val displayEtaSeconds: Long? = null
)
