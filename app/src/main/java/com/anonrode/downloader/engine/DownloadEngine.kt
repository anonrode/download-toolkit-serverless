package com.anonrode.downloader.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.pipeline.PipelineError
import com.anonrode.downloader.pipeline.StreamValidator
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.resolvers.isDirectMediaUrl
import com.anonrode.downloader.security.TorrentSecurityShield
import com.anonrode.downloader.service.DownloadService
import com.anonrode.downloader.util.NetworkObserver
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Normalize a packed episode number into (season, episode), or null when it
 * is NOT packed. The single source of truth is util.SeasonEpisode: 1000-base
 * values are unambiguous on every site; the historical 100-base decode applies
 * only to sites that actually used it (9jaRocks is flat — 113 is S01E113, not
 * S01E13 — and the codec deliberately excludes it).
 */
internal fun normalizeSeasonEncodedEpisodeNumber(site: String, episodeNum: Int): Pair<Int, Int>? {
    if (episodeNum < 100) return null
    val pair = com.anonrode.downloader.util.SeasonEpisode.of(site, 0, 0, episodeNum)
    // Flat numbering comes back unchanged (S1, same episode) — not packed.
    if (pair.second == episodeNum) return null
    if (pair.first !in 1..30 || pair.second !in 1..999) return null
    // site is part of the contract for logging/telemetry call sites.
    if (site.length > 512) return null
    return pair
}

/**
 * Pure HLS playlist transformations + plan fingerprint (JVM-testable).
 *
 * Live-by-default policy (2026-10-19): a playlist whose URIs are ordinary
 * relative paths is fed to yt-dlp as the LIVE URL. Only a scheme-relative
 * `//host/...` URI (yt-dlp joins it to http:// port 80) or an explicitly
 * approved http->https upgrade forces a local rewritten copy. When a local
 * copy IS needed, every relative URI in it must be absolutized or it would
 * resolve against file://.
 */
internal object HlsPlaylist {

    private val URI_VALUE = Pattern.compile("URI=\"([^\"]+)\"")

    private val IV_ATTRIBUTE = Regex("""(?:^|,)\s*IV=0x[0-9A-Fa-f]+""")

