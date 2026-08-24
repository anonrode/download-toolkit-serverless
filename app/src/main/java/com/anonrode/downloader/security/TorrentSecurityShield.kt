package com.anonrode.downloader.security

import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * 7-Layer Anti-Malware & Torrent Security Shield.
 * Directly ported from monolith `src/security.py`.
 *
 * Layers:
 *   1. Uploader Trust & Reputation (VIP/trusted floor, minimum seeders)
 *   2. Extension & Double-Extension Blacklist
 *   3. InfoHash SHA1 / Base32 Validation
 *   4. Magnet & Shell Metacharacter Sanitizer
 *   5. Negative Filters (CAM/TS, password/archive scams)
 *   6. Scope-Aware Plausible Size Guard (minimums and maximums)
 *   7. Post-Download Magic-Byte Container Inspector & Path Traversal Guard
 */
object TorrentSecurityShield {

    // --- Layer 1: Trust Tiers & Seeder Thresholds ---
    const val TRUST_VIP = "vip"
    const val TRUST_TRUSTED = "trusted"
    const val TRUST_MEMBER = "member"

    private val MIN_SEEDERS = mapOf(
        TRUST_VIP to 1,
        TRUST_TRUSTED to 3,
        TRUST_MEMBER to 15
    )
    private const val MIN_SEEDERS_DEFAULT = 15

    // --- Layer 2: Blacklisted Extensions ---
    val BLOCKED_EXTENSIONS = setOf(
        ".exe", ".dll", ".lnk", ".bat", ".cmd", ".vbs", ".vbe",
        ".js", ".jse", ".wsf", ".wsh", ".ps1", ".ps2", ".msc",
        ".msi", ".msp", ".scr", ".iso", ".img", ".inf", ".reg",
        ".hta", ".cpl", ".jar", ".com", ".pif", ".application",
        ".gadget", ".appref-ms", ".sct", ".ws", ".mst", ".chm",
        // Android-native executable formats — the single most relevant
        // malware vector for this platform (fake "codec"/"player" APKs).
        ".apk", ".aab", ".so", ".dex"
    )

    // --- Layer 3: InfoHash Regex ---
    private val INFOHASH_HEX = Pattern.compile("^[a-fA-F0-9]{40}$")
    private val INFOHASH_B32 = Pattern.compile("^[A-Z2-7]{32}$")

    // --- Layer 4: Shell & URI Dangerous Characters ---
    private val SHELL_DANGER = Pattern.compile("[;&|`$\\r\\n\\u0000-\\u001f]")
    private val MAGNET_DANGER = Pattern.compile("[;|`$\\r\\n\\u0000-\\u001f]")

