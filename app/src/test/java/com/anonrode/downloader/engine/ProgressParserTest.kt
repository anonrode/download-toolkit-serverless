package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM fixtures for parseProgressTick — the v3.0.4 progress regression corpus:
 * a no-total aria2c line must never reset a known total to 0, ticks never
 * regress across restarts, and every line format (aria2c with/without total,
 * yt-dlp, library fallback) maps to the right byte counts.
 */
class ProgressParserTest {

    @Test
    fun ariaKnownTotal() {
        val t = parseProgressTick("[#123456 45MiB/65MiB(69%) CN:4 DL:3.8MiB ETA:5s]", 0f, 0L, 0L)
        assertEquals(45L * 1024 * 1024, t.downloadedBytes)
        assertEquals(65L * 1024 * 1024, t.totalBytes)
        assertEquals(3.8 * 1024 * 1024, t.speedBytesPerSec, 1.0)
    }

    @Test
    fun ariaNoTotalInfersTotalFromPercent() {
        // 50MiB at 50% with a known 100MiB total -> total stays 100MiB.
        val t = parseProgressTick("[#a1b2 50MiB(50%) CN:4 DL:3.8MiB ETA:1s]", 0f, 0L, 100L * 1024 * 1024)
        assertEquals(100L * 1024 * 1024, t.totalBytes)
        assertEquals(50L * 1024 * 1024, t.downloadedBytes)
    }

    @Test
    fun ariaNoTotalParsesWireBytesWithoutSpeed() {
        val t = parseProgressTick("[#a1b2 100.7MiB(100%) CN:4 DL:3.8MiB ETA:1s]", 0f, 0L, 0L)
        assertEquals((100.7 * 1024 * 1024).toLong(), t.downloadedBytes)
        assertEquals(0L, t.totalBytes)
    }

    @Test
    fun noTotalNeverResetsKnownTotal() {
        // v3.0.4 regression: a no-total line (0% or unparseable) must not
        // overwrite a known total with 0 — the UI fell back to bare "100.7 MB".
        val t = parseProgressTick("[#a1b2 100.7MiB(0%) CN:4 DL:3.8MiB]", 0f, 0L, 65L * 1024 * 1024)
        assertEquals(65L * 1024 * 1024, t.totalBytes)
    }

    @Test
    fun ytdlPercentageOfTotal() {
        val t = parseProgressTick("[download]  45.2% of ~65.00MiB at 4.20MiB/s ETA 00:08", 0f, 0L, 0L)
        assertEquals((65.0 * 1024 * 1024).toLong(), t.totalBytes)
        assertEquals(((65.0 * 1024 * 1024) * 0.452).toLong(), t.downloadedBytes)
        assertEquals(4.2 * 1024 * 1024, t.speedBytesPerSec, 1.0)
    }

    @Test
    fun restartNeverRegresses() {
        // A restarted aria2c re-emits smaller totals, and lastTot was 200MiB.
        val t = parseProgressTick("[#123456 100MiB/120MiB(83%) CN:4 DL:3.8MiB ETA:5s]", 0f, 50L * 1024 * 1024, 200L * 1024 * 1024)
        assertEquals(200L * 1024 * 1024, t.totalBytes)
        assertEquals(100L * 1024 * 1024, t.downloadedBytes)
    }

    @Test
    fun blankLineUsesLibraryPercent() {
        // Empty tick: the library pct (0..1 fraction) fills the byte counter
        // instead of freezing it — the MB-only progress complaint.
        val t = parseProgressTick("", 0.5f, 25L * 1024 * 1024, 100L * 1024 * 1024)
        assertEquals(50L * 1024 * 1024, t.downloadedBytes)
        assertEquals(100L * 1024 * 1024, t.totalBytes)
        assertEquals(0.0, t.speedBytesPerSec, 0.0)
    }

    @Test
    fun unparseableLineUsesLibraryPercent() {
        val t = parseProgressTick("[download] some unknown line", 0.5f, 0L, 100L * 1024 * 1024)
        assertEquals(50L * 1024 * 1024, t.downloadedBytes)
        assertEquals(100L * 1024 * 1024, t.totalBytes)
    }

    @Test
    fun noProgressInfoKeepsLastTick() {
        // Out-of-range library pct and no parse: nothing usable, bytes stay.
        val t = parseProgressTick("", 150f, 25L * 1024 * 1024, 100L * 1024 * 1024)
        assertEquals(25L * 1024 * 1024, t.downloadedBytes)
        assertEquals(100L * 1024 * 1024, t.totalBytes)
    }

    @Test
    fun libraryFractionFillsMissingSize() {
        // No line, known total, library reports a 0..1 fraction.
        val t = parseProgressTick(null, 0.5f, 0L, 100L * 1024 * 1024)
        assertEquals(50L * 1024 * 1024, t.downloadedBytes)
        assertEquals(100L * 1024 * 1024, t.totalBytes)
    }

