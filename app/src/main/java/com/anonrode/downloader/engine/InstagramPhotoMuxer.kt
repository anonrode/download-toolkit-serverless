package com.anonrode.downloader.engine

import android.content.Context
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.util.DebugLog
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger

/**
 * IG-1: Instagram *photo-with-music* posts are rejected by yt-dlp by design.
 * The extractor builds formats only from `video_versions`/DASH, so a still
 * image carrying a licensed music track yields ZERO formats and yt-dlp dies
 * with "No video formats found" — a maintainer-confirmed out-of-scope case
 * (yt-dlp#16328: "downloading photos is out-of-scope"). Reels-with-music are
 * NOT this bug: they arrive server-muxed with audio and download fine.
 *
 * The asset we need does exist, though: the mobile-style REST detail endpoint
 *   GET api/v1/media/&lt;pk&gt;/info/
 * carries, even logged out,
 *   music_metadata.music_info.music_asset_info.{progressive_download_url,
 *     cover_artwork_uri, id, duration_in_ms, title, display_artist}
 * (+music_consumption_info fallback) — gallery-dl downloads exactly these.
 * A live probe on 2026-09-13 settled an open question the design phase got
 * wrong: the desktop graphql doc (PolarisLoggedOutDesktopWWWPostRootContentQuery,
 * doc_id 27130156389949648 — yt-dlp's surface) does NOT select the music
 * fields at all (a reel with audible music shows clips_metadata.music_info
 * = null and zero music_asset_info occurrences), so REST is queried FIRST.
 * The photo itself comes from image_versions2.candidates; graphql and the
 * RelayPrefetched HTML blob remain fallbacks for the photo shape.
 *
 * Whether anonymous REST answers from a phone's residential IP (vs the SPA
 * shell this PC's datacenter IP gets) cannot be proven off-device — the
 * parser is defensive either way. Everything here is a STRICT FALLBACK —
 * invoked only after yt-dlp has already failed on an Instagram post URL, so
 * working videos are never touched and a photo we cannot handle falls
 * through to yt-dlp's original error: clean failure, no fabricated video.
 */
object InstagramPhotoMuxer {

    /** Base64 alphabet (`0-9` before `-`/`_`) + the `id_to_pk` 28-char cut,
     *  mirroring yt-dlp's `_ENCODING_CHARS` and `_id_to_pk`. */
    internal const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    internal const val GRAPHQL_URL = "https://www.instagram.com/api/graphql"
    internal const val DOC_ID = "27130156389949648"
    internal const val FRIENDLY = "PolarisLoggedOutDesktopWWWPostRootContentQuery"
    private const val POST_BASE = "https://www.instagram.com/"
    // Hard cap for one ffmpeg encode of a still image + ≤32MB audio. A 90s
    // budget is generous on phone hardware for this workload; past it the
    // child is wedged and must not outlive the task.
    private const val FFMPEG_MAX_MS = 90_000L

