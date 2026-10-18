An in-depth audit of post_refactor_engine_audit-3.md and the companion speed notes uncovered 7 critical speed, concurrency, and failover defects—including a silent regression where attemptCrossProviderFailover is bypassed on direct downloads, aria2c is fed HTML decoy pages after TurboDownloader already proved the link is an HTML page, TurboDownloader.probe() fires 7 requests into HTTP 429 rate-limited servers, Okio's <= 8 KiB read segments remained uncoalesced, and fast sockets exit at the tail of a download instead of splitting/stealing remaining megabytes from slow sockets.
Copy the single block below and send it directly to your agent:
# MASTER HANDOVER DIRECTIVE: 1DM Work-Stealing ("Hurry Up" Piece Splitting), Dual-Engine Slow-Socket Drop & Deep Audit Fixes

An in-depth, line-by-line audit of `post_refactor_engine_audit-3.md`, `1DM_SPEED_DIRECTIVE_REFERENCE.md`, and `1DM_SPEED_NOTE_REFERENCE.md` identified **7 critical bugs, regressions, and speed bottlenecks** across `DownloadEngine.kt`, `TurboDownloader.kt`, `TurboState.kt`, `YoutubeDlDownloader.kt`, and `HostPolicyRegistry.kt`.

Using your **Python-First Simulation Workflow** (`scratch/test_*.py`) followed by your **4-Agent Verification & Adversarial QA Suite** (`scratch/adversarial_qa_audit.py`), implement all 7 fixes below completely and regenerate `post_refactor_engine_audit.md`.

---

## FIX 1: Restore `attemptCrossProviderFailover` on Direct Downloads & Stop Feeding HTML/Expired Links to `aria2c` (`DownloadEngine.kt`)

### Root Cause A — Cross-Provider Failover Was Accidentally Bypassed:
In `DownloadEngine.startTask()`, `StreamValidator.validateResult` is now skipped on fresh direct downloads (`hasExistingAria2Partial == false`). When a source link is dead:
1. `TurboDownloader.download(...)` returns `TurboResult.Failure` without throwing.
2. Step 3 runs `YoutubeDlDownloader.download(..., backend = "aria2c")` inside `try { ... } catch (e: Exception) { aria2Error = e.message }`, which swallows the exception into `aria2Error`.
3. Execution enters the `else` branch at `if (producedFile != null ...)` with `producedFile == null`. Unlike the outer `catch (e: Exception)` block, **that `else` branch never calls `attemptCrossProviderFailover(live)`**! It goes straight to `parkInsteadOfFail` or marks the task `TaskStatus.FAILED`, completely breaking cross-provider failover for direct downloads!

