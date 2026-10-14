import urllib.request
import subprocess
import json
import sys

def get_token():
    proc = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n", capture_output=True, text=True)
    for line in proc.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    return None

token = get_token()

class NoAuthRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req and "blob.core.windows.net" in newurl:
            if "Authorization" in new_req.headers:
                del new_req.headers["Authorization"]
        return new_req

opener = urllib.request.build_opener(NoAuthRedirectHandler())
urllib.request.install_opener(opener)

failed_job_id = 111494469833
log_url = f"https://api.github.com/repos/anonrode/REDACTED-DIRless/actions/jobs/{failed_job_id}/logs"
req = urllib.request.Request(log_url, headers={"User-Agent": "CI-Watcher/1.0", "Authorization": f"Bearer {token}"})

try:
    with urllib.request.urlopen(req) as resp:
        log_text = resp.read().decode(errors="replace")
        lines = log_text.splitlines()
        with open("ci_release_job.log", "w", encoding="utf-8") as f:
            f.write(log_text)
        print(f"Saved full log ({len(lines)} lines) to ci_release_job.log")
        kotlin_errors = [l for l in lines if "e: " in l or "CompilationErrorException" in l or ":app:compileReleaseKotlin" in l]
        print(f"Found {len(kotlin_errors)} error/compile lines:")
        for l in kotlin_errors[:30]:
            print(l)
except Exception as e:
    print(f"Error fetching log: {e}")
