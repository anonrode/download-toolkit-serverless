#!/usr/bin/env python3
"""Send an FCM HTTP v1 topic message from the VPS (stdlib + cryptography).

The service-account JSON lives ONLY on the VPS:
  /home/Anon/.secrets/fcm-service-account.json  (mode 0600)
Never copy it into the repo, Desktop, or any build artifact. This script
prints no credential material — only the FCM response name or error text.

Examples:
  python3 send_topic.py --dry-run
  python3 send_topic.py --data-only
  python3 send_topic.py --title "Anonrode test" --body "FCM delivery works"
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
except Exception as exc:  # pragma: no cover - VPS has cryptography installed
    print(f"cryptography is required: {exc}", file=sys.stderr)
    raise SystemExit(2)

DEFAULT_CREDENTIALS = "/home/Anon/.secrets/fcm-service-account.json"
DEFAULT_PROJECT = "anonrode-d591f"
DEFAULT_TOPIC = "anon_test"
DEFAULT_CHANNEL = "anon_announcements"


def b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def access_token(credentials: dict) -> str:
    now = int(time.time())
    header = b64e(json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":")).encode())
    token_uri = credentials.get("token_uri") or "https://oauth2.googleapis.com/token"
    claims = {
        "iss": credentials["client_email"],
        "scope": "https://www.googleapis.com/auth/firebase.messaging",
        "aud": token_uri,
        "iat": now,
        "exp": now + 3600,
    }
    payload = b64e(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode("ascii")
    key = serialization.load_pem_private_key(
        credentials["private_key"].encode("utf-8"), password=None
    )
    signature = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    assertion = f"{header}.{payload}.{b64e(signature)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode()
    req = urllib.request.Request(
        token_uri,
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as res:
        return json.loads(res.read().decode("utf-8"))["access_token"]


def build_payload(args: argparse.Namespace) -> dict:
    data = {"type": args.type, "ts": str(int(time.time()))}
    message = {
        "topic": args.topic,
        "data": data,
        "android": {"priority": "high"},
    }
    if not args.data_only:
        message["notification"] = {"title": args.title, "body": args.body}
        message["android"]["notification"] = {"channel_id": args.channel}
    return {"message": message}


def main() -> int:
    ap = argparse.ArgumentParser(description="Send an FCM HTTP v1 topic message.")
    ap.add_argument("--credentials", default=DEFAULT_CREDENTIALS)
    ap.add_argument("--project", default=DEFAULT_PROJECT)
    ap.add_argument("--topic", default=DEFAULT_TOPIC)
    ap.add_argument("--type", default="fcm_test")
    ap.add_argument("--title", default="Anonrode test")
    ap.add_argument("--body", default="FCM test notification")
    ap.add_argument("--channel", default=DEFAULT_CHANNEL)
    ap.add_argument("--data-only", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    payload = build_payload(args)
    if args.dry_run:
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    path = Path(args.credentials)
    if not path.exists():
        print(f"credentials not found: {path}", file=sys.stderr)
        return 2
    credentials = json.loads(path.read_text(encoding="utf-8"))
    try:
        token = access_token(credentials)
    except Exception as exc:
        print(f"token acquisition failed: {type(exc).__name__}", file=sys.stderr)
        return 1

    url = f"https://fcm.googleapis.com/v1/projects/{urllib.parse.quote(args.project)}/messages:send"
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            body = json.loads(res.read().decode("utf-8"))
            print(json.dumps({"ok": True, "name": body.get("name", "")}))
            return 0
    except urllib.error.HTTPError as exc:
        detail = exc.read(2048).decode("utf-8", "replace")
        print(json.dumps({"ok": False, "status": exc.code, "detail": detail[:1500]}))
        return 1
    except Exception as exc:
        print(json.dumps({"ok": False, "error": type(exc).__name__}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
