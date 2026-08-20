package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * JVM tests for the aria2c control-file parser. Control files are built by
 * hand following aria2's serialization (magic "aria2", version 1, big-endian),
 * so the parser is verified without needing the native binary.
 */
class Aria2ControlTest {

    @Test
    fun parsesSingleFileControlFile() {
        // 10-byte file, 4-byte pieces -> pieces 0-3, 4-7, 8-9.
        // Bitfield 0b10100000 (MSB-first): pieces 0 and 2 complete.
        val data = controlFile(
            fileCount = 1,
            path = "movie.mp4",
            fileLength = 10,
            pieceLength = 4,
            bitfield = byteArrayOf(0b1010_0000.toByte())
        )
        val parsed = Aria2Control.parse(data)
        assertNotNull(parsed)
        assertEquals(10L, parsed!!.fileLength)
        assertEquals(3, parsed.pieces.size)
        // TurboChunk is not a data class, so compare field by field.
        assertEquals(Triple(0L, 3L, 4L), Triple(parsed.pieces[0].start, parsed.pieces[0].end, parsed.pieces[0].current))   // complete
        assertEquals(Triple(4L, 7L, 4L), Triple(parsed.pieces[1].start, parsed.pieces[1].end, parsed.pieces[1].current))   // incomplete
        assertEquals(Triple(8L, 9L, 10L), Triple(parsed.pieces[2].start, parsed.pieces[2].end, parsed.pieces[2].current))  // complete, short tail
    }

    @Test
    fun acceptsZeroBitfieldAsNoCoverage() {
        // A control file written before any piece finished has no bitfield.
        val data = controlFile(
            fileCount = 1,
            path = "movie.mp4",
            fileLength = 10,
            pieceLength = 4,
            bitfield = ByteArray(0)
        )
        val parsed = Aria2Control.parse(data)
        assertNotNull(parsed)
        assertEquals(10L, parsed!!.fileLength)
        parsed.pieces.forEach { assertEquals(it.start, it.current) }
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Aria2Control.parse("not an aria2 file".toByteArray()))
        assertNull(Aria2Control.parse(byteArrayOf(1, 2, 3)))
        assertNull(Aria2Control.parse(ByteArray(0)))
    }

    @Test
    fun rejectsTruncatedControlFile() {
        val good = controlFile(
            fileCount = 1,
            path = "movie.mp4",
            fileLength = 10,
            pieceLength = 4,
            bitfield = byteArrayOf(0x01)
        )
        assertNull(Aria2Control.parse(good.copyOf(good.size - 3)))
    }

    @Test
    fun rejectsMultiFileTorrentControlFile() {
        // Multi-file control files (torrents) are not convertible to one map.
        val data = controlFile(
            fileCount = 2,
            path = "movie.mp4",
            fileLength = 10,
            pieceLength = 4,
            bitfield = byteArrayOf(0x01)
        )
        assertNull(Aria2Control.parse(data))
    }

    private fun controlFile(
        fileCount: Int,
        path: String,
        fileLength: Long,
        pieceLength: Int,
        bitfield: ByteArray
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write("aria2".toByteArray(Charsets.US_ASCII))
        buf.write(0x01) // format version 1
        buf.write(0)    // extension count
        buf.write((fileCount ushr 8) and 0xFF)
        buf.write(fileCount and 0xFF)
        repeat(fileCount) {
            val pathBytes = path.toByteArray(Charsets.UTF_8)
            writeInt32(buf, pathBytes.size)
            buf.write(pathBytes)
            writeInt64(buf, fileLength)
            writeInt32(buf, pieceLength)
            writeInt32(buf, bitfield.size)
            buf.write(bitfield)
        }
        return buf.toByteArray()
    }

    private fun writeInt32(buf: ByteArrayOutputStream, value: Int) {
        buf.write((value ushr 24) and 0xFF)
        buf.write((value ushr 16) and 0xFF)
        buf.write((value ushr 8) and 0xFF)
        buf.write(value and 0xFF)
    }

    private fun writeInt64(buf: ByteArrayOutputStream, value: Long) {
        writeInt32(buf, (value ushr 32).toInt())
        writeInt32(buf, value.toInt())
    }
}
