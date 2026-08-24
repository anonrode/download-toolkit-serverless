# UX & UI Review

## Critical

- **[critical] `EpisodeDrawer.kt:259-295`** The "Download (N)" button falls back to `selectedEpisodes.ifEmpty { episodes.toSet() }` — when the range field matches nothing (e.g. typo "999"), the user clears selection with "Clear" or "none", or the range parse yields no matches, the button relabels to "All" and clicking queues every episode of the show. A 50-episode K-drama at ~700 MB each = 35 GB silently enqueued on mobile data with no confirmation dialog. The "Clear" chip (line 218-230) invites the user to clear, then the button changes to "All" — the natural action of "clear then close" becomes "download everything" if the user's finger hits the download button before the close button. The only guard is the enqueue dedupe (same sourceUrl), but nav-junk episodes produce different URLs and all get queued.

- **[critical] `MainActivity.kt:94-98`** On Android 9-10 (API 26-29), the `writeStorageLauncher` callback sets `showStorageRationale = !granted`. If the user permanently denies the WRITE_EXTERNAL_STORAGE runtime dialog (deny twice → "Never ask again"), the system shows no dialog and `launch()` returns immediately with `granted=false`. The dialog re-opens instantly — the user is stuck in an infinite loop where tapping "Grant" does nothing and the only exit is "Not now". There is no fallback to `ACTION_APPLICATION_DETAILS_SETTINGS` on pre-11 (unlike the Android 11+ path). The app is effectively unusable for downloads after permanent denial.

- **[critical] `QuickShareActivity.kt:61-65, 94-96, 124-132`** The instant-download path (and the entire share flow) has zero storage-permission awareness. QuickShareActivity is a separate activity with no connection to MainActivity's storage rationale dialog. A fresh install → share from Instagram → "Always download instantly" → toast "Download queued in background" → activity finishes → user is back in the source app. The task fails silently in the downloads list with "Storage permission missing". The user never sees the failure. The "instant" path also hardcodes 720p and audioOnly=false with no way to configure it. Furthermore, **QuickShareActivity is the only activity with the `ACTION_SEND` intent filter** in the manifest — `MainActivity.kt:237-248` `handleShareIntent` is dead code and never receives share intents, meaning the share flow NEVER benefits from the storage permission dialog.

## Major

