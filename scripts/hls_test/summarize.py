#!/usr/bin/env python3
"""Summarize one HLS test run's requests.jsonl (stdlib only).

Usage:
  python3 summarize.py --run-dir /home/Anon/hls-test-origin/runs/A1
  python3 summarize.py --file requests.jsonl --csv summary.csv
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


def load_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not path.exists():
        return rows
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except ValueError:
                continue
    return rows


def summarize(rows: list[dict[str, Any]], run: str) -> dict[str, Any]:
    status_counts: dict[str, int] = defaultdict(int)
    reasons: dict[str, int] = defaultdict(int)
    per_task: dict[int, int] = defaultdict(int)
    per_ip: dict[str, int] = defaultdict(int)
    per_kind: dict[str, int] = defaultdict(int)
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
        per_kind[str(r.get("kind", "?"))] += 1
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
        "run": run,
        "requests": len(rows),
        "status_counts": dict(status_counts),
        "reasons": dict(reasons),
        "requests_by_kind": dict(per_kind),
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


def main() -> int:
    ap = argparse.ArgumentParser(description="Summarize an HLS test run.")
    ap.add_argument("--run-dir", default=None)
    ap.add_argument("--file", default=None)
    ap.add_argument("--csv", default=None, help="append one row of key metrics")
    ap.add_argument("--pretty", action="store_true")
    args = ap.parse_args()
    if not args.run_dir and not args.file:
        ap.error("pass --run-dir or --file")
    path = Path(args.file) if args.file else Path(args.run_dir) / "requests.jsonl"
    run = Path(args.run_dir).name if args.run_dir else path.stem
    summary = summarize(load_rows(path), run)
    print(json.dumps(summary, indent=2 if args.pretty else None, sort_keys=True))
    if args.csv:
        exists = Path(args.csv).exists()
        with open(args.csv, "a", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            if not exists:
                w.writerow([
                    "run", "requests", "ok_200", "partial_206", "denied_403",
                    "token_expired", "quota_ip", "after_n_bytes", "bad_token",
                    "bytes_total", "unique_segments", "duplicate_segment_requests",
                    "max_inflight", "p50_ms", "p95_ms", "first_403_ts",
                    "requests_after_first_403_60s",
                ])
            w.writerow([
                summary["run"], summary["requests"],
                summary["status_counts"].get("200", 0),
                summary["status_counts"].get("206", 0),
                summary["status_counts"].get("403", 0),
                summary["reasons"].get("token_expired", 0),
                summary["reasons"].get("quota_ip", 0),
                summary["reasons"].get("after_n_bytes", 0),
                summary["reasons"].get("bad_token", 0),
                summary["bytes_total"], summary["unique_segments"],
                summary["duplicate_segment_requests"], summary["max_inflight"],
                summary["duration_ms_p50"], summary["duration_ms_p95"],
                summary["first_403_ts"], summary["requests_after_first_403_60s"],
            ])
    return 0


if __name__ == "__main__":
    sys.exit(main())
