# MASTER JETPACK COMPOSE UI/UX, GEOMETRY & THEME OVERHAUL: ANONRODE (v3.2.9+)

## 0. CRITICAL OPERATING CONSTRAINTS & ANTI-SLOP MANDATE (READ FIRST)
1. **NATIVE ANDROID JETPACK COMPOSE (KOTLIN) — NO LOCAL GRADLE BUILDS:**
   - This app is built with **Kotlin & Jetpack Compose (Material 3)**.
   - This PC **never** builds the APK locally (`./gradlew assembleDebug` / `build` will fail or freeze the machine). All builds happen remotely via **GitHub Actions CI**.
   - **DO NOT** run `./gradlew` or launch the Android Gradle Daemon locally.
   - Because there is no local compiler or preview, you MUST manually verify every single `.kt` file you touch for **balanced braces, exact Kotlin imports (`androidx.compose.*`), valid Material 3 API signatures, and null-safety** before finishing. A single missing import or type mismatch will break the remote GitHub Actions CI run.
2. **LOAD KOTLIN / COMPOSE / UI SKILLS FIRST (IF AVAILABLE):**
   - If your environment supports loading skills or fetching reference rules via `npx` / GitHub without modifying the Android project dependencies, consult mobile UI design guidelines (`frontend-design`, `ui-ux-pro-max`, Material 3 Compose guidelines) before editing `.kt` files. Do NOT leave stray `node_modules` or `package.json` files in the git working tree.
3. **ZERO LAZINESS / ZERO PLACEHOLDERS:**
   - Do NOT leave `// TODO`, stubbed lambdas `{ /* todo */ }`, or half-implemented Composables.
   - Do NOT break existing ViewModels, Coroutine flows, Python/Chaquopy/`yt-dlp` bridges, Room databases, or scraper extractors—only refactor the Jetpack Compose UI layers, state/formatting helpers, in-memory/persistent episode cache, and Material 3 Theme tokens.
   - Every requirement in Sections 1 through 6 below is mandatory.

---

## 1. GLOBAL MATERIAL 3 THEME & "TRUE OLED OBSIDIAN" COLOR ENGINE
### Problems Found in Walkthrough
- The Episode Bottom Sheet (`Crystal Lake S01`, `Teetotaler`) uses a hardcoded, washed-out navy-slate (`~0xFF111622` / `0xFF182030`) that looks muddy in Dark Mode and ignores Light Mode completely.
- Dark Mode on the `Downloads & Media` screen uses harsh, bright gray `BorderStroke` outlines on `#000000` black instead of subtle surface elevation.
- **White-on-White Glare Bugs in Dark Mode:** Switching to Dark Mode turns the circular Play buttons (`▶`) and the `Resume all` icon into solid white circles with a white inner play icon (`tint = Color.White` on `containerColor = Color.White`, making the icon invisible!), and leaves fallback monogram boxes (`MN`, `JK`, `PS`, `SO`) as glaring white/light-gray squares.

### Required Jetpack Compose Implementation
1. **True OLED Obsidian `darkColorScheme` Tokens:**
   - `background` = `Color(0xFF000000)` (Pure OLED Black)
   - `surface` = `Color(0xFF0B0B0E)` (Cards, `ModalBottomSheet` containerColor, Settings groups)
   - `surfaceVariant` = `Color(0xFF141419)` (Grouped episode container, Search bar, Filter chips, Inactive pills)
   - `surfaceContainerHigh` / Elevated = `Color(0xFF1E1E26)` (Pressed states, Fallback poster/monogram backgrounds)
   - `onBackground` / `onSurface` = `Color(0xFFF4F4F6)` (Crisp primary text)
   - `onSurfaceVariant` = `Color(0xFF8E8E99)` (Secondary/subtitle text)
   - `outline` = `Color(0x12FFFFFF)` (`7%` white alpha hairline border; NEVER use bright opaque gray borders on `#000000`)
