"""sync_ota_mirror.py — pulls the current master OTA payloads into the served
mirror directory. Designed for cron on the mirror box.

What it does, in order:
  1. scraper_rules.json.enc — downloads the signed payload from GitHub
     (raw.githubusercontent.com, then jsdelivr, then statically; first byte-
     sane response wins) OR copies a local --file past in. Sanity gate: the
     body must look like the signed v2 envelope (JSON with v>=2, "payload",
     "sig") — garbage is never written; the previous good file stays.
  2. latest.json — calls the GitHub Releases API (releases/latest) and writes
     {"version": "<tag>", "html_url": "...", "assets": [{name,
     browser_download_url}...]}. On API failure the existing latest.json is
     kept untouched and the script still succeeds (message on stderr).
  3. rev history (append-only rollback registry) — on EVERY successful sync
     the served .enc bytes are ALSO archived to
         <parent-of-ota-dir>/logs/ota_rev_history/<rev>/scraper_rules.json.enc
     where <rev> is the integer `rev` field decrypted from the payload (the
     AES key ships in the APK, so the mirror can decrypt without holding any
     signing material). Payloads without a decryptable rev (pre-rev payloads)
     land in a UTC-stamped `ts-...` folder and BOTH pointer files are kept:
         logs/ota_rev_history/latest_rev.txt   (latest identifier, atomic)
         logs/ota_rev_history/rev_latest       (structured JSON pointer, atomic)
     History failures NEVER fail the sync (warn + continue). Re-serve a stored
     rev onto /ota with rollback_to_rev.py (byte-identical copy, atomic write).
  4. Writes are atomic (tmp + os.replace), so a concurrent app fetch can never
     read a half-written file.

The app verifies the ECDSA signature AFTER download, so this script being on
the same box as the HTTP server adds no trust requirement: a corrupted or
hijacked mirror file would be refused by every client (chain moves on), and
this script simply cannot mint valid signatures.

Usage (mirrors what the cron line on 68.155.146.145 runs):
    python3 sync_ota_mirror.py --dir /home/Anon/download-toolkit-server/ota_files

Options:
    --dir PATH       target directory served as /ota (default ./ota_files)
    --file PATH      bootstrap from a local .enc copy instead of downloading
    --skip-latest    do not touch latest.json
    --quiet          only errors on stderr, nothing on stdout
    --check-mirrors  additionally compare raw.githubusercontent vs jsdelivr
                     bytes and warn (stderr) when they differ
Exit codes: 0 ok, 1 no usable .enc obtained, 2 bad arguments.

Cron example (daily 05:17 UTC):
    17 5 * * * /usr/bin/python3 /home/Anon/download-toolkit-server/sync_ota_mirror.py \
        --dir /home/Anon/download-toolkit-server/ota_files --quiet \
        >> /home/Anon/download-toolkit-server/logs/ota_sync.log 2>&1
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import sys
import tempfile
import urllib.request

REPO = "anonrode/download-toolkit-serverless"
RAW_URLS = [
    f"https://raw.githubusercontent.com/{REPO}/master/scraper_rules.json.enc",
    f"https://cdn.jsdelivr.net/gh/{REPO}@master/scraper_rules.json.enc",
    f"https://cdn.statically.io/gh/{REPO}/master/scraper_rules.json.enc",
]
RELEASES_API = f"https://api.github.com/repos/{REPO}/releases/latest"
UA = "Anonrode-OTA-Mirror/1.0 (ops script)"
ENC_NAME = "scraper_rules.json.enc"
LATEST_NAME = "latest.json"
# Rev-registry layout (append-only): <hist-root>/<rev>/scraper_rules.json.enc
# where <hist-root> defaults to <parent-of-ota-dir>/logs/ota_rev_history.
HIST_DIRNAME = os.path.join("logs", "ota_rev_history")
LATEST_REV_FILE = "latest_rev.txt"
REV_LATEST_FILE = "rev_latest"
# AES payload key — ships inside the APK (encrypt_rules.RULES_KEY), so the
# mirror can DECRYPT the plaintext to read its `rev` field while still being
# unable to mint signatures (ECDSA private half never leaves key holders).
RULES_KEY = bytes.fromhex("8f3a9c21d4e65b0789a2c4f6d1e3b5a7")


def log(quiet, msg):
    if not quiet:
        sys.stdout.write(msg + "\n")


def warn(msg):
    sys.stderr.write(msg + "\n")


def fetch(url, timeout=30):
    """GET a URL with a browser-ish UA (jsdelivr/statically prefer one; the
    GitHub API requires one). Returns bytes or None."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except Exception as e:
        warn(f"[sync] {url.split('/')[2]} failed: {e}")
        return None


