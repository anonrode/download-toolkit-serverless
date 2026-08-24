package com.anonrode.downloader.engine

import kotlin.math.max

/** One state-free parse of a downloader progress tick. */
data class ProgressTick(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Double
)

// aria2c with a known total: [#123456 45MiB/65MiB(69%) CN:4 DL:3.8MiB ETA:5s]
private val ARIA_TOTAL_REGEX = Regex(
    """\s*([\d.]+[KMGT]?i?B)/([\d.]+[KMGT]?i?B).*?DL:\s*([\d.]+[KMGT]?i?B(?:/s)?)""",
    RegexOption.IGNORE_CASE
)

// aria2c WITHOUT a total (CDN omits Content-Length, or a resumed piece queue):
// [#a1b2 100.7MiB(100%) CN:4 DL:3.8MiB ETA:1s]. The size is wire bytes
// (re-downloaded pieces over-count the file), and the percentage infers the
// total from the best total seen so far. Matched only after the dl/total form
// above — its per-line size looks like the second byte string of the first form.
private val ARIA_NO_TOTAL_REGEX = Regex(
    """([\d.]+[KMGT]?i?B)\((\d+)%\)(?:.*?DL:\s*([\d.]+[KMGT]?i?B(?:/s)?))?""",
    RegexOption.IGNORE_CASE
)

// yt-dlp format: [download]  45.2% of ~65.00MiB at 4.20MiB/s ETA 00:08
private val YTDL_REGEX = Regex(
    """([\d.]+)%\s+of\s+~?([\d.]+[KMGT]?i?B).*?at\s+([\d.]+[KMGT]?i?B/s)""",
    RegexOption.IGNORE_CASE
)

/**
 * Parse one progress tick from a downloader line against the previous
 * best-known bytes, so the reported numbers never regress:
 *  - line formats: aria2c with total, aria2c without total (total inferred
 *    from the percentage), yt-dlp "45.2% of ~65.00MiB at 4.20MiB/s".
 *  - the library-supplied percentage is the last-resort source when the line
 *    carries no usable size (unknown formats, mixed downloader output); the
 *    library reports it as a 0..1 fraction or 0..100 depending on version.
 *  - unknown totals are dropped rather than resetting a known total to 0 —
 *    the v3.0.4 bug: a no-total line overwrote a parsed total with 0 and the
 *    UI fell back to bare "100.7 MB". aria restarts also re-emit smaller
 *    totals, and a late tick racing COMPLETED must not shrink what the user
 *    already saw.
 */
internal fun parseProgressTick(line: String?, libraryProgress: Float, lastDl: Long, lastTot: Long): ProgressTick {
    var dlBytes = lastDl
    var totBytes = lastTot
    var spdBps = 0.0
    var parsed = false

    if (!line.isNullOrBlank()) {
        val ariaMatch = ARIA_TOTAL_REGEX.find(line)
        if (ariaMatch != null) {
            parsed = true
            dlBytes = parseByteString(ariaMatch.groupValues[1])
            totBytes = parseByteString(ariaMatch.groupValues[2])
            spdBps = parseSpeedString(ariaMatch.groupValues[3])
        } else {
            val ariaNoTotal = ARIA_NO_TOTAL_REGEX.find(line)
            if (ariaNoTotal != null) {
                parsed = true
                dlBytes = parseByteString(ariaNoTotal.groupValues[1])
                spdBps = parseSpeedString(ariaNoTotal.groupValues[3])
                val pct = ariaNoTotal.groupValues[2].toDoubleOrNull()
                if (pct != null && pct > 0.0 && pct <= 100.0 && lastTot > 0) {
                    totBytes = (dlBytes * 100.0 / pct).toLong()
                }
            } else {
                val ytdlMatch = YTDL_REGEX.find(line)
                if (ytdlMatch != null) {
                    parsed = true
                    val pct = ytdlMatch.groupValues[1].toDoubleOrNull() ?: libraryProgress.toDouble()
                    totBytes = parseByteString(ytdlMatch.groupValues[2])
                    dlBytes = if (totBytes > 0) (totBytes * (pct / 100.0)).toLong() else 0L
                    spdBps = parseSpeedString(ytdlMatch.groupValues[3])
                }
            }
        }
    }

    // Library-supplied percentage as the last-resort source when the line
    // itself carries no usable size (unknown formats, mixed downloader output).
    // Fires only when NO line format matched — an empty or unparseable tick
    // would otherwise freeze the byte counter while downloads creep (the
    // MB-only progress complaint). The library reports pct as a 0..1 fraction
    // or 0..100 depending on version, so accept both. A parsed line is always
    // preferred: the wire bytes reflect the transfer, the pct is a hint.
    if (!parsed && lastTot > 0) {
        val libPct = when {
            libraryProgress in 0f..1f -> libraryProgress * 100.0
            libraryProgress in 1f..100f -> libraryProgress.toDouble()
            else -> 0.0
        }
        if (libPct > 0.0 && libPct <= 100.0) {
            dlBytes = (lastTot * libPct / 100.0).toLong()
        }
    }

    val safeTot = if (totBytes > 0) max(lastTot, totBytes) else lastTot
    val safeDl = max(lastDl, dlBytes)
    return ProgressTick(safeDl, safeTot, spdBps)
}

internal fun parseByteString(str: String): Long {
    val clean = str.trim().uppercase()
    val numPart = clean.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
    return when {
        clean.endsWith("GIB") || clean.endsWith("GB") -> (numPart * 1024 * 1024 * 1024).toLong()
        clean.endsWith("MIB") || clean.endsWith("MB") -> (numPart * 1024 * 1024).toLong()
        clean.endsWith("KIB") || clean.endsWith("KB") -> (numPart * 1024).toLong()
        clean.endsWith("B") -> numPart.toLong()
        else -> (numPart * 1024 * 1024).toLong()
    }
}

internal fun parseSpeedString(str: String): Double {
    val clean = str.trim().uppercase()
    val numPart = clean.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0.0
    return when {
        clean.contains("GB") || clean.contains("GIB") -> numPart * 1024 * 1024 * 1024
        clean.contains("MB") || clean.contains("MIB") -> numPart * 1024 * 1024
        clean.contains("KB") || clean.contains("KIB") -> numPart * 1024
        clean.contains("B/S") || clean.contains("BPS") -> numPart
        else -> numPart * 1024 * 1024
    }
}
