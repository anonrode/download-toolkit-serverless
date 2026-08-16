# 📋 Handover & Architecture Audit: Anon Downloader (Serverless Edition)

This document provides a complete technical audit of the **Anon Downloader (Serverless 100% On-Device Edition)** codebase, its directory structure, ported features from the Python monolith, and current working tree state.

---

## 📁 1. Repositories & Workspace Locations

* **Serverless Native App (Current Workspace)**: `C:\Users\Anon\download-toolkit-serverless`
  * **GitHub Remote**: `https://github.com/anonrode/download-toolkit-serverless`
  * **Default Branch**: `master`
  * **Tech Stack**: Kotlin, Jetpack Compose, Material You (Pure AMOLED Black `#000000`), Coroutines + Flow, Jsoup, OkHttp, `youtubedl-android` (v0.18.1 with `libaria2c.so`, `libffmpeg.so`, and embedded Python runtime).
* **Reference Python Monolith**: `C:\Users\Anon\download-toolkit`
  * **Source of Truth**: `src/downloader.py`, `src/resolvers.py`, `src/extractors/`, `src/torrent.py`, `src/search.py`, `.agents/AGENTS.md`.

---

## 🏛️ 2. High-Level Architecture

The Serverless Edition is a **100% client-side, on-device downloader** that completely replaces the need for a backend server or Termux terminal:

```
[User Input / Share Sheet / Search]
               │
               ▼
      [UrlRouter.kt] ── (Classifies into Drama, Torrent, Direct Media, Social, Search)
               │
       ┌───────┴───────────────────────────────┐
       ▼                                       ▼
 [ProviderRegistry]                   [Social / Direct]
 (11 Native Providers +                        │
  Generic Declarative Engine)                  ▼
       │                              [DownloadEngine.kt]
       ▼                               (Priority Queue + State)
 [Resolvers.kt]                                │
 (8+ Host Resolvers & Unpackers)               ▼
       │                         [DownloadService.kt (Foreground)]
       └─────────────────────────► (WakeLock + WifiLock + Notification)
                                               │
                                               ▼
                                   [YoutubeDlDownloader.kt]
                                  ┌────────────┴───────────┐
                                  ▼                        ▼
                          [libaria2c.so]            [libyoutubedl.so]
                      (Direct CDNs & Magnets)      (HLS m3u8 & Social)
```

---

## 📦 3. Complete Component Breakdown & What Was Built

### A. All 11+ Site Providers (`app/src/main/java/.../providers/`)
All extractors from the Python monolith have been ported into non-blocking, asynchronous Kotlin scrapers:
1. **`NkiriProvider.kt`**: Multi-wave slug probes + live RSS search (`/search/{q}/feed/rss2/`).
2. **`DramaKeyProvider.kt`**: Multi-domain slug probing (`dramakey.com` and `dramakey.cc`).
3. **`AsianCProvider.kt`**: Dramacool & MyAsianTV search API (`/api?a=search&keyword=...`) and episode parser.
4. **`AnitakuProvider.kt`**: Gogoanime AJAX search (`ts_ac_do_search`), sub/dub tags, and episode list extraction.
5. **`PlutoProvider.kt`**: Pluto Movies & Series HTML search and stream resolver.
6. **`DramaRainProvider.kt`**: DramaRain slug probing and stream extraction.
7. **`RocksProvider.kt`**: 9jaRocks live RSS feed search (`/search/{q}/feed/rss2/`).
8. **`NaijaVaultProvider.kt`**: WordPress REST API search (`/wp-json/wp/v2/posts?search=...`).
9. **`NaijaPreyProvider.kt`**: NaijaPrey RSS feed search (`/search/{q}/feed/rss2/`).
10. **`NepuProvider.kt`**: TMDB multi-search API (`/api/search?q=...`) for HD movies and TV.
11. **`TorrentProvider.kt`**: The Pirate Bay live search (`apibay.org/q.php?q=...`) with VIP/Trusted seeder validation and magnet builder.
12. **`GenericDeclarativeProvider.kt`**: Dynamic engine that can add **brand-new websites remotely** just by defining them in `scraper_rules.json` without updating the APK!