def envelope_is_sane(body: bytes) -> bool:
    """Structural sanity only — NOT a signature check (that exists in the
    app, with the key; the mirror cannot and need not hold one)."""
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        return False
    if not text.startswith("{"):
        return False
    try:
        env = json.loads(text)
    except ValueError:
        return False
    payload = env.get("payload")
    if not isinstance(payload, str) or len(payload) < 100:
        return False
    if int(env.get("v", 0)) < 2 or not str(env.get("sig", "")).strip():
        return False
    try:
        base64.b64decode(payload, validate=True)
    except Exception:
        return False
    return True


def atomic_write(path: str, data: bytes):
    d = os.path.dirname(path) or "."
    fd, tmp = tempfile.mkstemp(prefix=".ota-", dir=d)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)  # atomic on POSIX: readers never see a torn file
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def try_extract_rev(body: bytes):
    """Best-effort decryption of the envelope payload → its integer `rev`
    field, or None when undecryptable/absent/invalid. Soft dependency on the
    `cryptography` package (present on the box's /usr/bin/python3 which cron
    runs); any failure degrades to the timestamp-folder fallback."""
    try:
        env = json.loads(body.decode("utf-8"))
        ct = base64.b64decode(env["payload"])
        iv = bytes.fromhex(env["iv"])
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.hazmat.primitives.padding import PKCS7
        dec = Cipher(algorithms.AES(RULES_KEY), modes.CBC(iv)).decryptor()
        padded = dec.update(ct) + dec.finalize()
        unp = PKCS7(128).unpadder()
        plain = unp.update(padded) + unp.finalize()
        rev = json.loads(plain.decode("utf-8")).get("rev")
        if isinstance(rev, bool) or not isinstance(rev, int) or rev <= 0:
            return None
        return rev
    except Exception:
        return None


