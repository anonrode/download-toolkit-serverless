package com.anonrode.downloader.update

import android.content.Context
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.RemoteLogger
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest

/**
 * Play Integrity attestor (plan §4.6, "PRE-WIRED, honestly caveated").
 *
 * play-integrity ships compiled in but HARD-DISABLED: there is no payload
 * field yet and no cloud project linked, so [ENABLED] is a source constant
 * and the whole path returns before touching any SDK symbol. When the
 * maintainer registers (Google Cloud project + Play Console link + a payload
 * flag S1 adds — see TODO), flip [ENABLED], set [CLOUD_PROJECT_NUMBER], and
 * registration becomes the OTA flip the §4.6 matrix promises for the client
 * half. The honest caveat stands: verdict strength on sideloaded apps is
 * weak; enable only if wanted.
 *
 * Token handling: success/failure CLASSES are logged; the integrity token
 * itself is never logged, reported, or retained (only its presence matters
 * to diagnostics). Never throws.
 */
object IntegrityAttestor {

    /** TODO(S1): payload flag (e.g. "integrity": {"enabled": true}) — wire
     *  this to DynamicRulesManager once the section exists. Hard false keeps
     *  the path provably inert in v3.3.0. */
    private const val ENABLED = false

    /** Google Cloud project number; 0 = not registered (path inert). */
    private const val CLOUD_PROJECT_NUMBER = 0L

    /** Request hash binds a token to something app-scoped without user data;
     *  version-scoped is enough for a "is this a genuine install" signal.
     *  (Plain val, not const: BuildConfig fields are not Kotlin compile-time
     *  constants.) */
    private val REQUEST_HASH = BuildConfig.VERSION_NAME

    /** Called from the post-activation hook (AnonApp). No-op in v3.3.0. */
    fun maybeAttest(context: Context) {
        if (!ENABLED || CLOUD_PROJECT_NUMBER <= 0L) return
        try {
            val manager: StandardIntegrityManager =
                IntegrityManagerFactory.createStandard(context.applicationContext)
            manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                    .build()
            ).addOnSuccessListener { provider ->
                provider.request(
                    StandardIntegrityTokenRequest.builder()
                        .setRequestHash(REQUEST_HASH)
                        .build()
                ).addOnSuccessListener { response ->
                    // Presence only — never the token value.
                    report("request_ok", response != null)
                }.addOnFailureListener { t ->
                    report("request_fail", false, t)
                }
            }.addOnFailureListener { t ->
                report("prepare_fail", false, t)
            }
        } catch (t: Throwable) {
            report("init_fail", false, t)
        }
    }

    private fun report(stage: String, ok: Boolean, t: Throwable? = null) {
        try {
            DebugLog.engine("IntegrityAttestor: $stage ok=$ok ${t?.javaClass?.simpleName ?: ""}")
        } catch (_: Exception) {
        }
        try {
            RemoteLogger.report(
                type = "integrity_attest",
                message = "integrity attest: $stage",
                context = mapOf(
                    "stage" to stage,
                    "ok" to ok,
                    "failure" to (t?.javaClass?.simpleName ?: "")
                )
            )
        } catch (_: Exception) {
        }
    }
}
