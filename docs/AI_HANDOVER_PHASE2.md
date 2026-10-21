# AI Handover — Phase 2 fixes (2026-10-20)

Read this first, then the charter, then the reports below. This file is the
fast context for an AI session continuing the work. It records what changed,
why, what is proven, and what is still unverified.

## 0. Read-first rules (binding)

1. `C:\Users\user\Anon\ANON TOOLS\download-toolkit-serverless\.agents\AGENTS.md`
   — rules 10–17:
   - reread the senior-engineering-partner skill before every phase;
   - **no workarounds** — fix root causes, prove with tests/live evidence;
   - exhaustive log + server-log + live-recreation protocol;
   - NEPU must support concurrent search + download, real speed, real resume;
   - **proof of fix** (symptom → root cause → fix → test → live evidence);
   - **code-first**: OTA rules are site data only, never the fix; the app must
     work with rev 2/3, no payload, and a deliberately broken payload;
   - user authority: wait for approval before launching agents/fixes, confirm
     live before acting, never spawn duplicate agents, stop when told.
2. `C:\Users\user\.zcode\skills\senior-engineering-partner\SKILL.md`
3. Reports:
   - `C:\Users\user\.zcode\workspace\default\investigation\01_log_error_register.md`
   - `...\02_nepu_root_cause.md`
   - `...\03_sites_posters_media.md`
   - `...\04_9jarocks_season_matrix.md` (18 live pages + verified season rule)
   - `...\05_engine_remaining.md`, `...\05_status_and_remaining.md`

## 1. Project shape

Anonrode Downloader (`com.anonrode.downloader`), Kotlin/Compose Android app.
100% on-device scraping/search/resolution/downloading. The VPS
(68.155.146.145) is only a static OTA host + log/telemetry sink; it never
scrapes. Current released version: 3.3.0 (versionCode 331), tag `v3.3.0` at
`d837f56` (unchanged by this work).

Key paths:
- `app/src/main/java/com/anonrode/downloader/providers/` — site providers,
  `RulesPipeline` (OTA playbooks), `ProviderRegistry`, `SubtitleSurfaces`.
- `engine/` — `DownloadEngine`, `TurboDownloader`, aria2c/yt-dlp wrappers,
  `ProgressWatchdog`.
- `pipeline/` — `LinkResolver`, `ResolveCache`, `ResultVerifier`,
  `UrlClassifier`, `HostHealth`, `HostPolicyRegistry`.
- `resolvers/` — `ResolverRegistry`, Vidsrc/dynamic locker resolvers.
- `data/net/HttpClient.kt` — shared client, cooldowns, `OriginLoadScheduler`.
- `data/rules/DynamicRulesManager.kt` — signed OTA payload.
- `data/models/Models.kt` — `EpisodeItem`, `DownloadTask`, `DownloadRecipe`,
  `SubtitleTrack`.
- `scripts/encrypt_rules.py`, `scripts/publish_rules.py` — payload gates/signing.
- `app/src/test/resources/season/` — 11 captured 9jaRocks pages.
- `app/src/test/resources/anime/` — captured Anitaku AJAX response.

## 2. Repository state (local only — NOT pushed)

Commit chain on `master`:
```
be2456b wip: phase 2 fixes (providers complete, engine in progress)
1952985 wip: phase 2 engine season and resolver completion
b842f69 fix(ota): accept desc-img and disabledSites in payload validation
e06edfa fix(ota): sign rev4 payload locally and update stale gates
```
- Working tree: only `investigation/` is untracked; code is committed.
- `scraper_rules.json` is gitignored (authoring file). Its signed counterpart
  `scraper_rules.json.enc` is committed locally as **rev 4**, version
  `2026.10.20.1`, envelope v2, fleet-pin verified.
- Nothing pushed, no tag moved, no VPS upload.
- Cold OTA keys live outside the checkout:
  `C:\Users\user\Anon\ANON TOOLS\anon-ota-keys`.

## 3. What was fixed — code

### NEPU (root causes)
- Removed the contradictory hardcoded `stream-proxy` handling from
  `UrlClassifier.isStreamingLink` and `LinkResolver.isProvablyDirectFile`.
- Added evidence-based terminal inspection (`UrlClassifier.inspectTerminal`,
  `cachedTerminalEvidence`): 1 KiB probe + bounded body read for
  manifest-shaped responses; direct MP4 vs genuine HLS vs self-referential
  proxy; tiny mpegurl (<1024 B) rejected; self-loop rejected and
  `ResolveCache` poisoned.
- Payload rev 4 binds nsrplay `proxy` from `data.directUrl` (12 h signed
  master) with `data.playUrl` fallback, plus `upref` from `data.referer`;
  vimeos hosts added.
