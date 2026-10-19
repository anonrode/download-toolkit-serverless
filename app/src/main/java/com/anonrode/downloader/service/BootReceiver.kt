package com.anonrode.downloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anonrode.downloader.update.EngagementScheduler

/**
 * Reboot hook for the re-engagement scheduler (phase 2): the one-shot
 * inactivity alarm does not survive a reboot, so this receiver re-arms it
 * from the persisted prefs + the active payload policy.
 *
 * Declaration note (AndroidManifest.xml): BOOT_COMPLETED is an implicit
 * broadcast, so — unlike RetryReceiver's explicit PendingIntent — this
 * receiver MUST be exported="true" with the intent-filter to receive it.
 * It is a no-op while the engagement section is absent/disabled
 * (EngagementScheduler.arm returns before touching the AlarmManager).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            EngagementScheduler.onBoot(context.applicationContext)
        } catch (_: Exception) {
        }
    }
}