    @Test
    fun libraryPercentageFillsMissingSize() {
        val t = parseProgressTick(null, 37f, 0L, 100L * 1024 * 1024)
        assertEquals((100L * 1024 * 1024 * 37) / 100, t.downloadedBytes)
    }

    @Test
    fun outOfRangeLibraryProgressIgnored() {
        val t = parseProgressTick(null, 150f, 0L, 0L)
        assertEquals(0L, t.downloadedBytes)
        assertEquals(0L, t.totalBytes)
    }

    @Test
    fun lowerCaseUnits() {
        val t = parseProgressTick("[#123456 45mib/65mib(69%) CN:4 DL:3.8mib ETA:5s]", 0f, 0L, 0L)
        assertEquals(45L * 1024 * 1024, t.downloadedBytes)
        assertEquals(65L * 1024 * 1024, t.totalBytes)
    }

    @Test
    fun byteStringSizes() {
        assertEquals(1024L, parseByteString("1KiB"))
        assertEquals(1024L, parseByteString("1KB"))
        assertEquals((1.5 * 1024 * 1024).toLong(), parseByteString("1.5MiB"))
        assertEquals((1.5 * 1024 * 1024).toLong(), parseByteString("1.5MB"))
        assertEquals(2L * 1024 * 1024 * 1024, parseByteString("2GiB"))
        assertEquals(900L, parseByteString("900B"))
        assertEquals(0L, parseByteString("garbage"))
    }

    @Test
    fun speedStrings() {
        assertEquals(3.8 * 1024 * 1024, parseSpeedString("3.8MiB/s"), 1.0)
        assertEquals(999.0 * 1024, parseSpeedString("999KiB/s"), 1.0)
        assertEquals(512.0, parseSpeedString("512B/s"), 0.0)
        assertEquals(0.0, parseSpeedString("no speed"), 0.0)
    }

    // --progress-template @@DLP@@ format: pipe-separated fields, mirrors the
    // Python monolith's download.py:_ytdlp_parse_progress.  These tests pin the
    // dramakey / wetafiles / naijaprey HLS case the user reported: a CDN
    // segmented download where the engine needs fragment-derived totals.
    @Test
    fun ytdlTemplateFullFields() {
        // percent|speed|eta|frag_idx|frag_cnt|downloaded|total|total_estimate
        val t = parseProgressTick(
            "download:@@DLP@@ 15.0%|5.00MiB/s|00:30|45|296|45.5MiB|300.0MiB|298.0MiB",
            0f, 0L, 0L
        )
        // Explicit bytes are the most reliable source for HLS — use them
        // directly rather than computing from percent * total.
        assertEquals((45.5 * 1024 * 1024).toLong(), t.downloadedBytes)
        assertEquals((300.0 * 1024 * 1024).toLong(), t.totalBytes)
        assertEquals(5.0 * 1024 * 1024, t.speedBytesPerSec, 1.0)
    }

    @Test
    fun ytdlTemplateHlsEstimateOnly() {
        // HLS: total is "NA" (yt-dlp doesn't know the final size until the
        // last segment lands), total_estimate is computed from segment math.
        val t = parseProgressTick(
            "download:@@DLP@@ 15.0%|5.00MiB/s|00:30|45|296|45.5MiB|NA|298.0MiB",
            0f, 0L, 0L
        )
        assertEquals((45.5 * 1024 * 1024).toLong(), t.downloadedBytes)
        // Falls back to total_estimate (HLS-friendly).
        assertEquals((298.0 * 1024 * 1024).toLong(), t.totalBytes)
    }

    @Test
    fun ytdlTemplateFragmentOnlyDerivesTotal() {
        // HLS with downloaded bytes but no total AND no estimate: derive the
        // total from fragment progress (dl * frag_cnt / frag_idx).  Monolith
        // parity (download.py:_ytdlp_parse_progress L2951).
        // 12 of 100 fragments, 10.0MiB downloaded -> 10.0 * 100 / 12 = 83.3MiB.
        val t = parseProgressTick(
            "download:@@DLP@@ 12.0%|2.00MiB/s|01:00|12|100|10.0MiB|NA|NA",
            0f, 0L, 0L
        )
        assertEquals((10.0 * 1024 * 1024).toLong(), t.downloadedBytes)
        val expectedTotal = (10.0 * 1024 * 1024 * 100L / 12L)
        // Allow a few bytes of rounding tolerance (the parser does Long
        // arithmetic; 83.3MiB rounds to 873813 bytes).
        assertTrue(
            "expected total near $expectedTotal, got ${t.totalBytes}",
            kotlin.math.abs(t.totalBytes - expectedTotal) < 4096
        )
    }

