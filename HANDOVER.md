# 🚀 ANONRODE DOWNLOAD ENGINE — HANDOVER & ARCHITECTURE DOSSIER

**Target Codebase:** `C:\Users\Anon\download-toolkit-serverless` (Android / Kotlin / Jetpack Compose / OkHttp / libaria2c / youtubedl-android)
**Reference Python Monolith:** `C:\Users\Anon\download-toolkit` (`src/downloader.py`, `src/resolvers.py`, `src/extractors/`) — **the battle-tested reference; the Kotlin app is a port of it, and porting infidelities are the #1 bug source.**
**Operating Charter:** `.agents/AGENTS.md` (Permanent source of truth — never delete)
**Status:** Latest push `689ffee` — CI ✅ green. Local commits `33b0c73` (OTA rules), `a53c513` (m3u8 fix), `5c6468b` (nkiri/anitaku/asianc), `c37c2d8` (yt-dlp referer split), plus nkiri host failover (pending commit) — **NOT pushed** (user: commit only).
**This file replaces the previous handover (which stopped at `de0a67e`). Last updated: 2026-08-22 ~14:30 UTC+1.**

## 📊 Verification suite (2026-08-22, 50-title × 11 sites)

`probe/verify_batch.py` (monolith repo) — 4-stage pipeline (S1 search / S2 episodes / S3 crack / S4 1KB probe), rules-aware (reads `scraper_rules.json`), 150ms pacing, hard 1KB probe cap. Scorecard (after fix sweep):

| Site | S1 | S2 | S3 | S4 | Notes |
|---|---|---|---|---|---|
| 9jarocks | 48/48 | 8/8 | 8/8 | 8/8 | fully healthy |
| pluto | 45/45 | 7/8 | 7/8 | 7/8 | 1 clip page legitimately linkless |
| torrents | 49/49 | n/a | 8/8 | n/a | 8/8 magnets |
| naijaprey | 47/47 | 8/8 | 8/8 | 6/6→5/6 | chains fixed; dead /d/ links honest 404 |
| anitaku | 40/40 | 8/8 | 8/8 | **0/8 → 4/4** | needs Referer `https://megaplay.buzz/` exactly (engine map now covers watching.onl/anivideo.sbs); worker API path Cloudflare-challenged (app fallback works) |
| asianc | 46/46 | 8/8 | 8/8 | 2/8 → 2/4 | vidbasic 3rdplayer→lisaido.top works with NO referer; jisooido/jiminido dead site-side |
| dramarain | 47/47 | 4/8 | 4/8 | 3/8 | ?s= broken site-side; slugs work |
| nkiri | 0/50 → **5/5** | — | — | proven | original IP 80.82.65.46 IP-range-blocked on this network; switched to Cloudflare mirror **nkiri.top** (partial catalog ~591 posts, no squid game etc.) |
| nepu | 44/44 | 1/3 | 0/3 | 0/3 | vidsrc chain still open (fix-agent infra failed; known hostile) |
| naijavault | 49/49 | 1/8 | 1/8 | 0/8 | decay confirmed; filevault 526 |
| dramakey | 0/25 | — | — | — | dead site — app correctly disabled it |

