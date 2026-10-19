package com.anonrode.downloader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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
        repository.initPersistence(filesDir, this)
        com.anonrode.downloader.util.DebugLog.init(this)
        engine = DownloadEngine(this, repository, com.anonrode.downloader.util.NetworkObserver(this))
        // Torrent selective-file picker: the engine suspends on this callback
        // while the Compose dialog (HomeScreen) shows the swarm's file list.
        engine.onTorrentFileSelection = { files ->
            com.anonrode.downloader.ui.screens.TorrentFilePicker.pick(files)
        }
        // The activity journal is always on (rotating daily files under
        // filesDir/logs) — it is the black box used to diagnose any misbehavior.
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
        // Fatal-crash + watchdog-timeout sink (Track 6). Stores context only;
        // the endpoint arrives via OTA rules and reporting stays a no-op
        // until it provisions one.
        com.anonrode.downloader.util.RemoteLogger.install(this)
        // Full-log pipeline hooks (1 MB size trigger lives on the journal's
        // append path); the first-open-of-day send runs further below, after
        // DynamicRulesManager provisions the sink origin.
        com.anonrode.downloader.util.LogUploader.install(this)
        // Phase 2 (plan §4.2/Decision 5): first-foreground detection without
        // a new dependency — an activity-start counter, plus the engagement
        // clock stamp. onAppForegrounded() re-arms the inactivity alarm and,
        // when the last rules sync is >6h old, forces the existing sync path
        // so announcements/gates land promptly instead of waiting for the
        // 24h floor.
        registerActivityLifecycleCallbacks(foregroundTracker)
        com.anonrode.downloader.update.EngagementScheduler.noteAppOpen(this)
        appScope.launch {
            try {
                // Non-critical startup initializations: disk I/O, AES decryption,
                // and JSON decoding offloaded to Dispatchers.IO to prevent cold-start ANR.
                com.anonrode.downloader.data.rules.DynamicRulesManager.init(this@AnonApp)
                com.anonrode.downloader.pipeline.HostHealth.init(this@AnonApp)
                com.anonrode.downloader.providers.FeedCache.init(this@AnonApp)

                initYoutubeDlInternal(this@AnonApp)
                maybeSyncRules()
                // Phase 2 activation hooks: also run once here so a
                // cache-restored payload (activation happened inside
                // DynamicRulesManager.init) still evaluates on cold start;
                // idempotent when a fresh sync just ran them.
                afterRulesActivated(this@AnonApp)
                if (ytdlpReady) maybeUpdateYoutubeDL()
                // The "logs on the server" half (any connection, first open of
                // the day + the 1 MB / crash-first triggers wired above; same
                // kill-switch as the fatal reports; silent when anything's off).
                com.anonrode.downloader.util.LogUploader.onAppOpen(this@AnonApp)
            } catch (t: Throwable) {
                Log.e("AnonApp", "Background initialization error", t)
            }
        }
    }

    /** Auto-sync (24h floor); [force] = the resume-refresh path (>6h,
     *  Decision 5) bypassing the floor. internal so the companion's
     *  foreground hook can trigger it via the instance. */
    internal suspend fun maybeSyncRules(force: Boolean = false) {
        try {
            val prefs = getSharedPreferences(PREFS_SERVERLESS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_RULES_SYNC, 0L)
            val now = System.currentTimeMillis()
            if (!force && now - last < UPDATE_INTERVAL_MS) return
            lastSyncAttemptMs = now
            val (ok, ver) = com.anonrode.downloader.data.rules.DynamicRulesManager.syncFromGitHub(this)
            if (ok) {
                prefs.edit().putLong(KEY_LAST_RULES_SYNC, now).apply()
                Log.i("AnonApp", "Auto-synced scraper rules: $ver")
                afterRulesActivated(this)
            }
        } catch (t: Throwable) {
            Log.w("AnonApp", "Auto-sync scraper rules failed, keeping cached/bundled rules", t)
        }
    }

    /**
     * Phase 2 post-activation hooks (plan §4.3). Every call is individually
     * guarded and idempotent; the last two remain INERT until their payload
     * sections exist (FcmRegistrar: `fcm`; IntegrityAttestor: hard-false
     * const) — wiring them here now is what makes the later OTA flip real.
     */
    private fun afterRulesActivated(context: Context) {
        try { com.anonrode.downloader.update.AnnouncementManager.refresh(context) } catch (_: Throwable) {}
        try { com.anonrode.downloader.update.UpdateManager.evaluate(context) } catch (_: Throwable) {}
        try { com.anonrode.downloader.update.EngagementScheduler.arm(context) } catch (_: Throwable) {}
        try { com.anonrode.downloader.util.IntegrityCheck.evaluate(context) } catch (_: Throwable) {}
        try { com.anonrode.downloader.update.FcmRegistrar.maybeRegister(context) } catch (_: Throwable) {}
        try { com.anonrode.downloader.update.IntegrityAttestor.maybeAttest(context) } catch (_: Throwable) {}
    }

    /** Activity-start counter → session-first-foreground callback (see
     *  onAppForegrounded in the companion). */
    private val foregroundTracker = object : Application.ActivityLifecycleCallbacks {
        private var startedCount = 0

        override fun onActivityStarted(activity: Activity) {
            if (startedCount == 0) onAppForegrounded()
            startedCount++
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedCount > 0) startedCount--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private suspend fun maybeUpdateYoutubeDL() {
        try {
            val prefs = getSharedPreferences(PREFS_SERVERLESS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_YTDLP_UPDATE, 0L)
            val now = System.currentTimeMillis()
            if (now - last < UPDATE_INTERVAL_MS) return
            // YtDlpCore owns the channel choice (default MASTER/nightly —
            // YouTube's bot-check fixes land there first) and the one-shot
            // fallback onto the other channel.
            val outcome = com.anonrode.downloader.util.YtDlpCore.updateNow(this)
            if (outcome.ok) {
                prefs.edit().putLong(KEY_LAST_YTDLP_UPDATE, now).apply()
                Log.i("AnonApp", "yt-dlp auto-update: ${outcome.message} (${outcome.channelUsed})")
            } else {
                Log.w("AnonApp", "yt-dlp auto-update failed: ${outcome.message}")
            }
        } catch (t: Throwable) {
            Log.w("AnonApp", "yt-dlp update failed, keeping bundled binary", t)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        val client = HttpClient.shared.newBuilder()
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", HttpClient.DEFAULT_UA)
                    .build()
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(120)
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

        /** Written by AnonApp after each successful sync; UpdateManager reads
         *  it (with the rules cache mtime) as its payload-staleness signal —
         *  do not rename without updating update/UpdateManager.kt. */
        internal const val KEY_LAST_RULES_SYNC = "last_rules_sync"
        private const val KEY_LAST_YTDLP_UPDATE = "last_ytdlp_update"
        private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Prefs home of the sync/update bookkeeping (also read by
         *  UpdateManager as its payload-staleness signal — do not rename
         *  without updating update/UpdateManager.kt). */
        internal const val PREFS_SERVERLESS = "anon_serverless_prefs"

        /** Decision 5: resume-refresh fires when the last successful rules
         *  sync is older than this, even though the auto floor is 24h. */
        private const val RESUME_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** Cold-start guard: the initial auto-sync and the first foreground
         *  land nearly together — do not double-fire the sync path. */
        private const val RESUME_SYNC_DEBOUNCE_MS = 10L * 60 * 1000

        /** Last time a rules sync ATTEMPT started (any path), for the
         *  foreground debounce above. */
        @Volatile
        private var lastSyncAttemptMs = 0L

        /**
         * First foreground of a session (Decision 5 + engagement clock).
         * Stamps the last-open time / re-arms the inactivity alarm, and when
         * the last successful rules sync is >6h old triggers the existing
         * sync path with force=true. Cheap, guarded, never throws.
         */
        fun onAppForegrounded() {
            if (!::instance.isInitialized) return
            val app = instance
            try {
                com.anonrode.downloader.update.EngagementScheduler.noteAppOpen(app)
            } catch (_: Throwable) {
            }
            try {
                val now = System.currentTimeMillis()
                if (now - lastSyncAttemptMs < RESUME_SYNC_DEBOUNCE_MS) return
                val lastSync = app.getSharedPreferences(PREFS_SERVERLESS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_RULES_SYNC, 0L)
                if (now - lastSync < RESUME_SYNC_INTERVAL_MS) return
                appScope.launch { app.maybeSyncRules(force = true) }
            } catch (_: Throwable) {
            }
        }

        @Volatile
        var ytdlpReady: Boolean = false
            private set

        internal suspend fun initYoutubeDlInternal(context: Context) {
            if (ytdlpReady) return
            initMutex.withLock {
                if (ytdlpReady) return@withLock
                try {
                    YoutubeDL.getInstance().init(context)
                    FFmpeg.getInstance().init(context)
                    ytdlpReady = true
                    Log.i("AnonApp", "youtubedl-android and ffmpeg initialized successfully")
                } catch (t: Throwable) {
                    Log.e("AnonApp", "youtubedl-android init failed", t)
                }
            }
        }

        suspend fun ensureReady(): Boolean {
            if (ytdlpReady) return true
            if (::instance.isInitialized) {
                initYoutubeDlInternal(instance)
            } else {
                initMutex.withLock { /* wait for any in-flight init */ }
            }
            return ytdlpReady
        }
    }
}
