# AnonDownloader (Serverless) — Project Roadmap & To-Do List

## Active Architecture
- **Multi-Provider Engine**: Parallel search, multi-server iframe racing (`ResolverRegistry.resolveAny`), and transfer-time mirror cooldowns (`MirrorPool`).
- **Cluster Isolation**:
  - `ASIAN_DRAMA_CLUSTER`: `asianc`, `dramarain`, `dramakey`, `pluto`, `nepu`
  - `WESTERN_CLUSTER`: `nkiri`, `9jarocks`, `naijavault`, `naijaprey`
  - `Progress Banking Guard`: Tasks with banked bytes (`bytesLanded > 0`) are locked to their source/mirror to prevent byte-stream corruption across incompatible encodes.

---

## Operational Invariants & Lessons for Future Agents

1. **Sticky OTA Source Ordering & jsDelivr `@master` Caching**:
   - `DynamicRulesManager.kt` persists `PREF_LAST_SOURCE` across sessions. If an app syncs from `jsdelivr` or `self-box`, it prioritizes that same source on subsequent syncs.
   - When deploying an OTA rules revision:
     - **VPS (`self-box`)**: Always run `python3 /home/Anon/download-toolkit-server/sync_ota_mirror.py --dir /home/Anon/download-toolkit-server/ota_files` via SSH (`anon_vps`) immediately. Do NOT rely on the daily 05:17 UTC cron.
     - **jsDelivr**: Always purge BOTH the branch tree and the file path via `purge.jsdelivr.net`:
       1. `curl -i "https://purge.jsdelivr.net/gh/anonrode/download-toolkit-serverless@master"`
       2. `curl -i "https://purge.jsdelivr.net/gh/anonrode/download-toolkit-serverless@master/scraper_rules.json.enc"`
       Purging only the file path leaves jsDelivr's `@master` branch pointer pointing at the previous commit for up to 15 minutes!
     - **Monotonic Rev Gate**: `parseRulesJson` enforces `if (r != null && activeRev > 0 && r in 1 until activeRev) return false`. If a client is on Rev $N$ and a sticky source serves Rev $N$, it accepts it and stops before querying newer revs. All active sources must be kept in sync.

2. **Nkiri (and Positional Scrapers) `urlAllowlist` Invariant**:
   - In `pipelines.nkiri.episodes.steps[0].items.urlAllowlist`: **NEVER include the site's own hostnames** (`thenkiri.com`, `nkiri.top`, `nkiri.ink`).
   - `urlAllowlist` filters which anchor `href`s on the post page are kept as episode rows.
   - If site domains are placed in `urlAllowlist`, every navigation link on the site (`/category/...`, `/tag/...`, related posts, header, footer) is matched as an episode, causing Hollywood movies to display 35+ fake episodes (`S01 E01..S01 E35`) and series to append 30-40 fake episodes before the real episodes.
   - `urlAllowlist` must strictly contain only actual file locker/CDN hosts: `downloadwella.com`, `wetafiles.com`, `loadedfiles`, `nkiserv.com`, `vikingfile`, `lulacloud`, `waffi`.

3. **Ground Truth Live Site Rule**:
   - Never rely on local script assumptions or unverified guesses. Always verify against the live world (`curl.exe` / real HTML response) before coding against it.

---

## Completed Items

