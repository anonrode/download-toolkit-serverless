package com.anonrode.downloader.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anonrode.downloader.AnonApp
import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CI-executed feature tests for the v3.3.0 update state machine
 * (UpdateManager.evaluate) — the brick-safety rails are asserted against the
 * REAL compiled code in the GitHub build job, not simulated.
 *
 * Staleness evidence = max(prefs `last_rules_sync`, rules-cache mtime); with
 * no evidence the age is unknowable and rail 1 fails closed (never FORCED).
 *
 * @Config(manifest = NONE, application = Application::class) deliberately
 * skips AnonApp.onCreate (no engine/OTA in unit tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UpdateFlowTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun syncPrefs() =
        ctx.getSharedPreferences(AnonApp.PREFS_SERVERLESS, Context.MODE_PRIVATE)

    @Before
    fun reset() {
        DynamicRulesManager.parseRulesJson("""{"version":"qa-update-reset"}""")
        syncPrefs().edit().clear().commit()
        UpdateManager.state.value = UpdateManager.State.UNKNOWN
        UpdateManager.message.value = ""
    }

    @After
    fun tearDown() {
        UpdateManager.state.value = UpdateManager.State.UNKNOWN
        UpdateManager.message.value = ""
    }

    private fun payloadWith(forceJson: String) = """
        {
          "version": "qa-update",
          "update": {
            "latestVersionName": "9.9.9",
            "latestVersionCode": 999999,
            "sources": [{"kind": "github", "base": "https://example.com/rel/"}],
            "apkArm64": "a.apk",
            "apkUniversal": "u.apk",
            "sha256Arm64": "",
            "sha256Universal": "",
            "message": "please update",
            "force": $forceJson
          }
        }
    """.trimIndent()

    @Test
    fun absentPolicy_isUnknown() {
        UpdateManager.evaluate(ctx)
        assertEquals(UpdateManager.State.UNKNOWN, UpdateManager.state.value)
    }

    @Test
    fun newerVersion_available_withMessage() {
        DynamicRulesManager.parseRulesJson(
            payloadWith("""{"enabled":false,"minSupportedVersionCode":0}""")
        )
        UpdateManager.evaluate(ctx)
        assertEquals(UpdateManager.State.AVAILABLE, UpdateManager.state.value)
        assertEquals("please update", UpdateManager.message.value)
    }

    @Test
    fun floorSet_butNoSyncEvidence_failsClosed_toAvailable() {
        // Rail 1: unknowable payload age must NEVER force.
        DynamicRulesManager.parseRulesJson(
            payloadWith("""{"enabled":true,"minSupportedVersionCode":999999}""")
        )
        UpdateManager.evaluate(ctx)
        assertEquals(UpdateManager.State.AVAILABLE, UpdateManager.state.value)
    }

    @Test
    fun floorSet_withFreshSyncEvidence_isForced() {
        DynamicRulesManager.parseRulesJson(
            payloadWith("""{"enabled":true,"minSupportedVersionCode":999999}""")
        )
        syncPrefs().edit()
            .putLong(AnonApp.KEY_LAST_RULES_SYNC, System.currentTimeMillis())
            .commit()
        UpdateManager.evaluate(ctx)
        assertEquals(UpdateManager.State.FORCED, UpdateManager.state.value)
    }

    @Test
    fun floorZero_neverForced() {
        DynamicRulesManager.parseRulesJson(
            payloadWith("""{"enabled":true,"minSupportedVersionCode":0}""")
        )
        syncPrefs().edit()
            .putLong(AnonApp.KEY_LAST_RULES_SYNC, System.currentTimeMillis())
            .commit()
        UpdateManager.evaluate(ctx)
        assertEquals(UpdateManager.State.AVAILABLE, UpdateManager.state.value)
    }

    @Test
    fun staleSyncEvidence_degradesToAvailable() {
        DynamicRulesManager.parseRulesJson(
            payloadWith("""{"enabled":true,"minSupportedVersionCode":999999}""")
        )
        val fifteenDaysAgo = System.currentTimeMillis() - 15L * 24 * 60 * 60 * 1000
        syncPrefs().edit().putLong(AnonApp.KEY_LAST_RULES_SYNC, fifteenDaysAgo).commit()
        UpdateManager.evaluate(ctx)
        assertEquals(UpdateManager.State.AVAILABLE, UpdateManager.state.value)
        assertTrue(UpdateManager.message.value.isNotBlank())
    }

    @Test
    fun upToDate_whenLatestBelowCurrent() {
        DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "qa-upd-old",
              "update": {
                "latestVersionName": "0.0.1",
                "latestVersionCode": 1,
                "sources": [{"kind": "github", "base": "https://example.com/rel/"}],
                "sha256Arm64": "",
                "sha256Universal": ""
              }
            }
            """.trimIndent()
        )
        UpdateManager.evaluate(ctx)
        assertEquals(UpdateManager.State.UP_TO_DATE, UpdateManager.state.value)
        assertEquals("", UpdateManager.message.value)
    }
}
