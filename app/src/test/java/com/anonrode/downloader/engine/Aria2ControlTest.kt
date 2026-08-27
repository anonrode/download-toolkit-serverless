package com.anonrode.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * JVM tests for the aria2c control-file parser. Control files are built by
 * hand following aria2's real V1 serialization (DefaultBtProgressInfoFile.cc:
 * big-endian, NO magic, one piece space per download), so the parser is
 * verified without needing the native binary.
 */
class Aria2ControlTest {

    @Test
    fun parsesSingleFileControlFile() {
        // 10-byte file, 4-byte pieces -> pieces 0-3, 4-7, 8-9.
        // Bitfield 0b10100000 (MSB-first): pieces 0 and 2 complete.
        val data = controlFile(
            totalLength = 10L,
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
        // A control file written before any piece finished still carries a
        // full-length bitfield — all zeros, no coverage.
        val data = controlFile(
            totalLength = 10L,
            pieceLength = 4,
            bitfield = byteArrayOf(0x00)
        )
        val parsed = Aria2Control.parse(data)
        assertNotNull(parsed)
        assertEquals(10L, parsed!!.fileLength)
        parsed.pieces.forEach { assertEquals(it.start, it.current) }
    }

    @Test
    fun skipsInFlightPieceRecords() {
        // Real control files append one record per in-flight piece
        // (index, length, 16KiB-block bitfield). The parser must walk past
        // them and treat an in-flight piece as NOT complete — Turbo restarts
        // it from its start offset.
        val data = controlFile(
            totalLength = 10L,
            pieceLength = 4,
            bitfield = byteArrayOf(0b1000_0000.toByte()),
            inFlight = listOf(Triple(1, 4, byteArrayOf(0b1100_0000.toByte())))
        )
        val parsed = Aria2Control.parse(data)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.pieces.size)
        assertEquals(4L, parsed.pieces[0].current)  // bit set -> complete
        assertEquals(4L, parsed.pieces[1].current)  // in-flight -> restart from start
        assertEquals(8L, parsed.pieces[2].current)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Aria2Control.parse("not an aria2 file".toByteArray()))
        assertNull(Aria2Control.parse(byteArrayOf(1, 2, 3)))
        assertNull(Aria2Control.parse(ByteArray(0)))
        // The old parser expected a literal "aria2" magic that does not exist
        // in aria2's real format — those bytes must still be rejected.
        assertNull(Aria2Control.parse("aria2".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun rejectsTruncatedControlFile() {
        val good = controlFile(
            totalLength = 10L,
            pieceLength = 4,
            bitfield = byteArrayOf(0x01)
        )
        assertNull(Aria2Control.parse(good.copyOf(good.size - 3)))
    }

    @Test
    fun rejectsTorrentControlFile() {
        // BitTorrent control files (extension flag 0x1 + 20-byte info hash)
        // span the peer protocol's piece space and are not convertible.
        val data = controlFile(
            totalLength = 10L,
            pieceLength = 4,
            bitfield = byteArrayOf(0x01),
            extension = 1,
            infoHash = ByteArray(20) { it.toByte() }
        )
        assertNull(Aria2Control.parse(data))
    }

    @Test
    fun rejectsWrongBitfieldLength() {
        // aria2 validates bitfieldLength against the piece math; a mismatch
        // means a corrupt file and must not be trusted.
        val data = controlFile(
            totalLength = 10L,
            pieceLength = 4,
            bitfield = byteArrayOf(0x01, 0x02) // 2 bytes where 1 is expected
        )
        assertNull(Aria2Control.parse(data))
    }

    private fun controlFile(
        totalLength: Long,
        pieceLength: Int,
        bitfield: ByteArray,
        extension: Int = 0,
        infoHash: ByteArray? = null,
        inFlight: List<Triple<Int, Int, ByteArray>> = emptyList()
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(0x00); buf.write(0x01)        // VERSION 1 (no magic)
        writeInt32(buf, extension)              // EXTENSION flags
        writeInt32(buf, infoHash?.size ?: 0)    // infoHashLength
        if (infoHash != null) buf.write(infoHash)
        writeInt32(buf, pieceLength)
        writeInt64(buf, totalLength)
        writeInt64(buf, 0L)                     // uploadLength (0 for HTTP)
        writeInt32(buf, bitfield.size)
        buf.write(bitfield)
        writeInt32(buf, inFlight.size)
        for ((index, length, blocks) in inFlight) {
            writeInt32(buf, index)
            writeInt32(buf, length)
            writeInt32(buf, blocks.size)
            buf.write(blocks)
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
