package com.anonrode.downloader.update

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.anonrode.downloader.MainActivity
import com.anonrode.downloader.R
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.DebugLog

/**
 * Re-engagement scheduler (workstream B, phase 2; plan §4.1 `engagement`
 * + §4.2 EngagementScheduler).
 *
 * Mechanics: [noteAppOpen] stamps the last-open time and (re)arms ONE
 * inexact one-shot alarm for `lastOpen + inactiveDays`; [onAlarm] re-checks
 * the payload's window + cooldown and either posts the LOW-importance
 * `engagement` notification or re-arms. [onBoot] re-arms after reboot (the
 * alarm does not survive it) — see service/BootReceiver.kt. `setInexact`-
 * free by design: one alarm at a time, re-armed from every decision point,
 * never a repeating schedule that drifts.
 *
 * Gate discipline: the WHOLE surface (channel included) is inert while the
 * payload's engagement section is absent/disabled — arm() returns before
 * touching the alarm manager or the notification manager. Copy comes
 * verbatim from the signed payload; no local texts. Dedupe: no second
 * notification until cooldownDays have passed since the last one.
 * Never throws.
 */
object EngagementScheduler {

    private const val PREFS = "downloader_settings"
    private const val PREF_LAST_OPEN = "engagement_last_open_ms"
    private const val PREF_LAST_SENT = "engagement_last_sent_ms"
    private const val PREF_WELCOME_BACK = "engagement_welcome_back_pending"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val ARM_REQUEST_CODE = 77031
    private const val NOTIFICATION_ID = 8810
    const val CHANNEL_ID = "engagement"

    /** App was opened (AnonApp.onCreate + every foreground). Stamps the
     *  inactivity clock and re-arms when the feature is enabled. */
    fun noteAppOpen(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastOpen = prefs.getLong(PREF_LAST_OPEN, 0L)
            // Welcome-back pending (phase-2 UI contract): a user returning
            // after a >= inactiveDays absence gets the in-app card once — the
            // in-app twin of the notification, same payload copy. The Home
            // card clears the flag on dismiss (HomeScreen.WelcomeBackCard).
            val cfg = DynamicRulesManager.getEngagementConfig()
            if (cfg != null && cfg.enabled && cfg.welcomeBack && lastOpen > 0L &&
                now - lastOpen >= cfg.inactiveDays * DAY_MS
            ) {
                prefs.edit().putBoolean(PREF_WELCOME_BACK, true).apply()
            }
            prefs.edit().putLong(PREF_LAST_OPEN, now).apply()
            arm(context)
        } catch (t: Throwable) {
            DebugLog.engine("EngagementScheduler.noteAppOpen failed: ${t.javaClass.simpleName}")
        }
    }

    /** (Re)arm the one-shot alarm from current prefs + payload policy.
     *  No-op while disabled; creates the LOW channel on first entitled arm. */
    fun arm(context: Context) {
        try {
            val cfg = DynamicRulesManager.getEngagementConfig() ?: return
            if (!cfg.enabled) return
            ensureChannel(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastOpen = prefs.getLong(PREF_LAST_OPEN, 0L).takeIf { it > 0L } ?: run {
                prefs.edit().putLong(PREF_LAST_OPEN, now).apply()
                now
            }
            val lastSent = prefs.getLong(PREF_LAST_SENT, 0L)
            // Fire when BOTH windows have elapsed: idle for inactiveDays and
            // outside the cooldown since the last notification.
            var triggerAt = lastOpen + cfg.inactiveDays * DAY_MS
            if (lastSent > 0L) triggerAt = maxOf(triggerAt, lastSent + cfg.cooldownDays * DAY_MS)
            triggerAt = maxOf(triggerAt, now + 60_000L) // due-but-dropped → prompt, not instant-loop
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.set(AlarmManager.RTC, triggerAt, alarmPendingIntent(context))
        } catch (t: Throwable) {
            DebugLog.engine("EngagementScheduler.arm failed: ${t.javaClass.simpleName}")
        }
    }

    /** Reboot re-arm hook (alarms die at reboot). */
    fun onBoot(context: Context) {
        arm(context)
    }

    /** Alarm fired: re-verify, notify, re-arm. Internal — receiver only. */
    internal fun onAlarm(context: Context) {
        try {
            val cfg = DynamicRulesManager.getEngagementConfig() ?: return
            if (!cfg.enabled) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastOpen = prefs.getLong(PREF_LAST_OPEN, now).takeIf { it > 0L } ?: now
            if (now - lastOpen < cfg.inactiveDays * DAY_MS) {
                arm(context) // user came back since the arm — recompute
                return
            }
            val lastSent = prefs.getLong(PREF_LAST_SENT, 0L)
            if (lastSent > 0L && now - lastSent < cfg.cooldownDays * DAY_MS) {
                arm(context) // inside cooldown — next due is cooldown-end
                return
            }
            postNotification(context, cfg.title, cfg.body)
            prefs.edit().putLong(PREF_LAST_SENT, now).apply()
            arm(context) // next notification no earlier than cooldown-end
        } catch (t: Throwable) {
            DebugLog.engine("EngagementScheduler.onAlarm failed: ${t.javaClass.simpleName}")
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Re-engagement",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "A gentle nudge when the app has been idle for a while"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun postNotification(context: Context, title: String, body: String) {
        if (title.isBlank() && body.isBlank()) return
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Anonrode" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, EngagementAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Alarm delivery for [EngagementScheduler] (manifest-declared,
 * exported="false" — fired only by our own explicit PendingIntent).
 */
class EngagementAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            EngagementScheduler.onAlarm(context.applicationContext)
        } catch (_: Exception) {
        }
    }
}
