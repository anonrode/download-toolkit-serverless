package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for HlsSizeEstimator's pure functions: segment counting from a
 * fixture playlist, the estimate math, the fMP4 remux factor, and the
 * zero/empty degenerate cases.
 */
class HlsSizeEstimatorTest {

    /** A small VOD media playlist with exactly 10 EXTINF segments (.ts). */
    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:9.009,
        seg-00001.ts
        #EXTINF:9.009,
        seg-00002.ts
        #EXTINF:9.009,
        seg-00003.ts
        #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
        #EXTINF:9.009,
        seg-00004.ts
        #EXTINF:9.009,
        seg-00005.ts
        #EXTINF:9.009,
        seg-00006.ts
        #EXTINF:9.009,
        seg-00007.ts
        #EXTINF:9.009,
        seg-00008.ts
        #EXT-X-PROGRAM-DATE-TIME:2026-08-22T00:00:00Z
        #EXTINF:9.009,
        seg-00009.ts
        #EXTINF:9.009,
        seg-00010.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    // ---- parseSegmentCount ------------------------------------------------

    @Test
    fun countsEveryExtinfLineAsASegment() {
        assertEquals(10, HlsSizeEstimator.parseSegmentCount(mediaPlaylist))
    }

    @Test
    fun ignoresNonSegmentTags() {
        // EXT-X-KEY and EXT-X-PROGRAM-DATE-TIME lines must not be counted,
        // and a URI line is not a segment.
        val noisy = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            https://cdn.example.com/media/1.mp4
            #EXT-X-DISCONTINUITY
            #EXTINF:6.0,
            https://cdn.example.com/media/2.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals(2, HlsSizeEstimator.parseSegmentCount(noisy))
    }

    @Test
    fun countsExtinfWithCommaOrMissingMetadata() {
        val variants = """
            #EXTINF:10.0,
            a.ts
            #EXTINF:-1,
            b.ts
            #EXTINF:4.002,title here
            c.ts
            #EXTINF 9.5,
            d.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals(4, HlsSizeEstimator.parseSegmentCount(variants))
    }

    @Test
    fun emptyTextHasZeroSegments() {
        assertEquals(0, HlsSizeEstimator.parseSegmentCount(""))
        assertEquals(0, HlsSizeEstimator.parseSegmentCount("\n\n"))
    }

    @Test
    fun textWithoutExtinfHasZeroSegments() {
        val noSegments = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            video-360.m3u8
        """.trimIndent()
        assertEquals(0, HlsSizeEstimator.parseSegmentCount(noSegments))
    }

    // ---- estimateFromSamples ----------------------------------------------

    @Test
    fun estimateIsMeanTimesSegmentCountTimesRemuxFactor() {
        // mean(1000, 2000, 3000, 4000) = 2500; 2500 * 100 * 0.93 = 232500
        val estimate = HlsSizeEstimator.estimateFromSamples(
            listOf(1000L, 2000L, 3000L, 4000L), segmentCount = 100, isFmp4 = false
        )
        assertEquals(232500L, estimate)
    }

    @Test
    fun estimateUsesFmp4FactorWhenPlaylistIsFmp4() {
        // 2500 * 100 * 0.99 = 247500
        val estimate = HlsSizeEstimator.estimateFromSamples(
            listOf(1000L, 2000L, 3000L, 4000L), segmentCount = 100, isFmp4 = true
        )
        assertEquals(247500L, estimate)
    }

    @Test
    fun estimateWithSingleSample() {
        // 5000 * 10 * 0.93 = 46500
        val estimate = HlsSizeEstimator.estimateFromSamples(
            listOf(5000L), segmentCount = 10, isFmp4 = false
        )
        assertEquals(46500L, estimate)
    }

    @Test
    fun estimateIsZeroForEmptySamples() {
        assertEquals(0L, HlsSizeEstimator.estimateFromSamples(emptyList(), 10, isFmp4 = false))
    }

    @Test
    fun estimateIsZeroForZeroOrNegativeSamples() {
        assertEquals(0L, HlsSizeEstimator.estimateFromSamples(listOf(0L, 100L), 10, isFmp4 = false))
        assertEquals(0L, HlsSizeEstimator.estimateFromSamples(listOf(-5L, 100L), 10, isFmp4 = false))
    }

    @Test
    fun estimateIsZeroForZeroSegmentCount() {
        assertEquals(0L, HlsSizeEstimator.estimateFromSamples(listOf(1000L), 0, isFmp4 = false))
        assertEquals(0L, HlsSizeEstimator.estimateFromSamples(listOf(1000L), -1, isFmp4 = false))
    }

    @Test
    fun estimateScalesLinearlyWithSegmentCount() {
        val perSegment = HlsSizeEstimator.estimateFromSamples(listOf(1_000_000L), 100, isFmp4 = false)
        val doubleSegments = HlsSizeEstimator.estimateFromSamples(listOf(1_000_000L), 200, isFmp4 = false)
        assertEquals(perSegment * 2L, doubleSegments)
        assertTrue(perSegment > 0L)
    }
}
