#!/usr/bin/env python3
"""
Maintain `flake-ledger.json` for the instrumented smoke suite.

A flake is recorded when a test class fails on the first run but passes on
the retry; a real failure is when a class fails on the retry too. The
ledger's `id` is a SHA-1 prefix of (testClass + testName + normalized stack
top), so a flaky test isn't double-counted when source lines shift.

Reads:
  - flake-ledger.json (committed at repo root, or in app/ if you put it there)
  - Retry result XML directory (post-retry): same shape as the first run

Writes:
  - Updated flake-ledger.json, atomically (tmp + rename)
  - Stdout: list of pruned entries (anything > 14 days old) and a summary
    line for the CI log

Usage:
  update_flake_ledger.py <ledger_path> <retry_xml_dir> <outcome> \\
      [<failed_xml_dir>]

  <outcome> is "flaked" or "failed". In the "flaked" case the failing classes
  are recorded as new entries; in the "failed" case existing entries are
  bumped (occurrences++, last_seen=now) and new ones are appended.

If <failed_xml_dir> is omitted, the script only inspects the retry XML — this
is the path used by the workflow after the retry step has run.
"""
from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Set, Tuple


LEDGER_VERSION = 1
PRUNE_AFTER_DAYS = 14
ID_PREFIX_LEN = 12


def _now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _normalize_stack(message: str) -> str:
    """Strip line numbers and addresses so the same bug matches across minor
    source shifts. Keep only the top 1-2 logical lines so unrelated parts
    of a long stack do not pollute the signature."""
    if not message:
        return ""
    # Drop everything after the first newline so the signature is bounded.
    line = message.split("\n", 1)[0]
    # Strip ":NN" / "(NN)" / "at: 0x..." style noise.
    line = re.sub(r":\d+", "", line)
    line = re.sub(r"\(0x[0-9A-Fa-f]+\)", "", line)
    line = re.sub(r"0x[0-9A-Fa-f]+", "", line)
    return line.strip()[:200]


def _fingerprint(test_class: str, test_name: str, message: str) -> str:
    raw = f"{test_class}::{test_name}::{_normalize_stack(message)}"
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()
    return digest[:ID_PREFIX_LEN]


def _collect_failures(xml_dir: str) -> List[Tuple[str, str, str]]:
    """Return [(class, name, message), ...] for every failing testcase found."""
    out: List[Tuple[str, str, str]] = []
    if not os.path.isdir(xml_dir):
        return out
    # Walk recursively — Android Gradle writes XML under
    # connected/<flavor>/<class>.xml, not at the top level.
    xml_files: List[str] = []
    for root_dir, _dirs, files in os.walk(xml_dir):
        for name in files:
            if name.endswith(".xml"):
                xml_files.append(os.path.join(root_dir, name))
    for path in sorted(xml_files):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            out.append(("__malformed_xml__", os.path.basename(path), "JUnit XML parse error"))
            continue
        root = tree.getroot()
        suites = root.findall(".//testsuite") if root.tag == "testsuites" else [root]
        for suite in suites:
            suite_name = suite.get("name") or ""
            for case in suite.findall("testcase"):
                # ElementTree's Element.__bool__ returns False for elements
                # with no children, so a childless <failure/> is falsy. Use
                # explicit None checks; see parse_instrumented_failures.py.
                fnode = case.find("failure")
                if fnode is None:
                    fnode = case.find("error")
                if fnode is not None:
                    out.append((suite_name, case.get("name") or "", fnode.get("message") or ""))
    return out


def _load_ledger(path: str) -> Dict[str, Any]:
    if not os.path.exists(path):
        return {"version": LEDGER_VERSION, "entries": []}
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        # Corrupt ledger is treated as empty rather than failing the build —
        # the worst case is a missing history, not a bricked pipeline.
        return {"version": LEDGER_VERSION, "entries": []}


