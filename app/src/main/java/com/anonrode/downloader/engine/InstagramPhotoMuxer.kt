package com.anonrode.downloader.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.util.DebugLog
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.util.Locale

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
    private const val SHORTCODE_DOC_ID = "8845758582119845"
    private const val SHORTCODE_FRIENDLY = "PolarisPostActionLoadPostQueryQuery"
    private const val POST_BASE = "https://www.instagram.com"
    internal const val CRAWLER_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
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
    private val LSD_TOKEN = Regex("\\[\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"")
    // RelayPrefetched media blob lives in `<script data-sjs>{...}</script>`.
    private val SJS = Regex(
        """<script\b[^>]+\bdata-sjs>(\{.+?\})</script>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val CONTEXT_JSON_REGEX = Regex("\"contextJSON\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val REELS_AUDIO_LINK_REGEX = Regex("/reels/audio/(\\d+)", RegexOption.IGNORE_CASE)
    private val AUDIO_CLUSTER_ID_REGEX = Regex(
        "\"(?:audio_cluster_id|audio_asset_id|audio_id)\"\\s*:\\s*\"?(\\d{6,})\"?"
    )
    private val AUDIO_SRC_REGEX = Regex("\"audio_src\"\\s*:\\s*\"(https?:[^\"]+)\"")

    /** The extracted, decided media. `hasVideo` short-circuits the mux.
     *  `carouselPhotoUrls` holds ALL photo children of a carousel (in order);
     *  when non-empty a slideshow is built instead of the single-image mux. */
    internal data class MediaParts(
        val photoUrl: String,
        val audioUrl: String,
        val durationMs: Long,
        val title: String?,
        val artist: String?,
        val caption: String?,
        val hasVideo: Boolean,
        val carouselPhotoUrls: List<String> = emptyList(),
        /** Audio-bearing VIDEO children of a mixed carousel (live-verified on
         *  post DeALkBtIwSB 2026-10-14: mixed photo/video carousels expose
         *  `is_video:true, has_audio:true` children with video_versions URLs
         *  in the logged-out page payload — the ONLY anonymous audio source,
         *  since REST/GraphQL audio fields are login-gated everywhere). */
        val carouselVideoUrls: List<String> = emptyList(),
        val carouselVideoDurationMs: Long = 0L,
        /** Instagram audio-cluster id from music metadata / embed attribution. */
        val audioClusterId: String? = null
    ) {
        val hasAnyAudioHint: Boolean
            get() = audioUrl.isNotBlank() ||
                carouselVideoUrls.isNotEmpty() ||
                !audioClusterId.isNullOrBlank()
    }

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

    private fun shortcodeGraphqlForm(shortcode: String, lsd: String): Map<String, String> = mapOf(
        "lsd" to lsd,
        "fb_api_caller_class" to "RelayModern",
        "fb_api_req_friendly_name" to SHORTCODE_FRIENDLY,
        "server_timestamps" to "true",
        "variables" to JSONObject()
            .put("shortcode", shortcode)
            .put("fetch_tagged_user_count", JSONObject.NULL)
            .put("hoisted_comment_id", JSONObject.NULL)
            .put("hoisted_reply_id", JSONObject.NULL)
            .toString(),
        "doc_id" to SHORTCODE_DOC_ID
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
            o.has("carousel_media") || o.has("display_url") ||
            o.has("display_uri") || o.has("display_resources") ||
            o.has("edge_sidecar_to_children") || o.has("music_metadata") ||
            o.has("audio_tracks") || (o.has("pk") && (o.has("code") || o.has("taken_at")))

    private fun bestDisplayResource(resources: JSONArray?): String? {
        if (resources == null || resources.length() == 0) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until resources.length()) {
            val r = resources.optJSONObject(i) ?: continue
            val src = r.optString("src").takeIf { it.isNotBlank() } ?: continue
            val w = r.optInt("config_width", 0)
            if (w > bestW) { bestW = w; best = src }
        }
        return best
    }

    private fun allSidecarPhotos(m: JSONObject): List<String> {
        val sidecar = m.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (i in 0 until sidecar.length()) {
            val node = sidecar.optJSONObject(i)?.optJSONObject("node") ?: continue
            if (node.optBoolean("is_video", false)) continue
            val url = bestCandidate(node.optJSONObject("image_versions2")?.optJSONArray("candidates"))
                ?: bestDisplayResource(node.optJSONArray("display_resources"))
                ?: node.optString("display_url").takeIf { it.isNotBlank() }
                ?: node.optString("display_uri").takeIf { it.isNotBlank() }
            if (url != null) urls.add(url)
        }
        return urls
    }

    /** Decide the muxable shape from a media object; null when not applicable. */
    internal fun mediaToParts(m: JSONObject): MediaParts? {
        val videoVersions = m.optJSONArray("video_versions")
        val hasVideo = (videoVersions != null && videoVersions.length() > 0) || m.optBoolean("is_video", false)
        // A real video (even if yt-dlp choked on gating) is not a photo+music:
        // never fabricate a still from it — let yt-dlp's own error stand.
        if (hasVideo) return MediaParts("", "", 0, null, null, null, hasVideo = true)

        // A photo carousel keeps its images in `carousel_media` or `edge_sidecar_to_children`
        val singlePhoto = bestCandidate(m.optJSONObject("image_versions2")?.optJSONArray("candidates"))
            ?: bestDisplayResource(m.optJSONArray("display_resources"))
            ?: m.optString("display_url").takeIf { it.isNotBlank() }
            ?: m.optString("display_uri").takeIf { it.isNotBlank() }

        val isCarousel = m.has("carousel_media") || m.has("edge_sidecar_to_children")
        val carouselPhotos = if (m.has("carousel_media")) {
            allCarouselPhotos(m)
        } else if (m.has("edge_sidecar_to_children")) {
            allSidecarPhotos(m)
        } else emptyList()

        val photoUrl = singlePhoto
            ?: carouselPhotos.firstOrNull()
            ?: return null

        val audio = musicAssetInfo(m)
            ?: (if (m.has("carousel_media")) {
                val cm = m.optJSONArray("carousel_media")
                var found: JSONObject? = null
                if (cm != null) {
                    for (i in 0 until cm.length()) {
                        val child = cm.optJSONObject(i) ?: continue
                        found = musicAssetInfo(child)
                        if (found != null) break
                    }
                }
                found
            } else null)

        val audioUrl = (audio?.optString("progressive_download_url")?.takeIf { it.isNotBlank() }
            ?: audio?.optString("audio_src")?.takeIf { it.isNotBlank() }
            ?: audio?.optString("fast_start_progressive_download_url")?.takeIf { it.isNotBlank() }
            ?: "").trim()
        val durationMs = audio?.optLong("duration_in_ms", 0L)?.coerceAtLeast(0L)
            ?: (audio?.optDouble("duration", 0.0)?.times(1000)?.toLong()?.coerceAtLeast(0L) ?: 0L)
        val title = audio?.optString("title")?.takeIf { it.isNotBlank() }
            ?: audio?.optString("song_name")?.takeIf { it.isNotBlank() }
            ?: audio?.optString("audio_title")?.takeIf { it.isNotBlank() }
        val artist = audio?.let {
            (it.optString("display_artist")
                .ifBlank { it.optString("artist_name") }
                .ifBlank { it.optString("ig_artist") }
                .ifBlank { it.optString("audio_artist") }
            ).takeIf { a -> a.isNotBlank() }
        }
        val clusterId = audio?.let {
            (it.optString("audio_cluster_id")
                .ifBlank { it.optString("audio_asset_id") }
                .ifBlank { it.optString("audio_id") }
                .ifBlank { it.optString("id") }
            ).takeIf { id -> id.isNotBlank() && id.all(Char::isDigit) }
        }
        val caption = m.optJSONObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }
            ?: m.optString("caption").takeIf { it.isNotBlank() }
            ?: m.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)
                ?.optJSONObject("node")?.optString("text")?.takeIf { it.isNotBlank() }
        val (videoClips, videoDurationMs) =
            if (isCarousel) carouselVideoClips(m) else emptyList<String>() to 0L
        return MediaParts(photoUrl, audioUrl, durationMs, title, artist, caption,
            hasVideo = false, carouselPhotoUrls = carouselPhotos,
            carouselVideoUrls = videoClips, carouselVideoDurationMs = videoDurationMs,
            audioClusterId = clusterId)
    }

    private fun bestCandidate(candidates: JSONArray?): String? {
        if (candidates == null || candidates.length() == 0) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            val url = c.optString("url").takeIf { it.isNotBlank() } ?: continue
            var w = c.optInt("width", 0)
            if (w == 0) {
                val m = Regex("""p(\d+)x\d+""").find(url)
                w = m?.groupValues?.get(1)?.toIntOrNull() ?: if (i == 0) 9999 else 0
            }
            if (w > bestW) { bestW = w; best = url }
        }
        return best ?: candidates.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    /** All AUDIO-BEARING VIDEO children of a mixed carousel — each reduced to
     *  its SMALLEST video candidate (audio extraction is bitrate-blind, so
     *  only mobile data is conserved). Returns url and the child's declared
     *  duration (0 when absent) for the slideshow's per-slide math. */
    private fun carouselVideoClips(m: JSONObject): Pair<List<String>, Long> {
        val urls = mutableListOf<String>()
        var durationMs = 0L
        val collect: (JSONObject) -> Unit = { child ->
            val isVideo = child.optBoolean("is_video", false) ||
                (child.optJSONArray("video_versions")?.length() ?: 0) > 0
            if (isVideo) {
                val versions = child.optJSONArray("video_versions")
                val url = versions?.let { arr ->
                    var best: String? = null
                    var bestRatio = Long.MAX_VALUE / 2
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val v = arr.optJSONObject(i) ?: continue
                            val u = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                            val w = v.optLong("width", 0L).coerceAtLeast(1L)
                            val h = v.optLong("height", 0L).coerceAtLeast(1L)
                            val ratio = w * h
                            if (ratio < bestRatio) { bestRatio = ratio; best = u }
                        }
                    }
                    best
                } ?: child.optString("video_url").takeIf { it.isNotBlank() }
                    ?: child.optString("playable_url").takeIf { it.isNotBlank() }
                if (url != null) {
                    urls.add(url)
                    if (durationMs == 0L) {
                        durationMs = (child.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0L)
                    }
                }
            }
        }
        m.optJSONArray("carousel_media")?.let { cm ->
            for (i in 0 until cm.length()) {
                cm.optJSONObject(i)?.let(collect)
            }
        }
        if (urls.isEmpty()) {
            m.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")?.let { edges ->
                for (i in 0 until edges.length()) {
                    edges.optJSONObject(i)?.optJSONObject("node")?.let(collect)
                }
            }
        }
        return urls to durationMs
    }

    /** ALL PHOTO children of a carousel — video children are skipped on the
     *  same principle as the top-level hasVideo guard: a real video is not a
     *  still, so it can never serve as the fabricated cover. When only one
     *  photo exists (or the carousel has a single image), the list has one
     *  element and behaves exactly like the old firstCarouselPhoto. */
    private fun allCarouselPhotos(m: JSONObject): List<String> {
        val carousel = m.optJSONArray("carousel_media") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (i in 0 until carousel.length()) {
            val child = carousel.optJSONObject(i) ?: continue
            val v = child.optJSONArray("video_versions")
            if (v != null && v.length() > 0) continue
            val photo = bestCandidate(child.optJSONObject("image_versions2")?.optJSONArray("candidates"))
                ?: bestDisplayResource(child.optJSONArray("display_resources"))
                ?: child.optString("display_url").takeIf { it.isNotBlank() }
                ?: child.optString("display_uri").takeIf { it.isNotBlank() }
            photo?.let { urls.add(it) }
        }
        return urls
    }

    /** Comprehensive audio extraction: checks music_metadata, music_info,
     *  music_consumption_info, story_music_stickers, clips_metadata, and audio_tracks. */
    private fun musicAssetInfo(m: JSONObject): JSONObject? {
        // 1. Direct music_metadata
        val mm = m.optJSONObject("music_metadata")
        if (mm != null) {
            val mi = mm.optJSONObject("music_info") ?: mm
            val asset = mi.optJSONObject("music_asset_info")
                ?: mi.optJSONObject("music_consumption_info")
                ?: mm.optJSONObject("original_sound_info")
            if (asset != null && (asset.has("progressive_download_url") || asset.has("audio_src") || asset.has("fast_start_progressive_download_url"))) return asset
            if (asset != null) return asset
        }
        // 2. Direct music_info on m
        val miDirect = m.optJSONObject("music_info")
        if (miDirect != null) {
            val asset = miDirect.optJSONObject("music_asset_info")
                ?: miDirect.optJSONObject("music_consumption_info")
                ?: miDirect
            if (asset.has("progressive_download_url") || asset.has("audio_src")) return asset
        }
        // 3. Direct music_asset_info / music_consumption_info / original_sound_info on m
        val directAsset = m.optJSONObject("music_asset_info")
            ?: m.optJSONObject("music_consumption_info")
            ?: m.optJSONObject("original_sound_info")
        if (directAsset != null) return directAsset

        // 4. clips_metadata (reels or cross-posted clips)
        val clips = m.optJSONObject("clips_metadata")
        if (clips != null) {
            val cmi = clips.optJSONObject("music_info") ?: clips
            val cAsset = cmi.optJSONObject("music_asset_info")
                ?: cmi.optJSONObject("music_consumption_info")
                ?: clips.optJSONObject("original_sound_info")
            if (cAsset != null) return cAsset
        }

        // 5. clips_music_attribution_info (shortcode GraphQL / embed contextJSON)
        val attr = m.optJSONObject("clips_music_attribution_info")
        if (attr != null && !attr.optBoolean("should_mute_audio", false)) return attr

        // 6. story_music_stickers (array of stickers on images)
        val stickers = m.optJSONArray("story_music_stickers")
        if (stickers != null && stickers.length() > 0) {
            for (i in 0 until stickers.length()) {
                val st = stickers.optJSONObject(i) ?: continue
                val stAsset = st.optJSONObject("music_asset_info")
                    ?: st.optJSONObject("music_consumption_info")
                    ?: st.optJSONObject("sticker_data")?.optJSONObject("music_asset_info")
                if (stAsset != null) return stAsset
            }
        }

        // 6. audio_tracks (list of audio streams)
        val audioTracks = m.optJSONArray("audio_tracks")
        if (audioTracks != null && audioTracks.length() > 0) {
            for (i in 0 until audioTracks.length()) {
                val tr = audioTracks.optJSONObject(i) ?: continue
                if (tr.has("progressive_download_url") || tr.has("audio_src")) return tr
            }
        }

        // 7. Check if m itself contains progressive_download_url or audio_src
        if (m.has("progressive_download_url") || m.has("audio_src")) return m

        return null
    }

    /** Parse a candidate media set into the first muxable photo+music.
     *  When `shortcode` is provided, prefers the exact post whose code matches `shortcode`. */
    internal fun pickMuxable(objects: List<JSONObject>, shortcode: String? = null): MediaParts? {
        var fallback: MediaParts? = null
        for (o in objects) {
            val code = o.optString("code").ifBlank { o.optString("shortcode") }
            val matchesShortcode = shortcode != null && code.equals(shortcode, ignoreCase = true)
            val parts = mediaToParts(o) ?: continue
            if (parts.hasVideo) continue
            if (parts.photoUrl.isNotBlank()) {
                if (matchesShortcode) {
                    return parts
                }
                if (fallback == null) fallback = parts
            }
        }
        return fallback
    }

    /** Combine discoveries from two surfaces: photos from one, music from
     *  another. Primary wins on conflicts; the carousel list keeps the
     *  larger set so a REST carousel is not truncated by an embed fallback. */
    private fun mergeParts(primary: MediaParts, secondary: MediaParts): MediaParts =
        MediaParts(
            photoUrl = primary.photoUrl.ifBlank { secondary.photoUrl },
            audioUrl = primary.audioUrl.ifBlank { secondary.audioUrl },
            durationMs = if (primary.durationMs > 0L) primary.durationMs else secondary.durationMs,
            title = primary.title ?: secondary.title,
            artist = primary.artist ?: secondary.artist,
            caption = primary.caption ?: secondary.caption,
            hasVideo = primary.hasVideo || secondary.hasVideo,
            carouselPhotoUrls = if (primary.carouselPhotoUrls.size >= secondary.carouselPhotoUrls.size) {
                primary.carouselPhotoUrls
            } else {
                secondary.carouselPhotoUrls
            },
            carouselVideoUrls = primary.carouselVideoUrls.ifEmpty { secondary.carouselVideoUrls },
            carouselVideoDurationMs = if (primary.carouselVideoDurationMs > 0L) {
                primary.carouselVideoDurationMs
            } else {
                secondary.carouselVideoDurationMs
            },
            audioClusterId = primary.audioClusterId ?: secondary.audioClusterId
        )

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
        val scale = "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1" // libx264 rejects odd pixel dims
        val common = mutableListOf("-hide_banner", "-loglevel", "error", "-y")
        if (!audio.isNullOrBlank()) {
            common.addAll(listOf("-framerate", "1", "-loop", "1", "-i", cover, "-i", audio,
                "-vf", scale, "-r", "30", "-vsync", "cfr", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart"))
        } else {
            // 5s silent hold when the post carries no music — the monolith's
            // exact payload (-t 5), so the user still gets a playable video.
            common.addAll(listOf("-framerate", "1", "-loop", "1", "-i", cover, "-t", "5",
                "-vf", scale, "-r", "30", "-vsync", "cfr", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart"))
        }
        val x264 = ArrayList(common).apply { addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage")) }
        val mpeg4 = ArrayList(common).apply { addAll(listOf("-c:v", "mpeg4", "-vtag", "xvid", "-q:v", "4")) }
        x264.add(out); mpeg4.add(out)
        return listOf(listOf(libPath) + x264, listOf(libPath) + mpeg4)
    }

    /** ffmpeg arg vectors for a multi-image SLIDESHOW using the concat demuxer.
     *  Each image is held for `slideDurationSec` seconds (computed by the
     *  caller as `songDurationMs / numSlides / 1000`). The concat list file
     *  must already exist on disk with entries like:
     *    file '/path/to/slide_0.jpg'
     *    duration 6.5
     *    file '/path/to/slide_1.jpg'
     *    duration 6.5
     *  Best codec first (libx264), then mpeg4 fallback — same as single-image.
     */
    internal fun ffmpegSlideshowVariants(
        libPath: String, concatList: String, audio: String?, out: String
    ): List<List<String>> {
        val scale = "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1"
        val common = mutableListOf("-hide_banner", "-loglevel", "error", "-y",
            "-f", "concat", "-safe", "0", "-i", concatList)
        if (!audio.isNullOrBlank()) {
            common.addAll(listOf("-i", audio,
                "-vf", scale, "-r", "30", "-vsync", "cfr", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart"))
        } else {
            common.addAll(listOf("-vf", scale, "-r", "30", "-vsync", "cfr",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart"))
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
        val browserHeaders = mapOf(
            "User-Agent" to HttpClient.DEFAULT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        val crawlerHeaders = mapOf(
            "User-Agent" to CRAWLER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        // Browser HTML first: the embed/SJS surfaces are rendered for a normal
        // UA. The Googlebot page is the fallback when the browser page has no
        // LSD token (login wall / challenge shell).
        val browserHtml = HttpClient.getText(
            url, referer = POST_BASE, headers = browserHeaders,
            tag = "instagram", maxBytes = HttpClient.MAX_TEXT_BYTES
        )
        if (isCancelled()) throw CancellationException("IG mux cancelled after browser page fetch")
        val crawlerHtml = if (browserHtml.isNullOrBlank() || extractLsdToken(browserHtml) == null) {
            HttpClient.getText(
                url, referer = POST_BASE, headers = crawlerHeaders,
                tag = "instagram", maxBytes = HttpClient.MAX_TEXT_BYTES
            )
        } else null
        if (isCancelled()) throw CancellationException("IG mux cancelled after crawler page fetch")

        val html = browserHtml ?: crawlerHtml ?: return null
        val lsd = extractLsdToken(browserHtml ?: "") ?: extractLsdToken(crawlerHtml ?: "")
        val pk = idToPk(shortcode)

        // Multi-surface accumulation. The old code returned the REST result
        // immediately, so a REST reply with photos but no music (the normal
        // logged-out shape) skipped the surfaces that DO carry the track.
        var accumulated: MediaParts? = null
        fun accumulate(candidate: MediaParts?) {
            if (candidate == null) return
            accumulated = accumulated?.let { mergeParts(it, candidate) } ?: candidate
        }
        fun hasPhotoAndAudio(): Boolean =
            accumulated?.photoUrl?.isNotBlank() == true &&
                accumulated?.audioUrl?.isNotBlank() == true

        // 1) Mobile-style REST detail.
        if (pk != null) {
            val restHeaders = LinkedHashMap<String, String>().apply {
                put("X-IG-App-ID", "936619743392459")
                put("X-ASBD-ID", "129477")
                put("X-Requested-With", "XMLHttpRequest")
                put("Accept", "application/json, text/plain, */*")
                HttpClient.cookieValue("csrftoken", "www.instagram.com")?.let { put("X-CSRFToken", it) }
                put("X-IG-WWW-Claim", HttpClient.cookieValue("www-claim", "www.instagram.com") ?: "0")
            }
            val rest = HttpClient.getText(
                restInfoUrl(pk), referer = url, headers = restHeaders,
                tag = "instagram", maxBytes = HttpClient.MAX_TEXT_BYTES
            )
            if (isCancelled()) throw CancellationException("IG mux cancelled after rest")
            if (!rest.isNullOrBlank() && rest.trimStart().startsWith("{")) {
                try {
                    accumulate(pickMuxable(findMediaObjects(JSONObject(rest)), shortcode))
                    if (hasPhotoAndAudio()) return accumulated
                } catch (_: Exception) {}
            }
        }

        // 2) Logged-out media_id GraphQL (photo/carousel shape).
        if (lsd != null && pk != null) {
            val headers = LinkedHashMap<String, String>().apply {
                put("X-FB-Friendly-Name", FRIENDLY)
                put("X-FB-LSD", lsd)
                put("X-Requested-With", "XMLHttpRequest")
                put("Accept", "application/json, text/plain, */*")
                HttpClient.cookieValue("csrftoken", "www.instagram.com")?.let { put("X-CSRFToken", it) }
            }
            val resp = HttpClient.postForm(
                GRAPHQL_URL, graphqlForm(pk, lsd),
                referer = url, headers = headers, tag = "instagram"
            )
            if (isCancelled()) throw CancellationException("IG mux cancelled after graphql")
            if (!resp.isNullOrBlank() && resp.trimStart().startsWith("{")) {
                try {
                    val parts = pickMuxable(findMediaObjects(JSONObject(resp)), shortcode)
                    if (parts != null) {
                        accumulate(parts)
                    } else if (accumulated == null && anyVideoMedia(resp)) {
                        // A real video: yt-dlp failed for another reason; do
                        // not fabricate a still from its poster frame.
                        return null
                    }
                } catch (_: Exception) {}
            }
            if (hasPhotoAndAudio()) return accumulated

            // 2B) Shortcode GraphQL carries clips_music_attribution_info and
            // the sidecar children on many posts where media_id does not.
            if (!hasPhotoAndAudio()) {
                val scHeaders = LinkedHashMap(headers).apply {
                    put("X-FB-Friendly-Name", SHORTCODE_FRIENDLY)
                }
                val scResp = HttpClient.postForm(
                    GRAPHQL_URL, shortcodeGraphqlForm(shortcode, lsd),
                    referer = url, headers = scHeaders, tag = "instagram"
                )
                if (isCancelled()) throw CancellationException("IG mux cancelled after shortcode graphql")
                if (!scResp.isNullOrBlank() && scResp.trimStart().startsWith("{")) {
                    try {
                        accumulate(pickMuxable(findMediaObjects(JSONObject(scResp)), shortcode))
                        extractRawAudioHintsFromText(scResp)?.let { accumulate(it) }
                    } catch (_: Exception) {}
                }
            }
            if (hasPhotoAndAudio()) return accumulated
        }

        // 3) RelayPrefetched data-sjs blobs in either HTML shape.
        for (page in listOfNotNull(browserHtml, crawlerHtml)) {
            val objs = ArrayList<JSONObject>()
            for (mm in SJS.findAll(page)) {
                try {
                    objs.addAll(findMediaObjects(JSONObject(mm.groupValues[1])))
                } catch (_: Exception) {}
            }
            accumulate(pickMuxable(objs, shortcode))
            extractRawAudioHintsFromText(page)?.let { accumulate(it) }
            if (isCancelled()) throw CancellationException("IG mux cancelled after sjs")
        }
        if (hasPhotoAndAudio()) return accumulated

        // 4) Public embed page: contextJSON plus the rendered music ticker.
        if (!hasPhotoAndAudio() || accumulated?.carouselPhotoUrls.isNullOrEmpty()) {
            try {
                val embedHeaders = mapOf(
                    "Accept" to "text/html,application/xhtml+xml",
                    "User-Agent" to HttpClient.DEFAULT_UA,
                    "Accept-Language" to "en-US,en;q=0.9"
                )
                val embedHtml = HttpClient.getText(
                    "https://www.instagram.com/p/$shortcode/embed/captioned/",
                    referer = POST_BASE,
                    headers = embedHeaders,
                    tag = "instagram",
                    maxBytes = HttpClient.MAX_TEXT_BYTES
                )
                if (isCancelled()) throw CancellationException("IG mux cancelled after embed fetch")
                if (!embedHtml.isNullOrBlank()) {
                    parseEmbedPage(embedHtml, shortcode)?.let { accumulate(it) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {}
        }

        if (accumulated != null && accumulated!!.photoUrl.isNotBlank()) {
            return accumulated
        }

        // 5) Last resort: HTML OpenGraph tags (never for a video post).
        val ogSource = crawlerHtml ?: html
        if (ogSource.contains("og:video", ignoreCase = true)) return null
        val ogImg = Regex("""<meta[^>]+(?:property=["']og:image["'][^>]+content=["']([^"']+)["']|content=["']([^"']+)["'][^>]+property=["']og:image["'])""", RegexOption.IGNORE_CASE)
            .find(ogSource)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.replace("&amp;", "&")
        if (!ogImg.isNullOrBlank()) {
            val ogTitle = Regex("""<meta[^>]+(?:property=["']og:title["'][^>]+content=["']([^"']+)["']|content=["']([^"']+)["'][^>]+property=["']og:title["'])""", RegexOption.IGNORE_CASE)
                .find(ogSource)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            val ogParts = MediaParts(
                photoUrl = ogImg,
                audioUrl = "",
                durationMs = 0L,
                title = null,
                artist = null,
                caption = ogTitle,
                hasVideo = false
            )
            // Keep any audio/cluster hints already discovered by the embed
            // surface; only the image/OG caption comes from this last resort.
            return accumulated?.let { mergeParts(it, ogParts) } ?: ogParts
        }

        return null
    }

    /** Extracts contextJSON, carousel images, and music attribution from
     *  /p/$shortcode/embed/captioned/. */
    private fun parseEmbedPage(embedHtml: String, shortcode: String): MediaParts? {
        var result: MediaParts? = null
        var embedAudio: String? = null

        CONTEXT_JSON_REGEX.find(embedHtml)?.groupValues?.get(1)?.let { escaped ->
            try {
                val unescaped = JSONObject("{\"v\":\"$escaped\"}").getString("v")
                val objs = findMediaObjects(JSONObject(unescaped))
                result = pickMuxable(objs, shortcode)
                val musicHints = extractRawAudioHintsFromText(unescaped)
                // Only trust a loose progressive_download_url when the same
                // blob also carries real music metadata; otherwise it may be
                // a video sibling's stream.
                embedAudio = if (musicHints != null) musicAudioUrl(unescaped) else null
                musicHints?.let { hints ->
                    result = result?.let { mergeParts(it, hints) } ?: hints
                }
            } catch (_: Exception) {}
        }

        val clusterId = REELS_AUDIO_LINK_REGEX.find(embedHtml)?.groupValues?.get(1)
            ?: AUDIO_CLUSTER_ID_REGEX.find(embedHtml)?.groupValues?.get(1)
        val musicLinkText = Regex(
            """<a[^>]+href=["'][^"']*/reels/audio/\d+[^"']*["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(embedHtml)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&amp;", "&")
            ?.replace("&#039;", "'")
            ?.replace("&quot;", "\"")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        var parsedArtist: String? = null
        var parsedTitle: String? = null
        if (!musicLinkText.isNullOrBlank()) {
            val split = musicLinkText.split(Regex("""\s*[•·\-—]\s*"""), limit = 2)
            if (split.size == 2) {
                parsedArtist = split[0].trim().takeIf { it.isNotBlank() }
                parsedTitle = split[1].trim().takeIf { it.isNotBlank() }
            } else {
                parsedTitle = musicLinkText
            }
        }

        val embedImg = Regex("""<img[^>]+class=["'][^"']*EmbeddedMediaImage[^"']*["'][^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(embedHtml)?.groupValues?.get(1)?.replace("&amp;", "&")

        val htmlParts = MediaParts(
            photoUrl = embedImg.orEmpty(),
            audioUrl = embedAudio.orEmpty(),
            durationMs = 0L,
            title = parsedTitle,
            artist = parsedArtist,
            caption = null,
            hasVideo = false,
            audioClusterId = clusterId
        )

        val base = result
        return when {
            base != null -> mergeParts(base, htmlParts)
            embedImg != null -> htmlParts
            else -> null
        }
    }

    /** Pulls audio-only hints out of a JSON/HTML blob. Deliberately does NOT
     *  treat a generic progressive_download_url as audio: video_versions use
     *  the same key, and muxing a video stream as audio produces a broken MP4. */
    internal fun extractRawAudioHintsFromText(text: String): MediaParts? {
        val rawAudioUrl = AUDIO_SRC_REGEX.find(text)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("&amp;", "&")
            .orEmpty()
        val clusterId = AUDIO_CLUSTER_ID_REGEX.find(text)?.groupValues?.get(1)
            ?: REELS_AUDIO_LINK_REGEX.find(text)?.groupValues?.get(1)
        val songName = Regex("\"(?:song_name|audio_title)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])+)\"")
            .find(text)?.groupValues?.get(1)?.let { unescapeJsonString(it) }
        val artistName = Regex("\"(?:artist_name|display_artist)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])+)\"")
            .find(text)?.groupValues?.get(1)?.let { unescapeJsonString(it) }
        if (rawAudioUrl.isBlank() && clusterId == null && songName == null && artistName == null) {
            return null
        }
        return MediaParts(
            photoUrl = "",
            audioUrl = rawAudioUrl,
            durationMs = 0L,
            title = songName,
            artist = artistName,
            caption = null,
            hasVideo = false,
            audioClusterId = clusterId
        )
    }

    private fun unescapeJsonString(raw: String): String = try {
        JSONObject("{\"v\":\"$raw\"}").getString("v")
    } catch (_: Exception) {
        raw.replace("\\/", "/").replace("\\u0026", "&")
    }

    /** Music-page contexts may use progressive_download_url; this helper is
     *  only called on known-music surfaces, never on arbitrary media JSON. */
    private fun musicAudioUrl(text: String): String? =
        Regex("\"(?:audio_src|progressive_download_url|fast_start_progressive_download_url)\"\\s*:\\s*\"(https?:[^\"]+)\"")
            .find(text)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() }

    private fun anyVideoMedia(respJson: String): Boolean {
        return try {
            findMediaObjects(JSONObject(respJson)).any {
                val v = it.optJSONArray("video_versions"); v != null && v.length() > 0
            }
        } catch (_: Exception) { false }
    }

    // ------------------------------------------------------------------ run

    /**
     * Directly delivers pristine high-res photo(s) without audio/video re-encoding.
     * Used when the post has no audio, when audio download fails or is restricted,
     * or when ffmpeg is missing / fails to produce a valid video.
     */
    internal fun deliverStillPhotos(outDir: File, shortcode: String, parts: MediaParts): File? {
        val source = (parts.caption ?: parts.title?.let { t -> parts.artist?.let { a -> "$t — $a" } ?: t })
            ?: "Instagram"
        val label = if (source.isBlank()) "Instagram"
            else com.anonrode.downloader.util.NameSanitizer.savedName(source, 80, stripNoise = false)

        // Fallback contract: ONE cover image, never every carousel slide.
        // Delivering N loose .jpgs made the engine move them all into
        // Downloads (YoutubeDlDownloader's sibling sweep) and the user saw a
        // folder of pictures instead of a failed/photo artifact.
        val url = parts.carouselPhotoUrls.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: parts.photoUrl
        if (url.isBlank()) return null
        val ext = guessExt(url, ".jpg")
        val targetFile = File(outDir, "$label [$shortcode]$ext")
        val downloaded = fetchTo(outDir, targetFile.name, url)
        return downloaded?.takeIf { it.exists() && it.length() > 0 }
    }

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
        val cookieFile = File(context.filesDir, "cookies.txt").takeIf { it.exists() && it.length() > 0 }
            ?: File(context.getExternalFilesDir(null), "cookies.txt").takeIf { it.exists() && it.length() > 0 }
        if (cookieFile != null) {
            com.anonrode.downloader.data.net.HttpClient.loadCookiesFromFile(cookieFile)
        }
        // Audio availability is purely a session question (live-verified on
        // post DeALkBtIwSB 2026-10-14): REST/GraphQL are login-gated without
        // cookies and answer HTML shells, so the log must show which world
        // we are probing in or a missing track is undiagnosable.
        DebugLog.backend(
            "task=$taskId ig-mux probe start: cookies=${if (cookieFile != null) "YES" else "NO"} (audio via REST music field or has_audio carousel video siblings)"
        )
        val parts = try {
            probeMedia(shortcode, isCancelled) ?: return null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DebugLog.backend("task=$taskId ig-mux probe failed: ${(t.message ?: t.javaClass.simpleName).take(200)}")
            return null
        }
        if (parts.hasVideo || parts.photoUrl.isBlank()) return null

        // Monolith parity (src/extractors/instagram_muxer.py): a photo post
        // is ALWAYS finished as a video — still image + music muxed when the
        // track was found, a silent still-video / screenshot slideshow when
        // it was not. The earlier shortcut that handed back raw .jpg files
        // here is what produced "downloaded pictures that error on tap": a
        // still image is not a play artifact, and the player rightly refused
        // it. deliverStillPhotos stays exactly where the monolith puts
        // success-chances at risk — cover/audio fetch failure, missing
        // ffmpeg, or a failed encode — as the last resort, not the plan.
        val work = File(outDir, ".igmux-$taskId").apply { mkdirs() }
        val produced = File(outDir, buildFilename(shortcode, parts))
        var delivered = false
        try {
            val lib = resolveFfmpegBinary(context)
            if (lib == null) {
                DebugLog.backend("task=$taskId ig-mux: bundled ffmpeg not found, falling back to still photos")
                return deliverStillPhotos(outDir, shortcode, parts)
            }
            try { lib.setExecutable(true) } catch (_: Exception) {}
            val ldLibraryPath = buildFfmpegLdLibraryPath(context, lib)
            DebugLog.backend(
                "task=$taskId ig-mux ffmpeg binary=${lib.absolutePath} ld=$ldLibraryPath"
            )

            // ---------- audio ladder (direct track → mixed-carousel video
            // sibling → Instagram audio cluster/reel pivot → yt-dlp search).
            // No commercial preview APIs are used.
            val audio = resolveAudioTrack(
                context = context,
                work = work,
                lib = lib,
                ldLibraryPath = ldLibraryPath,
                parts = parts,
                taskId = taskId,
                isCancelled = isCancelled
            )
            val effectiveDurationMs = parts.durationMs.takeIf { it > 0L }
                ?: probeAudioDurationMs(audio)
                ?: (if (audio?.name == "audio.m4a") parts.carouselVideoDurationMs else 0L)

            // ---------- slideshow path: 2+ carousel photos
            val isSlideshow = parts.carouselPhotoUrls.size >= 2
            val variants = if (isSlideshow) {
                // download all carousel photos, then letterbox them onto one
                // even-dimension canvas so mixed aspect ratios/WebP cannot
                // change the concat filter output size mid-stream.
                val rawSlides = mutableListOf<File>()
                for ((idx, url) in parts.carouselPhotoUrls.withIndex()) {
                    if (isCancelled()) throw CancellationException("IG mux cancelled during slide fetch")
                    val ext = guessExt(url, ".jpg")
                    val f = fetchTo(work, "slide_$idx$ext", url)
                    if (f != null) rawSlides.add(f)
                }
                if (rawSlides.isEmpty()) {
                    DebugLog.backend("task=$taskId ig-mux: slideshow slide fetch failed, falling back to still photos")
                    return deliverStillPhotos(outDir, shortcode, parts)
                }
                val slideFiles = normalizeSlidesForFfmpeg(work, rawSlides)
                if (slideFiles.size == rawSlides.size) {
                    DebugLog.backend(
                        "task=$taskId ig-mux: normalized ${slideFiles.size} slides onto a uniform even canvas"
                    )
                }
                val totalTargetMs = if (effectiveDurationMs > 0L) {
                    effectiveDurationMs.coerceAtMost(90_000L)
                } else {
                    slideFiles.size * 4_000L
                }
                // Hold each slide 3–5s; if the song is longer than one pass
                // through the photos, cycle them until the audio ends.
                val perSlideSec = (totalTargetMs.toDouble() / slideFiles.size / 1000.0)
                    .coerceIn(3.0, 5.0)
                val totalSlidesNeeded = kotlin.math.ceil(
                    (totalTargetMs / 1000.0) / perSlideSec
                ).toInt().coerceAtLeast(slideFiles.size)
                val concatFile = File(work, "slides.txt")
                val sb = StringBuilder()
                for (i in 0 until totalSlidesNeeded) {
                    val sf = slideFiles[i % slideFiles.size]
                    sb.append("file '")
                        .append(sf.absolutePath.replace("\\", "\\\\").replace("'", "'\\''"))
                        .append("'\n")
                    sb.append("duration ")
                        .append(String.format(Locale.US, "%.3f", perSlideSec))
                        .append("\n")
                }
                // concat demuxer needs the last file repeated without duration to
                // prevent a black frame at the end
                if (slideFiles.isNotEmpty()) {
                    val last = slideFiles[(totalSlidesNeeded - 1) % slideFiles.size]
                    sb.append("file '")
                        .append(last.absolutePath.replace("\\", "\\\\").replace("'", "'\\''"))
                        .append("'\n")
                }
                concatFile.writeText(sb.toString())
                DebugLog.backend(
                    "task=$taskId ig-mux slideshow: ${slideFiles.size} unique slides " +
                        "($totalSlidesNeeded frames), ${String.format(Locale.US, "%.2f", perSlideSec)}s each"
                )
                ffmpegSlideshowVariants(
                    lib.absolutePath,
                    concatFile.absolutePath,
                    audio?.absolutePath,
                    produced.absolutePath
                )
            } else {
                // ---------- single-image path (original behavior)
                val imgExt = guessExt(parts.photoUrl, ".jpg")
                val cover = fetchTo(work, "cover$imgExt", parts.photoUrl)
                if (cover == null) {
                    DebugLog.backend("task=$taskId ig-mux: cover fetch failed, falling back to still photos")
                    return deliverStillPhotos(outDir, shortcode, parts)
                }
                if (isCancelled()) throw CancellationException("IG mux cancelled after cover fetch")
                // Single-photo posts can also be WebP; normalize to baseline
                // JPEG exactly like the carousel path so ffmpeg never decodes
                // an unexpected format.
                val normalizedCover = normalizeSlidesForFfmpeg(work, listOf(cover)).firstOrNull() ?: cover
                ffmpegVariants(
                    lib.absolutePath,
                    normalizedCover.absolutePath,
                    audio?.absolutePath,
                    produced.absolutePath
                )
            }

            for (cmd in variants) {
                if (isCancelled()) throw CancellationException("IG mux cancelled before ffmpeg")
                val ok = runFffmpeg(cmd, work.parentFile, isCancelled, ldLibraryPath)
                if (ok && produced.exists() && produced.length() > 0) {
                    DebugLog.backend("task=$taskId ig-mux OK: ${produced.name} (${produced.length() / 1024} KiB)")
                    delivered = true
                    return produced
                }
            }
            DebugLog.backend("task=$taskId ig-mux: ffmpeg produced nothing, falling back to still photos")
            return deliverStillPhotos(outDir, shortcode, parts)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DebugLog.backend("task=$taskId ig-mux error: ${(t.message ?: t.javaClass.simpleName).take(200)}")
            return deliverStillPhotos(outDir, shortcode, parts)
        } finally {
            runCatching { work.deleteRecursively() }
            // A cancelled/killed ffmpeg can leave a truncated, moov-less mp4
            // directly in the user-visible dir (ffmpeg writes -y as it goes).
            // Keep the file only when it was actually delivered above.
            if (!delivered && produced.exists()) runCatching { produced.delete() }
        }
    }

    /**
     * Shared even-dimension canvas for every carousel slide. Mixed aspect
     * ratios in a concat demuxer can change the filter-graph output size
     * mid-stream, which libx264 rejects; letterboxing every slide onto one
     * canvas removes that failure mode.
     */
    internal fun slideTargetSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int> {
        val valid = sizes.filter { it.first > 0 && it.second > 0 }
        if (valid.isEmpty()) return 1080 to 1080
        val maxW = valid.maxOf { it.first }.coerceAtMost(1080)
        val maxH = valid.maxOf { it.second }.coerceAtMost(1920)
        return ((maxW / 2) * 2).coerceAtLeast(2) to ((maxH / 2) * 2).coerceAtLeast(2)
    }

    private fun sampleSizeFor(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) {
            sample *= 2
        }
        return sample
    }

    private fun normalizeSlidesForFfmpeg(work: File, raws: List<File>): List<File> {
        if (raws.isEmpty()) return raws
        val sizes: List<Pair<Int, Int>?> = raws.map { raw ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }
        val (targetW, targetH) = slideTargetSize(sizes.filterNotNull())
        val normalized = mutableListOf<File>()
        for ((idx, raw) in raws.withIndex()) {
            try {
                val (srcW, srcH) = sizes.getOrNull(idx) ?: (targetW to targetH)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(srcW, srcH, targetW, targetH)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val src = BitmapFactory.decodeFile(raw.absolutePath, opts) ?: continue
                val canvasBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(canvasBitmap)
                canvas.drawColor(Color.BLACK)
                val scale = minOf(
                    targetW.toFloat() / src.width.toFloat(),
                    targetH.toFloat() / src.height.toFloat()
                )
                val w = (src.width * scale).toInt().coerceAtLeast(1)
                val h = (src.height * scale).toInt().coerceAtLeast(1)
                val left = (targetW - w) / 2f
                val top = (targetH - h) / 2f
                canvas.drawBitmap(
                    src, null, RectF(left, top, left + w, top + h),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
                src.recycle()
                val outFile = File(work, "norm_$idx.jpg")
                FileOutputStream(outFile).use { stream ->
                    canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                }
                canvasBitmap.recycle()
                if (outFile.length() > 0L) normalized.add(outFile)
            } catch (_: Throwable) {
                // Decode/OOM failure: keep this raw slide. If any slide
                // fails we fall back to the complete raw list below rather
                // than mixing normalized and unnormalized inputs.
            }
        }
        return if (normalized.size == raws.size) normalized else raws
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

    /**
     * Pick the runnable ffmpeg binary.
     *
     * The youtubedl-android:ffmpeg AAR packages the executable AND its
     * versioned shared libraries inside `libffmpeg.zip.so`; `FFmpeg.init`
     * extracts that archive to
     * `<noBackupFilesDir>/youtubedl-android/packages/ffmpeg`. The bare
     * `nativeLibraryDir/libffmpeg.so` is only the executable half — executing
     * it fails with `library "libavdevice.so.61" not found` because the
     * versioned `libav*.so.61` files were never in jniLibs (on-device log
     * 2026-10-19). Prefer the extracted copy (its siblings are complete),
     * keep the old path as a last-resort fallback.
     */
    internal fun pickFfmpegBinary(extractedDir: File, nativeDir: File): File? {
        val names = setOf("libffmpeg.so", "libffmpeg.bin.so", "ffmpeg")
        if (extractedDir.isDirectory) {
            extractedDir.walkTopDown()
                .firstOrNull { it.isFile && it.name in names }
                ?.let { return it }
        }
        return File(nativeDir, "libffmpeg.so").takeIf { it.isFile }
    }

    private data class ClusterAudioResult(
        val file: File? = null,
        val title: String? = null,
        val artist: String? = null
    )

    private fun probeAudioDurationMs(audio: File?): Long? {
        if (audio == null || !audio.exists() || audio.length() <= 0L) return null
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(audio.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Audio waterfall: Instagram direct → mixed-carousel video sibling →
     * audio-cluster/reel pivot → bounded yt-dlp search. Deliberately no
     * iTunes/Deezer preview APIs.
     */
    private suspend fun resolveAudioTrack(
        context: Context,
        work: File,
        lib: File,
        ldLibraryPath: String,
        parts: MediaParts,
        taskId: String,
        isCancelled: () -> Boolean
    ): File? {
        // Tier 1: direct Instagram audio URL.
        if (parts.audioUrl.isNotBlank()) {
            val audExt = guessExt(parts.audioUrl, ".m4a")
            val direct = fetchTo(work, "audio$audExt", parts.audioUrl)
            if (direct != null) {
                if (isCancelled()) throw CancellationException("IG mux cancelled after audio fetch")
                DebugLog.backend("task=$taskId ig-mux: Tier-1 direct audio fetched (${direct.length() / 1024} KiB)")
                return direct
            }
        }

        // Tier 2: audio from a video sibling in a mixed carousel.
        if (parts.carouselVideoUrls.isNotEmpty()) {
            val clip = fetchTo(work, "audio_source.mp4", parts.carouselVideoUrls.first())
            if (clip != null) {
                if (isCancelled()) throw CancellationException("IG mux cancelled after sibling clip fetch")
                extractAudioFromClip(context, clip, work, lib, ldLibraryPath, isCancelled)?.let {
                    DebugLog.backend("task=$taskId ig-mux: Tier-2 audio extracted from video sibling (${it.length() / 1024} KiB)")
                    return it
                }
            }
        }

        // Tier 3: Instagram audio cluster / public reel pivot.
        var songTitle = parts.title
        var songArtist = parts.artist
        if (!parts.audioClusterId.isNullOrBlank()) {
            val cluster = resolveAudioFromCluster(
                context = context,
                work = work,
                lib = lib,
                ldLibraryPath = ldLibraryPath,
                clusterId = parts.audioClusterId,
                taskId = taskId,
                isCancelled = isCancelled
            )
            if (cluster.file != null) return cluster.file
            if (songTitle.isNullOrBlank()) songTitle = cluster.title
            if (songArtist.isNullOrBlank()) songArtist = cluster.artist
        }

        // Tier 4: bounded yt-dlp ytsearch1 fallback (no commercial APIs).
        val query = listOfNotNull(
            songArtist?.takeIf { !it.equals("Original audio", ignoreCase = true) },
            songTitle?.takeIf { !it.equals("Original audio", ignoreCase = true) }
        ).joinToString(" ").trim()
        if (query.length >= 3) {
            if (isCancelled()) throw CancellationException("IG mux cancelled before ytsearch fallback")
            fetchYtSearchAudio(work, query, taskId)?.let { return it }
        }

        DebugLog.backend("task=$taskId ig-mux: no audio source resolved — encoding silent slideshow")
        return null
    }

    private fun extractAudioFromClip(
        context: Context,
        clip: File,
        work: File,
        lib: File,
        ldLibraryPath: String,
        isCancelled: () -> Boolean
    ): File? {
        val extracted = File(work, "audio.m4a")
        val ok = runFffmpeg(
            listOf(
                lib.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", clip.absolutePath, "-vn", "-acodec", "aac", "-b:a", "192k",
                extracted.absolutePath
            ),
            context.cacheDir, isCancelled, ldLibraryPath
        ) && extracted.exists() && extracted.length() > 0L
        return if (ok) extracted else null
    }

    private suspend fun resolveAudioFromCluster(
        context: Context,
        work: File,
        lib: File,
        ldLibraryPath: String,
        clusterId: String,
        taskId: String,
        isCancelled: () -> Boolean
    ): ClusterAudioResult {
        var discoveredTitle: String? = null
        var discoveredArtist: String? = null
        try {
            // 3A) Mobile clips/music REST endpoint.
            val apiHeaders = LinkedHashMap<String, String>().apply {
                put("X-IG-App-ID", "936619743392459")
                put("X-ASBD-ID", "129477")
                put("X-Requested-With", "XMLHttpRequest")
                put("Accept", "application/json, text/plain, */*")
                HttpClient.cookieValue("csrftoken", "www.instagram.com")?.let { put("X-CSRFToken", it) }
            }
            val apiResp = HttpClient.postForm(
                "https://www.instagram.com/api/v1/clips/music/",
                mapOf(
                    "audio_cluster_id" to clusterId,
                    "original_sound_audio_asset_id" to clusterId
                ),
                referer = "$POST_BASE/reels/audio/$clusterId/",
                headers = apiHeaders,
                tag = "instagram"
            )
            if (!apiResp.isNullOrBlank()) {
                val hints = extractRawAudioHintsFromText(apiResp)
                hints?.title?.let { discoveredTitle = it }
                hints?.artist?.let { discoveredArtist = it }
                val directUrl = hints?.audioUrl?.takeIf { it.isNotBlank() }
                    ?: musicAudioUrl(apiResp)
                if (!directUrl.isNullOrBlank()) {
                    fetchTo(work, "cluster_audio.m4a", directUrl)?.let { f ->
                        if (isCancelled()) throw CancellationException("IG mux cancelled after cluster audio")
                        DebugLog.backend("task=$taskId ig-mux: Tier-3A audio fetched from clips/music (${f.length() / 1024} KiB)")
                        return ClusterAudioResult(f, discoveredTitle, discoveredArtist)
                    }
                }
            }

            // 3B) Public /reels/audio/<clusterId>/ page.
            val audioPage = HttpClient.getText(
                "$POST_BASE/reels/audio/$clusterId/",
                referer = POST_BASE,
                headers = mapOf("User-Agent" to CRAWLER_UA, "Accept" to "text/html"),
                tag = "instagram",
                maxBytes = HttpClient.MAX_TEXT_BYTES
            )
            if (!audioPage.isNullOrBlank()) {
                val hints = extractRawAudioHintsFromText(audioPage)
                if (discoveredTitle == null) discoveredTitle = hints?.title
                if (discoveredArtist == null) discoveredArtist = hints?.artist
                val pageAudioUrl = hints?.audioUrl?.takeIf { it.isNotBlank() }
                    ?: musicAudioUrl(audioPage)
                if (!pageAudioUrl.isNullOrBlank()) {
                    fetchTo(work, "cluster_page_audio.m4a", pageAudioUrl)?.let { f ->
                        if (isCancelled()) throw CancellationException("IG mux cancelled after cluster page audio")
                        DebugLog.backend("task=$taskId ig-mux: Tier-3B audio fetched from reels/audio page (${f.length() / 1024} KiB)")
                        return ClusterAudioResult(f, discoveredTitle, discoveredArtist)
                    }
                }
                // Public reel clips on the sound page: demux the first one.
                val reelVideoUrl = Regex("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^{}]*\"url\"\\s*:\\s*\"(https?:[^\"]+)\"")
                    .find(audioPage)?.groupValues?.get(1)?.let { unescapeJsonString(it) }
                if (!reelVideoUrl.isNullOrBlank()) {
                    val clip = fetchTo(work, "cluster_reel.mp4", reelVideoUrl)
                    if (clip != null) {
                        if (isCancelled()) throw CancellationException("IG mux cancelled after cluster reel")
                        extractAudioFromClip(context, clip, work, lib, ldLibraryPath, isCancelled)?.let { extracted ->
                            DebugLog.backend("task=$taskId ig-mux: Tier-3B audio extracted from public cluster reel (${extracted.length() / 1024} KiB)")
                            return ClusterAudioResult(extracted, discoveredTitle, discoveredArtist)
                        }
                    }
                }
                if (discoveredTitle == null) {
                    val ogTitle = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(audioPage)?.groupValues?.get(1)
                        ?.substringBeforeLast(" on Instagram")
                        ?.replace("&amp;", "&")
                        ?.trim()
                    if (!ogTitle.isNullOrBlank() && !ogTitle.contains("Instagram", ignoreCase = true)) {
                        val split = ogTitle.split(Regex("""\s*[•·\-—]\s*"""), limit = 2)
                        if (split.size == 2) {
                            discoveredArtist = split[0].trim()
                            discoveredTitle = split[1].trim()
                        } else {
                            discoveredTitle = ogTitle
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {}
        return ClusterAudioResult(null, discoveredTitle, discoveredArtist)
    }

    private suspend fun fetchYtSearchAudio(work: File, query: String, taskId: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest("ytsearch1:$query").apply {
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                    addOption("--audio-quality", "0")
                    addOption("-o", File(work, "ytsearch_audio.%(ext)s").absolutePath)
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--retries", "1")
                    addOption("--socket-timeout", "15")
                    addOption("--no-part")
                }
                withTimeoutOrNull(90_000L) {
                    YoutubeDL.getInstance().execute(request, "ig-ytsearch") { _, _, _ -> }
                }
                work.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("ytsearch_audio.") && it.length() > 0L }
                    ?.maxByOrNull { it.length() }
                    ?.also {
                        DebugLog.backend("task=$taskId ig-mux: Tier-4 yt-dlp search audio resolved (${it.length() / 1024} KiB)")
                    }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DebugLog.backend(
                    "task=$taskId ig-mux: Tier-4 yt-dlp search failed: " +
                        (t.message ?: t.javaClass.simpleName).take(160)
                )
                null
            } finally {
                runCatching { YoutubeDL.getInstance().destroyProcessById("ig-ytsearch") }
            }
        }

    private fun extractFfmpegZipFallback(context: Context, targetDir: File) {
        val zipSo = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.zip.so")
        if (!zipSo.exists() || zipSo.length() <= 0L) return
        try {
            targetDir.mkdirs()
            java.util.zip.ZipFile(zipSo).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val dest = File(targetDir, entry.name)
                    if (!dest.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        continue
                    }
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            DebugLog.backend("ig-mux: extracted ffmpeg fallback completed to ${targetDir.absolutePath}")
        } catch (t: Throwable) {
            DebugLog.backend("ig-mux: extractFfmpegZipFallback failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    internal fun resolveFfmpegBinary(context: android.content.Context): File? {
        // Idempotent: extracts libffmpeg.zip.so on first use (and re-extracts
        // when the bundled version changes). Never let a failure here mask
        // the filesystem fallback.
        try {
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
        } catch (t: Throwable) {
            DebugLog.backend("ig-mux: FFmpeg.init failed: ${t.message ?: t.javaClass.simpleName}")
        }
        val extracted = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        if (!File(extracted, "usr/lib").isDirectory) {
            extractFfmpegZipFallback(context, extracted)
        }
        return pickFfmpegBinary(extracted, File(context.applicationInfo.nativeLibraryDir))
    }

    /**
     * Directories the extracted FFmpeg needs at link time.
     *
     * The youtubedl-android:ffmpeg package places the main executable in
     * `nativeLibraryDir/libffmpeg.so`, while the versioned shared libraries
     * (`libavdevice.so.61`, `libavformat.so.61`, `libavcodec.so.61`, etc.)
     * are unzipped by FFmpeg.init from `libffmpeg.zip.so` into
     * `<noBackupFilesDir>/youtubedl-android/packages/ffmpeg/usr/lib`.
     *
     * If LD_LIBRARY_PATH does not include `packages/ffmpeg/usr/lib`, executing
     * `libffmpeg.so` crashes with:
     * `CANNOT LINK EXECUTABLE ".../libffmpeg.so": library "libavdevice.so.61" not found`
     *
     * Include the extracted packages usr/lib directories, layout relative to
     * the binary, and the app's native library dir (libc++_shared.so).
     */
    internal fun buildFfmpegLdLibraryPath(context: Context, binary: File): String =
        buildFfmpegLdLibraryPath(binary, context.applicationInfo.nativeLibraryDir, context.noBackupFilesDir)

    internal fun buildFfmpegLdLibraryPath(binary: File, nativeLibraryDir: String): String =
        buildFfmpegLdLibraryPath(binary, nativeLibraryDir, null)

    internal fun buildFfmpegLdLibraryPath(
        binary: File,
        nativeLibraryDir: String,
        noBackupFilesDir: File?
    ): String {
        val dirs = LinkedHashSet<String>()

        val effectiveNoBackup = noBackupFilesDir
            ?: runCatching {
                if (com.anonrode.downloader.AnonApp::instance.isInitialized) {
                    com.anonrode.downloader.AnonApp.instance.noBackupFilesDir
                } else null
            }.getOrNull()

        // 1) youtubedl-android packages directory (packages/ffmpeg/usr/lib, etc.)
        // This is where FFmpeg.init extracts the versioned shared libraries (libavdevice.so.61, etc.)
        if (effectiveNoBackup != null) {
            val packagesDir = File(effectiveNoBackup, "youtubedl-android/packages")
            for (pkg in listOf("ffmpeg", "python", "aria2c")) {
                val pkgDir = File(packagesDir, pkg)
                dirs.add(File(pkgDir, "usr/lib").absolutePath)
                dirs.add(File(pkgDir, "lib").absolutePath)
                dirs.add(pkgDir.absolutePath)
            }
        }

        // 2) Layout relative to the binary (covers custom packaging & test fixtures)
        binary.parentFile?.let { bin ->
            dirs.add(bin.absolutePath)
            dirs.add(File(bin, "lib").absolutePath)
            dirs.add(File(bin, "usr/lib").absolutePath)
            bin.parentFile?.let { parent ->
                dirs.add(File(parent, "lib").absolutePath)
                dirs.add(File(parent, "usr/lib").absolutePath)
                parent.parentFile?.let { grand ->
                    dirs.add(File(grand, "usr/lib").absolutePath)
                    dirs.add(File(grand, "lib").absolutePath)
                }
            }
        }

        // 3) App native library dir (libc++_shared.so, libffmpeg.so)
        if (nativeLibraryDir.isNotBlank()) {
            val native = File(nativeLibraryDir)
            dirs.add(native.absolutePath)
            dirs.add(File(native, "lib").absolutePath)
        }

        return dirs.filter { File(it).isDirectory }.joinToString(File.pathSeparator)
    }

    private fun runFffmpeg(
        cmd: List<String>,
        workDir: File?,
        isCancelled: () -> Boolean,
        ldLibraryPath: String? = null
    ): Boolean {
        var p: Process? = null
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment()["TMPDIR"] = (workDir ?: File(".")).absolutePath
            val binaryDir = cmd.firstOrNull()?.let { File(it).parentFile?.absolutePath }
            val existing = pb.environment()["LD_LIBRARY_PATH"]
            val pieces = listOfNotNull(
                ldLibraryPath?.takeIf { it.isNotBlank() },
                binaryDir?.takeIf { it.isNotBlank() },
                existing?.takeIf { it.isNotBlank() }
            )
            if (pieces.isNotEmpty()) {
                pb.environment()["LD_LIBRARY_PATH"] = pieces.joinToString(File.pathSeparator)
            }
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