    @Test
    fun ytdlTemplateSingleFileLikeNative() {
        // Single-file IG-style download: template has exact total, no fragments.
        // Verifies the same field-extraction logic that YTDL_REGEX uses, so
        // the IG path keeps the byte counts the user already sees.
        val t = parseProgressTick(
            "download:@@DLP@@ 45.2%|4.20MiB/s|00:08|NA|NA|29.4MiB|65.0MiB|NA",
            0f, 0L, 0L
        )
        assertEquals((29.4 * 1024 * 1024).toLong(), t.downloadedBytes)
        assertEquals((65.0 * 1024 * 1024).toLong(), t.totalBytes)
        assertEquals(4.2 * 1024 * 1024, t.speedBytesPerSec, 1.0)
    }

    @Test
    fun ytdlTemplateHandlesNAEverywhere() {
        // Worst case: yt-dlp emits "NA" in every field.  parsed = true (the
        // line was a real templated line), but no byte data.  The existing
        // library-progress fallback at line ~91 only fires when parsed == false,
        // so we leave dl/tot at lastDl/lastTot and the engine's filesystem-fed
        // poll takes over for the UI.  No regression.
        val t = parseProgressTick(
            "download:@@DLP@@ NA|NA|NA|NA|NA|NA|NA|NA",
            0f, 50L * 1024 * 1024, 100L * 1024 * 1024
        )
        // lastDl / lastTot preserved (not zeroed).
        assertEquals(50L * 1024 * 1024, t.downloadedBytes)
        assertEquals(100L * 1024 * 1024, t.totalBytes)
        assertEquals(0.0, t.speedBytesPerSec, 0.0)
    }

    @Test
    fun ytdlTemplateDoesNotRegressKnownTotal() {
        // A previous tick established 100MiB total.  The new template tick
        // reports the same total -- must not regress.  Same monotonic
        // guarantee the existing noTotalNeverResetsKnownTotal test pins
        // for aria2c, now applied to yt-dlp.
        val t = parseProgressTick(
            "download:@@DLP@@ 25.0%|2.00MiB/s|00:30|NA|NA|25.0MiB|100.0MiB|100.0MiB",
            0f, 0L, 100L * 1024 * 1024
        )
        assertEquals((25.0 * 1024 * 1024).toLong(), t.downloadedBytes)
        assertEquals(100L * 1024 * 1024, t.totalBytes)
    }

    // ETA extraction: the downloader prefers the line's own ETA over the
    // library's (which only fills for "[download] ... ETA MM:SS" lines),
    // and derives one from speed when neither exists.
    @Test
    fun ytdlNativeLineCarriesEta() {
        val t = parseProgressTick("[download]  45.2% of ~65.00MiB at 4.20MiB/s ETA 00:08", 0f, 0L, 0L)
        assertEquals(8L, t.etaSeconds)
    }

    @Test
    fun ytdlNativeLineUnknownEtaStillParses() {
        // The HLS early-tick shape: total + ETA still unknown. Percent /
        // speed must still land (the old gate dropped these entirely when
        // the library float was still -1).
        val t = parseProgressTick("[download]   2.5% of ~Unknown at 1.50MiB/s ETA Unknown", 0f, 0L, 0L)
        assertEquals(-1L, t.etaSeconds)
        assertEquals(1.5 * 1024 * 1024, t.speedBytesPerSec, 1.0)
    }

    @Test
    fun ytdlTemplateEtaParsed() {
        val t = parseProgressTick(
            "download:@@DLP@@ 15.0%|5.00MiB/s|00:30|45|296|45.5MiB|300.0MiB|298.0MiB",
            0f, 0L, 0L
        )
        assertEquals(30L, t.etaSeconds)
    }

    @Test
    fun ytdlTemplateLongEtaParsed() {
        val t = parseProgressTick(
            "download:@@DLP@@ 15.0%|5.00MiB/s|1:02:03|45|296|45.5MiB|300.0MiB|298.0MiB",
            0f, 0L, 0L
        )
        assertEquals(3723L, t.etaSeconds)
    }

    @Test
    fun ytdlTemplateEtaUnknownIsNegative() {
        val t = parseProgressTick(
            "download:@@DLP@@ 3.0%|2.00MiB/s|Unknown|2|296|1.0MiB|NA|NA",
            0f, 0L, 0L
        )
        assertEquals(-1L, t.etaSeconds)
    }

    @Test
    fun etaStringFormats() {
        assertEquals(30L, parseEtaString("00:30"))
        assertEquals(8L, parseEtaString("00:08"))
        assertEquals(3723L, parseEtaString("1:02:03"))
        assertEquals(-1L, parseEtaString("Unknown"))
        assertEquals(-1L, parseEtaString("NA"))
        assertEquals(-1L, parseEtaString(""))
        assertEquals(-1L, parseEtaString(":"))
        assertEquals(-1L, parseEtaString("garbage"))
    }
}
