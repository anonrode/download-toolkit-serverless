"""read_client_logs.py — ops reader for the full activity-log uploads
(the /api/v1/telemetry/full-log pipeline, see app .../util/LogUploader.kt).

Every client upload lands on the VPS as
  logs/clients/<client_id>/<log_date>.p<seq>.log.gz     (the journal chunk)
  logs/clients/<client_id>/index.jsonl                  (append-only index;
      ts, ip, log_date, seq, raw_bytes, gz_bytes, build, ua)

The server records the CONNECTION's public IP on every upload (the client
usually can't know its own real address behind carrier NAT / VPN), so a
device is identified by anonymous client id AND by the set of IPs it
appeared from.

Usage (from the repo root; connection flags are always explicit):
  # timeline across ALL clients (newest first): every upload, its IP, size
  python scripts/read_client_logs.py list

  # same, but only one client (or only one IP — find which devices shared it):
  python scripts/read_client_logs.py list --client 8f0e...36
  python scripts/read_client_logs.py list --ip 102.89.34.7

  # print a DECOMPRESSED chunk (works with or without --client, needs date+seq):
  python scripts/read_client_logs.py cat --date 2026-10-15 --seq 1
  python scripts/read_client_logs.py cat --date 2026-10-15 --seq 1 --client 8f0e...36

  # bulk-copy one client's whole folder to a local dir for offline digging
  python scripts/read_client_logs.py pull --client 8f0e...36 --out ./client-logs

Connection (always explicit — nothing is hardcoded):
  --host user@server --key ~/.ssh/your_key
"""
import argparse
import base64
import gzip
import json
import os
import subprocess
import sys

DEFAULT_HOST = ""
DEFAULT_KEY = ""
DEFAULT_REMOTE_DIR = "~/REDACTED-DIR/logs/clients"


def ssh(host: str, key: str, remote_cmd: str, timeout: int = 60) -> str:
    """Runs a command on the VPS, returns stdout. Keyed ssh, batch mode."""
    if not host or not key:
        raise SystemExit("connection not set: pass --host user@server and --key ~/.ssh/your_key")
    key = os.path.expanduser(key)
    cmd = [
        "ssh", "-i", key, "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=15", "-o", "StrictHostKeyChecking=accept-new",
        host, remote_cmd,
    ]
    proc = subprocess.run(cmd, capture_output=True, timeout=timeout,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0 and not proc.stdout.strip():
        raise RuntimeError(f"ssh failed ({proc.returncode}): {proc.stderr.strip()[:400]}")
    return proc.stdout


def load_index(host: str, key: str, remote_dir: str) -> list:
    """Reads EVERY client's index.jsonl in one ssh round-trip (grep -H keeps
    the filename so the client id — the folder name — comes along)."""
    out = ssh(host, key,
              f"grep -H . {remote_dir}/*/index.jsonl 2>/dev/null || true")
    entries = []
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        fname, _, payload = line.partition(":")
        client = os.path.basename(os.path.dirname(fname))
        try:
            rec = json.loads(payload)
        except ValueError:
            continue  # a torn/tampered line never breaks the whole list
        rec["_client"] = client
        entries.append(rec)
    return entries


def render(rec: dict) -> str:
    kb = rec.get("gz_bytes", 0) / 1024.0
    raw = rec.get("raw_bytes", 0) / 1024.0
    return (f"{rec.get('ts', '?')}  {rec['_client'][:13]:<13}  "
            f"ip={rec.get('ip', '?'):<16}  v{rec.get('build', '?'):<16}  "
            f"{rec.get('log_date', '?')} p{rec.get('seq', '?')}  "
            f"[{raw:.0f} KB raw / {kb:.0f} KB gz]")


def cat_chunk(host: str, key: str, remote_dir: str, client: str,
              date: str, seq: str) -> None:
    if client:
        client_dir = f"{remote_dir}/{client}"
    else:
        # No id given: fall back to the single client when exactly one exists.
        out = ssh(host, key,
                  f"ls -1d {remote_dir}/* 2>/dev/null || true").split()
        if not out:
            print("(no client folders yet — nothing has ever uploaded)")
            return
        if len(out) != 1:
            print("multiple clients exist — pass --client (see `list`)")
            return
        client_dir = out[0]
    remote_path = f"{client_dir}/{date}.p{seq}.log.gz"
    b64 = ssh(host, key, f"base64 -w0 {remote_path} 2>/dev/null", timeout=120)
    if not b64.strip():
        print(f"(no such artifact: {remote_path} — run `list` for real names)")
        return
    try:
        blob = gzip.decompress(base64.b64decode(b64.strip()))
    except (ValueError, OSError) as e:
        raise RuntimeError(f"could not decode {remote_path}: {e}")
    sys.stdout.write(blob.decode("utf-8", errors="replace"))


def pull(host: str, key: str, remote_dir: str, client: str, out: str) -> None:
    out = os.path.abspath(out)
    os.makedirs(out, exist_ok=True)
    key_path = os.path.expanduser(key)
    src = f"{host}:{remote_dir}/{client}/"
    cmd = ["scp", "-i", key_path, "-o", "BatchMode=yes",
           "-o", "StrictHostKeyChecking=accept-new", "-r", src, out]
    proc = subprocess.run(cmd, capture_output=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"scp failed: {proc.stderr.strip()[:400]}")
    print(f"copied to {os.path.join(out, os.path.basename(client.rstrip('/')))}")


def main():
    ap = argparse.ArgumentParser(description="Full-log upload index / reader (VPS-backed).")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--client", default=None, help="client id (prefix ok on list, exact for cat/pull)")
        p.add_argument("--ip", default=None, help="filter by public IP")
        p.add_argument("--host", default=DEFAULT_HOST)
        p.add_argument("--key", default=DEFAULT_KEY)
        p.add_argument("--dir", default=DEFAULT_REMOTE_DIR, help=argparse.SUPPRESS)

    sl = sub.add_parser("list", help="upload timeline across clients (newest first)")
    common(sl)
    sl.add_argument("--n", type=int, default=40, help="rows shown (default 40)")

    cp = sub.add_parser("cat", help="print one decompressed log chunk")
    common(cp)
    cp.add_argument("--date", required=True, help="log date, e.g. 2026-10-15")
    cp.add_argument("--seq", required=True, help="upload part number")

    pp = sub.add_parser("pull", help="scp a client's whole folder locally")
    common(pp)
    pp.add_argument("--out", default="./client-logs")

    args = ap.parse_args()

    if args.cmd == "list":
        entries = load_index(args.host, args.key, args.dir)
        if args.client:
            entries = [e for e in entries
                       if str(e.get("_client", "")).startswith(args.client)]
        if args.ip:
            entries = [e for e in entries if e.get("ip") == args.ip]
        entries.sort(key=lambda e: e.get("ts", ""), reverse=True)
        if not entries:
            print("(no uploads recorded)")
            return
        for rec in entries[:args.n]:
            print(render(rec))
        if len(entries) > args.n:
            print(f"(… {len(entries) - args.n} more)")
    elif args.cmd == "cat":
        cat_chunk(args.host, args.key, args.dir, args.client,
                  args.date, args.seq)
    elif args.cmd == "pull":
        if not args.client:
            ap.error("pull needs --client")
        pull(args.host, args.key, args.dir, args.client, args.out)


if __name__ == "__main__":
    main()
