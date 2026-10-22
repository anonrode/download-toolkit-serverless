package com.anonrode.downloader.providers

import com.anonrode.downloader.data.net.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.LinkedHashMap

/**
 * One subtitle track a SITE exposed (nsrplay/vidsrc resolve JSON, `<track>`
 * tag, or a direct `.srt/.vtt/.ass` link).
 *
 * This is the provider-owned side of the subtitle contract: providers parse
 * the source they already fetched and record the tracks here, keyed by the
 * episode/show URL the engine will download from. The engine consumes
 * [SubtitleSurfaces.tracksFor] when it decides to write sidecars — the
 * download plumbing itself stays in the engine.
 */
data class SiteSubtitleTrack(
    val url: String,
    /** ISO-639-1/2 code when the site declares one; "und" otherwise. */
    val language: String,
    val label: String = "",
    /** srt | vtt | ass | ssa (lowercase, best-effort from the URL). */
    val format: String = ""
)

/**
 * Site-side subtitle extraction + a bounded URL-keyed cache.
 *
 * Live shapes covered:
 *  - nsrplay resolve JSON: `data.subtitles: [{url, language, label}]`
 *    (typed and present in the live payload; null for some titles).
 *  - vidsrc-family play JSON: top-level `subtitles` / `tracks` arrays with
 *    `url|file|src` and `language|lang|srclang|label`.
 *  - HTML pages: `<track src srclang label>` (some themes) and direct
 *    subtitle anchors (`*.srt`, `*.vtt`, `*.ass`).
 *
 * Pure parsers are JVM-tested from captured fixtures.
 */
object SubtitleSurfaces {

    private const val MAX_ENTRIES = 128
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private data class Entry(val tracks: List<SiteSubtitleTrack>, val expiresAtMs: Long)

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    private val SUB_EXTS = setOf("srt", "vtt", "ass", "ssa")

    /** Records the tracks a provider found for [episodeOrShowUrl]. */
    fun record(episodeOrShowUrl: String, tracks: List<SiteSubtitleTrack>) {
        val key = normalizeKey(episodeOrShowUrl)
        if (key.isEmpty() || tracks.isEmpty()) return
        val clean = tracks.filter { it.url.startsWith("http", ignoreCase = true) }.distinctBy { it.url.lowercase() }
        if (clean.isEmpty()) return
        synchronized(lock) {
            cache[key] = Entry(clean, System.currentTimeMillis() + TTL_MS)
        }
    }

    /** Tracks recorded for this episode/show URL (empty when none/expired). */
    fun tracksFor(episodeOrShowUrl: String): List<SiteSubtitleTrack> {
        val key = normalizeKey(episodeOrShowUrl)
        if (key.isEmpty()) return emptyList()
        synchronized(lock) {
            val entry = cache[key] ?: return emptyList()
            if (entry.expiresAtMs < System.currentTimeMillis()) {
                cache.remove(key)
                return emptyList()
            }
            return entry.tracks
        }
    }

    internal fun clearForTest() = synchronized(lock) { cache.clear() }

    /**
     * Convenience for a resolve step that already holds the payload bytes:
     * parse `data.subtitles` / `subtitles` / `tracks` and record them for the
     * episode URL the engine is about to download.
     */
    fun recordFromResolveJson(episodeOrShowUrl: String, json: String): List<SiteSubtitleTrack> {
        val tracks = parseResolveJson(json)
        record(episodeOrShowUrl, tracks)
        return tracks
    }

    /**
     * OpenSubtitles.org hash: file size + the sum of the little-endian
     * uint64 words in the first and last 64 KiB. Zero network and zero extra
     * parsing — the engine can compute it on the already-downloaded file and
     * query the hash endpoint as the LAST-RESORT real-subtitle source for
     * titles whose site payloads carry no `subtitles` list.
     *
     * Returns null for files smaller than 128 KiB (the format's minimum).
     */
    fun opensubtitlesHash(file: java.io.File): String? {
        val size = file.length()
        if (size < 131_072L) return null
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                var hash = size
                fun addChunk(offset: Long) {
                    val buffer = ByteArray(65_536)
                    raf.seek(offset)
                    var read = 0
                    while (read < buffer.size) {
                        val n = raf.read(buffer, read, buffer.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    var i = 0
                    while (i + 7 < read) {
                        var word = 0L
                        for (b in 7 downTo 0) {
                            word = (word shl 8) or (buffer[i + b].toLong() and 0xFFL)
                        }
                        hash += word
                        i += 8
                    }
                }
                addChunk(0L)
                addChunk(size - 65_536L)
                java.lang.Long.toHexString(hash).padStart(16, '0')
            }
        } catch (_: Exception) {
            null
        }
    }

    fun normalizeKey(url: String): String =
        url.trim().substringBefore('#').trimEnd('/').lowercase()

