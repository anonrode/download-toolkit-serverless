# PLATFORM OPERATIONS — remote messages, updates and ads (v3.3.0)

This is the operations bible for the v3.3.0 platform surfaces. Everything the app can be told —
announcements, update prompts, the force-update gate, the inactivity nudge, house ads — rides the
ONE signed OTA payload (`scraper_rules.json` → `scraper_rules.json.enc`). If you can edit that JSON
and run one publish command, you can send anything, to everyone, with no app rebuild.

The three commands you will ever need (run from the repo root):

```bash
python scripts/publish_rules.py --dry-run                      # validate + sign IN MEMORY (never publish without this printing OK)
python scripts/publish_rules.py --out scraper_rules.json.enc --write-repo --sync-box --rev <N+1>
```

`--dry-run` must print a `fleet-pin verified` envelope line. If it does not, the signing key was not
found — fix that first (see §e). NEVER publish an unsigned payload.

---

## a. The four surfaces (what each one is for)

| Surface | Section | What the user sees | Use it for |
|---|---|---|---|
| **Announcements** | `announcements` | Popup dialog / bottom sheet / top banner, dismissible, optional button | "Any message I want" — notes, one-off notices, promos, warnings |
| **Update flow** | `update` | Soft "update available" card (bottom, non-blocking) → in-app download → install | Every release: tell the fleet a new APK exists |
| **Force gate** | `update.force` | Same card, or a blocking dialog when `mode:"hard"` | Only when an old build is genuinely broken/unsafe |
| **Engagement** | `engagement` | A LOW-importance notification after N idle days + an in-app welcome-back card | Win back users who stopped opening the app |
| **Ads / house messages** | `ads` | A small house-ad slot (downloads shelf / home footer), frequency-capped | House promos that double as another message surface |

Every surface obeys the same law: **the section absent = the feature off** (the safe state). A
payload that mentions no `announcements` shows no announcements; a killed family (§c) behaves the
same as an absent section — never a crash, never a half state.

---

## b. Payload recipes (copy-paste)

Workflow for every recipe below:

