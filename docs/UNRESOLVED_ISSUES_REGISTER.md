# Unresolved Issues & Deferred Repairs Register

**Target Subsystem:** AnonDownloader Engine, Providers, Resolvers & OTA Pipeline  
**Source Baseline:** `activity-log-share-48.txt`, Live Network Probes (2026-10-24), OTA Rev 9 (`2026.10.24.2`)  
**Scope:** Permanent reference document detailing all open defects, disabled upstream providers, live site protocol changes, exact error traces, and required code-level repairs.

---

## Executive Summary of Current System State

As of **OTA Rev 9 (`2026.10.24.2`)**:
1. **Silenced via `contentPolicy.disabledSites`**: `pluto`, `nepu`, `anitaku`, `asianc` are silenced across search fan-out, home feeds, drawer loading, and download failovers to protect user experience from persistent upstream failures.
2. **Operational Core Sites**: `nkiri` (repaired in Rev 9), `9jarocks`, `naijavault`, `naijaprey`, and `dramakey` remain fully functional.
3. **Primary Direct Bypass**: `nsrplay.space` direct HLS master streams (`p6.vimeos.zip`) are bypassed directly into the downloader engine.

Below is the definitive technical breakdown of all remaining open issues to be implemented in code.

---

## 1. PlutoMovies: New Handshake Protocol & Cyclic XOR Cipher

### Current Status
- **Silenced in OTA**: Present in `contentPolicy.disabledSites`.
- **Live Site Status**: **100% OPERATIONAL**. High-speed CDN nodes (`ol3.kissorgrab.com`, `7up.kissorgrab.com`) serve full-length Matroska MKVs with `206 Partial Content` support.

### Log Evidence (`activity-log-share-48.txt`)
```text
10-23 14:00:46.467 [ERROR] task=a9f7cc9f-0761-46fe-8db8-a5ad30438ec6 could not crack stream link (host=dl.plutomovies.com)
10-23 14:00:46.471 [RESOLVE] task=a9f7cc9f-0761-46fe-8db8-a5ad30438ec6 host dl.plutomovies.com in backoff - parking for ~1m
```