2. **Clean `lightColorScheme` Tokens:**
   - `background` = `Color(0xFFF8F9FA)` | `surface` = `Color(0xFFFFFFFF)` | `surfaceVariant` = `Color(0xFFF1F3F5)` | `onBackground` / `onSurface` = `Color(0xFF0F1115)` | `onSurfaceVariant` = `Color(0xFF5C606B)` | `outline` = `Color(0x14000000)`.
   - Remove all hardcoded `Color(0xFF111622)` and `Color(0xFF182030)` navy colors from the Episode Bottom Sheet so it uses `MaterialTheme.colorScheme.surface` (`0xFF0B0B0E` in Dark Mode, `0xFFFFFFFF` in Light Mode) and `surfaceVariant`.
3. **Explicit Contrast Lock on Play & Resume Buttons (CRITICAL BUG FIX):**
   - On every circular Play button (`IconButton` / `Surface(shape = CircleShape)`) in `Downloads & Media` and the `Resume all` button, explicitly bind **both** the background container color and the `Icon` `tint`:
     - **In Dark Mode:** `containerColor = Color(0xFFFFFFFF)` with `Icon(..., tint = Color(0xFF000000))` (OR `containerColor = Color(0xFF1E1E26)` with `tint = Color(0xFFFFFFFF)` and `BorderStroke(1.dp, Color(0x1FFFFFFF))`).
     - **In Light Mode:** `containerColor = Color(0xFF0F1115)` with `Icon(..., tint = Color(0xFFFFFFFF))`.
   - **Rule:** Never rely on ambient `LocalContentColor` if it allows `Color.White` icon tint on a `Color.White` circle!
4. **Dark Mode Fallback Thumbnail Containers (`MN`, `JK`, `PS`, `SO`):**
   - Never render white or light-gray fallback boxes in Dark Mode. Use `Color(0xFF1E1E26)` in Dark Mode (`Color(0xFFE9ECEF)` in Light Mode) with a centered media/film `Icon` (`tint = MaterialTheme.colorScheme.onSurfaceVariant`).

---

## 2. EPISODE `ModalBottomSheet`: VIEWPORT LOCK, HEADER GEOMETRY & UX
### Problems Found in Walkthrough
- Redundant `"PULL UP FOR THE FULL VIEW"` `Text` sits at the top of the sheet.
- **Unpinned Footer in Half-View (`skipPartiallyExpanded = false`):** When the sheet opens in its 50% partially-expanded state, the bottom action bar (`Selected X episodes` + `Download` button) is placed at the bottom of a full-screen-height container, pushing it off-screen below the fold until the user swipes up to full screen.
- **Status Bar Collision:** When pulled to full view, the sheet collides with the Android top status bar (`6:36 AM` clock), and the `X` close button nearly clips the top-right corner.
- **Lopsided Header Geometry:** The poster sits on the left while 60% of the space to its right is empty, the series synopsis/write-up is missing, and `All (8)` sits on a lonely row underneath.
- **Range Input Clutter:** The huge `OutlinedTextField` (`Range (e.g. 1-5, 8, 10)`) renders even when a show only has 2 episodes (`Teetotaler`).
- **Bulky Floating Episode Boxes:** Each episode is wrapped in a tall separate bordered `Card` with vertical gaps, showing only 4 episodes in half-view.
- **Raw Scraper Text & Missing History:** Episodes display `01 Download` / `02 Download` instead of episode numbers, and there is no green tick showing if an episode was already downloaded.

### Required Jetpack Compose Implementation
1. **Always-Visible Sticky Action Bar (Even in Half-View):**
   - Either configure `rememberModalBottomSheetState(skipPartiallyExpanded = true)` combined with `Modifier.fillMaxWidth().fillMaxHeight(0.88f).statusBarsPadding()` so the sheet opens directly into a clean, status-bar-safe view with the sticky bottom bar immediately visible, **OR** if supporting a 55% partial state, constrain the sheet's root `Column` height to the **visible sheet offset** so the bottom `Surface` action bar (`Selected X episodes` + `Download` `Button`) is ALWAYS pinned to the bottom edge of the screen without swiping up.
   - Structure the sheet body as:
     ```kotlin
     Column(
         modifier = Modifier
             .fillMaxWidth()
             .navigationBarsPadding()
     ) {
         // 1. Header + Synopsis + Selection Pills
         // 2. LazyColumn(modifier = Modifier.weight(1f, fill = false)) { ... }
         // 3. Pinned Bottom Action Bar Surface(...)
     }
     ```
