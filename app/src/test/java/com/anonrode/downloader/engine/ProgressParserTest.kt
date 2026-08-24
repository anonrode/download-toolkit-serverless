package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
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
}
