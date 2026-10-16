# OTA mirror on 68.155.146.145 — deploy notes (Phase 0 delivery chain)

## Why

The phone (app v3.2.9, log share-31) could not reach ANY GitHub host
(`raw.githubusercontent.com` for rules, `api.github.com` for update checks)
while desktop was fine — carrier DNS/rate-limiting. The delivery chain in the
app (DynamicRulesManager.kt / UpdateChecker.kt) falls back to this box as the
last source: it serves the SAME byte-identical signed payloads at

    http://68.155.146.145:8000/ota/scraper_rules.json.enc
    http://68.155.146.145:8000/ota/latest.json

(`68.155.146.145` already has a cleartext exception in the app's
`network_security_config.xml`; a raw-IP host needs no DNS, so it answers even
when every name lookup on the device is broken or poisoned). The app still
verifies the ECDSA signature AFTER download — transport is never trusted, so a
mirror box cannot feed the fleet fake scraping logic.

## What is deployed (append-only, no existing routes/behavior touched)

Inside the EXISTING uvicorn/FastAPI service (`anon-server.service`, port 8000):

```python
OTA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ota_files')
if os.path.exists(OTA_DIR):
    app.mount('/ota', StaticFiles(directory=OTA_DIR), name='ota')
```

FastAPI StaticFiles sets Content-Type from the file extension
(`.enc` → application/octet-stream, `.json` → application/json). The version
middleware only gates `/api/v1/*` and `/health`, so `/ota/*` is unaffected.
Files:

```
/home/Anon/download-toolkit-server/ota_files/scraper_rules.json.enc   # byte-identical to repo master
/home/Anon/download-toolkit-server/ota_files/latest.json              # refreshed from the GitHub Releases API
/home/Anon/download-toolkit-server/sync_ota_mirror.py                 # cron-able refresher (scripts/ota_mirror/)
/home/Anon/download-toolkit-server/rollback_to_rev.py                 # re-serve a stored rev (scripts/ota_mirror/)
/home/Anon/download-toolkit-server/logs/ota_rev_history/              # append-only rev registry (below)
```

## Rev registry (append-only rollback history)

Every successful sync ALSO archives the served .enc bytes into an append-only
registry (nothing is ever deleted or overwritten — history failures never fail
the sync):

```
~/download-toolkit-server/logs/ota_rev_history/<rev>/scraper_rules.json.enc
# <rev> = the integer `rev` field DECRYPTED from the payload (the AES key ships
#         in the APK; the mirror can decrypt but still cannot sign). One folder
#         per rev; re-syncing the same bytes is a no-op. If the same rev ever
#         shows up with DIFFERENT bytes (a re-signed rev without a bump — a
#         publish-side mistake), the original is kept and the newcomer is
#         stored alongside as scraper_rules.json.enc.<UTCstamp> with a loud
#         warning.
~/download-toolkit-server/logs/ota_rev_history/ts-<UTC>/scraper_rules.json.enc
# fallback folder for payloads whose rev is not decryptable/absent (pre-rev)
~/download-toolkit-server/logs/ota_rev_history/latest_rev.txt   # text pointer (atomic write)
~/download-toolkit-server/logs/ota_rev_history/rev_latest       # JSON pointer {"latest","folder","rev","sha256","bytes","archived_utc"} (atomic)
```

Layout facts: the registry root defaults to
`<parent-of-ota-dir>/logs/ota_rev_history` (override with
`--history-dir`; skip with `--skip-history`), which on the box resolves to
`~/download-toolkit-server/logs/ota_rev_history` next to the existing
`ota_sync.log`. /ota keeps serving exactly the two flat files it always did —
the registry is never served.

## Rollback to a stored rev (evidence-safe)

`~/download-toolkit-server/rollback_to_rev.py` (source:
`scripts/ota_mirror/rollback_to_rev.py`) copies a stored entry back onto the
served path with an atomic write. Bytes come FROM the registry — never
re-encrypted/re-signed — so the re-served payload is byte-identical to what
that rev originally was, and the pre-rollback file stays in the registry as
the newest entry (roll the forward again via `latest`).

