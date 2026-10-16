import urllib.request
import json
import time
import sys

RUN_IDS = [36803331791, 36803309610]
BASE_URL = "https://api.github.com/repos/anonrode/download-toolkit-serverless/actions/runs"

import subprocess

def get_token():
    try:
        proc = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n", capture_output=True, text=True)
        for line in proc.stdout.splitlines():
            if line.startswith("password="):
                return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return None

TOKEN = get_token()

def get_json(url):
    headers = {"User-Agent": "CI-Watcher/1.0", "Accept": "application/vnd.github.v3+json"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"error": str(e)}

def check_runs():
    all_done = True
    for run_id in RUN_IDS:
        run_data = get_json(f"{BASE_URL}/{run_id}")
        if "error" in run_data:
            print(f"Run {run_id}: error fetching ({run_data['error']})")
            all_done = False
            continue
        
        status = run_data.get("status")
        conclusion = run_data.get("conclusion")
        event = run_data.get("event")
        branch = run_data.get("head_branch")
        
        print(f"\n=======================================================")
        print(f"Run {run_id} ({event} on {branch}): STATUS = {status} | CONCLUSION = {conclusion}")
        
        # Check jobs
        jobs_data = get_json(f"{BASE_URL}/{run_id}/jobs")
        for job in jobs_data.get("jobs", []):
            j_name = job.get("name")
            j_status = job.get("status")
            j_concl = job.get("conclusion")
            print(f"  -> Job: {j_name} | {j_status} | {j_concl}")
            
            # Print current or failed steps
            steps = job.get("steps", [])
            for step in steps:
                s_name = step.get("name")
                s_status = step.get("status")
                s_concl = step.get("conclusion")
                if s_status == "in_progress" or (s_concl and s_concl != "success"):
                    print(f"       * Step: {s_name} [{s_status}/{s_concl}]")
        
        if status != "completed":
            all_done = False

    return all_done

if __name__ == "__main__":
    done = check_runs()
    sys.exit(0 if done else 1)