    fun resolveSegmentUrl(base: String, uri: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("//")) return "https:$uri"
        return try {
            val b = java.net.URI(base)
            val qIdx = uri.indexOf('?')
            val pathPart = if (qIdx >= 0) uri.substring(0, qIdx) else uri
            val queryPart = if (qIdx >= 0) uri.substring(qIdx + 1) else null
            val joined = if (pathPart.startsWith("/")) {
                pathPart
            } else {
                b.path.substringBeforeLast('/') + "/" + pathPart
            }
            java.net.URI(b.scheme, null, b.host, b.port, joined, queryPart, null).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** True when the playlist contains a URI that a local rewrite must fix. */
    fun needsLocalRewrite(playlist: String, upgradeInsecureHttp: Boolean): Boolean {
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("//")) return true
            if (upgradeInsecureHttp && line.startsWith("http://")) return true
            if (line.startsWith("#")) {
                val m = URI_VALUE.matcher(line)
                while (m.find()) {
                    val u = m.group(1) ?: ""
                    if (u.startsWith("//") || (upgradeInsecureHttp && u.startsWith("http://"))) return true
                }
            }
        }
        return false
    }

    /** Rewritten playlist text, or null when the LIVE URL is safe to use. */
    fun rewrite(
        playlist: String,
        baseUrl: String,
        upgradeInsecureHttp: Boolean,
        forceLocal: Boolean = false
    ): String? {
        if (!forceLocal && !needsLocalRewrite(playlist, upgradeInsecureHttp)) return null
        val sb = StringBuilder()
        for (rawLine in playlist.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("//") -> sb.append("https:").append(line).append('\n')
                line.startsWith("#") -> {
                    var l = line
                    val m = URI_VALUE.matcher(l)
                    if (m.find()) {
                        val out = StringBuffer()
                        do {
                            val u = m.group(1) ?: ""
                            val repl = when {
                                u.startsWith("//") -> "https:$u"
                                u.startsWith("http://") && upgradeInsecureHttp ->
                                    "https://" + u.removePrefix("http://")
                                u.startsWith("http://") || u.startsWith("https://") -> u
                                else -> maybeUpgrade(resolveSegmentUrl(baseUrl, u) ?: u, upgradeInsecureHttp)
                            }
                            m.appendReplacement(out, "URI=\"" + Matcher.quoteReplacement(repl) + "\"")
                        } while (m.find())
                        m.appendTail(out)
                        l = out.toString()
                    }
                    sb.append(l).append('\n')
                }
                else -> {
                    // A local master is consumed from file://, so every
                    // relative URI must become absolute (http is upgraded
                    // only when the caller proved https works).
                    val resolved = when {
                        line.startsWith("http://") -> maybeUpgrade(line, upgradeInsecureHttp)
                        else -> maybeUpgrade(resolveSegmentUrl(baseUrl, line) ?: line, upgradeInsecureHttp)
                    }
                    sb.append(resolved).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /**
     * Structural plan fingerprint. Token/query rotation must NOT change it;
     * a changed segment sequence, duration, key, map, rendition or variant
     * set MUST. Hashed fields:
     *  - segment/URI basenames (query and tokenized path prefixes dropped)
     *  - #EXTINF durations, #EXT-X-BYTERANGE, discontinuity/media-sequence
     *  - RESOLUTION/BANDWIDTH-bearing tags, key method, map/media type
     * IV values are ignored: they may rotate per session without changing the
     * decrypted byte plan.
     */
    fun fingerprint(playlists: List<String>): String {
        // Live media playlists have a sliding window: MEDIA-SEQUENCE and the
        // segment set legitimately change on every fetch. Mark them
        // indeterminate so the caller never purges banked bytes.
        val hasLiveMedia = playlists.any { p ->
            val lines = p.lineSequence().map { it.trim() }.toList()
            lines.isNotEmpty() &&
                lines.none { it.startsWith("#EXT-X-STREAM-INF") } &&
                lines.none { it.startsWith("#EXT-X-ENDLIST") }
        }
        if (hasLiveMedia) return "live"
        val md = MessageDigest.getInstance("SHA-256")
        for (playlist in playlists) {
            for (raw in playlist.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val normalized: String? = when {
                    line.startsWith("#EXTINF") ->
                        "EXTINF:" + line.substringAfter(':', "").substringBefore(',')
                    line.startsWith("#EXT-X-KEY") ||
                        line.startsWith("#EXT-X-MAP") ||
                        line.startsWith("#EXT-X-MEDIA") ||
                        line.startsWith("#EXT-X-STREAM-INF") -> normalizeTagLine(line)
                    line.startsWith("#EXT-X-BYTERANGE") ||
                        line.startsWith("#EXT-X-DISCONTINUITY") ||
                        line.startsWith("#EXT-X-TARGETDURATION") ||
                        line.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
                        line.startsWith("#EXT-X-PLAYLIST-TYPE") ||
                        line.startsWith("#EXT-X-ENDLIST") -> line
                    line.startsWith("#") -> null
                    else -> "URI:" + uriBasename(line)
                }
                if (normalized != null) {
                    md.update(normalized.toByteArray(Charsets.UTF_8))
                    md.update('\n'.code.toByte())
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun maybeUpgrade(url: String, upgradeInsecureHttp: Boolean): String =
        if (upgradeInsecureHttp && url.startsWith("http://")) {
            "https://" + url.removePrefix("http://")
        } else url

    private fun uriBasename(uri: String): String =
        uri.substringBefore('?').substringBefore('#').substringAfterLast('/')

    private fun normalizeTagLine(line: String): String {
        val cleaned = line.replace(IV_ATTRIBUTE, "")
        val m = URI_VALUE.matcher(cleaned)
        val out = StringBuffer()
        while (m.find()) {
            val u = m.group(1) ?: ""
            m.appendReplacement(out, "URI=\"" + Matcher.quoteReplacement(uriBasename(u)) + "\"")
        }
        m.appendTail(out)
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }
}

enum class DramaCountry {
    KOREAN,
    CHINESE,
    JAPANESE,
    THAI,
    TAIWANESE,
    WESTERN,
    UNKNOWN
}

/**
 * Subtitle artifact extensions and the player-facing alignment (2026-10-21).
 *
 * Two different sets, deliberately:
 *  - [PLAYER_SIDECAR_EXTS] is exactly what the player discovers/renders
 *    (`<stem>.<lang>.srt|vtt|ass|ssa`; MediaPlayerModal's sidecar scan).
 *  - [RENAMED_ARTIFACT_EXTS] also carries the timed-text formats yt-dlp can
 *    leave behind (ttml/json3/srv3). They are subtitle artifacts — never the
 *    media file — but the player cannot render them.
 *
 * The engine therefore NEVER exposes an exotic format as a sidecar in the
 * media folder: [alignForPlayer] converts ttml/json3/srv3 to SRT first, and
 * a body it cannot parse is skipped with an explicit log instead of being
 * renamed into a folder where discovery would silently ignore it. This is
 * the engine half of the alignment; no player-side extension list change is
 * needed because unsupported formats never reach the shared folder.
 */
internal object SubtitleArtifacts {

    val PLAYER_SIDECAR_EXTS = setOf("srt", "vtt", "ass", "ssa")
    val EXOTIC_TIMED_TEXT_EXTS = setOf("ttml", "json3", "srv3")
    val RENAMED_ARTIFACT_EXTS = PLAYER_SIDECAR_EXTS + EXOTIC_TIMED_TEXT_EXTS

    data class AlignedSidecar(val extension: String, val body: String)

    /**
     * Returns the body+extension to write into the media folder, or null when
     * [extension] is an unknown / unparseable format. Native player formats
     * pass through (ssa is normalized to ass); exotic formats are converted
     * to SRT.
     */
    fun alignForPlayer(body: String, extension: String): AlignedSidecar? {
        val ext = extension.trim().lowercase().removePrefix(".")
        if (ext in PLAYER_SIDECAR_EXTS) {
            return AlignedSidecar(if (ext == "ssa") "ass" else ext, body)
        }
        val srt = when (ext) {
            "json3" -> json3ToSrt(body)
            "ttml" -> xmlTimedTextToSrt(body, tag = "p")
            "srv3" -> xmlTimedTextToSrt(body, tag = "text")
            else -> null
        } ?: return null
        return AlignedSidecar("srt", srt)
    }

    /** True when the body looks like one of the exotic timed-text formats
     *  (used to avoid labelling an XML/JSON subtitle as SRT). */
    fun sniffExotic(body: String): String? {
        val head = body.take(8192)
        return when {
            head.contains("\"tStartMs\"") && head.contains("\"segs\"") -> "json3"
            head.contains("<text ", ignoreCase = true) &&
                head.contains("start=", ignoreCase = true) -> "srv3"
            (head.contains("<tt", ignoreCase = true) || head.contains(":tt", ignoreCase = true)) &&
                head.contains("<p", ignoreCase = true) -> "ttml"
            else -> null
        }
    }

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private fun json3ToSrt(body: String): String? {
        if (!body.contains("tStartMs")) return null
        // yt-dlp/YouTube json3 events are flat objects carrying tStartMs,
        // dDurationMs and a segs array. Field order is stable in practice;
        // the regex tolerates other keys between them.
        val event = Regex(
            """\{[^{}]*?"tStartMs"\s*:\s*(-?\d+)[^{}]*?"dDurationMs"\s*:\s*(\d+)[^{}]*?"segs"\s*:\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL
        )
        val jsonString = Regex("\"utf8\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val cues = event.findAll(body).mapNotNull { m ->
            val start = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val dur = m.groupValues[2].toLongOrNull() ?: 0L
            val text = jsonString.findAll(m.groupValues[3])
                .joinToString("") { unescapeJson(it.groupValues[1]) }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isBlank()) null else Cue(start, start + maxOf(dur, 500L), text)
        }.toList()
        return renderSrt(cues)
    }

    private fun xmlTimedTextToSrt(body: String, tag: String): String? {
        val block = Regex("(?s)<" + tag + "\\b([^>]*)>(.*?)</" + tag + ">")
        val cues = block.findAll(body).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val text = cleanXmlText(m.groupValues[2])
            if (text.isBlank()) return@mapNotNull null
            val startRaw = attrValue(attrs, "begin") ?: attrValue(attrs, "start") ?: return@mapNotNull null
            val start = parseTimeMs(startRaw) ?: return@mapNotNull null
            val end = parseTimeMs(attrValue(attrs, "end") ?: "")
                ?: parseTimeMs(attrValue(attrs, "dur") ?: "")?.let { start + it }
                ?: (start + 2_000L)
            Cue(start, maxOf(end, start + 250L), text)
        }.toList()
        return renderSrt(cues)
    }

    private fun attrValue(attrs: String, name: String): String? =
        Regex("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    /** TTML clock ("hh:mm:ss.mmm", "hh:mm:ss:ff") or seconds ("1.5", "1.5s",
     *  "1500ms") to epoch-free milliseconds. */
    private fun parseTimeMs(raw: String): Long? {
        var v = raw.trim()
        if (v.isEmpty()) return null
        if (v.endsWith("ms", ignoreCase = true)) {
            return v.dropLast(2).toDoubleOrNull()?.let { Math.round(it) }
        }
        if (v.endsWith("s", ignoreCase = true)) v = v.dropLast(1)
        if (v.contains(':')) {
            val parts = v.split(':')
            if (parts.size < 3) return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val secPart = parts[2]
            val sec = secPart.toDoubleOrNull() ?: return null
            var ms = ((h * 60 + m) * 60) * 1000L + Math.round(sec * 1000.0)
            if (parts.size >= 4) {
                val frames = parts[3].toLongOrNull() ?: 0L
                ms += Math.round(frames * 1000.0 / 30.0)
            }
            return ms
        }
        return v.toDoubleOrNull()?.let { Math.round(it * 1000.0) }
    }

    private fun cleanXmlText(raw: String): String {
        val withBreaks = raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
        val noTags = withBreaks.replace(Regex("<[^>]+>"), "")
        return decodeEntities(noTags).trim()
    }

    private fun decodeEntities(raw: String): String {
        var out = raw
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        out = Regex("&#x([0-9A-Fa-f]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return out
    }

    private fun unescapeJson(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c)
                i++
                continue
            }
            when (val n = raw[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'u' -> {
                    if (i + 5 < raw.length) {
                        val hex = raw.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    sb.append(n)
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    private fun renderSrt(cues: List<Cue>): String? {
        val usable = cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
        if (usable.isEmpty()) return null
        val sb = StringBuilder()
        usable.forEachIndexed { index, cue ->
            sb.append(index + 1).append('\n')
            sb.append(srtTime(cue.startMs)).append(" --> ").append(srtTime(cue.endMs)).append('\n')
            sb.append(cue.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun srtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val h = safe / 3_600_000L
        val m = (safe % 3_600_000L) / 60_000L
        val s = (safe % 60_000L) / 1000L
        val milli = safe % 1000L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }
}

class DownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository,
    private val networkObserver: NetworkObserver = NetworkObserver(context)
) {
    private val ASIAN_DRAMA_CLUSTER: Set<String>
        get() = com.anonrode.downloader.data.rules.DynamicRulesManager.getFailoverClusters().asianCluster
    private val WESTERN_CLUSTER: Set<String>
        get() = com.anonrode.downloader.data.rules.DynamicRulesManager.getFailoverClusters().westernCluster
    private val DEDICATED_ASIAN_SITES: Set<String>
        get() = com.anonrode.downloader.data.rules.DynamicRulesManager.getFailoverClusters().dedicatedAsian
    // Secondary Asian-failover targets: mixed Western-cluster sites with PROVEN
    // Asian catalogs (coverage probe 2026-09-20: 50 random Asian series,
    // search-only — 9jarocks 98%, nkiri 62%, and a 6/6 rescue rate on titles
    // ALL dedicated sites missed). Reached ONLY by Asian tasks, and tried at
    // EQUAL RANK with Asian-cluster cards: exact title match + HostHealth
    // decide (deliberate choice — no secondary-tier penalty). Western tasks
    // never touch the dedicated sites; that one-way door stays shut.
    private val SECONDARY_ASIAN_FALLBACK: Set<String>
        get() = com.anonrode.downloader.data.rules.DynamicRulesManager.getFailoverClusters().secondaryAsianFallback
    private val YEAR_REGEX = Regex("""\b(19\d\d|20[0-3]\d)\b""")

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runtimeRegistry = TaskRuntimeRegistry()

    /**
     * Per-task backend failure tracker (progress-banking guard correction,
     * 2026-10-20). A task with banked bytes used to veto failover forever,
     * which parked f8cf38d2 at 1,669 KiB against a URL class that could never
     * progress (the nsrplay self-loop). Failover is now allowed when the
     * SAME terminal URL has burned a full backend ladder (>=3 failures) and
     * the task's banked bytes belong to that URL — the corruption protection
     * stays for a partial that is making progress; the dead-end escape opens
     * only for a proven-unprogressable source.
     */
    private data class BackendFailures(val url: String, val count: Int, val lastAtMs: Long)
    private val backendFailures = java.util.concurrent.ConcurrentHashMap<String, BackendFailures>()

    private fun noteBackendFailure(task: DownloadTask, failedUrl: String) {
        if (failedUrl.isBlank()) return
        val now = System.currentTimeMillis()
        backendFailures.compute(task.id) { _, old ->
            if (old != null && old.url == failedUrl) old.copy(count = old.count + 1, lastAtMs = now)
            else BackendFailures(failedUrl, 1, now)
        }
    }

    private fun clearBackendFailures(taskId: String) {
        backendFailures.remove(taskId)
    }

    /** True when the task's banked bytes belong to a URL that exhausted at
     *  least one full backend ladder (or is already poisoned as a decoy). */
    private fun bankedSourceUnprogressable(task: DownloadTask): Boolean {
        val current = task.directUrl.trim()
        if (current.isNotBlank() && com.anonrode.downloader.pipeline.ResolveCache.isPoisoned(current)) return true
        val failures = backendFailures[task.id] ?: return false
        if (failures.count < 3) return false
        return failures.url == current || current.isBlank()
    }

    /**
     * Same-show duplicate-terminal guard (Servamp S01E01 == S01E02,
     * 2026-10-20). Two DIFFERENT episodes of one show must never download the
     * SAME direct terminal. The comparison is host+path (query/signature
     * dropped: a CDN signs per request), and only for provable media/HLS
     * URLs. Returns the conflicting task, or null.
     */
    internal fun findDuplicateTerminal(task: DownloadTask, resolvedUrl: String): DownloadTask? {
        if (resolvedUrl.isBlank()) return null
        val classifier = com.anonrode.downloader.pipeline.UrlClassifier
        val looksTerminal = isDirectMediaUrl(resolvedUrl) || isProvablyDirectFile(resolvedUrl) ||
            classifier.isManifestShaped(resolvedUrl)
        if (!looksTerminal) return null
        val target = classifier.canonicalMediaPath(resolvedUrl) ?: return null
        val mine = com.anonrode.downloader.util.SeasonEpisode.of(task)
        return repository.snapshot().firstOrNull { other ->
            if (other.id == task.id) return@firstOrNull false
            if (other.status == TaskStatus.FAILED) return@firstOrNull false
            if (!other.showTitle.equals(task.showTitle, ignoreCase = true)) return@firstOrNull false
            if (other.directUrl.isBlank()) return@firstOrNull false
            val otherPath = classifier.canonicalMediaPath(other.directUrl) ?: return@firstOrNull false
            if (otherPath != target) return@firstOrNull false
            val theirs = com.anonrode.downloader.util.SeasonEpisode.of(other)
            theirs != mine
        }
    }

    private val progressWatchdog by lazy {
        ProgressWatchdog(
            repository = repository,
            computeVerifiedDiskBytes = { t -> computeDiskBytes(t) },
            onStallKill = { taskId, kills, exhausted, stallMsg, t ->
                handleWatchdogStall(taskId, kills, exhausted, stallMsg, t)
            }
        )
    }

    @Volatile var maxConcurrentDownloads: Int = 4
    @Volatile var parallelSocketsPerFile: Int = 16
    @Volatile var defaultQuality: String = "720p"
    @Volatile var autoOrganizeByShow: Boolean = true
    @Volatile var instantSocialDownload: Boolean = false
    @Volatile var wifiOnlyTorrents: Boolean = false
    var downloadTorrentsWifiOnly: Boolean
        get() = wifiOnlyTorrents
        set(value) { wifiOnlyTorrents = value }
    @Volatile var showPostersInResults: Boolean = true
    @Volatile var storageGuardGb: Double = 1.0

    // Tier-A settings (AppSettings-backed): defaults equal the previous
    // hardcoded behavior, so nothing changes until the user opts in.
    @Volatile var stallTimeoutSec: Int = 60
    @Volatile var magnetMaxAttempts: Int = 3
    @Volatile var ytdlpMaxAttempts: Int = 3
    @Volatile var hlsFragmentConcurrency: Int = 16
    @Volatile var globalSpeedLimitKbs: Int = 0          // 0 = unlimited
    @Volatile var torrentPeers: Int = -1                // -1 = auto (RAM tier)
    @Volatile var torrentPrivacyMode: Boolean = false   // qBittorrent anonymous-mode lessons
    @Volatile var wifiOnlyAll: Boolean = false
    @Volatile var clipboardDetect: Boolean = true
    @Volatile var completionNotifications: Boolean = true
    @Volatile var debugLogging: Boolean = false
    @Volatile var logRetentionDays: Int = 7
    // Seal-parity subtitle settings (2026-09-14): yt-dlp extractor downloads
    // fetch subtitles in this language (default English) and embed them in
    // the merged file. Both live in downloader_settings like the rest.
    @Volatile var downloadSubtitles: Boolean = true
    @Volatile var subtitleLanguage: String = "en"
    @Volatile var filterExplicitContent: Boolean = true


    // No byte movement (parsed progress or filesystem bytes) for this long while
    // DOWNLOADING means the backend is hung; the watchdog kills it so the retry
    // wrapper can relaunch, and the task eventually FAILED instead of hanging.
    private val STALL_TIMEOUT_MS: Long
        get() = stallTimeoutSec * 1000L

    /**
     * Set by the UI: called when a torrent's swarm exposes more than one safe
     * file (season packs). Receives the shield-checked file list and must
     * return the selected 1-based aria2c indexes, or null to download the
     * whole torrent. Runs on the engine's IO dispatcher — the UI should
     * bridge it to the main thread (e.g. a Compose dialog).
     */
    var onTorrentFileSelection: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult)? = null

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    init {
        loadPreferences()
        // Global concurrency controller: every ~5s water-fill the 24-lane
        // budget from measured per-task throughput. Turbo applies a new
        // grant at its next piece claim (boundary, never mid-piece); HLS
        // grants are fixed for the run.
        engineScope.launch {
            while (isActive) {
                delay(5_000L)
                com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget.rebalance()
                    .forEach { com.anonrode.downloader.util.DebugLog.engine(it) }
            }
        }
        engineScope.launch {
            // OWNER DIRECTIVE 2026-10-21 ("every error ends in a download"):
            // tasks interrupted by app kill/crash requeue automatically. The
            // repository restore (initPersistence) already mapped mid-flight
            // statuses to a system network pause before the engine was built,
            // so this is defense in depth for any future init reordering —
            // but now it parks as a SYSTEM pause (userPaused=false, network
            // marker) instead of a stranded manual pause, and every restored
            // task gets a one-shot RESTORED_REQUEUE_FLOOR_MS backoff below so
            // boot never silently drains data on open (v3.0.4) while still
            // guaranteeing the retry (the observer's requeue sweep honors the
            // floor). A caught-mid-VALIDATING task re-validates in seconds.
            val restoredFloor = System.currentTimeMillis() + RESTORED_REQUEUE_FLOOR_MS
            val currentTasks = repository.tasks.value
            currentTasks.forEach { t ->
                // QUEUED included: the repository restore parks it too, but a
                // task persisted as QUEUED between enqueue and restore (async
                // persist race) must not reach the network observer below,
                // whose first emission auto-starts every QUEUED task the
                // moment the app opens.
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING ||
                    t.status == TaskStatus.VALIDATING || t.status == TaskStatus.QUEUED) {
                    repository.update(t.id) { cur ->
                        if (cur.status != TaskStatus.DOWNLOADING && cur.status != TaskStatus.RESOLVING &&
                            cur.status != TaskStatus.VALIDATING && cur.status != TaskStatus.QUEUED
                        ) cur
                        else cur.copy(
                            status = TaskStatus.PAUSED,
                            speedBytesPerSec = 0.0,
                            errorMessage = NETWORK_PAUSE_MESSAGE,
                            userPaused = false
                        )
                    }
                }
                runtimeRegistry.getOrCreate(t.id).requeueNotBeforeMs.compareAndSet(0L, restoredFloor)
            }
            networkObserver.status.collect { net ->
                if (net.isConnected) {
                    if (lastNetworkTag != null && net.networkTag != null && lastNetworkTag != net.networkTag) {
                        // Network identity changed while still connected (Wi-Fi -> mobile, VPN toggle):
                        // restart active jobs losslessly on the new network instead of letting
                        // their sockets stall against the dead one.
                        runtimeRegistry.activeRunningTaskIds().forEach { id -> pauseForNetwork(id) }
                    }
                    lastNetworkTag = net.networkTag
                    requeueNetworkParkedTasks(isWifi = net.isWifi)
                    processQueue()
                } else {
                    // Park active jobs so a reconnect resumes them instead of failing them
                    lastNetworkTag = null
                    runtimeRegistry.activeRunningTaskIds().forEach { id -> pauseForNetwork(id) }
                }
            }
        }
        // Boot-retry pass (owner directive 2026-10-21): the observer above
        // emits on network CHANGES, so a floor-stamped restored task could
        // otherwise sit the entire session behind its 60s boot floor on a
        // stable network. One delayed pass replays the requeue sweep exactly
        // like a reconnect; only the restored tasks' one-shot boot floor (and
        // any live escalation floor) still gates the outcome.
        engineScope.launch {
            delay(RESTORED_REQUEUE_FLOOR_MS + 250L)
            val net = networkObserver.status.value
            if (net.isConnected) {
                requeueNetworkParkedTasks(isWifi = net.isWifi)
                processQueue()
            }
        }
        // Storage-guard self-heal: tasks parked by the storage limit have no
        // natural resume trigger (the network collector only matches network
        // messages), so a separate loop re-checks periodically and re-queues
        // them when space frees up instead of leaving the queue stuck until
        // manual resume. Runs in its own coroutine — the collector above never
        // returns, so this must not be sequenced after it.
        engineScope.launch {
            while (true) {
                delay(10_000)
                if (!checkStorageAvailable()) continue
                val parked = repository.tasks.value.filter {
                    it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith("Storage limit reached") == true
                }
                if (parked.isNotEmpty()) {
                    parked.forEach { repository.update(it.id) { t ->
                        if (t.userPaused || t.status != TaskStatus.PAUSED) t
                        else t.copy(status = TaskStatus.QUEUED, errorMessage = null)
                    } }
                    processQueue()
                }
            }
        }
        // Cooldown-parked auto-retry: a task parked because its locker host
        // is in HostHealth backoff re-queues itself once the host recovers —
        // nothing else resumes it (the network collector only matches network
        // messages). Restore-safe: parkForRestore keeps the marker and
        // userPaused=false for system parks, so a restart re-enters this loop
        // cleanly (pre-fix, restore crushed these to stranded manual pauses).
        //
        // OWNER DIRECTIVE 2026-10-21 ("every error ends in a download or a
        // proven no-source"): the old PARKED_LIFETIME_MS give-up that wrote
        // FAILED for 0-byte tasks is REMOVED — a parked download re-checks on
        // PARKED_RECHECK_FLOOR_MS cadence until the host returns. FAILED is
        // reserved for the proven no-source verdict (failover exhausted every
        // provider; NoSourceLedger), and manual retry always restarts cleanly.
        // Banked-byte tasks were already exempt from the cap and stay exempt.
        //
        // Trickle requeue: when MANY tasks are parked on the same host, requeue
        // at most a few per tick. Releasing all 18 at once recreates the exact
        // request storm that triggered the backoff in the first place.
        engineScope.launch {
            while (true) {
                delay(10_000)
                val parked = repository.tasks.value.filter {
                    it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith(PARKED_HOST_MESSAGE) == true
                }
                if (parked.isEmpty()) continue
                var requeued = 0
                var anyRequeued = false
                parked.forEach { t ->
                    val entry = runtimeRegistry.get(t.id)
                    val parkedAt = entry?.firstParkedAtMs?.get() ?: 0L
                    val now = System.currentTimeMillis()
                    val floor = entry?.requeueNotBeforeMs?.get() ?: 0L
                    val banked = maxOf(t.downloadedBytes, computeDiskBytes(t))
                    val longDead = parkedAt > 0L && now - parkedAt > PARKED_LIFETIME_MS && banked == 0L
                    val hostUsable = com.anonrode.downloader.pipeline.HostHealth.isUsable(
                        t.directUrl.ifBlank { t.selectedMirrorUrl }.ifBlank { t.sourceUrl }
                    )
                    // OWNER DIRECTIVE 2026-10-21 ("every error ends in a
                    // download or a proven no-source"): a park NEVER fails the
                    // task and never strands it. The old 30-minute give-up
                    // wrote FAILED for 0-byte tasks — under the invariant that
                    // end state is illegal unless the title is PROVEN
                    // no-source, which only the failover-exhausted
                    // NoSourceLedger path may claim. A long-dead host instead
                    // re-checks on a long floor while HostHealth stays steep;
                    // the moment the host is usable again the requeue below
                    // fires regardless of that floor.
                    if (longDead && !hostUsable) {
                        if (now >= floor) {
                            // One honest card update per re-check window — not
                            // every 10s tick (persist() writes each update).
                            repository.update(t.id) { cur ->
                                if (cur.userPaused || cur.status != TaskStatus.PAUSED) cur
                                else cur.copy(
                                    errorMessage = "$PARKED_HOST_MESSAGE — server unreachable 30+ min, still retrying every ~30 min. Tap retry to try now."
                                )
                            }
                            entry?.requeueNotBeforeMs?.set(now + PARKED_RECHECK_FLOOR_MS)
                            com.anonrode.downloader.util.DebugLog.user(
                                "park long-dead re-check task=${t.id} (retries every ~30m until the host returns)"
                            )
                        }
                        return@forEach
                    }
                    // Re-queue floor: parks escalate their wait (see parkFloorMs)
                    // so a repeatedly-rejected host is probed at most once per
                    // window instead of every tick. A long-dead host that just
                    // became usable bypasses the floor: recovery must win.
                    if (!longDead && now < floor) return@forEach
                    if (hostUsable) {
                        // Max 2 requeues per 10s tick: a 18-task batch comes
                        // back over ~90s instead of one thundering herd.
                        if (requeued >= 2) return@forEach
                        requeued++
                        entry?.firstParkedAtMs?.set(0L)
                        entry?.requeueNotBeforeMs?.set(0L)
                        repository.update(t.id) { task ->
                            if (task.userPaused || task.status != TaskStatus.PAUSED) task
                            else task.copy(status = TaskStatus.QUEUED, errorMessage = null)
                        }
                        anyRequeued = true
                    }
                }
                if (anyRequeued) processQueue()
            }
        }
    }

    /**
     * The two network-family requeue sweeps, shared by the network observer's
     * connect branch and the boot-retry pass. userPaused tasks are excluded
     * everywhere: a pause the user asked for must never be undone by a
     * network event. Each task's requeue-not-before floor gates the requeue:
     * a one-shot boot backoff for restored tasks (RESTORED_REQUEUE_FLOOR_MS),
     * escalation floors set by the park paths otherwise (0 in the common
     * case — in-session network parks requeue on the first heartbeat).
     * Atomic re-check inside the transform (engine-audit P1): the filter
     * froze userPaused at snapshot time, so a pause landing between snapshot
     * and write must not be overwritten — `cur` is authoritative.
     */
    private fun requeueNetworkParkedTasks(isWifi: Boolean) {
        repository.tasks.value
            .filter { it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage == NETWORK_PAUSE_MESSAGE }
            .forEach { t ->
                if (System.currentTimeMillis() < (runtimeRegistry.get(t.id)?.requeueNotBeforeMs?.get() ?: 0L)) return@forEach
                repository.update(t.id) { cur ->
                    if (cur.userPaused || cur.status != TaskStatus.PAUSED) cur
                    else cur.copy(status = TaskStatus.QUEUED, errorMessage = null)
                }
            }
        // Wi-Fi-gated torrents resume only once an actual Wi-Fi network is back
        repository.tasks.value
            .filter { it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith("Waiting for Wi-Fi") == true && isWifi }
            .forEach { t ->
                if (System.currentTimeMillis() < (runtimeRegistry.get(t.id)?.requeueNotBeforeMs?.get() ?: 0L)) return@forEach
                repository.update(t.id) { cur ->
                    if (cur.userPaused || cur.status != TaskStatus.PAUSED) cur
                    else cur.copy(status = TaskStatus.QUEUED, errorMessage = null)
                }
            }
    }

    private fun loadPreferences() {
        val prefs = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        maxConcurrentDownloads = prefs.getInt("pref_max_downloads", 4).coerceIn(1, 6)
        parallelSocketsPerFile = prefs.getInt("pref_parallel_sockets", 16).coerceIn(1, 16)
        defaultQuality = prefs.getString("pref_default_quality", "720p") ?: "720p"
        autoOrganizeByShow = prefs.getBoolean("pref_auto_organize", true)
        instantSocialDownload = prefs.getBoolean("pref_instant_social", false)
        wifiOnlyTorrents = prefs.getBoolean("pref_torrents_wifi_only", false)
        showPostersInResults = prefs.getBoolean("pref_show_posters", true)
        storageGuardGb = prefs.getFloat("pref_storage_guard", 1.0f).toDouble()

        stallTimeoutSec = prefs.getInt("pref_stall_timeout", 60)
        magnetMaxAttempts = prefs.getInt("pref_magnet_retries", 3)
        ytdlpMaxAttempts = prefs.getInt("pref_ytdlp_retries", 3)
        hlsFragmentConcurrency = prefs.getInt("pref_hls_fragments", 16)
        globalSpeedLimitKbs = prefs.getInt("pref_speed_limit_kbs", 0)
        torrentPeers = prefs.getInt("pref_torrent_peers", -1)
        torrentPrivacyMode = prefs.getBoolean("pref_torrent_privacy_mode", false)
        wifiOnlyAll = prefs.getBoolean("pref_wifi_only_all", false)
        clipboardDetect = prefs.getBoolean("pref_clipboard_detect", true)
        completionNotifications = prefs.getBoolean("pref_completion_notifications", true)
        debugLogging = prefs.getBoolean("pref_debug_logging", false)
        logRetentionDays = prefs.getInt("pref_log_retention_days", 7)
        downloadSubtitles = prefs.getBoolean("pref_download_subtitles", true)
        subtitleLanguage = prefs.getString("pref_subtitle_lang", "en") ?: "en"
        filterExplicitContent = prefs.getBoolean("pref_filter_explicit_content", true)
        // Retention applies at startup, not just when the setting changes.
        com.anonrode.downloader.util.DebugLog.configureRetention(logRetentionDays)
        // debugLogging drives DebugLog's TRACE gate — without this call
        // setVerbose had no production caller and trace() (4 call sites,
        // incl. ShadowRunner lifecycle) could never emit. Default OFF; only
        // the user's pref turns it on.
        com.anonrode.downloader.util.DebugLog.setVerbose(debugLogging)
    }

    fun setShowPosters(show: Boolean) {
        this.showPostersInResults = show
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_show_posters", show)
            .apply()
    }

    /** Persists the instant-social toggle so it survives restarts (SocialModal). */
    fun setInstantSocial(enabled: Boolean) {
        this.instantSocialDownload = enabled
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_instant_social", enabled)
            .apply()
    }

    fun saveAllSettings(
        maxConcurrent: Int,
        parallelSockets: Int,
        quality: String,
        autoOrganize: Boolean,
        storageGuard: Double,
        wifiOnlyTorrents: Boolean,
        instantSocial: Boolean = false,
        showPosters: Boolean = true,
        // Tier-A settings
        stallTimeout: Int = 60,
        magnetRetries: Int = 3,
        ytdlpRetries: Int = 3,
        hlsFragments: Int = 16,
        speedLimit: Int = 0,
        peers: Int = -1,
        privacyMode: Boolean = false,
        wifiAll: Boolean = false,
        clipboard: Boolean = true,
        notifications: Boolean = true,
        debugLog: Boolean = false,
        logRetention: Int = 7,
        downloadSubs: Boolean = true,
        subLang: String = "en",
        filterExplicit: Boolean = true
    ) {
        this.maxConcurrentDownloads = maxConcurrent.coerceIn(1, 6)
        this.parallelSocketsPerFile = parallelSockets.coerceIn(1, 16)
        this.defaultQuality = quality
        this.autoOrganizeByShow = autoOrganize
        this.storageGuardGb = storageGuard
        this.wifiOnlyTorrents = wifiOnlyTorrents
        this.instantSocialDownload = instantSocial
        this.showPostersInResults = showPosters
        this.stallTimeoutSec = stallTimeout
        this.magnetMaxAttempts = magnetRetries
        this.ytdlpMaxAttempts = ytdlpRetries
        this.hlsFragmentConcurrency = hlsFragments
        this.globalSpeedLimitKbs = speedLimit
        this.torrentPeers = peers
        this.torrentPrivacyMode = privacyMode
        this.wifiOnlyAll = wifiAll
        this.clipboardDetect = clipboard
        this.completionNotifications = notifications
        this.debugLogging = debugLog
        this.logRetentionDays = logRetention.coerceIn(1, 90)
        this.downloadSubtitles = downloadSubs
        this.subtitleLanguage = subLang.trim().ifBlank { "en" }
        this.filterExplicitContent = filterExplicit
        com.anonrode.downloader.util.DebugLog.configureRetention(this.logRetentionDays)
        // Verbose gate applies live on save, exactly like retention above.
        com.anonrode.downloader.util.DebugLog.setVerbose(this.debugLogging)

        // Turning a Wi-Fi gate OFF must release the tasks it parked: the network
        // observer only unparks on an actual Wi-Fi reconnect, so without this the
        // "Waiting for Wi-Fi" tasks would wait for a network change that never
        // comes. Deliberately NOT gated on net.isConnected — a toggle flipped while
        // offline must still queue the tasks; processQueue no-ops until the
        // reconnect collector re-runs it.
        repository.tasks.value.filter {
            it.status == TaskStatus.PAUSED && !it.userPaused && (
                (!this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Wi-Fi Only enabled)") ||
                    (!this.wifiOnlyTorrents && !this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)")
                )
        }.forEach { t ->
            repository.update(t.id) { cur ->
                if (cur.userPaused || cur.status != TaskStatus.PAUSED) cur
                else cur.copy(status = TaskStatus.QUEUED, errorMessage = null)
            }
        }

        // A lower limit preempts running tasks NOW (demote back to queue);
        // a higher limit just fills the new slots. This is the only place the
        // user-facing limit change lands, so preemption lives here.
        enforceConcurrencyLimit()

        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putInt("pref_max_downloads", maxConcurrent)
            .putInt("pref_parallel_sockets", parallelSockets)
            .putString("pref_default_quality", quality)
            .putBoolean("pref_auto_organize", autoOrganize)
            .putFloat("pref_storage_guard", storageGuard.toFloat())
            .putBoolean("pref_torrents_wifi_only", wifiOnlyTorrents)
            .putBoolean("pref_instant_social", instantSocial)
            .putBoolean("pref_show_posters", showPosters)
            .putInt("pref_stall_timeout", stallTimeout)
            .putInt("pref_magnet_retries", magnetRetries)
            .putInt("pref_ytdlp_retries", ytdlpRetries)
            .putInt("pref_hls_fragments", hlsFragments)
            .putInt("pref_speed_limit_kbs", speedLimit)
            .putInt("pref_torrent_peers", peers)
            .putBoolean("pref_torrent_privacy_mode", privacyMode)
            .putBoolean("pref_wifi_only_all", wifiAll)
            .putBoolean("pref_clipboard_detect", clipboard)
            .putBoolean("pref_completion_notifications", notifications)
            .putBoolean("pref_debug_logging", debugLog)
            .putInt("pref_log_retention_days", this.logRetentionDays)
            .putBoolean("pref_download_subtitles", this.downloadSubtitles)
            .putString("pref_subtitle_lang", this.subtitleLanguage)
            .putBoolean("pref_filter_explicit_content", this.filterExplicitContent)
            .apply()
    }

    // Dedupe + filename uniquification + add must be ONE atomic unit: two
    // near-simultaneous enqueues of the same URL (double-tap, share intent +
    // clipboard detect) could otherwise both pass the check-then-act gap and
    // queue two tasks writing one .part file.
    private val enqueueLock = Any()

    fun enqueue(
        showTitle: String,
        episodeNum: Int,
        episodeTitle: String,
        sourceUrl: String,
        isDirect: Boolean,
        backend: String = "aria2c",
        parallelSockets: Int = 16,
        audioOnly: Boolean = false,
        site: String = "",
        quality: String? = null,
        /** Search-verify oracle handoff (2026-09-14): a direct URL the
         *  ResultVerifier byte-proved <= PRERESOLVED_MAX_AGE_MS ago for THIS
         *  episode URL. Riding the existing direct-file pipeline (status
         *  DOWNLOADING immediately, no RESOLVING round-trip) is exactly how
         *  pasted/share links already work — no new engine branch. sourceUrl
         *  stays the episode page, so the 401/403/404/HTML self-heal
         *  re-resolves from scratch if this token aged out between proof and
         *  tap. Null = the normal path, unchanged. */
        verifiedDirectUrl: String? = null,
        mirrorUrls: List<String> = emptyList(),
        /** Track 4 ("Download Remaining"): the series page the episode was
         *  scraped from and the drawer's total episode count. Stamped on the
         *  task so the Downloads screen can offer a one-tap "grab the rest"
         *  without re-scraping. Defaults keep every other caller (direct
         *  pastes, playlists, subtitles) unchanged. */
        seriesUrl: String = "",
        seriesTotal: Int = 0,
        /** Downloads-card thumbnail art (v6 mock frame 1); "" keeps the
         *  initials tile. Callers without artwork just keep the default. */
        posterUrl: String = "",
        /** Authoritative season/episode identity from the drawer's
         *  EpisodeItem (util.SeasonEpisode). 0 = legacy caller: the engine
         *  derives the pair from episodeNum via SeasonEpisode.of(). */
        season: Int = 0,
        episode: Int = 0,
        triggerQueueProcessing: Boolean = true
    ): String {
        val downloadFolder = getDownloadDirectory(showTitle, createDirs = false)
        var retryTaskId: String? = null

        val taskId = synchronized(enqueueLock) {
            val currentTasks = repository.snapshot()
            val activeStatuses = setOf(
                TaskStatus.QUEUED,
                TaskStatus.RESOLVING,
                TaskStatus.DOWNLOADING,
                TaskStatus.VALIDATING
            )
            val existing = currentTasks.firstOrNull { it.sourceUrl == sourceUrl && it.status in activeStatuses }
            if (existing != null) {
                return@synchronized existing.id
            }

            // A PAUSED record is resumable, not a duplicate: re-enqueueing an
            // episode whose card shows Paused must resume it (the old dedupe
            // returned the existing id and did nothing, so the tap looked dead).
            val resumableExisting = currentTasks.firstOrNull {
                it.sourceUrl == sourceUrl && (it.status == TaskStatus.FAILED || it.status == TaskStatus.PAUSED)
            }
            if (resumableExisting != null) {
                retryTaskId = resumableExisting.id
                return@synchronized resumableExisting.id
            }

            val cleanTitle = sanitizeComponent(episodeTitle, 80, stripNoise = !isDirect)
            val candidateUrlForExt = verifiedDirectUrl ?: sourceUrl
            val detectedExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(candidateUrlForExt)
            val ext = when {
                audioOnly -> "mp3"
                detectedExt != null -> detectedExt
                backend.contains("yt") || !isDirect -> "mp4"
                else -> "mp4" // Default to .mp4 instead of .mkv when extension is unknown
            }

            // O(1) HashSet lookup + physical disk existence check so we never overwrite existing files
            val reservedPaths = currentTasks.mapTo(HashSet(currentTasks.size * 2)) { it.filePath }
            var targetFile = File(downloadFolder, "$cleanTitle.$ext")
            var counter = 2
            while (
                targetFile.absolutePath in reservedPaths ||
                targetFile.exists() ||
                File(targetFile.absolutePath + ".part").exists() ||
                File(targetFile.absolutePath + ".aria2").exists() ||
                File(targetFile.absolutePath + ".turbo").exists() ||
                File(targetFile.absolutePath + ".hts").exists() ||
                File(targetFile.absolutePath + ".hlsrs").exists()
            ) {
                targetFile = File(downloadFolder, "$cleanTitle-$counter.$ext")
                counter++
            }

            val resolvedDirectUrl = if (
                !isDirect &&
                verifiedDirectUrl != null &&
                isDirectMediaUrl(verifiedDirectUrl) &&
                !isKnownLockerHost(verifiedDirectUrl)
            ) verifiedDirectUrl else sourceUrl

            val task = DownloadTask(
                id = UUID.randomUUID().toString(),
                showTitle = showTitle,
                episodeNum = episodeNum,
                episodeTitle = episodeTitle,
                directUrl = resolvedDirectUrl,
                sourceUrl = sourceUrl,
                filePath = targetFile.absolutePath,
                status = TaskStatus.QUEUED,
                downloadedBytes = 0L,
                totalBytes = 0L,
                speedBytesPerSec = 0.0,
                etaSeconds = 0L,
                backend = backend,
                parallelSockets = parallelSockets,
                site = site,
                audioOnly = audioOnly,
                quality = quality,
                createdAt = System.currentTimeMillis(),
                mirrorUrls = MirrorPool.sources(sourceUrl, mirrorUrls).filter { it != sourceUrl },
                seriesUrl = seriesUrl,
                seriesTotal = seriesTotal,
                posterUrl = posterUrl,
                season = season.coerceAtLeast(0),
                episode = episode.coerceAtLeast(0)
            )

            repository.addFirst(task)
            task.id
        }

        if (retryTaskId != null) {
            retry(retryTaskId!!, processNow = triggerQueueProcessing)
        } else if (triggerQueueProcessing) {
            processQueue()
        }
        return taskId
    }

    /**
     * YouTube playlist batch (2026-09-15): enqueue the selected entries as ONE
     * group — showTitle = playlist title, episodeNum = position in the list —
     * so the Downloads group-by-show ordering and the player's episode-ordered
     * Next queue (DownloadsSorter.EPISODE_ORDER / playerQueueFor) walk the
     * playlist like a series. That grouping is the structural edge over Seal,
     * whose playlist tasks are loose singles. Each entry is a normal yt-dlp
     * extractor task: subtitles, quality, throttle-survival (round 4) and the
     * active-URL dedupe in enqueue() all apply per item, and a failed entry
     * never touches the others. Returns how many were queued.
     */
    fun enqueuePlaylist(
        meta: com.anonrode.downloader.pipeline.PlaylistPicker.PlaylistMeta,
        indices: List<Int>,
        audioOnly: Boolean,
        quality: String?
    ): Int {
        var queued = 0
        for (idx in indices.asReversed()) {
            val entry = meta.entries.getOrNull(idx - 1) ?: continue
            enqueue(
                showTitle = meta.title.ifBlank { "YouTube Playlist" },
                episodeNum = idx,
                episodeTitle = entry.title,
                sourceUrl = entry.watchUrl,
                isDirect = false,
                backend = "yt-dlp",
                parallelSockets = parallelSocketsPerFile,
                audioOnly = audioOnly,
                site = "youtube",
                quality = quality,
                triggerQueueProcessing = false
            )
            queued++
        }
        if (queued > 0) {
            processQueue()
        }
        return queued
    }


    fun pause(taskId: String) {
        val entry = runtimeRegistry.get(taskId)
        entry?.activeJob?.cancel()
        entry?.activeJob = null
        YoutubeDlDownloader.killProcess(taskId)
        // Turbo runs on blocking OkHttp calls that coroutine cancellation does
        // not interrupt, and resolver HTTP calls are plain blocking executes:
        // without these, a paused task kept draining mobile data until the app
        // was killed (user-reported).
        TurboDownloader.cancelTask(taskId)
        // A task paused mid-RESOLVING/mid-probe still has blocking HTTP
        // registered in HttpClient.inFlightCalls that coroutine cancellation
        // cannot interrupt. Sweep exactly like cancel() when it is safe —
        // with other tasks running their requests must stay untouched (the
        // cross-talk rule); the stragglers then die on their read timeout.
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        com.anonrode.downloader.util.DebugLog.user("pause $taskId")
        // userPaused=true is the authoritative "the user asked for this pause"
        // mark: every auto-resume path (network reconnect, storage self-heal,
        // host cooldown) refuses to re-queue a task carrying it, so the pause
        // survives flaky networks AND full app restarts until an explicit
        // resume. errorMessage is cleared as a second layer — a stale
        // NETWORK_PAUSE_MESSAGE from an earlier blip must not outlive the
        // user's pause and let the reconnect handler re-queue the task (the
        // marker overwrite race that resurrected a paused download ~3s after
        // pause, live-verified in app-2026-08-27.txt).
        repository.update(taskId) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true) }
        updateServiceState(force = true)
        processQueue()
    }

    /**
     * Concurrency preemption: applied when maxConcurrentDownloads CHANGES.
     * Lowering the limit takes effect IMMEDIATELY — running tasks beyond the
     * new limit are demoted back to QUEUED (killing their backends exactly
     * like pause does) and auto-resume when slots free up. The old behavior
     * (limit only gates NEW starts) is what let the user watch 2-3 downloads
     * run after setting the limit to 1 — the log-14 concurrency complaint.
     * Raising the limit just fills the new slots via processQueue.
     *
     * Demotion writes QUEUED, never PAUSED, so the task flows back through
     * the queue in order; the job's CancellationException rescue only rewrites
     * PAUSED over DOWNLOADING/RESOLVING/VALIDATING statuses, and repository
     * updates are atomic per write, so a rescue racing this demotion can never
     * strand the task in a stuck state: either the rescue lands first (PAUSED,
     * overwritten by this QUEUED) or this lands first (QUEUED, rescue no-ops).
     */
    private fun hasOtherActiveWork(ignoredTaskId: String): Boolean {
        return runtimeRegistry.hasOtherActiveWork(ignoredTaskId)
    }

    private fun enforceConcurrencyLimit() {
        val actives = repository.tasks.value.filter {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
        }
        if (actives.size <= maxConcurrentDownloads) {
            processQueue()
            return
        }
        // Keep the FIRST tasks in list order — and list order is NEWEST-FIRST
        // (DownloadRepository.addFirst prepends), so this keeps the MOST
        // RECENTLY enqueued/active tasks and demotes the OLDEST back to QUEUED.
        // processQueue then picks the newest QUEUED again (firstOrNull below),
        // so the queue is LIFO by design: the tap the user made most recently
        // starts next, matching the newest-first Downloads list on screen.
        actives.drop(maxConcurrentDownloads).forEach { t ->
            com.anonrode.downloader.util.DebugLog.user(
                "concurrency limit now $maxConcurrentDownloads — demoting task=${t.id} (${t.episodeTitle.take(40)}) back to queue"
            )
            val entry = runtimeRegistry.get(t.id)
            entry?.activeJob?.cancel()
            entry?.activeJob = null
            YoutubeDlDownloader.killProcess(t.id)
            TurboDownloader.cancelTask(t.id)
            repository.update(t.id) { task ->
                // Only demote a task that is STILL active (engine-audit P2):
                // the `actives` list is a snapshot, and a task whose completion
                // landed in the window must not be re-queued — that re-downloaded
                // a finished file over the same path on metered data.
                if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.RESOLVING ||
                    task.status == TaskStatus.VALIDATING
                ) task.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0, errorMessage = null)
                else task
            }
        }
        updateServiceState(force = true)
        processQueue()
    }

    private fun pauseForNetwork(taskId: String) {
        val task = repository.find(taskId) ?: return
        if (task.status != TaskStatus.DOWNLOADING && task.status != TaskStatus.RESOLVING) return
        val entry = runtimeRegistry.get(taskId)
        entry?.activeJob?.cancel()
        entry?.activeJob = null
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        // Same in-flight sweep as pause(): a network park mid-resolve must
        // not leave blocking HTTP draining data until its read timeout.
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        // Atomic re-check inside the StateFlow transform: the find()/status
        // guard above is a non-atomic snapshot, and on a flaky network a
        // disconnect-triggered park races a user pause() — its marker write
        // could land AFTER pause() had cleared errorMessage and set
        // userPaused, leaving NETWORK_PAUSE_MESSAGE behind so the next
        // reconnect re-queued the task the user just paused. Inside the
        // transform the check is CAS-atomic against every other repository
        // write: a user-paused or already-parked task makes it a no-op.
        repository.update(taskId) { t ->
            if (t.userPaused || (t.status != TaskStatus.DOWNLOADING && t.status != TaskStatus.RESOLVING)) t
            else t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = NETWORK_PAUSE_MESSAGE)
        }
        // P3-14 (2026-10-21): the network park itself writes NO log line, so
        // "it paused itself" was undiagnosable from every capture (0
        // occurrences of the marker message across all logs). Log AFTER the
        // CAS lands and only when it actually landed, so a racing user pause
        // is never misreported as a network park.
        val parkedRow = repository.find(taskId)
        if (parkedRow != null && parkedRow.status == TaskStatus.PAUSED &&
            parkedRow.errorMessage == NETWORK_PAUSE_MESSAGE && !parkedRow.userPaused
        ) {
            com.anonrode.downloader.util.DebugLog.engine(
                "network park task=$taskId (landed=${parkedRow.downloadedBytes / 1024}KiB) — connectivity lost; the reconnect sweep requeues it"
            )
        }
        updateServiceState(force = true)
    }

    fun cancel(taskId: String) {
        val task = repository.find(taskId)
        // Mark full-cancel intent BEFORE the job cancellation: the job's
        // CancellationException handler kills this task's in-flight resolver
        // HTTP based on this mark. Doing it here (instead of a global
        // cancelInFlight in this method) keeps OTHER tasks' in-flight
        // requests untouched — the cross-talk bug.
        val hasRunningJob = runtimeRegistry.getActiveJob(taskId) != null
        if (hasRunningJob) runtimeRegistry.markFullyCancelled(taskId)
        runtimeRegistry.cancelAndRemove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        // Full-cancel marking only matters when a Turbo run is unwinding; a queued
        // task has none, and the flag would otherwise linger in the set forever.
        TurboDownloader.cancelTask(taskId, isFullCancel = hasRunningJob)
        // Kill this task's own in-flight resolver HTTP immediately when it is
        // safe: with no other running task there is nothing to cross-talk
        // into. Otherwise the job's cancellation handler sweeps once this
        // task's current blocking call unwinds (bounded by the read timeout).
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        com.anonrode.downloader.util.DebugLog.user("cancel $taskId")
        if (task != null) {
            engineScope.launch {
                purgeTaskArtifacts(task)
            }
        }
        repository.remove(taskId)
        updateServiceState(force = true)
        processQueue()
    }

    /**
     * Batch cancel/delete. Same per-task teardown as [cancel], but the
     * repository is updated once and the artifact purges run sequentially in
     * one coroutine, so a 24-episode show delete does not enqueue 24 JSON
     * persists, 24 MediaStore mirrors, 24 service updates or 24 racing
     * folder-cleanup jobs on the UI thread.
     */
    fun cancelTasks(taskIds: Collection<String>) {
        val ids = taskIds.toSet()
        if (ids.isEmpty()) return
        val tasks = ids.mapNotNull { repository.find(it) }
        if (tasks.isEmpty()) return
        val hadRunning = tasks.any { runtimeRegistry.getActiveJob(it.id) != null }
        tasks.forEach { task ->
            val hasRunningJob = runtimeRegistry.getActiveJob(task.id) != null
            if (hasRunningJob) runtimeRegistry.markFullyCancelled(task.id)
            runtimeRegistry.cancelAndRemove(task.id)
            YoutubeDlDownloader.killProcess(task.id)
            TurboDownloader.cancelTask(task.id, isFullCancel = hasRunningJob)
            com.anonrode.downloader.util.DebugLog.user("cancel ${task.id}")
        }
        if (hadRunning && !runtimeRegistry.hasOtherActiveWork("")) {
            HttpClient.cancelInFlight()
        }
        repository.removeAll(ids)
        updateServiceState(force = true)
        processQueue()
        engineScope.launch {
            tasks.forEach { purgeTaskArtifacts(it) }
        }
    }


    /** Remove every partial artifact a cancelled download may have left.
     *  deleteRecursively: multi-file torrents write a DIRECTORY, and File.delete()
     *  silently refuses non-empty dirs — canceled season torrents left their
     *  preallocated gigabytes behind (user-reported 2026-08-22: 8GB+ stuck after
     *  cancel of a magnet with a dead swarm). The `.work-<taskId>` extractor
     *  dir is the same hazard reintroduced: yt-dlp's `.f140.m4a`/`.f135.mp4`
     *  shards live THERE, not at filePath, and were named after nothing the old
     *  sweep matched — a cancelled 2 GiB YouTube job stranded the whole partial
     *  set in the user's visible folder (audit 2026-09-13). */
    private fun purgeTaskArtifacts(task: DownloadTask) {
        try {
            val target = File(task.filePath)
            // The Turbo→aria2c handoff and the aria2c→Turbo rescue write a
            // URL-extension sibling ("Ep 1.mkv" task, "Ep 1.mp4" artifact +
            // its .aria2/.part/.turbo) — engine-audit P2: purging only the
            // task's own extension family left those partial bytes in the
            // user's folder (the "8 GB left after cancel" shape).
            // Legacy handoff artifacts were named after the URL's raw extension
            // (download.php?token=... → "Ep 1.php" + .aria2). The classifier only
            // knows media extensions, so fall back to the old short-alnum URL
            // extension for those — subtitle/aux extensions stay excluded so a
            // cancel can never purge a sibling .srt family.
            val legacyExt = task.directUrl.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').substringAfterLast('.', "")
            val legacySafe = legacyExt.isNotBlank() && legacyExt.length <= 5 &&
                legacyExt.all { it.isLetterOrDigit() } &&
                legacyExt.lowercase() !in setOf("srt", "vtt", "ass", "ssa", "sub")
            val urlExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(task.directUrl)
                ?: (if (legacySafe) legacyExt else "")
            val sibling = if (urlExt.isNotBlank()) {
                File(target.parentFile, "${target.nameWithoutExtension}.$urlExt")
                    .takeIf { it.absolutePath != target.absolutePath }
            } else null
            // Never touch a path that belongs to a DIFFERENT task: the sibling
            // family can coincide with a sibling task's real target name.
            val otherTargets = repository.tasks.value
                .filter { it.id != task.id }
                .map { it.filePath }
                .toSet()
            val bases = listOfNotNull(target, sibling).filter { it.absolutePath !in otherTargets }
            for (base in bases) {
                base.deleteRecursively()
                File(base.absolutePath + ".part").delete()
                File(base.absolutePath + ".ytdl").delete()
                File(base.absolutePath + ".aria2").delete()
                File(base.absolutePath + ".turbo").delete()
                File(base.absolutePath + ".hlsfp").delete()
                File(base.absolutePath + ".hlsrs").delete()
                File(base.absolutePath + ".hts").delete()
                base.parentFile?.let {
                    deletePartFragFiles(it, base.nameWithoutExtension, privateWorkdir = false)
                }
            }
            target.parentFile?.let { parent ->
                File(parent, ".work-${task.id}").deleteRecursively()

                // When a download is cancelled or deleted, if the enclosing show folder
                // is left empty (e.g. 1-episode series, standalone movie, or last remaining episode),
                // delete the folder so empty directory trees do not clutter user storage.
                //
                // Safety guards:
                // 1. Strictly verify the folder is a subfolder inside Downloads/Anon, NEVER Anon itself or Downloads root.
                // 2. Verify no other task in repository (active, paused, completed) targets this folder.
                // 3. File.delete() is atomic and POSIX-safe: it strictly succeeds ONLY if the folder is completely empty.
                try {
                    val rootAnon = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ),
                        "Anon"
                    )
                    val parentCanon = parent.canonicalFile
                    val anonCanon = rootAnon.canonicalFile
                    val isSubdirOfAnon = parentCanon.absolutePath != anonCanon.absolutePath &&
                        parentCanon.absolutePath.startsWith(anonCanon.absolutePath + File.separator)

                    if (isSubdirOfAnon) {
                        val otherTasksInFolder = repository.tasks.value.any { other ->
                            other.id != task.id && try {
                                File(other.filePath).canonicalFile.parentFile?.absolutePath == parentCanon.absolutePath
                            } catch (_: Throwable) {
                                File(other.filePath).parentFile?.absolutePath == parent.absolutePath
                            }
                        }
                        if (!otherTasksInFolder) {
                            parentCanon.listFiles()?.filter { it.isDirectory && it.name.startsWith(".work-") }?.forEach {
                                try { it.deleteRecursively() } catch (_: Throwable) {}
                            }
                            parentCanon.delete()
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun retry(taskId: String, processNow: Boolean = true) {
        // A stale id (task already removed, e.g. a notification retry after the
        // task was cancelled) must not mint a registry entry that nothing can
        // ever remove.
        val existingTask = repository.find(taskId) ?: return
        try {
            val target = File(existingTask.filePath)
            val isImg = target.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
            val isCorrupt = if (isImg) {
                target.length() < 1024L || looksLikeHtml(target) || !isImageValid(target)
            } else {
                target.length() < 50 * 1024L || looksLikeHtml(target) || !decodeCheck(target)
            }
            // An .aria2 control file marks an aria2c partial that resume will
            // continue (-c): decodeCheck fails on any truncated container, so
            // deleting here destroyed multi-GB banked progress on every
            // resume (and orphaned the control file).
            val hasAria2Partial = File(existingTask.filePath + ".aria2").exists()
            // Never delete while another path owns the file right now: an active
            // run (or a just-completed row) has the artifact under management,
            // and this check races the completion write.
            val liveStatus = repository.find(taskId)?.status
            val activelyOwned = liveStatus == TaskStatus.RESOLVING || liveStatus == TaskStatus.DOWNLOADING ||
                liveStatus == TaskStatus.VALIDATING || liveStatus == TaskStatus.COMPLETED
            if (target.exists() && isCorrupt && !hasAria2Partial && !activelyOwned) {
                target.delete()
            }
        } catch (_: Throwable) {}

        // Each tap grants ONE bypass of the host health gate: the next
        // resolution attempt runs even while the host sits in HostHealth
        // backoff (consumed in startTask). Bounded so stale ids cannot grow.
        runtimeRegistry.markForManualRetry(taskId)
        // Explicit resume/retry clears the user-pause mark — this is the ONLY
        // path allowed to undo a user pause (UI resume button and the
        // notification retry action both funnel through here).
        repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
        if (processNow) processQueue()
    }

    /**
     * Cross-provider auto-failover: the task's source site could not serve the
     * title (resolver chain empty, gateway dead, or resolution timed out).
     * Search the OTHER providers for the same title and rewrite this task onto
     * a working site's source, then requeue it — the user tapped a download
     * and should GET the download, not a lesson about which site is broken.
     *
     * Bounds: every site already tried for this task is excluded, so the loop
     * always walks new ground and cannot ping-pong between dead providers;
     * a task may be failed over at most 3 times, then it fails with an honest
     * error. In-memory only, like the park-cycle counter.
     *
     * Series are matched per-episode (loadEpisodes on the candidate show, pick
     * the episode with the same number) so a Suits episode lands on the same
     * episode, not a random one. Movies take the matched show card directly.
     *
     * Returns true when the task was re-pointed at a new source and requeued —
     * the caller must then EXIT its coroutine (the task no longer belongs to
     * this job); false means "no alternative found", and the caller proceeds
     * with the normal park/fail path.
     */
    private suspend fun attemptCrossProviderFailover(task: DownloadTask, garbageFile: File? = null): Boolean {
        // Progress Banking Guard. A GARBAGE artifact (HTML/tiny/bad
        // signature) is not banked progress, so it must not veto failover —
        // but real partials still do. Never fail over when:
        //  - a real .part / .aria2 partial exists,
        //  - a >1 MB artifact exists (a plausible real download).
        val garbage = garbageFile != null && isGarbageArtifact(garbageFile)
        if (garbage) {
            val partFile = File(task.filePath + ".part")
            val ariaControl = File(task.filePath + ".aria2")
            val realPartial = (partFile.exists() && partFile.length() > 0L) ||
                (ariaControl.exists() && ariaControl.length() > 0L)
            val largeArtifact = listOfNotNull(garbageFile, File(task.filePath))
                .any { it.exists() && it.isFile && it.length() > 1024L * 1024L }
            if (realPartial || largeArtifact) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "task=${task.id} failover: garbage output but real partial/large artifact exists — not failing over"
                )
                return false
            }
            // Proven garbage is deleted BEFORE the requeue so the new source
            // never resumes onto it or treats it as a completed artifact.
            try { garbageFile?.delete() } catch (_: Exception) {}
        } else if ((task.downloadedBytes > 0L || bytesLanded(task) > 0L) && !bankedSourceUnprogressable(task)) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=${task.id} failover: skipped because task already banked ${bytesLanded(task)} bytes on a source still making progress"
            )
            return false
        } else if (task.downloadedBytes > 0L || bytesLanded(task) > 0L) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=${task.id} failover: banked ${bytesLanded(task)} bytes belong to a proven-unprogressable source " +
                    "(${backendFailures[task.id]?.let { "ladder failures=${it.count}" } ?: "poisoned URL"}) — allowing failover"
            )
        }
        if (task.site.isBlank()) return false
        if (task.directUrl.startsWith("magnet:", ignoreCase = true)) return false
        val tried = runtimeRegistry.getOrCreate(task.id).triedFailoverSites
        if (!tried.add(task.site)) return false // already failed over FROM this site once
        if (tried.size > 3) {
            tried.clear()
            return false
        }
        val query = task.showTitle.ifBlank { task.episodeTitle }
        if (query.isBlank()) return false

        val isAsian = isAsianDramaContext(task)
        val origYear = extractYear(task.showTitle)
            ?: extractYear(task.episodeTitle)
            ?: extractYear(task.sourceUrl)
        val origCountry = extractCountry("${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}", task.site)
        val (origSeason, origEpNum) = parseSeasonAndEpisode(task)

        com.anonrode.downloader.util.DebugLog.resolve(
            "task=${task.id} failover: '${task.site}' cannot serve \"$query\" (isAsian=$isAsian, year=$origYear, country=$origCountry, S${origSeason}E${origEpNum}) — searching other providers"
        )

        // 4-Layer "Suits" Remake Guard - Layer 1: Genre / Cluster Boundary Guard
        // If isAsian: candidates filtered to ASIAN_DRAMA_CLUSTER plus the proven
        // mixed-site secondaries (nkiri/9jarocks). Every candidate still faces
        // Layers 2-4 (year, country tag, season/episode) below.
        // If not isAsian: candidates filtered to WESTERN_CLUSTER (must not query DEDICATED_ASIAN_SITES).
        val allowedCluster = if (isAsian) ASIAN_DRAMA_CLUSTER + SECONDARY_ASIAN_FALLBACK else WESTERN_CLUSTER

        // Search eligible providers in parallel, each bounded; skip hosts
        // currently in backoff so a failover never lands on another dead site.
        //
        // Completion accounting: NoSourceLedger may only record "no source"
        // when EVERY eligible search actually finished. A 451/transport
        // failure or a timeout is absence of evidence, not evidence of
        // absence (the ledger hides titles for 48h).
        val searchAttempted = java.util.concurrent.atomic.AtomicInteger(0)
        val searchCompleted = java.util.concurrent.atomic.AtomicInteger(0)
        var allBatchesCompleted = true

        suspend fun searchBatch(providers: List<com.anonrode.downloader.providers.SiteProvider>): List<com.anonrode.downloader.data.models.ShowCard> {
            if (providers.isEmpty()) return emptyList()
            val batch = kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                coroutineScope {
                    providers.map { provider ->
                        searchAttempted.incrementAndGet()
                        async(Dispatchers.IO) {
                            try {
                                val result = withTimeoutOrNull(20_000L) { provider.search(query) }
                                if (result != null) searchCompleted.incrementAndGet()
                                result ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
            }
            if (batch == null) allBatchesCompleted = false
            return batch ?: emptyList()
        }

        // OTA kill switch: a disabled site accepts no NEW work, so failover
        // must never select one (its existing tasks may still finish).
        val firstProviders = ProviderRegistry.enabledProviders.filter { provider ->
            val pName = provider.name.lowercase()
            provider.searchEnabled &&
                pName in allowedCluster &&
                (isAsian || pName !in DEDICATED_ASIAN_SITES) &&
                tried.none { it.equals(provider.name, ignoreCase = true) }
        }
        val candidates = searchBatch(firstProviders).toMutableList()

        // Anitaku/anime second pass: the anime catalog is intentionally
        // outside ASIAN_DRAMA_CLUSTER, so a nepu Servamp failover never saw
        // it. When the first pass has no plausible title match, ask the
        // anime provider once and let its cards through Layer 1.
        var secondPassSites = emptySet<String>()
        if (isAsian && candidates.none { card ->
                titleMatches(query, card.title) && card.site.lowercase() in allowedCluster
            }) {
            val animeProviders = ProviderRegistry.enabledProviders.filter { provider ->
                provider.name.lowercase() in ANIME_SECOND_PASS_SITES &&
                    provider.searchEnabled &&
                    tried.none { it.equals(provider.name, ignoreCase = true) }
            }
            if (animeProviders.isNotEmpty()) {
                secondPassSites = animeProviders.map { it.name.lowercase() }.toSet()
                candidates.addAll(searchBatch(animeProviders))
            }
        }

        val normQuery = normalizeTitleQuery(query)
        val ranked = candidates
            .asSequence()
            .filter { card ->
                val candSite = card.site.lowercase()
                // OTA kill switch (re-checked at CARD level too: a provider
                // disabled mid-search must not win the failover).
                if (!ProviderRegistry.isSiteEnabled(card.site)) return@filter false
                // Layer 1: Genre / Cluster Boundary Guard on candidate card
                val clusterMatch = if (candSite in secondPassSites) {
                    true
                } else if (isAsian) {
                    candSite in ASIAN_DRAMA_CLUSTER || candSite in SECONDARY_ASIAN_FALLBACK
                } else {
                    candSite in WESTERN_CLUSTER && candSite !in DEDICATED_ASIAN_SITES
                }
                if (!clusterMatch) return@filter false

                if (card.url.isBlank()) return@filter false
                if (tried.any { it.equals(card.site, ignoreCase = true) }) return@filter false
                if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(card.url)) return@filter false
                if (!titleMatches(query, card.title)) return@filter false

                // Layer 2: Release Year Matching with ±1 tolerance.
                // Streaming sites label the SAME show off-by-one (original
                // production year vs international premiere): asianc serves
                // "the-promised-neverland-2020" while 9jaRocks lists
                // "The Promised Neverland (2019)" — a hard mismatch reject
                // silently discarded the user's working source and pushed the
                // task onto a failing one (journal 10-15 20:25). ±1 years is
                // tight enough that implausible cross-show matches still lose
                // on season/title guards above and below.
                val candYear = extractYear(card.year)
                    ?: extractYear(card.title)
                    ?: extractYear(card.url)
                if (origYear != null && candYear != null && kotlin.math.abs(origYear - candYear) > 1) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" due to year mismatch (orig=$origYear, cand=$candYear)"
                    )
                    return@filter false
                }

                // Layer 3: Country Tag Matching.
                // Mixed-site secondaries (nkiri/9jarocks) must prove country by
                // EXPLICIT tag only: extractCountry's site-default WESTERN would
                // auto-reject an untagged K-drama card on nkiri — the exact
                // content this failover exists to rescue. No explicit tag means
                // UNKNOWN here, and UNKNOWN never rejects.
                val candCountry = extractFailoverCountry(isAsian, "${card.title} ${card.category}", card.site)
                if (origCountry != DramaCountry.UNKNOWN && candCountry != DramaCountry.UNKNOWN && origCountry != candCountry) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" due to country mismatch (orig=$origCountry, cand=$candCountry)"
                    )
                    return@filter false
                }

                // Season guard on title
                if (!seasonConsistent(query, card.title, if (origEpNum > 0) origSeason else null)) {
                    return@filter false
                }

                // Episode-COUNT pre-check deliberately removed (2026-10-19):
                // providers report partial/paginated counts and season-encoded
                // numbering (nepu 113 = S01E13) made "6 episodes < 113" reject
                // a provider that DID have the exact episode. Exact
                // (season, episode) matching below is the real guard.

                true
            }
            // Exact title match first, then a provider whose host has proven
            // itself in the ledger, then country agreement (Asian tasks: an
            // explicitly-tagged same-country card is probed before an
            // untagged one), then everything else.
            val agreeWith: Map<com.anonrode.downloader.data.models.ShowCard, Boolean> =
                ranked.associateWith { card ->
                    isAsian && origCountry != DramaCountry.UNKNOWN &&
                        extractFailoverCountry(true, "${card.title} ${card.category}", card.site) == origCountry
                }
            val ordered = ranked
            .sortedWith(
                compareByDescending<com.anonrode.downloader.data.models.ShowCard> { normalizeTitleQuery(it.title) == normQuery }
                    .thenByDescending { com.anonrode.downloader.pipeline.HostHealth.hasProvenLocker(it.url) }
                    .thenByDescending { agreeWith[it] == true }
            )
            .toList()

        // Hard deadline for the whole failover (episode lookups included).
        val failoverDeadline = System.currentTimeMillis() + 60_000L
        for (card in ordered) {
            if (System.currentTimeMillis() > failoverDeadline) break
            var newMirrors = emptyList<String>()
            val newUrl: String? = if (origEpNum > 0) {
                // Layer 4: Season & Episode Boundary Verification
                try {
                    val details = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        ProviderRegistry.loadEpisodes(card)
                    } ?: continue

                    // No episode-COUNT gate: a partial/paginated list is not
                    // proof of absence (the nepu Avatar case compared a 6-item
                    // list against 113). Exact SxxEyy matching below decides.

                    // Candidate must have matching (origSeason, origEpNum)
                    val origIsExplicitSeries = origEpNum > 0 && (task.episodeTitle.contains("Episode", ignoreCase = true) ||
                        task.episodeTitle.contains("Ep", ignoreCase = true) ||
                        task.episodeTitle.contains("Season", ignoreCase = true) ||
                        task.sourceUrl.isNotBlank())
                    val candIsMovie = card.category.equals("Movies", ignoreCase = true) || card.title.contains("Movie", ignoreCase = true)
                    if (origIsExplicitSeries && candIsMovie && details.episodes.size == 1) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: rejected movie card \"${card.title}\" for series task S${origSeason}E${origEpNum}"
                        )
                        continue
                    }
                    val isSingleMovie = details.episodes.size == 1 && origEpNum <= 1 && origSeason == 1 && !origIsExplicitSeries
                    val identityMatches = details.episodes.filter { candEp ->
                        if (isSingleMovie) true
                        else {
                            val (candS, candE) = parseCandidateSeasonAndEpisode(card, candEp)
                            candS == origSeason && candE == origEpNum
                        }
                    }
                    // Season test 9: when (season, episode) collides (specials
                    // like E5.5 vs E5), the TASK TITLE disambiguates.
                    val specialMarker = Regex("""(?i)\bs\d{1,2}\s*e(\d{1,4})\.(\d)\b""")
                        .find(task.episodeTitle)
                        ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
                    val matchingEp = if (specialMarker != null) {
                        identityMatches.firstOrNull { ep ->
                            Regex("""(?i)(?:^|[^a-z0-9])e(?:p|pisode)?\s*(\d{1,4})\.(\d)(?![a-z0-9])""")
                                .find("${ep.title} ${ep.url}")
                                ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } == specialMarker
                        } ?: identityMatches.firstOrNull()
                    } else identityMatches.firstOrNull()
                    if (matchingEp == null) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: no episode matching S${origSeason}E${origEpNum} in \"${card.title}\""
                        )
                        continue
                    }

                    if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(matchingEp.url)) continue
                    newMirrors = matchingEp.mirrorUrls
                    matchingEp.url
                } catch (_: Exception) {
                    continue
                }
            } else {
                card.url
            }
            if (newUrl.isNullOrBlank()) continue

            // Atomic Pause Guard Before Deleting Partials:
            // Verify task.userPaused inside repository.update before deleting partial files on disk.
            var wasUserPaused = false
            repository.update(task.id) { current ->
                if (current.userPaused) {
                    wasUserPaused = true
                    current
                } else {
                    current.copy(
                        sourceUrl = newUrl,
                        directUrl = newUrl,
                        mirrorUrls = newMirrors,
                        selectedMirrorUrl = "",
                        site = card.site,
                        quality = current.quality, // Preserve User Quality
                        status = TaskStatus.QUEUED,
                        errorMessage = null,
                        speedBytesPerSec = 0.0,
                        downloadedBytes = 0L,
                        totalBytes = 0L
                    )
                }
            }

            if (wasUserPaused) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "task=${task.id} failover: aborted because task was paused by user"
                )
                return false
            }

            com.anonrode.downloader.util.DebugLog.user(
                "task=${task.id} failover: switching source ${task.site} -> ${card.site} for \"$query\"" +
                    (if (origEpNum > 0) " (S${origSeason}E${origEpNum})" else "")
            )
            // The task is re-pointed at a different source: its previous
            // source's dead-URL marks must not answer for the new one.
            ResolveBudget.forgetTask(task.id)
            clearBackendFailures(task.id)

            // Safe to delete partial files ONLY after verifying task is actively continuing (not user paused)
            try {
                File(task.filePath).delete()
                File(task.filePath + ".part").delete()
                File(task.filePath + ".ytdl").delete()
                File(task.filePath + ".aria2").delete()
                File(task.filePath + ".turbo").delete()
                File(task.filePath + ".hlsfp").delete()
                File(task.filePath + ".hlsrs").delete()
                File(task.filePath + ".hts").delete()
                File(task.filePath).parentFile?.let {
                    deletePartFragFiles(it, File(task.filePath).nameWithoutExtension, privateWorkdir = false)
                    File(it, ".work-" + task.id).deleteRecursively()
                }
            } catch (_: Throwable) {}

            return true
        }

        // Nothing usable anywhere: leave the tried-set in place so the NEXT
        // failure of this task fails over again from its current site if a
        // manual retry re-points it, but don't loop here.
        com.anonrode.downloader.util.DebugLog.resolve(
            "task=${task.id} failover: no other provider could serve \"$query\""
        )
        // Strongest no-source evidence the app can gather: every eligible
        // provider was searched and none carried the title. Recorded ONCE per
        // attempt-cycle here — after the candidate loop, never inside it —
        // and the ledger TTLs it (48h) + clears on rules activation.
        // Only COMPLETED searches count: a 451/transport exception or a
        // timeout is not proof the title is absent.
        val searched = searchAttempted.get()
        val completed = searchCompleted.get()
        val allProvidersAnswered = allBatchesCompleted && searched > 0 && completed == searched
        if (allProvidersAnswered) {
            com.anonrode.downloader.pipeline.NoSourceLedger.recordUnresolved(query)
        } else {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=${task.id} failover: ledger NOT recorded — only $completed/$searched provider searches completed (batchCompleted=$allBatchesCompleted)"
            )
        }
        return false
    }

    internal fun extractYear(text: String): Int? {
        if (text.isBlank()) return null
        return YEAR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    // Keyword-only country read: the same tag vocabulary as the historical
    // extractCountry, WITHOUT the site-default tail. Used by failover for
    // mixed-site secondaries, where the WESTERN site default is actively
    // wrong (an nkiri K-drama card is not a Western show). Untagged text is
    // UNKNOWN, and UNKNOWN never rejects in Layer 3 — explicit tags still
    // do (a Hollywood-tagged card on 9jarocks loses to a Korean task).
    internal fun extractExplicitCountry(text: String): DramaCountry {
        val lower = text.lowercase()
        return when {
            lower.contains("korean") || lower.contains("k-drama") || lower.contains("kdrama") ||
                lower.contains("k drama") || lower.contains("korea") -> DramaCountry.KOREAN
            lower.contains("chinese") || lower.contains("c-drama") || lower.contains("cdrama") ||
                lower.contains("c drama") || lower.contains("china") -> DramaCountry.CHINESE
            lower.contains("japanese") || lower.contains("j-drama") || lower.contains("jdrama") ||
                lower.contains("j drama") || lower.contains("japan") -> DramaCountry.JAPANESE
            lower.contains("taiwanese") || lower.contains("tw-drama") || lower.contains("taiwan") -> DramaCountry.TAIWANESE
            lower.contains("thai") || lower.contains("thailand") || lower.contains("lakorn") ||
                lower.contains("th-drama") -> DramaCountry.THAI
            lower.contains("western") || lower.contains("hollywood") || lower.contains("american") ||
                lower.contains("nollywood") -> DramaCountry.WESTERN
            else -> DramaCountry.UNKNOWN
        }
    }

    internal fun extractCountry(text: String, site: String = ""): DramaCountry {
        val explicit = extractExplicitCountry(text)
        if (explicit != DramaCountry.UNKNOWN) return explicit
        return when {
            WESTERN_CLUSTER.contains(site.lowercase()) -> DramaCountry.WESTERN
            else -> DramaCountry.UNKNOWN
        }
    }

    // Layer-3 country read for cross-provider failover: identical to
    // extractCountry EXCEPT an Asian task reading a mixed-site secondary
    // card (nkiri/9jarocks) skips the WESTERN site default.
    internal fun extractFailoverCountry(isAsian: Boolean, text: String, site: String = ""): DramaCountry {
        if (isAsian && site.lowercase() in SECONDARY_ASIAN_FALLBACK) {
            return extractExplicitCountry(text)
        }
        return extractCountry(text, site)
    }

    internal fun isAsianDramaContext(task: DownloadTask): Boolean {
        if (com.anonrode.downloader.pipeline.HostPolicyRegistry.isAsianSite(task.site)) return true
        val country = extractCountry("${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}", task.site)
        if (country in setOf(DramaCountry.KOREAN, DramaCountry.CHINESE, DramaCountry.JAPANESE, DramaCountry.THAI, DramaCountry.TAIWANESE)) {
            return true
        }
        return false
    }

    /**
     * Some providers encode SxxEyy as a single integer (113 = S01E13).
     * 9jarocks did this historically; nepu's Avatar-style catalog does the
     * same (live failover evidence 2026-10-19: episodeNum=113, a 6-item
     * season-1 list, and the old count pre-check rejected every candidate
     * before the exact S01E13 match could run). Returns null when the number
     * is not a plausible season-encoded value.
     */
    internal fun normalizeSeasonEncodedEpisode(site: String, episodeNum: Int): Pair<Int, Int>? =
        normalizeSeasonEncodedEpisodeNumber(site, episodeNum)

    internal fun parseSeasonAndEpisode(task: DownloadTask): Pair<Int, Int> {
        // Authoritative identity first (util.SeasonEpisode): explicit task
        // fields, then the 1000-base encoding, then the site-scoped legacy
        // 100-base decode (9jarocks is flat and never decoded as packed).
        if (task.season > 0 || task.episode > 0 || task.episodeNum > 0) {
            val codec = com.anonrode.downloader.util.SeasonEpisode.of(task)
            if (codec.first > 0 && codec.second > 0) return codec
        }
        val text = "${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}".lowercase()
        val season = Regex("""(?:s|season)[\s._-]?0*(\d{1,2})\b""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val ep = if (task.episodeNum > 0) {
            task.episodeNum
        } else {
            Regex("""(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return Pair(season, ep)
    }

    internal fun parseCandidateSeasonAndEpisode(card: com.anonrode.downloader.data.models.ShowCard, ep: com.anonrode.downloader.data.models.EpisodeItem): Pair<Int, Int> {
        if (ep.season > 0 || ep.episode > 0 || ep.episodeNum > 0) {
            val codec = com.anonrode.downloader.util.SeasonEpisode.of(ep)
            if (codec.first > 0 && codec.second > 0) return codec
        }
        val text = "${card.title} ${ep.title} ${ep.url}".lowercase()
        val season = Regex("""(?:s|season)[\s._-]?0*(\d{1,2})\b""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val epNum = if (ep.episodeNum > 0) {
            ep.episodeNum
        } else {
            Regex("""(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return Pair(season, epNum)
    }

    /** Lowercase and strip everything but letters/digits — the loose title
     *  comparison used by failover matching ("Sholay (1975)" vs "sholay"). */
    private fun normalizeTitleQuery(q: String): String =
        q.lowercase().filter { it.isLetterOrDigit() }

    // Words too common to help tell two titles apart; dropped by
    // [titleMatches] so "My Name is Khan" doesn't demand the word "is".
    // Site-noise words ("indian movie", "hindi", "web-dl", "480p"…) are
    // appended by scrapers, not part of the title — demanding them makes
    // failover reject the exact movie it's looking for (naijavault's
    // "My Name Is Khan 2010 Indian Movie" vs pluto's
    // "My Name Is Khan 2010 Hindi" — same film).
    private val TITLE_STOPWORDS = setOf(
        "the", "a", "an", "is", "of", "and", "to", "in", "on", "le", "la", "de",
        "movie", "film", "series", "complete", "season", "episode", "download",
        "full", "hd", "hindi", "english", "dubbed", "subbed", "korean", "chinese",
        "drama", "web", "dl", "webdl", "bluray", "brrip", "dvdrip", "hevc", "x264",
        "x265", "aac", "480p", "720p", "1080p", "2160p", "4k"
    )

    /** True when the candidate title covers the query: every meaningful word
     *  of the query must appear in the normalized candidate. Pure numbers
     *  (years like "1975") are ignored — our own titles carry years that
     *  candidate listings often omit. */
    private fun titleMatches(query: String, candidate: String): Boolean {
        val normCard = normalizeTitleQuery(candidate)
        if (normCard.isBlank()) return false
        val words = Regex("[a-z0-9]+").findAll(query.lowercase())
            .map { it.value }
            .filter { it.length >= 2 && !TITLE_STOPWORDS.contains(it) && !it.all { c -> c.isDigit() } }
            .toList()
        if (words.isEmpty()) return false
        if (!words.all { normCard.contains(it) }) return false

        // Year check inside titleMatches if both present — ±1 tolerance,
        // same rationale as the Layer-2 comment above: streaming sites label
        // the SAME show off-by-one, so a strict equality here defeated the
        // documented tolerance before it was ever reached.
        val qYear = extractYear(query)
        val cYear = extractYear(candidate)
        if (qYear != null && cYear != null && kotlin.math.abs(qYear - cYear) > 1) return false

        // Sequel / Part check if both present
        val partRegex = Regex("""\b(?:part|chapter|pt)[\s._-]?(\d+|[ivx]+)\b""")
        val qPart = partRegex.find(query.lowercase())?.groupValues?.get(1)
        val cPart = partRegex.find(candidate.lowercase())?.groupValues?.get(1)
        if (qPart != null && cPart != null && qPart != cPart) return false

        return true
    }

    /** Season guard for failover: when the task title declares a season
     *  ("Suits S02", "Alchemy of Souls Season 2"), the candidate must declare
     *  the SAME season. Candidates that declare a different season are
     *  rejected; candidates with no season marker pass (the episode-number
     *  match inside the provider's episode list is the second guard). */
    private fun seasonConsistent(query: String, candidate: String, seasonNum: Int?): Boolean {
        if (seasonNum == null) return true
        val cardLower = candidate.lowercase()
        val cardSeason = Regex("(?:s|season)[\\s._-]?0*(\\d{1,2})\\b")
            .find(cardLower)?.groupValues?.get(1)?.toIntOrNull()
        return cardSeason == null || cardSeason == seasonNum
    }

    private fun looksLikeHtml(file: File): Boolean {
        return try {
            val buf = file.inputStream().use { ins ->
                val b = ByteArray(512)
                val n = ins.read(b)
                if (n <= 0) return false
                b.copyOf(n)
            }
            if (buf.size >= 16) {
                if (buf.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))) return false
                if (buf[0] == 0x1A.toByte() && buf[1] == 0x45.toByte() && buf[2] == 0xDF.toByte() && buf[3] == 0xA3.toByte()) return false
                if (buf[0] == 0x47.toByte() && (buf.size < 189 || buf[188] == 0x47.toByte())) return false
                if (buf[0] == 0x49.toByte() && buf[1] == 0x44.toByte() && buf[2] == 0x33.toByte()) return false
            }
            if (DynamicRulesManager.isDecoyBody(buf)) return true
            var start = 0
            if (buf.size >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) {
                start = 3
            }
            val head = String(buf, start, buf.size - start).trimStart().lowercase()
            head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
                head.startsWith("<body") || head.startsWith("<!--") || head.startsWith("<script") ||
                head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<style") ||
                head.startsWith("<iframe") || head.startsWith("<meta") || head.startsWith("<form") ||
                head.startsWith("{")
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeHtml(text: String): Boolean {
        if (DynamicRulesManager.isDecoyBodyText(text)) return true
        val head = text.trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
            head.startsWith("<body") || head.startsWith("<!--") || head.startsWith("<script") ||
            head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<style") ||
            head.startsWith("<iframe") || head.startsWith("<meta") || head.startsWith("<form") ||
            head.startsWith("{")
    }

    /**
     * Tier-2 verification: ask the platform codec stack whether the file is
     * actually decodable media. MediaExtractor parses the container and exposes
     * its tracks plus per-track duration; a garbage blob yields no usable
     * track, real media yields at least one with a positive duration. This is a
     * container parse only (no frames decoded), so it is cheap, and it is
     * bounded by the platform's own parser — no manual timeout needed.
     */
    private fun decodeCheck(file: File): Boolean {
        return try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var ok = false
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)
                    if (!mime.isNullOrBlank() && fmt.containsKey(android.media.MediaFormat.KEY_DURATION) &&
                        fmt.getLong(android.media.MediaFormat.KEY_DURATION) > 0L
                    ) {
                        ok = true
                        break
                    }
                }
                com.anonrode.downloader.util.DebugLog.write("decodeCheck ${file.name}: tracks=${extractor.trackCount} decodable=$ok")
                ok
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.write("decodeCheck ${file.name}: ${e.message}")
            false
        }
    }

    private fun isImageValid(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return try {
            file.inputStream().use { ins ->
                val head = ByteArray(16)
                val read = ins.read(head)
                if (read < 4) false
                // JPEG: FF D8 FF
                else if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) true
                // PNG: 89 50 4E 47 (\x89PNG)
                else if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) true
                // WebP: RIFF....WEBP
                else if (read >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                    head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()) true
                else false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Image-artifact predicate (jpg/jpeg/png/webp) for the O1 completion check. */
    private fun isImageFile(file: File): Boolean =
        file.name.lowercase().let {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp")
        }

    /**
     * A produced artifact that is provably NOT media: a <1 KiB non-image
     * stub, an HTML decoy, a corrupt image, or a file that neither the
     * container signature nor the platform decoder accepts. Used only on the
     * zero-banked-bytes failover path — never to delete a plausible download.
     */
    private fun isGarbageArtifact(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.length() < 1024L) return true
        if (looksLikeHtml(file)) return true
        if (isImageFile(file)) return !isImageValid(file)
        val parent = file.parentFile ?: return true
        val structural = try {
            TorrentSecurityShield.validateDownloadedFile(file, parent).first
        } catch (_: Exception) {
            false
        }
        return !structural && !decodeCheck(file)
    }

    /**
     * Actual bytes this task has written to disk: the final file, its .part /
     * .ytdl partials, and yt-dlp's concurrent fragment files. The UI progress
     * watchdog uses this as the source of truth when backend output parsing
     * reports nothing, and it is what stall detection compares against.
     *
     * yt-dlp extractor tasks write into a private per-task workdir
     * (.work-<taskId> inside the target folder) — see YoutubeDlDownloader.
     * While that workdir exists it IS the task's footprint and scanning it
     * keeps sibling jobs in the same folder (two YouTube videos at once,
     * app-2026-08-31) from counting each other's bytes; once the artifact is
     * moved back the workdir is deleted, and the task is one tick away from
     * a terminal status anyway (extractor outputs are named by yt-dlp's
     * metadata template, not by task.filePath).
     */
    private fun computeDiskBytes(task: DownloadTask): Long {
        return try {
            val targetDir = File(task.filePath).parentFile ?: return 0L
            val workDir = File(targetDir, ".work-${task.id}")
            if (workDir.isDirectory) {
                return workDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            val dir = targetDir
            dir.listFiles() ?: return 0L
            val base = File(task.filePath).name
            // yt-dlp's HLS native downloader stages fragments in
            // `<output>.part-Frag<N>` and only concatenates them into `.part`
            // in batches. While a batch is in flight those bytes are real
            // progress but were invisible to the watchdog (2026-10-19 task
            // c6710cfb). They are transient scratch: the concatenated prefix
            // lives in `.part`, so summing both never double-counts.
            val fragBytes = partFragBytes(dir, base.substringBeforeLast('.', base))
            // Segmented Turbo downloads pre-allocate the .part file to its full
            // size before a single byte is written, so file.length() is not a
            // trustworthy progress source while a download is in flight.
            // When a .turbo sidecar exists, the piece map IS the truth:
            // sum committed offsets, ignore pre-allocated zeros. Never sum with destFile.
            val sidecar = File(dir, base + ".turbo")
            val ariaControl = File(dir, "$base.aria2")
            // O2b precedence: the engine only starts Turbo when NO base.aria2
            // exists, so a base.aria2 control file is ALWAYS the live transfer —
            // its bitfield outranks a .turbo plan stranded by a failed handoff.
            // (Aria2 pre-allocates the dest to full size, so the bitfield is the
            // only honest source of committed bytes.)
            if (ariaControl.exists()) {
                val ctrl = Aria2Control.parse(ariaControl)
                return ctrl?.verifiedLandedBytes() ?: ctrl?.pieces?.sumOf { it.current - it.start } ?: 0L
            }
            if (sidecar.exists()) {
                return TurboState(sidecar).writtenBytes() ?: 0L
            }
            // Legacy handoff control ("X.part" + "X.part.aria2"): the engine's
            // resume gate does not see this name, so Turbo may legitimately own
            // the .part — keep this read AFTER the Turbo plan.
            val legacyAria2 = File(dir, "$base.part.aria2")
            if (legacyAria2.exists()) {
                val ctrl = Aria2Control.parse(legacyAria2)
                return ctrl?.verifiedLandedBytes() ?: ctrl?.pieces?.sumOf { it.current - it.start } ?: 0L
            }
            // HlsVodDownloader stages the current segment stream in
            // `<file>.hts` with its resume map in `.hlsrs`. During that
            // backend's run the .hts file is the ONLY real byte source; when
            // omitted, the watchdog, park-vs-fail rule and failover guard all
            // saw 0 bytes landed.
            val htsFile = File(dir, "$base.hts")
            if (htsFile.exists()) return htsFile.length()
            // Non-turbo transfers: use strict single-file priority.
            // Guard against unverified pre-allocated files: if the task is actively downloading
            // and the raw file length equals the expected totalBytes, do NOT trust the unverified
            // preallocation without a validating sidecar/control file.
            val partFile = File(dir, "$base.part")
            if (partFile.exists()) {
                val len = partFile.length()
                if (task.status == TaskStatus.DOWNLOADING && task.totalBytes > 0L && len >= task.totalBytes && task.downloadedBytes < task.totalBytes) {
                    return task.downloadedBytes
                }
                return len + fragBytes
            }
            val ytdlFile = File(dir, "$base.ytdl")
            if (ytdlFile.exists()) return ytdlFile.length() + fragBytes
            if (fragBytes > 0L) return fragBytes
            val destFile = File(dir, base)
            if (destFile.exists()) {
                val len = destFile.length()
                if (task.status == TaskStatus.DOWNLOADING && task.totalBytes > 0L && len >= task.totalBytes && task.downloadedBytes < task.totalBytes) {
                    return task.downloadedBytes
                }
                return len
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Bytes this task has actually banked: what the backends reported OR what
     * is on disk (partials included). The park-vs-fail rule below keys on this.
     */
    private fun bytesLanded(task: DownloadTask): Long =
        maxOf(task.downloadedBytes, computeDiskBytes(task))

    /**
     * Plan fingerprint gate (2026-10-19). Banked `.part` bytes survive a
     * retry ONLY when the freshly fetched playlist still describes the same
     * media plan. A missing sidecar (first run after upgrade) is treated as a
     * match so an existing 90 MB partial is never destroyed speculatively.
     * On a real mismatch the media plan changed (different episode/quality/
     * edge manifest), so resuming would splice incompatible bytes: purge the
     * partials. The final artifact is never deleted here.
     */
    private fun applyHlsPlanFingerprint(task: DownloadTask, fingerprint: String?): Boolean {
        if (fingerprint.isNullOrBlank() || fingerprint == "live") return false
        val fpFile = File(task.filePath + ".hlsfp")
        val previous = try {
            if (fpFile.exists()) fpFile.readText().trim() else null
        } catch (_: Exception) {
            null
        }
        var purged = false
        if (previous != null && previous != fingerprint) {
            purgeHlsPartialArtifacts(File(task.filePath))
            purged = true
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=${task.id} hls plan mismatch — purged .part/.ytdl/.part-Frag before retry"
            )
        }
        try {
            fpFile.parentFile?.mkdirs()
            fpFile.writeText(fingerprint)
        } catch (_: Exception) {}
        return purged
    }

    private fun purgeHlsPartialArtifacts(base: File) {
        try { File(base.absolutePath + ".part").delete() } catch (_: Exception) {}
        try { File(base.absolutePath + ".ytdl").delete() } catch (_: Exception) {}
        // RC-9: the paced-lane resume state is keyed to the media plan — a
        // fingerprint change reallocates segments, so the sidecar (and the
        // staged segment stream under it) can never survive a plan change.
        try { File(base.absolutePath + ".hlsrs").delete() } catch (_: Exception) {}
        try { File(base.absolutePath + ".hts").delete() } catch (_: Exception) {}
        try {
            base.parentFile?.let {
                deletePartFragFiles(it, base.nameWithoutExtension, privateWorkdir = false)
            }
        } catch (_: Exception) {}
    }

    /**
     * A 429 is an explicit "come back later" — never a reason to destroy a
     * task. Matches the engine's own vocabulary: the validator rejection
     * ("Server rejected the link (HTTP 429) — ..."), the Turbo failure
     * message, and the aria2c/yt-dlp chains that quote the status.
     */
    private fun isRateLimitedError(msg: String?): Boolean =
        msg != null && (msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true))

    /**
     * User-facing error translation (AGENTS rule 14, 2026-10-20): the raw
     * `CanceledException` wrapper, the empty `ERROR: ` aggregate, and bare
     * cooldown class names leaked into task cards. Every branch below states
     * what happened and what the user can do — never a Java class name.
     */
    internal fun humanizeDownloadError(raw: String?): String {
        val msg = raw?.trim().orEmpty()
        if (msg.isBlank()) {
            return "The source server returned an empty response instead of a video. Tap retry to fetch a fresh link."
        }
        val lower = msg.lowercase()
        if (lower.contains("canceledexception") || lower.contains("backendinterrupted") ||
            (lower.contains("canceled") && lower.contains("yt-dlp"))
        ) {
            return "The source server stopped sending video data (the stream stalled). " +
                "Tap retry to fetch a fresh link, or choose another server/episode."
        }
        if (lower == "error:" || lower == "error" || lower.endsWith(": error:")) {
            // Truth preservation (charter 12.6, share-43 rows 8/32): show the
            // actual aggregate the backend printed instead of asserting
            // "empty response" — a blank yt-dlp ERROR is a missing-reason
            // symptom, and the wording must not mask a status-bearing fail.
            return "The source server returned a blank failure line (${msg.take(120)}) instead of a video. " +
                "Tap retry to fetch a fresh link."
        }
        if (lower.contains("origincooldown") || lower.contains("cooling down")) {
            return "The source server is rate-limiting downloads right now — it will retry automatically."
        }
        if (lower.contains("http 451") || lower.contains("unavailable for legal reasons")) {
            return "The source is not available from this network right now (HTTP 451). Try another site or server."
        }
        return msg
    }

    private fun parkFloorMs(cycles: Int): Long =
        (cycles.toLong() * PARK_REQUEUE_FLOOR_MS).coerceAtMost(PARK_REQUEUE_MAX_MS)

    /**
     * THE RULE (user, 2026-09-14): a download that has ever moved bytes may
     * never be FAILED by a host-side hiccup — rate-limit, stall, dead link,
     * backend teardown. It parks instead: PAUSED with the cooldown-loop
     * message, partial files untouched (Turbo's .part/.turbo sidecar and
     * aria2c's control file both resume from where they stopped), and the
     * existing auto-retry loop re-queues it when the host is usable, on a
     * per-cycle escalating floor. Tasks that never started (zero banked
     * bytes and no rate-limit signal) keep the honest-error behavior so a
     * permanently dead source cannot hold a queue slot hostage forever.
     *
     * Returns true when the task was parked (caller must NOT fail/notify).
     */
    private fun parkInsteadOfFail(task: DownloadTask, err: String, forcePark: Boolean): Boolean {
        val landed = bytesLanded(task)
        if (landed == 0L && !forcePark) return false
        val entry = runtimeRegistry.getOrCreate(task.id)
        val cycles = entry.parkCycleCount.incrementAndGet()
        val now = System.currentTimeMillis()
        entry.firstParkedAtMs.compareAndSet(0L, now)
        val floorMs = parkFloorMs(cycles)
        entry.requeueNotBeforeMs.set(now + floorMs)
        val mins = maxOf(1L, floorMs / 60_000L)
        val saved = if (landed > 0) " — ${(landed / (1024L * 1024L)).coerceAtLeast(1)} MB already saved, it will resume" else ""
        repository.update(task.id) {
            it.copy(
                status = TaskStatus.PAUSED,
                speedBytesPerSec = 0.0,
                errorMessage = "$PARKED_HOST_MESSAGE — will retry in ~${mins}m$saved"
            )
        }
        // RC-6: this is an ENGINE decision, never a user action — the `[USER]`
        // tag made every automatic park look like the owner tapped pause
        // (06_phase3_issue_sheet: parks logged as user actions). The task
        // state itself is authoritative; only the tag changes.
        com.anonrode.downloader.util.DebugLog.engine(
            // 300 chars: the reason now carries the backend's real diagnostic
            // line + HTTP status (truth preservation, share-43 rows 8/32) —
            // the old 140-char cut truncated the status off the park line.
            "park task=${task.id} (cycle=$cycles, landed=${landed / 1024}KiB, forced=$forcePark) reason=${err.take(300)}"
        )
        // RC-1 (fresh-source per retry, 2026-10-21): the f8cf38d2 class — the
        // Promised Neverland task parked on a dead stream-proxy URL, requeued,
        // and the resolve layer replayed the IDENTICAL URL from cache
        // ("stage=cache …/embed/tv/83097/1/1 result=OK ms=0"), re-parking on
        // the same poisoned endpoint forever. Once the failing chain has
        // thrown on the SAME directUrl twice, poison the URL (resolver-side
        // skip list) and clear the task's directUrl so the requeued job runs a
        // FRESH resolve instead of a cache replay. Banked bytes are untouched:
        // they resume on whatever new source the fresh resolve mints (the
        // failover-veto fingerprint already treats proven-unprogressable
        // sources as failover-eligible). Rate-limited paced-lane parks (RC-9)
        // intentionally bypass this: their whole design is resume on the SAME
        // URL after the quota window, and they never increment the same-source
        // failure count.
        val failedUrl = backendFailures[task.id]
        val currentUrl = task.directUrl.trim()
        if (failedUrl != null && failedUrl.count >= 2 && currentUrl.isNotBlank() &&
            failedUrl.url == currentUrl && !com.anonrode.downloader.pipeline.ResolveCache.isPoisoned(currentUrl)
        ) {
            com.anonrode.downloader.pipeline.ResolveCache.poison(currentUrl)
            repository.update(task.id) { it.copy(directUrl = "") }
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=${task.id} fresh-source retry: parked twice on the same URL — poisoning it and " +
                    "clearing directUrl so the requeue performs a fresh resolve"
            )
        }
        updateServiceState(force = true)
        return true
    }

    private fun checkStorageAvailable(): Boolean {
        try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(path.path)
            val freeGb = (stat.availableBlocksLong * stat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
            return freeGb >= storageGuardGb
        } catch (_: Exception) {
            return true
        }
    }

    private fun getDownloadDirectory(showTitle: String, createDirs: Boolean = false): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(root, "Anon")
        val dest = when {
            showTitle.startsWith("Social", ignoreCase = true) -> {
                val platform = showTitle.substringAfter("Social/", "Generic").trim()
                File(base, "Social/${sanitizeComponent(platform, 40)}")
            }
            showTitle.equals("Torrents", ignoreCase = true) -> File(base, "Torrents")
            autoOrganizeByShow && showTitle.isNotBlank() && showTitle != "Direct Downloads" -> {
                File(base, sanitizeComponent(showTitle, 60))
            }
            else -> base
        }
        if (dest.exists() && !dest.isDirectory) {
            try { dest.delete() } catch (_: Exception) {}
        }
        if (createDirs && !dest.exists()) {
            dest.mkdirs()
        }
        return dest
    }

    /**
     * Turn an arbitrary scraped title into a safe single filesystem
     * component. THE policy lives in util/NameSanitizer (the app-wide name
     * standard: noise-phrase removal, full entity decoding, native script
     * preserved, control/forbidden chars replaced, traversal/reserved-name/
     * trailing-dot guards, byte-aware cap). Kept as the engine's local
     * doorway so folder/stem/social call sites all share one entry.
     */
    private fun sanitizeComponent(
        raw: String, maxChars: Int, stripNoise: Boolean = true
    ): String = com.anonrode.downloader.util.NameSanitizer.savedName(raw, maxChars, stripNoise)

    private fun getRefererForUrl(url: String): String {
        // Single source of truth is now the OTA playbook (DynamicRulesManager:
        // hostPolicies first, built-in defaults second). The old per-host
        // when-map lived here and drifted from the monolith + probe copies;
        // its exact entries survive as the manager's DEFAULT_HOST_POLICIES.
        return com.anonrode.downloader.data.rules.DynamicRulesManager.resolveReferer(url)
    }

    private fun getUserAgentForUrl(url: String): String {
        val policyUa = com.anonrode.downloader.data.rules.DynamicRulesManager.resolveUserAgent(url)
        return if (policyUa.isNotBlank()) policyUa else com.anonrode.downloader.data.net.HttpClient.DEFAULT_UA
    }

    /**
     * Soft-subtitle sidecars for direct-source downloads (2026-10-20).
     *
     * Only tracks a provider/recipe PROVED for this episode (ResolvedHints)
     * are fetched. Files land next to the video as
     * `<stem>.<lang>.srt|.vtt|.ass` — exactly the discovery pattern the player
     * already scans (`<videoStem>*`). Every failure is swallowed and journaled:
     * a missing subtitle must never fail or delay a completed video.
     *
     * Runs at most 3 tracks, each capped at 512 KiB, and validates the body
     * shape (WEBVTT / SRT cue / ASS header) so an HTML error page is never
     * written as a subtitle.
     */
    private suspend fun downloadSubtitleSidecars(task: DownloadTask, video: File) {
        if (!downloadSubtitles || task.audioOnly) return
        val tracks = (com.anonrode.downloader.pipeline.ResolvedHints.subtitlesFor(task.sourceUrl) +
            com.anonrode.downloader.pipeline.ResolvedHints.subtitlesFor(task.directUrl) +
            com.anonrode.downloader.providers.SubtitleSurfaces.tracksFor(task.sourceUrl)
                .map { com.anonrode.downloader.data.models.SubtitleTrack(it.url, it.language, it.format) } +
            com.anonrode.downloader.providers.SubtitleSurfaces.tracksFor(task.directUrl)
                .map { com.anonrode.downloader.data.models.SubtitleTrack(it.url, it.language, it.format) })
            .filter { it.url.startsWith("http", ignoreCase = true) }
            .distinctBy { it.url.substringBefore('?').lowercase() }
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            val referer = com.anonrode.downloader.pipeline.ResolvedHints.refererFor(task.directUrl)
                ?: com.anonrode.downloader.pipeline.ResolvedHints.refererFor(task.sourceUrl)
                ?: getRefererForUrl(task.directUrl)
            for (track in tracks.take(3)) {
                try {
                    if (track.url.isBlank() || !track.url.startsWith("http", ignoreCase = true)) continue
                    val body = com.anonrode.downloader.data.net.HttpClient.get(
                        track.url,
                        referer = referer.takeIf { it.isNotBlank() },
                        tag = "subtitles"
                    ).use { res ->
                        if (!res.isSuccessful) null
                        else com.anonrode.downloader.data.net.HttpClient.cappedText(res, maxBytes = 512L * 1024L)
                    } ?: continue
                    val trimmed = body.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                    if (trimmed.startsWith("<html", ignoreCase = true) || trimmed.startsWith("<!doctype", ignoreCase = true)) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} subtitle rejected (HTML body) ${track.url.take(100)}"
                        )
                        continue
                    }
                    val kind = subtitleExtension(track.kind, track.url, trimmed)
                    // Engine/player alignment: only srt/vtt/ass sidecars are
                    // discoverable. Exotic timed-text is converted to SRT; an
                    // unparseable body is skipped with an explicit log rather
                    // than written under a lying extension.
                    val aligned = SubtitleArtifacts.alignForPlayer(trimmed, kind)
                    if (aligned == null) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} subtitle skipped: .$kind is not player-renderable and could not be converted ${track.url.take(100)}"
                        )
                        continue
                    }
                    val lang = sanitizeSubtitleLang(track.lang)
                    val dest = File(video.parentFile, "${video.nameWithoutExtension}.$lang.${aligned.extension}")
                    if (dest.exists() && dest.length() > 16L) continue
                    val tmp = File(video.parentFile, "${dest.name}.tmp")
                    tmp.writeText(aligned.body)
                    if (!tmp.renameTo(dest)) {
                        dest.writeText(aligned.body)
                        tmp.delete()
                    }
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} subtitle sidecar saved ${dest.name} (${dest.length()}B)"
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} subtitle sidecar failed (${e.javaClass.simpleName}) ${track.url.take(100)}"
                    )
                }
            }
        }
    }

    internal fun sanitizeSubtitleLang(raw: String): String {
        val clean = raw.trim().lowercase()
            .replace('_', '-')
            .replace(Regex("""[^a-z0-9-]"""), "")
            .trim('-')
            .take(16)
        return clean.ifBlank { "und" }
    }

    internal fun subtitleExtension(kindHint: String, url: String, body: String): String {
        val hint = kindHint.trim().lowercase().removePrefix(".")
        val known = SubtitleArtifacts.RENAMED_ARTIFACT_EXTS
        if (hint in known) return if (hint == "ssa") "ass" else hint
        val pathExt = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        if (pathExt in known) return if (pathExt == "ssa") "ass" else pathExt
        // Exotic timed-text bodies are identified explicitly: the old
        // body-sniff fallback labelled TTML/JSON3 as SRT, producing a sidecar
        // the player attached as SubRip but rendered with zero cues.
        SubtitleArtifacts.sniffExotic(body)?.let { return it }
        val head = body.take(4096)
        return when {
            head.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
            head.contains("[Script Info]", ignoreCase = true) || head.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
    }

    // Adaptive-streaming manifest detection (monolith is_streaming_link parity):
    // DASH/ISM/F4M manifests and player-token URLs must go to yt-dlp, or the
    // segmented downloader grabs manifest XML and produces a corrupt file.
    private fun isStreamingLink(url: String): Boolean =
        com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(url)

    companion object {
        // Anime providers consulted by the failover SECOND pass when the
        // regular Asian cluster produced no plausible title match (Servamp on
        // nepu, 2026-10-19). Their cards bypass the Layer-1 cluster filter
        // only for that pass.
        private val ANIME_SECOND_PASS_SITES: Set<String>
            get() = DynamicRulesManager.getFailoverClusters().animeSecondPass

        // URI="..." inside HLS tags (EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA) — the
        // URI value may be scheme-relative or relative and needs the same
        // absolute-https rewrite as segment URIs.
        private val URI_VALUE = Pattern.compile("URI=\"([^\"]+)\"")

        private val STREAMING_QUERY_PATTERN = Regex("""[?&][^=&]*=(?:mpd|dash|hls|m3u8)(?:&|$)""")

        // KNOWN_LOCKER_HOSTS + the isKnownLockerHost/isProvablyDirectFile
        // classifiers moved to pipeline.LinkResolver (2026-09-14) so the
        // search-verify oracle proves results with the SAME ladder downloads
        // use. The thin delegates below keep this file's ~20 call sites intact.

        // Stall-watchdog tuning constants live in ProgressWatchdog.kt (single source of truth).

        // Hard ceiling on the link-cracking phase. Without it a slow site kept
        // a task in RESOLVING forever while the user's mobile data trickled
        // away on retries.
        private const val RESOLVE_TIMEOUT_MS = 90_000L

        // Prefix of the errorMessage set when a task is parked because its
        // locker host is in HostHealth backoff ("cooling down"). The
        // auto-retry loop matches this prefix and re-queues the task once the
        // host is usable again.
        // internal (both markers were private): RestorePolicy must recognize
        // them at app-restore time — crushing them to a manual pause used to
        // strand every auto-parked download across a restart (share-43 §RC-1).
        internal const val PARKED_HOST_MESSAGE = "Download server cooling down"

        // errorMessage of a system network-flap park (silent: pauseForNetwork
        // does not log). The network observer requeues tasks carrying it on
        // the next connected emission; parkForRestore restores interrupted
        // tasks with it so they requeue instead of stranding.
        internal const val NETWORK_PAUSE_MESSAGE = "Waiting for network..."

        // A parked task may cycle QUEUED → RESOLVING → park at most this many
        // times before it is FAILED outright. Without a cap, a host that never
        // recovers keeps the batch bouncing every backoff window forever (the
        // Suits batch churned for 90 minutes on a host that was never coming
        // back). Generous: at 2 windows each, 6 attempts ≈ 12 minutes of real
        // chances before the task gives up with an honest error.
        private const val MAX_PARK_CYCLES = 6

        // How long ONE task may sit parked by the cooldown loop before the
        // loop stops probing it on the short escalating ladder and switches
        // to the long re-check floor (PARKED_RECHECK_FLOOR_MS). Measured from
        // the FIRST park (firstParkedAtMs uses compareAndSet), so repeated
        // re-parks cannot reset the clock. OWNER DIRECTIVE 2026-10-21: this
        // window no longer fails the task — a parked download retries until
        // the host returns or the title is proven no-source; the window only
        // stretches the probe cadence so a dead host cannot hold the queue.
        private const val PARKED_LIFETIME_MS = 30 * 60_000L

        // Long re-check cadence after PARKED_LIFETIME_MS with the host still
        // unreachable: the cooldown loop re-arms its floor to this so the
        // parked task gets an honest ~30-minute retry cadence instead of
        // every-10s persist churn, while the FIRST usable verdict requeues
        // immediately (recovery must never wait out the floor).
        private const val PARKED_RECHECK_FLOOR_MS = 30 * 60_000L

        // One-shot backoff stamped at boot for tasks RESTORED by parkForRestore
        // (interrupted by process death, or parked by a system family before
        // the restart): the network observer's requeue sweep honors it, so a
        // session opens without instantly re-consuming data (v3.0.4) but the
        // retry is guaranteed, not stranded. In-session parks keep a 0 floor
        // and recover on the first connect heartbeat as before.
        private const val RESTORED_REQUEUE_FLOOR_MS = 60_000L

        // Re-queue floor per park cycle: the 1st park waits 60s, the 2nd 2m,
        // ... capped at 15m. Rate-limits on these lockers are minutes-long
        // penalties, and every premature bounce re-triggers them (share-5
        // log: the 429 storm retried every 2-4 seconds, each retry extending
        // the block and burning data on failed resolves).
        private const val PARK_REQUEUE_FLOOR_MS = 60_000L
        private const val PARK_REQUEUE_MAX_MS = 15 * 60_000L
    }

    private var lastNotificationTime: Long = 0L
    private var lastNetworkTag: String? = null

    private fun updateServiceState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationTime < 1000L) {
            return
        }
        lastNotificationTime = now
        val current = tasks.value
        val active = current.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }
        if (active.isNotEmpty()) {
            val first = active.first()
            val pct = if (first.totalBytes > 0) (first.downloadedBytes * 100 / first.totalBytes).toInt().coerceIn(0, 100) else 0
            // Board row 4: the notification is the card's SECOND face, not a
            // second pipeline. Bind the same repository-windowed value the
            // card prints; the raw backend speed is only the fallback while
            // the window warms up (~3 s) or while the backend reports none.
            val speed = com.anonrode.downloader.service.notificationSpeedBytesPerSec(
                first.displaySpeedBytesPerSec,
                first.speedBytesPerSec
            )
            DownloadService.updateProgress(
                context,
                title = com.anonrode.downloader.service.buildOngoingNotificationTitle(first.episodeTitle, speed),
                progress = pct,
                activeCount = active.size
            )
        } else {
            DownloadService.stop(context)
        }
    }

    /** Transfers competing for the lane pool right now (this one included). */
    private fun plannedTransferCount(): Int =
        tasks.value.count {
            it.status == TaskStatus.QUEUED ||
                it.status == TaskStatus.RESOLVING ||
                it.status == TaskStatus.DOWNLOADING ||
                it.status == TaskStatus.VALIDATING
        }.coerceAtLeast(1)

    @Synchronized
    private fun processQueue() {
        val net = networkObserver.getCurrentStatus()
        if (!net.isConnected) return

        // Fill EVERY free slot in one pass, not just one: the reconnect /
        // storage-self-heal / cooldown loops re-queue many parked tasks and
        // then call processQueue() exactly once, so single-step starts left
        // maxConcurrentDownloads-1 slots empty until the next completion.
        while (true) {
            val currentTasks = tasks.value
            // Two counters, and the LIMIT uses the LARGER of the two:
            //  - status count: what the repository claims (DOWNLOADING/RESOLVING/VALIDATING)
            //  - live-job count: coroutines actually running in runtimeRegistry
            // The status count alone missed tasks whose status was written by a
            // path outside this scheduler (watchdog restart, post-pause zombie
            // writes) — the live-job count alone misses a task between its
            // status flip and its job registration. Taking the max closes the
            // window both ways: this is THE concurrency choke-point, and no
            // other code may start a backend outside it.
            val activeIds = buildSet {
                currentTasks.filter {
                    it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
                }.forEach { add(it.id) }
                addAll(runtimeRegistry.activeRunningTaskIds())
            }
            val activeCount = activeIds.size

            if (activeCount >= maxConcurrentDownloads) return

            // Never start a task the user explicitly paused (engine-audit P1:
            // no legitimate state is QUEUED + userPaused — only retry()/
            // resumeAll() clear the flag, and they set QUEUED themselves, so
            // this predicate can only ever reject a pause that raced a requeue).
            // Never pick a task the runtime registry still owns: a failover flips a
            // task QUEUED milliseconds before its dying coroutine's finally runs, and
            // starting it here would flip it to RESOLVING/DOWNLOADING only to lose
            // the start-lock race and strand it (audit: queue↔start race).
            val nextTask = currentTasks.firstOrNull {
                it.status == TaskStatus.QUEUED && !it.userPaused && it.id !in activeIds
            } ?: return

            if (!checkStorageAvailable()) {
                // Park every queued task, not just the head, so the whole queue drains to PAUSED
                // in one pass instead of stalling on a single task per processQueue call.
                currentTasks.filter { it.status == TaskStatus.QUEUED }.forEach { queued ->
                    repository.update(queued.id) { t -> t.copy(status = TaskStatus.PAUSED, errorMessage = "Storage limit reached (< ${storageGuardGb}GB free)") }
                }
                return
            }

            if (wifiOnlyTorrents && !net.isWifi && nextTask.directUrl.startsWith("magnet:")) {
                repository.update(nextTask.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)") }
                // Continue instead of return: a gated magnet must not block
                // non-torrent HTTP tasks queued behind it from starting.
                continue
            }
            // Wi-Fi-only for ALL downloads (not just torrents)
            if (wifiOnlyAll && !net.isWifi) {
                currentTasks.filter { it.status == TaskStatus.QUEUED && !it.userPaused }.forEach { queued ->
                    repository.update(queued.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Wi-Fi Only enabled)") }
                }
                return
            }

            // Global admission control (2026-10-19): a new HTTP task may only
            // start when the lane pool can guarantee its 4-lane minimum.
            // Magnet/torrent tasks do not consume HTTP lanes. Admitted starts
            // are staggered 1.5s so four tasks cannot mint 16 connections at
            // the same instant.
            if (!nextTask.directUrl.startsWith("magnet:", ignoreCase = true)) {
                val laneBudget = com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                if (!laneBudget.hasMinimumCapacity()) {
                    com.anonrode.downloader.util.DebugLog.engine(
                        "queue admission deferred task=${nextTask.id} — ${laneBudget.snapshot()}"
                    )
                    return
                }
                val since = laneBudget.msSinceAdmission()
                if (since < 1_500L) {
                    engineScope.launch {
                        delay(1_500L - since)
                        processQueue()
                    }
                    return
                }
                laneBudget.noteAdmission()
            }

            // Status may only be flipped once the start lock is held, so no queue
            // path can strand a task in RESOLVING/DOWNLOADING without a coroutine.
            if (!runtimeRegistry.tryAcquireStartLock(nextTask.id)) return

            val isDirect = isDirectMediaUrl(nextTask.directUrl) && !isKnownLockerHost(nextTask.directUrl)
            val initialStatus = if (isDirect) TaskStatus.DOWNLOADING else TaskStatus.RESOLVING
            // Conditional flip: a pause landing between the selection and this
            // write must not be overwritten (a paused row is never QUEUED).
            repository.update(nextTask.id) { t ->
                if (t.userPaused || t.status != TaskStatus.QUEUED) t else t.copy(status = initialStatus)
            }
            updateServiceState(force = true)

            startTask(nextTask, lockAlreadyHeld = true)
            // Loop on: the status flip above is synchronous on the StateFlow,
            // so the next iteration counts this task as active and picks the
            // following QUEUED one until all slots are full.
        }
    }

    /** Moved to pipeline.LinkResolver (2026-09-14, oracle round); delegates
     *  here so the routing/startTask call sites below are untouched. */
    private fun isProvablyDirectFile(url: String): Boolean =
        com.anonrode.downloader.pipeline.LinkResolver.isProvablyDirectFile(url)

    private fun isKnownLockerHost(url: String): Boolean =
        com.anonrode.downloader.pipeline.LinkResolver.isKnownLockerHost(url)

    /** Moved verbatim to pipeline.LinkResolver.resolveChain (2026-09-14,
     *  oracle round). allowCacheHit=false preserves this caller's documented
     *  semantic: "fetch a FRESH link" — invalidate-first, never serve a
     *  cached (possibly dead-token) URL on the refresh path. All four
     *  call sites (startTask + the 401/403/404 self-heal re-resolves) get
     *  identical behavior to before the move.
     *
     *  [memoTaskId] is NON-NULL only on the resolve-phase call in startTask:
     *  it arms the per-task dead-URL memory (engine.ResolveBudget) + per-
     *  mirror attempt budgets for the engine-driven walk. The mid-download
     *  self-heal re-resolves deliberately stay memo-less — a 403-refresh
     *  re-mints a rotating token and must never be refused by a stale mark.
     *  (Attempt budgets still apply registry-wide, self-heal included:
     *  a hung refresh is clipped and moves on, while a healthy refresh on
     *  a proven-history host keeps the generous 40s cap. Budget expiry is
     *  a discarded result inside the chain — it never cancels a task.) */
    private suspend fun resolveStreamUrl(
        permUrl: String,
        site: String,
        defaultQual: String,
        bypassHealth: Boolean = false,
        allowCacheHit: Boolean = false,
        memoTaskId: String? = null
    ): String? =
        com.anonrode.downloader.pipeline.LinkResolver.resolveChain(
            permUrl, site, defaultQual, bypassHealth = bypassHealth, allowCacheHit = allowCacheHit,
            memoTaskId = memoTaskId
        )

    /**
     * Thrown by [preflightHls] when the CDN rejects the playlist with 401/403:
     * the token stamped into the persisted URL has expired or been invalidated.
     * The engine answers by re-resolving from the source page (fresh token)
     * instead of failing — a persisted HLS URL must never be trusted twice.
     */
    private class StaleStreamLinkException(message: String) : Exception(message)

    /**
     * HLS pre-flight: fetch the master playlist, probe the first segment host,
     * and — when the playlist carries scheme-relative (//cdn/...) or relative
     * segment URIs — rewrite them to absolute https into a local file that
     * yt-dlp consumes instead of the original URL.
     *
     * Why the rewrite is load-bearing: yt-dlp joins a scheme-relative segment
     * URI to http:// (port 80) while browsers (hls.js) use https:// — these
     * CDNs answer 443 and hang port 80. Live-verified: the same vidbasic
     * master stalled forever at port 80 and downloaded from a rewritten
     * https master. A dead segment CDN additionally used to pin the task in
     * DOWNLOADING at 0 bytes for minutes (watchdog kills + 3 attempts =
     * user-reported "stuck on starting"); the probe turns that into a clean
     * failure in seconds.
     *
     * Returns the rewritten master file (null = original URL is fine), and
     * throws a user-facing message when the stream is unreachable.
     *
     * Also runs the Segment-Sampling Estimator (measured segment sizes, not
     * BANDWIDTH tags) and exposes the predicted total through
     * [estimatedTotalBytes] — a one-element out-holder (0 = no estimate) —
     * for future progress plumbing. The estimate is diagnostic-only: its
     * every failure logs hls-size-estimate=null and never disturbs the
     * preflight outcome.
     */
    private suspend fun preflightHls(
        context: Context,
        taskId: String,
        masterUrl: String,
        referer: String?,
        estimatedTotalBytes: LongArray = LongArray(1),
        requestedQuality: String? = null,
        resolutionOut: Array<String?> = arrayOfNulls(1),
        segmentUrlOut: Array<String?> = arrayOfNulls(1),
        fingerprintOut: Array<String?> = arrayOfNulls(1),
        preflightedMasterOut: Array<String?> = arrayOfNulls(1),
        preflightedVariantOut: Array<String?> = arrayOfNulls(1),
        preflightedVariantUrlOut: Array<String?> = arrayOfNulls(1)
    ): File? {
        val cacheDir = File(context.cacheDir, "hls").apply { mkdirs() }
        val created = mutableListOf<File>()
        var currentUrl = masterUrl
        var playlist: String? = null
        var staleToken = false
        try {
            HttpClient.get(currentUrl, referer = referer, tag = "preflight").use { res ->
                when {
                    // 401/403/451 on the master means the token stamped into the
                    // persisted URL is dead — a stale link, not a dead server.
                    // The engine re-resolves from the source page for a fresh one.
                    res.code == 401 || res.code == 403 || res.code == 451 -> staleToken = true
                    res.isSuccessful -> {
                        val contentType = res.header("Content-Type").orEmpty().lowercase()
                        val text = HttpClient.cappedText(res)
                        if (text == null) {
                            staleToken = true
                        } else {
                            val trimmed = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                            // Preflight passthrough endpoints (e.g. megaplays display-ads playlist)
                            // are handed to yt-dlp verbatim (live-proven working): their body
                            // is not a plain #EXTM3U document, so the HLS-shape gate must not reject it.
                            val isPreflightPassthrough = DynamicRulesManager.isPreflightPassthrough(currentUrl)
                            val declaresHls = currentUrl.contains(".m3u8", true) ||
                                currentUrl.contains(".m3u", true) ||
                                currentUrl.contains(".mpegurl", true) ||
                                contentType.contains("mpegurl")
                            val html = looksLikeHtml(text) ||
                                text.contains("<!doctype", ignoreCase = true) ||
                                text.contains("<html", ignoreCase = true)
                            when {
                                isPreflightPassthrough -> playlist = text
                                // A 1-byte (or near-empty) body is the known
                                // vidrift/terminal stub, never a playlist.
                                trimmed.length < 7 -> staleToken = true
                                // mpegurl content MUST be an M3U document; a
                                // JSON error body or anti-bot page is a stale
                                // link, not a stream.
                                declaresHls && !trimmed.startsWith("#EXTM3U") -> staleToken = true
                                html -> staleToken = true
                                else -> playlist = text
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        if (staleToken) throw StaleStreamLinkException("Stream link expired — fetching a fresh one")
        val playlistText: String = playlist
            ?: throw Exception("Stream server unreachable — the playlist did not load. Try again later.")
        preflightedMasterOut[0] = playlistText
        // Live-by-default: only upgrade absolute http->https if the https
        // twin actually answers. A blind upgrade breaks http-only CDNs.
        // Find ANY absolute http URI (segment or tag attribute), not just the
        // first media line: mixed playlists list an https variant first and
        // still carry http segments.
        val upgradeInsecureHttp = shouldUpgradeInsecureHttp(playlistText, currentUrl, referer)
        if (upgradeInsecureHttp) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=$taskId preflight: https twin answered, upgrading absolute http URIs"
            )
        }
        var masterFile = rewriteHlsMaster(
            playlistText, currentUrl, "hls-$taskId.m3u8", cacheDir, upgradeInsecureHttp
        )
        if (masterFile != null) {
            created.add(masterFile)
            // What yt-dlp is actually fed — the 0-byte HLS stall was
            // undiagnosable without seeing the rewritten playlist.
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=$taskId rewritten master: ${masterFile.readText().take(300).replace("\n", " | ")}"
            )
        }

        // The master's RESOLUTION attributes are the REAL stream quality —
        // a requested "720p" can resolve to 1080p if that's all the host
        // offers. Mirror yt-dlp's height-limited format selection: the
        // highest variant at or below the requested height (or the smallest
        // when nothing fits). Exposed for the download card's quality chip.
        resolutionOut[0] = pickHlsResolution(playlistText, requestedQuality)

        // RC-5 (honest subtitle verdict, 2026-10-21): every NEPU-family master
        // so far carries NO subtitle media (nsrplay resolve JSON returns
        // `subtitles: null`; 0 subtitle events across every capture). Log the
        // verdict ONCE per preflight so "no subtitles" is diagnosable from the
        // activity log instead of silent.
        com.anonrode.downloader.util.DebugLog.resolve(
            if (playlistText.contains("#EXT-X-MEDIA:TYPE=SUBTITLES", ignoreCase = true))
                "task=$taskId hls master declares EXT-X-MEDIA:TYPE=SUBTITLES — subtitle track(s) will be fetched"
            else
                "task=$taskId hls master has no subtitle media — this source offers no subtitle track"
        )

        // Walk one variant level: rewrite referenced variant playlists to
        // local files too and repoint the master at them (relative name),
        // so their segments also go over https.
        val mediaLines = playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        var probeUrl: String? = null
        val planTexts = mutableListOf<String>()
        var variantsComplete = true
        if (mediaLines.isNotEmpty()) {
            var firstResolved = resolveSegmentUrl(currentUrl, mediaLines[0])
            if (firstResolved != null && firstResolved.contains(".m3u8")) {
                // M3U8-3: Only fetch/probe the single variant matching requestedQuality
                val targetHeight = (requestedQuality ?: "720p").filter { it.isDigit() }.toIntOrNull() ?: 720
                val chosenVariantUrl = HlsVodDownloader.parseMaster(playlistText, currentUrl, targetHeight)
                val chosenLine = mediaLines.firstOrNull { resolveSegmentUrl(currentUrl, it) == chosenVariantUrl } ?: mediaLines[0]
                val chosenIdx = mediaLines.indexOf(chosenLine).coerceAtLeast(0)
                val vUrl = resolveSegmentUrl(currentUrl, chosenLine) ?: firstResolved
                preflightedVariantUrlOut[0] = vUrl

                var variant: String? = null
                var variantStale = false
                try {
                    HttpClient.get(vUrl, referer = referer, tag = "preflight").use { res ->
                        when {
                            res.code == 401 || res.code == 403 || res.code == 451 -> variantStale = true
                            res.isSuccessful -> variant = HttpClient.cappedText(res)
                        }
                    }
                } catch (_: Exception) {}

                if (variantStale) throw StaleStreamLinkException("Stream link expired — fetching a fresh one")
                if (variant != null) {
                    preflightedVariantOut[0] = variant
                    planTexts.add(variant)
                    val variantUpgrade = upgradeInsecureHttp ||
                        shouldUpgradeInsecureHttp(variant, vUrl, referer)
                    val vFile = rewriteHlsMaster(
                        variant, vUrl, "hls-$taskId-v$chosenIdx.m3u8", cacheDir, variantUpgrade
                    )
                    if (vFile != null) created.add(vFile)
                    if (vFile != null && masterFile == null) {
                        masterFile = try {
                            val f = File(cacheDir, "hls-$taskId.m3u8")
                            f.writeText(
                                HlsPlaylist.rewrite(
                                    playlistText, currentUrl, upgradeInsecureHttp, forceLocal = true
                                ) ?: playlistText
                            )
                            f
                        } catch (_: Exception) {
                            null
                        }
                        if (masterFile != null) created.add(masterFile)
                    }
                    if (vFile != null && masterFile != null) {
                        try {
                            masterFile.writeText(
                                masterFile.readText().replace(vUrl, vFile.name)
                            )
                        } catch (_: Exception) {}
                    }
                    val vSeg = variant.lineSequence().map { it.trim() }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (vSeg != null) probeUrl = resolveSegmentUrl(vUrl, vSeg)
                    // M3U8-4: Segment-Sampling Estimator: runs asynchronously in background,
                    // passing the already-fetched variant text!
                    triggerAsyncHlsSizeEstimate(taskId, playlistText, currentUrl, vUrl, referer, variant)
                } else {
                    variantsComplete = false
                }
            } else {
                probeUrl = firstResolved
                preflightedVariantUrlOut[0] = currentUrl
                preflightedVariantOut[0] = playlistText
                // The URL is a media playlist itself (single-variant stream):
                triggerAsyncHlsSizeEstimate(taskId, playlistText, currentUrl, currentUrl, referer, playlistText)
            }
        }

        // Always materialize a local master for a multi-variant playlist, even
        // when every URI is already absolute and no rewrite was needed. yt-dlp
        // needs the local .m3u8 input both to classify the bridge URL as HLS
        // and for [YoutubeDlDownloader.masterDeclaresSeparateAudio] to see the
        // EXT-X-MEDIA audio rendition (vidbolt bridge, 2026-10-22).
        if (masterFile == null && playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            masterFile = try {
                val f = File(cacheDir, "hls-$taskId.m3u8")
                f.writeText(
                    HlsPlaylist.rewrite(playlistText, currentUrl, upgradeInsecureHttp, forceLocal = true)
                        ?: playlistText
                )
                f
            } catch (_: Exception) {
                null
            }
            if (masterFile != null) created.add(masterFile)
        }

        // Fingerprint the media plan BEFORE any probe failure can throw: the
        // caller uses it to decide whether the banked .part belongs to this
        // plan. It is structural (basenames/durations), so rotating tokens do
        // not look like a different plan.
        if (upgradeInsecureHttp && probeUrl != null && probeUrl.startsWith("http://")) {
            probeUrl = "https://" + probeUrl.removePrefix("http://")
        }
        segmentUrlOut[0] = probeUrl
        // A partial variant set (one fetch failed) must NOT be hashed: that
        // would look like a plan change and purge a healthy .part.
        fingerprintOut[0] = if (variantsComplete) {
            HlsPlaylist.fingerprint(
                buildList {
                    add(playlistText)
                    addAll(planTexts)
                }
            )
        } else null

        // Probe the segment host exactly as yt-dlp will reach it after the
        // rewrite — https first, http only as a fallback for https-dead CDNs.
        if (probeUrl != null) {
            if (!HttpClient.probe(probeUrl, referer, timeoutMs = 2_500L, tag = "preflight")) {
                val httpVariant = if (probeUrl.startsWith("https:")) {
                    "http:" + probeUrl.substringAfter("https:")
                } else null
                if (httpVariant == null || !HttpClient.probe(httpVariant, referer, timeoutMs = 2_500L, tag = "preflight")) {
                    val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(probeUrl)
                    for (f in created) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                    throw Exception("Stream CDN not responding ($host) — the source server is down or blocking this network. Try again later.")
                }
            }
        }
        return masterFile
    }

    /**
     * Segment-Sampling Estimator hook: probe a few real segments and log the
     * HLS size estimate (hls-size-estimate=...). Purely diagnostic — every
     * failure yields null and never disturbs preflightHls' outcome.
     */
    /**
     * Best-effort pick of the variant height a download will land on, from
     * the master playlist's EXT-X-STREAM-INF RESOLUTION attributes. Mirrors
     * yt-dlp's height-limited format selection (highest <= requested;
     * smallest if nothing fits). Null when the master declares no heights.
     */
    private fun pickHlsResolution(masterText: String, requestedQuality: String?): String? {
        val heights = mutableListOf<Int>()
        val re = Pattern.compile("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=\d+x(\d+)""")
        val m = re.matcher(masterText)
        while (m.find()) heights.add(m.group(1).toInt())
        if (heights.isEmpty()) return null
        val requested = requestedQuality?.filter { it.isDigit() }?.toIntOrNull()
        val chosen = when {
            requested == null -> heights.maxOrNull() ?: return null
            else -> heights.filter { it <= requested }.maxOrNull() ?: heights.min()
        }
        return "${chosen}p"
    }

    private suspend fun runSizeEstimate(
        taskId: String,
        masterText: String,
        masterUrl: String,
        variantUrl: String,
        referer: String?,
        estimatedTotalBytes: LongArray
    ) {
        val estimate = try {
            HlsSizeEstimator.estimate(masterText, masterUrl, variantUrl, referer)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        estimatedTotalBytes[0] = estimate ?: 0L
        com.anonrode.downloader.util.DebugLog.resolve(
            if (estimate != null) {
                "task=$taskId hls-size-estimate=$estimate (${estimate / (1024 * 1024)} MiB)"
            } else {
                "task=$taskId hls-size-estimate=null (no estimate)"
            }
        )
    }

    private fun triggerAsyncHlsSizeEstimate(
        taskId: String,
        masterText: String,
        masterUrl: String,
        variantUrl: String,
        referer: String?,
        prefetchedVariantText: String? = null
    ) {
        engineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val estimate = try {
                HlsSizeEstimator.estimate(masterText, masterUrl, variantUrl, referer, prefetchedVariantText)
            } catch (_: kotlinx.coroutines.CancellationException) {
                null
            } catch (_: Exception) {
                null
            }
            if (estimate != null && estimate > 0L) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "task=$taskId async hls-size-estimate=$estimate (${estimate / (1024 * 1024)} MiB)"
                )
                val live = repository.find(taskId)
                if (live != null && live.status == TaskStatus.DOWNLOADING && live.totalBytes <= 0L) {
                    repository.updateProgress(
                        taskId = taskId,
                        downloaded = live.downloadedBytes,
                        total = estimate,
                        speed = live.speedBytesPerSec,
                        eta = live.etaSeconds
                    )
                }
            }
        }
    }

    /**
     * Rewrite a playlist's scheme-relative (//host/...) and relative segment
     * URIs to absolute https. Returns the rewritten file, or null when the
     * playlist already uses absolute URLs (nothing to fix).
     */
    /** First absolute http URI in a playlist (segment line or tag URI="..."). */
    private fun firstInsecureUri(text: String, baseUrl: String): String? {
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val m = URI_VALUE.matcher(line)
                while (m.find()) {
                    val u = m.group(1) ?: ""
                    val resolved = resolveSegmentUrl(baseUrl, u) ?: u
                    if (resolved.startsWith("http://")) return resolved
                }
            } else {
                val resolved = resolveSegmentUrl(baseUrl, line) ?: line
                if (resolved.startsWith("http://")) return resolved
            }
        }
        return null
    }

    /** Upgrade http->https only when the https twin actually answers. */
    private suspend fun shouldUpgradeInsecureHttp(
        text: String,
        baseUrl: String,
        referer: String?
    ): Boolean {
        val insecure = firstInsecureUri(text, baseUrl) ?: return false
        val httpsTwin = "https://" + insecure.removePrefix("http://")
        return try {
            HttpClient.probe(httpsTwin, referer, timeoutMs = 2_500L, tag = "preflight")
        } catch (_: Exception) {
            false
        }
    }

    private fun rewriteHlsMaster(
        playlist: String,
        baseUrl: String,
        outName: String,
        dir: File,
        upgradeInsecureHttp: Boolean = true
    ): File? {
        val rewritten = HlsPlaylist.rewrite(playlist, baseUrl, upgradeInsecureHttp) ?: return null
        return try {
            val f = File(dir, outName)
            f.writeText(rewritten)
            f
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve a playlist-relative or protocol-relative URI against [base]. */
    private fun resolveSegmentUrl(base: String, uri: String): String? =
        HlsPlaylist.resolveSegmentUrl(base, uri)

    /**
     * Size of the produced artifact. A single-file download is a plain File,
     * but a multi-file torrent (season pack) lands as a DIRECTORY whose
     * File.length() is ~0 (or a block size) — the completion gate must sum
     * the tree instead of trusting the directory's own stat size.
     */
    private fun fileSize(file: File): Long {
        return if (file.isFile) file.length()
        else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private suspend fun handleWatchdogStall(
        taskId: String,
        kills: Int,
        exhausted: Boolean,
        stallMsg: String,
        t: DownloadTask
    ) {
        android.util.Log.w("AnonDownload", "No download progress for ${t.episodeTitle}, (stall kill $kills)")
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        if (exhausted) {
            com.anonrode.downloader.util.RemoteLogger.report(
                type = "stall_watchdog_timeout",
                message = stallMsg,
                context = mapOf(
                    "episode" to t.episodeTitle,
                    "backend" to t.backend,
                    "site" to t.site,
                    "host" to (try { java.net.URI(t.directUrl).host } catch (_: Exception) { null }),
                    "attempts" to kills,
                    "downloaded_bytes" to t.downloadedBytes,
                    "total_bytes" to t.totalBytes
                )
            )
            // A user pause racing this kill already wrote its own terminal state —
            // never overwrite it with park/fail or a failure notification.
            val liveRow = repository.find(taskId)
            if (liveRow == null || liveRow.userPaused) {
                runtimeRegistry.getActiveJob(taskId)?.cancel()
                return
            }
            // A backend ladder was killed on this URL. Remember it so a
            // banked partial from the SAME dead source can still escape via
            // cross-provider failover.
            noteBackendFailure(t, t.directUrl.ifBlank { t.sourceUrl })
            if (t.downloadedBytes == 0L && bytesLanded(t) == 0L && attemptCrossProviderFailover(t)) {
                runtimeRegistry.getActiveJob(taskId)?.cancel()
                return
            }
            var failedTerminal = false
            if (!parkInsteadOfFail(t, stallMsg, forcePark = isRateLimitedError(stallMsg))) {
                repository.update(taskId) {
                    it.copy(status = TaskStatus.FAILED, errorMessage = stallMsg)
                }
                com.anonrode.downloader.service.DownloadService.notifyFailed(context, taskId, t.episodeTitle, stallMsg)
                failedTerminal = true
            }
            val dyingJob = runtimeRegistry.getActiveJob(taskId)
            dyingJob?.cancel()
            // Watchdog-exhausted FAILED is terminal: drop the registry entry
            // (same rule as the COMPLETED path). Runs AFTER the cancel so the
            // dying job stays reachable, and never drops an entry a newer job
            // (a retry racing this kill) has taken over.
            if (failedTerminal) {
                val reg = runtimeRegistry.get(taskId)
                if (reg == null || reg.activeJob == null || reg.activeJob === dyingJob) runtimeRegistry.forget(taskId)
            }
        } else {
            // Preserve park counters across stall kills: markForManualRetry's
            // resetParkingAndFailoverState would wipe parkCycleCount/firstParkedAtMs
            // and defeat the anti-loop parking budget. Grant the bypass only.
            runtimeRegistry.grantRetryBypass(taskId)
        }
    }

    /**
     * Safely converts a TurboDownloader `.part` + `.turbo` pair into a contiguous prefix
     * file at [dest] so native `aria2c -c` can resume from the exact byte offset without
     * extension mismatches or orphaned files.
     */
    private fun handoffTurboPrefixToAria2c(dest: File) {
        try {
            val partFile = File(dest.absolutePath + ".part")
            val turboFile = File(dest.absolutePath + ".turbo")
            val ariaControl = File(dest.absolutePath + ".aria2")
            if (!partFile.exists()) return

            val prefix = if (turboFile.exists()) {
                TurboState(turboFile).contiguousPrefixBytes() ?: 0L
            } else {
                partFile.length()
            }

            if (prefix > 0L && prefix <= partFile.length() && !ariaControl.exists()) {
                // A stale dest must not block the prefix handoff — but delete it
                // ONLY when smaller than the prefix being placed, so a finished
                // file can never be destroyed by a stale .part (O2 hardening).
                if (dest.exists() && dest.length() < prefix) dest.delete()
                if (!dest.exists() && partFile.renameTo(dest)) {
                    RandomAccessFile(dest, "rw").use { it.setLength(prefix) }
                } else {
                    partFile.delete()
                }
            } else if (prefix == 0L) {
                partFile.delete()
            }
            // aria2c owns the transfer now: a stranded .turbo would shadow the
            // .aria2 progress read (O2b). Delete it whenever .part is gone.
            if (!partFile.exists() && turboFile.exists()) TurboState(turboFile).delete()
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.error("handoffTurboPrefixToAria2c failed: ${e.message}")
        }
    }

    /**
     * Non-suspending monitor helper. The giant startTask coroutine must not
     * contain a synchronized block: Kotlin 2.x's coroutine codegen
     * stack-overflows in checkForSuspensionPointInsideMonitor on that CFG
     * (observed on CI 2026-10-20, DownloadEngine.startTask invokeSuspend).
     */
    private fun adoptExtensionPath(taskId: String, updatedFile: File): Boolean = synchronized(enqueueLock) {
        val pathTaken = repository.snapshot().any { it.id != taskId && it.filePath == updatedFile.absolutePath }
        if (!pathTaken && !updatedFile.exists()) {
            repository.update(taskId) { it.copy(filePath = updatedFile.absolutePath) }
            true
        } else false
    }

    private fun startTask(initialTask: DownloadTask, lockAlreadyHeld: Boolean = false) {
        if (!lockAlreadyHeld && !runtimeRegistry.tryAcquireStartLock(initialTask.id)) return
        val live = repository.find(initialTask.id)
        if (live == null || live.status !in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING)) {
            // No job will ever attach to this entry, so forget it instead of
            // leaving a dead key behind (release-only left one per guarded
            // start). The start lock is exclusively ours here; a later retry
            // re-creates the entry via getOrCreate.
            runtimeRegistry.forget(initialTask.id)
            return
        }
        com.anonrode.downloader.util.DebugLog.write("start task=${initialTask.id} url=${initialTask.directUrl.take(80)} backend=${initialTask.backend}")

        val job = engineScope.launch(start = CoroutineStart.LAZY) {
            var watchdogJob: Job? = null
            // Re-read the task row: the enqueue/extension-sync paths may have moved
            // filePath while this task waited in the queue, and every later decision
            // (dest name, rename guard, sidecar cleanup) must use the current row.
            var task = repository.find(initialTask.id) ?: initialTask
            val originalFilePath = initialTask.filePath
            // Declared outside try so the finally can release the reservation.
            var grantedLanes = 0
            // HLS fallback width: the paced lane is single-connection, so the
            // initial reservation is 1 and grows only if yt-dlp takes over.
            var hlsFallbackLanes = 0
            try {
                if (!isActive) return@launch
                var streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(task.directUrl)
                val isMagnet = streamUrl.startsWith("magnet:", ignoreCase = true)
                val isSocial = task.showTitle.startsWith("Social/", ignoreCase = true) || task.backend.contains("yt-dlp")
                var permUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(
                    task.selectedMirrorUrl.ifBlank { task.sourceUrl }.ifBlank { streamUrl }
                )

                val bypassHealth = runtimeRegistry.consumeRetryBypass(task.id)
                if (bypassHealth) ResolveBudget.forgetTask(task.id)

                // Zero-Latency Resume: Check if streamUrl is already direct.
                // Adaptive-stream manifests are NEVER "already direct": a
                // persisted .m3u8 carries a token that dies within hours, and
                // skipping the source-page re-resolve made retries/re-resumes
                // loop on the dead token (2026-10-19 task c6710cfb). A
                // site-less HLS URL has nothing to resolve FROM, so it keeps
                // the direct path.
                val isHlsNow = com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)
                val isAlreadyDirect = !isHlsNow &&
                    isDirectMediaUrl(streamUrl) && !isKnownLockerHost(streamUrl)
                val hlsResolveImpossible = isHlsNow && task.site.isBlank()

                if (!isMagnet && !isSocial && !isAlreadyDirect && !hlsResolveImpossible) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState(force = true)

                    // D-α fix (2026-10-20): a 90s resolve timeout must not park
                    // a task that is actively downloading. If real bytes are
                    // banked on a RESUMABLE terminal, keep it and continue;
                    // failover is allowed when that source is proven
                    // unprogressable (>=3 ladder failures / poisoned URL).
                    var keepBankedUrl = false
                    val resolved = try {
                        kotlinx.coroutines.withTimeout(RESOLVE_TIMEOUT_MS) {
                            val candidates = if (task.mirrorUrls.isEmpty()) listOf(permUrl) else
                                MirrorPool.eligible(task.sourceUrl, task.mirrorUrls, permUrl, bytesLanded(task) > 0L)
                            val ordered = ResolveBudget.orderCandidates(candidates)
                            var result: String? = null
                            for (candidate in ordered) {
                                coroutineContext.ensureActive()
                                val output = try {
                                    resolveStreamUrl(candidate, task.site, task.quality ?: defaultQuality, bypassHealth = bypassHealth, allowCacheHit = true, memoTaskId = task.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) { null }
                                if (!output.isNullOrBlank()) {
                                    ResolveBudget.recordHostOk(candidate)
                                    permUrl = candidate
                                    if (task.mirrorUrls.isNotEmpty()) repository.update(task.id) {
                                        it.copy(selectedMirrorUrl = candidate)
                                    }
                                    result = output
                                    break
                                } else {
                                    ResolveBudget.recordHostFullPassFail(candidate)
                                }
                            }
                            result
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        com.anonrode.downloader.util.DebugLog.error("task=${task.id} resolution timed out after ${RESOLVE_TIMEOUT_MS / 1000}s")
                        noteBackendFailure(task, task.directUrl.ifBlank { streamUrl })
                        if (attemptCrossProviderFailover(task)) return@launch
                        val landed = bytesLanded(task)
                        val resumable = streamUrl.isNotBlank() && streamUrl != task.sourceUrl &&
                            (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || isHlsNow)
                        if (landed > 0L && resumable && !bankedSourceUnprogressable(task)) {
                            com.anonrode.downloader.util.DebugLog.resolve(
                                "task=${task.id} resolution timed out but $landed banked bytes are on a resumable terminal — continuing instead of parking"
                            )
                            keepBankedUrl = true
                            null
                        } else {
                            throw Exception("Link resolution timed out — the site took too long to answer. Retry, or try another server/episode.")
                        }
                    }
                    if (!resolved.isNullOrBlank()) {
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolved -> ${resolved.take(120)}")
                        runtimeRegistry.getOrCreate(task.id).resetParkingAndFailoverState()
                        ResolveBudget.forgetTask(task.id)
                        clearBackendFailures(task.id)
                        streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(resolved)
                        // Same-show duplicate-terminal guard (Servamp): two
                        // different episodes must not share one direct URL.
                        val duplicate = findDuplicateTerminal(task, streamUrl)
                        if (duplicate != null) {
                            com.anonrode.downloader.util.DebugLog.error(
                                "task=${task.id} DUPLICATE terminal for S${com.anonrode.downloader.util.SeasonEpisode.of(task).first}" +
                                    "E${com.anonrode.downloader.util.SeasonEpisode.of(task).second} already used by task=${duplicate.id} " +
                                    "(${streamUrl.take(120)}) — rejecting and re-resolving without cache"
                            )
                            com.anonrode.downloader.pipeline.ResolveCache.poison(streamUrl)
                            com.anonrode.downloader.pipeline.ResolveCache.invalidate(
                                com.anonrode.downloader.pipeline.ResolveCache.keyFor(permUrl, task.quality ?: defaultQuality)
                            )
                            val fresh = try {
                                resolveStreamUrl(
                                    permUrl, task.site, task.quality ?: defaultQuality,
                                    allowCacheHit = false, memoTaskId = task.id
                                )
                            } catch (_: Exception) { null }
                            if (!fresh.isNullOrBlank() && fresh != streamUrl && findDuplicateTerminal(task, fresh) == null) {
                                streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                com.anonrode.downloader.util.DebugLog.resolve(
                                    "task=${task.id} duplicate guard: fresh distinct terminal ${streamUrl.take(120)}"
                                )
                            } else {
                                noteBackendFailure(task, streamUrl)
                                if (attemptCrossProviderFailover(task)) return@launch
                                throw Exception(
                                    "This episode resolved to another episode's file — the source is serving the wrong episode. " +
                                        "Choose another server/episode."
                                )
                            }
                        }
                    } else if (!keepBankedUrl && (isKnownLockerHost(streamUrl) || !isDirectMediaUrl(streamUrl))) {
                        if (task.site.isNotBlank()) {
                            val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(streamUrl)
                            com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} (host=$host) — failing cleanly")
                            val hostReason = com.anonrode.downloader.pipeline.HostHealth.lastReason(streamUrl)
                            val reasonSuffix = if (!hostReason.isNullOrBlank()) " ($hostReason)" else ""
                            com.anonrode.downloader.util.DebugLog.error("task=${task.id} could not crack stream link (host=$host)$reasonSuffix for ${streamUrl.take(120)}")
                            if (attemptCrossProviderFailover(task)) return@launch
                            // No-source ledger is owned exclusively by
                            // attemptCrossProviderFailover: only that function
                            // knows whether every eligible provider actually
                            // completed a search. A duplicate record here fired
                            // even when failover was skipped (banked bytes) or
                            // timed out, hiding the title for 48h.
                            if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(streamUrl)) {
                                val mins = maxOf(1L, com.anonrode.downloader.pipeline.HostHealth.remainingBackoffMs(streamUrl) / 60_000L)
                                com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} host $host in backoff — parking for ~${mins}m")
                                val entry = runtimeRegistry.getOrCreate(task.id)
                                val cycles = entry.parkCycleCount.incrementAndGet()
                                val landed = bytesLanded(task)
                                if (cycles > MAX_PARK_CYCLES && landed == 0L) {
                                    entry.resetParkingAndFailoverState()
                                    throw Exception(
                                        "$host has been unreachable across $MAX_PARK_CYCLES cooldown cycles — giving up so the queue is not stuck on it. " +
                                            "Tap retry once the site is back."
                                    )
                                }
                                entry.firstParkedAtMs.compareAndSet(0L, System.currentTimeMillis())
                                entry.requeueNotBeforeMs.set(System.currentTimeMillis() + parkFloorMs(cycles))
                                ResolveBudget.forgetTask(task.id)
                                val saved = if (landed > 0) " — ${(landed / (1024L * 1024L)).coerceAtLeast(1)} MB already saved, it will resume" else ""
                                repository.update(task.id) {
                                    it.copy(
                                        status = TaskStatus.PAUSED,
                                        speedBytesPerSec = 0.0,
                                        errorMessage = "$PARKED_HOST_MESSAGE — will retry in ~${mins}m$saved"
                                    )
                                }
                                updateServiceState(force = true)
                                return@launch
                            }
                            throw Exception("Could not get a download link from the file host ($host) — it is not responding right now or the link expired. Try again in a few minutes, or choose another server/episode.")
                        }
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} — falling back to yt-dlp extractor")
                        android.util.Log.w("AnonDownload", "Resolver chain empty for $streamUrl, handing to yt-dlp")
                    }
                }

                // Evidence-based backend routing (2026-10-20). The old
                // name-heuristics contradicted each other about stream-proxy
                // and sent a self-referential playlist to yt-dlp 6 times.
                // inspectTerminal() is a bounded probe: direct MP4 -> Turbo/
                // aria2c, genuine m3u8 -> yt-dlp, decoy -> rejected here.
                val terminalEvidence = if (!isMagnet && !isSocial) {
                    val taskRef = task.headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
                    val hintedRef = com.anonrode.downloader.pipeline.ResolvedHints.refererFor(streamUrl)
                    com.anonrode.downloader.pipeline.UrlClassifier.inspectTerminal(
                        streamUrl,
                        referer = taskRef ?: hintedRef ?: getRefererForUrl(streamUrl),
                        tag = "engine-route"
                    )
                } else null
                if (terminalEvidence?.kind == com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.SELF_REFERENTIAL ||
                    terminalEvidence?.kind == com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.STUB
                ) {
                    com.anonrode.downloader.pipeline.ResolveCache.poison(streamUrl)
                    noteBackendFailure(task, streamUrl)
                    com.anonrode.downloader.util.DebugLog.error(
                        "task=${task.id} source returned a placeholder playlist (${terminalEvidence.kind}: ${terminalEvidence.reason}) — ${streamUrl.take(120)}"
                    )
                    if (attemptCrossProviderFailover(task)) return@launch
                    throw Exception(
                        "The source server returned a placeholder playlist instead of a video. " +
                            "Tap retry to fetch a fresh link, or choose another server/episode."
                    )
                }
                val isHlsByEvidence = terminalEvidence?.kind ==
                    com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.HLS_PLAYLIST
                val isHlsStream = isHlsByEvidence ||
                    (terminalEvidence?.kind !=
                        com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.DIRECT_MEDIA &&
                        com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl))
                val isDirectByEvidence = terminalEvidence?.kind ==
                    com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.DIRECT_MEDIA
                val isProvablyDirect = isDirectByEvidence || isProvablyDirectFile(streamUrl) ||
                    isDirectMediaUrl(streamUrl) || isHlsStream
                val isEmbedOrPage = !isMagnet && !isSocial && !isProvablyDirect &&
                    terminalEvidence?.kind == com.anonrode.downloader.pipeline.UrlClassifier.TerminalKind.UNKNOWN &&
                    !com.anonrode.downloader.pipeline.UrlClassifier.isDirectMediaPath(streamUrl)
                val route = DynamicRulesManager.resolveEngine(streamUrl)
                val finalBackend = if (task.audioOnly) {
                    "yt-dlp"
                } else if (isMagnet) {
                    "aria2c"
                } else if (route != null) {
                    when (route.engine) {
                        "ytdlp_only" -> "yt-dlp"
                        "aria2_only" -> "aria2c"
                        "hls_paced" -> "yt-dlp"
                        "turbo_aria2" -> "aria2c"
                        else -> if (isSocial || isHlsStream || isEmbedOrPage) "yt-dlp" else "aria2c"
                    }
                } else {
                    if (isSocial || isHlsStream || isEmbedOrPage) "yt-dlp" else "aria2c"
                }
                var isExtractor = isSocial || task.audioOnly || isEmbedOrPage
                // A stale-link re-resolve can change the stream type (HLS ->
                // direct MP4). Keep the extractor flag honest for subtitles
                // and the private workdir; the backend can still be yt-dlp.
                fun recomputeExtractor() {
                    isExtractor = isSocial || task.audioOnly ||
                        !(isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) ||
                            com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl))
                }
                val effectiveSockets = com.anonrode.downloader.pipeline.HostPolicyRegistry.effectiveSocketsForUrl(
                    streamUrl,
                    task.parallelSockets
                )
                // Global lane ledger (24 lanes): reserve once per task; the
                // same grant feeds Turbo AND the aria2c fallback so a handoff
                // never double-books. HLS reserves later, after preflight
                // reveals the real segment host.
                if (finalBackend != "yt-dlp" && !isMagnet) {
                    grantedLanes = com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                        .reserve(
                            task.id, effectiveSockets, hls = false,
                            plannedTasks = plannedTransferCount(),
                            hostCap = com.anonrode.downloader.pipeline.HostPolicyRegistry
                                .effectiveSocketsForUrl(streamUrl, 16)
                        )
                    if (grantedLanes == 0) {
                        // Admission race: hand the slot back to the queue
                        // instead of starting with a starved flow set.
                        repository.update(task.id) {
                            it.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0)
                        }
                        updateServiceState(force = true)
                        processQueue()
                        return@launch
                    }
                    com.anonrode.downloader.util.DebugLog.engine(
                        "task=${task.id} lane budget: granted $grantedLanes/${effectiveSockets} (direct)"
                    )
                }

                coroutineContext.ensureActive()

                // Synchronize file extension with the resolved direct media URL if no bytes have landed yet
                task = repository.find(task.id) ?: task
                if (!isMagnet && finalBackend == "aria2c" && bytesLanded(task) == 0L) {
                    val resolvedExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(streamUrl)
                    val currentFile = File(task.filePath)
                    if (resolvedExt != null && !currentFile.extension.equals(resolvedExt, ignoreCase = true)) {
                        val updatedFile = File(currentFile.parentFile, "${currentFile.nameWithoutExtension}.$resolvedExt")
                        // Same reservation lock as enqueue(): without it a concurrent
                        // enqueue can pick this exact path between our snapshot and
                        // our update, and two tasks end up owning one destination.
                        val renamed = adoptExtensionPath(task.id, updatedFile)
                        if (renamed) {
                            task = repository.find(task.id) ?: task.copy(filePath = updatedFile.absolutePath)
                        }
                    }
                }

                repository.update(task.id) { t ->
                    t.copy(
                        status = TaskStatus.DOWNLOADING,
                        directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || isHlsStream) streamUrl else t.directUrl
                    )
                }
                updateServiceState(force = true)

                // Launch unified ProgressWatchdog
                watchdogJob = progressWatchdog.launchForTask(
                    scope = this,
                    initialTask = task,
                    streamUrl = streamUrl,
                    stallTimeoutSecProvider = { stallTimeoutSec }
                )


                val targetFolder = getDownloadDirectory(task.showTitle, createDirs = true)
                // Android 11+ direct writes to public Downloads silently fail
                // without "All files access" (MANAGE_EXTERNAL_STORAGE) — fail
                // fast with the fix instead of a confusing mid-download IO
                // error that looks like the site broke.
                if (!targetFolder.isDirectory || !targetFolder.canWrite()) {
                    throw Exception(
                        "Storage permission missing — downloads are saved to your Downloads folder. " +
                            "Enable \"All files access\" for Anonrode (Settings > Apps > Anonrode > Permissions)."
                    )
                }
                // Header propagation: honor task headers first (from queue/recipe),
                // falling back to host policy rules from OTA playbook, and finally default UA.
                val taskReferer = task.headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
                val taskUa = task.headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
                // Recipe-declared CDN referer (nsrplay data.referer -> vimeos)
                // outranks the host-policy table for THIS resolved stream.
                val hintedReferer = com.anonrode.downloader.pipeline.ResolvedHints.refererFor(streamUrl)
                    ?: com.anonrode.downloader.pipeline.ResolvedHints.refererFor(permUrl)
                var refererToPass = taskReferer ?: hintedReferer ?: getRefererForUrl(streamUrl)
                var uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                fun buildHeaders(ref: String, ua: String): MutableMap<String, String> {
                    val m = mutableMapOf("User-Agent" to ua)
                    if (ref.isNotBlank()) m["Referer"] = ref
                    for ((k, v) in task.headers) {
                        if (!k.equals("user-agent", ignoreCase = true) && !k.equals("referer", ignoreCase = true)) {
                            m[k] = v
                        }
                    }
                    return m
                }
                var hdrs = buildHeaders(refererToPass, uaToPass)
                // Header/truth preservation (lead directive 2026-10-21): persist the
                // VALIDATED headers onto the task immediately. ResolvedHints die at
                // their 30-min TTL and a fresh resolve may return no headers, so a
                // retry — watchdog re-run, park→requeue, network repark, app
                // restart — used to re-enter with NO Referer and loop 403s forever
                // (nexabloom answers 200 WITH Referer / 403 WITHOUT; c6710cfb held
                // 89,794 KiB through 8 such parks). task.headers is a persisted
                // DownloadTask field, so every requeue/restart path re-enters with
                // the same Referer/UA. Mid-file 403/401 then resumes from the
                // banked .part with the exact plan the bytes were fetched under
                // (applyHlsPlanFingerprint keeps the partial when the plan matches).
                var persistedHdrs: Map<String, String> = task.headers
                fun persistHeadersIfChanged(h: Map<String, String>) {
                    // `task` is a boot-time snapshot; track what THIS run last
                    // persisted so re-resolved (but identical) header sets do
                    // not re-write the store on every recompute.
                    if (persistedHdrs.keys != h.keys || persistedHdrs.values.toSet() != h.values.toSet()) {
                        persistedHdrs = h
                        repository.update(task.id) { cur ->
                            if (cur.status == TaskStatus.COMPLETED || cur.status == TaskStatus.FAILED) cur
                            else cur.copy(headers = h)
                        }
                    }
                }
                persistHeadersIfChanged(hdrs)

                // HLS pre-flight: probe the segment CDN before yt-dlp so a dead
                // stream host fails cleanly in seconds instead of pinning the
                // task at 0 bytes for minutes (watchdog kills + 3 attempts).
                // Also returns a locally rewritten master (scheme-relative
                // segment URLs fixed to absolute https) for yt-dlp to consume.
                var hlsMasterFile: File? = null
                // Out-holder for the preflight's HLS size estimate (bytes,
                // 0 = none). Kept current across every preflight invocation
                // so a future consumer always sees the latest estimate.
                val hlsSizeEstimate = LongArray(1)
                // Out-holder for the real stream resolution from the master
                // playlist (e.g. "1080p"), shown on the download card.
                val hlsResolution = arrayOfNulls<String>(1)
                // Real segment host (for the HLS per-host lane cap) and the
                // structural media-plan fingerprint.
                val hlsSegmentUrl = arrayOfNulls<String>(1)
                val hlsFingerprint = arrayOfNulls<String>(1)
                val preflightedMaster = arrayOfNulls<String>(1)
                val preflightedVariant = arrayOfNulls<String>(1)
                val preflightedVariantUrl = arrayOfNulls<String>(1)
                if (finalBackend == "yt-dlp" && com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)) {
                    try {
                        hlsMasterFile = preflightHls(
                            context, task.id, streamUrl, refererToPass, hlsSizeEstimate,
                            task.quality ?: defaultQuality, hlsResolution, hlsSegmentUrl, hlsFingerprint,
                            preflightedMaster, preflightedVariant, preflightedVariantUrl
                        )
                    } catch (e: StaleStreamLinkException) {
                        // A persisted HLS URL carries a token that dies within
                        // hours — and the router trusts .m3u8 as "already
                        // direct", so a resumed/retried task NEVER re-resolved
                        // and looped 403s forever (live log 18:39: five starts,
                        // all 403). Go back to the source page for a fresh one.
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} ${e.message}")
                        val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (fresh.isNullOrBlank() || fresh == streamUrl) {
                            throw Exception("Stream link expired and no fresh link could be fetched — try again later.")
                        }
                        streamUrl = fresh
                        recomputeExtractor()
                        refererToPass = taskReferer ?: com.anonrode.downloader.pipeline.ResolvedHints.refererFor(streamUrl) ?: getRefererForUrl(streamUrl)
                        uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                        hdrs = buildHeaders(refererToPass, uaToPass)
                        persistHeadersIfChanged(hdrs)
                        val freshIsHls = com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)
                        repository.update(task.id) { t ->
                            t.copy(
                                directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || freshIsHls) streamUrl else t.directUrl
                            )
                        }
                        hlsMasterFile = if (freshIsHls) {
                            preflightHls(
                                context, task.id, streamUrl, refererToPass,
                                hlsSizeEstimate, task.quality ?: defaultQuality, hlsResolution,
                                hlsSegmentUrl, hlsFingerprint,
                                preflightedMaster, preflightedVariant, preflightedVariantUrl
                            )
                        } else null
                    }
                }
                if (finalBackend == "yt-dlp" && com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)) {
                    // Purge ONLY on a real plan change; a matching fingerprint
                    // keeps the banked .part untouched (2026-10-19: 89 MB of
                    // progress must survive a token-refresh retry).
                    if (applyHlsPlanFingerprint(task, hlsFingerprint[0])) {
                        repository.update(task.id) {
                            it.copy(downloadedBytes = 0L, speedBytesPerSec = 0.0, etaSeconds = 0L)
                        }
                    }
                    // yt-dlp cannot resize its worker pool mid-run, so the
                    // STARTING -N is chosen from live concurrency: 1 task → 8,
                    // 2 → 6, 3+ → 5 (within the global budget and per-host
                    // caps). This is the fragment-boundary-safe equivalent of
                    // the Turbo ramp.
                    val activeNow = runtimeRegistry.activeRunningTaskIds().size.coerceAtLeast(1)
                    val desiredByConcurrency = when {
                        activeNow <= 1 -> 8
                        activeNow == 2 -> 6
                        else -> 5
                    }
                    val wantedLanes = com.anonrode.downloader.pipeline.HostPolicyRegistry
                        .effectiveHlsLanesForUrl(
                            streamUrl,
                            hlsSegmentUrl[0],
                            minOf(hlsFragmentConcurrency, desiredByConcurrency)
                        )
                    hlsFallbackLanes = wantedLanes
                    grantedLanes = com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                        .reserve(task.id, 1, hls = true, plannedTasks = plannedTransferCount())
                    if (grantedLanes == 0) {
                        repository.update(task.id) {
                            it.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0)
                        }
                        updateServiceState(force = true)
                        processQueue()
                        return@launch
                    }
                    com.anonrode.downloader.util.DebugLog.engine(
                        "task=${task.id} lane budget: granted $grantedLanes/$wantedLanes " +
                            "(hls, active=$activeNow master=$streamUrl segment=${hlsSegmentUrl[0]})"
                    )
                } else if (finalBackend == "yt-dlp" && !isMagnet) {
                    grantedLanes = com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                        .reserve(
                            task.id, effectiveSockets, hls = false,
                            plannedTasks = plannedTransferCount(),
                            hostCap = com.anonrode.downloader.pipeline.HostPolicyRegistry
                                .effectiveSocketsForUrl(streamUrl, 16)
                        )
                    if (grantedLanes == 0) {
                        repository.update(task.id) {
                            it.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0)
                        }
                        updateServiceState(force = true)
                        processQueue()
                        return@launch
                    }
                }

                // Seed the estimated total into the progress display so the
                // UI shows "3.2 GB" immediately instead of "0 B" until yt-dlp
                // emits its first parsed line (the segment-sampling estimator
                // is the most accurate pre-download projection available).
                if (hlsSizeEstimate[0] > 0L) {
                    repository.updateProgress(task.id, downloaded = 0L, total = hlsSizeEstimate[0], speed = 0.0, eta = 0L)
                }
                // Same for the resolution: show the ACTUAL stream quality the
                // master offers at/below the requested height.
                hlsResolution[0]?.let { r ->
                    repository.update(task.id) { t -> t.copy(resolution = r) }
                }

                var producedFile: File? = null
                var turboFailure: TurboDownloader.TurboResult.Failure? = null
                var turboResult: TurboDownloader.TurboResult? = null
                var aria2Error: String? = null
                // A Turbo "success" that was actually a stub: skip the aria2c
                // step (the URL is poisoned) and go straight to failover.
                var turboStubRecovered = false

                if (finalBackend == "aria2c" && !isMagnet) {
                    var dest = File(targetFolder, File(task.filePath).name)
                    var hdrs = buildHeaders(refererToPass, uaToPass)
                        persistHeadersIfChanged(hdrs)

                    // Sidecar state is needed BEFORE the pre-flight: a 100%
                    // complete file (paused during VALIDATING after Turbo moved
                    // .part -> dest) must never be re-fed to aria2c — `-c` on a
                    // finished file is HTTP 416 on Range-honoring servers or a
                    // 200-truncate-and-rewrite on Range-ignoring ones (O1).
                    val partFile = File(dest.absolutePath + ".part")
                    val turboSidecar = File(dest.absolutePath + ".turbo")
                    val ariaControlFile = File(dest.absolutePath + ".aria2")

                    // Repair an interrupted prefix handoff (audit finding 1): a
                    // .turbo WITHOUT its .part means the sidecar's plan refers to
                    // bytes that were renamed into dest — a Turbo sidecar resume
                    // would skip those ranges while writing a fresh .part and
                    // zero-fill the completed prefix. Truncate dest to the
                    // sidecar's contiguous prefix (the handoff's lost step) and
                    // drop the stale sidecar, so the state falls through to the
                    // dest-partial (aria2c) resume below — or to O1 if complete.
                    if (!partFile.exists() && turboSidecar.exists() && dest.exists()) {
                        val staleSidecar = turboSidecar
                        val handoffPrefix = try {
                            TurboState(staleSidecar).contiguousPrefixBytes()
                        } catch (_: Exception) {
                            null
                        }
                        if (handoffPrefix != null && handoffPrefix >= 0L && handoffPrefix < dest.length()) {
                            try {
                                RandomAccessFile(dest, "rw").use { it.setLength(handoffPrefix) }
                            } catch (_: Exception) {}
                        }
                        try { TurboState(staleSidecar).delete() } catch (_: Exception) {}
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} repaired interrupted handoff: dest+.turbo state, prefix=$handoffPrefix"
                        )
                    }

                    val alreadyCompleteOnDisk = dest.exists() &&
                        !partFile.exists() && !turboSidecar.exists() && !ariaControlFile.exists() &&
                        ((task.totalBytes > 0L && dest.length() == task.totalBytes) ||
                            (task.totalBytes <= 0L && isImageFile(dest) && dest.length() >= 1024L &&
                                !looksLikeHtml(dest) && isImageValid(dest)) ||
                            (task.totalBytes <= 0L && dest.length() >= 50 * 1024L &&
                                !looksLikeHtml(dest) && decodeCheck(dest)))
                    if (alreadyCompleteOnDisk) {
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} alreadyCompleteOnDisk bytes=${dest.length()} — skipping both engines (zero redo)"
                        )
                        producedFile = dest
                    }

                    // A real aria2c partial resumes via its control file; a
                    // dest-with-bytes partial (no .part) also resumes via aria2c.
                    // A Turbo .part keeps Turbo's own sidecar-driven resume; a
                    // stale .turbo WITHOUT its .part was repaired above.
                    val hasExistingAria2Partial = !alreadyCompleteOnDisk && (ariaControlFile.exists() ||
                        (dest.exists() && dest.length() > 0L && !partFile.exists()))

                    // Pre-flight runs ONLY for an actual resume: on the fresh path
                    // Turbo's single-RT probe (status + magic-byte sniff) IS the
                    // pre-flight, and a complete-on-disk file needs no URL
                    // validation at all — an expired token must never kill a
                    // finished download (O4 closure + O1).
                    if (!isSocial && !alreadyCompleteOnDisk && hasExistingAria2Partial) {
                        var rejection = StreamValidator.validateResult(streamUrl, hdrs)
                            if (rejection?.refreshable == true) {
                                coroutineContext.ensureActive()
                                val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality, allowCacheHit = false)
                                if (!fresh.isNullOrBlank() && fresh != streamUrl &&
                                    (isDirectMediaUrl(fresh) || isProvablyDirectFile(fresh))) {
                                    streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                    refererToPass = taskReferer ?: com.anonrode.downloader.pipeline.ResolvedHints.refererFor(streamUrl) ?: getRefererForUrl(streamUrl)
                                    uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                                    hdrs = buildHeaders(refererToPass, uaToPass)
                        persistHeadersIfChanged(hdrs)
                                    rejection = StreamValidator.validateResult(streamUrl, hdrs)
                                    coroutineContext.ensureActive()
                                    repository.update(task.id) { it.copy(directUrl = streamUrl) }
                                }
                            }
                        rejection?.let {
                            if (task.downloadedBytes == 0L && bytesLanded(task) == 0L && attemptCrossProviderFailover(task)) {
                                return@launch
                            }
                            throw PipelineError.ValidationFailed(it.reason)
                        }
                    }

                    val progressCb: (Long, Long, Long) -> Unit = { got, tot, bps ->
                        com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                            .reportSpeed(task.id, bps.toDouble())
                        val eta = if (bps > 0 && tot > got) (tot - got) / bps else 0L
                        val liveTask = repository.find(task.id)
                        val safeEta = if (eta > 0L) eta else liveTask?.etaSeconds?.takeIf { it > 0L } ?: 0L
                        repository.updateProgress(
                            taskId = task.id,
                            downloaded = got,
                            total = tot,
                            speed = bps.toDouble(),
                            eta = safeEta
                        )
                        updateServiceState(force = false)
                    }

                    val allowTurbo = (DynamicRulesManager.resolveEngine(streamUrl)?.engine ?: route?.engine) != "aria2_only"
                    if (allowTurbo && !alreadyCompleteOnDisk && !hasExistingAria2Partial) {
                        // Step 1: Primary execution via TurboDownloader
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} turbo primary start sockets=${grantedLanes.coerceAtLeast(1)} url=${streamUrl.take(110)}"
                        )
                        turboResult = TurboDownloader.download(
                            url = streamUrl,
                            dest = dest,
                            headers = hdrs,
                            configuredSockets = grantedLanes.coerceAtLeast(1),
                            onProgress = progressCb,
                            taskId = task.id
                        )

                        // Step 2: Self-healing token refresh if CDN returned 401/403/404 or HTML decoy page
                        if (turboResult is TurboDownloader.TurboResult.Failure && coroutineContext.isActive) {
                            val tokenExpired = turboResult.httpStatus in listOf(401, 403, 404, 410) || turboResult.htmlPage
                            if (tokenExpired && !isSocial) {
                                val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality, allowCacheHit = false)
                                if (!fresh.isNullOrBlank() && fresh != streamUrl) {
                                    streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                    refererToPass = taskReferer ?: com.anonrode.downloader.pipeline.ResolvedHints.refererFor(streamUrl) ?: getRefererForUrl(streamUrl)
                                    uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                                    hdrs = buildHeaders(refererToPass, uaToPass)
                        persistHeadersIfChanged(hdrs)
                                    repository.update(task.id) { it.copy(directUrl = streamUrl) }

                                    turboResult = TurboDownloader.download(
                                        url = streamUrl,
                                        dest = dest,
                                        headers = hdrs,
                                        configuredSockets = grantedLanes.coerceAtLeast(1),
                                        onProgress = progressCb,
                                        taskId = task.id
                                    )
                                }
                            }
                        }

                        when (turboResult) {
                            is TurboDownloader.TurboResult.Success -> {
                                val artifact = turboResult.file
                                // A sub-1 KiB non-image "success" is a CDN
                                // stub/decoy page, not media (the same floor
                                // the final validator applies). Never let it
                                // count as produced.
                                if (artifact.exists() && artifact.length() < 1024L && !isImageFile(artifact)) {
                                    com.anonrode.downloader.util.DebugLog.engine(
                                        "task=${task.id} turbo returned a ${artifact.length()}B stub — discarding and re-resolving"
                                    )
                                    try { artifact.delete() } catch (_: Exception) {}
                                    producedFile = null
                                    // A 1-byte "success" already wrote a phantom
                                    // downloadedBytes=1 via onProgress; reset it so
                                    // the failover guard sees zero banked bytes.
                                    repository.update(task.id) {
                                        it.copy(
                                            downloadedBytes = 0L,
                                            totalBytes = 0L,
                                            speedBytesPerSec = 0.0,
                                            etaSeconds = 0L
                                        )
                                    }
                                    turboStubRecovered = true
                                } else {
                                    producedFile = artifact
                                }
                            }
                            is TurboDownloader.TurboResult.Failure -> {
                                turboFailure = turboResult
                            }
                        }

                        if (turboStubRecovered && coroutineContext.isActive) {
                            // The URL that produced the stub is poison: make
                            // sure no resolver hands it back, then mint a
                            // FRESH link (allowCacheHit=false).
                            com.anonrode.downloader.pipeline.ResolveCache.poison(streamUrl)
                            val fresh = try {
                                resolveStreamUrl(
                                    permUrl, task.site, task.quality ?: defaultQuality,
                                    allowCacheHit = false
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            if (!fresh.isNullOrBlank() && fresh != streamUrl &&
                                (isDirectMediaUrl(fresh) || isProvablyDirectFile(fresh))) {
                                streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                refererToPass = taskReferer ?: com.anonrode.downloader.pipeline.ResolvedHints.refererFor(streamUrl) ?: getRefererForUrl(streamUrl)
                                uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                                hdrs = buildHeaders(refererToPass, uaToPass)
                        persistHeadersIfChanged(hdrs)
                                repository.update(task.id) { it.copy(directUrl = streamUrl) }
                            }
                            // yt-dlp gets the next chance (its extractor can
                            // sometimes read a page Turbo cannot); then the
                            // terminal branch hands zero-banked garbage to
                            // cross-provider failover.
                            producedFile = try {
                                YoutubeDlDownloader.download(
                                    context = context,
                                    taskId = task.id,
                                    sourceUrl = streamUrl,
                                    targetDir = targetFolder,
                                    preferredFilename = dest.name,
                                    backend = "yt-dlp",
                                    referer = refererToPass,
                                    origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(refererToPass),
                                    ua = uaToPass,
                                    customHeaders = hdrs,
                                    explicitReferer = taskReferer != null || hintedReferer != null,
                                    parallelSockets = grantedLanes.coerceIn(1, 16),
                                    quality = task.quality ?: defaultQuality,
                                    isExtractorTask = false,
                                    audioOnly = task.audioOnly,
                                    onProgress = { dl, tot, spd, eta ->
                                        com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                                            .reportSpeed(task.id, spd)
                                        repository.updateProgress(task.id, dl, tot, spd, eta)
                                        updateServiceState(force = false)
                                    },
                                    magnetMaxAttempts = magnetMaxAttempts,
                                    ytdlpMaxAttempts = maxOf(ytdlpMaxAttempts, 3),
                                    hlsFragments = hlsFragmentConcurrency,
                                    speedLimitKbs = globalSpeedLimitKbs,
                                    torrentPeers = torrentPeers,
                                    privacyMode = torrentPrivacyMode,
                                    estimatedTotal = repository.find(task.id)?.totalBytes ?: task.totalBytes
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                aria2Error = e.message
                                null
                            }
                        }
                    }

                    // Step 3: Run aria2c when resuming an existing .aria2 partial OR
                    // as a single fallback after a RECOVERABLE Turbo failure.
                    // Never hand aria2c a link Turbo proved is an HTML page or a
                    // terminal 401/403/404/410/429 rejection (Fix 1B): aria2c
                    // sniffs nothing, so it would download the error page as
                    // media or burn retries hammering a dead URL.
                    val unrecoverableHttpOrHtml = turboFailure != null && (
                        turboFailure.htmlPage ||
                            turboFailure.httpStatus in listOf(401, 403, 404, 410, 429)
                        )
                    if (producedFile == null && !unrecoverableHttpOrHtml && !turboStubRecovered && coroutineContext.isActive) {
                        if (!hasExistingAria2Partial) {
                            handoffTurboPrefixToAria2c(dest)
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} turbo failed (${turboFailure?.message}), falling back once to aria2c"
                            )
                        } else {
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} resuming existing aria2c partial for ${dest.name}"
                            )
                        }
                        try {
                            producedFile = YoutubeDlDownloader.download(
                                context = context,
                                taskId = task.id,
                                sourceUrl = streamUrl,
                                targetDir = targetFolder,
                                preferredFilename = dest.name,
                                backend = "aria2c",
                                referer = refererToPass,
                                origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(refererToPass),
                                customHeaders = hdrs,
                                ua = uaToPass,
                                explicitReferer = taskReferer != null || hintedReferer != null,
                                parallelSockets = grantedLanes.coerceAtLeast(1),
                                quality = task.quality ?: defaultQuality,
                                isExtractorTask = false,
                                audioOnly = task.audioOnly,
                                onProgress = { dl, tot, spd, eta ->
                                    com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                                        .reportSpeed(task.id, spd)
                                    repository.updateProgress(task.id, dl, tot, spd, eta)
                                    updateServiceState(force = false)
                                },
                                magnetMaxAttempts = magnetMaxAttempts,
                                ytdlpMaxAttempts = ytdlpMaxAttempts,
                                hlsFragments = hlsFragmentConcurrency,
                                speedLimitKbs = globalSpeedLimitKbs,
                                torrentPeers = torrentPeers,
                                privacyMode = torrentPrivacyMode,
                                estimatedTotal = repository.find(task.id)?.totalBytes ?: task.totalBytes
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            aria2Error = e.message
                        }
                    }
                } else {

                    suspend fun runYtdlp(url: String, masterFile: File?): File? {
                        val runHint = com.anonrode.downloader.pipeline.ResolvedHints.refererFor(url)
                        val runReferer = taskReferer ?: runHint
                            ?: refererToPass.ifBlank { getRefererForUrl(url) }
                        val runExplicit = taskReferer != null || runHint != null
                        val runUa = taskUa ?: getUserAgentForUrl(url)
                        val runHdrs = buildHeaders(runReferer, runUa)
                        return YoutubeDlDownloader.download(
                            context = context,
                            taskId = task.id,
                            sourceUrl = url,
                            targetDir = targetFolder,
                            preferredFilename = File(task.filePath).name,
                            backend = finalBackend,
                            referer = runReferer,
                            origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(runReferer),
                            ua = runUa,
                            customHeaders = runHdrs,
                            explicitReferer = runExplicit,
                            parallelSockets = (if (grantedLanes > 0) grantedLanes else effectiveSockets).coerceIn(1, 16),
                            quality = task.quality ?: defaultQuality,
                            isExtractorTask = isExtractor,
                            audioOnly = task.audioOnly,
                            onProgress = { dl, tot, spd, eta ->
                                com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                                    .reportSpeed(task.id, spd)
                                repository.updateProgress(
                                    taskId = task.id,
                                    downloaded = dl,
                                    total = tot,
                                    speed = spd,
                                    eta = eta
                                )
                                updateServiceState(force = false)
                            },
                            onTorrentFiles = onTorrentFileSelection,
                            magnetMaxAttempts = magnetMaxAttempts,
                            // HLS and extractor/social tasks never run the retry ladder below 3: one
                            // attempt turns a token/quota blip or transient 55s crawl into a terminal
                            // failure (device setting was 1 on 2026-10-19 and 2026-10-25).
                            ytdlpMaxAttempts = if (isHlsStream || isExtractor || isSocial) maxOf(ytdlpMaxAttempts, 3) else ytdlpMaxAttempts,
                            hlsFragments = if (isHlsStream) {
                                (if (grantedLanes > 0) grantedLanes else hlsFragmentConcurrency).coerceIn(1, 16)
                            } else hlsFragmentConcurrency,
                            speedLimitKbs = globalSpeedLimitKbs,
                            torrentPeers = torrentPeers,
                            privacyMode = torrentPrivacyMode,
                            downloadSubs = downloadSubtitles && (isExtractor || isHlsStream),
                            subLang = subtitleLanguage,
                            hlsMasterFile = masterFile?.absolutePath,
                            estimatedTotal = if (hlsSizeEstimate[0] > 0L) hlsSizeEstimate[0] else task.totalBytes
                        )
                    }

                    // RC-9 root fix (board row 35, 2026-10-21): PLAYER-LIKE
                    // in-process HLS lane. The embed CDNs (fetch.nexabloom.top
                    // manifests / mo6mo.stellarfrontier.space segments)
                    // throttle BYTES PER WINDOW per IP — live evidence: 403 at
                    // the identical ~87.7 MiB boundary on every resumed
                    // attempt while a sequential single-connection read passes
                    // — and the released `-N16` fragment ladder burned that
                    // budget in seconds, then 403'd on every lane
                    // (`--abort-on-unavailable-fragments` → blank ERROR →
                    // park). This lane runs ONE keep-alive connection with the
                    // task's persisted headers, appends segments at an exact
                    // water mark, resumes AT the failed segment through a
                    // `.hlsrs` sidecar, and remuxes with the bundled ffmpeg.
                    // Unsupported shapes (AES, byterange, live windows)
                    // return null here and the yt-dlp chain below runs
                    // unchanged as the fallback.
                    val allowPacedHls = (DynamicRulesManager.resolveEngine(streamUrl)?.engine ?: route?.engine) != "ytdlp_only"
                    if (isHlsStream && allowPacedHls && producedFile == null && coroutineContext.isActive) {
                        HlsVodDownloader.attachContext(context)
                        val hlsSidecar = File(task.filePath + ".hlsrs")
                        val paced = try {
                            HlsVodDownloader.attempt(
                                masterUrl = streamUrl,
                                outPath = File(task.filePath),
                                sidecar = hlsSidecar,
                                headers = hdrs,
                                maxHeight = (task.quality ?: defaultQuality).filter { it.isDigit() }.toIntOrNull() ?: 720,
                                estimatedTotalBytes = if (hlsSizeEstimate[0] > 0L) hlsSizeEstimate[0] else 0L,
                                onProgress = { dl, tot, spd, eta ->
                                    com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget
                                        .reportSpeed(task.id, spd.toDouble())
                                    repository.updateProgress(
                                        taskId = task.id,
                                        downloaded = dl,
                                        total = tot,
                                        speed = spd.toDouble(),
                                        eta = eta
                                    )
                                    updateServiceState(force = false)
                                },
                                isCancelled = { !coroutineContext.isActive },
                                prefetchedMasterBody = preflightedMaster[0],
                                prefetchedVariantBody = preflightedVariant[0],
                                prefetchedVariantUrl = preflightedVariantUrl[0]
                            )
                        } catch (e: HlsVodDownloader.RateLimited) {
                            // Byte-quota escape: park with the REAL boundary and
                            // status (RC-8 charter — no empty error text), then
                            // let the escalated requeue windows resume at the
                            // exact failed segment when the host's window
                            // resets. Never a terminal failure, never a
                            // restart (owner invariant: every error ends in a
                            // download or a proven no-source).
                            com.anonrode.downloader.util.DebugLog.backend(
                                "task=${task.id} paced HLS lane rate-limited: ${e.message}"
                            )
                            val liveNow = repository.find(task.id) ?: task
                            parkInsteadOfFail(liveNow, e.message ?: "HLS rate limited", forcePark = true)
                            return@launch
                        } catch (e: HlsVodDownloader.DecoyResponse) {
                            // P-2 (vidbasic decoy-node class, measured live):
                            // three fresh dials all contradicted the playlist's
                            // byte math. Same park/resume family — the boundary
                            // holds, the requeue redials from scratch at the
                            // failed segment, and the banner carries the real
                            // expected/got math instead of a blank error.
                            com.anonrode.downloader.util.DebugLog.backend(
                                "task=${task.id} paced HLS lane decoy nodes: ${e.message}"
                            )
                            val liveNow = repository.find(task.id) ?: task
                            parkInsteadOfFail(liveNow, e.message ?: "HLS decoy response", forcePark = true)
                            return@launch
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            val segmentsDone = HlsVodDownloader.readResume(hlsSidecar).segmentsDone
                            if (e is java.io.IOException && segmentsDone > 0) {
                                com.anonrode.downloader.util.DebugLog.backend(
                                    "task=${task.id} paced HLS transient failure with $segmentsDone banked segments: ${e.message} — parking for segment resume"
                                )
                                val liveNow = repository.find(task.id) ?: task
                                parkInsteadOfFail(liveNow, e.message ?: "HLS segment failed", forcePark = true)
                                return@launch
                            }
                            com.anonrode.downloader.util.DebugLog.resolve(
                                "task=${task.id} paced HLS lane unavailable (${e.message}) — falling back to yt-dlp"
                            )
                            null
                        }
                        if (paced != null) {
                            producedFile = paced
                        } else {
                            try { File(task.filePath + ".hts").delete() } catch (_: Exception) {}
                            try { File(task.filePath + ".hlsrs").delete() } catch (_: Exception) {}
                        }
                    }

                    // YoutubeDlDownloader.download throws on final failure (after
                    // ytdlpMaxAttempts), so the re-resolve below must catch it —
                    // otherwise a failed chain skips the fresh-token recovery and
                    // the task dies with the first URL's errors.
                    var firstError: Exception? = null
                    producedFile = try {
                        if (producedFile == null && hlsFallbackLanes > 1) {
                            // The paced HLS lane declined; yt-dlp needs the
                            // fragment width that admission originally wanted.
                            grantedLanes = com.anonrode.downloader.pipeline.HostPolicyRegistry
                                .LaneBudget.resize(
                                    task.id,
                                    hlsFallbackLanes,
                                    hls = true,
                                    plannedTasks = plannedTransferCount()
                                )
                        }
                        if (producedFile != null) producedFile else runYtdlp(streamUrl, hlsMasterFile)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        firstError = e
                        null
                    }
                    // HLS/embed tokenized URLs (master.m3u8?token=...) expire
                    // mid-flight: on failure, re-resolve for a fresh URL and try
                    // once more, mirroring the token refresh on the direct path.
                    // A fresh resolve also lands a fresh edge node/session, which
                    // resets a CDN byte-quota throttle (the observed recovery).
                    // The re-resolved URL invalidates the old rewritten master —
                    // run its own preflight (probe + rewrite) on the retry.
                    if (producedFile == null && coroutineContext.isActive && !isSocial) {
                        val freshUrl = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (!freshUrl.isNullOrBlank() && freshUrl != streamUrl) {
                            android.util.Log.w("AnonDownload", "yt-dlp failed, re-resolving for a fresh URL")
                            streamUrl = freshUrl
                            recomputeExtractor()
                            var freshMaster: File? = null
                            if (com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)) {
                                try {
                                    val freshSegment = arrayOfNulls<String>(1)
                                    val freshFingerprint = arrayOfNulls<String>(1)
                                    freshMaster = preflightHls(
                                        context, task.id, freshUrl,
                                        taskReferer ?: getRefererForUrl(freshUrl),
                                        hlsSizeEstimate, task.quality ?: defaultQuality,
                                        hlsResolution, freshSegment, freshFingerprint
                                    )
                                    // Second preflight = second chance to notice a
                                    // plan change; a match still preserves bytes.
                                    if (applyHlsPlanFingerprint(task, freshFingerprint[0])) {
                                        repository.update(task.id) {
                                            it.copy(downloadedBytes = 0L, speedBytesPerSec = 0.0, etaSeconds = 0L)
                                        }
                                    }
                                    val wanted = com.anonrode.downloader.pipeline.HostPolicyRegistry
                                        .effectiveHlsLanesForUrl(
                                            freshUrl, freshSegment[0], hlsFragmentConcurrency
                                        )
                                    grantedLanes = com.anonrode.downloader.pipeline.HostPolicyRegistry
                                        .LaneBudget.reserve(
                                            task.id, wanted, hls = true,
                                            plannedTasks = plannedTransferCount()
                                        )
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Fresh stream unreachable — report the first
                                    // chain's error instead.
                                    throw firstError ?: e
                                }
                            }
                            try {
                                producedFile = runYtdlp(freshUrl, freshMaster)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Both attempt chains failed; report the first
                                // chain's error (it carries the attempt detail).
                                throw firstError ?: e
                            }
                        }
                    }
                    // Rewritten masters are scratch files in the cache dir —
                    // remove them once the chain is done (they can be large:
                    // 950+ rewritten segment lines).
                    if (hlsMasterFile != null) {
                        try {
                            val cacheDir = File(context.cacheDir, "hls")
                            cacheDir.listFiles { f -> f.name.startsWith("hls-${task.id}") }?.forEach { it.delete() }
                        } catch (_: Exception) {}
                        hlsMasterFile = null
                    }
                    if (producedFile == null && firstError != null) throw firstError
                }

                // A paused or cancelled job must never flip to a final state: the
                // download may have finished right as pause landed. The exception
                // propagates as CancellationException, which the catch below treats
                // as silent, leaving the PAUSED status intact.
                coroutineContext.ensureActive()

                if (producedFile != null && producedFile.exists()) {
                    // Normalize .unknown_video or .matroska extension if present
                    if (producedFile.extension.equals("unknown_video", ignoreCase = true) ||
                        producedFile.extension.equals("matroska", ignoreCase = true) ||
                        producedFile.extension.isBlank()) {
                        val properExt = if (task.filePath.lowercase().endsWith(".mkv") || producedFile.extension.equals("matroska", ignoreCase = true)) "mkv" else "mp4"
                        val corrected = File(producedFile.parentFile, "${producedFile.nameWithoutExtension}.$properExt")
                        if (!corrected.exists() && producedFile.renameTo(corrected)) {
                            producedFile = corrected
                        }
                    }
                    // For non-social downloads, ensure the artifact adopts the clean target filename
                    // if it was mangled by an extractor backend template.
                    if (!isSocial && producedFile.name != File(task.filePath).name) {
                        val targetDest = File(task.filePath)
                        if (targetDest.exists()) {
                            val isImg = targetDest.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
                            val isCorrupt = if (isImg) {
                                targetDest.length() < 1024L || looksLikeHtml(targetDest) || !isImageValid(targetDest)
                            } else {
                                targetDest.length() < 50 * 1024L || looksLikeHtml(targetDest) || !decodeCheck(targetDest)
                            }
                            if (isCorrupt) {
                                targetDest.delete()
                            }
                        }
                        if (!targetDest.exists() && producedFile.renameTo(targetDest)) {
                            producedFile = targetDest
                        }
                    }
                    repository.update(task.id) { it.copy(status = TaskStatus.VALIDATING) }
                    updateServiceState(force = true)
                }

                val validation = if (producedFile != null && producedFile.exists()) {
                    TorrentSecurityShield.validateDownloadedFile(producedFile, targetFolder)
                } else {
                    Pair(false, "File missing")
                }

                val isAudio = task.filePath.lowercase().let { it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".aac") }
                val isImage = (producedFile?.name ?: task.filePath).lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
                val minSize = if (isImage) 1024L else if (isAudio) 10 * 1024L else 50 * 1024L

                // ---- Tiered verification: answer "is it media / complete /
                // playable" with the strongest evidence available, instead of
                // the old ">5MB => trust it" size guess.
                // Tier 0 — transfer truth: non-HLS backends got the exact
                //           content length from the server; matching bytes =
                //           complete by protocol guarantee.
                // Tier 1 — structure: shield magic + atom scan (any known
                //           media atom in the head/tail windows proves a
                //           container, whatever its first box was).
                // Tier 2 — platform decode: MediaExtractor finds decodable
                //           tracks with a duration -> the OS itself confirms
                //           the file is playable media.
                // Tier 3 — HLS estimate: size within 90% of the
                //           segment-sampling estimate.
                val taskTotalBytes = repository.find(task.id)?.totalBytes ?: 0L
                val transferComplete = !isHlsStream && task.backend != "yt-dlp" &&
                    taskTotalBytes > 0 &&
                    producedFile != null && producedFile.exists() &&
                    fileSize(producedFile) >= taskTotalBytes
                val structuralOk = validation.first
                val decodeOk = !structuralOk && !transferComplete &&
                    producedFile != null && producedFile.exists() &&
                    producedFile.length() >= minSize &&
                    !looksLikeHtml(producedFile) && decodeCheck(producedFile)
                val hlsComplete = isHlsStream && hlsSizeEstimate[0] > 0 &&
                    producedFile != null && producedFile.exists() &&
                    producedFile.length() >= 0.9 * hlsSizeEstimate[0]
                val imageOk = isImage && producedFile != null && producedFile.exists() &&
                    producedFile.length() >= minSize && !looksLikeHtml(producedFile) && isImageValid(producedFile)
                val verified = transferComplete || structuralOk || decodeOk || hlsComplete || imageOk
                val path = when {
                    imageOk -> "image"
                    structuralOk -> "structure"
                    transferComplete -> "transfer"
                    decodeOk -> "decode"
                    hlsComplete -> "estimate"
                    else -> "none"
                }
                val note = when {
                    !verified || structuralOk || transferComplete || imageOk -> null
                    decodeOk -> "Downloaded — format not in the known container list, but the system decoder verified it plays."
                    else -> "Downloaded — file size matches the stream estimate."
                }

                if (producedFile != null && producedFile.exists() && fileSize(producedFile) >= minSize
                    && !looksLikeHtml(producedFile) && verified) {
                    // The block above ran without suspension points, so a pause
                    // landing mid-validation could not interrupt it. Re-check
                    // here: a cancelled job must never flip to COMPLETED after
                    // the user pressed pause.
                    coroutineContext.ensureActive()
                    val finalTitle = producedFile.nameWithoutExtension
                    val finalBytes = producedFile.length()
                    val targetPathBeforeCompletion = task.filePath

                    // Sidecars before completion so player and UI discovery find subtitles immediately.
                    try { downloadSubtitleSidecars(task, producedFile) } catch (_: Throwable) {}

                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isSocial) finalTitle else it.episodeTitle,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            speedBytesPerSec = 0.0,
                            etaSeconds = 0L,
                            status = TaskStatus.COMPLETED,
                            validationNote = note
                        )
                    }
                    task = repository.find(task.id) ?: task.copy(filePath = producedFile.absolutePath)

                    try {
                        File(producedFile.absolutePath + ".turbo").delete()
                        File(producedFile.absolutePath + ".part").delete()
                        File(producedFile.absolutePath + ".aria2").delete()
                        File(producedFile.absolutePath + ".hlsfp").delete()
                        File(producedFile.absolutePath + ".hlsrs").delete()
                        File(producedFile.absolutePath + ".hts").delete()
                        deletePartFragFiles(
                            producedFile.parentFile ?: File("."),
                            producedFile.nameWithoutExtension,
                            privateWorkdir = false
                        )
                        // Backends can produce a file whose name differs from the
                        // task's target (aria2c writes the URL's extension, e.g.
                        // .mp4, while the task targets .mkv) — without this the
                        // original target's sidecars are orphaned forever. Compare
                        // against the path captured BEFORE the completion write
                        // (task.filePath is the produced path by now). Never sweep
                        // a path another task now owns: the ext-sync rename frees
                        // the old name and a later enqueue may have taken it.
                        val otherTaskPaths = repository.snapshot()
                            .filter { it.id != task.id }
                            .mapTo(HashSet()) { it.filePath }
                        if (producedFile.absolutePath != targetPathBeforeCompletion &&
                            targetPathBeforeCompletion !in otherTaskPaths) {
                            File(targetPathBeforeCompletion + ".turbo").delete()
                            File(targetPathBeforeCompletion + ".part").delete()
                            File(targetPathBeforeCompletion + ".aria2").delete()
                            File(targetPathBeforeCompletion + ".ytdl").delete()
                            File(targetPathBeforeCompletion + ".hlsfp").delete()
                            File(targetPathBeforeCompletion + ".hlsrs").delete()
                            File(targetPathBeforeCompletion + ".hts").delete()
                        }
                        // The extension sync may have renamed the task's target
                        // (.mp4 → .mkv) before download start: the ORIGINAL path's
                        // sidecars are still orphaned unless swept too.
                        if (originalFilePath != task.filePath && originalFilePath != producedFile.absolutePath &&
                            originalFilePath != targetPathBeforeCompletion &&
                            originalFilePath !in otherTaskPaths) {
                            File(originalFilePath + ".turbo").delete()
                            File(originalFilePath + ".part").delete()
                            File(originalFilePath + ".aria2").delete()
                            File(originalFilePath + ".ytdl").delete()
                            File(originalFilePath + ".hlsfp").delete()
                            File(originalFilePath + ".hlsrs").delete()
                            File(originalFilePath + ".hts").delete()
                        }
                        // O3: bounded stem-family sweep — one directory listing
                        // clears every abandoned sidecar generation (a task whose
                        // extension alternated across 3+ runs could strand the
                        // oldest family forever). Sidecars only; data files and
                        // another task's live paths are never selected.
                        // Selection proven in scratch/test_zero_redo.py (section D).
                        val familyStems = setOf(
                            producedFile.nameWithoutExtension,
                            File(targetPathBeforeCompletion).nameWithoutExtension,
                            File(originalFilePath).nameWithoutExtension
                        )
                        val sidecarExts = setOf("part", "turbo", "aria2", "ytdl", "hlsfp", "hts", "hlsrs")
                        producedFile.parentFile?.listFiles()?.forEach { f ->
                            if (!f.isFile) return@forEach
                            // `.part-Frag<N>` has no fixed extension to match
                            // in sidecarExts; resolve its stem explicitly.
                            if (f.name.contains(".part-Frag", ignoreCase = true)) {
                                val fragStem = f.name.substringBefore(".part-Frag").substringBeforeLast('.')
                                if (fragStem in familyStems && f.absolutePath !in otherTaskPaths) f.delete()
                                return@forEach
                            }
                            val scExt = f.name.substringAfterLast('.', "")
                            if (scExt !in sidecarExts) return@forEach
                            val baseName = f.name.removeSuffix(".$scExt")
                            val basePath = File(f.parentFile, baseName).absolutePath
                            if (basePath == producedFile.absolutePath) return@forEach
                            if (basePath in otherTaskPaths || f.absolutePath in otherTaskPaths) return@forEach
                            var stem = baseName.substringBeforeLast('.', baseName)
                            if (baseName.endsWith(".part")) {
                                // Legacy two-level generation ("X.part.aria2" /
                                // "X.part.turbo"): strip the inner ".part" so the
                                // stem resolves to the data file's stem — but
                                // never touch it when the INNER data path is a
                                // live path of another task (that control file is
                                // its resume bookkeeping).
                                val legacyDataName = baseName.removeSuffix(".part")
                                val legacyDataPath = File(f.parentFile, legacyDataName).absolutePath
                                val legacyStem = legacyDataName.substringBeforeLast('.', "")
                                if (legacyStem.isNotBlank() && legacyStem in familyStems &&
                                    legacyDataPath !in otherTaskPaths) stem = legacyStem
                            }
                            if (stem in familyStems) f.delete()
                        }
                    } catch (_: Throwable) {}

                    // Main-looper hop inside: from this IO coroutine the old
                    // inline scanFile never bound the scanner service and
                    // silently no-opped — downloads stayed invisible to the
                    // gallery until a system rescan (user report 2026-09-13).
                    com.anonrode.downloader.util.MediaScan.notifyFile(context, producedFile)

                    if (completionNotifications) {
                        DownloadService.notifyCompleted(context, if (isSocial) finalTitle else task.episodeTitle)
                    }
                    com.anonrode.downloader.util.DebugLog.write("completed task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes validation=$path")
                    // Terminal cleanup: drop the registry entry so long queues do not
                    // accumulate keys. Guarded so a newer job for this task (a retry
                    // landing mid-completion) is never clobbered, and non-cancelling
                    // so this coroutine can finish its own tail (remove() would
                    // cancel it mid-flight).
                    val selfJob = coroutineContext[Job]
                    val reg = runtimeRegistry.get(task.id)
                    if (reg == null || reg.activeJob === selfJob) {
                        runtimeRegistry.forget(task.id)
                    }
                } else if (producedFile != null && producedFile.exists() && fileSize(producedFile) >= minSize
                    && !looksLikeHtml(producedFile)) {
                    // A real file IS on disk but no tier could verify it. Keep
                    // the file and say so honestly — never auto-delete it and
                    // never re-download over it (that is exactly what the old
                    // FAILED + retry path did, silently destroying the file).
                    coroutineContext.ensureActive()
                    val finalTitle = producedFile.nameWithoutExtension
                    val finalBytes = producedFile.length()

                    // Sidecars before completion
                    try { downloadSubtitleSidecars(task, producedFile) } catch (_: Throwable) {}

                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isSocial) finalTitle else it.episodeTitle,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            speedBytesPerSec = 0.0,
                            etaSeconds = 0L,
                            status = TaskStatus.COMPLETED,
                            validationNote = "File saved, but its format could not be verified — check that it plays before keeping."
                        )
                    }
                    task = repository.find(task.id) ?: task.copy(filePath = producedFile.absolutePath)
                    // Main-looper hop inside: from this IO coroutine the old
                    // inline scanFile never bound the scanner service and
                    // silently no-opped — downloads stayed invisible to the
                    // gallery until a system rescan (user report 2026-09-13).
                    com.anonrode.downloader.util.MediaScan.notifyFile(context, producedFile)
                    com.anonrode.downloader.util.DebugLog.write("completed-unverified task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes validation=none")
                    val selfJob = coroutineContext[Job]
                    val reg = runtimeRegistry.get(task.id)
                    if (reg == null || reg.activeJob === selfJob) {
                        runtimeRegistry.forget(task.id)
                    }
                } else {
                    val errReason = when {
                        producedFile != null && looksLikeHtml(producedFile) -> "Server returned an HTML page instead of the file"
                        // Null-check the transfer outcome BEFORE the generic
                        // validation pair: with no file, validation.second is
                        // always "File missing", which used to swallow every
                        // real HTTP status the backends captured (the 429 storm
                        // was logged as a bare "File missing").
                        // An aria2c rate-limit outranks Turbo's earlier non-429
                        // status: THE RULE parks on 429, and parking keys off this
                        // text reaching isRateLimitedError (the aria2c message
                        // carries the real "status=429" line).
                        producedFile == null && !aria2Error.isNullOrBlank() && isRateLimitedError(aria2Error) -> aria2Error!!
                        producedFile == null && turboFailure?.htmlPage == true -> "Server returned an HTML page instead of the file — the link expired; retry to refresh it"
                        producedFile == null && turboFailure?.httpStatus != null -> "Download rejected by server (HTTP ${turboFailure.httpStatus}) — retry to refresh the link"
                        // The aria2c fallback no longer swallows its exceptions
                        // (Fix 4): its message carries the real failure cause.
                        producedFile == null && !aria2Error.isNullOrBlank() -> aria2Error!!
                        producedFile == null -> "Download failed — the server never produced a file"
                        !validation.first -> validation.second
                        else -> "Output file was too small or corrupted"
                    }
                    // THE RULE: a transfer that never produced a file parks
                    // when bytes are banked (partials stay on disk, the
                    // cooldown loop resumes them) or when the server explicitly
                    // said 429. A file that DID land but is garbage (HTML
                    // decoy, too-small output) must still fail loudly — its
                    // bytes are not banked progress, and parking on them would
                    // re-fetch the same garbage forever.
                    val live = repository.find(task.id) ?: task
                    // Restore cross-provider failover for the producedFile==null
                    // path (Fix 1A): a Turbo Failure whose aria2c fallback threw
                    // lands HERE (the aria2c try/catch stores into aria2Error),
                    // and this branch previously went straight to park/fail —
                    // silently disabling failover on broken direct downloads.
                    // Garbage output (HTML/tiny/bad signature) with zero
                    // backend-reported bytes is NOT banked progress: fire the
                    // same failover a missing file gets. The guard inside also
                    // refuses when a real .part/.aria2 or >1 MB artifact exists.
                    val garbageOutput = producedFile != null && producedFile.exists() &&
                        isGarbageArtifact(producedFile)
                    // Garbage output is NOT banked progress — even when the
                    // garbage itself incremented downloadedBytes (the 10-22
                    // 3.3 KB bridge-manifest saved as .mp4 did exactly that).
                    // Let the failover helper's own partial/large-artifact
                    // guard protect real bytes; only a clean missing-file
                    // case additionally requires zero bytes.
                    val failoverEligible = garbageOutput ||
                        (producedFile == null && live.downloadedBytes == 0L)
                    if (failoverEligible && attemptCrossProviderFailover(live, producedFile)) {
                        return@launch
                    }
                    val parked = producedFile == null &&
                        parkInsteadOfFail(live, errReason, forcePark = isRateLimitedError(errReason))
                    if (!parked) {
                        if (producedFile != null && producedFile.exists() && (looksLikeHtml(producedFile) || fileSize(producedFile) < minSize || !validation.first)) {
                            try {
                                producedFile.delete()
                                File(producedFile.absolutePath + ".part").delete()
                                File(producedFile.absolutePath + ".turbo").delete()
                                File(producedFile.absolutePath + ".aria2").delete()
                                File(producedFile.absolutePath + ".hlsfp").delete()
                                File(producedFile.absolutePath + ".hlsrs").delete()
                                File(producedFile.absolutePath + ".hts").delete()
                                deletePartFragFiles(
                                    producedFile.parentFile ?: File("."),
                                    producedFile.nameWithoutExtension,
                                    privateWorkdir = false
                                )
                            } catch (_: Throwable) {}
                        }
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errReason) }
                        com.anonrode.downloader.util.DebugLog.write("failed task=${task.id} reason=$errReason")
                        com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, task.episodeTitle, errReason)
                        // Terminal FAILED: drop the registry entry exactly like
                        // the COMPLETED path (guarded so a newer job's entry is
                        // never clobbered).
                        val reg = runtimeRegistry.get(task.id)
                        if (reg == null || reg.activeJob === coroutineContext[Job]) {
                            runtimeRegistry.forget(task.id)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled. Nothing may outlive its job: kill the native
                // backend and any in-flight Turbo/resolver HTTP so a cancelled
                // task cannot keep draining data (defense in depth — pause()
                // and cancel() already do this; job cancellation can also come
                // from the queue processor or scope shutdown).
                YoutubeDlDownloader.killProcess(task.id)
                TurboDownloader.cancelTask(task.id)
                // Only cancel global in-flight HTTP for a FULL cancel (marked
                // in runtimeRegistry before the job was cancelled), not when
                // the user paused or the network parked the task — pause()/
                // pauseForNetwork already killed the specific backends, and a
                // global cancelInFlight would kill OTHER tasks' in-flight
                // requests — the cross-talk bug. The repository check stays
                // as a safety net for removals that bypassed cancel(). Even
                // then, only sweep when no other task is running.
                if (runtimeRegistry.isFullyCancelled(task.id) || repository.find(task.id) == null) {
                    if (!hasOtherActiveWork(task.id)) {
                        HttpClient.cancelInFlight()
                    }
                }
                // A pause landing during VALIDATING (after the first
                // ensureActive passed but before the terminal write) leaves the
                // status at VALIDATING — rescue it to PAUSED so the card is not
                // stuck mid-check.
                //
                // Ownership guard (engine-audit P2): a dying job unwinds AFTER
                // the queue may already have started its replacement (network
                // re-tag parks then requeues within the same tick). Writing
                // PAUSED over the live replacement's DOWNLOADING lied to the
                // user and silently disabled that run's watchdog. Rescue only
                // when no NEWER live job owns the task.
                val owner = runtimeRegistry.getActiveJob(task.id)
                if (owner == null || owner === coroutineContext[Job]) {
                    repository.update(task.id) { t ->
                        if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
                            t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                        } else t
                    }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    val rawErr = e.message ?: "Download error"
                    val rateLimited = isRateLimitedError(rawErr)
                    // Never surface a Java wrapper/empty aggregate to the user
                    // (AGENTS rule 14). The raw text still drives failover and
                    // the rate-limit park decision above.
                    val errMsg = humanizeDownloadError(rawErr)
                    // THE RULE again: any backend chain that finally threw —
                    // including the "yt-dlp failed after 3 attempt(s)" wrap of
                    // a 429 storm — parks when bytes are banked or the server
                    // explicitly asked us to wait (StreamValidator's
                    // "HTTP 429" rejection throws into this catch).
                    val live = repository.find(task.id) ?: task
                    noteBackendFailure(live, live.directUrl.ifBlank { task.directUrl })
                    if (live.downloadedBytes == 0L && bytesLanded(live) == 0L && attemptCrossProviderFailover(live)) {
                        return@launch
                    }
                    if (!parkInsteadOfFail(live, errMsg, forcePark = rateLimited)) {
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errMsg) }
                        com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, task.episodeTitle, errMsg)
                        // Terminal FAILED cleanup, same rule + guard as the
                        // produced-file FAILED path above.
                        val reg = runtimeRegistry.get(task.id)
                        if (reg == null || reg.activeJob === coroutineContext[Job]) {
                            runtimeRegistry.forget(task.id)
                        }
                    }
                } else {
                    // Cancelled (pause/cancel/network park): native-process teardown can surface
                    // as a plain exception, so never report that as FAILED. Also unwedge a task
                    // that was still marked DOWNLOADING when the job was cancelled.
                    repository.update(task.id) { t ->
                        if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING) {
                            t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                        } else t
                    }
                }
            } finally {
                watchdogJob?.cancel()
                val thisJob = coroutineContext[Job]
                val entry = runtimeRegistry.get(task.id)
                val ownedByThisJob = entry != null && thisJob != null && entry.activeJob === thisJob
                // Release only when no newer job has taken the task over; a
                // retry may already have re-reserved the same task id.
                if (grantedLanes > 0 &&
                    (entry == null || ownedByThisJob || entry.activeJob == null)
                ) {
                    com.anonrode.downloader.pipeline.HostPolicyRegistry.LaneBudget.release(task.id)
                }
                if (ownedByThisJob && entry != null) {
                    entry.activeJob = null
                }
                // Never clear a newer job's start flag: release (and drop the
                // full-cancel flag) only when this job still owns the entry or
                // nobody does.
                if (entry == null || entry.activeJob == null) {
                    runtimeRegistry.releaseStartLock(task.id)
                    // This job's Turbo interaction has fully unwound by now; the
                    // flag must not linger for tasks that never re-enter Turbo.
                    TurboDownloader.clearCancelledFlag(task.id)
                }
                // A non-Exception throwable (Error) skips both catch blocks above:
                // repair a still-active row we own so a dead job can never hold a
                // concurrency slot forever. A row owned by a NEWER job is untouched.
                if (ownedByThisJob) {
                    val liveRow = repository.find(task.id)
                    if (liveRow != null && (liveRow.status == TaskStatus.DOWNLOADING || liveRow.status == TaskStatus.RESOLVING || liveRow.status == TaskStatus.VALIDATING)) {
                        repository.update(task.id) { t ->
                            if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
                                t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                            } else t
                        }
                    }
                }
                // Rewritten HLS masters live in cacheDir/hls as scratch files
                // (950+ lines each). The inline sweep only ran on the success
                // path; a throw or cancel anywhere in the chain leaked them
                // until the OS reclaimed the cache (engine-audit P2). This
                // finally covers every exit; scoped by taskId so a concurrent
                // job for another task is never touched.
                try {
                    File(context.cacheDir, "hls").listFiles { f -> f.name.startsWith("hls-${task.id}") }
                        ?.forEach { it.delete() }
                } catch (_: Exception) {}
                updateServiceState(force = true)
                processQueue()
            }
        }

        runtimeRegistry.attachJob(initialTask.id, job)
        val current = repository.find(initialTask.id)
        if (current == null || current.userPaused || current.status !in setOf(TaskStatus.RESOLVING, TaskStatus.DOWNLOADING, TaskStatus.VALIDATING)) {
            job.cancel()
            // A pause landing after processQueue's status flip leaves the row in
            // RESOLVING/DOWNLOADING while this never-started LAZY job is killed
            // (its finally will never run): repair the row here, otherwise the
            // phantom active status holds a concurrency slot forever.
            if (current != null && current.userPaused &&
                (current.status == TaskStatus.RESOLVING || current.status == TaskStatus.DOWNLOADING)) {
                repository.update(initialTask.id) { t ->
                    if (t.userPaused && (t.status == TaskStatus.RESOLVING || t.status == TaskStatus.DOWNLOADING)) {
                        t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                    } else t
                }
            }
        } else {
            // LAZY + attachJob-first: the coroutine cannot execute before it is
            // registered, closing the window where a running job was invisible
            // to runtimeRegistry.activeRunningTaskIds().
            job.start()
        }
        // A job cancelled before it ever started (or one that finished this fast)
        // would otherwise leave a dead entry in runtimeRegistry — a stale key that
        // suppresses the runtimeRegistry.isEmpty()/none{} cancel-sweep heuristics.
        // Conditional remove: never clobbers a newer job for the same task.
        if (!job.isActive) {
            val entry = runtimeRegistry.get(initialTask.id)
            if (entry != null && entry.activeJob === job) {
                entry.activeJob = null
            }
            // The task row itself is gone (cancelled while this start was being
            // registered): drop the entry — no future run can ever clean it.
            if (current == null) {
                runtimeRegistry.forget(initialTask.id)
            }
        }
    }

    // ------------------------------------------------------------------
    // Bulk queue actions (Pause all / Resume all / Cancel all).
    //
    // The activity log showed users hand-tapping pause/cancel on card after
    // card (13 cancels in 2 seconds) because the queue screen had no bulk
    // actions. These run the SAME per-task teardown as the single-task
    // pause()/retry()/cancel() paths above — deliberately line-for-line,
    // so a bulk action can never diverge from what one card tap does —
    // with the service-state/processQueue churn batched to once per action
    // instead of once per task.

    /** Pause every task the user could pause card-by-card: running
     *  (DOWNLOADING/RESOLVING/VALIDATING) via pause()'s exact teardown, and
     *  QUEUED via pause()'s status write (a card tap on a queued card does
     *  the same). QUEUED tasks are included deliberately: each dying job's
     *  finally-guard calls processQueue(), which would otherwise start the
     *  queued tasks behind the user's back right after "Pause all".
     *  userPaused=true marks every one, so no auto-resume path (network
     *  reconnect, storage self-heal, host cooldown) will touch them. */
    fun pauseAll() {
        val pausable = repository.tasks.value.filter {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING ||
                it.status == TaskStatus.VALIDATING || it.status == TaskStatus.QUEUED
        }
        if (pausable.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("pause ALL (${pausable.size} tasks)")
        pausable.forEach { task ->
            val taskId = task.id
            // Same teardown as pause() — active jobs are cancelled, backends
            // killed, blocking HTTP swept once no task is left running.
            runtimeRegistry.getActiveJob(taskId)?.cancel()
            val entry = runtimeRegistry.get(taskId)
            if (entry != null) {
                entry.activeJob = null
                entry.isStarting.set(false)
            }
            YoutubeDlDownloader.killProcess(taskId)
            TurboDownloader.cancelTask(taskId)
            com.anonrode.downloader.util.DebugLog.user("pause $taskId")
            repository.update(taskId) { t ->
                // Status-guarded (engine-audit P2): a task that COMPLETED inside
                // the snapshot window must not be stamped PAUSED+userPaused —
                // that produced a "Paused at 100%" card with no auto-resume.
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING ||
                    t.status == TaskStatus.VALIDATING || t.status == TaskStatus.QUEUED
                ) t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true)
                else t
            }
        }
        // pause() sweeps in-flight HTTP only when the last job is gone; with
        // everything paused that condition holds by definition.
        if (runtimeRegistry.activeRunningTaskIds().isEmpty()) HttpClient.cancelInFlight()
        // One service/notification update for the whole batch, and NO
        // processQueue(): "Pause all" means everything stops, so the free
        // slots must not be refilled from the (now also paused) queue.
        updateServiceState(force = true)
    }

    /** Resume every PAUSED task via retry()'s exact path — including the ones
     *  carrying the userPaused mark: this IS the explicit user resume, the
     *  one path allowed to clear it. One processQueue() fills the slots. */
    fun resumeAll() {
        val paused = repository.tasks.value.filter { it.status == TaskStatus.PAUSED }
        if (paused.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("resume ALL (${paused.size} tasks)")
        paused.forEach { task ->
            val taskId = task.id
            // Same per-task body as retry(): one host-health bypass each, a
            // fresh park/failover history, and the userPaused mark cleared.
            runtimeRegistry.markForManualRetry(taskId)
            repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
        }
        processQueue()
        updateServiceState(force = true)
    }

    /** Cancel every non-terminal task (QUEUED/DOWNLOADING/RESOLVING/VALIDATING/
     *  PAUSED) via cancel()'s exact teardown. Destructive — the UI confirms
     *  before calling this. Partial files are removed per task exactly as a
     *  single cancel does. */
    fun cancelAll() {
        val cancellable = repository.tasks.value.filter {
            it.status == TaskStatus.QUEUED || it.status == TaskStatus.DOWNLOADING ||
                it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING ||
                it.status == TaskStatus.PAUSED
        }
        if (cancellable.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("cancel ALL (${cancellable.size} tasks)")
        cancellable.forEach { task ->
            val taskId = task.id
            // Same teardown as cancel(): mark full-cancel intent BEFORE job
            // cancellation, kill the backends, remove partial artifacts.
            val hadLiveJob = runtimeRegistry.getActiveJob(taskId) != null
            runtimeRegistry.markFullyCancelled(taskId)
            runtimeRegistry.getActiveJob(taskId)?.cancel()
            val entry = runtimeRegistry.get(taskId)
            if (entry != null) {
                entry.activeJob = null
                entry.isStarting.set(false)
            }
            YoutubeDlDownloader.killProcess(taskId)
            // Only gate a Turbo unwind; a queued task's flag would linger forever.
            TurboDownloader.cancelTask(taskId, isFullCancel = hadLiveJob)
            com.anonrode.downloader.util.DebugLog.user("cancel $taskId")
            val live = repository.find(taskId)
            // Cancel only what is STILL cancellable (engine-audit P2): if the
            // task completed between the snapshot and here, purging its
            // artifacts would delete a finished download and remove() would
            // drop its history entry.
            if (live != null && (live.status == TaskStatus.QUEUED || live.status == TaskStatus.DOWNLOADING ||
                    live.status == TaskStatus.RESOLVING || live.status == TaskStatus.VALIDATING ||
                    live.status == TaskStatus.PAUSED)
            ) {
                engineScope.launch {
                    purgeTaskArtifacts(live)
                }
                repository.remove(taskId)
            }
            runtimeRegistry.remove(taskId)
        }
        // Every job was cancelled above, so a global in-flight sweep cannot
        // cross-talk into another task — the condition cancel() relies on.
        if (runtimeRegistry.activeRunningTaskIds().isEmpty()) HttpClient.cancelInFlight()
        updateServiceState(force = true)
        processQueue()
    }
}
