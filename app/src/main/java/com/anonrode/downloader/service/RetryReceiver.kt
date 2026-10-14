package com.anonrode.downloader.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anonrode.downloader.AnonApp

/**
 * "Retry" action on a Download Failed notification. Re-queues the task the
 * same way the in-app retry button does, so a failure can be retried without
 * opening the app.
 */
class RetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notifId = 18000 + (taskId.hashCode().toLong() and 0x7FFFFFFFL).toInt() % 8000
            manager?.cancel(notifId)

            (context.applicationContext as? AnonApp)?.engine?.retry(taskId)
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_RETRY = "com.anonrode.downloader.RETRY_DOWNLOAD"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
