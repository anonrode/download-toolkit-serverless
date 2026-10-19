# Changelog

All notable changes to Anonrode (Anon Downloader) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this
project uses semantic-ish release tags (`vX.Y.Z`).

## [3.3.0] - 2026-10-18

### Added

- **Remote announcements** — OTA-authored dialog / bottom-sheet / banner messages with optional
  URL or update actions, per-message `oncePer` (id/rev), `minApp` floor and expiry; fully
  remote-controlled from the signed payload, no app rebuild.
- **In-app update flow** — update policy from the signed payload (version identity, dual hosting
  sources VPS + GitHub, per-ABI SHA-256 pins, message); in-app download with progress,
  SHA-256-verified on EVERY source attempt (fails closed on a blank/mismatched pin),
  PackageInstaller session install (primary) with an ACTION_VIEW/FileProvider fallback, plus a
  manual "Check for updates" that shares the one GitHub→VPS→cached chain.
- **Force-obsolete gate** — soft warning or blocking dialog driven by `update.force`, with the
  non-negotiable rails: never renders on stale (>14d) or failed policy, never on a zero floor,
  always escapable (5× long-press → 24h bypass + `gate_bypass` telemetry), background downloads
  unaffected.
- **Re-engagement** — one-shot inexact inactivity alarm (payload-configurable `inactiveDays` /
  `cooldownDays`, boot re-arm), LOW-importance `engagement` notification from payload copy, and an
  in-app welcome-back card for users returning after the idle window.
- **Ads readiness** — slot architecture with zero-SDK house ads (downloads shelf / home footer,
  daily frequency cap) plus pre-wired, DISABLED AppLovin / Unity Ads / AdMob (+ UMP) adapters whose
  credentials are OTA payload fields; the AdMob manifest app-id currently carries Google's test id.
- **Future-capability pre-wiring** — firebase-messaging registrar (manual `FirebaseOptions`,
  deliberately NO `google-services.json`) and a Play Integrity attestor; both compile in and stay
  provably inert until their payload config / registration flips them.
- **Runtime integrity check** — installed signing-cert SHA-256 compared against an OTA `tamper`
  pin; a mismatch emits one aggregate `tamper_detected` telemetry record per session (visibility,
  never enforcement/blocks).
- **OTA flags for the new surfaces** — per-family `enabled` / `percent` / `minApp` / `expiresMs` /
  `shadow`, with sticky per-install buckets for 5 → 25 → 100 ramps and instant kill switches.
- **Telemetry events** (id-only, encrypted sink): `announcement_shown`, `announcement_dismissed`,
  `update_flow`, `gate_shown`, `gate_bypass`, `ad_impression`, `engagement_sent`,
  `tamper_detected`, `fcm_registrar`, `integrity_attest`.
- **Tooling & docs** — `scripts/publish_apk.py` (APK + latest.json mirror to the VPS with on-box
  sha256 verification), `docs/PLATFORM_OPERATIONS.md` (the operations bible: payload recipes,
  flag ramps, brick-safety, release runbook, registration rollout, telemetry, troubleshooting),
  this changelog and a README.
- New tests: `RulesFlagsTest`, `ContentPolicyParseTest`, `QueueOrderTest`, `NollywoodFilterTest`,
  `UpdatePolicyTest` plus `scratch/` Python sims for update policy, announcement picking,
  engagement math, ads frequency and integrity verdicts.

### Changed

- Version bumped to **3.3.0** (versionCode **331**).
- **Release builds are hardened**: R8 minification + resource shrinking enabled for release,
  single-package repackaging with access widening, and a per-dependency keep-rule audit (consumer
  rules relied on where verified; explicit keeps only where justified).
- Update-check mirror moved from the retired `:8000` to `:80` (one update path everywhere; the
  stale-mirror dead end is gone).
- Rules sync now also refreshes on app resume when the last successful sync is older than 6 hours
  (24h floor kept for the steady state); engagement clock stamps on every app open.
- Six pre-wired SDKs added as dependencies (AppLovin, Unity Ads, play-services-ads, UMP,
  firebase-messaging, play-integrity) — inert at runtime, ~2–4 MB APK cost, documented trade-off.

### Fixed

- **Audit batch (subsystems)**: F-01 debug-logging toggle now reaches `DebugLog.setVerbose` live;
  F-02 no-source ledger records at the real resolver-empty/failover-exhausted sites; F-03 Nepu
  provider reads the OTA URL template (hardcoded shape as fallback); F-04 reserved `sites` fields
  flagged as INFO by the validator (no fake readers); F-05 new family flags shipped in the payload;
  F-06 unused TmdbPosterResolver removed; F-07 `SiteProvider.requiresSingleSocket` removed; F-08
  settings consolidated to one source (player subtitle language preserved); F-09 task registry
  forgets on all terminal failure paths + startTask guard; F-12 queue-order comment corrected and
  pinned by a test (LIFO by design).
- **Wiring**: drawer preselection, clipboard toggle and settings-snapshot clobber fixes; dead
  surfaces removed (share handler, dead card-status detail, dead episode helpers); "active" state
  unified to one helper; dead resources removed after zero-reference proof; debug builds get a
  `.debug` application-id suffix so debug and release coexist.
- **Activity-log audit**: HLS branch now rejects 1-byte/placeholder terminals via the shared
  terminal-size class; cold-start dead-host (vidsrcme) request wave contained; watchdog-kill /
  CancellationException / stub-linkage regressions recorded as regression-tested.

### Removed

- Dead code and resources from the audit (most notably `TmdbPosterResolver.kt`,
  `SiteProvider.requiresSingleSocket`, and a set of zero-reference resources).

### Notes

- Users still on an old debug-signed build must uninstall/reinstall once to receive release-signed
  updates (different signing keys; the in-app update flow cannot cross that boundary).
