# 🚀 ANONRODE DOWNLOAD ENGINE — COMPLETE HANDOVER & ARCHITECTURE DOSSIER

**Target Codebase:** `C:\Users\Anon\download-toolkit-serverless` (Android / Kotlin / Jetpack Compose / OkHttp / libaria2c / youtubedl-android)  
**Reference Python Monolith:** `C:\Users\Anon\download-toolkit` (`src/downloader.py`, `src/resolvers.py`, `src/extractors/`)  
**Operating Charter:** `.agents/AGENTS.md` (Permanent source of truth)  
**Status:** All CI/CD release builds passing ✅ (`de0a67e` / Run `32240181208`)

---

## 🏛️ 1. PROJECT CONTEXT & ARCHITECTURAL FOUNDATION

The project is **Anon Downloader** (Serverless 100% On-Device Android Edition). It ports the heavy-duty Python download toolkit monolith into an entirely standalone, serverless native Android app without any backend servers or proxy middleboxes.

### Key Workspaces:
1. **`C:\Users\Anon\download-toolkit-serverless`**:
   The native Android application. It implements direct CDN downloading, multi-socket range segmentation, locker cracking, episode scraping, HLS `.m3u8` extraction, BitTorrent engine, and background foreground services.
2. **`C:\Users\Anon\download-toolkit`**:
   The Python monolith reference. **Never bulk-read `src/downloader.py` (~3900 lines)** — use narrow `grep -n` / small ranges.

---

## 📜 2. THE NON-NEGOTIABLE LAWS & INVARIANTS

Incoming AI agents must adhere to these operating laws:
1. **Never add AI attribution to commits** (no `Co-Authored-By`, no "Generated with", no AI signatures). Commit messages describe the technical change only.
2. **Verify against the live world before coding** — every endpoint, selector, regex, and JSON field.
3. **`src/downloader.py` is ~3900 lines — never bulk-read it.**
4. **Never delete or replace `.agents/AGENTS.md`.** It is the permanent constitution. Keep WIP notes in separate files.
5. **Always monitor CI/CD after pushing** (`gh run list`, `gh run watch`) until builds complete with success.
6. **Download Routing Rules**:
   * BitTorrent Magnet $\to$ `libaria2c.so`
   * HLS (`.m3u8` / `manifest`) $\to$ `YoutubeDlDownloader` (youtubedl-android)
   * Direct CDN Media (`.mp4`, `.mkv`, etc.) $\to$ `TurboDownloader` (Multi-socket segmented range engine) $\to$ fallback to `aria2c`

---

## 🔬 3. FORENSIC AUDIT: THE 3 SOLVED PERFORMANCE BOTTLENECKS

During real-world device testing, three systemic architectural flaws were discovered and resolved:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    THE 3 FORENSIC ROOT CAUSES                                    │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 1. THROUGHPUT COLLAPSE (0.1 - 0.3 MB/s):                                                         │
│    • Probe False-Negative: `probe()` checked `Accept-Ranges` on HEAD; when CDNs omitted it on    │
│      HEAD, it returned `ranges=false`, permanently disabling 4-8 parallel socket chunking.       │
│    • Notification Binder IPC Storm: `onProgress` fired on EVERY 128KB buffer read, triggering     │
│      synchronous Android `NotificationManager` Binder IPC (10-20ms per call) that choked the    │
│      network loop to 0.1 MB/s.                                                                   │
│    • NIO Channel Locking: Coroutines blocked on POSIX file locks during unbuffered writes.       │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 2. TELEMETRY & ETA OSCILLATION:                                                                  │
│    • Raw 600ms Delta: `SpeedMeter` calculated raw speed over 600ms windows without exponential   │
│      smoothing, swinging from 0.0 MB/s to 4.5 MB/s on normal bursty TCP packet arrivals.         │
│    • yt-dlp Progress Erasure: `YoutubeDlDownloader` discarded stdout byte telemetry, mapping     │
│      percentages to dummy values (`downloadedBytes = pct`, `totalBytes = 100L`, `speed = 0.0`). │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 3. PAUSE / RESUME RE-RESOLUTION DELAY:                                                           │
│    • Premature `RESOLVING` State: Queue processor blindly set `status = RESOLVING` upfront.      │
│    • Unconditional Re-Resolution: Resume ran 25 resolvers and scraped show pages (taking 5-15s) │
│      instead of immediately issuing an HTTP `Range: bytes=<offset>-` on the active `directUrl`.  │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📂 4. CODEBASE TOUR & CRITICAL ENGINE FILES

Here is the exact map of the engine files in `app/src/main/java/com/anonrode/downloader/`:

