# 🚀 ANONRODE DOWNLOAD ENGINE — HANDOVER & ARCHITECTURE DOSSIER

**Target Codebase:** `C:\Users\Anon\download-toolkit-serverless` (Android / Kotlin / Jetpack Compose / OkHttp / libaria2c / youtubedl-android)
**Reference Python Monolith:** `C:\Users\Anon\download-toolkit` (`src/downloader.py`, `src/resolvers.py`, `src/extractors/`) — **the battle-tested reference; the Kotlin app is a port of it, and porting infidelities are the #1 bug source.**
**Operating Charter:** `.agents/AGENTS.md` (Permanent source of truth — never delete)
**Status:** ✅ **v3.0.3 RELEASED** (tag `v3.0.3`, 2026-08-23) — all jobs green (unit tests 77/77, emulator smoke, release APK). Everything on master is pushed.
**Last updated:** 2026-08-23 ~21:30 UTC+1

---

## 🏛️ 1. PROJECT CONTEXT

Anon Downloader = 100% serverless on-device Android downloader. Multi-site search (nkiri, 9jarocks, naijavault, naijaprey, nepu, asianc, pluto, dramarain, dramakey, anitaku, torrents) → per-site episode drawers → 3-engine downloading:
- **TurboDownloader**: Kotlin OkHttp segmented range downloader (default engine for direct files)
- **aria2c**: via bundled yt-dlp `--downloader libaria2c.so`
- **yt-dlp**: social embeds, HLS `.m3u8`, generic extractor fallbacks
Magnets → aria2c with selective-file picker. No backend servers anywhere.

### ⚖️ NON-NEGOTIABLE LAWS (user-mandated — violating these ends the session)
1. **Never add AI attribution to commits.**
2. **Never bulk-read `src/downloader.py` (~4200 lines)** or other huge files — grep/sed narrowly.
3. **Verify against the live world before coding.** Log-only guessing has been repeatedly wrong.
4. **Do not waste the user's mobile data.** Page fetches OK; video downloads NEVER without explicit permission. Test harness caps every download at 1KB (capped range probes only).
5. **GitHub builds, not local** — no local Gradle/Android toolchain exists. CI: `.github/workflows/build-apk.yml` builds + tests + emulator smoke on push, releases APK on `v*` tags. Always `gh run watch` after pushing.
6. **Ask before pushing** (user has revoked pushes mid-flight — but has authorized pushes when asked).
7. Things that worked yesterday must still work tomorrow — verify fixes against live sites before shipping.
8. **Subagents must NEVER push.** Push decision is main-agent-only, user-approved.

### 🧰 DIAGNOSTIC LIFEBLOOD
The activity log (`filesDir/logs/app-YYYY-MM-DD.txt`, shared via Settings → "Share Activity Log") records EVERYTHING: USER actions, NET requests, ENGINE transitions, RESOLVE attempts, BACKEND lifecycle, ERROR lines. The user shares these constantly — read them first, they are the primary diagnostic artifact. Categories: `[USER] [NET] [ENGINE] [RESOLVE] [BACKEND] [ERROR] [CRASH]`.

---

## 📌 2. RELEASE STATE (v3.0.3, 2026-08-23)

**Release:** `AnonDownloader-v3.0.3-{arm64-v8a,armeabi-v7a,universal,x86,x86_64}.apk` on GitHub Releases (Latest).

**v3.0.3 contains the full 10-commit LockerRegistry-era stack** (`4927e5d` → `e95d76b`):

```
e95d76b Fix PipelineTest: match >=3 consecutive-fails threshold (live-verified)
f44405f Fix LockerRegistry compile error: qualify all MediaKind references
9311851 Storage permission: prompt for All-files-access on Android 11+, clear engine guard
019dc0e NaijaVault: OTA selector union (kill sidebar junk) + resolution display for HLS
04f4204 Refresh HANDOVER.md for LockerRegistry work (4 local commits) + reverts map
24ce28d Fix naijaprey vdl chain (tokenless wildshare bait) + unseed moviereleases.net
c3893a4 Seed vdl.np-downloader.com + www.moviereleases.net in lockerHosts; conformance JSON search fixes
2ef8688 HostHealth learning + nav-junk refinement + conformance locker-discovery stage
4927e5d LockerRegistry: evidence-based locker discovery (extract everything, arbitrate by evidence)
(plus the pre-4927e5d commits 37694bb, 76b5444, 33b0c73 era — all pushed)
```

