#!/usr/bin/env python3
"""Mint a synthetic HLS test token and print the master URL.

The secret lives only in the origin's secret file (default
/home/Anon/hls-test-origin/secret, mode 0600). It is never printed.

Examples:
  python3 mint_token.py --run A1 --task 1 --lanes 4 --ttl 10 \
      --url-base http://68.155.146.145/hls-test
  python3 mint_token.py --run C1 --task 1 --lanes 8 --ttl 10 \
      --quota-mb 60 --rate-kib 0 --json
  python3 mint_token.py --run B1 --task 1 --lanes 8 --ttl 2 \
      --rate-kib 96 --json
"""
import argparse
import json
import sys
from pathlib import Path

from tokenlib import load_or_create_secret, mint_token


def main() -> int:
    ap = argparse.ArgumentParser(description="Mint an HMAC token for the HLS test origin.")
    ap.add_argument("--run", required=True)
    ap.add_argument("--task", type=int, default=1)
    ap.add_argument("--lanes", type=int, default=8)
    ap.add_argument("--ttl", type=float, default=10.0, help="token TTL in minutes")
    ap.add_argument("--quota-mb", type=float, default=0.0, help="per-IP quota; 0 = off")
    ap.add_argument("--after-mb", type=float, default=0.0, help="per-task after-N; 0 = off")
    ap.add_argument("--rate-kib", type=float, default=0.0, help="per-connection KiB/s; 0 = off")
    ap.add_argument("--quota-bytes", type=int, default=0)
    ap.add_argument("--after-bytes", type=int, default=0)
    ap.add_argument("--rate-bps", type=int, default=0)
    ap.add_argument("--secret-file", default="/home/Anon/hls-test-origin/secret")
    ap.add_argument("--url-base", default="http://68.155.146.145/hls-test")
    ap.add_argument("--init-secret", action="store_true",
                    help="create the secret file if absent (mode 0600)")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    secret_path = Path(args.secret_file)
    if not secret_path.exists() and not args.init_secret:
        print(f"secret not found: {secret_path} (run once with --init-secret)", file=sys.stderr)
        return 2
    secret = load_or_create_secret(secret_path)

    quota = args.quota_bytes or int(args.quota_mb * 1024 * 1024)
    after = args.after_bytes or int(args.after_mb * 1024 * 1024)
    rate = args.rate_bps or int(args.rate_kib * 1024)
    token = mint_token(
        secret=secret,
        run=args.run,
        task=args.task,
        lanes=args.lanes,
        ttl_minutes=args.ttl,
        quota_bytes=quota,
        after_bytes=after,
        rate_bps=rate,
    )
    master = f"{args.url_base.rstrip('/')}/r/{args.run}/master.m3u8?t={token}"
    out = {
        "run": args.run,
        "task": args.task,
        "lanes": args.lanes,
        "ttl_minutes": args.ttl,
        "quota_bytes": quota,
        "after_bytes": after,
        "rate_bps": rate,
        "token": token,
        "url": master,
    }
    if args.json:
        print(json.dumps(out, separators=(",", ":")))
    else:
        print(master)
    return 0


if __name__ == "__main__":
    sys.exit(main())