- `resolveAny` now settle-and-scores (~1.2 s) instead of first-valid-wins;
  candidates and winners are episode-pinned (`EpisodeRef`).
- `NepuProvider.srvMapCache` keyed `tmdb|season|episode`; cross-episode
  srvMap literals filtered; same-show duplicate-terminal guard prevents
  Servamp S01E01 == S01E02.
- Banked-partial failover: a task with bytes may fail over when its own URL
  ladder is exhausted; the 90 s resolve timeout no longer parks a banked task.
- Per-origin `OriginLoadScheduler` (search 3 / verify 2 / download 2 / total 4,
  search priority) and scoped `RetryAfterCooldown` (`search`/`verify`/
  `download`): a download-side 429/451 cannot block search.
- Watchdog false positives: crawl verdict requires no growth in the last
  half-timeout (`recentlyActive`); kill counter soft-resets.
- Human error text: killed yt-dlp → `BackendInterruptedException` / friendly
  reason; no empty `ERROR:` aggregates.
- `vidsrc.buzzhttps` URL concatenation repaired; garbage TMDB ids
  (0 / leading zero / >9 digits) refused; Vidsrc `_stream`/mint/proxy inputs
  refused as terminals.

### Season/episode architecture
- `EpisodeItem` and `DownloadTask` now carry explicit `season` / `episode`
  (defaults 0; legacy derives from `episodeNum`).
- `util/SeasonEpisode.kt`: authoritative resolution; 1000-base
  `encode(season, episode)`; legacy 100-base decode only for providers that
  genuinely packed (`dramakey`, `dramarain`, `nepu`, `nkiri`, `pluto`,
  `plutomovies`, `naijaprey`, `naijavault`); 9jaRocks flat numbering excluded.
- `RulesPipeline` season precedence (verified against 18 live pages):
  in-page `SEASON N` markers → page title `Episode N/X-Y Added` declaration
  when anchor count matches → page pin with evidence-gated outlier handling →
  token → position. Explicit season/episode + 1000-base keys; ZIP ranges and
  E5.5 specials preserved; remapped tokens keep the raw label.
- `RocksProvider` compiled parser is authoritative; the OTA pipeline may only
  merge when it carries explicit season identity.
- UI: drawer grouping by explicit season → SxxEyy → season-N → codec; range
  selection matches resolved episode; filenames use explicit season/episode;
  download sorting uses canonical season/episode.
- Tests: `RocksProviderSeasonTest`, `RulesPipelineSeasonMatrixTest`,
  `SeasonEpisodeTest`, `SeasonIdentityTest`. Asserted cases: Ace → S2E1/E2
  only; Link Click → S3E1–E11, no S4; genuine S3+S4 stays split; 150-episode
  S1 stays S1; 10-episode S2 stays S2; Dragon Ball 5 ZIPs; absent payload.

### Posters / providers
- `TrendingFeed.PosterBackfill`: rejects non-image URLs (`.mkv/.mp4/archive/
  page`, locker hosts, `data:`), URL-keyed cache, applied to WP-REST/RSS/API
  search.
- NKiri: WP-REST search first; poster from `_embedded.wp:featuredmedia[0]
  .source_url` (Jetpack field absent live). 9jaRocks: `desc-img` inline RSS
  image (enclosure was 339/339 MKV). NaijaVault: `_embedded` featured media.
  Generic backfill guard so a blank/non-image pipeline poster never wins.
- DramaRain: live `h4.entry-title` search; junk-slug guard (`/the/`); accepts
  and unwraps `download?link=` gateways. DramaKey click-through verified.
- AsianC parser proven on live titles; 2026 empty page documented as a
  site-side data gap (not a parser defect).
- NaijaPrey full-document episode sweep.
- Anitaku: sequential primary → fallback → mirror, 5-min query cache, live
  `.listupd` selectors, blocking calls on IO. `AnimeOracle`: 10-min negative
  cache + single-flight so Kitsu is not re-hit per keystroke.

### Subtitles
- `providers/SubtitleSurfaces.kt`: parses nsrplay/vidsrc resolve JSON
  (`subtitles`/`tracks`), page `<track>`/subtitle anchors; URL-keyed cache;
  `vttToSrt`; OpenSubtitles hash helper.
- `DownloadRecipe.subtitles` + `ResolvedHints` carry tracks across the
  resolve boundary.
- `DownloadEngine.downloadSubtitleSidecars`: up to 3 tracks, 512 KiB cap,
  writes `<video-stem>.<lang>.srt/.vtt/.ass`; a subtitle failure never fails
  the video.
- Player discovers sidecars, maps language names to codes, converts
  VTT↔SRT when extension/body disagree, auto-selects preferred language.
- **Not active:** OpenSubtitles API query (needs an API key decision); only
  source-provided tracks are fetched.

