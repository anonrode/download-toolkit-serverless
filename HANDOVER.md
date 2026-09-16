# 🚀 ANONRODE DOWNLOAD ENGINE — HANDOVER & ARCHITECTURE DOSSIER

**Target Codebase:** `C:\Users\user\Anon\ANON TOOLS\download-toolkit-serverless` (Android / Kotlin / Jetpack Compose / OkHttp / libaria2c / youtubedl-android)
**Reference Python Monolith:** `C:\Users\user\Anon\ANON TOOLS\download-toolkit` (`src/downloader.py`, `src/resolvers.py`, `src/extractors/`) — **the battle-tested reference; the Kotlin app is a port of it, and porting infidelities are the #1 bug source.**
**Operating Charter:** `.agents/AGENTS.md` (Permanent source of truth — never delete)
**Status:** ✅ **THE CRASH SAGA IS CLOSED.** The v3.1.1 tag was force-moved to the true fixed 313/3.1.3 build and re-published 2026-09-13 23:42Z (5 APKs; CI green INCLUDING the first-ever Tier-2 device-engine gate run); the user installed it — the 09-14 device log shows app starts, downloads completing, ZERO new crash entries. **ROUND 3 NOW COMMITTED LOCAL (314/3.1.4, push needs a NEW GO)** from the two engine complaints in that same log: (a) **THE NEVER-CANCEL RULE** (user decree: "if the download ever starts then it should have no reason to cancel") — a task that has moved ANY bytes can no longer be FAILED by host hiccups: the vikingfile 429 storm killed a download with 64 MiB already banked; it now PARKS (`PAUSED`, "Download server cooling down — … MB already saved, it will resume") with an escalating 60s→15m requeue floor so repeated rejects can't re-storm, and the existing cooldown loop auto-resumes it; 429 additionally no longer triggers the Turbo↔aria2c fallback relaunches; zero-byte tasks keep honest failures + the 30-min/6-cycle give-up caps. (b) **PROGRESS TRUTH** — Turbo now reports COMMITTED disk bytes (sidecar piece sum), not received bytes: the Suits bar pinned at 100% for 2+ minutes while one slow socket held the committed prefix 60 MiB short. Commits `e06884f` (Turbo) + `e06b6a3` (engine) + `9dcfa86` (bump). **ROUND 4 (same evening — user rejected the park answer: "it shouldn't f*cking throttle, it never throttles when I download on 1dm, it should f*cking work"): they were right, the park treated a SELF-INFLICTED wound.** The 429 storm was OUR shape: a piece hit the CDN's rate limit, burned its 5-attempt/15s retry budget ON 429s, set the global fail flag (killing 15 healthy siblings mid-stream), then aria2c hammered the same URL 3× more — that storm is what keeps a limiter tripped. 1DM survives because it WAITS and resumes from offsets. Fix `f41f220` (LOCAL): HTTP 429/503 never consumes an attempt in Turbo — one shared escalating cooldown (15s → 5 min cap) every socket sits out, the active worker count halves per cycle (16→8→4→2) so the resumed traffic is the shape that calms the host, and the SAME session continues from the sidecar offsets (no restart, yt-dlp never touched for direct links). The engine watchdog now tells "politely waiting" from "dead": while a task has a live Turbo throttle deadline NO stall/crawl/throttle/zombie kill fires (15-min grace cap) — the bar freezes at committed bytes, then resumes, exactly like the desktop managers. **PARKING IS NOW THE LAST RESORT (post-cap blackhole), not the answer to a routine hiccup.** JVM tests walk a 40-rejection storm to Success. `9dcfa86` stays 314/3.1.4 — this all rides the same unpushed release; push needs a NEW GO. **GENRE SECTIONS (`5f24d26` + poster-tile iteration `0617e9d`, LOCAL, ships as its OWN release 3.1.5 after 314, never bundled with engine work):** six POSTER TILES under Trending on the blank-query landing — user picked the shape from the rendered mockup (09-14): Reacher-box 124dp rectangles wearing each genre's LIVE top-post poster (`CategoryFeed.tilePosters`, per_page=1 from the top-priority site so tile = the page's first card; colored glyph-tile fallback, row never disappears over artwork). Tapping opens a root-overlay page (b5cdf99 doctrine — never a Dialog window) of up to 3 stacked horizontal rows, one per site, cards + EpisodeDrawer tap identical to trending. MECHANISM PIVOT (probe 09-14): the sites' WP categories are REGIONAL/media-type (nkiri: K-Drama/TV Series; 9jarocks: Hollywood/Anime/gist; naijaprey: Movie/Series; naijavault REST CF-blocked) — no genre taxonomy exists to point at; rows query each site's genre listings instead: WP **search RSS feed** for 9jarocks/naijaprey (posters embedded 22/22, 5/5) and **WP-REST search with `_embed`** for nkiri/naijavault (their search feeds carry ZERO posters; REST answers 5/5, 3/3 — exact per_page/search/_embed shape re-verified against the code's builder). `CategoryFeed` reuses `TrendingFeed.fetchRss`/`fetchWpRest` (both now path/query-parameterized — one parser per feed shape, no copy-drift); `assembleRows` is pure + JVM-tested (skip empties, cap 3 in priority order, cross-site dedupe); VM caches per genre once per process with cancelled-on-leave + label-guarded writes. Mockup: `ANON TOOLS/genre-mockup.png` (real posters). **SEARCH-VERIFY ORACLE (user: "a better search architecture… checks the logic down to the links… shouldnt flag downloadable as not downloadable and absolutely avoid undownloadable links" — MEASURED FIRST, 70 budgeted requests 09-14: nkiri's HTML search showed 3/5 tap-dead stubs (its search had NO gate); every gated chain traced actually cracks to bytes (downloadwella → 220/172 MB LIVE; vault /dl- → /temp/ → vikingfile → .zip; loadedfiles ?pt= → CDN .mkv); wildshare-class (marker present, chain garbage) occurred 0/13 today — it's incident-rate, not daily-rate. Doctrines follow from that: hide ONLY on proof (terminal 404/410 confirmed twice, per-episode retry first — one html-terminal episode must not kill a live show), badge ONLY winners (user picks 09-14: "✓ 220 MB" / "✓ 24 eps · 450 MB/ep" — episode count learned free from verification's own drawer fetch), uncertainty renders as a completely normal tappable card, verified float to the top by revealing in rank order.** Commits: `11ab565` nkiri search → WP-REST search + free DownloadLinkGate (orderby=relevance live-verified; HTML scrape kept as fallback); `3975439` crack ladder moved verbatim out of DownloadEngine.resolveStreamUrl into `pipeline/LinkResolver` (engine keeps invalidate-first allowCacheHit=false semantics via delegates; oracle uses cache hits; Pluto's CF-challenge detector shared — a challenge is NEVER proof of absence); `38c1e47` `pipeline/DownloadVerdict` — Verdict sealed class + VerdictPolicy pure doctrine (keys lowercase host/drop fragment but KEEP token case; LIVE 30min / DEAD 24h / UNREACHABLE 5min; preResolved window 120s); `fdcfbc4` `pipeline/ResultVerifier` — background queue (6 cards/query/3 attempts/card/20s/4 workers, HTTP tag "verify", new query swaps queue + tagged-cancel, tap prioritizes; injectable Chain seam); `2ba55ee` reveal wiring (VM streams verdicts into state; HomeScreen branches render via visibleOrdered; caption REPLACES the fake hardcoded "✓ 1080p" badge); `aca50ff` instant tap (Live carries episodeUrl; verifiedDirect(episode) rides enqueue's existing direct-file pipeline, sourceUrl untouched for the 401/403/404 self-heal). `dd4bd46` fixed a stray-brace CI-killer caught pre-push by a depth lexer (no local compiler — CI is the only build). 27 new JVM tests. Historic crash narrative below, kept for the record. The device activity log (2026-09-13 evening) shows EVERY download tap killing the app: `ExceptionInInitializerError` at `DownloadEngine.sanitizeComponent` — `util/NameSanitizer` (new in `d47c747`) embedded `(?i)` mid-pattern inside `DASH_EPISODE_JOIN`'s lookahead; Android's libcore regex engine accepts embedded inline flags only at position 0, so the class initializer threw PatternSyntaxException on ART while all 244 unit tests ran on a desktop JVM that accepts it — Tier-2 smoke never taps enqueue, so CI could not catch this by construction. Fix `10a6958`: every regex in the class compiles via the canonical `RegexOption` form (engine-agnostic, identical semantics); new permanent static gate `~/.zcode/tmp/ktregexflags.py` proven to catch the exact shipped pattern. `bd8368c`: 9jarocks drawer labels server-mirror locker links `Server N` (the Safety (2020) film showed as S01E01+S01E02 — one file, two hosts); asianc `Safety First ep 400+` was NOT an app bug (live-verified twice: the site's own 45-item list is numbered 481–525). `e5d8248` bumps 312/3.1.2. `b5cdf99`: **PLAYER RESTRUCTURED** — the six-round fullscreen complaint was the Dialog WINDOW itself (platform/OEM sizes it; re-clamps on recomposition; every patch lost). The player is now a root-level overlay in the activity's OWN window (hosted by MainActivity after MainScaffold): fillMaxSize = fullscreen by construction, no window left to shrink; bars hidden for the player's lifetime on the activity window. `04e7eb0`: full drawer sweep — shared `util/DownloadLinkLabels` kills the fake-episode count fallback for mirror-server/part film links in EVERY drawer (Nkiri, DramaKey, DramaRain, RulesPipeline (all OTA sites), GenericDeclarative, NaijaVault guard, Pluto, AsianC) + content scoping fixes (DramaRain's `.entry-content a` catch-all turned synopsis links into episodes). ALL hotfix commits were PUSHED and v3.1.1 force-retagged to `437708b` (first run died on a missing comma — python arg-splice casualty, compiler-caught; second run green, 5 fresh APKs 21:18:53Z, internally 312/3.1.2 under the v3.1.1 label). **SECOND INCIDENT (activity-log-share-4, 22:39): the retagged 312 build STILL crashed** — same `sanitizeComponent` line, same `ExceptionInInitializerError`. Lesson: `(?i)`-mid-pattern was only ONE member of a whole class of syntax the desktop JVM compiles and the phone's regex fork rejects; `endBare`'s lookbehind `(?<=\S)` sits in the same `<clinit>` and the v3.1.1 gate only scanned inline flags. Fix `bfa5404` (LOCAL, unpushed): all FIVE main-source lookbehinds purged to non-capturing-prefix equivalents (NameSanitizer endBare, NkiriProvider EP_TOKEN ×3, RulesPipeline ANCHOR_NUM_PATTERNS), group numbering preserved; gate extended to the whole class (lookbehind/named-group/atomic/possessive/\p + mid-pattern flags) and re-control-tested; ENGINE RULE rewritten as a portable whitelist. `8f0704c`: crashLog now prints the CAUSE chain FIRST and untruncated — the decisive "Caused by:" line was cut by take(2000) in BOTH crash logs, so the phone never revealed the actual PatternSyntaxException message. 313/3.1.3 bumped. **FINAL CHAPTER: the push+retag GO was executed that night — release v3.1.1 re-published 23:42:25Z from the fixed 313 tip (tag run 34789223870, attempt 2 green after attempt 1's script hiccup), 5 APKs, device-confirmed working 09-14.**
**RECHECK (later that night — user challenge 'I still think you haven't fixed whats causing the crash'): the user was right to push back.** Engine archaeology (LineageOS libcore mirrors = AOSP): Android 8.1–14 ship `ojluni` `Pattern.java` wrapping **ICU4C** (`icuFlags`, zero OpenJDK parser code on 9/10-generation branches) and OpenJDK 17 on the newest — BOTH accept mid-pattern `(?i)` AND lookbehind `(?<=`. So the shipped engines cannot be assumed to reject the two syntaxes my hotfixes removed: the `(?i)` fix and the lookbehind purge remain valid portability hygiene (the phone demonstrably rejected *something* in that `<clinit>`, twice), but **the root cause is now formally UNCONFIRMED — no longer a solved case**. Two artifacts replace speculation: (1) `2228294` — Tier-2 DEVICE-ENGINE GATE: new instrumented suite (`RegexEngineCompatTest` + generated `RegexCompatCatalog`, 68 classes / 108 static patterns / interpolated builders via runtime calls) compiles every regex the app holds ON THE REAL ANDROID ENGINE in CI — the first test that could ever have caught this class pre-ship; regenerate via `scripts/gen_regex_compat_catalog.py` after any regex change and commit both. (2) `8f0704c`+`d11ee48` — crashLog cause-chain, DEVICE line (API/model) in startup banner + crash files, and **Share Activity Log now appends `anon_crash.txt`** — which CrashHandler has ALWAYS written with the full untruncated `printStackTrace` including `Caused by:`. **RESOLVED MINUTES LATER (user retrieved the file, 5 crash entries)**: every crash since v3.1.1 — both builds — names ONE poison: the DECORATIONS brace pattern (curly-brace decoration) ended in an UNESCAPED closing brace. The desktop JVM reads a bare `}` as a literal; the phone engine — `com.android.icu.util.regex.PatternNative`, so Android's java.util.regex IS ICU4C-backed (the earlier ‘shipped engines accept everything’ recheck was ALSO wrong: ICU accepts (?i)/(?<=) but REJECTS unmatched `}`) — threw ‘Syntax error in regexp pattern near index 16’ at NameSanitizer `<clinit>` (line 51 on build 311, line 60 on 312). Both prior theories were chasing constructs the initializer never reached. Fix `168f62d`: both braces escaped, identical semantics. The same sweep caught FOUR more identical stray-`}` patterns in RulesPipeline — which DIDN’T crash only because try/catch swallowed them: renderTemplate returned null for EVERY templated OTA-pipeline URL, so playbook-only sites have been silently dead on-device since the pipeline shipped; fillSeasonTemplate/fillCaptureTemplate degraded labels (raw capture tokens leaking into titles). All fixed in 168f62d. A new curlyDecoration unit test pins the behavior (brace decoration had ZERO coverage before — JVM-side AND device-side blind). 313/3.1.3 is the RELEASED fixed build (tag v3.1.1 re-pointed 23:42Z): poison removed, the device-engine CI gate passed GREEN on the tag run, and the 09-14 device log proves downloads complete crash-free on real phones. Gallery fix `32d518a` is now finally reachable on-device — it never ran before because the crash fired before any file existed.  CI = the only sanctioned build (user decree 2026-09-13: NEVER build locally).
**Last updated:** 2026-09-15 (**v3.1.5 RELEASED 15:19:54Z — 5 APKs: splash identity (`5804fd0`) + YouTube playlists (`f68c1f9` + CI-round fix `a91a478`); v3.1.4 was released 07:04Z with the 21-commit PLAYER/SUBS/FAIRNESS round: group-by-show numeric ep order + same-show player queue, site-fair oracle budget, Seal-parity subtitles (Settings + yt-dlp SRT embed + MKV auto-read via pure `autoPickSubtitleIndex`), Rotate chip + PiP mini-player. Design docs final: `ANON TOOLS/designs/2026-09-14-round/` (index + rev-6 physics motion study, decisions recorded).)
**Open at this marker:** (1) **v3.1.5 IS RELEASED** (2026-09-15T15:19Z, 5 APKs — splash +
playlists + docs + bump rode the user's "push to vl to buid" GO; CI round 1 red on ONE test that
caught a REAL bug — `videoIdOf` parsed `?v=` from any host, risking false "✓ on device" skips —
fixed `a91a478`, unpublished tag repointed, round 2 green both runs). (1b) v3.1.4 was released
07:04Z (engine 314 + genre + oracle + player/subs on the earlier single GO). (2) **SPLASH
`5804fd0` SHIPPED in v3.1.5** (squircle·ink surface, tagline removed, idle off,
FULL-first-install/QUICK-warm; pure physics engine `SplashMotion.kt` + Canvas `SplashContent.kt`
sized 42%-of-width; `ic_anon_mark` vectors → splash window icon + home header + adaptive launcher +
monochrome) — compiled CLEAN first shot, no import round. (2b) **YOUTUBE PLAYLISTS `f68c1f9` SHIPPED
in v3.1.5** (user: "playlist support for youtube should be better than what seal has" — it is:
watch-with-list links open the picker too, search filter + invert + range specs, already-on-device
badged and skipped by default, ≈size estimate, audio-only batch, ONE grouped show so Downloads
order + player Next walk the playlist like a series; pure zero-regex `pipeline/PlaylistPicker` +
18 JVM tests; see ledger PL-9). (3) synopsis DATA is already
parsed into `ShowDetails.synopsis` by every provider but MainViewModel/drawer drop it — wiring is
UI-only, zero network; placement pick still open (drawer header vs info sheet). (4) phone
protocol for v3.1.5 (v3.1.4 items included): bar may FREEZE under a 429 host but must self-resume, never FAILED-with-bytes;
ep1→Next=ep2, YouTube download carries embedded CC, MKV opens with subs auto-on, Rotate +
Mini-player (PiP) behave, drama sites get a verified slot on mixed-provider queries, genre tiles
show live posters, playbook sites' first-ever device test; first install after update plays the
FULL identity animation once, warm starts the QUICK cut, launcher shows the new mark. (5) playlist
protocol once the f68c1f9 build is on-device: paste/share a playlist URL → picker lists all entries
in seconds (ONE metadata call); a watch link carrying list= → same sheet with THAT video pre-ticked;
pick a subset → Downloads shows ONE group under the playlist title in playlist order; play one →
player Next walks the playlist; private/age-restricted → friendly error + Retry, no crash; RD
auto-mix → amber regenerate warning; finished entries → "✓ on device" + skipped by default.

---

## 🏛️ 1. PROJECT CONTEXT

Anon Downloader = 100% serverless on-device Android downloader. Multi-site search (nkiri, 9jarocks, naijavault, naijaprey, nepu, asianc, pluto, dramarain, dramakey, anitaku, torrents) → per-site episode drawers → 3-engine downloading:
- **TurboDownloader**: Kotlin OkHttp segmented range downloader (default engine for direct files)
- **aria2c**: via bundled yt-dlp `--downloader libaria2c.so`
- **yt-dlp**: social embeds, HLS `.m3u8`, generic extractor fallbacks
Magnets → aria2c with selective-file picker. No backend servers anywhere.
- **Instagram photo+music**: local REST media probe + bounded ffmpeg cover/audio mux (`engine/InstagramPhotoMuxer.kt`, surfaced via `SocialModal`).
- **One name standard**: every saved folder/file flows through `util/NameSanitizer.kt` (site-junk phrases stripped position-gated, HTML entities decoded, native script KEPT, Windows-reserved/traversal/dot guards, ≤80 chars / ≤240 UTF-8 bytes, `stripNoise=false` for user prose).
- **History mirroring** (`engine/HistoryBackup.kt`): `download_tasks.json` mirrored to public Documents.
- **In-app player**: fullscreen Compose Dialog over Media3/ExoPlayer (`ui/components/MediaPlayerModal.kt`) with subtitle picker, speed, framing cycle, resume positions (`PlaybackPositions.kt`).

### ⚖️ NON-NEGOTIABLE LAWS (user-mandated — violating these ends the session)
1. **Never add AI attribution to commits.**
2. **Never bulk-read `src/downloader.py` (~4200 lines)** or other huge files — grep/sed narrowly.
3. **Verify against the live world before coding.** Log-only guessing has been repeatedly wrong.
4. **Do not waste the user's mobile data.** Page fetches OK; video downloads NEVER without explicit permission. Test harness caps every download at 1KB (capped range probes only).
5. **GitHub builds, not local** — no local Gradle/Android toolchain exists. CI: `.github/workflows/build-apk.yml` builds + tests + emulator smoke on push, releases APK on `v*` tags. Always `gh run watch` after pushing.
6. **Ask before pushing** (user has revoked pushes mid-flight — but has authorized pushes when asked).
7. Things that worked yesterday must still work tomorrow — verify fixes against live sites before shipping.
8. **Subagents must NEVER push.** Push decision is main-agent-only, user-approved.

### 🧰 DIAGNOSTIC LIFEBLOOD
The activity log (`filesDir/logs/app-YYYY-MM-DD.txt`, shared via Settings → "Share Activity Log") records EVERYTHING: USER actions, NET requests, ENGINE transitions, RESOLVE attempts, BACKEND lifecycle, ERROR lines. The user shares these constantly — read them first, they are the primary diagnostic artifact. Categories: `[USER] [NET] [ENGINE] [RESOLVE] [BACKEND] [ERROR]` (crashes land here as `[ERROR] CRASH ...`) and `[TRACE]` (verbose mode only).

---

## 📌 2. RELEASE STATE

**CURRENT — 2026-09-15 night: v3.1.6 FULLY BUILT LOCAL. PUSH HELD FOR AN EXPLICIT USER GO** ("DONT BE IN A RUSH TO PUSH, WAIT FOR MY GO AHEAD SIGNAL TO PUSH" — this round, plan approval does NOT imply push). Published-latest is **v3.1.5** (2026-09-15T15:19:54Z, 5 APKs, round-2 green). The unpushed stack on top of `origin/master` (`93398b5` — whose v3.1.6 round-1 CI run is RED, sole failure = the chained-`to` merge test):

```
892e5af test fix — explicit listOf replaces the uncompilable chained-Pair test
5f9cb0c logo proportionality pass — home header mark 34→40dp, radius 11dp
3f0bc6d v3.1.6 BUILD — FeedCache (30-min-TTL disk memory, init hydration,
        atomic writes, corrupt self-heal); MIXED 3-wide genre grids (pure
        mixCards, no site names) + wrapping home tiles + View More catalog
        with lazy per-genre rows + single-flight queue (rows seen mid-crawl
        drain after, never stick as spinners); genre accuracy via the
        sites' OWN taxonomy (wp:term names / RSS <category> / title; genre
        paths dropped the ungated all-miss fallback; null confirmTerms keeps
        trending/search byte-identical; pre-commit-caught bug: parseRssItems
        harvested <category> then threw it away); nepu+asianc as API_JSON
        genre sources (live-probed terms fight/love/revenge; TMDB-CDN art;
        asianc OTA-heal); sort-aware player queue playQueueFor + openPlayer
        reads the Downloads sort pref from disk; ~1,500 lines, 4 test files
bf19b1c DESIGN PASS (ui-ux-pro-max audit): genre tiles' double-inset (32dp
        vs trending's 16) → one gutter; oracle badge clipped on 360dp →
        shrinkable+ellipsize; playlist-sheet quality chips crushed ~34dp →
        own full-width row; PLAYER CHIP SWAP per user spec — bottom row is
        now Speed → Landscape → Subtitles → Fit → Audio; ripples on ALL
        player controls (indication=null was zero feedback); every poster
        2:3 + shared bottom vignette; placeholders reserve the real card
        height (240dp — kills the ~50dp first-paint jump); 48dp hit areas +
        8dp interactive-chip gaps across 6 screens; bottom-nav hairline
        (surface==background in BOTH palettes had erased its edge); check
        glyph in the player's choice sheet; 9→11sp chips; ▾→ArrowDropDown;
        emoji out of labels/toasts; 12.5/13.5/10.5sp → integer ramp
c623e18 docs — HANDOVER current-state rewrite (§2 + item 11 protocol)
12da8c1 PLAYBOOK SIGNED v2026.09.12.3 — the committed .enc had been stale
        since 09-02 (terminal recipes for nkiri/dramakey/naijavault,
        sectionGrouping never reached a device). Signing "blocked" only
        because HANDOVER documented a pre-wipe key path; real keypair at
        ANON TOOLS/anon-serverless-app-maintenance-build; cryptography pip
        package installed; envelope re-verified (signature + schema) after
        writing. HANDOVER's keys section corrected.
9fc68db IG-1 carousel fix — music rides the POST, images live in
        carousel_media CHILDREN; cover falls back to the first PHOTO child.
        Headers now match gallery-dl's v1 set (X-ASBD-ID 129477;
        X-IG-WWW-Claim literal "0" logged out). Seal research: it has NO
        Instagram code (pure yt-dlp GUI; yt-dlp refuses photos by ruling).
1653e20 PL-6 synopsis — drawer-header STORY block, 3-line clamp + Read
        more, hidden when empty; zero new network (providers already parsed
        it, the UI dropped it).
```

Tag `v3.1.6` sits on the UNPUBLISHED red `93398b5` (NO GitHub Release exists for it — verified 09-15): on GO, push master, force-repoint the tag to the green tip (the retag-retired rule guards PUBLISHED tags only), then CI ≈17-18 min. **Budget one compile-fix round** — CI is the first compiler on ~1,700 fresh lines; the fresh-never-compiled suspects are `androidx.compose.material3.ripple()` (needs material3 ≥1.3.0 — BOM 2024.09.00 maps to 1.3.0, unverified), `minimumInteractiveComponentSize` in files newly using it, and the `items as gridItems` grid imports. All static gates (ktregexflags/bracecheck/balance repo-wide) are 0-problems locally.

**Deliberately SKIPPED this round (documented decisions, not misses):** the drawer's two Download buttons (09-14 documented split — range bar + sticky count, the audit's "fix" would churn a user-vetted decision); settings section order (preference-only block move); light-theme poster fallback palette (needs AnonColors variant — user runs dark); stagger animations (re-trigger risk against streaming partials; v3.1.7 candidate). **ENGINE AUDIT DROPPED by user order (09-15):** two background Explore agents (bug-hunt + improvement review of DownloadEngine) both delivered NOTHING — two provider "Invalid request body" deaths, two "completed" runs with 0-byte outputs and no extractable transcript text. User: "LEAVE THE ENGINE ISSUES, FOCUS AND FIX THE UI STUFF". If ever revisited: foreground agent, report must be written to a workspace FILE, never trust the notification envelope.

