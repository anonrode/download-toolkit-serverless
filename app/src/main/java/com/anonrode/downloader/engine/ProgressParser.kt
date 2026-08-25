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

// yt-dlp --progress-template with the @@DLP@@ sentinel (mirrors the Python
// monolith).  Emitted as "download:@@DLP@@ percent|speed|eta|frag_idx|frag_cnt|
// downloaded|total|total_estimate" — pipe-separated, every field.  yt-dlp
// substitutes "NA" / "Unknown" for any field it can't fill, and those are
// treated as "no value" by the parser.  Matched AFTER YTDL_REGEX so single-file
// downloads (IG, TikTok) keep the exact byte counts they have today; this branch
// is the new path that gives HLS / segmented downloads a real progress feed.
private val YTDL_TEMPLATE_REGEX = Regex(
    """@@DLP@@\s+([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)""",
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
                } else {
                    // --progress-template @@DLP@@ format: pipe-separated
                    // fields.  field 1 = percent string ("45.2%"), 2 = speed
                    // ("5.00MiB/s"), 3 = eta ("00:30"), 4 = fragment_index
                    // ("12"), 5 = fragment_count ("296"), 6 = downloaded
                    // ("45.5MiB"), 7 = total ("100.0MiB"), 8 = total_estimate
                    // ("98.2MiB").  Any field yt-dlp can't fill renders as
                    // "NA" or "Unknown B/s" and is dropped.
                    val tmpl = YTDL_TEMPLATE_REGEX.find(line)
                    if (tmpl != null) {
                        parsed = true
                        val pctStr = tmpl.groupValues[1].trim()
                        val spdStr = tmpl.groupValues[2].trim()
                        val fiStr = tmpl.groupValues[4].trim()
                        val fcStr = tmpl.groupValues[5].trim()
                        val dlStr = tmpl.groupValues[6].trim()
                        val totStr = tmpl.groupValues[7].trim()
                        val estStr = tmpl.groupValues[8].trim()
                        // Speed always parseable when present.
                        spdBps = parseSpeedString(spdStr)
                        // Downloaded bytes: prefer the explicit byte string
                        // (most reliable for HLS where the percent lags
                        // reality by one tick); fall back to percent*total
                        // when explicit bytes are missing.
                        val dlFromStr = if (dlStr.isNotBlank() && !dlStr.uppercase().startsWith("NA") && dlStr != "0B") {
                            parseByteString(dlStr)
                        } else 0L
                        // Total: prefer exact total, then estimate (HLS
                        // usually reports "NA" for total and an estimate
                        // based on segment math).  Then fall through to
                        // fragment-based estimation.
                        val totFromStr = when {
                            totStr.isNotBlank() && !totStr.uppercase().startsWith("NA") && totStr != "0B" -> parseByteString(totStr)
                            estStr.isNotBlank() && !estStr.uppercase().startsWith("NA") && estStr != "0B" -> parseByteString(estStr)
                            else -> 0L
                        }
                        val fi = fiStr.toIntOrNull()
                        val fc = fcStr.toIntOrNull()
                        val pct = pctStr.removeSuffix("%").toDoubleOrNull()
                        when {
                            dlFromStr > 0L -> {
                                dlBytes = dlFromStr
                                if (totFromStr > 0L) {
                                    totBytes = totFromStr
                                } else if (fi != null && fc != null && fc > 0) {
                                    // HLS with downloaded bytes but no total:
                                    // derive a total estimate from fragment
                                    // progress.  Monolith parity
                                    // (download.py:_ytdlp_parse_progress L2951).
                                    totBytes = (dlFromStr.toDouble() * fc / fi).toLong()
                                }
                            }
                            totFromStr > 0L && pct != null -> {
                                // No explicit bytes but we have a total and
                                // a percent: derive bytes (rare; mostly
                                // single-file downloads land in the
                                // YTDL_REGEX branch above this one).
                                totBytes = totFromStr
                                dlBytes = (totFromStr * (pct / 100.0)).toLong()
                            }
                            // Fragment-only ticks (no bytes, no total):
                            // leave dlBytes / totBytes at 0.  Setting
                            // `parsed = true` here is correct -- we did
                            // parse a line, we just couldn't extract a
                            // byte count from it.  The library-progress
                            // fallback below (lastTot > 0) handles the
                            // case where a previous tick established a
                            // total and this tick only reports the
                            // fragment index.
                        }
                    }
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
