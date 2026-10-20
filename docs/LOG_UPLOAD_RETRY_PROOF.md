# Log-upload retry proof (debug-only)

The fix is in `LogUploader.kt`: a failed send persists a pending flag + seq and
reuses the seq until a 2xx; retries run at 1/2/5/15/30/60 min plus network
and foreground hooks, and app-open bypasses the daily gate while pending.
`LogUploaderDebugReceiver` (debug source set only) makes the failure
deterministic without touching release code.

## Setup

Use the debug APK (`com.anonrode.downloader.debug`). Baseline the VPS index:

```bash
python scripts/read_client_logs.py list --host Anon@68.155.146.145 \
  --key ~/.ssh/anon_vps --client <debug-client-id>
```

The client id appears in the journal identity line / list output. Note the
last acknowledged seq.

## Forced failure -> retry

```bash
PKG=com.anonrode.downloader.debug
RCV=$PKG/$PKG.LogUploadDebugReceiver
# 1. Force exactly one transport failure and trigger a cycle:
adb shell am broadcast -a $PKG.LOG_UPLOAD -n $RCV --ez force_fail true --ez trigger true
# 2. Watch the journal (or logcat "AnonLogUploadDebug"):
#    "log upload failed — nothing cleared (seq=N, status=..., err=debug_forced); retrying"
adb shell run-as $PKG cat files/logs/app-$(date +%F).txt | tail -20
adb shell am broadcast -a $PKG.LOG_UPLOAD -n $RCV --ez status true
```

Confirm the VPS index has **no** artifact for seq N. Then let the retry run
(or trigger immediately):

```bash
adb shell am broadcast -a $PKG.LOG_UPLOAD -n $RCV --ez trigger true
# journal: "activity log sent and cleared (seq=N, ... KB gz)"
```

Verify on the VPS: same seq N is now present (not N+1), `cat` the chunk and
check the identity line `# anon-log v2 | id=... | seq=N`. The local journal is
cleared/truncated only after that 2xx.

## Extra proofs

- **Connectivity callback**: enable airplane mode, force a trigger (fails
  offline), disable airplane mode; the network callback retries without a
  foreground event.
- **Daily-gate bypass**: while pending, set `pref_log_upload_last` to now via a
  debug prefs edit or wait for a successful send then force another failure;
  relaunch the app — `onAppOpen` must attempt despite the 24h stamp.
- **429**: point a test rules payload at an endpoint returning 429 with
  `Retry-After: 60`; the next retry must be at least 60s later (journal status
  line shows 429; no payload/token is logged).

## Cleanup

No production impact: the receiver exists only in `app/src/debug` and is
absent from the release manifest. Uninstall the debug APK when done.