1. Open `scraper_rules.json` at the repo root and add/replace ONE top-level section.
2. Bump the top-level `"rev"` (any positive integer, strictly higher than the previous payload's rev).
   (Or pass `--rev <N>` on the command line — same effect, done in memory.)
3. Validate: `python scripts/publish_rules.py --dry-run`
4. Publish:
   ```bash
   python scripts/publish_rules.py --out scraper_rules.json.enc --write-repo --sync-box --rev <N>
   ```
   (This writes the repo envelope, pushes it to the box, refreshes the mirror and sha256-verifies
   the SERVED bytes from on the box. `--sync-box` needs `--out` — always keep both flags together.)
5. Devices pick it up within 24h, or on the next app resume when the last sync is >6h old.
   To force immediately: Settings → the rules sync button on any device (doubles as your render check).

### Recipe 1 — Announcement (dialog, sheet and banner + all action variants)

```json
"announcements": [
  {
    "id": "2026-10-18-update-nudge",
    "title": "New version available",
    "body": "v3.3.0 is out with faster downloads and the new update flow.",
    "mode": "dialog",
    "action": "update",
    "actionLabel": "Update now",
    "oncePer": "rev",
    "minApp": 0,
    "expiresAtMs": 0
  },
  {
    "id": "2026-10-18-guide",
    "title": "Quick tip",
    "body": "Long-press a download to copy its direct link.",
    "mode": "sheet",
    "action": "url",
    "actionLabel": "How to",
    "actionUrl": "https://example.com/guide",
    "oncePer": "id",
    "minApp": 0,
    "expiresAtMs": 0
  },
  {
    "id": "2026-10-18-banner",
    "title": "Welcome back",
    "body": "New Nollywood titles were added this week.",
    "mode": "banner",
    "action": "none",
    "actionLabel": "",
    "actionUrl": "",
    "oncePer": "id",
    "minApp": 0,
    "expiresAtMs": 0
  }
]
```

- `id` (≤64 chars) is the oncePer key — pick a NEW id for a NEW message; reuse an id and the
  already-shown devices will never see it again when `oncePer:"id"`.
- `mode`: `dialog` (centered popup) | `sheet` (bottom sheet) | `banner` (non-blocking strip).
- `action`: `none` (just dismiss) | `url` (`actionUrl` required, http/https) | `update`
  (opens the in-app update flow).
- `oncePer`: `id` = show once ever (per id) | `rev` = re-eligible on every new payload rev.
- `minApp` (0 = everyone) is a VERSION_CODE floor; `expiresAtMs` (0 = never) an absolute epoch-ms
  kill date. Multiple entries = shown in order, first eligible wins; up to 16 entries.

### Recipe 2 — House ad (zero-SDK, doubles as a message surface)

```json
"ads": {
  "enabled": true,
  "provider": "house",
  "placements": ["downloads_shelf", "home_footer"],
  "frequencyPerDay": 2,
  "house": {
    "title": "Anonrode tip",
    "body": "Queue up a full season overnight — the app keeps going in the background.",
    "actionLabel": "Learn more",
    "actionUrl": "https://example.com/tips",
    "id": "h-2026-10-a"
  }
}
```

- `frequencyPerDay` (0–20) caps how many impressions the slot counts per day per device
  (per-day counter keyed by house id); the slot simply renders nothing once capped.
- `placements` only accepts known slot ids: `downloads_shelf`, `home_footer`.
- `provider:"house"` = no ad SDK involved. Vendor providers are §f.

### Recipe 3 — Update-available notice (soft, the normal release flow)

```json
"update": {
  "latestVersionName": "3.3.0",
  "latestVersionCode": 331,
  "sources": [
    { "kind": "vps",    "base": "http://68.155.146.145/ota/apk/" },
    { "kind": "github", "base": "https://github.com/anonrode/download-toolkit-serverless/releases/download/v3.3.0/" }
  ],
  "apkArm64": "AnonDownloader-v3.3.0-arm64-v8a.apk",
  "apkUniversal": "AnonDownloader-v3.3.0-universal.apk",
  "sha256Arm64": "<64-hex from latest.json apk_arm64.sha256>",
  "sha256Universal": "<64-hex from latest.json apk_universal.sha256>",
  "message": "v3.3.0 is ready — faster and smarter. Tap Update now.",
  "force": { "enabled": false, "minSupportedVersionCode": 0, "mode": "soft", "snoozeHours": 24, "escapeHatch": true }
}
```

- Sources are walked IN ORDER per download attempt (VPS first = fast and unmetered, GitHub
  fallback). **Every attempt must match the SHA-256 pin from this signed payload** — an empty or
  wrong pin fails closed; a source serving wrong bytes is skipped.
- Populate `sha256Arm64`/`sha256Universal` from the `latest.json` that
  `scripts/publish_apk.py` wrote (see §e). File names must match EXACTLY what's mirrored.
- Users on `latestVersionCode` (or higher) see nothing; below it they get the dismissible card.
  "Later" snoozes for `force.snoozeHours` (default 24h).

### Recipe 4 — HARD force-update gate (use sparingly)

```json
"update": {
  "latestVersionName": "3.3.0",
  "latestVersionCode": 331,
  "sources": [
    { "kind": "vps",    "base": "http://68.155.146.145/ota/apk/" },
    { "kind": "github", "base": "https://github.com/anonrode/download-toolkit-serverless/releases/download/v3.3.0/" }
  ],
  "apkArm64": "AnonDownloader-v3.3.0-arm64-v8a.apk",
  "apkUniversal": "AnonDownloader-v3.3.0-universal.apk",
  "sha256Arm64": "<64-hex>",
  "sha256Universal": "<64-hex>",
  "message": "This version is no longer supported. Please update to continue.",
  "force": {
    "enabled": true,
    "minSupportedVersionCode": 331,
    "mode": "hard",
    "snoozeHours": 24,
    "escapeHatch": true
  }
}
```

- `minSupportedVersionCode` = the floor: every install BELOW it gets the gate (blocking when
  `mode:"hard"`, a dismissible warning when `"soft"`). Installations at/above it see nothing.
- Hard gates NEVER render when: the payload is older than 14 days, policy evaluation failed, or the
  floor is 0. The escape hatch (§d) is always available; background downloads keep running.
- Before enabling a hard gate, verify BOTH sources serve the exact APK whose SHA-256 you pinned
  (the publish tool verifies the VPS side for you; click the GitHub asset once to sanity-check).
- To end a gate: set `"enabled": false` (or raise the floor) and publish a new rev. Devices pick it
  up on the next sync — typically within a day, or instantly on a fresh app open after 6h.

### Bonus recipe — re-engagement ("it's been a while")

```json
"engagement": {
  "enabled": true,
  "inactiveDays": 7,
  "cooldownDays": 14,
  "title": "Still there?",
  "body": "New episodes are waiting — open Anonrode and pick up where you left off.",
  "welcomeBack": true
}
```

A LOW-importance notification arrives after 7 idle days; no second one within 14 days. Returning
after the idle window also arms the in-app welcome-back card (same copy). Kill with
`"enabled": false` — the alarm stops re-arming and nothing further is scheduled.

### Validator caps (hard-fail — the publisher refuses, nothing reaches devices)

| Field | Cap |
|---|---|
| `announcements` entries | ≤16 |
| `id`, `ads.house.id` | ≤64 chars |
| `title` (any surface, required) | ≤80 chars |
| `body` / `update.message` | ≤300 chars |
| `actionLabel` | ≤64 chars |
| Any URL (`actionUrl`, `sources[].base`) | ≤512 chars, `http://` or `https://` only |
| `latestVersionName` | ≤40 chars |
| `sources` | 1–4 entries; `kind` ∈ `vps`/`github`/`gitea` |
| `apkArm64` / `apkUniversal` file names | ≤120 chars |
| `sha256Arm64` / `sha256Universal` | 64 lowercase hex, or `""` until release day |
| `force.minSupportedVersionCode` / version codes | 0–1,000,000 |
| `force.snoozeHours` | 0–168 (`0` is treated as the 24h default) |
| `ads.frequencyPerDay` | 0–20 |
| `ads.placements` | ⊆ {`downloads_shelf`, `home_footer`} |
| vendor credentials (`applovinSdkKey`, `unityGameId`, `admob*`, `fcm.*`) | ≤256 chars each |
| `engagement.inactiveDays` / `cooldownDays` | 1–365 |
| `tamper.expectedCertSha256` | 64 hex (either case; client normalizes) |

Unknown FIELDS inside any section are refused (typo protection). Unknown top-level sections are
refused too — keep the closed vocabulary.

---

## c. Flags — staged rollouts, kill switches

Every surface has a family flag. Flags live in the payload's top-level `"flags"` object:

```json
"flags": {
  "announcements": { "enabled": true, "percent": 100 },
  "update":        { "enabled": true, "percent": 100 },
  "ads":           { "enabled": true, "percent": 5 },
  "engagement":    { "enabled": true, "percent": 25, "minApp": 331 }
}
```

Allowed sub-keys per family (anything else voids that family's flag — typo protection):
`enabled` (bool) · `percent` (0–100) · `minApp` (VERSION_CODE floor) · `expiresMs` (advisory
timestamp) · `shadow` (bool).

Semantics, in precedence order:

1. **The section must ALSO be present.** Absent section = feature off, flag or no flag
   ("familyGateBlank": a held-back family publishes empty and the compiled app runs untouched).
2. `enabled:false` → **kill switch**: the family behaves as absent on every device, immediately.
3. `minApp` → devices below the floor don't get the family (old browser, new pack).
4. `shadow:true` → held back from the user path (measurement mode).
5. `percent:100` → everyone; below → a STICKY per-install bucket (`sha256(installUuid/family) mod
   100 < percent`). The same device stays in the same bucket across syncs — a cohort never flaps
   mid-rollout.

**The 5 → 25 → 100 ramp** (the standard rollout for anything with new-user-visible behavior):

```json
"flags": { "announcements": { "enabled": true, "percent": 5 } }
```
→ watch telemetry for a day → `"percent": 25` → watch → `"percent": 100`.
To stop a rollout dead: `"enabled": false` (or remove the flag + section). Flags live only inside
the payload that carried them: a payload that omits `"flags"` resets everything to **enabled@100%**
(the Firebase in-app-defaults floor) — which is why the SECTION is the real off-switch.

Fast path for flag-only changes (no JSON editing of the big file):

```bash
# flags.json contains ONLY the flags object, e.g. {"announcements":{"enabled":true,"percent":25}}
python scripts/publish_rules.py --flags-file flags.json --dry-run
python scripts/publish_rules.py --flags-file flags.json --out scraper_rules.json.enc --write-repo --sync-box --rev <N+1>
```
(add `--write-authoring` if you want the merge persisted back into `scraper_rules.json`.)

---

## d. Brick-safety rails, the escape hatch, and pulling a bad payload

Non-negotiable rails (enforced in code, listed so you can trust them):

1. A HARD gate never renders when the payload is older than **14 days**, when policy evaluation
   failed, or when `minSupportedVersionCode` is 0. Stale policy degrades to "no gate".
2. **Escape hatch**: on the hard gate, **long-press the "Update required" title 5 times** → the gate
   hides for 24 hours (a silent bypass — no extra dialog, by design). Each bypass emits a
   `gate_bypass` telemetry record, so bypasses are visible, never silent.
3. The gate blocks UI ONLY — downloads already in the engine keep running.
4. Every surface is percent-rollable (§c) — you can stage a gate for 5% and watch before the fleet.
5. The soft card's "Later" honors `force.snoozeHours` (default 24h) — no nagging loops.

**Pulling a bad payload.** Two levels, in order of preference:

1. **Republish the previous good content under a NEW rev** (the only move that reaches every
   device): restore the previous `scraper_rules.json` from your backup, bump `rev` HIGHER than the
   bad one, run the normal publish sequence. Rev monotonicity means never reusing an old rev
   number — old content under a new rev is the rollback.
2. **Box-side emergency re-serve** (rules payload only, helps devices that never accepted the bad
   rev — devices that did keep it by design):
   ```bash
   ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 "python3 ~/download-toolkit-server/rollback_to_rev.py --list"
   ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 "python3 ~/download-toolkit-server/rollback_to_rev.py <rev> --dir ~/download-toolkit-server/ota_files"
   ```
   (The on-box registry archives every served rev; see `scripts/ota_mirror/DEPLOY.md`.)

Emergency kill for a single surface: publish `"flags": {"<family>": {"enabled": false}}` — that
lands fleet-wide on the next sync. For a truly instant stop on one surface, remove the SECTION in
the same publish — same effect, simpler.

---

## e. Release + update runbook (ship vX.Y.Z, then turn the update notice on)

0. **Bump + tag**: `versionCode`/`versionName` are edited in `app/build.gradle.kts` (one commit),
   commit → push `master` → watch `build-apk.yml` go green → tag `vX.Y.Z` → the release job builds
   and publishes `AnonDownloader-vX.Y.Z-<abi>.apk` (arm64-v8a / universal / etc.) as GitHub assets.

1. **Mirror the APKs to the box** (download the two release assets first):
   ```bash
   python scripts/publish_apk.py --apk-dir <dir with the two APKs> --version 3.3.0 --tag v3.3.0 --dry-run
   python scripts/publish_apk.py --apk-dir <dir> --version 3.3.0 --tag v3.3.0 --sync-box
   ```
   This uploads the APKs + `latest.json` to `ota_files/apk/` (served
   `http://68.155.146.145/ota/apk/`), ALSO refreshes `ota_files/latest.json` (the
   `/ota/latest.json` the manual update check reads), and sha256-verifies everything from on the
   box. In dry mode it writes `latest.json` locally and prints the exact scp/ssh lines.

2. **Populate the `update` section** in `scraper_rules.json` using Recipe 3 with:
   - VPS base `http://68.155.146.145/ota/apk/` and GitHub base
     `https://github.com/anonrode/download-toolkit-serverless/releases/download/vX.Y.Z/`;
   - exact file names from `latest.json` (`apk_arm64.file` / `apk_universal.file`);
   - the pins from `latest.json` (`apk_arm64.sha256` / `apk_universal.sha256`) — 64 lowercase hex.

3. **Publish the payload** (dry-run → write → sync) and then **flip the flag**:
   start `"update": {"enabled": true, "percent": 5}` → 25 → 100 on comfort.

4. **Tamper pin** (the `tamper` section; compute once per signing key):
   ```bash
   apksigner verify --print-certs AnonDownloader-v3.3.0-arm64-v8a.apk   # "Signer #1 certificate SHA-256 digest:"
   # or:
   keytool -printcert -jarfile AnonDownloader-v3.3.0-arm64-v8a.apk      # "SHA256:" line, strip colons
   ```
   ```json
   "tamper": { "expectedCertSha256": "<64 hex, lowercase>", "reportOnly": true }
   ```
   A mismatch reports `tamper_detected` once per session (which install runs a repackaged build)
   and NEVER blocks anything. Keep `reportOnly: true`; false is reserved for a future enforcement
   step that does not exist in v3.3.0.

Proof checklist before considering a release done: dry-run printed `fleet-pin verified`; box
verification printed byte-identical sha256s for both APKs and latest.json; one real device
downloaded + installed via the in-app flow; `update_flow` telemetry shows
downloading → verified → install_started (and install success in the installer UI).

---

## f. Registration rollout (how each vendor flips from "compiled-in" to "live")

Everything below is either a payload edit (OTA) or a documented one-line code change. Every SDK is
currently INERT: nothing initializes unless its payload section is enabled.

| Service | Fill in the payload | Code / manifest change | Rebuild? |
|---|---|---|---|
| **AppLovin (MAX)** | `ads.provider:"applovin"`, `ads.applovinSdkKey` (Dashboard → Account → Keys) | none | **OTA for SDK initialization** — the bridge initializes from the payload credential on activation; vendor ad VIEWS are not rendered in v3.3.0 (house is the only visible slot until a follow-up release) |
| **Unity Ads** | `ads.provider:"unity"`, `ads.unityGameId` (Unity Dashboard game id) | none | **OTA for SDK initialization** (same view caveat as AppLovin) |
| **AdMob** | `ads.provider:"admob"`, `ads.admobAppId`, `ads.admobBannerUnitId`, `ads.admobNativeUnitId` | **ONE manifest line**: in `AndroidManifest.xml` replace the `com.google.android.gms.ads.APPLICATION_ID` meta-data value (currently Google's test id `ca-app-pub-3940256099942544~3347511713`) with your real app id | **Yes — one line + rebuild** (the only AdMob field the SDK reads from the manifest) |
| **Firebase / FCM** | `fcm.enabled:true`, `fcm.apiKey`, `fcm.appId`, `fcm.senderId`, `fcm.projectId` (Firebase console → project settings; manual options — deliberately NO `google-services.json`) | none client-side. Server side: send via FCM HTTP v1 with a service account | **No — fully OTA** |
| **Play Integrity** | none yet (the payload flag does not exist in v3.3.0) | `update/IntegrityAttestor.kt`: flip `ENABLED = true`, set `CLOUD_PROJECT_NUMBER`, stand up the server verify endpoint; Google Cloud project + Play Console link required | **Yes** (consts) — and honestly caveated: verdict strength on sideloaded apps is weak; enable only if wanted |

Notes that keep this honest:
- Ads SDKs only initialize when `ads.enabled:true` AND a provider with a credential is named —
  until then they never touch the network and consume nothing.
- AdMob/UMP: the SDKs are compiled in but there is no consent (UMP) UI wired in v3.3.0. Before
  serving ads to EEA/UK users you must add the consent flow (a future release or an OTA-triggered
  vendor flow — do not serve EEA traffic until then).
- Activation is a payload percent-flip: register → fill fields → `--dry-run` → publish → 5% → 100%.

---

## g. Telemetry — what each event means

All events go through the existing encrypted sink (`RemoteLogger`): aggregate, id-only, inside the
same AES envelope as every diagnostic report, hard-capped ≤4 KB and ≤24 reports/session. Read them
with `scripts/read_client_errors.py`. Contexts NEVER contain URLs, file paths, tokens, or copies of
user-visible text beyond the event id.

| Event | Emitted when | Context (abridged) |
|---|---|---|
| `announcement_shown` | an announcement is picked to render | announcement id |
| `announcement_dismissed` | user dismissed/acted on it | announcement id |
| `update_flow` | update lifecycle stage | `stage` ∈ shown / downloading / verified / install_started / failed, version codes, source KIND, failure class |
| `gate_shown` | the gate/card visibly appears (state transition) | mode (hard/soft), app version, payload rev |
| `gate_bypass` | the 5×long-press escape hatch was used | app version, payload rev |
| `ad_impression` | a house ad slot actually rendered | slot/house id |
| `engagement_sent` | the inactivity notification was posted | inactiveDays, cooldownDays, welcomeBack |
| `tamper_detected` | signing cert did not match the pin (once/session) | app version, actual-cert prefix, reportOnly, debug_build |
| `fcm_registrar` | FCM register attempt outcome (once enabled) | stage, failure class |
| `integrity_attest` | Play Integrity token request outcome (once enabled) | stage, ok, failure class |
| `ota_activation` | a rules payload was activated | version, rev, source, duration, counts |
| `ota_rev_rejected` / `ota_caps_warn` | stale rev refused / validator caps warned at load | counts, samples |

Watch signals: a `gate_shown` wave with zero `gate_bypass` = the gate is landing and being accepted.
A `update_flow` `failed` cluster with `failure=mismatch:*` = a mirror is serving wrong bytes — pull
that source's base from the payload. `tamper_detected` with `debug_build:false` = someone is running
a repackaged build (visibility only — decide the response, e.g. a payload gate).

---

## h. Troubleshooting

| Symptom | Check (in order) | Fix |
|---|---|---|
| Announcement doesn't show anywhere | Section present in the LIVE payload? `flags.announcements` enabled? `minApp` ≤ device build? `expiresAtMs` passed? Already shown? (`announcement_shown_<id>` for oncePer=id, `announcement_shown_rev_<rev>_<id>` for oncePer=rev — the rev key carries BOTH, so different ids in one rev don't suppress each other) | New id (or `oncePer:"rev"` + new rev) for retries; check the sync happened (24h floor / 6h resume / manual sync button) |
| Gate never appears (hard) | Payload really fresh? (last successful sync or rules-cache mtime < 14 days) `force.enabled` true? `minSupportedVersionCode` > device VERSION_CODE and non-zero? | The rails intentionally fail closed — verify with a device log (`UpdateManager.evaluate`) that the policy parsed; then re-check the payload fields |
| Gate won't dismiss | It's `mode:"hard"` by design | Use the escape hatch (long-press title 5×, 24h bypass) and then FIX THE PAYLOAD (`force.enabled:false` or raise the floor → new rev). Check `gate_bypass` telemetry to see how many users did the same |
| Update download fails on device | Source order + SHA: does the VPS serve the file (`curl -sI http://68.155.146.145/ota/apk/<file>` from on the box)? Does the GitHub asset exist? Do `latest.json` sha256s equal the payload pins? File names typo'd? | Re-run `publish_apk.py --sync-box` (it verifies hashes on the box); re-copy pins from the fresh `latest.json`; publish a corrected rev |
| Install never starts | REQUEST_INSTALL_PACKAGES present (it is); user hasn't allowed "install from this source" yet | The installer prompt asks once; the ACTION_VIEW fallback covers a SYNCHRONOUS session failure — an async installer error surfaces in the system installer UI and can be retried from the gate |
| Ads never show | `ads.enabled` true? `provider` correct? credential filled (applovin/unity/admob)? `placements` names match the slot ids? Daily cap already reached (`ad_impressions_<day>_<houseId>`)? | Fix the field, bump rev; for AdMob also check the manifest app id was replaced at registration (§f) |
| Engagement notification never arrives | `engagement.enabled` true? POST_NOTIFICATIONS permission granted on Android 13+? device battery-optimization killing alarms (inexact alarms are deferred in Doze — this is expected, not a bug)? inside `cooldownDays` of a previous send? | Verify `inactiveDays` window actually elapsed since last open; use `engagement_sent` telemetry as ground truth for "was it posted" |
| Everything is stale after publishing | Rev not bumped? Signed `fleet-pin verified` at publish time? Device within 24h floor and no >6h resume yet? | Bump rev and republish; manual sync on a test device to check within minutes |
| `tamper_detected` from your own phone | You're running a DEBUG build (debug-signed) — expected: context carries `debug_build:true` | Filter those in review; release-signed builds should report `debug_build:false` |

More background: `docs/FINAL_BUILD_EXECUTION_PLAN.md` (§4–§6), `scripts/ota_mirror/DEPLOY.md`
(box/rollback mechanics), `docs/SIGNING_ROTATION.md` (signing keys and drills).