### Historical: v3.1.6's base arc — v3.1.4 RELEASED 09-14 07:04Z (engine round-3/4 + oracle + subs + PiP), v3.1.5 RELEASED 09-15 15:19Z (splash physics identity + YouTube playlist picker + videoIdOf host-gate); the older v3.1.1-era retag saga below is kept for the force-retag mechanics it documents.

**Latest release on GitHub (as of the v3.1.1 era — see CURRENT above):** `AnonDownloader-v3.1.1-{arm64-v8a,armeabi-v7a,universal,x8,x86_64}.apk` (tag `v3.1.1`, gradle versionCode 311, published 2026-09-13T18:09Z from `7f66249`) — the first release ever compiled + tested from the full arc. **SUPERSEDED IN PLACE 2026-09-13 21:18Z: the tag was force-moved to `437708b`** (crash hotfix + drawer sweep + player restructure; versionCode 312), old crashing assets deleted and re-published green — **STILL CRASHING per 22:39 device log**: that hotfix only cleared `(?i)`; the lookbehind purge + cause-chain logging shipped as 313/3.1.3 (`bfa5404`+`8f0704c`, local) — **but the 22:39 crash file proved neither (?i) nor the lookbehind was the poison: it was an UNESCAPED `}` in NameSanitizer's brace pattern (ICU vs JVM divergence). Fixed + RulesPipeline's four silent stray-brace sites fixed in `168f62d` (still 313/3.1.3 — NOW RELEASED: the tag was re-moved 23:42:25Z (run 34789223870, attempt 2 green — device-engine gate included); user installed it, the 09-14 device log shows crash-free working downloads).** **ROUND 3 LOCAL (314/3.1.4 — `e06884f` Turbo progress reports committed disk bytes, not received; `e06b6a3` the NEVER-CANCEL rule: banked-bytes tasks park with escalating requeue floors instead of failing on 429 storms and mid-stream hiccups, 429 no longer re-triggers fallback backends; `9dcfa86` version bump). Push+retag needs a NEW GO.** The old `v3.1.0` artifacts remain below it as pre-compiler-era history (that APK was the last one devices could have installed before today; its code had never passed a compiler since 63a7032 until CI did it on the arc).

