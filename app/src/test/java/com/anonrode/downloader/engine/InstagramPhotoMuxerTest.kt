package com.anonrode.downloader.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * IG-1 unit tests — the PURE half of InstagramPhotoMuxer (everything that
 * decides whether/how to mux). The network + ffmpeg exec is exercised on
 * device only; these pin the parsing, the base57 pk math, the codec command
 * and the filename, which is where the logic bugs would hide.
 */
class InstagramPhotoMuxerTest {

    // ---- shortcode / pk ----------------------------------------------------

    @Test
    fun shortcode_allShapesAndTrailingSlash() {
        assertEquals("DV50JOdjR-P", InstagramPhotoMuxer.shortcodeFromUrl("https://www.instagram.com/p/DV50JOdjR-P/"))
        assertEquals("abc123", InstagramPhotoMuxer.shortcodeFromUrl("https://instagram.com/reel/abc123"))
        assertEquals("abc123", InstagramPhotoMuxer.shortcodeFromUrl("https://www.instagram.com/reels/abc123/"))
        assertEquals("x_y-9", InstagramPhotoMuxer.shortcodeFromUrl("https://www.instagram.com/tv/x_y-9/"))
        // query/fragment stripped after the code
        assertEquals("abc123", InstagramPhotoMuxer.shortcodeFromUrl("https://www.instagram.com/p/abc123/?igsh=xyz"))
        assertNull(InstagramPhotoMuxer.shortcodeFromUrl("https://www.youtube.com/watch?v=abc"))
        assertNull(InstagramPhotoMuxer.shortcodeFromUrl("https://instagram.com/nomad/user"))
    }

    @Test
    fun idToPk_matchesKnownShortcodeVectors() {
        // Alphabet (64 chars, yt-dlp order): A-Z=0..25, a-z=26..51, 0-9=52..61, -=62, _=63
        //   "p"  -> 41
        //   "ay" -> 26*64 + 50 = 1714
        assertEquals("41", InstagramPhotoMuxer.idToPk("p"))
        assertEquals("1714", InstagramPhotoMuxer.idToPk("ay"))
        // A long modern shortcode MUST exceed Long.MAX (64^11 ~ 5e19):
        // proves the BigInteger accumulator didn't silently overflow to garbage.
        val big = InstagramPhotoMuxer.idToPk("ZmFrZXNob3J0Y29kZWxvbmdlcg")
        assertNotNull(big)
        assertTrue(big!!.toBigInteger().compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0)
    }

    @Test
    fun idToPk_rejectsInvalidChars() {
        assertNull(InstagramPhotoMuxer.idToPk("has spaces"))
        assertNull(InstagramPhotoMuxer.idToPk("bad!char"))
        assertNull(InstagramPhotoMuxer.idToPk(""))
    }

    @Test
    fun idToPk_stripsTrailing28Checksum() {
        // A code longer than 28 chars: only the leading part decodes; a
        // well-formed long code still yields a positive pk (no crash).
        val pk = InstagramPhotoMuxer.idToPk("abcdefghijklmnopqrstuvwxyz0123456789EXTRA")
        assertNotNull(pk)
        assertTrue(pk!!.toBigInteger().signum() > 0)
    }

    // ---- lsd token ---------------------------------------------------------

    @Test
    fun lsd_eqmcAndFallbackForms() {
        val eqmc = "<script id=\"__eqmc\" type=\"application/json\">{\"l\":\"TOKEN_EQMC\",\"x\":1}</script>"
        assertEquals("TOKEN_EQMC", InstagramPhotoMuxer.extractLsdToken(eqmc))
        val req = "<body>blah [\"LSD\",[],{\"token\":\"TOKEN_REQ\"}] more</body>"
        assertEquals("TOKEN_REQ", InstagramPhotoMuxer.extractLsdToken(req))
        assertNull(InstagramPhotoMuxer.extractLsdToken("<html>nothing here</html>"))
    }

    // ---- graphql form ------------------------------------------------------

    @Test
    fun graphqlForm_hasClosedVocabulary() {
        val f = InstagramPhotoMuxer.graphqlForm("123", "lsdval")
        assertEquals("lsdval", f["lsd"])
        assertEquals("RelayModern", f["fb_api_caller_class"])
        assertEquals(InstagramPhotoMuxer.FRIENDLY, f["fb_api_req_friendly_name"])
        assertEquals(InstagramPhotoMuxer.DOC_ID, f["doc_id"])
        assertEquals("123", JSONObject(f["variables"]!!).getString("media_id"))
    }

    // ---- REST surface (2026-09-13 live-probe finding) -----------------------

