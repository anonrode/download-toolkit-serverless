"""ota_mirror_server.py — standalone stdlib fallback mirror for the OTA payloads.

Serves the SAME signed files the app normally pulls from GitHub:

    /ota/scraper_rules.json.enc   (the signed AES-CBC rules envelope)
    /ota/latest.json              ({"version": ..., "assets": [...]} release metadata)

from a local directory (default: ./ota_files next to this script). The app
verifies the ECDSA signature AFTER download (transport never trusted), so the
mirror is a dumb byte pipe.

Usage:
    python ota_mirror_server.py [--dir DIR] [--port 8100] [--bind 127.0.0.1]

NOTE for 68.155.146.145: on that box the payloads are served by the EXISTING
uvicorn/FastAPI service (anon-server.service on port 8000) via a `app.mount
('/ota', StaticFiles(...))` block — see scripts/ota_mirror/DEPLOY.md. This
standalone server exists for a machine where you want the mirror WITHOUT
touching an existing service (or for local testing):
    python ota_mirror_server.py --port 8100
    curl -s http://127.0.0.1:8100/ota/scraper_rules.json.enc | sha256sum

Behavior:
  - correct Content-Type per extension (.enc octet-stream, .json application/json)
  - hard no-cache headers (a CDN/edge in front of this must revalidate)
  - GET + HEAD only, everything else 405
  - path-traversal guard (only files inside --dir are served)
  - 404 with a plain body when the payload file does not exist yet (sync script
    has not run) — the app treats any non-2xx as a chain failure and moves on.
"""
import argparse
import os
import posixpath
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONTENT_TYPES = {
    ".enc": "application/octet-stream",
    ".json": "application/json; charset=utf-8",
}
DEFAULT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ota_files")


def make_handler(serve_dir: str):
    class OtaMirrorHandler(BaseHTTPRequestHandler):
        server_version = "AnonOtaMirror/1.0"

        def _resolve(self):
            """Returns (fs_path, error_response). Only /ota/<name> with a safe
            flat name is allowed — no subdirectories, no traversal."""
            if not (self.path == "/ota" or self.path.startswith("/ota/")):
                return None, (404, b"only /ota/* is served here\n")
            rel = posixpath.normpath(self.path[len("/ota/"):].split("?", 1)[0].split("#", 1)[0])
            rel = rel.lstrip("/")
            if not rel or "/" in rel or "\\" in rel or rel.startswith("."):
                return None, (404, b"no such OTA file\n")
            fs_path = os.path.abspath(os.path.join(serve_dir, rel))
            if not fs_path.startswith(os.path.abspath(serve_dir) + os.sep):
                return None, (404, b"no such OTA file\n")
            return fs_path, None

        def _send(self, include_body: bool):
            fs_path, err = self._resolve()
            if err is not None:
                code, body = err
                self.send_response(code)
                self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
                self.send_header("Pragma", "no-cache")
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if include_body:
                    self.wfile.write(body)
                return
            if not os.path.isfile(fs_path):
                body = b"OTA payload not yet synced\n"
                self.send_response(404)
                self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if include_body:
                    self.wfile.write(body)
                return
            try:
                with open(fs_path, "rb") as f:
                    data = f.read()
            except OSError:
                self.send_response(500)
                self.end_headers()
                return
            ext = os.path.splitext(fs_path)[1].lower()
            self.send_response(200)
            self.send_header("Content-Type", CONTENT_TYPES.get(ext, "application/octet-stream"))
            self.send_header("Content-Length", str(len(data)))
            # Hard no-cache: rules payloads change whenever the repo does.
            self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
            self.send_header("Pragma", "no-cache")
            self.end_headers()
            if include_body:
                self.wfile.write(data)

        def do_GET(self):
            self._send(include_body=True)

        def do_HEAD(self):
            self._send(include_body=False)

        def do_POST(self):
            body = b"405 method not allowed\n"
            self.send_response(405)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):  # stdout, one line per request
            sys.stderr.write("[ota-mirror] %s %s\n" % (self.address_string(), fmt % args))

    return OtaMirrorHandler


def main():
    ap = argparse.ArgumentParser(description="Anonrode OTA payload mirror (stdlib-only).")
    ap.add_argument("--dir", default=DEFAULT_DIR, help="directory holding scraper_rules.json.enc / latest.json")
    ap.add_argument("--port", type=int, default=8100)
    ap.add_argument("--bind", default="127.0.0.1", help="bind address; use 0.0.0.0 to expose")
    args = ap.parse_args()

    serve_dir = os.path.abspath(args.dir)
    if not os.path.isdir(serve_dir):
        os.makedirs(serve_dir, exist_ok=True)
    handler = make_handler(serve_dir)
    httpd = ThreadingHTTPServer((args.bind, args.port), handler)
    print(f"[ota-mirror] serving {serve_dir} on http://{args.bind}:{args.port}/ota/ (Ctrl+C to stop)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
