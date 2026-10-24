package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder

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
     * A player/bridge URL can hide the real manifest in a query parameter:
     *   https://bridge.example/?url=https%3A%2F%2Fcdn%2Fmaster.m3u8
     * Path-only classification called that a direct `.mp4` and downloaders
     * saved the playlist text as a video (NEPU 10-22 15:39). Decode query
     * values recursively (bounded) and return the first embedded manifest URL.
     */
    fun embeddedStreamingUrl(rawUrl: String, depth: Int = 0): String? {
        if (depth == 0) {
            DynamicRulesManager.unwrapProxyUrl(rawUrl)?.let { return it }
        }
        if (depth > 2) return null
        val query = rawUrl.substringAfter('?', "")
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val value = pair.substringAfter('=', "")
            if (value.isBlank()) continue
            val decoded = try {
                URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
                value
            }
            if (decoded.isBlank() || decoded == rawUrl) continue
            if (!decoded.startsWith("http", ignoreCase = true) && !decoded.startsWith("/")) continue
            val low = decoded.lowercase()
            val path = low.substringBefore('?').substringBefore('#')
            if (STREAMING_EXTENSIONS.any { path.endsWith(".$it") } ||
                path.endsWith("/manifest") ||
                path.contains("/manifest.") ||
                path.contains("/hls/")
            ) {
                return decoded
            }
            embeddedStreamingUrl(decoded, depth + 1)?.let { return it }
        }
        return null
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
            return STREAMING_QUERY_REGEX.containsMatchIn(query) ||
                embeddedStreamingUrl(rawUrl) != null
        }

        if (ext in STREAMING_EXTENSIONS) return true
        if (path.endsWith("/manifest") || path.contains("/manifest.") || path.contains("/hls/") || path.contains(".m3u8")) {
            return true
        }
        if (DynamicRulesManager.isStreamingUrlMarker(rawUrl)) {
            return true
        }
        if (low.contains("kickassanime") || low.contains("playlist.php") ||
            low.contains("/api/orion/")
        ) {
            return true
        }
        // `stream-proxy` was hardcoded here (HLS) AND in LinkResolver
        // (provably-direct) until 2026-10-20. The two contradicted each other
        // and both were wrong: nsrplay's proxy is an opaque endpoint whose
        // BODY decides (a self-referential manifest when it is the decoy, a
        // direct MP4 byte-range when it is the real stream). Classification
        // is evidence-based now — see inspectTerminal() — so no URL-name
        // heuristic for it remains.
        if (embeddedStreamingUrl(rawUrl) != null) return true
        val query = low.substringAfter('?', "")
        return STREAMING_QUERY_REGEX.containsMatchIn(query)
    }

    // ---------------------------------------------------------------- evidence
    //
    // Terminal classification for the download router (2026-10-20). One
    // bounded probe decides what a URL actually IS:
    //   DIRECT_MEDIA     -> TurboDownloader / aria2c (byte-range file)
    //   HLS_PLAYLIST     -> yt-dlp (genuine multi-variant m3u8 / media playlist)
    //   SELF_REFERENTIAL -> reject + poison (a "master" whose every variant is
    //                       itself with a rotated token; nsrplay stream-proxy)
    //   STUB             -> reject + poison (1-byte / sub-1KiB mpegurl decoy)
    //   UNKNOWN          -> no structural verdict; caller keeps its fallback
    //
    // The body check runs ONLY for manifest-shaped responses (mpegurl
    // content-type or .m3u8/.mpd URL or no media extension), so a direct MP4
    // costs exactly the existing 1 KiB probe. Results are cached by
    // host+path (query/token rotates per request; the SHAPE does not), which
    // keeps RulesPipeline validation, LinkResolver acceptance and the
    // DownloadEngine backend decision from re-fetching the same terminal.

    enum class TerminalKind { DIRECT_MEDIA, HLS_PLAYLIST, SELF_REFERENTIAL, STUB, UNKNOWN }

    data class TerminalEvidence(
        val kind: TerminalKind,
        val code: Int = 0,
        val contentType: String = "",
        val totalBytes: Long? = null,
        val reason: String = ""
    ) {
        /** True only for a terminal that a downloader can actually consume. */
        val playable: Boolean
            get() = kind == TerminalKind.DIRECT_MEDIA || kind == TerminalKind.HLS_PLAYLIST
    }

    private const val EVIDENCE_TTL_MS = 5 * 60_000L
    private const val EVIDENCE_MAX_ENTRIES = 128
    private const val MANIFEST_BODY_MAX_BYTES = 32L * 1024L

    private data class EvidenceEntry(val evidence: TerminalEvidence, val expiresAtMs: Long)

    private val evidenceCache = java.util.concurrent.ConcurrentHashMap<String, EvidenceEntry>()

    /** Cache key: origin + path, query deliberately excluded (tokens rotate).
     *  Bridge URLs are the exception: the embedded `url=` IS the media plan,
     *  so different episodes on one bridge path must not share evidence. */
    internal fun evidenceKey(rawUrl: String): String {
        val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return rawUrl.substringBefore('?').lowercase()
        val base = "${parsed.scheme}://${parsed.host}${parsed.port.let { if (it == 80 || it == 443) "" else ":$it" }}${parsed.encodedPath}"
        val embedded = embeddedStreamingUrl(rawUrl) ?: return base
        return "$base?embedded=${Integer.toHexString(embedded.hashCode())}"
    }

    fun cachedTerminalEvidence(rawUrl: String): TerminalEvidence? {
        val key = evidenceKey(rawUrl)
        val e = evidenceCache[key] ?: return null
        if (System.currentTimeMillis() >= e.expiresAtMs) {
            evidenceCache.remove(key, e)
            return null
        }
        return e.evidence
    }

    internal fun rememberTerminalEvidence(rawUrl: String, evidence: TerminalEvidence) {
        if (evidence.kind == TerminalKind.UNKNOWN) return
        if (evidenceCache.size >= EVIDENCE_MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            evidenceCache.entries.removeIf { now >= it.value.expiresAtMs }
            if (evidenceCache.size >= EVIDENCE_MAX_ENTRIES) {
                evidenceCache.keys.take(evidenceCache.size / 2).forEach { evidenceCache.remove(it) }
            }
        }
        evidenceCache[evidenceKey(rawUrl)] = EvidenceEntry(
            evidence,
            System.currentTimeMillis() + EVIDENCE_TTL_MS
        )
    }

    /** Tests only. */
    internal fun clearEvidenceCacheForTests() = evidenceCache.clear()

    fun isManifestShaped(rawUrl: String, contentType: String = ""): Boolean {
        val ct = contentType.lowercase()
        if (ct.contains("mpegurl") || ct.contains("dash+xml")) return true
        if (embeddedStreamingUrl(rawUrl) != null) return true
        val path = rawUrl.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd") || path.endsWith(".f4m")) return true
        return path.contains("/manifest") || path.contains("playlist")
    }

    fun isDirectByContentType(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.startsWith("video/") || ct.startsWith("audio/") ||
            ct.contains("application/octet-stream") || ct.contains("application/mp4")
    }

    /** True when the URL path itself proves a direct media file. */
    fun isDirectMediaPath(rawUrl: String): Boolean {
        val path = rawUrl.substringBefore('?').substringBefore('#').lowercase()
        val last = path.substringAfterLast('/')
        val ext = last.substringAfterLast('.', "")
        return ext in setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "m4v", "mp3", "m4a", "aac", "flac", "opus", "ogg", "wav")
    }

    /**
     * Structural self-loop detector for a manifest body. Every variant /
     * segment URI that points back to the SAME host+path as the manifest
     * (query-only difference — the rotated token) proves the playlist can
     * never reach media. Comparison ignores the query because that is exactly
     * how the nsrplay proxy loops.
     */
    fun isSelfReferentialManifest(body: String, manifestUrl: String): Boolean {
        if (body.isBlank()) return false
        val uris = mutableListOf<String>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            if (!line.startsWith("#")) {
                uris.add(line)
                continue
            }
            URI_VALUE.findAll(line).forEach { m ->
                val v = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (v.isNotBlank()) uris.add(v)
            }
        }
        if (uris.isEmpty()) return false
        val self = canonicalMediaPath(manifestUrl) ?: return false
        return uris.all { uri ->
            val resolved = HttpClient.safeResolveUri(manifestUrl, uri)
            canonicalMediaPath(resolved) == self
        }
    }

    /** host+path canonical form; null when unparseable. Query/hash dropped.
     *  When the URL is a streaming proxy worker (dash-hls-bridge / ?url=), canonicalizes
     *  the embedded target stream so proxy worker manifests are not falsely flagged
     *  as self-referential loops. */
    internal fun canonicalMediaPath(rawUrl: String): String? {
        val target = DynamicRulesManager.unwrapProxyUrl(rawUrl)
            ?: embeddedStreamingUrl(rawUrl)
            ?: rawUrl
        val parsed = target.trim().toHttpUrlOrNull() ?: return null
        val port = if (parsed.port == 80 || parsed.port == 443) "" else ":${parsed.port}"
        return "${parsed.host.lowercase()}$port${parsed.encodedPath}"
    }

    /**
     * Tiny mpegurl gate: a manifest under [HLS_STUB_MIN_BYTES] that carries
     * neither a variant tag nor a media segment is a decoy, never a playlist.
     */
    fun isTinyManifest(body: String, contentType: String, totalBytes: Long?): Boolean {
        val bytes = totalBytes ?: body.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes >= LinkResolver.HLS_STUB_MIN_BYTES) return false
        val ct = contentType.lowercase()
        val looksManifest = ct.contains("mpegurl") || body.trimStart().startsWith("#EXTM3U")
        if (!looksManifest) return false
        val hasVariant = body.contains("#EXT-X-STREAM-INF", ignoreCase = true)
        val hasSegment = body.contains("#EXTINF", ignoreCase = true)
        return !hasVariant && !hasSegment
    }

    /**
     * Bounded evidence probe. [referer] is attached to the body fetch (a
     * token-gated CDN may require it; classification must see the real body).
     * Never throws: any transport failure yields UNKNOWN.
     */
    suspend fun inspectTerminal(
        rawUrl: String,
        referer: String? = null,
        tag: String? = "terminal-evidence"
    ): TerminalEvidence {
        if (rawUrl.isBlank()) return TerminalEvidence(TerminalKind.UNKNOWN, reason = "blank")
        // Direct media path is proof enough; no network at all.
        if (isDirectMediaPath(rawUrl) && !isStreamingLink(rawUrl)) {
            return TerminalEvidence(TerminalKind.DIRECT_MEDIA, reason = "media path extension")
        }
        cachedTerminalEvidence(rawUrl)?.let { return it }

        val effectiveReferer = if (!referer.isNullOrBlank()) referer
        else ResolvedHints.refererFor(rawUrl) ?: com.anonrode.downloader.data.rules.DynamicRulesManager.resolveReferer(rawUrl)

        if (isManifestShaped(rawUrl)) {
            // M3U8-1: Manifest-shaped URLs skip the no-redirect probeTerminal call
            // and do one bounded GET (maxBytes = 32 KB) that follows redirects,
            // checks status/Content-Type/body, and caches the verdict.
            val body = try {
                com.anonrode.downloader.data.net.HttpClient.getText(
                    rawUrl, referer = effectiveReferer, tag = tag, maxBytes = MANIFEST_BODY_MAX_BYTES
                ).orEmpty()
            } catch (_: Exception) {
                ""
            }
            val trimmed = body.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            val isM3u = trimmed.startsWith("#EXTM3U")
            val isDash = trimmed.startsWith("<MPD") ||
                (trimmed.startsWith("<?xml") && trimmed.contains("<MPD", ignoreCase = true))
            val rawBytes = body.toByteArray(Charsets.ISO_8859_1)
            val bodyBytes = rawBytes.size.toLong()
            val isBinaryMedia = (rawBytes.size >= 8 && String(rawBytes, 4, 4, Charsets.US_ASCII).lowercase() in setOf("ftyp", "moov", "mdat", "styp")) ||
                (rawBytes.size >= 4 && rawBytes[0] == 0x1A.toByte() && rawBytes[1] == 0x45.toByte()) ||
                (rawBytes.isNotEmpty() && rawBytes[0] == 0x47.toByte())
            val ct = if (isDash) "application/dash+xml" else if (isBinaryMedia) "video/mp4" else "application/vnd.apple.mpegurl"
            val ev = when {
                trimmed.isBlank() ->
                    TerminalEvidence(TerminalKind.UNKNOWN, 0, ct, null, "manifest body unreadable")
                isDash ->
                    TerminalEvidence(TerminalKind.HLS_PLAYLIST, 200, ct, bodyBytes, "genuine DASH manifest")
                isBinaryMedia ->
                    TerminalEvidence(TerminalKind.DIRECT_MEDIA, 200, "video/mp4", bodyBytes, "binary media stream")
                !isM3u ->
                    TerminalEvidence(TerminalKind.STUB, 200, ct, bodyBytes, "mpegurl body is not an M3U document")
                isTinyManifest(trimmed, ct, bodyBytes) ->
                    TerminalEvidence(TerminalKind.STUB, 200, ct, bodyBytes, "tiny mpegurl (${bodyBytes}B) with no variants/segments")
                isSelfReferentialManifest(trimmed, rawUrl) ->
                    TerminalEvidence(TerminalKind.SELF_REFERENTIAL, 200, ct, bodyBytes, "every playlist URI points back to the playlist itself")
                else ->
                    TerminalEvidence(TerminalKind.HLS_PLAYLIST, 200, ct, bodyBytes, "genuine playlist")
            }
            rememberTerminalEvidence(rawUrl, ev)
            return ev
        }

        val probe = try {
            com.anonrode.downloader.data.net.HttpClient.probeTerminal(
                rawUrl, referer = effectiveReferer, timeoutMs = 6_000L, tag = tag
            )
        } catch (_: Exception) {
            null
        } ?: return TerminalEvidence(TerminalKind.UNKNOWN, reason = "probe failed")

        val ct = probe.contentType
        val total = probe.totalBytes
        if (total == null) {
            val ev = TerminalEvidence(TerminalKind.UNKNOWN, probe.code, ct, null, "no size proof")
            return ev
        }
        val manifestShaped = isManifestShaped(rawUrl, ct)
        if (!manifestShaped) {
            val kind = when {
                isDirectByContentType(ct) -> TerminalKind.DIRECT_MEDIA
                isDirectMediaPath(rawUrl) -> TerminalKind.DIRECT_MEDIA
                else -> TerminalKind.UNKNOWN
            }
            val ev = TerminalEvidence(kind, probe.code, ct, total, "non-manifest ${ct.ifBlank { "unknown ct" }}")
            rememberTerminalEvidence(rawUrl, ev)
            return ev
        }
        // Manifest: read a bounded head. getText uses the shared client
        // (redirects followed) but the URL was just probe-validated and is
        // host-gated by the caller.
        val body = try {
            com.anonrode.downloader.data.net.HttpClient.getText(
                rawUrl, referer = effectiveReferer, tag = tag, maxBytes = MANIFEST_BODY_MAX_BYTES
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val trimmed = body.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val isM3u = trimmed.startsWith("#EXTM3U")
        val isDash = trimmed.startsWith("<MPD") ||
            (trimmed.startsWith("<?xml") && trimmed.contains("<MPD", ignoreCase = true))
        val ev = when {
            trimmed.isBlank() ->
                TerminalEvidence(TerminalKind.UNKNOWN, probe.code, ct, total, "manifest body unreadable")
            isDash ->
                TerminalEvidence(TerminalKind.HLS_PLAYLIST, probe.code, ct, total, "genuine DASH manifest")
            !isM3u ->
                TerminalEvidence(TerminalKind.STUB, probe.code, ct, total, "mpegurl body is not an M3U document")
            isTinyManifest(trimmed, ct, total) ->
                TerminalEvidence(TerminalKind.STUB, probe.code, ct, total, "tiny mpegurl (${total}B) with no variants/segments")
            isSelfReferentialManifest(trimmed, rawUrl) ->
                TerminalEvidence(TerminalKind.SELF_REFERENTIAL, probe.code, ct, total, "every playlist URI points back to the playlist itself")
            else ->
                TerminalEvidence(TerminalKind.HLS_PLAYLIST, probe.code, ct, total, "genuine playlist")
        }
        rememberTerminalEvidence(rawUrl, ev)
        return ev
    }

    /**
     * Generic repair for a doubled scheme produced by a payload template
     * ("https://vidsrc.buzz" + a bound url that already starts with
     * "https://vidsrc.buzz" -> "https://vidsrc.buzzhttps://vidsrc.buzz/...").
     * Only repairs when the text before the second scheme is a BARE ORIGIN of
     * the same host — a legitimate `?url=https://...` parameter carries a
     * path/query before the embedded scheme and is left untouched.
     */
    fun repairConcatenatedUrl(rawUrl: String): String {
        val value = rawUrl.trim()
        val firstScheme = value.indexOf("://")
        if (firstScheme < 0) return value
        val authorityStart = firstScheme + 3
        val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it < 0) value.length else it }
        val authority = value.substring(authorityStart, authorityEnd)
        // Host-suffix concatenation: the device log produced
        // `vidsrc.buzzhttps:/…` and `vidsrc.buzzhttps://…`, where OkHttp
        // parsed the whole glued token as the HOST (`vidsrc.buzzhttps`).
        val embeddedInAuthority = authority.indexOf("http", ignoreCase = true)
        if (embeddedInAuthority > 0) {
            val prefixOrigin = value.substring(0, authorityStart) +
                authority.substring(0, embeddedInAuthority)
            val rest = authority.substring(embeddedInAuthority) + value.substring(authorityEnd)
            val normalizedRest = if (rest.startsWith("https:/") && !rest.startsWith("https://")) {
                "https://" + rest.removePrefix("https:/")
            } else if (rest.startsWith("http:/") && !rest.startsWith("http://")) {
                "http://" + rest.removePrefix("http:/")
            } else {
                rest
            }
            if ((normalizedRest.startsWith("http://", true) ||
                    normalizedRest.startsWith("https://", true)) &&
                extractHost(prefixOrigin).isNotBlank() &&
                extractHost(prefixOrigin) == extractHost(normalizedRest)
            ) {
                return normalizedRest
            }
        }
        val second = value.indexOf("://", firstScheme + 3)
        if (second < 0) return value
        val start = value.lastIndexOf("http", second)
        if (start <= 0) return value
        val prefix = value.substring(0, start)
        val rest = value.substring(start)
        if (!rest.startsWith("http://", true) && !rest.startsWith("https://", true)) return value
        val bareOrigin = prefix.trimEnd('/').lowercase()
        val originOk = bareOrigin.matches(Regex("""https?://[a-z0-9.-]+"""))
        if (!originOk) return value
        val prefixHost = bareOrigin.substringAfter("://").removePrefix("www.")
        val restHost = extractHost(rest).removePrefix("www.")
        return if (prefixHost.isNotBlank() && prefixHost == restHost) rest else value
    }
}

private val URI_VALUE = Regex("URI=\"([^\"]+)\"")
