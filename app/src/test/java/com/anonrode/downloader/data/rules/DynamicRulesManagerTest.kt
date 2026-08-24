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
        withEphemeralSigningKey { sign ->
            // Legacy bare-base64 payloads are refused post-signing; the
            // fixture now rides a signed v2 envelope (same AES content).
            val env = envelopeJson(fixtureB64, sign(fixtureB64))
            val plain = DynamicRulesManager.decryptRules(env)
            assertNotNull(plain)
            // json.dumps emits 'version": "test.1"' (space after colon) — assert
            // on the values, not the exact serializer formatting.
            assertTrue(plain!!.contains("\"version\""))
            assertTrue(plain.contains("test.1"))
            assertTrue(plain.contains("https://thenkiri.com"))
        }
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
        withEphemeralSigningKey { sign ->
            val env = envelopeJson(fixtureB64, sign(fixtureB64))
            val plain = DynamicRulesManager.decryptRules(env)!!
            assertTrue(DynamicRulesManager.parseRulesJson(plain))
            assertEquals("test.1", DynamicRulesManager.version.value)
            assertEquals(listOf("https://thenkiri.com", "https://nkiri.top"),
                DynamicRulesManager.getBaseUrls("nkiri"))
            val exts = DynamicRulesManager.getDirectMediaExtensions()
            assertTrue(exts.contains(".m3u8"))
            assertTrue(exts.contains("-mkv"))
        }
    }

    // ---------------- v2 envelope: signature verification ----------------

    /** Generates an ephemeral ECDSA P-256 pair, points the manager's verifier
     *  at the new public key, returns the private key for signing. */
    private fun withEphemeralSigningKey(block: (sign: (String) -> String) -> Unit) {
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val pair = kpg.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(pair.public.encoded)
        val original = DynamicRulesManager.rulesSigningPubB64
        DynamicRulesManager.rulesSigningPubB64 = pubB64
        try {
            block { payloadB64 ->
                val s = java.security.Signature.getInstance("SHA256withECDSA")
                s.initSign(pair.private)
                s.update(payloadB64.toByteArray(Charsets.US_ASCII))
                java.util.Base64.getEncoder().encodeToString(s.sign())
            }
        } finally {
            DynamicRulesManager.rulesSigningPubB64 = original
        }
    }

    private fun envelopeJson(payloadB64: String, sig: String?): String {
        val sigPart = if (sig != null) ",\"sig\":\"$sig\"" else ""
        return "{\"v\":2,\"alg\":\"aes-128-cbc\",\"iv\":\"5b7e9d2f4a6c8e10f3a5c7d9b1e2f4a6\"," +
            "\"payload\":\"$payloadB64\"$sigPart}"
    }

    @Test
    fun signedEnvelope_roundTrips() {
        withEphemeralSigningKey { sign ->
            // Encrypt a tiny plaintext with the fixed test IV so the payload
            // is deterministic; the point is the signature flow, not crypto.
            val plain = """{"version":"sigtest"}"""
            val key = javax.crypto.spec.SecretKeySpec(
                hexToBytesTest("8f3a9c21d4e65b0789a2c4f6d1e3b5a7"), "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
                javax.crypto.spec.IvParameterSpec(hexToBytesTest("5b7e9d2f4a6c8e10f3a5c7d9b1e2f4a6")))
            val payloadB64 = java.util.Base64.getEncoder().encodeToString(
                cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))

            val env = envelopeJson(payloadB64, sign(payloadB64))
            val decrypted = DynamicRulesManager.decryptRules(env)
            assertNotNull(decrypted)
            assertTrue(decrypted!!.contains("sigtest"))
        }
    }

    @Test
    fun signedEnvelope_badSignatureRejected() {
        withEphemeralSigningKey { _ ->
            assertNull(DynamicRulesManager.decryptRules(
                envelopeJson("AAAA", java.util.Base64.getEncoder().encodeToString(ByteArray(64))))
            )
        }
    }

    @Test
    fun v2Envelope_withoutSignatureRejected() {
        assertNull(DynamicRulesManager.decryptRules(envelopeJson("AAAA", null)))
    }

    @Test
    fun tamperedPayloadFailsSignatureEvenWithStructurallyValidEnvelope() {
        withEphemeralSigningKey { sign ->
            val env = envelopeJson("AAAA", sign("DIFFERENT_PAYLOAD"))
            assertNull(DynamicRulesManager.decryptRules(env))
        }
    }

    // ---------------- playbook v2 fields ----------------

    @Test
    fun hostPolicies_otaRulesOverrideDefaults() {
        DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "test.policies",
              "hostPolicies": [
                {"match": "anivideo.sbs", "referer": "none"},
                {"match": "mycdn.example", "referer": "exact:https://origin.example/"}
              ]
            }
            """.trimIndent()
        )
        // OTA rule overrides the built-in megaplay referer for anivideo.sbs.
        assertEquals("", DynamicRulesManager.resolveReferer("https://fntb0.anivideo.sbs/hls/x.m3u8"))
        // OTA exact rule resolves to its URL.
        assertEquals("https://origin.example/",
            DynamicRulesManager.resolveReferer("https://mycdn.example/file.mkv"))
        // Built-in defaults still apply where no OTA rule matches.
        assertEquals("https://my9jarocks.bz/",
            DynamicRulesManager.resolveReferer("https://loadedfiles.xyz/d/f.mkv"))
        assertEquals("https://megaplay.buzz/",
            DynamicRulesManager.resolveReferer("https://megap.abc/stream"))
        // Unknown host -> no referer.
        assertEquals("", DynamicRulesManager.resolveReferer("https://unmatched.host/v.mp4"))
    }

    @Test
    fun knownDeadAndUrlTemplatesParse() {
        DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "test.dead",
              "urlTemplates": {"nepu": "/watch/{media_type}/{id}"},
              "knownDead": ["jisooido.top", "jiminido"],
              "tokenTtlMinutes": 15
            }
            """.trimIndent()
        )
        assertTrue(DynamicRulesManager.isKnownDead("https://jisooido.top/hls/3rdplayer.m3u8"))
        assertFalse(DynamicRulesManager.isKnownDead("https://lisaido.top/x.m3u8"))
        assertEquals("/watch/{media_type}/{id}", DynamicRulesManager.getUrlTemplate("nepu"))
        assertEquals("", DynamicRulesManager.getUrlTemplate("nkiri"))
        assertEquals(15L * 60_000L, DynamicRulesManager.getTokenTtlMs())
    }
}

private fun hexToBytesTest(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
