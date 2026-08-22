package com.anonrode.downloader.data.settings

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences

/**
 * All user-configurable app settings, loaded once at startup and persisted
 * atomically. Every default equals the previous hardcoded behavior, so the
 * app behaves identically until the user opts into a change.
 */
data class AppSettings(
    // --- Engine ---
    val maxConcurrentDownloads: Int = 3,
    val parallelSockets: Int = 16,
    val defaultQuality: String = "720p",
    val stallTimeoutSec: Int = 60,
    val magnetMaxAttempts: Int = 3,
    val ytdlpMaxAttempts: Int = 3,
    val hlsFragmentConcurrency: Int = 8,
    val globalSpeedLimitKbs: Int = 0,       // 0 = unlimited
    val torrentPeers: Int = -1,             // -1 = auto (RAM tier)
    val torrentPrivacyMode: Boolean = false, // qBittorrent anonymous-mode lessons
    // --- Network ---
    val wifiOnlyTorrents: Boolean = false,
    val wifiOnlyAll: Boolean = false,
    // --- Behavior ---
    val autoOrganizeByShow: Boolean = true,
    val instantSocialDownload: Boolean = false,
    val showPostersInResults: Boolean = true,
    val storageGuardGb: Double = 1.0,
    val clipboardDetect: Boolean = true,
    val completionNotifications: Boolean = true,
    val debugLogging: Boolean = false
) {
    companion object {
        const val PREFS_NAME = "downloader_settings"
        const val PEERS_AUTO = -1

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AppSettings(
                maxConcurrentDownloads = prefs.getInt("pref_max_downloads", 3),
                parallelSockets = prefs.getInt("pref_parallel_sockets", 16),
                defaultQuality = prefs.getString("pref_default_quality", "720p") ?: "720p",
                stallTimeoutSec = prefs.getInt("pref_stall_timeout", 60),
                magnetMaxAttempts = prefs.getInt("pref_magnet_retries", 3),
                ytdlpMaxAttempts = prefs.getInt("pref_ytdlp_retries", 3),
                hlsFragmentConcurrency = prefs.getInt("pref_hls_fragments", 16),
                globalSpeedLimitKbs = prefs.getInt("pref_speed_limit_kbs", 0),
                torrentPeers = prefs.getInt("pref_torrent_peers", PEERS_AUTO),
                torrentPrivacyMode = prefs.getBoolean("pref_torrent_privacy_mode", false),
                wifiOnlyTorrents = prefs.getBoolean("pref_torrents_wifi_only", false),
                wifiOnlyAll = prefs.getBoolean("pref_wifi_only_all", false),
                autoOrganizeByShow = prefs.getBoolean("pref_auto_organize", true),
                instantSocialDownload = prefs.getBoolean("pref_instant_social", false),
                showPostersInResults = prefs.getBoolean("pref_show_posters", true),
                storageGuardGb = prefs.getFloat("pref_storage_guard", 1.0f).toDouble(),
                clipboardDetect = prefs.getBoolean("pref_clipboard_detect", true),
                completionNotifications = prefs.getBoolean("pref_completion_notifications", true),
                debugLogging = prefs.getBoolean("pref_debug_logging", false)
            )
        }

        fun save(context: Context, s: AppSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("pref_max_downloads", s.maxConcurrentDownloads)
                .putInt("pref_parallel_sockets", s.parallelSockets)
                .putString("pref_default_quality", s.defaultQuality)
                .putInt("pref_stall_timeout", s.stallTimeoutSec)
                .putInt("pref_magnet_retries", s.magnetMaxAttempts)
                .putInt("pref_ytdlp_retries", s.ytdlpMaxAttempts)
                .putInt("pref_hls_fragments", s.hlsFragmentConcurrency)
                .putInt("pref_speed_limit_kbs", s.globalSpeedLimitKbs)
                .putInt("pref_torrent_peers", s.torrentPeers)
                .putBoolean("pref_torrent_privacy_mode", s.torrentPrivacyMode)
                .putBoolean("pref_torrents_wifi_only", s.wifiOnlyTorrents)
                .putBoolean("pref_wifi_only_all", s.wifiOnlyAll)
                .putBoolean("pref_auto_organize", s.autoOrganizeByShow)
                .putBoolean("pref_instant_social", s.instantSocialDownload)
                .putBoolean("pref_show_posters", s.showPostersInResults)
                .putFloat("pref_storage_guard", s.storageGuardGb.toFloat())
                .putBoolean("pref_clipboard_detect", s.clipboardDetect)
                .putBoolean("pref_completion_notifications", s.completionNotifications)
                .putBoolean("pref_debug_logging", s.debugLogging)
                .apply()
        }

        /**
         * RAM-tier default for --bt-max-peers, detected at first launch.
         * Low-end devices get modest peer slots; flagships fan out hard.
         */
        fun detectRamTier(context: Context): Int {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val gb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
                when {
                    gb < 3 -> 64
                    gb < 6 -> 128
                    gb < 12 -> 256
                    else -> 500
                }
            } catch (_: Exception) {
                128
            }
        }
    }
}
