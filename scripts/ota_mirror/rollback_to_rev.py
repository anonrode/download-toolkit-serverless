"""rollback_to_rev.py — re-serve a chosen rev from the append-only OTA registry.

The sync script (sync_ota_mirror.py) archives every synced payload into an
append-only registry:

    <history-dir>/<rev>/scraper_rules.json.enc      (rev from the payload)
    <history-dir>/ts-<UTCstamp>/scraper_rules.json.enc   (pre-rev payloads)
    <history-dir>/latest_rev.txt                    text pointer, atomic
    <history-dir>/rev_latest                        JSON pointer, atomic

This script copies a stored entry back onto the SERVED path
(<ota-dir>/scraper_rules.json.enc) with an atomic write — the phone-visible
effect is a byte-identical re-serve of an exactly-known payload (evidence-safe:
bytes are read from the registry, never re-encrypted or re-signed).

Caveat (by design, mirrors the client contract): the Phase-2 client rejects
OTA payloads with rev <= its activeRev. Rolling back to an older rev therefore
only affects devices whose activeRev is LOWER than the target — newer devices
stay on their current rules. This is the honest semantics of a payload
registry and is why publishing must always bump the rev.

Usage:
    python3 rollback_to_rev.py --list
    python3 rollback_to_rev.py 7                    # by rev number
    python3 rollback_to_rev.py ts-20261016T121314Z  # by (no-rev) folder name
    python3 rollback_to_rev.py latest               # via the rev_latest pointer
    python3 rollback_to_rev.py 7 --dry-run          # show, do not touch /ota

Options:
    --dir PATH          served OTA directory (default: ./ota_files next to
                        this script — on the box: ~/download-toolkit-server/ota_files)
    --history-dir PATH  registry root (default: <parent-of-ota-dir>/logs/ota_rev_history)
    --list              list registry entries (rev, sha256, mtime) and exit
    --dry-run           resolve + verify + print, but never write

Exit codes: 0 ok, 1 nothing rolled back (target missing/insane), 2 bad args.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import sys
import tempfile

ENC_NAME = "scraper_rules.json.enc"
LATEST_REV_FILE = "latest_rev.txt"
REV_LATEST_FILE = "rev_latest"


def warn(msg):
    sys.stderr.write(msg + "\n")


def envelope_is_sane(body: bytes) -> bool:
    """Keep in lockstep with sync_ota_mirror.envelope_is_sane (standalone copy:
    the box deploys this script without the rest of the repo)."""
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
    """Keep in lockstep with sync_ota_mirror.atomic_write (tmp + os.replace)."""
    d = os.path.dirname(path) or "."
    fd, tmp = tempfile.mkstemp(prefix=".ota-rollback-", dir=d)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def load_rev(folder: str):
    """Decrypt just enough to REPORT the entry's rev (None for pre-rev)."""
    enc = os.path.join(folder, ENC_NAME)
    if not os.path.isfile(enc):
        return False, None
    with open(enc, "rb") as f:
        body = f.read()
    if not envelope_is_sane(body):
        return False, None
    rev = None
    try:
        env = json.loads(body.decode("utf-8"))
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.hazmat.primitives.padding import PKCS7
        ct = base64.b64decode(env["payload"])
        dec = Cipher(algorithms.AES(bytes.fromhex(
            "8f3a9c21d4e65b0789a2c4f6d1e3b5a7")), modes.CBC(bytes.fromhex(env["iv"]))).decryptor()
        unp = PKCS7(128).unpadder()
        plain = unp.update(dec.update(ct) + dec.finalize()) + unp.finalize()
        r = json.loads(plain.decode("utf-8")).get("rev")
        if isinstance(r, int) and not isinstance(r, bool) and r > 0:
            rev = r
    except Exception:
        rev = None
    return body, rev