2. **Top Safe-Area & Header Cleanup:**
   - Delete the `"PULL UP FOR THE FULL VIEW"` `Text` composable completely. Keep only a clean `36.dp × 4.dp` drag handle pill.
   - Apply `windowInsetsPadding(WindowInsets.statusBars)` or cap `maxHeight` so the sheet never overlaps the Android clock/status bar icons.
   - Inset the circular `X` close button cleanly (`padding(top = 12.dp, end = 16.dp)`).
3. **Balanced Hero Header Placement & Synopsis Restoration:**
   - Use a `Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top)`:
     - **Left:** Poster thumbnail (`AsyncImage` / `SubcomposeAsyncImage`) fixed at `width = 76.dp, height = 108.dp`, `clip(RoundedCornerShape(10.dp))`, `border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))`.
     - **Right (`Column(modifier = Modifier.weight(1f).heightIn(min = 108.dp), verticalArrangement = Arrangement.SpaceBetween)`):**
       - **Top Row:** Source badge pill (e.g., `NKIRI`) + Episode count pill (`8 EPS`), leaving `36.dp` end padding for the `X` close icon.
       - **Middle:** Series Title (`fontSize = 18.sp`, `fontWeight = FontWeight.Bold`, `maxLines = 2`) + **Series Synopsis/Write-up** directly below (`fontSize = 12.sp`, `color = MaterialTheme.colorScheme.onSurfaceVariant`, `maxLines = if (synopsisExpanded) 8 else 2`, `overflow = TextOverflow.Ellipsis`, `Modifier.clickable { synopsisExpanded = !synopsisExpanded }`).
       - **Bottom Row:** Compact selection chips (`All (X)`, conditional `Range` toggle chip, and `Clear (X)`) aligned cleanly with the bottom edge of the poster.
4. **Conditional Range Selector:**
   - Hide the `Range (e.g. 1-5, 8, 10)` `OutlinedTextField` by default (`var showRangeInput by remember { mutableStateOf(false) }`).
   - Only when `episodes.size > 6`, render a compact `"Range"` filter chip next to `All (X)` that toggles `AnimatedVisibility(visible = showRangeInput)` for the range text field.
5. **Grouped Inset Episode Container (Replace Separate Floating Cards):**
   - Wrap the episode list inside a grouped inset container appearance (`Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline))` or item background styling inside `LazyColumn`).
   - Render each episode as a compact `50.dp`-tall `Row(modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically)` separated by `HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)`:
     - **Left:** Clean monospace episode number (`"01"`, `"02"`, `fontSize = 13.sp`, `color = MaterialTheme.colorScheme.onSurfaceVariant`, `modifier = Modifier.width(28.dp)`—remove the bulky inner dark square box).
     - **Center (`Modifier.weight(1f)`):** Sanitized Episode Title (`fontSize = 14.sp`, `fontWeight = FontWeight.Medium`) + inline emerald badge (`✓ Downloaded`) if already completed.
     - **Right:** Single `Checkbox` or custom rounded `22.dp` check indicator. Hide the circular `i` info button unless non-blank episode-specific metadata exists.
