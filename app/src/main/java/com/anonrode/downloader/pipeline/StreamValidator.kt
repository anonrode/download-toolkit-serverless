package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.net.HttpClient

/**
 * Strict pre-enqueue URL validation (provider contract rule 3).
 *
 * History: HTML error pages entered the download queue as "mp4" tasks and
 * produced corrupted files that Layer 7 could only catch AFTER a full
 * download. This validator spends one ranged request (<=1KB) to reject dead
 * links BEFORE any bytes are persisted.
 *
 * Conservative by design — it rejects only provable garbage:
 *  - HTTP >= 400 (except 416, which just means the server ignored Range)
 *  - bodies that are clearly HTML/script/JSON error pages or archive/exec
 *    containers (PK/MZ)
 * Everything else passes; the download backends own transient errors.
 */
object StreamValidator {

    private const val PROBE_BYTES = 1024

    /**
     * @param headers the EXACT headers the real download will send (referer
     *   policies matter: vidbasic 403s with a Referer, megaplay CDNs 403
     *   without theirs — validating with different headers lies either way).
     * @return null when the URL looks downloadable, else a human-readable
     *   rejection reason.
     */
    fun validate(url: String, headers: Map<String, String>): String? {
        val start = System.currentTimeMillis()
        var status: Int = -1
        var head = ByteArray(0)
        try {
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Accept", "*/*")
            for ((k, v) in headers) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    try { builder.header(k, v) } catch (_: IllegalArgumentException) {}
                }
            }
            HttpClient.shared.newBuilder().build().newCall(builder.build()).execute().use { res ->
                status = res.code
                if (status in 200..299 || status == 416) {
                    val stream = res.body?.byteStream() ?: return null
                    head = stream.readNBytes(PROBE_BYTES)
                }
            }
        } catch (e: Exception) {
            // Network-class errors during probe are NOT proof of a bad link;
            // let the backend's retry machinery handle them.
            PipelineJournal.hop("", "validate", url, ok = true,
                ms = System.currentTimeMillis() - start,
                detail = "probe network error, deferring to backend: ${e.message?.take(80)}")
            return null
        }

        if (status == 416 || head.isEmpty()) {
            // Can't inspect (range refused / empty body) -> don't guess.
            return null
        }

        val reason = sniff(head)
        PipelineJournal.hop("", "validate", url, ok = reason == null,
            ms = System.currentTimeMillis() - start,
            detail = reason ?: "HTTP $status, ${head.size}B probed")
        return reason
    }

    /** Rejection reason for a file head, or null when plausibly media. */
    fun sniff(head: ByteArray): String? {
        if (head.size < 4) return null
        val text = String(head, Charsets.US_ASCII).trimStart().lowercase()
        if (text.startsWith("<!doctype html") || text.startsWith("<html") ||
            text.startsWith("<head") || text.startsWith("<body") ||
            text.startsWith("<script") || text.startsWith("<?xml") ||
            text.startsWith("<!--") || text.startsWith("{\"")) {
            return "URL serves an HTML/error page, not media"
        }
        if (head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) {
            return "URL serves an archive/APK container (PK header)"
        }
        if (head[0] == 0x4d.toByte() && head[1] == 0x5a.toByte()) {
            return "URL serves a Windows executable (MZ header)"
        }
        return null
    }
}
