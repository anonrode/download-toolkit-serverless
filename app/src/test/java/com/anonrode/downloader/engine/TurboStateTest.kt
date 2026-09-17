package com.anonrode.downloader.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TurboStateTest {
    private lateinit var dir: File
    private lateinit var sidecar: File
    private lateinit var state: TurboState

    @Before
    fun setUp() {
        dir = createTempDirectory("turbo-state-test").toFile()
        sidecar = File(dir, "video.turbo")
        state = TurboState(sidecar)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun malformedRowRejectsWholePlan() {
        sidecar.writeText("total=100\n0:49:50\nBAD\n")
        assertNull(state.read(100))
        assertNull(state.writtenBytes())
        assertNull(state.contiguousPrefixBytes())
    }

    @Test
    fun rejectsMissingOverlappingReorderedAndOutOfBoundsChunks() {
        val rows = listOf(
            "0:49:50",
            "0:49:50\n51:99:51",
            "0:49:50\n49:99:49",
            "50:99:50\n0:49:0",
            "-1:99:0",
            "0:100:0",
            "0:-1:0",
            "0:99:101",
            "0:99:-1",
            "0:99:not-a-number",
            "0:99:0:extra",
            "0:9223372036854775807:0"
        )
        for (row in rows) {
            sidecar.writeText("total=100\n$row\n")
            assertNull("accepted invalid rows: $row", state.read(100))
        }
    }

    @Test
    fun rejectsInvalidOrMismatchedHeaders() {
        for (header in listOf("100", "total=0", "total=-1", "total=101", "total=bad")) {
            sidecar.writeText("$header\n0:99:0\n")
            assertNull("accepted invalid header: $header", state.read(100))
        }
        sidecar.writeText("total=100\n")
        assertNull(state.read(100))
    }

    @Test
    fun validPlanPreservesProgressAcrossSocketChanges() {
        state.commit(
            listOf(TurboChunk(0, 49, 50), TurboChunk(50, 99, 75)),
            total = 100,
            force = true
        )
        val restored = state.loadOrCreate(100, 16)
        assertEquals(listOf(0L, 50L), restored.map { it.start })
        assertEquals(listOf(49L, 99L), restored.map { it.end })
        assertEquals(listOf(50L, 75L), restored.map { it.current })
        assertEquals(75L, state.writtenBytes())
        assertEquals(50L, state.contiguousPrefixBytes())
    }

    @Test
    fun invalidPlanCreatesCompleteFreshCoverage() {
        sidecar.writeText("total=100\n0:49:50\nBAD\n")
        val fresh = state.loadOrCreate(100, 4)
        assertEquals(0L, fresh.first().start)
        assertEquals(99L, fresh.last().end)
        assertTrue(fresh.all { it.current == it.start })
        assertTrue(fresh.zipWithNext().all { (left, right) -> left.end + 1 == right.start })
        assertEquals(0L, state.writtenBytes())
    }
}
