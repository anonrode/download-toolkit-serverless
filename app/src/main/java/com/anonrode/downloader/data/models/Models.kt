package com.anonrode.downloader.data.models

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
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
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val status: TaskStatus = TaskStatus.QUEUED,
    val filePath: String = "",
    val backend: String = "aria2c",
    val parallelSockets: Int = 16,
    val site: String = "",
    val headers: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)
