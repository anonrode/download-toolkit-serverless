package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.net.isTlsChainFailure
import java.io.ByteArrayOutputStream

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

        val req = try {
            okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Accept", "*/*")
                .apply {
                    for ((k, v) in headers) {
                        if (k.isNotBlank() && v.isNotBlank()) {
                            try { header(k, v) } catch (_: IllegalArgumentException) {}
                        }
                    }
                }
                .build()
        } catch (e: Exception) {
            // Malformed URL — same deferral contract as a network error: not
            // proof of a bad link from the probe's point of view.
            PipelineJournal.hop("", "validate", url, ok = true,
                ms = System.currentTimeMillis() - start,
                detail = "probe request error, deferring to backend: ${e.message?.take(80)}")
            return null
        }

        // Strict probe first; a broken TLS chain (wetafiles omits its
        // intermediate) must fall back to the trust-all client instead of
        // deferring to the backends — the backends are strict too, so the
        // deferral just handed the job to yt-dlp's own transport (v3.0.4:
        // ~18s of failing handshakes, no total, then yt-dlp with MB-only
        // progress). One retry only.
        var tlsRetried = false
        while (true) {
            try {
                val client = if (tlsRetried) HttpClient.permissiveClient else HttpClient.shared.newBuilder().build()
                HttpClient.executeRegistered(client.newCall(req)).use { res ->
                    status = res.code
                    if (status in 200..299 || status == 416) {
                        // readNBytes is API 33+; on Android 10-12 it throws
                        // NoSuchMethodError (an Error, invisible to the catch
                        // below) and crashes the app. Read in a loop instead.
                        head = res.body?.byteStream()?.let { s ->
                            ByteArrayOutputStream(PROBE_BYTES).use { baos ->
                                val buf = ByteArray(256)
                                while (baos.size() < PROBE_BYTES) {
                                    val read = s.read(buf, 0, minOf(buf.size, PROBE_BYTES - baos.size()))
                                    if (read < 0) break
                                    baos.write(buf, 0, read)
                                }
                                baos.toByteArray()
                            }
                        } ?: ByteArray(0)
                    }
                }
                break
            } catch (e: Exception) {
                val tls = isTlsChainFailure(e)
                PipelineJournal.hop("", "validate", url, ok = true,
                    ms = System.currentTimeMillis() - start,
                    detail = if (tls && !tlsRetried)
                        "strict TLS probe failed (${e.message?.take(60)}), retrying permissive"
                    else
                        "probe network error, deferring to backend: ${e.message?.take(80)}")
                if (!tls || tlsRetried) {
                    // Network-class errors during probe are NOT proof of a bad
                    // link; let the backend's retry machinery handle them.
                    return null
                }
                tlsRetried = true
            }
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