6. **Episode Title Sanitizer & "Already Downloaded" State:**
   - Write a pure Kotlin helper `sanitizeEpisodeTitle(rawTitle: String?, index: Int, seasonNumber: Int? = null): String`:
     - If `rawTitle.isNullOrBlank()` or matches `Regex("^(download|link|watch|stream|episode|part|mp4|mkv|hd|\\s*|\\d+)+$", RegexOption.IGNORE_CASE)`, return `"Episode ${index + 1}"` (or `"Season $seasonNumber · Episode ${index + 1}"` when season > 0). Never render `"01 Download"`.
   - Cross-check each episode against completed downloads in the app's Downloads state/repository (matching by normalized show title + season/episode number or episode URL). If completed, render a compact emerald pill (`"✓ Downloaded"`, text `Color(0xFF10B981)`, background `Color(0x1F10B981)`) and set row alpha to `0.78f`.
7. **Zero-Selection CTA Button State:**
   - When `selectedCount == 0`, either disable the `Download` `Button` (`enabled = false`) OR set its label to `"Download All (${episodes.size})"`.

---

## 3. DOWNLOADS & MEDIA SCREEN: COMPACT HEADER & 3-COLUMN CARD GEOMETRY
### Problems Found in Walkthrough
- **Header Bloat:** Nearly 35% of the vertical screen is consumed by stacked headers (storage text + full-width progress line + 4 giant sort rectangles + `Resume all` + 4 non-interactive legend dots).
- **Stranded Top-Left Thumbnail:** On every download card, the thumbnail sits in the top-left corner with empty space underneath it because the center `Column` stacks 4 rows (Title -> Status 1 -> Status 2 -> Source Badge `PLUTO`/`NEPU` on its own row -> Progress bar + 2-line error footer).
- **Redundant & Inaccurate Copy:** Paused cards say `"Paused"` twice and say `"Tap to retry"` instead of `"Tap to resume"`. Failed cards repeat `"Failed"` 3 times. Unknown file sizes render `/ 0 B` (`368.0 KB / 0 B`).

### Required Jetpack Compose Implementation
1. **Compact Header & Interactive Filter Chip Row:**
   - Consolidate storage into a compact header subtitle (`110 / 112 GB used · 2 GB free`) with a sleek `4.dp` rounded `LinearProgressIndicator`.
   - Remove the 4 giant sort boxes (`Date`, `By Show`, `Status`, `Size`) and the 4 static legend dots (`Live`, `Done`, `Paused`, `Failed`).
   - Replace them with **one `LazyRow` of interactive `FilterChip`s**: `All`, `Active`, `Paused`, `Done`, `Failed` (with counts), plus a compact `Sort: Date ▾` dropdown menu button aligned to the right of `Resume all`.
2. **Strict 3-Column Horizontal Card Geometry (Fix Stranded Thumbnail):**
   - Build each download card as a `Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline))` containing a `Box`:
   - Inside the `Box`, use a `Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp))`:
     - **Left Column (2:3 Poster Thumbnail):** `Box(modifier = Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)))`.
       - **Episode Tag Overlay:** Extract `S\d+E\d+` (e.g., `S01E01`, `S02E09`) from the title and render it as a micro badge anchored at `Alignment.BottomStart` (`padding(4.dp)`, `background(Color(0xCC000000), RoundedCornerShape(4.dp))`, `fontSize = 9.sp`, `fontWeight = FontWeight.Bold`, `color = Color.White`), stripping the `S01E01` code and trailing `[yt-dlp-id]` from the main title text.
     - **Center Column (Tight 2-Row Stack — `Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center)`):**
       - **Row 1 (Clean Title):** `Text(text = cleanTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)`.
       - **Row 2 (Inline Source Pill + Unified Status + Size):** Use a `Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp))` that places the compact source badge (`PLUTO`, `NEPU`, `ASIANC`) **inline on the exact same row** as the status and byte string. NEVER put the source badge on a separate 3rd vertical row!
         - *Paused:* `[PLUTO]` + `"Paused · 368.0 KB · Tap to resume"` (amber `Color(0xFFF59E0B)`; NEVER say `"Tap to retry"`).
         - *Active:* `[ASIANC]` + `"42% · 140.2 MB / 330.0 MB"` (blue/emerald).
         - *Failed:* `[NEPU]` + `"Failed · Host unreachable"` (red `Color(0xFFEF4444)`; remove the tall 2-line bottom error paragraph from the card face and show full error details on tap/dialog or inside the `⋮` menu).
         - *Done:* `[ASIANC]` + `"✓ Done · 323.3 MB"` (emerald `Color(0xFF10B981)`).
       - **Zero-Byte Denominator Rule:** In the byte formatter helper, if `totalBytes <= 0L`, output only `formatBytes(downloadedBytes)`. NEVER output `"/ 0 B"`.
     - **Right Column (Action Controls):** Vertically centered `36.dp` circular Play button (with explicit Dark/Light contrast!) or compact `Retry` pill + `⋮` `IconButton`.
   - **Flush Bottom Progress Bar:** For active and paused items only, place a `LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))` inside the outer `Box` so it sits flush along the bottom edge of the card and adds `0.dp` of extra vertical height. Hide the progress bar completely on `Done` and `Failed` cards.

