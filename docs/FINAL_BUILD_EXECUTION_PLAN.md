# FINAL BUILD — MASTER EXECUTION PLAN
**Target release: v3.3.0 (versionCode 331) — platform surfaces + hardening + every audit finding, in ONE build.**
Status: PLAN — awaiting the maintainer's "proceed". Created 2026-10-18. Owner: main agent (orchestrator).
Companion docs: `scratch/app_platform_features_design.md` (platform research), `scratch/audit_app_subsystems.md`,
`scratch/audit_app_wiring.md`, `scratch/activity_log_audit.md`, `docs/UI_OVERHAUL_DIRECTIVE_VETTED.md`.

---

## 0. Operating protocol (read first)

- **No local Gradle, ever.** Static gates locally (syntax gate, Python sims, adversarial suite, payload dry-run).
  The ONE real compile is CI at the end (push → `build-apk.yml`), and fixing its errors is part of this plan.
- **Subordinates make the edits; I review every phase boundary.** Agents never commit, never push, never run Gradle.
- **Python-first for new logic:** every new pure-logic block (gate evaluation, version compare, frequency caps,
  engagement scheduling math, payload parsing) is simulated in `scratch/` BEFORE/ALONGSIDE the Kotlin port.
- **Test contracts are frozen:** `DownloadCardStateTest`, `DownloadStatusStyleTest`, `DownloadsSorterTest`,
  `SplashMotionTest`, `SubtitleAutoPickTest`, `RegexEngineCompatTest` keep their pinned symbols/strings.
- **Charter rules stand:** zero AI attribution anywhere; never delete/replace `.agents/AGENTS.md`; live probes
  ≤1024 bytes; VPS is a static-serve/telemetry sink only; data budget 300 MB (currently ~30 MB used).