- [x] **Expand Asian Drama Fallback to `nkiri` & `9jarocks`**: *(implemented)*
- [x] **Apply `NameSanitizer.cleanTitle()` to Trending & Genre Feeds (`TrendingFeed.kt`)**: *(implemented)*
- [x] **Fix HLS Startup Latency & Remux AAC Bitstream Filter (`dfa134a` / `fa48c05`)**: *(implemented)*
- [x] **Silence 4 Broken Providers via OTA (`contentPolicy.disabledSites`)**: Silenced `pluto`, `nepu`, `anitaku`, `asianc` in search, feed fan-out, and failovers.
- [x] **Nsrplay / Vimeos Stream Direct Bypass (Rev 8)**: `/api/v1/embed/sources/...` binds direct stream `vimeos.zip` and relaxes mandatory step 3.
- [x] **Fix PlutoMovies Handshake & Cyclic XOR Decryption**: Implemented in [`PlutoMoviesResolver.kt`](file:///C:/Users/user/Anon/ANON%20TOOLS/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt#L2948-L3055) and [`src/resolvers.py`](file:///C:/Users/user/Anon/ANON%20TOOLS/download-toolkit/src/resolvers.py#L1488-L1535); removed from `disabledSites` in Rev 10 (`2026.10.24.3`).
- [x] **Fix Nkiri Allowlist Episode Count Regression (Rev 9)**: Removed site domains from `urlAllowlist`; restored exact 1-episode counts for movies and correct episode numbers for series.
- [x] **Zero-Bloat Cloudflare Turnstile & Bot-Check Solver**: Implemented 4-layer solver architecture:
  - `CloudflareSolver.kt`: Dynamic mobile Chrome UA alignment at boot, SharedPreferences clearance vault (12h TTL), per-host single-flight coalescing (`inFlightByHost`), process-wide 1-WebView cap (`solverMutex`), background tag gate (0ms fail-fast), and AJAX/POST origin root resolution (`resolveChallengeTargetUrl`).
  - `HttpClient.kt`: Scoped `Sec-Fetch-*` headers, precise `isCloudflareChallenge()` discrimination, and unclosed synthetic response fallback on solve cancellation.
  - `CloudflareChallengeHost.kt`: Single-window Compose overlay animated via `animateDpAsState` (no `ModalBottomSheet` reparenting crashes), skeleton subresource firewall (dropping media/styles to 204 No Content), and 250ms reactive cookie poller for `cf_clearance` and `hvp`.
  - `AnonApp.kt` & `MainActivity.kt`: Cold-boot vault hydration and root Compose overlay mounting.
  - `CloudflareSolverTest.kt`: Unit test suite covering challenge discrimination, background tag gating, origin normalization, token extraction, and subresource firewall.
- [x] **Gogoanime Multi-Server Base64 Fallback & Dynamic OTA Selectors**:
  - `AnitakuProvider.kt`: Decodes Base64-encoded `select.mirror option[value]` and `select#mirror-select option[value]` dropdowns to extract all fallback mirror iframes and direct streams across all servers.
  - `DynamicRulesManager.kt`: Added `mirrorSelectors: List<String>` in `SiteRuleConfig` with JSON parsing and getter `getMirrorSelectors(site)`. Allows mirror selectors to be dynamically patched via signed OTA payloads without requiring APK releases.
- [x] **YouTube Music / Audio Thumbnail Embedding via Native FFmpeg**:
- [x] **Dead Locker Fast-Drop & Permanently Dead Host Filtering**:
  - `StrictLinkClassifier.kt`: Added `PERMANENTLY_DEAD_HOSTS` (`zippyshare.com`, `openload.co`, `verystream.com`, `rapidvideo.com`, `vidcloud.co`). Drops dead locker links immediately as `NavigationJunk("permanently_dead_host")` in 0 ms.
  - `LinkResolver.kt`: Rejects permanently dead hosts before querying `ResolverRegistry`, preventing 1-minute `HostHealth` backoff stalls and triggering immediate cluster failover on legacy archived posts.
- [x] **OTA Dynamic XOR Transform & PlutoMovies Key Fallback**:
  - `RulesPipeline.kt`: Implemented `xor:<key>` transform in `applyTransforms()` to enable declarative, zero-APK XOR unscrambling in signed OTA rules.
  - `Resolvers.kt`: Wired `DynamicRulesManager.getCryptoKeys("pluto")` fallback into `PlutoMoviesResolver` for instant OTA key rotation.

---

## Inventory of Open / Unfixed Items

The following items are unresolved in the codebase and live service:

### 1. Nepu Browser Proof-of-Work Challenge (Silenced via OTA)
- **Status**: Silenced via `contentPolicy.disabledSites = ["nepu"]`.
- **Live Site Reality**: `nepu.gd` deployed a Cloudflare-style `<title>Quick check</title>` verification challenge.
- **Root Cause**: Plain HTTP requests receive a JavaScript challenge requiring a SHA-256 Proof-of-Work computation and WebGL canvas hardware probe, yielding `Cookie: hv=1; hv2=1; hvp={nonce}`. Without solving the PoW, the client receives HTTP 200 containing challenge HTML rather than media streams.
- **Required Fix**: Implement a background headless challenge solver (or WebView-assisted challenge pass) to harvest and bank the `hvp` cookie session for subsequent API and stream requests.

### 2. Gogoanime / Anitaku Upstream Outage & Dead Hosts (Silenced via OTA)
- **Status**: Silenced via `contentPolicy.disabledSites = ["anitaku"]`.
- **Live Site Reality**: Upstream streaming host `2anime.xyz` IP `64.190.63.222:443` is unreachable / timing out; Megaplays stream hoster returns `Video Not Available`; backup mirror `s3taku.com` redirects to ad spam.
- **Required Fix**: Probe for a new, stable, functioning Gogoanime / Anitaku mirror domain with operational embed players and update `scraper_rules.json` and `DynamicRulesManager` fallback domains.

### 3. AsianC Silenced per User Request
- **Status**: Silenced via `contentPolicy.disabledSites = ["asianc"]` per operator instruction.
- **Live Site Reality**: `asianc.id` catalog search, episode listings, and `vidbasic.top` player embeds are operational. Not a defect. Can be un-silenced at any time.

### 4. Subtitle Sidecar Extraction for Locker Direct Media
- **Status**: Open (tracked in `.agents/AGENTS.md` §14).
- **Symptom**: Direct file downloads from lockers (`downloadwella.com`, `loadedfiles.*`, `wetafiles.com`) do not download external `.srt` / `.vtt` subtitle files unless subtitles are already multiplexed inside the `.mkv` container.
- **Required Fix**: Add external subtitle provider fallback (e.g. OpenSubtitles API / Kitsu integration or extracting subtitle links from the post HTML if present).