def list_entries(hist_root: str) -> int:
    if not os.path.isdir(hist_root):
        warn(f"[rollback] no registry at {hist_root} (run sync_ota_mirror.py once)")
        return 1
    entries = []
    for name in sorted(os.listdir(hist_root)):
        folder = os.path.join(hist_root, name)
        if not os.path.isdir(folder):
            continue
        body, rev = load_rev(folder)
        if body is False:
            warn(f"[rollback] skipping {name}: no sane {ENC_NAME} stored")
            continue
        sha = hashlib.sha256(body).hexdigest()
        mt = datetime.datetime.fromtimestamp(os.path.getmtime(os.path.join(folder, ENC_NAME)),
                                             datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        entries.append((name, rev, sha, mt, len(body)))
    if not entries:
        warn("[rollback] registry is empty")
        return 1
    print(f"{'rev':<10} {'folder':<24} {'sha256 (12)':<14} {'bytes':>8}  archived")
    for name, rev, sha, mt, n in entries:
        print(f"{str(rev if rev is not None else '-'):<10} {name:<24} {sha[:12]:<14} {n:>8}  {mt}")
    return 0


def resolve_target(rev_arg: str, hist_root: str, ota_dir: str):
    """Map the CLI argument to a stored body. Accepts a rev number, a folder
    name, or 'latest' (resolved via rev_latest, then latest_rev.txt)."""
    if rev_arg == "latest":
        ptr = os.path.join(hist_root, REV_LATEST_FILE)
        if os.path.isfile(ptr):
            try:
                name = json.loads(open(ptr, encoding="utf-8").read())["folder"]
                folder = os.path.join(hist_root, name)
                body, rev = load_rev(folder)
                if body is not False:
                    return body, rev, folder
                warn(f"[rollback] rev_latest points at {name!r} but it has no sane payload")
            except (ValueError, KeyError) as e:
                warn(f"[rollback] unreadable rev_latest ({e}); falling back")
        ptr = os.path.join(hist_root, LATEST_REV_FILE)
        if os.path.isfile(ptr):
            name = open(ptr, encoding="ascii").read().strip()
            if name:
                folder = os.path.join(hist_root, name)
                body, rev = load_rev(folder)
                if body is not False:
                    return body, rev, folder
        warn("[rollback] 'latest' requested but no usable pointer file exists")
        return None, None, None

    folder = os.path.join(hist_root, rev_arg)
    body, rev = load_rev(folder)
    if body is not False:
        return body, rev, folder
    # numeric arg that has no <rev> folder yet → try to find the folder for it
    if rev_arg.isdigit() and os.path.isdir(hist_root):
        for name in sorted(os.listdir(hist_root)):
            sub = os.path.join(hist_root, name)
            if not os.path.isdir(sub):
                continue
            body, rev2 = load_rev(sub)
            if body is not False and rev2 is not None and str(rev2) == rev_arg:
                return body, rev2, sub
        warn(f"[rollback] no registry entry with rev {rev_arg}")
    else:
        warn(f"[rollback] no registry entry {rev_arg!r} under {hist_root}")
    return None, None, None


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="Re-serve a stored OTA rev (byte-identical, atomic).")
    ap.add_argument("rev", nargs="?", help="rev number, registry folder name, or 'latest'")
    ap.add_argument("--dir", default=os.path.join(here, "ota_files"),
                    help="served OTA directory (default: ./ota_files)")
    ap.add_argument("--history-dir", default=None,
                    help="registry root (default: <parent-of-ota-dir>/logs/ota_rev_history)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    ota_dir = os.path.abspath(args.dir)
    hist_root = os.path.abspath(args.history_dir) if args.history_dir \
        else os.path.join(os.path.dirname(ota_dir), "logs", "ota_rev_history")

    if args.list:
        return list_entries(hist_root)
    if not args.rev:
        warn("[rollback] need a rev (or --list); nothing done")
        return 2

    body, rev, folder = resolve_target(args.rev, hist_root, ota_dir)
    if body is None:
        return 1
    sha = hashlib.sha256(body).hexdigest()

    enc_path = os.path.join(ota_dir, ENC_NAME)
    cur_sha = None
    if os.path.isfile(enc_path):
        with open(enc_path, "rb") as f:
            cur = f.read()
        cur_sha = hashlib.sha256(cur).hexdigest()

    label = f"{os.path.basename(folder)} (rev={rev if rev is not None else 'none'})"
    print(f"[rollback] target    : {label}")
    print(f"[rollback] registry  : {folder}")
    print(f"[rollback] sha256    : {sha}")
    print(f"[rollback] served now: {cur_sha or '(no file yet)'}")
    if cur_sha == sha:
        print("[rollback] already byte-identical — nothing to do")
        return 0
    if args.dry_run:
        print(f"[rollback] DRY-RUN — would atomically install {len(body)} bytes "
              f"onto {enc_path}")
        return 0
    if not os.path.isdir(ota_dir):
        warn(f"[rollback] served dir {ota_dir} does not exist; refusing to create it")
        return 1
    try:
        atomic_write(enc_path, body)
    except Exception as e:
        warn(f"[rollback] FAILED to install ({e}); served file untouched")
        return 1
    with open(enc_path, "rb") as f:
        ok = hashlib.sha256(f.read()).hexdigest() == sha
    print(f"[rollback] installed: {enc_path} ({len(body)} bytes, verify={'OK' if ok else 'MISMATCH'})")
    if rev is None:
        print("[rollback] note: payload predates `rev` — every client that "
              "validates rev will reject it; publish a rev bump instead when possible.")
    else:
        print("[rollback] note: devices whose activeRev >= "
              f"{rev} keep their current rules (client contract); "
              "devices below it pick this up on the next check.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
