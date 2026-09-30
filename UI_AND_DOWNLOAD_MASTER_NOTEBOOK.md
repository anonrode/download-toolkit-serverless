# Anon Downloader: Master UI & Download Integrity Notebook
**Document Version:** 1.0.0  
**Repository:** `download-toolkit-serverless`  
**Purpose:** Permanent source of truth for all design standards, user feedback, audio/visual audit findings, and download engine reliability specifications. Never lose track of any user requirement or regression.

---

## 1. Core Operating Principles

1. **Zero AI Attribution:** Never add AI attribution (`Co-Authored-By`, "Generated with", etc.) to commits, code, documentation, or release tags. Commit messages describe technical changes only.
2. **Keyboard Resilience:** User has broken 'g' and 'h' keys (e.g., "te" = "the", "mit" = "might", "tink" = "think", "enire" = "ensure"). Infer intended meaning without asking for clarification.
3. **Respect Canonical Design Language:** Do NOT invent foreign design patterns, garish pastel color schemes, or awkward oversized buttons. Every new feature, card, and button must strictly mimic the app's native dark/light monochrome surface architecture.
4. **Zero Download Failures Doctrine:** Downloads that worked previously must never be allowed to fail. Every locker, gateway, and stream URL must be verified against live endpoints. Subprocesses must receive necessary hotlink headers. Direct media files must never be rejected by locker classifier checks.

---

## 2. Complete Inventory of User Complaints & Visual Frame Evidence

### Audio & Screen Recording Breakdown (`screen-20260929-231817.mp4`)

| # | User Feedback / Complaint | Timestamp & Frame | Root Cause in Code | Resolution Implemented |
|---|---|---|---|---|
| **1** | **Compress Sub-genres into All**<br>"Do NOT split shows into Palace Romance, Wuxia, Xianxia, Rom-Com... it fragments results and leaves screens with only 2 shows! Collapse everything under Modern and Historical." | 01:12<br>`frame_038.jpg` | `AsianDramaExplorer.kt` lines 229–262 had a horizontal scrolling row of sub-genres that filtered the catalog down to near-empty subsets. | Completely removed the sub-genre row. Preserved the top-level **Modern** vs **Historical** selector and the secondary status row (**All**, **Completed**, **Ongoing**). |
| **2** | **Remove Site Name Badges on Cards**<br>"Remove those site pills, they look ugly! Only keep the AIRING / END badge at the top right!" | 01:45<br>`frame_038.jpg` | `AsianDramaExplorer.kt` lines 442–458 had a `Box` in `DramaCardItem` overlaying `card.site.uppercase()` (`9JAROCKS`, `ASIANC`, `DRAMARAIN`) on the bottom-left of every poster. | Stripped the bottom-left site badge pill entirely. Preserved the top-right `AIRING` (cyan) and `END` (emerald) status badges. |
| **3** | **Redesign Asian Drama Hero Cards on Home**<br>"The two hero cards look out of place like foreign colored rectangles... and remove that literal `\n` in 'Explore catalog \n'." | 00:22<br>`frame_010.jpg`<br>`frame_015.jpg` | `HomeScreen.kt` lines 1654–1756 used hardcoded indigo (`#1E1B4B`) and crimson (`#3B0714`) gradients, along with a unicode arrow string `"Explore catalog →\n"` causing rendering artifacts. | Redesigned both cards with native `SurfaceCard`, `BorderHairline`, `SurfaceElevated` hub pills, clean typography, and a native Compose `Icons.Rounded.ArrowForward` icon. |
| **4** | **Align "View More" Beside "Anime"**<br>"Anime is sitting alone on Row 3 with columns 2 and 3 blank, and View More drops to Row 4 alone! View More must sit right beside Anime on Row 3." | 00:48<br>`frame_017.jpg` | `CategoryTilesGrid` in `HomeScreen.kt` chunked the 7 categories by 3 (leaving Anime alone on Row 3), then rendered `ViewMoreTileCard` in a separate row (Row 4). | Combined categories and View More into an 8-item unified grid chunked by 3, placing **Anime** and **View More** side-by-side on Row 3. |
| **5** | **Fix Light Mode Inverted Text Blur**<br>"Look at that, it's literally blurring out, both are black! In light mode it's unreadable!" | 02:15<br>`frame_038.jpg`<br>`frame_024.jpg` | `AsianDramaExplorer.kt` line 180 hardcoded selected pill text to `Color.Black`. In Light Mode, `AccentPrimary` is dark (`#07131A`), causing black text on a black pill. | Changed text color to `AnonTheme.colors.background` (which dynamically resolves to porcelain `#F4F7FA` in Light Mode and `#000000` in Dark Mode), ensuring maximum contrast. |
| **6** | **Redesign Downloads Sort Bar & Action Buttons**<br>"The clunky segmented bar and the pastel-colored buttons look out of place and don't conform to the monochrome app design." | 02:50<br>`frame_032.jpg`<br>`frame_028.jpg` | `DownloadsScreen.kt` had a bulky 38dp segmented bar and `BulkActionChip` used `accent.copy(alpha = 0.12f)` fills producing pastel pink/peach/teal bubbles (`#FFEBEE`, `#E8F5E9`, `#FFF3E0`). | Compacted sort bar to sleek 32dp `SurfaceCard` with `BorderHairline`. Redesigned action chips to minimalist `SurfaceElevated` with hairline borders, monochrome labels, and subtle icon accents. |
| **7** | **Catalog Under-population**<br>"K-Drama was only showing 7 shows from 9jaRocks instead of dozens." | 01:05<br>`frame_038.jpg` | `AsianDramaFeed.kt` failed to parse DramaKey cards (anchor was parent of `<article>`) and AsianC `/country/korean-drama` returned a text table matching 0 cards. | Fixed DramaKey selector to `a.series-card-link`, switched AsianC to live keyword feeds with `abs:data-original` poster extraction, and expanded concurrency across 6 providers. |

