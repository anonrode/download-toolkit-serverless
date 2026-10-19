#!/usr/bin/env python3
"""Mirror release APKs + latest.json onto the OTA box (phase 2, Decision 4).

Conventions are copied verbatim from scripts/publish_rules.py (and its
sync-box link): scp/ssh with ~/.ssh/anon_vps to Anon@68.155.146.145, the box
root /home/Anon/download-toolkit-server, and verification run FROM ON the box
(workstation egress to the serving port is blocked — live probes on
2026-10-18 confirmed :80 serves /ota/*, the retired :8000 does not).

Where the files go (deliberate choice, zero box-server changes):
    APKs + latest.json are placed under the EXISTING /ota/ StaticFiles mount
    (DEPLOY.md: ota_files/ is mounted at /ota by the already-deployed service)
    in an `apk/` subdirectory:

        /home/Anon/download-toolkit-server/ota_files/apk/<file>

    which makes them served at

        http://68.155.146.145/ota/apk/<file>

    latest.json is ALSO copied to the mount root
    (ota_files/latest.json -> http://68.155.146.145/ota/latest.json) because
    that root URL is the EXACT mirror path the app reads (util/UpdateChecker.kt
    source 2); the apk/-co-located copy keeps the mirror self-contained for
    humans.

    Reusing the append-only mount keeps this deployment inside DEPLOY.md's
    "no existing routes/behavior touched" discipline. (The plan's shorthand
    base "http://68.155.146.145/apk/" would require adding a NEW /apk mount
    on the box; if that is ever done, override --box-dir and use that base.)

latest.json field names MATCH the app's existing mirror parser (util/
UpdateChecker.kt source 2 reads `version` + `html_url`, exactly), plus the
per-APK sha256/size fields the release-day payload population needs
(plan Decision 4: version, size, SHA-256).

Behavior:
  - default (no flags): DRY — locate APKs, compute sha256 + sizes, write
    latest.json locally, print the exact scp/ssh lines; no remote contact.
  - --sync-box: upload APKs + latest.json, then sha256-verify every file
    from ON the box (both the files themselves and the served URL).
  - --dry-run: explicit dry even if --sync-box is also given.

Exit codes: 0 ok, 1 verification/build failure, 2 bad arguments/IO.
Never invokes git or any package/SCM tooling.
"""
import argparse
import datetime
import glob
import hashlib
import json
import os
import subprocess
import sys

BOX_USER = "Anon"
BOX_HOST = "68.155.146.145"
BOX_KEY = os.path.join(os.path.expanduser("~"), ".ssh", "anon_vps")
BOX_ROOT = "/home/Anon/download-toolkit-server"
BOX_OTA_DIR = f"{BOX_ROOT}/ota_files"
BOX_APK_DIR = f"{BOX_OTA_DIR}/apk"
SERVED_BASE = f"http://{BOX_HOST}/ota/apk/"
RELEASES_PAGE = "https://github.com/anonrode/download-toolkit-serverless/releases/tag/"


def step(dry, text):
    print(f"[publish-apk] {'would ' if dry else ''}{text}")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_artifact(apk_dir, needle):
    """Exactly one file containing `needle` and ending in .apk, else None."""
    matches = sorted(
        p for p in glob.glob(os.path.join(apk_dir, "*.apk"))
        if needle in os.path.basename(p)
    )
    if len(matches) == 1:
        return matches[0]
    if not matches:
        return None
    print(f"[publish-apk] FATAL: ambiguous {needle} matches: "
          f"{[os.path.basename(m) for m in matches]}", file=sys.stderr)
    return None