- **[major] `MainActivity.kt:99-106, 175-227`** On Android 11+, the "Grant" button opens either `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (which some OEMs drop) or the app-details page as fallback. On several OEM skins (MIUI, ColorOS, OneUI), the "All files access" toggle is buried under a different path (Settings > Apps > Special Access > All files access) and the app-details page may not show it. The user returns, the re-check fails, the dialog reopens with the same copy — no guidance on WHERE to find the toggle. The dialog text ("tap Grant and switch it on") implies the toggle is on the just-shown screen, but when it's not, the user goes in circles. The dialog copy should mention the actual settings path shown in the engine's fail-fast error message (line 1284: "Settings > Apps > Anon Downloader > Permissions"), but doesn't.

- **[major] `QuickShareActivity.kt:61-65, 94-96`** (storage gap, already covered above in critical — the instant path is the critical vector; the non-instant path at least shows a card before downloading, so the user can cancel. Still severe.)

- **[major] `DownloadsScreen.kt:200-204`** The progress bar target includes a hack: `task.downloadedBytes in 1..100 -> (task.downloadedBytes / 100f)`. When `totalBytes` is 0 (unknown total) and a handful of bytes trickle in (e.g. a 55-byte range-probe or a yt-dlp header fetch), the bar jumps to 1-100% for those few bytes. A task with 55 bytes downloaded shows 55% progress. This is a lie. The percentage is not shown in the status text in that branch, but the bar itself is visible and misleading.

- **[major] `DownloadsScreen.kt:200-204, 335-339, 362-375`** HLS download size estimates (from the segment-sampling estimator) are presented as exact numbers everywhere — no tilde, no "(est.)" suffix, no visual distinction. The progress bar can show "100% • 320 MB / 310 MB • 2.4 MB/s" when the estimate overshoots (downloaded > estimated total, coerced to 100% fraction while the size string shows the mismatch). The percentage field caps at 100% while the "X / Y" size string shows download exceeding estimate — a self-contradictory display. ETA for HLS tasks is computed from these estimates and shown as "12:34 left" with no indication it's approximate.

- **[major] `DownloadsScreen.kt:357-361`** Network-paused tasks (parked by NetworkObserver with `NETWORK_PAUSE_MESSAGE`) are displayed identically to user-paused tasks: "Paused at 45% • 320 MB / 710 MB" with a Resume (play) button. The user cannot distinguish "I paused this" from "the network dropped and it will auto-resume later". If the user taps Resume while still offline, the task re-queues and fails again, or the auto-resume races the manual resume. The PAUSED branch of `progressText` never reads `task.errorMessage`, so the network pause reason is invisible.

- **[major] `EpisodeDrawer.kt` (entire file)** — the drawer communicates nothing about what will happen when the user taps download. `EpisodeItem.sizeText` (defined in `Models.kt:33`) is never populated by any provider; `EpisodeItem.isDownloaded` (defined in `Models.kt:34`) is never set, never rendered. The user sees no file size, no resolution, no backend choice, and no indication of which episodes are already downloaded. Batch "Download (N)" enqueues with a hardcoded backend (`aria2c`), the engine's default quality, and 16 parallel sockets — the user has no preview of data cost (mobile data bill) or time. The charter says "Do not waste the user's mobile data", yet the UI openly offers to queue a full season with zero cost disclosure.

- **[major] `DownloadEngine.kt:306-325` (pause), `HttpClient.kt:244-257` (cancelInFlight)** — `pause()` calls `HttpClient.cancelInFlight()`, which cancels ALL in-flight HTTP calls except those tagged `"search"`. This includes resolver/probe HTTP for OTHER tasks' resolution. Pausing task A aborts task B's in-flight resolution, causing B to restart or fail. The comment in HttpClient.kt:247-248 acknowledges the problem ("pausing one download aborting unrelated in-flight searches") and exempts only search calls, but resolver calls are not exempted. Visible UX: intermittent stalls/cancellations on unrelated downloads when the user pauses one task.

- **[major] `MainActivity.kt:69-80`** POST_NOTIFICATIONS permission is requested with no rationale dialog on API 33+. The request fires at the first frame of the first launch, overlapping the splash screen and the storage rationale AlertDialog. The user sees two stacked permission prompts simultaneously. If the user denies notifications, the Settings "Completion Notifications" toggle (SettingsSheet.kt:543-549) remains ON but does nothing — there is no in-app mechanism to re-request the permission or explain why completion notifications never arrive.

## Minor

- **[minor] `HomeScreen.kt:424`, `EpisodeDrawer.kt:350`** LazyColumn `items` uses `key = { it.url }`. If two search results or two episodes share the same URL (e.g. same show from two providers, or naijavault fallback regex adding the same URL twice), Compose's LazyColumn will throw an `IllegalArgumentException` for duplicate keys at runtime. The search result dedupe is by URL only — cross-site duplicates are possible.

- **[minor] `HomeScreen.kt:71-86`** The clipboard-detection banner runs once at launch (`LaunchedEffect(Unit)`). If the user copies a link while the app is already open, the banner never surfaces. If the clipboard contained a stale link from hours ago, the same link is re-offered on every cold start.

- **[minor] `HomeScreen.kt:650-654`** Every non-anime, non-torrent show card shows a "✓ 1080p" badge (`secondaryBadge` line 653: `else -> "✓ 1080p"`). This is hardcoded and unverified — the actual stream may only be available at 720p, 480p, or lower. This is a UI lie about what the user will get.

- **[minor] `HomeScreen.kt:664-671`** The `rightMetric` function assigns fixed qualitative speed labels ("Very Fast", "Fast", "Normal") per site name. These are static, unverified claims that have no connection to the actual connection speed or CDN performance. The user has no way to verify or contest them.

- **[minor] `SettingsSheet.kt:522`** The About row hardcodes version "Anonrode v3.1.0". The actual `build.gradle.kts:17` has `versionName = "3.0.0-serverless"`, and the HANDOVER documents v3.0.3 as the released tag. Three different version strings in the same app. This will drift with every release since it's hardcoded text.

- **[minor] `SettingsSheet.kt:602`** Torrent peer subtitle contains a literal `$ battery` typo ("more = faster, $ battery").

- **[minor] `SettingsSheet.kt:344-373`** Aria2 Parallel Sockets slider (1-16) with Max Concurrent Downloads (1-5) means 5×16 = 80 simultaneous connections to the same CDN host. The label "High-speed segmented CDN connections" does not warn that this can trigger anti-bot blocks or IP bans. Similar: stall timeout (15-300s, default 60) — 15s will kill legitimate slow downloads on mobile networks; the label does not explain the trade-off. No "reset to default" exists on any slider.

- **[minor] `SocialModal.kt:173-175`** The format selector labels read "Video (MP4)" and "Audio Only (MP3)", but yt-dlp may output `.webm` (video) or `.m4a`/`.opus` (audio) depending on the source. The label promises a specific container that the download may not deliver.

- **[minor] `QuickShareActivity.kt:406-426`** The engine selector labels "Auto", "Fast" (aria2c), "Extractor" (yt-dlp). A user picking "Fast" for an Instagram or TikTok URL queues aria2c on a social embed page — resolution fails, the task fails. The labels provide no guidance about when to use each engine.

- **[minor] `MainActivity.kt:125-133`** The artificial 1100ms splash delay runs on every cold start. The splash is a Compose screen that delays the real UI by over a second. On a fast device this is a visible, unnecessary wait.

- **[minor] `HomeScreen.kt:453-472`** `TorrentFilePickerHost` runs an infinite `while(true)` loop with a 250ms delay polling `TorrentFilePicker.consume()` — a continuous background task on the main composable's scope (even when no torrent selection is pending). Battery impact is small but unnecessary.

- **[minor] `EpisodeDrawer.kt:82-87`** Season grouping heuristic: `episodeNum >= 100 -> episodeNum / 100`. For shows with episode numbers in the 100s (e.g. episode 150), this mis-groups as season 1. Shows with >100 episodes per season are incorrectly grouped.

- **[minor] `MainActivity.kt:237-248`** `handleShareIntent` is dead code — the manifest routes `ACTION_SEND` exclusively to `QuickShareActivity`. MainActivity's share handling never fires, making the clipboard-upon-launch and social-modal paths the only ways to handle social URLs in the main activity. This is safe but confusing for maintainers reading the code.

- **[minor] `DownloadsScreen.kt:149-168`** The DownloadCard does not display which site the download came from (the `task.site` field exists in the model but is unused). The user cannot distinguish a download from NaijaVault vs NKiri in the list.

- **[minor] State communication — enqueue from the drawer gives zero feedback.** The drawer dismisses silently; no snackbar, no toast, no badge increment animation. The only way to confirm episodes were queued is to navigate to the Downloads screen and check the count. For a batch of 20 episodes the user is left guessing.

- **[minor] `EpisodeDrawer.kt:47-76`** The range field parses on every keystroke. Typing "1-5" first selects episode 1 (when "1" is typed), then 1-5 (when the full range is entered). Transient incorrect selections flash briefly. Unlikely to cause accidental downloads but visually confusing.

## Nits

- **[nit] `SettingsSheet.kt:596-618`** Peer Connections slider: steps=9 over range 0-500 means each step is ~55 peers. To set 10 peers you must hit a near-impossible slider position. The slider is effectively a coarse toggle between "Auto" (0) and "500" (max). This is usability theater.

- **[nit] `HomeScreen.kt:55-67`** Filter chips are hardcoded with site names. The playbook can add dynamic providers (GenericDeclarativeProvider) but the filter chips won't include them — new OTA sites are undiscoverable via the site filter.

- **[nit] `EpisodeDrawer.kt:218-230`** The "Clear (N)" chip shows the count of selected items; clearing is a single tap. Combined with the "All" button fallback, the clear action is one tap away from "download everything" — the UI should distinguish between "selection cleared" and "no selection" more clearly.

- **[nit] `MainActivity.kt:56`** Theme mode is read from SharedPreferences on the main thread in `onCreate`'s `setContent` lambda. This is a minor UI thread violation.

- **[nit] `DownloadsScreen.kt:351`** The `estimating` flag is `task.status == DOWNLOADING && speedBytesPerSec < 1024.0 && totalBytes > downloadedBytes`. For a genuinely stalled download at 0.5 KB/s, this shows "Estimating..." instead of a stall warning. The watchdog will eventually kill it, but the user sees no indication of a problem.

---

## Verdict

The storage permission flow is the most dangerous UX problem: on Android 9-10 the user can get permanently stuck in an infinite dialog loop (critical), and the share-intent path (QuickShareActivity) bypasses storage permission entirely, silently dropping downloads with a deceptive "queued" toast. The episode drawer is a data-cost footgun — it offers to queue a full season with **zero** information about file sizes, resolution, or data usage, and a typo in the range field silently downloads every episode. The download card lies about HLS progress (no "est." markers, contradictory 100% + overshoot display) and hides the network-pause reason from the user. The three-backend download engine and OTA update UI are well-designed, but the permission and batch-queueing surfaces need urgent fixes before the app is safe for everyday use on mobile data.