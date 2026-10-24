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
- [x] **Fix Nkiri Allowlist Episode Count Regression (Rev 9)**: Removed site domains from `urlAllowlist`; restored exact 1-episode counts for movies and correct episode numbers for series.

---

## Inventory of Open / Unfixed Items

The following items are unresolved in the codebase and live service:

### 1. PlutoMovies Handshake & Cyclic XOR Decryption (Silenced via OTA)
- **Status**: Silenced via `contentPolicy.disabledSites = ["pluto"]`.
- **Live Site Reality**: `dl.plutomovies.com` is 100% operational on `ol3.kissorgrab.com` and `7up.kissorgrab.com`, verified live with `206 Partial Content` Matroska streaming.
- **Root Cause**: Pluto deprecated their old endpoint and deployed a new POST handshake at `/api/v1/dl-link/{id}` returning encrypted JSON tokens decrypted via cyclic XOR (`data[i] ^ key[i % len(key)]`).
- **Required Fix**: Implement the new XOR handshake and decryptor in Kotlin (`PlutoProvider.kt` and/or OTA `decrypt` transform step) so PlutoMovies can be re-enabled.

### 2. Nepu Browser Proof-of-Work Challenge (Silenced via OTA)
- **Status**: Silenced via `contentPolicy.disabledSites = ["nepu"]`.
- **Live Site Reality**: `nepu.gd` deployed a Cloudflare-style `<title>Quick check</title>` verification challenge.
- **Root Cause**: Plain HTTP requests receive a JavaScript challenge requiring a SHA-256 Proof-of-Work computation and WebGL canvas hardware probe, yielding `Cookie: hv=1; hv2=1; hvp={nonce}`. Without solving the PoW, the client receives HTTP 200 containing challenge HTML rather than media streams.
- **Required Fix**: Implement a background headless challenge solver (or WebView-assisted challenge pass) to harvest and bank the `hvp` cookie session for subsequent API and stream requests.

### 3. Gogoanime / Anitaku Upstream Outage & Dead Hosts (Silenced via OTA)
- **Status**: Silenced via `contentPolicy.disabledSites = ["anitaku"]`.
- **Live Site Reality**: Upstream streaming host `2anime.xyz` IP `64.190.63.222:443` is unreachable / timing out; Megaplays stream hoster returns `Video Not Available`; backup mirror `s3taku.com` redirects to ad spam.
- **Required Fix**: Probe for a new, stable, functioning Gogoanime / Anitaku mirror domain with operational embed players and update `scraper_rules.json` and `DynamicRulesManager` fallback domains.

### 4. AsianC Upstream Player Outage (Silenced via OTA)
- **Status**: Silenced via `contentPolicy.disabledSites = ["asianc"]`.
- **Live Site Reality**: `asianc.id` and `asianc.sh` embed players (`/streaming.php`) return 404 or empty iframe responses.
- **Required Fix**: Investigate current active AsianC mirrors and embed parsers.

### 5. Dead Zippyshare Links on Archived Catalog Items
- **Status**: Unresolved for legacy items.
- **Symptom**: In `activity-log-share-48.txt`, tasks downloading older series from 9jaRocks / NaijaVault hit `www82.zippyshare.com` which shut down in 2023 (`host in backoff - parking for ~1m`).
- **Root Cause**: Scraped posts contain hardcoded legacy Zippyshare links with no alternative mirror links present in the HTML.
- **Required Fix**: Add an immediate rejection / dead-link classifier for `*.zippyshare.com` in `StrictLinkClassifier` so tasks fail over immediately to secondary clusters (e.g. Nkiri / 9jaRocks secondary lockers) rather than entering repeated backoff stalls.

### 6. Subtitle Sidecar Extraction for Locker Direct Media
- **Status**: Open (tracked in `.agents/AGENTS.md` §14).
- **Symptom**: Direct file downloads from lockers (`downloadwella.com`, `loadedfiles.*`, `wetafiles.com`) do not download external `.srt` / `.vtt` subtitle files unless subtitles are already multiplexed inside the `.mkv` container.
- **Required Fix**: Add external subtitle provider fallback (e.g. OpenSubtitles API / Kitsu integration or extracting subtitle links from the post HTML if present).
