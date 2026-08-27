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
         * aria2's V1 serialization (src/DefaultBtProgressInfoFile.cc in the
         * aria2 source), big-endian and with NO magic header:
         *
         *   VERSION(2) = 0x0001
         *   EXTENSION(4)          flag word; bit 0x1 = BitTorrent download
         *   infoHashLength(4)     0 for HTTP; 20 + 20 hash bytes for BT
         *   pieceLength(4)
         *   totalLength(8)        whole download — there are no per-file
         *                         records, the control file describes one
         *                         piece space
         *   uploadLength(8)       BT upload counter, 0 for HTTP
         *   bitfieldLength(4)
         *   bitfield              one bit per piece, MSB-first, whole file
         *   numInFlightPiece(4)
         *   per in-flight piece:  index(4), length(4), bitfieldLength(4),
         *                         block bitfield (one bit per 16 KiB block)
         *
         * Stable across all 1.x releases, and pinned here because the app
         * bundles its own statically linked binary. Returns null when the
         * file is not a recognizable plain-HTTP control file.
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
            // Fixed header through bitfieldLength: 2+4+4+4+8+8+4 = 34 bytes.
            if (data.size < 34) return null
            if (data[0] != 0x00.toByte() || data[1] != 0x01.toByte()) return null

            var pos = 2
            val extension = be32(data, pos); pos += 4
            val infoHashLength = be32(data, pos); pos += 4
            // Only plain HTTP downloads are convertible: a torrent control
            // file (extension bit set / info hash present) spans the peer
            // protocol's multi-file piece space and needs more than a map.
            if ((extension and 1) != 0 || infoHashLength != 0) return null
            if (pos + 4 + 8 + 8 + 4 > data.size) return null
            val pieceLength = be32(data, pos).toLong(); pos += 4
            val totalLength = be64(data, pos); pos += 8
            pos += 8 // uploadLength — irrelevant for HTTP
            val bitfieldLength = be32(data, pos); pos += 4
            if (pieceLength <= 0 || totalLength <= 0 || bitfieldLength < 0) return null
            // aria2 itself rejects a control file whose bitfield length
            // doesn't match the piece math — do the same instead of trusting
            // a corrupt map.
            val expectedBfLen = ((totalLength + pieceLength - 1) / pieceLength + 7) / 8
            if (bitfieldLength.toLong() != expectedBfLen) return null
            if (pos.toLong() + bitfieldLength + 4 > data.size) return null
            val bitfield = data.copyOfRange(pos, pos + bitfieldLength); pos += bitfieldLength

            // In-flight piece records carry 16 KiB block maps; Turbo restarts
            // a partial piece from its start offset, so only the whole-piece
            // bitfield matters. Walk (not skip) them to validate lengths — a
            // truncated file must be rejected, not misparsed.
            val numInFlight = be32(data, pos); pos += 4
            if (numInFlight < 0) return null
            repeat(numInFlight) {
                if (pos + 12 > data.size) return null
                val pBfLen = be32(data, pos + 8)
                if (pBfLen < 0) return null
                pos += 12
                if (pos.toLong() + pBfLen > data.size) return null
                pos += pBfLen
            }

            val pieceCount = ((totalLength + pieceLength - 1) / pieceLength).toInt()
            val pieces = ArrayList<TurboChunk>(pieceCount)
            for (i in 0 until pieceCount) {
                val start = i.toLong() * pieceLength
                val end = minOf(start + pieceLength - 1, totalLength - 1)
                // aria2's bitfield is MSB-first: piece n lives in byte n/8, bit 7-(n%8).
                val complete = i < bitfield.size * 8 &&
                    ((bitfield[i / 8].toInt() and (0x80 ushr (i % 8))) != 0)
                pieces.add(TurboChunk(start, end, if (complete) end + 1 else start))
            }
            return Aria2Control(totalLength, pieces)
        }

        private fun be32(data: ByteArray, pos: Int): Int =
            ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)

        private fun be64(data: ByteArray, pos: Int): Long =
            (be32(data, pos).toLong() and 0xFFFFFFFFL) shl 32 or (be32(data, pos + 4).toLong() and 0xFFFFFFFFL)
    }
}
