#!/usr/bin/env python3
"""Static gate for the verification/ops lane (no gradle, no adb).

Checks:
  - FCM service is declared exported=false with the MESSAGING_EVENT filter;
  - notification channel/icon meta-data exist;
  - the manual-runtime route has no google-services plugin/json;
  - BuildConfig carries the four FCM client fields;
  - FcmRegistrar has the defaults/override/kill-switch and anon_test logic;
  - the messaging service has the required callbacks + channel;
  - ProGuard keeps the service;
  - LogUploader has pending-seq/retry/network/foreground/429 machinery;
  - debug-only proof receivers exist in the debug manifest/source set.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8", errors="replace")


def main() -> int:
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, ok, detail))

    manifest = read("app/src/main/AndroidManifest.xml")
    try:
        tree = ET.fromstring(manifest)
        app = tree.find("application")
        services = [] if app is None else app.findall("service")
        service = next(
            (s for s in services
             if s.get(ANDROID + "name") == ".update.AnonFirebaseMessagingService"),
            None,
        )
        check("manifest: FCM service declared", service is not None)
        if service is not None:
            check("manifest: FCM service exported=false",
                  service.get(ANDROID + "exported") == "false")
            actions = [a.get(ANDROID + "name") for a in service.iter("action")]
            check("manifest: MESSAGING_EVENT filter",
                  "com.google.firebase.MESSAGING_EVENT" in actions)
        metas = []
        if app is not None:
            for m in app.findall("meta-data"):
                metas.append((m.get(ANDROID + "name"), m.get(ANDROID + "value"), m.get(ANDROID + "resource")))
        check("manifest: default notification channel",
              any(n == "com.google.firebase.messaging.default_notification_channel_id"
                  and v == "anon_announcements" for n, v, _ in metas))
        check("manifest: default notification icon",
              any(n == "com.google.firebase.messaging.default_notification_icon"
                  and r == "@drawable/ic_notification" for n, _, r in metas))
        check("manifest: POST_NOTIFICATIONS permission",
              'android.permission.POST_NOTIFICATIONS' in manifest)
    except ET.ParseError as exc:
        check("manifest parses", False, str(exc))

    gradle = read("app/build.gradle.kts")
    for field in ("FCM_PROJECT_ID", "FCM_APP_ID", "FCM_SENDER_ID", "FCM_API_KEY"):
        check(f"gradle: BuildConfig.{field}", f'"{field}"' in gradle)
    check("gradle: no google-services plugin",
          'id("com.google.gms.google-services")' not in gradle)
    check("gradle: no google-services.json in app/",
          not (ROOT / "app/google-services.json").exists())
    check("gradle: FCM_APP_ID value",
          "1:93321768940:android:6ef4186eb2157d72ce5ac9" in gradle)
    check("gradle: FCM_SENDER_ID value", "93321768940" in gradle)
    check("gradle: FCM_PROJECT_ID value", "anonrode-d591f" in gradle)
    check("gradle: buildConfig enabled", "buildConfig = true" in gradle)

    registrar = read("app/src/main/java/com/anonrode/downloader/update/FcmRegistrar.kt")
    check("registrar: BuildConfig defaults", "BuildConfig.FCM_API_KEY" in registrar)
    check("registrar: anon_test topic", 'TOPIC_TEST = "anon_test"' in registrar)
    check("registrar: override appId compare",
          "existing.options.applicationId == cfg.appId" in registrar)
    check("registrar: kill switch", "deleteToken" in registrar and "unsubscribeFromTopic" in registrar)
    check("registrar: token stages", '"token_ok"' in registrar and '"token_fail"' in registrar)
    check("registrar: topic stages", '"topic_ok"' in registrar and '"topic_fail"' in registrar)
    check("registrar: no token logging", "token=" not in registrar)

    service = read("app/src/main/java/com/anonrode/downloader/update/AnonFirebaseMessagingService.kt")
    check("service: extends FirebaseMessagingService", "FirebaseMessagingService()" in service)
    check("service: onNewToken", "override fun onNewToken" in service)
    check("service: onMessageReceived", "override fun onMessageReceived" in service)
    check("service: onDeletedMessages", "override fun onDeletedMessages" in service)
    check("service: channel id", 'CHANNEL_ID = "anon_announcements"' in service)
    check("service: notification permission guard", "POST_NOTIFICATIONS" in service)

    proguard = read("app/proguard-rules.pro")
    check("proguard: keeps FCM service",
          "-keep class com.anonrode.downloader.update.AnonFirebaseMessagingService" in proguard)

    uploader = read("app/src/main/java/com/anonrode/downloader/util/LogUploader.kt")
    for needle, name in [
        ("PENDING_ACTIVE_KEY", "pending flag"),
        ("PENDING_SEQ_KEY", "pending seq"),
        ("RETRY_BACKOFF_MS", "retry ladder"),
        ("Retry-After", "Retry-After parsing"),
        ("registerDefaultNetworkCallback", "network callback"),
        ("ActivityLifecycleCallbacks", "foreground hook"),
        ("debugForceFailOnce", "debug forced failure"),
        ("enforceDailyGate && !pending", "daily-gate bypass while pending"),
    ]:
        check(f"log uploader: {name}", needle in uploader)

    check("release manifest: no debug receivers",
          "LogUploadDebugReceiver" not in manifest and "HlsTestDebugReceiver" not in manifest)

    debug_manifest = ROOT / "app/src/debug/AndroidManifest.xml"
    check("debug: manifest exists", debug_manifest.exists())
    if debug_manifest.exists():
        dm = debug_manifest.read_text(encoding="utf-8")
        try:
            ET.fromstring(dm)
            check("debug: manifest parses", True)
        except ET.ParseError as exc:
            check("debug: manifest parses", False, str(exc))
        check("debug: LogUploadDebugReceiver", "LogUploadDebugReceiver" in dm)
        check("debug: HlsTestDebugReceiver", "HlsTestDebugReceiver" in dm)
    check("debug: LogUpload receiver source",
          (ROOT / "app/src/debug/java/com/anonrode/downloader/debug/LogUploadDebugReceiver.kt").exists())
    check("debug: HLS receiver source",
          (ROOT / "app/src/debug/java/com/anonrode/downloader/debug/HlsTestDebugReceiver.kt").exists())

    # The manual-runtime route must not smuggle a service-account private key.
    for rel in [
        "app/build.gradle.kts",
        "app/src/main/java/com/anonrode/downloader/update/FcmRegistrar.kt",
        "app/src/main/java/com/anonrode/downloader/update/AnonFirebaseMessagingService.kt",
    ]:
        text = read(rel)
        check(f"no service-account key in {Path(rel).name}",
              "private_key" not in text and "service_account" not in text)

    failed = [c for c in checks if not c[1]]
    for name, ok, detail in checks:
        print(("PASS: " if ok else "FAIL: ") + name + (f" ({detail})" if detail and not ok else ""))
    print(f"\n{len(checks) - len(failed)}/{len(checks)} static FCM/ops checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
