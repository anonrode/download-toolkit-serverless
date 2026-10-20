# Release verification checklist (v3.3.0)

Current state (2026-10-20): signed rules **rev 3 is published and synced to the
VPS**; the branch carries the engine/UI/ops fixes up to `87cde3c`. No tag move
or new APK build has been performed in this freeze. The remote tag still points
at the previous release and GitHub Release `v3.3.0` has its existing 5 APKs.
The final force-move happens only after the CI gate is green.

## Signing-key custody

Cold OTA keys live OUTSIDE the checkout: `<parent>/anon-ota-keys` or
`$ANON_OTA_KEYS_DIR` (the legacy `<repo>/keys` is only a fallback). Never
`git add` a `*.pem`; never commit a service-account JSON. The publish script
refuses to emit an unsigned envelope.

## Per-lane gates (run before each push)

```bash
python scripts/verify_kotlin_syntax.py          # main + test + debug
python scratch/adversarial_qa_audit.py          # 100% required
python scripts/verify_fcm_static.py             # manifest/FCM/log-upload static checks
python -m py_compile scripts/hls_test/*.py scripts/fcm/*.py scripts/verify_fcm_static.py
# Engine lane additionally:
python scratch/test_drop_replace.py
python scratch/test_work_steal.py
# CI (authoritative compile/test gate):
gradle :app:testDebugUnitTest
```

CI must be green for the pushed SHA: build (unit + Robolectric), emulator-test,
release job. Treat every red check as blocking; fix forward. Do not tag until
the full HLS matrix, log-upload proof, and FCM runtime test pass.

## Commit / push convention

- Conventional scopes: `fix(engine|resolvers|ui|fcm|verify)`, `feat(fcm)`,
  `test(verify)`, `docs(verify)`.
- One logical change per commit; body states root cause, fix, evidence, gates.
- `git fetch origin && git pull --rebase` before push; push master only.
- Never amend/rebase pushed commits. No tag or release without the gate. OTA
  payload changes go through `publish_rules.py` with a rev bump and the
  `fleet-pin verified` line; never hand-edit the committed `.enc`.

## CI watch loop

For each pushed SHA:
1. `gh run list --workflow=build-apk.yml --branch master --limit 1`
2. `gh run watch <run-id> --exit-status`
3. On failure: `gh run view <run-id> --log-failed`, classify
   (compile/unit/emulator/infra), fix-forward, rerun gates.
4. Record run id + SHA + conclusion. Emulator flakes are accepted only when
   the retry passes and `flake-ledger.json` records the signature.

## Final force-tag procedure (only after green)

```bash
git fetch origin
grep -n "versionCode\|versionName" app/build.gradle.kts   # expect 331 / 3.3.0
test -z "$(git status --porcelain)"
GREEN_SHA=$(git rev-parse HEAD)
git tag -f -a v3.3.0 -m "Anonrode v3.3.0" "$GREEN_SHA"
git push --force origin refs/tags/v3.3.0
git ls-remote --tags origin v3.3.0 v3.3.0^{}
```

Watch the tag-triggered CI run. Release job publishes under `v3.3.0`.

## GitHub Release verification

```bash
curl -s https://api.github.com/repos/anonrode/download-toolkit-serverless/releases/tags/v3.3.0
```

Require: `draft=false`, `tag_name=v3.3.0`, exactly 5 APK assets with non-zero
sizes matching the CI artifact list:
`arm64-v8a`, `armeabi-v7a`, `universal`, `x86`, `x86_64`.
For each asset compare the API `digest` (`sha256:...`) with a server-side
`sha256sum`; record sizes/digests in the verification report. If stale assets
from the old tag remain, delete/replace them only after the new CI run finishes.

## Server-side mirror (zero PC APK downloads)

Run on the VPS, not the PC:

1. `mkdir -p /home/Anon/release-v3.3.0 && cd ...`
2. Download the 5 assets directly from the GitHub Release with `curl -L --fail`
   and verify each against the API `digest` + `Content-Length`.
3. Archive the current `ota_files/apk/` + `latest.json` to
   `ota_files/archive/v3.3.0-<UTC>/`.
4. Run `scripts/publish_apk.py` **on the box**. Its current `--sync-box` path
   scp's to `Anon@68.155.146.145`; for the server-side run add a local mode
   (`--server-side`) or a self-host detection so it installs into
   `ota_files/apk/` without scp. Do not copy APKs to the PC.
5. `latest.json` pins arm64-v8a + universal with exact `size`/`sha256`;
   mirror all five APKs for completeness.
6. Verify from the box:
   ```bash
   sha256sum ota_files/apk/*.apk
   curl -s http://127.0.0.1/ota/latest.json | sha256sum
   curl -sI http://127.0.0.1/ota/apk/<file>.apk
   ```
   Compare with the local `latest.json` and the GitHub API digests.
7. From the PC, only fetch `latest.json` and a 1 KB range of an APK to prove
   serving; never download a full APK.

## Desktop deliverable refresh

Update `C:\Users\user\Desktop\ANON_DOWNLOADER_FINAL\` with:
- final verification report (gates, HLS matrix, upload proof, FCM proof);
- `summary.csv` + per-run `summary.json` from `scratch/hls_matrix/`;
- release asset digest table;
- `CHANGELOG.md` and updated runbook/checklist copies.

## Rollback

Keep the old tag/mirror backup and the archive folder. Restoring the mirror is
`cp` from `ota_files/archive/...`; restoring the tag is a deliberate
force-move back to the recorded old SHA, never automatic.
