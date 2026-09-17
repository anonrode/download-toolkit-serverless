package com.anonrode.downloader.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TurboRestartTest {
    @Test
    fun ignoredRangeWithUnknownTotalReplacesOldTail() = runBlocking {
        val dir = createTempDirectory("turbo-restart").toFile()
        try {
            val dest = File(dir, "video.mp4")
            File(dest.path + ".part").writeText("OLD-CONTENT-TAIL")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .body("NEW".toResponseBody())
                    .build()
            }.build()
            val result = TurboDownloader.download(
                url = "https://media.example/video.mp4",
                dest = dest,
                configuredSockets = 1,
                client = client
            )
            assertTrue("Expected success, got $result", result is TurboDownloader.TurboResult.Success)
            assertEquals("NEW", dest.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
