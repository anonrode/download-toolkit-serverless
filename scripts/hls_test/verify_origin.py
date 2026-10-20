#!/usr/bin/env python3
"""Black-box self-test for a running HLS test origin (stdlib only).

Run ON the VPS (or anywhere that can reach the origin):
  python3 verify_origin.py --base http://127.0.0.1:8099/hls-test \
      --secret-file /home/Anon/hls-test-origin/secret

Checks: master 200, variant 200 + 300 segment lines, segment 200, range 206
with Content-Range, bad token 403, expired token 403, per-IP quota 403.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from tokenlib import load_or_create_secret, mint_token


def get(url: str, headers: dict[str, str] | None = None, read_limit: int = 4096) -> tuple[int, dict, bytes]:
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            return res.status, {k.lower(): v for k, v in res.headers.items()}, res.read(read_limit)
    except urllib.error.HTTPError as exc:
        return exc.code, {k.lower(): v for k, v in exc.headers.items()}, exc.read(2048)



def get_full(url: str) -> tuple[int, dict, bytes, float]:
    started = time.monotonic()
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            data = res.read()
            return res.status, {k.lower(): v for k, v in res.headers.items()}, data, time.monotonic() - started
    except urllib.error.HTTPError as exc:
        return exc.code, {k.lower(): v for k, v in exc.headers.items()}, exc.read(2048), time.monotonic() - started


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8099/hls-test")
    ap.add_argument("--secret-file", default="/home/Anon/hls-test-origin/secret")
    ap.add_argument("--run", default="selftest")
    ap.add_argument("--ttl", type=float, default=10)
    args = ap.parse_args()

    secret = load_or_create_secret(Path(args.secret_file))
    base = args.base.rstrip("/")
    token = mint_token(secret, args.run, 1, 8, args.ttl)
    master_url = f"{base}/r/{args.run}/master.m3u8?t={token}"
    failures: list[str] = []

    code, headers, body = get(master_url)
    print(f"master: {code}")
    if code != 200:
        failures.append(f"master {code}")
    text = body.decode("utf-8", "replace")
    variant_url = next((ln.strip() for ln in text.splitlines()
                        if ln.strip() and not ln.startswith("#")), "")
    if not variant_url:
        failures.append("master has no variant URI")
        variant_url = f"{base}/r/{args.run}/v0/index.m3u8?t={token}"

    code, _, body = get(variant_url, read_limit=2_000_000)
    variant_text = body.decode("utf-8", "replace")
    seg_lines = [ln.strip() for ln in variant_text.splitlines()
                 if ln.strip() and not ln.startswith("#")]
    print(f"variant: {code}, segments={len(seg_lines)}")
    if code != 200:
        failures.append(f"variant {code}")
    if len(seg_lines) != 300:
        failures.append(f"segment count {len(seg_lines)} != 300")
    if not seg_lines:
        print(json.dumps({"ok": False, "failures": failures}))
        return 1

    code, headers, body = get(seg_lines[0], read_limit=2048)
    print(f"segment: {code}, content-length={headers.get('content-length')}")
    if code != 200:
        failures.append(f"segment {code}")

    code, headers, body = get(seg_lines[0], headers={"Range": "bytes=0-1024"}, read_limit=2048)
    print(f"range: {code}, content-range={headers.get('content-range')}")
    if code != 206 or not str(headers.get("content-range", "")).startswith("bytes 0-1024/"):
        failures.append("range probe did not return 206 + Content-Range")

    code, headers, _ = get(f"{base}/r/{args.run}/master.m3u8?t=bad.token")
    print(f"bad token: {code} {headers.get('x-hls-reason')}")
    if code != 403:
        failures.append("bad token not 403")

    expired = mint_token(secret, args.run + "-exp", 1, 8, -1)
    code, headers, _ = get(f"{base}/r/{args.run}-exp/master.m3u8?t={expired}")
    print(f"expired token: {code} {headers.get('x-hls-reason')}")
    if code != 403 or headers.get("x-hls-reason") != "token_expired":
        failures.append("expired token not 403 token_expired")

    # Per-IP quota: the first master/variant requests already bank bytes, so a
    # quota of 1 byte must deny the next segment request.
    quota_token = mint_token(secret, args.run + "-quota", 1, 8, args.ttl, quota_bytes=1)
    q_master = f"{base}/r/{args.run}-quota/master.m3u8?t={quota_token}"
    code, _, _ = get(q_master)
    if code != 200:
        failures.append(f"quota master {code}")
    code, headers, _ = get(f"{base}/r/{args.run}-quota/v0/seg_000.ts?t={quota_token}")
    print(f"quota segment: {code} {headers.get('x-hls-reason')}")
    if code != 403 or headers.get("x-hls-reason") != "quota_ip":
        failures.append("quota did not return 403 quota_ip")


    # Per-task after-N: the master request banks bytes, so ab=1 denies the
    # first segment with after_n_bytes.
    after_token = mint_token(secret, args.run + "-after", 1, 8, args.ttl, after_bytes=1)
    a_master = f"{base}/r/{args.run}-after/master.m3u8?t={after_token}"
    code, _, _ = get(a_master)
    if code != 200:
        failures.append(f"after master {code}")
    code, headers, _ = get(f"{base}/r/{args.run}-after/v0/seg_000.ts?t={after_token}")
    print(f"after-N segment: {code} {headers.get('x-hls-reason')}")
    if code != 403 or headers.get("x-hls-reason") != "after_n_bytes":
        failures.append("after-N did not return 403 after_n_bytes")

    # Per-connection pacing: 48 KiB/s on a ~354 KB segment must take >5 s.
    rate_token = mint_token(secret, args.run + "-rate", 1, 8, args.ttl, rate_bps=48 * 1024)
    r_url = f"{base}/r/{args.run}-rate/v0/seg_000.ts?t={rate_token}"
    code, headers, data, elapsed = get_full(r_url)
    print(f"rate segment: {code} bytes={len(data)} elapsed={elapsed:.1f}s")
    if code != 200 or len(data) < 100_000:
        failures.append(f"rate segment failed ({code}, {len(data)} bytes)")
    if elapsed < 5.0:
        failures.append(f"rate pacing too fast: {elapsed:.1f}s")

    result = {"ok": not failures, "failures": failures, "ts": int(time.time())}
    print(json.dumps(result))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
