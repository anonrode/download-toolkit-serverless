package com.anonrode.downloader.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.anonrode.downloader.MainActivity
import com.anonrode.downloader.R
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM message receiver (2026-10-19 un-park). Manual-runtime FirebaseOptions —
 * no google-services.json. The manifest entry is exported=false with the
 * com.google.firebase.MESSAGING_EVENT intent filter.
 *
 * Privacy: message payloads are opaque to our logs. Only the message id,
 * type, and sender id are recorded; the device token, Firebase Installation
 * ID, and arbitrary data values are never written to the journal or telemetry.
 *
 * Notification payloads are auto-displayed by the system when the app is in
 * the background; foreground receipt lands here, where we log and (when the
 * POST_NOTIFICATIONS permission allows) raise a local notification on the
 * dedicated channel. Data-only messages still log and can show the same
 * notification for the fcm_test type.
 */
class AnonFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        // Defensive: make sure the manual-runtime default app exists before
        // the first message/token callback. The AnonApp startup hook is the
        // primary path; this is idempotent when it already ran.
        try {
            FcmRegistrar.maybeRegister(this)
        } catch (_: Throwable) {
        }
        ensureChannel()
    }

    /** Token refresh: resubscribe to the test topic. Never log the token. */
    override fun onNewToken(token: String) {
        FcmRegistrar.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"]
            ?: if (message.notification != null) "notification" else "data"
        val id = message.messageId ?: "-"
        val from = message.from ?: "-"
        try {
            DebugLog.engine("FCM message received id=$id type=$type from=$from")
        } catch (_: Throwable) {
        }
        try {
            RemoteLogger.report(
                type = "fcm_message",
                message = "fcm message received",
                context = mapOf(
                    "message_id" to id,
                    "message_type" to type,
                    "from" to from
                )
            )
        } catch (_: Throwable) {
        }

        val show = message.notification != null || type == "fcm_test" || type == "anon_test"
        if (show) {
            val title = message.notification?.title
                ?: message.data["title"]
                ?: "Anonrode"
            val body = message.notification?.body
                ?: message.data["body"]
                ?: "FCM test notification"
            showNotification(title, body)
        }
    }

    override fun onDeletedMessages() {
        try {
            DebugLog.engine("FCM messages deleted (queue overflow)")
        } catch (_: Throwable) {
        }
        try {
            RemoteLogger.report(
                type = "fcm_message",
                message = "fcm messages deleted (queue overflow)"
            )
        } catch (_: Throwable) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Announcements",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "App announcements and service messages"
            setShowBadge(true)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun showNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try { DebugLog.engine("FCM notification skipped: POST_NOTIFICATIONS denied") } catch (_: Throwable) {}
            return
        }
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify((System.currentTimeMillis() and 0x7FFFFFFFL).toInt(), notification)
        } catch (t: Throwable) {
            try { DebugLog.engine("FCM notification failed: ${t.javaClass.simpleName}") } catch (_: Throwable) {}
        }
    }

    companion object {
        const val CHANNEL_ID = "anon_announcements"
    }
}
