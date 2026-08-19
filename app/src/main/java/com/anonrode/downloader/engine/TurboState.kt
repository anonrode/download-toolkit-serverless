package com.anonrode.downloader.engine

import java.io.File

/**
 * Sidecar resume state for [TurboDownloader], the equivalent of aria2c's .aria2
 * control file.
 *
 * A segmented download pre-allocates the target file, which zero-fills it. That
 * makes the file alone useless for resume: a zero byte could be downloaded data
 * or untouched padding. This records, per chunk, the absolute offset that has
 * actually been committed to disk, so a resumed run restarts each worker exactly
 * where it stopped instead of restarting the file (or worse, skipping bytes and
 * producing a video that plays until it cuts out).
 *
 * Format is one line per chunk, `start:end:current`, with a `total` header so a
 * stale sidecar from a different file size is rejected rather than trusted.
 */
class TurboState(private val file: File) {

    /**
     * Load a previous plan if it matches this transfer, else build a fresh one.
     * Reuses an existing valid plan across pause/resume even if socket limits change.
     */
    fun loadOrCreate(total: Long, sockets: Int): List<TurboChunk> {
        val existing = read(total)
        if (existing != null && existing.isNotEmpty()) {
            return existing
        }

        val effectiveSockets = sockets.coerceAtLeast(1)
        val chunkSize = total / effectiveSockets
        val plan = (0 until effectiveSockets).map { i ->
            val start = i * chunkSize
            val end = if (i == effectiveSockets - 1) total - 1 else (start + chunkSize - 1)
            TurboChunk(start, end, start)
        }
        commit(plan, total)
        return plan
    }

    fun read(total: Long): List<TurboChunk>? {
        if (!file.exists()) return null
        return try {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            val header = lines.first().removePrefix("total=").toLongOrNull() ?: return null
            if (total > 0 && header != total && kotlin.math.abs(header - total) > 1024) return null
            val chunks = lines.drop(1).mapNotNull { line ->
                val p = line.split(":")
                if (p.size != 3) return@mapNotNull null
                val s = p[0].toLongOrNull() ?: return@mapNotNull null
                val e = p[1].toLongOrNull() ?: return@mapNotNull null
                val c = p[2].toLongOrNull() ?: return@mapNotNull null
                if (c < s || c > e + 1) return@mapNotNull null
                TurboChunk(s, e, c)
            }
            if (chunks.isEmpty()) null else chunks
        } catch (_: Exception) {
            null
        }
    }

    private var cachedTotal: Long = -1L
    private var lastCommitTime: Long = 0L

    @Synchronized
    fun commit(plan: List<TurboChunk>, total: Long = cachedTotal, force: Boolean = false) {
        if (total > 0) cachedTotal = total
        val now = System.currentTimeMillis()
        if (!force && now - lastCommitTime < 2000L) {
            return
        }
        lastCommitTime = now
        try {
            val sb = StringBuilder("total=").append(cachedTotal).append('\n')
            for (c in plan) sb.append(c.start).append(':').append(c.end).append(':').append(c.current).append('\n')
            val parent = file.parentFile ?: return
            if (!parent.exists()) parent.mkdirs()
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(sb.toString())
            if (tmp.exists()) {
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Losing a commit only costs re-downloading a small buffer; never fatal.
        }
    }

    fun delete() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }
}
