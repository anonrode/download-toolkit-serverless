package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 7 (engine speed queue add-on): subtitle extension alignment.
 *
 * The player discovers only srt/vtt/ass/ssa. The engine's artifact set also
 * carries ttml/json3/srv3 (yt-dlp can leave those behind), so the engine must
 * convert them to SRT before placing a sidecar beside the video — and must
 * skip (not mislabel) a body it cannot parse.
 */
class SubtitleArtifactsTest {

    @Test
    fun extensionSetsMatchThePlayerDiscoveryContract() {
        assertEquals(setOf("srt", "vtt", "ass", "ssa"), SubtitleArtifacts.PLAYER_SIDECAR_EXTS)
        assertEquals(setOf("ttml", "json3", "srv3"), SubtitleArtifacts.EXOTIC_TIMED_TEXT_EXTS)
        assertEquals(
            setOf("srt", "vtt", "ass", "ssa", "ttml", "json3", "srv3"),
            SubtitleArtifacts.RENAMED_ARTIFACT_EXTS
        )
        assertTrue(
            SubtitleArtifacts.RENAMED_ARTIFACT_EXTS.containsAll(SubtitleArtifacts.PLAYER_SIDECAR_EXTS)
        )
    }

    @Test
    fun nativePlayerFormatsPassThroughAndSsaNormalizesToAss() {
        assertEquals("srt", SubtitleArtifacts.alignForPlayer("1\n00:00:01,000 --> 00:00:02,000\nhi\n", "srt")!!.extension)
        assertEquals("vtt", SubtitleArtifacts.alignForPlayer("WEBVTT\n", "vtt")!!.extension)
        val ssa = SubtitleArtifacts.alignForPlayer("[Script Info]\n", "ssa")!!
        assertEquals("ass", ssa.extension)
        assertEquals("[Script Info]\n", ssa.body)
    }

    @Test
    fun json3ConvertsToSrtTimingsAndText() {
        val json3 = """
            {"events":[
              {"tStartMs":1000,"dDurationMs":1500,"segs":[{"utf8":"Hello "},{"utf8":"world"}]},
              {"tStartMs":4000,"dDurationMs":2000,"segs":[{"utf8":"Second line"}]}
            ]}
        """.trimIndent()
        val srt = SubtitleArtifacts.alignForPlayer(json3, "json3")!!
        assertEquals("srt", srt.extension)
        assertTrue(srt.body, srt.body.contains("00:00:01,000 --> 00:00:02,500"))
        assertTrue(srt.body, srt.body.contains("Hello world"))
        assertTrue(srt.body, srt.body.contains("00:00:04,000 --> 00:00:06,000"))
        assertTrue(srt.body, srt.body.contains("Second line"))
        assertTrue(srt.body.startsWith("1\n"))
    }

    @Test
    fun ttmlConvertsClockTimesAndStripsNestedTags() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="00:00:01.000" end="00:00:02.500">Hello <span>world</span></p>
            </div></body></tt>
        """.trimIndent()
        val srt = SubtitleArtifacts.alignForPlayer(ttml, "ttml")!!
        assertEquals("srt", srt.extension)
        assertTrue(srt.body, srt.body.contains("00:00:01,000 --> 00:00:02,500"))
        assertTrue(srt.body, srt.body.contains("Hello world"))
    }

    @Test
    fun srv3ConvertsSecondsAndDurations() {
        val srv3 = "<timedtext><body><text start=\"1.5\" dur=\"2\">Hi <br/>there</text></body></timedtext>"
        val srt = SubtitleArtifacts.alignForPlayer(srv3, "srv3")!!
        assertEquals("srt", srt.extension)
        assertTrue(srt.body, srt.body.contains("00:00:01,500 --> 00:00:03,500"))
        assertTrue(srt.body, srt.body.contains("Hi \nthere"))
    }

    @Test
    fun unparseableExoticBodiesAreSkippedNotMislabeled() {
        assertNull(SubtitleArtifacts.alignForPlayer("<html><body>error</body></html>", "ttml"))
        assertNull(SubtitleArtifacts.alignForPlayer("not json", "json3"))
        assertNull(SubtitleArtifacts.alignForPlayer("<timedtext></timedtext>", "srv3"))
        assertNull(SubtitleArtifacts.alignForPlayer("whatever", "sub"))
    }

    @Test
    fun sniffExoticIdentifiesTheThreeFormatsAndNeverNativeOnes() {
        assertEquals("json3", SubtitleArtifacts.sniffExotic("""{"events":[{"tStartMs":0,"segs":[]}]}"""))
        assertEquals("srv3", SubtitleArtifacts.sniffExotic("<timedtext><body><text start=\"1\">x</text></body></timedtext>"))
        assertEquals("ttml", SubtitleArtifacts.sniffExotic("<tt xmlns=\"http://www.w3.org/ns/ttml\"><p begin=\"1s\">x</p></tt>"))
        assertNull(SubtitleArtifacts.sniffExotic("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhi"))
        assertNull(SubtitleArtifacts.sniffExotic("1\n00:00:01,000 --> 00:00:02,000\nhi\n"))
    }
}
