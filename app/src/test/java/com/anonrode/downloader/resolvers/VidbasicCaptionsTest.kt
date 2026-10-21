package com.anonrode.downloader.resolvers

import com.anonrode.downloader.providers.SiteSubtitleTrack
import com.anonrode.downloader.providers.SubtitleSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sub= captions surface (owner subtitle directive 2026-10-21): vidbasic's
 * 3rdplayer URL carries an AES-encrypted `sub=` parameter that the player JS
 * decrypts into a jwplayer captions track (live-verified 2026-10-21,
 * vidbasic.top/embed/gq18kua9je3 — the setup() call passes
 * tracks=[{'kind':'captions','file':CryptoJS.AES.decrypt(sub, key, {iv})…}]).
 * Before this surface existed the track was dropped and AsianC downloads had
 * no subtitles.
 *
 * KEY/IV literals below are duplicated deliberately from
 * VidbasicResolver.KEY/IV (private in production — the constants ship in the
 * APK so they are not secret material, but tests must not widen their
 * visibility). If either side changes without the other, the round-trip test
 * fails red: that is the tripwire.
 */
class VidbasicCaptionsTest {

    // Must match VidbasicResolver.KEY / .IV exactly (see drift note above).
    private val key = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
    private val iv = "5259228356829423".toByteArray(Charsets.UTF_8)

    private fun encryptForSub(plaintext: String): String =
        java.util.Base64.getEncoder().encodeToString(
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(plaintext.toByteArray(Charsets.UTF_8))
            }
        )

    private fun playerUrl(subB64: String): String {
        val enc = URLEncoder.encode(subB64, "UTF-8")
        return "https://vidbasic.top/3rdplayer.html?key=GgT0Koy5wxJ&id=gq18kua9je3&sub=$enc"
    }

    @Test
    fun decryptsSubParamToTheCaptionsFileUrl() {
        val captions = "https://cdn.vidbasic.top/subs/gq18kua9je3-en.vtt"
        assertEquals(captions, VidbasicResolver.decryptCaptionsUrl(playerUrl(encryptForSub(captions))))
    }

    @Test
    fun playerUrlWithoutSubParamYieldsNull() {
        assertNull(
            VidbasicResolver.decryptCaptionsUrl("https://vidbasic.top/3rdplayer.html?key=GgT0Koy5wxJ&id=gq18kua9je3")
        )
    }

    @Test
    fun garbageSubParamYieldsNullInsteadOfThrowing() {
        assertNull(VidbasicResolver.decryptCaptionsUrl(playerUrl("!!!not-base64!!!")))
        assertNull(VidbasicResolver.decryptCaptionsUrl(playerUrl(encryptForSub("not a url at all"))))
    }

    @Test
    fun recordCaptionsLandsUnderBothTheSourceAndDirectKeys() {
        SubtitleSurfaces.clearForTest()
        val captions = "https://cdn.vidbasic.top/subs/gq18kua9je3-en.vtt"
        val playerUrl = playerUrl(encryptForSub(captions))
        val episodeSource = "https://asianc.id/love-as-bait-2026-episode-9.html"
        val direct = "https://stream.vidbasic.top/16ccad01-eaa5/index.m3u8"
        // The REAL production wiring — no mirrored copy of the key set.
        VidbasicResolver.recordCaptions(direct, episodeSource, playerUrl)
        SubtitleSurfaces.tracksFor(playerUrl).single().let {
            assertEquals(captions, it.url)
            assertEquals("vtt", it.format)
            assertEquals("und", it.language)
        }
        SubtitleSurfaces.tracksFor(direct).single().let { assertEquals(captions, it.url) }
        // The sourceUrl key: recordCaptions keys every candidate URL too — the
        // engine queries sourceUrl first, so a task whose sourceUrl equals the
        // recorded candidate resolves immediately.
        assertEquals(captions, SubtitleSurfaces.tracksFor(episodeSource).single().url)
        SubtitleSurfaces.clearForTest()
    }
}