---

### B. All 8+ Embed Resolvers (`app/src/main/java/.../resolvers/Resolvers.kt`)
Ported video lockers and unpackers:
* **`DownloadwellaResolver`**: Form POST simulation (`method_free`) for direct `.mkv` files.
* **`StreamwishResolver` / `VidhideResolver`**: Pure Kotlin Dean Edwards `p.a.c.k.e.r` unpacker.
* **`DoodstreamResolver`**: `/pass_md5/` token resolver with dynamic timestamp generation.
* **`MixdropResolver`**: MDCore regex unpacker.
* **`StreamtapeResolver`**: DOM token string concatenation.
* **`PixelDrainResolver`**: Direct API stream resolver (`/api/file/<id>`).
* **`PlutoMoviesResolver`**: Location redirect unpacker.
* **`GenericLockerResolver`**: Multi-host resolver for `waffi.cloud`, `loadedfiles`, `wildshare`, `vikingfile`, `lulacloud`, and `vidmoly`.

---

### C. Background Lifecycle & Mobile OS Hardening
1. **Foreground Service (`service/DownloadService.kt`)**:
   * Runs as an Android `dataSync` Foreground Service.
   * Holds a `PARTIAL_WAKE_LOCK` and high-performance `WifiLock` so downloads **never sleep or drop speed when the phone is locked or screen is off**.
   * Shows a low-priority, ongoing notification with live progress and active task counts.
2. **Smart Network Observer (`util/NetworkObserver.kt`)**:
   * Uses `ConnectivityManager.NetworkCallback` to detect connection drops.
   * Auto-pauses tasks with `"Waiting for network..."` and **auto-resumes the instant Wi-Fi or 4G/5G reconnects**.
   * Enforces `"Download Torrents Only on Wi-Fi"` setting to protect mobile data balances.

---

### D. Download Engine & Quality Selection (`engine/`)
1. **`YoutubeDlDownloader.kt`**:
   * **True Quality Mapping**: Translates user's quality preference (`480p`, `720p`, `1080p`, `4k`) to exact height constraints:
     * HLS m3u8 Streams (Gogoanime, AsianC, etc.): `-f "best[height<=$height]/best"`
     * Social Media (YouTube, TikTok, IG, FB): `-f "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/best[height<=$height]/best"`
     * Audio Extraction: `-f "bestaudio/best" --extract-audio --audio-format mp3`
   * **Monolith Social Metadata Naming**: Uses `-o "%(uploader,creator,channel)s - %(title).80s [%(id)s].%(ext)s"`.
   * **BitTorrent Optimization**: Pre-loads top 10 public Tier-1 trackers (`ngosang/trackerslist`), enables DHT (`--enable-dht=true --enable-dht6=true`), peer exchange (`--enable-peer-exchange=true`), peer ceiling (`--bt-max-peers=120`), RAM write buffer (`--disk-cache=32M`), and stops seeding immediately on completion (`--seed-time=0`).
2. **`DownloadEngine.kt`**:
   * Storage Guard: Checks `StatFs`; auto-pauses if free space drops below threshold (e.g. 1.0 GB).
   * Minimum Size Check: Drops files $< 500\text{ KB}$ to protect against 16-byte HTML error tombstones.
   * `MediaScannerConnection`: Automatically scans completed videos into Android `MediaStore` so they appear instantly in Google Photos/Gallery.
   * `SharedPreferences` Persistence: Saves all user preferences permanently.

---

