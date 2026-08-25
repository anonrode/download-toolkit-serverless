#!/usr/bin/env python3
"""
Parse JUnit XML output from `gradle :app:connectedDebugAndroidTest` and emit
a single-line, JSON-encoded list of failing test classes to stdout. Used by
the CI gate step to decide whether to retry a class.

Output format (one line of JSON):
  {"failing_classes": ["com.anonrode.downloader.FooTest"], "failing_tests": [...]}

`failing_classes` is deduplicated. A "failure" is any <testcase> with a
<failure> or <error> child node. Skipped/ignored tests are not failures.

Exit code 0 even if failures were found; this script only inspects.
"""
from __future__ import annotations

import json
import os
import sys
import xml.etree.ElementTree as ET
from typing import List, Set, Dict, Any


def _walk_xml_files(results_dir: str) -> List[str]:
    """Yield every .xml file under results_dir, recursively. The
    Android Gradle plugin nests XML under
    connected/<flavor>/<class>.xml, so a flat listdir misses them."""
    out: List[str] = []
    for root, _dirs, files in os.walk(results_dir):
        for name in files:
            if name.endswith(".xml"):
                out.append(os.path.join(root, name))
    return sorted(out)


def collect_failures(results_dir: str) -> Dict[str, Any]:
    failing_classes: Set[str] = set()
    failing_tests: List[Dict[str, str]] = []
    if not os.path.isdir(results_dir):
        return {"failing_classes": [], "failing_tests": []}

    for path in _walk_xml_files(results_dir):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            # Malformed XML in a partial write — record as a generic failure
            # so the workflow can decide what to do with it.
            failing_classes.add("__malformed_xml__")
            failing_tests.append({
                "class": "__malformed_xml__",
                "name": os.path.basename(path),
                "message": "JUnit XML parse error",
            })
            continue
        root = tree.getroot()
        # The root element may itself be <testsuite> (single-class) or
        # <testsuites> (multi-class aggregator produced by Android Gradle).
        suites = root.findall(".//testsuite") if root.tag == "testsuites" else [root]
        for suite in suites:
            suite_name = suite.get("name") or ""
            for case in suite.findall("testcase"):
                # ElementTree's Element.__bool__ returns False for elements
                # with no children, so an empty <failure/> child would be
                # silently treated as "no failure" if we used `or` to chain
                # the two lookups. Use explicit is-not-None checks.
                failure_node = case.find("failure")
                if failure_node is None:
                    failure_node = case.find("error")
                if failure_node is not None:
                    failing_classes.add(suite_name)
                    failing_tests.append({
                        "class": suite_name,
                        "name": case.get("name") or "",
                        "message": (failure_node.get("message") or "")[:300],
                    })
    return {
        "failing_classes": sorted(failing_classes),
        "failing_tests": failing_tests,
    }


def main() -> int:
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/androidTest-results/connected"
    payload = collect_failures(results_dir)
    has_failures = "true" if payload["failing_classes"] else "false"
    # Always write to GITHUB_OUTPUT if available, so downstream steps can
    # read the same data without re-parsing.
    out_path = os.environ.get("GITHUB_OUTPUT")
    if out_path:
        with open(out_path, "a", encoding="utf-8") as fh:
            fh.write("failures<<EOF\n")
            fh.write(json.dumps(payload))
            fh.write("\nEOF\n")
            fh.write(f"has_failures={has_failures}\n")
    print(json.dumps(payload))
    return 0


if __name__ == "__main__":
    sys.exit(main())
