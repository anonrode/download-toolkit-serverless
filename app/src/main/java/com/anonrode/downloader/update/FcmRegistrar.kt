package com.anonrode.downloader.update

import android.content.Context
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.data.rules.FcmConfig
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM registrar (plan §4.6): firebase-messaging ships COMPILED-IN but
 * DISABLED, and deliberately WITHOUT google-services.json — all four
 * credential strings come from the signed payload and are handed to
 * [FirebaseOptions] at runtime ("OTA can ship config/credentials, never
 * code"). enabled=false is a TOTAL no-op: no Firebase symbol executes.
 *
 * Token handling: only the PRESENCE/absence of a token is ever recorded
 * (id-only telemetry + journal) — the token itself never leaves the device
 * through our logging paths (it would be useless to us and sensitive
 * anyway). Registration is re-entrant: a payload revision with new
 * credentials deletes the previous named app and re-initializes.
 * Never throws.
 */
object FcmRegistrar {

    private const val APP_NAME = "ota-fcm"

    @Volatile
    private var registeredAppId: String = ""

    /** Called after each successful rules activation (AnonApp hook) —
     *  inert until the payload's `fcm` section lands enabled. */
    fun maybeRegister(context: Context) {
        val cfg = try {
            DynamicRulesManager.getFcmConfig()
        } catch (_: Exception) {
            null
        } ?: return
        if (!cfg.enabled) return
        if (cfg.apiKey.isBlank() || cfg.appId.isBlank() || cfg.projectId.isBlank()) return
        try {
            val app = ensureApp(context, cfg) ?: return
            FirebaseMessaging.getInstance(app).token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    report("token_ok")
                } else {
                    report("token_fail", task.exception?.javaClass?.simpleName)
                }
            }
        } catch (t: Throwable) {
            report("init_fail", t.javaClass.simpleName)
        }
    }

    /** Named-app lifecycle: reuse when the payload's appId is unchanged,
     *  otherwise delete + re-init so a credential rotation takes effect. */
    private fun ensureApp(context: Context, cfg: FcmConfig): FirebaseApp? {
        val existing = try {
            FirebaseApp.getInstance(APP_NAME)
        } catch (_: IllegalStateException) {
            null
        }
        if (existing != null) {
            if (registeredAppId == cfg.appId) return existing
            try {
                existing.delete()
            } catch (_: Exception) {
            }
            registeredAppId = ""
        }
        val options = FirebaseOptions.Builder()
            .setApiKey(cfg.apiKey)
            .setApplicationId(cfg.appId)
            .setProjectId(cfg.projectId)
            .setGcmSenderId(cfg.senderId)
            .build()
        val app = try {
            FirebaseApp.initializeApp(context.applicationContext, options, APP_NAME)
        } catch (t: Throwable) {
            DebugLog.error("FcmRegistrar: initializeApp failed (${t.javaClass.simpleName})")
            null
        }
        if (app != null) registeredAppId = cfg.appId
        return app
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