---

## 4. HOME, DISCOVERY, CATEGORY GRIDS, CACHING & SETTINGS
### Problems Found in Walkthrough
- Missing posters render as flat gray rectangles with a single giant letter (`A`, `P`, `T`, `M`).
- `"Browse by Genre"` uses 2:3 vertical posters where `Action` has no image (`A`) and `View More` duplicates the exact same poster as `Anime` (`The Ramparts of Ice`).
- Poster titles contain raw scraper suffixes (`| Download Comedy Special`) and uneven line wrapping.
- Category view (`COMEDY`) reshuffles cards and jumps title counts (`23` -> `9` -> `21`) while background sources resolve.
- Re-opening an episode sheet takes 7–8 seconds (`"Finding episode pages..."`) every time.
- Settings rows display number pills (`5`, `8`, `8`, `5`, `4`) that look like unread notifications.

### Required Jetpack Compose Implementation
1. **Stylized Poster Fallback Composable:**
   - Replace single-letter gray blocks (`A`, `P`, `M`) with a dark gradient `Box` (`Brush.linearGradient(listOf(Color(0xFF181824), Color(0xFF0C0C12)))`) featuring a subtle centered Movie/Play `Icon` (`alpha = 0.18f`) and the show's title cleanly centered inside the frame.
2. **Grid Title Sanitizer & Uniform Alignment:**
   - Strip scraper suffixes (`Regex("(\\s*\\|\\s*download.*|\\s*-\\s*download.*|\\s*free download.*)$", RegexOption.IGNORE_CASE)`) from poster titles.
   - Extract `(YYYY)` and `Season X` into a muted secondary subtitle (`2021 · Season 6`) below a fixed 2-line title (`maxLines = 2, minLines = 2`) so every poster card in a grid row aligns horizontally.
3. **Genre Cards & Asian Drama Hubs:**
   - Redesign `"Browse by Genre"` into sleek `16:9` or `2:1` horizontal gradient category cards with bold typography and category icons (eliminating the broken `A` poster and duplicate `View More` poster).
   - Give `K-DRAMA HUB` and `C-DRAMA HUB` rich gradient backgrounds and subtle accent borders. Remove the `"Tap a title to pick episodes — or search above"` helper text under *Trending Now*.
4. **Stable Incremental Category Aggregation & Episode Cache:**
   - In the Category ViewModel/state (`COMEDY`), merge and append incoming source items using a stable key (`distinctBy { it.url }`) without clearing or reshuffling cards already visible on screen.
   - Add an in-memory `ConcurrentHashMap<String, CachedEpisodeList>` (with 30-minute TTL) in the repository/ViewModel keyed by `source + showUrl` so re-opening a show's Episode Bottom Sheet is instantaneous (`0ms`).
5. **Settings Screen:**
   - Remove the numeric badge pills (`5`, `8`, `8`, `5`, `4`) next to the chevrons on Settings rows; leave only the clean chevron `Icon`.

---

