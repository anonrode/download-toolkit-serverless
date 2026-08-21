package com.anonrode.downloader

import android.app.Application
import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.engine.DownloadEngine
import com.anonrode.downloader.engine.DownloadRepository
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnonApp : Application(), ImageLoaderFactory {

    lateinit var engine: DownloadEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository.initPersistence(filesDir)
        com.anonrode.downloader.util.DebugLog.init(this)
        // Load cached scraper rules (domain fixes / dynamic providers) so a
        // manual sync in Settings survives app restarts.
        com.anonrode.downloader.data.rules.DynamicRulesManager.init(this)
        engine = DownloadEngine(this, repository, com.anonrode.downloader.util.NetworkObserver(this))
        // Torrent selective-file picker: the engine suspends on this callback
        // while the Compose dialog (HomeScreen) shows the swarm's file list.
        engine.onTorrentFileSelection = { files ->
            com.anonrode.downloader.ui.screens.TorrentFilePicker.pick(files)
        }
        com.anonrode.downloader.util.DebugLog.setEnabled(
            getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
                .getBoolean("pref_debug_logging", false)
        )
        // First launch: detect device RAM and set the torrent peer default once.
        // The user can override it in Settings afterwards.
        try {
            val prefs = getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            if (!prefs.contains("pref_torrent_peers")) {
                val tier = com.anonrode.downloader.data.settings.AppSettings.detectRamTier(this)
                prefs.edit().putInt("pref_torrent_peers", tier).apply()
            }
        } catch (_: Throwable) {}
        com.anonrode.downloader.util.CrashHandler.install(this)
        appScope.launch {
            try {
                initMutex.withLock {
                    if (ytdlpReady) return@withLock
                    try {
                        YoutubeDL.getInstance().init(this@AnonApp)
                        FFmpeg.getInstance().init(this@AnonApp)
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
        lateinit var instance: AnonApp
            private set

        val repository: DownloadRepository by lazy { DownloadRepository() }

        val downloadEngine: DownloadEngine
            get() = instance.engine

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
