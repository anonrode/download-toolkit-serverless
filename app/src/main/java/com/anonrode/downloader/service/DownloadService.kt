package com.anonrode.downloader.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.anonrode.downloader.MainActivity
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.engine.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        observeDownloads()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Anon Downloader", "Download service active", 0, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
    }

    private fun observeDownloads() {
        serviceScope.launch {
            DownloadEngine.instance.tasks.collect { tasks ->
                val active = tasks.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }
                val completed = tasks.filter { it.status == TaskStatus.COMPLETED }
                val failed = tasks.filter { it.status == TaskStatus.FAILED }

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                when {
                    active.isNotEmpty() -> {
                        val first = active.first()
                        val count = active.size
                        val title = if (count == 1) first.episodeTitle else "Downloading $count files"
                        val progress = if (first.totalBytes > 0) ((first.downloadedBytes * 100) / first.totalBytes).toInt() else 0
                        val speedMb = first.speedBytesPerSec / (1024 * 1024)
                        val text = if (speedMb > 0.05) String.format("%.1f MB/s • %d%%", speedMb, progress) else "$progress%"

                        manager.notify(NOTIFICATION_ID, buildNotification(title, text, progress, true))
                    }
                    failed.isNotEmpty() && completed.isEmpty() -> {
                        manager.notify(NOTIFICATION_ID, buildNotification("Download Failed", "Tap to retry in Downloads", 0, false))
                    }
                    completed.isNotEmpty() -> {
                        val count = completed.size
                        val text = if (count == 1) "${completed.first().episodeTitle} saved" else "$count downloads finished"
                        manager.notify(NOTIFICATION_ID, buildNotification("Downloads Complete", text, 100, false))
                    }
                    tasks.isEmpty() -> {
                        manager.cancel(NOTIFICATION_ID)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun buildNotification(title: String, text: String, progress: Int, isIndeterminate: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(isIndeterminate)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        if (isIndeterminate) {
            builder.setProgress(100, progress, progress == 0)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Anon Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground notifications for active downloads"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnonDownloader::ServiceWakeLock").apply {
            acquire(12 * 60 * 60 * 1000L) // 12 hours max
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    companion object {
        private const val CHANNEL_ID = "anon_download_channel"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
