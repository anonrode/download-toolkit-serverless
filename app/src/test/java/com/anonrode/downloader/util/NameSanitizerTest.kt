package com.anonrode.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the app-wide name standard (NameSanitizer) — the user-
 * demanded "same shape for every site" behavior. The headline vector is the
 * literal complaint of 2026-09-13: scraped show title + decoration junk +
 * drawer-appended label must save as a plain readable title.
 */
class NameSanitizerTest {

    @Test
    fun thePitt_standardComplaint() {
        assertEquals(
            "The Pitt S02 Episode 1",
            NameSanitizer.savedName("The Pitt S02 _Episode 15 Added_ _ TV Series - Episode 1")
        )
        // Idempotent: cleaning an already-clean name changes nothing.
        assertEquals(
            "The Pitt S02 Episode 1",
            NameSanitizer.savedName(NameSanitizer.savedName("The Pitt S02 _Episode 15 Added_ _ TV Series - Episode 1"))
        )
    }

    @Test
    fun keepsNativeScript() {
        // Hangul/accented names used to collapse to "______"; now they save
        // as they read. Only site junk is cut.
        assertEquals(
            "이 연애는 불가항력 Episode 5",
            NameSanitizer.savedName("이 연애는 불가항력 _ TV Series - Episode 5")
        )
        assertEquals("Café des Arts", NameSanitizer.savedName("Café des Arts"))
        assertEquals("王的花", NameSanitizer.savedName("王的花"))
    }

    @Test
    fun entities_decodeAndSurvive() {
        assertEquals("S1 & 2", NameSanitizer.savedName("S1 &#038; 2"))
        assertEquals("A — B", NameSanitizer.savedName("A &#8212; B"))
        // nbsp collapses like any whitespace
        assertEquals("One Two", NameSanitizer.savedName("One\u00A0Two"))
    }

    @Test
    fun yearsAndRealParens_surviveNoiseGroupsDie() {
        assertEquals("Dune (2021)", NameSanitizer.savedName("Dune (2021) Full Movie"))
        assertEquals("Blade [2049]", NameSanitizer.savedName("Blade [2049]"))
        assertEquals("Show", NameSanitizer.savedName("Show [Watch Online HD]"))
        assertEquals("Title (Original Mix)", NameSanitizer.savedName("Title (Original Mix)"))
    }

    @Test
    fun curlyDecoration_works() {
        // Pins the DECORATIONS brace pattern — the exact regex that threw
        // PatternSyntaxException on the phone's ICU engine (unescaped trailing
        // `}`, anon_crash.txt) and killed every download since v3.1.1. Until
        // this test the brace decoration had ZERO coverage anywhere.
        assertEquals("Show", NameSanitizer.savedName("Show {Watch Online}"))
        assertEquals("Dune {2021}", NameSanitizer.savedName("Dune {2021}"))
        assertEquals("Show S02 Episode 1",
            NameSanitizer.savedName("Show S02 {TV Series} - Episode 1"))
    }

    @Test
    fun duplicateEpisodeTokens_keepTheLast() {
        assertEquals("Show Episode 7", NameSanitizer.savedName("Show Episode 3 - Episode 7"))
    }

    @Test
    fun reservedNames_and_traversal_guarded() {
        assertEquals("_CON", NameSanitizer.savedName("CON"))
        assertEquals("_nul", NameSanitizer.savedName("nul"))
        assertEquals("Download", NameSanitizer.savedName(".."))
        assertEquals("Download", NameSanitizer.savedName("."))
        assertEquals("Download", NameSanitizer.savedName(""))
        // a Windows-forgery path becomes one flat, inert component
        val evil = NameSanitizer.savedName("..\\..\\Windows\\system32\\cmd.exe")
        assertFalse(evil.contains('\\'))
        assertFalse(evil.contains("/"))
    }

