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
import com.anonrode.downloader.providers.PosterNetwork
import com.anonrode.downloader.providers.PosterStore
import com.anonrode.downloader.util.NetworkObserver
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

class AnonApp : Application(), ImageLoaderFactory {

    lateinit var engine: DownloadEngine
        private set

    /** One live connectivity source for the engine, the poster cache and the
     *  image prefetcher (no second ConnectivityManager callback per feature). */
    lateinit var networkObserver: NetworkObserver
        private set

    /** Completed after FeedCache + PosterStore have read their JSON. The
     *  ViewModel awaits this before hydrating, killing the cold-start race
     *  where the first composition read an empty cache and never re-read it. */
    private val cachesReady = CompletableDeferred<Unit>()

    /** Completed after scraper rules / host health are live, so the first
     *  network crawl cannot race rule initialization. */
    private val initReady = CompletableDeferred<Unit>()

    fun awaitCachesReady(): Deferred<Unit> = cachesReady

    fun awaitInitReady(): Deferred<Unit> = initReady

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository.initPersistence(filesDir, this)
        com.anonrode.downloader.util.DebugLog.init(this)
        com.anonrode.downloader.data.net.CloudflareSolver.init(this)
        networkObserver = NetworkObserver(this)
        PosterNetwork.isOnline = networkObserver.status.value.isConnected
        engine = DownloadEngine(this, repository, networkObserver)
        // Watch history (owner directive): app-wide init so the player can
        // record/read even when the Downloads tab was never opened. The
        // bootstrap seeds existing completed tasks that already carry a saved
        // playback position; historical records are never rewritten.
        com.anonrode.downloader.data.WatchHistoryStore.init(this)
        com.anonrode.downloader.data.WatchHistoryStore.syncWithTasks(
            repository.tasks.value,
            positionFor = { path ->
                com.anonrode.downloader.ui.components.PlaybackPositions.get(this, path)
                    ?.let { it.positionMs to it.durationMs }
            }
        )
        // Keep the process-wide poster flag honest for the whole session.
        appScope.launch {
            networkObserver.status.collect { status ->
                PosterNetwork.isOnline = status.isConnected
                if (status.isConnected) {
                    // RC-2: negotiate DNS + a pooled connection for the hot
                    // origins while the user is still on the first screen, so
                    // the first search does not pay the HTTP/2 preface stall.
                    // Fire-and-forget: idempotent, silent, non-blocking.
                    HttpClient.warmHotOrigins()
                }
            }
        }
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
        appScope.launch {
            // Cache reads gate the ViewModel's initial hydrate. Keep them in
            // their own block so a rules/AES failure still releases the UI.
            try {
                com.anonrode.downloader.providers.FeedCache.init(this@AnonApp)
                PosterStore.init(this@AnonApp)
            } catch (t: Throwable) {
                Log.e("AnonApp", "Feed/poster cache init error", t)
            } finally {
                cachesReady.complete(Unit)
            }
            try {
                // Rules and host health must be live before the first crawl.
                com.anonrode.downloader.data.rules.DynamicRulesManager.init(this@AnonApp)
                com.anonrode.downloader.pipeline.HostHealth.init(this@AnonApp)
                // FCM registration is inert until the OTA payload enables it;
                // this onCreate call covers the cold start before any sync.
                try {
                    com.anonrode.downloader.update.FcmRegistrar.maybeRegister(this@AnonApp)
                } catch (_: Throwable) {
                }
            } catch (t: Throwable) {
                Log.e("AnonApp", "Background initialization error", t)
            } finally {
                initReady.complete(Unit)
            }
            try {
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

    /** Single-flight guard (QA-V3 F4): the cold-start auto sync and the
     *  first-foreground forced sync used to be able to overlap and
     *  double-fire the 4-source chain + hooks. */
    @Volatile private var syncInFlight = false

    /** Auto-sync (24h floor); [force] = the resume-refresh path (>6h,
     *  Decision 5) bypassing the floor. internal so the companion's
     *  foreground hook can trigger it via the instance. */
    internal suspend fun maybeSyncRules(force: Boolean = false) {
        if (syncInFlight) return
        syncInFlight = true
        try {
            val prefs = getSharedPreferences(PREFS_SERVERLESS, Context.MODE_PRIVATE)
            // Floor considers BOTH the last success and the last attempt, so a
            // failed attempt still spaces out retries (QA-V3 F4).
            val last = maxOf(
                prefs.getLong(KEY_LAST_RULES_SYNC, 0L),
                lastSyncAttemptMs
            )
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
        } finally {
            syncInFlight = false
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
        // QA-V3 F3 / OTA-5: Only stamp last-open on genuine foreground activations;
        // headless background process starts (boot/alarms/FCM) arm the scheduler without resetting the clock.
        if (hasEnteredForeground) {
            try { com.anonrode.downloader.update.EngagementScheduler.noteAppOpen(context) } catch (_: Throwable) {}
        }
        try { com.anonrode.downloader.util.IntegrityCheck.evaluate(context) } catch (_: Throwable) {}
        try { com.anonrode.downloader.update.FcmRegistrar.maybeRegister(context) } catch (_: Throwable) {}
        try { com.anonrode.downloader.update.IntegrityAttestor.maybeAttest(context) } catch (_: Throwable) {}
        // QA-V3 F7: initialize the configured vendor ad bridge on activation
        // (inert while ads are disabled; SDK init + credentials = the OTA
        // activation half — vendor ad VIEWS remain a follow-up).
        try { com.anonrode.downloader.ads.Ads.activeBridge(context) } catch (_: Throwable) {}
    }

    @Volatile private var hasEnteredForeground = false

    /** Manual-sync path (Settings "Sync Now", QA-V3 F5): run the same
     *  post-activation hooks the auto-sync runs, so a manually pulled payload
     *  renders/arms immediately instead of waiting for the next restart. */
    fun runPostActivationHooks() = afterRulesActivated(this)

    /** Activity-start counter → session-first-foreground callback (see
     *  onAppForegrounded in the companion). */
    private val foregroundTracker = object : Application.ActivityLifecycleCallbacks {
        private var startedCount = 0

        override fun onActivityStarted(activity: Activity) {
            hasEnteredForeground = true
            com.anonrode.downloader.data.net.CloudflareSolver.isAppInForeground = true
            if (startedCount == 0) onAppForegrounded()
            startedCount++
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedCount > 0) startedCount--
            if (startedCount == 0) {
                com.anonrode.downloader.data.net.CloudflareSolver.isAppInForeground = false
            }
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

    /**
     * The ONLY production image client. PosterImages owns the hard limits:
     * six concurrent requests, three per host, short timeouts. Feed/resolver
     * traffic keeps its own dispatcher (HttpClient.shared), so a poster
     * stampede can never starve a download resolution.
     */
    internal fun buildImageHttpClient(): okhttp3.OkHttpClient =
        PosterImages.configure(
            HttpClient.shared.newBuilder()
                // Coil's networkCachePolicy(DISABLED) maps to OkHttp's
                // only-if-cached, which is a no-op without a Cache. This cache
                // makes the offline/cache-only underlay truly network-free on
                // a disk miss instead of waiting out the call timeout.
                .cache(okhttp3.Cache(File(noBackupFilesDir, "image_http_cache"), 64L * 1024 * 1024))
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", HttpClient.DEFAULT_UA)
                        .build()
                    chain.proceed(request)
                }
                .build()
        )

    /**
     * 250 MB poster cache in noBackupFilesDir (app-private, not OS-evictable,
     * and excluded from cloud backup so a 250 MB art cache never blows the
     * 25 MB backup quota). The old cacheDir location is migrated once when
     * possible so an upgrade keeps its offline art.
     */
    private fun imageCacheDir(): File {
        val target = File(noBackupFilesDir, "image_cache")
        if (!target.exists()) {
            val legacy = File(cacheDir, "image_cache")
            if (legacy.exists()) {
                try {
                    legacy.renameTo(target)
                } catch (_: Throwable) {
                }
            }
        }
        if (!target.exists()) target.mkdirs()
        return target
    }

    override fun newImageLoader(): ImageLoader {
        val client = buildImageHttpClient()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(imageCacheDir())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            // CDNs commonly send no-store/private on poster bytes. The disk
            // cache exists precisely to survive that, so ignore the headers.
            .respectCacheHeaders(false)
            .crossfade(120)
            .build()
    }

    companion object {
        lateinit var instance: AnonApp
            private set

        fun instanceOrNull(): AnonApp? = if (::instance.isInitialized) instance else null

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

/**
 * Hard image-plane policy, extracted so JVM/Robolectric tests can pin it
 * without booting the Application. Six total in flight, three per host:
 * enough to fill a 3-column grid, low enough that poster traffic cannot
 * monopolize sockets, battery or a slow source host.
 */
object PosterImages {
    const val MAX_REQUESTS = 6
    const val MAX_REQUESTS_PER_HOST = 3
    const val CONNECT_TIMEOUT_SECONDS = 8L
    const val READ_TIMEOUT_SECONDS = 20L
    const val CALL_TIMEOUT_SECONDS = 25L

    fun newDispatcher(): okhttp3.Dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = MAX_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }

    fun configure(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
        base.newBuilder()
            .dispatcher(newDispatcher())
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
}
