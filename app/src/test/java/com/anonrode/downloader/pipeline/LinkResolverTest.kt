package com.anonrode.downloader.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the classifiers that MOVED VERBATIM out of DownloadEngine (2026-09-14,
 * oracle round). These encode hard-won rules — the nkiserv direct-file lesson,
 * the "host decides, not the extension" rule, the token-output exemptions —
 * and they now feed BOTH the download ladder and the search verifier, so a
 * silent edit here corrupts both. If a case fails, LinkResolver was edited,
 * not the test's memory.
 */
class LinkResolverTest {

    @Test
    fun `known locker hosts are matched by host not path extension`() {
        // The rule that bites: locker pages carry media filenames in the PATH.
        assertTrue(LinkResolver.isKnownLockerHost("https://loadedfiles.net/abc123/Episode.mkv"))
        assertTrue(LinkResolver.isKnownLockerHost("https://dl.downloadwella.com/f/12345"))
        assertTrue(LinkResolver.isKnownLockerHost("https://wetafiles.com/embed-x"))
        assertTrue(LinkResolver.isKnownLockerHost("https://wildshare.net/f/abc"))
        assertTrue(LinkResolver.isKnownLockerHost("https://vikingfile.com/f/abc"))
    }

    @Test
    fun `nkiserv stays OUT of the locker list`() {
        // 2026-09 live log 23:23:03: naijavault drawers hand out DIRECT
        // ds2.nkiserv.com/TV/*.mkv files; treating them as lockers made the
        // engine crack finished files and fail.
        assertFalse(LinkResolver.isKnownLockerHost("https://ds2.nkiserv.com/TV/Show.S01E01.mkv"))
    }

    @Test
    fun `provably-direct token outputs are exempt from locker treatment`() {
        assertTrue(LinkResolver.isProvablyDirectFile("https://cdn.x/Show.mkv?pt=abc%3D"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://pixeldrain.com/api/file/xyz"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://x.f/cd?token=123"))
        // The exemption overrides the locker-host membership (resolver outputs
        // legitimately embed the locker name):
        assertFalse(LinkResolver.isKnownLockerHost("https://fsmc02.downloadwella.com/d/Show.mkv?pt=t1"))
        // but a plain locker PAGE is still a page:
        assertFalse(LinkResolver.isProvablyDirectFile("https://downloadwella.com/f/12345"))
        assertTrue(LinkResolver.isKnownLockerHost("https://downloadwella.com/f/12345"))
    }

    @Test
    fun `blank and direct file urls are not lockers`() {
        assertFalse(LinkResolver.isKnownLockerHost(""))
        assertFalse(LinkResolver.isKnownLockerHost("https://cdn.example.com/movie.mp4"))
        // fragment-only query tricks must not crash the parsers
        assertFalse(LinkResolver.isKnownLockerHost("https://#"))
    }

    @Test
    fun `security challenge detector covers all four marker shapes`() {
        assertTrue(LinkResolver.isSecurityChallenge("<html>Just a moment... checking your browser</html>"))
        assertTrue(LinkResolver.isSecurityChallenge("<div class='cf-challenge'></div>"))
        assertTrue(LinkResolver.isSecurityChallenge("<script src=... challenge-platform ...>"))
        assertTrue(LinkResolver.isSecurityChallenge("Cloudflare Ray ID ... Please verify you are human"))
        assertFalse(LinkResolver.isSecurityChallenge("<article><h1>Scary Movie</h1>downloadwella link here</article>"))
    }
}