```bash
# what is stored?
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
    "python3 ~/download-toolkit-server/rollback_to_rev.py --list"

# inspect without touching /ota
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
    "python3 ~/download-toolkit-server/rollback_to_rev.py 7 --dir \
     ~/download-toolkit-server/ota_files --dry-run"

# actually re-serve rev 7 (or 'latest', or a ts-<UTC> folder name), then verify
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
    "python3 ~/download-toolkit-server/rollback_to_rev.py 7 --dir \
     ~/download-toolkit-server/ota_files && \
     curl -s http://127.0.0.1:8000/ota/scraper_rules.json.enc | sha256sum"
```

NO service restart is involved (writes land under ota_files/, which
StaticFiles serves straight from disk). Client contract caveat: devices whose
`activeRev` >= the target rev keep their current rules — rollback is for
devices that never accepted the newer payload. Publishing new rules must
always bump the rev.

Deploying the updated scripts to the box (append-only: new file + overwrite of
the standalone refresher; NO server.py change, NO restart needed):

```bash
scp -i ~/.ssh/anon_vps scripts/ota_mirror/sync_ota_mirror.py scripts/ota_mirror/rollback_to_rev.py \
    Anon@68.155.146.145:~/download-toolkit-server/
```

Refresh (manual or cron; script pulls from raw → jsdelivr → statically and
keeps the previous good file on any failure):

    python3 /home/Anon/download-toolkit-server/sync_ota_mirror.py \
        --dir /home/Anon/download-toolkit-server/ota_files --quiet

Installed cron (daily, 05:17 server time, log to logs/ota_sync.log):

```
17 5 * * * /usr/bin/python3 /home/Anon/download-toolkit-server/sync_ota_mirror.py --dir /home/Anon/download-toolkit-server/ota_files --quiet >> /home/Anon/download-toolkit-server/logs/ota_sync.log 2>&1
```

## DEPLOYED — verification results (2026-10-15)

- `systemctl is-active anon-server` → `active` (backup file on the box:
  `server.py.bak-20261016-1231-ota`; rollback = copy back + restart).
- `GET http://68.155.146.145:8000/ota/scraper_rules.json.enc` (verified from
  the box on 127.0.0.1:8000; the ops workstation cannot route to port 8000
  directly — client devices demonstrably can, the telemetry sink on the same
  port receives their reports) → HTTP 200, 57,873 bytes, sha256
  `5f83a97b7a162f794b2a4916326b7c98bbb607803e7bead48cebd26f216abc98` —
  BYTE-IDENTICAL to repo master `scraper_rules.json.enc` (same hash computed
  locally, and a pull-mode re-sync from raw.githubusercontent.com on the box
  produced the same hash).
- `GET /ota/latest.json` → HTTP 200, `{"version": "v3.2.9", "html_url":
  ".../releases/tag/v3.2.9", "assets": [5 APKs]}` — the exact shape
  UpdateChecker's fallback parses.
- Existing service health after restart: `/health` with an up-to-date client
  header → 200; `POST /api/logs/client-error` → 200 (telemetry sink
  untouched). The pull-mode sync twice produced v3.2.9 latest.json, proving
  both the cron wiring and the box's GitHub egress.


## DEPLOYED — rev-registry verification results (2026-10-16)

- `sync_ota_mirror.py` + `rollback_to_rev.py` scp'd to
  `~/download-toolkit-server/` and `py_compile`-clean under the box's
  `/usr/bin/python3` (3.12.3, has `cryptography` — rev decryption active for
  cron runs). NO server.py change, NO restart; all endpoints 200 before and
  after (verified from the box on 127.0.0.1:8000 — the ops workstation's
  egress to port 8000 is blocked):
  - `GET /health` (header `x-client-version: 3.2.9`) → 200 `{"status":"ok"}`
    (426 without the header — middleware behavior unchanged)
  - `GET /ota/scraper_rules.json.enc` → 200, 57,873 bytes, sha256
    `5f83a97b7a162f794b2a4916326b7c98bbb607803e7bead48cebd26f216abc98`
    (unchanged through deploy + sync + drill)
  - `GET /ota/latest.json` → 200, `v3.2.9`, 5 assets
  - `POST /api/logs/client-error` → 200 (telemetry sink untouched)
