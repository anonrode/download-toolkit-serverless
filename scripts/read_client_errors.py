"""read_client_errors.py — the ONLY reader for Anonrode's remote
client-error reports (Track 6).

The devices post AES-128-CBC-encrypted diagnostics to
/api/logs/client-error (see app .../util/RemoteLogger.kt). The server is a
DUMB SINK: it stores opaque envelopes in logs/client_errors.jsonl and can
never read them. This script holds the same key as the OTA rules payload
(RULES_KEY, identical to scripts/encrypt_rules.py and the APK) — so
whoever can run this script reads; the VPS, wire sniffers and datacenter
techs cannot.

Usage (Windows PowerShell / Git Bash):
  # decrypt a downloaded copy of the server's jsonl (pull it with your own
  # ssh/scp invocation — no server address is hardcoded here):
  scp user@server:~/path/logs/client_errors.jsonl .
  python scripts/read_client_errors.py client_errors.jsonl

  # pipe a single envelope through stdin:
  cat event.json | python scripts/read_client_errors.py -

Options:
  --type stall_watchdog_timeout   only show that error_type
  --client  <client_id prefix>    filter by client id
  --n 50                          show at most 50 records (default: all)
  --raw                           print decrypted JSON instead of a report
"""
import argparse
import base64
import datetime
import json
import sys

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

# CANONICAL key: identical bytes to encrypt_rules.py RULES_KEY and the APK's
# DynamicRulesManager.RULES_KEY_HEX. Note: obfuscation-grade (ships in the
# APK), exactly like the OTA rules themselves — it stops casual reading by
# outsiders, not a reverse engineer who extracts the key.
import importlib.util
import os

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "encrypt_rules", os.path.join(_here, "encrypt_rules.py"))
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
RULES_KEY = _mod.RULES_KEY


def decrypt_event(record: dict):
    """Returns the decrypted diagnostics dict, or None for plaintext/legacy
    events that were written before encryption shipped (those carry error
    fields at top level instead of the envelope)."""
    env = record.get("payload")
    iv_hex = record.get("iv")
    if not env or not iv_hex:
        return None
    iv = bytes.fromhex(iv_hex)
    ct = base64.b64decode(env)
    dec = Cipher(algorithms.AES(RULES_KEY), modes.CBC(iv)).decryptor()
    unpadder = padding.PKCS7(128).unpadder()
    plain = unpadder.update(dec.update(ct) + dec.finalize()) + unpadder.finalize()
    return json.loads(plain.decode("utf-8"))


def is_envelope(record: dict) -> bool:
    return "payload" in record and "iv" in record


def iso(ts: float) -> str:
    try:
        return datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError, OSError):
        return "?"


def render(idx: int, rec: dict, diag: dict) -> str:
    ctx = diag.get("context") or {}
    ctx_s = " ".join(
        f"{k}={v}" for k, v in ctx.items() if v not in (None, "")) or "-"
    stack = diag.get("stack_trace") or rec.get("stack_trace") or ""
    lines = [
        f"[{idx}] {iso(diag.get('timestamp') or rec.get('server_timestamp'))}  "
        f"{diag.get('error_type', '?')}  "
        f"client={str(diag.get('client_id', '?'))[:13]}  "
        f"v{diag.get('client_version', '?')}",
        f"     msg: {diag.get('message', '')}",
        f"     ctx: {ctx_s[:300]}",
    ]
    if stack:
        first = stack.splitlines()[0] if stack.splitlines() else ""
        lines.append(f"     stack: {first[:150]}  ({len(stack)} chars stored)")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description="Decrypt client error reports (only we hold the key).")
    ap.add_argument("source", help="path to client_errors.jsonl (or '-' for stdin)")
    ap.add_argument("--type", default=None, help="filter by error_type")
    ap.add_argument("--client", default=None, help="filter by client_id prefix")
    ap.add_argument("--n", type=int, default=0, help="max records shown (0 = all)")
    ap.add_argument("--raw", action="store_true", help="print decrypted JSON, not the summary")
    args = ap.parse_args()

    if args.source == "-":
        raw_lines = sys.stdin.read().splitlines()
    else:
        with open(args.source, encoding="utf-8") as fh:
            raw_lines = fh.read().splitlines()

    # Newest last in the jsonl (server appends); newest first on screen.
    shown = 0
    for line in reversed(raw_lines):
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        enc = is_envelope(rec)
        diag = decrypt_event(rec) if enc else rec
        if diag is None:
            print(f"[!] undecryptable envelope: {rec.get('iv', '?')[:16]}… "
                  "(wrong key copy?)/skipped", file=sys.stderr)
            continue
        if args.type and (diag.get("error_type") or "") != args.type:
            continue
        if args.client and not str(diag.get("client_id", "")).startswith(args.client):
            continue
        shown += 1
        if args.n and shown > args.n:
            break
        if args.raw:
            print(json.dumps(diag, ensure_ascii=False, indent=2))
        elif enc:
            print(render(shown, rec, diag))
        else:
            # Pre-encryption legacy record: fields sit at top level.
            print(f"[legacy plain] {iso(rec.get('server_timestamp'))}  "
                  f"{rec.get('error_type') or rec.get('error', '?')}  "
                  f"{rec.get('message', '')[:200]}")

    if shown == 0:
        print("(no matching reports)")


if __name__ == "__main__":
    main()