### Root Cause B — Step 3 Feeds Proved HTML Pages & Dead 401/403/404/410 Links to `aria2c`:
In Step 3 of `startTask()`:
`if (producedFile == null && turboFailure?.httpStatus != 429 && coroutineContext.isActive)`
When `turboFailure?.htmlPage == true` (Turbo proved the URL returns an HTML web page instead of a media file, even after Step 2's token refresh) OR `turboFailure?.httpStatus in listOf(401, 403, 404, 410)` (the file is missing or forbidden even after token refresh), Step 3 still launches `aria2c`! `aria2c` does not sniff HTML magic bytes, so it either downloads the HTML error page as `Episode.mp4` or wastes 15 retries hammering a 404/403 URL.

### Exact Implementation in `DownloadEngine.kt`:
1. Gate Step 3 (`aria2c` fallback) so it never runs when `TurboDownloader` proved the link is an HTML page or a terminal 401/403/404/410 error:
```kotlin
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
    ...
}

 * In the else failure branch of startTask() (right before val parked = producedFile == null && parkInsteadOfFail(...)), restore the cross-provider failover call:
val live = repository.find(task.id) ?: task
if (producedFile == null && live.downloadedBytes == 0L && bytesLanded(live) == 0L && attemptCrossProviderFailover(live)) {
    return@launch
}
val parked = producedFile == null &&
    parkInsteadOfFail(live, errReason, forcePark = isRateLimitedError(errReason))

FIX 2: Fast-Fail HTTP 429 & Auth Errors (401/403/404/410) in TurboDownloader.probe() (TurboDownloader.kt)
Root Cause:
Because StreamValidator is skipped on fresh downloads, TurboDownloader.probe() is the first request to contact the server. Currently, when the server responds with HTTP 429 or 401/403/404/410:
 * probe() sees r.code is neither 206 nor 200 and discards r.code.
 * probe() fires headFallback(-1L) (a 2nd request into the rate-limiter) and returns ProbeResult.Unreachable(-1L).
 * TurboDownloader.download() treats Unreachable(-1L) as "server doesn't support Range" and launches single(), which fires 5 more requests (or enters a 15s+ cooldown) before DownloadEngine ever learns the token was 403/404 or the host was 429! Worse, HostHealth.recordFail(url, rateLimited = true) is never called on a probe 429.
Exact Implementation in TurboDownloader.kt:
 * Add HttpError to ProbeResult:
internal sealed class ProbeResult {
    data class File(val total: Long, val acceptsRanges: Boolean) : ProbeResult()
    object HtmlPage : ProbeResult()
    data class HttpError(val code: Int, val message: String) : ProbeResult()
    data class Unreachable(val total: Long) : ProbeResult()
}

 * In probe():
   * If r.code == 429: call com.anonrode.downloader.pipeline.HostHealth.recordFail(url, rateLimited = true, reason = "HTTP 429 in Turbo probe") and immediately return ProbeResult.HttpError(429, "Server rate limited request (HTTP 429)") without calling headFallback!
   * If r.code in listOf(401, 403, 404, 410): call val hb = headFallback(-1L) once (in case a server only rejects Range headers with 403 but answers 200 on HEAD). If hb is ProbeResult.File, return hb; otherwise return ProbeResult.HttpError(r.code, "Server rejected link (HTTP ${r.code})").
   * Note: Keep 500/502/503 returning ProbeResult.Unreachable(-1L) so TurboDownloaderTest.givesUpAfterMaxAttempts still runs the full 5-attempt retry ladder on 5xx errors.
 * In TurboDownloader.download() inside when (probe):
is ProbeResult.HttpError -> {
    return@withContext TurboResult.Failure(
        httpStatus = probe.code,
        message = probe.message,
        htmlPage = false
    )
}

FIX 3: 1DM "Hurry Up" Dynamic In-Flight Piece Splitting (Work-Stealing) in TurboDownloader.kt & TurboState.kt
Root Cause:
Currently, TurboState.loadOrCreate(total, sockets) slices the file into fixed pieces at startup. Once nextPiece >= plan.size, fast sockets that finish their piece call claimNext(), get null, and exit (break), shutting themselves down! If 1 or 2 sockets are on slower routes (e.g. 45 KB/s) with 15 MB left, the user watches the last 10% of the download crawl on 1–2 sockets while 14 fast sockets sit completely idle!
How 1DM Solves This (Dynamic Work-Stealing):
When a worker finishes its piece and no unclaimed initial pieces remain in plan, it finds the active piece with the largest remaining byte range (remaining >= 2 MB), splits that piece's remaining range in half atomically, shortens the slow worker's end offset, and steals the second half on its own fast socket!
Exact Race-Free Implementation:
 * Update TurboChunk (TurboDownloader.kt):
   Make end volatile so in-flight shortening is immediately visible across threads:
   class TurboChunk(val start: Long, @Volatile var end: Long, @Volatile var current: Long)

 * Thread-Safe Plan Access (TurboState.kt & TurboDownloader.kt):
   * In TurboDownloader.segmented(), wrap all reads/mutations of the plan list (committed(),  state.commit(...), and claimOrStealNext()) in synchronized(plan) (or take a snapshot under synchronized(plan) before persisting to disk in TurboState.commit).
   * Ensure plan in segmented() is a MutableList<TurboChunk> (val plan = state.loadOrCreate(total, sockets).toMutableList()).
 * Replace claimNext() with claimOrStealNext() in TurboDownloader.segmented():
val minStealRemainingBytes = 2L * 1024 * 1024 // Only split pieces with >= 2 MiB remaining
val maxTotalPieces = 64                       // Cap total dynamic splits to keep sidecar compact

fun claimOrStealNext(): TurboChunk? {
    synchronized(plan) {
        while (nextPiece.get() < plan.size) {
            val idx = nextPiece.getAndIncrement()
            if (idx < plan.size && plan[idx].current <= plan[idx].end) {
                return plan[idx]
            }
        }
        // All initial pieces have been claimed: steal the back half of the largest active piece!
        if (plan.size >= maxTotalPieces) return null
        val victim = plan
            .filter { it.current < it.end }
            .maxByOrNull { it.end - it.current }
            ?: return null
        val remaining = victim.end - victim.current + 1L
        if (remaining < minStealRemainingBytes) return null

        val splitOffset = victim.current + (remaining / 2L)
        val oldEnd = victim.end
        if (splitOffset <= victim.current || splitOffset >= oldEnd) return null

        // Shorten victim to [victim.current .. splitOffset - 1]
        // and assign [splitOffset .. oldEnd] to the stealing worker
        victim.end = splitOffset - 1L
        val stolen = TurboChunk(start = splitOffset, end = oldEnd, current = splitOffset)
        plan.add(stolen)
        nextPiece.set(plan.size)
        com.anonrode.downloader.util.DebugLog.backend(
            "task=$taskId work-steal split piece=${victim.start}-${oldEnd} -> victimEnds=${victim.end} stolen=${stolen.start}-${stolen.end}"
        )
        return stolen
    }
}

 * Zero-Overlap Clamping & Immediate Socket Close in downloadPiece(chunk: TurboChunk):
   Because a work-stealing sibling can lower chunk.end while the victim is mid-read, the victim must clamp its write to chunk.end and cancel its HTTP call once pos > chunk.end (so OkHttp's Response.close() does not stall trying to drain the un-stolen tail of the HTTP stream):
val requestedRangeEnd = chunk.end
val req = Request.Builder().url(url).apply {
    header("User-Agent", headers["User-Agent"] ?: HttpClient.DEFAULT_UA)
    headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
    header("Range", "bytes=${chunk.current}-$requestedRangeEnd")
    if (drops > 0) header("Connection", "close")
}.build()
...
while (pos <= chunk.end && !failed.get()) {
    if (!coroutineContext.isActive) {
        if (canCommit(taskId)) state.commit(plan, total, force = true)
        return@use
    }
    val liveEnd = chunk.end
    val remainingInChunk = liveEnd - pos + 1L
    if (remainingInChunk <= 0L) break
    val want = minOf(buf.size.toLong(), remainingInChunk).toInt()
    val n = readCoalesced(src, buf, want)
    if (n <= 0) break

    // Re-clamp in case a work-stealer shortened chunk.end while readCoalesced was in flight
    val allowedBytes = (chunk.end - pos + 1L).coerceAtLeast(0L).toInt()
    val writeLen = minOf(n, allowedBytes)
    if (writeLen > 0) {
        val bb = ByteBuffer.wrap(buf, 0, writeLen)
        var w = 0
        while (bb.hasRemaining()) {
            val k = channel.write(bb, pos + w)
            if (k <= 0) throw java.io.IOException("short positional write at ${pos + w}")
            w += k
        }
        pos += writeLen
        chunk.current = pos
        done.addAndGet(writeLen.toLong())
    }
    if (pos > chunk.end) {
        // If a stealer shortened our chunk.end below what we originally requested in the
        // HTTP Range header, cancel the call so OkHttp closes the socket immediately
        // instead of draining megabytes of stolen tail on res.close()!
        if (chunk.end < requestedRangeEnd) {
            try { call.cancel() } catch (_: Exception) {}
        }
        break
    }
    ...
}

 * Update the worker loop to call val chunk = claimOrStealNext() ?: break and if (!downloadPiece(chunk)) break, and update the final completion check at the bottom of segmented() to synchronize on plan:
   if (synchronized(plan) { plan.any { it.current <= it.end } }) return@coroutineScope false
FIX 4: Cut Slow-Socket Drop Latency in Half (8s Instead of 16s), Add Severe Relative-Lag Drop & Fix ConnectionPool Leak (TurboDownloader.kt)
Root Cause:
 * In TurboDownloader.kt, DROP_WINDOW_MS = 8_000L and DROP_WARMUP_MS = 10_000L. At t = 8,000ms, the first window check fires, evaluates val eligible = nowMs - connStartedAt > DROP_WARMUP_MS (8000 > 10000 -> false!), and resets dropWindowStart = nowMs. Thus, no slow socket can ever be dropped until the second window at t = 16,000ms!
 * If 15 sockets are running at 1.5 MB/s (bestWindowBps = 1,500,000) and 1 socket is stuck on a throttled CDN edge at 55 KB/s, bps < DROP_FLOOR_BPS (55 KB/s < 40 KB/s) is false, so the 55 KB/s socket is never dropped!
 * freshConnectionClient(base: OkHttpClient) creates a brand-new okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS) object on every single drop instead of sharing a single zero-idle pool instance.
Exact Implementation in TurboDownloader.kt:
 * Update the drop constants:
private const val DROP_WINDOW_MS = 4_000L
private const val DROP_WARMUP_MS = 4_000L       // Window 1 (0..4s) is warmup; Window 2 (4..8s) drops at t=8s!
private const val DROP_FLOOR_BPS = 40L * 1024   // 40 KiB/s absolute floor
private const val DROP_RELATIVE_FRACTION = 0.30
private const val SEVERE_LAG_MIN_BEST_BPS = 512L * 1024 // When fastest sibling >= 512 KiB/s
private const val SEVERE_LAG_FRACTION = 0.12            // Drop lane if < 12% of fastest sibling

 * Reuse a single zeroIdlePool instance at object level:
private val zeroIdlePool = okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS)

private fun freshConnectionClient(base: OkHttpClient): OkHttpClient =
    base.newBuilder().connectionPool(zeroIdlePool).build()

 * In the window check inside downloadPiece, evaluate both absolute-floor lag and severe-relative lag:
val bestBps = bestWindowBps.get()
val isSlowAbsolute = bps < DROP_FLOOR_BPS && bps < bestBps * DROP_RELATIVE_FRACTION
val isSeverelyLagging = bestBps >= SEVERE_LAG_MIN_BEST_BPS && bps < bestBps * SEVERE_LAG_FRACTION
if (isSlowAbsolute || isSeverelyLagging) {
    val eligible = nowMs - connStartedAt > DROP_WARMUP_MS
    ...
} else if (lastConnWasPostDrop && bps >= DROP_FLOOR_BPS && (!isSeverelyLagging)) {
    slowAfterDrop = 0
}

FIX 5: Implement Non-Blocking 64 KiB Read Coalescing in TurboDownloader.kt
Root Cause:
As documented in 1DM_SPEED_DIRECTIVE_REFERENCE.md, Okio's BufferedSource.read(buf, 0, want) returns at most 8,192 bytes (one internal 8 KiB Okio segment) per call, even when buf is 256 KB! That causes a kernel pwrite64 (channel.write) syscall and atomic counter update every 8 KiB (131,072 syscalls per GB).
Exact Implementation in TurboDownloader.kt:
Add this helper to TurboDownloader and use it in both downloadPiece() and single():
private const val COALESCE_TARGET_BYTES = 64 * 1024 // Coalesce up to 64 KiB per pwrite64 syscall
private const val OKIO_SEGMENT_SIZE = 8192

private fun readCoalesced(
    src: okio.BufferedSource,
    buf: ByteArray,
    maxWant: Int
): Int {
    val target = minOf(maxWant, COALESCE_TARGET_BYTES)
    var totalRead = 0
    while (totalRead < target) {
        val r = src.read(buf, totalRead, maxWant - totalRead)
        if (r == -1) break
        totalRead += r
        // If Okio returned less than a full 8 KiB segment, the socket buffer is currently
        // drained — return immediately so we never block waiting for network packets when
        // we already have bytes ready to write to disk!
        if (r < OKIO_SEGMENT_SIZE) break
    }
    return if (totalRead == 0) -1 else totalRead
}

FIX 6: Extend Slow-Socket Drop & Write Caching to aria2c + Fix hlsFragments Override (YoutubeDlDownloader.kt)
Root Cause:
 * When a download runs on or falls back to downloadHttpAria2c in YoutubeDlDownloader.kt, aria2c has no --lowest-speed-limit flag, so aria2c holds onto 1 KB/s throttled sockets forever instead of dropping and replacing them! It also lacks --stream-piece-selector=inorder and --disk-cache.
 * In YoutubeDlDownloader.download(), lines 1995 and 2027 compute:
   val frags = if (hlsFragments > 0) hlsFragments.coerceIn(1, 16) else parallelSockets.coerceIn(1, 16)
   Because DownloadEngine always passes hlsFragments = hlsFragmentConcurrency (default 16), hlsFragments > 0 is ALWAYS true, which completely ignores any per-host socket cap applied to parallelSockets!
Exact Implementation in YoutubeDlDownloader.kt:
 * In downloadHttpAria2c, add these 1DM-parity flags to cmd:
"--lowest-speed-limit=25K",          // Drop & replace any socket crawling below 25 KiB/s
"--stream-piece-selector=inorder",   // Front-to-back piece selection for clean prefix resume
"--disk-cache=32M",                  // 32 MiB in-memory write cache to reduce flash I/O
"--http-no-cache=true"               // Bypass stale throttling proxy caches

 * In YoutubeDlDownloader.download() (both isExtractorTask and isM3u8 branches), respect host caps on parallelSockets while still allowing hlsFragments tuning:
val hostCap = HostPolicyRegistry.effectiveSocketsForUrl(sourceUrl, 16)
val rawFrags = if (hlsFragments > 0) hlsFragments else parallelSockets
val frags = minOf(rawFrags.coerceIn(1, 16), hostCap)

FIX 7: Unlock 12-Socket Initial Burst on Primary Lockers in HostPolicyRegistry.kt & scraper_rules.json
Why:
Now that TurboDownloader has:
 * Shared escalating 429 rate-limit cooldowns (enterRateLimit halving active workers on 429/503),
 * 4s/4s Slow-Socket Drop-and-Replace, and
 * Dynamic In-Flight Piece Splitting (claimOrStealNext),
   pre-capping wetafiles, downloadwella, and loadedfiles to 8 sockets leaves 33% of speed on the table when those CDNs aren't throttling.
   Raise wetafiles.*, downloadwella.com, and loadedfiles.* from 8 to 12 in both HostPolicyRegistry.SENSITIVE_SOCKET_CAP_HOSTS and scraper_rules.json (contentPolicy.socketCaps) while keeping kissorgrab.com at 6, dl.plutomovies.com at 6, and plutomovies.com at 8.
MANDATORY VERIFICATION & AUDIT OUTPUT
 * Python-First Simulation: Add unit simulations in scratch/ testing:
   * claimOrStealNext() dynamic in-flight piece splitting (verifying zero byte overlap, victim.end shortening, call.cancel() on shortened completion, and tail speedup when 1 slow socket is helped by 15 fast sockets).
   * probe() fast-fail on HTTP 429 (1 request total, HostHealth.recordFail triggered) and 401/403/404/410 (HEAD fallback once, then immediate TurboResult.Failure(code) without entering single()).
   * DownloadEngine cross-provider failover firing when producedFile == null and skipping aria2c when turboFailure.htmlPage == true or httpStatus in (401, 403, 404, 410, 429).
 * Adversarial QA Suite: Add checks for all 7 fixes to scratch/adversarial_qa_audit.py and verify 100% pass.
 * Syntax Gate: Run scripts/verify_kotlin_syntax.py across all 45 Kotlin files.
 * Updated Specification: Regenerate post_refactor_engine_audit.md containing the complete, unabridged source files and updated verification matrix.