    // High-uptime public trackers
    val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.tiny-vps.com:6969/announce"
    )

    // --- Layer 5: Negative Filter Patterns ---
    private val CAM_RE = Pattern.compile("""(?i)(?:CAM|HDTS|TELESYNC|TS[\-\.]?RIP|HDCAM)""")
    private val SUSPICIOUS_CONTENT_RE = Pattern.compile("""(?i)(?:password|passcode|pass\s*in\s*txt)|\.(?:rar|zip|7z)""")
    private val EXECUTABLE_RE = Pattern.compile("""(?i)\.(?:exe|bat|cmd|scr|msi|ps1|vbs|js|com|pif)(?:|$)""")
    private val CAM_INTENT_RE = Pattern.compile("""(?i)cam""")

    // --- Layer 6: HEVC & Plausible Size Limits ---
    private val HEVC_RE = Pattern.compile("""(?i)(?:x265|h\.?265|HEVC)""")
    private const val HEVC_SIZE_FACTOR = 0.6

    private val MIN_SIZE_MOVIE = mapOf(
        4 to 3L * 1024 * 1024 * 1024,      // 4K: 3GB+
        3 to 900L * 1024 * 1024,           // 1080p: 900MB+
        2 to 500L * 1024 * 1024            // 720p: 500MB+
    )
    private val MIN_SIZE_EPISODE = mapOf(
        4 to 600L * 1024 * 1024,           // 4K ep: 600MB+
        3 to 180L * 1024 * 1024,           // 1080p ep: 180MB+
        2 to 90L * 1024 * 1024             // 720p ep: 90MB+
    )
    private val MAX_SIZE_MOVIE = mapOf(
        4 to 90L * 1024 * 1024 * 1024,     // 4K movie: 90GB cap
        3 to 40L * 1024 * 1024 * 1024,     // 1080p movie: 40GB cap
        2 to 25L * 1024 * 1024 * 1024      // 720p movie: 25GB cap
    )
    private val MAX_SIZE_EPISODE = mapOf(
        4 to 50L * 1024 * 1024 * 1024,     // 4K ep: 50GB cap
        3 to 25L * 1024 * 1024 * 1024,     // 1080p ep: 25GB cap
        2 to 15L * 1024 * 1024 * 1024      // 720p ep: 15GB cap
    )
    private val MAX_SIZE_PACK = mapOf(
        4 to 2000L * 1024 * 1024 * 1024,   // 4K pack: 2TB cap
        3 to 1000L * 1024 * 1024 * 1024,   // 1080p pack: 1TB cap
        2 to 600L * 1024 * 1024 * 1024     // 720p pack: 600GB cap
    )
    private val PACK_RE = Pattern.compile(
        """(?i)(?:S\d{1,2}\s*-\s*S?\d{1,2}|S\d{1,2}(?![\s\.\-_]*E\d)|seasons?[\s\.\-_]*\d{1,2}(?!\d)|complete[\s\.\-_]*(?:series|collection|seasons?)|(?:full|entire)[\s\.\-_]*series|box[\s\.\-_]?set)"""
    )

    // --- Layer 7: Magic Byte Signatures ---
    private val MAGIC_MKV = byteArrayOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte())
    private val MAGIC_MP4 = "ftyp".toByteArray(Charsets.US_ASCII)
    private val MAGIC_AVI_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val MAGIC_AVI_TAG = "AVI ".toByteArray(Charsets.US_ASCII)
    private val MAGIC_EXE_MZ = byteArrayOf(0x4d.toByte(), 0x5a.toByte()) // "MZ"
    // "PK" — zip/apk/jar/ooxml container. A torrent "movie" that is really a
    // zip archive is the classic password-scam payload; reject outright.
    private val MAGIC_ZIP_PK = byteArrayOf(0x50.toByte(), 0x4b.toByte())
    private val MAGIC_OGG = "OggS".toByteArray(Charsets.US_ASCII)
    private val MAGIC_FLAC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val MAGIC_ID3 = "ID3".toByteArray(Charsets.US_ASCII)
    private val MAGIC_ELF = byteArrayOf(0x7f.toByte(), 0x45.toByte(), 0x4c.toByte(), 0x46.toByte()) // "ELF"

    // -------------------------------------------------------------
    // LAYER 1: Uploader Trust Check
    // -------------------------------------------------------------
    fun checkUploaderTrust(status: String, seeders: Int): Pair<Boolean, String> {
        val tier = when (status.lowercase().trim()) {
            "vip" -> TRUST_VIP
            "trusted" -> TRUST_TRUSTED
            else -> TRUST_MEMBER
        }
        val minSeeds = MIN_SEEDERS[tier] ?: MIN_SEEDERS_DEFAULT
        if (seeders < minSeeds) {
            return Pair(false, "Too few seeders ($seeders) for $tier tier (needs $minSeeds+)")
        }
        return Pair(true, tier)
    }

    // -------------------------------------------------------------
    // LAYER 2: Extension & Double-Extension Shield
    // -------------------------------------------------------------
    fun checkExtensions(name: String): Pair<Boolean, String> {
        if (name.isBlank()) return Pair(false, "Empty release name")
        val clean = name.lowercase().trim()
        val segments = clean.split('.')

        // Check double-extension attack first (e.g. Movie.mp4.apk): a blocked
        // extension anywhere in the name — not just the final position — is a
        // hidden-executable disguise and must be reported as such.
        if (segments.size >= 3) {
            for (i in 1 until segments.size) {
                val segExt = "." + segments[i]
                if (segExt in BLOCKED_EXTENSIONS) {
                    return Pair(false, "Hidden executable in name: ...${segments[i - 1]}.${segments[i]}")
                }
            }
        }

        // Check final extension
        if (segments.size > 1) {
            val finalExt = "." + segments.last()
            if (finalExt in BLOCKED_EXTENSIONS) {
                return Pair(false, "Blocked extension: $finalExt")
            }
        }

        val base = segments.firstOrNull() ?: ""
        if (base.length < 2) {
            return Pair(false, "Suspiciously short release name")
        }

        return Pair(true, "")
    }

    // -------------------------------------------------------------
    // LAYER 3: InfoHash Validation
    // -------------------------------------------------------------
    fun checkInfoHash(infoHash: String): Pair<Boolean, String> {
        val h = infoHash.trim()
        if (INFOHASH_HEX.matcher(h).matches() || INFOHASH_B32.matcher(h).matches()) {
            return Pair(true, "")
        }
        return Pair(false, "Malformed info_hash: $h")
    }

    // -------------------------------------------------------------
    // LAYER 4: Magnet URI Construction & Injection Guard
    // -------------------------------------------------------------
    fun buildSanitizedMagnet(infoHash: String, name: String): Pair<String?, String> {
        val hashCheck = checkInfoHash(infoHash)
        if (!hashCheck.first) return Pair(null, hashCheck.second)

        val cleanName = SHELL_DANGER.matcher(name).replaceAll("").trim().ifEmpty { infoHash }
        val encodedName = URLEncoder.encode(cleanName, "UTF-8")
        val tr = TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        val magnet = "magnet:?xt=urn:btih:$infoHash&dn=$encodedName$tr"

        if (MAGNET_DANGER.matcher(magnet).find()) {
            return Pair(null, "Assembled magnet contains dangerous characters")
        }

        return Pair(magnet, "")
    }

    // -------------------------------------------------------------
    // LAYER 5: Negative Filters (CAM/TS, Password, Archives)
    // -------------------------------------------------------------
    fun checkNegativeFilters(name: String, userQuery: String = ""): Pair<Boolean, String> {
        if (name.isBlank()) return Pair(true, "")

        if (!CAM_INTENT_RE.matcher(userQuery).find()) {
            if (CAM_RE.matcher(name).find()) {
                return Pair(false, "Excluded low-quality CAM/TS release")
            }
        }

        if (SUSPICIOUS_CONTENT_RE.matcher(name).find()) {
            return Pair(false, "Excluded passworded/archive release")
        }

        if (EXECUTABLE_RE.matcher(name).find()) {
            return Pair(false, "Excluded dangerous executable extension")
        }

        return Pair(true, "")
    }

    // -------------------------------------------------------------
    // LAYER 6: Scope-Aware Plausible Size Guard
    // -------------------------------------------------------------
    fun checkSizePlausible(name: String, sizeBytes: Long): Pair<Boolean, String> {
        if (sizeBytes <= 0) return Pair(false, "Zero or negative file size")

        val qualityTier = when {
            Pattern.compile("""(?i)(?:2160p|4K|UHD)""").matcher(name).find() -> 4
            Pattern.compile("""(?i)(?:1080p|FHD)""").matcher(name).find() -> 3
            Pattern.compile("""(?i)(?:720p|HD)""").matcher(name).find() -> 2
            else -> 0
        }

        val isEp = Pattern.compile("""(?i)(?:S\d{1,2}E\d{1,3}|\d{1,2}x\d{2,3}|episode\s*\d+)""").matcher(name).find()
        val isPack = !isEp && PACK_RE.matcher(name).find()

        val minMap = if (isEp) MIN_SIZE_EPISODE else MIN_SIZE_MOVIE
        var minBytes = (minMap[qualityTier] ?: 0L).toDouble()

        if (minBytes > 0 && HEVC_RE.matcher(name).find()) {
            minBytes *= HEVC_SIZE_FACTOR
        }

        if (minBytes > 0 && sizeBytes < minBytes.toLong()) {
            val tierLabel = mapOf(4 to "4K", 3 to "1080p", 2 to "720p")[qualityTier] ?: ""
            val sizeMb = sizeBytes / (1024 * 1024)
            val minMb = minBytes.toLong() / (1024 * 1024)
            return Pair(false, "Size too small for claimed quality $tierLabel (${sizeMb}MB < ${minMb}MB)")
        }

        val maxMap = if (isPack) MAX_SIZE_PACK else if (isEp) MAX_SIZE_EPISODE else MAX_SIZE_MOVIE
        val maxBytes = maxMap[qualityTier] ?: 0L
        if (maxBytes > 0 && sizeBytes > maxBytes) {
            val tierLabel = mapOf(4 to "4K", 3 to "1080p", 2 to "720p")[qualityTier] ?: ""
            val sizeGb = sizeBytes / (1024L * 1024 * 1024)
            val maxGb = maxBytes / (1024L * 1024 * 1024)
            return Pair(false, "Size too large for claimed quality $tierLabel (${sizeGb}GB > ${maxGb}GB)")
        }

        return Pair(true, "")
    }

    // -------------------------------------------------------------
    // Full Pre-Download Validator (Layers 1-6)
    // -------------------------------------------------------------
    fun validateTorrent(
        title: String,
        infoHash: String,
        status: String,
        seeders: Int,
        sizeBytes: Long,
        userQuery: String = ""
    ): Pair<Boolean, String> {
        val l1 = checkUploaderTrust(status, seeders)
        if (!l1.first) return Pair(false, "[Layer 1] ${l1.second}")

        val l2 = checkExtensions(title)
        if (!l2.first) return Pair(false, "[Layer 2] ${l2.second}")

        val l3 = checkInfoHash(infoHash)
        if (!l3.first) return Pair(false, "[Layer 3] ${l3.second}")

        val l4 = buildSanitizedMagnet(infoHash, title)
        if (l4.first == null) return Pair(false, "[Layer 4] ${l4.second}")

        val l5 = checkNegativeFilters(title, userQuery)
        if (!l5.first) return Pair(false, "[Layer 5] ${l5.second}")

        val l6 = checkSizePlausible(title, sizeBytes)
        if (!l6.first) return Pair(false, "[Layer 6] ${l6.second}")

        return Pair(true, "")
    }

    // -------------------------------------------------------------
    // Selective-file support: per-file shield checks (Layers 2/6 + traversal)
    // -------------------------------------------------------------

    /** One file inside a torrent as reported by aria2c --show-files. */
    data class TorrentFileEntry(
        val index: Int,          // 1-based aria2c index (what --select-file uses)
        val originalPath: String, // raw path from the swarm (untrusted)
        val length: Long,
        val blocked: Boolean = false,    // Layer 2: blocked extension / hidden exe
        val traversal: Boolean = false,  // path escapes base dir
        val sizeOk: Boolean = true       // Layer 6: plausible for the name
    ) {
        /** Name safe for display: basename only, control chars stripped. */
        val displayName: String
            get() {
                val base = originalPath.substringAfterLast('/').substringAfterLast('\\')
                return SHELL_DANGER.matcher(base).replaceAll("").trim()
                    .ifBlank { "file_$index" }
            }

        /** True when every shield layer passes; only these are offered for selection. */
        val isSafe: Boolean get() = !blocked && !traversal && sizeOk
    }

    private val TRAVERSAL_RE = Pattern.compile("""(?i)(?:\.\./|\.\.\\|^/|^[A-Za-z]:[\\/])""")

    /** Validate one torrent file entry. Blocked entries are never selectable. */
    fun checkTorrentFileEntry(originalPath: String, length: Long, parentTitle: String): TorrentFileEntry {
        val base = originalPath.substringAfterLast('/').substringAfterLast('\\')
        val extCheck = checkExtensions(base)
        val traversal = TRAVERSAL_RE.matcher(originalPath).find()
        val sizeCheck = checkSizePlausible("$parentTitle $base", length)
        return TorrentFileEntry(
            index = 0, // filled by caller
            originalPath = originalPath,
            length = length,
            blocked = !extCheck.first,
            traversal = traversal,
            sizeOk = sizeCheck.first
        )
    }

    // -------------------------------------------------------------
    // LAYER 7: Post-Download Magic-Byte & Path Traversal Inspector
    // -------------------------------------------------------------
    fun validateDownloadedFile(file: File, baseDir: File): Pair<Boolean, String> {
        // Path traversal guard (directory boundary, not string prefix)
        try {
            val canonicalFile = file.canonicalPath
            val canonicalBase = baseDir.canonicalPath
            // Require the separator: "/Download/Anon2/x" starts with "/Download/Anon"
            // but is not inside "/Download/Anon".
            val inside = canonicalFile.startsWith(canonicalBase + File.separator) || canonicalFile == canonicalBase
            if (!inside) {
                return Pair(false, "Path escapes base directory: $canonicalFile is outside $canonicalBase")
            }
        } catch (e: Exception) {
            return Pair(false, "Path validation error: ${e.message}")
        }

        if (!file.exists() || file.length() < 4) {
            return Pair(false, "File too small or missing")
        }

        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(512)
                val bytesRead = raf.read(header)
                if (bytesRead < 4) return Pair(false, "Header too small")

                // Executable detection -> Quarantine/Reject
                if (header[0] == MAGIC_EXE_MZ[0] && header[1] == MAGIC_EXE_MZ[1]) {
                    file.delete()
                    return Pair(false, "BLOCKED: Windows executable (MZ header) disguised as media")
                }
                if (header.copyOfRange(0, 4).contentEquals(MAGIC_ELF)) {
                    file.delete()
                    return Pair(false, "BLOCKED: Linux ELF binary disguised as media")
                }
                if (header[0] == MAGIC_ZIP_PK[0] && header[1] == MAGIC_ZIP_PK[1]) {
                    file.delete()
                    return Pair(false, "BLOCKED: Archive/APK container (PK header) disguised as media")
                }

                // HTML / script / XML / SVG / JSON decoy sniff: strip UTF-8 BOM
                // and check whether the file starts with a text-like prefix.
                var textStart = 0
                if (bytesRead >= 3 && header[0] == 0xEF.toByte() && header[1] == 0xBB.toByte() && header[2] == 0xBF.toByte()) {
                    textStart = 3
                }
                val textHead = String(header, textStart, bytesRead - textStart, Charsets.US_ASCII).trimStart().lowercase()
                if (textHead.startsWith("<!doctype html") || textHead.startsWith("<html") ||
                    textHead.startsWith("<head") || textHead.startsWith("<body") ||
                    textHead.startsWith("<script") || textHead.startsWith("<svg") ||
                    textHead.startsWith("<?xml") || textHead.startsWith("<style") ||
                    textHead.startsWith("<!--") || textHead.startsWith("<iframe") ||
                    textHead.startsWith("<meta") || textHead.startsWith("<form") ||
                    textHead.startsWith("{")) {
                    file.delete()
                    return Pair(false, "BLOCKED: File is an HTML/script/error page, not media")
                }

                // Known safe media containers
                if (header.copyOfRange(0, 4).contentEquals(MAGIC_MKV)) {
                    return Pair(true, "MKV")
                }
                // ISO BMFF / MP4 / MOV / fragmented MP4 (fMP4): check box type at offset 4
                if (bytesRead >= 8) {
                    val boxType = String(header, 4, 4, Charsets.US_ASCII).lowercase()
                    val validMp4Boxes = setOf("ftyp", "moov", "mdat", "free", "wide", "skip", "moof", "styp", "uuid", "pnot")
                    if (boxType in validMp4Boxes) {
                        return Pair(true, "MP4/M4A/MOV/fMP4 ($boxType)")
                    }
                }
                // MPEG-TS Transport Stream sync byte (0x47)
                if (bytesRead >= 1 && header[0] == 0x47.toByte()) {
                    return Pair(true, "MPEG-TS")
                }
                if (textHead.startsWith("riff") && textHead.contains("avi ")) {
                    return Pair(true, "AVI")
                }
                // RIFF/WAVE: "WAVE" form tag sits at offset 8 (same layout as AVI's)
                if (textHead.startsWith("riff") && textHead.length >= 12 && textHead.substring(8, 12) == "wave") {
                    return Pair(true, "WAV")
                }
                // Audio containers the size guard legitimately lets through —
                // without these branches a valid .flac/.ogg/.mp3 fell to the
                // strongMagicExts rejection below (audit finding).
                if (header.copyOfRange(0, 4).contentEquals(MAGIC_OGG)) {
                    return Pair(true, "OGG/Opus")
                }
                if (header.copyOfRange(0, 4).contentEquals(MAGIC_FLAC)) {
                    return Pair(true, "FLAC")
                }
                if (header.copyOfRange(0, 3).contentEquals(MAGIC_ID3)) {
                    return Pair(true, "MP3")
                }

                // If file is > 5MB and passed all text/HTML/script checks, it is a valid binary media file
                if (file.length() >= 5 * 1024 * 1024L) {
                    return Pair(true, "BinaryMedia")
                }

                val ext = file.extension.lowercase()
                // Extensions that carry a mandatory magic signature
                val strongMagicExts = setOf("mkv", "mp4", "avi", "webm", "ogg", "opus", "flac", "wav", "zip", "rar", "7z")
                if (ext in strongMagicExts) {
                    return Pair(false, "Unrecognized file signature for .$ext — expected container header not found")
                }
            }
        } catch (e: Exception) {
            return Pair(false, "Inspection error: ${e.message}")
        }

        return Pair(true, "unknown")
    }
}