### ⚡ Direct Engine & Concurrency
* **[`engine/TurboDownloader.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/engine/TurboDownloader.kt)**:
  * Multi-socket segmented downloader using OkHttp and `FileChannel` on pre-allocated `RandomAccessFile`.
  * `probe()`: Sends `HEAD`; if `Accept-Ranges` is missing, tests `Range: bytes=0-0`. Upon `HTTP 206`, unlocks 4–8 parallel sockets.
  * Decoupled telemetry ticker (250ms) using `AtomicLong` counters.
  * Exponential Moving Average (`SpeedMeter`, $\alpha = 0.25$) for smoothed speed and ETA.
* **[`engine/TurboState.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/engine/TurboState.kt)**:
  * Manages `.turbo` sidecar files storing `start:end:current` byte offsets per segment.
  * Throttles disk commits to at most once every 2 seconds during active streaming.
* **[`engine/DownloadEngine.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt)**:
  * Central task orchestrator and queue processor.
  * **Zero-Latency Resume:** When resuming, if `task.directUrl` is already an HTTP URL, immediately transitions to `DOWNLOADING` and fires an optimistic `Range: bytes=<offset>-` request.
  * **Self-Healing Token Refresh:** Only transitions to `RESOLVING` if the CDN returns `401/403/404/410` (token expired).
  * **Throttled Notifications:** `updateServiceState` runs at most once per second to prevent Binder IPC storms.
* **[`engine/YoutubeDlDownloader.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/engine/YoutubeDlDownloader.kt)**:
  * Handles HLS streams, BitTorrent magnets, and aria2c fallbacks.
  * Parses stdout regex lines to deliver true byte counts, live speed, and ETA.
* **[`engine/DownloadRepository.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/engine/DownloadRepository.kt)**:
  * In-memory `MutableStateFlow<List<DownloadTask>>` with JSON disk persistence (`download_tasks.json`).
  * Enforces monotonic progress (`downloadedBytes = maxOf(current, new)`).

### 🌐 Networking, Scrapers & Resolvers
* **[`data/net/HttpClient.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/data/net/HttpClient.kt)**:
  * Global `OkHttpClient` with hybrid DNS (System DNS + DoH fallback) and dedicated `downloadClient` (30s read timeout).
* **[`resolvers/Resolvers.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt)**:
  * 25 host locker decryptors (`VidbasicResolver`, `LoadedfilesResolver`, `DownloadwellaResolver`, `StreamwishResolver`, etc.) with `JsUnpacker` and AES-256-CBC decryptors.
* **[`providers/Providers.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/providers/Providers.kt)**:
  * Provider search and episode scrapers (`AsianC`, `Anitaku`, `NKiri`, `DramaKey`, `PlutoMovies`, `NaijaVault`, `NineJaRocks`, etc.).

### 📱 UI & ViewModels
* **[`ui/screens/DownloadsScreen.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/ui/screens/DownloadsScreen.kt)**:
  * Compose UI with progress bars, play/pause/resume/cancel controls, and speed/ETA badges.
* **[`viewmodel/MainViewModel.kt`](file:///C:/Users/Anon/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/viewmodel/MainViewModel.kt)**:
  * Manages global app state, search debounce (350ms), and provider drawer episodes.

---

## 🛠️ 5. RECENT COMMITS & REVISION HISTORY

| Commit Hash | Message / Summary | Key Impact |
| :--- | :--- | :--- |
| **`de0a67e`** | `Fix cachedTotal reference in TurboState` | Restored `cachedTotal` field; CI/CD release build compiled with 100% success. |
| **`574b47a`** | `Optimize download engine throughput, stream resume, and telemetry` | Implemented EMA speed meter, definitive range probing, zero-latency resume, stdout regex progress parsing, and Binder IPC throttling. |
| **`829319e`** | `Fix onFocusChanged import and extension in HomeScreen` | Fixed Compose focus modifier import. |
| **`9601e1a`** | `Fix download pause/resume concurrency...` | Prevented coroutine leaks and race conditions on rapid pause/resume. |

---

## 📋 6. INCOMING AI QUICK-START CHECKLIST

When picking up work on this codebase:
1. **Always check git branch status**: `git status` (should be on `master`, clean).
2. **Never break range semantics**: Segmented workers MUST receive `HTTP 206`. If a CDN does not reply `206`, fall back to single stream or aria2c.
3. **Keep Telemetry Decoupled**: Never call `onProgress` or `NotificationManager.notify` directly inside tight 128KB/256KB byte loops.
4. **Preserve Monotonicity**: Ensure `downloadedBytes` never regresses in repository updates.
5. **Always Verify & Watch Builds**:
   ```bash
   git push origin master
   gh run watch <RUN_ID> --repo anonrode/download-toolkit-serverless --exit-status
   ```