    @Test
    fun restInfoUrl_canonicalPkEndpoint() {
        // The desktop graphql doc carries NO music fields at all (control test
        // on a known-music reel: clips_metadata.music_info = null, zero
        // music_asset_info occurrences). Music lives on the REST surface,
        // which is keyed by the numeric pk, not the shortcode.
        assertEquals(
            "https://www.instagram.com/api/v1/media/3853340288614211471/info/",
            InstagramPhotoMuxer.restInfoUrl("3853340288614211471")
        )
    }

    // ---- media decision ----------------------------------------------------

    private fun photoMusicMedia(): JSONObject = JSONObject(
        """
        {
          "pk": "500123",
          "code": "DV50JOdjR-P",
          "taken_at": 1700000000,
          "media_type": 1,
          "image_versions2": { "candidates": [
            { "url": "https://lookaside.fbsbx.com/hi.jpg", "width": 1080, "height": 1350 },
            { "url": "https://lookaside.fbsbx.com/lo.jpg", "width": 320,  "height": 400 }
          ]},
          "caption": { "text": "Golden hour at the  beach" },
          "music_metadata": { "music_info": { "music_asset_info": {
             "id": "999",
             "progressive_download_url": "https://cdn.fbsbx.com/track.m4a",
             "duration_in_ms": 32000,
             "title": "Sunset Lover",
             "display_artist": "Petit Biscuit"
          }}}
        }
        """
    )

