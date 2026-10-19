# Anonrode — Anon Downloader (serverless)

An Android video downloader and stream resolver with an OTA-tunable engine: site scraping logic,
update policy, announcements and ads configuration all arrive through one signed, encrypted rules
payload — no app rebuild to change behavior or talk to the fleet.

- **Package id:** `com.anonrode.downloader` (debug builds: `com.anonrode.downloader.debug`)
- **Platform:** minSdk 26 · target/compileSdk 34 · Kotlin 2.0 · Jetpack Compose
- **Distribution:** sideloaded APKs (GitHub Releases; no Play dependency; GMS-free devices supported)

## How to build

There is no local Gradle workflow — **CI is the compiler**. Push to `master` or open the repo's
**Actions** tab and run the **`build-apk`** workflow; it produces per-ABI + universal release
artifacts named:

```
AnonDownloader-<tag>-arm64-v8a.apk
AnonDownloader-<tag>-armeabi-v7a.apk
AnonDownloader-<tag>-x86.apk
AnonDownloader-<tag>-x86_64.apk
AnonDownloader-<tag>-universal.apk
```

Release signing comes from CI secrets (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`); without them the release build falls back to debug signing for local/dev use and
the release job refuses to publish unsigned artifacts.

## How to release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`, commit, push `master`.
2. Watch the Actions run until green (unit + Robolectric + emulator tiers).
3. Tag: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z` — the release job builds and
   publishes the signed APKs as GitHub release assets.
4. Mirror the APKs to the OTA box and populate the `update` payload —
   `python scripts/publish_apk.py --apk-dir <dir> --version X.Y.Z --tag vX.Y.Z --sync-box`,
   then follow the release runbook.

## Sending messages / forcing updates (operations)

Everything user-visible and remotely controllable (announcements, update prompts, the force gate,
re-engagement nudges, house ads, kill switches and percent rollouts) is operated from the signed
payload. The complete how-to — copy-paste JSON recipes, caps, flag ramps, brick-safety rails,
release/update runbook, vendor registration steps, telemetry meanings and troubleshooting — lives in:

**`docs/PLATFORM_OPERATIONS.md`**

Related: `docs/FINAL_BUILD_EXECUTION_PLAN.md` (build plan), `scripts/ota_mirror/DEPLOY.md` (rules
mirror box + rollback), `docs/SIGNING_ROTATION.md` (OTA signing keys), `CHANGELOG.md`.

## Maintainer deliverables

Release deliverables for the maintainer are collected in
`C:\Users\user\Desktop\ANON_DOWNLOADER_FINAL\` (nothing is saved loose on the Desktop).
