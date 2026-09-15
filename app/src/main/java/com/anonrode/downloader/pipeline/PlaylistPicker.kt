package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import org.json.JSONObject

/**
 * YouTube playlist support — the pure half. Everything here is string/JSON
 * logic with ZERO Android imports and ZERO Regex (device-engine doctrine:
 * the app's regexes run on Android's ICU engine; this file sidesteps the
 * question entirely with string ops), so it is fully JVM-unit-tested.
 *
 * What this beats Seal with (Seal = checkbox list + select-all + index-range
 * dialog, one task per entry, no dedupe, no search, no size estimate):
 *  - a playlist URL AND a watch URL that carries ?list= both open the picker
 *    (Seal only reacts to playlist-shaped URLs);
 *  - already-downloaded entries are detected and badged, and skipped by
 *    default on confirm (Seal re-queues the whole list blindly);
 *  - a typed range ("1-5,8,10-12") plus search filter plus invert;
 *  - honest size estimate from durations x the chosen quality's typical
 *    bitrate — Seal shows nothing;
 *  - items enqueue as ONE group: showTitle = playlist title, episodeNum =
 *    playlist position — so the existing group-by-show ordering and the
 *    player's episode-ordered Next queue walk the playlist like a series
 *    (Seal's playlist tasks are loose singles).
 */
object PlaylistPicker {

    enum class ListKind { PLAYLIST, MIX }

    /** A YouTube URL that carries a list= parameter. */
    data class ParsedList(
        val kind: ListKind,
        val listId: String,
        /** Video id when the URL was a watch link inside the playlist. */
        val videoId: String,
        /** Clean playlist URL fed to yt-dlp. */
        val playlistUrl: String
    )

    data class Entry(
        val videoId: String,
        val watchUrl: String,
        val title: String,
        val durationSec: Int,
        val thumbnailUrl: String,
        val uploader: String
    )

    data class PlaylistMeta(
        val title: String,
        val uploader: String,
        val entries: List<Entry>
    )

    /**
     * Detect a playlist-carrying YouTube URL. Returns null for anything that
     * is not youtube/youtu.be or has no (or empty) list= parameter. RD-prefixed
     * ids are auto-mixes (regenerating, arbitrary order) — surfaced as MIX so
     * the UI can warn. Pure string ops, no regex.
     */
    fun detect(url: String): ParsedList? {
        val u = url.trim()
        val lower = u.lowercase()
        val isYoutube = lower.contains("youtube.com") || lower.contains("youtu.be")
        if (!isYoutube) return null
        val query = u.substringAfter('?', "")
        if (query.isEmpty()) return null
        var listId = ""
        for (pair in query.split('&')) {
            if (pair.startsWith("list=", ignoreCase = true)) {
                listId = pair.substringAfter('=').substringBefore('#').trim()
            }
        }
        if (listId.isEmpty()) return null

        // Video id: youtu.be/<id> or v= on youtube.com
        var videoId = ""
        if (lower.contains("youtu.be/")) {
            videoId = u.substringAfter("youtu.be/", "").substringAfterLast('/')
                .substringBefore('?').substringBefore('#')
        } else {
            for (pair in query.split('&')) {
                if (pair.startsWith("v=", ignoreCase = true)) {
                    videoId = pair.substringAfter('=').substringBefore('#').trim()
                }
            }
        }
        // Mix ids start RD (RD*, RDEXO*, OM, PM are mix/radio variants); PL/OU/LU/LC/UU/FM are real lists.
        val kind = if (listId.startsWith("RD", ignoreCase = true) ||
            listId.startsWith("PM", ignoreCase = true) ||
            listId.startsWith("OM", ignoreCase = true)
        ) ListKind.MIX else ListKind.PLAYLIST

        return ParsedList(
            kind = kind,
            listId = listId,
            videoId = videoId,
            playlistUrl = "https://www.youtube.com/playlist?list=$listId"
        )
    }

