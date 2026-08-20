package com.anonrode.downloader.engine

import java.io.File

/**
 * Parsed state of an aria2c `.aria2` control file, converted into Turbo's
 * piece map so the two engines can hand a partial download back and forth.
 */
class Aria2Control(
    val fileLength: Long,
    val pieces: List<TurboChunk>
) {
    companion object {
        /**
         * Parse an aria2c control file into piece coverage. The format is
         * aria2's internal serialization (magic "aria2", version 1) — stable
         * across all 1.x releases, and pinned here because the app bundles its
         * own statically linked binary. Returns null when the file is not a
         * recognizable single-file control file.
         */
        fun parse(file: File): Aria2Control? {
            val data = try {
                file.readBytes()
            } catch (_: Exception) {
                return null
            }
            return parse(data)
        }

        internal fun parse(data: ByteArray): Aria2Control? {
            if (data.size < 7) return null
            if (data[0] != 'a'.code.toByte() || data[1] != 'r'.code.toByte() ||
                data[2] != 'i'.code.toByte() || data[3] != 'a'.code.toByte() ||
                data[4] != '2'.code.toByte() || data[5] != 0x01.toByte()) return null

            var pos = 6
            val extCount = data[pos].toInt() and 0xFF
            pos += 1
            repeat(extCount) {
                if (pos + 3 > data.size) return null
                val extLen = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
                pos += 3
                if (pos + extLen > data.size) return null
                pos += extLen
            }

            if (pos + 2 > data.size) return null
            val fileCount = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2

            var fileLength = -1L
            var pieceLength = -1L
            var bitfield = ByteArray(0)
            repeat(fileCount) {
                if (pos + 4 > data.size) return null
                val pathLen = be32(data, pos); pos += 4
                if (pos + pathLen > data.size) return null
                pos += pathLen
                if (pos + 12 > data.size) return null
                val len = be64(data, pos); pos += 8
                val plen = be32(data, pos); pos += 4
                if (pos + 4 > data.size) return null
                val blen = be32(data, pos); pos += 4
                if (pos + blen > data.size) return null
                val bf = data.copyOfRange(pos, pos + blen); pos += blen
                fileLength = len
                pieceLength = plen
                bitfield = bf
            }

            // Only single-file downloads (direct HTTP) are convertible: a torrent
            // control file covers many files and needs the peer protocol anyway.
            if (fileCount != 1 || fileLength <= 0 || pieceLength <= 0) return null

            val pieceCount = ((fileLength + pieceLength - 1) / pieceLength).toInt()
            val pieces = ArrayList<TurboChunk>(pieceCount)
            for (i in 0 until pieceCount) {
                val start = i.toLong() * pieceLength
                val end = minOf(start + pieceLength - 1, fileLength - 1)
                // aria2's bitfield is MSB-first: piece n lives in byte n/8, bit 7-(n%8).
                val complete = i < bitfield.size * 8 &&
                    ((bitfield[i / 8].toInt() and (0x80 ushr (i % 8))) != 0)
                pieces.add(TurboChunk(start, end, if (complete) end + 1 else start))
            }
            return Aria2Control(fileLength, pieces)
        }

        private fun be32(data: ByteArray, pos: Int): Int =
            ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)

        private fun be64(data: ByteArray, pos: Int): Long =
            (be32(data, pos).toLong() and 0xFFFFFFFFL) shl 32 or (be32(data, pos + 4).toLong() and 0xFFFFFFFFL)
    }
}
