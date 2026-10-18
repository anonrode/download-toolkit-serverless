# UI OVERHAUL DIRECTIVE — VETTED (2 independent verification passes, 2026-10-18)

**Status: vetted against the live code. Implement WITH the corrections below — 2 items in the
original directive are wrong as stated, 1 is already fixed, and several line references shifted.**
Every claim below was verified by two independent read-only passes (file:line receipts available
in the session log). No code has been changed yet. Local gradle is FORBIDDEN (CI-only compile);
after any edit run the local static gate: `python scripts/verify_kotlin_syntax.py`.

---

## A. Verdict summary (original directive claim → reality)

| # | Original claim | Verdict |
|---|---|---|
| 1 | `DarkAnonColors` navy-slate `0xFF090B10`/`0xFF141822`/`0xFF1A1F2C` vs OLED Material scheme `#000000`/`#0B0B0E` | **CONFIRMED** (Theme.kt:25-40; scheme :125-159) |
| 2 | Splash constants `:116-119` unused | **CONFIRMED — zero refs repo-wide**; safe to delete |
| 3 | Explorer `genres` dead val `:86-88` | **CONFIRMED** (declared :86, never read) |
| 4 | `DramaCardItem` :403-480 uses `SubcomposeAsyncImage` + `InitialGlyph` | **CONFIRMED** (`InitialGlyph` :373-385) |
| 5 | `TILE_COLORS_*` duplicated | **CONFIRMED** — actual range :387-394, duplicated in HomeScreen.kt:1777-1791 |
| 6 | `genreTiles` never rendered | **CONFIRMED** — `CategoryTilesGrid` is :987-1030 (not :987-1217); static `GENRE_POSTER_PREVIEWS` (:952-981) is rendered at :1109 instead |
| 7 | `AsianDramaHeroSection` :1826-2017 backdrops+gradients | **CONFIRMED** (TMDB backdrops :1874/:1955; gradients :1884-1885/:1965-1966) |
| 8 | EpisodeDrawer HALF-mode geometry: sheet full-H offset down, list bottom unreachable, CTA above list | **CONFIRMED** — geometry math is :394-395 (`halfTopPx = Hpx - Hpx*0.60f`), sheet Box :627-640, CTA Row :1028-1102, LazyColumn :1104-1212 |
| 9 | `fullTopPx = 0f` pushes handle/X under status bar | **WRONG as stated** — no `fullTopPx` identifier exists; the header ALREADY applies a status-bar inset (`artTop = if (isFull) statusBarDp else gripZoneH` :577) and the grip fades to 0 at FULL (:696). **Do NOT add a status-bar offset.** |
| 10 | `:371-380` forces light status-bar icons | **CONFIRMED** — `isAppearanceLightStatusBars = false` unconditional (:378) |
| 11 | Art band navy `#3A4254→#1B2029` / `0xEB0B0E14` | **CONFIRMED** (:656, :683) — but band block is :641-687 |
| 12 | "`SheetPalette` uses `#0B0E14` at :720-755" | **WRONG** — `DarkSheetPalette` is :1835-1900 and its surface is ALREADY `0xFF0B0B0E` (:1857). :720-755 is the poster card + glyph. Ranges were swapped in the directive. |
| 13 | Header poster fallback = 2-letter glyph | **CONFIRMED** — glyph at :746-754; `PosterImageOrFallback` exists (MediaFallback.kt:99, already used DownloadsScreen:1310 + HomeScreen) but not used here |
| 14 | `downloadedOrQueuedUrls` labels FAILED/QUEUED as "✓ Downloaded" | **CONFIRMED** — set built :222-228 (title match only, no TaskStatus filter); pill :1352; passed :1160-1208. **Same bug also corrupts `remainingEpisodes` (:229-231), "Remaining (N)" (:916-933) and the one-shot auto-select (:237-241)** |
| 15 | 34dp single-download circle in collapsed row | **CONFIRMED exactly** — i-button :1390-1406 (24dp), download :1408-1428 (34dp), checkbox :1430-1453 (22dp) |
| 16 | Checkbox contentDescription doesn't announce state | **CONFIRMED with nuance** — :1448 is `if (isSelected) "Selected" else "Not selected"`, but the Icon (and thus semantics) exists only when selected; the outer Box :1431-1444 has no role/stateDescription |
| 17 | DownloadsScreen: PAUSED card whole-click inert | **CONFIRMED** — `enabled = isDone \|\| isFailed` (:1287-1295). Note: verify the resume handler actually resumes PAUSED when reusing `onRetry()` |
| 18 | DownloadsScreen: shelf vanishes under Done when no groups | **ALREADY FIXED — DO NOT "FIX"** — the guard is already `filteredGroups.isEmpty() && visibleShelf.isEmpty()` (:570); shelf renders :664-692. Verify only. |
| 19 | DownloadsScreen: FILTER_ACTIVE count mismatches predicate | **WRONG as stated** — chip count (:341-344) and `matchesStatusFilter` (:166-167) already agree (both include QUEUED). The REAL drift: `stats.active` intentionally excludes QUEUED yet feeds the header meta (:396) and the nav badge (MainScaffold :313) — align or relabel by decision |
| 20 | Test-contract symbols at stated ranges | **CONFIRMED** — `DownloadCard` :2116-2552, `StatusBadge` :2554-2574, `BulkActionChip` :2080-2114 (private), `cardStatusDetail` :1680-1723, `statusDetailText` :1729-1777, `downloadStatusStyle` 1-arg :1623-1640 + chroma overload :1646-1660, `groupCountColor` :984-989 + overload :994-999. Tests pin: `DownloadCard` rendered tree + `downloadStatusStyle(TaskStatus)` 1-arg mapping + `groupCountColor(String)` hexes + `statusDetailText` output strings |
| 21 | SocialModal 56dp 2-letter square | **CONFIRMED** (:147, :154) |
| 22 | QualityChipRow AccentViolet vs SegmentBar AccentPrimary | **CONFIRMED** (QualityChipRow :56-64; SegmentBar :69/:82/:91) |
| 23 | SettingsSheet snapshot staleness (instant-social) | **CONFIRMED with full mechanism** — `remember(viewModel)` captures once (:213, engine fields :85-112); ON_RESUME refreshes STORAGE only (:288). QuickShareActivity dual-writes the pref directly (:189-190 ON, :197-198 OFF) + pokes the engine field; SettingsSheet's 500ms-debounced full snapshot (:1093 → MainViewModel :1213-1284 → DownloadEngine :343, :392) overwrites it |
| 24 | `hlsFragmentConcurrency` default 8 vs 16 | **CONFIRMED** — constructor default 8 (AppSettings :20) vs `load()` 16 (:57) vs engine 16 (DownloadEngine :83, :280), MainViewModel :1225 |