### Historical: v3.0.3 contained the full 10-commit LockerRegistry-era stack (`4927e5d` → `e95d76b`):

```
e95d76b Fix PipelineTest: match >=3 consecutive-fails threshold (live-verified)
f44405f Fix LockerRegistry compile error: qualify all MediaKind references
9311851 Storage permission: prompt for All-files-access on Android 11+, clear engine guard
019dc0e NaijaVault: OTA selector union (kill sidebar junk) + resolution display for HLS
04f4204 Refresh HANDOVER.md for LockerRegistry work (4 local commits) + reverts map
24ce28d Fix naijaprey vdl chain (tokenless wildshare bait) + unseed moviereleases.net
c3893a4 Seed vdl.np-downloader.com + www.moviereleases.net in lockerHosts; conformance JSON search fixes
2ef8688 HostHealth learning + nav-junk refinement + conformance locker-discovery stage
4927e5d LockerRegistry: evidence-based locker discovery (extract everything, arbitrate by evidence)
(plus the pre-4927e5d commits 37694bb, 76b5444, 33b0c73 era — all pushed)
```

**CI findings during the v3.0.3 push (2 real bugs caught — both fixed):**
1. `LockerRegistry.kt` had NEVER been compiled (no local toolchain, first push since creation). All **unqualified** nested references (`return None`, `return Direct`, `return Locker(kh)`, `classify(u) != None`) failed with "Unresolved reference" while qualified ones (`is MediaKind.Direct`) compiled. **Fix (`f44405f`): qualify ALL MediaKind references.** Lesson: never assume a local commit compiles — the first push is the moment of truth.
2. `PipelineTest.hostHealth_backoffWindowAfterFailures_resetsOnSuccess` asserted the OLD threshold (2 fails → backoff). Commit `37694bb` changed the live-verified behavior to **>=3 consecutive fails**. **Fix (`e95d76b`): test updated to match the current logic.**