Key suite-side fixes: `app_resolver.py` broken regex `["\]` (root cause of historical all-None smoke tests), anitaku mirror (`malId/ep` → fetch_download_links → megaplay fallback), Kotlin-faithful streamwish/vidhide ports, naijaprey class-anchor chain (+wildshare//d/ terminal hops), pluto dl-anchor/descent backport, monolith `urlunparse` NameError fix. New files: `probe/verify_batch.py`, `probe/aggregate_scorecard.py`, `probe/results-verify-*.jsonl`, `probe/verify-scorecard.json`.

---

## 🏛️ 1. PROJECT CONTEXT

Anon Downloader = 100% serverless on-device Android downloader. Multi-site search (nkiri, 9jarocks, naijavault, naijaprey, nepu, asianc, pluto, dramarain) → per-site episode drawers → 3-engine downloading:
- **TurboDownloader**: Kotlin OkHttp segmented range downloader (default engine for direct files)
- **aria2c**: via bundled yt-dlp `--downloader libaria2c.so`
- **yt-dlp**: social embeds, HLS `.m3u8`, generic extractor fallbacks
Magnets → aria2c with selective-file picker. No backend servers anywhere.

### ⚖️ NON-NEGOTIABLE LAWS (user-mandated — violating these ends the session)
1. **Never add AI attribution to commits.**
2. **Never bulk-read `src/downloader.py` (~4200 lines)** or other huge files — grep/sed narrowly.
3. **Verify against the live world before coding.** Log-only guessing has been repeatedly wrong.
4. **Do not waste the user's mobile data.** Page fetches OK; video downloads NEVER without explicit permission. Test harness caps every download at 100KB.
5. **GitHub builds, not local** — no local Gradle/Android toolchain exists. CI: `.github/workflows/build-apk.yml` overwrites release assets on tag `v3.0.0` per push to master. Always `gh run watch` after pushing.
6. **Ask before pushing** (user has revoked pushes mid-flight).
7. Things that worked yesterday must still work tomorrow — verify fixes against live sites before shipping.

### 🧰 DIAGNOSTIC LIFEBLOOD
The activity log (`filesDir/logs/app-YYYY-MM-DD.txt`, shared via Settings → "Share Activity Log") records EVERYTHING: USER actions, NET requests, ENGINE transitions, RESOLVE attempts, BACKEND lifecycle, ERROR lines. The user shares these constantly — read them first, they are the primary diagnostic artifact. Categories: `[USER] [NET] [ENGINE] [RESOLVE] [BACKEND] [ERROR] [CRASH]`.

---

## 📌 2. CURRENT STATE AT HANDOVER

Latest commit pushed: **`689ffee`**, CI green. Commit chain: `b3beeaa` → `30402bf` → `b9248ec` → `341069e` → `6db3acf` → `9b5eab9` → `689ffee`.

### ✅ Verified working (live-tested 2026-08-22, probe harness + user logs + live curl)
| Chain | Status |
|---|---|
| nkiri → downloadwella → direct MKV | 22/22 verified |
| 9jarocks → loadedfiles → gfrdaseazzs CDN | 13/13 after fixes; **user log confirms Vincenzo resolved+downloading** |
| Instagram/social (yt-dlp) | always works |
| asianc Vincenzo | **FIXED, user-log confirmed**: Vidbasic mirror-selector delegation → Streamwish(sfastwish) → premilkyway HLS moved real bytes |
| pluto drawer + movies | drawer loads (baseUri fix); movies 10/15 verified; series episode extraction added (`dl.plutomovies.com` anchors on `/series/` pages). `689ffee`: hub/season directory descent live-walked All-American hub → season-8 → s08e07 → dl anchor |
| dramarain gateway→waffi | end-to-end verified (site-side TLS/transient failures remain) |
| naijavault drawer | loads episodes (many posts genuinely lost their links — site decay) |

### ⚠️ Known-broken / open (do not re-diagnose from scratch — this is the map)
1. **vidsrc HLS deep-dive**: nepu TV HLS historically stalled at 0 bytes. Root causes found & fixed: segment CDNs 403 ANY Referer (referer suppression added for rewritten masters), stale persisted tokens 403-loop (StaleStreamLinkException re-resolves), variant root-absolute paths handled. **Next user log will show yt-dlp's own stderr per attempt** (instrumentation added) — confirm the stall is gone.
2. **asianc segment CDN `cdn.jisooido.top` domain-locks** (403 "domain forbidden" for every referer) — site-side; some episodes undownloadable. premilkyway masters expire within minutes. Site increasingly hostile.
3. **naijavault content decay**: most posts expose no links anymore; filevault.com.ng is down (Cloudflare 526). Site-side.
4. **asianc hglink.to secondary mirrors all dead** ("file expired") — site-side; primary vidbasic path works now.
5. **nkiri search (thenkiri.com) connect-timeouts from the user's network** — site-side connectivity.
6. **dramarain `?s=` search broken server-side** (2026-08-22): "Nothing Found" even for titles its own sidebar links to. App-side slug guessing now covers category-suffixed slugs (`-chinese-drama/-thai-drama/-japanese-drama/-philippines-drama`, live-verified 200); Korean-title searches stay empty — the site has no Korean section, that's correct behavior.
7. **Monolith bug**: `src/resolvers.py` ~1546 Vidhide uses unimported `urlunparse` → NameError (reference-side only).
8. **Log-share filename staleness**: the Aug 21 23:20 session was shared as `app-2026-08-13.txt` (content timestamps authoritative). Cosmetic, uninvestigated.

---

## 🏛️ 3. ARCHITECTURE (key files)

- **`engine/DownloadEngine.kt`** — task state machine; resolution gate (`isDirectMediaUrl` / `isKnownLockerHost` / `isProvablyDirectFile`); routing; watchdog (crawl floor 64KiB/60s, rate-drop detector, zombie cap 4×stall+<1MiB); HLS preflight+rewrite (`preflightHls`, `rewriteHlsMaster`, `resolveSegmentUrl`, `StaleStreamLinkException` → re-resolve on 401/403); failure notifications; registry-recursion consumer.
- **`engine/YoutubeDlDownloader.kt`** — yt-dlp wrapper: attempt loop w/ resume, **per-attempt stderr logging**, `hlsMasterFile` (file:// + --enable-file-urls), referer suppression for rewritten masters.
- **`engine/DownloadRepository.kt`** — JSON persistence; **reopen NEVER auto-resumes** (parking markers cleared on init).
- **`resolvers/Resolvers.kt`** — 25 resolvers + `ResolverRegistry` (**now recursive** — intermediate results re-enter the registry, Python resolvers.py:2185 parity) + helpers. `isDirectMediaUrl` also accepts hyphen-extension forms (`…s01e19-mp4`, dl.plutomovies.com style) — engine routes them Turbo/aria2c and recursion stops there; PlutoMoviesResolver descends hub→season→episode directories when no anchor extracts.
- **`providers/*.kt`** — per-site search/drawers/resolveEpisode. NaijaPreyProvider chases vdl.np-downloader.com/sdm_downloads → a.sdm_download anchor → wildshare → direct MKV.
- **`data/net/HttpClient.kt`** — shared client + cookie jar (LOAD-BEARING for locker chains), search-call tagging (`cancelTagged("search")` spares live searches), `probe()` (headers-only reachability), capped body reads (3MB text / 5MB bin).
- **`providers/ProviderRegistry.kt`** — search fan-out (7s timeout, 4-min result cache, `searchEnabled` flag — dramakey disabled), episodesCache 5-min.
- **`service/DownloadService.kt` + `RetryReceiver.kt`** — progress/complete/failure notifications; failure ones carry a Retry action.
- **`util/DebugLog.kt` + `util/CrashHandler.kt`** — always-on journal; crashes land in it.
- Probe harness lives in the monolith repo: `C:\Users\Anon\download-toolkit\probe\`.

---

## 📋 4. THE AUDIT SYSTEM (regression alarm — use it!)

`C:\Users\Anon\download-toolkit\probe\probe_harness.py`: searches real titles per site → resolves → downloads ONLY the first 100KB (Range-capped) → verifies magic bytes (MP4 `ftyp`, MKV `1a45dfa3`, TS `0x47`). Usage:
```bash
cd C:/Users/Anon/download-toolkit/probe
python probe_harness.py --site nkiri --max 15 [--mode app]
```
Results → `results-<site>-<type>.jsonl`. `app_resolver.py` mirrors the FIXED Kotlin logic in Python (`--mode app`) so app algorithms can be validated without an Android build — **INCOMPLETE: smoke test returned None everywhere; known bug fixed was `_get` no-redirect body-read; needs debugging** (monolith resolves the same URLs fine).

Harness quirks learned: 9jarocks RSS carries URLs in `<link>` elements (not hrefs); pluto emits relative hrefs; dramarain show slugs look like categories (`<title>-chinese-drama/`) so category-skips must be anchored (`^https://dramarain.com/[a-z]+-drama/$`); nepu api/search dropped its `url` field (build watch URLs from id+media_type); asianc emits relative episode hrefs.

---

## 📋 5. INCOMING AI QUICK-START CHECKLIST
1. `git status` (master, clean) and `gh run list --limit 2` — confirm latest CI green.
2. Ask the user for the newest activity log if anything's broken — read `[ERROR]/[RESOLVE]/[ENGINE]` lines first; the instrumentation now shows yt-dlp stderr per attempt and rewritten-master dumps.
3. For any chain failure: run the probe harness for that site BEFORE coding; compare against the monolith resolver (parity diff); check `audit-*.json` reports in the probe folder.
4. vidsrc chain reference (as of today): watch page → iframe vidsrc.mov/embed → `data.vidsrcme.ru/api.php?type=tv|movie&tmdb=N[&season&episode]&stream_urls` → wasm ChaCha20 decrypt (rotating wasm module) → playlist on rotating `<name>.site/.space` domains → generate.php JWT (IP-bound, hours) → master.m3u8?token → variants → segments (403 ANY Referer). Movies often null = genuinely no stream. Tokens die fast → always re-resolve on 401/403.
5. Never regress these user-facing guarantees: reopen never auto-resumes; pause actually pauses; failures notify with Retry; searches cancel their predecessors; nothing silently consumes data.

## 🏛️ Architecture Upgrade (2026-08-23): Kernel + OTA Playbooks v2 + Reliability Engine

**Status line update:** local commits `cbb8e61` (torrent shield hardening), `4f115b6` (playbooks v2 + signing), `74cf53a` (pipeline), `b4b0e91` (conformance + search chains), `ee29857` (log retention), `c31f789` (search-quality), `c149b68` (pluto leak) — **NOT pushed** (user: don't push yet).

### What changed and where
1. **Signed OTA pipeline** — `scraper_rules.json.enc` is now a v2 envelope `{"v":2,"iv":<random>,"payload":<b64>,"sig":<ECDSA-P256>}`. `DynamicRulesManager.decryptRules` verifies the signature BEFORE decrypting; v2 unsigned/tampered payloads refused; legacy fixed-IV payloads still parse (migration). Signing private key = GitHub secret `OTA_SIGNING_PRIVATE_KEY` (SET live 2026-08-23); public key embedded in manager (`rulesSigningPubB64`). Workflow `ota-rules.yml` regenerates+seals the payload on `scraper_rules.json` pushes. Canonical tooling: `scripts/encrypt_rules.py` (strict schema validation incl. field allow-list, selector/size caps). Mirror: monolith `probe/encrypt_rules.py` (keys in gitignored `ota_keys/`).
2. **Host policies as data** — `hostPolicies[]` (ordered match→referer rules) in the playbook; `DynamicRulesManager.resolveReferer()` is the SINGLE referer source. `DownloadEngine.getRefererForUrl` is now a one-line delegate. The old hardcoded map lives on as `DEFAULT_HOST_POLICIES` fallback. Also new playbook fields: `urlTemplates` (nepu /watch rebuild), `knownDead` (jisooido etc.), `tokenTtlMinutes`, `searchStrategies` (ordered fallback chains per site; `SearchStrategyRunner` executes urlTemplate/rss/slugGuess; dramarain wired).
3. **pipeline/ package** — `PipelineError` (typed: SiteDown/HostDead/RateLimited/BlockedIp/TokenExpired/ParseEmpty/ValidationFailed/BudgetExceeded + classify()), `PipelineJournal` (structured `[hop]` lines with ms + page hash; wired into ResolverRegistry), `HostHealth` (persistent per-host ok/fail/429 + exponential backoff, seeded by knownDead; init in AnonApp), `ResolveCache` (token-TTL in-memory cache; `resolveStreamUrl` invalidates before refresh so the 403 self-heal can't be served a stale URL), `StreamValidator` (1KB ranged pre-enqueue check with REAL download headers; wired in engine direct path; throws PipelineError.ValidationFailed).
4. **ResolverRegistry** — resolve() = cache+health wrapper around resolveInternal (recursion uncached); `resolveAny(urls, quality, max=3)` races locker candidates, first winner cancels losers. NaijaVault adopted it (multi-locker /dl- pages).
5. **Conformance** — `probe/conformance.py` (serverless repo): signature-verifying runner (refuses unsigned payloads); stage sweep + `--search-quality` mode (strict hit = parsed-title fuzzy match; 24-query curated set + `probe/search_queries_fixture.json` 232-query fixture). Workflow `conformance.yml`: MANUAL dispatch only (live site traffic from GitHub IPs). Local run 2026-08-23: all sites OK except dramarain search (known decay; slug-guess OTA chain is the fix) + 9jarocks/naijaprey RSS "100% hits incl. fake titles" = junk-feed signal (RelevanceScorer filters app-side).
6. **Log retention user setting** — Settings > Diagnostics "Keep Activity Logs" 1–30 days (default 7), applied live via `DebugLog.configureRetention`.

### Known-open items
- anitaku + nepu "Could not crack stream link" — debug prompt handed to Antigravity (screenshots 2026-08-22 21:xx).
- 9jaRocks episode grouping/sorting (2-ep posts, newest-first).
- KPop tasks stuck PAUSED at small sizes — undiagnosed.
- Pluto leak FIXED locally (c149b68): episode links scoped to same series id/slug.