- **Maintainer deliverables live in `C:\Users\user\Desktop\ANON_DOWNLOADER_FINAL\`** — never saved loose on the
  Desktop (convention set 2026-10-18; the existing 16 deliverables have been moved into that folder).
- **Every phase ends with:** syntax gate clean + agent report + my line-level diff review + explicit "phase N
  approved" note appended to §14 of this document.

---

## 1. Mission

One final build that ships, together:
1. **Remote messaging he can send at will** — arbitrary title/body announcements (popup, sheet or banner),
   dismissible, percent-rollable, OTA-controlled, no APK rebuild to send.
2. **Soft update notice + true in-app update flow** — "update available" → download in-app → SHA-256 verified
   → install (FileProvider + `REQUEST_INSTALL_PACKAGES`), with release-page fallback.
3. **Force-obsolete gate** — a message that forces users onto the new version (soft-warn and hard modes,
   brick-safety rails, escape hatch), fully remote.
4. **Re-engagement** — "it's been a while" notifications + welcome-back in-app card, remote-controlled.
5. **Ads readiness** — slot architecture + house ads (which double as another arbitrary-message surface),
   OTA toggled, zero ad-SDK lock-in.
6. **Anti-decompile / anti-mod mitigation** — realistic layered hardening (details §5), honestly documented.
7. **Every open audit finding fixed** — subsystems F-01..F-15, wiring P1/P2s, log-audit still-reachable bugs.

---

## 2. Decisions (the 10 open questions, answered — and additional senior calls)

| # | Question | Decision |
|---|---|---|
| 1 | Retroactive scope of gate/announcements | **Ship the groundwork NOW in v3.3.0** — it is the first version that can ever be gated; fleet-forward accepted and documented. |
| 2 | `REQUEST_INSTALL_PACKAGES` | **YES, add it.** Default action = in-app install (verified APK); secondary = open release page. Documented one-time "allow installs from this source" UX. |
| 3 | Gate aggressiveness | **Default = soft** (dismissible, 24h snooze). Hard mode only when the payload explicitly sets `force.mode:"hard"`. **`escapeHatch: true` is the house default** (§4.5 rails) — never a brick. |
| 4 | APK hosting | **FULL dual hosting — not optional.** BOTH GitHub Releases AND the VPS static mirror ship for real: `scripts/publish_apk.py` mirrors `arm64-v8a` + `universal` APKs + a `latest.json` (version, size, SHA-256) onto the box; the `update` payload carries BOTH source bases; `UpdateManager` walks sources in order (VPS first — it is the fast, unmetered pipe; GitHub fallback) with the **same SHA-256 pin from the signed payload on every attempt**, so neither source can serve a wrong/fake binary. Mirroring is a release-day step, verified by re-downloading from both sources. |
| 5 | Sync cadence | Keep 24h floor; **add resume-triggered refresh when >6h since last sync** (small `AnonApp`/lifecycle change) so announcements/gates land timely. |
| 6 | FCM | **NO.** Confirmed — no GMS dependency; local notifications + AlarmManager cover re-engagement. |
| 7 | Ads network | **Readiness + house ads now; AdMob/AppLovin deferred.** Tripwire documented (revisit only if a store listing exists or the maintainer explicitly accepts GMS + AD_ID + EEA consent + app-ads.txt gap). |
| 8 | Release-key hygiene | CI already REFUSES unsigned releases (`build-apk.yml` signing guard) — good. Release notes will state: debug-build users must uninstall/reinstall to move to signed builds. |
| 9 | Copy ownership / caps | Maintainer writes all texts; validator caps **title ≤80 chars, body ≤300 chars**; templates in `docs/PLATFORM_OPERATIONS.md`. |
| 10 | Telemetry additions | **YES** — new event types on the existing encrypted sink, same caps/aggregate-only discipline: `announcement_shown/dismissed`, `update_flow`, `gate_shown`, `engagement_sent`, `tamper_detected`, `ad_impression` (id-only). |
| 11 | Version | **v3.3.0 / versionCode 331** (current repo: 330 / 3.2.11). Tag `v3.3.0` at release. |
| 12 | Queue order (F-12) | Comment says FIFO, `addFirst`+`firstOrNull` makes **LIFO**. Senior call: **keep behavior, fix the comment** + one unit test pinning current order — changing scheduler semantics under a no-compile constraint is unjustified risk. |
| 13 | F-04 payload `sites` reserved fields | Do NOT fake readers. Mark the unread fields **"reserved"** in the ops docs + payload validator emits an INFO note; remove nothing (harmless data). |
| 14 | F-03 `urlTemplates` | **Wire it for real**: `NepuProvider` reads `getUrlTemplate("nepu")` with the hardcoded shape as fallback. |
| 15 | F-08 `AppSettings` duplicate | Collapse to a single source: player's `AppSettings.load().subtitleLanguage` migrates to a direct pref read helper; the data class shrinks to what is actually used (or is deleted if nothing remains). |
| 16 | Future-capability pre-wiring | **Ship every future SDK/adapter DISABLED in v3.3.0** so later registration is an OTA/config flip wherever the vendor accepts runtime credentials (§4.6 honest matrix; AdMob manifest app-id = the one possible two-line rebuild). Cost accepted: +2–4 MB APK + GMS-inert code paths + visible AD_ID permission. |

---

## 3. Workstream A — audit-fix backlog (owner / phase / exit criteria)

### A1. Subsystems findings (`scratch/audit_app_subsystems.md`)

| ID | Fix | Owner · Phase | Exit criteria |
|---|---|---|---|
| F-01 | Wire `debugLogging` → `DebugLog.setVerbose` (call at engine loadPreferences/saveAllSettings + an immediate apply) | S2 · P1 | Setting flips verbose live; `trace()` emits; default OFF in release |
| F-02 | Call `NoSourceLedger.recordUnresolved` at the real no-source sites (resolver-empty/failover-exhausted paths) | S2 · P1 | Ledger records; 3 seeds still work; TTL/clear unchanged |
| F-03 | `NepuProvider` uses `getUrlTemplate("nepu")` (fallback = current hardcode) — see Decision 14 | S2 · P1 | Payload edit changes the URL shape fleet-wide |
| F-04 | Reserved-field INFO note in validator + docs table; no fake readers — see Decision 13 | S1 · P1 | Dry-run prints the note; ops doc lists reserved fields |
| F-05 | Ship `flags` in the payload (families for the 4 new sections + `pipelines` shadow off) | S1 · P1 | Payload dry-run passes; flags parse; families gate (unit-tested) |
| F-06 | Delete `providers/TmdbPosterResolver.kt` | S2 · P1 | Zero refs, builds (CI) |
| F-07 | Remove `SiteProvider.requiresSingleSocket` | S2 · P1 | Zero refs |
| F-08 | `AppSettings` consolidation — see Decision 15 | S2 · P1 | One settings source; player still reads sub language |
| F-09 | `TaskRuntimeRegistry.forget` on the 4 terminal-FAILED paths + startTask guard-fail | S2 · P1 | Every terminal path forgets; sim proves pairing |
| F-10 | Doc §9 "capped to 1" → mark superseded by §12 (audit builder text) | Me · P3 | Doc regenerated without the stale line |
| F-11/F-14/F-15 | Informational — no change (recorded) | — | — |
| F-12 | Queue-order comment fix + pinned test — see Decision 12 | S2 · P1 | Comment matches code; test documents LIFO |
| F-13 | Syntax gate covers ALL main Kotlin files (120) + all tests; add Kotlin unit tests: `RulesFlags`, `ContentPolicy` parse, rev monotonicity, `NollywoodFilter`, `UpdatePolicy` evaluation | S1 · P1 (gate+parser tests) / S3 · P2 (filter+update tests) | Gate runs 120+54 files; new tests exist (run in CI) |

### A2. Wiring findings (`scratch/audit_app_wiring.md`)

| Finding | Fix | Owner · Phase |
|---|---|---|
| P1 drawer preselect-remaining never passed | **DONE (01c28ca)** | — |
| P1 clipboard toggle inert | **DONE (01c28ca)** | — |
| P1 settings snapshot clobber | **DONE (d032de7)** | — |
| `MainActivity.handleShareIntent` unreachable | Delete the dead method (QuickShareActivity is the real share surface) | S3 · P1 |
| "Parallel sockets" has no UI control | Add the missing control in the Settings → Network drawer (engine field + pref already wired) | S3 · P1 |
| `cardStatusDetail` dead + lying KDoc | Delete; fix KDoc references (test files don't touch it — verified) | S3 · P1 |
| `downloadEpisode`/`downloadAllEpisodes` dead | Delete both | S3 · P1 |
| Four definitions of "active" in Downloads | Unify to ONE helper in `DownloadsSorter` (chips/header/badge); keep pinned test strings intact | S3 · P1 |
| Dead resources (`ic_anon_mark`, `@color/black|white`, `app_name` fallback, `pref_window_brightness` pair) | Remove after a zero-ref grep proof | S3 · P1 |
| Debug/release coexistence | Verify `app/build.gradle`: add `applicationIdSuffix ".debug"` to the debug buildType if absent (debug + signed release install side-by-side; FileProvider authority is `${applicationId}`-scoped — verify); release notes carry the Decision-8 uninstall line for anyone on an old debug-signed build | S4 · P1 |
| Genre-tile pipeline orphan | **DONE in d032de7** (live fallback) | — |
| 41-field `HomeUiState` orphan table | Re-audit after P1 fixes; delete orphan fields only with zero-reader proof | S3 · P2 |

### A3. Activity-log still-reachable bugs (`scratch/activity_log_audit.md`)

| Bug | Fix | Owner · Phase |
|---|---|---|
| `UpdateChecker.kt:49` points at the dead `:8000` mirror | Repoint to `:80` (same host) **and unify with the new `UpdateManager`** so there is exactly ONE update path | S2 (repoint) / S4 (unify) · P1/P2 |
| Cold-start `data.vidsrcme.ru` ConnectException wave (~30/cold start) | Seed into the compiled dead-host list + `knownDead` payload entry; verify the in-session dead-flag persists across cold starts (pref-backed flag) | S2 · P1 |
| HLS branch accepts a 1-byte `.m3u8` terminal | Extend the terminal-size gate (1024-byte class) to the HLS branch + sim | S2 · P1 |
| Watchdog-kill / CanceledException / stub / linkage classes | Already fixed in un-pushed tree — record as regression-tested via the audit report reference | — |

---

## 4. Workstream B — platform surfaces (the part that makes messages/updates/ads real)

### 4.1 Payload schema (exact; added to `scraper_rules.json`, validated in lockstep)

```json
"announcements": [
  { "id": "2026-10-18-a", "title": "≤80 chars", "body": "≤300 chars",
    "mode": "dialog|sheet|banner", "action": "none|url|update",
    "actionLabel": "Update now", "actionUrl": "https://…",
    "oncePer": "id|rev", "minApp": 0, "expiresAtMs": 0 }
],
"update": {
  "latestVersionName": "3.3.0", "latestVersionCode": 331,
  "sources": [
    { "kind": "vps",    "base": "http://68.155.146.145/ota/apk/" },
    { "kind": "github", "base": "https://github.com/anonrode/download-toolkit-serverless/releases/download/v3.3.0/" }
  ],
  "apkArm64": "AnonDownloader-3.3.0-arm64-v8a.apk",
  "apkUniversal": "AnonDownloader-3.3.0-universal.apk",
  "sha256Arm64": "<hex>", "sha256Universal": "<hex>",
  "message": "≤300 chars",
  "force": { "enabled": false, "minSupportedVersionCode": 0, "mode": "soft|hard",
             "snoozeHours": 24, "escapeHatch": true }
},
"ads": { "enabled": false, "provider": "house|none", "placements": ["downloads_shelf","home_footer"],
         "frequencyPerDay": 2,
         "house": { "title": "…", "body": "…", "actionLabel": "…", "actionUrl": "…", "id": "h-1" } },
