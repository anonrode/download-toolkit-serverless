package com.anonrode.downloader.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the torrent security shield, focused on the audited gaps:
 * Android executables in Layer 2, PK/archive and audio container magics in
 * Layer 7, and per-file entry checks. Layer 7 runs on real temp files so the
 * delete-on-block behavior is verified too.
 */
class TorrentSecurityShieldTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- Layer 2: extensions ----------------

    @Test
    fun apkExtensionIsBlocked() {
        val (ok, why) = TorrentSecurityShield.checkExtensions("Fake.Codec.Player.v2.apk")
        assertFalse(ok)
        assertTrue(why.contains(".apk"))
    }

    @Test
    fun androidNativeFormatsAreBlocked() {
        for (name in listOf("libnative.so", "classes.dex", "bundle.aab")) {
            assertFalse(name, TorrentSecurityShield.checkExtensions(name).first)
        }
    }

    @Test
    fun doubleExtensionApkIsCaught() {
        val (ok, why) = TorrentSecurityShield.checkExtensions("Movie.2026.1080p.WEBRip.mp4.apk")
        assertFalse(ok)
        assertTrue(why.contains("Hidden executable"))
    }

    @Test
    fun cleanMediaNamesPass() {
        assertTrue(TorrentSecurityShield.checkExtensions("Movie.2026.1080p.WEBRip.x264.mkv").first)
        assertTrue(TorrentSecurityShield.checkExtensions("Show.S01E01.720p.mp4").first)
    }

    // ---------------- Layer 7: magic bytes ----------------

    private fun write(name: String, header: ByteArray, pad: Int = 64): File {
        val f = tmp.root.resolve(name)
        f.writeBytes(header + ByteArray(pad))
        return f
    }

    private fun validate(name: String, header: ByteArray): Pair<Boolean, String> {
        val f = write(name, header)
        val result = TorrentSecurityShield.validateDownloadedFile(f, tmp.root)
        return result
    }

    @Test
    fun zipDisguisedAsMp4IsBlockedAndDeleted() {
        val f = write("movie.mp4", byteArrayOf(0x50, 0x4b, 0x03, 0x04)) // "PK.."
        val (ok, why) = TorrentSecurityShield.validateDownloadedFile(f, tmp.root)
        assertFalse(ok)
        assertTrue(why.contains("PK header"))
        assertFalse("blocked file must be deleted", f.exists())
    }

    @Test
    fun apkZipDisguisedAsMkvIsBlocked() {
        // An APK is itself a zip: same PK\x03\x04 prefix.
        val (ok, why) = validate("video.mkv", byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        assertFalse(ok)
        assertTrue(why.contains("PK header"))
    }

    @Test
    fun windowsExecutableStillBlocked() {
        val (ok, why) = validate("movie.mp4", byteArrayOf(0x4d, 0x5a, 0x90.toByte(), 0x00)) // "MZ"
        assertFalse(ok)
        assertTrue(why.contains("MZ header"))
    }

    @Test
    fun htmlErrorPageStillBlocked() {
        val (ok, why) = validate("movie.mp4", "<!DOCTYPE html><html>".toByteArray())
        assertFalse(ok)
        assertTrue(why.contains("not media"))
    }

    @Test
    fun mkvContainerPasses() {
        val (ok, kind) = validate("movie.mkv", byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
        assertTrue(ok)
        assertEquals("MKV", kind)
    }

    @Test
    fun oggContainerPasses() {
        val (ok, kind) = validate("audio.ogg", "OggS".toByteArray())
        assertTrue(ok)
        assertEquals("OGG/Opus", kind)
    }

    @Test
    fun flacContainerPasses() {
        val (ok, kind) = validate("audio.flac", "fLaC".toByteArray())
        assertTrue(ok)
        assertEquals("FLAC", kind)
    }

    @Test
    fun mp3WithId3TagPasses() {
        val (ok, kind) = validate("audio.mp3", "ID3".toByteArray())
        assertTrue(ok)
        assertEquals("MP3", kind)
    }

    @Test
    fun wavContainerPasses() {
        val header = "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()
        val (ok, kind) = validate("audio.wav", header)
        assertTrue(ok)
        assertEquals("WAV", kind)
    }

    @Test
    fun corruptFlacIsRejectedBySignatureExpectation() {
        // .flac without the fLaC header: strong-magic extension must fail closed.
        val (ok, why) = validate("audio.flac", byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertFalse(ok)
        assertTrue(why.contains("flac"))
    }

    // ---------------- Per-file entries ----------------

    @Test
    fun fileEntryFlagsApkAsBlocked() {
        val entry = TorrentSecurityShield.checkTorrentFileEntry(
            "Pack/Fake.Player.apk", 700L * 1024 * 1024, "Some Show S01"
        )
        assertTrue(entry.blocked)
        assertFalse(entry.isSafe)
    }

    @Test
    fun fileEntryFlagsTraversalPath() {
        val entry = TorrentSecurityShield.checkTorrentFileEntry(
            "../../../sdcard/evil.mp4", 700L * 1024 * 1024, "Some Show S01"
        )
        assertTrue(entry.traversal)
        assertFalse(entry.isSafe)
    }

    @Test
    fun fileEntrySanitizesDisplayName() {
        val entry = TorrentSecurityShield.checkTorrentFileEntry(
            "deep/dir/Show;rm -rf S01E01.mp4", 700L * 1024 * 1024, "Show S01"
        )
        // Shell metacharacters are stripped from the swarm-provided name.
        assertEquals("Showrm -rf S01E01.mp4", entry.displayName)
    }
}
