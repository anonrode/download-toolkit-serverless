# Android Download Engine: Complete Post-Refactor Source Code & Architectural Audit Specification

**Target Module:** `com.anonrode.downloader.engine` and supporting pipeline components (`com.anonrode.downloader.pipeline`)  
**Specification Version:** 2.0.0 (Post-Refactoring Architecture Verification)  
**Completeness:** 100% Unabridged Full Production Source Code Included Verbatim  

---

## Section 1: 16-Bug Remediation Matrix & Architectural Diff

| Bug ID | Title & Subsystem | Status | Exact Location in Codebase | Root-Cause Fix & Architecture Adaptation Notes |
|---|---|---|---|---|
| **Bug 1** | Triple-Backend Ping-Pong (`aria2c` -> `Turbo` -> `aria2c`) & Orphaned Partials | `FIXED` | `DownloadEngine.kt` (lines 2435–2530) | Direct HTTP/HTTPS tasks route to `TurboDownloader` as primary backend. If Turbo encounters 401/403/404 or HTML decoy, the link token is refreshed once. On persistent non-429 failures, `handoffTurboPrefixToAria2c` renames the contiguous prefix `.part` to destination media file and invokes `downloadHttpAria2c` once. aria2c is never invoked on HTTP 429. |
| **Bug 2** | `isEmbedOrPage` Dead Code Due to Default `backend = "aria2c"` | `FIXED` | `DownloadEngine.kt` (lines 2305–2325) | Removed `|| task.backend.contains("aria2c")` condition. Unlisted web embed links with default `backend = "aria2c"` now reach `HttpClient.probeTerminal` and route cleanly to `yt-dlp`. |
| **Bug 3** | Watchdog `if (moved < 0)` Math Flaw Killing Healthy ffmpeg Merges | `FIXED` | `ProgressWatchdog.kt` (lines 80–86) & `DownloadEngine.kt` | Added pre-check `if (disk < windowStartDisk || parsed < windowStartParsed)` to reset window baseline before evaluating `maxOf(disk - windowStartDisk, parsed - windowStartParsed)`. Prevents false stall kills during shard deletion. |
| **Bug 4** | Watchdog False-Stall & Double-Counting on Pre-Allocated Files | `FIXED` | `ProgressWatchdog.kt` (lines 40–55) & `TurboDownloader.kt` | Created `computeVerifiedDiskBytes` which reads committed bytes from `TurboState.writtenBytes()` when `.turbo` sidecar exists instead of trusting sparse pre-allocated `.part` file length. |
| **Bug 5** | Watchdog Stall Log Always Printing `idle=0s` | `FIXED` | `ProgressWatchdog.kt` (lines 150–162) | Extracted `val idleSeconds = (now - lastActivity) / 1000L` strictly before assigning `lastActivity = now`. |
| **Bug 6** | TurboDownloader Cross-Mode Resume Truncates Newly Downloaded Bytes | `FIXED` | `TurboDownloader.kt` (lines 530–545) | Calls `state.delete()` immediately upon truncating `.part` to contiguous prefix before entering single-stream mode, eliminating stale `.turbo` truncation on subsequent resumes. |
| **Bug 7** | `fallbackRegex` in `downloadHttpAria2c` Wipes Progress to `0L` | `FIXED` | `YoutubeDlDownloader.kt` (lines 1195–1205, 1408–1417) | Modified fallback matching in both magnet and HTTP paths to preserve `lastDl`, compute proportional progress `(lastTot * pct) / 100L`, and smooth ETA via `etaCalc.update(inferredDl, lastTot, spd)`. Never emits `(0L, 0L, spd, 0L)`. |
| **Bug 8** | `existingPartial` Stem Collision Overwrites Unrelated Files | `FIXED` | `YoutubeDlDownloader.kt` (lines 1290–1300) | Restricted `existingPartial` to exact preferred filename or files with an active `.aria2` control file. Protected `.srt` subtitles and completed media files from accidental overwrite. |
| **Bug 9** | `isFinal(f)` Accepting Directories and Scanning Blindly | `FIXED` | `YoutubeDlDownloader.kt` (lines 631, 1219) | Replaced `(f.length() > 0 || f.isDirectory)` with `f.exists() && f.isFile && f.length() > 0L`. Prevents folders from being returned as media files. |
| **Bug 10** | Native `aria2c` Process Orphaning on Coroutine Cancellation | `FIXED` | `YoutubeDlDownloader.kt` (lines 1165–1175, 1375–1385) | Attached `coroutineContext[Job]?.invokeOnCompletion { if (process.isAlive) process.destroyForcibly() }` and added periodic `coroutineContext.ensureActive()` calls during stdout reading. |
| **Bug 11** | Memory/Map Leakage Across 8 Scattered Maps/Sets | `FIXED` | `TaskRuntimeRegistry.kt` & `DownloadEngine.kt` | Unified all runtime state into `TaskRuntimeRegistry` with per-task `TaskRuntimeEntry`. Eradicated all 8 scattered maps/sets (`activeJobs`, `startingTaskIds`, `retryBypassTasks`, etc.). |
| **Bug 12** | Naive URL String Slicing (`?preview`) Stripping Query Tokens | `FIXED` | `UrlClassifier.kt` (lines 20–35) | Implemented `stripPreviewParam` using OkHttp `HttpUrl.newBuilder().removeAllQueryParameters("preview")`, preserving all authentication tokens. Fixed `isStreamingLink` token segmentation. |
| **Bug 13** | Hardcoded `.mkv` Extension Desynchronizing Database Records | `FIXED` | `DownloadEngine.kt` (lines 2300–2310, 2740–2755) | Pre-download extension resolution syncs database record before backend start; post-download completion block removes sidecars matching both current and original paths. |
| **Bug 14** | Enqueue Uniquification Loop Running O(N^2) Snapshot Scans | `FIXED` | `DownloadEngine.kt` (lines 350–430) | Captured single snapshot of repository tasks, created $O(1)$ reserved paths lookup set, checked physical disk files, and batched queue processing. |
| **Bug 15** | Over 20 Mutable Engine Preferences Accessed Cross-Thread Without `@Volatile` | `FIXED` | `DownloadEngine.kt` (lines 65–100) | Annotated all 23 engine preference fields with `@Volatile`, ensuring visibility across coroutine threads. |
| **Bug 16** | Global `--check-certificate=false` Permissive TLS Security Flaw | `FIXED` | `HostPolicyRegistry.kt` & `YoutubeDlDownloader.kt` (lines 1304–1315) | Restricted permissive TLS to `BROKEN_TLS_INTERMEDIATE_HOSTS` allowlist via `HostPolicyRegistry.allowsPermissiveTlsFallback(url)`. |

---

## Section 2: Test Suite & Build Verification Report

### 2.1 Kotlin Static Verification Pass
- **Verification Harness:** `scripts/verify_kotlin_syntax.py`
- **Scope:** Brace/parenthesis/bracket balance and string/comment awareness across the 45 target Kotlin files in `com.anonrode.downloader.*`.
- **Result:** **45 / 45 Files PASSED (100% clean, 0 syntax/brace errors)**.
- **Compile-verification caveat (honest limitation):** this harness balances delimiters — it is NOT a Kotlin compiler. No local JDK / Android SDK / Gradle wrapper exists on the authoring machine, so a real `kotlinc`/Gradle compile cannot run locally. Compile verification is therefore deferred to the repository's CI (`build-apk.yml`, Temurin 17 + setup-gradle running `gradle :app:testDebugUnitTest` / `assembleRelease`). An independent QA pass audited the changed sources specifically for compile-breaking constructs (smart-cast legality on `var turboResult`, suspend-call sites, imports for `runInterruptible`/`CoroutineStart`/`CancellationException`, `!!` on `String?`, named arguments) and found none.

### 2.2 Automated Simulation Suites (`scratch/test_*.py`)
- **`scratch/test_url_classifier.py`** — includes the Fix 14 cases:
  - `strip_preview_param`: auth tokens preserved; case-insensitive `?PREVIEW=`/`?Preview=` stripped; valueless `&preview`/`&PREVIEW` stripped mid-query; multi-valued preview removed whole.
  - Image extensions (jpg/jpeg/png/webp) classify as direct media; URL-encoded `filename=clip%2Ejpg` resolves via the decoded-query pass.
  - `Manifest.S01E01.720p.mp4` stays DIRECT; `.m3u8`/`.mpd` stay STREAMING.
  - Result: **100% Assertions Passed**.
- **`scratch/test_watchdog_logic.py`**:
  - ffmpeg-merge shrink reset; sparse `.part` committed-bytes reader; idleSeconds ordering.
  - `MAX_STALL_KILLS = 8` budget exhausts exactly at kill #8.
  - `grantRetryBypass` preserves parkCycleCount/firstParkedAtMs (the buggy reset wipes them).
  - Result: **100% Assertions Passed**.
- **`scratch/test_turbo_handoff.py`**:
  - Cross-mode resume truncation + `.turbo` deletion; handoff positive/zero prefix.
  - `SocketTimeoutException`/watchdog socket-reset count as retryable; user pause / full cancel still abort.
  - segmented→single fallback blocked when cancelled or bytes are banked.
  - Result: **100% Assertions Passed**.
- **`scratch/test_probe_terminal.py`**: probeTerminal routing for unlisted embeds and direct media.
  - Result: **100% Assertions Passed**.

### 2.3 Adversarial QA Audit Suite (`scratch/adversarial_qa_audit.py`)
- **Coverage:** 129 automated source-inspection checks: the original 16 bug categories (Phase 3.1–3.3, 50), the master-directive cross-file fixes (Phase 3.4, 24), the post-triage seam fixes (Phase 3.5, 19), the 1DM speed batch + OTA policy plane including the fresh-lens locks (Phase 3.6, 28), and the work-stealing directive batch (WS-S14..S21, 8).
- **Result:** **129 / 129 Checks PASSED (100% clean, zero defects detected)**.

### 2.4 Live Wire Range Probes (`scratch/phase_4_3_live_probes.py`)
- **Policy:** Strict data conservation (<= 1024 bytes per probe with `Range: bytes=0-1023`; total network usage for the entire verification run is kilobytes, far below the 300 MB data budget).
- **Results:**
  - `https://megaplays.se/`: HTTP 200 OK (1024 bytes).
  - `https://asianc.id/`: HTTP 200 OK (1024 bytes).
  - `https://www.w3schools.com/html/mov_bbb.mp4`: HTTP 206 Partial Content (1024 bytes, magic bytes `ftyp`/`mp4`/`moov` verified).

### 2.5 Independent QA Verification (fresh-context agent)
- **Verdict:** all 16 master-directive fixes present and individually sound as implemented; no compile-breaking construct found; the pause/resume unit-test expectations survive the new `CancellationException` rethrow in `downloadPiece`; zero AI attribution repo-wide.
- **Verified green hypotheses:** smart casts on `var turboResult` are legal (local var, never captured/reassigned mid-check); `runOnce` suspend call sites are inside suspend functions; `TaskRuntimeRegistry` lock lifecycle cannot leak (every acquire reaches `releaseStartLock` or `attachJob`); the aria2c-fallback throw reaches the engine's park/fail classifier; `retry()` is corruption-gated.
- **Residual findings:** see Section 11 — all Medium findings were fixed in the post-triage patch; Low items are listed as open items.

### 2.6 Post-Triage Hardening Patch Set (applied after the 4-lens audit)
- retry() preserves `.aria2`-tracked partials and never deletes while another path owns the file.
- Wi-Fi unpark on settings change no longer requires connectivity at toggle time.
- Extension-sync rename is atomic against `enqueueLock` reservations.
- Purge regains the legacy short-URL-extension fallback (subtitle extensions excluded).
- Completion sidecar sweeps skip paths owned by other tasks (both sweeps).
- `updateProgress` can no longer re-stamp `COMPLETED` rows (late-tick inflation).
- Torrent-listing child processes are destroyed on cancellation (both phases).
- A non-`Exception` throwable (Error) can no longer strand a DOWNLOADING row holding a concurrency slot; the dead job repairs it.
- Stale retry ids (removed tasks) no longer mint permanent registry entries.
- `fullyCancelledTaskIds` pruned: finally-clear + flag marking gated on a live job (both cancel paths).
- Watchdog stall kill can no longer overwrite a racing user pause with park/fail.
- `trackCall` re-checks the cancelTask race window.
- Queue status flip is pause-conditional; stranded flips are repaired.

### 2.7 Python-First Validation & 1DM Speed Batch (2026-10-18)
- **Method (user-directed):** every new logic path was SIMULATED IN PYTHON FIRST
  (`scratch/test_probe_logic.py`, `scratch/test_drop_replace.py`, `scratch/test_zero_redo.py`)
  and tuned until green BEFORE any Kotlin changed. The simulations caught four real ordering
  bugs in the draft design (warm-up strike counting; two-strikes latch ordering; strike reset
  on healthy fresh lanes; a budget-exercise scenario that completed before degrading).
- **Simulation results:** probe decision matrix 14/14; drop-and-replace 11/11 (throttled-lane
  win: 24 s vs 274 s; IP-wide throttle bounded to 2 extra connections; 4-drop budget exercised
  exactly); caps/OTA overlay 14/14; O1 12/12; O2 10/10; O3 destructive-sweep selection 5/5.
- **Adversarial suite:** extended to **121 / 121 checks** — Phase 3.6 (28 checks) verifies the
  Kotlin port of the 1DM batch + OTA policy plane against the validated designs, including the
  locks for every fresh-lens audit fix (S11–S13, O5, O6).
- **Payload lockstep proof:** `scripts/encrypt_rules.py` `validate_schema` accepts the shipped
  `scraper_rules.json` (rev 2) and rejects bad values with exact messages
  (`contentPolicy.socketCaps.kissorgrab.com: host->int 1..16`;
  `contentPolicy.bogusField: unknown field`).
- **Kotlin port:** syntax gate 45/45 clean. No local Android toolchain exists on the authoring
  machine, so compilation is CI-verified (build-apk.yml unit tests + emulator gate) on the next
  push/dispatch; a fresh-lens compile-risk audit of the batch is recorded in Section 12.4.
- **Existing suites:** all 7 local simulation suites green (4 original + 3 new) plus the server
  scenario matrix (12/12) below.

---

## Section 3: Data Models & New Modular Components (100% Complete Files)

### 3.1 `DownloadTask.kt` / `Models.kt`
File: `app/src/main/java/com/anonrode/downloader/data/models/Models.kt`

```kotlin
package com.anonrode.downloader.data.models

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    VALIDATING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Serializable
data class ShowCard(
    val title: String,
    val url: String,
    val posterUrl: String = "",
    val site: String,
    val category: String = "Drama",
    val year: String = "",
    val totalEpisodes: Int = 0,
    val tags: List<String> = emptyList(),
    val genres: List<String> = emptyList()
)

@Serializable
data class EpisodeItem(
    val title: String,
    val url: String,
    val episodeNum: Int,
    val site: String,
    val sizeText: String = "",
    val isDownloaded: Boolean = false,
    val mirrorUrls: List<String> = emptyList()
)

@Serializable
data class ShowDetails(
    val show: ShowCard,
    val synopsis: String = "",
    val episodes: List<EpisodeItem> = emptyList()
)

@Serializable
data class DownloadRecipe(
    val directUrl: String,
    val filename: String,
    val headers: Map<String, String> = emptyMap(),
    val backend: String = "aria2c",
    val parallelSockets: Int = 16
)

@Serializable
data class DownloadTask(
    val id: String,
    val showTitle: String,
    val episodeNum: Int,
    val episodeTitle: String,
    val directUrl: String,
    val sourceUrl: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val etaSeconds: Long = 0L,
    val status: TaskStatus = TaskStatus.QUEUED,
    val filePath: String = "",
    val backend: String = "aria2c",
    val parallelSockets: Int = 16,
    val site: String = "",
    val headers: Map<String, String> = emptyMap(),
    val audioOnly: Boolean = false,
    val errorMessage: String? = null,
    /** True only when the USER explicitly paused (pause button/notification).
     *  Every auto-resume path — network reconnect, storage self-heal, host
     *  cooldown — must skip tasks carrying this flag; only an explicit resume
     *  (retry) clears it. System parks (connectivity drop, Wi-Fi gate, storage
     *  limit, host backoff) leave it false so genuinely interrupted downloads
     *  still auto-recover. Persisted like any other field and preserved by
     *  parkForRestore, so a user pause survives full app restarts. */
    val userPaused: Boolean = false,
    val quality: String? = null,
    /** Real stream resolution extracted from the HLS master playlist
     *  (e.g. "1080p", "720p"). Populated during preflight when the master
     *  declares RESOLUTION attributes; null for direct files and unknown. */
    val resolution: String? = null,
    /** Honest warning shown on COMPLETED cards when the file could not be
     *  fully verified (e.g. no recognizable container signature but the
     *  platform decoder accepted it, or size fell short of the HLS
     *  estimate). Null = verified clean. */
    val validationNote: String? = null,
    /** Epoch ms when the task was enqueued. Powers the "Date added" sort
     *  buckets with real clock ages. 0 = task persisted by an older build —
     *  the UI falls back to list-position age for those (the list is
     *  newest-first, so position still orders them correctly). */
    val createdAt: Long = 0L,
    val mirrorUrls: List<String> = emptyList(),
    val selectedMirrorUrl: String = "",
    /** Series page URL this episode belongs to ("" for direct/one-off
     *  downloads). Stamped by the episode drawer at enqueue time; powers the
     *  "Download Remaining (X left)" chip on the Downloads screen. */
    val seriesUrl: String = "",
    /** Total episode count the drawer knew for the series at enqueue time
     *  (0 = unknown: direct downloads or tasks persisted by older builds).
     *  Only a card-level hint — the drawer recomputes the exact un-downloaded
     *  set live from the provider when the chip opens it. */
    val seriesTotal: Int = 0,
    /** Poster art for the Downloads card thumbnail (v6 mock frame 1):
     *  stamped by the episode drawer at enqueue time from the series poster.
     *  "" for direct/social/torrent downloads, which keep the initials tile.
     *  Serialized like any other field; journals persisted by older builds
     *  simply load as "" (initials fallback). */
    val posterUrl: String = ""
)
```

### 3.2 `UrlClassifier.kt`
File: `app/src/main/java/com/anonrode/downloader/pipeline/UrlClassifier.kt`

```kotlin
package com.anonrode.downloader.pipeline

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

object UrlClassifier {

    private val STREAMING_EXTENSIONS = setOf("m3u8", "mpd", "m3u", "ism", "f4m")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val DIRECT_MEDIA_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m4v",
        "mp3", "m4a", "aac", "flac", "opus", "ogg", "wav",
        "jpg", "jpeg", "png", "webp"
    )
    private val STREAMING_QUERY_REGEX = Regex("""(?:^|&)[^=&]*=(?:mpd|dash|hls|m3u8)(?:&|$)""", RegexOption.IGNORE_CASE)

    /**
     * Safely strips the `preview` query parameter ONLY if it is an actual query parameter,
     * preserving all other query parameters (such as auth tokens or signatures).
     */
    fun stripPreviewParam(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return trimmed
        val matchingNames = httpUrl.queryParameterNames.filter { it.equals("preview", ignoreCase = true) }
        if (matchingNames.isEmpty()) return trimmed
        val builder = httpUrl.newBuilder()
        matchingNames.forEach { builder.removeAllQueryParameters(it) }
        return builder.build().toString()
    }

    /**
     * Safely extracts the lowercase hostname without credentials, ports, or path segments.
     */
    fun extractHost(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        trimmed.toHttpUrlOrNull()?.host?.let { return it.lowercase() }
        return try {
            URI(trimmed).host?.lowercase() ?: ""
        } catch (_: Exception) {
            trimmed.substringAfter("://", "")
                .substringAfter('@')
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .lowercase()
        }
    }

    /**
     * Safely extracts `scheme://host[:port]` for Origin headers.
     */
    fun extractOrigin(rawUrl: String): String {
        val httpUrl = rawUrl.trim().toHttpUrlOrNull() ?: return ""
        val defaultPort = (httpUrl.scheme == "http" && httpUrl.port == 80) ||
            (httpUrl.scheme == "https" && httpUrl.port == 443)
        return if (defaultPort) {
            "${httpUrl.scheme}://${httpUrl.host}"
        } else {
            "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
        }
    }

    /**
     * Extracts a clean file extension (2..5 alphanumeric chars) from the URL path or
     * `filename=` query parameter, returning null if none is found.
     */
    fun extractMediaExtension(rawUrl: String): String? {
        val httpUrl = rawUrl.trim().toHttpUrlOrNull()
        val lastSegment = httpUrl?.pathSegments?.lastOrNull { it.isNotBlank() }
            ?: rawUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val pathExt = lastSegment.substringAfterLast('.', "").lowercase()
        if (pathExt in DIRECT_MEDIA_EXTENSIONS) {
            return pathExt
        }
        val encodedQuery = httpUrl?.encodedQuery ?: rawUrl.substringAfter('?', "")
        val decodedQuery = httpUrl?.query ?: encodedQuery
        val filenameRegex = Regex("""filename[*]?=[^&;]+\.([a-zA-Z0-9]{2,5})""")
        // LAST filename= wins: repeated query parameters are resolved last-one-wins
        // by virtually every server-side parser (first-match renamed tasks to a
        // thumbnail's extension on double-filename signed URLs).
        val queryExt = (filenameRegex.findAll(encodedQuery).lastOrNull()
            ?: filenameRegex.findAll(decodedQuery).lastOrNull())
            ?.groupValues?.get(1)?.lowercase()
        return if (queryExt != null && queryExt in DIRECT_MEDIA_EXTENSIONS) queryExt else null
    }

    /**
     * Strict adaptive-streaming manifest check.
     * Fixes the bug where a direct video file named "Manifest.S01E01.mp4" matched `low.contains("manifest")`.
     */
    fun isStreamingLink(rawUrl: String): Boolean {
        val low = rawUrl.trim().lowercase()
        val path = low.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "")

        // If the path explicitly ends with a direct media file extension (.mp4, .mkv, etc.),
        // it is NOT a manifest unless the query string explicitly requests an HLS/DASH format.
        // Images and .ts keep falling through to the path/manifest heuristics below:
        // "/hls/master.jpg" style extension-obfuscated manifests must stay streaming.
        if (ext in DIRECT_MEDIA_EXTENSIONS && ext != "ts" && ext !in IMAGE_EXTENSIONS) {
            val query = low.substringAfter('?', "")
            return STREAMING_QUERY_REGEX.containsMatchIn(query)
        }

        if (ext in STREAMING_EXTENSIONS) return true
        if (path.endsWith("/manifest") || path.contains("/manifest.") || path.contains("/hls/") || path.contains(".m3u8")) {
            return true
        }
        if (low.contains("kickassanime") || low.contains("playlist.php") ||
            low.contains("/api/orion/") || low.contains("stream-proxy")
        ) {
            return true
        }
        val query = low.substringAfter('?', "")
        return STREAMING_QUERY_REGEX.containsMatchIn(query)
    }
}
```

### 3.3 `HostPolicyRegistry.kt`
File: `app/src/main/java/com/anonrode/downloader/pipeline/HostPolicyRegistry.kt`

```kotlin
package com.anonrode.downloader.pipeline

object HostPolicyRegistry {

    val ASIAN_DRAMA_CLUSTER = setOf("asianc", "dramarain", "dramakey", "pluto", "nepu")
    val WESTERN_CLUSTER = setOf("nkiri", "9jarocks", "naijavault", "naijaprey")
    val DEDICATED_ASIAN_SITES = setOf("asianc", "dramarain", "dramakey")
    val SECONDARY_ASIAN_FALLBACK = setOf("nkiri", "9jarocks")

    // Built-in per-host socket caps. Speed-first policy (2026-10, 1DM batch):
    // start wide and let TurboDownloader's 429 ladder + drop-and-replace adapt
    // per connection instead of pre-throttling to 1-4 sockets. OTA-overridable
    // via contentPolicy.socketCaps (see effectiveSocketsForUrl). Clamp + overlay
    // semantics validated in scratch/test_zero_redo.py (section A).
    private val SENSITIVE_SOCKET_CAP_HOSTS = mapOf(
        "kissorgrab.com" to 6,
        "dl.plutomovies.com" to 6,
        "plutomovies.com" to 8,
        "wetafiles.com" to 12,
        "downloadwella.com" to 12,
        "loadedfiles.org" to 12,
        "loadedfiles.net" to 12,
        "loadedfiles.com" to 12,
        "loadedfiles.st" to 12,
        "loadedfiles.to" to 12
    )

    // Explicit allowlist of locker hosts known to omit intermediate CA certificates.
    // Never disable TLS verification globally for untrusted/arbitrary hosts.
    private val BROKEN_TLS_INTERMEDIATE_HOSTS = setOf(
        "wetafiles.com",
        "downloadwella.com",
        "loadedfiles.org",
        "loadedfiles.net",
        "loadedfiles.com",
        "loadedfiles.st",
        "loadedfiles.to",
        "kissorgrab.com",
        "plutomovies.com",
        // Live-verified broken/mismatched CA chains, documented in
        // resolvers/Resolvers.kt (lulacloud.com verify code 20; mixdrop.co).
        "lulacloud.com",
        "mixdrop.co"
    )

    fun effectiveSocketsForUrl(url: String, requestedSockets: Int): Int {
        val clampedRequest = requestedSockets.coerceIn(1, 16)
        if (clampedRequest == 1) return 1
        val host = UrlClassifier.extractHost(url)
        // OTA overlay first (contentPolicy.socketCaps), built-ins second — the
        // overlay can retune any host (including the capped ones) without an
        // APK; removing the section restores these compiled caps. Same
        // read-through precedence style as DynamicRulesManager.resolveReferer.
        val otaCap = com.anonrode.downloader.data.rules.DynamicRulesManager
            .getContentPolicy().socketCaps.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
            ?.coerceIn(1, 16)
        if (otaCap != null) return minOf(otaCap, clampedRequest)
        val matchedCap = SENSITIVE_SOCKET_CAP_HOSTS.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
        return if (matchedCap != null) {
            minOf(matchedCap, clampedRequest)
        } else {
            clampedRequest
        }
    }

    fun allowsPermissiveTlsFallback(url: String): Boolean {
        val host = UrlClassifier.extractHost(url)
        return BROKEN_TLS_INTERMEDIATE_HOSTS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }
}
```

### 3.4 `TaskRuntimeRegistry.kt`
File: `app/src/main/java/com/anonrode/downloader/engine/TaskRuntimeRegistry.kt`

```kotlin
package com.anonrode.downloader.engine

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Consolidates all per-task runtime bookkeeping into a single lifecycle-managed entry,
 * replacing the 8 separate ConcurrentHashMaps/Sets in DownloadEngine and TurboDownloader.
 */
class TaskRuntimeRegistry {

    class TaskRuntimeEntry(val taskId: String) {
        @Volatile var activeJob: Job? = null
        val isStarting = AtomicBoolean(false)
        val retryBypassHealth = AtomicBoolean(false)
        val isFullyCancelled = AtomicBoolean(false)
        val parkCycleCount = AtomicInteger(0)
        val firstParkedAtMs = AtomicLong(0L)
        val requeueNotBeforeMs = AtomicLong(0L)
        val triedFailoverSites: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun resetParkingAndFailoverState() {
            parkCycleCount.set(0)
            firstParkedAtMs.set(0L)
            requeueNotBeforeMs.set(0L)
            triedFailoverSites.clear()
        }
    }

    private val entries = ConcurrentHashMap<String, TaskRuntimeEntry>()

    fun getOrCreate(taskId: String): TaskRuntimeEntry =
        entries.computeIfAbsent(taskId) { TaskRuntimeEntry(it) }

    fun get(taskId: String): TaskRuntimeEntry? = entries[taskId]

    fun getActiveJob(taskId: String): Job? = entries[taskId]?.activeJob

    fun tryAcquireStartLock(taskId: String): Boolean {
        val entry = getOrCreate(taskId)
        val existingJob = entry.activeJob
        if (existingJob != null && existingJob.isActive) return false
        return entry.isStarting.compareAndSet(false, true)
    }

    fun attachJob(taskId: String, job: Job) {
        val entry = getOrCreate(taskId)
        entry.activeJob = job
        entry.isStarting.set(false)
    }

    fun releaseStartLock(taskId: String) {
        entries[taskId]?.isStarting?.set(false)
    }

    fun markForManualRetry(taskId: String) {
        val entry = getOrCreate(taskId)
        entry.isFullyCancelled.set(false)
        entry.retryBypassHealth.set(true)
        entry.resetParkingAndFailoverState()
    }

    /**
     * Stall-kill retry: grants the health-check bypass WITHOUT resetting parking counters,
     * so park/failover backoff state survives repeated watchdog kills.
     */
    fun grantRetryBypass(taskId: String) {
        val entry = getOrCreate(taskId)
        entry.isFullyCancelled.set(false)
        entry.retryBypassHealth.set(true)
    }

    fun consumeRetryBypass(taskId: String): Boolean =
        entries[taskId]?.retryBypassHealth?.getAndSet(false) ?: false

    fun markFullyCancelled(taskId: String) {
        getOrCreate(taskId).isFullyCancelled.set(true)
    }

    fun isFullyCancelled(taskId: String): Boolean =
        entries[taskId]?.isFullyCancelled?.get() ?: false

    /**
     * Called when a task is permanently removed or completed to prevent memory leaks.
     */
    fun remove(taskId: String) {
        entries.remove(taskId)?.activeJob?.cancel()
    }

    /**
     * Terminal cleanup from inside a task's own coroutine: drops the entry WITHOUT
     * cancelling the running job (unlike [remove], which would self-cancel).
     */
    fun forget(taskId: String) {
        entries.remove(taskId)
    }

    fun hasOtherActiveWork(ignoredTaskId: String = ""): Boolean =
        entries.values.any { entry ->
            entry.taskId != ignoredTaskId && (entry.activeJob?.isActive == true || entry.isStarting.get())
        }

    fun activeRunningTaskIds(): Set<String> =
        entries.values.filter { it.activeJob?.isActive == true || it.isStarting.get() }
            .mapTo(HashSet()) { it.taskId }

    fun cancelAndRemove(taskId: String) {
        val entry = entries.remove(taskId)
        entry?.activeJob?.cancel()
    }

    fun isEmpty(): Boolean =
        entries.values.none { it.activeJob?.isActive == true || it.isStarting.get() }
}
```

### 3.5 `ProgressWatchdog.kt`
File: `app/src/main/java/com/anonrode/downloader/engine/ProgressWatchdog.kt`

```kotlin
package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProgressWatchdog(
    private val repository: DownloadRepository,
    private val computeVerifiedDiskBytes: (DownloadTask) -> Long,
    private val onStallKill: suspend (taskId: String, killCount: Int, exhausted: Boolean, stallMessage: String, task: DownloadTask) -> Unit
) {
    companion object {
        private const val CRAWL_WINDOW_BYTES = 64L * 1024 // 64 KiB floor per stall window
        private const val THROTTLE_SESSION_CAP_MS = 15 * 60_000L
        private const val MAX_STALL_KILLS = 8
    }

    fun launchForTask(
        scope: CoroutineScope,
        initialTask: DownloadTask,
        streamUrl: String,
        stallTimeoutSecProvider: () -> Int
    ): Job = scope.launch {
        val initialDisk = computeVerifiedDiskBytes(initialTask)
        val initialParsed = initialTask.downloadedBytes
        var lastDisk = initialDisk
        var lastParsed = initialParsed
        var lastActivity = System.currentTimeMillis()
        var lastKillTime = 0L
        var stallKills = 0
        var throttleFirstSeen = 0L

        var windowStartDisk = initialDisk
        var windowStartParsed = initialParsed
        var windowStartTime = System.currentTimeMillis()
        var bestWindowBps = 0.0
        var lastProgressLog = 0L
        val watchdogStart = System.currentTimeMillis()

        while (isActive) {
            delay(2000L)
            if (!isActive) break
            val t = repository.find(initialTask.id) ?: break
            when (t.status) {
                TaskStatus.DOWNLOADING -> {}
                TaskStatus.RESOLVING -> continue
                else -> break
            }

            val stallTimeoutSec = stallTimeoutSecProvider().coerceAtLeast(15)
            val stallTimeoutMs = stallTimeoutSec * 1000L
            val now = System.currentTimeMillis()

            // BUG FIX: computeVerifiedDiskBytes reads TurboState sidecar for pre-allocated .part files
            val disk = computeVerifiedDiskBytes(t)
            val parsed = t.downloadedBytes
            val diskDelta = disk - lastDisk
            val diskGrew = disk > lastDisk
            val parsedGrew = parsed > lastParsed

            if (diskGrew) lastDisk = disk
            if (parsedGrew) lastParsed = parsed
            if (diskGrew || parsedGrew) {
                lastActivity = now
                throttleFirstSeen = 0L
            }

            if (TurboDownloader.throttleRemainingMs(initialTask.id) > 0L) {
                if (throttleFirstSeen == 0L) throttleFirstSeen = now
                lastActivity = now
            }
            val throttleGrace = throttleFirstSeen > 0L && (now - throttleFirstSeen) <= THROTTLE_SESSION_CAP_MS

            // BUG FIX: Detect shrinking disk footprint (e.g. yt-dlp/ffmpeg deleting .fNNN shards
            // during muxing) or reset parsed counter BEFORE taking maxOf(diskDelta, parsedDelta).
            if (disk < windowStartDisk || parsed < windowStartParsed) {
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                lastActivity = now
            }

            val moved = maxOf(disk - windowStartDisk, parsed - windowStartParsed)

            if (moved > 0 && now - lastProgressLog >= 10_000L) {
                lastProgressLog = now
                com.anonrode.downloader.util.DebugLog.engine(
                    "task=${initialTask.id} progress disk=${disk / 1024}KiB parsed=${parsed / 1024}KiB window=${moved / 1024}KiB"
                )
            }

            // Bridge verified filesystem progress to UI when stdout parser lags
            if (disk > t.downloadedBytes) {
                val safeDisk = if (t.totalBytes > 0L && disk >= t.totalBytes) {
                    t.downloadedBytes
                } else {
                    disk
                }
                if (safeDisk > t.downloadedBytes) {
                    val speed = if (t.speedBytesPerSec > 0.0) {
                        t.speedBytesPerSec
                    } else {
                        diskDelta.coerceAtLeast(0L) * 1000.0 / 2000.0
                    }
                    val eta = if (t.etaSeconds > 0L) {
                        t.etaSeconds
                    } else if (speed > 0.0 && t.totalBytes > safeDisk) {
                        ((t.totalBytes - safeDisk) / speed).toLong().coerceAtLeast(1L)
                    } else {
                        0L
                    }
                    repository.updateProgress(
                        taskId = initialTask.id,
                        downloaded = safeDisk,
                        total = t.totalBytes,
                        speed = speed,
                        eta = eta
                    )
                }
            }

            val windowSecs = ((now - windowStartTime).coerceAtLeast(500L)) / 1000.0
            val windowBps = if (windowSecs > 0.0) moved / windowSecs else 0.0
            if (windowBps > bestWindowBps) bestWindowBps = windowBps

            if (moved >= CRAWL_WINDOW_BYTES) {
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                if (windowBps >= bestWindowBps * 0.5 || bestWindowBps < 1.0 * 1024 * 1024) {
                    stallKills = 0
                }
            }

            val crawlStalled = (now - windowStartTime > stallTimeoutMs) && (moved < CRAWL_WINDOW_BYTES)
            val magnetTask = streamUrl.startsWith("magnet:", ignoreCase = true)
            val attemptBoundary = if (magnetTask) 90_000L else maxOf(stallKills * stallTimeoutMs * 2, stallTimeoutMs)
            val stalledLong = (now - lastActivity) > stallTimeoutMs
            val isStalledOrCrawling = stalledLong || crawlStalled

            if (!magnetTask &&
                !throttleGrace &&
                isStalledOrCrawling &&
                (now - watchdogStart) > attemptBoundary &&
                (stallKills == 0 || (now - lastKillTime) > stallTimeoutMs)
            ) {
                // BUG FIX: Compute idleSeconds BEFORE resetting lastActivity = now!
                val idleSeconds = (now - lastActivity) / 1000L
                stallKills++
                lastKillTime = now
                windowStartDisk = disk
                windowStartParsed = parsed
                windowStartTime = now
                lastActivity = now

                com.anonrode.downloader.util.DebugLog.engine(
                    "task=${initialTask.id} watchdog stall kill #$stallKills (idle=${idleSeconds}s crawl=$crawlStalled window=${moved / 1024}KiB best=${(bestWindowBps / 1024).toInt()}KiB/s)"
                )

                val exhausted = stallKills >= MAX_STALL_KILLS
                val stallMsg = "Download stalled — no progress for ${stallTimeoutSec}s across $stallKills attempts"
                onStallKill(initialTask.id, stallKills, exhausted, stallMsg, t)
                if (exhausted) break
            }
        }
    }
}
```

---

## Section 4: Core Engine Controller (`DownloadEngine.kt` — Complete File)
File: `app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt`

```kotlin
package com.anonrode.downloader.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.pipeline.PipelineError
import com.anonrode.downloader.pipeline.StreamValidator
import com.anonrode.downloader.providers.ProviderRegistry
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.resolvers.isDirectMediaUrl
import com.anonrode.downloader.security.TorrentSecurityShield
import com.anonrode.downloader.service.DownloadService
import com.anonrode.downloader.util.NetworkObserver
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

enum class DramaCountry {
    KOREAN,
    CHINESE,
    JAPANESE,
    THAI,
    TAIWANESE,
    WESTERN,
    UNKNOWN
}

class DownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository,
    private val networkObserver: NetworkObserver = NetworkObserver(context)
) {
    private val ASIAN_DRAMA_CLUSTER = com.anonrode.downloader.pipeline.HostPolicyRegistry.ASIAN_DRAMA_CLUSTER
    private val WESTERN_CLUSTER = com.anonrode.downloader.pipeline.HostPolicyRegistry.WESTERN_CLUSTER
    private val DEDICATED_ASIAN_SITES = com.anonrode.downloader.pipeline.HostPolicyRegistry.DEDICATED_ASIAN_SITES
    // Secondary Asian-failover targets: mixed Western-cluster sites with PROVEN
    // Asian catalogs (coverage probe 2026-09-20: 50 random Asian series,
    // search-only — 9jarocks 98%, nkiri 62%, and a 6/6 rescue rate on titles
    // ALL dedicated sites missed). Reached ONLY by Asian tasks, and tried at
    // EQUAL RANK with Asian-cluster cards: exact title match + HostHealth
    // decide (deliberate choice — no secondary-tier penalty). Western tasks
    // never touch the dedicated sites; that one-way door stays shut.
    private val SECONDARY_ASIAN_FALLBACK = com.anonrode.downloader.pipeline.HostPolicyRegistry.SECONDARY_ASIAN_FALLBACK
    private val YEAR_REGEX = Regex("""\b(19\d\d|20[0-3]\d)\b""")

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runtimeRegistry = TaskRuntimeRegistry()
    private val progressWatchdog by lazy {
        ProgressWatchdog(
            repository = repository,
            computeVerifiedDiskBytes = { t -> computeDiskBytes(t) },
            onStallKill = { taskId, kills, exhausted, stallMsg, t ->
                handleWatchdogStall(taskId, kills, exhausted, stallMsg, t)
            }
        )
    }

    @Volatile var maxConcurrentDownloads: Int = 4
    @Volatile var parallelSocketsPerFile: Int = 16
    @Volatile var defaultQuality: String = "720p"
    @Volatile var autoOrganizeByShow: Boolean = true
    @Volatile var instantSocialDownload: Boolean = false
    @Volatile var wifiOnlyTorrents: Boolean = false
    var downloadTorrentsWifiOnly: Boolean
        get() = wifiOnlyTorrents
        set(value) { wifiOnlyTorrents = value }
    @Volatile var showPostersInResults: Boolean = true
    @Volatile var storageGuardGb: Double = 1.0

    // Tier-A settings (AppSettings-backed): defaults equal the previous
    // hardcoded behavior, so nothing changes until the user opts in.
    @Volatile var stallTimeoutSec: Int = 60
    @Volatile var magnetMaxAttempts: Int = 3
    @Volatile var ytdlpMaxAttempts: Int = 3
    @Volatile var hlsFragmentConcurrency: Int = 16
    @Volatile var globalSpeedLimitKbs: Int = 0          // 0 = unlimited
    @Volatile var torrentPeers: Int = -1                // -1 = auto (RAM tier)
    @Volatile var torrentPrivacyMode: Boolean = false   // qBittorrent anonymous-mode lessons
    @Volatile var wifiOnlyAll: Boolean = false
    @Volatile var clipboardDetect: Boolean = true
    @Volatile var completionNotifications: Boolean = true
    @Volatile var debugLogging: Boolean = false
    @Volatile var logRetentionDays: Int = 7
    // Seal-parity subtitle settings (2026-09-14): yt-dlp extractor downloads
    // fetch subtitles in this language (default English) and embed them in
    // the merged file. Both live in downloader_settings like the rest.
    @Volatile var downloadSubtitles: Boolean = true
    @Volatile var subtitleLanguage: String = "en"
    @Volatile var filterExplicitContent: Boolean = true


    // No byte movement (parsed progress or filesystem bytes) for this long while
    // DOWNLOADING means the backend is hung; the watchdog kills it so the retry
    // wrapper can relaunch, and the task eventually FAILED instead of hanging.
    private val STALL_TIMEOUT_MS: Long
        get() = stallTimeoutSec * 1000L

    /**
     * Set by the UI: called when a torrent's swarm exposes more than one safe
     * file (season packs). Receives the shield-checked file list and must
     * return the selected 1-based aria2c indexes, or null to download the
     * whole torrent. Runs on the engine's IO dispatcher — the UI should
     * bridge it to the main thread (e.g. a Compose dialog).
     */
    var onTorrentFileSelection: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult)? = null

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    init {
        loadPreferences()
        engineScope.launch {
            // Park tasks interrupted by app kill/crash — never re-queue them.
            // The repository restore (initPersistence) already mapped
            // mid-flight statuses to PAUSED before the engine was built, so
            // this is defense in depth for any future init reordering: a
            // reopen must never auto-resume anything, including a task caught
            // mid-VALIDATING (a pause there is a real user pause too, and the
            // file on disk means a manual resume re-validates in seconds).
            val currentTasks = repository.tasks.value
            currentTasks.forEach { t ->
                // QUEUED included: the repository restore parks it too, but a
                // task persisted as QUEUED between enqueue and restore (async
                // persist race) must not reach the network observer below,
                // whose first emission auto-starts every QUEUED task the
                // moment the app opens.
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING ||
                    t.status == TaskStatus.VALIDATING || t.status == TaskStatus.QUEUED) {
                    repository.update(t.id) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true) }
                }
            }
            networkObserver.status.collect { net ->
                if (net.isConnected) {
                    if (lastNetworkTag != null && net.networkTag != null && lastNetworkTag != net.networkTag) {
                        // Network identity changed while still connected (Wi-Fi -> mobile, VPN toggle):
                        // restart active jobs losslessly on the new network instead of letting
                        // their sockets stall against the dead one.
                        runtimeRegistry.activeRunningTaskIds().forEach { id -> pauseForNetwork(id) }
                    }
                    lastNetworkTag = net.networkTag
                    // Auto-resume tasks parked by a connectivity drop.
                    // userPaused tasks are excluded everywhere in this
                    // collector: a pause the user asked for must never be
                    // undone by a network event.
                    repository.tasks.value
                        .filter { it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage == NETWORK_PAUSE_MESSAGE }
                        .forEach { repository.update(it.id) { t ->
                            // Re-check INSIDE the transform (engine-audit P1): the
                            // filter above froze userPaused at snapshot time, so a
                            // pause landing between snapshot and write would be
                            // overwritten and the download would resume anyway.
                            if (t.userPaused || t.status != TaskStatus.PAUSED) t
                            else t.copy(status = TaskStatus.QUEUED, errorMessage = null)
                        } }
                    // Wi-Fi-gated torrents resume only once an actual Wi-Fi network is back
                    repository.tasks.value
                        .filter { it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith("Waiting for Wi-Fi") == true && net.isWifi }
                        .forEach { repository.update(it.id) { t ->
                            if (t.userPaused || t.status != TaskStatus.PAUSED) t
                            else t.copy(status = TaskStatus.QUEUED, errorMessage = null)
                        } }
                    processQueue()
                } else {
                    // Park active jobs so a reconnect resumes them instead of failing them
                    lastNetworkTag = null
                    runtimeRegistry.activeRunningTaskIds().forEach { id -> pauseForNetwork(id) }
                }
            }
        }
        // Storage-guard self-heal: tasks parked by the storage limit have no
        // natural resume trigger (the network collector only matches network
        // messages), so a separate loop re-checks periodically and re-queues
        // them when space frees up instead of leaving the queue stuck until
        // manual resume. Runs in its own coroutine — the collector above never
        // returns, so this must not be sequenced after it.
        engineScope.launch {
            while (true) {
                delay(10_000)
                if (!checkStorageAvailable()) continue
                val parked = repository.tasks.value.filter {
                    it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith("Storage limit reached") == true
                }
                if (parked.isNotEmpty()) {
                    parked.forEach { repository.update(it.id) { t ->
                        if (t.userPaused || t.status != TaskStatus.PAUSED) t
                        else t.copy(status = TaskStatus.QUEUED, errorMessage = null)
                    } }
                    processQueue()
                }
            }
        }
        // Cooldown-parked auto-retry: a task parked because its locker host
        // is in HostHealth backoff re-queues itself once the host recovers —
        // nothing else resumes it (the network collector only matches network
        // messages). In-memory only: an app restart leaves it PAUSED for a
        // manual resume (DownloadRepository restore), which is intended.
        //
        // Give-up cap: a task parked for longer than PARKED_LIFETIME_MS total
        // (measured from its parkedAt timestamp) is FAILED instead of bounced
        // forever — the Suits batch spent 90 minutes orbiting a host that was
        // never coming back. Manual retry always restarts it cleanly.
        // EXEMPTION (user rule 2026-09-14, "if a download ever starts there is
        // no reason to cancel it"): the cap only applies to tasks that NEVER
        // landed a byte. A parked task with bytes on disk is saved progress —
        // it waits out the host instead of being failed and forgotten.
        //
        // Trickle requeue: when MANY tasks are parked on the same host, requeue
        // at most a few per tick. Releasing all 18 at once recreates the exact
        // request storm that triggered the backoff in the first place.
        engineScope.launch {
            while (true) {
                delay(10_000)
                val parked = repository.tasks.value.filter {
                    it.status == TaskStatus.PAUSED && !it.userPaused && it.errorMessage?.startsWith(PARKED_HOST_MESSAGE) == true
                }
                if (parked.isEmpty()) continue
                var requeued = 0
                var anyRequeued = false
                parked.forEach { t ->
                    val entry = runtimeRegistry.get(t.id)
                    val parkedAt = entry?.firstParkedAtMs?.get() ?: 0L
                    if (parkedAt > 0L && System.currentTimeMillis() - parkedAt > PARKED_LIFETIME_MS &&
                        maxOf(t.downloadedBytes, computeDiskBytes(t)) == 0L
                    ) {
                        entry?.resetParkingAndFailoverState()
                        val msg = "Download server stayed unreachable for 30+ minutes — gave up. Tap retry once the site is back."
                        repository.update(t.id) { it.copy(status = TaskStatus.FAILED, errorMessage = msg, speedBytesPerSec = 0.0) }
                        com.anonrode.downloader.util.DebugLog.user("park give-up task=${t.id}")
                        // Terminal FAILED rows drop their registry entry too
                        // (same leak class the COMPLETED path already cleans).
                        // Guarded so an entry a live job still owns (a retry
                        // racing this tick) is never clobbered.
                        if (runtimeRegistry.get(t.id)?.activeJob?.isActive != true) runtimeRegistry.forget(t.id)
                        try {
                            com.anonrode.downloader.service.DownloadService.notifyFailed(
                                context, t.id, t.episodeTitle, msg
                            )
                        } catch (_: Exception) {}
                        return@forEach
                    }
                    // Re-queue floor: parks escalate their wait (see parkFloorMs)
                    // so a repeatedly-rejected host is probed at most once per
                    // window instead of every tick.
                    if (System.currentTimeMillis() < (entry?.requeueNotBeforeMs?.get() ?: 0L)) return@forEach
                    if (com.anonrode.downloader.pipeline.HostHealth.isUsable(t.directUrl)) {
                        // Max 2 requeues per 10s tick: a 18-task batch comes
                        // back over ~90s instead of one thundering herd.
                        if (requeued >= 2) return@forEach
                        requeued++
                        entry?.firstParkedAtMs?.set(0L)
                        entry?.requeueNotBeforeMs?.set(0L)
                        repository.update(t.id) { task ->
                            if (task.userPaused || task.status != TaskStatus.PAUSED) task
                            else task.copy(status = TaskStatus.QUEUED, errorMessage = null)
                        }
                        anyRequeued = true
                    }
                }
                if (anyRequeued) processQueue()
            }
        }
    }

    private fun loadPreferences() {
        val prefs = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        maxConcurrentDownloads = prefs.getInt("pref_max_downloads", 4).coerceIn(1, 6)
        parallelSocketsPerFile = prefs.getInt("pref_parallel_sockets", 16).coerceIn(1, 16)
        defaultQuality = prefs.getString("pref_default_quality", "720p") ?: "720p"
        autoOrganizeByShow = prefs.getBoolean("pref_auto_organize", true)
        instantSocialDownload = prefs.getBoolean("pref_instant_social", false)
        wifiOnlyTorrents = prefs.getBoolean("pref_torrents_wifi_only", false)
        showPostersInResults = prefs.getBoolean("pref_show_posters", true)
        storageGuardGb = prefs.getFloat("pref_storage_guard", 1.0f).toDouble()

        stallTimeoutSec = prefs.getInt("pref_stall_timeout", 60)
        magnetMaxAttempts = prefs.getInt("pref_magnet_retries", 3)
        ytdlpMaxAttempts = prefs.getInt("pref_ytdlp_retries", 3)
        hlsFragmentConcurrency = prefs.getInt("pref_hls_fragments", 16)
        globalSpeedLimitKbs = prefs.getInt("pref_speed_limit_kbs", 0)
        torrentPeers = prefs.getInt("pref_torrent_peers", -1)
        torrentPrivacyMode = prefs.getBoolean("pref_torrent_privacy_mode", false)
        wifiOnlyAll = prefs.getBoolean("pref_wifi_only_all", false)
        clipboardDetect = prefs.getBoolean("pref_clipboard_detect", true)
        completionNotifications = prefs.getBoolean("pref_completion_notifications", true)
        debugLogging = prefs.getBoolean("pref_debug_logging", false)
        logRetentionDays = prefs.getInt("pref_log_retention_days", 7)
        downloadSubtitles = prefs.getBoolean("pref_download_subtitles", true)
        subtitleLanguage = prefs.getString("pref_subtitle_lang", "en") ?: "en"
        filterExplicitContent = prefs.getBoolean("pref_filter_explicit_content", true)
        // Retention applies at startup, not just when the setting changes.
        com.anonrode.downloader.util.DebugLog.configureRetention(logRetentionDays)
        // debugLogging drives DebugLog's TRACE gate — without this call
        // setVerbose had no production caller and trace() (4 call sites,
        // incl. ShadowRunner lifecycle) could never emit. Default OFF; only
        // the user's pref turns it on.
        com.anonrode.downloader.util.DebugLog.setVerbose(debugLogging)
    }

    fun setShowPosters(show: Boolean) {
        this.showPostersInResults = show
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_show_posters", show)
            .apply()
    }

    /** Persists the instant-social toggle so it survives restarts (SocialModal). */
    fun setInstantSocial(enabled: Boolean) {
        this.instantSocialDownload = enabled
        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_instant_social", enabled)
            .apply()
    }

    fun saveAllSettings(
        maxConcurrent: Int,
        parallelSockets: Int,
        quality: String,
        autoOrganize: Boolean,
        storageGuard: Double,
        wifiOnlyTorrents: Boolean,
        instantSocial: Boolean = false,
        showPosters: Boolean = true,
        // Tier-A settings
        stallTimeout: Int = 60,
        magnetRetries: Int = 3,
        ytdlpRetries: Int = 3,
        hlsFragments: Int = 16,
        speedLimit: Int = 0,
        peers: Int = -1,
        privacyMode: Boolean = false,
        wifiAll: Boolean = false,
        clipboard: Boolean = true,
        notifications: Boolean = true,
        debugLog: Boolean = false,
        logRetention: Int = 7,
        downloadSubs: Boolean = true,
        subLang: String = "en",
        filterExplicit: Boolean = true
    ) {
        this.maxConcurrentDownloads = maxConcurrent.coerceIn(1, 6)
        this.parallelSocketsPerFile = parallelSockets.coerceIn(1, 16)
        this.defaultQuality = quality
        this.autoOrganizeByShow = autoOrganize
        this.storageGuardGb = storageGuard
        this.wifiOnlyTorrents = wifiOnlyTorrents
        this.instantSocialDownload = instantSocial
        this.showPostersInResults = showPosters
        this.stallTimeoutSec = stallTimeout
        this.magnetMaxAttempts = magnetRetries
        this.ytdlpMaxAttempts = ytdlpRetries
        this.hlsFragmentConcurrency = hlsFragments
        this.globalSpeedLimitKbs = speedLimit
        this.torrentPeers = peers
        this.torrentPrivacyMode = privacyMode
        this.wifiOnlyAll = wifiAll
        this.clipboardDetect = clipboard
        this.completionNotifications = notifications
        this.debugLogging = debugLog
        this.logRetentionDays = logRetention.coerceIn(1, 90)
        this.downloadSubtitles = downloadSubs
        this.subtitleLanguage = subLang.trim().ifBlank { "en" }
        this.filterExplicitContent = filterExplicit
        com.anonrode.downloader.util.DebugLog.configureRetention(this.logRetentionDays)
        // Verbose gate applies live on save, exactly like retention above.
        com.anonrode.downloader.util.DebugLog.setVerbose(this.debugLogging)

        // Turning a Wi-Fi gate OFF must release the tasks it parked: the network
        // observer only unparks on an actual Wi-Fi reconnect, so without this the
        // "Waiting for Wi-Fi" tasks would wait for a network change that never
        // comes. Deliberately NOT gated on net.isConnected — a toggle flipped while
        // offline must still queue the tasks; processQueue no-ops until the
        // reconnect collector re-runs it.
        repository.tasks.value.filter {
            it.status == TaskStatus.PAUSED && !it.userPaused && (
                (!this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Wi-Fi Only enabled)") ||
                    (!this.wifiOnlyTorrents && !this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)")
                )
        }.forEach { t ->
            repository.update(t.id) { cur ->
                if (cur.userPaused || cur.status != TaskStatus.PAUSED) cur
                else cur.copy(status = TaskStatus.QUEUED, errorMessage = null)
            }
        }

        // A lower limit preempts running tasks NOW (demote back to queue);
        // a higher limit just fills the new slots. This is the only place the
        // user-facing limit change lands, so preemption lives here.
        enforceConcurrencyLimit()

        context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE).edit()
            .putInt("pref_max_downloads", maxConcurrent)
            .putInt("pref_parallel_sockets", parallelSockets)
            .putString("pref_default_quality", quality)
            .putBoolean("pref_auto_organize", autoOrganize)
            .putFloat("pref_storage_guard", storageGuard.toFloat())
            .putBoolean("pref_torrents_wifi_only", wifiOnlyTorrents)
            .putBoolean("pref_instant_social", instantSocial)
            .putBoolean("pref_show_posters", showPosters)
            .putInt("pref_stall_timeout", stallTimeout)
            .putInt("pref_magnet_retries", magnetRetries)
            .putInt("pref_ytdlp_retries", ytdlpRetries)
            .putInt("pref_hls_fragments", hlsFragments)
            .putInt("pref_speed_limit_kbs", speedLimit)
            .putInt("pref_torrent_peers", peers)
            .putBoolean("pref_torrent_privacy_mode", privacyMode)
            .putBoolean("pref_wifi_only_all", wifiAll)
            .putBoolean("pref_clipboard_detect", clipboard)
            .putBoolean("pref_completion_notifications", notifications)
            .putBoolean("pref_debug_logging", debugLog)
            .putInt("pref_log_retention_days", this.logRetentionDays)
            .putBoolean("pref_download_subtitles", this.downloadSubtitles)
            .putString("pref_subtitle_lang", this.subtitleLanguage)
            .putBoolean("pref_filter_explicit_content", this.filterExplicitContent)
            .apply()
    }

    // Dedupe + filename uniquification + add must be ONE atomic unit: two
    // near-simultaneous enqueues of the same URL (double-tap, share intent +
    // clipboard detect) could otherwise both pass the check-then-act gap and
    // queue two tasks writing one .part file.
    private val enqueueLock = Any()

    fun enqueue(
        showTitle: String,
        episodeNum: Int,
        episodeTitle: String,
        sourceUrl: String,
        isDirect: Boolean,
        backend: String = "aria2c",
        parallelSockets: Int = 16,
        audioOnly: Boolean = false,
        site: String = "",
        quality: String? = null,
        /** Search-verify oracle handoff (2026-09-14): a direct URL the
         *  ResultVerifier byte-proved <= PRERESOLVED_MAX_AGE_MS ago for THIS
         *  episode URL. Riding the existing direct-file pipeline (status
         *  DOWNLOADING immediately, no RESOLVING round-trip) is exactly how
         *  pasted/share links already work — no new engine branch. sourceUrl
         *  stays the episode page, so the 401/403/404/HTML self-heal
         *  re-resolves from scratch if this token aged out between proof and
         *  tap. Null = the normal path, unchanged. */
        verifiedDirectUrl: String? = null,
        mirrorUrls: List<String> = emptyList(),
        /** Track 4 ("Download Remaining"): the series page the episode was
         *  scraped from and the drawer's total episode count. Stamped on the
         *  task so the Downloads screen can offer a one-tap "grab the rest"
         *  without re-scraping. Defaults keep every other caller (direct
         *  pastes, playlists, subtitles) unchanged. */
        seriesUrl: String = "",
        seriesTotal: Int = 0,
        /** Downloads-card thumbnail art (v6 mock frame 1); "" keeps the
         *  initials tile. Callers without artwork just keep the default. */
        posterUrl: String = "",
        triggerQueueProcessing: Boolean = true
    ): String {
        val downloadFolder = getDownloadDirectory(showTitle, createDirs = false)
        var retryTaskId: String? = null

        val taskId = synchronized(enqueueLock) {
            val currentTasks = repository.snapshot()
            val activeStatuses = setOf(
                TaskStatus.QUEUED,
                TaskStatus.RESOLVING,
                TaskStatus.DOWNLOADING,
                TaskStatus.VALIDATING
            )
            val existing = currentTasks.firstOrNull { it.sourceUrl == sourceUrl && it.status in activeStatuses }
            if (existing != null) {
                return@synchronized existing.id
            }

            // A PAUSED record is resumable, not a duplicate: re-enqueueing an
            // episode whose card shows Paused must resume it (the old dedupe
            // returned the existing id and did nothing, so the tap looked dead).
            val resumableExisting = currentTasks.firstOrNull {
                it.sourceUrl == sourceUrl && (it.status == TaskStatus.FAILED || it.status == TaskStatus.PAUSED)
            }
            if (resumableExisting != null) {
                retryTaskId = resumableExisting.id
                return@synchronized resumableExisting.id
            }

            val cleanTitle = sanitizeComponent(episodeTitle, 80, stripNoise = !isDirect)
            val candidateUrlForExt = verifiedDirectUrl ?: sourceUrl
            val detectedExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(candidateUrlForExt)
            val ext = when {
                audioOnly -> "mp3"
                detectedExt != null -> detectedExt
                backend.contains("yt") || !isDirect -> "mp4"
                else -> "mp4" // Default to .mp4 instead of .mkv when extension is unknown
            }

            // O(1) HashSet lookup + physical disk existence check so we never overwrite existing files
            val reservedPaths = currentTasks.mapTo(HashSet(currentTasks.size * 2)) { it.filePath }
            var targetFile = File(downloadFolder, "$cleanTitle.$ext")
            var counter = 2
            while (
                targetFile.absolutePath in reservedPaths ||
                targetFile.exists() ||
                File(targetFile.absolutePath + ".part").exists() ||
                File(targetFile.absolutePath + ".aria2").exists()
            ) {
                targetFile = File(downloadFolder, "$cleanTitle-$counter.$ext")
                counter++
            }

            val resolvedDirectUrl = if (
                !isDirect &&
                verifiedDirectUrl != null &&
                isDirectMediaUrl(verifiedDirectUrl) &&
                !isKnownLockerHost(verifiedDirectUrl)
            ) verifiedDirectUrl else sourceUrl

            val task = DownloadTask(
                id = UUID.randomUUID().toString(),
                showTitle = showTitle,
                episodeNum = episodeNum,
                episodeTitle = episodeTitle,
                directUrl = resolvedDirectUrl,
                sourceUrl = sourceUrl,
                filePath = targetFile.absolutePath,
                status = TaskStatus.QUEUED,
                downloadedBytes = 0L,
                totalBytes = 0L,
                speedBytesPerSec = 0.0,
                etaSeconds = 0L,
                backend = backend,
                parallelSockets = parallelSockets,
                site = site,
                audioOnly = audioOnly,
                quality = quality,
                createdAt = System.currentTimeMillis(),
                mirrorUrls = MirrorPool.sources(sourceUrl, mirrorUrls).filter { it != sourceUrl },
                seriesUrl = seriesUrl,
                seriesTotal = seriesTotal,
                posterUrl = posterUrl
            )

            repository.addFirst(task)
            task.id
        }

        if (retryTaskId != null) {
            retry(retryTaskId!!, processNow = triggerQueueProcessing)
        } else if (triggerQueueProcessing) {
            processQueue()
        }
        return taskId
    }

    /**
     * YouTube playlist batch (2026-09-15): enqueue the selected entries as ONE
     * group — showTitle = playlist title, episodeNum = position in the list —
     * so the Downloads group-by-show ordering and the player's episode-ordered
     * Next queue (DownloadsSorter.EPISODE_ORDER / playerQueueFor) walk the
     * playlist like a series. That grouping is the structural edge over Seal,
     * whose playlist tasks are loose singles. Each entry is a normal yt-dlp
     * extractor task: subtitles, quality, throttle-survival (round 4) and the
     * active-URL dedupe in enqueue() all apply per item, and a failed entry
     * never touches the others. Returns how many were queued.
     */
    fun enqueuePlaylist(
        meta: com.anonrode.downloader.pipeline.PlaylistPicker.PlaylistMeta,
        indices: List<Int>,
        audioOnly: Boolean,
        quality: String?
    ): Int {
        var queued = 0
        for (idx in indices) {
            val entry = meta.entries.getOrNull(idx - 1) ?: continue
            enqueue(
                showTitle = meta.title.ifBlank { "YouTube Playlist" },
                episodeNum = idx,
                episodeTitle = entry.title,
                sourceUrl = entry.watchUrl,
                isDirect = false,
                backend = "yt-dlp",
                parallelSockets = parallelSocketsPerFile,
                audioOnly = audioOnly,
                site = "youtube",
                quality = quality,
                triggerQueueProcessing = false
            )
            queued++
        }
        if (queued > 0) {
            processQueue()
        }
        return queued
    }


    fun pause(taskId: String) {
        val entry = runtimeRegistry.get(taskId)
        entry?.activeJob?.cancel()
        entry?.activeJob = null
        YoutubeDlDownloader.killProcess(taskId)
        // Turbo runs on blocking OkHttp calls that coroutine cancellation does
        // not interrupt, and resolver HTTP calls are plain blocking executes:
        // without these, a paused task kept draining mobile data until the app
        // was killed (user-reported).
        TurboDownloader.cancelTask(taskId)
        // A task paused mid-RESOLVING/mid-probe still has blocking HTTP
        // registered in HttpClient.inFlightCalls that coroutine cancellation
        // cannot interrupt. Sweep exactly like cancel() when it is safe —
        // with other tasks running their requests must stay untouched (the
        // cross-talk rule); the stragglers then die on their read timeout.
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        com.anonrode.downloader.util.DebugLog.user("pause $taskId")
        // userPaused=true is the authoritative "the user asked for this pause"
        // mark: every auto-resume path (network reconnect, storage self-heal,
        // host cooldown) refuses to re-queue a task carrying it, so the pause
        // survives flaky networks AND full app restarts until an explicit
        // resume. errorMessage is cleared as a second layer — a stale
        // NETWORK_PAUSE_MESSAGE from an earlier blip must not outlive the
        // user's pause and let the reconnect handler re-queue the task (the
        // marker overwrite race that resurrected a paused download ~3s after
        // pause, live-verified in app-2026-08-27.txt).
        repository.update(taskId) { it.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true) }
        updateServiceState(force = true)
        processQueue()
    }

    /**
     * Concurrency preemption: applied when maxConcurrentDownloads CHANGES.
     * Lowering the limit takes effect IMMEDIATELY — running tasks beyond the
     * new limit are demoted back to QUEUED (killing their backends exactly
     * like pause does) and auto-resume when slots free up. The old behavior
     * (limit only gates NEW starts) is what let the user watch 2-3 downloads
     * run after setting the limit to 1 — the log-14 concurrency complaint.
     * Raising the limit just fills the new slots via processQueue.
     *
     * Demotion writes QUEUED, never PAUSED, so the task flows back through
     * the queue in order; the job's CancellationException rescue only rewrites
     * PAUSED over DOWNLOADING/RESOLVING/VALIDATING statuses, and repository
     * updates are atomic per write, so a rescue racing this demotion can never
     * strand the task in a stuck state: either the rescue lands first (PAUSED,
     * overwritten by this QUEUED) or this lands first (QUEUED, rescue no-ops).
     */
    private fun hasOtherActiveWork(ignoredTaskId: String): Boolean {
        return runtimeRegistry.hasOtherActiveWork(ignoredTaskId)
    }

    private fun enforceConcurrencyLimit() {
        val actives = repository.tasks.value.filter {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
        }
        if (actives.size <= maxConcurrentDownloads) {
            processQueue()
            return
        }
        // Keep the FIRST tasks in list order — and list order is NEWEST-FIRST
        // (DownloadRepository.addFirst prepends), so this keeps the MOST
        // RECENTLY enqueued/active tasks and demotes the OLDEST back to QUEUED.
        // processQueue then picks the newest QUEUED again (firstOrNull below),
        // so the queue is LIFO by design: the tap the user made most recently
        // starts next, matching the newest-first Downloads list on screen.
        actives.drop(maxConcurrentDownloads).forEach { t ->
            com.anonrode.downloader.util.DebugLog.user(
                "concurrency limit now $maxConcurrentDownloads — demoting task=${t.id} (${t.episodeTitle.take(40)}) back to queue"
            )
            val entry = runtimeRegistry.get(t.id)
            entry?.activeJob?.cancel()
            entry?.activeJob = null
            YoutubeDlDownloader.killProcess(t.id)
            TurboDownloader.cancelTask(t.id)
            repository.update(t.id) { task ->
                // Only demote a task that is STILL active (engine-audit P2):
                // the `actives` list is a snapshot, and a task whose completion
                // landed in the window must not be re-queued — that re-downloaded
                // a finished file over the same path on metered data.
                if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.RESOLVING ||
                    task.status == TaskStatus.VALIDATING
                ) task.copy(status = TaskStatus.QUEUED, speedBytesPerSec = 0.0, errorMessage = null)
                else task
            }
        }
        updateServiceState(force = true)
        processQueue()
    }

    private fun pauseForNetwork(taskId: String) {
        val task = repository.find(taskId) ?: return
        if (task.status != TaskStatus.DOWNLOADING && task.status != TaskStatus.RESOLVING) return
        val entry = runtimeRegistry.get(taskId)
        entry?.activeJob?.cancel()
        entry?.activeJob = null
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        // Same in-flight sweep as pause(): a network park mid-resolve must
        // not leave blocking HTTP draining data until its read timeout.
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        // Atomic re-check inside the StateFlow transform: the find()/status
        // guard above is a non-atomic snapshot, and on a flaky network a
        // disconnect-triggered park races a user pause() — its marker write
        // could land AFTER pause() had cleared errorMessage and set
        // userPaused, leaving NETWORK_PAUSE_MESSAGE behind so the next
        // reconnect re-queued the task the user just paused. Inside the
        // transform the check is CAS-atomic against every other repository
        // write: a user-paused or already-parked task makes it a no-op.
        repository.update(taskId) { t ->
            if (t.userPaused || (t.status != TaskStatus.DOWNLOADING && t.status != TaskStatus.RESOLVING)) t
            else t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = NETWORK_PAUSE_MESSAGE)
        }
        updateServiceState(force = true)
    }

    fun cancel(taskId: String) {
        val task = repository.find(taskId)
        // Mark full-cancel intent BEFORE the job cancellation: the job's
        // CancellationException handler kills this task's in-flight resolver
        // HTTP based on this mark. Doing it here (instead of a global
        // cancelInFlight in this method) keeps OTHER tasks' in-flight
        // requests untouched — the cross-talk bug.
        val hasRunningJob = runtimeRegistry.getActiveJob(taskId) != null
        if (hasRunningJob) runtimeRegistry.markFullyCancelled(taskId)
        runtimeRegistry.cancelAndRemove(taskId)
        YoutubeDlDownloader.killProcess(taskId)
        // Full-cancel marking only matters when a Turbo run is unwinding; a queued
        // task has none, and the flag would otherwise linger in the set forever.
        TurboDownloader.cancelTask(taskId, isFullCancel = hasRunningJob)
        // Kill this task's own in-flight resolver HTTP immediately when it is
        // safe: with no other running task there is nothing to cross-talk
        // into. Otherwise the job's cancellation handler sweeps once this
        // task's current blocking call unwinds (bounded by the read timeout).
        if (!hasOtherActiveWork(taskId)) HttpClient.cancelInFlight()
        com.anonrode.downloader.util.DebugLog.user("cancel $taskId")
        if (task != null) {
            engineScope.launch {
                purgeTaskArtifacts(task)
            }
        }
        repository.remove(taskId)
        updateServiceState(force = true)
        processQueue()
    }


    /** Remove every partial artifact a cancelled download may have left.
     *  deleteRecursively: multi-file torrents write a DIRECTORY, and File.delete()
     *  silently refuses non-empty dirs — canceled season torrents left their
     *  preallocated gigabytes behind (user-reported 2026-08-22: 8GB+ stuck after
     *  cancel of a magnet with a dead swarm). The `.work-<taskId>` extractor
     *  dir is the same hazard reintroduced: yt-dlp's `.f140.m4a`/`.f135.mp4`
     *  shards live THERE, not at filePath, and were named after nothing the old
     *  sweep matched — a cancelled 2 GiB YouTube job stranded the whole partial
     *  set in the user's visible folder (audit 2026-09-13). */
    private fun purgeTaskArtifacts(task: DownloadTask) {
        try {
            val target = File(task.filePath)
            // The Turbo→aria2c handoff and the aria2c→Turbo rescue write a
            // URL-extension sibling ("Ep 1.mkv" task, "Ep 1.mp4" artifact +
            // its .aria2/.part/.turbo) — engine-audit P2: purging only the
            // task's own extension family left those partial bytes in the
            // user's folder (the "8 GB left after cancel" shape).
            // Legacy handoff artifacts were named after the URL's raw extension
            // (download.php?token=... → "Ep 1.php" + .aria2). The classifier only
            // knows media extensions, so fall back to the old short-alnum URL
            // extension for those — subtitle/aux extensions stay excluded so a
            // cancel can never purge a sibling .srt family.
            val legacyExt = task.directUrl.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').substringAfterLast('.', "")
            val legacySafe = legacyExt.isNotBlank() && legacyExt.length <= 5 &&
                legacyExt.all { it.isLetterOrDigit() } &&
                legacyExt.lowercase() !in setOf("srt", "vtt", "ass", "ssa", "sub")
            val urlExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(task.directUrl)
                ?: (if (legacySafe) legacyExt else "")
            val sibling = if (urlExt.isNotBlank()) {
                File(target.parentFile, "${target.nameWithoutExtension}.$urlExt")
                    .takeIf { it.absolutePath != target.absolutePath }
            } else null
            // Never touch a path that belongs to a DIFFERENT task: the sibling
            // family can coincide with a sibling task's real target name.
            val otherTargets = repository.tasks.value
                .filter { it.id != task.id }
                .map { it.filePath }
                .toSet()
            val bases = listOfNotNull(target, sibling).filter { it.absolutePath !in otherTargets }
            for (base in bases) {
                base.deleteRecursively()
                File(base.absolutePath + ".part").delete()
                File(base.absolutePath + ".ytdl").delete()
                File(base.absolutePath + ".aria2").delete()
                File(base.absolutePath + ".turbo").delete()
            }
            target.parentFile?.let { parent ->
                File(parent, ".work-${task.id}").deleteRecursively()

                // When a download is cancelled or deleted, if the enclosing show folder
                // is left empty (e.g. 1-episode series, standalone movie, or last remaining episode),
                // delete the folder so empty directory trees do not clutter user storage.
                //
                // Safety guards:
                // 1. Strictly verify the folder is a subfolder inside Downloads/Anon, NEVER Anon itself or Downloads root.
                // 2. Verify no other task in repository (active, paused, completed) targets this folder.
                // 3. File.delete() is atomic and POSIX-safe: it strictly succeeds ONLY if the folder is completely empty.
                try {
                    val rootAnon = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ),
                        "Anon"
                    )
                    val parentCanon = parent.canonicalFile
                    val anonCanon = rootAnon.canonicalFile
                    val isSubdirOfAnon = parentCanon.absolutePath != anonCanon.absolutePath &&
                        parentCanon.absolutePath.startsWith(anonCanon.absolutePath + File.separator)

                    if (isSubdirOfAnon) {
                        val otherTasksInFolder = repository.tasks.value.any { other ->
                            other.id != task.id && try {
                                File(other.filePath).canonicalFile.parentFile?.absolutePath == parentCanon.absolutePath
                            } catch (_: Throwable) {
                                File(other.filePath).parentFile?.absolutePath == parent.absolutePath
                            }
                        }
                        if (!otherTasksInFolder) {
                            parentCanon.listFiles()?.filter { it.isDirectory && it.name.startsWith(".work-") }?.forEach {
                                try { it.deleteRecursively() } catch (_: Throwable) {}
                            }
                            parentCanon.delete()
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun retry(taskId: String, processNow: Boolean = true) {
        // A stale id (task already removed, e.g. a notification retry after the
        // task was cancelled) must not mint a registry entry that nothing can
        // ever remove.
        val existingTask = repository.find(taskId) ?: return
        try {
            val target = File(existingTask.filePath)
            val isImg = target.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
            val isCorrupt = if (isImg) {
                target.length() < 1024L || looksLikeHtml(target) || !isImageValid(target)
            } else {
                target.length() < 50 * 1024L || looksLikeHtml(target) || !decodeCheck(target)
            }
            // An .aria2 control file marks an aria2c partial that resume will
            // continue (-c): decodeCheck fails on any truncated container, so
            // deleting here destroyed multi-GB banked progress on every
            // resume (and orphaned the control file).
            val hasAria2Partial = File(existingTask.filePath + ".aria2").exists()
            // Never delete while another path owns the file right now: an active
            // run (or a just-completed row) has the artifact under management,
            // and this check races the completion write.
            val liveStatus = repository.find(taskId)?.status
            val activelyOwned = liveStatus == TaskStatus.RESOLVING || liveStatus == TaskStatus.DOWNLOADING ||
                liveStatus == TaskStatus.VALIDATING || liveStatus == TaskStatus.COMPLETED
            if (target.exists() && isCorrupt && !hasAria2Partial && !activelyOwned) {
                target.delete()
            }
        } catch (_: Throwable) {}

        // Each tap grants ONE bypass of the host health gate: the next
        // resolution attempt runs even while the host sits in HostHealth
        // backoff (consumed in startTask). Bounded so stale ids cannot grow.
        runtimeRegistry.markForManualRetry(taskId)
        // Explicit resume/retry clears the user-pause mark — this is the ONLY
        // path allowed to undo a user pause (UI resume button and the
        // notification retry action both funnel through here).
        repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
        if (processNow) processQueue()
    }

    /**
     * Cross-provider auto-failover: the task's source site could not serve the
     * title (resolver chain empty, gateway dead, or resolution timed out).
     * Search the OTHER providers for the same title and rewrite this task onto
     * a working site's source, then requeue it — the user tapped a download
     * and should GET the download, not a lesson about which site is broken.
     *
     * Bounds: every site already tried for this task is excluded, so the loop
     * always walks new ground and cannot ping-pong between dead providers;
     * a task may be failed over at most 3 times, then it fails with an honest
     * error. In-memory only, like the park-cycle counter.
     *
     * Series are matched per-episode (loadEpisodes on the candidate show, pick
     * the episode with the same number) so a Suits episode lands on the same
     * episode, not a random one. Movies take the matched show card directly.
     *
     * Returns true when the task was re-pointed at a new source and requeued —
     * the caller must then EXIT its coroutine (the task no longer belongs to
     * this job); false means "no alternative found", and the caller proceeds
     * with the normal park/fail path.
     */
    private suspend fun attemptCrossProviderFailover(task: DownloadTask): Boolean {
        // Progress Banking Guard: failover MUST only run if task.downloadedBytes == 0L && bytesLanded(task) == 0L
        if (task.downloadedBytes > 0L || bytesLanded(task) > 0L) {
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=${task.id} failover: skipped because task already banked ${bytesLanded(task)} bytes"
            )
            return false
        }
        if (task.site.isBlank()) return false
        if (task.directUrl.startsWith("magnet:", ignoreCase = true)) return false
        val tried = runtimeRegistry.getOrCreate(task.id).triedFailoverSites
        if (!tried.add(task.site)) return false // already failed over FROM this site once
        if (tried.size > 3) {
            tried.clear()
            return false
        }
        val query = task.showTitle.ifBlank { task.episodeTitle }
        if (query.isBlank()) return false

        val isAsian = isAsianDramaContext(task)
        val origYear = extractYear(task.showTitle)
            ?: extractYear(task.episodeTitle)
            ?: extractYear(task.sourceUrl)
        val origCountry = extractCountry("${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}", task.site)
        val (origSeason, origEpNum) = parseSeasonAndEpisode(task)

        com.anonrode.downloader.util.DebugLog.resolve(
            "task=${task.id} failover: '${task.site}' cannot serve \"$query\" (isAsian=$isAsian, year=$origYear, country=$origCountry, S${origSeason}E${origEpNum}) — searching other providers"
        )

        // 4-Layer "Suits" Remake Guard - Layer 1: Genre / Cluster Boundary Guard
        // If isAsian: candidates filtered to ASIAN_DRAMA_CLUSTER plus the proven
        // mixed-site secondaries (nkiri/9jarocks). Every candidate still faces
        // Layers 2-4 (year, country tag, season/episode) below.
        // If not isAsian: candidates filtered to WESTERN_CLUSTER (must not query DEDICATED_ASIAN_SITES).
        val allowedCluster = if (isAsian) ASIAN_DRAMA_CLUSTER + SECONDARY_ASIAN_FALLBACK else WESTERN_CLUSTER

        // Search eligible providers in parallel, each bounded; skip hosts
        // currently in backoff so a failover never lands on another dead site.
        val candidates = kotlinx.coroutines.withTimeoutOrNull(25_000L) {
            coroutineScope {
                ProviderRegistry.allProviders
                    .filter { provider ->
                        val pName = provider.name.lowercase()
                        provider.searchEnabled &&
                            pName in allowedCluster &&
                            (isAsian || pName !in DEDICATED_ASIAN_SITES) &&
                            tried.none { it.equals(provider.name, ignoreCase = true) }
                    }
                    .map { provider ->
                        async(Dispatchers.IO) {
                            try {
                                withTimeoutOrNull(20_000L) { provider.search(query) } ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }
        } ?: emptyList()

        val normQuery = normalizeTitleQuery(query)
        val ranked = candidates
            .asSequence()
            .filter { card ->
                val candSite = card.site.lowercase()
                // Layer 1: Genre / Cluster Boundary Guard on candidate card
                val clusterMatch = if (isAsian) {
                    candSite in ASIAN_DRAMA_CLUSTER || candSite in SECONDARY_ASIAN_FALLBACK
                } else {
                    candSite in WESTERN_CLUSTER && candSite !in DEDICATED_ASIAN_SITES
                }
                if (!clusterMatch) return@filter false

                if (card.url.isBlank()) return@filter false
                if (tried.any { it.equals(card.site, ignoreCase = true) }) return@filter false
                if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(card.url)) return@filter false
                if (!titleMatches(query, card.title)) return@filter false

                // Layer 2: Release Year Matching with ±1 tolerance.
                // Streaming sites label the SAME show off-by-one (original
                // production year vs international premiere): asianc serves
                // "the-promised-neverland-2020" while 9jaRocks lists
                // "The Promised Neverland (2019)" — a hard mismatch reject
                // silently discarded the user's working source and pushed the
                // task onto a failing one (journal 10-15 20:25). ±1 years is
                // tight enough that implausible cross-show matches still lose
                // on season/title guards above and below.
                val candYear = extractYear(card.year)
                    ?: extractYear(card.title)
                    ?: extractYear(card.url)
                if (origYear != null && candYear != null && kotlin.math.abs(origYear - candYear) > 1) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" due to year mismatch (orig=$origYear, cand=$candYear)"
                    )
                    return@filter false
                }

                // Layer 3: Country Tag Matching.
                // Mixed-site secondaries (nkiri/9jarocks) must prove country by
                // EXPLICIT tag only: extractCountry's site-default WESTERN would
                // auto-reject an untagged K-drama card on nkiri — the exact
                // content this failover exists to rescue. No explicit tag means
                // UNKNOWN here, and UNKNOWN never rejects.
                val candCountry = extractFailoverCountry(isAsian, "${card.title} ${card.category}", card.site)
                if (origCountry != DramaCountry.UNKNOWN && candCountry != DramaCountry.UNKNOWN && origCountry != candCountry) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" due to country mismatch (orig=$origCountry, cand=$candCountry)"
                    )
                    return@filter false
                }

                // Season guard on title
                if (!seasonConsistent(query, card.title, if (origEpNum > 0) origSeason else null)) {
                    return@filter false
                }

                // Layer 4 pre-check: if candidate specifies totalEpisodes, reject if less than origEpNum
                if (origEpNum > 0 && card.totalEpisodes > 0 && card.totalEpisodes < origEpNum) {
                    com.anonrode.downloader.util.DebugLog.resolve(
                        "task=${task.id} failover: rejected card \"${card.title}\" totalEpisodes (${card.totalEpisodes}) < origEpNum ($origEpNum)"
                    )
                    return@filter false
                }

                true
            }
            // Exact title match first, then a provider whose host has proven
            // itself in the ledger, then country agreement (Asian tasks: an
            // explicitly-tagged same-country card is probed before an
            // untagged one), then everything else.
            val agreeWith: Map<com.anonrode.downloader.data.models.ShowCard, Boolean> =
                ranked.associateWith { card ->
                    isAsian && origCountry != DramaCountry.UNKNOWN &&
                        extractFailoverCountry(true, "${card.title} ${card.category}", card.site) == origCountry
                }
            val ordered = ranked
            .sortedWith(
                compareByDescending<com.anonrode.downloader.data.models.ShowCard> { normalizeTitleQuery(it.title) == normQuery }
                    .thenByDescending { com.anonrode.downloader.pipeline.HostHealth.hasProvenLocker(it.url) }
                    .thenByDescending { agreeWith[it] == true }
            )
            .toList()

        // Hard deadline for the whole failover (episode lookups included).
        val failoverDeadline = System.currentTimeMillis() + 60_000L
        for (card in ordered) {
            if (System.currentTimeMillis() > failoverDeadline) break
            var newMirrors = emptyList<String>()
            val newUrl: String? = if (origEpNum > 0) {
                // Layer 4: Season & Episode Boundary Verification
                try {
                    val details = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        ProviderRegistry.loadEpisodes(card)
                    } ?: continue

                    // Total episodes check: candidate with fewer episodes than origEpNum is rejected
                    if (details.episodes.size < origEpNum) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: rejected \"${card.title}\" loaded episodes (${details.episodes.size}) < origEpNum ($origEpNum)"
                        )
                        continue
                    }

                    // Candidate must have matching (origSeason, origEpNum)
                    val origIsExplicitSeries = origEpNum > 0 && (task.episodeTitle.contains("Episode", ignoreCase = true) ||
                        task.episodeTitle.contains("Ep", ignoreCase = true) ||
                        task.episodeTitle.contains("Season", ignoreCase = true) ||
                        task.sourceUrl.isNotBlank())
                    val candIsMovie = card.category.equals("Movies", ignoreCase = true) || card.title.contains("Movie", ignoreCase = true)
                    if (origIsExplicitSeries && candIsMovie && details.episodes.size == 1) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: rejected movie card \"${card.title}\" for series task S${origSeason}E${origEpNum}"
                        )
                        continue
                    }
                    val isSingleMovie = details.episodes.size == 1 && origEpNum <= 1 && origSeason == 1 && !origIsExplicitSeries
                    val matchingEp = details.episodes.firstOrNull { candEp ->
                        if (isSingleMovie) true
                        else {
                            val (candS, candE) = parseCandidateSeasonAndEpisode(card, candEp)
                            candS == origSeason && candE == origEpNum
                        }
                    }
                    if (matchingEp == null) {
                        com.anonrode.downloader.util.DebugLog.resolve(
                            "task=${task.id} failover: no episode matching S${origSeason}E${origEpNum} in \"${card.title}\""
                        )
                        continue
                    }

                    if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(matchingEp.url)) continue
                    newMirrors = matchingEp.mirrorUrls
                    matchingEp.url
                } catch (_: Exception) {
                    continue
                }
            } else {
                card.url
            }
            if (newUrl.isNullOrBlank()) continue

            // Atomic Pause Guard Before Deleting Partials:
            // Verify task.userPaused inside repository.update before deleting partial files on disk.
            var wasUserPaused = false
            repository.update(task.id) { current ->
                if (current.userPaused) {
                    wasUserPaused = true
                    current
                } else {
                    current.copy(
                        sourceUrl = newUrl,
                        directUrl = newUrl,
                        mirrorUrls = newMirrors,
                        selectedMirrorUrl = "",
                        site = card.site,
                        quality = current.quality, // Preserve User Quality
                        status = TaskStatus.QUEUED,
                        errorMessage = null,
                        speedBytesPerSec = 0.0,
                        downloadedBytes = 0L,
                        totalBytes = 0L
                    )
                }
            }

            if (wasUserPaused) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "task=${task.id} failover: aborted because task was paused by user"
                )
                return false
            }

            com.anonrode.downloader.util.DebugLog.user(
                "task=${task.id} failover: switching source ${task.site} -> ${card.site} for \"$query\"" +
                    (if (origEpNum > 0) " (S${origSeason}E${origEpNum})" else "")
            )
            // The task is re-pointed at a different source: its previous
            // source's dead-URL marks must not answer for the new one.
            ResolveBudget.forgetTask(task.id)

            // Safe to delete partial files ONLY after verifying task is actively continuing (not user paused)
            try {
                File(task.filePath).delete()
                File(task.filePath + ".part").delete()
                File(task.filePath + ".ytdl").delete()
                File(task.filePath + ".aria2").delete()
                File(task.filePath + ".turbo").delete()
                File(task.filePath).parentFile?.let {
                    File(it, ".work-" + task.id).deleteRecursively()
                }
            } catch (_: Throwable) {}

            return true
        }

        // Nothing usable anywhere: leave the tried-set in place so the NEXT
        // failure of this task fails over again from its current site if a
        // manual retry re-points it, but don't loop here.
        com.anonrode.downloader.util.DebugLog.resolve(
            "task=${task.id} failover: no other provider could serve \"$query\""
        )
        // Strongest no-source evidence the app can gather: every eligible
        // provider was searched and none carried the title. Recorded ONCE per
        // attempt-cycle here — after the candidate loop, never inside it —
        // and the ledger TTLs it (48h) + clears on rules activation.
        com.anonrode.downloader.pipeline.NoSourceLedger.recordUnresolved(query)
        return false
    }

    internal fun extractYear(text: String): Int? {
        if (text.isBlank()) return null
        return YEAR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    // Keyword-only country read: the same tag vocabulary as the historical
    // extractCountry, WITHOUT the site-default tail. Used by failover for
    // mixed-site secondaries, where the WESTERN site default is actively
    // wrong (an nkiri K-drama card is not a Western show). Untagged text is
    // UNKNOWN, and UNKNOWN never rejects in Layer 3 — explicit tags still
    // do (a Hollywood-tagged card on 9jarocks loses to a Korean task).
    internal fun extractExplicitCountry(text: String): DramaCountry {
        val lower = text.lowercase()
        return when {
            lower.contains("korean") || lower.contains("k-drama") || lower.contains("kdrama") ||
                lower.contains("k drama") || lower.contains("korea") -> DramaCountry.KOREAN
            lower.contains("chinese") || lower.contains("c-drama") || lower.contains("cdrama") ||
                lower.contains("c drama") || lower.contains("china") -> DramaCountry.CHINESE
            lower.contains("japanese") || lower.contains("j-drama") || lower.contains("jdrama") ||
                lower.contains("j drama") || lower.contains("japan") -> DramaCountry.JAPANESE
            lower.contains("taiwanese") || lower.contains("tw-drama") || lower.contains("taiwan") -> DramaCountry.TAIWANESE
            lower.contains("thai") || lower.contains("thailand") || lower.contains("lakorn") ||
                lower.contains("th-drama") -> DramaCountry.THAI
            lower.contains("western") || lower.contains("hollywood") || lower.contains("american") ||
                lower.contains("nollywood") -> DramaCountry.WESTERN
            else -> DramaCountry.UNKNOWN
        }
    }

    internal fun extractCountry(text: String, site: String = ""): DramaCountry {
        val explicit = extractExplicitCountry(text)
        if (explicit != DramaCountry.UNKNOWN) return explicit
        return when {
            WESTERN_CLUSTER.contains(site.lowercase()) -> DramaCountry.WESTERN
            else -> DramaCountry.UNKNOWN
        }
    }

    // Layer-3 country read for cross-provider failover: identical to
    // extractCountry EXCEPT an Asian task reading a mixed-site secondary
    // card (nkiri/9jarocks) skips the WESTERN site default.
    internal fun extractFailoverCountry(isAsian: Boolean, text: String, site: String = ""): DramaCountry {
        if (isAsian && site.lowercase() in SECONDARY_ASIAN_FALLBACK) {
            return extractExplicitCountry(text)
        }
        return extractCountry(text, site)
    }

    internal fun isAsianDramaContext(task: DownloadTask): Boolean {
        val site = task.site.lowercase()
        if (site in DEDICATED_ASIAN_SITES) return true
        val country = extractCountry("${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}", task.site)
        if (country in setOf(DramaCountry.KOREAN, DramaCountry.CHINESE, DramaCountry.JAPANESE, DramaCountry.THAI, DramaCountry.TAIWANESE)) {
            return true
        }
        if (site in ASIAN_DRAMA_CLUSTER && site !in WESTERN_CLUSTER) return true
        return false
    }

    internal fun parseSeasonAndEpisode(task: DownloadTask): Pair<Int, Int> {
        if (task.site.equals("9jarocks", ignoreCase = true) && task.episodeNum >= 100) {
            return Pair(task.episodeNum / 100, task.episodeNum % 100)
        }
        if (task.episodeNum >= 100 && !isAsianDramaContext(task)) {
            return Pair(task.episodeNum / 100, task.episodeNum % 100)
        }
        val text = "${task.showTitle} ${task.episodeTitle} ${task.sourceUrl}".lowercase()
        val season = Regex("""(?:s|season)[\s._-]?0*(\d{1,2})\b""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val ep = if (task.episodeNum > 0) {
            task.episodeNum
        } else {
            Regex("""(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return Pair(season, ep)
    }

    internal fun parseCandidateSeasonAndEpisode(card: com.anonrode.downloader.data.models.ShowCard, ep: com.anonrode.downloader.data.models.EpisodeItem): Pair<Int, Int> {
        if (card.site.equals("9jarocks", ignoreCase = true) && ep.episodeNum >= 100) {
            return Pair(ep.episodeNum / 100, ep.episodeNum % 100)
        }
        val text = "${card.title} ${ep.title} ${ep.url}".lowercase()
        val season = Regex("""(?:s|season)[\s._-]?0*(\d{1,2})\b""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val epNum = if (ep.episodeNum > 0) {
            if (ep.episodeNum >= 100 && card.site.lowercase() !in ASIAN_DRAMA_CLUSTER) {
                ep.episodeNum % 100
            } else {
                ep.episodeNum
            }
        } else {
            Regex("""(?:e|ep|episode)[\s._-]?0*(\d{1,3})\b""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return Pair(season, epNum)
    }

    /** Lowercase and strip everything but letters/digits — the loose title
     *  comparison used by failover matching ("Sholay (1975)" vs "sholay"). */
    private fun normalizeTitleQuery(q: String): String =
        q.lowercase().filter { it.isLetterOrDigit() }

    // Words too common to help tell two titles apart; dropped by
    // [titleMatches] so "My Name is Khan" doesn't demand the word "is".
    // Site-noise words ("indian movie", "hindi", "web-dl", "480p"…) are
    // appended by scrapers, not part of the title — demanding them makes
    // failover reject the exact movie it's looking for (naijavault's
    // "My Name Is Khan 2010 Indian Movie" vs pluto's
    // "My Name Is Khan 2010 Hindi" — same film).
    private val TITLE_STOPWORDS = setOf(
        "the", "a", "an", "is", "of", "and", "to", "in", "on", "le", "la", "de",
        "movie", "film", "series", "complete", "season", "episode", "download",
        "full", "hd", "hindi", "english", "dubbed", "subbed", "korean", "chinese",
        "drama", "web", "dl", "webdl", "bluray", "brrip", "dvdrip", "hevc", "x264",
        "x265", "aac", "480p", "720p", "1080p", "2160p", "4k"
    )

    /** True when the candidate title covers the query: every meaningful word
     *  of the query must appear in the normalized candidate. Pure numbers
     *  (years like "1975") are ignored — our own titles carry years that
     *  candidate listings often omit. */
    private fun titleMatches(query: String, candidate: String): Boolean {
        val normCard = normalizeTitleQuery(candidate)
        if (normCard.isBlank()) return false
        val words = Regex("[a-z0-9]+").findAll(query.lowercase())
            .map { it.value }
            .filter { it.length >= 2 && !TITLE_STOPWORDS.contains(it) && !it.all { c -> c.isDigit() } }
            .toList()
        if (words.isEmpty()) return false
        if (!words.all { normCard.contains(it) }) return false

        // Year check inside titleMatches if both present
        val qYear = extractYear(query)
        val cYear = extractYear(candidate)
        if (qYear != null && cYear != null && qYear != cYear) return false

        // Sequel / Part check if both present
        val partRegex = Regex("""\b(?:part|chapter|pt)[\s._-]?(\d+|[ivx]+)\b""")
        val qPart = partRegex.find(query.lowercase())?.groupValues?.get(1)
        val cPart = partRegex.find(candidate.lowercase())?.groupValues?.get(1)
        if (qPart != null && cPart != null && qPart != cPart) return false

        return true
    }

    /** Season guard for failover: when the task title declares a season
     *  ("Suits S02", "Alchemy of Souls Season 2"), the candidate must declare
     *  the SAME season. Candidates that declare a different season are
     *  rejected; candidates with no season marker pass (the episode-number
     *  match inside the provider's episode list is the second guard). */
    private fun seasonConsistent(query: String, candidate: String, seasonNum: Int?): Boolean {
        if (seasonNum == null) return true
        val cardLower = candidate.lowercase()
        val cardSeason = Regex("(?:s|season)[\\s._-]?0*(\\d{1,2})\\b")
            .find(cardLower)?.groupValues?.get(1)?.toIntOrNull()
        return cardSeason == null || cardSeason == seasonNum
    }

    private fun looksLikeHtml(file: File): Boolean {
        return try {
            val head = file.inputStream().use { ins ->
                val buf = ByteArray(512)
                val n = ins.read(buf)
                if (n <= 0) return false
                // Strip UTF-8 BOM (U+FEFF) before prefix matching so BOM-prefixed
                // HTML error pages are caught, then decode and normalize.
                var start = 0
                if (n >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) {
                    start = 3
                }
                String(buf, start, n - start).trimStart().lowercase()
            }
            head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
                head.startsWith("<body") || head.startsWith("<!--") || head.startsWith("<script") ||
                head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<style") ||
                head.startsWith("<iframe") || head.startsWith("<meta") || head.startsWith("<form") ||
                head.startsWith("{")
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
            head.startsWith("<body") || head.startsWith("<!--") || head.startsWith("<script") ||
            head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<style") ||
            head.startsWith("<iframe") || head.startsWith("<meta") || head.startsWith("<form") ||
            head.startsWith("{")
    }

    /**
     * Tier-2 verification: ask the platform codec stack whether the file is
     * actually decodable media. MediaExtractor parses the container and exposes
     * its tracks plus per-track duration; a garbage blob yields no usable
     * track, real media yields at least one with a positive duration. This is a
     * container parse only (no frames decoded), so it is cheap, and it is
     * bounded by the platform's own parser — no manual timeout needed.
     */
    private fun decodeCheck(file: File): Boolean {
        return try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var ok = false
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)
                    if (!mime.isNullOrBlank() && fmt.containsKey(android.media.MediaFormat.KEY_DURATION) &&
                        fmt.getLong(android.media.MediaFormat.KEY_DURATION) > 0L
                    ) {
                        ok = true
                        break
                    }
                }
                com.anonrode.downloader.util.DebugLog.write("decodeCheck ${file.name}: tracks=${extractor.trackCount} decodable=$ok")
                ok
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.write("decodeCheck ${file.name}: ${e.message}")
            false
        }
    }

    private fun isImageValid(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return try {
            file.inputStream().use { ins ->
                val head = ByteArray(16)
                val read = ins.read(head)
                if (read < 4) false
                // JPEG: FF D8 FF
                else if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) true
                // PNG: 89 50 4E 47 (\x89PNG)
                else if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) true
                // WebP: RIFF....WEBP
                else if (read >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                    head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()) true
                else false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Image-artifact predicate (jpg/jpeg/png/webp) for the O1 completion check. */
    private fun isImageFile(file: File): Boolean =
        file.name.lowercase().let {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp")
        }

    /**
     * Actual bytes this task has written to disk: the final file, its .part /
     * .ytdl partials, and yt-dlp's concurrent fragment files. The UI progress
     * watchdog uses this as the source of truth when backend output parsing
     * reports nothing, and it is what stall detection compares against.
     *
     * yt-dlp extractor tasks write into a private per-task workdir
     * (.work-<taskId> inside the target folder) — see YoutubeDlDownloader.
     * While that workdir exists it IS the task's footprint and scanning it
     * keeps sibling jobs in the same folder (two YouTube videos at once,
     * app-2026-08-31) from counting each other's bytes; once the artifact is
     * moved back the workdir is deleted, and the task is one tick away from
     * a terminal status anyway (extractor outputs are named by yt-dlp's
     * metadata template, not by task.filePath).
     */
    private fun computeDiskBytes(task: DownloadTask): Long {
        return try {
            val targetDir = File(task.filePath).parentFile ?: return 0L
            val workDir = File(targetDir, ".work-${task.id}")
            if (workDir.isDirectory) {
                return workDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            val dir = targetDir
            dir.listFiles() ?: return 0L
            val base = File(task.filePath).name
            // Segmented Turbo downloads pre-allocate the .part file to its full
            // size before a single byte is written, so file.length() is not a
            // trustworthy progress source while a download is in flight.
            // When a .turbo sidecar exists, the piece map IS the truth:
            // sum committed offsets, ignore pre-allocated zeros. Never sum with destFile.
            val sidecar = File(dir, base + ".turbo")
            val ariaControl = File(dir, "$base.aria2")
            // O2b precedence: the engine only starts Turbo when NO base.aria2
            // exists, so a base.aria2 control file is ALWAYS the live transfer —
            // its bitfield outranks a .turbo plan stranded by a failed handoff.
            // (Aria2 pre-allocates the dest to full size, so the bitfield is the
            // only honest source of committed bytes.)
            if (ariaControl.exists()) {
                val ctrl = Aria2Control.parse(ariaControl)
                return ctrl?.verifiedLandedBytes() ?: ctrl?.pieces?.sumOf { it.current - it.start } ?: 0L
            }
            if (sidecar.exists()) {
                return TurboState(sidecar).writtenBytes() ?: 0L
            }
            // Legacy handoff control ("X.part" + "X.part.aria2"): the engine's
            // resume gate does not see this name, so Turbo may legitimately own
            // the .part — keep this read AFTER the Turbo plan.
            val legacyAria2 = File(dir, "$base.part.aria2")
            if (legacyAria2.exists()) {
                val ctrl = Aria2Control.parse(legacyAria2)
                return ctrl?.verifiedLandedBytes() ?: ctrl?.pieces?.sumOf { it.current - it.start } ?: 0L
            }
            // Non-turbo transfers: use strict single-file priority.
            // Guard against unverified pre-allocated files: if the task is actively downloading
            // and the raw file length equals the expected totalBytes, do NOT trust the unverified
            // preallocation without a validating sidecar/control file.
            val partFile = File(dir, "$base.part")
            if (partFile.exists()) {
                val len = partFile.length()
                if (task.status == TaskStatus.DOWNLOADING && task.totalBytes > 0L && len >= task.totalBytes && task.downloadedBytes < task.totalBytes) {
                    return task.downloadedBytes
                }
                return len
            }
            val ytdlFile = File(dir, "$base.ytdl")
            if (ytdlFile.exists()) return ytdlFile.length()
            val destFile = File(dir, base)
            if (destFile.exists()) {
                val len = destFile.length()
                if (task.status == TaskStatus.DOWNLOADING && task.totalBytes > 0L && len >= task.totalBytes && task.downloadedBytes < task.totalBytes) {
                    return task.downloadedBytes
                }
                return len
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Bytes this task has actually banked: what the backends reported OR what
     * is on disk (partials included). The park-vs-fail rule below keys on this.
     */
    private fun bytesLanded(task: DownloadTask): Long =
        maxOf(task.downloadedBytes, computeDiskBytes(task))

    /**
     * A 429 is an explicit "come back later" — never a reason to destroy a
     * task. Matches the engine's own vocabulary: the validator rejection
     * ("Server rejected the link (HTTP 429) — ..."), the Turbo failure
     * message, and the aria2c/yt-dlp chains that quote the status.
     */
    private fun isRateLimitedError(msg: String?): Boolean =
        msg != null && (msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true))

    private fun parkFloorMs(cycles: Int): Long =
        (cycles.toLong() * PARK_REQUEUE_FLOOR_MS).coerceAtMost(PARK_REQUEUE_MAX_MS)

    /**
     * THE RULE (user, 2026-09-14): a download that has ever moved bytes may
     * never be FAILED by a host-side hiccup — rate-limit, stall, dead link,
     * backend teardown. It parks instead: PAUSED with the cooldown-loop
     * message, partial files untouched (Turbo's .part/.turbo sidecar and
     * aria2c's control file both resume from where they stopped), and the
     * existing auto-retry loop re-queues it when the host is usable, on a
     * per-cycle escalating floor. Tasks that never started (zero banked
     * bytes and no rate-limit signal) keep the honest-error behavior so a
     * permanently dead source cannot hold a queue slot hostage forever.
     *
     * Returns true when the task was parked (caller must NOT fail/notify).
     */
    private fun parkInsteadOfFail(task: DownloadTask, err: String, forcePark: Boolean): Boolean {
        val landed = bytesLanded(task)
        if (landed == 0L && !forcePark) return false
        val entry = runtimeRegistry.getOrCreate(task.id)
        val cycles = entry.parkCycleCount.incrementAndGet()
        val now = System.currentTimeMillis()
        entry.firstParkedAtMs.compareAndSet(0L, now)
        val floorMs = parkFloorMs(cycles)
        entry.requeueNotBeforeMs.set(now + floorMs)
        val mins = maxOf(1L, floorMs / 60_000L)
        val saved = if (landed > 0) " — ${(landed / (1024L * 1024L)).coerceAtLeast(1)} MB already saved, it will resume" else ""
        repository.update(task.id) {
            it.copy(
                status = TaskStatus.PAUSED,
                speedBytesPerSec = 0.0,
                errorMessage = "$PARKED_HOST_MESSAGE — will retry in ~${mins}m$saved"
            )
        }
        com.anonrode.downloader.util.DebugLog.user(
            "park task=${task.id} (cycle=$cycles, landed=${landed / 1024}KiB, forced=$forcePark) reason=${err.take(140)}"
        )
        updateServiceState(force = true)
        return true
    }

    private fun checkStorageAvailable(): Boolean {
        try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(path.path)
            val freeGb = (stat.availableBlocksLong * stat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
            return freeGb >= storageGuardGb
        } catch (_: Exception) {
            return true
        }
    }

    private fun getDownloadDirectory(showTitle: String, createDirs: Boolean = false): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(root, "Anon")
        val dest = when {
            showTitle.startsWith("Social", ignoreCase = true) -> {
                val platform = showTitle.substringAfter("Social/", "Generic").trim()
                File(base, "Social/${sanitizeComponent(platform, 40)}")
            }
            showTitle.equals("Torrents", ignoreCase = true) -> File(base, "Torrents")
            autoOrganizeByShow && showTitle.isNotBlank() && showTitle != "Direct Downloads" -> {
                File(base, sanitizeComponent(showTitle, 60))
            }
            else -> base
        }
        if (dest.exists() && !dest.isDirectory) {
            try { dest.delete() } catch (_: Exception) {}
        }
        if (createDirs && !dest.exists()) {
            dest.mkdirs()
        }
        return dest
    }

    /**
     * Turn an arbitrary scraped title into a safe single filesystem
     * component. THE policy lives in util/NameSanitizer (the app-wide name
     * standard: noise-phrase removal, full entity decoding, native script
     * preserved, control/forbidden chars replaced, traversal/reserved-name/
     * trailing-dot guards, byte-aware cap). Kept as the engine's local
     * doorway so folder/stem/social call sites all share one entry.
     */
    private fun sanitizeComponent(
        raw: String, maxChars: Int, stripNoise: Boolean = true
    ): String = com.anonrode.downloader.util.NameSanitizer.savedName(raw, maxChars, stripNoise)

    private fun getRefererForUrl(url: String): String {
        // Single source of truth is now the OTA playbook (DynamicRulesManager:
        // hostPolicies first, built-in defaults second). The old per-host
        // when-map lived here and drifted from the monolith + probe copies;
        // its exact entries survive as the manager's DEFAULT_HOST_POLICIES.
        return com.anonrode.downloader.data.rules.DynamicRulesManager.resolveReferer(url)
    }

    private fun getUserAgentForUrl(url: String): String {
        val policyUa = com.anonrode.downloader.data.rules.DynamicRulesManager.resolveUserAgent(url)
        return if (policyUa.isNotBlank()) policyUa else com.anonrode.downloader.data.net.HttpClient.DEFAULT_UA
    }

    // Adaptive-streaming manifest detection (monolith is_streaming_link parity):
    // DASH/ISM/F4M manifests and player-token URLs must go to yt-dlp, or the
    // segmented downloader grabs manifest XML and produces a corrupt file.
    private fun isStreamingLink(url: String): Boolean =
        com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(url)

    companion object {
        // URI="..." inside HLS tags (EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA) — the
        // URI value may be scheme-relative or relative and needs the same
        // absolute-https rewrite as segment URIs.
        private val URI_VALUE = Pattern.compile("URI=\"([^\"]+)\"")

        private val STREAMING_QUERY_PATTERN = Regex("""[?&][^=&]*=(?:mpd|dash|hls|m3u8)(?:&|$)""")

        // KNOWN_LOCKER_HOSTS + the isKnownLockerHost/isProvablyDirectFile
        // classifiers moved to pipeline.LinkResolver (2026-09-14) so the
        // search-verify oracle proves results with the SAME ladder downloads
        // use. The thin delegates below keep this file's ~20 call sites intact.

        // Stall-watchdog tuning constants live in ProgressWatchdog.kt (single source of truth).

        // Hard ceiling on the link-cracking phase. Without it a slow site kept
        // a task in RESOLVING forever while the user's mobile data trickled
        // away on retries.
        private const val RESOLVE_TIMEOUT_MS = 90_000L

        // Prefix of the errorMessage set when a task is parked because its
        // locker host is in HostHealth backoff ("cooling down"). The
        // auto-retry loop matches this prefix and re-queues the task once the
        // host is usable again.
        private const val PARKED_HOST_MESSAGE = "Download server cooling down"

        // A parked task may cycle QUEUED → RESOLVING → park at most this many
        // times before it is FAILED outright. Without a cap, a host that never
        // recovers keeps the batch bouncing every backoff window forever (the
        // Suits batch churned for 90 minutes on a host that was never coming
        // back). Generous: at 2 windows each, 6 attempts ≈ 12 minutes of real
        // chances before the task gives up with an honest error.
        private const val MAX_PARK_CYCLES = 6

        // How long ONE task may sit parked by the cooldown loop before the
        // give-up fires it. Measured from the FIRST park (firstParkedAtMs uses
        // compareAndSet), so repeated re-parks cannot reset the clock: a host
        // that stays dead for 30 minutes of cycling loses the task with an
        // honest error instead of holding the queue hostage all afternoon.
        private const val PARKED_LIFETIME_MS = 30 * 60_000L

        // Re-queue floor per park cycle: the 1st park waits 60s, the 2nd 2m,
        // ... capped at 15m. Rate-limits on these lockers are minutes-long
        // penalties, and every premature bounce re-triggers them (share-5
        // log: the 429 storm retried every 2-4 seconds, each retry extending
        // the block and burning data on failed resolves).
        private const val PARK_REQUEUE_FLOOR_MS = 60_000L
        private const val PARK_REQUEUE_MAX_MS = 15 * 60_000L
    }

    private var lastNotificationTime: Long = 0L
    private val NETWORK_PAUSE_MESSAGE = "Waiting for network..."
    private var lastNetworkTag: String? = null

    private fun updateServiceState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationTime < 1000L) {
            return
        }
        lastNotificationTime = now
        val current = tasks.value
        val active = current.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING }
        if (active.isNotEmpty()) {
            val first = active.first()
            val pct = if (first.totalBytes > 0) (first.downloadedBytes * 100 / first.totalBytes).toInt().coerceIn(0, 100) else 0
            val speedMb = first.speedBytesPerSec / (1024.0 * 1024.0)
            val speedStr = if (speedMb > 0.05) " • %.1f MB/s".format(java.util.Locale.US, speedMb) else ""
            DownloadService.updateProgress(
                context,
                title = "${first.episodeTitle}$speedStr",
                progress = pct,
                activeCount = active.size
            )
        } else {
            DownloadService.stop(context)
        }
    }

    @Synchronized
    private fun processQueue() {
        val net = networkObserver.getCurrentStatus()
        if (!net.isConnected) return

        // Fill EVERY free slot in one pass, not just one: the reconnect /
        // storage-self-heal / cooldown loops re-queue many parked tasks and
        // then call processQueue() exactly once, so single-step starts left
        // maxConcurrentDownloads-1 slots empty until the next completion.
        while (true) {
            val currentTasks = tasks.value
            // Two counters, and the LIMIT uses the LARGER of the two:
            //  - status count: what the repository claims (DOWNLOADING/RESOLVING/VALIDATING)
            //  - live-job count: coroutines actually running in runtimeRegistry
            // The status count alone missed tasks whose status was written by a
            // path outside this scheduler (watchdog restart, post-pause zombie
            // writes) — the live-job count alone misses a task between its
            // status flip and its job registration. Taking the max closes the
            // window both ways: this is THE concurrency choke-point, and no
            // other code may start a backend outside it.
            val activeIds = buildSet {
                currentTasks.filter {
                    it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING
                }.forEach { add(it.id) }
                addAll(runtimeRegistry.activeRunningTaskIds())
            }
            val activeCount = activeIds.size

            if (activeCount >= maxConcurrentDownloads) return

            // Never start a task the user explicitly paused (engine-audit P1:
            // no legitimate state is QUEUED + userPaused — only retry()/
            // resumeAll() clear the flag, and they set QUEUED themselves, so
            // this predicate can only ever reject a pause that raced a requeue).
            // Never pick a task the runtime registry still owns: a failover flips a
            // task QUEUED milliseconds before its dying coroutine's finally runs, and
            // starting it here would flip it to RESOLVING/DOWNLOADING only to lose
            // the start-lock race and strand it (audit: queue↔start race).
            val nextTask = currentTasks.firstOrNull {
                it.status == TaskStatus.QUEUED && !it.userPaused && it.id !in activeIds
            } ?: return

            if (!checkStorageAvailable()) {
                // Park every queued task, not just the head, so the whole queue drains to PAUSED
                // in one pass instead of stalling on a single task per processQueue call.
                currentTasks.filter { it.status == TaskStatus.QUEUED }.forEach { queued ->
                    repository.update(queued.id) { t -> t.copy(status = TaskStatus.PAUSED, errorMessage = "Storage limit reached (< ${storageGuardGb}GB free)") }
                }
                return
            }

            if (wifiOnlyTorrents && !net.isWifi && nextTask.directUrl.startsWith("magnet:")) {
                repository.update(nextTask.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)") }
                // Continue instead of return: a gated magnet must not block
                // non-torrent HTTP tasks queued behind it from starting.
                continue
            }
            // Wi-Fi-only for ALL downloads (not just torrents)
            if (wifiOnlyAll && !net.isWifi) {
                currentTasks.filter { it.status == TaskStatus.QUEUED && !it.userPaused }.forEach { queued ->
                    repository.update(queued.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Wi-Fi Only enabled)") }
                }
                return
            }

            // Status may only be flipped once the start lock is held, so no queue
            // path can strand a task in RESOLVING/DOWNLOADING without a coroutine.
            if (!runtimeRegistry.tryAcquireStartLock(nextTask.id)) return

            val isDirect = isDirectMediaUrl(nextTask.directUrl) && !isKnownLockerHost(nextTask.directUrl)
            val initialStatus = if (isDirect) TaskStatus.DOWNLOADING else TaskStatus.RESOLVING
            // Conditional flip: a pause landing between the selection and this
            // write must not be overwritten (a paused row is never QUEUED).
            repository.update(nextTask.id) { t ->
                if (t.userPaused || t.status != TaskStatus.QUEUED) t else t.copy(status = initialStatus)
            }
            updateServiceState(force = true)

            startTask(nextTask, lockAlreadyHeld = true)
            // Loop on: the status flip above is synchronous on the StateFlow,
            // so the next iteration counts this task as active and picks the
            // following QUEUED one until all slots are full.
        }
    }

    /** Moved to pipeline.LinkResolver (2026-09-14, oracle round); delegates
     *  here so the routing/startTask call sites below are untouched. */
    private fun isProvablyDirectFile(url: String): Boolean =
        com.anonrode.downloader.pipeline.LinkResolver.isProvablyDirectFile(url)

    private fun isKnownLockerHost(url: String): Boolean =
        com.anonrode.downloader.pipeline.LinkResolver.isKnownLockerHost(url)

    /** Moved verbatim to pipeline.LinkResolver.resolveChain (2026-09-14,
     *  oracle round). allowCacheHit=false preserves this caller's documented
     *  semantic: "fetch a FRESH link" — invalidate-first, never serve a
     *  cached (possibly dead-token) URL on the refresh path. All four
     *  call sites (startTask + the 401/403/404 self-heal re-resolves) get
     *  identical behavior to before the move.
     *
     *  [memoTaskId] is NON-NULL only on the resolve-phase call in startTask:
     *  it arms the per-task dead-URL memory (engine.ResolveBudget) + per-
     *  mirror attempt budgets for the engine-driven walk. The mid-download
     *  self-heal re-resolves deliberately stay memo-less — a 403-refresh
     *  re-mints a rotating token and must never be refused by a stale mark.
     *  (Attempt budgets still apply registry-wide, self-heal included:
     *  a hung refresh is clipped and moves on, while a healthy refresh on
     *  a proven-history host keeps the generous 40s cap. Budget expiry is
     *  a discarded result inside the chain — it never cancels a task.) */
    private suspend fun resolveStreamUrl(
        permUrl: String,
        site: String,
        defaultQual: String,
        bypassHealth: Boolean = false,
        allowCacheHit: Boolean = false,
        memoTaskId: String? = null
    ): String? =
        com.anonrode.downloader.pipeline.LinkResolver.resolveChain(
            permUrl, site, defaultQual, bypassHealth = bypassHealth, allowCacheHit = allowCacheHit,
            memoTaskId = memoTaskId
        )

    /**
     * Thrown by [preflightHls] when the CDN rejects the playlist with 401/403:
     * the token stamped into the persisted URL has expired or been invalidated.
     * The engine answers by re-resolving from the source page (fresh token)
     * instead of failing — a persisted HLS URL must never be trusted twice.
     */
    private class StaleStreamLinkException(message: String) : Exception(message)

    /**
     * HLS pre-flight: fetch the master playlist, probe the first segment host,
     * and — when the playlist carries scheme-relative (//cdn/...) or relative
     * segment URIs — rewrite them to absolute https into a local file that
     * yt-dlp consumes instead of the original URL.
     *
     * Why the rewrite is load-bearing: yt-dlp joins a scheme-relative segment
     * URI to http:// (port 80) while browsers (hls.js) use https:// — these
     * CDNs answer 443 and hang port 80. Live-verified: the same vidbasic
     * master stalled forever at port 80 and downloaded from a rewritten
     * https master. A dead segment CDN additionally used to pin the task in
     * DOWNLOADING at 0 bytes for minutes (watchdog kills + 3 attempts =
     * user-reported "stuck on starting"); the probe turns that into a clean
     * failure in seconds.
     *
     * Returns the rewritten master file (null = original URL is fine), and
     * throws a user-facing message when the stream is unreachable.
     *
     * Also runs the Segment-Sampling Estimator (measured segment sizes, not
     * BANDWIDTH tags) and exposes the predicted total through
     * [estimatedTotalBytes] — a one-element out-holder (0 = no estimate) —
     * for future progress plumbing. The estimate is diagnostic-only: its
     * every failure logs hls-size-estimate=null and never disturbs the
     * preflight outcome.
     */
    private suspend fun preflightHls(
        context: Context,
        taskId: String,
        masterUrl: String,
        referer: String?,
        estimatedTotalBytes: LongArray = LongArray(1),
        requestedQuality: String? = null,
        resolutionOut: Array<String?> = arrayOfNulls(1)
    ): File? {
        val cacheDir = File(context.cacheDir, "hls").apply { mkdirs() }
        val created = mutableListOf<File>()
        var currentUrl = masterUrl
        var playlist: String? = null
        var staleToken = false
        try {
            HttpClient.get(currentUrl, referer = referer, tag = "preflight").use { res ->
                when {
                    // 401/403 on the master means the token stamped into the
                    // persisted URL is dead — a stale link, not a dead server.
                    // The engine re-resolves from the source page for a fresh one.
                    res.code == 401 || res.code == 403 -> staleToken = true
                    res.isSuccessful -> {
                        val text = HttpClient.cappedText(res)
                        if (text != null) {
                            if (text.startsWith("#EXTM3U") || text.contains("#EXT-X-STREAM-INF") || text.contains("#EXTINF")) {
                                playlist = text
                            } else if (looksLikeHtml(text) || text.contains("<!doctype", ignoreCase = true) || text.contains("<html", ignoreCase = true)) {
                                staleToken = true
                            } else {
                                playlist = text
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        when {
            staleToken -> throw StaleStreamLinkException("Stream link expired — fetching a fresh one")
            playlist == null -> throw Exception("Stream server unreachable — the playlist did not load. Try again later.")
        }
        var masterFile = rewriteHlsMaster(playlist, currentUrl, "hls-$taskId.m3u8", cacheDir)
        if (masterFile != null) {
            created.add(masterFile)
            // What yt-dlp is actually fed — the 0-byte HLS stall was
            // undiagnosable without seeing the rewritten playlist.
            com.anonrode.downloader.util.DebugLog.resolve(
                "task=$taskId rewritten master: ${masterFile.readText().take(300).replace("\n", " | ")}"
            )
        }

        // The master's RESOLUTION attributes are the REAL stream quality —
        // a requested "720p" can resolve to 1080p if that's all the host
        // offers. Mirror yt-dlp's height-limited format selection: the
        // highest variant at or below the requested height (or the smallest
        // when nothing fits). Exposed for the download card's quality chip.
        resolutionOut[0] = pickHlsResolution(playlist, requestedQuality)

        // Walk one variant level: rewrite referenced variant playlists to
        // local files too and repoint the master at them (relative name),
        // so their segments also go over https.
        val mediaLines = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        var probeUrl: String? = null
        if (mediaLines.isNotEmpty()) {
            var firstResolved = resolveSegmentUrl(currentUrl, mediaLines[0])
            if (firstResolved != null && firstResolved.contains(".m3u8")) {
                // Fetch every variant playlist CONCURRENTLY: the old sequential
                // loop let one slow variant stall the whole preflight. Each
                // result keeps its original index so the first-variant probe /
                // size-estimate logic still runs in stream order afterwards.
                val variantResults = coroutineScope {
                    mediaLines.mapIndexedNotNull { idx, line ->
                        val vUrl = resolveSegmentUrl(currentUrl, line) ?: return@mapIndexedNotNull null
                        if (!vUrl.contains(".m3u8")) return@mapIndexedNotNull null
                        async {
                            var variant: String? = null
                            var variantStale = false
                            try {
                                HttpClient.get(vUrl, referer = referer, tag = "preflight").use { res ->
                                    when {
                                        res.code == 401 || res.code == 403 -> variantStale = true
                                        res.isSuccessful -> variant = HttpClient.cappedText(res)
                                    }
                                }
                            } catch (_: Exception) {}
                            idx to Triple(vUrl, variant, variantStale)
                        }
                    }
                }.awaitAll()
                // All fetches complete: a stale token surfaces first (in stream
                // order), then the variant-level rewrite/repoint/estimate runs.
                for ((idx, res) in variantResults) {
                    val (vUrl, variant, variantStale) = res
                    if (variantStale) throw StaleStreamLinkException("Stream link expired — fetching a fresh one")
                    if (variant == null) continue
                    val vFile = rewriteHlsMaster(variant, vUrl, "hls-$taskId-v$idx.m3u8", cacheDir)
                    if (vFile != null) created.add(vFile)
                    if (vFile != null && masterFile != null) {
                        // Repoint this variant's URI in the local master at the
                        // local variant file (same directory → relative name).
                        try {
                            masterFile.writeText(
                                masterFile.readText().replace(vUrl, vFile.name)
                            )
                        } catch (_: Exception) {}
                    }
                    if (idx == 0) {
                        val vSeg = variant.lineSequence().map { it.trim() }
                            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        if (vSeg != null) probeUrl = resolveSegmentUrl(vUrl, vSeg)
                        // Segment-Sampling Estimator: runs asynchronously in the
                        // background without blocking yt-dlp's launch.
                        triggerAsyncHlsSizeEstimate(taskId, playlist, currentUrl, vUrl, referer)
                    }
                }
            } else {
                probeUrl = firstResolved
                // The URL is a media playlist itself (single-variant stream):
                // estimate asynchronously in the background.
                triggerAsyncHlsSizeEstimate(taskId, playlist, currentUrl, currentUrl, referer)
            }
        }

        // Probe the segment host exactly as yt-dlp will reach it after the
        // rewrite — https first, http only as a fallback for https-dead CDNs.
        if (probeUrl != null) {
            if (!HttpClient.probe(probeUrl, referer, timeoutMs = 2_500L, tag = "preflight")) {
                val httpVariant = if (probeUrl.startsWith("https:")) {
                    "http:" + probeUrl.substringAfter("https:")
                } else null
                if (httpVariant == null || !HttpClient.probe(httpVariant, referer, timeoutMs = 2_500L, tag = "preflight")) {
                    val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(probeUrl)
                    for (f in created) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                    throw Exception("Stream CDN not responding ($host) — the source server is down or blocking this network. Try again later.")
                }
            }
        }
        return masterFile
    }

    /**
     * Segment-Sampling Estimator hook: probe a few real segments and log the
     * HLS size estimate (hls-size-estimate=...). Purely diagnostic — every
     * failure yields null and never disturbs preflightHls' outcome.
     */
    /**
     * Best-effort pick of the variant height a download will land on, from
     * the master playlist's EXT-X-STREAM-INF RESOLUTION attributes. Mirrors
     * yt-dlp's height-limited format selection (highest <= requested;
     * smallest if nothing fits). Null when the master declares no heights.
     */
    private fun pickHlsResolution(masterText: String, requestedQuality: String?): String? {
        val heights = mutableListOf<Int>()
        val re = Pattern.compile("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=\d+x(\d+)""")
        val m = re.matcher(masterText)
        while (m.find()) heights.add(m.group(1).toInt())
        if (heights.isEmpty()) return null
        val requested = requestedQuality?.filter { it.isDigit() }?.toIntOrNull()
        val chosen = when {
            requested == null -> heights.maxOrNull() ?: return null
            else -> heights.filter { it <= requested }.maxOrNull() ?: heights.min()
        }
        return "${chosen}p"
    }

    private suspend fun runSizeEstimate(
        taskId: String,
        masterText: String,
        masterUrl: String,
        variantUrl: String,
        referer: String?,
        estimatedTotalBytes: LongArray
    ) {
        val estimate = try {
            HlsSizeEstimator.estimate(masterText, masterUrl, variantUrl, referer)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        estimatedTotalBytes[0] = estimate ?: 0L
        com.anonrode.downloader.util.DebugLog.resolve(
            if (estimate != null) {
                "task=$taskId hls-size-estimate=$estimate (${estimate / (1024 * 1024)} MiB)"
            } else {
                "task=$taskId hls-size-estimate=null (no estimate)"
            }
        )
    }

    private fun triggerAsyncHlsSizeEstimate(
        taskId: String,
        masterText: String,
        masterUrl: String,
        variantUrl: String,
        referer: String?
    ) {
        engineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val estimate = try {
                HlsSizeEstimator.estimate(masterText, masterUrl, variantUrl, referer)
            } catch (_: kotlinx.coroutines.CancellationException) {
                null
            } catch (_: Exception) {
                null
            }
            if (estimate != null && estimate > 0L) {
                com.anonrode.downloader.util.DebugLog.resolve(
                    "task=$taskId async hls-size-estimate=$estimate (${estimate / (1024 * 1024)} MiB)"
                )
                val live = repository.find(taskId)
                if (live != null && live.status == TaskStatus.DOWNLOADING && live.totalBytes <= 0L) {
                    repository.updateProgress(
                        taskId = taskId,
                        downloaded = live.downloadedBytes,
                        total = estimate,
                        speed = live.speedBytesPerSec,
                        eta = live.etaSeconds
                    )
                }
            }
        }
    }

    /**
     * Rewrite a playlist's scheme-relative (//host/...) and relative segment
     * URIs to absolute https. Returns the rewritten file, or null when the
     * playlist already uses absolute URLs (nothing to fix).
     */
    private fun rewriteHlsMaster(playlist: String, baseUrl: String, outName: String, dir: File): File? {
        val sb = StringBuilder()
        var changed = false
        for (rawLine in playlist.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("//") -> {
                    sb.append("https:").append(line).append('\n')
                    changed = true
                }
                line.startsWith("#") -> {
                    var l = line
                    val m = URI_VALUE.matcher(l)
                    if (m.find()) {
                        val out = StringBuffer()
                        do {
                            val u = m.group(1) ?: ""
                            val repl = when {
                                u.startsWith("//") -> "https:$u"
                                u.startsWith("http://") || u.startsWith("https://") -> u
                                else -> resolveSegmentUrl(baseUrl, u) ?: u
                            }
                            m.appendReplacement(out, "URI=\"" + java.util.regex.Matcher.quoteReplacement(repl) + "\"")
                        } while (m.find())
                        m.appendTail(out)
                        l = out.toString()
                        if (l != line) changed = true
                    }
                    sb.append(l).append('\n')
                }
                else -> {
                    val resolved = resolveSegmentUrl(baseUrl, line)
                    if (resolved != null && resolved != line) {
                        sb.append(resolved).append('\n')
                        changed = true
                    } else {
                        sb.append(line).append('\n')
                    }
                }
            }
        }
        if (!changed) return null
        return try {
            val f = File(dir, outName)
            f.writeText(sb.toString())
            f
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve a playlist-relative or protocol-relative URI against [base]. */
    private fun resolveSegmentUrl(base: String, uri: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("//")) return "https:$uri"
        return try {
            val b = java.net.URI(base)
            val qIdx = uri.indexOf('?')
            val pathPart = if (qIdx >= 0) uri.substring(0, qIdx) else uri
            val queryPart = if (qIdx >= 0) uri.substring(qIdx + 1) else null
            val joined = if (pathPart.startsWith("/")) {
                pathPart
            } else {
                b.path.substringBeforeLast('/') + "/" + pathPart
            }
            java.net.URI(b.scheme, null, b.host, b.port, joined, queryPart, null).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Size of the produced artifact. A single-file download is a plain File,
     * but a multi-file torrent (season pack) lands as a DIRECTORY whose
     * File.length() is ~0 (or a block size) — the completion gate must sum
     * the tree instead of trusting the directory's own stat size.
     */
    private fun fileSize(file: File): Long {
        return if (file.isFile) file.length()
        else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private suspend fun handleWatchdogStall(
        taskId: String,
        kills: Int,
        exhausted: Boolean,
        stallMsg: String,
        t: DownloadTask
    ) {
        android.util.Log.w("AnonDownload", "No download progress for ${t.episodeTitle}, (stall kill $kills)")
        YoutubeDlDownloader.killProcess(taskId)
        TurboDownloader.cancelTask(taskId)
        if (exhausted) {
            com.anonrode.downloader.util.RemoteLogger.report(
                type = "stall_watchdog_timeout",
                message = stallMsg,
                context = mapOf(
                    "episode" to t.episodeTitle,
                    "backend" to t.backend,
                    "site" to t.site,
                    "host" to (try { java.net.URI(t.directUrl).host } catch (_: Exception) { null }),
                    "attempts" to kills,
                    "downloaded_bytes" to t.downloadedBytes,
                    "total_bytes" to t.totalBytes
                )
            )
            // A user pause racing this kill already wrote its own terminal state —
            // never overwrite it with park/fail or a failure notification.
            val liveRow = repository.find(taskId)
            if (liveRow == null || liveRow.userPaused) {
                runtimeRegistry.getActiveJob(taskId)?.cancel()
                return
            }
            if (t.downloadedBytes == 0L && bytesLanded(t) == 0L && attemptCrossProviderFailover(t)) {
                runtimeRegistry.getActiveJob(taskId)?.cancel()
                return
            }
            var failedTerminal = false
            if (!parkInsteadOfFail(t, stallMsg, forcePark = isRateLimitedError(stallMsg))) {
                repository.update(taskId) {
                    it.copy(status = TaskStatus.FAILED, errorMessage = stallMsg)
                }
                com.anonrode.downloader.service.DownloadService.notifyFailed(context, taskId, t.episodeTitle, stallMsg)
                failedTerminal = true
            }
            val dyingJob = runtimeRegistry.getActiveJob(taskId)
            dyingJob?.cancel()
            // Watchdog-exhausted FAILED is terminal: drop the registry entry
            // (same rule as the COMPLETED path). Runs AFTER the cancel so the
            // dying job stays reachable, and never drops an entry a newer job
            // (a retry racing this kill) has taken over.
            if (failedTerminal) {
                val reg = runtimeRegistry.get(taskId)
                if (reg == null || reg.activeJob == null || reg.activeJob === dyingJob) runtimeRegistry.forget(taskId)
            }
        } else {
            // Preserve park counters across stall kills: markForManualRetry's
            // resetParkingAndFailoverState would wipe parkCycleCount/firstParkedAtMs
            // and defeat the anti-loop parking budget. Grant the bypass only.
            runtimeRegistry.grantRetryBypass(taskId)
        }
    }

    /**
     * Safely converts a TurboDownloader `.part` + `.turbo` pair into a contiguous prefix
     * file at [dest] so native `aria2c -c` can resume from the exact byte offset without
     * extension mismatches or orphaned files.
     */
    private fun handoffTurboPrefixToAria2c(dest: File) {
        try {
            val partFile = File(dest.absolutePath + ".part")
            val turboFile = File(dest.absolutePath + ".turbo")
            val ariaControl = File(dest.absolutePath + ".aria2")
            if (!partFile.exists()) return

            val prefix = if (turboFile.exists()) {
                TurboState(turboFile).contiguousPrefixBytes() ?: 0L
            } else {
                partFile.length()
            }

            if (prefix > 0L && prefix <= partFile.length() && !ariaControl.exists()) {
                // A stale dest must not block the prefix handoff — but delete it
                // ONLY when smaller than the prefix being placed, so a finished
                // file can never be destroyed by a stale .part (O2 hardening).
                if (dest.exists() && dest.length() < prefix) dest.delete()
                if (!dest.exists() && partFile.renameTo(dest)) {
                    RandomAccessFile(dest, "rw").use { it.setLength(prefix) }
                } else {
                    partFile.delete()
                }
            } else if (prefix == 0L) {
                partFile.delete()
            }
            // aria2c owns the transfer now: a stranded .turbo would shadow the
            // .aria2 progress read (O2b). Delete it whenever .part is gone.
            if (!partFile.exists() && turboFile.exists()) TurboState(turboFile).delete()
        } catch (e: Exception) {
            com.anonrode.downloader.util.DebugLog.error("handoffTurboPrefixToAria2c failed: ${e.message}")
        }
    }

    private fun startTask(initialTask: DownloadTask, lockAlreadyHeld: Boolean = false) {
        if (!lockAlreadyHeld && !runtimeRegistry.tryAcquireStartLock(initialTask.id)) return
        val live = repository.find(initialTask.id)
        if (live == null || live.status !in setOf(TaskStatus.QUEUED, TaskStatus.RESOLVING, TaskStatus.DOWNLOADING)) {
            // No job will ever attach to this entry, so forget it instead of
            // leaving a dead key behind (release-only left one per guarded
            // start). The start lock is exclusively ours here; a later retry
            // re-creates the entry via getOrCreate.
            runtimeRegistry.forget(initialTask.id)
            return
        }
        com.anonrode.downloader.util.DebugLog.write("start task=${initialTask.id} url=${initialTask.directUrl.take(80)} backend=${initialTask.backend}")

        val job = engineScope.launch(start = CoroutineStart.LAZY) {
            var watchdogJob: Job? = null
            // Re-read the task row: the enqueue/extension-sync paths may have moved
            // filePath while this task waited in the queue, and every later decision
            // (dest name, rename guard, sidecar cleanup) must use the current row.
            var task = repository.find(initialTask.id) ?: initialTask
            val originalFilePath = initialTask.filePath
            try {
                if (!isActive) return@launch
                var streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(task.directUrl)
                val isMagnet = streamUrl.startsWith("magnet:", ignoreCase = true)
                val isSocial = task.showTitle.startsWith("Social/", ignoreCase = true) || task.backend.contains("yt-dlp")
                var permUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(
                    task.selectedMirrorUrl.ifBlank { task.sourceUrl }.ifBlank { streamUrl }
                )

                val bypassHealth = runtimeRegistry.consumeRetryBypass(task.id)
                if (bypassHealth) ResolveBudget.forgetTask(task.id)

                // Zero-Latency Resume: Check if streamUrl is already direct
                val isAlreadyDirect = isDirectMediaUrl(streamUrl) && !isKnownLockerHost(streamUrl)

                if (!isMagnet && !isSocial && !isAlreadyDirect) {
                    repository.update(task.id) { it.copy(status = TaskStatus.RESOLVING) }
                    updateServiceState(force = true)

                    val resolved = try {
                        kotlinx.coroutines.withTimeout(RESOLVE_TIMEOUT_MS) {
                            val candidates = if (task.mirrorUrls.isEmpty()) listOf(permUrl) else
                                MirrorPool.eligible(task.sourceUrl, task.mirrorUrls, permUrl, bytesLanded(task) > 0L)
                            val ordered = ResolveBudget.orderCandidates(candidates)
                            var result: String? = null
                            for (candidate in ordered) {
                                coroutineContext.ensureActive()
                                val output = try {
                                    resolveStreamUrl(candidate, task.site, task.quality ?: defaultQuality, bypassHealth = bypassHealth, allowCacheHit = true, memoTaskId = task.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) { null }
                                if (!output.isNullOrBlank()) {
                                    ResolveBudget.recordHostOk(candidate)
                                    permUrl = candidate
                                    if (task.mirrorUrls.isNotEmpty()) repository.update(task.id) {
                                        it.copy(selectedMirrorUrl = candidate)
                                    }
                                    result = output
                                    break
                                } else {
                                    ResolveBudget.recordHostFullPassFail(candidate)
                                }
                            }
                            result
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        com.anonrode.downloader.util.DebugLog.error("task=${task.id} resolution timed out after ${RESOLVE_TIMEOUT_MS / 1000}s")
                        if (attemptCrossProviderFailover(task)) return@launch
                        throw Exception("Link resolution timed out — the site took too long to answer. Retry, or try another server/episode.")
                    }
                    if (!resolved.isNullOrBlank()) {
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolved -> ${resolved.take(120)}")
                        runtimeRegistry.getOrCreate(task.id).resetParkingAndFailoverState()
                        ResolveBudget.forgetTask(task.id)
                        streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(resolved)
                    } else if (isKnownLockerHost(streamUrl) || !isDirectMediaUrl(streamUrl)) {
                        if (task.site.isNotBlank()) {
                            val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(streamUrl)
                            com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} (host=$host) — failing cleanly")
                            val hostReason = com.anonrode.downloader.pipeline.HostHealth.lastReason(streamUrl)
                            val reasonSuffix = if (!hostReason.isNullOrBlank()) " ($hostReason)" else ""
                            com.anonrode.downloader.util.DebugLog.error("task=${task.id} could not crack stream link (host=$host)$reasonSuffix for ${streamUrl.take(120)}")
                            if (attemptCrossProviderFailover(task)) return@launch
                            // No source found: a resolver chain that came back
                            // with zero candidates AND failover that exhausted
                            // every other provider. Record the title for the
                            // no-source ledger — once here, outside every
                            // per-candidate loop. Gated on host usability so a
                            // task merely parking out a cooldown (not a
                            // terminal verdict) is never recorded.
                            if (com.anonrode.downloader.pipeline.HostHealth.isUsable(streamUrl)) {
                                com.anonrode.downloader.pipeline.NoSourceLedger.recordUnresolved(
                                    task.showTitle.ifBlank { task.episodeTitle }
                                )
                            }
                            if (!com.anonrode.downloader.pipeline.HostHealth.isUsable(streamUrl)) {
                                val mins = maxOf(1L, com.anonrode.downloader.pipeline.HostHealth.remainingBackoffMs(streamUrl) / 60_000L)
                                com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} host $host in backoff — parking for ~${mins}m")
                                val entry = runtimeRegistry.getOrCreate(task.id)
                                val cycles = entry.parkCycleCount.incrementAndGet()
                                val landed = bytesLanded(task)
                                if (cycles > MAX_PARK_CYCLES && landed == 0L) {
                                    entry.resetParkingAndFailoverState()
                                    throw Exception(
                                        "$host has been unreachable across $MAX_PARK_CYCLES cooldown cycles — giving up so the queue is not stuck on it. " +
                                            "Tap retry once the site is back."
                                    )
                                }
                                entry.firstParkedAtMs.compareAndSet(0L, System.currentTimeMillis())
                                entry.requeueNotBeforeMs.set(System.currentTimeMillis() + parkFloorMs(cycles))
                                ResolveBudget.forgetTask(task.id)
                                val saved = if (landed > 0) " — ${(landed / (1024L * 1024L)).coerceAtLeast(1)} MB already saved, it will resume" else ""
                                repository.update(task.id) {
                                    it.copy(
                                        status = TaskStatus.PAUSED,
                                        speedBytesPerSec = 0.0,
                                        errorMessage = "$PARKED_HOST_MESSAGE — will retry in ~${mins}m$saved"
                                    )
                                }
                                updateServiceState(force = true)
                                return@launch
                            }
                            throw Exception("Could not get a download link from the file host ($host) — it is not responding right now or the link expired. Try again in a few minutes, or choose another server/episode.")
                        }
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} resolver chain EMPTY for ${streamUrl.take(120)} — falling back to yt-dlp extractor")
                        android.util.Log.w("AnonDownload", "Resolver chain empty for $streamUrl, handing to yt-dlp")
                    }
                }

                val isHlsStream = com.anonrode.downloader.pipeline.UrlClassifier.isStreamingLink(streamUrl)
                val isProvablyDirect = isProvablyDirectFile(streamUrl) || isDirectMediaUrl(streamUrl) || isHlsStream
                val isEmbedOrPage = !isMagnet && !isProvablyDirect && run {
                    if (task.site.isNotBlank()) {
                        false
                    } else if (!isSocial) {
                        val tp = com.anonrode.downloader.data.net.HttpClient.probeTerminal(
                            streamUrl,
                            referer = getRefererForUrl(streamUrl)
                        )
                        tp?.totalBytes == null
                    } else {
                        true
                    }
                }
                val finalBackend = if (isSocial || isHlsStream || isEmbedOrPage || task.audioOnly) "yt-dlp" else "aria2c"
                val isExtractor = isSocial || task.audioOnly || isEmbedOrPage
                val effectiveSockets = com.anonrode.downloader.pipeline.HostPolicyRegistry.effectiveSocketsForUrl(
                    streamUrl,
                    task.parallelSockets
                )

                coroutineContext.ensureActive()

                // Synchronize file extension with the resolved direct media URL if no bytes have landed yet
                task = repository.find(task.id) ?: task
                if (!isMagnet && finalBackend == "aria2c" && bytesLanded(task) == 0L) {
                    val resolvedExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(streamUrl)
                    val currentFile = File(task.filePath)
                    if (resolvedExt != null && !currentFile.extension.equals(resolvedExt, ignoreCase = true)) {
                        val updatedFile = File(currentFile.parentFile, "${currentFile.nameWithoutExtension}.$resolvedExt")
                        // Same reservation lock as enqueue(): without it a concurrent
                        // enqueue can pick this exact path between our snapshot and
                        // our update, and two tasks end up owning one destination.
                        val renamed = synchronized(enqueueLock) {
                            val pathTaken = repository.snapshot().any { it.id != task.id && it.filePath == updatedFile.absolutePath }
                            if (!pathTaken && !updatedFile.exists()) {
                                repository.update(task.id) { it.copy(filePath = updatedFile.absolutePath) }
                                true
                            } else false
                        }
                        if (renamed) {
                            task = repository.find(task.id) ?: task.copy(filePath = updatedFile.absolutePath)
                        }
                    }
                }

                repository.update(task.id) { t ->
                    t.copy(
                        status = TaskStatus.DOWNLOADING,
                        directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || isHlsStream) streamUrl else t.directUrl
                    )
                }
                updateServiceState(force = true)

                // Launch unified ProgressWatchdog
                watchdogJob = progressWatchdog.launchForTask(
                    scope = this,
                    initialTask = task,
                    streamUrl = streamUrl,
                    stallTimeoutSecProvider = { stallTimeoutSec }
                )


                val targetFolder = getDownloadDirectory(task.showTitle, createDirs = true)
                // Android 11+ direct writes to public Downloads silently fail
                // without "All files access" (MANAGE_EXTERNAL_STORAGE) — fail
                // fast with the fix instead of a confusing mid-download IO
                // error that looks like the site broke.
                if (!targetFolder.isDirectory || !targetFolder.canWrite()) {
                    throw Exception(
                        "Storage permission missing — downloads are saved to your Downloads folder. " +
                            "Enable \"All files access\" for Anonrode (Settings > Apps > Anonrode > Permissions)."
                    )
                }
                // Header propagation: honor task headers first (from queue/recipe),
                // falling back to host policy rules from OTA playbook, and finally default UA.
                val taskReferer = task.headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
                val taskUa = task.headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
                var refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                var uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                fun buildHeaders(ref: String, ua: String): MutableMap<String, String> {
                    val m = mutableMapOf("User-Agent" to ua)
                    if (ref.isNotBlank()) m["Referer"] = ref
                    for ((k, v) in task.headers) {
                        if (!k.equals("user-agent", ignoreCase = true) && !k.equals("referer", ignoreCase = true)) {
                            m[k] = v
                        }
                    }
                    return m
                }
                var hdrs = buildHeaders(refererToPass, uaToPass)

                // HLS pre-flight: probe the segment CDN before yt-dlp so a dead
                // stream host fails cleanly in seconds instead of pinning the
                // task at 0 bytes for minutes (watchdog kills + 3 attempts).
                // Also returns a locally rewritten master (scheme-relative
                // segment URLs fixed to absolute https) for yt-dlp to consume.
                var hlsMasterFile: File? = null
                // Out-holder for the preflight's HLS size estimate (bytes,
                // 0 = none). Kept current across every preflight invocation
                // so a future consumer always sees the latest estimate.
                val hlsSizeEstimate = LongArray(1)
                // Out-holder for the real stream resolution from the master
                // playlist (e.g. "1080p"), shown on the download card.
                val hlsResolution = arrayOfNulls<String>(1)
                if (finalBackend == "yt-dlp" && isHlsStream) {
                    try {
                        hlsMasterFile = preflightHls(
                            context, task.id, streamUrl, refererToPass, hlsSizeEstimate,
                            task.quality ?: defaultQuality, hlsResolution
                        )
                    } catch (e: StaleStreamLinkException) {
                        // A persisted HLS URL carries a token that dies within
                        // hours — and the router trusts .m3u8 as "already
                        // direct", so a resumed/retried task NEVER re-resolved
                        // and looped 403s forever (live log 18:39: five starts,
                        // all 403). Go back to the source page for a fresh one.
                        com.anonrode.downloader.util.DebugLog.resolve("task=${task.id} ${e.message}")
                        val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (fresh.isNullOrBlank() || fresh == streamUrl) {
                            throw Exception("Stream link expired and no fresh link could be fetched — try again later.")
                        }
                        streamUrl = fresh
                        refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                        uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                        hdrs = buildHeaders(refererToPass, uaToPass)
                        repository.update(task.id) { t ->
                            t.copy(
                                directUrl = if (isDirectMediaUrl(streamUrl) || isProvablyDirectFile(streamUrl) || isHlsStream) streamUrl else t.directUrl
                            )
                        }
                        hlsMasterFile = preflightHls(
                            context, task.id, streamUrl, refererToPass,
                            hlsSizeEstimate, task.quality ?: defaultQuality, hlsResolution
                        )
                    }
                }

                // Seed the estimated total into the progress display so the
                // UI shows "3.2 GB" immediately instead of "0 B" until yt-dlp
                // emits its first parsed line (the segment-sampling estimator
                // is the most accurate pre-download projection available).
                if (hlsSizeEstimate[0] > 0L) {
                    repository.updateProgress(task.id, downloaded = 0L, total = hlsSizeEstimate[0], speed = 0.0, eta = 0L)
                }
                // Same for the resolution: show the ACTUAL stream quality the
                // master offers at/below the requested height.
                hlsResolution[0]?.let { r ->
                    repository.update(task.id) { t -> t.copy(resolution = r) }
                }

                var producedFile: File? = null
                var turboFailure: TurboDownloader.TurboResult.Failure? = null
                var turboResult: TurboDownloader.TurboResult? = null
                var aria2Error: String? = null

                if (finalBackend == "aria2c" && !isMagnet) {
                    var dest = File(targetFolder, File(task.filePath).name)
                    var hdrs = buildHeaders(refererToPass, uaToPass)

                    // Sidecar state is needed BEFORE the pre-flight: a 100%
                    // complete file (paused during VALIDATING after Turbo moved
                    // .part -> dest) must never be re-fed to aria2c — `-c` on a
                    // finished file is HTTP 416 on Range-honoring servers or a
                    // 200-truncate-and-rewrite on Range-ignoring ones (O1).
                    val partFile = File(dest.absolutePath + ".part")
                    val turboSidecar = File(dest.absolutePath + ".turbo")
                    val ariaControlFile = File(dest.absolutePath + ".aria2")

                    // Repair an interrupted prefix handoff (audit finding 1): a
                    // .turbo WITHOUT its .part means the sidecar's plan refers to
                    // bytes that were renamed into dest — a Turbo sidecar resume
                    // would skip those ranges while writing a fresh .part and
                    // zero-fill the completed prefix. Truncate dest to the
                    // sidecar's contiguous prefix (the handoff's lost step) and
                    // drop the stale sidecar, so the state falls through to the
                    // dest-partial (aria2c) resume below — or to O1 if complete.
                    if (!partFile.exists() && turboSidecar.exists() && dest.exists()) {
                        val staleSidecar = turboSidecar
                        val handoffPrefix = try {
                            TurboState(staleSidecar).contiguousPrefixBytes()
                        } catch (_: Exception) {
                            null
                        }
                        if (handoffPrefix != null && handoffPrefix >= 0L && handoffPrefix < dest.length()) {
                            try {
                                RandomAccessFile(dest, "rw").use { it.setLength(handoffPrefix) }
                            } catch (_: Exception) {}
                        }
                        try { TurboState(staleSidecar).delete() } catch (_: Exception) {}
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} repaired interrupted handoff: dest+.turbo state, prefix=$handoffPrefix"
                        )
                    }

                    val alreadyCompleteOnDisk = dest.exists() &&
                        !partFile.exists() && !turboSidecar.exists() && !ariaControlFile.exists() &&
                        ((task.totalBytes > 0L && dest.length() == task.totalBytes) ||
                            (task.totalBytes <= 0L && isImageFile(dest) && dest.length() >= 1024L &&
                                !looksLikeHtml(dest) && isImageValid(dest)) ||
                            (task.totalBytes <= 0L && dest.length() >= 50 * 1024L &&
                                !looksLikeHtml(dest) && decodeCheck(dest)))
                    if (alreadyCompleteOnDisk) {
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} alreadyCompleteOnDisk bytes=${dest.length()} — skipping both engines (zero redo)"
                        )
                        producedFile = dest
                    }

                    // A real aria2c partial resumes via its control file; a
                    // dest-with-bytes partial (no .part) also resumes via aria2c.
                    // A Turbo .part keeps Turbo's own sidecar-driven resume; a
                    // stale .turbo WITHOUT its .part was repaired above.
                    val hasExistingAria2Partial = !alreadyCompleteOnDisk && (ariaControlFile.exists() ||
                        (dest.exists() && dest.length() > 0L && !partFile.exists()))

                    // Pre-flight runs ONLY for an actual resume: on the fresh path
                    // Turbo's single-RT probe (status + magic-byte sniff) IS the
                    // pre-flight, and a complete-on-disk file needs no URL
                    // validation at all — an expired token must never kill a
                    // finished download (O4 closure + O1).
                    if (!isSocial && !alreadyCompleteOnDisk && hasExistingAria2Partial) {
                        var rejection = StreamValidator.validateResult(streamUrl, hdrs)
                            if (rejection?.refreshable == true) {
                                coroutineContext.ensureActive()
                                val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality, allowCacheHit = false)
                                if (!fresh.isNullOrBlank() && fresh != streamUrl &&
                                    (isDirectMediaUrl(fresh) || isProvablyDirectFile(fresh))) {
                                    streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                    refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                                    uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                                    hdrs = buildHeaders(refererToPass, uaToPass)
                                    rejection = StreamValidator.validateResult(streamUrl, hdrs)
                                    coroutineContext.ensureActive()
                                    repository.update(task.id) { it.copy(directUrl = streamUrl) }
                                }
                            }
                        rejection?.let {
                            if (task.downloadedBytes == 0L && bytesLanded(task) == 0L && attemptCrossProviderFailover(task)) {
                                return@launch
                            }
                            throw PipelineError.ValidationFailed(it.reason)
                        }
                    }

                    val progressCb: (Long, Long, Long) -> Unit = { got, tot, bps ->
                        val eta = if (bps > 0 && tot > got) (tot - got) / bps else 0L
                        val liveTask = repository.find(task.id)
                        val safeEta = if (eta > 0L) eta else liveTask?.etaSeconds?.takeIf { it > 0L } ?: 0L
                        repository.updateProgress(
                            taskId = task.id,
                            downloaded = got,
                            total = tot,
                            speed = bps.toDouble(),
                            eta = safeEta
                        )
                        updateServiceState(force = false)
                    }

                    if (!alreadyCompleteOnDisk && !hasExistingAria2Partial) {
                        // Step 1: Primary execution via TurboDownloader
                        com.anonrode.downloader.util.DebugLog.engine(
                            "task=${task.id} turbo primary start sockets=$effectiveSockets url=${streamUrl.take(110)}"
                        )
                        turboResult = TurboDownloader.download(
                            url = streamUrl,
                            dest = dest,
                            headers = hdrs,
                            configuredSockets = effectiveSockets,
                            onProgress = progressCb,
                            taskId = task.id
                        )

                        // Step 2: Self-healing token refresh if CDN returned 401/403/404 or HTML decoy page
                        if (turboResult is TurboDownloader.TurboResult.Failure && coroutineContext.isActive) {
                            val tokenExpired = turboResult.httpStatus in listOf(401, 403, 404, 410) || turboResult.htmlPage
                            if (tokenExpired && !isSocial) {
                                val fresh = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality, allowCacheHit = false)
                                if (!fresh.isNullOrBlank() && fresh != streamUrl) {
                                    streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
                                    refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
                                    uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
                                    hdrs = buildHeaders(refererToPass, uaToPass)
                                    repository.update(task.id) { it.copy(directUrl = streamUrl) }

                                    turboResult = TurboDownloader.download(
                                        url = streamUrl,
                                        dest = dest,
                                        headers = hdrs,
                                        configuredSockets = effectiveSockets,
                                        onProgress = progressCb,
                                        taskId = task.id
                                    )
                                }
                            }
                        }

                        when (turboResult) {
                            is TurboDownloader.TurboResult.Success -> {
                                producedFile = turboResult.file
                            }
                            is TurboDownloader.TurboResult.Failure -> {
                                turboFailure = turboResult
                            }
                        }
                    }

                    // Step 3: Run aria2c when resuming an existing .aria2 partial OR
                    // as a single fallback after a RECOVERABLE Turbo failure.
                    // Never hand aria2c a link Turbo proved is an HTML page or a
                    // terminal 401/403/404/410/429 rejection (Fix 1B): aria2c
                    // sniffs nothing, so it would download the error page as
                    // media or burn retries hammering a dead URL.
                    val unrecoverableHttpOrHtml = turboFailure != null && (
                        turboFailure.htmlPage ||
                            turboFailure.httpStatus in listOf(401, 403, 404, 410, 429)
                        )
                    if (producedFile == null && !unrecoverableHttpOrHtml && coroutineContext.isActive) {
                        if (!hasExistingAria2Partial) {
                            handoffTurboPrefixToAria2c(dest)
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} turbo failed (${turboFailure?.message}), falling back once to aria2c"
                            )
                        } else {
                            com.anonrode.downloader.util.DebugLog.engine(
                                "task=${task.id} resuming existing aria2c partial for ${dest.name}"
                            )
                        }
                        try {
                            producedFile = YoutubeDlDownloader.download(
                                context = context,
                                taskId = task.id,
                                sourceUrl = streamUrl,
                                targetDir = targetFolder,
                                preferredFilename = dest.name,
                                backend = "aria2c",
                                referer = refererToPass,
                                origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(refererToPass),
                                customHeaders = hdrs,
                                ua = uaToPass,
                                parallelSockets = effectiveSockets,
                                quality = task.quality ?: defaultQuality,
                                isExtractorTask = false,
                                audioOnly = task.audioOnly,
                                onProgress = { dl, tot, spd, eta ->
                                    repository.updateProgress(task.id, dl, tot, spd, eta)
                                    updateServiceState(force = false)
                                },
                                magnetMaxAttempts = magnetMaxAttempts,
                                ytdlpMaxAttempts = ytdlpMaxAttempts,
                                hlsFragments = hlsFragmentConcurrency,
                                speedLimitKbs = globalSpeedLimitKbs,
                                torrentPeers = torrentPeers,
                                privacyMode = torrentPrivacyMode,
                                estimatedTotal = repository.find(task.id)?.totalBytes ?: task.totalBytes
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            aria2Error = e.message
                        }
                    }
                } else {

                    suspend fun runYtdlp(url: String, masterFile: File?): File? {
                        val runReferer = taskReferer ?: getRefererForUrl(url)
                        val runUa = taskUa ?: getUserAgentForUrl(url)
                        val runHdrs = buildHeaders(runReferer, runUa)
                        return YoutubeDlDownloader.download(
                            context = context,
                            taskId = task.id,
                            sourceUrl = url,
                            targetDir = targetFolder,
                            preferredFilename = File(task.filePath).name,
                            backend = finalBackend,
                            referer = runReferer,
                            origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(runReferer),
                            ua = runUa,
                            customHeaders = runHdrs,
                            parallelSockets = effectiveSockets.coerceIn(1, 16),
                            quality = task.quality ?: defaultQuality,
                            isExtractorTask = isExtractor,
                            audioOnly = task.audioOnly,
                            onProgress = { dl, tot, spd, eta ->
                                repository.updateProgress(
                                    taskId = task.id,
                                    downloaded = dl,
                                    total = tot,
                                    speed = spd,
                                    eta = eta
                                )
                                updateServiceState(force = false)
                            },
                            onTorrentFiles = onTorrentFileSelection,
                            magnetMaxAttempts = magnetMaxAttempts,
                            ytdlpMaxAttempts = ytdlpMaxAttempts,
                            hlsFragments = hlsFragmentConcurrency,
                            speedLimitKbs = globalSpeedLimitKbs,
                            torrentPeers = torrentPeers,
                            privacyMode = torrentPrivacyMode,
                            downloadSubs = downloadSubtitles && isExtractor,
                            subLang = subtitleLanguage,
                            hlsMasterFile = masterFile?.absolutePath,
                            estimatedTotal = if (hlsSizeEstimate[0] > 0L) hlsSizeEstimate[0] else task.totalBytes
                        )
                    }

                    // YoutubeDlDownloader.download throws on final failure (after
                    // ytdlpMaxAttempts), so the re-resolve below must catch it —
                    // otherwise a failed chain skips the fresh-token recovery and
                    // the task dies with the first URL's errors.
                    var firstError: Exception? = null
                    producedFile = try {
                        runYtdlp(streamUrl, hlsMasterFile)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        firstError = e
                        null
                    }
                    // HLS/embed tokenized URLs (master.m3u8?token=...) expire
                    // mid-flight: on failure, re-resolve for a fresh URL and try
                    // once more, mirroring the token refresh on the direct path.
                    // A fresh resolve also lands a fresh edge node/session, which
                    // resets a CDN byte-quota throttle (the observed recovery).
                    // The re-resolved URL invalidates the old rewritten master —
                    // run its own preflight (probe + rewrite) on the retry.
                    if (producedFile == null && coroutineContext.isActive && !isSocial) {
                        val freshUrl = resolveStreamUrl(permUrl, task.site, task.quality ?: defaultQuality)
                        if (!freshUrl.isNullOrBlank() && freshUrl != streamUrl) {
                            android.util.Log.w("AnonDownload", "yt-dlp failed, re-resolving for a fresh URL")
                            streamUrl = freshUrl
                            var freshMaster: File? = null
                            if (isHlsStream) {
                                try {
                                    freshMaster = preflightHls(context, task.id, freshUrl, taskReferer ?: getRefererForUrl(freshUrl), hlsSizeEstimate, task.quality ?: defaultQuality, hlsResolution)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Fresh stream unreachable — report the first
                                    // chain's error instead.
                                    throw firstError ?: e
                                }
                            }
                            try {
                                producedFile = runYtdlp(freshUrl, freshMaster)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Both attempt chains failed; report the first
                                // chain's error (it carries the attempt detail).
                                throw firstError ?: e
                            }
                        }
                    }
                    // Rewritten masters are scratch files in the cache dir —
                    // remove them once the chain is done (they can be large:
                    // 950+ rewritten segment lines).
                    if (hlsMasterFile != null) {
                        try {
                            val cacheDir = File(context.cacheDir, "hls")
                            cacheDir.listFiles { f -> f.name.startsWith("hls-${task.id}") }?.forEach { it.delete() }
                        } catch (_: Exception) {}
                        hlsMasterFile = null
                    }
                    if (producedFile == null && firstError != null) throw firstError
                }

                // A paused or cancelled job must never flip to a final state: the
                // download may have finished right as pause landed. The exception
                // propagates as CancellationException, which the catch below treats
                // as silent, leaving the PAUSED status intact.
                coroutineContext.ensureActive()

                if (producedFile != null && producedFile.exists()) {
                    // Normalize .unknown_video or .matroska extension if present
                    if (producedFile.extension.equals("unknown_video", ignoreCase = true) ||
                        producedFile.extension.equals("matroska", ignoreCase = true) ||
                        producedFile.extension.isBlank()) {
                        val properExt = if (task.filePath.lowercase().endsWith(".mkv") || producedFile.extension.equals("matroska", ignoreCase = true)) "mkv" else "mp4"
                        val corrected = File(producedFile.parentFile, "${producedFile.nameWithoutExtension}.$properExt")
                        if (!corrected.exists() && producedFile.renameTo(corrected)) {
                            producedFile = corrected
                        }
                    }
                    // For non-social downloads, ensure the artifact adopts the clean target filename
                    // if it was mangled by an extractor backend template.
                    if (!isSocial && producedFile.name != File(task.filePath).name) {
                        val targetDest = File(task.filePath)
                        if (targetDest.exists()) {
                            val isImg = targetDest.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
                            val isCorrupt = if (isImg) {
                                targetDest.length() < 1024L || looksLikeHtml(targetDest) || !isImageValid(targetDest)
                            } else {
                                targetDest.length() < 50 * 1024L || looksLikeHtml(targetDest) || !decodeCheck(targetDest)
                            }
                            if (isCorrupt) {
                                targetDest.delete()
                            }
                        }
                        if (!targetDest.exists() && producedFile.renameTo(targetDest)) {
                            producedFile = targetDest
                        }
                    }
                    repository.update(task.id) { it.copy(status = TaskStatus.VALIDATING) }
                    updateServiceState(force = true)
                }

                val validation = if (producedFile != null && producedFile.exists()) {
                    TorrentSecurityShield.validateDownloadedFile(producedFile, targetFolder)
                } else {
                    Pair(false, "File missing")
                }

                val isAudio = task.filePath.lowercase().let { it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".aac") }
                val isImage = (producedFile?.name ?: task.filePath).lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
                val minSize = if (isImage) 1024L else if (isAudio) 10 * 1024L else 50 * 1024L

                // ---- Tiered verification: answer "is it media / complete /
                // playable" with the strongest evidence available, instead of
                // the old ">5MB => trust it" size guess.
                // Tier 0 — transfer truth: non-HLS backends got the exact
                //           content length from the server; matching bytes =
                //           complete by protocol guarantee.
                // Tier 1 — structure: shield magic + atom scan (any known
                //           media atom in the head/tail windows proves a
                //           container, whatever its first box was).
                // Tier 2 — platform decode: MediaExtractor finds decodable
                //           tracks with a duration -> the OS itself confirms
                //           the file is playable media.
                // Tier 3 — HLS estimate: size within 90% of the
                //           segment-sampling estimate.
                val taskTotalBytes = repository.find(task.id)?.totalBytes ?: 0L
                val transferComplete = !isHlsStream && task.backend != "yt-dlp" &&
                    taskTotalBytes > 0 &&
                    producedFile != null && producedFile.exists() &&
                    fileSize(producedFile) >= taskTotalBytes
                val structuralOk = validation.first
                val decodeOk = !structuralOk && !transferComplete &&
                    producedFile != null && producedFile.exists() &&
                    producedFile.length() >= minSize &&
                    !looksLikeHtml(producedFile) && decodeCheck(producedFile)
                val hlsComplete = isHlsStream && hlsSizeEstimate[0] > 0 &&
                    producedFile != null && producedFile.exists() &&
                    producedFile.length() >= 0.9 * hlsSizeEstimate[0]
                val imageOk = isImage && producedFile != null && producedFile.exists() &&
                    producedFile.length() >= minSize && !looksLikeHtml(producedFile) && isImageValid(producedFile)
                val verified = transferComplete || structuralOk || decodeOk || hlsComplete || imageOk
                val path = when {
                    imageOk -> "image"
                    structuralOk -> "structure"
                    transferComplete -> "transfer"
                    decodeOk -> "decode"
                    hlsComplete -> "estimate"
                    else -> "none"
                }
                val note = when {
                    !verified || structuralOk || transferComplete || imageOk -> null
                    decodeOk -> "Downloaded — format not in the known container list, but the system decoder verified it plays."
                    else -> "Downloaded — file size matches the stream estimate."
                }

                if (producedFile != null && producedFile.exists() && fileSize(producedFile) >= minSize
                    && !looksLikeHtml(producedFile) && verified) {
                    // The block above ran without suspension points, so a pause
                    // landing mid-validation could not interrupt it. Re-check
                    // here: a cancelled job must never flip to COMPLETED after
                    // the user pressed pause.
                    coroutineContext.ensureActive()
                    val finalTitle = producedFile.nameWithoutExtension
                    val finalBytes = producedFile.length()
                    val targetPathBeforeCompletion = task.filePath
                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isSocial) finalTitle else it.episodeTitle,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            speedBytesPerSec = 0.0,
                            etaSeconds = 0L,
                            status = TaskStatus.COMPLETED,
                            validationNote = note
                        )
                    }
                    task = repository.find(task.id) ?: task.copy(filePath = producedFile.absolutePath)

                    try {
                        File(producedFile.absolutePath + ".turbo").delete()
                        File(producedFile.absolutePath + ".part").delete()
                        File(producedFile.absolutePath + ".aria2").delete()
                        // Backends can produce a file whose name differs from the
                        // task's target (aria2c writes the URL's extension, e.g.
                        // .mp4, while the task targets .mkv) — without this the
                        // original target's sidecars are orphaned forever. Compare
                        // against the path captured BEFORE the completion write
                        // (task.filePath is the produced path by now). Never sweep
                        // a path another task now owns: the ext-sync rename frees
                        // the old name and a later enqueue may have taken it.
                        val otherTaskPaths = repository.snapshot()
                            .filter { it.id != task.id }
                            .mapTo(HashSet()) { it.filePath }
                        if (producedFile.absolutePath != targetPathBeforeCompletion &&
                            targetPathBeforeCompletion !in otherTaskPaths) {
                            File(targetPathBeforeCompletion + ".turbo").delete()
                            File(targetPathBeforeCompletion + ".part").delete()
                            File(targetPathBeforeCompletion + ".aria2").delete()
                            File(targetPathBeforeCompletion + ".ytdl").delete()
                        }
                        // The extension sync may have renamed the task's target
                        // (.mp4 → .mkv) before download start: the ORIGINAL path's
                        // sidecars are still orphaned unless swept too.
                        if (originalFilePath != task.filePath && originalFilePath != producedFile.absolutePath &&
                            originalFilePath != targetPathBeforeCompletion &&
                            originalFilePath !in otherTaskPaths) {
                            File(originalFilePath + ".turbo").delete()
                            File(originalFilePath + ".part").delete()
                            File(originalFilePath + ".aria2").delete()
                            File(originalFilePath + ".ytdl").delete()
                        }
                        // O3: bounded stem-family sweep — one directory listing
                        // clears every abandoned sidecar generation (a task whose
                        // extension alternated across 3+ runs could strand the
                        // oldest family forever). Sidecars only; data files and
                        // another task's live paths are never selected.
                        // Selection proven in scratch/test_zero_redo.py (section D).
                        val familyStems = setOf(
                            producedFile.nameWithoutExtension,
                            File(targetPathBeforeCompletion).nameWithoutExtension,
                            File(originalFilePath).nameWithoutExtension
                        )
                        val sidecarExts = setOf("part", "turbo", "aria2", "ytdl")
                        producedFile.parentFile?.listFiles()?.forEach { f ->
                            if (!f.isFile) return@forEach
                            val scExt = f.name.substringAfterLast('.', "")
                            if (scExt !in sidecarExts) return@forEach
                            val baseName = f.name.removeSuffix(".$scExt")
                            val basePath = File(f.parentFile, baseName).absolutePath
                            if (basePath == producedFile.absolutePath) return@forEach
                            if (basePath in otherTaskPaths || f.absolutePath in otherTaskPaths) return@forEach
                            var stem = baseName.substringBeforeLast('.', baseName)
                            if (baseName.endsWith(".part")) {
                                // Legacy two-level generation ("X.part.aria2" /
                                // "X.part.turbo"): strip the inner ".part" so the
                                // stem resolves to the data file's stem — but
                                // never touch it when the INNER data path is a
                                // live path of another task (that control file is
                                // its resume bookkeeping).
                                val legacyDataName = baseName.removeSuffix(".part")
                                val legacyDataPath = File(f.parentFile, legacyDataName).absolutePath
                                val legacyStem = legacyDataName.substringBeforeLast('.', "")
                                if (legacyStem.isNotBlank() && legacyStem in familyStems &&
                                    legacyDataPath !in otherTaskPaths) stem = legacyStem
                            }
                            if (stem in familyStems) f.delete()
                        }
                    } catch (_: Throwable) {}

                    // Main-looper hop inside: from this IO coroutine the old
                    // inline scanFile never bound the scanner service and
                    // silently no-opped — downloads stayed invisible to the
                    // gallery until a system rescan (user report 2026-09-13).
                    com.anonrode.downloader.util.MediaScan.notifyFile(context, producedFile)

                    if (completionNotifications) {
                        DownloadService.notifyCompleted(context, if (isSocial) finalTitle else task.episodeTitle)
                    }
                    com.anonrode.downloader.util.DebugLog.write("completed task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes validation=$path")
                    // Terminal cleanup: drop the registry entry so long queues do not
                    // accumulate keys. Guarded so a newer job for this task (a retry
                    // landing mid-completion) is never clobbered, and non-cancelling
                    // so this coroutine can finish its own tail (remove() would
                    // cancel it mid-flight).
                    val selfJob = coroutineContext[Job]
                    val reg = runtimeRegistry.get(task.id)
                    if (reg == null || reg.activeJob === selfJob) {
                        runtimeRegistry.forget(task.id)
                    }
                } else if (producedFile != null && producedFile.exists() && fileSize(producedFile) >= minSize
                    && !looksLikeHtml(producedFile)) {
                    // A real file IS on disk but no tier could verify it. Keep
                    // the file and say so honestly — never auto-delete it and
                    // never re-download over it (that is exactly what the old
                    // FAILED + retry path did, silently destroying the file).
                    coroutineContext.ensureActive()
                    val finalTitle = producedFile.nameWithoutExtension
                    val finalBytes = producedFile.length()
                    repository.update(task.id) {
                        it.copy(
                            filePath = producedFile.absolutePath,
                            episodeTitle = if (isSocial) finalTitle else it.episodeTitle,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            speedBytesPerSec = 0.0,
                            etaSeconds = 0L,
                            status = TaskStatus.COMPLETED,
                            validationNote = "File saved, but its format could not be verified — check that it plays before keeping."
                        )
                    }
                    task = repository.find(task.id) ?: task.copy(filePath = producedFile.absolutePath)
                    // Main-looper hop inside: from this IO coroutine the old
                    // inline scanFile never bound the scanner service and
                    // silently no-opped — downloads stayed invisible to the
                    // gallery until a system rescan (user report 2026-09-13).
                    com.anonrode.downloader.util.MediaScan.notifyFile(context, producedFile)
                    com.anonrode.downloader.util.DebugLog.write("completed-unverified task=${task.id} file=${producedFile.absolutePath} bytes=$finalBytes validation=none")
                    val selfJob = coroutineContext[Job]
                    val reg = runtimeRegistry.get(task.id)
                    if (reg == null || reg.activeJob === selfJob) {
                        runtimeRegistry.forget(task.id)
                    }
                } else {
                    val errReason = when {
                        producedFile != null && looksLikeHtml(producedFile) -> "Server returned an HTML page instead of the file"
                        // Null-check the transfer outcome BEFORE the generic
                        // validation pair: with no file, validation.second is
                        // always "File missing", which used to swallow every
                        // real HTTP status the backends captured (the 429 storm
                        // was logged as a bare "File missing").
                        // An aria2c rate-limit outranks Turbo's earlier non-429
                        // status: THE RULE parks on 429, and parking keys off this
                        // text reaching isRateLimitedError (the aria2c message
                        // carries the real "status=429" line).
                        producedFile == null && !aria2Error.isNullOrBlank() && isRateLimitedError(aria2Error) -> aria2Error!!
                        producedFile == null && turboFailure?.htmlPage == true -> "Server returned an HTML page instead of the file — the link expired; retry to refresh it"
                        producedFile == null && turboFailure?.httpStatus != null -> "Download rejected by server (HTTP ${turboFailure.httpStatus}) — retry to refresh the link"
                        // The aria2c fallback no longer swallows its exceptions
                        // (Fix 4): its message carries the real failure cause.
                        producedFile == null && !aria2Error.isNullOrBlank() -> aria2Error!!
                        producedFile == null -> "Download failed — the server never produced a file"
                        !validation.first -> validation.second
                        else -> "Output file was too small or corrupted"
                    }
                    // THE RULE: a transfer that never produced a file parks
                    // when bytes are banked (partials stay on disk, the
                    // cooldown loop resumes them) or when the server explicitly
                    // said 429. A file that DID land but is garbage (HTML
                    // decoy, too-small output) must still fail loudly — its
                    // bytes are not banked progress, and parking on them would
                    // re-fetch the same garbage forever.
                    val live = repository.find(task.id) ?: task
                    // Restore cross-provider failover for the producedFile==null
                    // path (Fix 1A): a Turbo Failure whose aria2c fallback threw
                    // lands HERE (the aria2c try/catch stores into aria2Error),
                    // and this branch previously went straight to park/fail —
                    // silently disabling failover on broken direct downloads.
                    if (producedFile == null && live.downloadedBytes == 0L && bytesLanded(live) == 0L &&
                        attemptCrossProviderFailover(live)) {
                        return@launch
                    }
                    val parked = producedFile == null &&
                        parkInsteadOfFail(live, errReason, forcePark = isRateLimitedError(errReason))
                    if (!parked) {
                        if (producedFile != null && producedFile.exists() && (looksLikeHtml(producedFile) || fileSize(producedFile) < minSize || !validation.first)) {
                            try {
                                producedFile.delete()
                                File(producedFile.absolutePath + ".part").delete()
                                File(producedFile.absolutePath + ".turbo").delete()
                                File(producedFile.absolutePath + ".aria2").delete()
                            } catch (_: Throwable) {}
                        }
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errReason) }
                        com.anonrode.downloader.util.DebugLog.write("failed task=${task.id} reason=$errReason")
                        com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, task.episodeTitle, errReason)
                        // Terminal FAILED: drop the registry entry exactly like
                        // the COMPLETED path (guarded so a newer job's entry is
                        // never clobbered).
                        val reg = runtimeRegistry.get(task.id)
                        if (reg == null || reg.activeJob === coroutineContext[Job]) {
                            runtimeRegistry.forget(task.id)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled. Nothing may outlive its job: kill the native
                // backend and any in-flight Turbo/resolver HTTP so a cancelled
                // task cannot keep draining data (defense in depth — pause()
                // and cancel() already do this; job cancellation can also come
                // from the queue processor or scope shutdown).
                YoutubeDlDownloader.killProcess(task.id)
                TurboDownloader.cancelTask(task.id)
                // Only cancel global in-flight HTTP for a FULL cancel (marked
                // in runtimeRegistry before the job was cancelled), not when
                // the user paused or the network parked the task — pause()/
                // pauseForNetwork already killed the specific backends, and a
                // global cancelInFlight would kill OTHER tasks' in-flight
                // requests — the cross-talk bug. The repository check stays
                // as a safety net for removals that bypassed cancel(). Even
                // then, only sweep when no other task is running.
                if (runtimeRegistry.isFullyCancelled(task.id) || repository.find(task.id) == null) {
                    if (!hasOtherActiveWork(task.id)) {
                        HttpClient.cancelInFlight()
                    }
                }
                // A pause landing during VALIDATING (after the first
                // ensureActive passed but before the terminal write) leaves the
                // status at VALIDATING — rescue it to PAUSED so the card is not
                // stuck mid-check.
                //
                // Ownership guard (engine-audit P2): a dying job unwinds AFTER
                // the queue may already have started its replacement (network
                // re-tag parks then requeues within the same tick). Writing
                // PAUSED over the live replacement's DOWNLOADING lied to the
                // user and silently disabled that run's watchdog. Rescue only
                // when no NEWER live job owns the task.
                val owner = runtimeRegistry.getActiveJob(task.id)
                if (owner == null || owner === coroutineContext[Job]) {
                    repository.update(task.id) { t ->
                        if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
                            t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                        } else t
                    }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    val errMsg = e.message ?: "Download error"
                    // THE RULE again: any backend chain that finally threw —
                    // including the "yt-dlp failed after 3 attempt(s)" wrap of
                    // a 429 storm — parks when bytes are banked or the server
                    // explicitly asked us to wait (StreamValidator's
                    // "HTTP 429" rejection throws into this catch).
                    val live = repository.find(task.id) ?: task
                    if (live.downloadedBytes == 0L && bytesLanded(live) == 0L && attemptCrossProviderFailover(live)) {
                        return@launch
                    }
                    if (!parkInsteadOfFail(live, errMsg, forcePark = isRateLimitedError(errMsg))) {
                        repository.update(task.id) { it.copy(status = TaskStatus.FAILED, errorMessage = errMsg) }
                        com.anonrode.downloader.service.DownloadService.notifyFailed(context, task.id, task.episodeTitle, errMsg)
                        // Terminal FAILED cleanup, same rule + guard as the
                        // produced-file FAILED path above.
                        val reg = runtimeRegistry.get(task.id)
                        if (reg == null || reg.activeJob === coroutineContext[Job]) {
                            runtimeRegistry.forget(task.id)
                        }
                    }
                } else {
                    // Cancelled (pause/cancel/network park): native-process teardown can surface
                    // as a plain exception, so never report that as FAILED. Also unwedge a task
                    // that was still marked DOWNLOADING when the job was cancelled.
                    repository.update(task.id) { t ->
                        if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING) {
                            t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                        } else t
                    }
                }
            } finally {
                watchdogJob?.cancel()
                val thisJob = coroutineContext[Job]
                val entry = runtimeRegistry.get(task.id)
                val ownedByThisJob = entry != null && thisJob != null && entry.activeJob === thisJob
                if (ownedByThisJob && entry != null) {
                    entry.activeJob = null
                }
                // Never clear a newer job's start flag: release (and drop the
                // full-cancel flag) only when this job still owns the entry or
                // nobody does.
                if (entry == null || entry.activeJob == null) {
                    runtimeRegistry.releaseStartLock(task.id)
                    // This job's Turbo interaction has fully unwound by now; the
                    // flag must not linger for tasks that never re-enter Turbo.
                    TurboDownloader.clearCancelledFlag(task.id)
                }
                // A non-Exception throwable (Error) skips both catch blocks above:
                // repair a still-active row we own so a dead job can never hold a
                // concurrency slot forever. A row owned by a NEWER job is untouched.
                if (ownedByThisJob) {
                    val liveRow = repository.find(task.id)
                    if (liveRow != null && (liveRow.status == TaskStatus.DOWNLOADING || liveRow.status == TaskStatus.RESOLVING || liveRow.status == TaskStatus.VALIDATING)) {
                        repository.update(task.id) { t ->
                            if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING || t.status == TaskStatus.VALIDATING) {
                                t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                            } else t
                        }
                    }
                }
                // Rewritten HLS masters live in cacheDir/hls as scratch files
                // (950+ lines each). The inline sweep only ran on the success
                // path; a throw or cancel anywhere in the chain leaked them
                // until the OS reclaimed the cache (engine-audit P2). This
                // finally covers every exit; scoped by taskId so a concurrent
                // job for another task is never touched.
                try {
                    File(context.cacheDir, "hls").listFiles { f -> f.name.startsWith("hls-${task.id}") }
                        ?.forEach { it.delete() }
                } catch (_: Exception) {}
                updateServiceState(force = true)
                processQueue()
            }
        }

        runtimeRegistry.attachJob(initialTask.id, job)
        val current = repository.find(initialTask.id)
        if (current == null || current.userPaused || current.status !in setOf(TaskStatus.RESOLVING, TaskStatus.DOWNLOADING, TaskStatus.VALIDATING)) {
            job.cancel()
            // A pause landing after processQueue's status flip leaves the row in
            // RESOLVING/DOWNLOADING while this never-started LAZY job is killed
            // (its finally will never run): repair the row here, otherwise the
            // phantom active status holds a concurrency slot forever.
            if (current != null && current.userPaused &&
                (current.status == TaskStatus.RESOLVING || current.status == TaskStatus.DOWNLOADING)) {
                repository.update(initialTask.id) { t ->
                    if (t.userPaused && (t.status == TaskStatus.RESOLVING || t.status == TaskStatus.DOWNLOADING)) {
                        t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0)
                    } else t
                }
            }
        } else {
            // LAZY + attachJob-first: the coroutine cannot execute before it is
            // registered, closing the window where a running job was invisible
            // to runtimeRegistry.activeRunningTaskIds().
            job.start()
        }
        // A job cancelled before it ever started (or one that finished this fast)
        // would otherwise leave a dead entry in runtimeRegistry — a stale key that
        // suppresses the runtimeRegistry.isEmpty()/none{} cancel-sweep heuristics.
        // Conditional remove: never clobbers a newer job for the same task.
        if (!job.isActive) {
            val entry = runtimeRegistry.get(initialTask.id)
            if (entry != null && entry.activeJob === job) {
                entry.activeJob = null
            }
            // The task row itself is gone (cancelled while this start was being
            // registered): drop the entry — no future run can ever clean it.
            if (current == null) {
                runtimeRegistry.forget(initialTask.id)
            }
        }
    }

    // ------------------------------------------------------------------
    // Bulk queue actions (Pause all / Resume all / Cancel all).
    //
    // The activity log showed users hand-tapping pause/cancel on card after
    // card (13 cancels in 2 seconds) because the queue screen had no bulk
    // actions. These run the SAME per-task teardown as the single-task
    // pause()/retry()/cancel() paths above — deliberately line-for-line,
    // so a bulk action can never diverge from what one card tap does —
    // with the service-state/processQueue churn batched to once per action
    // instead of once per task.

    /** Pause every task the user could pause card-by-card: running
     *  (DOWNLOADING/RESOLVING/VALIDATING) via pause()'s exact teardown, and
     *  QUEUED via pause()'s status write (a card tap on a queued card does
     *  the same). QUEUED tasks are included deliberately: each dying job's
     *  finally-guard calls processQueue(), which would otherwise start the
     *  queued tasks behind the user's back right after "Pause all".
     *  userPaused=true marks every one, so no auto-resume path (network
     *  reconnect, storage self-heal, host cooldown) will touch them. */
    fun pauseAll() {
        val pausable = repository.tasks.value.filter {
            it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.RESOLVING ||
                it.status == TaskStatus.VALIDATING || it.status == TaskStatus.QUEUED
        }
        if (pausable.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("pause ALL (${pausable.size} tasks)")
        pausable.forEach { task ->
            val taskId = task.id
            // Same teardown as pause() — active jobs are cancelled, backends
            // killed, blocking HTTP swept once no task is left running.
            runtimeRegistry.getActiveJob(taskId)?.cancel()
            val entry = runtimeRegistry.get(taskId)
            if (entry != null) {
                entry.activeJob = null
                entry.isStarting.set(false)
            }
            YoutubeDlDownloader.killProcess(taskId)
            TurboDownloader.cancelTask(taskId)
            com.anonrode.downloader.util.DebugLog.user("pause $taskId")
            repository.update(taskId) { t ->
                // Status-guarded (engine-audit P2): a task that COMPLETED inside
                // the snapshot window must not be stamped PAUSED+userPaused —
                // that produced a "Paused at 100%" card with no auto-resume.
                if (t.status == TaskStatus.DOWNLOADING || t.status == TaskStatus.RESOLVING ||
                    t.status == TaskStatus.VALIDATING || t.status == TaskStatus.QUEUED
                ) t.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null, userPaused = true)
                else t
            }
        }
        // pause() sweeps in-flight HTTP only when the last job is gone; with
        // everything paused that condition holds by definition.
        if (runtimeRegistry.activeRunningTaskIds().isEmpty()) HttpClient.cancelInFlight()
        // One service/notification update for the whole batch, and NO
        // processQueue(): "Pause all" means everything stops, so the free
        // slots must not be refilled from the (now also paused) queue.
        updateServiceState(force = true)
    }

    /** Resume every PAUSED task via retry()'s exact path — including the ones
     *  carrying the userPaused mark: this IS the explicit user resume, the
     *  one path allowed to clear it. One processQueue() fills the slots. */
    fun resumeAll() {
        val paused = repository.tasks.value.filter { it.status == TaskStatus.PAUSED }
        if (paused.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("resume ALL (${paused.size} tasks)")
        paused.forEach { task ->
            val taskId = task.id
            // Same per-task body as retry(): one host-health bypass each, a
            // fresh park/failover history, and the userPaused mark cleared.
            runtimeRegistry.markForManualRetry(taskId)
            repository.update(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null, userPaused = false) }
        }
        processQueue()
        updateServiceState(force = true)
    }

    /** Cancel every non-terminal task (QUEUED/DOWNLOADING/RESOLVING/VALIDATING/
     *  PAUSED) via cancel()'s exact teardown. Destructive — the UI confirms
     *  before calling this. Partial files are removed per task exactly as a
     *  single cancel does. */
    fun cancelAll() {
        val cancellable = repository.tasks.value.filter {
            it.status == TaskStatus.QUEUED || it.status == TaskStatus.DOWNLOADING ||
                it.status == TaskStatus.RESOLVING || it.status == TaskStatus.VALIDATING ||
                it.status == TaskStatus.PAUSED
        }
        if (cancellable.isEmpty()) return
        com.anonrode.downloader.util.DebugLog.user("cancel ALL (${cancellable.size} tasks)")
        cancellable.forEach { task ->
            val taskId = task.id
            // Same teardown as cancel(): mark full-cancel intent BEFORE job
            // cancellation, kill the backends, remove partial artifacts.
            val hadLiveJob = runtimeRegistry.getActiveJob(taskId) != null
            runtimeRegistry.markFullyCancelled(taskId)
            runtimeRegistry.getActiveJob(taskId)?.cancel()
            val entry = runtimeRegistry.get(taskId)
            if (entry != null) {
                entry.activeJob = null
                entry.isStarting.set(false)
            }
            YoutubeDlDownloader.killProcess(taskId)
            // Only gate a Turbo unwind; a queued task's flag would linger forever.
            TurboDownloader.cancelTask(taskId, isFullCancel = hadLiveJob)
            com.anonrode.downloader.util.DebugLog.user("cancel $taskId")
            val live = repository.find(taskId)
            // Cancel only what is STILL cancellable (engine-audit P2): if the
            // task completed between the snapshot and here, purging its
            // artifacts would delete a finished download and remove() would
            // drop its history entry.
            if (live != null && (live.status == TaskStatus.QUEUED || live.status == TaskStatus.DOWNLOADING ||
                    live.status == TaskStatus.RESOLVING || live.status == TaskStatus.VALIDATING ||
                    live.status == TaskStatus.PAUSED)
            ) {
                engineScope.launch {
                    purgeTaskArtifacts(live)
                }
                repository.remove(taskId)
            }
            runtimeRegistry.remove(taskId)
        }
        // Every job was cancelled above, so a global in-flight sweep cannot
        // cross-talk into another task — the condition cancel() relies on.
        if (runtimeRegistry.activeRunningTaskIds().isEmpty()) HttpClient.cancelInFlight()
        updateServiceState(force = true)
        processQueue()
    }
}
```

---

## Section 5: Pure-Kotlin Segmented Engine (`TurboDownloader.kt` — Complete File)
File: `app/src/main/java/com/anonrode/downloader/engine/TurboDownloader.kt`

```kotlin
package com.anonrode.downloader.engine

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.net.isTlsChainFailure
import com.anonrode.downloader.pipeline.HostPolicyRegistry
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * High-performance multi-socket segmented downloader for direct CDN files.
 *
 * Designed with:
 * 1. Definitive Range verification (probe with fallback bytes=0-1024 GET).
 * 2. Decoupled telemetry ticker (250ms cadence) with EMA speed smoothing.
 * 3. Non-blocking parallel worker streams writing to pre-allocated FileChannels.
 * 4. Throttled sidecar resume state commits.
 * 5. BitTorrent-style shared piece queue with per-piece retry and exponential
 *    backoff: a flaky connection costs one piece, not the whole download.
 */
object TurboDownloader {

    private const val BUFFER = 256 * 1024 // 256 KB read buffer (okio returns <=8 KiB per read())
    private const val MIN_SEGMENTED_SIZE = 2L * 1024 * 1024 // 2 MB — small files get parallel lanes too
    private const val MAX_ATTEMPTS = 5 // retries per piece / per single-stream attempt

    // Drop-and-replace (1DM behavior): a lane that lags its siblings after
    // warm-up gets replaced with a FRESH connection. Semantics validated in
    // scratch/test_drop_replace.py before this port. NEVER consumes attempts.
    // Directivete Fix 4: 4s windows/warm-up halve detection latency (first
    // possible drop now t=8s, was t=16s).
    private const val DROP_WINDOW_MS = 4_000L
    private const val DROP_WARMUP_MS = 4_000L
    private const val DROP_FLOOR_BPS = 40L * 1024
    private const val MAX_DROPS_PER_PIECE = 4
    private const val DROP_STRIKE_LIMIT = 2
    private const val DROP_RELATIVE_FRACTION = 0.3
    private const val DROP_RETRY_DELAY_MS = 250L
    // Severe relative lag: when a fast sibling field exists (>= 512 KiB/s),
    // even a lane ABOVE the absolute floor is dropped if it runs below 12% of
    // the best window (the 55KB/s-among-1.5MB/s case the floor alone missed).
    private const val SEVERE_LAG_MIN_BEST_BPS = 512L * 1024
    private const val SEVERE_LAG_FRACTION = 0.12

    // Read coalescing (directive Fix 5): Okio returns at most one 8 KiB segment
    // per read(), so a naive loop causes one pwrite64 syscall + atomic update
    // per 8 KiB (131k syscalls/GiB). Coalesce up to 64 KiB per write, stopping
    // early on a short read so we never block for network when data is ready.
    private const val COALESCE_TARGET_BYTES = 64 * 1024
    private const val OKIO_SEGMENT_SIZE = 8192

    // Work-stealing (directive Fix 3, corrected design D2a/D2b): when all
    // initial pieces are claimed, a free worker splits the ACTIVE piece with
    // the largest remaining range. Threshold 512 KiB (not 2 MiB: pieces here
    // are 1 MiB, so a 2 MiB threshold could never fire); the cap counts
    // SPLITS (not total pieces: initial plans hold up to 2048 pieces).
    // Validated in scratch/test_work_steal.py before this port.
    private const val STEAL_MIN_REMAINING_BYTES = 512L * 1024
    private const val MAX_DYNAMIC_SPLITS = 32
    private const val RETRY_CAP = 8L // backoff ceiling, in multiples of the base delay

    /** Test hook: collapses exponential backoff so retry tests run fast. */
    internal var retryBaseDelayMs: Long = 1000L

    /**
     * Rate-limit ladder. HTTP 429 (and 503) are "come back later" answers, and
     * a desktop download manager (IDM/1DM) survives them simply by WAITING.
     * The old policy burned the normal 5-attempt / ~15s piece budget per
     * socket on a 429 — 16 sockets × 5 relaunches is itself a request storm
     * that kept the limiter tripped, and when the budget ran out the WHOLE
     * segmented download died and the engine handed the angry URL to aria2c
     * (activity-log-share-5: 64 MiB banked at ~26 MB/s, then FAILED).
     * Rate-limits are now waits, not attempts: one shared escalating cooldown
     * (base ×2 per cycle, capped at 5 min), every socket sits it out, and the
     * active worker count halves per cycle (16→8→4→2) so the resumed traffic
     * is the gentle shape that never provokes the host again.
     */
    private const val THROTTLE_BASE_MS = 15_000L
    private const val THROTTLE_MAX_MS = 5 * 60_000L

    /** Test hook: collapses rate-limit cooldowns so throttle tests run fast. */
    internal var throttleBaseDelayMs: Long = THROTTLE_BASE_MS

    /**
     * In-flight OkHttp calls per task id. The engine's stall watchdog calls
     * [cancelTask] so a trickling-but-alive transfer can be interrupted even
     * though Turbo has no native process to kill.
     */
    private val activeCalls = ConcurrentHashMap<String, CopyOnWriteArrayList<Call>>()
    private val fullyCancelledTaskIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Interrupt every in-flight transfer of a task. The affected pieces fail,
     * are retried per the normal piece policy, and the task eventually FAILED
     * instead of hanging in DOWNLOADING forever. Idempotent for unknown ids.
     */
    fun cancelTask(taskId: String, isFullCancel: Boolean = false) {
        if (isFullCancel) fullyCancelledTaskIds.add(taskId)
        activeCalls.remove(taskId)?.forEach { it.cancel() }
    }

    fun clearCancelledFlag(taskId: String) {
        fullyCancelledTaskIds.remove(taskId)
    }

    /**
     * Returns actual verified bytes written to disk for [dest], properly handling
     * pre-allocated segmented `.part` files by reading the `.turbo` sidecar first.
     */
    fun computeVerifiedDiskBytes(dest: File): Long {
        val turboFile = File(dest.absolutePath + ".turbo")
        val partFile = File(dest.absolutePath + ".part")
        if (turboFile.exists()) {
            val state = TurboState(turboFile)
            state.writtenBytes()?.let { return it }
            state.contiguousPrefixBytes()?.let { return it }
            return 0L // Pre-allocated .part file with unreadable sidecar has 0 verified bytes
        }
        if (partFile.exists()) {
            return partFile.length()
        }
        if (dest.exists()) {
            return dest.length()
        }
        return 0L
    }

    /**
     * taskId → epoch-millis until which the run is deliberately sitting out a
     * server rate-limit. Bytes will NOT move while a deadline is live — the
     * download is alive and waiting (the "1DM freezes the bar, then resumes"
     * behavior). The engine's stall watchdog consults [throttleRemainingMs]
     * and refuses every kill decision while a cooldown is live, so the
     * in-session wait can never be cut short into a FAILED card. Entries
     * live exactly as long as the run that set them (removed in the
     * segmented()/single() finally blocks).
     */
    private val throttleDeadlines = ConcurrentHashMap<String, Long>()

    /** Milliseconds left on the task's live rate-limit cooldown (0 if none). */
    fun throttleRemainingMs(taskId: String): Long {
        if (taskId.isEmpty()) return 0L
        val deadline = throttleDeadlines[taskId] ?: return 0L
        return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Registers a call against [taskId] (empty = untracked, e.g. tests). */
    private fun trackCall(taskId: String, call: Call) {
        if (taskId.isEmpty()) return
        activeCalls.getOrPut(taskId) { CopyOnWriteArrayList() }.add(call)
        // cancelTask() may have removed the map entry between getOrPut and add:
        // its snapshot then missed this call — cancel it here instead.
        if (!activeCalls.containsKey(taskId)) {
            call.cancel()
        }
    }

    private fun canCommit(taskId: String): Boolean =
        taskId.isEmpty() || !fullyCancelledTaskIds.contains(taskId)

    private fun untrackCall(taskId: String, call: Call) {
        if (taskId.isEmpty()) return
        activeCalls[taskId]?.remove(call)
    }

    /** One shared zero-idle pool for every post-drop client (directive Fix 4:
     *  a fresh pool object per drop was wasteful; sharing keeps sockets from
     *  ever being reused while creating no per-drop garbage). */
    private val zeroIdlePool =
        okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS)

    /** Client for post-drop attempts: zero-idle pool so a fresh connection is
     *  actually fresh (derived from the passed client to keep any permissive
     *  TLS choice the caller made). */
    private fun freshConnectionClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder().connectionPool(zeroIdlePool).build()

    /** Coalesce Okio's <=8 KiB segment reads into one up-to-64 KiB write.
     *  Stops early on a short segment read (socket buffer drained) so the
     *  caller never blocks waiting for packets when bytes are ready. */
    private fun readCoalesced(src: okio.BufferedSource, buf: ByteArray, maxWant: Int): Int {
        val target = minOf(maxWant, COALESCE_TARGET_BYTES)
        var totalRead = 0
        while (totalRead < target) {
            val r = src.read(buf, totalRead, maxWant - totalRead)
            if (r == -1) break
            totalRead += r
            if (r < OKIO_SEGMENT_SIZE) break
        }
        return if (totalRead == 0) -1 else totalRead
    }

    private fun backoffMillis(attempt: Int): Long {
        val factor = (1L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(RETRY_CAP)
        return (factor * retryBaseDelayMs).coerceAtMost(RETRY_CAP * retryBaseDelayMs)
    }

    /**
     * Sockets per download: clamped 1..16 (floor 1, ceiling 16) and capped per
     * host by HostPolicyRegistry — built-ins start wide (6 on kissorgrab /
     * dl.plutomovies, 12 on the wetafiles/downloadwella/loadedfiles lockers —
     * Fix 7) and are OTA-overridable via contentPolicy.socketCaps. Speed
     * policy: start fast; let the 429 ladder, drop-and-replace, and
     * work-stealing adapt per connection.
     */
    fun socketsFor(url: String, configured: Int): Int =
        HostPolicyRegistry.effectiveSocketsForUrl(url, configured)

    /** Outcome of a transfer: either a completed file or a failure with the server status when known. */
    sealed interface TurboResult {
        data class Success(val file: File, val bytes: Long, val segmented: Boolean) : TurboResult

        /** [htmlPage] is set when the probe proved the URL serves HTML, not a media file. */
        data class Failure(val httpStatus: Int?, val message: String, val htmlPage: Boolean = false) : TurboResult
    }

    /** Outcome of the pre-download probe. */
    internal sealed class ProbeResult {
        /** The URL answers with a downloadable file. */
        data class File(val total: Long, val acceptsRanges: Boolean) : ProbeResult()

        /** The URL answers with an HTML page (locker page, expired-token error page). */
        object HtmlPage : ProbeResult()

        /** Terminal server verdict (directive Fix 2): the engine must hear
         *  429/401/403/404/410 immediately instead of watching single() burn
         *  five attempts on a dead or rate-limited link. */
        data class HttpError(val code: Int, val message: String) : ProbeResult()

        /** The server could not be reached or answered without usable headers. */
        data class Unreachable(val total: Long) : ProbeResult()
    }

    private fun atomicMove(src: File, dest: File): Boolean {
        if (!src.exists()) return false
        try {
            // Android is Linux: rename(2) REPLACES an existing dest
            // atomically, so try the rename first. The old unconditional
            // dest.delete() before the rename opened a window where the
            // completed file was gone and nothing had taken its place yet —
            // a crash there destroyed the previous download for nothing.
            if (src.renameTo(dest)) return true
            if (dest.exists()) {
                if (!dest.delete()) return false
                if (src.renameTo(dest)) return true
            }
            // Cross-device or rename-refused: stream-copy, then remove src.
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            src.delete()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Download [url] to [dest]. Returns [TurboResult.Success] on completion or
     * [TurboResult.Failure] with the HTTP status (when the server answered) and a
     * human-readable cause. Cancellation propagates as CancellationException so a
     * paused job never surfaces as a failure.
     * [onProgress] receives (downloadedBytes, totalBytes, bytesPerSecond).
     */
    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        configuredSockets: Int = 8,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        client: OkHttpClient = HttpClient.downloadClient,
        taskId: String = ""
    ): TurboResult = withContext(Dispatchers.IO) {
        // Clear any stale cancellation flag from a prior run of this taskId
        if (taskId.isNotBlank()) {
            fullyCancelledTaskIds.remove(taskId)
        }

        try {
            dest.parentFile?.mkdirs()
            val safe = HttpClient.safeUrl(url)

            var effectiveClient = client
            var probeError: Throwable? = null
            var probe = probe(safe, headers, effectiveClient) { probeError = it }

            // SECURITY FIX: Only fallback to permissiveDownloadClient if the host is on
            // the known broken-intermediate allowlist in HostPolicyRegistry.
            if (probe is ProbeResult.Unreachable &&
                probeError != null &&
                isTlsChainFailure(probeError!!) &&
                HostPolicyRegistry.allowsPermissiveTlsFallback(safe)
            ) {
                com.anonrode.downloader.util.DebugLog.backend(
                    "task=$taskId probe TLS chain failure on allowlisted host (${probeError!!.message?.take(80)}), retrying with permissive client"
                )
                effectiveClient = HttpClient.permissiveDownloadClient
                probeError = null
                probe = probe(safe, headers, effectiveClient) { probeError = it }
            }

            val total: Long
            val acceptsRanges: Boolean
            when (probe) {
                is ProbeResult.File -> {
                    total = probe.total
                    acceptsRanges = probe.acceptsRanges
                }
                ProbeResult.HtmlPage -> {
                    return@withContext TurboResult.Failure(
                        httpStatus = null,
                        message = "Server returned an HTML page instead of a media file",
                        htmlPage = true
                    )
                }
                is ProbeResult.HttpError -> {
                    // Terminal verdict: hand the code to the engine immediately
                    // (its self-heal/park logic keys on 401/403/404/410/429).
                    return@withContext TurboResult.Failure(
                        httpStatus = probe.code,
                        message = probe.message,
                        htmlPage = false
                    )
                }
                is ProbeResult.Unreachable -> {
                    total = probe.total
                    acceptsRanges = false
                }
            }

            val sockets = HostPolicyRegistry.effectiveSocketsForUrl(safe, configuredSockets)
            val useSegmented = acceptsRanges && total > MIN_SEGMENTED_SIZE && sockets > 1

            val partFile = File(dest.absolutePath + ".part")
            val turboSidecar = File(dest.absolutePath + ".turbo")
            val state = TurboState(turboSidecar)
            val failureStatus = AtomicInteger(0)
            val failureMessage = AtomicReference<String?>(null)

            if (useSegmented) {
                // Defense in depth (audit finding 1): a .turbo without its .part
                // means the plan's offsets describe bytes that are no longer in
                // this .part — a sidecar-driven resume would skip those ranges
                // and zero-fill them. A stale plan can never be trusted here;
                // delete it and start a fresh plan (correctness over redo).
                if (!partFile.exists() && turboSidecar.exists()) {
                    TurboState(turboSidecar).delete()
                }
                val ok = segmented(
                    safe, partFile, headers, total, sockets, state,
                    failureStatus, failureMessage, onProgress, effectiveClient, taskId
                )
                if (ok) {
                    state.delete()
                    if (moveVerified(partFile, dest, total)) {
                        TurboResult.Success(dest, dest.length(), true)
                    } else {
                        failure(failureStatus, failureMessage)
                    }
                } else {
                    val landedBytes = state.writtenBytes() ?: 0L
                    val canFallbackToSingle = canCommit(taskId) && coroutineContext.isActive &&
                        (!partFile.exists() || partFile.length() == 0L || landedBytes == 0L)
                    if (canFallbackToSingle) {
                        partFile.delete()
                        state.delete()
                        if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, effectiveClient, taskId)) {
                            if (moveVerified(partFile, dest, total)) {
                                TurboResult.Success(dest, dest.length(), false)
                            } else {
                                failure(failureStatus, failureMessage)
                            }
                        } else {
                            failure(failureStatus, failureMessage)
                        }
                    } else {
                        failure(failureStatus, failureMessage)
                    }
                }
            } else {
                // BUG FIX (Cross-Mode Resume): If transitioning from a pre-allocated segmented
                // `.part` file to single-stream mode, truncate `.part` to the contiguous prefix
                // AND immediately delete the `.turbo` sidecar so future resumes do not re-truncate
                // newly appended single-stream bytes!
                if (partFile.exists() && partFile.length() > 0L && turboSidecar.exists()) {
                    val prefix = state.contiguousPrefixBytes() ?: 0L
                    try {
                        if (prefix < partFile.length()) {
                            RandomAccessFile(partFile, "rw").use { it.setLength(prefix) }
                        }
                        state.delete()
                    } catch (e: Exception) {
                        partFile.delete()
                        state.delete()
                    }
                }
                if (single(safe, partFile, headers, total, failureStatus, failureMessage, onProgress, effectiveClient, taskId)) {
                    state.delete()
                    if (moveVerified(partFile, dest, total)) {
                        TurboResult.Success(dest, dest.length(), false)
                    } else {
                        failure(failureStatus, failureMessage)
                    }
                } else {
                    failure(failureStatus, failureMessage)
                }
            }
        } finally {
            if (taskId.isNotBlank()) {
                activeCalls.remove(taskId)
                fullyCancelledTaskIds.remove(taskId)
            }
        }
    }

    /**
     * Move the verified .part to its final name and PROVE the move landed.
     * [atomicMove]'s return value used to be ignored, so a failed rename
     * (and a copy fallback killed mid-way, e.g. by disk-full) still reported
     * Success — handing the engine a missing or truncated file that the
     * structure tier could bless (faststart MP4 keeps its moov atom at the
     * head, so a truncated file scans as "structured"). With the length
     * known (Content-Length from the probe), only a byte-exact dest counts;
     * with no known length, existence is all the evidence there is.
     */
    private fun moveVerified(partFile: File, dest: File, total: Long): Boolean {
        if (!atomicMove(partFile, dest)) return false
        if (!dest.exists()) return false
        return total <= 0L || dest.length() == total
    }

    /** Builds a Failure outcome, rethrowing cancellation so a paused job never looks like a server error. */
    private suspend fun failure(status: AtomicInteger, message: AtomicReference<String?>): TurboResult.Failure {
        coroutineContext.ensureActive()
        val code = status.get()
        return TurboResult.Failure(code.takeIf { it != 0 }, message.get() ?: "Download failed")
    }

    /**
     * Single-round-trip fast probe (1DM): the first request is a ranged GET
     * (bytes=0-1023 + Accept-Encoding: identity) returning status, total size,
     * range support AND the first bytes for a best-effort media sniff in ONE
     * round trip. Header verdicts decide first; HEAD remains a fallback for
     * non-206/200 answers (e.g. a rate-limited range server whose HEAD still
     * answers 200 + Accept-Ranges). Decision table validated in
     * scratch/test_probe_logic.py before this port.
     */
    private fun probe(url: String, headers: Map<String, String>, client: OkHttpClient, onFailure: (Throwable) -> Unit = {}): ProbeResult {
        fun buildGet() = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            header("Range", "bytes=0-1023")
            header("Accept-Encoding", "identity")
        }.build()

        fun buildHead() = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
            head()
        }.build()

        fun isHtmlPage(contentType: String?): Boolean {
            val ct = contentType?.lowercase() ?: return false
            return ct.startsWith("text/html") || ct.startsWith("application/xhtml")
        }

        fun sniffHead(r: okhttp3.Response): Boolean {
            val head = try {
                r.peekBody(1024).bytes()
            } catch (_: Exception) {
                null // mid-body disconnect: never invalidates a header verdict
            }
            return head != null && head.isNotEmpty() &&
                com.anonrode.downloader.pipeline.StreamValidator.sniff(head) != null
        }

        fun headFallback(carried: Long): ProbeResult {
            var total = carried
            try {
                // Registered so pause/cancel kills the probe too.
                HttpClient.executeRegistered(client.newCall(buildHead())).use { r ->
                    val ct = r.header("Content-Type")
                    val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    val ranges = r.header("Accept-Ranges")?.contains("bytes", true) == true
                    if (r.isSuccessful && len > 0 && !isHtmlPage(ct)) {
                        total = len
                        if (ranges) return ProbeResult.File(total, true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onFailure(e)
            }
            return ProbeResult.Unreachable(total)
        }

        // 1. Ranged GET first — one round trip for status + total + sniff.
        var getStatus: Int? = null
        try {
            HttpClient.executeRegistered(client.newCall(buildGet())).use { r ->
                getStatus = r.code
                val ct = r.header("Content-Type")
                if (isHtmlPage(ct)) return ProbeResult.HtmlPage

                val cr = r.header("Content-Range")
                val totalFromCr = cr?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                val lenHeader = r.header("Content-Length")?.toLongOrNull() ?: -1L

                if (r.code == 206 && totalFromCr > 0) {
                    // Header-proven range support: sniff is best-effort AFTER the
                    // verdict — a body read failure must not discard it
                    // (TurboDownloaderTest.givesUpAfterMaxAttempts).
                    if (sniffHead(r)) return ProbeResult.HtmlPage
                    return ProbeResult.File(totalFromCr, true)
                }
                if (r.code == 200 && lenHeader > 0) {
                    if (sniffHead(r)) return ProbeResult.HtmlPage
                    return ProbeResult.File(lenHeader, false)
                }
                // Deliberately NO carry of Content-Length/Content-Range from
                // error or unusable responses (audit finding 2): an error
                // page's byte length must never become the tracked total.
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            onFailure(e)
        }

        // 2. HEAD fallback for non-206/200 answers (416/429/403/...) and for
        // responses that did not advertise a usable length — ONE try only.
        // Directive Fix 2, corrected: a 429 whose HEAD still answers 200 +
        // Accept-Ranges keeps the segmented path alive (that is the
        // RateLimitedRangeServer storm-survival constraint); only when HEAD is
        // also unusable does the probe fast-fail.
        val hb = headFallback(-1L)
        if (hb is ProbeResult.File) return hb
        val code = getStatus
        if (code != null) {
            if (code == 429) {
                com.anonrode.downloader.pipeline.HostHealth.recordFail(
                    url, rateLimited = true, reason = "HTTP 429 in Turbo probe"
                )
                return ProbeResult.HttpError(429, "Server rate limited request (HTTP 429)")
            }
            if (code in listOf(401, 403, 404, 410)) {
                return ProbeResult.HttpError(code, "Server rejected link (HTTP $code)")
            }
            // 5xx deliberately falls through to Unreachable: the full
            // 5-attempt retry ladder must stay reachable
            // (TurboDownloaderTest.givesUpAfterMaxAttempts).
        }
        return hb
    }

    private suspend fun segmented(
        url: String,
        dest: File,
        headers: Map<String, String>,
        total: Long,
        sockets: Int,
        state: TurboState,
        failureStatus: AtomicInteger,
        failureMessage: AtomicReference<String?>,
        onProgress: (Long, Long, Long) -> Unit,
        client: OkHttpClient,
        taskId: String = ""
    ): Boolean = coroutineScope {
            // Work-stealing appends split pieces mid-run (Fix 3). CopyOnWrite
            // makes every append atomically visible to concurrent commits and
            // telemetry without wrapping a dozen call sites in synchronized —
            // the steal DECISION itself still runs under synchronized(plan)
            // so two workers can never split the same victim.
            val plan = CopyOnWriteArrayList(state.loadOrCreate(total, sockets))
        val initialBytes = plan.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        val done = AtomicLong(initialBytes)
            val failed = AtomicBoolean(false)
            val nextPiece = AtomicInteger(0)
            var dynamicSplits = 0  // work-steal splits this run (Fix 3 budget)
            // Best window throughput seen by ANY lane this run — the relative
            // drop guard: a lane is dropped only when slow BOTH absolutely
            // (< DROP_FLOOR_BPS) and relatively (< 30% of the best sibling
            // window), so a uniformly-slow link (live-verified at ~6-9 KB/s
            // total on the maintainer connection) never churns lanes and never
            // wastes multi-second handshakes. Shared-best scenarios validated
            // in scratch/test_drop_replace.py.
            val bestWindowBps = AtomicLong(0L)
        // Rate-limit coordination: one shared cooldown deadline every socket
        // sits out (the limiter is per-IP — only a PAUSE heals it; retrying
        // now deepens it), a cycle counter driving the escalating ladder, and
        // a worker cap halved per cycle; the shared piece queue lets the
        // survivors drain everything the departed workers would have claimed.
        val throttleUntil = AtomicLong(0L)
        val throttleCycles = AtomicInteger(0)
        val maxWorkers = AtomicInteger(sockets)
        fun enterRateLimit() {
            val cycle = throttleCycles.incrementAndGet()
            val waitMs = (throttleBaseDelayMs shl (cycle - 1).coerceAtMost(5)).coerceAtMost(THROTTLE_MAX_MS)
            val until = System.currentTimeMillis() + waitMs
            throttleUntil.set(until)
            if (taskId.isNotEmpty()) throttleDeadlines[taskId] = until
            maxWorkers.set(maxOf(2, maxWorkers.get() / 2))
            com.anonrode.downloader.util.DebugLog.backend(
                "task=$taskId rate limited (cycle $cycle): all sockets cooling ${waitMs / 1000}s, workers→${maxWorkers.get()}"
            )
        }

        RandomAccessFile(dest, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
        }

        val speed = SpeedMeter(initialBytes)
        // Progress truth = bytes committed to the file, NOT bytes received.
        // `done` counts every socket's reads; with out-of-order pieces the
        // received total runs ahead of what the resume map holds — the Suits
        // download (activity-log-share-5) showed the bar pinned at 100% for
        // 2+ minutes while one slow socket still held the committed prefix
        // 60 MiB short. The engine's watchdog disk feed (computeDiskBytes)
        // reads exactly this sidecar sum, so reporting it here keeps the bar,
        // the watchdog and a future resume all on the same number. Speed
        // still samples `done`: displayed speed is the real transfer rate.
        fun committed(): Long = plan.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        onProgress(initialBytes, total, 0L)

        // Decoupled Telemetry Dispatcher: Ticks every 250ms with smoothed EMA speed
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = done.get()
                val currentSpeed = speed.sample(currentDone)
                onProgress(committed(), total, currentSpeed)
            }
        }

        try {
            RandomAccessFile(dest, "rw").use { raf ->
                val channel: FileChannel = raf.channel

                // Shared piece queue (BitTorrent style): workers pull the next
                // unfinished piece. When every initial piece is claimed, a free
                // worker STEALS: it splits the active piece with the largest
                // remaining range in half and takes the back half (directive
                // Fix 3, "hurry up"). Threshold/budget corrected per
                // scratch/test_work_steal.py: 512 KiB minimum remainder (pieces
                // are 1 MiB, so a 2 MiB threshold could never fire) and the cap
                // counts SPLITS (initial plans hold up to 2048 pieces).
                fun claimOrStealNext(): TurboChunk? {
                    while (true) {
                        val idx = nextPiece.getAndIncrement()
                        if (idx >= plan.size) break
                        val c = plan[idx]
                        if (c.current <= c.end) return c
                    }
                    synchronized(plan) {
                        if (dynamicSplits >= MAX_DYNAMIC_SPLITS) return null
                        var victim: TurboChunk? = null
                        var victimRemaining = 0L
                        for (c in plan) {
                            if (c.current <= c.end) {
                                val remaining = c.end - c.current + 1L
                                if (remaining >= STEAL_MIN_REMAINING_BYTES && remaining > victimRemaining) {
                                    victim = c
                                    victimRemaining = remaining
                                }
                            }
                        }
                        val v = victim ?: return null
                        val splitOffset = v.current + victimRemaining / 2L
                        if (splitOffset <= v.current || splitOffset > v.end) return null
                        val oldEnd = v.end
                        v.end = splitOffset - 1L
                        val stolen = TurboChunk(start = splitOffset, end = oldEnd, current = splitOffset)
                        plan.add(stolen)
                        dynamicSplits++
                        com.anonrode.downloader.util.DebugLog.backend(
                            "task=$taskId work-steal split piece=${v.start}-$oldEnd -> victimEnds=${v.end} stolen=${stolen.start}-${stolen.end}"
                        )
                        return stolen
                    }
                }

                /**
                 * Download one piece. NETWORK failures follow the normal
                 * policy: 5 attempts with exponential backoff, each retry
                 * re-requesting from the piece's committed offset so a mid-body
                 * drop resumes instead of restarting. RATE-LIMIT answers
                 * (429/503) never consume an attempt — they park the whole
                 * socket pool in the shared cooldown (enterRateLimit) and the
                 * piece retries after the host calmed down. The global failure
                 * flag is only set once a piece exhausts attempts on REAL
                 * failures, so a single hiccup never aborts the siblings.
                 */
                suspend fun downloadPiece(chunk: TurboChunk): Boolean {
                    var attempt = 0
                    // Drop-and-replace state is PER PIECE (survives retries);
                    // semantics validated in scratch/test_drop_replace.py.
                    var drops = 0
                    var slowAfterDrop = 0
                    var noMoreDrops = false
                    var lastConnWasPostDrop = false
                    while (true) {
                        if (failed.get() || !coroutineContext.isActive) return false
                        // Sit out a live cooldown in ≤2s slices so pause/kill
                        // stays responsive; a cancellation here escapes as the
                        // CancellationException the worker handler commits on.
                        while (System.currentTimeMillis() < throttleUntil.get()) {
                            if (failed.get() || !coroutineContext.isActive) return false
                            delay((throttleUntil.get() - System.currentTimeMillis()).coerceIn(0L, 2_000L))
                        }
                        if (attempt >= MAX_ATTEMPTS) break
                        var completed = false
                        var rateLimited = false
                        var dropped = false
                        var connStartedAt = System.currentTimeMillis()
                        var dropWindowStart = connStartedAt
                        var dropWindowBytes = 0L
                        try {
                            // Remember what we ASKED for: if a work-stealer shortens
                            // chunk.end below this while we are mid-read, the call
                            // must be cancelled so OkHttp closes the socket instead
                            // of draining the stolen tail (Fix 3).
                            val requestedRangeEnd = chunk.end
                            val req = Request.Builder().url(url).apply {
                                header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                                headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                                header("Range", "bytes=${chunk.current}-$requestedRangeEnd")
                                // After a drop, force a brand-new connection: the pool
                                // could otherwise hand back the throttled socket.
                                if (drops > 0) header("Connection", "close")
                            }.build()
                            val call = (if (drops > 0) freshConnectionClient(client) else client).newCall(req)
                            trackCall(taskId, call)
                            try {
                                call.execute().use { res ->
                                if (res.code == 206) {
                                    // Audit finding 4: a 206 from the WRONG start
                                    // offset would write the file head at a piece
                                    // offset (silent duplication) — verify the
                                    // server honored the requested Range.
                                    val crStart = res.header("Content-Range")
                                        ?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                                    if (crStart != null && crStart != chunk.current) {
                                        throw java.io.IOException(
                                            "range start mismatch: asked ${chunk.current}, server began at $crStart"
                                        )
                                    }
                                    val src = res.body?.source() ?: throw IOException("Empty response body from server")
                                    val buf = ByteArray(BUFFER)
                                    var pos = chunk.current
                                    var bytesSinceLastCommit = 0L
                                    connStartedAt = System.currentTimeMillis()
                                    dropWindowStart = connStartedAt
                                    dropWindowBytes = 0L
                                    while (pos <= chunk.end && !failed.get()) {
                                        if (!coroutineContext.isActive) {
                                            if (canCommit(taskId)) {
                                                state.commit(plan, total, force = true)
                                            }
                                            return@use
                                        }
                                        val liveEnd = chunk.end
                                        val remainingInChunk = liveEnd - pos + 1L
                                        if (remainingInChunk <= 0L) break
                                        val want = minOf(buf.size.toLong(), remainingInChunk).toInt()
                                        val n = readCoalesced(src, buf, want)
                                        if (n <= 0) break

                                        // Lock-free positional writes: FileChannel.write
                                        // (ByteBuffer, position) is kernel-atomic
                                        // (pwrite64) and each piece owns a DISJOINT byte
                                        // range, so lanes no longer queue behind one
                                        // monitor. The drain loop stays: a short write
                                        // (legal per contract) must surface as a
                                        // retryable failure, never as silent zero holes.
                                        // Re-clamp: a work-stealer may have shortened
                                        // chunk.end while readCoalesced was in flight —
                                        // bytes past the new end belong to the stealer.
                                        val allowedBytes = (chunk.end - pos + 1L).coerceAtLeast(0L).toInt()
                                        val writeLen = minOf(n, allowedBytes)
                                        if (writeLen > 0) {
                                            val bb = ByteBuffer.wrap(buf, 0, writeLen)
                                            var w = 0
                                            while (bb.hasRemaining()) {
                                                val k = channel.write(bb, pos + w)
                                                if (k <= 0) throw java.io.IOException(
                                                    "short positional write at ${pos + w}"
                                                )
                                                w += k
                                            }
                                            pos += writeLen
                                            chunk.current = pos
                                            done.addAndGet(writeLen.toLong())
                                        }
                                        if (pos > chunk.end) {
                                            if (chunk.end < requestedRangeEnd) {
                                                try { call.cancel() } catch (_: Exception) {}
                                            }
                                            break
                                        }

                                        // Drop-and-replace window check (1DM): a lane
                                        // under the floor AFTER warm-up is replaced by
                                        // a fresh connection. Warm-up windows are not
                                        // evidence, and the two-strikes latch is set
                                        // BEFORE the drop decision so IP-wide
                                        // throttling is bounded (test_drop_replace.py).
                                        val nowMs = System.currentTimeMillis()
                                        dropWindowBytes += writeLen
                                        if (nowMs < throttleUntil.get()) {
                                            // Cooldown (audit finding 3): window
                                            // bookkeeping is frozen AND the window
                                            // anchor resets, so parked time can
                                            // never count as slow-lane evidence.
                                            dropWindowBytes = 0L
                                            dropWindowStart = nowMs
                                        } else if (nowMs - dropWindowStart >= DROP_WINDOW_MS) {
                                            val span = (nowMs - dropWindowStart).coerceAtLeast(1L)
                                            val bps = dropWindowBytes * 1000.0 / span
                                            dropWindowBytes = 0L
                                            dropWindowStart = nowMs
                                            // Shared best FIRST (cold start safe: the
                                            // first window seeds it with its own bps,
                                            // so a uniformly slow link never drops).
                                            if (bps > 0.0) bestWindowBps.accumulateAndGet(bps.toLong()) { a, b ->
                                                if (a > b) a else b
                                            }
                                            val bestBps = bestWindowBps.get()
                                            val isSlowAbsolute = bps < DROP_FLOOR_BPS &&
                                                bps < bestBps * DROP_RELATIVE_FRACTION
                                            // Fix 4: above-floor but far below the sibling
                                            // field (55KB/s among 1.5MB/s lanes) is still
                                            // a drop candidate.
                                            val isSeverelyLagging = bestBps >= SEVERE_LAG_MIN_BEST_BPS &&
                                                bps < bestBps * SEVERE_LAG_FRACTION
                                            if (isSlowAbsolute || isSeverelyLagging) {
                                                val eligible = nowMs - connStartedAt > DROP_WARMUP_MS
                                                if (eligible && lastConnWasPostDrop) {
                                                    slowAfterDrop++
                                                    if (slowAfterDrop >= DROP_STRIKE_LIMIT) noMoreDrops = true
                                                }
                                                if (eligible &&
                                                    chunk.end - pos + 1 > 2L * BUFFER &&
                                                    !noMoreDrops &&
                                                    drops < MAX_DROPS_PER_PIECE &&
                                                    nowMs >= throttleUntil.get()
                                                ) {
                                                    drops++
                                                    lastConnWasPostDrop = true
                                                    dropped = true
                                                    com.anonrode.downloader.util.DebugLog.backend(
                                                        "task=$taskId drop slow socket piece=${chunk.start}-${chunk.end} rate=${(bps / 1024).toInt()}kB/s drops=$drops/$MAX_DROPS_PER_PIECE"
                                                    )
                                                    try { call.cancel() } catch (_: Exception) {}
                                                    break
                                                }
                                            } else if (lastConnWasPostDrop && bps >= DROP_FLOOR_BPS &&
                                                !isSeverelyLagging) {
                                                slowAfterDrop = 0 // healthy (not merely non-relative-slow) window
                                            }
                                        }

                                        bytesSinceLastCommit += writeLen
                                        if (bytesSinceLastCommit >= 2 * 1024 * 1024L || pos > chunk.end) {
                                            if (canCommit(taskId)) {
                                                state.commit(plan, total, force = false)
                                            }
                                            bytesSinceLastCommit = 0L
                                        }
                                    }
                                    if (canCommit(taskId)) {
                                        state.commit(plan, total, force = true)
                                    }
                                    completed = pos > chunk.end
                                } else {
                                    failureStatus.compareAndSet(0, res.code)
                                    if (res.code == 429 || res.code == 503) rateLimited = true
                                }
                                }
                                } finally {
                                    untrackCall(taskId, call)
                                }
                                if (completed) return true
                                if (rateLimited) {
                                    // The server told us to come back later: come
                                    // back later. This is NOT one of the piece's
                                    // 5 tries — counting 429s against the budget
                                    // is what turned a 26 MB/s healthy stream into
                                    // a corpse in share-5.
                                    enterRateLimit()
                                    continue
                                }
                                if (dropped) {
                                    // Drop-and-replace: the next attempt resumes from
                                    // chunk.current on a fresh connection. NEVER
                                    // consumes an attempt (mirrors the rateLimited
                                    // path); persist state first so a crash right
                                    // after a drop resumes at the committed offset.
                                    if (canCommit(taskId)) state.commit(plan, total, force = true)
                                    delay(DROP_RETRY_DELAY_MS)
                                    continue
                                }
                                attempt++
                            if (failed.get() || !coroutineContext.isActive) return false
                            failureMessage.compareAndSet(
                                null,
                                "Piece ${chunk.start}-${chunk.end} incomplete (attempt $attempt of $MAX_ATTEMPTS)"
                            )
                        } catch (e: CancellationException) {
                            if (canCommit(taskId)) {
                                state.commit(plan, total, force = true)
                            }
                            throw e
                        } catch (e: Exception) {
                            // Only abort without retry if the coroutine itself was cancelled (user
                            // pause/cancel) or the task was marked for full cancellation. A
                            // SocketTimeoutException (extends InterruptedIOException) or a
                            // watchdog socket reset while the coroutine is still active MUST
                            // count as a normal retryable attempt.
                            if (!coroutineContext.isActive || !canCommit(taskId)) {
                                if (canCommit(taskId)) {
                                    state.commit(plan, total, force = true)
                                }
                                return false
                            }
                            attempt++
                            failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                            // Persist the mid-piece position so a pause after this
                            // failure resumes from here instead of the piece start.
                            if (canCommit(taskId)) {
                                state.commit(plan, total, force = true)
                            }
                        }
                        if (attempt < MAX_ATTEMPTS) delay(backoffMillis(attempt))
                    }
                    failed.set(true)
                    return false
                }

                coroutineScope {
                    repeat(minOf(sockets, plan.size)) { i ->
                        launch {
                            try {
                                while (isActive && !failed.get()) {
                                    // Concurrency downshift: after a rate-limit
                                    // cycle only the first maxWorkers sockets
                                    // keep claiming; the shared queue routes the
                                    // rest of the pieces to the survivors.
                                    if (i >= maxWorkers.get()) break
                                    val chunk = claimOrStealNext() ?: break
                                    if (!downloadPiece(chunk)) break
                                }
                            } catch (_: CancellationException) {
                                if (canCommit(taskId)) {
                                    state.commit(plan, total, force = true)
                                }
                            }
                        }
                    }
                }
                channel.force(true)
            }
        } finally {
            telemetryTicker.cancel()
            onProgress(committed(), total, speed.getSpeed())
            if (taskId.isNotEmpty()) {
                throttleDeadlines.remove(taskId)
            }
        }

        if (failed.get()) return@coroutineScope false
        if (plan.any { it.current <= it.end }) return@coroutineScope false
        return@coroutineScope dest.length() == total
    }

    private suspend fun single(
        url: String,
        dest: File,
        headers: Map<String, String>,
        total: Long,
        failureStatus: AtomicInteger,
        failureMessage: AtomicReference<String?>,
        onProgress: (Long, Long, Long) -> Unit,
        client: OkHttpClient,
        taskId: String = ""
    ): Boolean = coroutineScope {
        val initialBytes = if (dest.exists()) dest.length() else 0L
        val done = AtomicLong(initialBytes)
        val speed = SpeedMeter(initialBytes)
        onProgress(initialBytes, if (total > 0) total else 0L, 0L)

        // Decoupled Telemetry Dispatcher: Ticks every 250ms
        val telemetryTicker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                val currentDone = done.get()
                val currentSpeed = speed.sample(currentDone)
                onProgress(currentDone, if (total > 0) total else 0L, currentSpeed)
            }
        }

        var success = false
        var attemptCount = 0
        // Rate-limit state for the single-stream path — same ladder as the
        // segmented worker pool, no cross-worker sharing needed (one socket).
        var lastRateLimited = false
        var throttleUntil = 0L
        var throttleCycles = 0
        try {
            suspend fun attempt(): Boolean {
                lastRateLimited = false
                try {
                    val resumeAt = if (dest.exists()) dest.length() else 0L
                    val req = Request.Builder().url(url).apply {
                        header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
                        headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
                        if (resumeAt > 0) header("Range", "bytes=$resumeAt-")
                    }.build()

                    val call = client.newCall(req)
                    trackCall(taskId, call)
                    try {
                        call.execute().use { res ->
                            if (!res.isSuccessful) {
                                failureStatus.compareAndSet(0, res.code)
                                lastRateLimited = res.code == 429 || res.code == 503
                                return false
                            }
                            val resuming = res.code == 206
                            val src = res.body?.source() ?: run {
                                failureMessage.compareAndSet(null, "Empty response body from server")
                                return false
                            }
                            val startAt = if (resuming) resumeAt else 0L
                            done.set(startAt)
                            RandomAccessFile(dest, "rw").use { raf ->
                                if (!resuming || raf.length() < startAt) raf.setLength(startAt)
                                raf.seek(startAt)
                                val buf = ByteArray(BUFFER)
                                var written = startAt
                                while (true) {
                                    if (!coroutineContext.isActive) return false
                                                val n = readCoalesced(src, buf, buf.size)
                                                if (n <= 0) break
                                                raf.write(buf, 0, n)
                                    written += n
                                    done.set(written)
                                }
                                if (total > 0 && written != total) return false
                            }
                        }
                    } finally {
                        untrackCall(taskId, call)
                    }
                    return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One attempt failing (disconnect, timeout) must not abort the
                    // retry loop: record the cause and let the next attempt resume.
                    failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                    return false
                }
            }

            while (!success) {
                // Sit out a live cooldown in ≤2s slices; a pause surfaces as
                // the CancellationException caught below (a paused job never
                // looks like a failure).
                while (System.currentTimeMillis() < throttleUntil) {
                    delay((throttleUntil - System.currentTimeMillis()).coerceIn(0L, 2_000L))
                }
                if (attemptCount >= MAX_ATTEMPTS) break
                if (attempt()) {
                    success = true
                } else if (lastRateLimited) {
                    // "Too Many Requests" is a WAIT instruction, not a failed
                    // attempt: the next request resumes from dest.length() via
                    // the Range header, after an escalating pause.
                    throttleCycles++
                    val waitMs = (throttleBaseDelayMs shl (throttleCycles - 1).coerceAtMost(5))
                        .coerceAtMost(THROTTLE_MAX_MS)
                    throttleUntil = System.currentTimeMillis() + waitMs
                    if (taskId.isNotEmpty()) throttleDeadlines[taskId] = throttleUntil
                    com.anonrode.downloader.util.DebugLog.backend(
                        "task=$taskId single stream rate limited (cycle $throttleCycles): cooling ${waitMs / 1000}s"
                    )
                } else {
                    attemptCount++
                    if (attemptCount < MAX_ATTEMPTS) delay(backoffMillis(attemptCount))
                }
            }
        } catch (_: CancellationException) {
            // A paused job never surfaces as a failure.
        } catch (e: Exception) {
            failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
        } finally {
            telemetryTicker.cancel()
            val finalDone = done.get()
            onProgress(finalDone, if (total > 0) total else 0L, speed.getSpeed())
            if (taskId.isNotEmpty()) {
                throttleDeadlines.remove(taskId)
            }
        }
        return@coroutineScope success
    }
}

/** One segment's byte window plus how far it has been committed to disk. */
/** One segment's byte window plus how far it has been committed to disk.
 *  [end] is @Volatile: a work-stealing sibling may SHORTEN it in flight
 *  (directive Fix 3), and the victim must observe the new end immediately. */
class TurboChunk(val start: Long, @Volatile var end: Long, @Volatile var current: Long)

/**
 * Thread-safe aggregate speed meter utilizing Exponential Moving Average (EMA).
 *
 * Samples at a 250ms cadence. The first ~1 second uses a heavy alpha (fast
 * acquisition) so the readout tracks the real transfer ramp: TCP slow start
 * and fresh sockets make the first windows genuinely slow, and a light EMA
 * from sample one made the displayed speed chase the true speed for ~2.5s —
 * user-visible as "100KB → 400KB → 3MB" long after the transfer was already
 * at full speed. After acquisition it settles to alpha = 0.25 to smooth
 * TCP burst jitter.
 */
class SpeedMeter(initialBytes: Long = 0L) {
    private var lastBytes = initialBytes
    private var lastTime = System.currentTimeMillis()
    private var emaSpeed = 0.0
    private var initialized = false
    private var sampleCount = 0

    @Synchronized
    fun sample(totalBytes: Long): Long {
        val now = System.currentTimeMillis()
        val dt = now - lastTime
        if (dt >= 250) {
            val delta = (totalBytes - lastBytes).coerceAtLeast(0L)
            val instantBps = (delta * 1000.0) / dt
            sampleCount++
            if (!initialized) {
                emaSpeed = instantBps
                initialized = true
            } else {
                val alpha = if (sampleCount <= FAST_ACQUIRE_SAMPLES) 0.6 else 0.25
                emaSpeed = (alpha * instantBps) + ((1.0 - alpha) * emaSpeed)
            }
            lastBytes = totalBytes
            lastTime = now
        }
        return emaSpeed.toLong().coerceAtLeast(0L)
    }

    @Synchronized
    fun getSpeed(): Long = emaSpeed.toLong().coerceAtLeast(0L)

    private companion object {
        /** Samples 2..5 (roughly the first second) run in fast-acquire mode. */
        const val FAST_ACQUIRE_SAMPLES = 5
    }
}
```

---

## Section 6: Native Aria2c & yt-dlp Wrapper (`YoutubeDlDownloader.kt` — Complete File)
File: `app/src/main/java/com/anonrode/downloader/engine/YoutubeDlDownloader.kt`

```kotlin
package com.anonrode.downloader.engine

import android.content.Context
import com.anonrode.downloader.data.settings.AppSettings
import com.anonrode.downloader.pipeline.HostPolicyRegistry
import com.anonrode.downloader.security.TorrentSecurityShield
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.File

object YoutubeDlDownloader {

    /** Native yt-dlp teardown, not a user cancellation or a media verdict. */
    class BackendInterruptedException(message: String) : Exception(message)

    private fun isNativeBackendInterruption(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("canceledexception") ||
            message.contains("read interrupted by close() on another thread")
    }

    /**
     * Terminal yt-dlp errors: the extractor answered with a condition that
     * retrying IDENTICAL arguments cannot change. Substring matches, all
     * lowercase, chosen from live-log failures:
     *  - "sign in to confirm" — YouTube/YouTube Music bot wall (activity log
     *    29: all 3 retries of the same task re-hit the identical wall, 48-61s
     *    wasted per task, plus two 61s watchdog stall kills on silent crawls).
     *  - "login required" / "log in required" / "private video" /
     *    "members-only" — platform gates that never clear mid-ladder.
     *  - "unsupported url" — extractor has no handler for the page shape.
     * NOT terminal on purpose: transient player/extraction failures, network
     * errors, and rate limits (those legitimately recover on retry; the
     * 14-10-23:22 session downloaded a Music video that the bot wall blocked
     * the day before — so the wall is per-SESSION state, not per-video).
     */
    private val TERMINAL_YTDLP_MARKERS = listOf(
        "sign in to confirm",
        "login required",
        "log in required",
        "private video",
        "members-only",
        "unsupported url"
    )

    private fun isTerminalYtDlpError(message: String?): Boolean {
        val low = message?.lowercase() ?: return false
        return TERMINAL_YTDLP_MARKERS.any { low.contains(it) }
    }

    // Task-level retry counts are now AppSettings-backed (pref_magnet_retries /
    // pref_ytdlp_retries), threaded in via download()'s maxAttempts params.

    // Trackers probed live (2026-08): the 3 dropped entries below were dead
    // (no reply / DNS fail); explodie.org and anirena.com verified alive.
    // anirena is an anime tracker -- relevant for this app's content.
    private val TIER1_TRACKERS = listOf(
        "https://tracker.foreverpirates.co:443/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "http://tracker.anirena.com:80/announce",
        "http://tracker.dler.org:6969/announce",
        "http://tracker.mywaifu.best:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce"
    ).joinToString(",")

    /** File extensions that are never the produced media artifact (subtitle
     *  sidecars written by the embed-subs pass). */
    private val SUBTITLE_ARTIFACT_EXTS = setOf("srt", "vtt", "ass", "ssa", "ttml", "json3", "srv3")

    /** Terminal-stub floor for the FINAL accepted output (activity-log audit
     *  §3.4 class): a yt-dlp/aria2c "artifact" under this size is a decoy or
     *  error body, never media — the same 1024-byte class the terminal probe
     *  applies to non-HLS responses (HttpClient.kt:1309-1310) and the
     *  probe consumers re-apply for HLS (LinkResolver.isHlsStubTerminal).
     *  Only the output gate uses this: HLS segment/fragment reads are
     *  untouched (sub-KB segment reads are normal). */
    private const val MIN_PRODUCED_BYTES = 1024L

    /**
     * Flat playlist metadata dump: `yt-dlp -J --flat-playlist` over the whole
     * list WITHOUT resolving any video page (one paginated metadata call),
     * capped at [cap] entries so a 10k-video mix can't melt the phone. The
     * JSON arrives line-by-line through the progress callback and is stitched
     * back; any failure (binary missing, network, timeout, non-zero exit)
     * returns null — the picker shows an honest error + Retry, and per the
     * round-4 doctrine nothing here hammers the host on its own.
     */
    suspend fun fetchPlaylistJson(context: Context, url: String, cap: Int = 1000): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = StringBuilder()
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("-J")
                    addOption("--flat-playlist")
                    addOption("--playlist-items", "1-$cap")
                    addOption("--no-warnings")
                    addOption("--retries", "1")
                    addOption("--socket-timeout", "15")
                    addOption("--user-agent", com.anonrode.downloader.data.net.HttpClient.DEFAULT_UA)
                }
                withTimeoutOrNull(90_000L) {
                    try {
                        YoutubeDL.getInstance().execute(request, "playlist-meta") { _, _, line ->
                            out.append(line).append('\n')
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    }
                    out.toString()
                }
            } catch (_: Throwable) {
                null
            }
        }

    suspend fun download(
        context: Context,
        taskId: String,
        sourceUrl: String,
        targetDir: File,
        preferredFilename: String,
        backend: String,
        referer: String = "",
        origin: String = "",
        ua: String = "",
        customHeaders: Map<String, String> = emptyMap(),
        parallelSockets: Int = 16,
        quality: String = "720p",
        isExtractorTask: Boolean = false,
        audioOnly: Boolean = false,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        onTorrentFiles: (suspend (List<TorrentSecurityShield.TorrentFileEntry>) -> com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult)? = null,
        // Tier-A settings (defaults = previous hardcoded behavior)
        magnetMaxAttempts: Int = 3,
        ytdlpMaxAttempts: Int = 3,
        hlsFragments: Int = -1,
        speedLimitKbs: Int = 0,
        torrentPeers: Int = -1,
        // Torrent Privacy Mode: disables DHT/LPD/PEX peer discovery, requires
        // encrypted peer links, caps upload near zero and randomizes the listen
        // port. Trackers alone find peers, so dead-swarm discovery is weaker.
        privacyMode: Boolean = false,
        // Locally rewritten HLS master (scheme-relative segment URLs fixed to
        // absolute https) — yt-dlp is fed the file instead of the original URL.
        hlsMasterFile: String? = null,
        // Seal-parity subtitles (2026-09-14): when true (and not audio-only),
        // extractor downloads fetch subtitles in [subLang] (manual + auto-
        // generated), convert them to SRT and EMBED them in the merged MP4 —
        // one artifact, subs visible in this player and any external one.
        downloadSubs: Boolean = false,
        subLang: String = "en",
        estimatedTotal: Long = 0L
    ): File? {
        if (!targetDir.exists()) targetDir.mkdirs()

        val isMagnet = sourceUrl.startsWith("magnet:", ignoreCase = true)
        // When a rewritten master file is supplied, yt-dlp consumes that file
        // (file:// + --enable-file-urls) — the m3u8 branch still applies.
        val inputUrl = if (hlsMasterFile != null) "file://$hlsMasterFile" else sourceUrl
        val isM3u8 = inputUrl.lowercase().contains(".m3u8")
        // Token-locked HLS CDNs (vidsrc family, live-verified) reject segment
        // requests that carry ANY Referer with 403 while serving them fine
        // without one — and the rewritten playlist was validated exactly that
        // way. A rewritten master therefore runs referer-free, EXCEPT for the
        // anitaku CDNs (cdn.watching.onl / fntb0.anivideo.sbs) which are the
        // inverse: they 403 every request that does NOT carry exactly
        // https://megaplay.buzz/ (live-verified 2026-08-22).
        val effReferer = if (hlsMasterFile != null && !requiresExplicitReferer(referer)) "" else referer

        val height = quality.filter { it.isDigit() }.toIntOrNull() ?: when (quality.lowercase()) {
            "4k" -> 2160
            "sd" -> 480
            else -> 720
        }

        if (isMagnet) {
            // Selective-file pass: probe the swarm for its file list; when more
            // than one safe file exists, ask the UI which to download. A single
            // safe file is auto-selected (no picker). A failed probe (null)
            // falls back to the full download; a probe where EVERY file fails
            // the shield refuses the download entirely.
            var selectIndexes: List<Int>? = null
            if (onTorrentFiles != null) {
                val files = listTorrentFiles(context, sourceUrl, File(preferredFilename).nameWithoutExtension, privacyMode)
                if (files != null) {
                    val safe = files.filter { it.isSafe }
                    when {
                        safe.size > 1 -> {
                            when (val res = onTorrentFiles(files)) {
                                is com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult.Selected -> selectIndexes = res.indices
                                com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult.Cancelled -> throw kotlinx.coroutines.CancellationException("User cancelled torrent selection")
                            }
                        }
                        safe.size == 1 -> selectIndexes = listOf(safe.first().index)
                        // Probe succeeded but EVERY file failed the shield:
                        // surface the picker's warning state and refuse the
                        // legacy fallback — a silent full download here would
                        // fetch exactly the flagged payload.
                        else -> {
                            when (val res = onTorrentFiles(files)) {
                                is com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult.Selected -> {
                                    if (res.indices.isEmpty()) {
                                        throw SecurityException(
                                            "All ${files.size} file(s) blocked by security shield — download refused"
                                        )
                                    }
                                    selectIndexes = res.indices
                                }
                                com.anonrode.downloader.ui.screens.TorrentFilePicker.TorrentPickResult.Cancelled -> throw kotlinx.coroutines.CancellationException("User cancelled torrent selection")
                            }
                        }
                    }
                    if (safe.size != files.size) {
                        android.util.Log.w("AnonDownload",
                            "listTorrentFiles: ${files.size - safe.size} of ${files.size} entries blocked by shield")
                    }
                }
            }
            // isActiveCheck keeps the task-level retry loop from relaunching
            // aria2c after the user paused or cancelled the job. Named arg:
            // speedLimitKbs is now the last parameter, so a trailing lambda
            // would no longer bind to isActiveCheck.
            return downloadMagnetAria2c(
                context, taskId, sourceUrl, targetDir, preferredFilename, parallelSockets, onProgress,
                selectIndexes = selectIndexes,
                maxAttempts = magnetMaxAttempts,
                peersOverride = torrentPeers,
                speedLimitKbs = speedLimitKbs,
                privacyMode = privacyMode,
                isActiveCheck = { coroutineContext.isActive },
                estimatedTotal = estimatedTotal
            )
        }

        // Direct HTTP downloads explicitly routed to aria2c (CDNs, movie lockers, etc.).
        // Native aria2c executable handles HTTP downloads directly. On failure the
        // exception propagates to the engine, which owns the park/fail/retry decision.
        if (backend.equals("aria2c", true) && !isM3u8 && !isExtractorTask) {
            val aria2Exec = findAria2Executable(context)
            if (aria2Exec != null) {
                try {
                    val file = downloadHttpAria2c(
                        context = context,
                        taskId = taskId,
                        url = sourceUrl,
                        targetDir = targetDir,
                        preferredFilename = preferredFilename,
                        referer = referer,
                        origin = origin,
                        ua = if (ua.isNotBlank()) ua else com.anonrode.downloader.data.net.HttpClient.DEFAULT_UA,
                        customHeaders = customHeaders,
                        parallelSockets = parallelSockets,
                        speedLimitKbs = speedLimitKbs,
                        maxAttempts = ytdlpMaxAttempts,
                        onProgress = onProgress,
                        isActiveCheck = { coroutineContext.isActive },
                        estimatedTotal = estimatedTotal
                    )
                    if (file != null) return file
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    com.anonrode.downloader.util.DebugLog.backend(
                        "task=$taskId aria2c HTTP failed (${e.message})"
                    )
                    // aria2c failures must reach the engine's park/fail classifier with
                    // their HTTP status intact (the 429/403 message powers
                    // isRateLimitedError and parkInsteadOfFail); swallowing them into a
                    // null return hid throttling and expiry errors from that logic.
                    throw e
                }
            }
        }

        // The yt-dlp/ffmpeg runtime initializes in the app's background
        // bootstrap (AnonApp.appScope): a yt-dlp task enqueued before that
        // finishes would otherwise burn its first attempt on an uninitialized
        // runtime. Magnets above run aria2c directly and don't need it.
        if (!com.anonrode.downloader.AnonApp.ensureReady()) {
            throw java.io.IOException("yt-dlp runtime is not initialized")
        }

        // Extractor tasks (social/YouTube, where yt-dlp picks its own output
        // name) write into a PRIVATE workdir instead of the shared target
        // folder. Two YouTube jobs downloading into Social/YouTube/ at the
        // same time used to count each other's .part files in the engine's
        // disk-progress scan (task 2's first sample read 33 MB at t+2s — task
        // 1's bytes), which then fired phantom watchdog kills
        // (window=-145334KiB) the moment the sibling completed and renamed
        // its parts away. The produced artifact is moved to the real target
        // folder after a successful run; the engine's computeDiskBytes scans
        // the workdir while it exists.
        val outDir = if (isExtractorTask) File(targetDir, ".work-$taskId").apply { mkdirs() } else targetDir

        val request = YoutubeDLRequest(inputUrl).apply {
            if (hlsMasterFile != null) {
                // The input is our own rewritten playlist file in the app cache.
                addOption("--enable-file-urls")
            }
            if (isExtractorTask) {
                // If caller provided a specific preferredFilename (movies / TV show episodes),
                // use that clean filename stem instead of yt-dlp's generic extractor metadata
                // which can produce garbage like "RBNNj1sIT2 [id].unknown_video".
                // %(title)s [%(id)s] is reserved for social video downloads where the title
                // is unknown in advance.
                val isGenericName = isGenericPreferredName(preferredFilename)
                val outTemplate = if (!isGenericName) {
                    val stem = File(outDir, preferredFilename.substringBeforeLast('.')).absolutePath
                    val ext = File(preferredFilename).extension.ifBlank { "mp4" }
                    val safeExt = if (audioOnly) "mp3" else if (ext.equals("matroska", ignoreCase = true)) "mkv" else ext
                    "$stem.$safeExt"
                } else {
                    File(outDir, "%(title).100s [%(id)s].%(ext)s").absolutePath
                }
                addOption("-o", outTemplate)
                if (audioOnly) {
                    addOption("-f", "bestaudio/best")
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-thumbnail")
                    addOption("--embed-metadata")
                    addOption("--convert-thumbnails", "jpg")
                } else {
                    addOption("-f", "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best")
                    addOption("-S", "height~$height,+size,+br,abr")
                    addOption("--merge-output-format", "mp4")
                    if (downloadSubs) {
                        // Seal parity (2026-09-14): manual AND auto-generated
                        // captions for the configured language, normalized to
                        // SRT (mp4 mov_text) and embedded by the bundled
                        // ffmpeg during the merge pass — the artifact stays a
                        // single file, so the workdir move logic and the
                        // gallery scan never learn about sidecars. Videos
                        // without subtitles are unaffected (yt-dlp just
                        // reports "no subtitles" and moves on).
                        addOption("--write-subs")
                        addOption("--write-auto-subs")
                        addOption("--sub-langs", subLang.ifBlank { "en" })
                        addOption("--convert-subs", "srt")
                        addOption("--embed-subs")
                    }
                }
                addOption("--no-playlist")
                // Embed/watch-page cracks (nepu, social, etc.) often resolve to HLS.
                // Parallel fragments keep multi-socket speed instead of a single
                // rate-capped connection.
                // Floor 1, ceiling 16; per-host caps arrive pre-applied through
                // HostPolicyRegistry (6/12 on the sensitive lockers, OTA-tunable
                // via contentPolicy.socketCaps) and — after Fix 6b — the HLS
                // path applies the host cap too (minOf below).
                // Fix 6b: host caps must bind on the HLS/extractor path too —
                // hlsFragments > 0 is always true from the engine, which used
                // to bypass every per-host socket cap entirely.
                val hostCap = HostPolicyRegistry.effectiveSocketsForUrl(sourceUrl, 16)
                val rawFrags = if (hlsFragments > 0) hlsFragments else parallelSockets
                val frags = minOf(rawFrags.coerceIn(1, 16), hostCap)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "2M")
                addOption("--socket-timeout", "15")
                addOption("--retries", "5")
                addOption("--fragment-retries", "5")
                addOption("--retry-sleep", "3")
                addOption("--retry-sleep", "fragment:3")
                // Fail loudly instead of letting yt-dlp skip missing fragments
                // and silently produce a video with gaps in it.
                addOption("--abort-on-unavailable-fragments")
                if (speedLimitKbs > 0) addOption("--limit-rate", "${speedLimitKbs}K")
            } else if (isM3u8) {
                // HLS m3u8 stream variant selection with multi-fragment parallel downloading
                val stem = File(outDir, preferredFilename.substringBeforeLast('.')).absolutePath
                val safeExt = File(preferredFilename).extension.ifBlank { "mp4" }
                if (audioOnly) {
                    addOption("-o", "$stem.%(ext)s")
                    addOption("-f", "bestaudio/best")
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-thumbnail")
                    addOption("--embed-metadata")
                    addOption("--convert-thumbnails", "jpg")
                } else {
                    addOption("-o", "$stem.$safeExt")
                    addOption("-f", "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best")
                    addOption("-S", "height~$height,+size,+br")
                    addOption("--merge-output-format", "mp4")
                }
                addOption("--no-playlist")
                // Fix 6b: host caps must bind on the HLS/extractor path too —
                // hlsFragments > 0 is always true from the engine, which used
                // to bypass every per-host socket cap entirely.
                val hostCap = HostPolicyRegistry.effectiveSocketsForUrl(sourceUrl, 16)
                val rawFrags = if (hlsFragments > 0) hlsFragments else parallelSockets
                val frags = minOf(rawFrags.coerceIn(1, 16), hostCap)
                addOption("-N", "$frags")
                addOption("--concurrent-fragments", "$frags")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "2M")
                addOption("--socket-timeout", "15")
                addOption("--retries", "5")
                addOption("--fragment-retries", "5")
                addOption("--retry-sleep", "3")
                addOption("--retry-sleep", "fragment:3")
                addOption("--abort-on-unavailable-fragments")
                if (speedLimitKbs > 0) addOption("--limit-rate", "${speedLimitKbs}K")
            } else {
                // Direct CDN HTTP fallback via yt-dlp native chunked engine
                val stem = File(outDir, preferredFilename.substringBeforeLast('.')).absolutePath
                val ext = File(preferredFilename).extension.ifBlank { "mp4" }
                val safeExt = if (audioOnly) "mp3" else if (ext.equals("matroska", ignoreCase = true)) "mkv" else ext
                addOption("-o", "$stem.$safeExt")
                if (audioOnly) {
                    addOption("-f", "bestaudio/best")
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-thumbnail")
                    addOption("--embed-metadata")
                    addOption("--convert-thumbnails", "jpg")
                }
                val conns = parallelSockets.coerceIn(1, 16)
                addOption("-N", "$conns")
                addOption("--concurrent-fragments", "$conns")
                addOption("--buffer-size", "1M")
                addOption("--http-chunk-size", "2M")
                addOption("--socket-timeout", "15")
                addOption("--retries", "10")
                if (speedLimitKbs > 0) addOption("--limit-rate", "${speedLimitKbs}K")
            }

            addOption("--no-mtime")
            addOption("--no-warnings")
            if (HostPolicyRegistry.allowsPermissiveTlsFallback(sourceUrl)) {
                addOption("--no-check-certificate")
            }
            // --no-restrict-filenames: yt-dlp refuses "unusual" extensions like
            // .mkv by default ("The extracted extension ('matroska') is unusual
            // and will be skipped for safety reasons" — fired 8 times on
            // naijavault→vikingfile .mkv URLs in app-2026-08-29 / 09-01, all
            // returning 0 bytes after the engine had already burned 3-6 minutes
            // cracking the locker URL). Disabling is safe: we always write to
            // our own sanitized path, never to a location derived from the
            // remote filename.
            addOption("--no-restrict-filenames")
            // Remap unsafe/unusual extensions extracted from remote headers (e.g. Content-Type: video/matroska
            // -> ext='matroska', or Content-Type: application/octet-stream -> ext='unknown_video') before
            // yt-dlp's _catch_unsafe_extension_error skips the download.
            addCommands(listOf("--replace-in-metadata", "ext", "(?i)matroska", "mkv"))
            addCommands(listOf("--replace-in-metadata", "ext", "(?i)unknown_video", "mp4"))
            addOption("--newline")
            addOption("--progress")
            // Refuse outside config files: a stray yt-dlp.conf could override
            // -o/-f and silently break the output path (monolith parity).
            addOption("--ignore-config")

            // Cookies support: if user has provided cookies.txt in app storage,
            // forward to yt-dlp so authenticated platforms (Instagram, etc.) succeed.
            val cookieFile = File(context.filesDir, "cookies.txt").takeIf { it.exists() && it.length() > 0 }
                ?: File(context.getExternalFilesDir(null), "cookies.txt").takeIf { it.exists() && it.length() > 0 }
            if (cookieFile != null) {
                addOption("--cookies", cookieFile.absolutePath)
            }

            // --progress-template: emit one templated progress line per tick,
            // parsed by ProgressParser's YTDL_TEMPLATE_REGEX.  Two templates
            // are emitted (yt-dlp allows multiple) — the first mirrors
            // yt-dlp's native "[download] X% of Y at Z" format so the
            // existing YTDL_REGEX keeps matching unchanged (belt-and-
            // suspenders for any download where the structured fields
            // fall back to "NA").  The second carries structured fields
            // (percent, speed, eta, fragment_index, fragment_count,
            // downloaded_bytes, total_bytes, total_bytes_estimate) needed
            // for HLS / segmented downloads where the native line is
            // fragment-internal and the engine's existing YTDL_REGEX
            // never matched (dramakey / wetafiles symptom: "Starting..."
            // for 90s, then BAM "DONE").  The @@DLP@@ sentinel is the
            // same one the Python monolith uses.
            addOption(
                "--progress-template",
                "download:[download]  %(progress._percent_str)s of ~%(progress._total_bytes_str)s at %(progress._speed_str)s ETA %(progress._eta_str)s"
            )
            addOption(
                "--progress-template",
                "download:@@DLP@@ %(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.fragment_index)s|%(progress.fragment_count)s|%(progress._downloaded_bytes_str)s|%(progress._total_bytes_str)s|%(progress._total_bytes_estimate_str)s"
            )
            if (effReferer.isNotBlank()) addOption("--referer", effReferer)
            if (ua.isNotBlank()) addOption("--user-agent", ua)

            // Origin header from the referer's base domain, like the monolith's
            // HLS path always sends it.
            val originToPass = origin.ifBlank {
                effReferer.takeIf { it.isNotBlank() }?.let { r ->
                    Regex("""https?://[^/]+""").find(r)?.value ?: ""
                } ?: ""
            }
            if (originToPass.isNotBlank()) {
                val cleanOrig = originToPass.replace("\r", "").replace("\n", "").trim()
                if (cleanOrig.isNotBlank()) addOption("--add-header", "Origin: $cleanOrig")
            }

            for ((k, v) in customHeaders) {
                if (k.equals("user-agent", ignoreCase = true) ||
                    k.equals("referer", ignoreCase = true) ||
                    k.equals("origin", ignoreCase = true)) continue
                val cleanK = k.replace("\r", "").replace("\n", "").trim()
                val cleanV = v.replace("\r", "").replace("\n", "").trim()
                if (cleanK.isNotBlank() && cleanV.isNotBlank()) {
                    addOption("--add-header", "$cleanK:$cleanV")
                }
            }
        }

        val before = outDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        val stem = preferredFilename.substringBeforeLast('.')

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0
        // Last attempt's raw error text, for the terminal-error fail-fast below.
        var lastErrorMessage: String? = null
        var terminalErrorHit = false

        suspend fun attemptOnce(): File? {
            var lastDl = 0L
            var lastTot = estimatedTotal
            var currentStreamDl = 0L
            var currentStreamTot = estimatedTotal
            var bankedDl = 0L
            var bankedTot = 0L
            var hlsReconciler: HlsMonotonicProgressReconciler? = null
            val etaCalc = FlickerFreeEtaCalculator()

            try {
                YoutubeDL.getInstance().execute(request, taskId) { progress, etaInSeconds, line ->
                    if (line != null) {
                        val trimmedLine = line.trim()
                        // Detect format/stream transition (e.g. video finished, audio starting)
                        if (trimmedLine.startsWith("[download] Destination:") || trimmedLine.startsWith("[download] Downloading item")) {
                            if (currentStreamDl > 0L) {
                                bankedDl += currentStreamDl
                                bankedTot += if (currentStreamTot > 0L) currentStreamTot else currentStreamDl
                                currentStreamDl = 0L
                                currentStreamTot = 0L
                                hlsReconciler = null
                            }
                        }
                    }

                    // Parse progress against current stream
                    val tick = parseProgressTick(line, progress, currentStreamDl, currentStreamTot)
                    if (tick.fragmentIndex != null && tick.fragmentCount != null && tick.fragmentCount > 0) {
                        val fi = tick.fragmentIndex
                        val fc = tick.fragmentCount
                        val rec = hlsReconciler ?: HlsMonotonicProgressReconciler(
                            estimatedTotal = if (estimatedTotal > 0L) estimatedTotal else (tick.totalBytes.takeIf { it > 0L } ?: 0L),
                            segmentCount = fc
                        ).also { hlsReconciler = it }

                        val dl = if (tick.downloadedBytes > 0L) tick.downloadedBytes else currentStreamDl
                        val (recDl, recTot, _) = rec.update(fi, dl)
                        currentStreamDl = recDl
                        currentStreamTot = recTot
                    } else if (tick.downloadedBytes > 0L || tick.totalBytes > 0L) {
                        if (tick.downloadedBytes > 0L) {
                            currentStreamDl = maxOf(currentStreamDl, tick.downloadedBytes)
                        }
                        if (tick.totalBytes > 0L) {
                            currentStreamTot = maxOf(currentStreamTot, tick.totalBytes)
                        }
                    }

                    var effectiveDl = maxOf(lastDl, bankedDl + currentStreamDl)
                    var effectiveTot = maxOf(lastTot, bankedTot + currentStreamTot)

                    if (line != null && (line.contains("[Merger]") || line.contains("Merging formats"))) {
                        if (effectiveTot > effectiveDl) {
                            effectiveDl = effectiveTot
                        }
                    }

                    // Change-detect so the hundreds of non-progress lines
                    // ([info], destination notices) never spam the UI flow with no-op updates.
                    if (effectiveDl != lastDl || effectiveTot != lastTot || tick.speedBytesPerSec > 0.0) {
                        lastDl = effectiveDl
                        lastTot = effectiveTot
                        val eta = when {
                            tick.etaSeconds > 0 -> tick.etaSeconds
                            etaInSeconds > 0 -> etaInSeconds
                            else -> etaCalc.update(lastDl, lastTot, tick.speedBytesPerSec)
                        }
                        onProgress(lastDl, lastTot, tick.speedBytesPerSec, eta)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (isNativeBackendInterruption(e) && !coroutineContext.isActive) {
                    throw BackendInterruptedException("yt-dlp backend interrupted; preserving partial data")
                }
                errors.append("run ").append(attempts).append(": ").append(e.message ?: e.javaClass.simpleName).append('\n')
                lastErrorMessage = e.message ?: e.javaClass.simpleName
                // Per-attempt visibility: the aggregate error only lands after
                // ALL attempts, and an HLS stall gave the log nothing to work
                // with (audit finding).
                com.anonrode.downloader.util.DebugLog.backend(
                    "task=$taskId yt-dlp attempt $attempts failed: ${(e.message ?: e.javaClass.simpleName).take(280)}"
                )
                return null
            }

            // yt-dlp's pre-merge format shards ("Title [id].f135.mp4" = the
            // video-only stream, ".f140.m4a" = audio-only) must never be
            // accepted as the produced artifact: the watchdog once killed a
            // task mid-merge (app-2026-08-31 07:13) and attempt 2 picked up
            // the leftover .f135.mp4 — the user got a 138 MiB file with NO
            // audio track. A merged output never carries the .f<NNN> infix,
            // so exclude the shards; when only shards exist the attempt
            // counts as failed and the next one re-runs the merge.
            val formatShard = Regex("""\.f\d+\.[A-Za-z0-9]{2,5}$""")
            // Same guard the magnet path applies below (aria2c contract): a
            // sibling .aria2 control file means the data file is still
            // PARTIAL — the external libaria2c downloader only removes it on
            // successful completion. Without this, an attempt that exits 0
            // while a straggler range request died could hand the engine a
            // truncated file that the structure tier then blesses.
            fun isFinal(f: File) = f.exists() && f.isFile && f.length() >= MIN_PRODUCED_BYTES &&
                !f.name.endsWith(".aria2") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") &&
                // Subtitle sidecars (write-subs/embed-subs output; embed
                // normally deletes them, a half-finished pass may not) were
                // never artifacts.
                f.extension.lowercase() !in SUBTITLE_ARTIFACT_EXTS &&
                !formatShard.containsMatchIn(f.name) &&
                !File(f.absolutePath + ".aria2").exists()

            val candidates = outDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()

            val fresh = candidates.filter { it.absolutePath !in before }
            return fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                ?: fresh.maxByOrNull { it.lastModified() }
                ?: candidates.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                // Multi-file downloads (season packs) land as a NEW directory
                // whose File.length() is ~0 — when no file matched, take the
                // most-recently-created directory as the produced artifact.
                ?: outDir.listFiles { f -> f.isDirectory && f.absolutePath !in before }?.maxByOrNull { it.lastModified() }
        }

        // Same-engine retry with resume: yt-dlp's .part (native) and aria2c's
        // .aria2 control file (external downloader) keep progress across runs,
        // so a retry continues instead of restarting. Never relaunch after the
        // job was paused or cancelled.
        while (attempts < ytdlpMaxAttempts && produced == null) {
            attempts++
            if (!coroutineContext.isActive) throw CancellationException("Task was cancelled before yt-dlp retry")
            com.anonrode.downloader.util.DebugLog.backend("task=$taskId yt-dlp attempt $attempts/$ytdlpMaxAttempts url=${inputUrl.take(110)}")
            produced = attemptOnce()
            if (produced != null) {
                val size = produced.length()
                val sizeLabel = if (size >= 1048576) "${size / 1048576} MiB" else "${size / 1024} KiB"
                com.anonrode.downloader.util.DebugLog.backend("task=$taskId yt-dlp attempt $attempts produced ${produced.name} ($sizeLabel)")
            }
            if (produced == null && attempts < ytdlpMaxAttempts) {
                val isInstagramPhotoError = InstagramPhotoMuxer.shortcodeFromUrl(sourceUrl) != null && (
                    errors.contains("No video formats found", ignoreCase = true) ||
                    errors.contains("There is no video in this post", ignoreCase = true) ||
                    errors.contains("This Instagram post contains only still photos", ignoreCase = true) ||
                    errors.contains("only still photos", ignoreCase = true)
                )
                if (isInstagramPhotoError) {
                    com.anonrode.downloader.util.DebugLog.backend("task=$taskId yt-dlp: Instagram photo post detected (no video in post) — skipping retry to run photo muxer immediately")
                    break
                }
                // Bot-check wall: rotate the youtube player client instead of
                // re-running identical arguments or giving up early. The wall
                // is per-session state (note in TERMINAL_YTDLP_MARKERS doc)
                // and a client swap is the field-proven anonymous bypass —
                // web → android → ios across the ladder's attempts. No
                // additional latency when the wall doesn't fire, and the
                // ladder stays at its settings-shaped length (rotations only
                // happen while the wall holds). Activity log 2026-10:
                // music.youtube spent 48-234s re-hitting the IDENTICAL wall
                // with unchanged args, incl. two 61s watchdog kills.
                val botWall = lastErrorMessage?.contains("sign in to confirm", ignoreCase = true) == true
                val youtubeFamily = Regex(
                    """https?://(www\.|m\.|music\.)?(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE
                ).containsMatchIn(inputUrl)
                if (botWall && youtubeFamily && attempts < ytdlpMaxAttempts) {
                    val rotation = listOf("android", "ios")
                    request.addOption("--extractor-args", "youtube:player_client=${rotation[(attempts - 1).coerceIn(0, rotation.lastIndex)]}")
                    com.anonrode.downloader.util.DebugLog.backend(
                        "task=$taskId yt-dlp: bot-check wall — rotating player_client=${rotation[(attempts - 1).coerceIn(0, rotation.lastIndex)]} for attempt ${attempts + 1}"
                    )
                    cancellableRetryWait(2_000L * attempts)
                } else if (isTerminalYtDlpError(lastErrorMessage)) {
                    // login/private/members-only/unsupported: these never
                    // clear mid-ladder — abort now with the honest reason.
                    terminalErrorHit = true
                    com.anonrode.downloader.util.DebugLog.backend(
                        "task=$taskId yt-dlp: terminal error (platform gate) — aborting retry ladder after $attempts attempt(s)"
                    )
                    break
                } else {
                    cancellableRetryWait(2_000L * attempts)
                }
            }
        }
        // IG-1 fallback: an Instagram *photo-with-music* post produces NO video
        // format, so every yt-dlp attempt above has already failed (fast — the
        // extractor throws before any bytes move). yt-dlp will never download
        // this shape (maintainer-declared out-of-scope), so mux cover+audio
        // with the bundled ffmpeg instead. Strict fallback: only fires once
        // yt-dlp has failed AND the URL is an Instagram post, so working
        // videos and every other social site are untouched. A null return (not
        // a photo+music, IG gated us, or encode failed) falls through to the
        // original yt-dlp error below.
        if (produced == null && isExtractorTask && InstagramPhotoMuxer.shortcodeFromUrl(sourceUrl) != null) {
            com.anonrode.downloader.util.DebugLog.backend("task=$taskId yt-dlp exhausted on an Instagram post URL — trying photo+music muxer")
            // Capture the Job in the SUSPEND body: CoroutineContext.isActive is
            // a suspend property and cannot be read from the plain
            // () -> Boolean callback below.
            val muxJob = coroutineContext[kotlinx.coroutines.Job]
            produced = InstagramPhotoMuxer.tryMux(
                context = context,
                sourceUrl = sourceUrl,
                outDir = outDir,
                taskId = taskId,
                isCancelled = { muxJob?.isActive != true }
            )
        }
        if (produced == null && errors.isNotBlank()) {
            val isIgPhoto = InstagramPhotoMuxer.shortcodeFromUrl(sourceUrl) != null && (
                errors.contains("No video formats found", ignoreCase = true) ||
                errors.contains("There is no video in this post", ignoreCase = true) ||
                errors.contains("This Instagram post contains only still photos", ignoreCase = true) ||
                errors.contains("only still photos", ignoreCase = true)
            )
            val friendlyError = if (isIgPhoto) {
                // Photo post saved; audio recovery depends on an anonymous
                // audio source (video carousel sibling) or the login-gated
                // REST music surface. Live-verified 2026-10-14 on
                // DeALkBtIwSB — mixed carousels recover the audio from their
                // has_audio video children, logged out, no session needed.
                "Photo post saved. Instagram hides this post's music from every logged-out surface, so it could not be included."
            } else if (terminalErrorHit) {
                // The platform refused anonymous access (login wall/private/
                // members-only) or the extractor has no handler for this page.
                // State the real reason — the pre-fix aggregate read "failed
                // after N attempt(s)" with identical wall text three times,
                // which reads like a bug.
                "The platform is requiring sign-in for this link, so the download cannot proceed anonymously. ${errors.toString().trim().lineSequence().lastOrNull()?.take(200) ?: ""}"
            } else if (produced == null && errors.toString().contains("sign in to confirm", ignoreCase = true)) {
                // Bot-check wall that player-client rotation could not clear
                // this session. Honest + actionable, not a generic ladder dump.
                "YouTube/Google flagged this session for sign-in (bot-check) even across player-client rotation. A manual retry in a little while usually clears it as the session rotates. ${errors.toString().trim().lineSequence().lastOrNull()?.take(200) ?: ""}"
            } else {
                "yt-dlp failed after $attempts attempt(s): ${errors.toString().trim()}"
            }
            com.anonrode.downloader.util.DebugLog.error("task=$taskId $friendlyError")
            throw Exception(friendlyError)
        }
        // Move the artifact out of the private workdir into the user-visible
        // target folder. renameTo within the same volume is free; copy+delete
        // only as a cross-volume fallback. The workdir is removed when empty
        // so a folder of one-shot YouTube jobs doesn't accumulate dot-dirs.
        if (produced != null && outDir != targetDir) {
            val isGenericName = isGenericPreferredName(preferredFilename)

            val baseStem = if (!isGenericName) {
                File(preferredFilename).nameWithoutExtension
            } else {
                produced.nameWithoutExtension
            }

            var finalExt = produced.extension
            if (finalExt.equals("matroska", ignoreCase = true)) {
                finalExt = "mkv"
            } else if (finalExt.equals("unknown_video", ignoreCase = true) || finalExt.isBlank()) {
                val sniffed = try {
                    java.io.RandomAccessFile(produced, "r").use { raf ->
                        val head = ByteArray(16)
                        val read = raf.read(head)
                        if (read >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) {
                            "mkv"
                        } else if (read >= 8 && String(head, 4, 4, Charsets.US_ASCII).lowercase() in
                            setOf("ftyp", "moov", "mdat", "free", "wide", "skip", "moof", "styp")) {
                            "mp4"
                        } else null
                    }
                } catch (_: Exception) { null }
                finalExt = sniffed ?: if (!isGenericName) File(preferredFilename).extension.ifBlank { "mp4" } else "mp4"
            }

            var dest = File(targetDir, "$baseStem.$finalExt")
            if (dest.exists()) {
                val isBogus = dest.length() == 0L || run {
                    try {
                        dest.inputStream().use { ins ->
                            val buf = ByteArray(256)
                            val n = ins.read(buf)
                            if (n <= 0) true
                            else {
                                val head = String(buf, 0, n, Charsets.US_ASCII).trimStart().lowercase()
                                head.startsWith("<!doctype html") || head.startsWith("<html") ||
                                    head.startsWith("<head") || head.startsWith("<body") || head.startsWith("{")
                            }
                        }
                    } catch (_: Exception) { false }
                }
                if (isBogus) {
                    try { dest.delete() } catch (_: Throwable) {}
                }
            }
            var n = 1
            while (dest.exists()) {
                dest = File(targetDir, "$baseStem ($n).$finalExt")
                n++
            }
            val movedOk = if (produced.isDirectory) produced.renameTo(dest)
            else {
                if (produced.renameTo(dest)) true
                else {
                    try {
                        produced.copyTo(dest, overwrite = true)
                        produced.delete()
                        true
                    } catch (_: Exception) { false }
                }
            }
            if (movedOk) produced = dest
            else {
                // Engine-audit P2: returning the in-workdir artifact let the
                // engine report COMPLETED for a file inside a hidden dot-dir
                // the media scanner ignores (and a later cancel would delete
                // the very file that was called complete). Throw instead — the
                // engine's failure path parks tasks with banked bytes, so the
                // finished bytes stay on disk and the retry resumes from them.
                com.anonrode.downloader.util.DebugLog.error(
                    "task=$taskId yt-dlp artifact left in workdir (move failed): ${produced.absolutePath}"
                )
                throw java.io.IOException(
                    "Download finished, but it could not be moved into your folder (storage full or access lost). Free some space, then tap retry."
                )
            }
            // Move any remaining files (e.g. carousel sibling images). Subtitle
            // sidecars get RENAMED to the video's final (sanitized) stem so the
            // player's "<stem>.<lang>.srt" discovery re-finds them — yt-dlp's
            // original workdir naming dies with the rename to baseStem above,
            // and an orphaned sidecar leaves embedded-ASS as the only track
            // (Break Clause 2026-10-15: release-group typesetting pinned the
            // dialogue to the screen top).
            outDir.listFiles()?.filter { it.isFile }?.forEach { sibling ->
                val destBase = dest.nameWithoutExtension
                val sExt = sibling.extension.lowercase()
                if (sExt in SUBTITLE_ARTIFACT_EXTS) {
                    val langTag = sibling.nameWithoutExtension.substringAfterLast('.')
                        .takeIf { Regex("^[a-zA-Z]{2,3}$").matches(it) } ?: "en"
                    var sibDest = File(targetDir, "$destBase.$langTag.$sExt")
                    var sn = 1
                    while (sibDest.exists()) {
                        // Keep the collision inside the "$destBase." prefix
                        // space: the player discovers sidecars by that prefix
                        // and derives the language from the next dot segment
                        // ("destBase.en-2.srt" -> "en"). A "destBase (2).en.srt"
                        // collision name would be invisible to the discovery.
                        sibDest = File(targetDir, "$destBase.$langTag-$sn.$sExt")
                        sn++
                    }
                    if (!sibling.renameTo(sibDest)) {
                        runCatching { sibling.copyTo(sibDest, overwrite = true); sibling.delete() }
                    }
                    return@forEach
                }
                var sibDest = File(targetDir, sibling.name)
                var sn = 1
                while (sibDest.exists()) {
                    val stem = sibling.nameWithoutExtension
                    val ext = sibling.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
                    sibDest = File(targetDir, "$stem ($sn)$ext")
                    sn++
                }
                if (!sibling.renameTo(sibDest)) {
                    runCatching { sibling.copyTo(sibDest, overwrite = true); sibling.delete() }
                }
            }
            outDir.listFiles()?.takeIf { it.isEmpty() }?.let { outDir.delete() }
        }
        return produced
    }

    private val activeNativeProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    fun killProcess(taskId: String) {
        com.anonrode.downloader.util.DebugLog.backend("task=$taskId killProcess (native teardown)")
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        activeNativeProcesses.remove(taskId)?.let { p ->
            try {
                p.destroy()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val exited = p.waitFor(350, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (!exited) {
                        p.destroyForcibly()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Fetch a magnet's file list from the swarm WITHOUT downloading any data.
     *
     * Two phases (--show-files only reads real .torrent files, not magnets):
     *   1. `--bt-metadata-only --bt-save-metadata` fetches metadata and writes
     *      `<hex-infohash>.torrent` into a cache dir (no data downloaded).
     *   2. `--show-files <torrent>` prints the table; each entry is run through
     *      [TorrentSecurityShield.checkTorrentFileEntry] (Layers 2/6 + traversal),
     *      so blocked/unsafe files are flagged and never selectable.
     *
     * Returns null when metadata can't be fetched (dead swarm, timeout) — the
     * caller should fall back to a full download rather than fail the task.
     */
    suspend fun listTorrentFiles(
        context: Context,
        magnetUrl: String,
        parentTitle: String = "",
        privacyMode: Boolean = false
    ): List<TorrentSecurityShield.TorrentFileEntry>? {
        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")

        // ---- Phase 1: metadata -> .torrent (no data) ----
        val metaDir = File(context.cacheDir, "torrent_meta").apply { mkdirs() }
        val infoHash = Regex("""(?i)btih:([a-f0-9]{40})""").find(magnetUrl)?.groupValues?.get(1)
            ?: return null
        val torrentFile = File(metaDir, "$infoHash.torrent")

        // Reuse an already-cached .torrent when present (instant list, no swarm contact).
        if (!torrentFile.exists()) {
            val fetchCmd = mutableListOf(aria2Exec.absolutePath)
            if (privacyMode) {
                // Metadata via trackers only: no DHT announce that would put
                // this IP into the swarm's peer-discovery tables.
                fetchCmd += "--enable-dht=false"
                fetchCmd += "--bt-enable-lpd=false"
                fetchCmd += "--enable-peer-exchange=false"
                fetchCmd += "--bt-require-crypto=true"
            } else {
                fetchCmd += "--enable-dht=true"
                fetchCmd += "--bt-enable-lpd=true"
                fetchCmd += "--enable-peer-exchange=true"
                fetchCmd += "--dht-entry-point=router.bittorrent.com:6881"
                fetchCmd += "--dht-entry-point=dht.transmissionbt.com:6881"
                fetchCmd += "--dht-entry-point=dht.libtorrent.org:25401"
                fetchCmd += "--dht-entry-point6=[2400:cb00:2049:1::a29f:9877]:6881"
                fetchCmd += "--dht-file-path=${context.cacheDir.absolutePath}/dht.dat"
            }
            fetchCmd += listOf(
                "--seed-time=0",
                "--seed-ratio=0.0",
                "--summary-interval=0",
                "--bt-max-peers=64",
                "--bt-request-peer-speed-limit=50M",
                "--bt-stop-timeout=45",
                "--bt-tracker-connect-timeout=10",
                "--bt-tracker-timeout=10",
                "--bt-tracker=$TIER1_TRACKERS",
                "--bt-metadata-only=true",
                "--bt-save-metadata=true",
                "-d", metaDir.absolutePath,
                magnetUrl
            )
            val fetchPb = ProcessBuilder(fetchCmd)
            fetchPb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            fetchPb.redirectErrorStream(true)
            val fetchProc = try {
                fetchPb.start()
            } catch (e: Exception) {
                android.util.Log.w("AnonDownload", "listTorrentFiles: launch failed: ${e.message}")
                return null
            }
            val fetchOk = withTimeoutOrNull(45_000) {
                val handle = coroutineContext[Job]?.invokeOnCompletion {
                    if (fetchProc.isAlive) fetchProc.destroyForcibly()
                }
                try {
                    runInterruptible(Dispatchers.IO) { fetchProc.waitFor() }
                } catch (ce: CancellationException) {
                    // The interrupt only unblocks the waiter; the child process
                    // itself must still be destroyed (it is not registered in
                    // activeNativeProcesses, so nothing else can reach it).
                    fetchProc.destroyForcibly()
                    throw ce
                } finally {
                    handle?.dispose()
                }
            } ?: run {
                fetchProc.destroyForcibly()
                return null
            }
            if (fetchOk != 0 || !torrentFile.exists()) {
                android.util.Log.w("AnonDownload", "listTorrentFiles: metadata fetch failed (code=$fetchOk)")
                return null
            }
        }

        // ---- Phase 2: --show-files on the .torrent ----
        val showCmd = listOf(
            aria2Exec.absolutePath,
            "--show-files=true",
            torrentFile.absolutePath
        )
        val showPb = ProcessBuilder(showCmd)
        showPb.redirectErrorStream(true)
        val showProc = try {
            showPb.start()
        } catch (e: Exception) {
            android.util.Log.w("AnonDownload", "listTorrentFiles: show-files launch failed: ${e.message}")
            return null
        }
        val output = withTimeoutOrNull(15_000) {
            val handle = coroutineContext[Job]?.invokeOnCompletion {
                if (showProc.isAlive) showProc.destroyForcibly()
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    showProc.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (ce: CancellationException) {
                showProc.destroyForcibly()
                throw ce
            } finally {
                handle?.dispose()
            }
        } ?: run {
            showProc.destroyForcibly()
            return null
        }

        // aria2c --show-files prints two lines per entry (verified live):
        //   "  1|./Sintel/Sintel.de.srt"      then "   |1.6KiB (1,652)"
        val entries = mutableListOf<TorrentSecurityShield.TorrentFileEntry>()
        val pathRe = Regex("""^\s*(\d+)\|(.+)$""")
        val sizeRe = Regex("""\(\s*([\d,]+)\s*\)$""")
        val lines = output.lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val pm = pathRe.matchEntire(lines[i].trimEnd())
            if (pm != null) {
                val index = pm.groupValues[1].toInt()
                val path = pm.groupValues[2]
                var length = 0L
                if (i + 1 < lines.size) {
                    sizeRe.find(lines[i + 1])?.let { m ->
                        length = m.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    }
                }
                val checked = TorrentSecurityShield.checkTorrentFileEntry(path, length, parentTitle)
                entries.add(checked.copy(index = index))
                i += 2
                continue
            }
            i += 1
        }

        if (entries.isEmpty()) {
            android.util.Log.w("AnonDownload", "listTorrentFiles: no entries parsed")
            return null
        }
        return entries
    }

    private suspend fun downloadMagnetAria2c(
        context: Context,
        taskId: String,
        magnetUrl: String,
        targetDir: File,
        preferredFilename: String,
        parallelSockets: Int = 16,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        isActiveCheck: suspend () -> Boolean = { true },
        selectIndexes: List<Int>? = null,
        maxAttempts: Int = 3,
        peersOverride: Int = -1,
        speedLimitKbs: Int = 0,
        privacyMode: Boolean = false,
        estimatedTotal: Long = 0L
    ): File? {
        val before = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")
        val cmd = mutableListOf(aria2Exec.absolutePath)
        if (privacyMode) {
            // qBittorrent anonymous-mode lessons: your IP stops propagating
            // through peer-discovery gossip (DHT/LPD/PEX off), peer links must
            // be encrypted, and the listen port leaves the fingerprintable
            // 6881 band. Trackers alone still find peers.
            cmd += "--enable-dht=false"
            cmd += "--bt-enable-lpd=false"
            cmd += "--enable-peer-exchange=false"
            cmd += "--bt-require-crypto=true"
            // aria2c reads 0 as UNLIMITED here, so cap at 1K instead — near-zero
            // upload without breaking swarm etiquette mid-download.
            cmd += "--max-overall-upload-limit=1K"
            // Per-task ephemeral port range; hash spreads concurrent tasks.
            val base = 49152 + (taskId.hashCode() and 0x7FFFFFFF) % 8000
            cmd += "--listen-port=$base-${base + 31}"
        } else {
            cmd += listOf(
                "--enable-dht=true",
                "--bt-enable-lpd=true",
                "--enable-peer-exchange=true",
                // Live-verified 2026-08: router.bittorrent.com does not answer DHT
                // pings from some networks; transmissionbt + libtorrent.org do.
                "--dht-entry-point=router.bittorrent.com:6881",
                "--dht-entry-point=dht.transmissionbt.com:6881",
                "--dht-entry-point=dht.libtorrent.org:25401",
                "--dht-entry-point6=[2400:cb00:2049:1::a29f:9877]:6881",
                // Android has no $HOME, so aria2c's default ~/.aria2/dht.dat never
                // persists: every launch cold-starts an empty DHT table. Pin it into
                // cacheDir so the routing table survives across downloads AND runs.
                "--dht-file-path=${context.cacheDir.absolutePath}/dht.dat"
            )
        }
        cmd += listOf(
            "--seed-time=0",
            "--seed-ratio=0.0",
            "--summary-interval=1",
            // Concrete user override wins; Auto (-1) resolves to the live RAM
            // tier so the Settings label "Auto (RAM-detected)" is truthful
            // (it used to fall back to a connections-based formula instead).
            "--bt-max-peers=${if (peersOverride > 0) peersOverride else AppSettings.detectRamTier(context)}",
            "--bt-request-peer-speed-limit=50M",
            "--bt-stop-timeout=300",
            // Fail fast on dead trackers: 30s connect + 60s default announce
            // timeout burned a minute per dead tracker before.
            "--bt-tracker-connect-timeout=10",
            "--bt-tracker-timeout=10",
            // This is the magnet-only path: allocate lazily — falloc would
            // reserve the FULL torrent size the instant a magnet starts (8GB+
            // season = instant storage shock, and a big hole if the swarm is
            // dead). Sparse growth costs nothing.
            "--file-allocation=none",
            "--check-certificate=false",
            "--continue=true",
            "--max-tries=10",
            "--retry-wait=1",
            "--follow-torrent=mem",
            "--bt-save-metadata=true",
            "--bt-load-saved-metadata=true",
            "--auto-save-interval=15",
            "--allow-overwrite=false",
            "--console-log-level=error",
            "--bt-tracker=$TIER1_TRACKERS",
            "-d", targetDir.absolutePath
        )
        // Selective download (season packs): --select-file takes only numeric
        // indexes, never names -- the swarm's file paths never reach the command
        // line, so there is no injection surface from untrusted torrent names.
        if (!selectIndexes.isNullOrEmpty()) {
            cmd += "--select-file=" + selectIndexes.joinToString(",")
        }
        if (speedLimitKbs > 0) {
            cmd += "--max-download-limit=${speedLimitKbs}K"
        }
        // Single magnet URI, always last: aria2c binds options to the downloads
        // that follow them, so the URI must come after --select-file etc. (it
        // was previously passed twice, which orphaned those flags on a second job).
        cmd += magnetUrl

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        suspend fun runOnce(): File? {
            val pb = ProcessBuilder(cmd)
            // Statically linked binary (see NOTICE): no LD_LIBRARY_PATH/PATH needed.
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.directory(targetDir)
            pb.redirectErrorStream(true)

            coroutineContext.ensureActive()
            activeNativeProcesses.remove(taskId)?.destroyForcibly()
            val process = pb.start()
            activeNativeProcesses[taskId] = process

            val completionHandle = coroutineContext[Job]?.invokeOnCompletion {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }

            // aria2c stdout stream: parse with parseProgressTick and smooth ETA
            var lastDl = 0L
            var lastTot = estimatedTotal
            val etaCalc = FlickerFreeEtaCalculator()
            val fallbackRegex = Regex("""\((\d+)%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)
            val reader = process.inputStream.bufferedReader()
            val logBuffer = mutableListOf<String>()

            var exitCode: Int? = null
            try {
                var line: String? = reader.readLine()
                while (line != null) {
                    coroutineContext.ensureActive()
                    if (logBuffer.size < 50) {
                        logBuffer.add(line)
                    } else {
                        logBuffer.removeAt(0)
                        logBuffer.add(line)
                    }
                    val tick = parseProgressTick(line, 0f, lastDl, lastTot)
                    if (tick.downloadedBytes > 0L || tick.totalBytes > 0L || tick.speedBytesPerSec > 0.0) {
                        lastDl = maxOf(lastDl, tick.downloadedBytes)
                        lastTot = maxOf(lastTot, tick.totalBytes)
                        val eta = if (tick.etaSeconds > 0L) tick.etaSeconds else etaCalc.update(lastDl, lastTot, tick.speedBytesPerSec)
                        onProgress(lastDl, lastTot, tick.speedBytesPerSec, eta)
                    } else {
                        val fb = fallbackRegex.find(line)
                        if (fb != null) {
                            val spd = parseSpeedString(fb.groupValues[2])
                            val pct = fb.groupValues[1].toLongOrNull() ?: 0L
                            val inferredDl = if (lastDl > 0L) lastDl else if (lastTot > 0L && pct in 1..100) (lastTot * pct) / 100L else 0L
                            val eta = etaCalc.update(inferredDl, lastTot, spd)
                            onProgress(inferredDl, lastTot, spd, eta)
                        }
                    }
                    line = reader.readLine()
                }
                exitCode = process.waitFor()
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                errors.append("run ").append(attempts).append(": ").append(e.message ?: e.javaClass.simpleName).append('\n')
                return null
            } finally {
                completionHandle?.dispose()
                activeNativeProcesses.remove(taskId, process)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }

            if (exitCode != 0) {
                val errSummary = logBuffer.takeLast(3).joinToString(" | ")
                errors.append("run ").append(attempts).append(" exit ").append(exitCode).append(": ").append(errSummary).append('\n')
                return null
            }

            fun isFinal(f: File) = f.exists() && f.isFile && f.length() >= MIN_PRODUCED_BYTES &&
                    !f.name.endsWith(".aria2") && !f.name.endsWith(".part") &&
                    !f.name.endsWith(".ytdl") && !f.name.endsWith(".torrent")

            val candidates = targetDir.listFiles { f -> isFinal(f) }?.toList() ?: emptyList()
            val fresh = candidates.filter { it.absolutePath !in before }
            val stem = preferredFilename.substringBeforeLast('.')
            val found = fresh.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                ?: fresh.maxByOrNull { it.lastModified() }
                // A resumed aria2 run (--continue=true) keeps writing the SAME
                // file that existed before this attempt, so a legitimate result
                // is not always "fresh" — but it must still carry the name we
                // asked aria2 for. The previous "newest file in the folder"
                // fallback here could bless an UNRELATED finished episode that
                // already lived in the season directory and report the torrent
                // as a success, so the folder-wide match is by stem only.
                ?: candidates.firstOrNull { it.nameWithoutExtension == stem || it.name.startsWith("$stem.") }
                // Multi-file torrents (season packs) land as a NEW directory
                // whose File.length() is ~0 — when no file matched, take the
                // most-recently-created directory as the produced artifact.
                ?: targetDir.listFiles { f -> f.isDirectory && f.absolutePath !in before }?.maxByOrNull { it.lastModified() }

            // A sibling .aria2 control file marks the data file as still partial
            // (resume bookkeeping), so never treat it as a finished download.
            if (found != null && !File(found.absolutePath + ".aria2").exists()) return found

            errors.append("run ").append(attempts).append(": no complete file (control file still present)\n")
            return null
        }

        // Task-level retry with resume: the .aria2 control file keeps aria2c's
        // per-piece progress across runs, so a retry continues instead of
        // restarting (BitTorrent piece-queue parity). Never relaunch after the
        // job was paused or cancelled.
        while (attempts < maxAttempts && produced == null) {
            attempts++
            if (!isActiveCheck()) throw CancellationException("Task was cancelled before magnet retry")
            produced = runOnce()
            if (produced == null && attempts < maxAttempts) {
                cancellableRetryWait(2_000L * attempts, isActiveCheck = isActiveCheck)
            }
        }
        if (produced == null) {
            throw Exception("aria2c failed after $attempts attempt(s): ${errors.toString().trim()}")
        }
        return produced
    }

    private suspend fun downloadHttpAria2c(
        context: Context,
        taskId: String,
        url: String,
        targetDir: File,
        preferredFilename: String,
        referer: String = "",
        origin: String = "",
        ua: String = "",
        customHeaders: Map<String, String> = emptyMap(),
        parallelSockets: Int = 16,
        speedLimitKbs: Int = 0,
        maxAttempts: Int = 3,
        onProgress: (downloaded: Long, total: Long, speed: Double, eta: Long) -> Unit,
        isActiveCheck: suspend () -> Boolean = { true },
        estimatedTotal: Long = 0L
    ): File? = withContext(Dispatchers.IO) {
        val aria2Exec = findAria2Executable(context)
            ?: throw IllegalStateException("aria2c binary missing: libaria2c.so not found in native libs")

        targetDir.mkdirs()

        // BUG FIX: Only resume an existing file if it matches preferredFilename directly
        // OR has an active `.aria2` control file next to it. Never match arbitrary files
        // by stem alone (which previously matched and overwrote .srt subtitles or finished files).
        val stem = preferredFilename.substringBeforeLast('.')
        val existingPartial = targetDir.listFiles { f ->
            f.isFile && f.length() > 0 && (
                f.name == preferredFilename ||
                (f.nameWithoutExtension == stem && File(f.absolutePath + ".aria2").exists())
            )
        }?.firstOrNull()
        val effOutputName = existingPartial?.name ?: preferredFilename
        val expectedOutputFile = File(targetDir, effOutputName)
        val expectedControlFile = File(expectedOutputFile.absolutePath + ".aria2")

        val sockets = HostPolicyRegistry.effectiveSocketsForUrl(url, parallelSockets)
        val allowInsecure = HostPolicyRegistry.allowsPermissiveTlsFallback(url)

        val cmd = mutableListOf(
            aria2Exec.absolutePath,
            "-c",
            "-d", targetDir.absolutePath,
            "-o", effOutputName,
            "--file-allocation=none",
            "--check-certificate=${!allowInsecure}",
            "--summary-interval=1",
            "--max-tries=5",
            "--retry-wait=2",
            "--connect-timeout=15",
            "--timeout=30",
            "--auto-file-renaming=false",
            // Only allow overwrite if an aria2 control file exists or we are resuming a partial
            "--allow-overwrite=${expectedControlFile.exists() || existingPartial != null}",
            "--console-log-level=error",
            "-s", "$sockets",
            "-x", "$sockets",
            "-j", "$sockets",
            "--max-connection-per-server=$sockets",
            "--min-split-size=1M",
            // Directive Fix 6 (aria2c 1DM parity). --lowest-speed-limit is
            // deliberately OMITTED: aria2c applies it absolutely, so a 25K
            // value aborts every download on a genuinely slow link (the
            // maintainer connection measured 8.7 KB/s) — the same failure
            // class the Turbo relative-drop guard exists to avoid.
            "--stream-piece-selector=inorder",  // clean contiguous prefix for resume
            "--disk-cache=32M",                 // in-memory write cache, less flash I/O
            "--http-no-cache=true"              // bypass stale throttling proxy caches
        )

        if (ua.isNotBlank()) {
            val cleanUa = ua.replace("\r", "").replace("\n", "").trim()
            cmd += "--user-agent=$cleanUa"
        }
        if (referer.isNotBlank()) {
            val cleanRef = referer.replace("\r", "").replace("\n", "").trim()
            cmd += "--referer=$cleanRef"
        }
        if (origin.isNotBlank()) {
            val cleanOrig = origin.replace("\r", "").replace("\n", "").trim()
            cmd += "--header=Origin: $cleanOrig"
        }
        for ((k, v) in customHeaders) {
            if (k.equals("user-agent", ignoreCase = true) ||
                k.equals("referer", ignoreCase = true) ||
                k.equals("origin", ignoreCase = true)
            ) continue
            val cleanK = k.replace("\r", "").replace("\n", "").trim()
            val cleanV = v.replace("\r", "").replace("\n", "").trim()
            if (cleanK.isNotBlank() && cleanV.isNotBlank()) {
                cmd += "--header=$cleanK:$cleanV"
            }
        }
        if (speedLimitKbs > 0) {
            cmd += "--max-download-limit=${speedLimitKbs}K"
        }
        cmd += url

        val errors = StringBuilder()
        var produced: File? = null
        var attempts = 0

        suspend fun runOnce(): File? {
            coroutineContext.ensureActive()
            // Ensure any previous process for this taskId is terminated before starting a new one
            activeNativeProcesses.remove(taskId)?.destroyForcibly()

            val pb = ProcessBuilder(cmd).apply {
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                directory(targetDir)
                redirectErrorStream(true)
            }

            val process = pb.start()
            activeNativeProcesses[taskId] = process

            // Guarantee process termination if the parent coroutine is cancelled while blocked on I/O
            val completionHandle = coroutineContext[Job]?.invokeOnCompletion {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }

            var lastDl = if (expectedOutputFile.exists()) expectedOutputFile.length() else 0L
            var lastTot = estimatedTotal
            val etaCalc = FlickerFreeEtaCalculator()
            val fallbackRegex = Regex("""\((\d+)%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)
            val logBuffer = ArrayDeque<String>(50)
            var exitCode: Int? = null

            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        coroutineContext.ensureActive()
                        if (logBuffer.size >= 50) logBuffer.removeFirst()
                        logBuffer.addLast(line)

                        val tick = parseProgressTick(line, 0f, lastDl, lastTot)
                        if (tick.downloadedBytes > 0L || tick.totalBytes > 0L || tick.speedBytesPerSec > 0.0) {
                            lastDl = maxOf(lastDl, tick.downloadedBytes)
                            lastTot = maxOf(lastTot, tick.totalBytes)
                            val eta = if (tick.etaSeconds > 0L) {
                                tick.etaSeconds
                            } else {
                                etaCalc.update(lastDl, lastTot, tick.speedBytesPerSec)
                            }
                            onProgress(lastDl, lastTot, tick.speedBytesPerSec, eta)
                        } else {
                            val fb = fallbackRegex.find(line)
                            if (fb != null) {
                                val spd = parseSpeedString(fb.groupValues[2])
                                val pct = fb.groupValues[1].toLongOrNull() ?: 0L
                                val inferredDl = if (lastDl > 0L) lastDl else if (lastTot > 0L && pct in 1..100) (lastTot * pct) / 100L else 0L
                                val eta = etaCalc.update(inferredDl, lastTot, spd)
                                // BUG FIX: Never pass (0L, 0L, spd, 0L) which wipes out DB/UI progress!
                                onProgress(inferredDl, lastTot, spd, eta)
                            }
                        }
                        line = reader.readLine()
                    }
                }
                exitCode = process.waitFor()
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                errors.append("run ").append(attempts).append(": ")
                    .append(e.message ?: e.javaClass.simpleName).append('\n')
                return null
            } finally {
                completionHandle?.dispose()
                activeNativeProcesses.remove(taskId, process)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }

            if (exitCode != 0) {
                val errSummary = logBuffer.takeLast(3).joinToString(" | ")
                errors.append("run ").append(attempts).append(" exit ").append(exitCode)
                    .append(": ").append(errSummary).append('\n')
                return null
            }

            // BUG FIX: Check the exact expected output file and verify it is a file (never a directory)
            // and has no lingering .aria2 control file.
            if (expectedOutputFile.isFile && expectedOutputFile.length() > 0L && !expectedControlFile.exists()) {
                return expectedOutputFile
            }

            errors.append("run ").append(attempts)
                .append(": output file missing or incomplete (.aria2 control file still present)\n")
            return null
        }

        while (attempts < maxAttempts && produced == null) {
            attempts++
            if (!isActiveCheck()) throw CancellationException("Task cancelled before aria2c retry")
            produced = runOnce()
            if (produced == null && attempts < maxAttempts) {
                cancellableRetryWait(2_000L * attempts, isActiveCheck = isActiveCheck)
            }
        }

        if (produced == null) {
            throw Exception("aria2c HTTP download failed after $attempts attempt(s):${errors.toString().trim()}")
        }
        produced
    }

    internal fun isGenericPreferredName(name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return true
        val lower = clean.lowercase()
        if (lower == "video.mp4" || lower == "download.mp4" || lower == "video" || lower == "download") return true
        if (clean.startsWith("Social_", ignoreCase = true)) return true
        val genericPlatformRegex = Regex(
            """^(Instagram|YouTube|Twitter|Facebook|TikTok|Pinterest|Reddit|X|Social|Web)[\s_]*(Video)?[-\d\s]*(\.[A-Za-z0-9]+)?$""",
            RegexOption.IGNORE_CASE
        )
        return genericPlatformRegex.matches(clean)
    }

    internal fun findAria2Executable(context: Context): File? {
        val libFile = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
        if (libFile.exists()) {
            try { libFile.setExecutable(true) } catch (_: Exception) {}
            return libFile
        }
        return null
    }

    /** CDN families and stream hosts that require an explicit referer —
     *  rewritten-master runs keep the original referer for hotlink protection. */
    private fun requiresExplicitReferer(referer: String): Boolean {
        val low = referer.lowercase().trimEnd('/')
        return low.startsWith("http://") || low.startsWith("https://")
    }
}
```

---

## Section 7: Supporting Pipeline & Repository Interfaces

### 7.1 `DownloadRepository.kt`
File: `app/src/main/java/com/anonrode/downloader/engine/DownloadRepository.kt`

```kotlin
package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DownloadRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private var stateFile: File? = null
    // Set by initPersistence; used to mirror every persist into public
    // Documents so the history survives an uninstall (see HistoryBackup).
    @Volatile private var appContext: android.content.Context? = null

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun initPersistence(dir: File, context: android.content.Context? = null) {
        if (stateFile != null) return
        val f = File(dir, "download_tasks.json")
        stateFile = f
        appContext = context?.applicationContext
        try {
            if (f.exists()) {
                val raw = f.readText()
                if (raw.isNotBlank()) {
                    _tasks.value = json.decodeFromString<List<DownloadTask>>(raw)
                        .map { parkForRestore(it) }
                    persist()
                }
            }
        } catch (_: Throwable) {
            // engine-audit P1: a corrupt or partially-flushed state file used
            // to be DELETED right here with the list emptied — while an intact
            // Documents mirror sat unused, and the very next persist()
            // overwrote that mirror too, destroying the whole history for good.
            // Set the poison file aside for diagnostics instead; the mirror
            // branch below then recovers the tasks (persist() re-syncs the
            // private file immediately after a successful import).
            try { f.renameTo(File(f.parentFile, f.name + ".corrupt")) } catch (_: Throwable) {}
        }
        if (_tasks.value.isEmpty()) {
            try {
                // Fresh install (filesDir wiped, e.g. by an uninstall) but a
                // Documents mirror exists from before: import it, and the
                // persist() below re-syncs the private file immediately.
                val mirrored = appContext?.let { HistoryBackup.load(it) }
                if (!mirrored.isNullOrBlank()) {
                    _tasks.value = json.decodeFromString<List<DownloadTask>>(mirrored).map { parkForRestore(it) }
                    persist()
                }
            } catch (_: Throwable) {}
        }
    }

    private val persistMutex = kotlinx.coroutines.sync.Mutex()

    fun persist() {
        val f = stateFile ?: return
        scope.launch {
            persistMutex.withLock {
                try {
                    val encoded = json.encodeToString(_tasks.value)
                    val tmp = File(f.parentFile, "${f.name}.tmp")
                    tmp.writeText(encoded)
                    if (!tmp.renameTo(f)) {
                        // Engine-audit: the rename result was unchecked — the
                        // one write path with no failure handling. A direct
                        // rewrite keeps the newest state on disk even when the
                        // rename fails (FUSE volumes, transient EBUSY).
                        f.writeText(encoded)
                        tmp.delete()
                    }
                    // Mirror only on meaningful writes (this runs on status
                    // transitions, adds, removes — not per progress tick).
                    appContext?.let { HistoryBackup.save(it, encoded) }
                } catch (_: Throwable) {}
            }
        }
    }

    fun snapshot(): List<DownloadTask> = _tasks.value

    fun find(taskId: String): DownloadTask? = _tasks.value.find { it.id == taskId }

    fun addFirst(task: DownloadTask) {
        _tasks.update { listOf(task) + it }
        persist()
    }

    fun remove(taskId: String) {
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        persist()
    }

    fun update(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
        persist()
    }

    fun updateProgress(taskId: String, downloaded: Long, total: Long, speed: Double, eta: Long) {
        _tasks.update { list ->
            list.map {
                if (it.id == taskId) {
                    // First bytes landing flips RESOLVING -> DOWNLOADING here, in
                    // the SAME atomic write as the progress: some backends (Turbo
                    // direct downloads) report progress with no separate status
                    // event, so without this the card sits at "Resolving" with no
                    // bar while the notification already shows live percent.
                    val status = if (it.status == TaskStatus.RESOLVING && downloaded > 0) {
                        TaskStatus.DOWNLOADING
                    } else it.status
                    // Monotonic downloaded bytes: a late telemetry tick racing the
                    // COMPLETED write (or a stale tick after pause) must never
                    // regress the recorded size. Speed/ETA are only meaningful
                    // while actually transferring — writing them on a PAUSED or
                    // COMPLETED task would re-stamp a stale "0.5 MB/s".
                    // A COMPLETED row is closed: the recorded size is the on-disk
                    // file length, not a backend estimate — a late tick racing the
                    // terminal write must never re-stamp downloaded/total.
                    val finalDl = if (it.status == TaskStatus.COMPLETED) it.downloadedBytes
                    else if (downloaded > 0) maxOf(it.downloadedBytes, downloaded) else it.downloadedBytes
                    val finalTot = if (it.status == TaskStatus.COMPLETED) it.totalBytes else when {
                        total <= 0L -> it.totalBytes
                        it.totalBytes <= 0L -> total
                        it.totalBytes > 10_000_000_000L && total < 5_000_000_000L -> total
                        total > it.totalBytes -> total
                        else -> it.totalBytes
                    }
                    val transferring = status == TaskStatus.DOWNLOADING
                    val finalEta = when {
                        !transferring -> 0L
                        eta > 0L -> eta
                        it.etaSeconds > 0L -> it.etaSeconds
                        else -> 0L
                    }
                    it.copy(
                        status = status,
                        downloadedBytes = finalDl,
                        totalBytes = finalTot,
                        speedBytesPerSec = if (transferring) speed else 0.0,
                        etaSeconds = finalEta
                    )
                } else it
            }
        }
    }
}
```

### 7.2 `StreamValidator.kt`
File: `app/src/main/java/com/anonrode/downloader/pipeline/StreamValidator.kt`

```kotlin
package com.anonrode.downloader.pipeline

import com.anonrode.downloader.data.net.HttpClient
import com.anonrode.downloader.data.net.isTlsChainFailure
import java.io.ByteArrayOutputStream

/**
 * Strict pre-enqueue URL validation (provider contract rule 3).
 *
 * History: HTML error pages entered the download queue as "mp4" tasks and
 * produced corrupted files that Layer 7 could only catch AFTER a full
 * download. This validator spends one ranged request (<=1 KiB) to reject dead
 * links BEFORE any bytes are persisted.
 *
 * Conservative by design — it rejects only provable garbage:
 *  - HTTP >= 400 (except 416, which just means the server ignored Range)
 *  - bodies that are clearly HTML/script/JSON error pages or archive/exec
 *    containers (PK/MZ)
 * Everything else passes; the download backends own transient errors.
 */
object StreamValidator {

    private const val PROBE_BYTES = 1025

    /**
     * @param headers the EXACT headers the real download will send (referer
     *   policies matter: vidbasic 403s with a Referer, megaplay CDNs 403
     *   without theirs — validating with different headers lies either way).
     * @return null when the URL looks downloadable, else a human-readable
     *   rejection reason.
     */
    data class Rejection(val reason: String, val refreshable: Boolean = false)

    fun validate(url: String, headers: Map<String, String>): String? =
        validateResult(url, headers)?.reason

    fun validateResult(url: String, headers: Map<String, String>): Rejection? {
        val start = System.currentTimeMillis()
        var status: Int = -1
        var head = ByteArray(0)

        val req = try {
            okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Accept", "*/*")
                .apply {
                    for ((k, v) in headers) {
                        if (k.isNotBlank() && v.isNotBlank()) {
                            try { header(k, v) } catch (_: IllegalArgumentException) {}
                        }
                    }
                }
                .build()
        } catch (e: Exception) {
            // Malformed URL — same deferral contract as a network error: not
            // proof of a bad link from the probe's point of view.
            PipelineJournal.hop("", "validate", url, ok = true,
                ms = System.currentTimeMillis() - start,
                detail = "probe request error, deferring to backend: ${e.message?.take(80)}")
            return null
        }

        // Strict probe first; a broken TLS chain (wetafiles omits its
        // intermediate) must fall back to the trust-all client instead of
        // deferring to the backends — the backends are strict too, so the
        // deferral just handed the job to yt-dlp's own transport (v3.0.4:
        // ~18s of failing handshakes, no total, then yt-dlp with MB-only
        // progress). One retry only.
        var tlsRetried = false
        while (true) {
            try {
                val client = (if (tlsRetried) HttpClient.permissiveClient else HttpClient.shared)
                    .newBuilder()
                    .connectTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                HttpClient.executeRegistered(client.newCall(req)).use { res ->
                    status = res.code
                    if (status in 200..299 || status == 416) {
                        // readNBytes is API 33+; on Android 10-12 it throws
                        // NoSuchMethodError (an Error, invisible to the catch
                        // below) and crashes the app. Read in a loop instead.
                        head = res.body?.byteStream()?.let { s ->
                            ByteArrayOutputStream(PROBE_BYTES).use { baos ->
                                val buf = ByteArray(256)
                                while (baos.size() < PROBE_BYTES) {
                                    val read = s.read(buf, 0, minOf(buf.size, PROBE_BYTES - baos.size()))
                                    if (read < 0) break
                                    baos.write(buf, 0, read)
                                }
                                baos.toByteArray()
                            }
                        } ?: ByteArray(0)
                    }
                }
                break
            } catch (e: Exception) {
                val tls = isTlsChainFailure(e) && HostPolicyRegistry.allowsPermissiveTlsFallback(url)
                PipelineJournal.hop("", "validate", url, ok = true,
                    ms = System.currentTimeMillis() - start,
                    detail = if (tls && !tlsRetried)
                        "strict TLS probe failed (${e.message?.take(60)}), retrying permissive"
                    else
                        "probe network error, deferring to backend: ${e.message?.take(80)}")
                if (!tls || tlsRetried) {
                    if (e is java.net.ConnectException || e is java.net.UnknownHostException) {
                        return Rejection(reason = "Storage node unreachable: ${e.message?.take(60)}", refreshable = true)
                    }
                    // Transient network errors during probe defer to backend
                    return null
                }
                tlsRetried = true
            }
        }

        if (status >= 400 && status != 416) {
            // The doc contract: reject HTTP >= 400 (except 416). Previously
            // these responses fell through with an empty head and passed as
            // "looks downloadable", so dead/expired links spent a task slot
            // and extra probe traffic before failing in the backend.
            val isLockerFrontend = LinkResolver.isKnownLockerHost(url)
            val detailMsg = if (status == 429 && isLockerFrontend) {
                "HTTP 429: hit locker frontend instead of cracked storage backend"
            } else {
                "HTTP $status"
            }
            if (status == 429) {
                HostHealth.recordFail(url, rateLimited = true, reason = detailMsg)
            }
            val reason = if (status == 429 && isLockerFrontend) {
                "Locker frontend returned HTTP 429 (unresolved locker redirector — was not cracked to storage backend)"
            } else {
                "Server rejected the link (HTTP $status) — it is dead or expired"
            }
            PipelineJournal.hop("", "validate", url, ok = false,
                ms = System.currentTimeMillis() - start,
                detail = detailMsg)
            return Rejection(reason, status in setOf(401, 403, 404, 410))
        }

        if (status == 416 || head.isEmpty()) {
            // Can't inspect (range refused / empty body) -> don't guess.
            return null
        }

        val reason = sniff(head)
        PipelineJournal.hop("", "validate", url, ok = reason == null,
            ms = System.currentTimeMillis() - start,
            detail = reason ?: "HTTP $status, ${head.size}B probed")
        return reason?.let { Rejection(it, it == "URL serves an HTML/error page, not media") }
    }

    /** Rejection reason for a file head, or null when plausibly media. */
    fun sniff(head: ByteArray): String? {
        if (head.size < 4) return null
        val offset = if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) 3 else 0
        val text = String(head, offset, head.size - offset, Charsets.UTF_8).trimStart().lowercase()
        if (text.startsWith("<!doctype html") || text.startsWith("<html") ||
            text.startsWith("<head") || text.startsWith("<body") ||
            text.startsWith("<script") || text.startsWith("<?xml") ||
            text.startsWith("<!--") || text.startsWith("<meta") ||
            text.startsWith("<iframe") || text.startsWith("<style") ||
            text.startsWith("<form") || text.startsWith("<svg") ||
            text.startsWith("{")) {
            return "URL serves an HTML/error page, not media"
        }
        if (head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) {
            return "URL serves an archive/APK container (PK header)"
        }
        if (head[0] == 0x4d.toByte() && head[1] == 0x5a.toByte()) {
            return "URL serves a Windows executable (MZ header)"
        }
        return null
    }
}
```

---

## Section 8: End-to-End File & State Invariant Trace

The following step-by-step trace documents the lifecycle of disk artifacts and state transitions for a download that starts on `TurboDownloader`, encounters an interruption, and hands off to `aria2c`:

### 1. Enqueue & Destination Reservation
- User enqueues: `https://storage.cdn.com/episodes/Show.S01E01.1080p.mp4?preview=0&token=sec_abc123`.
- `UrlClassifier.stripPreviewParam()` strips `preview=0` while preserving `token=sec_abc123`.
- Destination directory resolved to `Downloads/Anonrode/Show/`.
- File uniqueness check reserves: `Show.S01E01.1080p.mp4`.
- Initial database record created with `status = QUEUED`, `filePath = "/storage/emulated/0/Download/Anonrode/Show/Show.S01E01.1080p.mp4"`.

### 2. Startup & TurboDownloader Segmented Execution
- Slot becomes available in `DownloadEngine.processQueue()`.
- `TaskRuntimeRegistry.tryAcquireStartLock(taskId)` succeeds; task status set to `DOWNLOADING`.
- `ProgressWatchdog.launchForTask(...)` spawns monitoring coroutine.
- Routing selects `TurboDownloader.download()`:
  - Total size probed: 1,000,000,000 bytes (1 GB).
  - Sparse data file pre-allocated: `Show.S01E01.1080p.mp4.part` (1 GB sparse file on disk).
  - Bitset sidecar created: `Show.S01E01.1080p.mp4.turbo` (piece size = 1 MB, 1000 pieces).
  - `computeVerifiedDiskBytes()` inspects `TurboState.writtenBytes()`; reports actual transferred bytes (0 MB -> 400 MB), preventing watchdog false stalls.

### 3. Non-429 Interruption at 40%
- Connection drops or socket reset occurs at 400 MB.
- Backend returns `TurboResult.Failure(httpStatus = null, htmlPage = false, error = "Connection reset by peer")`.
- `coroutineContext.isActive` remains true; token self-healing checks if link expired.
- Because it is a network drop and not an HTTP 429 rate limit, engine proceeds to aria2c handoff.

### 4. Contiguous Prefix Handoff via `handoffTurboPrefixToAria2c()`
- Engine checks `Show.S01E01.1080p.mp4.part` and `Show.S01E01.1080p.mp4.turbo`.
- `TurboState.contiguousPrefixBytes()` computes verified contiguous byte offset = 400,000,000 bytes (400 MB).
- `partFile.renameTo(dest)` renames `.part` directly to `Show.S01E01.1080p.mp4`.
- `RandomAccessFile.setLength(400_000_000L)` truncates any sparse uncommitted chunks past 400 MB.
- `TurboState.delete()` removes `Show.S01E01.1080p.mp4.turbo`.
- Resulting disk state: Only `Show.S01E01.1080p.mp4` (400 MB) exists on disk. No orphaned `.part` or `.turbo` remains.

### 5. Native aria2c Resume (`downloadHttpAria2c`)
- Engine invokes `YoutubeDlDownloader.download(..., backend = "aria2c")`.
- `existingPartial` detects `Show.S01E01.1080p.mp4` (exact name match, length = 400 MB).
- Native aria2c is executed with arguments:
  `-c -d /storage/emulated/0/Download/Anonrode/Show/ -o Show.S01E01.1080p.mp4 --allow-overwrite=true ...`
- `aria2c` creates its control file: `Show.S01E01.1080p.mp4.aria2`.
- `aria2c` sends HTTP request with `Range: bytes=400000000-` and resumes downloading remaining 600 MB.
- Process cancellation cleanup is bound: if user pauses or cancels, `invokeOnCompletion` forcibly destroys native process.

### 6. Validation & MediaScan Finalization
- `aria2c` completes remaining 600 MB and exits with code 0.
- `aria2c` automatically deletes `Show.S01E01.1080p.mp4.aria2`.
- `isFinal(f)` verifies `f.exists() && f.isFile && f.length() > 0L`.
- `StreamValidator` validates media headers (MP4 `ftyp` box present, non-HTML).
- Database updated with `status = COMPLETED`, `downloadedBytes = 1000000000L`, `totalBytes = 1000000000L`.
- Sidecar cleanup sweep runs defensively:
  `File("Show.S01E01.1080p.mp4.part").delete()`
  `File("Show.S01E01.1080p.mp4.turbo").delete()`
  `File("Show.S01E01.1080p.mp4.aria2").delete()`
- `MediaScan.notifyFile()` registers the completed file with Android MediaStore.
- `TaskRuntimeRegistry.releaseStartLock(taskId)` frees the download slot.


---

## Section 9: Master Directive Cross-File Fixes (Final Production Pass)

Applied on top of the 16-bug refactor above; verified by Phase 3.4 of the adversarial suite and by the independent QA pass.

| # | Fix | Implementation Evidence |
|---|---|---|
| 1 | SocketTimeoutException retry trap | `downloadPiece` catch: CancellationException force-commits + rethrows; Exception aborts only on `!isActive || !canCommit` else `attempt++`; fallback guard `canCommit && isActive`; full-cancel flag removed from inner finallys of `segmented()`/`single()` (kept at entry/clearCancelledFlag/outer-finally). |
| 2 | Magnet `runOnce` compile error + swallowed cancel | `suspend fun runOnce()`; `catch (e: CancellationException) { process.destroyForcibly(); throw e }`; finally uses conditional `remove(taskId, process)` + isAlive kill (mirrors the HTTP twin). |
| 3 | ICU-unsafe regex escape | HTTP `fallbackRegex` no longer escapes the percent character (the magnet copy was already clean; consistency fix). |
| 4 | aria2c error swallow | `download()` aria2c branch rethrows; the dead always-true `isDirectMedia \|\| backend == "aria2c"` block was deleted. |
| 5 | Torrent-listing orphaned processes | Both blocking waits (`waitFor`, `readText`) wrapped in `invokeOnCompletion { destroyForcibly }` + `runInterruptible(Dispatchers.IO)`, with explicit CancellationException destroy. |
| 6 | `parkForRestore` existence | Verified present in `RestorePolicy.kt:29` (same package as DownloadRepository); no code change required. |
| 7 | StreamValidator pre-flight restored | aria2c branch: skip when provably-direct/banked; refreshable rejection re-resolves once + updates DB; else cross-provider failover attempt or `throw PipelineError.ValidationFailed`. |
| 8 | Existing `.aria2` partial resume + error preservation | `hasExistingAria2Partial` gate skips Turbo; aria2c fallback wrapped in try/catch capturing `aria2Error` (CancellationException rethrown); `errReason` consults `aria2Error` (rate-limit branch first). |
| 9 | Stale `task.filePath` snapshot | `startTask(initialTask, lockAlreadyHeld)`; `var task` re-read and refreshed at ext-sync and both completion branches; `val originalFilePath` + pre-completion path capture feed the rename guard and sidecar sweeps. |
| 10 | Queue↔start lock & registration race | Selection excludes `activeIds`; start lock acquired before the pause-conditional status flip; `CoroutineStart.LAZY` launch + `attachJob` before `start()`; stranding windows repaired. |
| 11 | Wi-Fi gate head-of-line block + unpark | Magnet gate parks + `continue`; wifiOnlyAll parks all queued; `saveAllSettings` unparks matching messages when gates are disabled (deliberately not gated on connectivity). |
| 12 | Stall-kill counter wipe + budget sync | Else-branch now `grantRetryBypass` (bypass only, counters preserved); `MAX_STALL_KILLS = 8` in ProgressWatchdog; dead duplicate constants removed from the engine. |
| 13 | Socket floor 1→2 | `effectiveSocketsForUrl` returns 1 for an explicit 1; kissorgrab.com and dl.plutomovies.com capped to 1 per charter rule 2. |
| 14 | Preview param + image extensions | Case-insensitive valueless `preview` removal via OkHttp `queryParameterNames`; jpg/jpeg/png/webp classified; decoded query scanned; last `filename=` wins; images keep manifest path heuristics. |
| 15 | PAUSED re-enqueue no-op | PAUSED removed from `activeStatuses`; resumable lookup `(FAILED \|\| PAUSED)` routes to `retry()` (corruption-gated, `.aria2`-preserving). |
| 16 | Registry cleanup, TLS scoping, delegations | Both COMPLETED branches call the non-cancelling identity-guarded `forget()`; yt-dlp `--no-check-certificate` gated on the allowlist; StreamValidator TLS retry gated; cluster sets + URL parsing delegated to HostPolicyRegistry/UrlClassifier. |

---

## Section 10: Deviations & Senior-Judgment Adjustments

Each entry: what the directive said, what was implemented, and why.

1. **Fix 1 flag lifetime — kept the entry-time stale-flag clear (TurboDownloader `download()` entry) and `clearCancelledFlag`.** The directive asked to keep the removal "only in the outer finally". Removing the entry-time clear would let a cancel that lands before a run poison the next run's `canCommit` state; the entry clear is a start-of-run staleness guard, not an inner-finally removal.
2. **Fix 3 applied although its stated root cause is inaccurate.** A backslash-escaped percent is a legal redundant escape in the JVM/ICU regex engines — it would not throw `PatternSyntaxException`. The change was applied for consistency with the magnet copy; documented as cosmetic.
3. **Fix 6 is a verification no-op.** `parkForRestore` already exists as a top-level `internal fun` in `RestorePolicy.kt` (same package), so `DownloadRepository.initPersistence()` compiles. Adding a duplicate member would have been worse than doing nothing.
4. **Fix 12 implemented as bypass-only (`grantRetryBypass`) instead of deleting the else branch** (explicit user decision). Deleting the branch entirely would also drop the stall-retry health-check bypass; the counter wipe (the actual bug) is fixed while the bypass — its use case — survives.
5. **Fix 16a uses the non-cancelling `forget()` with an identity guard, not raw `remove()`.** `remove()` cancels the entry's active job; called from inside that very coroutine's completion path it would cancel the job mid-completion and skip sidecar cleanup and the completion notification.
6. **Fix 7 restores only the aria2c-branch pre-flight** (the directive's scope). HEAD contained three further `validateResult` call sites (yt-dlp retry validation, mirror guard) that the refactor also removed; they remain unrestored — see Open Item O5.
7. **Magnet aria2c `--check-certificate=false` left global.** The allowlist is locker-host-scoped; applying it to magnets would effectively force strict TLS on all torrent tracker/peer traffic — a regression risk with no locker-threat model (BT payloads are infohash-verified). Flagged as open item.
8. **Fix 4's snippet contained an always-true condition** (`isDirectMedia || backend == "aria2c"` inside the `backend == "aria2c"` branch). Implemented as an unconditional rethrow and deleted the now-dead `isDirectMedia` computation, matching the fix's stated intent without dead code.
9. **Fix 13 extended beyond the directive:** `kissorgrab.com` and `dl.plutomovies.com` capped to **1** socket (not 4). AGENTS.md rule 2 (live-verified 2026-08, binding charter) forces single-socket for exactly these two hosts; the blanket 4-cap silently violated it, and the stale code comments claiming a forced single socket were false until this change.
10. **TLS allowlist extended beyond the directive's six hosts** with `lulacloud.com`, `mixdrop.co`, and `loadedfiles.com/.st/.to`. In-repo live-verified evidence (`resolvers/Resolvers.kt` comments: lulacloud broken chain verify code 20, mixdrop mismatched chains) and `scraper_rules.json` (loadedfiles .com/.st/.to are active terminal hosts) prove these would otherwise regress from working downloads to hard TLS failures. Availability protection scoped strictly to documented-broken hosts.
11. **Purge extension resolution: classifier + legacy fallback** (instead of classifier-only). The classifier knows media extensions only; legacy handoff artifacts are named after raw URL extensions (`download.php?token=...` → `Ep 1.php`), so classifier-only would leave multi-GB strays forever — the exact leak the purge exists to prevent. Subtitle/aux extensions (srt/vtt/ass/ssa/sub) are excluded so a cancel can never purge a sibling subtitle family.
12. **No local compile possible.** No JDK/SDK/Gradle wrapper on the authoring machine; the syntax gate is a bracket/string balancer. Compile verification is deferred to CI; an independent audit of compile-risk constructs found none. Stated plainly rather than presenting the static gate as a compiler.

---

## Section 11: Additional Audit Findings & Triage (4-Lens Fan-Out)

Four read-only agents audited the post-fix tree (independent QA verifier + concurrency/lifecycle, data/state integrity, routing/security/error-propagation lenses), with falsification-style evidence requirements. Conservative triage rule: evidence-backed + contained => minimal fix + gate re-run; real but architectural => documented open item, not changed.

### Fixed in the post-triage patch set (Section 2.6)
- **HIGH** `retry()` deleted valid aria2c partials (`decodeCheck` fails on any truncated container) and could delete during a racing completion write — now `.aria2`-preserving and active-owner-aware.
- **HIGH** Wi-Fi gate toggled off while offline left tasks parked forever (both unpark paths required connectivity/Wi-Fi) — unpark no longer gated on connectivity.
- **MED** Extension-sync rename raced `enqueueLock` reservations (two tasks could own one destination) — rename now holds the same lock.
- **MED** Purge lost legacy URL-extension strays — fallback restored (see Deviation 11).
- **MED** Completion sidecar sweep could cross-delete another task's partials after a rename freed the name — both sweeps now skip paths owned by other tasks.
- **MED** Pause racing a queue start could strand a DOWNLOADING row with no coroutine (introduced by the LAZY change) — flip is pause-conditional and stranded flips are repaired.
- **MED** A non-`Exception` throwable (Error) killed the job without repairing its status — dead jobs now repair a row they own.
- **MED** Magnet metadata subprocess survived cancellation (completion hook disposed before it could fire; interrupt doesn't kill the child) — explicit CancellationException destroy for both listing phases.
- **MED-LOW** Stale retry ids (removed tasks) minted permanent registry entries — guarded + forget on deleted-task registration.
- **LOW** `fullyCancelledTaskIds` grew unboundedly for tasks that never re-enter Turbo — pruned in finally; flag marking gated on a live job in both cancel paths.
- **LOW** Watchdog stall kill could overwrite a racing user pause with FAILED + notification — pause-aware guard.
- **LOW** `cancelTask`/`trackCall` race let one in-flight request escape cancellation — re-check after tracking.
- **LOW** Playlist batching (`triggerQueueProcessing=false`) was defeated by `retry()`'s unconditional `processQueue()` — `processNow` parameter added.

### Open items (documented, deliberately not changed)
- **O1 — `hasExistingAria2Partial` trust model.** A dest-only file (no `.part`, no `.aria2`) is treated as an aria2c-resumable partial, skipping Turbo (and its token self-heal). For a *complete* file paused during VALIDATING this is cheap-and-correct on Range-honoring servers, but a Range-ignoring server answering 200 could truncate-and-rewrite a finished file if paused mid-rewrite. Fixing properly needs a "validate-first" resume branch (or requiring `dest.length() < task.totalBytes`), which flips a directive-specified gate and re-download cost — needs its own design pass.
- **O2 — Quad-state accounting.** In the rare broken-handoff state `dest + .part + .turbo + .aria2`, disk accounting prefers the stale `.turbo` plan over the live `.aria2` progress, and the abandoned `.part` stays until completion cleanup. Impact is bounded (accounting display, eventual cleanup); reordering sidecar precedence touches the watchdog's core read — deferred.
- **O3 — Multi-generation stem sweep.** The completion sweep covers the current and one prior path generation; a task whose extension alternated across three-plus runs could strand the oldest sidecar family. Exotic; a stem-family sweep would widen the delete surface and needs its own safety review.
- **O4 — Pre-flight probe duplication.** The restored ranged probe duplicates Turbo's own probe for plain-extension URLs (~1-3 extra requests per direct task; RSS/TLS budget only, no payload). Optimizing requires plumbing the probe result into Turbo or aligning skip sets — deferred (behavior-preserving today).
- **O5 — Unrestored HEAD validation sites.** Three further `StreamValidator.validateResult` call sites present at HEAD (yt-dlp retry validation, mirror guard) were removed by the refactor and not restored (out of the directive's Fix 7 scope). They are a quality loss vs HEAD, not a defect introduced here.
- **O6 — Image-classifier disagreement.** `UrlClassifier` now knows image extensions, but `isDirectMediaUrl`/`StrictLinkClassifier` do not, so a paused image task re-enters the resolver chain instead of resuming its direct URL. Extending those lists reaches outside the directive's scope (router subsystem) and needs its own regression pass.

### Disproven (falsification attempted, no defect)
- `var turboResult` smart casts, import completeness, suspend-call sites and `!!` usage: all legal (independent QA pass).
- Pause/resume unit-test expectations under the new rethrow: survive unchanged.
- Blast radius of the aria2c rethrow: only two callers, both prepared (engine fallback try/catch; runYtdlp's firstError catch).
- Start-lock lifecycle leak: every acquire reaches release/attachJob; no throwing call in between.
- Registry-entry recreation after `forget()`: finally only does null-tolerant `get` + null-safe `releaseStartLock`.
- Zero AI attribution across all changed sources, docs and suites.


---

## Section 12: 1DM Speed Batch & OTA Policy Plane (2026-10-18)

### 12.1 Shipped changes

| Area | Change | Evidence |
|---|---|---|
| Socket caps | kissorgrab.com / dl.plutomovies.com -> 6; plutomovies / wetafiles / downloadwella / loadedfiles.* -> 8; floor 1 preserved | HostPolicyRegistry.kt; adversarial 3.6-S1 |
| OTA policy plane | `contentPolicy.socketCaps` parsed (lenient, reset-on-absent), overlaid before built-ins at download start; `contentPolicy` family in RulesFlags + publish validator; payload rev 2 | DynamicRulesManager.kt; encrypt_rules.py; adversarial 3.6-P1..P4 |
| Lock-free writes | `synchronized(channel)` removed around positional `pwrite64` writes (disjoint per-piece ranges); drain-loop retained | TurboDownloader.kt; adversarial 3.6-S3 |
| Single-RT probe | ranged GET first (status + Content-Range total + `peekBody` magic sniff, `Accept-Encoding: identity`); HEAD fallback for non-206/200; sniff errors never invalidate a header verdict | TurboDownloader.kt probe(); test_probe_logic.py; adversarial 3.6-S4 |
| Drop-and-replace | per-lane 8 s window / 40 KB-s floor / 10 s warm-up / 4-drop budget / two-strikes latch / 429-cooldown guard / near-done guard; NEVER consumes retry attempts; fresh connection via `Connection: close` + zero-idle pool | TurboDownloader.kt downloadPiece; test_drop_replace.py; adversarial 3.6-S6..S9 |
| Threshold | MIN_SEGMENTED_SIZE 8 MB -> 2 MB | adversarial 3.6-S5 |
| O1 zero-redo | `alreadyCompleteOnDisk` (incl. image branch) skips both engines; pre-flight demoted to the resume path only | DownloadEngine.kt; test_zero_redo.py (B); adversarial 3.6-O1a/b |
| O2 sidecar truth | handoff: stale-smaller-dest guard + always drops a stranded `.turbo`; computeDiskBytes: live `base.aria2` > `.turbo` > legacy `.part.aria2` > `.part` | DownloadEngine.kt; test_zero_redo.py (C); adversarial 3.6-O2a/b |
| O3 sweep | stem-family sidecar sweep (sidecars only, other-task paths protected, one directory listing) | DownloadEngine.kt; test_zero_redo.py (D); adversarial 3.6-O3 |
| O4 / O6 | probe consolidation; image extensions in the built-in list + shipped payload | adversarial 3.6-P4/P5/O4 |
| O5 | two superseded HEAD validation sites retired with rationale (self-heal re-validate; mirror guard) | Section 11 lineage |

### 12.2 Deliberately deferred (with reasons)
- 512 KB buffer number bump — inert (okio returns <=8 KiB per read); the fill-the-buffer loop is
  the real fix and stays a follow-up.
- End-of-piece commit interval change — sidecar-write volume vs resume granularity tradeoff; not
  needed for the speed goal.
- H2/H3 restores — superseded by the single-RT probe + Turbo 401/403/404/410 self-heal.
- StrictLinkClassifier image extensions — provider-harvest regression surface; needs its own pass.
- Nollywood suppression — parked by user decision; the schema fields (`suppressNollywood`,
  `nollywoodSites`, `nollywoodOnlySites`, `nollywoodTerms`) are parsed and validator-accepted so
  the filter can ship without a schema change.

### 12.3 Live-test instrumentation anchors (on-device evidence)
`turbo primary start sockets=N` (existing) · `drop slow socket piece=.. rate=..kB/s drops=N/4`
(new) · `alreadyCompleteOnDisk bytes=..` (new) · `rate limited (cycle …)` (existing) ·
`turbo failed (…), falling back once to aria2c` (existing).

### 12.4 Fresh-lens audit results (independent agent, completed)

Verdict: the batch is substantially faithful to its validation artifacts — the probe decision
table, the drop state machine (incl. the relative guard), the O1/O2/O3 matrices, the OTA
caps/overlay and the encrypt_rules lockstep all match their Python references; the pinned test
expectations survive; no compile blocker was found. One HIGH and two MED findings, all fixed
the same day and locked in by new adversarial checks (3.6-S11..S13, O5, O6):

| Finding | Severity | Disposition |
|---|---|---|
| `dest`+`.turbo` without `.part` -> Turbo sidecar resume would zero-fill the completed prefix, and Tier-0 would certify the corrupt file | HIGH | FIXED: the engine repairs an interrupted handoff (truncate dest to the sidecar's contiguous prefix + drop the stale sidecar) before the O1/gate evaluation; TurboDownloader additionally deletes a stale plan whose `.part` is missing (defense in depth) |
| Probe carried error-response `Content-Length` into `Unreachable(total)` | MED | FIXED: no length carry from error/unusable responses (matches the validated sim) |
| Drop window not cooldown-gated; strike reset broader than the sim | MED | FIXED: window bookkeeping frozen + anchor reset during cooldown; strike reset requires a floor-passing window |
| 206 `Content-Range` start offset not validated in the piece read | LOW | FIXED: a mismatched start is now a retryable failure (silent duplication closed) |
| O3 sweep could not reach legacy two-level generations (`X.part.aria2`) | LOW | FIXED: legacy-stem resolution with an ownership guard on the inner data path — the sim caught that the naive version could delete another task's resume control |
| Stale comments / dead local / wrong test comment | LOW | FIXED (KDoc made truthful, unused local removed, truncated comment finished, threshold comment corrected) |

### 12.5 Live validation results (2026-10-18)

**Local connection baseline** (maintainer link, measured): single stream 8.7 KB/s at 10.8 s TTFB;
2 lanes 5.9 KB/s, 4 lanes 6.0 KB/s aggregate — the LINK is the bottleneck, parallelism cannot
help there. This measurement directly drove the drop-and-replace **relative guard** (a lane is
dropped only when slow both absolutely and vs the best sibling; uniformly-slow links now make
ZERO drops — simulated before porting).

**Server rig** (fast pipe; neutral public endpoints only — the bundle hard-refuses
locker/pirate hosts): single stream **17.0 MB/s**, 4 lanes 11.8, 8 lanes 24.0, **16 lanes
42.2 MB/s aggregate (~2.5x at 16)** — near-linear scaling validates "start wide"; all HTTP 200,
zero errors. Probe semantics verified live on three endpoints: 206+Content-Range -> File(total,
ranges) in one round trip; 200+Content-Length -> File(len, no-ranges); tail-range probes answer
206 (the O1 premise holds on real CDNs). Public-host A/B: 1/4/8 sockets -> 14.2 / 48.8 / 47.7
MB/s, zero rejections/resets — multi-socket is productive and hosts tolerate it on a healthy
pipe. Results: `scratch/server_live_tests/*.json`.

**Nollywood live dry-run** (real site data, OTA switch OFF): 12 requests / 1.55 MiB, all HTTP
200, no anti-bot blocks. R1 (title keyword) VALIDATED: 22/22 Nollywood titles caught on
9jarocks, 0/22 collateral on its Korean cards. R2 (site+taxonomy) is PATH-SENSITIVE: the
client-injected category literal ("Nollywood & Series" on naijavault) causes Korean
false-positives (8/8) on that path, while naijaprey's pipeline path (category "Movies", no
tags) has 0 recall — **build decision: R2 must match TAGS/GENRES only, never the injected
category literal**; the corpus data shows tags are specific (9jarocks tags "Anikulapo" as
"Nollywood Tv Series") while category labels are wholesale. R3 (site-only) would kill ~90-100%
of the sampled home feeds — keep OFF. Full data + named examples:
`scratch/live_nollywood_rules_report.md`; reusable script:
`scratch/live_nollywood_rules_test.py`. (SUPERSEDED by the shipped implementation and its full
live validation — Section 15: R2 shipped GLOBAL over tags/genres since the payload's
`nollywoodSites` list is empty, and additional live runs on nkiri + all 10 sites followed.)

**Scenario matrix (live TCP, scripted mock endpoints)** — `scratch/server_live_tests/scenario_matrix.py`:
12 scenarios over real sockets — 206/200/416 probe decisions, 429-storm with an open HEAD,
token-refresh self-heal, HTML decoy, flaky mid-body drops, HLS manifest, embed-page routing,
image artifacts, and classifier ordering — **12/12 PASSED locally AND on the fast server**.
The headline: the throttled-lane scenario (first connection scripted at ~80 KB/s) proves the
real drop-and-replace payoff over TCP — slow lane **81 KB/s**, fast sibling **226 MB/s**, and
the FRESH connection after the drop **404 MB/s**: a replaced connection is thousands of times
faster than the throttled one, exactly the 1DM behavior, now proven live.

---

## Section 13: 1DM Work-Stealing Batch (2026-10-18, second speed directive)

Python-first, then ported: every logic change below was simulated in `scratch/` and tuned
green BEFORE Kotlin changed — `test_work_steal.py` (new), plus updated `test_drop_replace.py`,
`test_probe_logic.py`, `test_zero_redo.py` (section F).

| # | Fix | Implementation | Deviations & rationale |
|---|---|---|---|
| 1A | Cross-provider failover bypassed on direct downloads | The `producedFile == null` failure branch now calls `attemptCrossProviderFailover(live)` before park/fail (it previously went straight to parking — Turbo-Failure + aria2c-Failure never reached failover) | — |
| 1B | aria2c fed proved-HTML / terminal 401/403/404/410 links | Step 3 gated on `unrecoverableHttpOrHtml` (htmlPage or 401/403/404/410/429); 429 was already excluded | — |
| 2 | Probe discarded 429/4xx codes (single() burned 5 attempts) | `ProbeResult.HttpError(code, message)`; 429 records `HostHealth.recordFail(rateLimited=true)` and fast-fails; 401/403/404/410 fast-fail after ONE HEAD try; 5xx keeps Unreachable (retry ladder intact) | **Deviation (D5):** a 429 whose HEAD answers 200 + Accept-Ranges still returns File — the RateLimitedRangeServer storm-survival unit test depends on it; fast-fail only when HEAD is also unusable |
| 3 | Work-stealing ("hurry up" in-flight splitting) | `claimOrStealNext()` splits the largest active piece's remainder in half when all initial pieces are claimed; `chunk.end` now `@Volatile`; victim clamps writes to the shortened end and cancels its call once `pos > chunk.end` (no draining the stolen tail) | **Deviations (D2a/D2b/D2c):** steal threshold 512 KiB (2 MiB could never fire — pieces are 1 MiB); cap counts SPLITS (≤32), not total pieces (initial plans hold up to 2048); plan is a `CopyOnWriteArrayList` instead of wrapping a dozen call sites in `synchronized(plan)` — appends are atomic and the steal decision itself stays under the lock |
| 4 | Drop detection latency + no severe-lag rule + per-drop pool creation | Windows/warm-up 8s/10s → **4s/4s** (first drop at t=8s, was t=16s); severe-lag rule drops an above-floor lane running below 12% of the best sibling window (when best ≥ 512 KiB/s); one shared `zeroIdlePool` | — |
| 5 | Okio ≤8 KiB reads uncoalesced (one pwrite64 per segment) | `readCoalesced()`: up to 64 KiB per write, stopping early on a short segment read so it never blocks for packets; wired into `downloadPiece()` and `single()` | — |
| 6a | aria2c had no slow-socket handling / write caching | `--stream-piece-selector=inorder`, `--disk-cache=32M`, `--http-no-cache=true` | **Deviation (D3):** `--lowest-speed-limit=25K` deliberately OMITTED — aria2c applies it absolutely, so it would abort every download on a genuinely slow link (the maintainer connection measures 8.7 KB/s) — the exact failure class the Turbo relative-drop guard exists to avoid |
| 6b | `hlsFragments > 0` always true → per-host caps bypassed on the HLS path | `frags = minOf(rawFrags.coerceIn(1,16), hostCap)` in both branches | — |
| 7 | Lockers pre-capped at 8 leave speed on the table | wetafiles / downloadwella / loadedfiles.* → **12** in built-ins AND the shipped payload (kissorgrab/dl.plutomovies stay 6, plutomovies 8) | No live locker data exists (by design, lockers are never tested from the VPS); caps are OTA-tunable, so a wrong guess is a one-field fix. Flagged for on-device measurement |

Verification for this batch: syntax 45/45; adversarial **129/129** (WS-S14..S21 added); simulations
green (work-steal 7/7 incl. tail-makespan 9s→5s and exact-coverage proofs; drop-replace 9/9 with
the severe-lag case dropping at t=8s; probe 21/21; engine gates 11/11); payload validator still
accepts the republished payload.

---

## Section 14: Live Test Protocol (tester on a phone / your end)

**Purpose:** settle the speed questions with REAL numbers on YOUR network, using OTA flips —
no APK rebuild required for any tuning change.

**L0 — One-time setup**
1. Build/install the batch: CI (push/dispatch `build-apk.yml`; artifact
   `Anonrode-Downloader-Serverless-APK`, GitHub Release only on `v*` tags) or a local
   Android Studio build.
2. In the app: enable **Settings -> Debug Logging** (verbose).
3. Pick ONE small test file per host (~10-15 MB) on kissorgrab / plutomovies and keep its URL.

**L1 — Speed A/B via OTA (the core test)**
- Edit `scraper_rules.json`: set `contentPolicy.socketCaps {"kissorgrab.com": 1}` ->
  run `python scripts/publish_rules.py --dry-run` (validate) -> `--write-repo` (update the
  repo `.enc`) and/or `--sync-box` (mirror to the VPS OTA dir). Restart the app so the rules
  re-sync.
- Download the test file; record from the app: average speed + total time, and from the log:
  `turbo primary start sockets=1`.
- Repeat with `6` and `8`. Two runs per config, deleting the downloaded file + `.part`/`.turbo`
  between runs. Budget: 3 configs x 2 runs x ~15 MB ≈ **90 MB** (+ ~45 MB optional second host)
  — inside the 300 MB cap; halve it with a smaller sample file.
- **Expected:** 1-socket crawls (the old 6-30 KB/s class), 6/8 saturate; if the host pushes back,
  the log shows `rate limited (cycle …)` and workers halving, not failures.

**L2 — Drop-and-replace**
- Download the same file at 6-8 sockets; watch for `drop slow socket …kB/s` lines and speed
  recovery after each drop. Expected: at most 2 drops under IP-wide throttling, up to 4 under
  intermittent degradation; never more.

**L3 — Never-redo**
- Pause mid-download -> Resume: bytes continue from the partial (no restart), log shows the
  resume; let it finish -> tap Download again on the same episode: expect an instant complete
  with `alreadyCompleteOnDisk bytes=…` and ~0 data used.

**L4 — Nollywood flag (shipped; flip via OTA only)**
- Set `contentPolicy.suppressNollywood: true` -> publish -> sync -> home/search lose Nollywood
  cards on EVERY site (R1 title + global R2 taxonomy; leave `nollywoodSites` / `nollywoodOnlySites`
  EMPTY for the validated default). Flip back to `false` (or remove the field) -> everything
  returns on the next sync. Live dry-run evidence: Section 15.

**L5 — Evidence collection**
- On-phone: Settings -> Privacy & Logs -> **Share Activity Log** (whole retention window,
  redacted) and grep for the anchors in 12.3.
- Or over the VPS: the app auto-uploads the journal (1 MB trigger / first open of day /
  crash) to the OTA origin; pull with
  `python scripts/read_client_logs.py list|cat` and grep the same anchors.

**L6 — Decision**
- Set production `socketCaps` from the measured numbers (still OTA-only), or drop the section
  entirely to return to compiled defaults. Record the winning numbers in this document.

---

## Section 15: Nollywood Suppression Plane — Final Implementation & Live Validation (2026-10-18)

Hidden, OTA-controlled suppression of Nollywood / Nigerian content across every feed surface.
Default OFF in the shipped payload; flipping one signed flag turns it on fleet-wide with no APK
rebuild. User requirement on record: "most important thing, I don't want false positives" —
precision-first design, live evidence below.

### 15.1 Shipped implementation

New file `app/src/main/java/com/anonrode/downloader/util/NollywoodFilter.kt` (verbatim):

```kotlin
package com.anonrode.downloader.util

import com.anonrode.downloader.data.models.ShowCard
import com.anonrode.downloader.data.rules.DynamicRulesManager

/**
 * Hidden, OTA-controlled Nollywood / Nigerian-content suppression.
 *
 * Rules (live-validated against all supported sites, 2026-10-18 —
 * scratch/live_nollywood_all_sites_report.md):
 *
 *  R1 title     : whole-word term in the TITLE — any site. The workhorse:
 *                 22/22 Nollywood titles caught on 9jarocks, 0 collateral on
 *                 its Korean catalog. Also the only reliable rule for sites
 *                 whose cards carry neither tags nor a meaningful category
 *                 (nkiri titles literally say "Nollywood"/"Yoruba").
 *  R2 taxonomy  : term match in TAGS or GENRES only — the client-injected
 *                 `category` literal is NEVER matched (naijavault stamps
 *                 "Nollywood & Series" on every search card; matching it
 *                 suppressed 8/8 Korean results in the live dry-run while its
 *                 real taxonomy tags stayed specific). Scoped to
 *                 [DynamicRulesManager.ContentPolicy.nollywoodSites] when that
 *                 list is NON-empty; global when empty (default: global —
 *                 tags proved specific on every site tested).
 *  R3 site-only : every card from nollywoodOnlySites. Brand-level kill switch,
 *                 default EMPTY (live data: it would kill 90-100% of the Naija
 *                 home feeds, which carry plenty of non-Nollywood content).
 *
 * The switch is `contentPolicy.suppressNollywood` in the signed OTA payload:
 * absent/false => every method here is a pure pass-through (zero cost, zero
 * risk for the fleet). There is deliberately NO Settings UI — the payload
 * decides, and flipping it off restores everything on the next sync.
 * Downloads, playback, history and pasted links are never filtered.
 */
object NollywoodFilter {

    /** Compiled floor; the payload's nollywoodTerms overrides when non-empty. */
    private val DEFAULT_TERMS = listOf("nollywood", "yoruba", "naija", "igbo", "hausa")

    private fun termRegex(terms: List<String>): Regex =
        Regex(
            """\b(?:""" + terms.joinToString("|") { Regex.escape(it) } + """)\b""",
            RegexOption.IGNORE_CASE
        )

    private fun matchesTerm(text: String?, re: Regex): Boolean =
        !text.isNullOrEmpty() && re.containsMatchIn(text)

    private fun activeTerms(): List<String> {
        val policy = DynamicRulesManager.getContentPolicy()
        return policy.nollywoodTerms.ifEmpty { DEFAULT_TERMS }
    }

    /** Title-only predicate for card-less surfaces (e.g. genre tiles whose
     *  poster pick only has a title string). */
    fun isNollywoodTitle(title: String): Boolean {
        val policy = DynamicRulesManager.getContentPolicy()
        if (!policy.suppressNollywood) return false
        return matchesTerm(title, termRegex(policy.nollywoodTerms.ifEmpty { DEFAULT_TERMS }))
    }

    fun isNollywood(card: ShowCard): Boolean {
        val policy = DynamicRulesManager.getContentPolicy()
        if (!policy.suppressNollywood) return false
        val site = card.site.lowercase()

        // R3: brand-level kill list (default empty).
        if (policy.nollywoodOnlySites.any { it.equals(site, ignoreCase = true) }) return true

        val re = termRegex(policy.nollywoodTerms.ifEmpty { DEFAULT_TERMS })

        // R1: the title says it — any site.
        if (matchesTerm(card.title, re)) return true

        // R2: taxonomy tags/genres only (never the injected category literal).
        // Scoped to nollywoodSites when that list is non-empty; global when empty.
        if (policy.nollywoodSites.isNotEmpty() &&
            policy.nollywoodSites.none { it.equals(site, ignoreCase = true) }
        ) {
            return false
        }
        if (card.tags.any { matchesTerm(it, re) }) return true
        if (card.genres.any { matchesTerm(it, re) }) return true
        return false
    }

    /** The single entry point used by every feed / search choke point. */
    fun filterSafe(cards: List<ShowCard>): List<ShowCard> {
        if (!DynamicRulesManager.getContentPolicy().suppressNollywood) return cards
        return cards.filterNot { isNollywood(it) }
    }
}
```

Rules (final, live-validated):

| Rule | Matches on | Scope | Why it is shaped this way |
|---|---|---|---|
| R1 title | whole-word `nollywood\|yoruba\|naija\|igbo\|hausa` in `card.title` | every site | The workhorse. nkiri/pluto/9jarocks put the marker in the title ("... \| Download Nollywood Movie"), where it is unambiguous |
| R2 taxonomy | same terms in `card.tags` / `card.genres` ONLY | GLOBAL (payload `nollywoodSites` empty; scoping activates only when that list is non-empty) | tags/genres are the site's REAL `wp:term`/RSS-category names — NEVER the client-injected `category` literal ("Nollywood & Series" / "Nollywood & Movies" stamped on every card by NaijaVault/NaijaPrey/Rocks providers and UrlRouter; matching it falsely suppressed 8/8 Korean cards in the first live dry-run) |
| R3 site-only | every card from `nollywoodOnlySites` | default EMPTY (OFF) | brand kill-switch; live data showed it would kill 90-100% of the Naija home feeds, which carry plenty of non-Nollywood content |

Switch: `DynamicRulesManager.ContentPolicy.suppressNollywood` (absent/false => every method is a
pure pass-through, zero cost for the fleet). `nollywoodTerms` empty => compiled floor is used.
Deliberately NOT filtered: episodes, playback, history, pasted links.

Threading — every feed/search choke point (verified call sites):

| Surface | File:line | Hook |
|---|---|---|
| Trending merge | providers/TrendingFeed.kt:124 | `mergeRoundRobin` isSafe gate |
| Category/genre rows | providers/CategoryFeed.kt:327 | `mixCards` gate |
| Asian drama | providers/AsianDramaFeed.kt:38 | cached-branch `filterSafe` |
| Home cache hydration | viewmodel/MainViewModel.kt:273 and :280 | cached trending + cached categories |
| Search | providers/ProviderRegistry.kt:180 | `searchFlow(...) .map { filterSafe(it) }` |

OTA plane (all pre-existing machinery): `ContentPolicy(socketCaps, suppressNollywood,
nollywoodOnlySites, nollywoodSites, nollywoodTerms)` — `DynamicRulesManager.kt:50-56` (data class),
`:246` (volatile snapshot), `:256-270` (parse), `:561` (getter); `RulesFlags.kt:91` keeps
`contentPolicy` in the flag family; `scripts/encrypt_rules.py` validates it in lockstep
(`:162` top-level allowlist, `:959` schema, `:981-1016` contentPolicy checks: host->int 1..16 caps,
bool switch, <=64/128-entry string lists, unknown-field reject). Shipped payload rev 2 carries
`contentPolicy` with `socketCaps` ONLY — `suppressNollywood` is ABSENT (OFF) until deliberately
published.

Verification gates: Kotlin syntax gate 45/45 clean; `scratch/test_nollywood_filter.py` 18/18
(precision fixtures: 9jarocks Korean with wholesale "Nollywood & Movies" label stays, naijavault
Korean with injected "Nollywood & Series" stays, Hollywood never substring-matched, Yorubaland
boundary holds; recall fixtures: nkiri title, "[Nollywood]" bracket title, uppercase YORUBA,
global taxonomy tag); `scratch/adversarial_qa_audit.py` 134/134 incl. NN-1..NN-5.

### 15.2 Live validation — nkiri (the hard case: both catalogues on one site)

Full report: `scratch/live_nkiri_filter_report.md`. Headline evidence:

- PRECISION: **104/104 non-Nigerian cards kept, 0 collateral** — 45 hollywood/korean/avengers
  control cards, 30 of nkiri's own `-download-hollywood-movie` sitemap slugs (Once Upon A Time in
  Hollywood, Star Wars IX, Jumanji...), and 29 Korean/Hollywood cards whose BODIES contain nkiri's
  site-wide promo ("Download Nollywood movies free.", "download roti nollywood movie") — a
  body-matching filter would have suppressed ~100% of the site; the title+taxonomy design kept
  every one.
- RECALL: 100% of nkiri marker-carrying cards caught (live: "Mother Of George | Download
  Hollywood & Nollywood Movie" -> R1). Taxonomy census PROVES R2 silence on nkiri is data-driven:
  11 categories total (International / TV Series / K-Drama / Asian Movies / Korean Movies /
  Bollywood / Chinese Movie / Philippine Movies / K-Variety / Chinese Dramas / Info), the two
  "nollywood" TAGS exist with count=0, sitemap census of 6,360 URLs = 2,919 hollywood slugs vs
  2 stale nw-slugs (whose current titles are South African films — correctly kept).
- HONEST LEAK (documented): Iyanu S01/S02 (Nigerian animated series) carries the marker only in
  the post description -> it would be shown. Mitigation is OTA-only: append a term (e.g. `iyanu`)
  to `contentPolicy.nollywoodTerms` in the signed payload — no APK rebuild.

### 15.3 Live validation — all content sites (final global rules)

Harness: `scratch/live_nollywood_all_sites_test.py` (10 content sites; per site one "nollywood"
capture + one control capture; enforced budgets; local machine only). Final-run log:
`scratch/live_nollywood_all_sites_final_run.txt` (733 lines incl. the retry pass); report:
`scratch/live_nollywood_all_sites_report.md`. Independent full re-run (2026-10-18, after the nkiri
campaign); the retry pass recovered every failed capture (9jarocks SSL handshake,
naijaprey/pluto/asianc timeouts, nkiri cap-truncation — all repaired to full captures).

| Site | "nollywood" capture | Control capture | Notes |
|---|---|---|---|
| 9jarocks | 22 cards / 22 hits (R1+R2) | 22 Korean cards / 0 hits | RSS `<category>` taxonomy: global R2 fired 22/22 where the superseded scoped variant fired 0; the injected "Nollywood & Movies" literal is never matched |
| nepu | 11 cards / 11 hits (R1) | 0 (`[]`) | TMDB proxy; hits = documentary titles ("Nollywood Babylon", "Welcome to Nollywood") |
| naijavault | 8 cards / 8 hits (R1 3 + R2 8) | 8 Korean cards / 0 hits | R2 catches the 5 title-less ones; injected "Nollywood & Series" literal never matched |
| naijaprey | 5 cards / 5 hits (R1 4 + R2 5) | 5 Korean cards / 0 hits | R2 catches "Dark October"; relaxed retry after an SSL timeout, 0 collateral |
| pluto | 2 cards / 2 hits (R1) | 34 Korean cards / 0 hits | "Ijogbon (2023) (Yoruba Nollywood)", "Mentally (Nollywood Movie)" |
| nkiri | covered in 15.2 | 5 kept / 0 hits | nw + ctl re-captured full-taxonomy (cap repaired) |
| dramakey | 24 cards / 0 hits | 0 | `?s=` fallback capture; site is Asian-drama-only |
| anitaku / asianc / dramarain | 0 Nollywood results (expected: anime/Asian-drama catalogues) | 0 hits | dramarain literally answers "nothing found"; asianc zero-Nollywood is low-confidence (its supplementary probe hit RemoteDisconnected) |

**Totals: 48/48 Nollywood-marked cards caught, 0 missed; 74/74 real control cards kept, 0
collateral. Zero blocks.** Budget for this run: 28 requests / 2.74 MiB. Endpoint traps documented
for future runs: pluto `?s=` and asianc `/search?keyword=` are query-insensitive (byte-identical
nw/ctl pages).

### 15.4 Tunables (all OTA, no rebuild)

- `nollywoodTerms` — add false-negative terms (e.g. specific Nigerian titles) as live data surfaces them.
- `nollywoodSites` — if a site ever shows collateral, scope R2 to a site list (R2 scopes only when the list is non-empty).
- `nollywoodOnlySites` — brand-level kill switch (kept empty; too blunt for multi-catalogue sites).
- `suppressNollywood` — master switch; false/absent restores everything on the next sync.


---

## Section 16: v3.3.0 Platform Surfaces — Announcements, Update Flow, Force Gate, Engagement, Ads (2026-10-18)

Same cycle as Section 15. The signed OTA payload gains six sections; the app gains the matching
runtime; every surface is OFF when its section is absent (pure pass-through), percent-rollable via
the `flags` families, and kill-switchable by republishing the previous payload rev.

**Payload sections (parsed by `data/rules/DynamicRulesManager.kt`; validated in lockstep by
`scripts/encrypt_rules.py`):**

| Section | Getter | Shape |
|---|---|---|
| `announcements` | `getAnnouncements()` | array of `{id,title,body,mode(dialog|sheet|banner),action(none|url|update),actionLabel,actionUrl,oncePer(id|rev),minApp,expiresAtMs}` |
| `update` | `getUpdatePolicy()` | `{latestVersionName,latestVersionCode,sources[{kind,base}],apkArm64,apkUniversal,sha256Arm64,sha256Universal,message,force{enabled,minSupportedVersionCode,mode,snoozeHours,escapeHatch}}` |
| `ads` | `getAdsConfig()` | `{enabled,provider(house|none|applovin|unity|admob|auto),placements[],frequencyPerDay,house{title,body,actionLabel,actionUrl,id},vendor credentials}` |
| `engagement` | `getEngagementConfig()` | `{enabled,inactiveDays,cooldownDays,title,body,welcomeBack}` |
| `tamper` | `getTamperPolicy()` | `{expectedCertSha256,reportOnly}` |
| `fcm` | `getFcmConfig()` | `{enabled,apiKey,appId,senderId,projectId}` |

Flags families added in lockstep (`RulesFlags.FAMILY_IDS` == `encrypt_rules.py`): `announcements`,
`update`, `ads`, `engagement` — the same kill/percent/shadow ladder as every existing family.

**Runtime modules (new):**

| File | Role |
|---|---|
| `update/UpdateManager.kt` | State machine (UNKNOWN/UP_TO_DATE/AVAILABLE/FORCED) with the brick rails (staleness >14d, failure, floor==0 never FORCED); source-walk download (VPS → GitHub per payload order) with a SHA-256 pin verified on EVERY attempt (blank pin fails closed); PackageInstaller session install with ACTION_VIEW/FileProvider fallback; `update_flow` stage telemetry |
| `update/AnnouncementManager.kt` | First-eligible pick (minApp/expiry/oncePer id|rev), shown-marker persisted at pick time, id-only telemetry |
| `update/EngagementScheduler.kt` (+ `service/BootReceiver.kt`) | One-shot AlarmManager (boot re-arm), LOW channel, cooldown dedupe, payload copy verbatim, welcome-back pending flag for the in-app card |
| `update/FcmRegistrar.kt` | Manual `FirebaseOptions` (no google-services.json — OTA-portable credentials), hard no-op while disabled |
| `update/IntegrityAttestor.kt` | Play-Integrity client, hard-gated off (payload flag pending); class-only logs |
| `ads/Ads.kt` | `AdBridge` interface + House/AppLovin/Unity/AdMob bridges — all inert unless enabled+provider+credential; AdMob meta-data = Google TEST app id with the registration comment |
| `ui/components/AnnouncementHost.kt` | Root host rendering the three modes + action dispatch |
| `ui/components/UpdateGate.kt` | Soft card (snooze) + hard dialog (no back/outside dismiss; Update-now → download→verify→install; release-page fallback; 5×-long-press 24h escape hatch with `gate_bypass` telemetry); blocks UI only |
| `ui/components/AdSlot.kt` | House-only renderer with per-day frequency cap + `ad_impression` telemetry |
| `util/IntegrityCheck.kt` | Signing-cert SHA-256 (API 26-27 legacy fallback + API 28+ signing-info union) vs the OTA pin; Verdict{NONE,OK,MISMATCH,UNKNOWN}; never blocks; one id-only report per session |
| `scripts/publish_apk.py` | APK + `latest.json` mirror to the VPS (`ota_files/apk/` + root `ota_files/latest.json` = the exact `/ota/latest.json` URL the app reads), dry-run default, on-box sha256 verification |

**Integration points:** `AnonApp.kt` (post-activation hooks for all managers + cold start + >6h
resume-refresh via activity-start counter), `MainActivity.kt` root (AnnouncementHost + UpdateGate),
`DownloadsScreen.kt` (`AdSlot("downloads_shelf")` under the shelf), `HomeScreen.kt`
(`WelcomeBackCard` + `AdSlot("home_footer")`), `SettingsSheet.kt` About (version + Check-for-updates
+ App-signature diagnostics), `AndroidManifest.xml` (REQUEST_INSTALL_PACKAGES, RECEIVE_BOOT_COMPLETED
+ BootReceiver, AdMob test-id meta-data).

**Verification:** Python reference sims (written BEFORE/ALONGSIDE the Kotlin): update policy 25/25
(rails + source walk + pin), announcements 18/18, ads frequency 15/15, engagement math 18/18; Kotlin
tests `PlatformSectionsParseTest` (8), `RulesFlagsTest` (3) via CI; validator dry-runs PASS on both
the shipped and example payloads; syntax gate covers all files.

---

## Section 17: v3.3.0 Hardening & Cleanup Batch (2026-10-18)

**Anti-decompile / anti-mod (honest layering):**
- R8 + resource shrinking ON for release only (`app/build.gradle.kts`), `-repackageclasses 'anon'` +
  `-allowaccessmodification`; `proguard-rules.pro` rewritten against the actual dependency set with
  per-rule justification (OkHttp/coroutines/media3 consumer rules verified; Coil ships NONE — kept
  wholesale deliberately; youtubedl-android kept wholesale; kotlinx.serialization 1.7.2 bundled rules).
- IP shield: site URLs/selectors live in the ENCRYPTED OTA payload, not the APK; rules are ECDSA-signed
  and rev-monotonic.
- `IntegrityCheck` reports repackaged installs (telemetry + Settings row) — a patched app can lie;
  the value is visibility. Play Integrity rejected (GMS/Play assumptions) — documented.
- CI signing guard already refuses debug-signed releases. Debug/release coexist via `.debug` suffix.

**Audit-fix closures in this batch:** F-01 debug-logging wired (setVerbose from settings),
F-02 NoSourceLedger records, F-03 urlTemplates consumed by Nepu, F-04 reserved-field INFO note,
F-05 flags plane armed at publish, F-06 dead file deleted, F-07 retired interface member removed,
F-08 AppSettings consolidation (helper + deprecation; call site swapped), F-09 registry forget on
every terminal FAILED path, F-12 queue-order truth (comment fixed, behavior pinned by
QueueOrderTest), F-13 gate covers ALL 188 Kotlin files. Log-audit still-reachables: UpdateChecker
port-80, vidsrcme compiled seed + `legacyApiUsable` gate, HLS stub terminal floor
(`HLS_STUB_MIN_BYTES=1024` at LinkResolver/RulesPipeline/ResultVerifier + `isFinal >=
MIN_PRODUCED_BYTES` in YoutubeDlDownloader; `HlsStubTerminalTest` added). Wiring P2s: dead share
handler / ViewModel methods / `cardStatusDetail` deleted, one "active" predicate unified across UI
surfaces (service filter deliberately distinct), parallel-sockets control added, dead resources
removed with zero-ref proofs, `AppSettings` player call swapped.

**Tests added this cycle:** `PlatformSectionsParseTest`, `RulesFlagsTest`, `QueueOrderTest`,
`HlsStubTerminalTest` (CI-executed); sims as listed in Section 16; `adversarial_qa_audit.py` extended
(NN-6+: families lockstep, getters, validator keys, gate coverage) with the isFinal check STRENGTHENED
to the new `>= MIN_PRODUCED_BYTES` invariant (the stale `> 0L` string match was the batch's one red
gate and was corrected with the reason recorded).

**CI-watch list (first release run):** vendor SDK call signatures (AppLovin settings ctor, Unity
listener, integrity builders); dependency versions pinned to the last compileSdk-34-compatible lines
(a metadata bump forces compileSdk 35 + AGP review); R8 runtime behavior on device (Coil kept
wholesale to remove discovery risk); `-dontwarn kotlin.reflect.**` speculation; Unity keep rule
removable after a green run.

---