### OTA site toggle (option added; nothing disabled)
- `contentPolicy.disabledSites` string array; lenient on-device parse
  (absent/blank/malformed = all enabled); `DynamicRulesManager.isSiteEnabled`
  / snapshot; `ProviderRegistry.enabledProviders` enforced in search fan-out,
  provider list, drawer loads, failover and resolver sweeps; `getProvider`
  stays unfiltered for in-flight downloads.
- Feed/UI consumers (`SiteGate`, `TrendingFeed`, `CategoryFeed`,
  `AsianDramaFeed`, Home chips) hide disabled sites; a selected disabled chip
  falls back to All Sites.
- Default payload: `"disabledSites": []` — **no site is disabled today**.

### OTA gates
- `scripts/encrypt_rules.py` now accepts `desc-img` / `desc-img:<css>` and
  validates `contentPolicy.disabledSites` (<=64 non-empty strings).
- Rev 4 signed locally (no publish): `scraper_rules.json.enc`, 80,313 bytes,
  sha256 `729b4d99…f4975c`; version `2026.10.20.1`, rev 4.
- Payload content verified by decrypting the committed envelope.

## 4. What is proven

- `scripts/verify_kotlin_syntax.py`: 228 files, zero syntax/brace errors.
- `scratch/adversarial_qa_audit.py`: 141/141.
- `scripts/verify_fcm_static.py`: 46/46.
- `scripts/ci/check_httpclient_request_evidence.py`: 7/7.
- `scratch/test_drop_replace.py` 9/9, `test_work_steal.py` 7/7,
  `test_turbo_handoff.py` pass, `test_zero_redo.py` 11/11,
  `test_nollywood_filter.py` 18/18, `test_url_classifier.py`,
  `test_watchdog_logic.py`, payload lockstep (rev 4).
- Payload decrypt: nsrplay `directUrl` + referer, vimeos hosts, 9jaRocks
  `desc-img`, NKiri REST, NaijaVault embedded poster, DramaRain selector,
  Anitaku admin-ajax, 4 subtitle binds, `disabledSites: []`.
- Compile-risk spots manually reviewed (select/getCompleted imports,
  scheduler lock/Condition, subtitle field names, JSON walker root arrays,
  `resolveAny` named args, enqueue stamping) — no obvious mismatch.

## 5. What is NOT proven / remains

1. **No Kotlin compile and no JVM/Robolectric test execution.** CI is the
   compiler; pushing is currently disallowed. Compile errors are still possible.
2. **No device acceptance.** Not exercised on a phone: NEPU real download
   (speed/selection), search while downloading, the site toggle in the UI,
   subtitle sidecar playback, season chips, poster rendering.
3. **Rev 4 is not published.** GitHub/VPS still serve rev 3. The installed
   APK is the old build, so neither the toggle nor the code fixes are live.
4. **Site-side gaps are not fixable in the app:** AsianC
   `a-certain-dark-item-2026` serves an empty episode list; some hosts return
   451/429/DNS failures. The app now handles them correctly (failover/backoff/
   no search lockout) but cannot invent media that the site does not have.
5. **OpenSubtitles fallback** needs an API key; not active.
6. **OTA rev2→rev3 delivery** was not independently verified.
7. **FCM delivery** and the **HLS matrix** still require a device/port
   exposure.
8. Independent verifier sign-off and the v3.3.0 tag move are not started.

## 6. How to continue (suggested order)

1. Run CI (requires push approval): build, unit/Robolectric, emulator,
   release APK. Fix compile errors in this order if any:
   `RulesPipeline.kt` walkJson root arrays; `Resolvers.kt` settleAndScore;
   `HttpClient.kt` OriginLoadScheduler; `DownloadEngine` subtitle sidecars;
   `NepuProvider` resolveAny named args.
2. Publish rev 4 (GitHub `.enc` + VPS `/ota/scraper_rules.json.enc` and
   `latest.json`) only after approval.
3. Install the new APK; verify on device:
   - NEPU S1E7/S2E7 resolves to a real master, >0 KB/s, no `_stream` loop;
   - search works while a NEPU download runs;
   - `disabledSites` toggles a site off/on with default all-on;
   - subtitles appear as `<stem>.<lang>.vtt/srt` and play;
   - 9jaRocks Ace/Link Click season chips match report 04.
4. Independent verifier signs off every register row; then move the tag only
   if approved.

## 7. Hard constraints

- No workarounds; no OTA-as-fix; no hardcoded secrets; no AI attribution.
- VPS never scrapes third-party sites.
- Do not delete/replace `.agents/AGENTS.md`.
- Desktop deliverables only in `C:\Users\user\Desktop\ANON_DOWNLOADER_FINAL\`.
- Current user instruction: **no push, local commits only**.
