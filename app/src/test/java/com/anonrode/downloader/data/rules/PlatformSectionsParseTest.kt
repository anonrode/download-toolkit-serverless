package com.anonrode.downloader.data.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.3.0 platform-surface payload sections (plan §4.1): lenient parse + the
 * typed getters other agents consume. Absent section = feature OFF; malformed
 * entries are skipped, never fatal; force defaults follow Decision 3 /
 * brick-safety rail §4.5 (soft mode, escape hatch on).
 */
class PlatformSectionsParseTest {

    private val sha64 = "a".repeat(64)

    @Test
    fun announcements_absentYieldsEmpty() {
        assertTrue(DynamicRulesManager.parseRulesJson("""{"version":"ps.ann.absent"}"""))
        assertTrue(DynamicRulesManager.getAnnouncements().isEmpty())
    }

    @Test
    fun announcements_twoEntriesParseInOrderAndOmittedFieldsDefault() {
        val ok = DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "ps.ann",
              "announcements": [
                {"id": "2026-10-18-a", "title": "Hello", "body": "World",
                 "mode": "sheet", "action": "url", "actionLabel": "Open",
                 "actionUrl": "https://example.com/a", "oncePer": "rev",
                 "minApp": 331, "expiresAtMs": 1790000000000},
                {"id": "2026-10-18-b", "title": "Second"}
              ]
            }
            """.trimIndent()
        )
        assertTrue(ok)
        val list = DynamicRulesManager.getAnnouncements()
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("2026-10-18-a", a.id)
        assertEquals("Hello", a.title)
        assertEquals("World", a.body)
        assertEquals("sheet", a.mode)
        assertEquals("url", a.action)
        assertEquals("Open", a.actionLabel)
        assertEquals("https://example.com/a", a.actionUrl)
        assertEquals("rev", a.oncePer)
        assertEquals(331, a.minApp)
        assertEquals(1790000000000L, a.expiresAtMs)
        // Omitted fields fall back to the safe defaults.
        val b = list[1]
        assertEquals("dialog", b.mode)
        assertEquals("none", b.action)
        assertEquals("id", b.oncePer)
        assertEquals(0, b.minApp)
        assertEquals(0L, b.expiresAtMs)
    }

    @Test
    fun announcements_malformedEntriesSkippedNeverFatal() {
        val ok = DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "ps.ann.malformed",
              "announcements": ["not an object", {"title": "no id"}, {"id": "  "},
                                {"id": "ok", "title": "Kept"}]
            }
            """.trimIndent()
        )
        assertTrue(ok)
        val list = DynamicRulesManager.getAnnouncements()
        assertEquals(1, list.size)
        assertEquals("ok", list[0].id)
        assertEquals("ps.ann.malformed", DynamicRulesManager.version.value)
    }

    @Test
    fun update_absentYieldsNull() {
        assertTrue(DynamicRulesManager.parseRulesJson("""{"version":"ps.up.absent"}"""))
        assertNull(DynamicRulesManager.getUpdatePolicy())
    }

    @Test
    fun update_parsesPolicyAndOmittedForceFieldsDefaultSafe() {
        val ok = DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "ps.up",
              "update": {
                "latestVersionName": "3.3.0",
                "latestVersionCode": 331,
                "sources": [
                  {"kind": "vps", "base": "http://68.155.146.145/apk/"},
                  {"kind": "github", "base": "https://github.com/x/y/releases/download/v3.3.0/"}
                ],
                "apkArm64": "Anon-3.3.0-arm64-v8a.apk",
                "apkUniversal": "Anon-3.3.0-universal.apk",
                "sha256Arm64": "$sha64",
                "sha256Universal": "$sha64",
                "message": "Please update",
                "force": {"mode": "hard"}
              }
            }
            """.trimIndent()
        )
        assertTrue(ok)
        val maybePolicy = DynamicRulesManager.getUpdatePolicy()
        assertNotNull(maybePolicy)
        val policy = maybePolicy!!
        assertEquals("3.3.0", policy.latestVersionName)
        assertEquals(331, policy.latestVersionCode)
        assertEquals(2, policy.sources.size)
        assertEquals("vps", policy.sources[0].kind)
        assertEquals("http://68.155.146.145/apk/", policy.sources[0].base)
        assertEquals("github", policy.sources[1].kind)
        assertEquals(sha64, policy.sha256Arm64)
        assertEquals("Please update", policy.message)
        val maybeForce = policy.force
        assertNotNull(maybeForce)
        val force = maybeForce!!
        assertEquals("hard", force.mode)
        // Omitted force fields: enabled stays false and the escape hatch
        // stays TRUE — a hard gate is always escapable by construction.
        assertFalse(force.enabled)
        assertTrue(force.escapeHatch)
        assertEquals(24, force.snoozeHours)
        assertEquals(0, force.minSupportedVersionCode)
    }

    @Test
    fun update_withoutForceYieldsNullForce() {
        assertTrue(DynamicRulesManager.parseRulesJson(
            """{"version":"ps.up.noforce","update":{"latestVersionName":"3.3.0","latestVersionCode":331,"sources":[{"kind":"vps","base":"https://x.test/apk/"}]}}"""
        ))
        val maybePolicy = DynamicRulesManager.getUpdatePolicy()
        assertNotNull(maybePolicy)
        assertNull(maybePolicy!!.force)
    }

    @Test
    fun ads_engagement_tamper_fcm_parseTyped() {
        val ok = DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "ps.surfaces",
              "ads": {
                "enabled": true, "provider": "house",
                "placements": ["downloads_shelf", "home_footer"],
                "frequencyPerDay": 2,
                "house": {"title": "Support us", "body": "Telegram", "actionLabel": "Join",
                          "actionUrl": "https://t.me/x", "id": "h-1"},
                "applovinSdkKey": "", "unityGameId": "", "admobAppId": "",
                "admobBannerUnitId": "", "admobNativeUnitId": ""
              },
              "engagement": {"enabled": true, "inactiveDays": 7, "cooldownDays": 14,
                             "title": "Still here?", "body": "New releases", "welcomeBack": true},
              "tamper": {"expectedCertSha256": "$sha64"},
              "fcm": {"enabled": false, "apiKey": "k", "appId": "a", "senderId": "s", "projectId": "p"}
            }
            """.trimIndent()
        )
        assertTrue(ok)
        val maybeAds = DynamicRulesManager.getAdsConfig()
        assertNotNull(maybeAds)
        val ads = maybeAds!!
        assertTrue(ads.enabled)
        assertEquals("house", ads.provider)
        assertEquals(listOf("downloads_shelf", "home_footer"), ads.placements)
        assertEquals(2, ads.frequencyPerDay)
        assertEquals("Support us", ads.houseTitle)
        assertEquals("Telegram", ads.houseBody)
        assertEquals("Join", ads.houseActionLabel)
        assertEquals("https://t.me/x", ads.houseActionUrl)
        assertEquals("h-1", ads.houseId)
        val maybeEng = DynamicRulesManager.getEngagementConfig()
        assertNotNull(maybeEng)
        val eng = maybeEng!!
        assertTrue(eng.enabled)
        assertEquals(7, eng.inactiveDays)
        assertEquals(14, eng.cooldownDays)
        assertEquals("Still here?", eng.title)
        assertTrue(eng.welcomeBack)
        val maybeTam = DynamicRulesManager.getTamperPolicy()
        assertNotNull(maybeTam)
        val tam = maybeTam!!
        assertEquals(sha64, tam.expectedCertSha256)
        assertTrue(tam.reportOnly)
        val maybeFcm = DynamicRulesManager.getFcmConfig()
        assertNotNull(maybeFcm)
        val fcm = maybeFcm!!
        assertFalse(fcm.enabled)
        assertEquals("k", fcm.apiKey)
        assertEquals("a", fcm.appId)
        assertEquals("s", fcm.senderId)
        assertEquals("p", fcm.projectId)
    }

    @Test
    fun absentSectionsPublishOffAfterAPopulatedPayload() {
        // A populated payload followed by a bare one: every getter must fall
        // back to OFF — no stale state survives the atomic swap.
        DynamicRulesManager.parseRulesJson(
            """{"version":"ps.on","tamper":{"expectedCertSha256":"$sha64"},"fcm":{"enabled":true}}"""
        )
        assertNotNull(DynamicRulesManager.getTamperPolicy())
        assertNotNull(DynamicRulesManager.getFcmConfig())
        assertTrue(DynamicRulesManager.parseRulesJson("""{"version":"ps.off"}"""))
        assertNull(DynamicRulesManager.getUpdatePolicy())
        assertNull(DynamicRulesManager.getAdsConfig())
        assertNull(DynamicRulesManager.getEngagementConfig())
        assertNull(DynamicRulesManager.getTamperPolicy())
        assertNull(DynamicRulesManager.getFcmConfig())
        assertTrue(DynamicRulesManager.getAnnouncements().isEmpty())
    }
}
