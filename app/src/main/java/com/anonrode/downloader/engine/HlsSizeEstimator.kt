package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Segment-Sampling Estimator (SSE): predict an HLS download's final size by
 * MEASURING real segments instead of trusting the master playlist's BANDWIDTH
 * tag — a peak value, not an average, and some CDNs lie outright.
 *
 * The media playlist lists every segment with an exact #EXTINF duration, so
 * the segment count N is exact; the only unknown is the average segment size.
 * That unknown is probed: k=4 segments at ~5/35/65/95% of the playlist with
 * a `Range: bytes=0-0` request (headers only — the body is never read), all
 * run concurrently. estimate_wire = mean(sampled sizes) * N. When the master
 * carries an #EXT-X-MEDIA:TYPE=AUDIO rendition, that audio playlist is
 * fetched, ONE audio segment is probed, and its projected size
 * (sample * audio segment count) is added to the wire total. Finally the
 * wire total is scaled by the TS→MP4 remux factor (0.93, or 0.99 when the
 * playlist uses fMP4 segments — detected by the absence of .ts names).
 *
 * Every failure path returns null (live/sliding-window playlist without
 * ENDLIST, playlist fetch failure, a single failed probe). Callers must treat
 * null as "no estimate" and never fail a download over it; only coroutine
 * cancellation propagates.
 */
object HlsSizeEstimator {

    /** Playlist percentiles the segment probes land on. */
    private val PROBE_POSITIONS = listOf(5, 35, 65, 95)

    private val URI_VALUE = Regex("""URI="([^"]+)"""")

    /**
     * Count media segments in an HLS playlist: every #EXTINF line is exactly
     * one segment (an EXT-X-BYTERANGE segment still carries a single EXTINF).
     * Tag lines, blank lines and URIs are ignored.
     */
    fun parseSegmentCount(playlistText: String): Int {
        if (playlistText.isEmpty()) return 0
        return playlistText.lineSequence().count { it.trimStart().startsWith("#EXTINF") }
    }

    /**
     * The estimate math: (mean of sampled segment sizes) * segmentCount,
     * scaled by the remux factor — 0.93 for TS segments (yt-dlp remuxes TS
     * into MP4, ~7% smaller on the wire), 0.99 for fMP4 segments (copied
     * with a near-neutral factor). Returns 0 for degenerate inputs: no
     * samples, a zero/negative sample, or no segments.
     */
    fun estimateFromSamples(sampleSizes: List<Long>, segmentCount: Int, isFmp4: Boolean): Long {
        if (sampleSizes.isEmpty() || segmentCount <= 0) return 0L
        if (sampleSizes.any { it <= 0L }) return 0L
        val mean = sampleSizes.average()
        val factor = if (isFmp4) 0.99 else 0.93
        return (mean * segmentCount * factor).toLong()
    }

    /**
     * Full size-estimation pipeline. [masterText] is the already-fetched
     * master playlist, [masterUrl] its URL (base for relative audio URIs),
     * [variantUrl] the media playlist the real download will consume, and
     * [referer] the same referer the real download uses. Returns the
     * predicted final file size in bytes, or null when no estimate is
     * possible. Never throws (except coroutine cancellation).
     */
    suspend fun estimate(masterText: String, masterUrl: String, variantUrl: String, referer: String?): Long? {
        try {
            val variantText = HttpClient.getText(variantUrl, referer = referer, tag = "hls-estimate") ?: return null
            // Live / sliding-window playlists have no ENDLIST: the segment
            // count keeps changing, so no estimate.
            if (!variantText.contains("#EXT-X-ENDLIST")) return null
            val segmentCount = parseSegmentCount(variantText)
            if (segmentCount <= 0) return null
            val segments = segmentUrls(variantText, variantUrl)
            if (segments.isEmpty()) return null

            // Probe k=4 segments at spread-out positions, concurrently.
            val indices = PROBE_POSITIONS
                .map { (segmentCount * it / 100).coerceIn(0, segmentCount - 1) }
                .distinct()
            if (indices.any { it >= segments.size }) return null
            val samples = coroutineScope {
                indices.map { idx -> async { probeSegmentSize(segments[idx], referer) } }
                    .map { it.await() }
            }
            if (samples.any { it == null }) return null
            val fmp4 = isFmp4(segments)
            var total = estimateFromSamples(samples.filterNotNull(), segmentCount, fmp4)
            if (total <= 0L) return null

            // Optional audio rendition: probe ONE audio segment and add its
            // projected size (sample * audio segment count) to the total.
            val audioUri = audioPlaylistUri(masterText)
            if (audioUri != null) {
                val audioUrl = resolveUrl(masterUrl, audioUri) ?: return null
                val audioText = HttpClient.getText(audioUrl, referer = referer, tag = "hls-estimate") ?: return null
                if (!audioText.contains("#EXT-X-ENDLIST")) return null
                val audioCount = parseSegmentCount(audioText)
                if (audioCount > 0) {
                    val audioSegments = segmentUrls(audioText, audioUrl)
                    if (audioSegments.isEmpty()) return null
                    // Middle segment: representative of a constant-bitrate
                    // audio track (the first can be an intro outlier).
                    val audioSample = probeSegmentSize(
                        audioSegments[(audioCount / 2).coerceIn(0, audioSegments.size - 1)],
                        referer
                    ) ?: return null
                    total += estimateFromSamples(listOf(audioSample), audioCount, fmp4)
                }
            }
            return total
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Headers-only size probe: GET with `Range: bytes=0-0` and read the full
     * size out of the response headers — the body is closed, never read
     * (same pattern as HttpClient.probe).
     *  - 206 (range honored): the full size rides in `Content-Range:
     *    bytes 0-0/TOTAL`; Content-Length is only the 1-byte slice.
     *  - 200 (server ignored Range): Content-Length is the full size.
     * `Accept-Encoding: identity` keeps OkHttp from transparently gzipping
     * (which would strip Content-Length from the response).
     */
    private fun probeSegmentSize(url: String, referer: String?): Long? {
        return try {
            HttpClient.get(
                url,
                referer = referer,
                headers = mapOf("Range" to "bytes=0-0", "Accept-Encoding" to "identity"),
                tag = "hls-estimate"
            ).use { res ->
                when (res.code) {
                    206 -> res.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                    200 -> res.header("Content-Length")?.toLongOrNull()
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Non-tag, non-blank lines of a playlist, resolved against [baseUrl]. */
    private fun segmentUrls(playlistText: String, baseUrl: String): List<String> {
        return playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { resolveUrl(baseUrl, it) }
            .toList()
    }

    /** Absolute-https resolution of a playlist-relative / protocol-relative URI. */
    private fun resolveUrl(base: String, uri: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("//")) return "https:$uri"
        return try {
            HttpClient.safeResolveUri(base, uri)
        } catch (_: Exception) {
            null
        }
    }

    /** fMP4 detection: no segment name carries a .ts extension. */
    private fun isFmp4(segmentUrls: List<String>): Boolean =
        segmentUrls.none { it.substringBefore('?').substringBefore('#').endsWith(".ts", ignoreCase = true) }

    /** First #EXT-X-MEDIA:TYPE=AUDIO rendition that carries a URI, or null. */
    private fun audioPlaylistUri(masterText: String): String? {
        for (line in masterText.lineSequence()) {
            val l = line.trim()
            if (!l.startsWith("#EXT-X-MEDIA")) continue
            if (!l.contains("TYPE=AUDIO")) continue
            val m = URI_VALUE.find(l) ?: continue
            return m.groupValues[1]
        }
        return null
    }
}