    @Test
    fun pickMuxable_photoWithMusic() {
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(photoMusicMedia()))
        assertNotNull(parts)
        assertEquals("https://lookaside.fbsbx.com/hi.jpg", parts!!.photoUrl) // widest candidate
        assertEquals("https://cdn.fbsbx.com/track.m4a", parts.audioUrl)
        assertEquals(32000L, parts.durationMs)
        assertEquals("Sunset Lover", parts.title)
        assertEquals("Petit Biscuit", parts.artist)
        assertEquals("Golden hour at the  beach", parts.caption)
        assertFalse(parts.hasVideo)
    }

    @Test
    fun pickMuxable_videoShortcircuits() {
        val m = photoMusicMedia()
        m.put("video_versions", org.json.JSONArray().put(JSONObject().put("url", "https://x/v.mp4").put("height", 720)))
        m.remove("image_versions2")
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(m))
        // video present -> hasVideo true -> not muxable
        assertNull(parts)
    }

    @Test
    fun pickMuxable_photoWithoutAudio_isMuxable() {
        val m = photoMusicMedia()
        m.remove("music_metadata")
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(m))
        assertNotNull(parts)
        assertEquals("https://lookaside.fbsbx.com/hi.jpg", parts!!.photoUrl)
        assertEquals("", parts.audioUrl)
        assertFalse(parts.hasVideo)
    }

    @Test
    fun pickMuxable_audioWithoutProgressiveUrl_fallsBackToSilentVideo() {
        val m = photoMusicMedia()
        m.getJSONObject("music_metadata").getJSONObject("music_info")
            .getJSONObject("music_asset_info").remove("progressive_download_url")
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(m))
        assertNotNull(parts)
        assertEquals("https://lookaside.fbsbx.com/hi.jpg", parts!!.photoUrl)
        assertEquals("", parts.audioUrl)
    }

    @Test
    fun pickMuxable_consumptionInfoFallback() {
        val m = photoMusicMedia()
        val mi = m.getJSONObject("music_metadata").getJSONObject("music_info")
        mi.remove("music_asset_info")
        mi.put("music_consumption_info",
            JSONObject("""{"progressive_download_url":"https://cdn/alt.m4a","title":"T","display_artist":"A"}"""))
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(m))
        assertNotNull(parts)
        assertEquals("https://cdn/alt.m4a", parts!!.audioUrl)
    }

    // ---- nested discovery (graphql + RelayPrefetched shapes) ---------------

    @Test
    fun findMediaObjects_reachesNestedIfNotGated() {
        val wrapped = JSONObject(
            """{ "data": { "xig_polaris_media": { "__bbox": { "result": { "data": {
                 "xig_polaris_media": { "if_not_gated_logged_out": ${photoMusicMedia()} }
            }}}}}}}"""
        )
        val objs = InstagramPhotoMuxer.findMediaObjects(wrapped)
        assertNotNull(InstagramPhotoMuxer.pickMuxable(objs))
    }

    @Test
    fun findMediaObjects_boundsDeepHostilePayload() {
        // 60-deep nesting must not stack-overflow; the guard caps depth.
        var node = JSONObject().put("leaf", true)
        for (i in 0 until 60) node = JSONObject().put("n", node)
        val objs = InstagramPhotoMuxer.findMediaObjects(node)
        assertTrue(objs.isEmpty())
    }

    // ---- carousel (2026-09-15 — gallery-dl reference read) -----------------
    // Music rides the POST object; images live in carousel_media CHILDREN.
    // Requiring both on ONE object (the pre-fix shape) made every
    // carousel-with-music post unmuxable — the parser had to learn the split.

    private fun carouselWithMusic(): JSONObject = JSONObject(
        """
        {
          "pk": "700777",
          "code": "CAROUSEL1",
          "taken_at": 1700000000,
          "media_type": 8,
          "carousel_media": [
            { "media_type": 1, "image_versions2": { "candidates": [
                { "url": "https://lookaside.fbsbx.com/slide1.jpg", "width": 1080 } ] } },
            { "media_type": 2, "video_versions": [ { "url": "https://x/v.mp4" } ],
              "image_versions2": { "candidates": [
                { "url": "https://lookaside.fbsbx.com/vidthumb.jpg", "width": 720 } ] } },
            { "media_type": 1, "image_versions2": { "candidates": [
                { "url": "https://lookaside.fbsbx.com/slide3.jpg", "width": 1080 } ] } }
          ],
          "caption": { "text": "Carousel day" },
          "music_metadata": { "music_info": { "music_asset_info": {
             "progressive_download_url": "https://cdn.fbsbx.com/carousel.m4a",
             "duration_in_ms": 15000,
             "title": "Song",
             "display_artist": "Artist"
          }}}
        }
        """
    )

    @Test
    fun pickMuxable_carousel_musicOnPost_photoFromFirstChild() {
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(carouselWithMusic()))
        assertNotNull(parts)
        assertEquals("https://lookaside.fbsbx.com/slide1.jpg", parts!!.photoUrl)
        assertEquals("https://cdn.fbsbx.com/carousel.m4a", parts.audioUrl)
        assertEquals("Carousel day", parts.caption)
        assertFalse(parts.hasVideo)
    }

    @Test
    fun pickMuxable_carousel_skipsVideoChildForCover() {
        // First child is a VIDEO (with a thumbnail): the cover must come from
        // the first PHOTO child — a video still can never be the fabricated
        // cover, same rule as the top-level hasVideo guard.
        val m = carouselWithMusic()
        val kids = m.getJSONArray("carousel_media")
        val videoChild = kids.getJSONObject(1)
        kids.remove(1)
        kids.put(0, videoChild)
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(m))
        assertNotNull(parts)
        assertEquals("https://lookaside.fbsbx.com/slide3.jpg", parts!!.photoUrl)
    }

    @Test
    fun pickMuxable_carousel_allVideo_isNotMuxable() {
        val m = carouselWithMusic()
        m.put("carousel_media", org.json.JSONArray().put(
            JSONObject("""{"media_type":2,"video_versions":[{"url":"https://x/a.mp4"}]}""")
        ))
        assertNull(InstagramPhotoMuxer.pickMuxable(listOf(m)))
    }

    @Test
    fun pickMuxable_carousel_withoutMusic_isPhotoMuxable() {
        val m = carouselWithMusic()
        m.remove("music_metadata")
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(m))
        assertNotNull(parts)
        assertFalse(parts!!.hasVideo)
        assertTrue(parts.carouselPhotoUrls.isNotEmpty())
    }

    @Test
    fun pickMuxable_withShortcode_prefersMatchingTargetPost() {
        val other = photoMusicMedia()
        other.put("code", "OTHER_CODE")
        val target = photoMusicMedia()
        target.put("code", "TARGET_CODE")
        target.getJSONObject("caption").put("text", "Target Post Caption")

        val parts = InstagramPhotoMuxer.pickMuxable(listOf(other, target), shortcode = "TARGET_CODE")
        assertNotNull(parts)
        assertEquals("Target Post Caption", parts!!.caption)
    }

    // ---- filename ----------------------------------------------------------

    @Test
    fun pickMuxable_matchesGraphqlShortcodeKey() {
        // REST uses "code"; xdt_shortcode_media GraphQL uses "shortcode".
        val other = photoMusicMedia()
        other.put("code", "OTHER_CODE")
        val target = photoMusicMedia()
        target.remove("code")
        target.put("shortcode", "TARGET_CODE")
        target.getJSONObject("caption").put("text", "GraphQL target")
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(other, target), shortcode = "TARGET_CODE")
        assertNotNull(parts)
        assertEquals("GraphQL target", parts!!.caption)
    }

    @Test
    fun buildFilename_sanitizesAndCaps() {
        val parts = InstagramPhotoMuxer.pickMuxable(listOf(photoMusicMedia()))!!
        val name = InstagramPhotoMuxer.buildFilename("DV50JOdjR-P", parts)
        // caption whitespace runs collapse to single spaces (see \s+ rule)
        assertEquals("Golden hour at the beach [DV50JOdjR-P].mp4", name)
        // path traversal / separators neutralized
        val evil = parts.copy(caption = "a/b\\c:d*e?f\"g<h>i|j")
        val n2 = InstagramPhotoMuxer.buildFilename("SC", evil)
        assertFalse(n2.contains('/'))
        assertFalse(n2.contains('\\'))
        assertTrue(n2.endsWith("[SC].mp4"))
        // blank caption -> title/artist fallback, else "Instagram"
        val noText = InstagramPhotoMuxer.MediaParts("p", "a", 0, null, null, "   ", false)
        assertEquals("Instagram [SC].mp4", InstagramPhotoMuxer.buildFilename("SC", noText))
    }

    // ---- ffmpeg command ----------------------------------------------------

    @Test
    fun ffmpegVariants_shape() {
        val v = InstagramPhotoMuxer.ffmpegVariants("/x/libffmpeg.so", "/w/c.jpg", "/w/a.m4a", "/out/final.mp4")
        assertEquals(2, v.size)
        val x264 = v[0]
        assertEquals("/x/libffmpeg.so", x264[0])
        assertTrue(x264.containsAll(listOf("-loop", "1")))
        assertTrue(x264.windowed(2).any { it == listOf("-c:v", "libx264") })
        assertTrue(x264.contains("-shortest"))
        assertTrue(x264.windowed(2).any { it == listOf("-r", "30") })
        assertTrue(x264.windowed(2).any { it == listOf("-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1") })
        assertEquals("/out/final.mp4", x264.last())
        val mpeg = v[1]
        assertTrue(mpeg.windowed(2).any { it == listOf("-c:v", "mpeg4") })
    }

    @Test
    fun ffmpegVariants_withoutAudio() {
        val v = InstagramPhotoMuxer.ffmpegVariants("/x/libffmpeg.so", "/w/c.jpg", null, "/out/final.mp4")
        assertEquals(2, v.size)
        val x264 = v[0]
        assertEquals("/x/libffmpeg.so", x264[0])
        // TRIAGE (A) TEST DRIFT: v3.2.7 (a099465) extended the no-music silent
        // hold from 3s to 5s — the Python monolith's exact payload — so the
        // user still gets a playable video without audio.
        assertTrue(x264.containsAll(listOf("-loop", "1", "-t", "5")))
        assertFalse(x264.contains("-shortest"))
        assertTrue(x264.windowed(2).any { it == listOf("-r", "30") })
        assertEquals("/out/final.mp4", x264.last())
    }

    @Test
    fun rawAudioHintsIgnoreVideoProgressiveUrls() {
        // A video_versions URL must never be treated as an audio track.
        val video = """{"video_versions":[{"url":"https://x/v.mp4","progressive_download_url":"https://x/v.mp4"}]}"""
        assertNull(InstagramPhotoMuxer.extractRawAudioHintsFromText(video))
        val audio = """{"audio_src":"https://x/a.m4a","song_name":"Song","display_artist":"Artist","audio_cluster_id":"123456789"}"""
        val parts = InstagramPhotoMuxer.extractRawAudioHintsFromText(audio)
        assertNotNull(parts)
        assertEquals("https://x/a.m4a", parts!!.audioUrl)
        assertEquals("Song", parts.title)
        assertEquals("Artist", parts.artist)
        assertEquals("123456789", parts.audioClusterId)
    }

    @Test
    fun slideTargetSizeIsEvenAndCapped() {
        assertEquals(1080 to 1350, InstagramPhotoMuxer.slideTargetSize(listOf(1080 to 1350, 1080 to 1080)))
        assertEquals(1080 to 1920, InstagramPhotoMuxer.slideTargetSize(listOf(2000 to 3000, 1080 to 1920)))
        // Odd dimensions round down to even; an unknown set falls back to 1080².
        assertEquals(1080 to 1080, InstagramPhotoMuxer.slideTargetSize(listOf(1081 to 1081)))
        assertEquals(1080 to 1080, InstagramPhotoMuxer.slideTargetSize(emptyList()))
    }

    @Test
    fun ffmpegSlideshowVariantsUseCfrAndSetsar() {
        val v = InstagramPhotoMuxer.ffmpegSlideshowVariants(
            "/x/libffmpeg.so", "/w/slides.txt", "/w/a.m4a", "/out/final.mp4"
        )
        assertEquals(2, v.size)
        val x264 = v[0]
        assertTrue(x264.contains("-shortest"))
        assertTrue(x264.windowed(2).any { it == listOf("-r", "30") })
        assertTrue(x264.windowed(2).any { it == listOf("-vsync", "cfr") })
        assertTrue(x264.windowed(2).any {
            it == listOf("-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1")
        })
    }

    @Test
    fun ffmpegLdPathCoversUsrLibAndNativeDir() {
        // Regression: the old code put only the executable's parent on
        // LD_LIBRARY_PATH; the extracted runtime keeps libav*.so.* in usr/lib.
        val root = Files.createTempDirectory("ffmpeg-ld").toFile()
        val native = Files.createTempDirectory("ffmpeg-native-ld").toFile()
        try {
            val bin = File(root, "usr/bin").apply { mkdirs() }
            val usrLib = File(root, "usr/lib").apply { mkdirs() }
            val exe = File(bin, "ffmpeg").apply { writeBytes(ByteArray(1)) }
            val parts = InstagramPhotoMuxer
                .buildFfmpegLdLibraryPath(exe, native.absolutePath)
                .split(File.pathSeparator)
            assertTrue(parts.contains(bin.absolutePath))
            assertTrue(parts.contains(usrLib.absolutePath))
            assertTrue(parts.contains(native.absolutePath))
        } finally {
            root.deleteRecursively()
            native.deleteRecursively()
        }
    }

    @Test
    fun ffmpegBinaryPrefersExtractedRuntimeWithVersionedLibs() {
        // The AAR extracts the complete runtime (versioned libav*.so.61) from
        // libffmpeg.zip.so into noBackupFilesDir/youtubedl-android/packages/
        // ffmpeg; the bare jniLib executable has no sibling libraries and
        // dies with "libavdevice.so.61 not found" on device (2026-10-19).
        val extracted = Files.createTempDirectory("ffmpeg-extracted").toFile()
        val native = Files.createTempDirectory("ffmpeg-native").toFile()
        try {
            val real = File(extracted, "libffmpeg.so").apply { writeBytes(ByteArray(8)) }
            File(extracted, "libavdevice.so.61").writeBytes(ByteArray(8))
            val nativeLib = File(native, "libffmpeg.so").apply { writeBytes(ByteArray(8)) }

            assertEquals(real.absolutePath, InstagramPhotoMuxer.pickFfmpegBinary(extracted, native)?.absolutePath)
            // Old layout name is still accepted.
            real.delete()
            val legacy = File(extracted, "libffmpeg.bin.so").apply { writeBytes(ByteArray(4)) }
            assertEquals(legacy.absolutePath, InstagramPhotoMuxer.pickFfmpegBinary(extracted, native)?.absolutePath)
            // Nothing extracted -> preserve the historical fallback.
            legacy.delete()
            assertEquals(nativeLib.absolutePath, InstagramPhotoMuxer.pickFfmpegBinary(extracted, native)?.absolutePath)
        } finally {
            extracted.deleteRecursively()
            native.deleteRecursively()
        }
    }

    @Test
    fun ffmpegLdPathIncludesNoBackupPackagesWhenBinaryIsInNativeDir() {
        // Issue 1 regression: When libffmpeg.so is in nativeLibraryDir (e.g. /data/app/.../lib/arm64),
        // buildFfmpegLdLibraryPath must include the extracted packages/ffmpeg/usr/lib directory
        // from noBackupFilesDir so that the dynamic linker finds versioned libs (libavdevice.so.61).
        val noBackup = Files.createTempDirectory("ffmpeg-nobackup").toFile()
        val native = Files.createTempDirectory("ffmpeg-native").toFile()
        try {
            val ffmpegUsrLib = File(noBackup, "youtubedl-android/packages/ffmpeg/usr/lib").apply { mkdirs() }
            val pythonUsrLib = File(noBackup, "youtubedl-android/packages/python/usr/lib").apply { mkdirs() }
            val exe = File(native, "libffmpeg.so").apply { writeBytes(ByteArray(8)) }

            val path = InstagramPhotoMuxer.buildFfmpegLdLibraryPath(exe, native.absolutePath, noBackup)
            val parts = path.split(File.pathSeparator)

            assertTrue(parts.contains(ffmpegUsrLib.absolutePath))
            assertTrue(parts.contains(pythonUsrLib.absolutePath))
            assertTrue(parts.contains(native.absolutePath))
        } finally {
            noBackup.deleteRecursively()
            native.deleteRecursively()
        }
    }
}
