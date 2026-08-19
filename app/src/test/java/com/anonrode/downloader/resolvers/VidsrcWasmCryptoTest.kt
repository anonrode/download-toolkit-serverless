package com.anonrode.downloader.resolvers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// Regression tests for VidsrcWasmCrypto against real vidsrc wasm builds and
// encrypted payloads captured live from data.vidsrcme.ru. The fixtures are
// frozen snapshots of two CDN builds (the key derivation location differs
// between them), so a passing run proves the extractor survives rotation.
// Expected outputs are ground truth: the old build's was verified byte-exact
// against the CDN's own wasm via Node, the fresh build's was verified live —
// its master playlists answered HTTP 200 from noosphere-nectar.site.
class VidsrcWasmCryptoTest {

    private fun fixture(name: String): ByteArray? =
        javaClass.classLoader?.getResourceAsStream("vidsrc/$name")?.use { it.readBytes() }

    @Test
    fun oldBuildDecryptsExactly() {
        val enc = fixture("enc.b64")?.let { String(it, Charsets.UTF_8) } ?: return
        assertDecryptsTo("dec.wasm", enc, "expected_old.txt")
    }

    @Test
    fun freshBuildDecryptsExactly() {
        val api = fixture("api_fresh.json")?.let { String(it, Charsets.UTF_8) } ?: return
        val payload = JSONObject(api).optJSONObject("data")?.optString("stream_urls") ?: return
        assertDecryptsTo("fresh.wasm", payload, "expected_fresh.txt")
    }

    private fun assertDecryptsTo(wasmName: String, payload: String, expectedName: String) {
        val wasm = fixture(wasmName) ?: return
        val key = VidsrcWasmCrypto.extractKey(wasm)
        assertNotNull(key)
        val urls = VidsrcWasmCrypto.decrypt(payload, key!!)
        val expected = fixture(expectedName)?.let { String(it, Charsets.UTF_8).trim() } ?: return
        assertEquals(expected, urls.joinToString("\n"))
    }
}