## 5. FINAL MICRO-POLISH: SKELETONS, TABS, SOCIAL TITLES & SCROLL PADDING
1. **Grouped Row Skeletons & Step-Based Loading:**
   - During `"Finding episode pages..."` and `"Unlocking stream locker..."`, replace the 4 bulky navy double-bordered skeleton boxes with a sleek grouped container (`surfaceVariant`, `RoundedCornerShape(14.dp)`) holding 5 compact `50.dp` shimmer rows.
   - Format the loading status text with step progress: `"Step 1/2 · Finding episode pages..."` -> `"Step 2/2 · Unlocking stream links..."`.
2. **Unified Bottom Navigation Bar (`NavigationBar` / `NavigationBarItem`):**
   - Explicitly configure `NavigationBarItemDefaults.colors(...)` for both Light and Dark themes so the active tab indicator pill is clearly visible in Light Mode (`indicatorColor = Color(0x140F1115)`) and crisp in Dark Mode (`indicatorColor = Color(0x1FFFFFFF)`).
   - Add a `BadgedBox` on the `Downloads` tab icon when active or failed downloads exist.
3. **Strip Raw `yt-dlp` Extractor IDs from Social Download Titles:**
   - Strip trailing bracketed IDs matching `Regex("\\s*\\[[A-Za-z0-9_-]{8,20}\\]\\s*$")` so social video titles like `"Video by lolita [Defo4HhjcWO]"` render cleanly as `"Video by lolita"`.
4. **Media Fallback Icons for Social/Direct Downloads:**
   - Replace the `"SO"` / `"NK"` two-letter monogram squares on `Downloads & Media` cards with the `52.dp × 72.dp` dark gradient fallback thumbnail + centered video/reel SVG `Icon`, and display `"SOCIAL"` or `"NKIRI"` in the inline source badge.
5. **Source Filter `LazyRow` Padding:**
   - Set `contentPadding = PaddingValues(horizontal = 16.dp)` on the horizontal source filter `LazyRow` (`All Sites`, `Torrents`, `Nkiri`, `9jaRocks`, `AsianC`) so `AsianC` is never clipped against the screen edge.

---

## 6. NO-GRADLE CI-SAFE STATIC VERIFICATION BENCHMARKS (MANDATORY BEFORE FINISHING)
Because this PC never runs Gradle locally and builds exclusively on **GitHub Actions CI**, you MUST perform the following static checks and report the results in your final message:

1. **Manual Kotlin Syntax, Import & Brace Audit:**
   - Inspect every modified `.kt` file to verify balanced `{}` / `()`, valid Kotlin syntax, and complete `androidx.compose.*` imports (e.g., `BorderStroke`, `CircleShape`, `RoundedCornerShape`, `Alignment`, `Arrangement`, `WindowInsets`, `statusBarsPadding`, `navigationBarsPadding`, `TextOverflow`).
   - Run `git status` and `git diff --stat` to ensure no stray temporary files (`node_modules`, `package.json`, etc.) were left in the repository.
2. **Grep / Invariant Audit (Must All Pass):**
   - [ ] `PULL UP FOR THE FULL VIEW` returns **0 matches** in `app/src/`.
   - [ ] `Tap to retry` on paused items returns **0 matches** (replaced with `Tap to resume`).
   - [ ] `0xFF111622` and `0xFF182030` navy sheet colors return **0 matches** (replaced with Obsidian theme tokens).
   - [ ] Byte formatter guards against `totalBytes <= 0L` so `"/ 0 B"` can never be emitted.
   - [ ] Download card Play button explicitly sets contrasting `containerColor` and `Icon` `tint` in Dark Mode (no white-on-white bug).
   - [ ] Episode `ModalBottomSheet` pins the `Selected X episodes` + `Download` footer inside the visible viewport height and applies `statusBarsPadding()`.
   - [ ] Download card source badge is placed inline inside the 2nd metadata `Row` (not a 3rd vertical row), and the `LinearProgressIndicator` is aligned to `Alignment.BottomCenter` of the card `Box`.
   - [ ] Title sanitizer strips `| Download...` suffixes, `S\d+E\d+` tags (moved to thumbnail badge), and trailing `[yt-dlp_id]` brackets.
