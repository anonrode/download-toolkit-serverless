package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 2026-10-19 live-playlist policy and the structural plan
 * fingerprint used to decide whether banked HLS bytes may be resumed.
 */
class HlsPlaylistTest {

    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:9.009,
        seg-000.ts
        #EXTINF:9.009,
        seg-001.ts
        #EXTINF:9.009,
        seg-002.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private val masterPlaylist = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        v1/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
        v2/index.m3u8
    """.trimIndent()

    @Test
    fun relativeOnlyPlaylistStaysLive() {
        val base = "https://cdn.example/show/ep1/master.m3u8?token=abc"
        assertFalse(HlsPlaylist.needsLocalRewrite(mediaPlaylist, upgradeInsecureHttp = false))
        assertNull(HlsPlaylist.rewrite(mediaPlaylist, base, upgradeInsecureHttp = false))
        assertNull(HlsPlaylist.rewrite(mediaPlaylist, base, upgradeInsecureHttp = true))
    }

    @Test
    fun schemeRelativeUriForcesLocalRewriteAndAbsolutizesRelatives() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="//keys.example/k1"
            #EXTINF:4,
            //cdn.example/seg0.ts
            #EXTINF:4,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val base = "https://origin.example/show/ep1/index.m3u8"
        assertTrue(HlsPlaylist.needsLocalRewrite(playlist, upgradeInsecureHttp = false))
        val out = HlsPlaylist.rewrite(playlist, base, upgradeInsecureHttp = false)
        assertNotNull(out)
        assertTrue(out!!.contains("URI=\"https://keys.example/k1\""))
        assertTrue(out.contains("https://cdn.example/seg0.ts"))
        // A local file resolves against file://, so the relative segment MUST
        // be absolutized too.
        assertTrue(out.contains("https://origin.example/show/ep1/seg1.ts"))
    }

    @Test
    fun absoluteHttpIsLiveByDefaultAndUpgradesOnlyWhenApproved() {
        val playlist = """
            #EXTM3U
            #EXTINF:4,
            http://cdn.example/seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val base = "http://cdn.example/index.m3u8"
        assertNull(HlsPlaylist.rewrite(playlist, base, upgradeInsecureHttp = false))
        val upgraded = HlsPlaylist.rewrite(playlist, base, upgradeInsecureHttp = true)
        assertNotNull(upgraded)
        assertTrue(upgraded!!.contains("https://cdn.example/seg0.ts"))
        assertFalse(upgraded.contains("http://cdn.example"))
    }

    @Test
    fun fingerprintIgnoresTokenRotationInQueryAndPath() {
        val base = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:9.009,
            https://cdn.example/tokenAAA/seg-000.ts?token=AAA
            #EXTINF:9.009,
            https://cdn.example/tokenAAA/seg-001.ts?token=AAA
            #EXT-X-ENDLIST
        """.trimIndent()
        val rotated = base.replace("tokenAAA", "tokenZZZ").replace("AAA", "ZZZ")
        assertEquals(HlsPlaylist.fingerprint(listOf(base)), HlsPlaylist.fingerprint(listOf(rotated)))
    }

    @Test
    fun fingerprintChangesWhenPlanChanges() {
        val base = HlsPlaylist.fingerprint(listOf(mediaPlaylist))
        val extraSegment = mediaPlaylist.replace(
            "#EXT-X-ENDLIST",
            "#EXTINF:9.009,\nseg-003.ts\n#EXT-X-ENDLIST"
        )
        val changedDuration = mediaPlaylist.replaceFirst("#EXTINF:9.009,", "#EXTINF:10.009,")
        val changedKey = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/other"
            #EXTINF:9.009,
            seg-000.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertNotEquals(base, HlsPlaylist.fingerprint(listOf(extraSegment)))
        assertNotEquals(base, HlsPlaylist.fingerprint(listOf(changedDuration)))
        assertNotEquals(base, HlsPlaylist.fingerprint(listOf(changedKey)))
    }

    @Test
    fun fingerprintIgnoresIvRotationButKeepsKeyMethod() {
        val a = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1",IV=0x00000000000000000000000000000001
            #EXTINF:4,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val b = a.replace("00000000000000000000000000000001", "0000000000000000000000000000000F")
        assertEquals(HlsPlaylist.fingerprint(listOf(a)), HlsPlaylist.fingerprint(listOf(b)))
        val methodChanged = a.replace("METHOD=AES-128", "METHOD=SAMPLE-AES")
        assertNotEquals(HlsPlaylist.fingerprint(listOf(a)), HlsPlaylist.fingerprint(listOf(methodChanged)))
    }

    @Test
    fun masterFingerprintTracksVariantSetAndResolution() {
        val base = HlsPlaylist.fingerprint(listOf(masterPlaylist))
        val reordered = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            v2/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            v1/index.m3u8
        """.trimIndent()
        assertNotEquals(base, HlsPlaylist.fingerprint(listOf(reordered)))
    }
}