"engagement": { "enabled": false, "inactiveDays": 7, "cooldownDays": 14,
                "title": "…", "body": "…", "welcomeBack": true }
```

Rules: sections absent = feature OFF (pure pass-through); unknown fields rejected by the validator (existing
discipline); `flags.<family>` (announcements/update/ads/engagement) gate percent/shadow/kill like every family.

### 4.2 Client modules (new; owners)

| Module | Purpose | Owner |
|---|---|---|
| `DynamicRulesManager` extensions | Parse + typed getters for the 4 sections (`getAnnouncements()`, `getUpdatePolicy()`, `getAdsConfig()`, `getEngagementConfig()`) | S1 · P1 |
| `RulesFlags` + `encrypt_rules.py` | 4 new family ids in lockstep (client list == validator list) | S1 · P1 |
| `update/UpdateManager.kt` | Version compare vs `BuildConfig.VERSION_CODE`; ABI pick; download to cache with progress; **SHA-256 verify against the payload pin on EVERY source attempt**; source-walk (VPS → GitHub); FileProvider URI; `ACTION_VIEW` install intent; unknown-sources redirect; release-page fallback; telemetry `update_flow` | S4 · P2 |
| `update/AnnouncementManager.kt` | Active-announcement selection (oncePer id/rev, minApp, expiry), shown-store (prefs) | S1 · P1 (logic) + S3 · P2 (UI) |
| `update/EngagementScheduler.kt` | Last-open stamp; `AlarmManager` (inexact, `setInexactRepeating`-free: one-shot + re-arm); boot receiver re-arm; channel (LOW importance); dedupe cooldown | S4 · P2 |
| `util/IntegrityCheck.kt` | Runtime signing-cert SHA-256 vs payload pinned expected; verdict → telemetry `tamper_detected` (+ Settings diagnostics row) | S4 · P1 |
| `ui/components/AnnouncementHost.kt` | Renders dialog/sheet/banner per payload; dismiss/snooze/action | S3 · P2 |
| `ui/components/UpdateGate.kt` | Soft card / hard dialog with `Update now` (in-app install) + escape hatch; never renders if payload evaluation is stale (>14 days) | S3 · P2 |
| `ui/components/AdSlot.kt` + house provider | Slot composable + `HouseAd` render (uses `PosterFallbackWell`-grade styling); no ad SDK | S3 · P2 |

### 4.3 Integration points (file:line targets)

- `AnonApp.kt` (OTA sync块): after each successful rules activation → IntegrityCheck.run + UpdateManager.refresh + AnnouncementManager.pick + EngagementScheduler.arm. Resume-refresh (>6h) hook (Decision 5).
- `MainActivity.kt` root overlay: `AnnouncementHost` + `UpdateGate` above the Scaffold (same layer as existing modals).
- `MainScaffold.kt`: banner-slot anchor for `mode:"banner"` announcements + `home_footer` ad slot anchor.
- `DownloadsScreen.kt`: `downloads_shelf` ad slot (under the shelf row).
- `SettingsSheet.kt` → About card: version row, "Check for updates" (calls UpdateManager manually), diagnostics rows (tamper verdict, last sync, rules rev).
- `AndroidManifest.xml`: `REQUEST_INSTALL_PACKAGES`; `RECEIVE_BOOT_COMPLETED` + boot receiver for the engagement re-arm; engagement channel is runtime-created (no manifest change).

### 4.4 "Any message I want" — the operational contract

He writes plain JSON (or uses the templates in `docs/PLATFORM_OPERATIONS.md`), runs
`python scripts/publish_rules.py --dry-run` (validator enforces caps + schema) then `--write-repo`
and/or `--sync-box`, and the fleet picks it up on the next sync (≤24h, or on next resume >6h).
Any of: announcement (3 display modes), house ad text, update message, force-update message.
Kill switch for each surface = remove the section or `flags.<family>.enabled:false`.

### 4.5 Brick-safety rails (non-negotiable)

1. Hard gate NEVER renders when: payload older than 14 days, evaluation failed, or `minSupportedVersionCode` is 0.
2. `escapeHatch: true` default = long-press the gate title 5× → "Continue anyway (not recommended)" → logs an
   `gate_bypass` telemetry event (we SEE bypasses; abuse is measurable, not silent).
3. The gate blocks UI only — background downloads continue (updates are not hostage to a slow download).
4. Every new surface is percent-rollable via `flags` (5 → 25 → 100) with the existing telemetry as the watch signal.
5. Rollback = republish the previous payload rev (existing mirror/rollback discipline).

### 4.6 Future-capability pre-wiring — "register later, activate by OTA" (honest matrix)

Rule stated plainly: **OTA can ship CONFIG and CREDENTIALS, never CODE.** So v3.3.0 compiles every
future SDK + adapter in, DISABLED, with every credential exposed as a payload field; activation later =
payload edit only where the vendor accepts runtime credentials.

| Capability | Ships in v3.3.0 (disabled) | Credential source later | Pure-OTA activation? |
|---|---|---|---|
| AppLovin | `applovin-sdk` + adapter; key = payload field | AppLovin dashboard SDK key (runtime API) | **YES — fully OTA** |
| Unity Ads | `unity-ads` + adapter; gameId = payload field | Unity dashboard gameId (runtime API) | **YES — fully OTA** |
| AdMob | `play-services-ads` + UMP adapter; unit IDs = payload fields | AdMob account: ad unit IDs (runtime strings) + **App ID** | **PARTIAL — honest:** current SDKs read the App ID from manifest meta-data (the old runtime app-id overload is deprecated). I verify against the pinned SDK version during implementation; if it is truly manifest-only, registration costs ONE two-line rebuild — every other AdMob field stays OTA |
| FCM | `firebase-messaging` + registrar using **manual `FirebaseOptions` (deliberately NO google-services.json)** | Firebase project: apiKey/appId/senderId/projectId = payload fields | **YES — fully OTA** (the manual-options path is exactly why we skip the build-time file) |
| Play Integrity | `play-integrity` + client attestor + VPS verify hook | Google Cloud project + Play Console link (server-side; not a client credential) | **PRE-WIRED, honestly caveated:** enabling needs the server-side project + verify endpoint; verdict strength on sideloaded apps is weak — enable only if you decide you want it |
| PackageInstaller session install | **Implemented now** — framework API, zero registration, zero deps — primary install path with `ACTION_VIEW` fallback | none | n/a — nothing to register |

Honest costs of pre-wiring (stated because "don't be lazy" cuts both ways):
- APK size + roughly 2–4 MB across the SDKs (exact figure measured and reported at phase 2).
- GMS-dependent code paths compiled in but **inert while disabled** (nothing initializes).
- `AD_ID` permission becomes visible in app-info (required by the ads SDKs; harmless while ads are off).
- Registration day = fill the payload fields, `publish_rules.py --dry-run`, publish. The ops doc gains a
  **"Registration rollout"** chapter with exact per-service steps + copy-paste payload recipes.

---

## 5. Workstream C — anti-decompile / anti-mod mitigation (honest layering)

| Layer | Change | Owner · Phase | Honest limit |
|---|---|---|---|
| R8 (release) | `minifyEnabled true`, `shrinkResources true`, `-repackageclasses`, `-allowaccessmodification`, full keep-rule audit (kotlinx.serialization plugin rules, okhttp/okio, coil, media3 consumer rules, jsoup, org.json) | S4 · P1 | Obfuscation raises cost, never prevents decompilation |
| Secrets-in-code | Site URLs/selectors already live in the ENCRYPTED OTA payload (not in the APK) — document this as the primary IP shield; audit for any hardcoded selector leftovers | S2 · P1 | Embedded AES key in the client is extractable by a determined modder |
| Runtime integrity | `IntegrityCheck` (signing-cert SHA-256 vs payload pin) → `tamper_detected` telemetry + Settings row | S4 · P1 | A patched app can lie; the value is that WE see repackages |
| OTA signature | Already in place: payloads are ECDSA-signed (ROOT-1) and rev-monotonic — a modded client that ignores it gets nothing NEW from us; our lever is refusing to serve/fleet-gating by terra | S1 (verify) | Local-only |
| Release-signing guard | CI already refuses debug-signed releases (verified in `build-apk.yml`) | — | — |
| Manifest hygiene | `debuggable` false (release default), remove dead permissions if any, keep backup/log exclusions | S4 · P1 | — |
| Play Integrity | **Rejected** (needs Play/GMS; sideloaded product) — documented in ops doc | — | — |

**Documented verdict (goes in the ops doc):** sideloaded APKs can always be repackaged; our goal is cost +
visibility + a server-side lever (signed payloads, version gates, tamper telemetry). Nothing here pretends
to be DRM.

---

## 6. Workstream D — release engineering

1. `versionCode 331` / `versionName "3.3.0"` (single edit, S4 · P3).
2. **`CHANGELOG.md`** created (Keep-a-Changelog, this release's entries) — repo currently has none.
3. **`README.md`** created (purpose, build via CI, release process, operations-doc link) — repo currently has none.
4. Annotated tag `v3.3.0` on the release commit → CI `build-apk.yml` builds + publishes per-ABI release APKs.
5. **Release day ops**: mirror the two APKs + `latest.json` to the VPS (`python scripts/publish_apk.py
   --sync-box`), populate the `update` section (both source bases + SHA-256s), publish payload
   (dry-run → write-repo → sync-box), flip `update`/`announcements` flags on. Mirroring verified by
   re-downloading from BOTH sources and matching hashes against the payload pins.
6. CI failure loop: I watch the run, fix red, re-push (each fix = normal commit, still no local gradle).

---

## 7. Workstream E — documentation deliverables

- `docs/PLATFORM_OPERATIONS.md` (the "how I send messages / force updates" bible): payload recipes
  (announcement/ad/house/update/force, copy-paste JSON), publish commands, rollback, caps table, flag rollouts,
  brick-safety behavior, telemetry meanings, troubleshooting, **Registration rollout** chapter (§4.6).
- `docs/FINAL_BUILD_EXECUTION_PLAN.md` — this file (updated live: §14 log).
- `CHANGELOG.md`, `README.md` (§6).
- Audit doc: new Sections 16 (platform surfaces) + 17 (hardening) via the builder script.
- **Every deliverable is copied to `Desktop\ANON_DOWNLOADER_FINAL\`** — the single maintainer folder; nothing
  goes loose on the Desktop.

---

## 8. Roster & phase schedule (4 subordinates max, edit-capable, reporting per phase)

| Sub | Owns (files) — never touches others' files in the same phase |
|---|---|
| **S1 — Rules/OTA plane** | `data/rules/*`, `scripts/encrypt_rules.py`, `scripts/publish_rules.py`, `scraper_rules.json` (local), payload tests |
| **S2 — Engine/pipeline** | `engine/*`, `pipeline/*`, `providers/*`, `resolvers/*`, `util/DebugLog.kt`, `util/CachePaths*` as needed |
| **S3 — UI surfaces** | `ui/*`, `viewmodel/MainViewModel.kt` (UI state only), `MainActivity.kt`, `AnonApp.kt` (UI-adjacent hooks only) |
| **S4 — Hardening + update mechanics + mirroring + future-SDK pre-wiring + docs** | `app/build.gradle` (+debug appId hygiene, +SDK deps), `proguard-rules.pro`, `AndroidManifest.xml` (permissions/meta-data), `update/*` (new: UpdateManager, AnnouncementManager, EngagementScheduler, FcmRegistrar), `ads/*` (new: slot interface + house + AppLovin/Unity/AdMob adapters), `util/IntegrityCheck.kt`, `scripts/publish_apk.py` (APK-mirror tool), `docs/PLATFORM_OPERATIONS.md`, CHANGELOG/README |

**PHASE 1 — Foundation (no UI feature work):** S1 schema+flags+tests, S2 all Workstream-A fixes, S3 dead-code/
resource/wiring cleanups + "About" row skeleton, S4 R8 config + IntegrityCheck + manifest prep.
**→ my review gate: syntax gate (now all 120+54 files), sims, diff review, "phase 1 approved".**

**PHASE 2 — Features:** S1 parser tests + adversarial additions (NN-6+), S3 all UI hosts (announcements, gate,
ads, settings rows, engagement in-app), S4 UpdateManager + install flow + engagement scheduler + telemetry events,
S2 any engine interplay (none expected beyond update-flow hooks).
**→ my review gate: same + Python sims for new logic green + "phase 2 approved".**

**PHASE 3 — Release:** S4 version bump + CHANGELOG/README, S1 payload republish dry-run, S3/S2 self-review
passes, **me**: integration sweep, audit-doc rebuild, Desktop refresh, final full suite, commit → push →
tag v3.3.0 → CI watch → fix loop → verify release artifacts → final report.

---

## 9. Verification matrix

| Layer | What | When |
|---|---|---|
| Python sims (new) | `test_update_policy.py` (version compare, force rails, escape hatch, staleness), `test_announcement_pick.py` (oncePer/minApp/expiry/frequency), `test_engagement_math.py` (inactive window + cooldown), `test_ads_frequency.py`, `test_integrity_verdict.py` | P1/P2 |
| Python sims (existing) | `adversarial_qa_audit.py` (+NN-6..), `test_nollywood_filter.py`, drop/probe/work-steal/zero-redo suites | every phase boundary |
| Static | `verify_kotlin_syntax.py` expanded to ALL main+test files; zero-warning target | every phase boundary |
| Payload | `publish_rules.py --dry-run` (validator incl. new caps + reserved-field notes) | P1, P3 |
| Kotlin unit tests (new) | `RulesFlagsTest`, `ContentPolicyParseTest`, `NollywoodFilterTest`, `UpdatePolicyTest`, `QueueOrderTest` — executed by CI | CI (P3) |
| Compile | CI `build-apk.yml` on push; fix-loop until green | P3 |
| Live | none in this cycle (device testing is the maintainer's L1-L6 protocol) | post-release |

---

## 10. Review protocol (what I check at each phase boundary)

1. `git status --short` + full `git diff` review of every file the phase touched (line-level; I re-read risky hunks).
2. Gates re-run (matrix row 2+3); payload dry-run at P1/P3.
3. Targeted READ of every integration point the phase claims (hosts actually hosted, hooks actually called).
4. Cross-file contract check (S1's getters ↔ S3/S4 consumers; names, types, null-safety).
5. Anything the phase couldn't do — recorded in §14 with the reason, not silently skipped.

## 11. Release protocol — THE FINAL FINAL STAGE (nothing happens after this)

Order is fixed (maintainer directive 2026-10-18): push → watch the GitHub CI build → fix anything that
pops up → only then tag → watch the release job → fix → verify the Release. Nothing else runs after.

```
# ── FINAL FINAL STAGE — step 1: push master (triggers build-apk.yml WITHOUT [skip ci]) ──
git add <explicit paths>                         # never -A in a multi-writer window
git commit -m "feat: platform surfaces (announcements/update/gate/engagement/ads), hardening, audit fixes"
git push origin master

# ── step 2: WATCH the build+test jobs (Tier0 unit / Tier1 Robolectric / emulator gate) ──
#   gh run watch  (gh authed) — else watch the Actions page manually
#   any red → fix → commit → push → watch again; loop until GREEN. No tag while red.

# ── step 3: only after step 2 is green — tag under Releases ──
git tag -a v3.3.0 -m "v3.3.0 — platform surfaces + hardening"
git push origin v3.3.0
#   the tag fires the `release` job (signing guard refuses unsigned builds)

# ── step 4: watch the release job; fix + re-tag (delete+recreate the tag) if it pops ──
# ── step 5: verify the GitHub Release: per-ABI APKs present, SHA-256s match, notes correct ──
# ── step 6 (ops, documented in PLATFORM_OPERATIONS.md): mirror APKs to the VPS,
#    populate the `update` payload (both sources + SHA-256s), publish, flip flags ──
```
Rollback: revert commit + republish previous payload rev (kill switches make code rollback rarely necessary).

## 12. Risk register

| Risk | Mitigation |
|---|---|
| R8 breaks reflection in release (worst case: CI-only discovery) | S4 writes keep-rules for every reflective lib; `-dontwarn` only with justification; CI is the gate |
| Parallel agents touch the same file | Ownership matrix (§8); any shared file change is serialized through me |
| No local compile: syntax slips | Syntax gate on all files + my line-level review + small diffs; CI is the final compiler |
| Hard gate bricks someone | §4.5 rails (staleness, escape hatch, background downloads unaffected, percent rollout) |
| APK bloat / GMS coupling / AD_ID visibility from pre-wired SDKs | Accepted deliberately (§4.6); size measured + reported at phase 2; every adapter inert while disabled |
| Payload cap/negotiation drift | S1 owns validator lockstep; dry-run at every phase |

## 13. Deferred with reasons (not silently skipped)

- **Future-capability pre-wiring: MOVED IN SCOPE** — AppLovin / Unity Ads / AdMob / FCM / Play-Integrity
  adapters all ship DISABLED in v3.3.0; registration later = OTA flip per the §4.6 honest matrix
  (AdMob's manifest App-ID is the one possible two-line rebuild, verified during implementation).
- **PackageInstaller session install: MOVED IN SCOPE** — implemented as the primary install path in
  v3.3.0 (no registration, no dependencies).
- **VPS APK mirroring: MOVED IN SCOPE** — both hosting sources ship in v3.3.0 (Decision 4).
- Kotlin unit-test coverage for the entire legacy engine — ratchet beyond the new tests in A1/F-13
  (a codebase-wide coverage program is not a release blocker).

## 14. Execution log (append at every phase boundary)

- 2026-10-18 — plan written; awaiting "proceed".
- 2026-10-18 — plan v2: dual APK hosting (GitHub + VPS) as first-class scope (Decision 4 revised).
- 2026-10-18 — plan v3 (FINAL): future SDKs pre-wired disabled for OTA-only activation (§4.6, Decision 16);
  PackageInstaller + VPS mirror moved in scope; §11 rewritten — **CI build + tag + watch + fix is the FINAL
  FINAL stage, nothing runs after it**; Desktop deliverables consolidated into
  `Desktop\ANON_DOWNLOADER_FINAL\` (16 files moved; no loose saves from now on).
- 2026-10-18 16:4x — **"proceed" received. PHASE 1 LAUNCHED** — 4 subordinate agents in flight:
  S1 rules/OTA plane (sections+flags+validator+gate expansion+tests+dry-run), S2 engine/audit fixes (F-01..F-09,
  F-12, UpdateChecker :8000, vidsrcme seeds, HLS 1-byte gate), S3 UI cleanups (dead code/resources,
  active-definition unification, parallel-sockets control, About/Check-for-updates skeleton, player
  sub-language swap), S4 hardening (R8 on + keep-rules, IntegrityCheck.kt, REQUEST_INSTALL_PACKAGES).
  Next: my review gate per §10 as each agent reports; then PHASE 2.
- 2026-10-18 — **S4 PHASE 1 REVIEWED: PASS** — gradle (R8+shrink, release-only) and manifest
  (REQUEST_INSTALL_PACKAGES only) diffs exactly scoped; `proguard-rules.pro` rewritten per real deps
  with per-rule justification (caught that Coil 2.7 ships NO consumer rules — brief corrected);
  `IntegrityCheck.kt` verified against the real `RemoteLogger.report(type,message,context,…)` signature
  and S1's landed `TamperPolicy`/`getTamperPolicy()`; minSdk=26 legacy-signature fallback present;
  never blocks; id-only telemetry. Syntax gate: **177 files, ALL CLEAN**. → **S4 advanced to PHASE 2**
  (UpdateManager / AnnouncementManager / EngagementScheduler / FcmRegistrar / IntegrityAttestor /
  AdBridge set / SDK deps / `scripts/publish_apk.py` / AnonApp hooks), with the frozen consumer
  contracts sent in the handoff.
- 2026-10-18 — S1 getter contracts confirmed landed (:862-884 + getActiveRev :600); S1/S2/S3 still
  in phase 1 — review gates pending their reports.
- 2026-10-18 17:30-19:30 — **network incident**: the subagent API endpoint (api.river.ai) began timing out;
  S1, S2 (twice) and S4-phase-2 runs died on connect timeouts (infra, not work). Handling: assessed each
  agent's landed state from the tree; resumed S1 (it finished), re-dispatched S2 + S4 with state-aware
  instructions; S3 confirmed alive (message reached its active turn). S2 verified to have landed ZERO edits
  before dying — resumed with a write-first priority order. S4 phase-2 verified to have landed nothing —
  re-dispatched with the full assignment.
- 2026-10-18 — **S1 PHASE 1 REVIEWED: PASS** — report `scratch/phase1_report_S1.md`; my independent re-run:
  gate 177/177 PASS, default + example dry-runs PASS with the reserved-fields INFO line, real payload/.enc
  md5s unchanged; contracts frozen (`getAnnouncements/getUpdatePolicy/getAdsConfig/getEngagementConfig/
  getTamperPolicy/getFcmConfig`, exact data-class field names); validator strict across all six sections +
  nested unknown-field rejects; found & fixed a real false positive in the syntax-gate scanner (Kotlin
  string templates were misread) — gate is now template-aware. → **S1 advanced to PHASE 2** (Python
  reference sims for update/announcement/ads/engagement semantics + adversarial NN-6+ extension).
- 2026-10-18 — **S3 PHASE 1 REVIEWED: PASS** — report `scratch/phase1_report_S3.md`. Verified from my shell:
  manifest has zero `@string` refs (strings.xml deletion safe), no dangling refs after the resource/key
  deletions (only tombstone comments), MainActivity free of the dead share path (onNewIntent removed),
  `isActiveTask/activeTaskCount` in place with `downloadsStats` + all test pins untouched. Sockets control
  placed in the Downloads drawer (flagged: 1-hunk move if "Connections" was meant). → **S3 advanced to
  PHASE 2** (AnnouncementHost / UpdateGate / AdSlot / welcome-back / About diagnostics row).
- 2026-10-18 — S2 first fixes landing (F-01 `setVerbose` present); S2 + S1-phase-2 + S4-phase-2 resumed after
  the second API outage. S3's blocker recorded: `AppSettings.subtitleLanguage(Context)` must land via S2 or
  CI compile breaks — **orchestrator must verify before the final push**.
- 2026-10-18 late — **S2 PHASE 1 COMPLETE** (report `scratch/phase1_report_S2.md`): all 11 items landed incl.
  the HLS stub gate (LinkResolver `HLS_STUB_MIN_BYTES=1024` + RulesPipeline/ResultVerifier/YoutubeDl
  `isFinal >= MIN_PRODUCED_BYTES` + new HlsStubTerminalTest), vidsrcme compiled seed + `legacyApiUsable`,
  F-01/02/03/06/07/08/09/12, UpdateChecker :80, QueueOrderTest. Two cross-scope blockers fixed by the
  orchestrator: stale `HttpClientSafetyTest` assertions (500/9-byte accepts → refusals + HLS-exemption cases)
  and the androidTest `RegexCompatCatalog` entries for the deleted TmdbPosterResolver.
- 2026-10-18 late — **S4 PHASE 2 COMPLETE** (report `scratch/phase2_report_S4.md`): all five managers +
  Ads.kt + publish_apk.py + BootReceiver + manifest + six pinned SDK deps + AnonApp hooks; self-verified
  integration (file_paths already covers cacheDir, AdMob test-id confirmed, BOOT exported correct).
  Orchestrator fixed the one documented mismatch: publish_apk now ALSO copies `latest.json` to
  `ota_files/latest.json` (the exact `/ota/latest.json` URL the app's mirror check reads) + dry-run/verify
  updated. `AppSettings.subtitleLanguage` landed → S3's call-site blocker CLEARED.
- 2026-10-18 late — **PHASE 2 UI HOSTS completed by the orchestrator** (S3's agent retired after two deaths;
  no double-writer risk): `AnnouncementHost.kt`, `UpdateGate.kt` (soft snooze + hard gate + 5×-long-press
  24h escape hatch + `gate_shown`/`gate_bypass` telemetry), `AdSlot.kt` (house-only, per-day cap,
  `ad_impression`), wired in MainActivity root + Downloads shelf + Home footer; `WelcomeBackCard` +
  engagement pending-flag write in the scheduler; Settings "App signature" diagnostics row.
- 2026-10-18 late — **verification sweep green**: syntax gate 188 files PASS; adversarial 100% (check 17
  strengthened to the new `>= MIN_PRODUCED_BYTES` invariant); sims update 25/25, announcement 18/18,
  ads 15/15, engagement 18/18, nollywood 18/18; `publish_apk.py` compiles. Checkpoint committed.
- 2026-10-18 late — **PHASE 3 underway**: S4 agent dispatched for version bump (331/3.3.0) +
  `docs/PLATFORM_OPERATIONS.md` + `CHANGELOG.md` + `README.md`. Orchestrator: audit doc rebuilt with
  **Sections 16 (platform surfaces) + 17 (hardening/cleanup)** — 444 KB, Desktop refreshed; pre-push
  integration sweep clean (zero dangling refs to deleted symbols anywhere incl. androidTest; HLS gate
  single-sourced at `LinkResolver.HLS_STUB_MIN_BYTES` used by RulesPipeline/ResultVerifier; BootReceiver
  exported="true" with the BOOT_COMPLETED rationale; AdMob test-id confirmed). CI-watch prep: `gh` is
  unauthenticated → poll the public Actions API with curl; fix-loop identifies failing steps via the
  jobs endpoint and patches statically.
- 2026-10-18 late — **CI feature suites added** (orchestrator): `UpdateFlowTest` (state machine + all
  brick rails incl. fails-closed), `AnnouncementFlowTest` (pick/дismiss/no-replay/rev semantics),
  `EngagementIntegrityAdsTest` (welcome-back stamp, integrity off-states, ads inertness) — Robolectric,
  executed by the GitHub build job. Baseline green: gate 191 files, adversarial 100%, 9 sims pass,
  payload dry-run PASS, scripts compile.
- 2026-10-18/19 — **QA FAN-OUT (3 agents)**:
  - **QA-V2 (Python parity) COMPLETE** — 3 Kotlin-mirror suites (63/35/62 checks) + pref-key audit
    (no orphans); findings F1 gate-not-satisfiable, F5 dead checkForUpdate, F6 ads budget scope KDoc,
    F9 blank-copy cooldown burn + lost-update counter — ALL CLOSED in `1ba0b55`.
  - **QA-V3 (flow/kill-switch sweep) COMPLETE** — all four families' kill switches verified WIRED;
    3×P1 (announcement consumed by cold-start double activation; force.mode ignored → soft deployed as
    hard; welcome-back unreachable due to pre-init call ordering) + P2s F4-F9 — ALL CLOSED in `1ba0b55`
    (+ doc patches below).
  - **QA-V1 (config/deps vs online docs)** — still running; its findings gate the final push.
- 2026-10-18 late — `docs/PLATFORM_OPERATIONS.md` corrected against code (QA-V3 §5): escape-hatch
  wording (silent 24h hide, no "Continue anyway" dialog), AppLovin/Unity = OTA SDK-init only (vendor
  views not rendered in v3.3.0), snoozeHours 0→24h note, PackageInstaller fallback = synchronous-failure
  scope, rev shown-key format `announcement_shown_rev_<rev>_<id>`. Checklist checkpoint `1ba0b55`
  (191 files gate-green). Next: V1 gates → FINAL FINAL STAGE (push/watch/fix/tag/verify).
- 2026-10-19 — **QA-V1 COMPLETE (`scratch/qa_report_V1.md`, live-URL evidence)** — verified all six dep
  pins exist + compileSdk-34 OK (upgrade locks recorded), byte-verified every vendor API call in the
  pinned AARs, confirmed manifest/meta-data/test-id and that every ops-doc command runs as written.
  **Caught two real compile errors before CI**: IntegrityAttestor imported a nonexistent top-level
  `StandardIntegrityTokenRequest` (now the nested `StandardIntegrityManager.…`) and FcmRegistrar called
  the package-private `FirebaseMessaging.getInstance(FirebaseApp)` (now the default-app flow) — both
  fixed; proguard consumer-rule claims corrected from its byte checks; the build.gradle "requires
  compileSdk 35" comment it disproved was rewritten. All included in `1eb5981`.
- 2026-10-19 — **Cross-document external audit adjudicated** (`scratch/qa_report_crossaudit.md`):
  6 of 8 claims confirmed and fixed (incl. the P1 `requiresExplicitReferer` regression, batch-enqueue
  reverse order, sanitized-title mismatches, shelf/𝕏, notification deep-link, stale plan path); 2
  verified no-op; the "remove the 6 pre-wired SDKs" recommendation **declined with reasons** (owner's
  explicit OTA-registration mandate, Decision 16; costs already documented — reversion is 6 lines).
- 2026-10-19 — Motion/polish investigation complete (`scratch/motion_polish_report.md`,
  Desktop `MOTION_POLISH_REPORT.md`): tab switching already crossfades (report corrects the earlier
  "no transitions" assessment); the real gaps are 5 surfaces + reduce-motion leaks + press-scale
  coverage, with Tier A drop-in snippets — scheduled for the NEXT cycle (investigation-only per owner).
- 2026-10-19 — **FINAL FINAL STAGE begins: push → watch CI → fix → tag v3.3.0 → watch release →
  verify.** All local gates green: syntax 191 files, adversarial 100%, sims 94/94, payload dry-run
  PASS, CI feature suites + screenshot receipts wired.