    // /p/ (photo or legacy video), /reel/, /reels/, /tv/. Photos are /p/.
    private val SHORTCODE = Regex(
        """instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    // LSD token, the two sources yt-dlp knows (eqmc JSON `l` field + the inline
    // require call), tried in order.
    private val LSD_EQMC = Regex(
        """<script\b[^>]*\bid="__eqmc"[^>]*>(\{.*?\})</script>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val LSD_TOKEN = Regex("""\["LSD",\[\],\{"token":"([^"]+)"""")
    // RelayPrefetched media blob lives in `<script data-sjs>{...}</script>`.
    private val SJS = Regex(
        """<script\b[^>]+\bdata-sjs>(\{.+?\})</script>""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** The extracted, decided media. `hasVideo` short-circuits the mux. */
    internal data class MediaParts(
        val photoUrl: String,
        val audioUrl: String,
        val durationMs: Long,
        val title: String?,
        val artist: String?,
        val caption: String?,
        val hasVideo: Boolean
    )

    // ------------------------------------------------------------------ pure

    fun shortcodeFromUrl(url: String): String? =
        SHORTCODE.find(url)?.groupValues?.get(1)?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /**
     * Shortcode → numeric media pk, base64-decoded (yt-dlp `decode_base_n` uses
     * the ALPHABET LENGTH as the base = 64) with the trailing 28-char checksum
     * stripped. BigInteger on purpose: modern 11-char shortcodes decode past
     * Long.MAX (64^11 ≈ 5e19), so a Long accumulator silently overflows and
     * every post-2019 photo would look up the wrong media.
     */
    fun idToPk(shortcode: String): String? {
        val code = if (shortcode.length > 28) shortcode.substring(0, shortcode.length - 28) else shortcode
        if (code.isEmpty()) return null
        var n = BigInteger.ZERO
        val base = BigInteger.valueOf(64)
        for (c in code) {
            val d = B64.indexOf(c)
            if (d < 0) return null // not a valid shortcode char
            n = n.multiply(base).add(BigInteger.valueOf(d.toLong()))
        }
        return n.toString()
    }

    fun extractLsdToken(html: String): String? {
        LSD_EQMC.find(html)?.groupValues?.get(1)?.let { blob ->
            try {
                val l = JSONObject(blob).optString("l")
                if (l.isNotBlank()) return l
            } catch (_: Exception) {}
        }
        return LSD_TOKEN.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    fun graphqlForm(pk: String, lsd: String): Map<String, String> = mapOf(
        "lsd" to lsd,
        "fb_api_caller_class" to "RelayModern",
        "fb_api_req_friendly_name" to FRIENDLY,
        "server_timestamps" to "true",
        "variables" to JSONObject().put("media_id", pk).toString(),
        "doc_id" to DOC_ID
    )

    /**
     * Media objects live at different roots across the logged-out surfaces
     * (graphql `data.xig_polaris_media.if_not_gated_logged_out`, the RelayPrefetched
     * cache's nested `__bbox.result.data`, and older `entry_data`). Rather than
     * hard-code one path that breaks when IG reshuffles, collect EVERY object
     * that has media fingerprints (`image_versions2` / `video_versions` /
     * `carousel_media` / `music_metadata`), bounded so a hostile blob can't
     * blow the stack or time.
     */
    internal fun findMediaObjects(root: Any?, out: MutableList<JSONObject> = ArrayList()): List<JSONObject> {
        val stack = ArrayDeque<Pair<Any?, Int>>()
        stack.addLast(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < 20000) {
            val (node, depth) = stack.removeLast()
            visited++
            if (depth > 40) continue
            when (node) {
                is JSONObject -> {
                    if (looksLikeMedia(node)) out.add(node)
                    for (k in node.keys()) stack.addLast(node.opt(k) to depth + 1)
                }
                is JSONArray -> for (i in 0 until node.length()) stack.addLast(node.opt(i) to depth + 1)
                else -> {}
            }
        }
        return out
    }

    private fun looksLikeMedia(o: JSONObject): Boolean =
        o.has("image_versions2") || o.has("video_versions") ||
            o.has("carousel_media") || (o.has("pk") && (o.has("code") || o.has("taken_at")))

    /** Decide the muxable shape from a media object; null when not applicable. */
    internal fun mediaToParts(m: JSONObject): MediaParts? {
        val videoVersions = m.optJSONArray("video_versions")
        val hasVideo = videoVersions != null && videoVersions.length() > 0
        // A real video (even if yt-dlp choked on gating) is not a photo+music:
        // never fabricate a still from it — let yt-dlp's own error stand.
        if (hasVideo) return MediaParts("", "", 0, null, null, null, hasVideo = true)

        // A photo carousel keeps its images in `carousel_media` CHILDREN while
        // the music_metadata rides the post itself — gallery-dl reads them
        // from exactly those two places (`post["carousel_media"]` items +
        // `post["music_metadata"]`, instagram.py). Requiring both on one
        // object made every carousel-with-music unmuxable; the first photo
        // child becomes the cover instead.
        val photoUrl = bestCandidate(m.optJSONObject("image_versions2")?.optJSONArray("candidates"))
            ?: firstCarouselPhoto(m)
            ?: return null
        val audio = musicAssetInfo(m)
        val audioUrl = audio?.optString("progressive_download_url")?.takeIf { it.isNotBlank() } ?: ""
        val durationMs = audio?.optLong("duration_in_ms", 0L)?.coerceAtLeast(0L) ?: 0L
        val title = audio?.optString("title")?.takeIf { it.isNotBlank() }
        val artist = audio?.let { (it.optString("display_artist").ifBlank { it.optString("ig_artist") }).takeIf { a -> a.isNotBlank() } }
        val caption = m.optJSONObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }
            ?: m.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)
                ?.optJSONObject("node")?.optString("text")?.takeIf { it.isNotBlank() }
        return MediaParts(photoUrl, audioUrl, durationMs, title, artist, caption, hasVideo = false)
    }

    private fun bestCandidate(candidates: JSONArray?): String? {
        if (candidates == null || candidates.length() == 0) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            val url = c.optString("url").takeIf { it.isNotBlank() } ?: continue
            val w = c.optInt("width", 0)
            if (w > bestW) { bestW = w; best = url }
        }
        return best
    }

    /** First PHOTO child of a carousel — video children are skipped on the
     *  same principle as the top-level hasVideo guard: a real video is not a
     *  still, so it can never serve as the fabricated cover. */
    private fun firstCarouselPhoto(m: JSONObject): String? {
        val carousel = m.optJSONArray("carousel_media") ?: return null
        for (i in 0 until carousel.length()) {
            val child = carousel.optJSONObject(i) ?: continue
            val v = child.optJSONArray("video_versions")
            if (v != null && v.length() > 0) continue
            bestCandidate(child.optJSONObject("image_versions2")?.optJSONArray("candidates"))?.let { return it }
        }
        return null
    }

    /** music_metadata.music_info.music_asset_info, with the consumption-info
     *  sibling as a fallback when the asset node is absent. */
    private fun musicAssetInfo(m: JSONObject): JSONObject? {
        val mm = m.optJSONObject("music_metadata") ?: return null
        val mi = mm.optJSONObject("music_info") ?: mm
        return mi.optJSONObject("music_asset_info") ?: mi.optJSONObject("music_consumption_info")
    }

    /** Parse a candidate media set into the first muxable photo+music. */
    internal fun pickMuxable(objects: List<JSONObject>): MediaParts? {
        for (o in objects) {
            val parts = mediaToParts(o) ?: continue
            if (parts.hasVideo) continue
            if (parts.photoUrl.isNotBlank()) return parts
        }
        return null
    }

    /** Filename: caption (or a slug fallback) + shortcode, filesystem-safe.
     *  The label goes through the app-wide NameSanitizer standard (same noise
     *  + safety policy as every other saved name); the "[shortcode]" suffix
     *  is appended AFTER, since brackets are what the sanitizer strips from
     *  scraped text. Captions are user prose: a blank one means "Instagram",
     *  not the generic "Download" fallback. */
    internal fun buildFilename(shortcode: String, parts: MediaParts): String {
        val source = (parts.caption ?: parts.title?.let { t -> parts.artist?.let { a -> "$t — $a" } ?: t })
            ?: "Instagram"
        val label = if (source.isBlank()) "Instagram"
            else com.anonrode.downloader.util.NameSanitizer.savedName(source, 80, stripNoise = false)
        return "$label [$shortcode].mp4"
    }

    /** ffmpeg arg vectors for a still-image mux, best codec first, then the
     *  universally-present mpeg4 fallback. Split out for exact unit testing. */
    internal fun ffmpegVariants(libPath: String, cover: String, audio: String?, out: String): List<List<String>> {
        val scale = "scale=trunc(iw/2)*2:trunc(ih/2)*2" // libx264 rejects odd pixel dims
        val common = mutableListOf("-hide_banner", "-loglevel", "error", "-y")
        if (!audio.isNullOrBlank()) {
            common.addAll(listOf("-framerate", "1", "-loop", "1", "-i", cover, "-i", audio,
                "-vf", scale, "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest"))
        } else {
            common.addAll(listOf("-framerate", "1", "-loop", "1", "-i", cover, "-t", "3",
                "-vf", scale, "-pix_fmt", "yuv420p"))
        }
        val x264 = ArrayList(common).apply { addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage")) }
        val mpeg4 = ArrayList(common).apply { addAll(listOf("-c:v", "mpeg4", "-vtag", "xvid", "-q:v", "4")) }
        x264.add(out); mpeg4.add(out)
        return listOf(listOf(libPath) + x264, listOf(libPath) + mpeg4)
    }

    // -------------------------------------------------------------- network

    /** The ONLY logged-out surface that carries `music_metadata` (gallery-dl's
     *  audio comes from here). The desktop graphql query does not select the
     *  music fields at all — probed live 2026-09-13: the response for a reel
     *  that audibly HAS music contains zero occurrences of music_asset_info /
     *  progressive_download_url, and photo/carousel nodes have no
     *  music_metadata key. So REST is tried first; graphql stays as the
     *  photo-shape fallback and the sjs HTML blob as last resort. */
    internal fun restInfoUrl(pk: String): String = "https://www.instagram.com/api/v1/media/$pk/info/"

    /** Fetch the post and decide whether it is a muxable photo+music. Never
     *  throws on network errors — returns null so the caller surfaces yt-dlp's
     *  original message. */
    internal suspend fun probeMedia(shortcode: String, isCancelled: () -> Boolean): MediaParts? {
        val url = "$POST_BASE/p/$shortcode/"
        val html = HttpClient.getText(url, referer = POST_BASE, tag = "instagram",
            maxBytes = HttpClient.MAX_TEXT_BYTES) ?: return null
        if (isCancelled()) throw CancellationException("IG mux cancelled after page fetch")

        val lsd = extractLsdToken(html)
        val pk = idToPk(shortcode)

        // 1) mobile-style REST detail — where the music asset actually lives.
        // A shell/HTML reply (this happens from datacenter IPs) is not JSON and
        // falls through; a phone on residential data can get real JSON here.
        if (pk != null) {
            val restHeaders = LinkedHashMap<String, String>().apply {
                put("X-IG-App-ID", "936619743392459")          // web app id, as gallery-dl sends
                put("X-ASBD-ID", "129477")                     // gallery-dl's v1 API pair for the app id
                put("X-Requested-With", "XMLHttpRequest")
                put("Accept", "application/json, text/plain, */*")
                HttpClient.cookieValue("csrftoken", "www.instagram.com")?.let { put("X-CSRFToken", it) }
                // Logged-out default is the literal "0" (gallery-dl Extractor
                // init); a real cookie value, when we have one, supersedes it.
                put("X-IG-WWW-Claim", HttpClient.cookieValue("www-claim", "www.instagram.com") ?: "0")
            }
            val rest = HttpClient.getText(restInfoUrl(pk), referer = url, headers = restHeaders,
                tag = "instagram", maxBytes = HttpClient.MAX_TEXT_BYTES)
            if (isCancelled()) throw CancellationException("IG mux cancelled after rest")
            if (!rest.isNullOrBlank() && rest.trimStart().startsWith("{")) {
                try {
                    val parts = pickMuxable(findMediaObjects(JSONObject(rest)))
                    if (parts != null) return parts
                } catch (_: Exception) {}
            }
        }

        // 2) logged-out GraphQL (photo/carousel shape, older clients' baked data).
        if (lsd != null && pk != null) {
            val headers = LinkedHashMap<String, String>().apply {
                put("X-FB-Friendly-Name", FRIENDLY)
                put("X-FB-LSD", lsd)
                put("X-Requested-With", "XMLHttpRequest")
                put("Accept", "application/json, text/plain, */*")
                HttpClient.cookieValue("csrftoken", "www.instagram.com")?.let { put("X-CSRFToken", it) }
            }
            val resp = HttpClient.postForm(GRAPHQL_URL, graphqlForm(pk, lsd),
                referer = url, headers = headers, tag = "instagram")
            if (isCancelled()) throw CancellationException("IG mux cancelled after graphql")
            if (!resp.isNullOrBlank() && resp.trimStart().startsWith("{")) {
                try {
                    val parts = pickMuxable(findMediaObjects(JSONObject(resp)))
                    if (parts != null) return parts
                    // GraphQL responded but no muxable media (gated / video): the
                    // video case means yt-dlp failed for another reason — bail out.
                    if (anyVideoMedia(resp)) return null
                } catch (_: Exception) {}
            }
        }

        // 3) RelayPrefetched blob baked into the post HTML (last resort).
        val objs = ArrayList<JSONObject>()
        for (mm in SJS.findAll(html)) {
            try { objs.addAll(findMediaObjects(JSONObject(mm.groupValues[1]))) } catch (_: Exception) {}
        }
        return pickMuxable(objs)
    }

    private fun anyVideoMedia(respJson: String): Boolean {
        return try {
            findMediaObjects(JSONObject(respJson)).any {
                val v = it.optJSONArray("video_versions"); v != null && v.length() > 0
            }
        } catch (_: Exception) { false }
    }

    // ------------------------------------------------------------------ run

    /**
     * Orchestration entry point, called from YoutubeDlDownloader ONLY after
     * yt-dlp has failed on an Instagram post URL. Returns the produced mp4 in
     * `outDir` (already named), or null to let yt-dlp's error surface. Writes
     * intermediates to a private subdir that is always removed.
     */
    suspend fun tryMux(
        context: Context,
        sourceUrl: String,
        outDir: File,
        taskId: String,
        isCancelled: () -> Boolean = { false }
    ): File? {
        val shortcode = shortcodeFromUrl(sourceUrl) ?: return null
        val parts = try {
            probeMedia(shortcode, isCancelled) ?: return null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DebugLog.backend("task=$taskId ig-mux probe failed: ${(t.message ?: t.javaClass.simpleName).take(200)}")
            return null
        }
        if (parts.hasVideo || parts.photoUrl.isBlank()) return null

        val work = File(outDir, ".igmux-$taskId").apply { mkdirs() }
        val produced = File(outDir, buildFilename(shortcode, parts))
        var delivered = false
        try {
            val imgExt = guessExt(parts.photoUrl, ".jpg")
            val cover = fetchTo(work, "cover$imgExt", parts.photoUrl) ?: return null
            if (isCancelled()) throw CancellationException("IG mux cancelled after cover fetch")
            val audio = if (parts.audioUrl.isNotBlank()) {
                val audExt = guessExt(parts.audioUrl, ".m4a")
                val a = fetchTo(work, "audio$audExt", parts.audioUrl) ?: return null
                if (isCancelled()) throw CancellationException("IG mux cancelled after audio fetch")
                a
            } else null

            val lib = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            if (!lib.exists()) {
                DebugLog.backend("task=$taskId ig-mux: bundled ffmpeg not found at ${lib.name}")
                return null
            }
            try { lib.setExecutable(true) } catch (_: Exception) {}

            val variants = ffmpegVariants(lib.absolutePath, cover.absolutePath, audio?.absolutePath, produced.absolutePath)
            for (cmd in variants) {
                if (isCancelled()) throw CancellationException("IG mux cancelled before ffmpeg")
                val ok = runFffmpeg(cmd, work.parentFile, isCancelled)
                if (ok && produced.exists() && produced.length() > 0) {
                    DebugLog.backend("task=$taskId ig-mux OK: ${produced.name} (${produced.length() / 1024} KiB)")
                    delivered = true
                    return produced
                }
            }
            DebugLog.backend("task=$taskId ig-mux: ffmpeg produced nothing")
            return null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DebugLog.backend("task=$taskId ig-mux error: ${(t.message ?: t.javaClass.simpleName).take(200)}")
            return null
        } finally {
            runCatching { work.deleteRecursively() }
            // A cancelled/killed ffmpeg can leave a truncated, moov-less mp4
            // directly in the user-visible dir (ffmpeg writes -y as it goes).
            // Keep the file only when it was actually delivered above.
            if (!delivered && produced.exists()) runCatching { produced.delete() }
        }
    }

    private fun guessExt(url: String, fallback: String): String {
        val q = url.substringBefore('?').substringAfterLast('/', "")
        val e = q.substringAfterLast('.', "").lowercase()
        return if (e in setOf("jpg", "jpeg", "png", "webp", "m4a", "mp3", "aac", "mp4")) ".$e" else fallback
    }

    private fun fetchTo(dir: File, name: String, url: String): File? {
        if (!url.startsWith("http")) return null
        return try {
            HttpClient.get(url, referer = POST_BASE, headers = mapOf("Accept" to "*/*"), tag = "instagram")
                .use { res ->
                    if (!res.isSuccessful) null
                    // media is not a "page": a licensed track can be 10+ MB and
                    // a phone on 3G needs minutes — give this read a 5-minute
                    // budget instead of the 90 s page default (still bounded).
                    else HttpClient.cappedBytes(res, 32L * 1024 * 1024, budgetMs = 300_000L)
                }
                ?.let { bytes ->
                    val f = File(dir, name)
                    f.writeBytes(bytes)
                    if (f.length() > 0) f else null
                }
        } catch (_: Exception) { null }
    }

    private fun runFffmpeg(cmd: List<String>, workDir: File?, isCancelled: () -> Boolean): Boolean {
        var p: Process? = null
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment()["TMPDIR"] = (workDir ?: File(".")).absolutePath
            pb.redirectErrorStream(true)
            p = pb.start()
            val reader = p.inputStream.bufferedReader()
            val tail = StringBuilder()
            var cancelRequested = false
            val pump = Thread {
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        if (tail.length < 1200) tail.append(line).append('\n')
                        line = reader.readLine()
                    }
                } catch (_: Exception) {}
            }
            pump.isDaemon = true
            pump.start()
            // -loglevel error emits nothing while an encode runs, so cancellation
            // cannot be observed from output lines alone; poll instead, and cap
            // the encode so a wedged child can never outlive the task.
            val deadline = System.currentTimeMillis() + FFMPEG_MAX_MS
            var exit = Int.MIN_VALUE
            while (true) {
                if (p.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    exit = p.exitValue(); break
                }
                if (isCancelled() && !cancelRequested) {
                    cancelRequested = true
                    try { p.destroy() } catch (_: Exception) {}
                    try { p.destroyForcibly() } catch (_: Exception) {}
                }
                if (System.currentTimeMillis() > deadline) {
                    try { p.destroyForcibly() } catch (_: Exception) {}
                    break
                }
            }
            try { pump.join(1500) } catch (_: InterruptedException) {}
            if (exit == Int.MIN_VALUE) {
                DebugLog.backend("ig-mux ffmpeg exceeded ${FFMPEG_MAX_MS / 1000}s cap — killed")
                false
            } else {
                if (exit != 0) DebugLog.backend("ig-mux ffmpeg exit=$exit: ${tail.toString().take(300)}")
                exit == 0
            }
        } catch (t: Throwable) {
            try { p?.destroyForcibly() } catch (_: Exception) {}
            DebugLog.backend("ig-mux ffmpeg launch failed: ${(t.message ?: t.javaClass.simpleName).take(160)}")
            false
        }
    }
}
