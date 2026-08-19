package com.anonrode.downloader.resolvers

import java.util.Base64

/**
 * Pure-Kotlin ChaCha20 decryptor plus rotation-proof WASM key extraction for
 * the vidsrc chain (data.vidsrcme.ru). The CDN serves a freshly built wasm
 * module per ~5-minute window (wasm.php?w=<window>&_=<ts>) and BOTH the key
 * bytes and their location inside the data segment rotate between builds, so
 * nothing about the key may be hardcoded. Instead we locate the decryptor
 * function in the code section (it embeds the ChaCha constant 0x61707865),
 * read the memory addresses of its key loads straight out of its instruction
 * stream, and XOR the data-segment words at those addresses — exactly what
 * the wasm runtime does.
 */
object VidsrcWasmCrypto {

    private const val CONST0 = 0x61707865 // "expa"
    private const val CONST1 = 0x3320646e // "nd 3"
    private const val CONST2 = 0x79622d32 // "2-by"
    private const val CONST3 = 0x6b206574 // "te k"

    // ---------------------------------------------------------------
    // Key extraction from a vidsrc wasm build
    // ---------------------------------------------------------------

    /**
     * Returns the 8 ChaCha20 key words for a vidsrc wasm module, or null when
     * the module doesn't match the known decryptor shape (a fresh build style).
     */
    fun extractKey(wasm: ByteArray): IntArray? {
        if (wasm.size < 12) return null

        // Walk the top-level section table, keeping the data segments (id 11)
        // and the code section body (id 10).
        val segments = mutableListOf<Pair<Int, ByteArray>>() // (memory offset, bytes)
        var codeBody: ByteArray? = null
        var i = 8 // skip magic + version
        while (i + 1 < wasm.size) {
            val id = wasm[i].toInt() and 0xFF
            val (size, n) = readUnsignedLeb(wasm, i + 1)
            val bodyStart = i + 1 + n
            if (bodyStart + size > wasm.size) return null
            when (id) {
                11 -> parseDataSection(wasm, bodyStart, size, segments)
                10 -> codeBody = wasm.copyOfRange(bodyStart, bodyStart + size)
            }
            i = bodyStart + size
        }
        val code = codeBody ?: return null
        if (segments.isEmpty()) return null
        val mem = assembleMemory(segments) ?: return null

        // Collect every i32.const -> i32.load address pair in the code section.
        // The decryptor loads its key words via exactly this pattern.
        val addresses = mutableListOf<Int>()
        var p = 0
        while (p < code.size) {
            if ((code[p].toInt() and 0xFF) == 0x41) {
                val (addr, n) = readUnsignedLeb(code, p + 1)
                val afterConst = p + 1 + n
                if (afterConst < code.size && (code[afterConst].toInt() and 0xFF) == 0x28) {
                    addresses.add(addr)
                    val (_, a1) = readUnsignedLeb(code, afterConst + 1)
                    val (_, a2) = readUnsignedLeb(code, afterConst + 1 + a1)
                    p = afterConst + 1 + a1 + a2
                } else {
                    p = afterConst
                }
            } else {
                p++
            }
        }
        if (addresses.size < 16) return null

        // The key loads appear as interleaved [base0, partner0, base1, ...]
        // pairs: base addresses 0,4,8,...,28 with a constant partner offset.
        // Both observed vidsrc builds validate; herring functions don't.
        val expected = intArrayOf(0, 4, 8, 12, 16, 20, 24, 28)
        for (w in 0..addresses.size - 16) {
            val window = addresses.subList(w, w + 16)
            val bases = IntArray(8) { window[it * 2] }
            val partners = IntArray(8) { window[it * 2 + 1] }
            if (!bases.contentEquals(expected)) continue
            val delta = partners[0] - bases[0]
            if (delta <= 0) continue
            var constant = true
            for (k in 1 until 8) {
                if (partners[k] - bases[k] != delta) { constant = false; break }
            }
            if (!constant) continue
            if (bases[7] + 4 > mem.size || partners[7] + 4 > mem.size) continue

            // key[k] = le32(mem[k*4]) XOR le32(mem[k*4 + delta]) — the wasm
            // decryptor's own key derivation, replayed against the data segment.
            return IntArray(8) { k ->
                leInt(mem, bases[k]) xor leInt(mem, bases[k] + delta)
            }
        }
        return null
    }

