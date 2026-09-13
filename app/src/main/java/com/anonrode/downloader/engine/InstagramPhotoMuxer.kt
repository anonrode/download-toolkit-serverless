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
 * The asset we need does exist, though: the same logged-out payload carries
 *   music_metadata.music_info.music_asset_info.progressive_download_url
 *   (+ cover_artwork_uri, duration_in_ms, title, display_artist)
 * and the photo itself in image_versions2.candidates[0].gallery-dl reads these
 * exact fields; yt-dlp ignores them. So we bypass yt-dlp for this one shape:
 * fetch the post, grab cover + audio, and mux a still-image mp4 locally with
 * the ffmpeg binary the app already ships (same `lib*.so` exec the aria2c path
 * uses). Everything is a STRICT FALLBACK — invoked only after yt-dlp has
 * already failed on an Instagram post URL, so working videos are never touched
 * and a photo we cannot handle falls through to yt-dlp's original error.
 *
 * The logged-out GraphQL query + RelayPrefetched blob paths mirror yt-dlp
 * master's InstagramIE (doc_id 27130156389949648). Whether IG exposes
 * music_metadata to a logged-out client cannot be confirmed off-device (data-
 * center IPs get the SPA shell), so the parser is defensive: anything missing
 * or non-photo returns null → clean failure, no fabricated video.
 */
object InstagramPhotoMuxer {

    /** Base64 alphabet (`0-9` before `-`/`_`) + the `id_to_pk` 28-char cut,
     *  mirroring yt-dlp's `_ENCODING_CHARS` and `_id_to_pk`. */
    internal const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    internal const val GRAPHQL_URL = "https://www.instagram.com/api/graphql"
    internal const val DOC_ID = "27130156389949648"
    internal const val FRIENDLY = "PolarisLoggedOutDesktopWWWPostRootContentQuery"
    private const val POST_BASE = "https://www.instagram.com/"

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

