# FCM runbook (manual-runtime route, un-parked 2026-10-19)

## Why no google-services plugin

The debug Android app is not registered in Firebase; adding the plugin would
make `testDebugUnitTest`/`assembleDebug` fail with “No matching client found”.
The app therefore uses the existing manual `FirebaseOptions` route. The four
NON-SECRET client identifiers are `BuildConfig` defaults for both debug and
release:

- `FCM_PROJECT_ID` = `anonrode-d591f`
- `FCM_APP_ID` = `1:93321768940:android:6ef4186eb2157d72ce5ac9`
- `FCM_SENDER_ID` = `93321768940`
- `FCM_API_KEY` = supplied Google Android API key (non-secret client config)

No service-account key is in the repo, Gradle, docs, or PC. It exists only at
`/home/Anon/.secrets/fcm-service-account.json` (mode 0600) on the VPS.

Signed OTA override/rotation block (omit it to use the BuildConfig defaults):

```json
"fcm": {
  "enabled": true,
  "apiKey": "<current_key from the supplied google-services.json>",
  "appId": "1:93321768940:android:6ef4186eb2157d72ce5ac9",
  "senderId": "93321768940",
  "projectId": "anonrode-d591f"
}
```

Use `"enabled": false` as the fleet-wide kill switch. The API key value above
is deliberately not duplicated here; it is a non-secret Android client key, not
a service-account credential.

## Config precedence (FcmRegistrar)

| OTA `fcm` block | Behaviour |
|---|---|
| absent | BuildConfig defaults; register + subscribe `anon_test` |
| `enabled=false` | kill switch: delete token, unsubscribe `anon_test` |
| enabled + all four fields | override; appId change deletes/re-inits FirebaseApp |
| enabled + incomplete | ignored; BuildConfig defaults stay active |

Logs contain only `token_ok`, `token_fail`, `topic_ok`, `topic_fail`, and
`fcm_message` (message id/type/from). Never token, FID, or payload data values.

## Files

- `FcmRegistrar.kt` — defaults/override/kill-switch, topic `anon_test`.
- `AnonFirebaseMessagingService.kt` — exported=false; channel
  `anon_announcements`; `onNewToken`, `onMessageReceived`, `onDeletedMessages`.
- `AndroidManifest.xml` — MESSAGING_EVENT service + default channel/icon.
- `proguard-rules.pro` — keep for the service.
- `scripts/fcm/send_topic.py` — HTTP v1 sender (VPS only).

## One-line AnonApp hook (owned by the UI agent)

Add once in `AnonApp.onCreate()`, after `RemoteLogger.install(this)`:

```kotlin
try { com.anonrode.downloader.update.FcmRegistrar.maybeRegister(this) } catch (_: Throwable) {}
```

The existing `afterRulesActivated` hook remains; the registrar is idempotent.

## API-key restriction (required)

Google Cloud Console → APIs & Services → Credentials → the Android API key:

1. Application restrictions → Android apps:
   - `com.anonrode.downloader` + release SHA-1;
   - optional `com.anonrode.downloader.debug` + debug SHA-1.
2. API restrictions: Firebase Cloud Messaging API and Firebase Installations API.
3. Re-test token registration after saving; if registration fails, verify the
   release SHA-1 before relaxing the restriction.

## First delivery test (Firebase Console topic campaign)

Use a **release-signed** build (package `com.anonrode.downloader`) for the FCM
runtime test. The debug package `com.anonrode.downloader.debug` is not
registered in Firebase; the manual runtime options can still initialize, but
token registration is not guaranteed until that package is registered with its
debug SHA-1. HLS matrix testing uses the debug package independently.

1. Install the release-signed build; launch; confirm journal/logcat:
   `FcmRegistrar: token_ok` then `topic_ok`.
2. Firebase Console → Messaging → New campaign → Notifications.
3. Target → Topic → `anon_test`; send now.
4. Foreground: `AnonFirebaseMessagingService` logs
   `FCM message received id=... type=notification` and posts on
   `anon_announcements`. Background: system tray notification; tap opens app.
5. Deny POST_NOTIFICATIONS and resend: receipt is logged, notification skipped,
   no crash.

## Repeatable HTTP v1 test (VPS only)

```bash
ssh -i ~/.ssh/anon_vps Anon@68.155.146.145 \
  'python3 /home/Anon/download-toolkit-serverless/scripts/fcm/send_topic.py --data-only'
```

Copy `scripts/fcm/send_topic.py` to the VPS (or run from a checkout there).
The default credentials path is the 0600 file above. Notification variant:

```bash
python3 send_topic.py --title "Anonrode test" --body "FCM delivery works"
```

Verify the response `name=projects/.../messages/...`, the device journal
`fcm_message`, and a VPS full-log chunk containing that line.

## Gates

`python scripts/verify_fcm_static.py`, Kotlin syntax gate (main/test/debug),
adversarial audit, `py_compile` for scripts. No gradle in the verification
phase; CI build + emulator remains the compile gate. Do not publish OTA until
the runtime test and API-key restriction are green.