def archive_rev(hist_root: str, body: bytes, quiet: bool):
    """Append-only rev registry. Returns the archive folder name or None on
    failure (a registry problem must never fail the sync itself)."""
    rev = try_extract_rev(body)
    if rev is not None:
        folder = str(rev)
    else:
        folder = "ts-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    sha = hashlib.sha256(body).hexdigest()
    try:
        enc_path = os.path.join(hist_root, folder, ENC_NAME)
        os.makedirs(os.path.dirname(enc_path), exist_ok=True)
        existing = None
        if os.path.exists(enc_path):
            with open(enc_path, "rb") as f:
                existing = f.read()
        if existing == body:
            log(quiet, f"[sync] rev history: {folder}/{ENC_NAME} unchanged ({sha[:12]})")
        elif existing is not None:
            # Append-only: never overwrite stored history. A same-rev/different-
            # bytes payload is a publish-side mistake (re-signed rev without a
            # bump) — keep BOTH, side-by-side, and say so loudly.
            stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            variant = os.path.join(hist_root, folder, f"{ENC_NAME}.{stamp}")
            atomic_write(variant, body)
            warn(f"[sync] WARNING: rev {folder} already stored with different bytes; "
                 f"kept original + wrote variant {stamp} "
                 f"(was {hashlib.sha256(existing).hexdigest()[:12]}, new {sha[:12]})")
        else:
            atomic_write(enc_path, body)
            log(quiet, f"[sync] rev history: archived {folder}/{ENC_NAME} ({len(body)} bytes, {sha[:12]})")
    except Exception as e:
        warn(f"[sync] rev history write failed ({e}); served payloads unaffected")
        return None

    identifier = folder if rev is None else str(rev)
    pointer = json.dumps({
        "latest": identifier,
        "folder": folder,
        "rev": rev,               # null when the payload predates `rev`
        "sha256": sha,
        "bytes": len(body),
        "archived_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    }, separators=(",", ":")).encode("utf-8") + b"\n"
    try:
        atomic_write(os.path.join(hist_root, LATEST_REV_FILE), identifier.encode("ascii") + b"\n")
        atomic_write(os.path.join(hist_root, REV_LATEST_FILE), pointer)
    except Exception as e:
        warn(f"[sync] rev pointer update failed ({e}); archive unaffected")
    return folder


def sync_enc(target_dir: str, local_file: str | None, quiet: bool, check_mirrors: bool) -> bytes | None:
    # F5 (OTA red-team P1): a rollback installed by rollback_to_rev.py would
    # otherwise be silently undone by the next daily cron re-pull of master.
    # A marker file pauses syncs until an operator removes it — the rollback
    # (or any last-known-good override) stays in force by explicit decision.
    hold = os.path.join(os.path.dirname(target_dir.rstrip(os.sep)), "logs", "ota_hold")
    if os.path.exists(hold):
        warn(f"[sync] OTA HOLD active ({hold}) — refusing to overwrite {ENC_NAME}; "
             "rollback stays in force until the marker file is removed")
        return None
    enc_path = os.path.join(target_dir, ENC_NAME)

    if local_file:
        with open(local_file, "rb") as f:
            body = f.read()
        if not envelope_is_sane(body):
            warn(f"[sync] --file {local_file} is not a sane signed envelope; refusing")
            return None
        atomic_write(enc_path, body)
        log(quiet, f"[sync] installed {ENC_NAME} from {local_file} ({len(body)} bytes)")
        return body

    for url in RAW_URLS:
        body = fetch(url)
        if body is None or not envelope_is_sane(body):
            warn(f"[sync] {url.split('/')[2]} unusable (missing/short/unsigned)")
            continue
        atomic_write(enc_path, body)
        log(quiet, f"[sync] {ENC_NAME} updated from {url.split('/')[2]} ({len(body)} bytes)")

        if check_mirrors:
            other = fetch(RAW_URLS[1] if url == RAW_URLS[0] else RAW_URLS[0])
            if other is not None and other != body:
                warn("[sync] WARNING: mirror bytes differ from the primary — "
                     "clients verify signatures, but check upstream freshness "
                     "or a poisoned CDN edge")

        return body

    warn("[sync] no usable .enc from any source; existing file left untouched")
    return None


def sync_latest(target_dir: str, quiet: bool):
    """Releases metadata for the app's update-check fallback source. Shape the
    app parses: {"version": tag, "html_url": page, "assets": [{name, url}]}."""
    body = fetch(RELEASES_API)
    latest_path = os.path.join(target_dir, LATEST_NAME)
    if body is None:
        warn("[sync] releases API unreachable; latest.json left untouched")
        return False
    try:
        rel = json.loads(body)
        tag = str(rel["tag_name"]).strip()
        html_url = str(rel.get("html_url", "")).strip()
        assets = [
            {"name": a.get("name", ""), "url": a.get("browser_download_url", "")}
            for a in rel.get("assets", [])
            if isinstance(a, dict)
        ]
    except (ValueError, KeyError) as e:
        warn(f"[sync] releases API response unusable ({e}); latest.json left untouched")
        return False
    if not tag:
        warn("[sync] releases API returned an empty tag; latest.json left untouched")
        return False
    doc = {"version": tag, "html_url": html_url, "assets": assets}
    atomic_write(latest_path, json.dumps(doc, indent=1).encode("utf-8") + b"\n")
    log(quiet, f"[sync] {LATEST_NAME} updated (version {tag}, {len(assets)} assets)")
    return True


def main():
    ap = argparse.ArgumentParser(description="Refresh the OTA mirror's served payloads from GitHub.")
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "ota_files"))
    ap.add_argument("--file", default=None, help="bootstrap the .enc from a local copy instead of downloading")
    ap.add_argument("--history-dir", default=None,
                    help="append-only rev-registry root "
                         f"(default: <parent-of-ota-dir>/{HIST_DIRNAME})")
    ap.add_argument("--skip-latest", action="store_true", help="do not refresh latest.json")
    ap.add_argument("--skip-history", action="store_true",
                    help="do not archive the synced payload into the rev registry")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--check-mirrors", action="store_true", help="cross-check raw vs jsdelivr bytes")
    args = ap.parse_args()

    target = os.path.abspath(args.dir)
    os.makedirs(target, exist_ok=True)

    got = sync_enc(target, args.file, args.quiet, args.check_mirrors)
    if got is None:
        return 1
    if not args.skip_history:
        hist = os.path.abspath(args.history_dir) if args.history_dir \
            else os.path.join(os.path.dirname(target), HIST_DIRNAME)
        archive_rev(hist, got, args.quiet)
    if not args.skip_latest:
        sync_latest(target, args.quiet)
    return 0


if __name__ == "__main__":
    sys.exit(main())
