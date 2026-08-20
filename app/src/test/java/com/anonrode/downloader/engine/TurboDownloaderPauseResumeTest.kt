package com.anonrode.downloader.engine

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.math.min

/**
 * JVM tests verifying pause/resume for both single-mode and segmented-mode
 * downloads. A slow chunked HTTP server keeps the transfer in-flight long
 * enough to cancel mid-stream and inspect the partial state.
 */
class TurboDownloaderPauseResumeTest {

    private lateinit var server: SlowHttpServer
    private lateinit var client: OkHttpClient
    private lateinit var dir: File

    @Before
    fun setUp() {
        TurboDownloader.retryBaseDelayMs = 1L
        dir = createTempDirectory("turbo-resume-test").toFile()
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        TurboDownloader.retryBaseDelayMs = 1000L
        server.stop()
        dir.deleteRecursively()
    }

    @Test
    fun singleMode_resumesAfterPause() = runBlocking {
        val payload = payload(2 * 1024 * 1024)
        server = SlowHttpServer(payload, chunkBytes = 256 * 1024, chunkDelayMs = 60L)
        server.start()
        val dest = File(dir, "video.mp4")

        // Run 1: start download, cancel after a few chunks are on disk (pause).
        val job = launch(Dispatchers.IO) {
            TurboDownloader.download(
                url = "http://localhost:${server.port}/video.mp4",
                dest = dest,
                configuredSockets = 4,
                client = client
            )
        }
        val part = File(dest.absolutePath + ".part")
        var pausedBytes = 0L
        while (pausedBytes < 256 * 1024L) {
            delay(5)
            pausedBytes = if (part.exists()) part.length() else 0L
        }
        job.cancelAndJoin()
        assertTrue(pausedBytes < payload.size)

        // Record the server's state before the resume run.
        val rangeStartsBefore = server.rangeStarts.toList()

        // Run 2: resume (same dest, same part file).
        val result = TurboDownloader.download(
            url = "http://localhost:${server.port}/video.mp4",
            dest = dest,
            configuredSockets = 4,
            client = client
        )
        assertTrue("expected Success, got $result", result is TurboDownloader.TurboResult.Success)
        val success = result as TurboDownloader.TurboResult.Success
        assertEquals(payload.size.toLong(), success.bytes)
        assertFalse(success.segmented) // 2MB < 8MB
        assertTrue(dest.readBytes().contentEquals(payload))

        // The resumed run's first range request must have started where the
        // paused run left off, not at 0.
        val newRangeStarts = server.rangeStarts.drop(rangeStartsBefore.size)
        assertTrue("expected at least one range request on resume, got none", newRangeStarts.isNotEmpty())
        assertTrue("resume should start past 0, started at ${newRangeStarts.first()}", newRangeStarts.first() > 0)
    }

    @Test
    fun segmented_resumesFromSidecarAfterPause() = runBlocking {
        val payload = payload(20 * 1024 * 1024)
        server = SlowHttpServer(payload, chunkBytes = 512 * 1024, chunkDelayMs = 20L)
        server.start()
        val dest = File(dir, "video.mp4")

        // Run 1: start segmented download, cancel after a few pieces.
        val job = launch(Dispatchers.IO) {
            TurboDownloader.download(
                url = "http://localhost:${server.port}/video.mp4",
                dest = dest,
                configuredSockets = 4,
                client = client
            )
        }
        delay(400) // let a few pieces complete; sidecar gets committed
        job.cancelAndJoin()

        // Verify the sidecar has at least one complete piece.
        val sidecar = File(dest.absolutePath + ".turbo")
        assertTrue("sidecar should exist after segmented pause", sidecar.exists())
        val prefix = TurboState(sidecar).contiguousPrefixBytes()
        assertNotNull("sidecar should have a readable prefix", prefix)
        assertTrue("at least one piece should be complete after 400ms, got $prefix", prefix!! > 0)

        // Run 2: resume.
        val result = TurboDownloader.download(
            url = "http://localhost:${server.port}/video.mp4",
            dest = dest,
            configuredSockets = 4,
            client = client
        )
        assertTrue("expected Success, got $result", result is TurboDownloader.TurboResult.Success)
        val success = result as TurboDownloader.TurboResult.Success
        assertEquals(payload.size.toLong(), success.bytes)
        assertTrue(success.segmented) // 20MB > 8MB
        assertTrue(dest.readBytes().contentEquals(payload))
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }
}

/**
 * A minimal HTTP server that writes the response body in slow chunks, so
 * a download stays in-flight long enough to cancel mid-stream.
 * Supports Range requests (206) for resume testing.
 */
private class SlowHttpServer(
    private val content: ByteArray,
    private val chunkBytes: Int = 256 * 1024,
    private val chunkDelayMs: Long = 60L
) {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort
    private val _rangeStarts = mutableListOf<Int>()
    val rangeStarts: List<Int> get() = synchronized(_rangeStarts) { _rangeStarts.toList() }

    private val running = AtomicInteger(1)

    fun start() {
        Thread {
            while (running.get() > 0) {
                try {
                    val socket = serverSocket.accept()
                    Thread {
                        try { handle(socket) } catch (_: Exception) {}
                    }.apply { isDaemon = true }.start()
                } catch (_: Exception) {
                    if (running.get() > 0) running.set(0)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(0)
        try { serverSocket.close() } catch (_: Exception) {}
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line: String
            while (reader.readLine().also { line = it } != null && line.isNotBlank()) {
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val range = headers["range"]
            val out = s.getOutputStream()

            if (requestLine.startsWith("HEAD")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val start = if (range != null) {
                val v = range.removePrefix("bytes=").substringBefore('-').trim().toIntOrNull()
                if (v != null) {
                    synchronized(_rangeStarts) { _rangeStarts.add(v) }
                    v
                } else 0
            } else 0

            val end = content.size - 1
            // A real server answers ANY Range header (including bytes=0-) with
            // 206; only requests without a Range header get a full 200.
            val isRange = range != null

            if (isRange) {
                out.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${content.size}\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray())
            }

            var pos = start
            while (pos <= end) {
                val n = minOf(chunkBytes, end - pos + 1)
                out.write(content, pos, n)
                out.flush()
                pos += n
                if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
            }
        }
    }
}