- One live pull-mode sync (the exact cron command, non-quiet): pulled from
  raw.githubusercontent.com, archived
  `ts-20261016T151324Z/scraper_rules.json.enc` (57873 bytes,
  `5f83a97b7a16...`), wrote both pointers, refreshed latest.json, rc=0. The
  live master payload carries NO `rev` yet — hence the `ts-` fallback folder
  and `"rev": null` in `rev_latest`; the first rev'd publish flips the
  registry to `<rev>/` folders.
- Rollback drill: `rollback_to_rev.py latest` against the REAL served path
  correctly reports "already byte-identical — nothing to do" (the registry
  entry IS the live bytes); the actual atomic-install path was then exercised
  in a scratch dir (`/tmp/ota_drill_ota`, dirty bytes → rollback → byte-
  identical `5f83a97b...` restored, verify=OK, scratch removed). The real
  served path was never non-identical to the registry at any point.


## Exact steps that were performed (repeatable)

From the repo root (Windows, Git Bash; ssh alias/config: `~/.ssh/anon_vps`,
user `Anon`):

```bash
# 1. payload + refresher onto the box
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 "mkdir -p ~/download-toolkit-server/ota_files"
scp -i ~/.ssh/anon_vps scraper_rules.json.enc \
    Anon@68.155.146.145:~/download-toolkit-server/ota_files/
scp -i ~/.ssh/anon_vps scripts/ota_mirror/sync_ota_mirror.py \
    Anon@68.155.146.145:~/download-toolkit-server/

# 2. refresh latest.json from the GitHub API (also proves egress works)
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
    "python3 ~/download-toolkit-server/sync_ota_mirror.py \
     --dir ~/download-toolkit-server/ota_files --file \
     ~/download-toolkit-server/ota_files/scraper_rules.json.enc"

# 3. backup + append the mount block + syntax check (venv python)
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 "
  cp ~/download-toolkit-server/server.py ~/download-toolkit-server/server.py.bak-\$(date +%Y%m%d-%H%M)-ota &&
  printf '%s\n' \
    '' \
    '# --- OTA mirror (Phase 0 delivery chain, 2026-10-15): static mirror of' \
    '# the GitHub-hosted signed OTA payloads; additive only. /ota/* is served' \
    '# by StaticFiles and is NOT touched by the client-version middleware' \
    '# (that gates /api/v1/* and /health only). The app verifies the payload' \
    '# signature after download — transport never trusted. Refresh with' \
    '# sync_ota_mirror.py (cron); files are byte-identical to repo master.' \
    "OTA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ota_files')" \
    'if os.path.exists(OTA_DIR):' \
    "    app.mount('/ota', StaticFiles(directory=OTA_DIR), name='ota')" \
    >> ~/download-toolkit-server/server.py &&
  ~/download-toolkit-server/venv/bin/python -m py_compile ~/download-toolkit-server/server.py"

# 4. restart + health check (Restart=always on the unit; rollback = restore .bak)
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
    "sudo systemctl restart anon-server && sleep 4 && systemctl is-active anon-server"

# 5. byte-identity verification (sha256 of repo file vs served file)
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
    "curl -s http://127.0.0.1:8000/ota/scraper_rules.json.enc | sha256sum; \
     sha256sum ~/download-toolkit-server/ota_files/scraper_rules.json.enc"
```

## Rollback

```bash
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 "
  cp ~/download-toolkit-server/server.py.bak-<stamp>-ota ~/download-toolkit-server/server.py &&
  sudo systemctl restart anon-server"
```

(The standalone `ota_mirror_server.py` in this directory is NOT used on the
box — it exists for a host where the existing service must stay untouched, or
for local testing on a spare port.)

## OTA HOLD marker (F5, added 2026-10-16)

`rollback_to_rev.py` installs a chosen rev onto /ota — but the daily sync cron
(`17 5 * * *`) re-pulls master and would silently undo it within 24h. To keep a
rollback (or any manual last-known-good) in force:

```bash
mkdir -p /home/Anon/download-toolkit-server/logs
touch /home/Anon/download-toolkit-server/logs/ota_hold
```

While `logs/ota_hold` exists, `sync_ota_mirror.py` refuses to overwrite
`/ota/scraper_rules.json.enc` and logs why (latest.json + rev-archive still
update). Lift the hold with `rm .../logs/ota_hold`. The marker is honored by
the pull path AND `--file` installs; `rollback_to_rev.py` itself is not gated
(an operator running a rollback is the deliberate act).