### Root Cause
PlutoMovies deprecated the legacy direct download links previously handled by [`PlutoProvider.kt`](file:///C:/Users/user/Anon/ANON%20TOOLS/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/providers/PlutoProvider.kt). The site transitioned to a tokenized dynamic handshake:
1. Movie page embeds a dynamic movie ID and security key in script blocks or attributes.
2. Client must perform an authenticated POST request to `https://dl.plutomovies.com/api/v1/dl-link/{id}` or `https://plutomovies.com/api/v1/dl-link/{id}` with headers:
   - `Content-Type: application/json`
   - `Referer: https://plutomovies.com/`
   - `Origin: https://plutomovies.com`
   - `User-Agent: Mozilla/5.0 ...`
3. Response returns an encrypted payload string and salt/key token.
4. The ciphertext is encrypted using a cyclic XOR transformation:
   $$\text{plaintext}[i] = \text{ciphertext}[i] \oplus \text{key}[i \pmod{\text{len}(\text{key})}]$$
5. The decrypted JSON contains `{"url": "https://ol3.kissorgrab.com/dl/...", "status": 200}`.
6. The Android Kotlin provider currently lacks this POST handshake and cyclic XOR decryption routine, failing with `could not crack stream link`.

### Required Implementation Blueprint
1. **Implement XOR Decryptor**:
   In [`PlutoProvider.kt`](file:///C:/Users/user/Anon/ANON%20TOOLS/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/providers/PlutoProvider.kt), add:
   ```kotlin
   fun xorDecrypt(cipherBytes: ByteArray, keyBytes: ByteArray): ByteArray {
       val out = ByteArray(cipherBytes.size)
       for (i in cipherBytes.indices) {
           out[i] = (cipherBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
       }
       return out
   }
   ```
2. **Implement Handshake**:
   Extract post token ID from the show page HTML (`data-id` or `movieId`), query `/api/v1/dl-link/{id}`, decrypt the returned base64 token using the session key, and parse the target `kissorgrab.com` URL.
3. **Re-enable in Rules**:
   Remove `"pluto"` from `contentPolicy.disabledSites` in `scraper_rules.json`.

---

## 2. Nepu: Browser-Level Proof-of-Work Challenge (`nepu.gd`)

### Current Status
- **Silenced in OTA**: Present in `contentPolicy.disabledSites`.
- **Live Site Status**: Running anti-bot protection on all endpoints (`nepu.gd/api/search`, `/series/`, `/embed/`).

### Log Evidence (`activity-log-share-48.txt`)
```text
10-23 14:01:05.984 [NET] GET https://nepu.gd/api/search?q=horror -> 200 in 2349ms
10-23 14:01:06.120 [ERROR] nepu API returned challenge page (unexpected HTML in JSON parser: "<!doctype html><title>Quick check</title>")
```

### Root Cause
`nepu.gd` deployed a browser-verification gate:
1. Every standard `GET` request from plain HTTP clients receives HTTP 200 with an inline script challenge titled `<title>Quick check</title>`.
2. The script executes a client-side SHA-256 Proof-of-Work search (computing nonces until `sha256(challenge + nonce)` starts with required leading zero bits).
3. The script additionally executes WebGL rendering calls (`createBuffer`, `getShaderPrecisionFormat`) to verify real GPU canvas hardware.
4. On success, the script sets browser cookies:
   - `Cookie: hv=1; hv2=1; hvp={solution_nonce}`
5. Standard OkHttp requests lacking these cookies cannot access search or media streams.

### Required Implementation Blueprint
1. **Option A (Headless Challenge Solver)**:
   Extract challenge parameters from the HTML body, calculate the SHA-256 PoW in a Kotlin coroutine, and set `hvp` on `HttpClient.cookieJar`.
2. **Option B (WebView Cookie Harvest)**:
   Launch a hidden `Android WebView` when a 403 / Quick Check challenge is detected on `nepu.gd`. Let the WebView execute the challenge script, intercept the resulting `hvp` cookie via `CookieManager.getInstance().getCookie("https://nepu.gd")`, bank it into `HttpClient`, and retry the background request.
3. **Re-enable in Rules**:
   Remove `"nepu"` from `contentPolicy.disabledSites` in `scraper_rules.json`.

---

## 3. Gogoanime / Anitaku: Dead Upstream Stream Hosters

### Current Status
- **Silenced in OTA**: Present in `contentPolicy.disabledSites`.
- **Live Site Status**: Major upstream infrastructure failure across secondary and third-party mirrors.

### Log Evidence (`activity-log-share-48.txt`)
```text
10-23 14:01:07.494 [NET] POST https://gogoanime.or.at/wp-admin/admin-ajax.php -> 200 in 1434ms
10-23 14:01:08.812 [ERROR] task=f102ca12-b219-4820-9dc2-9fa311d9bc7a could not crack stream link (host=2anime.xyz)
10-23 14:01:08.815 [RESOLVE] host 2anime.xyz connect timed out (64.190.63.222:443)
```

### Root Cause
1. Gogoanime (`anitaku.com.ro`, `gogoanime.or.at`) delegates streaming to `2anime.xyz` and `megaplays.se`.
2. **2anime.xyz**: The origin server IP `64.190.63.222:443` is unreachable and drops TCP SYN packets, triggering socket timeouts across all clients.
3. **Megaplays**: The embed endpoint responds with HTML containing `Video Not Available`.
4. **s3taku.com**: Deprecated mirror responding with HTTP 302 redirects to malicious ad networks.
5. The direct download AJAX endpoint (`/wp-admin/admin-ajax.php?action=get_download_link`) returns HTTP 500 internal server error upstream.

### Required Implementation Blueprint
1. Monitor Gogoanime / Anitaku announcements and community mirrors for the new official stream server domain (replacing `2anime.xyz`).
2. Verify live resolution of an anime episode against the candidate mirror using `curl.exe` to inspect the returned player iframe.
3. Update `domains.anitaku`, `mirrors.anitaku`, and `hostPolicies` in `scraper_rules.json`.
4. Remove `"anitaku"` from `contentPolicy.disabledSites`.

---

## 4. AsianC: Broken Embed Streaming Endpoints

### Current Status
- **Silenced in OTA**: Present in `contentPolicy.disabledSites`.
- **Live Site Status**: Catalog pages load, but video players fail.

### Log Evidence (`activity-log-share-48.txt`)
```text
10-23 14:01:10.129 [RESOLVE] asianc resolver: iframe search for streaming.php returned 0 matches
10-23 14:01:10.134 [ERROR] task=c879a941-897b-4029-9fa1-018274d81239 could not extract embed player from https://asianc.id/...
```

### Root Cause
1. `asianc.id` updated their WordPress / custom theme layout.
2. The `/streaming.php?id=...` endpoint previously used to extract server options now returns empty responses or redirects to inactive locker stubs.
3. Direct video embeds previously provided by Vidplay / MyCloud on AsianC are currently broken upstream.

### Required Implementation Blueprint
1. Probe current AsianC alternative domains (`asianc.to`, `asianc.sh`, `dramacool`) to determine active iframe patterns.
2. Update iframe extraction selectors in `scraper_rules.json` under `pipelines.asianc`.
3. Once embed player extraction produces working HLS manifests (`.m3u8`), remove `"asianc"` from `contentPolicy.disabledSites`.

---

## 5. Dead Zippyshare Links on Archived Catalog Items

### Current Status
- **Unresolved for Older Catalog Posts**.
- Affects legacy anime and drama posts on `9jarocks.net` and `naijavault.com`.

### Log Evidence (`activity-log-share-48.txt`)
```text
10-23 01:37:34.339 [ERROR] task=e18ea09c-7f3c-486d-ac7a-fcdfb9f5fa58 could not crack stream link (host=www82.zippyshare.com) (Zippyshare shut down in 2023 - this link cannot be downloaded) for https://www82.zippyshare.com/v/D3oqfaxN/file.html
10-23 01:37:34.340 [RESOLVE] task=e18ea09c-7f3c-486d-ac7a-fcdfb9f5fa58 host www82.zippyshare.com in backoff - parking for ~1m
```

### Root Cause
1. Zippyshare permanently shut down in March 2023.
2. Historical blog posts published before 2023 retain dead `*.zippyshare.com` URLs in their download sections.
3. When a user selects an episode linking solely to Zippyshare, the app attempts to resolve it, fails, and enters a 1-minute host backoff parking state. This delays failover to alternate providers (e.g. searching the same title on Nkiri or 9jaRocks secondary lockers).

### Required Implementation Blueprint
1. Update [`StrictLinkClassifier.kt`](file:///C:/Users/user/Anon/ANON%20TOOLS/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/pipeline/StrictLinkClassifier.kt) and [`LinkResolver.kt`](file:///C:/Users/user/Anon/ANON%20TOOLS/download-toolkit-serverless/app/src/main/java/com/anonrode/downloader/pipeline/LinkResolver.kt):
   - Add `zippyshare.com` to `PERMANENTLY_DEAD_HOSTS`.
   - When a URL matches `*.zippyshare.com`, immediately mark the candidate as `PermanentFailure` without entering a backoff cooldown.
   - Instantly trigger `attemptCrossProviderFailover()` to search active providers.

---

## 6. Subtitle Sidecar Extraction for Locker Direct Media

### Current Status
- **Open Architectural Item** (tracked in [`.agents/AGENTS.md`](file:///C:/Users/user/Anon/ANON%20TOOLS/download-toolkit-serverless/.agents/AGENTS.md#L101)).

### Root Cause
1. Video files downloaded directly from web lockers (`downloadwella.com`, `loadedfiles.*`, `wetafiles.com`, `vikingfile.com`) are raw `.mkv` or `.mp4` containers.
2. While some MKVs contain embedded soft subtitles (SSA/VTT/SRT tracks), MP4 containers and compressed encodes frequently have no embedded subtitle streams.
3. The app currently does not extract separate subtitle links even when a post page provides a distinct "Download Subtitles" button.

### Required Implementation Blueprint
1. In `scraper_rules.json` and compiled providers (`NkiriProvider`, `NaijaVaultProvider`, `NineJaRocksProvider`):
   - Add a subtitle anchor selector (`a[href*='subtitle'], a[href*='.srt'], a[href*='.vtt']`).
2. Pass subtitle candidate URLs into `DownloadRecipe.subtitleUrls`.
3. In `DownloadEngine`, download the subtitle sidecar alongside the media file and register it with `LocalMediaRepository` so `MediaPlayerModal` and external players load the subtitle track automatically.

---

## Acceptance Verification Protocol

When addressing any of the items above, verify against this strict checklist:
- [ ] **Live World Verification**: Test against real endpoints with `curl.exe` before editing code.
- [ ] **Monolithic Code-First Principle**: Follow `.agents/AGENTS.md` §16 — OTA is data-only; code-level routing, decryption, and error handling must reside in Kotlin.
- [ ] **Zero AI Attribution**: Commit messages describe technical changes only.
- [ ] **Sync Verification**: Pushing OTA updates requires immediate VPS synchronization (`sync_ota_mirror.py`) and jsDelivr cache + branch tree purging.
