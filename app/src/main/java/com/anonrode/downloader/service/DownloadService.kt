package com.anonrode.downloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import com.anonrode.downloader.MainActivity

class DownloadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastUpdateTime = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        acquireWakeLock()
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // START_STICKY restart with a null intent (process killed by the system):
        // don't re-pin a phantom "Downloading…" notification — if the engine is
        // still running it will re-issue a real update, otherwise this service
        // instance has nothing to show and should go away.
        if (intent == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val activeTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Downloading..."
        val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
        val activeCount = intent.getIntExtra(EXTRA_COUNT, 1)

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime >= 500L || progress >= 100 || progress == 0) {
            lastUpdateTime = now
            val notification = buildOngoingNotification(activeTitle, progress, activeCount)
            try {
                ServiceCompat.startForeground(
                    this,
                    ONGOING_NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                )
            } catch (_: Exception) {
                // Prevent ForegroundServiceStartNotAllowedException crash if system restarts service while app is backgrounded
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnonDownloader::DownloadWakeLock").apply {
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            // Channel 1: Silent live progress updates
            val ongoingChannel = NotificationChannel(
                CHANNEL_ONGOING_ID,
                "Active Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent progress updates during active downloads"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // Channel 2: Download Complete chime & vibration
            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE_ID,
                "Download Completed",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notification when a download reaches 100%"
                setShowBadge(true)
                enableVibration(true)
            }

            manager.createNotificationChannel(ongoingChannel)
            manager.createNotificationChannel(completeChannel)
        }
    }

    private fun buildOngoingNotification(title: String, progress: Int, activeCount: Int): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val countText = if (activeCount > 1) " ($activeCount active)" else ""

        return NotificationCompat.Builder(this, CHANNEL_ONGOING_ID)
            .setContentTitle("Anonrode$countText")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ONGOING_ID = "anon_downloads_channel"
        const val CHANNEL_COMPLETE_ID = "anon_completed_channel"
        const val ONGOING_NOTIFICATION_ID = 8801

        const val ACTION_START_OR_UPDATE = "com.anonrode.downloader.START_OR_UPDATE"
        const val ACTION_STOP = "com.anonrode.downloader.STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_COUNT = "extra_count"

        /** The currently running service instance, if any. Used by [stop] to
         *  avoid the background-startService crash on Android 8+. */
        @Volatile
        var instance: DownloadService? = null
            private set

        fun updateProgress(context: Context, title: String, progress: Int, activeCount: Int) {
            try {
                val intent = Intent(context, DownloadService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_PROGRESS, progress)
                    putExtra(EXTRA_COUNT, activeCount)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun notifyCompleted(context: Context, filename: String) {
            try {
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    appIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
                    .setContentTitle("Download Complete")
                    .setContentText(filename)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notifId = (System.currentTimeMillis() % 100000).toInt() + 9000
                manager.notify(notifId, notification)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                // On Android 8+ a background app cannot start a service; use the
                // live instance instead. The NotificationManager fallback ensures
                // the notification is removed even when the service cannot be
                // reached (e.g. after process death before the null-intent guard).
                instance?.stopForeground(STOP_FOREGROUND_REMOVE)
                instance?.stopSelf()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(ONGOING_NOTIFICATION_ID)
            } catch (_: Exception) {
                // Last-resort: send the STOP intent anyway (works pre-O, or when
                // the runtime allows it).
                try {
                    val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_STOP }
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        }
    }
}