    /**
     * Parse a `yt-dlp -J --flat-playlist` dump. Tolerates relative
     * "/watch?v=" urls, absent titles/durations/thumbnails, and single-video
     * JSON with no entries array (returns empty entries so the caller can
     * show "not a playlist"). Returns null only on unparseable input — a
     * failed fetch is the caller's error path.
     */
    fun parseFlatJson(json: String): PlaylistMeta? {
        if (json.isBlank()) return null
        return try {
            val root = JSONObject(json)
            val title = root.optString("title", "")
            val uploader = firstNonBlank(
                root.optString("channel", ""), root.optString("uploader", ""),
                root.optString("uploader_id", "")
            )
            val entriesArr = root.optJSONArray("entries") ?: return PlaylistMeta(title, uploader, emptyList())
            val entries = ArrayList<Entry>(entriesArr.length())
            for (i in 0 until entriesArr.length()) {
                val e = entriesArr.optJSONObject(i) ?: continue
                val id = e.optString("id", "")
                if (id.isEmpty()) continue
                var url = e.optString("url", "").ifEmpty { e.optString("webpage_url", "") }
                if (url.startsWith("//")) url = "https:" + url
                if (url.startsWith("/")) url = "https://www.youtube.com" + url
                if (url.isEmpty() || !url.contains("http")) url = "https://www.youtube.com/watch?v=$id"
                var thumb = ""
                val thumbs = e.optJSONArray("thumbnails")
                if (thumbs != null && thumbs.length() > 0) {
                    // Highest-res thumbnails come last in yt-dlp's list.
                    thumb = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                }
                entries.add(
                    Entry(
                        videoId = id,
                        watchUrl = url,
                        title = e.optString("title", "").ifBlank { "Video ${i + 1}" },
                        durationSec = e.optDouble("duration", 0.0).toInt(),
                        thumbnailUrl = thumb,
                        uploader = firstNonBlank(e.optString("channel", ""), e.optString("uploader", ""), uploader)
                    )
                )
            }
            PlaylistMeta(title, uploader, entries)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parse a selection spec into 1-based entry indices: "1-5,8,10-12".
     * Clamped to 1..size, deduped, order preserved (so "8,1-3" selects 8
     * first). Any malformed token rejects the WHOLE spec with null — a
     * silently-partial range would queue the wrong videos.
     */
    fun parseRanges(spec: String, size: Int): List<Int>? {
        val out = LinkedHashSet<Int>()
        for (raw in spec.split(',')) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            val dash = token.indexOf('-')
            if (dash < 0) {
                val n = token.toIntOrNull() ?: return null
                if (n < 1 || n > size) return null
                out.add(n)
            } else {
                val a = token.substring(0, dash).trim().toIntOrNull() ?: return null
                val b = token.substring(dash + 1).trim().toIntOrNull() ?: return null
                if (a < 1 || b > size || a > b) return null
                for (n in a..b) out.add(n)
            }
        }
        return if (out.isEmpty()) null else out.toList()
    }

    /** 1-based indices of entries whose title/uploader contains [query] (blank = all). */
    fun filterIndices(entries: List<Entry>, query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return entries.indices.map { it + 1 }
        return entries.withIndex()
            .filter { (_, e) ->
                e.title.contains(q, ignoreCase = true) || e.uploader.contains(q, ignoreCase = true)
            }
            .map { it.index + 1 }
    }

    /** Video id inside any URL shape (watch, youtu.be, embed) — string ops. */
    fun videoIdOf(url: String): String {
        val lower = url.lowercase()
        if (lower.contains("youtu.be/")) {
            return url.substringAfter("youtu.be/").substringBefore('?').substringBefore('#')
        }
        val query = url.substringAfter('?', "")
        for (pair in query.split('&')) {
            if (pair.startsWith("v=", ignoreCase = true)) {
                return pair.substringAfter('=').substringBefore('#')
            }
        }
        if (lower.contains("/embed/")) {
            return url.substringAfter("/embed/").substringBefore('?').substringBefore('/')
        }
        return ""
    }

    /** Video ids of COMPLETED tasks — the picker badges/skips these. */
    fun downloadedIds(tasks: List<DownloadTask>): Set<String> =
        tasks.filter { it.status == TaskStatus.COMPLETED }
            .map { videoIdOf(it.sourceUrl) }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Honest size ESTIMATE: flat-playlist metadata carries no filesize, so we
     * multiply durations by a per-quality typical YouTube bitrate (measured
     * averages, VP9 era). Labelled "≈" in the UI — never a promise.
     */
    fun estimateBytes(entries: List<Entry>, indices: List<Int>, quality: String, audioOnly: Boolean): Long {
        val kbps = when {
            audioOnly -> 128L
            quality.startsWith("2160") || quality.startsWith("4k") -> 17_000L
            quality.startsWith("1440") -> 9_000L
            quality.startsWith("1080") -> 4_500L
            quality.startsWith("720") -> 2_500L
            quality.startsWith("480") -> 1_000L
            else -> 2_500L // "auto"/unknown -> 720p assumption
        }
        var sec = 0L
        for (i in indices) {
            val e = entries.getOrNull(i - 1) ?: continue
            sec += e.durationSec
        }
        return sec * kbps * 1000L / 8L
    }

    fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatBytes(b: Long): String = when {
        b >= 1_000_000_000L -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000L -> "%.0f MB".format(b / 1e6)
        else -> "${b / 1000} KB"
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() } ?: ""
}
