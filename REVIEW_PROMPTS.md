# Anon Downloader — Independent Review Prompt Kit

Paste the prompts below into ChatGPT, Claude, or any other AI service to get a
fresh-eyed, independent review of this codebase. Each prompt is self-contained:
give the AI the full codebase (or the listed key files), then paste the prompt.
Reviewers are intentionally asked to be critical — the goal is to find real
problems, not validation.

---

## 1. Context for the reviewer

Anon Downloader is a serverless Android app that scrapes streaming sites,
resolves download links through a resolver chain, and downloads files via
aria2c/yt-dlp/TurboDownloader. It uses signed OTA playbooks to update scraping
logic without APK updates.

It is written in Kotlin with Jetpack Compose. Its architecture:
- **Kernel + OTA Playbooks v2 + Health Ledger**: the app kernel ships a
  minimal scraping core; a signed, encrypted playbook (`scraper_rules.json.enc`)
  updates site URLs, selectors, locker hosts, referer policies, and search
  strategies over the air. A persistent on-device host-health ledger
  (`HostHealth`) records per-host success/failure and learns locker hosts the
  playbook never listed.
- **LockerRegistry**: an evidence-based media-link classifier
  (Direct / Locker / Unknown / None) that extracts every plausible anchor from
  an episode page, races known lockers concurrently (`resolveAny`), and probes
  unknown hosts once via a 1KB range-request validator.
- **Resolver chain**: 25 resolvers in a flat registry with recursive descent
  (depth cap 6), an in-memory TTL cache, and a health gate.
- **Download engine**: three backends (TurboDownloader, aria2c via
  libaria2c.so, yt-dlp), a watchdog that kills stalled backends, a rate-drop
  detector, an HLS segment-sampling size estimator, and auto-rescue of tasks
  interrupted by process death.

---

## 2. Review prompts

### Prompt 1 — Architecture & Design

**Context:** This app's core architectural claim is "Kernel + OTA Playbooks v2
+ Health Ledger": the APK is a thin kernel and the scraper-specific knowledge
lives in a signed, OTA-updatable playbook, while the app learns host behavior
on-device through an evidence ledger. Evaluate whether this design actually
holds together — whether the module boundaries are clean, whether the
evidence-based "extract everything, arbitrate by evidence" approach in
LockerRegistry is sound, and whether the resolver chain and pipeline layers
are coherent or entangled.

