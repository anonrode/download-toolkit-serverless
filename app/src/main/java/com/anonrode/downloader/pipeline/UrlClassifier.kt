package com.anonrode.downloader.pipeline

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

object UrlClassifier {

    private val STREAMING_EXTENSIONS = setOf("m3u8", "mpd", "m3u", "ism", "f4m")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val DIRECT_MEDIA_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m4v",
        "mp3", "m4a", "aac", "flac", "opus", "ogg", "wav",
        "jpg", "jpeg", "png", "webp"
    )
    private val STREAMING_QUERY_REGEX = Regex("""(?:^|&)[^=&]*=(?:mpd|dash|hls|m3u8)(?:&|$)""", RegexOption.IGNORE_CASE)

    /**
     * Safely strips the `preview` query parameter ONLY if it is an actual query parameter,
     * preserving all other query parameters (such as auth tokens or signatures).
     */
    fun stripPreviewParam(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return trimmed
        val matchingNames = httpUrl.queryParameterNames.filter { it.equals("preview", ignoreCase = true) }
        if (matchingNames.isEmpty()) return trimmed
        val builder = httpUrl.newBuilder()
        matchingNames.forEach { builder.removeAllQueryParameters(it) }
        return builder.build().toString()
    }

    /**
     * Safely extracts the lowercase hostname without credentials, ports, or path segments.
     */
    fun extractHost(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        trimmed.toHttpUrlOrNull()?.host?.let { return it.lowercase() }
        return try {
            URI(trimmed).host?.lowercase() ?: ""
        } catch (_: Exception) {
            trimmed.substringAfter("://", "")
                .substringAfter('@')
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .lowercase()
        }
    }

    /**
     * Safely extracts `scheme://host[:port]` for Origin headers.
     */
    fun extractOrigin(rawUrl: String): String {
        val httpUrl = rawUrl.trim().toHttpUrlOrNull() ?: return ""
        val defaultPort = (httpUrl.scheme == "http" && httpUrl.port == 80) ||
            (httpUrl.scheme == "https" && httpUrl.port == 443)
        return if (defaultPort) {
            "${httpUrl.scheme}://${httpUrl.host}"
        } else {
            "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
        }
    }

    /**
     * Extracts a clean file extension (2..5 alphanumeric chars) from the URL path or
     * `filename=` query parameter, returning null if none is found.
     */
    fun extractMediaExtension(rawUrl: String): String? {
        val httpUrl = rawUrl.trim().toHttpUrlOrNull()
        val lastSegment = httpUrl?.pathSegments?.lastOrNull { it.isNotBlank() }
            ?: rawUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val pathExt = lastSegment.substringAfterLast('.', "").lowercase()
        if (pathExt in DIRECT_MEDIA_EXTENSIONS) {
            return pathExt
        }
        val encodedQuery = httpUrl?.encodedQuery ?: rawUrl.substringAfter('?', "")
        val decodedQuery = httpUrl?.query ?: encodedQuery
        val filenameRegex = Regex("""filename[*]?=[^&;]+\.([a-zA-Z0-9]{2,5})""")
        // LAST filename= wins: repeated query parameters are resolved last-one-wins
        // by virtually every server-side parser (first-match renamed tasks to a
        // thumbnail's extension on double-filename signed URLs).
        val queryExt = (filenameRegex.findAll(encodedQuery).lastOrNull()
            ?: filenameRegex.findAll(decodedQuery).lastOrNull())
            ?.groupValues?.get(1)?.lowercase()
        return if (queryExt != null && queryExt in DIRECT_MEDIA_EXTENSIONS) queryExt else null
    }

    /**
     * Strict adaptive-streaming manifest check.
     * Fixes the bug where a direct video file named "Manifest.S01E01.mp4" matched `low.contains("manifest")`.
     */
    fun isStreamingLink(rawUrl: String): Boolean {
        val low = rawUrl.trim().lowercase()
        val path = low.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "")

        // If the path explicitly ends with a direct media file extension (.mp4, .mkv, etc.),
        // it is NOT a manifest unless the query string explicitly requests an HLS/DASH format.
        // Images and .ts keep falling through to the path/manifest heuristics below:
        // "/hls/master.jpg" style extension-obfuscated manifests must stay streaming.
        if (ext in DIRECT_MEDIA_EXTENSIONS && ext != "ts" && ext !in IMAGE_EXTENSIONS) {
            val query = low.substringAfter('?', "")
            return STREAMING_QUERY_REGEX.containsMatchIn(query)
        }

        if (ext in STREAMING_EXTENSIONS) return true
        if (path.endsWith("/manifest") || path.contains("/manifest.") || path.contains("/hls/") || path.contains(".m3u8")) {
            return true
        }
        if (low.contains("kickassanime") || low.contains("playlist.php") ||
            low.contains("/api/orion/") || low.contains("stream-proxy")
        ) {
            return true
        }
        val query = low.substringAfter('?', "")
        return STREAMING_QUERY_REGEX.containsMatchIn(query)
    }
}