def build_latest_json(tag, version, arm64_path, universal_path):
    """The shape util/UpdateChecker.kt reads from the mirror: `version`
    (release tag) + `html_url` (release page); assets carry the sha256/size
    table the update payload pins come from."""
    def entry(path):
        return {
            "file": os.path.basename(path),
            "size": os.path.getsize(path),
            "sha256": sha256_of(path),
        }

    arm = entry(arm64_path)
    uni = entry(universal_path)
    return {
        "version": tag,                       # EXACT field name the app reads
        "version_name": version,
        "html_url": RELEASES_PAGE + tag,      # EXACT field name the app reads
        "assets": [
            {"name": arm["file"], "size": arm["size"], "sha256": arm["sha256"]},
            {"name": uni["file"], "size": uni["size"], "sha256": uni["sha256"]},
        ],
        "apk_arm64": arm,
        "apk_universal": uni,
        "generated_utc": datetime.datetime.now(datetime.timezone.utc)
        .strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def sync_box(apk_dir, files, local_latest):
    """scp the artifacts, then verify EVERYTHING from ON the box (the
    workstation cannot reach the serving port directly — DEPLOY.md)."""
    mkdir = ("ssh", "-i", BOX_KEY, f"{BOX_USER}@{BOX_HOST}", f"mkdir -p {BOX_APK_DIR}")
    print(f"[publish-apk] ssh mkdir -p {BOX_APK_DIR}")
    subprocess.run(mkdir, check=True)

    remote_target = f"{BOX_USER}@{BOX_HOST}:{BOX_APK_DIR}/"
    dsts = " ".join(files + [local_latest])
    print(f"[publish-apk] scp {dsts} -> {remote_target}")
    scp = ("scp", "-i", BOX_KEY) + tuple(files + [local_latest]) + (remote_target,)
    subprocess.run(scp, check=True)

    # ALSO place latest.json at the /ota/ mount ROOT — that path is the exact
    # mirror URL the app reads (util/UpdateChecker.kt source 2:
    # http://HOST/ota/latest.json). The apk/-co-located copy above keeps the
    # mirror self-contained; this root copy is the machine contract.
    root_target = f"{BOX_USER}@{BOX_HOST}:{BOX_OTA_DIR}/"
    print(f"[publish-apk] scp {local_latest} -> {root_target}")
    subprocess.run(("scp", "-i", BOX_KEY, local_latest, root_target), check=True)

    failed = False
    for path in files:
        name = os.path.basename(path)
        local_sha = sha256_of(path)
        r = subprocess.run(
            ("ssh", "-i", BOX_KEY, f"{BOX_USER}@{BOX_HOST}",
             f"sha256sum {BOX_APK_DIR}/{name}"),
            capture_output=True, text=True, check=True,
        )
        remote_sha = r.stdout.split()[0] if r.stdout.split() else "?"
        print(f"[publish-apk] {name}: local={local_sha[:12]}… box={remote_sha[:12]}…")
        if remote_sha != local_sha:
            print(f"[publish-apk] MISMATCH for {name} — served file is NOT the built artifact",
                  file=sys.stderr)
            failed = True

    # Served-bytes check from the box against the URL THE APP ACTUALLY READS
    # (/ota/latest.json; :80 first — the surface the fleet uses; :8000 kept as
    # a legacy fallback in the same one-liner).
    verify_cmd = (
        f"curl -s http://127.0.0.1/ota/latest.json "
        f"|| curl -s http://127.0.0.1:8000/ota/latest.json"
    )
    r = subprocess.run(
        ("ssh", "-i", BOX_KEY, f"{BOX_USER}@{BOX_HOST}",
         f"( {verify_cmd} ) | sha256sum"),
        capture_output=True, text=True, check=True,
    )
    remote_latest = r.stdout.split()[0] if r.stdout.split() else "?"
    with open(local_latest, "rb") as f:
        local_latest_sha = hashlib.sha256(f.read()).hexdigest()
    print(f"[publish-apk] latest.json local={local_latest_sha[:12]}… served={remote_latest[:12]}…")
    if remote_latest != local_latest_sha:
        print("[publish-apk] MISMATCH — served latest.json differs from the local artifact",
              file=sys.stderr)
        failed = True

    if failed:
        return 1
    print("[publish-apk] byte-identical on the box — mirror complete")
    print(f"[publish-apk] payload source base for the update section: {SERVED_BASE}")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="Mirror release APKs + latest.json to the OTA box (dry by default).")
    ap.add_argument("--apk-dir", default=".",
                    help="directory containing the built APKs (CI output/release assets)")
    ap.add_argument("--version", required=True,
                    help='release version name, e.g. "3.3.0"')
    ap.add_argument("--tag", required=True,
                    help='release tag, e.g. "v3.3.0" (becomes latest.json "version")')
    ap.add_argument("--sync-box", action="store_true",
                    help="upload + verify on the box (otherwise: perfect dry run)")
    ap.add_argument("--dry-run", action="store_true",
                    help="print every step that WOULD run; no writes, no remote contact")
    args = ap.parse_args()

    dry = args.dry_run or not args.sync_box

    if not os.path.isdir(args.apk_dir):
        print(f"[publish-apk] FATAL: --apk-dir not found: {args.apk_dir}", file=sys.stderr)
        return 2

    arm64 = find_artifact(args.apk_dir, "arm64-v8a")
    universal = find_artifact(args.apk_dir, "universal")
    if arm64 is None or universal is None:
        print(f"[publish-apk] FATAL: need exactly one *arm64-v8a*.apk and one "
              f"*universal*.apk in {args.apk_dir} "
              f"(found arm64={arm64 is not None}, universal={universal is not None})",
              file=sys.stderr)
        return 2

    latest = build_latest_json(args.tag, args.version, arm64, universal)

    print(f"[publish-apk] version={args.version} tag={args.tag}")
    for key in ("apk_arm64", "apk_universal"):
        e = latest[key]
        print(f"[publish-apk] {e['file']}: {e['size']} bytes  sha256={e['sha256']}")

    local_latest = os.path.join(args.apk_dir, "latest.json")
    if dry:
        step(dry, f"write {local_latest}")
        print("[publish-apk] -- dry run: exact deploy lines --")
        print(f"  ssh -i {BOX_KEY} {BOX_USER}@{BOX_HOST} 'mkdir -p {BOX_APK_DIR}'")
        print(f"  scp -i {BOX_KEY} {arm64} {universal} {local_latest} "
              f"{BOX_USER}@{BOX_HOST}:{BOX_APK_DIR}/")
        print(f"  scp -i {BOX_KEY} {local_latest} "
              f"{BOX_USER}@{BOX_HOST}:{BOX_OTA_DIR}/")
        print(f"  ssh -i {BOX_KEY} {BOX_USER}@{BOX_HOST} "
              f"'sha256sum {BOX_APK_DIR}/{os.path.basename(arm64)} "
              f"{BOX_APK_DIR}/{os.path.basename(universal)}'")
        print(f"  ssh -i {BOX_KEY} {BOX_USER}@{BOX_HOST} "
              f"'( curl -s http://127.0.0.1/ota/latest.json || "
              f"curl -s http://127.0.0.1:8000/ota/latest.json ) | sha256sum'")
        print(f"[publish-apk] payload source base afterwards: {SERVED_BASE}")
    with open(local_latest, "w", encoding="utf-8") as f:
        json.dump(latest, f, indent=2, sort_keys=False)
        f.write("\n")
    print(f"[publish-apk] wrote {local_latest}")

    if dry:
        return 0

    rc = sync_box(args.apk_dir, [arm64, universal], local_latest)
    if rc == 0:
        print("[publish-apk] now populate the update payload: sources[0].base = "
              f"{SERVED_BASE}, sha256Arm64/sha256Universal from the table above")
    return rc


if __name__ == "__main__":
    sys.exit(main())
