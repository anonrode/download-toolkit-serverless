import urllib.request
import json
import sys

url = "https://api.github.com/repos/anonrode/REDACTED-DIRless/actions/runs?per_page=10"
req = urllib.request.Request(url, headers={"User-Agent": "CI-Watcher/1.0", "Accept": "application/vnd.github.v3+json"})

try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        print(f"Total runs found: {data.get('total_count', 0)}")
        for r in data.get("workflow_runs", []):
            run_id = r.get("id")
            name = r.get("name")
            event = r.get("event")
            status = r.get("status")
            conclusion = r.get("conclusion")
            branch = r.get("head_branch")
            sha = r.get("head_sha", "")[:7]
            html_url = r.get("html_url")
            created = r.get("created_at")
            print(f"[{status.upper()}] Run {run_id}: {name} ({event} on {branch} @ {sha}) -> Conclusion: {conclusion}")
            print(f"       URL: {html_url}")
except urllib.error.HTTPError as e:
    print(f"HTTPError: {e.code} - {e.reason}")
    if e.code == 404:
        print("Note: The repository might be private. Checking git credentials...")
except Exception as e:
    print(f"Error: {e}")
