# Operating Charter & Monolith Alignment Rules — download-toolkit-serverless

## 0. The Prime Directives

1. **ALWAYS LEARN FROM THE MONOLITH (`../download-toolkit/src/`) FIRST**:
   - The Python monolith is the authoritative, battle-tested source of truth.
   - Before writing or editing any provider, resolver, or download router in Kotlin, **always read the corresponding Python extractor (`src/extractors/*.py`), resolver (`src/resolvers.py`), and downloader backend (`src/downloader.py`) first.**
   - Never guess CSS selectors, API endpoints, regexes, or routing flags when the working implementation is right there in `src/`.

2. **Download Routing Rules**:
   - `magnet:?` URIs MUST NEVER pass into `yt-dlp` (`YoutubeDLRequest`). `yt-dlp` runs in Python `urllib` and crashes with `Unsupported url scheme: "magnet"`.
   - Magnets MUST execute directly via the native `libaria2c.so` binary with `--enable-dht=true`, `--bt-tracker=...`, and `--summary-interval=1`.
   - `.m3u8` streams and social video URLs route to `yt-dlp`.
   - Direct HTTP/HTTPS lockers route to `aria2c` with `-x 16 -s 16` and explicit `--header="Referer: <url>"`.

3. **HTML Parsing & Episode Extraction Invariants**:
   - ALL Jsoup parsing MUST pass the page URL as `baseUri` (`Jsoup.parse(html, pageUrl)`); otherwise relative links resolve to empty strings `""` and discard all episodes.
   - Do NOT constrain scrapers to narrow CSS containers (e.g. `.entry-content`). Search the full document for canonical locker domains (`downloadwella.com`, `loadedfiles.net`, `wetafiles.com`, `vikingfile.com`, `lulacloud.com`, `nkiserv.com`).

4. **Seal-Style Transparent Quick Share Floating Dialog (`QuickShareActivity`)**:
   - External `ACTION_SEND` intents from Instagram, TikTok, YouTube, and Twitter MUST NOT launch the full application window or disrupt the user's workflow.
   - Route `ACTION_SEND` to `QuickShareActivity` styled with `Theme.AnonDownloader.Dialog`.
   - If Instant Download is enabled (`pref_instant_social == true`), enqueue in the background, post a brief Toast, and `finish()` immediately.
   - If Instant Download is disabled, show the floating `QuickShareCard` with quality chips, format toggles (MP4/MP3), and an "Always Instant" option.

5. **Download Engine Resolver Routing Gate (`DownloadEngine.kt`)**:
   - Never gate `ResolverRegistry.resolve` on `!streamUrl.startsWith("http")`. Web locker pages (`downloadwella.com`, `loadedfiles.net`, `dood.to`, `streamwish.com`) start with `http` and MUST be cracked to direct media/manifest URLs before handing off to aria2c.

6. **Canonical Domain Migrations & Fallbacks**:
   - Always keep canonical domains synchronized with the monolith: `9jarocks` -> `my9jarocks.bz`, `anitaku` -> `anitaku.com.ro`, `naijaprey` -> `naijaprey.com`.

7. **Coroutine Threading & OkHttp Execution Invariants**:
   - `viewModelScope.launch { }` runs on `Dispatchers.Main` by default.
   - Synchronous network calls (such as `HttpClient.getText()` / OkHttp `.execute()`) MUST ALWAYS be executed on `Dispatchers.IO` (using `withContext(Dispatchers.IO)` at both caller and registry levels).
   - Never execute blocking HTTP calls on `Dispatchers.Main` where Android throws `NetworkOnMainThreadException` which blanket catch blocks silently swallow into empty episode lists.

8. **Never add AI attribution to commits** (no `Co-Authored-By`, no "Generated with"). Commit messages describe the change only.
9. **Ask before pushing** and before large refactors.

