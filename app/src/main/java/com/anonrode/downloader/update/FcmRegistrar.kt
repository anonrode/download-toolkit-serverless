package com.anonrode.downloader.update

import android.content.Context
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.rules.FcmConfig
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM registrar — manual-runtime route (2026-10-19 un-park).
 *
 * There is deliberately NO google-services plugin and NO google-services.json.
 * The four NON-SECRET Android client identifiers come from BuildConfig
 * defaults, so a plain APK can register with FCM with zero OTA dependency.
 * A signed OTA `fcm` block remains the override/rotation path:
 *
 *  - block absent            -> BuildConfig defaults are active;
 *  - block `enabled=false`   -> kill switch (delete token + unsubscribe);
 *  - block enabled+complete  -> override; an appId change deletes the old
 *                              FirebaseApp and re-initializes.
 *  - block enabled/incomplete-> ignored; BuildConfig defaults stay active.
 *
 * Token handling: only the PRESENCE/absence of a token is ever recorded
 * (id-only telemetry + journal) — the token itself and the Firebase
 * Installation ID never leave the device through our logging paths.
 * Topic subscription is re-entrant and repeated after every token refresh.
 *
 * App scope: firebase-messaging exposes only the no-arg
 * FirebaseMessaging.getInstance() publicly (the FirebaseApp overload is
 * package-private in the 24.1.0 artifact — verified), so the registrar owns
 * the DEFAULT FirebaseApp. Never throws to the caller.
 */
object FcmRegistrar {

    /** Topic used for delivery tests; no device token ever needs exposing. */
    const val TOPIC_TEST = "anon_test"

    @Volatile
    private var lastModeWasDisabled: Boolean = false

    /** Called after each successful rules activation and once from app start.
     *  Safe to call repeatedly: the app is reused, and a failed token/topic
     *  step is retried on the next activation. */
    fun maybeRegister(context: Context) {
        val ota = try {
            DynamicRulesManager.getFcmConfig()
        } catch (_: Exception) {
            null
        }

        if (ota != null && !ota.enabled) {
            disable(context)
            return
        }
        lastModeWasDisabled = false

        val cfg = if (ota != null && isComplete(ota)) ota else buildConfigDefaults()
        if (cfg == null) return

        try {
            ensureApp(context, cfg) ?: return
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    report("token_ok")
                    subscribeToTestTopic()
                } else {
                    report("token_fail", task.exception?.javaClass?.simpleName)
                }
            }
        } catch (t: Throwable) {
            report("token_fail", "init:" + t.javaClass.simpleName)
        }
    }

    /** Token-refresh callback from AnonFirebaseMessagingService. The token
     *  argument is intentionally never read, logged, or persisted here. */
    fun onNewToken(context: Context, token: String) {
        @Suppress("UNUSED_PARAMETER")
        val unused = token
        // Re-read the active policy: an explicit enabled=false must stay
        // silent even if the SDK emits a refreshed token later.
        val ota = try {
            DynamicRulesManager.getFcmConfig()
        } catch (_: Exception) {
            null
        }
        if (ota != null && !ota.enabled) {
            disable(context)
            return
        }
        lastModeWasDisabled = false
        try {
            FirebaseApp.getInstance()
        } catch (_: Throwable) {
            return
        }
        subscribeToTestTopic()
    }

    /** A complete OTA block overrides BuildConfig; a partial block is ignored
     *  so a typo cannot silently disable a working default registration. */
    private fun isComplete(cfg: FcmConfig): Boolean =
        cfg.apiKey.isNotBlank() && cfg.appId.isNotBlank() &&
            cfg.senderId.isNotBlank() && cfg.projectId.isNotBlank()

    private fun buildConfigDefaults(): FcmConfig? {
        val apiKey = BuildConfig.FCM_API_KEY.trim()
        val appId = BuildConfig.FCM_APP_ID.trim()
        val senderId = BuildConfig.FCM_SENDER_ID.trim()
        val projectId = BuildConfig.FCM_PROJECT_ID.trim()
        if (apiKey.isEmpty() || appId.isEmpty() || senderId.isEmpty() || projectId.isEmpty()) return null
        return FcmConfig(
            enabled = true,
            apiKey = apiKey,
            appId = appId,
            senderId = senderId,
            projectId = projectId
        )
    }

    /** Default-app lifecycle: reuse when the effective appId is unchanged,
     *  otherwise delete + re-init so a credential rotation takes effect. */
    private fun ensureApp(context: Context, cfg: FcmConfig): FirebaseApp? {
        val existing = try {
            FirebaseApp.getInstance()
        } catch (_: Throwable) {
            null
        }
        // Compare the full credential set: a key/sender/project rotation that
        // keeps the same appId is common and must still re-initialize.
        if (existing != null &&
            existing.options.applicationId == cfg.appId &&
            existing.options.apiKey == cfg.apiKey &&
            existing.options.gcmSenderId == cfg.senderId &&
            existing.options.projectId == cfg.projectId) {
            return existing
        }
        if (existing != null) {
            try {
                existing.delete()
            } catch (_: Throwable) {
            }
        }
        val options = FirebaseOptions.Builder()
            .setApiKey(cfg.apiKey)
            .setApplicationId(cfg.appId)
            .setProjectId(cfg.projectId)
            .setGcmSenderId(cfg.senderId)
            .build()
        val app = try {
            FirebaseApp.initializeApp(context.applicationContext, options)
        } catch (t: Throwable) {
            DebugLog.error("FcmRegistrar: initializeApp failed (${t.javaClass.simpleName})")
            null
        }
        return app
    }

    private fun subscribeToTestTopic() {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_TEST)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        report("topic_ok")
                    } else {
                        report("topic_fail", task.exception?.javaClass?.simpleName)
                    }
                }
        } catch (t: Throwable) {
            report("topic_fail", t.javaClass.simpleName)
        }
    }

    /** Explicit `enabled=false`: stop future registration and drop the token
     *  + topic so a kill switch actually silences the channel. */
    private fun disable(context: Context) {
        if (lastModeWasDisabled) return
        lastModeWasDisabled = true
        try {
            FirebaseApp.getInstance()
            FirebaseMessaging.getInstance().deleteToken()
            FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC_TEST)
        } catch (_: Throwable) {
            // No initialized app / no prior registration: already silent.
        }
        try { DebugLog.engine("FcmRegistrar: disabled") } catch (_: Throwable) {}
    }

    /** Stage + failure class only — never a token, never a credential. */
    private fun report(stage: String, failureClass: String? = null) {
        try {
            DebugLog.engine("FcmRegistrar: $stage ${failureClass ?: ""}")
        } catch (_: Exception) {
        }
        try {
            RemoteLogger.report(
                type = "fcm_registrar",
                message = "fcm registrar: $stage",
                context = mapOf(
                    "stage" to stage,
                    "failure" to (failureClass ?: "")
                )
            )
        } catch (_: Exception) {
        }
    }
}