**Focus areas:**
1. Trace the flow from episode URL to DownloadRecipe. Are the pipeline,
   resolver, and provider layers cleanly separated, or do providers reach
   around the abstractions (e.g. NaijaPreyProvider re-routing through
   ResolverRegistry, NaijaVaultProvider's all-links sweep)? What does that
   say about the module boundaries?
2. Evaluate LockerRegistry's classify()/resolveCandidates() design: the
   classifier, the NAV_SEGMENTS heuristic set, the "unknown hosts get probed
   once" rule, and HostHealth's `hasProvenLocker` learning. Is the evidence
   model sound, or does it trade precision for recall in ways that hurt?
   Note: three providers (Nkiri, Rocks, DramaRain) were reverted off the
   classifier after it produced regressions — what does that reveal about the
   abstraction's maturity?
3. Assess the OTA playbook coupling: how much of the app's behavior actually
   depends on playbook data, and how much remains hardcoded (e.g.
   DEFAULT_LOCKER_HOSTS, resolver list, engine constants)? Is the kernel
   actually thin?
4. Is the object-heavy "registry of objects" pattern (ResolverRegistry's flat
   list of 25 resolver singletons) scalable, and is the sealed-class error
   model (PipelineError) used consistently across the pipeline?
5. Where are the seams that a future maintainer would have to break to add a
   new site, a new locker, or a new backend?

**Key files:**
- `app/src/main/java/com/anonrode/downloader/resolvers/LockerRegistry.kt`
- `app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt`
- `app/src/main/java/com/anonrode/downloader/pipeline/HostHealth.kt`
- `app/src/main/java/com/anonrode/downloader/pipeline/StreamValidator.kt`
- `app/src/main/java/com/anonrode/downloader/pipeline/PipelineError.kt`
- `app/src/main/java/com/anonrode/downloader/pipeline/ResolveCache.kt`
- `app/src/main/java/com/anonrode/downloader/data/rules/DynamicRulesManager.kt`
- `app/src/main/java/com/anonrode/downloader/providers/ProviderRegistry.kt`
- `app/src/main/java/com/anonrode/downloader/providers/NaijaPreyProvider.kt`
- `app/src/main/java/com/anonrode/downloader/providers/NaijaVaultProvider.kt`
- `HANDOVER.md` (section 3, "Architecture")

---

### Prompt 2 — Security

**Context:** The app distributes scraping rules via a signed, encrypted OTA
playbook: AES-128-CBC for confidentiality (the key ships inside the APK and in
the plaintext signing script), ECDSA P-256 for authenticity (public key
embedded in the app, private key in a GitHub Actions secret). It also
requests MANAGE_EXTERNAL_STORAGE ("All files access"), uses a persistent
session cookie jar, sends custom referers to scraping targets, and enables
cleartext traffic. Assess the real threat model and whether the protections
are meaningful or decorative.

**Focus areas:**
1. The OTA signing pipeline: the AES key is hardcoded in both the app
   (`DynamicRulesManager.kt`) and `scripts/encrypt_rules.py`, and the plaintext
   `scraper_rules.json` sits in the repo root. What can the AES layer actually
   protect, and does the ECDSA signature meaningfully raise the bar for
   tampering (repo hijack, MITM, malicious playbook)? Is key rotation feasible?
   Is there any path where the app accepts unsigned or legacy-format payloads?
2. MANAGE_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE + `usesCleartextTraffic`
   + `allowBackup="true"`: are these justified by the app's functionality, or
   are they over-broad? What data could leak via backup, log files, or the
   shared activity log?
3. The network layer: the session cookie jar (shared cookies, capped list) and
   referer handling — do cookies leak across hosts, is the referer policy
   coherent, and does the app send anything beyond what a browser would?
4. Scan for credentials/secrets/keys anywhere in the repo (build files, CI
   workflows, configs, tests, docs). Is the private signing key actually
   absent from the repo, and are there any other hardcoded secrets?
5. Torrent handling (`TorrentSecurityShield.kt`): what does the shield
   actually protect against, and what risks remain (content, metadata, peer
   exposure)? Also assess anything else security-relevant you find: HTTPS
   enforcement, WebView usage, intent handling (share intents), certificate
   pinning (or lack thereof).

**Key files:**
- `scripts/encrypt_rules.py`
- `app/src/main/java/com/anonrode/downloader/data/rules/DynamicRulesManager.kt`
- `app/src/main/java/com/anonrode/downloader/data/net/HttpClient.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/anonrode/downloader/MainActivity.kt`
- `app/src/main/java/com/anonrode/downloader/security/TorrentSecurityShield.kt`
- `.github/workflows/build-apk.yml`
- `.github/workflows/ota-rules.yml`
- `scraper_rules.json` (plaintext reference)
- `NOTICE.md`

---

### Prompt 3 — Code Quality & Kotlin Idioms

**Context:** This is a single-developer Kotlin/Compose app ported from a
Python monolith, with a heavy emphasis on live-site verification. Review the
code as a Kotlin codebase: style, idiom use, nullability, exception handling,
coroutines, sealed classes, and test coverage. The goal is an honest assessment
of maintainability for a future contributor — not a style nitpick.

**Focus areas:**
1. Exception handling: several providers and resolvers swallow exceptions
   with bare `catch (_: Exception) {}` and return empty results — trace where
   this hides failures and where it is legitimate. Is the PipelineError sealed
   class used consistently, or bypassed?
2. Nullable handling and correctness: look for patterns like `?.let { } ?:`
   chains, `substringAfter`/`substringBefore` string surgery, and places where
   null/blank strings are overloaded to mean "failed" (e.g. resolve returning
   `String?`). Does the code distinguish "not resolved" from "resolved to
   nothing"? Are there silently swallowed errors that would produce confusing
   user-visible failures?
3. Coroutines: is the codebase disciplined about scopes (e.g. the engine's
   `CoroutineScope(Dispatchers.IO + SupervisorJob())`), cancellation, and
   structured concurrency? Are there races, leaks, or fire-and-forget
   launches that could strand work?
4. State & duplication: `DownloadEngine` holds many mutable settings and
   flags — are there duplicates or inconsistencies (e.g. both
   `wifiOnlyTorrents` and `downloadTorrentsWifiOnly`)? Is state management in
   the ViewModel/UI coherent?
5. Test coverage: review the unit tests (rules, engine, resolvers, estimator,
   shield) and the single androidTest. Do they test behavior or implementation
   details? What critical paths (resolver chains, watchdog, permission flows,
   Compose UI) have no tests? Also assess build config in `build.gradle.kts`.

**Key files:**
- `app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt`
- `app/src/main/java/com/anonrode/downloader/engine/HlsSizeEstimator.kt`
- `app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt`
- `app/src/main/java/com/anonrode/downloader/resolvers/LockerRegistry.kt`
- `app/src/main/java/com/anonrode/downloader/providers/NaijaPreyProvider.kt`
- `app/src/main/java/com/anonrode/downloader/data/models/Models.kt`
- `app/src/main/java/com/anonrode/downloader/viewmodel/MainViewModel.kt`
- `app/src/test/java/` (all test files)
- `app/src/androidTest/java/com/anonrode/downloader/MainSmokeTest.kt`
- `app/build.gradle.kts`

---

### Prompt 4 — Performance & Resource Usage

**Context:** The app is a phone doing heavy network work: it races resolver
candidates concurrently, probes unknown hosts, samples HLS segments to
estimate sizes, and runs a watchdog with a rate-drop detector over downloads
using up to 16 parallel sockets per file. Review whether the app is
memory-conscious and bandwidth-conscious on a mobile device, and whether the
concurrency is bounded, correct, and worth its cost.

**Focus areas:**
1. `resolveAny` racing: up to 3 concurrent resolver candidates, losers
   cancelled mid-flight, plus a recursion depth cap of 6 and a retry wrapper.
   Are the concurrency limits actually enforced end-to-end (including nested
   `resolveAny` calls inside resolvers)? What are the worst-case request
   fan-outs per episode, and what happens on slow networks?
2. The HLS segment-sampling estimator: 4 concurrent header-only probes at
   playlist percentiles, plus an audio-rendition probe, plus playlist fetches.
   Is the estimate sound (sample at 5/35/65/95%, mean × count × remux factor
   0.93/0.99), and does the extra request traffic justify a diagnostic-only
   value that the UI may not even display?
3. Cache/TTL strategy: the in-memory resolve cache (token TTL from the
   playbook) and the 4-minute search result cache — are TTLs sane, is the
   cache bounded, and does anything keep references longer than needed?
4. The download engine's watchdog: stall detection, crawl detection
   (64 KiB/60s floor), the rate-drop detector, zombie cap, and
   `MAX_STALL_KILLS` retry semantics. Are the heuristics robust against
   legitimately slow-but-alive transfers? Is `parallelSocketsPerFile = 16`
   sensible on mobile radios and mid-range devices?
5. Memory: any unbounded collections (cookie jar, journals, logs, caches),
   large HTML held in memory, `String`/regex churn, or Main-thread work in
   the Compose UI and engine callbacks that could cause jank or OOMs?
   Also assess `StatFs`/storage-guard usage and disk layout.

**Key files:**
- `app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt`
- `app/src/main/java/com/anonrode/downloader/engine/HlsSizeEstimator.kt`
- `app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt`
- `app/src/main/java/com/anonrode/downloader/engine/TurboDownloader.kt`
- `app/src/main/java/com/anonrode/downloader/pipeline/ResolveCache.kt`
- `app/src/main/java/com/anonrode/downloader/pipeline/HostHealth.kt`
- `app/src/main/java/com/anonrode/downloader/pipeline/StreamValidator.kt`
- `app/src/main/java/com/anonrode/downloader/data/net/HttpClient.kt`
- `app/src/main/java/com/anonrode/downloader/util/NetworkObserver.kt`

---

### Prompt 5 — UX & UI

**Context:** The UI is a Jetpack Compose app: a home/search screen, per-site
episode drawers, a downloads screen with per-task cards, a settings sheet, and
a storage-permission flow that must route Android 11+ users to the system
"All files access" settings page (there is no runtime dialog for that
permission). Review how clearly the app communicates state and guides the
user through its unusual permission and download flows.

**Focus areas:**
1. The storage permission flow in MainActivity: rationale dialog, launcher
   callbacks that re-check `Environment.isExternalStorageManager()`, and the
   notification-permission request on API 33+. Walk the flow for a first-time
   user on Android 13+ and Android 9-10. Where can it get stuck, confuse, or
   send the user in circles? Is the rationale copy adequate?
2. The download card and progress display (DownloadsScreen/DownloadCard):
   how are the seven task states (QUEUED, RESOLVING, DOWNLOADING, VALIDATING,
   PAUSED, COMPLETED, FAILED) conveyed? Are failures actionable (Retry with
   an error message), and is progress (bytes, speed, ETA, resolution) honest
   — especially for HLS downloads where total size is only an estimate?
3. The episode drawer and home screen: is it clear which episodes are
   downloaded, which sites a show came from, and what will happen when the
   user taps download (resolution, backend choice, size)? Is there any
   discoverability problem with multi-site search results?
4. The settings sheet: with dozens of engine options (socket counts, stall
   timeouts, retry counts, torrent privacy mode), which settings are
   user-comprehensible and which are dangerous knobs? Are defaults
   reasonable and do labels explain consequences?
5. State communication generally: loading indicators, empty states, error
   toasts vs. inline errors, network-loss handling, and the share-intent /
   social-download flow. List concrete places where the UI lies, stalls, or
   leaves the user guessing.

**Key files:**
- `app/src/main/java/com/anonrode/downloader/MainActivity.kt`
- `app/src/main/java/com/anonrode/downloader/ui/screens/DownloadsScreen.kt`
- `app/src/main/java/com/anonrode/downloader/ui/screens/EpisodeDrawer.kt`
- `app/src/main/java/com/anonrode/downloader/ui/screens/HomeScreen.kt`
- `app/src/main/java/com/anonrode/downloader/ui/screens/SettingsSheet.kt`
- `app/src/main/java/com/anonrode/downloader/ui/screens/SocialModal.kt`
- `app/src/main/java/com/anonrode/downloader/ui/screens/QuickShareActivity.kt`
- `app/src/main/java/com/anonrode/downloader/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/anonrode/downloader/service/DownloadService.kt`

---

### Prompt 6 — Maintainability & OTA Data-Driven Design

**Context:** The stated design goal is that the OTA playbook ("OTA Playbooks
v2") reduces the need for APK updates: sites, selectors, locker hosts, referer
policies, and search strategies are all data. There is a conformance suite
(`probe/conformance.py`) that validates the signed payload against live sites,
and extensive documentation (HANDOVER.md). Evaluate whether this claim is
true in practice, whether the playbook schema is well-designed, and whether a
new maintainer can actually keep this system alive.

**Focus areas:**
1. Does the OTA playbook genuinely reduce APK-update frequency? Look at what
   is playbook-driven vs. hardcoded (resolver registry, LockerRegistry
   defaults, provider logic, engine constants). Find at least one change in
   the recent history that required a Kotlin change anyway (e.g. the
   episodeSelector field that NaijaVaultProvider never reads, wildshare
   re-routing in NaijaPreyProvider) — how much of the "serverless OTA" claim
   holds?
2. Playbook schema quality: fields like `lockerHosts`, `hostPolicies`
   (ordered referer rules), `searchStrategies`, `knownDead`,
   `tokenTtlMinutes`, `directMediaExtensions`, `episodeSelector`. Are field
   semantics documented, validated (see `encrypt_rules.py` validation and
   caps), and versioned (envelope `v`)? What happens if a malformed or
   partially-applied playbook ships — is there a fallback, and is the
   plaintext/encrypted pair kept in sync?
3. The conformance suite: it verifies the signed `.enc`, runs 4 stages
   (search, episodes, direct-pass, locker-discovery) against live sites, and
   mirrors Kotlin classification logic in Python (`classify_media`). How much
   regression protection does it actually provide, given it does not exercise
   the Kotlin resolver algorithms, the watchdog, or the engine? Where would a
   regression slip through (e.g. the `.webmanifest`-vs-`.webm` bug)?
4. Documentation: does HANDOVER.md give a new maintainer what they need —
   known-broken state, reverts, the "non-negotiable laws", the checklist?
   What is missing or stale, and is the split between HANDOVER.md, the audit
   doc, and NOTICE.md coherent? Is there anything dangerous about
   maintaining this project as documented (unpushed commits, manually
   regenerated envelopes)?
5. Test/code drift: do the JVM tests and the Python conformance mirror stay
   in sync with the Kotlin code (e.g. `DEFAULT_LOCKER_HOSTS` mirrored in
   `conformance.py`)? What is the cost of keeping two classification
   implementations in two languages?

**Key files:**
- `HANDOVER.md`
- `HANDOVER_AUDIT.md`
- `app/src/main/java/com/anonrode/downloader/data/rules/DynamicRulesManager.kt`
- `scripts/encrypt_rules.py`
- `probe/conformance.py`
- `scraper_rules.json` / `scraper_rules.json.enc`
- `app/src/main/java/com/anonrode/downloader/providers/NaijaVaultProvider.kt`
- `app/src/main/java/com/anonrode/downloader/providers/NaijaPreyProvider.kt`
- `app/src/main/java/com/anonrode/downloader/resolvers/LockerRegistry.kt`
- `.github/workflows/conformance.yml`
- `app/src/test/java/com/anonrode/downloader/resolvers/LockerRegistryTest.kt`

---

## 3. Catch-all prompt

Use this when you want a single, broad, independent review. Paste it together
with the full codebase:

> I have an Android app called Anon Downloader. Here is the full codebase.
> Review it comprehensively for any issues, and organize your findings by
> severity (critical / major / minor / nit).
>
> Context: Anon Downloader is a serverless Android app that scrapes streaming
> sites, resolves download links through a resolver chain, and downloads files
> via aria2c/yt-dlp/TurboDownloader. It uses signed OTA playbooks to update
> scraping logic without APK updates. It is written in Kotlin with Jetpack
> Compose, and was ported from a Python monolith.
>
> Cover at minimum:
> 1. **Architecture** — the kernel + OTA playbook + health-ledger design,
>    LockerRegistry's evidence-based classification, the resolver chain, and
>    module boundaries.
> 2. **Security** — the OTA signing/encryption pipeline (AES-128-CBC key in
>    the APK, ECDSA P-256 signature, private key in CI), the
>    MANAGE_EXTERNAL_STORAGE / cleartext-traffic / backup configuration, the
>    cookie jar and referer handling, and any exposed secrets or credentials.
> 3. **Code quality** — Kotlin idioms, exception handling (including silent
>    `catch {}` blocks), nullability, coroutine usage, and test coverage.
> 4. **Performance** — concurrent resolver racing, the HLS segment-sampling
>    estimator, caching/TTLs, the download watchdog and rate-drop detector,
>    and memory/bandwidth consciousness on mobile.
> 5. **UX** — the download card and progress display, the storage-permission
>    flow, the settings sheet, the episode drawer, and clarity of state
>    communication.
> 6. **Maintainability** — whether the OTA playbook actually reduces APK
>    update frequency, the playbook schema and conformance suite, and whether
>    the documentation (HANDOVER.md, README) is sufficient for a new
>    maintainer.
>
> Be blunt and specific: quote the exact code locations for every issue you
> find, and for each issue explain the realistic impact in this app's actual
> usage (scraping live sites, downloading on phones with limited bandwidth).
> Do not include praise unless something is genuinely exemplary; focus on
> finding real problems.