**CI findings during the v3.0.3 push (2 real bugs caught — both fixed):**
1. `LockerRegistry.kt` had NEVER been compiled (no local toolchain, first push since creation). All **unqualified** nested references (`return None`, `return Direct`, `return Locker(kh)`, `classify(u) != None`) failed with "Unresolved reference" while qualified ones (`is MediaKind.Direct`) compiled. **Fix (`f44405f`): qualify ALL MediaKind references.** Lesson: never assume a local commit compiles — the first push is the moment of truth.
2. `PipelineTest.hostHealth_backoffWindowAfterFailures_resetsOnSuccess` asserted the OLD threshold (2 fails → backoff). Commit `37694bb` changed the live-verified behavior to **>=3 consecutive fails**. **Fix (`e95d76b`): test updated to match the current logic.**

---

## 🏛️ 3. ARCHITECTURE (key files)

### Core pipeline
- **`pipeline/HostHealth.kt`** — Persistent per-host health ledger (JSON in filesDir). Exponential backoff (30s<<consec-1, cap 1h). **>=3 consecutive failures** opens the backoff window (a single 404/timeout must NOT gate a host — nepu.gd lesson, live-verified). `recordFail` ignores user-initiated cancellations ("Canceled"/"CancellationException"/"abort" via `HttpClient.lastFailure`). `hasProvenLocker(host)` — any host with >=1 successful crack is a known locker. `isUsable(url)` also checks playbook `knownDead`.
- **`pipeline/StreamValidator.kt`** — 1KB Range probe with real download headers; rejects HTML/archive/exec via magic-byte sniffing; `sniff()` is JVM-testable. Throws `PipelineError.ValidationFailed`.
- **`pipeline/ResolveCache.kt`** — In-memory, TTL = `tokenTtlMinutes` from playbook; `keyFor(url, quality)`; engine invalidates before refresh so the 403 self-heal can't be served a stale URL.
- **`pipeline/PipelineJournal.kt`** — Structured `[hop]` lines with ms + page hash; wired into ResolverRegistry.
- **`pipeline/PipelineError.kt`** — Sealed: `SiteDown/HostDead/RateLimited/BlockedIp/TokenExpired/ParseEmpty/ValidationFailed/BudgetExceeded` + `classify(host, lastFailure)`.

