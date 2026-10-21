package com.anonrode.downloader.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * RC-9 (board row 35): the player-paced in-process HLS lane. Covers the
 * pure contract surfaces — master/variant parsing, resume sidecar, the
 * sequential append loop with exact water-mark semantics, byte-resume, and
 * the typed rate-limit escape — plus the FULL-DOWNLOAD guarantee: a 403
 * mid-stream must leave every committed segment on disk with sidecar state
 * that RESUMES, never restarts.
 */
class HlsVodDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var tmp: File

    /** Deterministic per-index segment payloads ("SEGMENT-0-…" × 64). */
    private val segmentBodies: List<ByteArray> =
        (0 until 5).map { i -> ("SEGMENT-$i-").repeat(64).toByteArray() }

    /** Global request counter (redial/dial assertions). */
    private val reqSeq = java.util.concurrent.atomic.AtomicLong(0)
    /** Per-fixture 403-serving-path counter. */
    private val failHubRequests = java.util.concurrent.atomic.AtomicLong(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
        tmp = createTempDir("hlsrs")
        reqSeq.set(0)
        failHubRequests.set(0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun index(path: String?): Int =
        Regex("seg(\\d)").find(path ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private fun concat(parts: List<ByteArray>): ByteArray = parts.reduce { a, b -> a + b }

    // ─────────────────────────── parsing ───────────────────────────

    @Test
    fun `master picks the highest variant at or below the requested height`() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1962263,RESOLUTION=1920x1080
            index-f1-v1-a1.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1151726,RESOLUTION=1280x720
            index-f2-v1-a1.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=558600,RESOLUTION=640x360
            index-f3-v1-a1.m3u8
        """.trimIndent()
        val base = "https://fetch.example/anime/master.m3u8"
        assertEquals("https://fetch.example/anime/index-f1-v1-a1.m3u8", HlsVodDownloader.parseMaster(body, base, 1080))
        assertEquals("https://fetch.example/anime/index-f2-v1-a1.m3u8", HlsVodDownloader.parseMaster(body, base, 720))
        assertEquals("https://fetch.example/anime/index-f3-v1-a1.m3u8", HlsVodDownloader.parseMaster(body, base, 480))
    }

    @Test
    fun `master with no fitting variant falls back to the smallest`() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1962263,RESOLUTION=1920x1080
            hi.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=558600,RESOLUTION=640x360
            lo.m3u8
        """.trimIndent()
        assertEquals("https://x/y/lo.m3u8", HlsVodDownloader.parseMaster(body, "https://x/y/master.m3u8", 240))
    }

    @Test
    fun `variant-only body is not a master`() {
        val body = "#EXTM3U\n#EXTINF:9.0,\nseg-1.ts\n#EXT-X-ENDLIST\n"
        assertNull(HlsVodDownloader.parseMaster(body, "https://x/v.m3u8", 1080))
    }

    @Test
    fun `variant parses segments and flags`() {
        val body = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:11
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:11.000,
            seg-1-f1-v1-a1.jpg
            #EXTINF:11.000,
            seg-2-f1-v1-a1.html
            #EXTINF:10.500,
            /abs/seg-3.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val v = HlsVodDownloader.parseVariant(body, "https://h/a/pl/index-f1.m3u8")
        assertEquals(
            listOf(
                "https://h/a/pl/seg-1-f1-v1-a1.jpg",
                "https://h/a/pl/seg-2-f1-v1-a1.html",
                "https://h/abs/seg-3.ts"
            ),
            v.segments
        )
        assertEquals(32.5, v.durationSec, 0.001)
        assertFalse(v.encrypted)
        assertFalse(v.byterange)
        assertTrue(v.hasEndlist)
        assertTrue(v.supported)
    }

    @Test
    fun `encrypted byterange and live playlists are unsupported`() {
        val enc = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:9,\ns.ts\n#EXT-X-ENDLIST\n"
        val vEnc = HlsVodDownloader.parseVariant(enc, "https://x/v.m3u8")
        assertTrue(vEnc.encrypted)
        assertFalse(vEnc.supported)

        val br = "#EXTM3U\n#EXTINF:9,\ns.ts\n#EXT-X-BYTERANGE:1000@0\n#EXT-X-ENDLIST\n"
        val vBr = HlsVodDownloader.parseVariant(br, "https://x/v.m3u8")
        assertTrue(vBr.byterange)
        assertFalse(vBr.supported)

        val live = "#EXTM3U\n#EXTINF:9,\ns.ts\n"
        val vLive = HlsVodDownloader.parseVariant(live, "https://x/v.m3u8")
        assertFalse(vLive.hasEndlist)
        assertFalse(vLive.supported)

        // METHOD=NONE is genuinely unencrypted.
        val none = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:9,\ns.ts\n#EXT-X-ENDLIST\n"
        assertFalse(HlsVodDownloader.parseVariant(none, "https://x/v.m3u8").encrypted)
    }

    // ─────────────────────────── P-1 fresh-dial ───────────────────────────

    @Test
    fun `403 drops the connection and redials fresh before escaping`() = runBlocking {
        // Segment 0: first request 403 (the throttled pooled connection),
        // every later request fine (the fresh dial proves the budget is
        // per-connection). Exactly ONE redial must recover the stream inside
        // the same attempt, with the resume boundary untouched.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val idx = index(req.path)
                return if (idx == 0 && reqSeq.getAndIncrement() == 0L)
                    MockResponse().setResponseCode(403)
                else MockResponse().setBody(okio.Buffer().write(segmentBodies[idx]))
            }
        }
        val segs = (0 until 2).map { server.url("/seg$it.ts").toString() }
        val dest = File(tmp, "freshdial.hts")
        val sidecar = File(tmp, "freshdial.hlsrs")
        HlsVodDownloader.downloadSegments(
            client, segs, mapOf("Referer" to "https://megaplay.example/"), dest, sidecar,
            estimatedTotalBytes = 0L, onProgress = { _, _, _, _ -> }, isCancelled = { false }
        )
        // The completed file is exact (byte order preserved redial-or-not).
        assertTrue(dest.readBytes().contentEquals(concat(segmentBodies.take(2))))
        assertEquals(HlsVodDownloader.ResumeState(2, concat(segmentBodies.take(2)).size.toLong()),
            HlsVodDownloader.readResume(sidecar))
        // seg0 was requested twice: the throttled dial + the fresh one; seg1 once.
        assertEquals(3, reqSeq.get())
    }

    @Test
    fun `persistent 403 across three fresh dials escapes as RateLimited with boundary held`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                if (index(req.path) == 1) {
                    failHubRequests.incrementAndGet()
                    MockResponse().setResponseCode(403)
                } else MockResponse().setBody(okio.Buffer().write(segmentBodies[0]))
        }
        val segs = (0 until 3).map { server.url("/seg$it.ts").toString() }
        val dest = File(tmp, "persist.hts")
        val sidecar = File(tmp, "persist.hlsrs")
        var caught: HlsVodDownloader.RateLimited? = null
        try {
            HlsVodDownloader.downloadSegments(
                client, segs, emptyMap(), dest, sidecar,
                estimatedTotalBytes = 0L, onProgress = { _, _, _, _ -> }, isCancelled = { false }
            )
        } catch (e: HlsVodDownloader.RateLimited) {
            caught = e
        }
        assertNotNull(caught)
        assertEquals(403, caught!!.lastStatus)
        assertEquals(1, caught.segmentsDone)
        // Boundary = exactly segment 0's bytes; sidecar resumes there.
        assertTrue(dest.readBytes().contentEquals(segmentBodies[0]))
        assertEquals(HlsVodDownloader.ResumeState(1, segmentBodies[0].size.toLong()),
            HlsVodDownloader.readResume(sidecar))
        // Three dials for the 403ing segment: original + two fresh redials of
        // the pool before the typed escape.
        assertEquals(3, failHubRequests.get())
    }

    // ─────────────────────────── resume sidecar ───────────────────────────

    @Test
    fun `subtitle media in a master is detected for the yt-dlp handoff`() {
        val withSubs = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs_en.m3u8"
            #EXT-X-STREAM-INF:RESOLUTION=1920x1080,SUBTITLES="subs"
            index-v1.m3u8
        """.trimIndent()
        assertTrue(HlsVodDownloader.hasSubtitleMedia(withSubs))
        val clean = "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nindex-f2.m3u8\n"
        assertFalse(HlsVodDownloader.hasSubtitleMedia(clean))
    }


    @Test
    fun `resume sidecar roundtrips and survives corruption`() {
        val f = File(tmp, "a.hlsrs")
        assertEquals(HlsVodDownloader.ResumeState(0, 0L), HlsVodDownloader.readResume(f))
        HlsVodDownloader.writeResume(f, 12, 94112L)
        assertEquals(HlsVodDownloader.ResumeState(12, 94112L), HlsVodDownloader.readResume(f))
        f.writeText("garbage\n")
        assertEquals(HlsVodDownloader.ResumeState(0, 0L), HlsVodDownloader.readResume(f))
        HlsVodDownloader.writeResume(f, 3, 4L)
        assertFalse(File(f.absolutePath + ".tmp").exists())
    }

    // ─────────────────────────── download loop ───────────────────────────

    @Test
    fun `sequential download appends in order and records resume state`() = runBlocking {
        val order = ConcurrentLinkedQueue<Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val idx = index(req.path)
                order.add(idx)
                return MockResponse().setBody(okio.Buffer().write(segmentBodies[idx]))
            }
        }
        val segs = (0 until 5).map { server.url("/seg$it.ts").toString() }
        val dest = File(tmp, "out.hts")
        val sidecar = File(tmp, "out.hlsrs")
        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        HlsVodDownloader.downloadSegments(
            client, segs, emptyMap(), dest, sidecar,
            estimatedTotalBytes = 0L,
            onProgress = { dl, tot, _, _ -> progress.add(dl to tot) },
            isCancelled = { false }
        )
        // Exact byte order on disk.
        val expected = concat(segmentBodies)
        assertTrue(dest.readBytes().contentEquals(expected))
        assertEquals(HlsVodDownloader.ResumeState(5, expected.size.toLong()), HlsVodDownloader.readResume(sidecar))
        // Server-side arrival order is strictly sequential.
        assertEquals(listOf(0, 1, 2, 3, 4), order.toList())
        // Progress is monotone and ends complete.
        assertEquals(5, progress.size)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first > a.first })
        assertEquals(expected.size.toLong(), progress.last().first)
        assertTrue(progress.last().second >= expected.size.toLong())
    }

    @Test
    fun `resume skips committed segments and appends only the tail`() = runBlocking {
        val seen = ConcurrentLinkedQueue<Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val idx = index(req.path)
                seen.add(idx)
                return MockResponse().setBody(okio.Buffer().write(segmentBodies[idx]))
            }
        }
        val segs = (0 until 5).map { server.url("/seg$it.ts").toString() }
        val dest = File(tmp, "resume.hts")
        val sidecar = File(tmp, "resume.hlsrs")
        val prefix = concat(segmentBodies.take(2))
        dest.writeBytes(prefix)
        HlsVodDownloader.writeResume(sidecar, 2, prefix.size.toLong())

        HlsVodDownloader.downloadSegments(
            client, segs, emptyMap(), dest, sidecar,
            estimatedTotalBytes = 0L, onProgress = { _, _, _, _ -> }, isCancelled = { false }
        )
        val expected = concat(segmentBodies)
        assertTrue(dest.readBytes().contentEquals(expected))
        assertEquals(listOf(2, 3, 4), seen.toList())
        assertEquals(HlsVodDownloader.ResumeState(5, expected.size.toLong()), HlsVodDownloader.readResume(sidecar))
    }

    @Test
    fun `403 mid-stream throws RateLimited with real status and keeps committed state`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val idx = index(req.path)
                return if (idx == 2) MockResponse().setResponseCode(403)
                else MockResponse().setBody(okio.Buffer().write(segmentBodies[idx]))
            }
        }
        val segs = (0 until 5).map { server.url("/seg$it.ts").toString() }
        val dest = File(tmp, "quota.hts")
        val sidecar = File(tmp, "quota.hlsrs")
        var caught: HlsVodDownloader.RateLimited? = null
        try {
            HlsVodDownloader.downloadSegments(
                client, segs, emptyMap(), dest, sidecar,
                estimatedTotalBytes = 0L, onProgress = { _, _, _, _ -> }, isCancelled = { false }
            )
        } catch (e: HlsVodDownloader.RateLimited) {
            caught = e
        }
        assertNotNull(caught)
        val prefix = concat(segmentBodies.take(2))
        assertEquals(2, caught!!.segmentsDone)
        assertEquals(403, caught.lastStatus)
        assertEquals(prefix.size.toLong(), caught.bytesDone)
        // The file on disk holds exactly the committed prefix; sidecar matches.
        assertTrue(dest.readBytes().contentEquals(prefix))
        assertEquals(HlsVodDownloader.ResumeState(2, prefix.size.toLong()), HlsVodDownloader.readResume(sidecar))
        // …and the SAME call resumes: only the remaining segments are fetched,
        // with the persisted headers intact (RC-8 follow-through).
        val resumed = HlsVodDownloader.downloadSegments(
            client, segs, mapOf("Referer" to "https://megaplay.example/"), dest, sidecar,
            estimatedTotalBytes = 0L, onProgress = { _, _, _, _ -> }, isCancelled = { false }
        )
        assertTrue(resumed.readBytes().contentEquals(concat(segmentBodies)))
    }

    @Test
    fun `resume trims garbage past the water mark before appending`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val idx = index(req.path)
                return MockResponse().setBody(okio.Buffer().write(segmentBodies[idx]))
            }
        }
        val segs = (0 until 3).map { server.url("/seg$it.ts").toString() }
        val dest = File(tmp, "trim.hts")
        val sidecar = File(tmp, "trim.hlsrs")
        val prefix = concat(segmentBodies.take(2))
        dest.writeBytes(prefix + "PARTIAL-GARBAGE".toByteArray())
        HlsVodDownloader.writeResume(sidecar, 2, prefix.size.toLong())

        HlsVodDownloader.downloadSegments(
            client, segs, emptyMap(), dest, sidecar,
            estimatedTotalBytes = 0L, onProgress = { _, _, _, _ -> }, isCancelled = { false }
        )
        assertTrue(dest.readBytes().contentEquals(concat(segmentBodies.take(3))))
    }

    @Test
    fun `task headers ride on every segment request`() = runBlocking {
        val referrers = ConcurrentLinkedQueue<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                referrers.add(req.getHeader("Referer"))
                val idx = index(req.path)
                return MockResponse().setBody(okio.Buffer().write(segmentBodies[idx]))
            }
        }
        val segs = (0 until 2).map { server.url("/seg$it.ts").toString() }
        val headers = mapOf("Referer" to "https://megaplay.example/", "User-Agent" to "AnonTest/1.0")
        HlsVodDownloader.downloadSegments(
            client, segs, headers, File(tmp, "hdr.hts"), File(tmp, "hdr.hlsrs"),
            estimatedTotalBytes = 0L, onProgress = { _, _, _, _ -> }, isCancelled = { false }
        )
        // Every request, without exception — a retry must never run with
        // fewer headers than the attempt that worked (RC-8 follow-through).
        assertEquals(listOf("https://megaplay.example/", "https://megaplay.example/"), referrers.toList())
    }
}
