package com.anonrode.downloader

import android.app.Application
import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.anonrode.downloader.data.net.HttpClient
import com.yausername.ffmpeg.FFmpeg
import com.yausername.aria2c.Aria2c
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnonApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
                initMutex.withLock {
                    if (ytdlpReady) return@withLock
                    try {
                        YoutubeDL.getInstance().init(this@AnonApp)
                        FFmpeg.getInstance().init(this@AnonApp)
                        Aria2c.getInstance().init(this@AnonApp)
                        ytdlpReady = true
                    } catch (t: Throwable) {
                        Log.e("AnonApp", "youtubedl-android init failed", t)
                    }
                }
                if (ytdlpReady) maybeUpdateYoutubeDL()
            } catch (t: Throwable) {
                Log.e("AnonApp", "Background initialization error", t)
            }
        }
    }

    private suspend fun maybeUpdateYoutubeDL() {
        try {
            val prefs = getSharedPreferences("anon_serverless_prefs", Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_YTDLP_UPDATE, 0L)
            val now = System.currentTimeMillis()
            if (now - last < UPDATE_INTERVAL_MS) return
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(KEY_LAST_YTDLP_UPDATE, now).apply()
            Log.i("AnonApp", "yt-dlp update: $status")
        } catch (t: Throwable) {
            Log.w("AnonApp", "yt-dlp update failed, keeping bundled binary", t)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val client = HttpClient.shared.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .build()
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }

    companion object {
        private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val initMutex = Mutex()

        private const val KEY_LAST_YTDLP_UPDATE = "last_ytdlp_update"
        private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

        @Volatile
        var ytdlpReady: Boolean = false
            private set

        suspend fun ensureReady(): Boolean {
            if (ytdlpReady) return true
            initMutex.withLock { return ytdlpReady }
        }
    }
}