---

## 3. Canonical UI Design System & Placement Rules

### Core Surfaces & Palette Tokens

| Token Name | Dark Mode Value | Light Mode Value | Architectural Usage |
|---|---|---|---|
| `background` | `#000000` (Pure Black) | `#F4F7FA` (Clean Porcelain) | Screen root surface; selected text color inside `AccentPrimary` pills. |
| `surfaceCard` | `#101216` (Deep Charcoal) | `#FFFFFF` (Pure White) | Primary cards, content containers, sort bars. |
| `surfaceElevated` | `#181B22` (Elevated Charcoal)| `#E9EFF5` (Soft Platinum) | Action chips, secondary pills, icon button backdrops. |
| `borderHairline` | `#1F232D` (Subtle Edge) | `#D8E0E8` (Crisp Border) | All 1.dp structural card, chip, and divider outlines. |
| `accentPrimary` | `#FFFFFF` (Pure White) | `#07131A` (Deep Ink) | Selected states, primary focus rings, active indicator marks. |
| `textPrimary` | `#FFFFFF` (High Contrast) | `#07131A` (Deep Ink) | Screen titles, card titles, button labels. |
| `textSecondary` | `#A8B8CE` (Muted Slate) | `#475569` (Slate Grey) | Secondary subtitles, metadata, file sizes, inactive tabs. |
| `textMuted` | `#94A3B8` (Ghost Grey) | `#55627A` (Dull Slate) | Micro-captions, timestamps, disabled hints. |

### Component Placement & Geometry Specifications

1. **Category & Genre Grid (`CategoryTilesGrid`):**
   - **Columns:** 3 equal-width columns (`weight(1f)` per column, `Arrangement.spacedBy(Spacing.sm)`).
   - **Aspect Ratio:** 0.68f poster proportion with `RoundedCornerShape(Radius.sm)`.
   - **Overlay Scrim:** `Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))` from 60% height down.
   - **View More Tile:** Always placed directly adjacent to the final category item (**Anime**) on Row 3, never orphaned on Row 4.
2. **Sort & Segmented Bars:**
   - **Height:** Exactly 32.dp.
   - **Container:** `SurfaceCard` with `1.dp, BorderHairline, RoundedCornerShape(Radius.md)`, internal padding `3.dp`.
   - **Selected Pill:** `AccentPrimary` background, `AnonTheme.colors.background` text/icon color.
   - **Unselected Pill:** `Color.Transparent` background, `TextSecondary` text, `TextMuted` icon.
