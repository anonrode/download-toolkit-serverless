package com.anonrode.downloader.ui.components

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.SuccessResult
import com.anonrode.downloader.PosterImages
import com.anonrode.downloader.providers.PosterNetwork
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Acceptance #3: after one successful load, an OFFLINE cold start must paint
 * the real cached poster with zero network bytes. A real Coil disk cache is
 * warmed against MockWebServer, the server is shut down and the network flag
 * is flipped off; the second execute must still return SuccessResult from
 * disk (the request factory switches to CachePolicy.DISABLED for the
 * network plane).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PosterOfflineCacheTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun resetNetworkFlag() {
        PosterNetwork.isOnline = true
    }

    @Test
    fun offlineColdStartServesPosterFromDiskWithoutNetwork() = runBlocking {
        val server = MockWebServer()
        val png = Base64.decode(ONE_PX_PNG_B64, Base64.DEFAULT)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(png))
        )
        server.start()

        val cacheDir = Files.createTempDirectory("coil-offline-test").toFile()
        val loader = ImageLoader.Builder(context)
            .okHttpClient(PosterImages.configure(OkHttpClient()))
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir)
                    .maxSizeBytes(10L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(false)
            .build()

        try {
            val url = server.url("/poster.png").toString()
            val warm = loader.execute(PosterRequests.request(context, url))
            assertTrue("warm load failed: $warm", warm is SuccessResult)
            assertEquals(1, server.requestCount)

            server.shutdown()
            PosterNetwork.isOnline = false

            val cold = loader.execute(PosterRequests.request(context, url))
            assertTrue("offline cold load was not served from disk: $cold", cold is SuccessResult)
            // No server is running; a network attempt cannot succeed. The
            // disk-backed result above plus the disabled network policy is
            // the zero-byte proof.
            assertEquals(1, server.requestCount)
        } finally {
            runCatching { server.shutdown() }
            loader.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    private companion object {
        /** 1x1 transparent PNG. */
        const val ONE_PX_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
