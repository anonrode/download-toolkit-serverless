package com.anonrode.downloader.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * `.part-Frag<N>` staging files are scratch, but they break retries
 * ("part-Frag263 missing") and were invisible to disk progress. Pin the
 * scoped sweep and the accounting.
 */
class HlsPartFilesTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("hls-frags").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun scopedSweepOnlyTouchesThisTaskStem() {
        val a1 = file("Demon's Ascension S01E03.mp4.part-Frag3", 100)
        val a2 = file("Demon's Ascension S01E03.mp4.part-Frag12", 50)
        val other = file("Other Show S01E01.mp4.part-Frag3", 30)
        val part = file("Demon's Ascension S01E03.mp4.part", 10)
        val media = file("Demon's Ascension S01E03.mp4", 20)

        assertEquals(2, partFragFiles(dir, "Demon's Ascension S01E03", privateWorkdir = false).size)
        assertEquals(150L, partFragBytes(dir, "Demon's Ascension S01E03"))
        assertEquals(2, deletePartFragFiles(dir, "Demon's Ascension S01E03", privateWorkdir = false))

        assertFalse(a1.exists())
        assertFalse(a2.exists())
        assertTrue(other.exists())
        assertTrue(part.exists())
        assertTrue(media.exists())
    }

    @Test
    fun privateWorkdirSweepRemovesEveryFragment() {
        file("Show.S01E01.mp4.part-Frag1", 10)
        file("Show.S01E01.f137.mp4.part-Frag2", 20)
        file("Show.S01E01.mp4.part", 5)

        assertEquals(2, deletePartFragFiles(dir, "unrelated", privateWorkdir = true))
        assertEquals(1, dir.listFiles()?.size)
        assertTrue(File(dir, "Show.S01E01.mp4.part").exists())
    }

    @Test
    fun accountingSumsConcatPrefixAndPendingFragments() {
        file("Ep.mp4.part", 1000)
        file("Ep.mp4.part-Frag7", 200)
        file("Ep.mp4.part-Frag8", 300)
        assertEquals(500L, partFragBytes(dir, "Ep"))
    }
}
