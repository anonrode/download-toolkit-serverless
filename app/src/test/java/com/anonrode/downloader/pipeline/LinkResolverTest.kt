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
        // TRIAGE (A) TEST DRIFT: 5b0a903 (2026-09-28 engine hardening) narrowed
        // the resolver-output exemption from the blanket "?pt= / ?token= /
        // ?download" match to the dedicated output params (download_token= /
        // media_token= / direct_download=1) — ?pt= is Loadedfiles' INTERNAL
        // hop marker, and mid-chain pages carry it, so it must never exempt
        // (same hardening also inverted accept()'s guard order).
        assertTrue(LinkResolver.isProvablyDirectFile("https://cdn.x/Show.mkv?download_token=abc%3D"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://pixeldrain.com/api/file/xyz"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://x.f/cd?media_token=123"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://x.f/cd?direct_download=1"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://west-eu-upload.04b3d96d52475741e6b10f97f0a84a16.r2.cloudflarestorage.com/RBNNj1sIT2?response-content-disposition=attachment"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://vikingfile.04b3d96d52475741e6b10f97f0a84a16.r2.cloudflarestorage.com/DMFZgz5wue?X-Amz-Signature=xyz"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://3static.gfrdaseazzs.com/token/download/tempuser/83e68101e3976ae8/All.American.Homecoming"))
        assertTrue(LinkResolver.isProvablyDirectFile("https://dwbe02.downloadwella.com/d/viw53sddbwatc4c5fdyu2xprtubrseoplbakytzsqfnjj/video"))
        // The exemption overrides the locker-host membership (resolver outputs
        // legitimately embed the locker name):
        assertFalse(LinkResolver.isKnownLockerHost("https://fsmc02.downloadwella.com/d/Show.mkv?pt=t1"))
        assertFalse(LinkResolver.isKnownLockerHost("https://vikingfile.04b3d96d52475741e6b10f97f0a84a16.r2.cloudflarestorage.com/DMFZgz5wue"))
        // but a plain locker PAGE is still a page:
        assertFalse(LinkResolver.isProvablyDirectFile("https://downloadwella.com/f/12345"))
        assertTrue(LinkResolver.isKnownLockerHost("https://downloadwella.com/f/12345"))

        // ?pt= never proves directness by itself — Loadedfiles mid-chain hop
        // pages carry it and must keep being cracked as the locker pages they are:
        assertFalse(LinkResolver.isProvablyDirectFile("https://loadedfiles.net/f/x?pt=abc%3D"))
        assertTrue(LinkResolver.isKnownLockerHost("https://loadedfiles.net/f/x?pt=abc%3D"))

        // kissorgrab.com/dl/ is the direct cracked media endpoint (live-verified
        // 2026-09-19: HTTP 206, MKV magic header). A POST to this URL returned
        // HTTP 405 when DownloadwellaResolver mistakenly claimed it (activity log
        // f17d792c). Both classifiers must agree it is NOT a locker page.
        assertTrue(LinkResolver.isProvablyDirectFile("https://ol3.kissorgrab.com/dl/2f8cca675e/all-american-s01e16-55186-mkv"))
        assertFalse(LinkResolver.isKnownLockerHost("https://ol3.kissorgrab.com/dl/2f8cca675e/all-american-s01e16-55186-mkv"))
        // wetafiles.com/d/ is also a direct CDN endpoint:
        assertTrue(LinkResolver.isProvablyDirectFile("https://wetafiles.com/d/abc/movie.mkv"))
        assertFalse(LinkResolver.isKnownLockerHost("https://wetafiles.com/d/abc/movie.mkv"))
        // vikingfile.com/d/ is NOT direct — it 302-hops to R2 and the Viking
        // resolver must crack it (429 lesson, commit 0a5b1df):
        assertFalse(LinkResolver.isProvablyDirectFile("https://vikingfile.com/d/wtKpY7FHW2/Lanterns.S01E01.mkv"))
        assertTrue(LinkResolver.isKnownLockerHost("https://vikingfile.com/d/wtKpY7FHW2/Lanterns.S01E01.mkv"))
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