### Resolver layer
- **`resolvers/Resolvers.kt`** — 25 resolvers + `ResolverRegistry`. `resolve()` = cache+health wrapper around `resolveInternal` (recursion uncached). `resolveAny(urls, quality, max=3)` races locker candidates concurrently — first winner cancels losers; health-dead hosts filtered before launch. Recursive descent with depth limit 6; `resolveWithRetry` retries only network-class failures (3 attempts).
- **`resolvers/LockerRegistry.kt`** — Evidence-based locker discovery:
  - `classify(url)` → `MediaKind.Direct` (media ext) / `Locker(host)` (playbook-seeded + built-in + **learned via HostHealth.hasProvenLocker**) / `Unknown(host)` (never gated) / `None` (nav junk).
  - **Nav-junk filter**: `NAV_SEGMENTS` set (tag/category/dmca/menu/date/archive/author/cdn-cgi/email-protection/series-download/download-movies/...) + `startsWith("how-to")` + `endsWith("-menu")` + `contains("movies")`. Single-segment paths kept only with media markers (`-episode-`, `season`, `-movie-`, or `-drama` slug with >=2 dashes — `vincenzo-korean-drama` survives, `chinese-drama` doesn't). `/dl-` and deep `/download/` preserved above nav check.
  - `findLockerLinksInHtml(html)` — Jsoup-first (href/data-video/data-src), regex fallback.
  - `resolveCandidates(urls, quality)` — direct passthrough, known lockers race via `resolveAny`, **unknown hosts probed once via StreamValidator** (works on first contact).
  - `isKnownMedia(url)` — classify is Direct or Locker (excludes Unknown; used for curated episode lists).
  - ⚠️ **All MediaKind references must be QUALIFIED** (`MediaKind.None` etc.) — unqualified references fail to compile (CI caught this).
- **`data/rules/DynamicRulesManager.kt`** — OTA playbook: decrypts + **verifies ECDSA P-256 signature** BEFORE using the payload; parses domains/mirrors/sites/resolvers/hostPolicies/urlTemplates/knownDead/tokenTtlMinutes/searchStrategies/lockerHosts/directMediaExtensions/slugSuffixes/countries. `resolveReferer()` is the SINGLE referer source. `getSiteConfig(site).episodeSelector` exists (used by NaijaVault now).

### Provider layer
- **`providers/*.kt`** — Per-site search/drawers/resolveEpisode.
- **NaijaVaultProvider** — Uses `LockerRegistry.classify` for download-link gate. **NEW: prefers the OTA `episodeSelector`** (union with `isKnownMedia` links) — kills ~23 sidebar/comment junk entries per show (live-verified: 1 real link vs 90 all-links). `resolveEpisode`: /dl- pages race ALL locker matches via `resolveAny`.
- **NaijaPreyProvider** — RSS search (regex `<link>` extraction). `resolveEpisode` uses `extractFileLink()` for vdl.np-downloader.com/sdm_downloads gateway → a.sdm_download → wildshare → **NEW: re-routes the tokenless wildshare bait through WildshareResolver** (pt= token → 302 → silversurfer CDN file with download_token). **Regex fix**: `(?![a-zA-Z0-9])` after extension + `&quot;`/`&amp;` entity stripping (webmanifest false-positive).
- **NkiriProvider, RocksProvider (9jarocks), DramaRainProvider** — NO classify usage. **Reverted to committed substring-based gates** after live probes proved classify regressions (DramaRain `/download?link=` single-segment episodes dropped; Nkiri/9jarocks nav-junk pollution). See "Reverts" below.
- **SearchStrategyRunner** — OTA searchStrategies chain (urlTemplate/rss/slugGuess). Nav-junk guard: single-segment paths dropped unless media markers.
- **`providers/ProviderRegistry.kt`** — Search fan-out (7s timeout, 4-min result cache, searchEnabled flag).

### Download engine
- **`engine/DownloadEngine.kt`** — Task state machine. `getDownloadDirectory` → `Download/Anon/<ShowTitle>/`. **NEW: writability guard** — fails fast with an actionable message when storage access is missing. `preflightHls` — probe + rewrite master; **NEW: `pickHlsResolution()`** extracts RESOLUTION from EXT-X-STREAM-INF, picks the variant yt-dlp's height-limited selector lands on (highest <= requested; smallest if nothing fits) → task.resolution → UI chip. `runSizeEstimate` — segment-sampling estimator (real segment sizes, not BANDWIDTH tags). Watchdog (crawl floor 64KiB/60s, rate-drop detector, zombie cap). HLS rewrite (`rewriteHlsMaster`, `resolveSegmentUrl`, `StaleStreamLinkException` → re-resolve on 401/403).
- **`data/models/Models.kt`** — `DownloadTask` has `quality` + **`resolution`** (NEW). `DownloadRecipe` (directUrl/filename/headers/backend/parallelSockets).

### UI
- **`MainActivity.kt`** — **NEW: storage permission flow.** Android 11+ (API 30+): rationale dialog at launch → `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` settings redirect (falls back to app details page if OEM dropped the action); re-checks `Environment.isExternalStorageManager()` on return. Android 9-10: WRITE_EXTERNAL_STORAGE runtime prompt. Also POST_NOTIFICATIONS on API 33+.
- **`ui/screens/DownloadsScreen.kt`** — DownloadCard: **NEW resolution chip** (completed) + resolution first in progress string (active) — e.g. `720p • 45% • 320 MB / 710 MB • 2.4 MB/s`.

### Keys & signing (⚠️ IMPORTANT — moved 2026-08-23)
- **The OTA signing keypair lives at `C:\Users\Anon\anon-serverless-app-maintenance-build\`** — OUTSIDE both git repos (they are PUBLIC). `ota_signing_private_key.pem` (SECRET) + `ota_signing_public_key.pem` + `README.md` explaining everything.
- **GitHub secrets** (serverless repo): `OTA_SIGNING_PRIVATE_KEY` (CI signing), `CONFORMANCE_PUB_B64` (conformance public key).
- `scripts/encrypt_rules.py` (canonical) + `download-toolkit/probe/encrypt_rules.py` (mirror) — KEY_PATH defaults to `~/anon-serverless-app-maintenance-build/ota_signing_private_key.pem`, overridable via `OTA_SIGNING_KEY_FILE` env var.
- **AES `RULES_KEY`** (16 bytes hex, in encrypt_rules.py) — obfuscation-grade encryption only; the key ships in the APK. The SIGNATURE (ECDSA P-256) is the real protection.
- **NEVER commit the private key. NEVER share it.** If it leaks: regenerate keypair (`--gen-keys`), update GitHub secret, ship new APK with new public key.

### OTA Playbook pipeline
- `scraper_rules.json` — plaintext rules (gitignored, NEVER committed).
- `scraper_rules.json.enc` — signed v2 envelope (committed): `{"v":2,"iv":...,"payload":<b64>,"sig":<ECDSA>}`.
- `.github/workflows/ota-rules.yml` — validates the committed .enc on push (signature + schema).
- `.github/workflows/conformance.yml` — MANUAL dispatch only; runs probe/conformance.py (live site traffic from GitHub IPs).

### Conformance
- **`probe/conformance.py`** — Signature-verifying runner (refuses unsigned payloads). 4 stages: search / episodes / direct-pass / **locker-discovery (NEW — reports UNKNOWN HOSTS FOUND with link counts)**. `--search-quality` mode. `classify_media()` Python mirror (20/20 parity verified). JSON searchType: walks link/url fields (accepts relative URLs). RSS: regex `<item><link>` (HTML parser treats `<link>` as void).

---

## ⚠️ 4. REVERTS MAP (do not re-introduce)

The following providers were reverted to committed code after live probes proved classify regressions:
- **DramaRainProvider** — episode links are `/download?link=...` (single-segment path → classify returned None → ALL episodes dropped). Reverted to old category/tag gate.
- **NkiriProvider** — classify gate added 44+ nav-junk entries (`/tag/`, `/dmca/`, `/korean-drama-menu/`) to the episode list. Old substring gate was precise (20 real MKV links).
- **RocksProvider (9jarocks)** — 62 `/date/` archive links added. Reverted.

The classify gate belongs ONLY where the design intends: NaijaVault's download-link filter, `findLockerLinksInHtml`, `resolveCandidates`. Episode-list construction on show pages uses per-provider gates or OTA episodeSelectors.

---

## 📋 5. KNOWN-BROKEN / OPEN (do not re-diagnose from scratch)

1. **vidsrc HLS deep-dive** — nepu TV HLS historically stalled at 0 bytes. Root causes found & fixed (referer suppression for rewritten masters, stale persisted tokens → StaleStreamLinkException re-resolves, variant root-absolute paths). Confirm with new user log.
2. **asianc segment CDN `cdn.jisooido.top` domain-locks** (403 for every referer) — site-side; some episodes undownloadable. premilkyway masters expire within minutes.
3. **naijavault content decay** — most posts expose no links anymore; filevault.com.ng down (Cloudflare 526). Site-side.
4. **nkiri search (thenkiri.com) connect-timeouts** from the user's network — site-side.
5. **dramarain `?s=` search broken server-side** — slug guessing works (OTA searchStrategies).
6. **9jarocks HTTP 522** — transient Cloudflare, not app issue.
7. **seriezloaded.com.ng** — dead domain (DNS NXDOMAIN). Not seeded. Ignore.
8. **USER ACTION PENDING: token rotation** — the old GitHub PAT pasted into chat. Rotate in GitHub Settings > Developer settings > Personal access tokens.
9. **USER ACTION PENDING: phone test of v3.0.3** — install APK from Releases; verify: storage prompt at launch, naijaprey downloads (was broken until 24ce28d), resolution chip on HLS, anitaku/nepu cracks, pluto 20 eps, 9jarocks ordering, dramarain search.
10. **Known minor**: naijaprey show pages emit a `.srt` subtitle link that appears as a dead episode entry (pre-existing, harmless — fails cleanly).

---

## 📋 6. INCOMING AI QUICK-START CHECKLIST

1. `git status` + `git log --oneline -8` — everything on master is pushed (v3.0.3 released). Verify CI: `gh run list --limit 3` should be green.
2. Read `.agents/AGENTS.md` first (operating charter). Then this HANDOVER.
3. **Unbiased review kit exists at `REVIEW_PROMPTS.md`** (root) — 6 focused prompts + a catch-all prompt the user can paste into any AI reviewer. Covers architecture, security, code quality, performance, UX, maintainability.
4. Ask the user for the newest activity log if anything's broken — read `[ERROR]/[RESOLVE]/[ENGINE]` lines first.
5. For any chain failure: probe the live site BEFORE coding (curl_cffi, 1KB Range probes only, ~0.4s pacing, page cap 300KB). Compare against the monolith resolver. The app's resolved URLs must pass StreamValidator semantics (no HTML decoys, no hotlink bait).
6. If debugging a failed download, trace the chain. naijaprey: show → vdl.sdm_downloads → a.sdm_download → wildshare → pt= token (session-bound cookies!) → 302 → silversurfer CDN. Test with a session-based Python script (cookies load-bearing).
7. Never regress these user-facing guarantees: reopen never auto-resumes; pause actually pauses; failures notify with Retry; searches cancel their predecessors; nothing silently consumes data; storage permission is prompted at launch with a settings redirect.
8. **CI lessons:** unqualified nested references in LockerRegistry fail to compile (qualify everything). HostHealth backoff threshold is >=3 consecutive fails (tests must match).
9. Signing pipeline: `python scripts/encrypt_rules.py` finds the key automatically in the maintenance folder. If the private key leaks, rotate the whole keypair + GitHub secret + app-embedded public key.
10. **Never push without asking.** Tag pushes create releases (dynamic tag_name). Watch `gh run watch` after any push.
