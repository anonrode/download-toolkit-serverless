package com.anonrode.downloader.engine

import android.content.Context
import com.anonrode.downloader.data.settings.AppSettings
import com.anonrode.downloader.security.TorrentSecurityShield
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.File

object YoutubeDlDownloader {

    // Task-level retry counts are now AppSettings-backed (pref_magnet_retries /
    // pref_ytdlp_retries), threaded in via download()'s maxAttempts params.

    // Trackers probed live (2026-08): the 3 dropped entries below were dead
    // (no reply / DNS fail); explodie.org and anirena.com verified alive.
    // anirena is an anime tracker -- relevant for this app's content.
    private val TIER1_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce",
        "http://tracker.anirena.com:80/announce"
    ).joinToString(",")

    /** File extensions that are never the produced media artifact (subtitle
     *  sidecars written by the embed-subs pass). */
    private val SUBTITLE_ARTIFACT_EXTS = setOf("srt", "vtt", "ass", "ssa", "ttml", "json3", "srv3")

    /**
     * Flat playlist metadata dump: `yt-dlp -J --flat-playlist` over the whole
     * list WITHOUT resolving any video page (one paginated metadata call),
     * capped at [cap] entries so a 10k-video mix can't melt the phone. The
     * JSON arrives line-by-line through the progress callback and is stitched
     * back; any failure (binary missing, network, timeout, non-zero exit)
     * returns null — the picker shows an honest error + Retry, and per the
     * round-4 doctrine nothing here hammers the host on its own.
     */
    suspend fun fetchPlaylistJson(context: Context, url: String, cap: Int = 1000): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = StringBuilder()
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("-J")
                    addOption("--flat-playlist")
                    addOption("--playlist-items", "1-$cap")
                    addOption("--no-warnings")
                    addOption("--retries", "1")
                    addOption("--socket-timeout", "15")
                    addOption("--user-agent", com.anonrode.downloader.data.net.HttpClient.DEFAULT_UA)
                }
                withTimeoutOrNull(90_000L) {
                    try {
                        YoutubeDL.getInstance().execute(request, "playlist-meta") { _, _, line ->
                            out.append(line).append('\n')
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    }
                    out.toString()
                }
            } catch (_: Throwable) {
                null
            }
        }

    suspend fun download(
        context: Context,
        taskId: String,
        sourceUrl: String,
        targetDir: File,
        preferredFilename: String,
        backend: String,
        referer: String = "",
        origin: String = "",
        ua: String = "",
        customHeaders: Map<String, String> = emptyMap(),
        parallelSockets: Int = 16,
        quality: String = "720p",
        isExtractorTask: Boolean = false,
        audioOnly: Boolean = false,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        onTorrentFiles: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> List<Int>?)? = null,
        // Tier-A settings (defaults = previous hardcoded behavior)
        magnetMaxAttempts: Int = 3,
        ytdlpMaxAttempts: Int = 3,
        hlsFragments: Int = -1,
        speedLimitKbs: Int = 0,
        torrentPeers: Int = -1,
        // Torrent Privacy Mode: disables DHT/LPD/PEX peer discovery, requires
        // encrypted peer links, caps upload near zero and randomizes the listen
        // port. Trackers alone find peers, so dead-swarm discovery is weaker.
        privacyMode: Boolean = false,
        // Locally rewritten HLS master (scheme-relative segment URLs fixed to
        // absolute https) — yt-dlp is fed the file instead of the original URL.
        hlsMasterFile: String? = null,
        // Seal-parity subtitles (2026-09-14): when true (and not audio-only),
        // extractor downloads fetch subtitles in [subLang] (manual + auto-
        // generated), convert them to SRT and EMBED them in the merged MP4 —
        // one artifact, subs visible in this player and any external one.
        downloadSubs: Boolean = false,
        subLang: String = "en"
    ): File? {
        if (!targetDir.exists()) targetDir.mkdirs()

        val isMagnet = sourceUrl.startsWith("magnet:", ignoreCase = true)
        // When a rewritten master file is supplied, yt-dlp consumes that file
        // (file:// + --enable-file-urls) — the m3u8 branch still applies.
        val inputUrl = if (hlsMasterFile != null) "file://$hlsMasterFile" else sourceUrl
        val isM3u8 = inputUrl.lowercase().contains(".m3u8")
        // Token-locked HLS CDNs (vidsrc family, live-verified) reject segment
        // requests that carry ANY Referer with 403 while serving them fine
        // without one — and the rewritten playlist was validated exactly that
        // way. A rewritten master therefore runs referer-free, EXCEPT for the
        // anitaku CDNs (cdn.watching.onl / fntb0.anivideo.sbs) which are the
        // inverse: they 403 every request that does NOT carry exactly
        // https://megaplay.buzz/ (live-verified 2026-08-22).
        val effReferer = if (hlsMasterFile != null && !requiresExplicitReferer(referer)) "" else referer

        val height = when (quality.lowercase()) {
            "480p", "480" -> 480
            "1080p", "1080" -> 1080
            "4k", "2160p" -> 2160
            else -> 720
        }

        if (isMagnet) {
            // Selective-file pass: probe the swarm for its file list; when more
            // than one safe file exists, ask the UI which to download. A single
            // safe file is auto-selected (no picker). A failed probe (null)
            // falls back to the full download; a probe where EVERY file fails
            // the shield refuses the download entirely.
            var selectIndexes: List<Int>? = null
            if (onTorrentFiles != null) {
                val files = listTorrentFiles(context, sourceUrl, File(preferredFilename).nameWithoutExtension, privacyMode)
                if (files != null) {
                    val safe = files.filter { it.isSafe }
                    when {
                        safe.size > 1 -> selectIndexes = onTorrentFiles(files)
                        safe.size == 1 -> selectIndexes = listOf(safe.first().index)
                        // Probe succeeded but EVERY file failed the shield:
                        // surface the picker's warning state and refuse the
                        // legacy fallback — a silent full download here would
                        // fetch exactly the flagged payload.
                        else -> {
                            val picked = onTorrentFiles(files)
                            if (picked.isNullOrEmpty()) {
                                throw SecurityException(
                                    "All ${files.size} file(s) blocked by security shield — download refused"
                                )
                            }
                            selectIndexes = picked
                        }
                    }
                    if (safe.size != files.size) {
                        android.util.Log.w("AnonDownload",
                            "listTorrentFiles: ${files.size - safe.size} of ${files.size} entries blocked by shield")
                    }
                }
            }
            // isActiveCheck keeps the task-level retry loop from relaunching
            // aria2c after the user paused or cancelled the job. Named arg:
            // speedLimitKbs is now the last parameter, so a trailing lambda
            // would no longer bind to isActiveCheck.
            return downloadMagnetAria2c(
                context, taskId, sourceUrl, targetDir, preferredFilename, parallelSockets, onProgress,
                selectIndexes = selectIndexes,
                maxAttempts = magnetMaxAttempts,
                peersOverride = torrentPeers,
                speedLimitKbs = speedLimitKbs,
                privacyMode = privacyMode,
                isActiveCheck = { coroutineContext.isActive }
            )
        }

        // The yt-dlp/ffmpeg runtime initializes in the app's background
        // bootstrap (AnonApp.appScope): a yt-dlp task enqueued before that
        // finishes would otherwise burn its first attempt on an uninitialized
        // runtime. Magnets above run aria2c directly and don't need it.
        com.anonrode.downloader.AnonApp.ensureReady()

        // Extractor tasks (social/YouTube, where yt-dlp picks its own output
        // name) write into a PRIVATE workdir instead of the shared target
        // folder. Two YouTube jobs downloading into Social/YouTube/ at the
        // same time used to count each other's .part files in the engine's
        // disk-progress scan (task 2's first sample read 33 MB at t+2s — task
        // 1's bytes), which then fired phantom watchdog kills
        // (window=-145334KiB) the moment the sibling completed and renamed
        // its parts away. The produced artifact is moved to the real target
        // folder after a successful run; the engine's computeDiskBytes scans
        // the workdir while it exists.
        val outDir = if (isExtractorTask) File(targetDir, ".work-$taskId").apply { mkdirs() } else targetDir

        val request = YoutubeDLRequest(inputUrl).apply {
            if (hlsMasterFile != null) {
                // The input is our own rewritten playlist file in the app cache.
                addOption("--enable-file-urls")
            }
            if (isExtractorTask) {
                // If caller provided a specific preferredFilename (movies / TV show episodes),
                // use that clean filename stem instead of yt-dlp's generic extractor metadata
                // which can produce garbage like "RBNNj1sIT2 [id].unknown_video".
                // %(title)s [%(id)s] is reserved for social video downloads where the title
                // is unknown in advance.
                val isGenericName = preferredFilename.isBlank() ||
                    preferredFilename.equals("video.mp4", ignoreCase = true) ||
                    preferredFilename.equals("download.mp4", ignoreCase = true) ||
                    preferredFilename.startsWith("Social_", ignoreCase = true)
                val outTemplate = if (!isGenericName) {
                    val stem = File(outDir, preferredFilename.substringBeforeLast('.')).absolutePath
                    val ext = File(preferredFilename).extension.ifBlank { "mp4" }
                    val safeExt = if (audioOnly) "mp3" else if (ext.equals("matroska", ignoreCase = true)) "mkv" else ext
                    "$stem.$safeExt"
                } else {
                    File(outDir, "%(title).100s [%(id)s].%(ext)s").absolutePath
                }
                addOption("-o", outTemplate)
                if (audioOnly) {
                    addOption("-f", "bestaudio/best")
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                } else {
                    addOption("-f", "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/best[height<=$height][ext=mp4]/best[height<=$height]/best")
                    addOption("-S", "height~$height,+size,+br")
                    addOption("--merge-output-format", "mp4")
                    if (downloadSubs) {
                        // Seal parity (2026-09-14): manual AND auto-generated
                        // captions for the configured language, normalized to
                        // SRT (mp4 mov_text) and embedded by the bundled
                        // ffmpeg during the merge pass — the artifact stays a
                        // single file, so the workdir move logic and the
                        // gallery scan never learn about sidecars. Videos
                        // without subtitles are unaffected (yt-dlp just
                        // reports "no subtitles" and moves on).
                        addOption("--write-subs")
                        addOption("--write-auto-subs")
                        addOption("--sub-langs", subLang.ifBlank { "en" })
                        addOption("--convert-subs", "srt")
                        addOption("--embed-subs")
                    }
                }
                addOption("--no-playlist")
                // Embed/watch-page cracks (nepu, social, etc.) often resolve to HLS.
                // Parallel fragments keep multi-socket speed instead of a single
                // rate-capped connection.
                // Floor 1: the engine forces single-socket for hosts that reject
                // multi-connection downloads; clamping the floor to 4 overrode that.
                val frags = if (hlsFragments > 0) hlsFragments.coerceIn(1, 16) else parallelSockets.coerceIn(1, 16)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "2M")
                addOption("--socket-timeout", "15")
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("--retry-sleep", "10")
                addOption("--retry-sleep", "fragment:exp=1:20")
                // Fail loudly instead of letting yt-dlp skip missing fragments
                // and silently produce a video with gaps in it.
                addOption("--abort-on-unavailable-fragments")
                if (speedLimitKbs > 0) addOption("--limit-rate", "${speedLimitKbs}K")
            } else if (isM3u8) {
                // HLS m3u8 stream variant selection with multi-fragment parallel downloading
                val stem = File(outDir, preferredFilename.substringBeforeLast('.')).absolutePath
                val ext = File(preferredFilename).extension.ifBlank { "mp4" }
                val safeExt = if (ext.equals("matroska", ignoreCase = true)) "mkv" else ext
                addOption("-o", "$stem.$safeExt")
                addOption("-f", "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best")
                addOption("-S", "height~$height,+size,+br")
                addOption("--merge-output-format", "mp4")
                addOption("--no-playlist")
                val frags = if (hlsFragments > 0) hlsFragments.coerceIn(1, 16) else parallelSockets.coerceIn(1, 16)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "2M")
                addOption("--socket-timeout", "15")
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("--retry-sleep", "10")
                addOption("--retry-sleep", "fragment:exp=1:20")
                addOption("--abort-on-unavailable-fragments")
                if (speedLimitKbs > 0) addOption("--limit-rate", "${speedLimitKbs}K")
            } else {
                // Direct CDN HTTP multi-socket via aria2c
                val stem = File(outDir, preferredFilename.substringBeforeLast('.')).absolutePath
                val ext = File(preferredFilename).extension.ifBlank { "mp4" }
                val safeExt = if (ext.equals("matroska", ignoreCase = true)) "mkv" else ext
                addOption("-o", "$stem.$safeExt")
                addOption("--downloader", "libaria2c.so")
                val conns = parallelSockets.coerceIn(1, 16)
                val aria2Args = buildString {
                    // --max-tries/--retry-wait mirror the magnet path: without them
                    // aria2c hammers a flaky connection 5x with zero wait.
                    append("aria2c:-x $conns -s $conns -j $conns -k 1M --max-connection-per-server=$conns --split=$conns --min-split-size=1M --continue=true --max-tries=10 --retry-wait=1 --disk-cache=64M")
                    if (origin.isNotBlank()) append(" --header=\"Origin: $origin\"")
                    if (referer.isNotBlank()) append(" --header=\"Referer: $referer\"")
                    if (ua.isNotBlank()) append(" --header=\"User-Agent: $ua\"")
                    append(" --header=\"Accept: video/mp4,video/x-matroska,video/*,*/*\"")
                    append(" --check-certificate=false")
                    append(" --summary-interval=1")
                }
                addOption("--downloader-args", aria2Args)
            }

            addOption("--no-mtime")
            addOption("--no-warnings")
            addOption("--no-check-certificate")
            // --no-restrict-filenames: yt-dlp refuses "unusual" extensions like
            // .mkv by default ("The extracted extension ('matroska') is unusual
            // and will be skipped for safety reasons" — fired 8 times on
            // naijavault→vikingfile .mkv URLs in app-2026-08-29 / 09-01, all
            // returning 0 bytes after the engine had already burned 3-6 minutes
            // cracking the locker URL). Disabling is safe: we always write to
            // our own sanitized path, never to a location derived from the
            // remote filename.
            addOption("--no-restrict-filenames")
            // Remap unsafe/unusual extensions extracted from remote headers (e.g. Content-Type: video/matroska
            // -> ext='matroska', or Content-Type: application/octet-stream -> ext='unknown_video') before
            // yt-dlp's _catch_unsafe_extension_error skips the download.
            addCommands(listOf("--replace-in-metadata", "ext", "(?i)matroska", "mkv"))
            addCommands(listOf("--replace-in-metadata", "ext", "(?i)unknown_video", "mp4"))
            addOption("--newline")
            addOption("--progress")
            // Refuse outside config files: a stray yt-dlp.conf could override
            // -o/-f and silently break the output path (monolith parity).
            addOption("--ignore-config")

            // --progress-template: emit one templated progress line per tick,
            // parsed by ProgressParser's YTDL_TEMPLATE_REGEX.  Two templates
            // are emitted (yt-dlp allows multiple) — the first mirrors
            // yt-dlp's native "[download] X% of Y at Z" format so the
            // existing YTDL_REGEX keeps matching unchanged (belt-and-
            // suspenders for any download where the structured fields
            // fall back to "NA").  The second carries structured fields
            // (percent, speed, eta, fragment_index, fragment_count,
            // downloaded_bytes, total_bytes, total_bytes_estimate) needed
            // for HLS / segmented downloads where the native line is
            // fragment-internal and the engine's existing YTDL_REGEX
            // never matched (dramakey / wetafiles symptom: "Starting..."
            // for 90s, then BAM "DONE").  The @@DLP@@ sentinel is the
            // same one the Python monolith uses.
            addOption(
                "--progress-template",
                "download:[download]  %(progress._percent_str)s of ~%(progress._total_bytes_str)s at %(progress._speed_str)s ETA %(progress._eta_str)s"
            )
            addOption(
                "--progress-template",
                "download:@@DLP@@ %(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.fragment_index)s|%(progress.fragment_count)s|%(progress._downloaded_bytes_str)s|%(progress._total_bytes_str)s|%(progress._total_bytes_estimate_str)s"
            )
            if (effReferer.isNotBlank()) addOption("--referer", effReferer)
            if (ua.isNotBlank()) addOption("--user-agent", ua)

            // Origin header from the referer's base domain, like the monolith's
            // HLS path always sends it.
            val originToPass = origin.ifBlank {
                effReferer.takeIf { it.isNotBlank() }?.let { r ->
                    Regex("""https?://[^/]+""").find(r)?.value ?: ""
                } ?: ""
            }
            if (originToPass.isNotBlank()) addOption("--add-header", "Origin: $originToPass")

            for ((k, v) in customHeaders) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    addOption("--add-header", "$k:$v")
                }
            }
        }

        val before = outDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        val stem = preferredFilename.substringBeforeLast('.')

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        fun attemptOnce(): File? {
            var lastDl = 0L
            var lastTot = 0L

            try {
                YoutubeDL.getInstance().execute(request, taskId) { progress, etaInSeconds, line ->
                    // Parse EVERY stdout line. The library only moves its own
                    // `progress` float when its narrow regexes match —
                    // "[download] X.X% ... ETA MM:SS" (a numeric ETA is
                    // required) or aria2c's "(NN%)" summary — and HLS ticks
                    // report "ETA Unknown" while fragments are still being
                    // measured. The float then stays at its -1 initial, and
                    // the old `if (progress >= 0f)` gate silently dropped the
                    // structured @@DLP@@ lines: the card froze on "Starting…"
                    // and jumped straight to DONE. parseProgressTick is a
                    // no-op on lines it doesn't understand, so feeding it
                    // everything is safe.
                    val tick = parseProgressTick(line, progress, lastDl, lastTot)
                    // Change-detect so the hundreds of non-progress lines
                    // ([info], [Merger], destination notices) never spam the
                    // UI flow with no-op updates.
                    if (tick.downloadedBytes != lastDl || tick.totalBytes != lastTot || tick.speedBytesPerSec > 0.0) {
                        lastDl = tick.downloadedBytes
                        lastTot = tick.totalBytes
                        val eta = when {
                            tick.etaSeconds > 0 -> tick.etaSeconds
                            etaInSeconds > 0 -> etaInSeconds
                            // yt-dlp said nothing, but speed + total are known:
                            // derive it so the card reads like the aria2c CDN
                            // path does (parity with the magnet loop).
                            tick.speedBytesPerSec > 0.0 && lastTot > lastDl ->
                                ((lastTot - lastDl) / tick.speedBytesPerSec).toLong()
                            else -> 0L
                        }
                        onProgress(lastDl, lastTot, tick.speedBytesPerSec, eta)
                    }
                }
            } catch (e: Exception) {
                errors.append("run ").append(attempts).append(": ").append(e.message ?: e.javaClass.simpleName).append('\n')
                // Per-attempt visibility: the aggregate error only lands after
                // ALL attempts, and an HLS stall gave the log nothing to work
                // with (audit finding).
                com.anonrode.downloader.util.DebugLog.backend(
                    "task=$taskId yt-dlp attempt $attempts failed: ${(e.message ?: e.javaClass.simpleName).take(280)}"
                )
                return null
            }

            // yt-dlp's pre-merge format shards ("Title [id].f135.mp4" = the
            // video-only stream, ".f140.m4a" = audio-only) must never be
            // accepted as the produced artifact: the watchdog once killed a
            // task mid-merge (app-2026-08-31 07:13) and attempt 2 picked up
            // the leftover .f135.mp4 — the user got a 138 MiB file with NO
            // audio track. A merged output never carries the .f<NNN> infix,
            // so exclude the shards; when only shards exist the attempt
            // counts as failed and the next one re-runs the merge.
            val formatShard = Regex("""\.f\d+\.[A-Za-z0-9]{2,5}$""")
            // Same guard the magnet path applies below (aria2c contract): a
            // sibling .aria2 control file means the data file is still
            // PARTIAL — the external libaria2c downloader only removes it on
            // successful completion. Without this, an attempt that exits 0
            // while a straggler range request died could hand the engine a
            // truncated file that the structure tier then blesses.
            fun isFinal(f: File) = (f.length() > 0 || f.isDirectory) &&
                !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") &&
                // Subtitle sidecars (write-subs/embed-subs output; embed
                // normally deletes them, a half-finished pass may not) were
                // never artifacts.
                f.extension.lowercase() !in SUBTITLE_ARTIFACT_EXTS &&
                !formatShard.containsMatchIn(f.name) &&
                !(f.isFile && File(f.absolutePath + ".aria2").exists())

            val candidates = outDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()

            val fresh = candidates.filter { it.absolutePath !in before }
            return fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                ?: fresh.maxByOrNull { it.lastModified() }
                ?: candidates.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                // Multi-file downloads (season packs) land as a NEW directory
                // whose File.length() is ~0 — when no file matched, take the
                // most-recently-created directory as the produced artifact.
                ?: outDir.listFiles { f -> f.isDirectory && f.absolutePath !in before }?.maxByOrNull { it.lastModified() }
        }

        // Same-engine retry with resume: yt-dlp's .part (native) and aria2c's
        // .aria2 control file (external downloader) keep progress across runs,
        // so a retry continues instead of restarting. Never relaunch after the
        // job was paused or cancelled.
        while (attempts < ytdlpMaxAttempts && produced == null) {
            attempts++
            if (!coroutineContext.isActive) throw CancellationException("Task was cancelled before yt-dlp retry")
            com.anonrode.downloader.util.DebugLog.backend("task=$taskId yt-dlp attempt $attempts/$ytdlpMaxAttempts url=${inputUrl.take(110)}")
            produced = attemptOnce()
            if (produced != null) {
                val size = produced.length()
                val sizeLabel = if (size >= 1048576) "${size / 1048576} MiB" else "${size / 1024} KiB"
                com.anonrode.downloader.util.DebugLog.backend("task=$taskId yt-dlp attempt $attempts produced ${produced.name} ($sizeLabel)")
            }
            if (produced == null && attempts < ytdlpMaxAttempts) {
                cancellableRetryWait(2_000L * attempts)
            }
        }
        // IG-1 fallback: an Instagram *photo-with-music* post produces NO video
        // format, so every yt-dlp attempt above has already failed (fast — the
        // extractor throws before any bytes move). yt-dlp will never download
        // this shape (maintainer-declared out-of-scope), so mux cover+audio
        // with the bundled ffmpeg instead. Strict fallback: only fires once
        // yt-dlp has failed AND the URL is an Instagram post, so working
        // videos and every other social site are untouched. A null return (not
        // a photo+music, IG gated us, or encode failed) falls through to the
        // original yt-dlp error below.
        if (produced == null && isExtractorTask && InstagramPhotoMuxer.shortcodeFromUrl(sourceUrl) != null) {
            com.anonrode.downloader.util.DebugLog.backend("task=$taskId yt-dlp exhausted on an Instagram post URL — trying photo+music muxer")
            // Capture the Job in the SUSPEND body: CoroutineContext.isActive is
            // a suspend property and cannot be read from the plain
            // () -> Boolean callback below.
            val muxJob = coroutineContext[kotlinx.coroutines.Job]
            produced = InstagramPhotoMuxer.tryMux(
                context = context,
                sourceUrl = sourceUrl,
                outDir = outDir,
                taskId = taskId,
                isCancelled = { muxJob?.isActive != true }
            )
        }
        if (produced == null && errors.isNotBlank()) {
            com.anonrode.downloader.util.DebugLog.error("task=$taskId yt-dlp failed after $attempts attempt(s): ${errors.toString().take(300)}")
            throw Exception("yt-dlp failed after $attempts attempt(s): ${errors.toString().trim()}")
        }
        // Move the artifact out of the private workdir into the user-visible
        // target folder. renameTo within the same volume is free; copy+delete
        // only as a cross-volume fallback. The workdir is removed when empty
        // so a folder of one-shot YouTube jobs doesn't accumulate dot-dirs.
        if (produced != null && outDir != targetDir) {
            val isGenericName = preferredFilename.isBlank() ||
                preferredFilename.equals("video.mp4", ignoreCase = true) ||
                preferredFilename.equals("download.mp4", ignoreCase = true) ||
                preferredFilename.startsWith("Social_", ignoreCase = true)

            val baseStem = if (!isGenericName) {
                File(preferredFilename).nameWithoutExtension
            } else {
                produced.nameWithoutExtension
            }

            var finalExt = produced.extension
            if (finalExt.equals("matroska", ignoreCase = true)) {
                finalExt = "mkv"
            } else if (finalExt.equals("unknown_video", ignoreCase = true) || finalExt.isBlank()) {
                val sniffed = try {
                    java.io.RandomAccessFile(produced, "r").use { raf ->
                        val head = ByteArray(16)
                        val read = raf.read(head)
                        if (read >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) {
                            "mkv"
                        } else if (read >= 8 && String(head, 4, 4, Charsets.US_ASCII).lowercase() in
                            setOf("ftyp", "moov", "mdat", "free", "wide", "skip", "moof", "styp")) {
                            "mp4"
                        } else null
                    }
                } catch (_: Exception) { null }
                finalExt = sniffed ?: if (!isGenericName) File(preferredFilename).extension.ifBlank { "mp4" } else "mp4"
            }

            var dest = File(targetDir, "$baseStem.$finalExt")
            var n = 1
            while (dest.exists()) {
                dest = File(targetDir, "$baseStem ($n).$finalExt")
                n++
            }
            val movedOk = if (produced.isDirectory) produced.renameTo(dest)
            else {
                if (produced.renameTo(dest)) true
                else {
                    try {
                        produced.copyTo(dest, overwrite = true)
                        produced.delete()
                        true
                    } catch (_: Exception) { false }
                }
            }
            if (movedOk) produced = dest
            else {
                // Engine-audit P2: returning the in-workdir artifact let the
                // engine report COMPLETED for a file inside a hidden dot-dir
                // the media scanner ignores (and a later cancel would delete
                // the very file that was called complete). Throw instead — the
                // engine's failure path parks tasks with banked bytes, so the
                // finished bytes stay on disk and the retry resumes from them.
                com.anonrode.downloader.util.DebugLog.error(
                    "task=$taskId yt-dlp artifact left in workdir (move failed): ${produced.absolutePath}"
                )
                throw java.io.IOException(
                    "Download finished, but it could not be moved into your folder (storage full or access lost). Free some space, then tap retry."
                )
            }
            outDir.listFiles()?.takeIf { it.isEmpty() }?.let { outDir.delete() }
        }
        return produced
    }

    private val activeNativeProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    fun killProcess(taskId: String) {
        com.anonrode.downloader.util.DebugLog.backend("task=$taskId killProcess (native teardown)")
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        activeNativeProcesses.remove(taskId)?.let { p ->
            try {
                p.destroy()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    p.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Fetch a magnet's file list from the swarm WITHOUT downloading any data.
     *
     * Two phases (--show-files only reads real .torrent files, not magnets):
     *   1. `--bt-metadata-only --bt-save-metadata` fetches metadata and writes
     *      `<hex-infohash>.torrent` into a cache dir (no data downloaded).
     *   2. `--show-files <torrent>` prints the table; each entry is run through
     *      [TorrentSecurityShield.checkTorrentFileEntry] (Layers 2/6 + traversal),
     *      so blocked/unsafe files are flagged and never selectable.
     *
     * Returns null when metadata can't be fetched (dead swarm, timeout) — the
     * caller should fall back to a full download rather than fail the task.
     */
    suspend fun listTorrentFiles(
        context: Context,
        magnetUrl: String,
        parentTitle: String = "",
        privacyMode: Boolean = false
    ): List<TorrentSecurityShield.TorrentFileEntry>? {
        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")

        // ---- Phase 1: metadata -> .torrent (no data) ----
        val metaDir = File(context.cacheDir, "torrent_meta").apply { mkdirs() }
        val infoHash = Regex("""(?i)btih:([a-f0-9]{40})""").find(magnetUrl)?.groupValues?.get(1)
            ?: return null
        val torrentFile = File(metaDir, "$infoHash.torrent")

        // Reuse an already-cached .torrent when present (instant list, no swarm contact).
        if (!torrentFile.exists()) {
            val fetchCmd = mutableListOf(aria2Exec.absolutePath)
            if (privacyMode) {
                // Metadata via trackers only: no DHT announce that would put
                // this IP into the swarm's peer-discovery tables.
                fetchCmd += "--enable-dht=false"
                fetchCmd += "--bt-enable-lpd=false"
                fetchCmd += "--enable-peer-exchange=false"
                fetchCmd += "--bt-require-crypto=true"
            } else {
                fetchCmd += "--enable-dht=true"
                fetchCmd += "--bt-enable-lpd=true"
                fetchCmd += "--enable-peer-exchange=true"
                fetchCmd += "--dht-entry-point=router.bittorrent.com:6881"
                fetchCmd += "--dht-entry-point=dht.transmissionbt.com:6881"
                fetchCmd += "--dht-entry-point=dht.libtorrent.org:25401"
                fetchCmd += "--dht-entry-point6=[2400:cb00:2049:1::a29f:9877]:6881"
                fetchCmd += "--dht-file-path=${context.cacheDir.absolutePath}/dht.dat"
            }
            fetchCmd += listOf(
                "--seed-time=0",
                "--seed-ratio=0.0",
                "--summary-interval=0",
                "--bt-max-peers=64",
                "--bt-request-peer-speed-limit=50M",
                "--bt-stop-timeout=45",
                "--bt-tracker-connect-timeout=10",
                "--bt-tracker-timeout=10",
                "--bt-tracker=$TIER1_TRACKERS",
                "--bt-metadata-only=true",
                "--bt-save-metadata=true",
                "-d", metaDir.absolutePath,
                magnetUrl
            )
            val fetchPb = ProcessBuilder(fetchCmd)
            fetchPb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            fetchPb.redirectErrorStream(true)
            val fetchProc = try {
                fetchPb.start()
            } catch (e: Exception) {
                android.util.Log.w("AnonDownload", "listTorrentFiles: launch failed: ${e.message}")
                return null
            }
            val fetchOk = withTimeoutOrNull(45_000) {
                fetchProc.waitFor()
            } ?: run {
                fetchProc.destroy()
                return null
            }
            if (fetchOk != 0 || !torrentFile.exists()) {
                android.util.Log.w("AnonDownload", "listTorrentFiles: metadata fetch failed (code=$fetchOk)")
                return null
            }
        }

        // ---- Phase 2: --show-files on the .torrent ----
        val showCmd = listOf(
            aria2Exec.absolutePath,
            "--show-files=true",
            torrentFile.absolutePath
        )
        val showPb = ProcessBuilder(showCmd)
        showPb.redirectErrorStream(true)
        val showProc = try {
            showPb.start()
        } catch (e: Exception) {
            android.util.Log.w("AnonDownload", "listTorrentFiles: show-files launch failed: ${e.message}")
            return null
        }
        val output = withTimeoutOrNull(15_000) {
            showProc.inputStream.bufferedReader().readText()
        } ?: run {
            showProc.destroy()
            return null
        }

        // aria2c --show-files prints two lines per entry (verified live):
        //   "  1|./Sintel/Sintel.de.srt"      then "   |1.6KiB (1,652)"
        val entries = mutableListOf<TorrentSecurityShield.TorrentFileEntry>()
        val pathRe = Regex("""^\s*(\d+)\|(.+)$""")
        val sizeRe = Regex("""\(\s*([\d,]+)\s*\)$""")
        val lines = output.lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val pm = pathRe.matchEntire(lines[i].trimEnd())
            if (pm != null) {
                val index = pm.groupValues[1].toInt()
                val path = pm.groupValues[2]
                var length = 0L
                if (i + 1 < lines.size) {
                    sizeRe.find(lines[i + 1])?.let { m ->
                        length = m.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    }
                }
                val checked = TorrentSecurityShield.checkTorrentFileEntry(path, length, parentTitle)
                entries.add(checked.copy(index = index))
                i += 2
                continue
            }
            i += 1
        }

        if (entries.isEmpty()) {
            android.util.Log.w("AnonDownload", "listTorrentFiles: no entries parsed")
            return null
        }
        return entries
    }

    private suspend fun downloadMagnetAria2c(
        context: Context,
        taskId: String,
        magnetUrl: String,
        targetDir: File,
        preferredFilename: String,
        parallelSockets: Int = 16,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        isActiveCheck: suspend () -> Boolean = { true },
        selectIndexes: List<Int>? = null,
        maxAttempts: Int = 3,
        peersOverride: Int = -1,
        speedLimitKbs: Int = 0,
        privacyMode: Boolean = false
    ): File? {
        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")
        val cmd = mutableListOf(aria2Exec.absolutePath)
        if (privacyMode) {
            // qBittorrent anonymous-mode lessons: your IP stops propagating
            // through peer-discovery gossip (DHT/LPD/PEX off), peer links must
            // be encrypted, and the listen port leaves the fingerprintable
            // 6881 band. Trackers alone still find peers.
            cmd += "--enable-dht=false"
            cmd += "--bt-enable-lpd=false"
            cmd += "--enable-peer-exchange=false"
            cmd += "--bt-require-crypto=true"
            // aria2c reads 0 as UNLIMITED here, so cap at 1K instead — near-zero
            // upload without breaking swarm etiquette mid-download.
            cmd += "--max-overall-upload-limit=1K"
            // Per-task ephemeral port range; hash spreads concurrent tasks.
            val base = 49152 + (taskId.hashCode() and 0x7FFFFFFF) % 8000
            cmd += "--listen-port=$base-${base + 31}"
        } else {
            cmd += listOf(
                "--enable-dht=true",
                "--bt-enable-lpd=true",
                "--enable-peer-exchange=true",
                // Live-verified 2026-08: router.bittorrent.com does not answer DHT
                // pings from some networks; transmissionbt + libtorrent.org do.
                "--dht-entry-point=router.bittorrent.com:6881",
                "--dht-entry-point=dht.transmissionbt.com:6881",
                "--dht-entry-point=dht.libtorrent.org:25401",
                "--dht-entry-point6=[2400:cb00:2049:1::a29f:9877]:6881",
                // Android has no $HOME, so aria2c's default ~/.aria2/dht.dat never
                // persists: every launch cold-starts an empty DHT table. Pin it into
                // cacheDir so the routing table survives across downloads AND runs.
                "--dht-file-path=${context.cacheDir.absolutePath}/dht.dat"
            )
        }
        cmd += listOf(
            "--seed-time=0",
            "--seed-ratio=0.0",
            "--summary-interval=1",
            // Concrete user override wins; Auto (-1) resolves to the live RAM
            // tier so the Settings label "Auto (RAM-detected)" is truthful
            // (it used to fall back to a connections-based formula instead).
            "--bt-max-peers=${if (peersOverride > 0) peersOverride else AppSettings.detectRamTier(context)}",
            "--bt-request-peer-speed-limit=50M",
            "--bt-stop-timeout=300",
            // Fail fast on dead trackers: 30s connect + 60s default announce
            // timeout burned a minute per dead tracker before.
            "--bt-tracker-connect-timeout=10",
            "--bt-tracker-timeout=10",
            // This is the magnet-only path: allocate lazily — falloc would
            // reserve the FULL torrent size the instant a magnet starts (8GB+
            // season = instant storage shock, and a big hole if the swarm is
            // dead). Sparse growth costs nothing.
            "--file-allocation=none",
            "--check-certificate=false",
            "--continue=true",
            "--max-tries=10",
            "--retry-wait=1",
            "--follow-torrent=mem",
            "--bt-save-metadata=true",
            "--bt-load-saved-metadata=true",
            "--auto-save-interval=15",
            "--allow-overwrite=false",
            "--console-log-level=error",
            "--bt-tracker=$TIER1_TRACKERS",
            "-d", targetDir.absolutePath
        )
        // Selective download (season packs): --select-file takes only numeric
        // indexes, never names -- the swarm's file paths never reach the command
        // line, so there is no injection surface from untrusted torrent names.
        if (!selectIndexes.isNullOrEmpty()) {
            cmd += "--select-file=" + selectIndexes.joinToString(",")
        }
        if (speedLimitKbs > 0) {
            cmd += "--max-download-limit=${speedLimitKbs}K"
        }
        // Single magnet URI, always last: aria2c binds options to the downloads
        // that follow them, so the URI must come after --select-file etc. (it
        // was previously passed twice, which orphaned those flags on a second job).
        cmd += magnetUrl

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        fun runOnce(): File? {
            val pb = ProcessBuilder(cmd)
            // Statically linked binary (see NOTICE): no LD_LIBRARY_PATH/PATH needed.
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.directory(targetDir)
            pb.redirectErrorStream(true)
            val process = pb.start()

            if (Thread.currentThread().isInterrupted) {
                process.destroy()
                throw InterruptedException("Task was cancelled before process started")
            }

            activeNativeProcesses[taskId] = process

            // aria2c summary lines put sizes before the percentage: [#d5f4b1 45MiB/65MiB(69%) CN:8 DL:3.8MiB ETA:5s]
            val progressRegex = Regex("""([\d.]+[KMGT]?i?B)/([\d.]+[KMGT]?i?B)\([\d.]+%\).*?DL:\s*([\d.]+[KMGT]?i?B(?:/s)?)""", RegexOption.IGNORE_CASE)
            val fallbackRegex = Regex("""\((\d+)%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)
            val reader = process.inputStream.bufferedReader()
            val logBuffer = mutableListOf<String>()

            var exitCode: Int? = null
            try {
                var line: String? = reader.readLine()
                while (line != null) {
                    if (logBuffer.size < 50) {
                        logBuffer.add(line)
                    } else {
                        logBuffer.removeAt(0)
                        logBuffer.add(line)
                    }
                    val match = progressRegex.find(line)
                    if (match != null) {
                        val dl = parseByteString(match.groupValues[1])
                        val tot = parseByteString(match.groupValues[2])
                        val spd = parseSpeedString(match.groupValues[3])
                        val eta = if (spd > 0 && tot > dl) ((tot - dl) / spd).toLong() else 0L
                        onProgress(dl, tot, spd, eta)
                    } else {
                        val fb = fallbackRegex.find(line)
                        if (fb != null) {
                            val spd = parseSpeedString(fb.groupValues[2])
                            // total=0 AND downloaded=0 on purpose: a percentage
                            // carries no byte information. Passing pct in the
                            // downloaded slot used to stamp "50 bytes" into the
                            // task (and flipped RESOLVING->DOWNLOADING off a
                            // number that never counted anything);
                            // repository.updateProgress already ignores zeros,
                            // so this line contributes SPEED only. A synthetic
                            // total likewise overwrote the real multi-GB total
                            // and made the "transfer complete" check
                            // (fileSize >= totalBytes) vacuously true.
                            onProgress(0L, 0L, spd, 0L)
                        }
                    }
                    line = reader.readLine()
                }
                exitCode = process.waitFor()
            } catch (e: Exception) {
                process.destroy()
                errors.append("run ").append(attempts).append(": ").append(e.message ?: e.javaClass.simpleName).append('\n')
                return null
            } finally {
                activeNativeProcesses.remove(taskId)
            }

            if (exitCode != 0) {
                val errSummary = logBuffer.takeLast(3).joinToString(" | ")
                errors.append("run ").append(attempts).append(" exit ").append(exitCode).append(": ").append(errSummary).append('\n')
                return null
            }

            fun isFinal(f: File) = (f.length() > 0 || f.isDirectory) &&
                    !f.name.endsWith(".aria2") && !f.name.endsWith(".part") &&
                    !f.name.endsWith(".ytdl") && !f.name.endsWith(".torrent")

            val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()
            val fresh = candidates.filter { it.absolutePath !in before }
            val stem = preferredFilename.substringBeforeLast('.')
            val found = fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                ?: fresh.maxByOrNull { it.lastModified() }
                // A resumed aria2 run (--continue=true) keeps writing the SAME
                // file that existed before this attempt, so a legitimate result
                // is not always "fresh" — but it must still carry the name we
                // asked aria2 for. The previous "newest file in the folder"
                // fallback here could bless an UNRELATED finished episode that
                // already lived in the season directory and report the torrent
                // as a success, so the folder-wide match is by stem only.
                ?: candidates.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                // Multi-file torrents (season packs) land as a NEW directory
                // whose File.length() is ~0 — when no file matched, take the
                // most-recently-created directory as the produced artifact.
                ?: targetDir.listFiles { f -> f.isDirectory && f.absolutePath !in before }?.maxByOrNull { it.lastModified() }

            // A sibling .aria2 control file marks the data file as still partial
            // (resume bookkeeping), so never treat it as a finished download.
            if (found != null && !File(found.absolutePath + ".aria2").exists()) return found

            errors.append("run ").append(attempts).append(": no complete file (control file still present)\n")
            return null
        }

        // Task-level retry with resume: the .aria2 control file keeps aria2c's
        // per-piece progress across runs, so a retry continues instead of
        // restarting (BitTorrent piece-queue parity). Never relaunch after the
        // job was paused or cancelled.
        while (attempts < maxAttempts && produced == null) {
            attempts++
            if (!isActiveCheck()) throw CancellationException("Task was cancelled before magnet retry")
            produced = runOnce()
            if (produced == null && attempts < maxAttempts) {
                cancellableRetryWait(2_000L * attempts, isActiveCheck = isActiveCheck)
            }
        }
        if (produced == null) {
            throw Exception("aria2c failed after $attempts attempt(s): ${errors.toString().trim()}")
        }
        return produced
    }

    private fun findAria2Executable(context: Context): File? {
        val libFile = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
        if (libFile.exists()) {
            try { libFile.setExecutable(true) } catch (_: Exception) {}
            return libFile
        }
        return null
    }

    /** CDN families that 403 WITHOUT an exact referer (inverse of the
     *  referer-suppression family) — rewritten-master runs must keep it. */
    private fun requiresExplicitReferer(referer: String): Boolean {
        return referer.equals("https://megaplay.buzz/", ignoreCase = true)
    }
}
