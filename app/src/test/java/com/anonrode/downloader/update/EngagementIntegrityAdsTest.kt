package com.anonrode.downloader.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.util.IntegrityCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CI-executed feature tests for the engagement scheduler's welcome-back
 * stamping, the integrity verdict's off-states, and the ads bridge
 * inert-ness guarantee (no SDK path may engage while the payload disables it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class EngagementIntegrityAdsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs() =
        ctx.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        DynamicRulesManager.parseRulesJson("""{"version":"qa-eng-reset"}""")
        prefs().edit().clear().commit()
    }

    // ---------------------------------------------------------- engagement

    private fun engagementPayload(enabled: Boolean, welcomeBack: Boolean = true) = """
        {
          "version": "qa-eng",
          "engagement": {
            "enabled": $enabled,
            "inactiveDays": 7,
            "cooldownDays": 14,
            "title": "We miss you",
            "body": "Come back",
            "welcomeBack": $welcomeBack
          }
        }
    """.trimIndent()

    @Test
    fun disabled_isInert_noWelcomeStamp() {
        DynamicRulesManager.parseRulesJson(engagementPayload(enabled = false))
        prefs().edit()
            .putLong("engagement_last_open_ms", System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            .commit()
        EngagementScheduler.noteAppOpen(ctx)
        assertFalse(prefs().getBoolean("engagement_welcome_back_pending", false))
    }

    @Test
    fun longAbsence_stampsWelcomeBackPending() {
        DynamicRulesManager.parseRulesJson(engagementPayload(enabled = true))
        prefs().edit()
            .putLong("engagement_last_open_ms", System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            .commit()
        EngagementScheduler.noteAppOpen(ctx)
        assertTrue(prefs().getBoolean("engagement_welcome_back_pending", false))
        // The stamp precedes the last-open overwrite: the clock must move.
        assertTrue(prefs().getLong("engagement_last_open_ms", 0L) > 0L)
    }

    @Test
    fun freshOpen_noWelcomeStamp() {
        DynamicRulesManager.parseRulesJson(engagementPayload(enabled = true))
        prefs().edit()
            .putLong("engagement_last_open_ms", System.currentTimeMillis() - 60L * 60 * 1000)
            .commit()
        EngagementScheduler.noteAppOpen(ctx)
        assertFalse(prefs().getBoolean("engagement_welcome_back_pending", false))
    }

    // ----------------------------------------------------------- integrity

    @Test
    fun noPin_isNoPin_andNeverThrows() {
        DynamicRulesManager.parseRulesJson("""{"version":"qa-int-none"}""")
        assertEquals(IntegrityCheck.Verdict.NO_PIN, IntegrityCheck.evaluate(ctx))
    }

    @Test
    fun malformedPin_isUnknown() {
        DynamicRulesManager.parseRulesJson(
            """{"version":"qa-int-bad","tamper":{"expectedCertSha256":"zz"}}"""
        )
        // Client-lenient parse keeps the string; a non-64-hex pin cannot be
        // compared to anything -> UNKNOWN (never a false MISMATCH).
        assertEquals(IntegrityCheck.Verdict.UNKNOWN, IntegrityCheck.evaluate(ctx))
    }

    // ------------------------------------------------------------------ ads

    @Test
    fun adsAbsent_noBridge() {
        DynamicRulesManager.parseRulesJson("""{"version":"qa-ads-none"}""")
        assertNull(com.anonrode.downloader.ads.Ads.activeBridge(ctx))
    }

    @Test
    fun adsDisabled_noBridge() {
        DynamicRulesManager.parseRulesJson(
            """{"version":"qa-ads-off","ads":{"enabled":false,"provider":"house","frequencyPerDay":2}}"""
        )
        assertNull(com.anonrode.downloader.ads.Ads.activeBridge(ctx))
    }

    @Test
    fun vendorWithoutCredential_noBridge() {
        DynamicRulesManager.parseRulesJson(
            """{"version":"qa-ads-nocred","ads":{"enabled":true,"provider":"applovin","frequencyPerDay":2}}"""
        )
        assertNull(com.anonrode.downloader.ads.Ads.activeBridge(ctx))
    }

    @Test
    fun houseProvider_readyWithoutAnySdk() {
        DynamicRulesManager.parseRulesJson(
            """{"version":"qa-ads-house","ads":{"enabled":true,"provider":"house","frequencyPerDay":2,"house":{"id":"h1","title":"t","body":"b"}}}"""
        )
        val bridge = com.anonrode.downloader.ads.Ads.activeBridge(ctx)
        assertEquals(com.anonrode.downloader.ads.HouseAds, bridge)
    }
}
