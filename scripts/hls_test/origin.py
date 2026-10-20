#!/usr/bin/env python3
"""Synthetic HLS test origin (VPS test content only).

Nothing here scrapes, proxies, or downloads third-party media. The media is
generated locally by generate_media.sh from ffmpeg's testsrc2 + sine sources.

Run standalone (recommended on the VPS test port):

    HLS_TEST_SECRET_FILE=/home/Anon/hls-test-origin/secret \
    python3 origin.py --host 0.0.0.0 --port 8099 \
        --media-dir /home/Anon/hls-test-origin/media \
        --state-dir /home/Anon/hls-test-origin/runs

Env-gated mount (only when an operator deliberately exposes it through the
existing :80 FastAPI process):

    import os
    if os.environ.get("ANON_HLS_TEST") == "1":
        from origin import origin_app
        app.mount("/hls-test", origin_app)

Routes (mounted under /hls-test; standalone at /):
  GET  /r/<run>/master.m3u8?t=<token>
  GET  /r/<run>/v0/index.m3u8?t=<token>
  GET  /r/<run>/v0/seg_NNN.ts?t=<token>   (Range supported)
  GET  /health
  POST /admin/reset?run=<run>            (localhost only)
  GET  /admin/summary?run=<run>          (localhost only)

Token claims (HMAC-SHA256, base64url): r run, tk task, ln lanes, iat, exp,
qb per-IP quota bytes, ab per-task after-N bytes, rb per-connection bytes/s,
n nonce. A zero qb/ab/rb disables that control.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import threading
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, AsyncIterator

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, PlainTextResponse, Response, StreamingResponse

from tokenlib import load_or_create_secret, verify_token

CHUNK_BYTES = 32 * 1024
SEGMENT_RE = re.compile(r"^seg_(\d{3})\.ts$")
RUN_RE = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")


def parse_range(header: str | None, size: int) -> tuple[int, int] | None:
    """Inclusive (start, end), or None for no range. Raises ValueError on 416."""
    if not header:
        return None
    match = RANGE_RE.match(header.strip())
    if not match:
        raise ValueError("bad_range")
    first, last = match.group(1), match.group(2)
    if first == "" and last == "":
        raise ValueError("bad_range")
    if first == "":
        length = int(last)
        if length <= 0:
            raise ValueError("bad_range")
        start = max(0, size - length)
        return start, size - 1
    start = int(first)
    end = int(last) if last else size - 1
    if start >= size or end < start:
        raise ValueError("bad_range")
    return start, min(end, size - 1)


class RunState:
    def __init__(self, run: str, run_dir: Path):
        self.run = run
        self.run_dir = run_dir
        self.log_path = run_dir / "requests.jsonl"
        self.lock = threading.Lock()
        self.ip_bytes: dict[str, int] = defaultdict(int)
        self.task_bytes: dict[int, int] = defaultdict(int)
        self.inflight = 0
        self.max_inflight = 0
        self.first_block_ts: float | None = None
        self.blocked_requests = 0
        self.requests_after_block = 0

    def begin(self) -> None:
        with self.lock:
            self.inflight += 1
            self.max_inflight = max(self.max_inflight, self.inflight)

    def end(self) -> None:
        with self.lock:
            self.inflight = max(0, self.inflight - 1)

    def add_bytes(self, ip: str, task: int, count: int) -> None:
        if count <= 0:
            return
        with self.lock:
            self.ip_bytes[ip] += count
            self.task_bytes[task] += count

    def counters(self, ip: str, task: int) -> tuple[int, int, int]:
        with self.lock:
            return self.ip_bytes[ip], self.task_bytes[task], self.inflight

    def record(self, entry: dict[str, Any]) -> None:
        with self.lock:
            if entry.get("status") == 403:
                self.blocked_requests += 1
                if self.first_block_ts is None:
                    self.first_block_ts = float(entry["ts"])
                elif float(entry["ts"]) - self.first_block_ts <= 60.0:
                    self.requests_after_block += 1
            line = json.dumps(entry, separators=(",", ":"), sort_keys=True)
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(line + "\n")


class Origin:
    def __init__(self, media_dir: Path, state_dir: Path, secret: bytes):
        self.media_dir = Path(media_dir)
        self.state_dir = Path(state_dir)
        self.secret = secret
        self.states: dict[str, RunState] = {}
        self.states_lock = threading.Lock()
        self.info = self._load_info()
        self.variant_text = (self.media_dir / "v0" / "index.m3u8").read_text(encoding="utf-8")
        self.segment_sizes = self._segment_sizes()

    def _load_info(self) -> dict[str, Any]:
        info_path = self.media_dir / "manifest.json"
        if info_path.exists():
            return json.loads(info_path.read_text(encoding="utf-8"))
        segs = sorted((self.media_dir / "v0").glob("seg_*.ts"))
        return {
            "segment_count": len(segs),
            "total_bytes": sum(p.stat().st_size for p in segs),
            "duration_seconds": len(segs) * 2,
            "resolution": "640x360",
        }

    def _segment_sizes(self) -> dict[str, int]:
        return {
            p.name: p.stat().st_size
            for p in sorted((self.media_dir / "v0").glob("seg_*.ts"))
        }

    def state(self, run: str) -> RunState:
        with self.states_lock:
            st = self.states.get(run)
            if st is None:
                run_dir = self.state_dir / run
                run_dir.mkdir(parents=True, exist_ok=True)
                st = RunState(run, run_dir)
                self.states[run] = st
            return st

    def reset(self, run: str) -> None:
        with self.states_lock:
            st = self.states.pop(run, None)
        run_dir = self.state_dir / run
        run_dir.mkdir(parents=True, exist_ok=True)
        log = run_dir / "requests.jsonl"
        if log.exists():
            log.unlink()
        self.states.pop(run, None)


def _base_url(request: Request) -> str:
    host = request.headers.get("host") or request.url.netloc
    root = request.scope.get("root_path", "") or ""
    return f"{request.url.scheme}://{host}{root}".rstrip("/")


def _deny(state: RunState, claims: dict[str, Any] | None, reason: str, ip: str,
          path: str, task: int = 0, lanes: int = 0, extra: dict[str, Any] | None = None) -> JSONResponse:
    st = state
    ts = time.time()
    ip_bytes, task_bytes, inflight = st.counters(ip, task)
    entry = {
        "ts": round(ts, 3),
        "run": st.run,
        "task": task,
        "lanes": lanes,
        "ip": ip,
        "path": path,
        "kind": "denied",
        "status": 403,
        "reason": reason,
        "bytes": 0,
        "token_iat": (claims or {}).get("iat"),
        "token_exp": (claims or {}).get("exp"),
        "bytes_task": task_bytes,
        "bytes_ip": ip_bytes,
        "inflight": inflight,
    }
    if extra:
        entry.update(extra)
    # A denied request is not a segment success; no segment coverage add.
    st.record(entry)
    return JSONResponse(
        status_code=403,
        content={"reason": reason, "run": st.run, "task": task},
        headers={"X-HLS-Reason": reason},
    )


def _claims_from(request: Request, run: str, secret: bytes) -> tuple[dict[str, Any] | None, str | None]:
    token = request.query_params.get("t", "")
    try:
        return verify_token(secret, token, run), None
    except ValueError as exc:
        return None, str(exc)


def create_origin_app(media_dir: Path, state_dir: Path, secret: bytes) -> FastAPI:
    origin = Origin(media_dir, state_dir, secret)
    app = FastAPI(title="Anon HLS test origin", docs_url=None, redoc_url=None)

    def run_state_and_claims(request: Request, run: str):
        if not RUN_RE.match(run):
            return origin.state("_invalid"), None, "bad_run"
        claims, err = _claims_from(request, run, secret)
        if err is not None:
            st = origin.state(run)
            return st, None, err
        return origin.state(run), claims, None

    @app.get("/health")
    def health():
        return {"ok": True, "info": origin.info, "segments": len(origin.segment_sizes)}

    @app.post("/admin/reset")
    def admin_reset(request: Request, run: str):
        if not _is_local(request):
            return JSONResponse(status_code=403, content={"reason": "admin_local_only"})
        if not RUN_RE.match(run):
            return JSONResponse(status_code=400, content={"reason": "bad_run"})
        origin.reset(run)
        return {"ok": True, "run": run, "reset": True}

    @app.get("/admin/summary")
    def admin_summary(request: Request, run: str):
        if not _is_local(request):
            return JSONResponse(status_code=403, content={"reason": "admin_local_only"})
        if not RUN_RE.match(run):
            return JSONResponse(status_code=400, content={"reason": "bad_run"})
        return summarize_run(origin.state_dir / run)

    @app.get("/r/{run}/master.m3u8")
    def master(request: Request, run: str):
        st, claims, err = run_state_and_claims(request, run)
        if err is not None:
            return _deny(st, None, err, _ip(request), request.url.path)
        assert claims is not None
        ip = _ip(request)
        ip_bytes, task_bytes, inflight = st.counters(ip, int(claims["tk"]))
        if int(claims.get("qb", 0)) > 0 and ip_bytes >= int(claims["qb"]):
            return _deny(st, claims, "quota_ip", ip, request.url.path, int(claims["tk"]), int(claims["ln"]))
        if int(claims.get("ab", 0)) > 0 and task_bytes >= int(claims["ab"]):
            return _deny(st, claims, "after_n_bytes", ip, request.url.path, int(claims["tk"]), int(claims["ln"]))
        base = _base_url(request)
        variant = f"{base}/r/{run}/v0/index.m3u8?t={_token_from(request)}"
        bandwidth = max(1, int(origin.info.get("total_bytes", 0) * 8 / max(1, origin.info.get("duration_seconds", 1))))
        body = (
            "#EXTM3U\n"
            "#EXT-X-VERSION:3\n"
            f"#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},RESOLUTION={origin.info.get('resolution', '640x360')}\n"
            f"{variant}\n"
        )
        data = body.encode()
        st.add_bytes(ip, int(claims["tk"]), len(data))
        _record_ok(st, claims, ip, request.url.path, "playlist", len(data), 200, None)
        return PlainTextResponse(body, media_type="application/vnd.apple.mpegurl")

    @app.get("/r/{run}/v0/index.m3u8")
    def variant(request: Request, run: str):
        st, claims, err = run_state_and_claims(request, run)
        if err is not None:
            return _deny(st, None, err, _ip(request), request.url.path)
        assert claims is not None
        ip = _ip(request)
        ip_bytes, task_bytes, inflight = st.counters(ip, int(claims["tk"]))
        if int(claims.get("qb", 0)) > 0 and ip_bytes >= int(claims["qb"]):
            return _deny(st, claims, "quota_ip", ip, request.url.path, int(claims["tk"]), int(claims["ln"]))
        if int(claims.get("ab", 0)) > 0 and task_bytes >= int(claims["ab"]):
            return _deny(st, claims, "after_n_bytes", ip, request.url.path, int(claims["tk"]), int(claims["ln"]))
        base = _base_url(request)
        token = _token_from(request)
        out = []
        for line in origin.variant_text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            if stripped.startswith("#"):
                if stripped.startswith("#EXT-X-ENDLIST"):
                    continue
                out.append(stripped)
            else:
                out.append(f"{base}/r/{run}/v0/{stripped}?t={token}")
        out.append("#EXT-X-ENDLIST")
        body = "\n".join(out) + "\n"
        data = body.encode()
        st.add_bytes(ip, int(claims["tk"]), len(data))
        _record_ok(st, claims, ip, request.url.path, "playlist", len(data), 200, None)
        return PlainTextResponse(body, media_type="application/vnd.apple.mpegurl")

    @app.get("/r/{run}/v0/{name}")
    @app.head("/r/{run}/v0/{name}")
    async def segment(request: Request, run: str, name: str):
        st, claims, err = run_state_and_claims(request, run)
        if err is not None:
            return _deny(st, None, err, _ip(request), request.url.path)
        assert claims is not None
        if not SEGMENT_RE.match(name):
            return _deny(st, claims, "bad_segment", _ip(request), request.url.path, int(claims["tk"]), int(claims["ln"]))
        path = origin.media_dir / "v0" / name
        if not path.exists():
            return _deny(st, claims, "not_found", _ip(request), request.url.path, int(claims["tk"]), int(claims["ln"]))
        size = path.stat().st_size
        ip = _ip(request)
        task = int(claims["tk"])
        ip_bytes, task_bytes, inflight = st.counters(ip, task)
        if int(claims.get("qb", 0)) > 0 and ip_bytes >= int(claims["qb"]):
            return _deny(st, claims, "quota_ip", ip, request.url.path, task, int(claims["ln"]))
        if int(claims.get("ab", 0)) > 0 and task_bytes >= int(claims["ab"]):
            return _deny(st, claims, "after_n_bytes", ip, request.url.path, task, int(claims["ln"]))
        try:
            rng = parse_range(request.headers.get("range"), size)
        except ValueError:
            return Response(status_code=416, headers={"Content-Range": f"bytes */{size}"})
        if rng is None:
            start, end = 0, size - 1
            status = 200
        else:
            start, end = rng
            status = 206
        length = end - start + 1
        is_probe = request.headers.get("range") is not None and length <= 4096
        kind = "probe" if is_probe else "segment"
        rate = int(claims.get("rb", 0))
        headers = {
            "Content-Length": str(length),
            "Accept-Ranges": "bytes",
            "Cache-Control": "no-store",
        }
        if status == 206:
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"

        if request.method == "HEAD":
            _record_ok(st, claims, ip, request.url.path, kind, 0, status, None,
                        segment=name, range_header=request.headers.get("range"), content_length=length)
            return Response(status_code=status, media_type="video/mp2t", headers=headers)

        st.begin()

        async def body_stream() -> AsyncIterator[bytes]:
            sent = 0
            started = time.monotonic()
            status_code = status
            reason = None
            try:
                with open(path, "rb") as f:
                    f.seek(start)
                    remaining = length
                    while remaining > 0:
                        chunk = f.read(min(CHUNK_BYTES, remaining))
                        if not chunk:
                            break
                        chunk_started = time.monotonic()
                        yield chunk
                        sent += len(chunk)
                        remaining -= len(chunk)
                        st.add_bytes(ip, task, len(chunk))
                        if rate > 0:
                            target = len(chunk) / float(rate)
                            elapsed = time.monotonic() - chunk_started
                            if target > elapsed:
                                await asyncio.sleep(target - elapsed)
                status_code = status
                reason = None
            except asyncio.CancelledError:
                status_code = status
                reason = "client_disconnect"
                raise
            finally:
                duration_ms = int((time.monotonic() - started) * 1000)
                _record_ok(st, claims, ip, request.url.path, kind, sent, status_code, duration_ms,
                            segment=name, range_header=request.headers.get("range"), content_length=length,
                            note=reason)
                st.end()

        return StreamingResponse(body_stream(), status_code=status, media_type="video/mp2t", headers=headers)

    return app


def _ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def _is_local(request: Request) -> bool:
    host = _ip(request)
    return host in ("127.0.0.1", "::1", "localhost")


def _token_from(request: Request) -> str:
    return request.query_params.get("t", "")


def _record_ok(st: RunState, claims: dict[str, Any], ip: str, path: str, kind: str,
               sent: int, status: int, duration_ms: int | None, segment: str | None = None,
               range_header: str | None = None, content_length: int | None = None,
               note: str | None = None) -> None:
    task = int(claims["tk"])
    ip_bytes, task_bytes, inflight = st.counters(ip, task)
    entry = {
        "ts": round(time.time(), 3),
        "run": st.run,
        "task": task,
        "lanes": int(claims["ln"]),
        "ip": ip,
        "path": path,
        "kind": kind,
        "segment": segment,
        "range": range_header,
        "status": status,
        "reason": note,
        "bytes": int(sent),
        "content_length": content_length,
        "duration_ms": int(duration_ms) if duration_ms is not None else None,
        "token_iat": claims.get("iat"),
        "token_exp": claims.get("exp"),
        "bytes_task": task_bytes,
        "bytes_ip": ip_bytes,
        "inflight": inflight,
    }
    st.record(entry)


def summarize_run(run_dir: Path) -> dict[str, Any]:
    log = Path(run_dir) / "requests.jsonl"
    rows: list[dict[str, Any]] = []
    if log.exists():
        with open(log, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except ValueError:
                    continue
    status_counts: dict[str, int] = defaultdict(int)
    reasons: dict[str, int] = defaultdict(int)
    per_task: dict[int, int] = defaultdict(int)
    per_ip: dict[str, int] = defaultdict(int)
    segments: set[str] = set()
    duplicates = 0
    durations: list[int] = []
    first_block: float | None = None
    after_block = 0
    for r in rows:
        status_counts[str(r.get("status"))] += 1
        if r.get("reason"):
            reasons[str(r["reason"])] += 1
        per_task[int(r.get("task", 0))] += int(r.get("bytes", 0))
        per_ip[str(r.get("ip", "?"))] += int(r.get("bytes", 0))
        if r.get("kind") in ("segment", "probe") and r.get("segment"):
            seg = str(r["segment"])
            if seg in segments:
                duplicates += 1
            segments.add(seg)
        if r.get("duration_ms") is not None:
            durations.append(int(r["duration_ms"]))
        if r.get("status") == 403:
            ts = float(r.get("ts", 0))
            if first_block is None:
                first_block = ts
            elif ts - first_block <= 60:
                after_block += 1
    durations.sort()

    def pct(p: float) -> int:
        if not durations:
            return 0
        idx = min(len(durations) - 1, max(0, int(round(p * (len(durations) - 1)))))
        return durations[idx]

    return {
        "run": Path(run_dir).name,
        "requests": len(rows),
        "status_counts": dict(status_counts),
        "reasons": dict(reasons),
        "bytes_total": sum(int(r.get("bytes", 0)) for r in rows),
        "bytes_per_task": {str(k): v for k, v in sorted(per_task.items())},
        "bytes_per_ip": dict(per_ip),
        "unique_segments": len(segments),
        "duplicate_segment_requests": duplicates,
        "max_inflight": max((int(r.get("inflight", 0)) for r in rows), default=0),
        "duration_ms_p50": pct(0.50),
        "duration_ms_p95": pct(0.95),
        "first_403_ts": first_block,
        "requests_after_first_403_60s": after_block,
    }


def mount_hls_test(parent: FastAPI, media_dir: Path, state_dir: Path, secret: bytes,
                   prefix: str = "/hls-test") -> None:
    """Explicit operator hook for the existing :80 FastAPI app (env-gated)."""
    parent.mount(prefix, create_origin_app(media_dir, state_dir, secret))


# Importable ASGI app for standalone uvicorn and for the env-gated mount.
_media = Path(os.environ.get("HLS_TEST_MEDIA_DIR", Path(__file__).parent / "media"))
_state = Path(os.environ.get("HLS_TEST_STATE_DIR", Path(__file__).parent / "runs"))
_secret_file = Path(os.environ.get("HLS_TEST_SECRET_FILE", Path(__file__).parent / "secret"))
origin_app = create_origin_app(_media, _state, load_or_create_secret(_secret_file))


def main() -> None:
    ap = argparse.ArgumentParser(description="Run the synthetic HLS test origin.")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--media-dir", default=str(_media))
    ap.add_argument("--state-dir", default=str(_state))
    ap.add_argument("--secret-file", default=str(_secret_file))
    ap.add_argument("--prefix", default="/hls-test",
                    help="mount prefix used by the public wrapper app")
    args = ap.parse_args()

    secret = load_or_create_secret(Path(args.secret_file))
    inner = create_origin_app(Path(args.media_dir), Path(args.state_dir), secret)
    wrapper = FastAPI()
    wrapper.mount(args.prefix, inner)

    import uvicorn
    uvicorn.run(wrapper, host=args.host, port=args.port, log_level="info", access_log=False)


if __name__ == "__main__":
    main()