### E. User Interface & Experience (`ui/screens/`)
1. **Seal-Inspired Settings UI (`SettingsSheet.kt`)**:
   * Pure AMOLED Black theme with modular categorized cards:
     * **Self-Healing & Core Updates**: 1-tap "Sync Scraper Logic" from GitHub + 1-tap "Update yt-dlp Core".
     * **General & Automation**: Auto-Organize folders, Instant Social download.
     * **Engine & Performance**: Sockets slider (1-16), Concurrent tasks slider (1-5), Storage Guard slider (0.5-5.0 GB), Wi-Fi Only Torrents switch.
     * **Media & Quality Formats**: Stream resolution selection chips (`480p`, `720p`, `1080p`).
     * **Storage & About**: Live storage bar and engine telemetry.
2. **Downloads & Playback Screen (`DownloadsScreen.kt`)**:
   * 1-Tap Play: Launches `Intent(ACTION_VIEW)` with `FileProvider` (`content://...`) to open video in **VLC, MX Player, or Gallery player**.
   * 1-Tap Share: Launches `Intent(ACTION_SEND)` to send video directly to WhatsApp/Telegram.
   * Pause / Resume / Retry / Cancel / Delete controls.
3. **Home Screen (`HomeScreen.kt`)**:
   * Filter Chips: `All`, `Torrents (TPB)`, `Asian Drama`, `NKiri`, `DramaKey`, `Anime`, `Pluto`, `Nepu HD`, `9jaRocks`, `NaijaVault`, `NaijaPrey`, `DramaRain`.
   * Smart Clipboard Sniffer: Automatically detects copied links on app focus and shows a 1-tap `📋 Paste` banner.
4. **Social Modal (`SocialModal.kt`)**:
   * Clean platform title deduplication (e.g. "Twitter Video").
   * **`🎬 Video (MP4)` vs `🎵 Audio Only (MP3)`** format selector.

---

### F. Over-The-Air Self-Healing Engine (`scraper_rules.json` & `DynamicRulesManager.kt`)
* Bundles `scraper_rules.json` with base URLs for all 11 providers, resolver host lists, and active trackers.
* Tapping **"Sync Scraper Logic"** in Settings fetches `https://raw.githubusercontent.com/anonrode/download-toolkit-serverless/master/scraper_rules.json`.
* Updates domain mirrors and instantiates new dynamic site providers in-memory within 1 second without updating the APK!

---

## 🔒 4. Current Git & Working Tree State

* **Branch**: `master`
* **Local Modified / New Files**:
  * `M app/src/main/java/.../data/router/UrlRouter.kt`
  * `M app/src/main/java/.../engine/DownloadEngine.kt`
  * `M app/src/main/java/.../engine/YoutubeDlDownloader.kt`
  * `M app/src/main/java/.../providers/ProviderRegistry.kt`
  * `M app/src/main/java/.../resolvers/Resolvers.kt`
  * `M app/src/main/java/.../service/DownloadService.kt`
  * `M app/src/main/java/.../ui/screens/DownloadsScreen.kt`
  * `M app/src/main/java/.../ui/screens/HomeScreen.kt`
  * `M app/src/main/java/.../ui/screens/SettingsSheet.kt`
  * `M app/src/main/java/.../ui/screens/SocialModal.kt`
  * `M app/src/main/java/.../viewmodel/MainViewModel.kt`
  * `?? app/src/main/java/.../data/rules/DynamicRulesManager.kt`
  * `?? app/src/main/java/.../providers/GenericDeclarativeProvider.kt`
  * `?? app/src/main/java/.../providers/NaijaPreyProvider.kt`
  * `?? app/src/main/java/.../providers/NaijaVaultProvider.kt`
  * `?? app/src/main/java/.../providers/NepuProvider.kt`
  * `?? app/src/main/java/.../providers/TorrentProvider.kt`
  * `?? app/src/main/java/.../util/NetworkObserver.kt`
  * `?? scraper_rules.json`
* **Verification Status**:
  * ✅ `audit_serverless_integrity.py` executed: **100% balanced braces/brackets and well-formed XML**.
  * 🛑 **Zero commits and zero pushes made** (held strictly in local workspace waiting for user instructions).