    /** Best-effort extension from a URL path. */
    fun formatOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in SUB_EXTS) ext else ""
    }

    /** True when the payload is a WebVTT document (BOM tolerated). */
    fun looksLikeVtt(text: String): Boolean =
        text.removePrefix("\uFEFF").trimStart().startsWith("WEBVTT", ignoreCase = true)

    /**
     * Subtitle list from a resolve JSON payload. Tolerant by design: the
     * player families disagree on the key names and `subtitles` is often
     * `null` — that is a valid "no tracks", not an error.
     */
    fun parseResolveJson(json: String): List<SiteSubtitleTrack> {
        if (json.isBlank()) return emptyList()
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val containers = mutableListOf<JSONArray?>()
        val data = root.optJSONObject("data")
        containers.add(root.optJSONArray("subtitles"))
        containers.add(root.optJSONArray("tracks"))
        if (data != null) {
            containers.add(data.optJSONArray("subtitles"))
            containers.add(data.optJSONArray("tracks"))
            containers.add(data.optJSONObject("subtitles")?.optJSONArray("tracks"))
        }
        val out = mutableListOf<SiteSubtitleTrack>()
        for (array in containers) {
            if (array == null) continue
            for (i in 0 until array.length()) {
                when (val item = array.opt(i)) {
                    is JSONObject -> {
                        val url = item.optString("url")
                            .ifBlank { item.optString("file") }
                            .ifBlank { item.optString("src") }
                            .ifBlank { item.optString("path") }
                        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) continue
                        val lang = item.optString("language")
                            .ifBlank { item.optString("lang") }
                            .ifBlank { item.optString("srclang") }
                            .ifBlank { item.optString("code") }
                            .ifBlank { "und" }
                        val label = item.optString("label").ifBlank { item.optString("name") }
                        out.add(
                            SiteSubtitleTrack(
                                url = url,
                                language = lang.trim().lowercase(),
                                label = label.trim(),
                                format = formatOf(url)
                            )
                        )
                    }
                    is String -> {
                        if (item.startsWith("http", ignoreCase = true)) {
                            out.add(
                                SiteSubtitleTrack(
                                    url = item,
                                    language = "und",
                                    label = "",
                                    format = formatOf(item)
                                )
                            )
                        }
                    }
                }
            }
        }
        return out.distinctBy { it.url.lowercase() }
    }

    /**
     * Subtitle surfaces on a fetched page: `<track>` elements plus anchors
     * whose URL or label is clearly a subtitle file (never episode links).
     */
    fun parsePageTracks(doc: Document, baseUrl: String): List<SiteSubtitleTrack> {
        val out = mutableListOf<SiteSubtitleTrack>()
        for (track in doc.select("track[src]")) {
            val raw = track.attr("src")
            val url = if (raw.startsWith("http", ignoreCase = true)) raw
            else HttpClient.safeResolveUri(baseUrl, raw)
            if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) continue
            val lang = track.attr("srclang").ifBlank { track.attr("lang") }.ifBlank { "und" }
            out.add(
                SiteSubtitleTrack(
                    url = url,
                    language = lang.trim().lowercase(),
                    label = track.attr("label").trim(),
                    format = formatOf(url)
                )
            )
        }
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href")
            val url = if (raw.startsWith("http", ignoreCase = true)) raw
            else HttpClient.safeResolveUri(baseUrl, raw)
            if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) continue
            val fmt = formatOf(url)
            val text = a.text().trim().lowercase()
            if (fmt.isEmpty() && !text.contains("subtitle") && !text.contains("subtitles") && !text.contains(" sub ")) continue
            if (fmt.isEmpty()) continue
            if (url.contains("/episode", ignoreCase = true)) continue
            val lang = Regex("""(?i)\b(en|eng|es|spa|fr|fre|pt|por|ar|ara|hi|hin|zh|chi|ja|jpn|ko|kor)\b""")
                .find(url.substringAfterLast('/'))?.groupValues?.get(1)?.lowercase()
                ?: "und"
            out.add(
                SiteSubtitleTrack(
                    url = url,
                    language = lang,
                    label = a.text().trim(),
                    format = fmt
                )
            )
        }
        return out.distinctBy { it.url.lowercase() }
    }

    /**
     * WebVTT → SubRip.
     *
     * Pure and total: header/NOTE/STYLE/REGION blocks are dropped, cue
     * timestamps lose their cue settings, `.` becomes `,`, and cues are
     * renumbered. SRT consumers (and ExoPlayer's SubRip parser) reject a
     * WEBVTT header, which is exactly the mislabeled-sidecar class this
     * conversion exists for.
     */
    fun vttToSrt(vtt: String): String {
        val text = vtt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        val out = StringBuilder()
        var index = 1
        var i = 0
        val ts = Regex("""^\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3}\s*-->\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{3}.*$""")
        fun isTimestamp(line: String) = ts.matches(line)

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }
            val upper = trimmed.uppercase()
            if (upper.startsWith("WEBVTT") || upper.startsWith("NOTE") ||
                upper.startsWith("STYLE") || upper.startsWith("REGION")
            ) {
                // Skip the block header and any non-cue payload lines.
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    if (isTimestamp(lines[i])) break
                    i++
                }
                continue
            }
            // Optional numeric cue id before the timestamp.
            var tsLine = trimmed
            if (!isTimestamp(tsLine) && i + 1 < lines.size && isTimestamp(lines[i + 1])) {
                i++
                tsLine = lines[i].trim()
            }
            if (!isTimestamp(tsLine)) {
                i++
                continue
            }
            val arrow = tsLine.replace(',', '.')
            val start = arrow.substringBefore("-->").trim().trimStart('.')
            val end = arrow.substringAfter("-->").trim()
                .substringBefore(' ') // drop cue settings (align:, position:, …)
                .replace(',', '.')
            out.append(index++).append('\n')
            out.append(start.replace('.', ',')).append(" --> ").append(end.replace('.', ',')).append('\n')
            i++
            val cue = StringBuilder()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                cue.append(lines[i].trim()).append('\n')
                i++
            }
            val cleaned = cue.toString()
                .replace(Regex("""</?c(?:\.[^>]*)?>"""), "")
                .replace(Regex("""</?v(?:\s+[^>]*)?>"""), "")
                .replace(Regex("""</?(?:ruby|rt|lang)[^>]*>"""), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            out.append(cleaned.trimEnd('\n')).append("\n\n")
        }
        return out.toString().trimEnd() + "\n"
    }
}
