> Provenance: companion note received 2026-10-18 alongside the code spec saved in
> `docs/1DM_SPEED_DIRECTIVE_REFERENCE.md` (the "1DM-SPEED ARCHITECTURE" paste).
> Transcribed from the user message; contents unmodified. References to
> "post_refactor_engine_audit-2.md" mean the user's copy of this repo's
> `post_refactor_engine_audit.md`.

---

Yes — what the agent did in HostPolicyRegistry.kt will severely choke your download speeds on those hosts!
Look at what the agent snuck into post_refactor_engine_audit-2.md under Section 3.3 (HostPolicyRegistry.kt) and Section 10 (Deviation #9):
    private val SENSITIVE_SOCKET_CAP_HOSTS = mapOf(
        "kissorgrab.com" to 1,
        "dl.plutomovies.com" to 1,
        "plutomovies.com" to 4,
        "wetafiles.com" to 4,
        "downloadwella.com" to 4,
        "loadedfiles.org" to 4, ...
    )

Why That Capping Makes Downloads Slow (And Why Your Original Comment Warned Against It!)
 * The 1-Socket Trap (kissorgrab.com & dl.plutomovies.com): Look back at your original codebase comment in download_engine_code_and_architecture.md (Section 3.4 & 4.1):
   > "Sensitive hosts (kissorgrab, plutomovies) trip 429/connection-drop if overwhelmed by 16 sockets, but collapsing to 1 socket destroys throughput over high-latency cellular networks (crawls to 6-30 KB/s). Port aria2c/1DM safe concurrency: cap at 4 parallel sockets, which stays under server connection limits while delivering 4x throughput."
   > The agent ignored that hard-earned lesson and forced kissorgrab.com and dl.plutomovies.com back down to 1 socket (to 1), which will make them crawl at 6–30 KB/s on mobile networks!
   >
 * Hard Static Caps (4 Sockets) on wetafiles, downloadwella, loadedfiles: Those are your primary file lockers (nkiri, 9jarocks, dramakey)! By hard-capping them at 4 sockets from the very first byte, you are throwing away 50% to 75% of your speed when those servers aren't even rate-limiting you!
 * Why Static Capping Is Redundant When You Have TurboDownloader's Dynamic Rate-Limit Ladder:
   Look at TurboDownloader.kt (Section 5, enterRateLimit()):
   maxWorkers.set(maxOf(2, maxWorkers.get() / 2))

   TurboDownloader already has an automatic dynamic concurrency downshifter! If you start fast (e.g., 8 or 16 sockets) and a server sends HTTP 429/503, TurboDownloader automatically halves the active workers (16 → 8 → 4 → 2)! By pre-capping HostPolicyRegistry at 1 or 4 before the download even starts, you get slow downloads 100% of the time instead of letting TurboDownloader run at full speed and only downshift if the server actually complains!
 * Triple-Sequential Latency Before Bytes Move (Open Item O4):
   Look at what happens before TurboDownloader transfers a single byte on an unlisted URL:
   * First, HttpClient.probeTerminal() makes a network round-trip.
   * Second, StreamValidator.validateResult() makes a second network round-trip (Range: bytes=0-1024).
   * Third, TurboDownloader.probe() makes a HEAD request and a Range: bytes=0-1024 request!
     That is 3 to 4 sequential HTTP handshakes (2–6 seconds of dead waiting) before the download even starts!
 * Disk I/O Contention on 16 Sockets (synchronized(channel)):
   In TurboDownloader.kt (Section 5), all 16 worker coroutines synchronize on a lock around channel.write(bb, pos + w):
   synchronized(channel) {
    val bb = ByteBuffer.wrap(buf, 0, n)
    var w = 0
    while (bb.hasRemaining()) {
        val k = channel.write(bb, pos + w)
        ...
    }
}

   In Java/Android NIO, FileChannel.write(ByteBuffer, long position) is ALREADY thread-safe and atomic at the OS kernel level (pwrite64)! Wrapping pwrite64 in synchronized(channel) forces all 16 sockets to wait in a single-file line for flash storage I/O, throttling multi-socket throughput!
Audit of post_refactor_engine_audit-2.md (What Else We Found)
The agent did a much better job on the 16 reliability fixes in post_refactor_engine_audit-2.md (and even fixed several edge cases in Section 11), but take a look at its "Open Items" (O1–O6 in Section 11) plus the speed bottlenecks above:
 * Speed Killer #1 (HostPolicyRegistry.kt): Hard-capping kissorgrab.com and dl.plutomovies.com to 1 socket (6–30 KB/s crawl) and wetafiles/downloadwella/loadedfiles to 4 sockets.
 * Speed Killer #2 (TurboDownloader.kt): synchronized(channel) serializes thread-safe positional FileChannel.write(bb, pos) across all parallel sockets.
 * Speed Killer #3 (TurboDownloader.kt): probe() runs a sequential HEAD request first, and when CDNs omit Accept-Ranges on HEAD (very common), it runs a second GET Range: bytes=0-1024 request. A single GET Range: bytes=0-1023 (with header("Accept-Encoding", "identity")) gives you Content-Range (total size), 206 (range support), AND Content-Type (HTML check) in one single round-trip instead of two!
 * Speed Killer #4 (TurboDownloader.kt & YoutubeDlDownloader.kt): Dynamic piece sizing and aria2c --min-split-size=1M vs 10M. Also, TurboDownloader only commits resume state on 2 MB boundaries per socket.
 * Bug in Open Item O1 (DownloadEngine.kt):
   Look at hasExistingAria2Partial in DownloadEngine.kt:
   val hasExistingAria2Partial = ariaControlFile.exists() ||
    (dest.exists() && dest.length() > 0L && !partFile.exists())

   As the agent admitted in O1, if a download finished TurboDownloader (so dest is 100% complete on disk and .part is gone) and the user paused during VALIDATING, on resume hasExistingAria2Partial is true (because dest.exists() && !partFile.exists())! It then sends the 100% finished file to aria2c, which either fails with HTTP 416 (Requested Range Not Satisfiable) or overwrites the finished file from zero if the server returns HTTP 200!
   * The Fix: If dest.exists() && !partFile.exists() && !ariaControlFile.exists() && task.totalBytes > 0L && dest.length() == task.totalBytes, the file is already 100% downloaded — skip both TurboDownloader and aria2c and go straight to VALIDATING!
 * Bug in Open Item O2 (DownloadEngine.kt - computeDiskBytes):
   If a task fell back from TurboDownloader to aria2c, and a stale .turbo file somehow survived alongside .aria2, computeDiskBytes checks .turbo before .aria2, freezing progress! Also, handoffTurboPrefixToAria2c only deletes .turbo if partFile.renameTo(dest) succeeds — if dest.exists() already, handoffTurboPrefixToAria2c leaves .part and .turbo sitting on disk!
 * Bug in Open Item O6 (UrlClassifier.kt vs isDirectMediaUrl):
   UrlClassifier added jpg/jpeg/png/webp, but isDirectMediaUrl in Resolvers.kt does not include them, so paused image tasks re-enter the resolver chain.
Copy-Paste "Ultra-Fast Speed & Open-Items Closure" Prompt
Send this prompt to your coding agent to unlock maximum multi-socket download speeds and close out the remaining Open Items (O1, O2, O4, O6):
# DIRECTIVE: Ultra-Fast Throughput Optimization & Closure of Audit Open Items (O1, O2, O4, O6)

The reliability refactor in `post_refactor_engine_audit-2.md` is solid, but your changes in `HostPolicyRegistry` and `TurboDownloader` introduced severe throughput bottlenecks that choke download speeds. Additionally, Open Items O1, O2, O4, and O6 in Section 11 are straightforward to fix cleanly without architectural risk.

Execute the following 6 speed and correctness upgrades immediately:

---

### 1. Unlock Maximum Multi-Socket Concurrency in `HostPolicyRegistry.kt` (Remove the 1-Socket Crawl!)
* **Why:** Setting `kissorgrab.com` and `dl.plutomovies.com` to `1` socket causes downloads to crawl at 6–30 KB/s over high-latency mobile networks (as documented in `DownloadEngine` and `YoutubeDlDownloader`). Furthermore, hard-capping `wetafiles`, `downloadwella`, and `loadedfiles` at `4` sockets from byte zero sacrifices 50–75% of peak throughput on hosts where `TurboDownloader` already has an adaptive rate-limit ladder (`enterRateLimit` halves active workers dynamically on 429/503).
* **Required Change in `HostPolicyRegistry.kt`:**
  1. Never cap `kissorgrab.com` or `dl.plutomovies.com` to `1`. Use `6` sockets for `kissorgrab.com` and `dl.plutomovies.com`, and `8` sockets for `plutomovies.com`, `wetafiles.com`, `downloadwella.com`, and `loadedfiles.*` so they start fast and let `TurboDownloader`'s adaptive 429 downshifter (`8 -> 4 -> 2`) step down ONLY if the server actually rate-limits the user:
     ```kotlin
     private val SENSITIVE_SOCKET_CAP_HOSTS = mapOf(
         "kissorgrab.com" to 6,
         "dl.plutomovies.com" to 6,
         "plutomovies.com" to 8,
         "wetafiles.com" to 8,
         "downloadwella.com" to 8,
         "loadedfiles.org" to 8,
         "loadedfiles.net" to 8,
         "loadedfiles.com" to 8,
         "loadedfiles.st" to 8,
         "loadedfiles.to" to 8
     )
     ```
  2. For all other hosts, allow the user's full configured `parallelSockets` (default 16, or raise the ceiling to `minOf(32, requestedSockets.coerceIn(1, 32))` if the user configures higher).

---

### 2. Remove Lock Contention on `FileChannel.write` in `TurboDownloader.kt`
* **Why:** In `TurboDownloader.segmented()` -> `downloadPiece()`, all 16 parallel socket coroutines block on `synchronized(channel)` around `channel.write(bb, pos + w)`. In Java/Android NIO, positional `FileChannel.write(ByteBuffer, long position)` is ALREADY thread-safe and atomic at the OS kernel level (`pwrite64`) and does not modify the channel's global position! Wrapping it in `synchronized(channel)` serializes all 16 network threads behind a single mutex on every 256 KB read!
* **Required Change in `TurboDownloader.kt`:**
  Remove `synchronized(channel)` around the positional write loop in `downloadPiece`:
  ```kotlin
  val bb = ByteBuffer.wrap(buf, 0, n)
  var w = 0
  while (bb.hasRemaining()) {
      val k = channel.write(bb, pos + w)
      if (k <= 0) throw java.io.IOException("short positional write at ${pos + w}")
      w += k
  }

3. Single-Round-Trip Fast Probe in TurboDownloader.kt & Eliminate Double Pre-Flight (Closes Open Item O4)
 * Why: Currently, DownloadEngine runs StreamValidator.validateResult() (1st network round-trip), and then TurboDownloader.probe() runs a HEAD request (2nd network round-trip) followed by a Range: bytes=0-1024 GET request (3rd network round-trip). That wastes 2 to 5 seconds of sequential TLS/HTTP latency before downloading starts!
 * Required Change:
   * In TurboDownloader.probe(): Send the GET with Range: bytes=0-1023 and Accept-Encoding: identity FIRST! A single ranged GET immediately returns HTTP 206, Content-Range: bytes 0-1023/TOTAL, Content-Type, AND the first 1024 bytes (so you can also call StreamValidator.sniff(headBytes) right inside TurboDownloader.probe()!). Only fall back to a HEAD request if the Range GET did not yield the total length.
   * In TurboDownloader.probe(): Sniff the first 512–1024 bytes returned by the Range: bytes=0-1023 GET using StreamValidator.sniff(head) in addition to checking Content-Type: text/html. If StreamValidator.sniff(head) reports HTML, APK (PK), or EXE (MZ), return ProbeResult.HtmlPage (or a rejected result) immediately!
   * In DownloadEngine.startTask(): Because TurboDownloader.probe() now performs the exact same 1 KB ranged header + magic-byte sniff on connect, you only need to run the separate StreamValidator.validateResult() preflight when hasExistingAria2Partial is true! This cuts pre-download startup latency in half.
4. Close Open Item O1: Zero-Latency Completion When dest Is Already 100% Downloaded
 * Why: In DownloadEngine.startTask(), val hasExistingAria2Partial = ariaControlFile.exists() || (dest.exists() && dest.length() > 0L && !partFile.exists()) treats a 100% finished dest file (e.g. a task that was paused during VALIDATING after TurboDownloader already moved .part -> dest) as an incomplete aria2c partial and launches aria2c on a finished file!
 * Required Change in DownloadEngine.startTask():
   Before running TurboDownloader or aria2c in if (finalBackend == "aria2c" && !isMagnet):
   val partFile = File(dest.absolutePath + ".part")
val turboSidecar = File(dest.absolutePath + ".turbo")
val ariaControlFile = File(dest.absolutePath + ".aria2")
val alreadyCompleteOnDisk = dest.exists() &&
    !partFile.exists() &&
    !turboSidecar.exists() &&
    !ariaControlFile.exists() &&
    ((task.totalBytes > 0L && dest.length() == task.totalBytes) ||
        (task.totalBytes <= 0L && dest.length() >= 50 * 1024L && !looksLikeHtml(dest) && decodeCheck(dest)))

if (alreadyCompleteOnDisk) {
    producedFile = dest
} else {
    val hasExistingAria2Partial = ariaControlFile.exists() ||
        (dest.exists() && dest.length() > 0L && !partFile.exists() && !turboSidecar.exists())
    ...
}

5. Close Open Item O2: Sidecar Precedence & Clean Handoff When dest Exists
 * Why: If both .aria2 and .turbo exist (e.g. a handoff where .turbo deletion failed), computeDiskBytes checks .turbo first and ignores live .aria2 progress. Also, in handoffTurboPrefixToAria2c(dest), if !dest.exists() is false, .part and .turbo are left behind.
 * Required Change in DownloadEngine.kt:
   * In handoffTurboPrefixToAria2c(dest): if dest.exists() and !File(dest.absolutePath + ".aria2").exists(), delete the stale dest before partFile.renameTo(dest) so the handoff always succeeds, and always delete turboFile once partFile is renamed or removed.
   * In computeDiskBytes(task): if aria2File.exists() AND !File(dir, "$base.part").exists(), check aria2File before sidecar (.turbo).
6. Close Open Item O6: Align Image Extensions in isDirectMediaUrl
 * Why: UrlClassifier recognizes .jpg, .jpeg, .png, .webp, but isDirectMediaUrl does not, causing paused image downloads to re-enter the resolver chain on resume.
 * Required Change: Add .jpg, .jpeg, .png, .webp to isDirectMediaUrl (in Resolvers.kt / LinkResolver.kt) so direct image URLs are consistently recognized across all classifiers.
Verification Requirement
Re-run all automated simulation suites and the Adversarial QA Audit Suite, and update post_refactor_engine_audit.md with the verified results.

---

> **RESOLVED 2026-10-18** (Python-first, then ported):
> - Pre-validated in `scratch/test_probe_logic.py` (14/14), `scratch/test_drop_replace.py` (11/11),
>   `scratch/test_zero_redo.py` (41/41) BEFORE any Kotlin changed — the simulations caught four
>   ordering bugs in the draft design (warm-up strike counting, latch order, strike reset, drop budget).
> - Ported: caps 6/6/8/8 built-in + OTA `contentPolicy.socketCaps` overlay; lock-free positional writes;
>   single-round-trip probe (ranged GET + inline sniff, HEAD fallback); drop-and-replace (budget 4,
>   two-strikes, warm-up-gated, never consumes attempts, fresh connection via Connection: close +
>   zero-idle pool); MIN_SEGMENTED 2 MB; O1/O2/O4/O6 closed (see audit doc Section 12).
> - Adversarial suite extended to 113/113 checks covering this batch (Phase 3.6).
> - Deliberately deferred with reasons: 512 KB buffer number bump (inert under okio ≤8 KiB reads —
>   the real fix, a fill-the-buffer loop, remains a follow-up), commit-interval change, H2/H3 restores.
