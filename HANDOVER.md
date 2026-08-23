# 🚀 ANONRODE DOWNLOAD ENGINE — HANDOVER & ARCHITECTURE DOSSIER

**Target Codebase:** `C:\Users\Anon\download-toolkit-serverless` (Android / Kotlin / Jetpack Compose / OkHttp / libaria2c / youtubedl-android)
**Reference Python Monolith:** `C:\Users\Anon\download-toolkit` (`src/downloader.py`, `src/resolvers.py`, `src/extractors/`) — **the battle-tested reference; the Kotlin app is a port of it, and porting infidelities are the #1 bug source.**
**Operating Charter:** `.agents/AGENTS.md` (Permanent source of truth — never delete)
**Status:** Last pushed commit `689ffee` (v3.0.2 release). Four local commits NOT pushed — **user: DON'T PUSH WITHOUT ASKING.** Latest: `24ce28d` (24ce28d Fix naijaprey vdl chain + unseed moviereleases.net).
**This file replaces the previous handover (which stopped at `689ffee`). Last updated: 2026-08-23 ~22:00 UTC+1.**

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
5. **GitHub builds, not local** — no local Gradle/Android toolchain exists. CI: `.github/workflows/build-apk.yml` overwrites release assets on tag push. Always `gh run watch` after pushing.
6. **Ask before pushing** (user has revoked pushes mid-flight).
7. Things that worked yesterday must still work tomorrow — verify fixes against live sites before shipping.
8. **Subagents must NEVER push.** Push decision is main-agent-only, user-approved.

### 🧰 DIAGNOSTIC LIFEBLOOD
The activity log (`filesDir/logs/app-YYYY-MM-DD.txt`, shared via Settings → "Share Activity Log") records EVERYTHING: USER actions, NET requests, ENGINE transitions, RESOLVE attempts, BACKEND lifecycle, ERROR lines. The user shares these constantly — read them first, they are the primary diagnostic artifact. Categories: `[USER] [NET] [ENGINE] [RESOLVE] [BACKEND] [ERROR] [CRASH]`.

---

## 📌 2. CURRENT STATE — LOCAL COMMITS (NOT PUSHED, NOT IN v3.0.2)

Four commits ahead of the last pushed release (v3.0.2 / `689ffee`):

```
24ce28d Fix naijaprey vdl chain (tokenless wildshare bait) + unseed moviereleases.net
c3893a4 Seed vdl.np-downloader.com + www.moviereleases.net in lockerHosts; conformance JSON search fixes
2ef8688 HostHealth learning + nav-junk refinement + conformance locker-discovery stage
4927e5d LockerRegistry: evidence-based locker discovery (extract everything, arbitrate by evidence)
```

### Commit 1: `4927e5d` — LockerRegistry + NaijaVault swap + OTA field + tests

**What it does:** Extracts locker-host knowledge from per-provider hardcoded lists into a single `LockerRegistry` with evidence-based classification.

**Key files:**
- `resolvers/LockerRegistry.kt` — Central registry, 3 methods:
  - `classify(url)` → `MediaKind.Direct` (known media extension) / `Locker(host)` (OTA-seeded + built-in known hosts, hostname-boundary match — no substring false positives) / `Unknown(host)` (never gated — the evidence-based contract: "extract everything, arbitrate by evidence") / `None` (nav junk: root pages, single-segment paths without media markers, known nav words).
  - `findLockerLinksInHtml(html)` — Jsoup-first (href/data-video/data-src) with regex fallback; nav-junk filtered.
  - `resolveCandidates(urls, quality)` — direct files passthrough, known lockers race via `ResolverRegistry.resolveAny` (max 3 concurrent), **unknown hosts probed once via `StreamValidator`** — valid streams work on first contact, no code path can refuse an unfamiliar link.
- `providers/NaijaVaultProvider.kt` — Replaced hardcoded substring list with `classify(href) != MediaKind.None` for its download-link gate. `resolveEpisode` now uses `resolveAny` over all locker matches (including unseeded hosts like streamsss).
- `data/rules/DynamicRulesManager.kt` — Added `lockerHosts` field parsing + `getLockerHosts()` accessor.
- `scraper_rules.json.enc` — Signed envelope regenerated with 22 seeded locker hosts.
- `test/.../LockerRegistryTest.kt` — 8 JVM tests: classify cases, extraction, nav-junk, OTA seeding.