        val photoUrl = bestCandidate(m.optJSONObject("image_versions2")?.optJSONArray("candidates")) ?: return null
        val audio = musicAssetInfo(m) ?: return null
        val audioUrl = audio.optString("progressive_download_url").takeIf { it.isNotBlank() } ?: return null
        val durationMs = audio.optLong("duration_in_ms", 0L).coerceAtLeast(0L)
        val title = audio.optString("title").takeIf { it.isNotBlank() }
        val artist = (audio.optString("display_artist").ifBlank { audio.optString("ig_artist") })
            .takeIf { it.isNotBlank() }
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
            if (parts.photoUrl.isNotBlank() && parts.audioUrl.isNotBlank()) return parts
        }
        return null
    }

    /** Filename: caption (or a slug fallback) + shortcode, filesystem-safe. */
    internal fun buildFilename(shortcode: String, parts: MediaParts): String {
        val source = (parts.caption ?: parts.title?.let { t -> parts.artist?.let { a -> "$t — $a" } ?: t })
            ?: "Instagram"
        val cleaned = source.replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""[/\\:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .trimEnd('.', ' ')
        val label = cleaned.ifBlank { "Instagram" }
        return "$label [$shortcode].mp4"
    }

    /** ffmpeg arg vectors for a still-image mux, best codec first, then the
     *  universally-present mpeg4 fallback. Split out for exact unit testing. */
    internal fun ffmpegVariants(libPath: String, cover: String, audio: String, out: String): List<List<String>> {
        val scale = "scale=trunc(iw/2)*2:trunc(ih/2)*2" // libx264 rejects odd pixel dims
        val common = mutableListOf("-hide_banner", "-loglevel", "error", "-y",
            "-framerate", "1", "-loop", "1", "-i", cover, "-i", audio,
            "-vf", scale, "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest")
        val x264 = ArrayList(common).apply { addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage")) }
        val mpeg4 = ArrayList(common).apply { addAll(listOf("-c:v", "mpeg4", "-vtag", "xvid", "-q:v", "4")) }
        x264.add(out); mpeg4.add(out)
        return listOf(listOf(libPath) + x264, listOf(libPath) + mpeg4)
    }

    // -------------------------------------------------------------- network

    /** Fetch the post and decide whether it is a muxable photo+music. Never
     *  throws on network errors — returns null so the caller surfaces yt-dlp's
     *  original message. */
    internal suspend fun probeMedia(shortcode: String, isCancelled: () -> Boolean): MediaParts? {
        val url = "$POST_BASE/p/$shortcode/"
        val html = HttpClient.getText(url, referer = POST_BASE, tag = "instagram",
            maxBytes = HttpClient.MAX_TEXT_BYTES) ?: return null
        if (isCancelled()) throw CancellationException("IG mux cancelled after page fetch")

        // 1) logged-out GraphQL (the surface that actually carries media data).
        val lsd = extractLsdToken(html)
        val pk = idToPk(shortcode)
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

        // 2) RelayPrefetched blob baked into the post HTML (fallback).
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
        if (parts.hasVideo || parts.photoUrl.isBlank() || parts.audioUrl.isBlank()) return null

        val work = File(outDir, ".igmux-$taskId").apply { mkdirs() }
        try {
            val imgExt = guessExt(parts.photoUrl, ".jpg")
            val audExt = guessExt(parts.audioUrl, ".m4a")
            val cover = fetchTo(work, "cover$imgExt", parts.photoUrl) ?: return null
            if (isCancelled()) throw CancellationException("IG mux cancelled after cover fetch")
            val audio = fetchTo(work, "audio$audExt", parts.audioUrl) ?: return null
            if (isCancelled()) throw CancellationException("IG mux cancelled after audio fetch")

            val lib = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            if (!lib.exists()) {
                DebugLog.backend("task=$taskId ig-mux: bundled ffmpeg not found at ${lib.name}")
                return null
            }
            try { lib.setExecutable(true) } catch (_: Exception) {}

            val produced = File(outDir, buildFilename(shortcode, parts))
            val variants = ffmpegVariants(lib.absolutePath, cover.absolutePath, audio.absolutePath, produced.absolutePath)
            for (cmd in variants) {
                if (isCancelled()) throw CancellationException("IG mux cancelled before ffmpeg")
                val ok = runFffmpeg(cmd, work.parentFile, isCancelled)
                if (ok && produced.exists() && produced.length() > 0) {
                    DebugLog.backend("task=$taskId ig-mux OK: ${produced.name} (${produced.length() / 1024} KiB)")
                    return produced
                }
            }
            if (produced.exists()) runCatching { produced.delete() }
            DebugLog.backend("task=$taskId ig-mux: ffmpeg produced nothing")
            return null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DebugLog.backend("task=$taskId ig-mux error: ${(t.message ?: t.javaClass.simpleName).take(200)}")
            return null
        } finally {
            runCatching { work.deleteRecursively() }
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
                .use { res -> if (!res.isSuccessful) null else HttpClient.cappedBytes(res, 32L * 1024 * 1024) }
                ?.let { bytes ->
                    val f = File(dir, name)
                    f.writeBytes(bytes)
                    if (f.length() > 0) f else null
                }
        } catch (_: Exception) { null }
    }

    private fun runFffmpeg(cmd: List<String>, workDir: File?, isCancelled: () -> Boolean): Boolean {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment()["TMPDIR"] = (workDir ?: File(".")).absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            val reader = p.inputStream.bufferedReader()
            val tail = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                if (tail.length < 1200) tail.append(line).append('\n')
                if (isCancelled()) { try { p.destroy() } catch (_: Exception) {} }
                line = reader.readLine()
            }
            val exit = p.waitFor()
            if (exit != 0) DebugLog.backend("ig-mux ffmpeg exit=$exit: ${tail.toString().take(300)}")
            exit == 0
        } catch (t: Throwable) {
            DebugLog.backend("ig-mux ffmpeg launch failed: ${(t.message ?: t.javaClass.simpleName).take(160)}")
            false
        }
    }
}