## B. Corrections to apply while implementing

1. **§3.2 (status bar):** drop the `fullTopPx` change (does not exist; would double-inset). The real
   fix in that area is item 10 only: `isAppearanceLightStatusBars = !pal.isDark` (:378).
2. **§3.3 (palette):** `DarkSheetPalette` background is already `0xFF0B0B0E` (:1857) — verify each
   existing value at :1835-1900 against the target before editing; only change what is actually
   navy. The REAL navy items are the art-band hexes (:656, :683) and the header title hardcode
   (:797-805 area) plus the glyph fallback (:746-754).
3. **§3.4 (badges):** extend the fix to `remainingEpisodes` (:229-231) + "Remaining (N)" chip
   (:916-933) + auto-select (:237-241): FAILED/QUEUED/PAUSED must NOT be treated as downloaded;
   FAILED must land in Remaining so it can be re-downloaded.
4. **§4.2:** skip (already fixed). **§4.3:** replace the chip-count work with the `stats.active`
   consistency decision (header :396 + MainScaffold :313 vs chip :341-344) and optionally the
   label drift "Done" (:830) vs "No completed downloads" (:586).
5. **Extra findings worth folding into the same pass** (verified, outside the original directive):
   - Theme: `borderHairline` alpha mismatch (0.08 in `DarkAnonColors` :30 vs `0x12FFFFFF` in the
     Material scheme :136) — the directive's `0x12FFFFFF` target is the right one.
   - Navy literals bypassing tokens: HomeScreen :427, :1042, :1117, :1861, :1942; DownloadsScreen
     :1488.
   - HomeScreen comment says genre tiles "3 per row" (:548-553) but code renders `chunked(2)` (:1003).
   - Explorer: `activeGenre`/`onSelectGenre` are a half-built API reachable only via "Reset
     filters" (:312-317) — keep age-safe or clean deliberately; `horizontalScroll` import (:10) unused.
   - DownloadsScreen legacy `DownloadCard` whole-card click only when completed (:2144);
     `seriesRemaining` counts ALL statuses (:358-365) so the "Download Remaining" chip lies after
     failures; shelf tiles use raw `pointerInput` with no semantics (:1854-1862) — TalkBack cannot
     activate Play.
   - EpisodeDrawer row click (:1316) and checkbox (:1442) both call `onToggle` (duplicate hit
     targets); the 24dp "i" button (:1390-1406) exposes only the glyph "i" as its label.
6. **§6 checklist additions:** replace the DownloadsScreen shelf item with "verified already
   guarded (no change)"; replace the status-bar item with the `isAppearanceLightStatusBars`
   change only; add "`remainingEpisodes` uses completed-only"; add "syntax gate run:
   `python scripts/verify_kotlin_syntax.py`" as the local pre-handoff check.

## C. Unchanged guardrails from the original directive

- **NO local gradle/builds, ever** (CI-only compile). Static checks only.
- **Test contracts are sacred:** do not alter the ranges/behaviors listed in verdict #20 (the two
  test files are the contract; `BulkActionChip` is private and untestable from outside the file).
- **No new `Regex(...)` instances** that would break `RegexEngineCompatTest` — reuse
  `TitleFormatting.kt` helpers.
- **K-Drama/C-Drama: Era + Status filters ONLY.** No genre chip row. Keep hero backdrops/gradients.
- Preserve: `AsianDramaHeroSection` backdrops, and do not touch EpisodeDrawer's existing
  `weight(1f)` list + `height(Hdp - listTop)` structure (:837-841, :1108) without re-deriving the
  geometry — the fix is to bind the inner height to `(Hpx - anim.value)` as specified, which makes
  the existing weight(1f) list reach the true visible bottom.

*Two additional investigator passes (app-wide wiring + subsystems) are still running; if they
surface anything that interacts with these files, an addendum will follow. Nothing above depends
on them.*