**Design philosophy:** The old design had each provider maintain its own host list, and any host it didn't know was silently dropped → "0 episodes" on shows using new lockers. The new design: EXTRACT EVERYTHING, ARBITRATE BY EVIDENCE. Every plausible anchor from the episode page is collected (no host filter), classify() sorts by evidence, resolveCandidates() races known lockers and probes unknown hosts. HostHealth records successes so unknown hosts derive their own reputation. The playbook seeds the initial list; the app learns beyond it.

**Nav-junk filter (as of 4927e5d, refined in 2ef8688):** Single-segment paths (count '/' <= 1) → None. This is the baseline; the refinement in the next commit adds the `NAV_SEGMENTS` set and media-marker rules.

### Commit 2: `2ef8688` — HostHealth learning + nav-junk refinement + conformance locker-discovery stage

**Move 2 — HostHealth learning:**
- `HostHealth.hasProvenLocker(host)`: `records[host]?.ok >= 1` → any host that successfully served ≥1 stream is treated as a known locker from then on, no OTA needed.
- `LockerRegistry.classify()` calls `hasProvenLocker` before path heuristics — proven hosts never gate on nav-junk rules.

**Nav-junk refinement:**
- `NAV_SEGMENTS` set: `tag/category/dmca/menu/date/archive/author/cdn-cgi/email-protection/series-download/movie-download/download-movies/...` — exact match, `startsWith('how-to')`, `endsWith('-menu')`, `contains('movies')`. Applied to unknown hosts only (known lockers and proven hosts already returned).
- Single-segment paths kept only when they carry media markers: `-episode-`, `season`, `-movie-`, or a show-style `-drama` slug with ≥2 dashes (`vincenzo-korean-drama` survives, `chinese-drama` doesn't — the latter is a category page).
- `/dl-` and deep `/download/` paths preserved above nav check.
- **Live-verified:** Nkiri show page: old substring gate kept 20 links (all real), new gate kept 36 (same 20 + 16 same-site show links / fragment anchors). 9jarocks: old 2, new 64 (62 `/date/` archive junk). **These were BEFORE the NAV_SEGMENTS fix** — the fix eliminates the /tag/ /date/ /dmca/ /korean-drama-menu/ noise. The remaining same-site noise is additive, not destructive.
- **IMPORTANT: Only NaijaVaultProvider uses `classify` for gating.** NkiriProvider, RocksProvider, DramaRainProvider were REVERTED to their committed substring-based gates after live probes proved classify regressions (DramaRain: `/download?link=` single-segment episodes dropped; Nkiri/9jarocks: 44-62 nav junk entries added). The classify gate stays where the design intends it: NaijaVault's download-link filter, `findLockerLinksInHtml`, and `resolveCandidates`.

**Move 3 — Conformance locker-discovery stage (Stage 4):**
- `probe/conformance.py` adds `classify_media()` mirror (20/20 parity verified against Kotlin), `DEFAULT_LOCKER_HOSTS` + `NAV_SEGMENTS` constants, and `stage_locker_discovery()`.
- `run_site` wires the stage on whichever page was fetched (episodes or strategy-chain fallback). Reports `UNKNOWN HOSTS FOUND` with link counts per host — playbook gaps the app learns via `HostHealth.hasProvenLocker`.
- Fresh conformance run (2026-08-23): surfaced `vdl.np-downloader.com` (5 links, real naijaprey download host) and `www.moviereleases.net` (10 links, later proven to be a release-date tracker — unseeded in commit 3).
- **RSS show-URL extraction fix:** `<link>` is a void element under the HTML parser, so URL text never lands inside the tag. Fixed with regex on raw body (same approach as `extract_titles`).
- **JSON search fix:** naijavault + asianc use JSON search endpoints. `run_site` now walks `link`/`url` fields accepting relative URLs (asianc returns `/drama-detail/vincenzo`).

**Tests:**
- `classify_navJunkIsNone` extended: `/date/archive/`, `/dmca/`.
- `classify_shallowShowSlugsAreUnknown`: single-segment markers survive.
- `classify_knownLockerHosts` fixed: `.mkv` URL triggered Direct (ext check fires first), changed to non-media path to test boundary matching as intended.

### Commit 3: `c3893a4` — Seed vdl.np-downloader.com + www.moviereleases.net; conformance JSON search fix

- `lockerHosts`: 22 → 24.
- Signed envelope regenerated locally.
- Conformance JSON search show-URL extraction (relative URLs, `url` field fallback for asianc).

### Commit 4: `24ce28d` — Naijaprey vdl chain fix + unseed moviereleases.net

**Live-verified chain (2026-08-23, 1KB probes only):**
```
naijaprey show page → vdl.np-downloader.com/sdm_downloads/download-<slug>/ (SDM post)
→ a.sdm_download → wildshare.net/<fileId> → ?pt= token (session-bound cookies!)
→ 302 → silversurfer.wildshare.net/<id>/<name>.mkv?download_token=...
→ 206 video/x-matroska (MKV magic 1A45DFA3)
```

**Bug found and fixed — the app's chain-chaser was returning the wrong URL at TWO points:**
1. `extractFileLink`'s first-match regex hit `site.webmanifest` (a WP favicon link) before the real file, because `.webmanifest` contains `.webm` as a prefix. **Fix:** `(?![a-zA-Z0-9])` after the extension group + strip HTML entities (`&quot;`/`&amp;`) off the tail. Same fix applied to `extractMp4FromHtml` in `Resolvers.kt` (used by GenericLocker/VikingFile/LulaCloud/Embed/FivePlay resolvers).
2. The wildshare page's `.mkv` link is **hotlink bait**: without the `?pt=` token it answers 206 with `text/html` (anti-hotlink). **Fix:** `resolveEpisode` in `NaijaPreyProvider.kt` now re-routes wildshare.net `*.mkv`/`*.mp4` results back through `ResolverRegistry`, where `WildshareResolver` stamps `pt=` and follows the 302 to the tokenized CDN file. The app's persistent cookie jar (`sessionCookieJar` in `HttpClient.kt`) makes the session-bound `pt` token work.

**Unseed `www.moviereleases.net`:** Live probe proved it's a TMDB-driven release-date tracker — the 10 "links" were naijaprey's trailers sidebar widget (YouTube embeds, zero download links anywhere). `classify() → Locker → raced via resolveAny` would have been pointless. Removed from `lockerHosts` (now 23 hosts). Signed envelope regenerated.

**`seriezloaded.com.ng`:** Appeared in dramarain's conformance discovery (1 link). Probed: DNS NXDOMAIN. Not seeded.

---

## 📋 3. ARCHITECTURE (key files)

### Core pipeline
- **`pipeline/HostHealth.kt`** — Persistent per-host health ledger. Exponential backoff (30s<<consec-1, cap 1h). ≥3 consecutive failures triggers backoff window. `hasProvenLocker(host)` — any host that successfully served ≥1 stream is a known locker. Cancellation errors ignored (search typing no longer poisons hosts).
- **`pipeline/StreamValidator.kt`** — 1KB Range probe with real download headers. Rejects HTML/archive/exec. Throws `PipelineError.ValidationFailed`.
- **`pipeline/ResolveCache.kt`** — In-memory, TTL = `tokenTtlMinutes` from playbook. Engine invalidates before refresh.
- **`pipeline/PipelineJournal.kt`** — Structured `[hop]` lines with ms + page hash.
- **`pipeline/PipelineError.kt`** — Sealed class: `SiteDown/HostDead/RateLimited/BlockedIp/TokenExpired/ParseEmpty/ValidationFailed/BudgetExceeded`.

### Resolver layer
- **`resolvers/Resolvers.kt`** — 25 resolvers + `ResolverRegistry`. `resolve()` = cache+health wrapper; `resolveAny(urls, quality, max=3)` races candidates concurrently. Recursive descent (intermediate results re-enter the registry). GenericLockerResolver handles vikingfile/lulacloud.
- **`resolvers/LockerRegistry.kt`** — Evidence-based locker discovery. `classify(url)` → Direct/Locker/Unknown/None. `findLockerLinksInHtml(html)` → extraction. `resolveCandidates(urls, quality)` → racing + probing. `NAV_SEGMENTS` set + media-marker rules for single-segment paths.

### Provider layer
- **`providers/*.kt`** — Per-site search/drawers/resolveEpisode.
- **NaijaPreyProvider:** Uses `extractFileLink()` for vdl.np-downloader.com/sdm_downloads → a.sdm_download → wildshare → MKV. Now re-routes wildshare bait through WildshareResolver (pt= token → 302 → CDN).
- **NaijaVaultProvider:** Uses `LockerRegistry.classify()` for download-link gate. `resolveAny` over all locker matches. **Known issue:** episode list includes same-site sidebar links + #mh-comments fragments (the playbook has a precise episodeSelector but the provider ignores it — uses all-links sweep instead).
- **NkiriProvider, RocksProvider, DramaRainProvider:** NO classify usage (reverted to committed substring-based gates). See "Reverts" section below.
- **SearchStrategyRunner:** Executes OTA searchStrategies chain (urlTemplate/rss/slugGuess). Nav-junk guard: single-segment paths dropped unless they carry media markers.
- **`providers/ProviderRegistry.kt`** — Search fan-out (7s timeout, 4-min result cache, searchEnabled flag).

### OTA Playbooks
- **`data/rules/DynamicRulesManager.kt`** — Signed envelope decryption + field parsing. `lockerHosts`, `hostPolicies` (ordered referer rules), `searchStrategies`, `knownDead`, `tokenTtlMinutes`, `directMediaExtensions`, `slugSuffixes`, `countries`, `mirrors`.
- **`scripts/encrypt_rules.py`** — Canonical pipeline: validate → encrypt (AES-128-CBC) → sign (ECDSA P-256 SHA256) → envelope. Field allow-list, selector/size caps.
- **`scraper_rules.json.enc`** — Signed v2 envelope (git-tracked). Plaintext `scraper_rules.json` is gitignored.

### Conformance
- **`probe/conformance.py`** — Signature-verifying runner (refuses unsigned payloads). 4 stages: search/episodes/direct-pass/locker-discovery. `--search-quality` mode for strict per-query hit testing. JSON search show-URL extraction (walk link/url fields, accept relative URLs). RSS show-URL extraction (regex on raw body).

### Reverts from Move 1
⚠️ **The following providers were reverted to committed code after live probes proved classify regressions:**
- **DramaRainProvider:** Its episode links are `/download?link=...` — single-segment path → `classify()` returned None → every episode dropped. Reverted to old category/tag gate.
- **NkiriProvider:** The `classify != None` gate added 44+ nav-junk entries (`/tag/`, `/dmca/`, `/korean-drama-menu/`) to the episode list. Old substring-based gate was precise (20 real MKV links). Reverted.
- **RocksProvider (9jarocks):** Same story — 62 `/date/` archive links. Reverted.

The `classify` gate stays where the design intends it: NaijaVault's download-link filter, `findLockerLinksInHtml`, and `resolveCandidates`.

---

## 📋 4. KNOWN-BROKEN / OPEN (do not re-diagnose from scratch)

1. **NaijaVault episode-list noise** — The playbook's `episodeSelector` for naijavault is precise (`a[href*='nkiserv'], a[href*='filevault'], '/dl-'`), but the app's `NaijaVaultProvider.loadEpisodes` uses an all-links sweep with `classify != None` gate, producing 23 noise entries (same-site sidebar links + `#mh-comments` fragments) per real download. **Fix:** wire the provider to prefer the OTA episodeSelector. Data-only change (no APK rebuild needed if the OTA field is read — but the code currently doesn't read it for episodes). Small Kotlin change needed for a future iteration.
2. **vidsrc HLS deep-dive** — nepu TV HLS historically stalled at 0 bytes. Root causes found & fixed in earlier commits (689ffee era). Confirm with new user log.
3. **asianc segment CDN `cdn.jisooido.top` domain-locks** — site-side; some episodes undownloadable.
4. **naijavault content decay** — most posts expose no links anymore; filevault.com.ng is down (Cloudflare 526). Site-side.
5. **nkiri search (thenkiri.com) connect-timeouts** — site-side connectivity (user's network).
6. **dramarain `?s=` search broken server-side** — site-side; slug guessing works.
7. **9jarocks HTTP 522** — transient Cloudflare, not app issue.
8. **seriezloaded.com.ng** — dead domain (DNS NXDOMAIN). Not seeded. Ignore.

---

## 📋 5. INCOMING AI QUICK-START CHECKLIST

1. `git log --oneline -6` to see the current local commit stack. All 4 commits are NOT pushed. **Do NOT push without asking the user.**
2. Ask the user for the newest activity log if anything's broken — read `[ERROR]/[RESOLVE]/[ENGINE]` lines first.
3. The `LockerRegistry` design: **extract everything, arbitrate by evidence.** `classify()` never gates on unknown hosts — they get probed once via StreamValidator, and HostHealth records the outcome. The playbook is just a seed; the app learns its own locker list.
4. For the nav-junk filter: `NAV_SEGMENTS` set + media-marker rules. See `LockerRegistry.classify()` for the canonical logic; `probe/conformance.py` has a verified Python mirror.
5. If debugging a failed download: trace the chain. For naijaprey: show page → vdl.sdm_downloads → a.sdm_download → wildshare → pt= → 302 → silversurfer CDN. The app's `extractFileLink` + `WildshareResolver` handle it. Test with a session-based Python script (cookies are load-bearing).
6. Never regress these user-facing guarantees: reopen never auto-resumes; pause actually pauses; failures notify with Retry; searches cancel their predecessors; nothing silently consumes data.