---

## 🏛️ 3. ARCHITECTURE (key files)

### Core pipeline
- **`pipeline/HostHealth.kt`** — Persistent per-host health ledger (JSON in filesDir). Exponential backoff (30s<<consec-1, **capped at 2 minutes** since the 2026-09-02 queue incident — the old 1h cap is gone). **>=3 consecutive failures** opens the backoff window (a single 404/timeout must NOT gate a host — nepu.gd lesson, live-verified). `recordFail` ignores user-initiated cancellations ("Canceled"/"CancellationException"/"abort" via `HttpClient.lastFailure`). `hasProvenLocker(host)` — any host with >=1 successful crack is a known locker. `isUsable(url)` also checks playbook `knownDead`.
- **`pipeline/StreamValidator.kt`** — 1KB Range probe with real download headers; rejects HTML/archive/exec via magic-byte sniffing; `sniff()` is JVM-testable. Throws `PipelineError.ValidationFailed`.
- **`pipeline/ResolveCache.kt`** — In-memory, TTL = `tokenTtlMinutes` from playbook; `keyFor(url, quality)`; engine invalidates before refresh so the 403 self-heal can't be served a stale URL.
- **`PipelineJournal` (object in `pipeline/PipelineError.kt`)** — Structured `[hop]` lines with ms + page hash; wired into ResolverRegistry and StreamValidator. (There is no PipelineJournal.kt file.)
- **`pipeline/PipelineError.kt`** — Sealed: `SiteDown/HostDead/RateLimited/BlockedIp/TokenExpired/ParseEmpty/ValidationFailed/BudgetExceeded` + `classify(host, lastFailure)`.