3. **Action & Status Chips:**
   - **Height / Padding:** `padding(horizontal = 10.dp, vertical = 6.dp)`.
   - **Shape:** `RoundedCornerShape(Radius.full)`.
   - **Container:** `SurfaceElevated` with `1.dp, BorderHairline`. Never use pastel or semi-transparent colored backgrounds!
   - **Iconography:** 13.dp icon with subtle functional tint (`StatusSuccess`, `StatusWarning`, `StatusError`, `TextSecondary`).
   - **Label:** `TextPrimary`, `11.sp`, `FontWeight.SemiBold`.

---

## 4. Forensic Autopsy of Download Failures (`activity-log-share-21.txt`)

### Case 1: "Phoenix Sleuth" S01E01 (`task=f6299564...`)
* **Initial URL:** `https://dramarain.com/download?link=MTI4MDMsMA==`
* **Flow:** `DramaGatewayResolver` cracked token to `https://japa.waffi.cloud/c/5/Phoenix-Sleuth-S01E01-DRAMARAIN-COM.mkv?preview`. `WaffiCloudResolver` stripped `?preview` to yield `https://japa.waffi.cloud/c/5/Phoenix-Sleuth-S01E01-DRAMARAIN-COM.mkv`.
* **Failure Point:** `LinkResolver.isKnownLockerHost` contained `"waffi"`, but `isProvablyDirectFile` did NOT exempt direct `.mkv` files on `waffi.cloud`. `LinkResolver.accept()` saw a locker host and returned `false`.
* **Engine Result:** `resolver chain EMPTY for dramarain.com - failing cleanly`.
* **Fix Applied:** Registered `waffi.cloud` direct CDN endpoints (`/c/`, `.mkv`, `.mp4`) in `LinkResolver.isProvablyDirectFile`.

### Case 2: AsianC / Vidbasic HLS Stream Failure
* **Initial URL:** `https://vidbasic.top/embed/aljakfd6ebm`
* **Resolved Stream:** `https://stream.vidbasic.top/9bece622-6c7d-d6a4-b9ab-58574295e511/index.m3u8?1789037476`
* **Failure Point:** In `VidbasicResolver.canResolve(url)`, the check was `!low.endsWith(".m3u8")`. Because the URL carried a query string (`?1789037476`), `low.endsWith(".m3u8")` evaluated to `false`. `VidbasicResolver` claimed the `.m3u8` stream as an HTML embed, attempted to parse HTML on a video playlist, and failed with `Vidbasic: no crypto payload found in HTML`.
* **Fix Applied:** Stripped query strings and hash anchors before checking file extensions in `VidbasicResolver.canResolve`.

### Case 3: "Echoes of the Self" S01E02 (`task=deb8d7d7...`)
* **Initial URL:** `https://dramarain.com/download?link=NjI1OSwx`
* **Failure Point:** Same root cause as Case 1 (`drip.waffi.cloud/.../Echoes%20of%20the%20Self%20S01E02%20(DRAMARAIN.COM).mkv` rejected by `LinkResolver.isKnownLockerHost`).
* **Fix Applied:** Fixed comprehensively via the `waffi.cloud` CDN recognition rule in `isProvablyDirectFile`.

---

## 5. Verification Checklist for Every Release

Before any new build is tagged or pushed:
- [ ] Run `python scripts/test_all_26_resolvers.py` (all 17 tests pass).
- [ ] Run `python scripts/ktregexflags.py app/src/main/java` (0 portability problems).
- [ ] Run `python scripts/test_asian_drama_taxonomy.py` (100% pass).
- [ ] Run `python scripts/test_explicit_content_filter.py` (100% pass).
- [ ] Run `python scripts/test_genre_accuracy.py` (100% pass).
- [ ] Run `python scripts/test_federated_failover.py` (100% pass).
- [ ] Confirm Light Mode and Dark Mode contrast on all selected pills (`AnonTheme.colors.background`).
- [ ] Confirm no pastel backgrounds appear in `DownloadsScreen.kt`.
- [ ] Confirm zero AI attribution in all commit messages.
