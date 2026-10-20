#!/usr/bin/env python3
"""HMAC token helpers shared by origin.py and mint_token.py (stdlib only)."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from pathlib import Path
from typing import Any


def b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def load_or_create_secret(path: Path) -> bytes:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        data = path.read_bytes().strip()
        if len(data) >= 32:
            return data
        raise SystemExit(f"secret file too short: {path}")
    data = secrets.token_bytes(32)
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    return data


def mint_token(
    secret: bytes,
    run: str,
    task: int,
    lanes: int,
    ttl_minutes: float,
    quota_bytes: int = 0,
    after_bytes: int = 0,
    rate_bps: int = 0,
    now: float | None = None,
) -> str:
    issued = time.time() if now is None else now
    claims = {
        "r": run,
        "tk": int(task),
        "ln": int(lanes),
        "iat": int(issued),
        "exp": int(issued + float(ttl_minutes) * 60),
        "qb": int(quota_bytes),
        "ab": int(after_bytes),
        "rb": int(rate_bps),
        "n": secrets.token_hex(8),
    }
    payload = b64e(json.dumps(claims, separators=(",", ":"), sort_keys=True).encode())
    sig = b64e(hmac.new(secret, payload.encode("ascii"), hashlib.sha256).digest())
    return f"{payload}.{sig}"


def verify_token(secret: bytes, token: str, expected_run: str) -> dict[str, Any]:
    if not token or "." not in token:
        raise ValueError("bad_token")
    payload, sig = token.split(".", 1)
    expected = b64e(hmac.new(secret, payload.encode("ascii"), hashlib.sha256).digest())
    if not hmac.compare_digest(expected, sig):
        raise ValueError("bad_token")
    try:
        claims = json.loads(b64d(payload).decode("utf-8"))
    except Exception as exc:
        raise ValueError("bad_token") from exc
    if claims.get("r") != expected_run:
        raise ValueError("bad_token")
    if int(claims.get("exp", 0)) <= int(time.time()):
        raise ValueError("token_expired")
    return claims