### Resolver layer
- **`resolvers/Resolvers.kt`** — 25 resolvers + `ResolverRegistry`. `resolve()` = cache+health wrapper around `resolveInternal` (recursion uncached). `resolveAny(urls, quality, max=3)` races locker candidates concurrently — first winner cancels losers; health-dead hosts filtered before launch. Recursive descent with depth limit 6; `resolveWithRetry` retries only network-class failures (3 attempts).
- **`resolvers/LockerRegistry.kt`** — Evidence-based locker discovery:
  - `classify(url)` → `MediaKind.Direct` (media ext) / `Locker(host)` (playbook-seeded + built-in + **learned via HostHealth.hasProvenLocker**) / `Unknown(host)` (never gated) / `None` (nav junk).
  - **Nav-junk filter**: `NAV_SEGMENTS` set (tag/category/dmca/menu/date/archive/author/cdn-cgi/email-protection/series-download/download-movies/...) + `startsWith("how-to")` + `endsWith("-menu")` + `contains("movies")`. Single-segment paths kept only with media markers (`-episode-`, `season`, `-movie-`, or `-drama` slug with >=2 dashes — `vincenzo-korean-drama` survives, `chinese-drama` doesn't). `/dl-` and deep `/download/` preserved above nav check.
  - `findLockerLinksInHtml(html)` — Jsoup-first (href/data-video/data-src), regex fallback.
  - `resolveCandidates(urls, quality)` — direct passthrough, known lockers race via `resolveAny`, **unknown hosts probed once via StreamValidator** (works on first contact).
  - `isKnownMedia(url)` — classify is Direct or Locker (excludes Unknown; used for curated episode lists).
  - ⚠️ **All MediaKind references must be QUALIFIED** (`MediaKind.None` etc.) — unqualified references fail to compile (CI caught this).
- **`data/rules/DynamicRulesManager.kt`** — OTA playbook: decrypts + **verifies ECDSA P-256 signature** BEFORE using the payload; parses domains/mirrors/sites/resolvers/hostPolicies/urlTemplates/knownDead/tokenTtlMinutes/searchStrategies/lockerHosts/directMediaExtensions/**pipelines** (A-5 declarative resolve/search step recipes — terminal-host allowlist gate, urlBinds pre-fetch, sectionGrouping season labels)/**dynamic_providers** (fully declarative sites via GenericDeclarativeProvider)/**seriesDescentSelectors**/**downloadAnchorSelector**, plus country/locker lists. `resolveReferer()` is the SINGLE referer source. `getSiteConfig(site).episodeSelector` exists (used by NaijaVault now). Encrypt-side shape checks live in `scripts/encrypt_rules.py` (incl. nested-quantifier ReDoS heuristic).

### Provider layer
- **`providers/*.kt`** — Per-site search/drawers/resolveEpisode.
- **NaijaVaultProvider** — Uses `LockerRegistry.classify` for download-link gate. **NEW: prefers the OTA `episodeSelector`** (union with `isKnownMedia` links) — kills ~23 sidebar/comment junk entries per show (live-verified: 1 real link vs 90 all-links). `resolveEpisode`: /dl- pages race ALL locker matches via `resolveAny`.
- **NaijaPreyProvider** — RSS search (regex `<link>` extraction). `resolveEpisode` uses `extractFileLink()` for vdl.np-downloader.com/sdm_downloads gateway → a.sdm_download → wildshare → **NEW: re-routes the tokenless wildshare bait through WildshareResolver** (pt= token → 302 → silversurfer CDN file with download_token). **Regex fix**: `(?![a-zA-Z0-9])` after extension + `&quot;`/`&amp;` entity stripping (webmanifest false-positive).
- **NkiriProvider, RocksProvider (9jarocks), DramaRainProvider** — NO classify usage. **Reverted to committed substring-based gates** after live probes proved classify regressions (DramaRain `/download?link=` single-segment episodes dropped; Nkiri/9jarocks nav-junk pollution). See "Reverts" below.
- **SearchStrategyRunner** — OTA searchStrategies chain (urlTemplate/rss/slugGuess). Nav-junk guard: single-segment paths dropped unless media markers.
- **`providers/ProviderRegistry.kt`** — Search fan-out (7s timeout, 4-min result cache, searchEnabled flag).

### Download engine
- **`engine/DownloadEngine.kt`** — Task state machine. `getDownloadDirectory` → `Download/Anon/<ShowTitle>/` (auto-organize flag; social saves go to `Social/<platform>`, torrents keep their picked names under the same root). **Every saved name — file stems and folders — goes through `util/NameSanitizer.savedName()`** (the one naming standard). **NEW: writability guard** — fails fast with an actionable message when storage access is missing. `preflightHls` — probe + rewrite master; **NEW: `pickHlsResolution()`** extracts RESOLUTION from EXT-X-STREAM-INF, picks the variant yt-dlp's height-limited selector lands on (highest <= requested; smallest if nothing fits) → task.resolution → UI chip. `runSizeEstimate` — segment-sampling estimator (real segment sizes, not BANDWIDTH tags). Watchdog (crawl floor 64KiB/60s, rate-drop detector, zombie cap, throttle stall detection). Completed-task purge drops `.work-` sidecar dirs too. HLS rewrite (`rewriteHlsMaster`, `resolveSegmentUrl`, `StaleStreamLinkException` → re-resolve on 401/403).
- **`data/models/Models.kt`** — `DownloadTask` has `quality` + **`resolution`** (NEW). `DownloadRecipe` (directUrl/filename/headers/backend/parallelSockets).

### UI
- **`MainActivity.kt`** — **NEW: storage permission flow.** Android 11+ (API 30+): rationale dialog at launch → `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` settings redirect (falls back to app details page if OEM dropped the action); re-checks `Environment.isExternalStorageManager()` on return. Android 9-10: WRITE_EXTERNAL_STORAGE runtime prompt. Also POST_NOTIFICATIONS on API 33+.
- **`ui/screens/DownloadsScreen.kt`** — DownloadCard: **NEW resolution chip** (completed) + resolution first in progress string (active) — e.g. `720p • 45% • 320 MB / 710 MB • 2.4 MB/s`.

### Keys & signing (⚠️ IMPORTANT — moved 2026-08-23)
- **The OTA signing keypair lives at `C:\Users\user\Anon\ANON TOOLS\anon-serverless-app-maintenance-build\`** — OUTSIDE both git repos (they are PUBLIC). `ota_signing_private_key.pem` (SECRET) + `ota_signing_public_key.pem` + `release-cert.pem` + `keystore-password.txt` + `README.md` explaining everything. (This doc previously said `C:\Users\Anon\anon-serverless-app-maintenance-build\` — a PRE-WIPE path that no longer exists; on 2026-09-15 that stale line caused a false "the key is lost" alarm. The wipe re-created the profile as `C:\Users\user`, and the folder was restored under `ANON TOOLS`. Sign with an explicit key argument to be immune to path drift:
  `python scripts/encrypt_rules.py --key "/c/Users/user/Anon/ANON TOOLS/anon-serverless-app-maintenance-build/ota_signing_private_key.pem"`.)
- Signing also needs the `cryptography` pip package on this machine (installed 2026-09-15; CI installs it itself for validation).
- **GitHub secrets** (serverless repo): `OTA_SIGNING_PRIVATE_KEY` (CI signing), `CONFORMANCE_PUB_B64` (conformance public key).
- `scripts/encrypt_rules.py` (canonical) + `download-toolkit/probe/encrypt_rules.py` (mirror) — KEY_PATH defaults to `~/anon-serverless-app-maintenance-build/ota_signing_private_key.pem`, overridable via `OTA_SIGNING_KEY_FILE` env var.
- **AES `RULES_KEY`** (16 bytes hex, in encrypt_rules.py) — obfuscation-grade encryption only; the key ships in the APK. The SIGNATURE (ECDSA P-256) is the real protection.
- **NEVER commit the private key. NEVER share it.** If it leaks: regenerate keypair (`--gen-keys`), update GitHub secret, ship new APK with new public key.

### OTA Playbook pipeline
- `scraper_rules.json` — plaintext rules (gitignored, NEVER committed).
- `scraper_rules.json.enc` — signed v2 envelope (committed): `{"v":2,"iv":...,"payload":<b64>,"sig":<ECDSA>}`.
- `.github/workflows/ota-rules.yml` — validates the committed .enc on push (signature + schema).
- `.github/workflows/conformance.yml` — MANUAL dispatch only; runs probe/conformance.py (live site traffic from GitHub IPs).

### Conformance
- **`probe/conformance.py`** — Signature-verifying runner (refuses unsigned payloads). 4 stages: search / episodes / direct-pass / **locker-discovery (NEW — reports UNKNOWN HOSTS FOUND with link counts)**. `--search-quality` mode. `classify_media()` Python mirror (20/20 parity verified). JSON searchType: walks link/url fields (accepts relative URLs). RSS: regex `<item><link>` (HTML parser treats `<link>` as void).

### 🧱 Data vs Code split (the honest "thin kernel" answer)

**Playbook-driven (OTA, no APK needed):** site base URLs + mirrors, search patterns/types + card/episode selectors, lockerHosts (unions with built-ins), hostPolicies (referers), urlTemplates, knownDead, tokenTtlMinutes, searchStrategies (urlTemplate/rss/slugGuess), directMediaExtensions, episodeSelector (NaijaVault reads it), pipelines / dynamic_providers (A-5 declarative recipes), **minAppVersion (ENFORCED fleet guard: a payload requiring a newer app is rejected wholesale and the previous rules stay — DynamicRulesManager rejects on `minApp > BuildConfig.VERSION_CODE`)**.

**Hardcoded in the APK (requires a release to change):** the 25 resolver implementations (JS unpackers, AES/wasm crypto, token chains — these are algorithms, not config), the resolver registry list + order, LockerRegistry DEFAULT_LOCKER_HOSTS/NAV_SEGMENTS fallbacks, engine constants (watchdog floors, socket counts, timeouts), provider extraction logic. Porting these to data is deliberately NOT planned — the schema can't express algorithms, and data-driven behavior would enlarge the attack surface if the signing key ever leaked.

**Design rule of thumb:** lists/selectors/strings that rotate → playbook. Logic/parsing/crypto → code. Recent history (~1 OTA fix per 8 Kotlin fixes) reflects that most real failures are algorithmic, not config — the "thin kernel" is an aspiration, the current split is the right line.

---

## ⚠️ 4. REVERTS MAP (do not re-introduce)

The following providers were reverted to committed code after live probes proved classify regressions:
- **DramaRainProvider** — episode links are `/download?link=...` (single-segment path → classify returned None → ALL episodes dropped). Reverted to old category/tag gate.
- **NkiriProvider** — classify gate added 44+ nav-junk entries (`/tag/`, `/dmca/`, `/korean-drama-menu/`) to the episode list. Old substring gate was precise (20 real MKV links).
- **RocksProvider (9jarocks)** — 62 `/date/` archive links added. Reverted.

The classify gate belongs ONLY where the design intends: NaijaVault's download-link filter, `findLockerLinksInHtml`, `resolveCandidates`. Episode-list construction on show pages uses per-provider gates or OTA episodeSelectors.

---

## 📋 5. KNOWN-BROKEN / OPEN (do not re-diagnose from scratch)

1. **vidsrc HLS deep-dive** — nepu TV HLS historically stalled at 0 bytes. Root causes found & fixed (referer suppression for rewritten masters, stale persisted tokens → StaleStreamLinkException re-resolves, variant root-absolute paths). Confirm with new user log.
2. **asianc segment CDN `cdn.jisooido.top` domain-locks** (403 for every referer) — site-side; some episodes undownloadable. premilkyway masters expire within minutes.
3. **naijavault content decay** — most posts expose no links anymore; filevault.com.ng down (Cloudflare 526). Site-side.
4. **nkiri search (thenkiri.com) connect-timeouts** from the user's network — site-side.
5. **dramarain `?s=` search broken server-side** — slug guessing works (OTA searchStrategies).
6. **9jarocks HTTP 522** — transient Cloudflare, not app issue.
7. **seriezloaded.com.ng** — dead domain (DNS NXDOMAIN). Not seeded. Ignore.
8. **USER ACTION PENDING (still): token rotation** — the old GitHub PAT pasted into chat. Rotate in GitHub Settings > Developer settings > Personal access tokens.
9. **USER ACTION PENDING: device testing.** THE RAN-AND-PASSED PROTOCOL IS BELOW ITEM 11 — the v3.1.4 list that used to live here RAN on 2026-09-15 and produced exactly three complaints (splash edge-to-edge, late content, slow 9jarocks art), all root-caused and fixed in the v3.1.6 stack above. Its still-UNVERIFIED-BY-USER carryovers: throttle freeze-resume never FAILED-with-bytes (round 4), progress truth at 100% tail (the Suits case), YouTube embedded CC, MKV auto-subs, Rotate/PiP chips, the verified-slots ordering, OTA playbook sites first-ever run, and the full v3.1.5 protocol (splash FULL-once/QUICK-warm + new launcher icon + playlist picker end-to-end). Old item body kept for history: ~~(314/3.1.4 — COMMITTED LOCAL; push+retag needs a NEW GO~~) — THAT GO RAN (v3.1.4 released 09-14, v3.1.5 on 09-15); install the arm64 APK and verify: **downloads still work** (313-confirmed; re-check on 314), **PLAYER TRULY FULLSCREEN** — open a video: black edge-to-edge INCLUDING under where the bars were, bottom nav NOT visible around it, tap toggles controls, rotate to fullscreen and back (six-complaint bug, restructured `b5cdf99`); FILM DRAWERS — a single movie on 2 servers lists `Server 1`/`Server 2` (never Episode 1/2): retest 9jarocks Safety; Project Sacrifice on naijavault should enqueue its locker link and download; gallery visibility: download a TikTok/Facebook video — it must appear WITHOUT sharing out (`32d518a`, finally reachable); storage prompt, saved-name shapes (junk stripped, native script kept), combined 9jaRocks post (drawer crash fix), resume positions, subtitle track. The IG-vs-TikTok gallery asymmetry from v3.1.0 stays open — if the symptom survives the next build, send the activity log (`completed task=... file=...` comparison is the evidence path). NEW for the 313 build: any future crash entry now leads with `CAUSE 1: <exact exception>: <message>` — if downloads STILL die after 313, the log will finally name the precise rejected construct and index instead of a truncated wrapper error. UPDATE: the phone DID produce that evidence (anon_crash.txt) and the poison is REMOVED in 168f62d — 313 is now expected GOOD, not experimental. Also newly testable on this build: OTA playbook sites (search + drawer + enqueue) work on-device for the FIRST TIME (RulesPipeline fixes); watch for literal tokens like {1}/{season} leaking into episode titles — that would mean a missed stray-brace site. ROUND 3/4 VERIFICATION (after the 314 push — the anon_crash.txt retrieval from this item's old tail is DONE: it named the brace pattern, fixed in `168f62d`): (1) **RATE-LIMIT SURVIVAL (round 4 — the real answer to "it should just work")** — start a big download from a throttling host (the 09-14 vikingfile 429 storm is the reference case); when the CDN pushes back the card should KEEP SAYING Downloading with a frozen bar for tens of seconds to minutes and then RESUME on its own in the same session — no FAILED card, no re-tapping, no yt-dlp/aria2c lines in the log for the direct link; expect `[BACKEND] ... rate limited (cycle N): all sockets cooling Xs, workers→Y` lines and a visible speed collapse→recovery pattern. Only a host that blackholes for 15+ min falls back to the parking path (`Download server cooling down — ... MB already saved, it will resume`, PAUSED + auto-resume). A FAILED card with bytes banked is still a violation of the rule at every stage. (2) **PROGRESS TRUTH** — the Turbo bar must track bytes actually written: no pinning at 100% while the tail trickles (the Suits case); the speed readout stays the real transfer rate. (3) The Instagram photo-carousel "No video formats found" path still lands in the photo-muxer fallback — a FAILED *after* the muxer try is honest, but watch it.
10. **Known minor**: naijaprey show pages emit a `.srt` subtitle link that appears as a dead episode entry (pre-existing, harmless — fails cleanly).

11. **DEVICE PROTOCOL FOR THE v3.1.6 STACK (run after installing the v3.1.6 APK):**
   - **Instant home**: kill the app completely, reopen → trending row and genre tiles render IMMEDIATELY from disk (no spinner, no blank), then update silently if the cached groups are >30 min old. A second launch inside 30 min should touch NO network for those groups.
   - **Mixed genre grid**: tap any genre → ONE 3-wide grid (Action/Comedy/Horror/Romance/Sci-Fi/Thriller), scroll DOWN only, zero site names on the page (provenance appears only in the episode drawer after a tap). Top-bar refresh icon re-crawls in place.
   - **View More catalog**: the 7th tile on Home opens the per-genre catalog — each horizontal row is a genre, rows fill as they scroll into view (never all six at once). A row glimpsed while another genre is still loading must still fill in (the single-flight pending queue) — a permanent spinner there is a bug.
   - **Genre accuracy**: Sci-Fi must NOT contain posts that merely MENTION sci-fi in their bodies (the animated-series-inside-Sci-Fi complaint); a post carrying the site's own sci-fi category/tag is correct even for anime. nkiri/naijavault/9jarocks/naijaprey rows still populate; nepu leads the movie genres (TMDB images), asianc leads Romance.
   - **Sort-aware Next**: Downloads sorted by DATE → opening any finished file, Next walks across shows AND days (newest→oldest); by SIZE → largest first; by STATUS → on-screen order; **By show (Library) is the ONLY mode that stops at the series boundary** (finish ep2 → Next = ep3 of the same series, never another show).
   - **Player layout**: bottom chip row reads **Speed → Landscape/Portrait → Subtitles → Fit/Crop/Stretch → Audio** (the 09-15 swap); every control ripples on tap (close, PiP, ±10s, skips, chips, sheet rows); the audio picker's selected row shows a check mark.
   - **Home alignment/polish**: the genre tiles' LEFT edge lines up with the "Trending Now" header (16dp gutter); long verified captions on search result cards ellipsize with "…" instead of clipping; the bottom nav bar has a visible hairline above it.
   - **Playlists (v3.1.5 carryover, still unverified)**: paste a YouTube playlist/watch-with-list URL → picker opens fast, watch link pre-ticks its own video, subset enqueues as ONE grouped show, player Next walks the playlist, already-on-device entries show "✓ on device" and skip.
   - **Synopsis (PL-6, new)**: open any show's drawer → a STORY block sits under the title, collapsed to three lines with Read more/Show less where the blurb is long; no story block at all when a provider returns none.
   - **Instagram photos (IG-1, new)**: share an IG photo post or a photo CAROUSEL with music → it downloads as an mp4 (cover + the licensed audio). A photo post with no music still fails cleanly with yt-dlp's message — that is honest, not a bug.
   - **OTA rules (new)**: the playbook envelope is now signed v2026.09.12.3 (it had been stale since 09-02). After the update installs, nkiri/dramakey/naijavault drawer behaviour rides the newer recipes for the first time — watch for literal `{1}`/`{season}` tokens in episode titles (a missed stray-brace site) and for sectionGrouped season accordions on dramakey.
   - If anything misbehaves: Settings → Share Activity Log is the evidence channel; the crash file leads with `CAUSE 1:`.

---

## 📋 6. INCOMING AI QUICK-START CHECKLIST

1. `git status` — **origin/master is `93398b5` and its CI run is RED** (v3.1.6 round 1: the sole failure was the chained-`to` merge test, fixed by local `892e5af`). The local stack (892e5af → bf19b1c, see §2) is the fixed + full v3.1.6 round and is **unpushed pending an explicit user GO**. After any push, verify CI: `gh run list --limit 3` (a green build ≈17-18 min; do NOT chain `echo` onto an exit-checked `gh run watch` — the echo masks the real exit code; trust `gh run list`). Note: `gh` exists at `C:\Users\user\bin\gh.exe` (v2.100.0) — no login state; authenticate per-invocation by exporting `GH_TOKEN` from `git credential fill` (github.com) without echoing it.
2. Read `.agents/AGENTS.md` first (operating charter). Then this HANDOVER.
3. **Unbiased review kit exists at `REVIEW_PROMPTS.md`** (root) — 6 focused prompts + a catch-all prompt the user can paste into any AI reviewer. Covers architecture, security, code quality, performance, UX, maintainability.
4. Ask the user for the newest activity log if anything's broken — read `[ERROR]/[RESOLVE]/[ENGINE]` lines first.
5. For any chain failure: probe the live site BEFORE coding (curl_cffi, 1KB Range probes only, ~0.4s pacing, page cap 300KB). Compare against the monolith resolver. The app's resolved URLs must pass StreamValidator semantics (no HTML decoys, no hotlink bait).
6. If debugging a failed download, trace the chain. naijaprey: show → vdl.sdm_downloads → a.sdm_download → wildshare → pt= token (session-bound cookies!) → 302 → silversurfer CDN. Test with a session-based Python script (cookies load-bearing).
7. Never regress these user-facing guarantees: reopen never auto-resumes; pause actually pauses; failures notify with Retry; searches cancel their predecessors; nothing silently consumes data; storage permission is prompted at launch with a settings redirect.
8. **CI lessons:** unqualified nested references in LockerRegistry fail to compile (qualify everything). HostHealth backoff threshold is >=3 consecutive fails (tests must match).
9. Signing pipeline: `python scripts/encrypt_rules.py` finds the key automatically in the maintenance folder. If the private key leaks, rotate the whole keypair + GitHub secret + app-embedded public key.
10. **Never push without asking.** Tag pushes create releases (dynamic tag_name). Watch `gh run watch` after any push.
