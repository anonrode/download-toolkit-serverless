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
import com.anonrode.downloader.data.rules.EngagementConfig
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger

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
    private const val PREF_ARM_PENDING = "engagement_arm_pending"
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
            val cfg = DynamicRulesManager.getEngagementConfig()
            if (cfg == null) {
                // QA-V3 F3: the cold-start call runs BEFORE the async rules
                // init, so the payload is not loaded yet — and stamping
                // last-open now would destroy the very absence this method
                // measures. Leave the clock for the post-activation call
                // (AnonApp.afterRulesActivated -> noteAppOpen).
                return
            }
            // Welcome-back pending (phase-2 UI contract): a user returning
            // after a >= inactiveDays absence gets the in-app card once — the
            // in-app twin of the notification, same payload copy. The Home
            // card clears the flag on dismiss (HomeScreen.WelcomeBackCard).
            if (cfg.enabled && cfg.welcomeBack && lastOpen > 0L &&
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
            // QA-V3 F6: a boot-time arm attempt that found no payload leaves
            // this flag set; the first arm with a loaded payload clears it.
            prefs.edit().putBoolean(PREF_ARM_PENDING, false).apply()
        } catch (t: Throwable) {
            DebugLog.engine("EngagementScheduler.arm failed: ${t.javaClass.simpleName}")
        }
    }

    /** Reboot re-arm hook (alarms die at reboot). Best-effort with an honest
     *  bound (QA-V3 F6): BOOT_COMPLETED can arrive before the async rules
     *  init finishes, so when the payload is not loaded yet this flags a
     *  pending arm that the next post-activation hook clears — i.e. after a
     *  reboot the alarm re-arms at the next app start, not mid-boot. */
    fun onBoot(context: Context) {
        if (DynamicRulesManager.getEngagementConfig() == null) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_ARM_PENDING, true).apply()
            } catch (_: Exception) {
            }
            return
        }
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
            if (cfg.title.isBlank() && cfg.body.isBlank()) {
                // Misconfigured payload copy: nothing to say. Do NOT stamp the
                // cooldown (a fixed payload must not wait a full cooldown) and
                // do NOT fall through to the 60 s re-arm floor (that would
                // spin a blank-copy alarm every minute) — defer to the
                // cooldown horizon; the next app-open re-arms normally.
                DebugLog.engine("EngagementScheduler: blank copy — deferring to cooldown horizon")
                val alarmManager = context.getSystemService(AlarmManager::class.java)
                alarmManager?.set(
                    AlarmManager.RTC,
                    now + cfg.cooldownDays * DAY_MS,
                    alarmPendingIntent(context)
                )
                return
            }
            val posted = postNotification(context, cfg.title, cfg.body)
            if (posted) {
                prefs.edit().putLong(PREF_LAST_SENT, now).apply()
                reportSent(cfg)
            }
            arm(context) // next notification no earlier than cooldown-end
        } catch (t: Throwable) {
            DebugLog.engine("EngagementScheduler.onAlarm failed: ${t.javaClass.simpleName}")
        }
    }

    /** One id-only `engagement_sent` record per posted notification (plan
     *  §10); cooldownDays already rate-limits, so no session cap needed. */
    private fun reportSent(cfg: EngagementConfig) {
        try {
            RemoteLogger.report(
                type = "engagement_sent",
                message = "engagement notification posted after ${cfg.inactiveDays}d of inactivity",
                context = mapOf(
                    "inactive_days" to cfg.inactiveDays,
                    "cooldown_days" to cfg.cooldownDays,
                    "welcome_back" to cfg.welcomeBack
                )
            )
        } catch (_: Exception) {
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

    /** Posts the payload's copy; true when it was handed to the
     *  NotificationManager (false only for fully blank copy). */
    private fun postNotification(context: Context, title: String, body: String): Boolean {
        if (title.isBlank() && body.isBlank()) return false
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
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.notify(NOTIFICATION_ID, notification)
        return true
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