    @Test
    fun forbiddenChars_replaced_spacesKept() {
        val s = NameSanitizer.savedName("a/b\\c:d*e?f\"g<h>i|j")
        assertFalse(s.contains('/')); assertFalse(s.contains('\\'))
        assertFalse(s.contains(':')); assertFalse(s.contains('*'))
        assertFalse(s.contains('?')); assertFalse(s.contains('"'))
        assertFalse(s.contains('<')); assertFalse(s.contains('>'))
        assertFalse(s.contains('|'))
        assertEquals("a_b_c_d_e_f_g_h_i_j", s)
    }

    @Test
    fun caps_countBytes_notJustChars() {
        val long = "x".repeat(300)
        assertEquals(80, NameSanitizer.savedName(long, 80).length)
        // CJK: 3 bytes/char — 80 chars would be 240 bytes (exactly at the
        // ceiling); ask for more chars and the BYTE cap still holds.
        val cjk = "王".repeat(200)
        val out = NameSanitizer.savedName(cjk, 120)
        assertTrue("utf8 bytes ${out.toByteArray(Charsets.UTF_8).size}",
            out.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(out.all { it == '王' })
    }

    @Test
    fun trailingDotsAndSpaces_neverSurvive() {
        assertEquals("Show", NameSanitizer.savedName("Show...  "))
        assertEquals("Show", NameSanitizer.savedName("....Show"))
    }

    @Test
    fun episodeJoinOnlyBeforeEpisodeTokens() {
        // a real dash between title words must NOT collapse:
        assertEquals("Dr. Dolittle - 2001", NameSanitizer.savedName("Dr. Dolittle - 2001"))
        // but a dash introducing the drawer's episode label should:
        assertEquals("Show S01 Episode 4", NameSanitizer.savedName("Show S01 - Episode 4"))
    }

    // ---- 2026-09-13 over-cut mitigations -----------------------------------
    // Bare noise phrases are cut only behind a decoration separator or at the
    // END of the string — a title that contains the words as content never
    // loses them mid-sentence.

    @Test
    fun midSentenceWordsSurvive() {
        assertEquals("The Online Class", NameSanitizer.savedName("The Online Class"))
        assertEquals("Free as a Bird", NameSanitizer.savedName("Free as a Bird"))
        // whole title IS the phrase: it is the title, nothing gets cut
        assertEquals("TV Series", NameSanitizer.savedName("TV Series"))
        // the END cut only takes multi-word phrases — single-word titles that
        // end on an ambiguous word keep every word even when the phrase would
        // match mid-string ("… _ online" is noise; "Last Online" is a title):
        assertEquals("Last Online", NameSanitizer.savedName("Last Online"))
        assertEquals("The Watch", NameSanitizer.savedName("The Watch"))
        assertEquals("Born Free", NameSanitizer.savedName("Born Free"))
        // while the true trailing junk (scraped <title> shape) is multi-word
        // and does get cut:
        assertEquals("Dune (2021)", NameSanitizer.savedName("Dune (2021) Full Movie"))
        assertEquals("Show", NameSanitizer.savedName("Show Watch Online"))
    }

    @Test
    fun separatedOrTrailingNoiseIsCut() {
        assertEquals("Show", NameSanitizer.savedName("Show - Watch Online"))
        assertEquals("Show", NameSanitizer.savedName("Show _ Free Download"))
        assertEquals("Show Episode 2", NameSanitizer.savedName("Show _ Episode 1 Added - Episode 2"))
    }

    @Test
    fun userProseNeverLosesWords_stripNoiseOff() {
        // IG captions / shared filenames: safety only, no word deletion.
        val caption = "Feeling online and free 🎬 full movie marathon"
        val cleaned = NameSanitizer.savedName(caption, 80, stripNoise = false)
        assertTrue(cleaned.contains("online"))
        assertTrue(cleaned.contains("free"))
        assertTrue(cleaned.contains("movie"))
        // ...while the same text as a SCRAPED title loses the site-junk tail:
        assertEquals("Feeling online and free 🎬",
            NameSanitizer.savedName("Feeling online and free 🎬 full movie", 80))
    }
}