    private fun parseDataSection(wasm: ByteArray, start: Int, size: Int, out: MutableList<Pair<Int, ByteArray>>) {
        val end = start + size
        var p = start
        val (count, n0) = readUnsignedLeb(wasm, p)
        p += n0
        var seen = 0
        while (seen < count && p < end) {
            val (flags, n1) = readUnsignedLeb(wasm, p)
            p += n1
            if (flags and 1 != 0) {
                // Passive/declarative segment: not written to memory.
                val (segSize, n2) = readUnsignedLeb(wasm, p)
                p += n2 + segSize
                seen++
                continue
            }
            if (flags and 2 != 0) {
                val (memIdx, n2) = readUnsignedLeb(wasm, p)
                p += n2
                if (memIdx != 0) {
                    seen++
                    continue
                }
            }
            // Offset expression: 0x41 <sleb> 0x0b
            if (p >= end || (wasm[p].toInt() and 0xFF) != 0x41) break
            val (offset, n3) = readSignedLeb(wasm, p + 1)
            p += 1 + n3
            if (p >= end || (wasm[p].toInt() and 0xFF) != 0x0b) break
            p += 1
            val (segSize, n4) = readUnsignedLeb(wasm, p)
            p += n4
            if (p + segSize > end) break
            out.add(Pair(offset, wasm.copyOfRange(p, p + segSize)))
            p += segSize
            seen++
        }
    }

    /** Flatten the segments into their linear-memory layout. */
    private fun assembleMemory(segments: List<Pair<Int, ByteArray>>): ByteArray? {
        var maxEnd = 0
        for ((off, bytes) in segments) {
            if (off < 0) return null
            maxEnd = maxOf(maxEnd, off + bytes.size)
        }
        if (maxEnd == 0) return null
        val mem = ByteArray(maxEnd)
        for ((off, bytes) in segments) {
            bytes.copyInto(mem, off)
        }
        return mem
    }

    // ---------------------------------------------------------------
    // ChaCha20 (standard 20-round, 12-byte nonce, block counter from 0)
    // ---------------------------------------------------------------

    fun decrypt(encB64: String, key: IntArray): List<String> {
        val raw = Base64.getDecoder().decode(encB64.replace(Regex("\\s"), ""))
        if (raw.size < 12) return emptyList()
        val nonce = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val out = ByteArray(ct.size)
        val keystream = ByteArray(64)
        var block = 0
        var off = 0
        while (off < ct.size) {
            chachaBlock(key, block, nonce, keystream)
            val n = minOf(64, ct.size - off)
            for (j in 0 until n) {
                out[off + j] = (ct[off + j].toInt() xor keystream[j].toInt()).toByte()
            }
            off += n
            block++
        }
        return String(out, Charsets.UTF_8)
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("http") }
    }

    private fun chachaBlock(key: IntArray, counter: Int, nonce: ByteArray, out: ByteArray) {
        val x = IntArray(16)
        x[0] = CONST0
        x[1] = CONST1
        x[2] = CONST2
        x[3] = CONST3
        for (k in 0 until 8) x[4 + k] = key[k]
        x[12] = counter
        x[13] = leInt(nonce, 0)
        x[14] = leInt(nonce, 4)
        x[15] = leInt(nonce, 8)
        val state = x.copyOf()
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            writeLeInt(out, i * 4, x[i] + state[i])
        }
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]
        x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]
        x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]
        x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]
        x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    // ---------------------------------------------------------------
    // Little-endian / LEB128 helpers (Kotlin Int arithmetic wraps like i32)
    // ---------------------------------------------------------------

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun writeLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun readUnsignedLeb(data: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return Pair(result, i - start)
    }

    private fun readSignedLeb(data: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (shift < 32 && (data[i - 1].toInt() and 0x40) != 0) {
            result = result or (-1 shl shift)
        }
        return Pair(result, i - start)
    }
}
