package com.anonrode.downloader.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM tests for the crash-file hygiene guards added for the 2026-10-15
 * journal-flood incident (anon_crash.txt outpaced its 512 KB share tail):
 * an identical crash repeating right after itself folds into a single
 * "REPEATED xN" line, and the file is hard-capped at 256 KB (truncation
 * marker + newest 128 KB). Pure File I/O — appendCrashEntry / crashSignature
 * touch no Android APIs.
 */
class CrashHandlerFileHygieneTest {

    private val dir: File = Files.createTempDirectory("anon-crash-test").toFile()
    private val crashFile = File(dir, "anon_crash.txt")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(message: String, frame: String, seconds: Int): String =
        "=== ANONRODE CRASH LOG (2026-10-15 01:02:$seconds) ===\n" +
            "Thread: main (id=1)\n" +
            "Device: android 33 (13) test device\n" +
            "Exception: $message\n\n" +
            "Stacktrace:\n" +
            "$message\n" +
            "\tat $frame\n" +
            "\tat frame.two(F.kt:2)\n" +
            "\tat frame.three(F.kt:3)\n" +
            "========================================\n\n"

    private fun countOccurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    @Test
    fun identicalRepeatedCrashIsFoldedIntoCounterLine() {
        val stackOverflow = "java.lang.StackOverflowError: stack size 8MB"
        // Same crash site, different timestamps: signature ignores the header.
        CrashHandler.appendCrashEntry(crashFile, entry(stackOverflow, "frame.one(A.kt:1)", 1))
        CrashHandler.appendCrashEntry(crashFile, entry(stackOverflow, "frame.one(A.kt:1)", 2))
        CrashHandler.appendCrashEntry(crashFile, entry(stackOverflow, "frame.one(A.kt:1)", 3))

        val text = crashFile.readText()
        assertEquals(1, countOccurrences(text, "Exception: $stackOverflow"))
        assertTrue(text.contains("REPEATED x2"))
        assertTrue(text.contains("REPEATED x3"))
    }

    @Test
    fun differentCrashSignatureIsAppendedInFull() {
        val stackOverflow = "java.lang.StackOverflowError: stack size 8MB"
        CrashHandler.appendCrashEntry(crashFile, entry(stackOverflow, "frame.one(A.kt:1)", 1))
        CrashHandler.appendCrashEntry(crashFile, entry("java.lang.NullPointerException: boom", "frame.one(A.kt:1)", 2))
        // The SOE is not the LAST entry anymore, so it is appended in full.
        CrashHandler.appendCrashEntry(crashFile, entry(stackOverflow, "frame.one(A.kt:1)", 3))

        val text = crashFile.readText()
        assertEquals(2, countOccurrences(text, "Exception: $stackOverflow"))
        assertEquals(1, countOccurrences(text, "Exception: java.lang.NullPointerException"))
        assertFalse(text.contains("REPEATED x"))
    }

    @Test
    fun oversizedCrashFileIsTruncatedToMarkerPlusNewestTail() {
        val filler = "0123456789".repeat(12) + "\n" // 121 bytes per line
        val big = entry("java.lang.StackOverflowError: stack size 8MB", "frame.one(A.kt:1)", 1) +
            filler.repeat(3_000) // ~363 KB — past the 256 KB cap
        CrashHandler.appendCrashEntry(crashFile, big)

        val text = crashFile.readText()
        assertTrue(text.startsWith("=== ANONRODE CRASH LOG TRUNCATED"))
        assertTrue(crashFile.length() < CrashHandler.CRASH_FILE_MAX_BYTES)
        assertTrue(crashFile.length() <= CrashHandler.CRASH_FILE_KEEP_BYTES + 1_024)
        // The newest data survived the truncation.
        assertTrue(text.contains(filler.trim()))
    }
}
