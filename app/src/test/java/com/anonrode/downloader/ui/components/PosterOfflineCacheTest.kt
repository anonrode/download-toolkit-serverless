package com.anonrode.downloader.ui.components

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.SuccessResult
import com.anonrode.downloader.PosterImages
import com.anonrode.downloader.providers.PosterNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PosterOfflineCacheTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // Interrupts on the test thread; JUnit's timeout would run the body off
    // the main thread and break Robolectric's paused-looper control.
    @get:Rule
    val timeout = TimeoutRule.seconds(90)

    @Before
    fun installMainDispatcher() {
        // Coil's execute() always wraps work in Dispatchers.Main.immediate.
        // Robolectric's main looper is paused and runBlocking blocks the test
        // thread, so a posted continuation would never be pumped. Unconfined
        // runs that hop inline instead.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun resetMainDispatcherAndNetworkFlag() {
        Dispatchers.resetMain()
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

        try {
            val url = server.url("/poster.png").toString()

            // Warm a real Coil disk cache, then drop this loader entirely: the
            // cold read below must come from disk, not from a warm memory
            // cache that a real process restart would not have.
            val warmLoader = buildLoader(cacheDir)
            try {
                val warm = warmLoader.execute(PosterRequests.request(context, url))
                assertTrue("warm load failed: $warm", warm is SuccessResult)
                assertEquals(1, server.requestCount)
            } finally {
                warmLoader.shutdown()
            }

            server.shutdown()
            PosterNetwork.isOnline = false

            val coldLoader = buildLoader(cacheDir)
            try {
                val cold = coldLoader.execute(PosterRequests.request(context, url))
                assertTrue("offline cold load was not served from disk: $cold", cold is SuccessResult)
                // Fresh loader (empty memory cache), server down, network
                // policy disabled: Success can only be the disk entry.
                assertEquals(1, server.requestCount)
            } finally {
                coldLoader.shutdown()
            }
        } finally {
            runCatching { server.shutdown() }
            cacheDir.deleteRecursively()
        }
    }

    private fun buildLoader(cacheDir: File): ImageLoader = ImageLoader.Builder(context)
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

    private companion object {
        /** 1x1 transparent PNG. */
        const val ONE_PX_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
