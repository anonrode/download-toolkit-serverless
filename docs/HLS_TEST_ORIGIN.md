# Synthetic HLS test origin (VPS test content only)

No third-party media is fetched. Media is generated from ffmpeg `testsrc2` +
`sine`. Never scrape pirate sites from the box.

## Files

- `scripts/hls_test/generate_media.sh` — 300 × 2 s segments, 640×360, ~123 MB.
- `scripts/hls_test/origin.py` — FastAPI origin: HMAC tokens, per-IP quota,
  per-task after-N, per-connection pacing, Range/206, JSONL request log.
- `scripts/hls_test/tokenlib.py` — stdlib token mint/verify.
- `scripts/hls_test/mint_token.py` — mint a token/master URL on the VPS.
- `scripts/hls_test/summarize.py` — JSON/CSV summary of one run.
- `scripts/hls_test/run_matrix.py` — drives matrix A/B/C/D via adb + SSH.
- `scripts/hls_test/verify_origin.py` — black-box self-test.

## VPS setup

```bash
mkdir -p /home/Anon/hls-test-origin
scp -i ~/.ssh/anon_vps scripts/hls_test/* \
    Anon@68.155.146.145:/home/Anon/hls-test-origin/
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
  'chmod +x /home/Anon/hls-test-origin/generate_media.sh && \
   /home/Anon/hls-test-origin/generate_media.sh /home/Anon/hls-test-origin/media'
# Runs with the existing FastAPI venv (fastapi + uvicorn already installed):
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
  'cd /home/Anon/hls-test-origin && \
   HLS_TEST_SECRET_FILE=/home/Anon/hls-test-origin/secret \
   nohup /home/Anon/download-toolkit-server/venv/bin/python origin.py \
     --host 0.0.0.0 --port 8099 \
     --media-dir /home/Anon/hls-test-origin/media \
     --state-dir /home/Anon/hls-test-origin/runs \
     > origin.log 2>&1 &'
```

If port 8099 is not externally reachable, use the env-gated mount below; do
not edit the production server permanently. Make a backup and restart
`anon-server` only with operator approval:

```python
# server.py, additive and removable
if os.environ.get("ANON_HLS_TEST") == "1":
    from origin import origin_app
    app.mount("/hls-test", origin_app)
```

Then the public base is `http://68.155.146.145/hls-test`; otherwise use
`http://68.155.146.145:8099/hls-test` (only if the firewall allows 8099).

Observed on 2026-10-19: :8099 is **blocked externally** (the origin listens
and passes localhost tests, but the PC cannot reach it), and the mount hook
was validated in an isolated FastAPI probe on 127.0.0.1:8100. Device-facing
matrix runs therefore require either the env-gated `/hls-test` mount on the
existing :80 process (operator-approved restart) or a firewall allowance for
8099. The production `server.py` was deliberately not edited in this phase.

## Token contract

`t = base64url(claims).base64url(HMAC-SHA256(secret, payload))`

Claims: `r` run, `tk` task, `ln` lanes, `iat`, `exp`, `qb` per-IP quota bytes,
`ab` per-task after-N bytes, `rb` per-connection bytes/s, `n` nonce.
Zero `qb`/`ab`/`rb` disables that control. Playlists contain absolute segment
URLs with the same token (required: the engine drops base queries on relative
URIs). Segment requests support `Range` and return `206` + `Content-Range`.

403 reasons: `token_expired`, `quota_ip`, `after_n_bytes`, `bad_token`,
`bad_segment`, `not_found`. Requests are logged to
`runs/<run>/requests.jsonl`; `GET /admin/summary?run=` is localhost-only.

## Matrix

```bash
python scripts/hls_test/run_matrix.py --dry-run
python scripts/hls_test/run_matrix.py --group A
python scripts/hls_test/run_matrix.py --group B --public-base http://68.155.146.145/hls-test
```

Groups: A baseline (1/3 tasks × 4/8/16 lanes, TTL 10, no quota); B TTL
2/5/10 mid-stream + start-expiry; C quota 60/90 MB and after-N 40/60 MB;
D rate 48 KiB/s at 4/16 lanes. Results land in `scratch/hls_matrix/<run>/`
including `requests.jsonl`, `summary.json`, `client-state.txt`, and a log tail.
Run `summarize.py --file ... --csv summary.csv` for the aggregate.

## Verification

```bash
ssh ... 'python3 /home/Anon/hls-test-origin/verify_origin.py \
  --base http://127.0.0.1:8099/hls-test \
  --secret-file /home/Anon/hls-test-origin/secret'
```

Pass criteria are in the Phase-2 report: baseline 300/300 segments; TTL one
`token_expired` and no requests after exp+60 s; quota/after bounded retries;
rate within ±15% with no churn above the 40 KiB/s drop floor.
