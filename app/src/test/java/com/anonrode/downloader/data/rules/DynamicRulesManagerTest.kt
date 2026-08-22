package com.anonrode.downloader.data.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the OTA rules pipeline: decrypt (AES-128-CBC, java.util.Base64),
 * parse, and the failover/extension getters. The fixture below was produced by
 * probe/encrypt_rules.py (same key/IV) — see the repo probe tooling.
 */
class DynamicRulesManagerTest {

    private val fixtureB64 =
        "Z/F9xFRvIAL290HKngJNA2UpqdcoETYc4W0IalVtp82l8+g5brTcynFD9M+G8lU/+jeF1C0hH8M0IQaCQD/+MTq/Jcxkhgf1qp9oWz2+I8NVuftdYAGqUkDLUxg2yx9T2HH7IVaVJWc9nK/pq1W2aPUoIK61G2vwELjDjV19kO2DDed4XDUEV30qIuSuAHckltBLO1i+rErPLNHTDoYWKb58Yff6aN9nWy0sWchRLN3SK6xyxHzd+X/IC6ig+mUF"

    @Test
    fun decryptRules_recoversKnownPayload() {
        val plain = DynamicRulesManager.decryptRules(fixtureB64)
        assertNotNull(plain)
        // json.dumps emits 'version": "test.1"' (space after colon) — assert
        // on the values, not the exact serializer formatting.
        assertTrue(plain!!.contains("\"version\""))
        assertTrue(plain.contains("test.1"))
        assertTrue(plain.contains("https://thenkiri.com"))
    }

    @Test
    fun decryptRules_rejectsGarbage() {
        assertNull(DynamicRulesManager.decryptRules("not base64 at all !!!"))
        // valid base64 but wrong ciphertext shape (not 16-byte aligned)
        assertNull(DynamicRulesManager.decryptRules("aGVsbG8="))
    }

    @Test
    fun parseRulesJson_appliesVersionDomainsMirrorsAndExtensions() {
        val ok = DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "test.2",
              "domains": {"nkiri": "https://thenkiri.com"},
              "mirrors": {"nkiri": ["https://nkiri.top", "https://thenkiri.com"]},
              "directMediaExtensions": [".mp4", ".mkv", ".m3u8", "-mp4", "-mkv"]
            }
            """.trimIndent()
        )
        assertTrue(ok)
        assertEquals("test.2", DynamicRulesManager.version.value)
        assertEquals("https://thenkiri.com", DynamicRulesManager.getBaseUrl("nkiri"))
    }

    @Test
    fun getBaseUrls_primaryThenDeduplicatedMirrors() {
        DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "test.3",
              "domains": {"nkiri": "https://thenkiri.com"},
              "mirrors": {"nkiri": ["https://nkiri.top", "https://thenkiri.com"]}
            }
            """.trimIndent()
        )
        val urls = DynamicRulesManager.getBaseUrls("nkiri")
        assertEquals(listOf("https://thenkiri.com", "https://nkiri.top"), urls)
    }

    @Test
    fun getBaseUrls_fallsBackToDefaultsForUnknownSite() {
        DynamicRulesManager.parseRulesJson("""{"version":"test.4"}""")
        // defaultDomains contains nkiri -> thenkiri.com (bundled default)
        assertTrue(DynamicRulesManager.getBaseUrls("nkiri").isNotEmpty())
    }

    @Test
    fun parseRulesJson_rejectsMalformedPayload() {
        assertFalse(DynamicRulesManager.parseRulesJson("{not json"))
        assertFalse(DynamicRulesManager.parseRulesJson("[]"))
    }

    @Test
    fun decryptThenParse_fullPipelineFromEncryptedPayload() {
        val plain = DynamicRulesManager.decryptRules(fixtureB64)!!
        assertTrue(DynamicRulesManager.parseRulesJson(plain))
        assertEquals("test.1", DynamicRulesManager.version.value)
        assertEquals(listOf("https://thenkiri.com", "https://nkiri.top"),
            DynamicRulesManager.getBaseUrls("nkiri"))
        val exts = DynamicRulesManager.getDirectMediaExtensions()
        assertTrue(exts.contains(".m3u8"))
        assertTrue(exts.contains("-mkv"))
    }
}