def _save_ledger(path: str, data: Dict[str, Any]) -> None:
    """Atomic write: tmp file + rename so a partial write can't corrupt the
    ledger even if the runner dies mid-write."""
    dir_name = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp = tempfile.mkstemp(prefix=".flake-ledger-", dir=dir_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2, sort_keys=True)
            fh.write("\n")
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def _prune(entries: List[Dict[str, Any]], now: datetime.datetime) -> Tuple[List[Dict[str, Any]], List[str]]:
    cutoff = now - datetime.timedelta(days=PRUNE_AFTER_DAYS)
    keep: List[Dict[str, Any]] = []
    pruned_ids: List[str] = []
    for entry in entries:
        last_seen = entry.get("last_seen")
        if not last_seen:
            keep.append(entry)
            continue
        try:
            ts = datetime.datetime.strptime(last_seen, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
        except ValueError:
            keep.append(entry)
            continue
        if ts < cutoff:
            pruned_ids.append(entry.get("id", "<unknown>"))
        else:
            keep.append(entry)
    return keep, pruned_ids


def main() -> int:
    if len(sys.argv) < 4:
        print("usage: update_flake_ledger.py <ledger> <retry_xml_dir> <outcome> [first_xml_dir]", file=sys.stderr)
        return 2
    ledger_path = sys.argv[1]
    retry_dir = sys.argv[2]
    outcome = sys.argv[3]
    first_dir = sys.argv[4] if len(sys.argv) > 4 else None

    now_dt = datetime.datetime.now(datetime.timezone.utc)
    now_iso = now_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    data = _load_ledger(ledger_path)
    if data.get("version") != LEDGER_VERSION:
        # Unknown version: keep the file but record under current version.
        data = {"version": LEDGER_VERSION, "entries": data.get("entries", [])}

    # Which classes failed on the first run, and which (if any) failed again
    # on the retry? Map class -> list of (name, message) tuples.
    first_failures: Dict[str, List[Tuple[str, str]]] = {}
    if first_dir and os.path.isdir(first_dir):
        for cls, name, msg in _collect_failures(first_dir):
            first_failures.setdefault(cls, []).append((name, msg))

    retry_failures: Dict[str, List[Tuple[str, str]]] = {}
    for cls, name, msg in _collect_failures(retry_dir):
        retry_failures.setdefault(cls, []).append((name, msg))

    by_id: Dict[str, Dict[str, Any]] = {e["id"]: e for e in data["entries"] if "id" in e}

    if outcome == "flaked":
        # The retry passed for every class that failed the first run. Record
        # a flake entry per (class, test) so the next run can correlate.
        for cls, items in first_failures.items():
            for name, msg in items:
                fid = _fingerprint(cls, name, msg)
                existing = by_id.get(fid)
                if existing is not None:
                    existing["last_seen"] = now_iso
                    existing["occurrences"] = int(existing.get("occurrences", 1)) + 1
                    existing["last_error_signature"] = _normalize_stack(msg) or existing.get("last_error_signature", "")
                else:
                    by_id[fid] = {
                        "id": fid,
                        "first_seen": now_iso,
                        "last_seen": now_iso,
                        "occurrences": 1,
                        "last_error_signature": _normalize_stack(msg),
                        "test_class": cls,
                        "test_name": name,
                    }
        message = "Flake recorded"
    elif outcome == "failed":
        # Retry also failed. Bump or create entries for what we just saw.
        for cls, items in retry_failures.items():
            for name, msg in items:
                fid = _fingerprint(cls, name, msg)
                existing = by_id.get(fid)
                if existing is not None:
                    existing["last_seen"] = now_iso
                    existing["occurrences"] = int(existing.get("occurrences", 1)) + 1
                    existing["last_error_signature"] = _normalize_stack(msg) or existing.get("last_error_signature", "")
                else:
                    by_id[fid] = {
                        "id": fid,
                        "first_seen": now_iso,
                        "last_seen": now_iso,
                        "occurrences": 1,
                        "last_error_signature": _normalize_stack(msg),
                        "test_class": cls,
                        "test_name": name,
                    }
        message = "Failure recorded"
    else:
        print(f"unknown outcome: {outcome}", file=sys.stderr)
        return 2

    # Prune old entries; print their ids to the workflow log so a reviewer
    # can see the ledger's "memory" resetting in real time.
    entries = list(by_id.values())
    entries.sort(key=lambda e: e.get("last_seen", ""))
    entries, pruned = _prune(entries, now_dt)
    for pid in pruned:
        print(f"[flake-ledger] pruned {pid} (> {PRUNE_AFTER_DAYS} days old)")

    data["entries"] = entries
    _save_ledger(ledger_path, data)

    summary = {
        "outcome": outcome,
        "entries_total": len(entries),
        "pruned": pruned,
        "flaked_classes": sorted(first_failures.keys()) if outcome == "flaked" else [],
        "failed_classes": sorted(retry_failures.keys()) if outcome == "failed" else [],
        "message": message,
    }
    out_path = os.environ.get("GITHUB_OUTPUT")
    if out_path:
        with open(out_path, "a", encoding="utf-8") as fh:
            fh.write("ledger_summary<<EOF\n")
            fh.write(json.dumps(summary))
            fh.write("\nEOF\n")
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
