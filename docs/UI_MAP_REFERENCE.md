# UI_MAP_REFERENCE — Anonrode Downloader (com.anonrode.downloader)

Single-source-of-truth reference for the **complete UI layer** of the Android app in this repo
(`app/` module, Jetpack Compose + a small amount of classic Android). Every claim below was read
from source at the cited file:line. Nothing here is inferred from filenames alone.

**How to use this doc:** find the surface you need in the Table of Contents or the
[file inventory](#9-file-inventory), jump to its deep-dive section, and use the line references to
edit. All Compose code lives under
`app/src/main/java/com/anonrode/downloader/ui/`; the two Activities and the Application class live one
package up; the single ViewModel lives in `viewmodel/MainViewModel.kt`. The only XML UI surface is the
player layout and the theme/color/drawable resources (Section 5).

**Scope notes (verified):**
- There are exactly **25 `.kt` files under `ui/`** (10 components, 10 screens, 2 theme, 3 util) plus
  `MediaPlayerModal`'s two helpers `MediaPlayerPrefs.kt`/`PlaybackPositions.kt` (counted in the 10
  components). Counted from the directory listing.
- There is **no fragment, no XML activity layout, no navigation-graph library**. Navigation is
  hand-rolled: a `rememberSaveable` tab string in `MainScaffold`, plus modal state held in
  `MainViewModel` flows and in `MainActivity` local state.
- There is **no user-visible string resource** other than `app_name`; every UI string is hardcoded in
  Kotlin (see §8, gap G-1).

---

## Table of Contents

1. [App / UI architecture overview](#1-app--ui-architecture-overview)
2. [Navigation & screen graph](#2-navigation--screen-graph)
3. [Screen-by-screen deep dives](#3-screen-by-screen-deep-dives)
   - 3.1 [MainActivity — the root host](#31-mainactivity--the-root-host)
   - 3.2 [MainScaffold — tabs](#32-mainscaffold--tabs-bottom-nav)
   - 3.3 [HomeScreen](#33-homescreen)
   - 3.4 [DownloadsScreen](#34-downloadsscreen)
   - 3.5 [EpisodeDrawer](#35-episodedrawer)
   - 3.6 [SettingsScreen (SettingsSheet.kt)](#36-settingsscreen-settingssheetkt)
   - 3.7 [QuickShareActivity + QuickShareCard](#37-quickshareactivity--quicksharecard)
   - 3.8 [PlaylistPickerSheet](#38-playlistpickersheet)
   - 3.9 [SocialModal](#39-socialmodal)
   - 3.10 [TorrentFilePicker](#310-torrentfilepicker)
   - 3.11 [SplashContent + SplashMotion](#311-splashcontent--splashmotion)
4. [Component catalog](#4-component-catalog)
   - 4.1 [MediaPlayerModal](#41-mediaplayermodal)
   - 4.2 [AsianDramaExplorer](#42-asiandramaexplorer)
   - 4.3 [DownloadsSorter (+ downloadsStats)](#43-downloadssorter--downloadsstats)
   - 4.4 [Skeleton family](#44-skeleton-family)
   - 4.5 [MediaFallback (PosterFallbackWell / PosterImageOrFallback)](#45-mediafallback)
   - 4.6 [SegmentedBar](#46-segmentedbar)
   - 4.7 [QualityChipRow](#47-qualitychiprow)
   - 4.8 [PlaybackPositions / MediaPlayerPrefs](#48-playbackpositions--mediaplayerprefs)
5. [Theme, tokens, resources](#5-theme-tokens-resources)
   - 5.1 [Theme.kt — palettes & Material schemes](#51-themekt)
   - 5.2 [Tokens.kt — spacing/radius/motion/type](#52-tokenskt)
   - 5.3 [UI utilities](#53-ui-utilities)
   - 5.4 [AndroidManifest](#54-androidmanifest)
   - 5.5 [res/ inventory](#55-res-inventory)
6. [ViewModel & state reference](#6-viewmodel--state-reference)
   - 6.1 [HomeUiState — every field](#61-homeuistate--every-field)
   - 6.2 [Other UI flows](#62-other-ui-flows)
   - 6.3 [Every public ViewModel method](#63-every-public-viewmodel-method)
   - 6.4 [Engine (DownloadEngine) surface read by the UI](#64-engine-downloadengine-surface-read-by-the-ui)
   - 6.5 [Data models rendered by the UI](#65-data-models-rendered-by-the-ui)
   - 6.6 [Content filters and their UI-visible effect](#66-content-filters-and-their-ui-visible-effect)
   - 6.7 [Persistence keys](#67-persistence-keys)
7. [UI → behavior map](#7-ui--behavior-map)
8. [Gaps & risks](#8-gaps--risks)
9. [File inventory](#9-file-inventory)

---

## 1. App / UI architecture overview

### 1.1 Boot chain

```
AnonApp : Application (AnonApp.kt:20)
  onCreate (AnonApp.kt:25)
    ├─ repository.initPersistence(filesDir)               (AnonApp.kt:28)
    ├─ DebugLog.init                                      (AnonApp.kt:29)
    ├─ engine = DownloadEngine(...NetworkObserver)        (AnonApp.kt:30)
    ├─ engine.onTorrentFileSelection -> TorrentFilePicker.pick(files)   (AnonApp.kt:33-35)
    ├─ first-launch torrent-peer default (RAM tier)       (AnonApp.kt:41-46)
    ├─ CrashHandler.install / RemoteLogger.install / LogUploader.install (AnonApp.kt:47-55)
    └─ appScope.launch (IO): DynamicRulesManager.init, HostHealth.init,
       FeedCache.init, initYoutubeDlInternal, maybeSyncRules() [24h],
       maybeUpdateYoutubeDL() [24h], LogUploader.onAppOpen   (AnonApp.kt:56-74)
  newImageLoader(): Coil ImageLoader — okHttp Dispatcher maxRequests=64/16 per host,
      UA header, memory cache 25%, disk cache 150 MB under cacheDir/image_cache, crossfade 120ms
      (AnonApp.kt:114-144)

MainActivity : ComponentActivity (MainActivity.kt:63)
  viewModel: MainViewModel by viewModels()               (MainActivity.kt:65)
  onCreate (MainActivity.kt:67)
    ├─ installSplashScreen() (androidx splashscreen, try/catch)  (MainActivity.kt:69)
    ├─ cold theme read ("pref_theme_mode", default "dark") + applyEdgeToEdge (MainActivity.kt:80-82)
    ├─ handleShareIntent(intent)  — ACTION_SEND text/plain -> UrlExtractor.firstUrl
    │     -> viewModel.handlePastedInput(url, onOpenSocial)   (MainActivity.kt:84, 417-432)
    ├─ initialTab read from "pref_last_tab" (validated against MainTab) (MainActivity.kt:94-97)
    ├─ splash full/quick decision "splash_full_shown"      (MainActivity.kt:103-105)
    └─ setContent { AnonDownloaderTheme(themeMode) { ... } }  (MainActivity.kt:107-391)
         ├─ SideEffect { syncSystemBars(isDark) }          (MainActivity.kt:127)
         ├─ MainScaffold(viewModel, initialTab, themeMode, onThemeChanged,
         │      onOpenSocial, onWriteTabPref, onPlayTask)   (MainActivity.kt:261-277)
         ├─ MediaPlayerModal(ctx) when activePlayingTaskId != null  (MainActivity.kt:279-281)
         ├─ SocialModal when activeSocialTarget != null     (MainActivity.kt:283-290)
         ├─ PlaylistPickerSheet when playlistState.url != null (MainActivity.kt:295-301)
         ├─ TorrentFilePickerHost()                         (MainActivity.kt:310, 529-538)
         ├─ storage-rationale AlertDialog (lifecycle-driven) (MainActivity.kt:312-364)
         └─ SplashContent overlay when showSplash            (MainActivity.kt:377-389)

MainActivity.onNewIntent -> handleShareIntent again         (MainActivity.kt:394-397)
MainActivity.onPictureInPictureModeChanged -> PlayerPipState.inPip (MainActivity.kt:408-415)
```

`MainActivity` is declared with `launchMode="singleTask"`, `supportsPictureInPicture="true"`,
`configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"` and
theme `Theme.AnonDownloader.Splash` (AndroidManifest.xml:28-40).

### 1.2 Compose vs XML split

- **All UI is Compose** except:
  - `res/layout/player_modal_texture_view.xml` — a `PlayerView` with
    `app:surface_type="texture_view"`, inflated inside `MediaPlayerModal` because media3-ui reads
    `surface_type` only from XML (player_modal_texture_view.xml:1-12; usage MediaPlayerModal.kt:1081-1088).
  - `res/mipmap-anydpi-v26/ic_launcher*.xml` adaptive icons and the five vector drawables
    (SplashContent/HomeScreen use two of them).
- **Layout inflation for UI is otherwise zero.** No XML strings, dimens, or menus exist
  (`res/values/strings.xml` contains only `app_name`; there is no `dimens.xml`).

### 1.3 State flow direction (one-way, Compose classic)

```
AnonApp.engine: DownloadEngine
   ├─ engine.tasks: StateFlow<List<DownloadTask>>            (DownloadEngine.kt:115)
   └─ engine settings @Volatile vars (DownloadEngine.kt:66-97)

MainViewModel (AndroidViewModel, viewmodel/MainViewModel.kt:91)
   ├─ uiState: StateFlow<HomeUiState>            (MainViewModel.kt:95-96)
   ├─ playlistState: StateFlow<PlaylistUiState>  (MainViewModel.kt:109-110)
   ├─ activeSocialTarget: MutableStateFlow<Pair<String,String>?> (MainViewModel.kt:113)
   ├─ activePlayingTaskId: MutableStateFlow<String?>             (MainViewModel.kt:114)
   └─ methods mutate these + call engine.enqueue/saveAllSettings/etc.

UI composables
   ├─ collectAsState(viewModel.uiState / playlistState / engine.tasks) to read
   ├─ call viewModel methods / engine methods to write
   └─ local `remember` / `rememberSaveable` state for purely visual state
      (focus, dialogs, drag offsets, selection sets)
```

Notable direction rules verified in code:
- **Theme** is owned by `MainActivity` local state (`themeMode`), fed down through `MainScaffold`
  to `SettingsScreen`, and persisted by the MainActivity `onThemeChanged` lambda writing
  `pref_theme_mode` (MainActivity.kt:265-269; SettingsSheet.kt:509-511).
- **Tab** is owned by `MainScaffold` (`rememberSaveable`), persisted through `onWriteTabPref`
  (MainScaffold.kt:94, 111-113; MainActivity.kt:273-275).
- **Active player task** is an ID in the ViewModel; the player context (file path + queue) is
  derived in MainActivity from the engine snapshot, NOT stored (MainActivity.kt:238-259).
- All three tab screens **stay composed**; only z-index+alpha change on tab switch
  (MainScaffold.kt:141-179, 192-215).

---

## 2. Navigation & screen graph

### 2.1 Tabs

`MainTab` constants: `SEARCH="search"`, `DOWNLOADS="downloads"`, `SETTINGS="settings"`,
`DEFAULT=SEARCH` (MainScaffold.kt:55-60). The stored pref is validated in MainActivity before being
used (MainActivity.kt:94-97).

### 2.2 Diagram (all root-level surfaces)

```
┌─ MainActivity window (edge-to-edge, single window) ─────────────────────────────┐
│                                                                                 │
│  MainScaffold                                                                   │
│   ├─ TabPage(SEARCH)    -> HomeScreen                                           │
│   │     ├─ overlay: CategoryPage      (uiState.activeCategory != null)          │
│   │     ├─ overlay: AsianDramaExplorer(uiState.activeDramaRegion != null)       │
│   │     ├─ overlay: CatalogPage       (catalogOpen && no category && no drama)  │
│   │     └─ overlay: EpisodeDrawer     (uiState.activeShowForDrawer != null)     │
│   ├─ TabPage(DOWNLOADS) -> DownloadsScreen  (+ internal AlertDialogs)           │
│   ├─ TabPage(SETTINGS)  -> SettingsScreen   (hub <-> 5 drawers, AnimatedContent)│
│   └─ bottom: BottomNavBar (Search / Downloads+badge / Settings)                 │
│                                                                                 │
│  MediaPlayerModal  (root overlay, ABOVE the bottom nav; not a Dialog)           │
│    └─ child dialogs: AudioOptionsSheet, BottomChoiceSheet (subtitles)           │
│  SocialModal       (ModalBottomSheet, root)                                     │
│  PlaylistPickerSheet (ModalBottomSheet, root)                                   │
│  TorrentFilePickerDialog (AlertDialog, root; engine-driven)                     │
│  Storage rationale AlertDialog (MainActivity)                                   │
│  SplashContent overlay (painted LAST = topmost for its duration)                │
└─────────────────────────────────────────────────────────────────────────────────┘

Second activity (separate task, singleInstance, excludeFromRecents):
QuickShareActivity  <- ACTION_SEND text/plain
  ├─ instant mode ON  -> headless enqueue + finish  (no UI)
  └─ instant mode OFF -> QuickShareCard bottom sheet over 50% black scrim
```

### 2.3 Back handling (all `BackHandler`s, verified)

| Surface | Handler | File:line |
|---|---|---|
| MainScaffold, tab == DOWNLOADS | back → SEARCH tab | MainScaffold.kt:100-102 |
| MainScaffold, tab == SETTINGS | back → SEARCH tab | MainScaffold.kt:103-105 |
| HomeScreen tab (Search) | **none** — back exits the app (intentional, comment at MainScaffold.kt:97-99) | — |
| CategoryPage | back → `closeCategory()` | HomeScreen.kt:1254 |
| CatalogPage | back → `closeCatalog()` | HomeScreen.kt:1504 |
| AsianDramaExplorer | back → `closeAsianDramaHub()` | AsianDramaExplorer.kt:66 |
| EpisodeDrawer | system back → `onDismiss` (Dialog's `onDismissRequest`) | EpisodeDrawer.kt:358 |
| MediaPlayerModal | back → `onDismiss` | MediaPlayerModal.kt:915 |
| MediaImageStill (in-player image viewer) | back → `onDismiss` | MediaPlayerModal.kt:2124 |
| SettingsScreen drawer open | back → close drawer (hub) | SettingsSheet.kt:268 |
| SocialModal / PlaylistPickerSheet | ModalBottomSheet dismiss | SocialModal.kt:53-66; PlaylistPickerSheet.kt:91-104 |

Note the asymmetry: Downloads/Settings tabs route back to Search **instead of exiting**
(MainScaffold.kt:96-105); Search has no BackHandler so back exits the app.

### 2.4 Transitions/animations between surfaces (summary, details in §3/§4)

| Transition | Spec | File:line |
|---|---|---|
| Tab switch | alpha cross-fade `Motion.DurationFast` (200ms), zIndex instant | MainScaffold.kt:199-208 |
| Settings hub ↔ drawer | slide+fade, 200ms in / 120ms out, `SettingsEasing` | SettingsSheet.kt:336-356 |
| EpisodeDrawer open/close/detents | `Motion.Spatial` springs on a single translated surface | EpisodeDrawer.kt:420-483 |
| Spotlight list items (search / trending / grids / catalog rows) | `Modifier.animateItem` — effects spring in, spatial placement, tween fade-out | HomeScreen.kt:599-607, 832-841, 1378-1387, 1592-1601 |
| Player controls overlay | `AnimatedVisibility` fade in/out | MediaPlayerModal.kt:1166-1171 |
| Volume HUD | fade in/out | MediaPlayerModal.kt:1151-1164 |
| Splash exit | 150ms settle + 250ms `FastOutSlowInEasing` fade of the whole overlay | SplashContent.kt:109-117 |
| Download card progress bar | `animateFloatAsState(tween 350)` | DownloadsScreen.kt:1273-1277 |
| Live status dot pulse | infinite 800ms reverse alpha 1.0→0.25 | DownloadsScreen.kt:1524-1533 |
| Skeleton shimmer | 1200ms linear sweep; frozen when system animations off | Skeleton.kt:66-93 |

---

## 3. Screen-by-screen deep dives

### 3.1 MainActivity — the root host

**File:** `app/src/main/java/com/anonrode/downloader/MainActivity.kt` (538 lines, class at :63).

**Purpose:** the app's one activity window; hosts the theme, the system-bar policy, the splash
overlay, and ALL root-level overlays (player, social, playlist, torrent picker, storage dialog).

**Key state (all local to `onCreate`/composition):**

| State | Type | Init | Line |
|---|---|---|---|
| `themeMode` | `mutableStateOf(initialThemeMode)` | from `pref_theme_mode`, default "dark" | 93, 108 |
| `showStorageRationale` | `mutableStateOf(false)` | re-evaluated on every ON_RESUME | 162, 185-199 |
| `showSplash` | `mutableStateOf(savedInstanceState == null)` | cold start only; fallback timeout `splashCutMs + 1200ms` | 209-215 |
| `playingTaskId` | collected from `viewModel.activePlayingTaskId` | null | 238 |
| `playerCtx` | derived `MediaPlayerContext?` from engine snapshot + sort pref | — | 243-259 |
| `socialTarget` | collected from `viewModel.activeSocialTarget` | null | 130 |
| `playlistState` | collected from `viewModel.playlistState` | — | 295 |

**Wiring details:**

- **Permission asks:** POST_NOTIFICATIONS requested on API 33+ at first composition
  (MainActivity.kt:133-149). Storage: on API 30+, `MANAGE_EXTERNAL_STORAGE`-style check through
  `Environment.isExternalStorageManager()` + redirect to `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
  with OEM fallback to app-info settings; on API 26-29 a `WRITE_EXTERNAL_STORAGE` runtime request.
  Dialog copy and buttons at MainActivity.kt:312-364.
- **Player open:** `openPlayer(task)` sets `viewModel.activePlayingTaskId.value = task.id`
  (MainActivity.kt:239-241). The `MediaPlayerContext` is rebuilt whenever the task snapshot changes:
  `filePath`, `episodeTitle`, and `queuePeerPaths = DownloadsSorter.playQueueFor(tasks, sortMode, task)`
  where `sortMode` comes from pref `pref_downloads_sort` (MainActivity.kt:243-259). `onPlayFile`
  re-points `activePlayingTaskId` to the task owning that path.
- **Share handling:** `ACTION_SEND` + `text/plain` only; URL extracted with
  `UrlExtractor.firstUrl` else raw text; routed to `viewModel.handlePastedInput` (MainActivity.kt:417-432).
- **Theme → system bars:** `applyEdgeToEdge` at cold start (MainActivity.kt:460-487);
  per-recomposition `SideEffect { syncSystemBars(isDark) }` (MainActivity.kt:127, 499-510);
  window background mirrored (MainActivity.kt:517-521). `resolveIsDark` (MainActivity.kt:445-450)
  maps "light"→false, "system"→device night mode, else→true.
- **Splash:** the overlay is composed LAST inside the theme block so it paints over everything —
  deliberately NOT an early-return gate, so HomeScreen composes and fetches behind it
  (MainActivity.kt:216-223, 366-389). Tap sink swallows touches while showing
  (MainActivity.kt:381).
- **Torrent picker host:** `TorrentFilePickerHost()` observes `TorrentFilePicker.requestState`
  and renders `TorrentFilePickerDialog` (MainActivity.kt:529-538).

**Strings (hardcoded):** "Storage access needed", "Grant", "Not now" + two body variants
(MainActivity.kt:319-360).

### 3.2 MainScaffold — tabs (bottom nav)

**File:** `ui/components/MainScaffold.kt` (341 lines).

**Signature** (MainScaffold.kt:84-93):

```kotlin
fun MainScaffold(
    viewModel: MainViewModel,
    initialTab: String,
    themeMode: String,
    onThemeChanged: (String) -> Unit,
    onOpenSocial: (String, String) -> Unit,
    onWriteTabPref: (String) -> Unit,
    onPlayTask: (DownloadTask) -> Unit
)
```

**Composition tree:**

```
Scaffold(containerColor = BackgroundDark, contentWindowInsets = WindowInsets(0.dp))   :126-141
 ├─ bottomBar = BottomNavBar(currentTab, viewModel, onTabSelected)                    :217-286
 │    └─ Surface(color = MaterialTheme.colorScheme.surface,
 │               border = 1dp BorderHairline, tonalElevation = 0)                     :223-230
 │         └─ NavigationBar(containerColor = surface, tonalElevation = 0)             :231-284
 │              ├─ NavigationBarItem "Search"    (Icons.Rounded.Search)               :235-252
 │              ├─ NavigationBarItem "Downloads" (icon = DownloadsBadge)              :253-265
 │              └─ NavigationBarItem "Settings"  (Icons.Rounded.Settings)             :266-283
 └─ content Box(padding(innerPadding))                                                :151-179
      ├─ TabPage(active = tab==SEARCH)    { HomeScreen(...) }                         :156-163
      ├─ TabPage(active = tab==DOWNLOADS) { DownloadsScreen(...) }                    :164-170
      └─ TabPage(active = tab==SETTINGS)  { SettingsScreen(...) }                     :171-178
```

**Behaviours:**
- `DownloadsBadge` (MainScaffold.kt:310-341) observes ONLY `engine.tasks`
  (`map { downloadsStats(it).badgeCount } .distinctUntilChanged()`), so progress ticks don't
  recompose. Shows `Badge` with `badgeCount` or "99+" (MainScaffold.kt:317-334).
- `navBarItemColors()` (MainScaffold.kt:288-303): selected icon/text = AccentPrimary, unselected =
  TextMuted; indicator = `0x1FFFFFFF` dark / `0x140F1115` light (read from `AnonTheme.colors.isDark`).
- `TabPage` (MainScaffold.kt:192-215) uses a raw tap-swallow `pointerInput/detectTapGestures` on the
  active layer to consume stray taps WITHOUT contributing click semantics (comment :183-191).

### 3.3 HomeScreen

**File:** `ui/screens/HomeScreen.kt` (2018 lines). Composable at :69-681; `@Suppress("UnusedParameter")`
because `onOpenDownloads`/`onOpenSettings` are unused (both are bottom tabs now) (HomeScreen.kt:68).

```kotlin
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenDownloads: () -> Unit,   // unused
    onOpenSettings: () -> Unit,    // unused
    onOpenSocial: (String, String) -> Unit
)
```

**Top-level layout:** root `Box` (background BackgroundDark, :151-155) containing a
`Column(statusBarsPadding, horizontal padding = Spacing.lg)` (:156-165) and the four full-page
overlays on top.

#### 3.3.1 Header (:166-207)
- 36dp `ic_anon_monogram` tinted TextPrimary, "ANONRODE" (22sp Black, letterSpacing 1sp),
  subtitle "Search · download · play" (caption, TextMuted). No top-right icon row (comment :203-206).

#### 3.3.2 Search bar (:211-361)
- 56dp circle-clipped `SurfaceCard` box, border = AccentPrimary when focused else BorderHairline.
- Leading Search icon 20dp; `BasicTextField` bound to `uiState.query`;
  **paste-jump rule:** when `uiState.query` is empty AND new text `UrlExtractor.isLikelyUrl`,
  it calls `viewModel.handlePastedInput(newValue)` and resets query to "" (:264-275).
  IME action Search → `keyboardController.hide(); viewModel.search()` (:287-291).
- Trailing when query non-blank: Clear IconButton (`onQueryChanged("")`) + 36dp accent circle
  ArrowForward → `search()` (:298-337). When blank: Paste Link IconButton reading the system
  clipboard via `LocalClipboardManager` → `handlePastedInput` (:338-359).
- Local state `isSearchFocused` (:212), `clipboardSnippet: String?` (:94).

#### 3.3.3 Smart clipboard banner (:363-403)
- Shown when `clipboardSnippet != null && uiState.query.isBlank()`. Registered by a
  `DisposableEffect(Unit)` clipboard listener gated on pref `pref_clipboard_detect` (:102-134).
  Seeds from current clipboard on composition (:117-121). Banner text "Paste link: <35 chars…>",
  trailing "Download"; tap → `handlePastedInput(snippet)` (:373-378).

#### 3.3.4 Filter chips (:79-92, :405-449)
- `filters` list literal at :79-92: `all, torrents, nkiri, 9jarocks, asianc, dramakey, anitaku,
  pluto, nepu, naijavault, naijaprey, dramarain` with display labels.
- Horizontally scrolling `FilterChip`s; tap → `viewModel.onFilterSelected(key)`.
- Selected colors differ per theme (dark: container `0xFFF3F5F9`, label `0xFF090B10`; light:
  container AccentPrimary, label white) (:417-443). End runway spacer 16dp (:448).

#### 3.3.5 Results region (:453-611) — the central branch
`visibleResults = VerdictPolicy.visibleOrdered(searchResults, verdicts, now).distinctBy { it.url }`
(:458-469). Branch order:
1. `isSearching && visibleResults.isEmpty()` → "Searching all sources…" + `SearchListSkeleton()`
   (:470-482).
2. `searchError != null && visibleResults.isEmpty()` → warning icon, error text, hint and a
   **Retry** TextButton calling `viewModel.search()` (:483-510).
3. `visibleResults.isEmpty() && query.trim().length >= 2 && !isSearching` → empty state: icon,
   "No matches for “<query>”", explanation, "Clear search" → `onQueryChanged("")` (:511-545).
4. `visibleResults.isEmpty()` (blank query) → **landing column** (verticalScroll) with
   `TrendingSection` → `AsianDramaHeroSection` → `CategoryTilesGrid` (:546-579).
5. else → `LazyColumn` of `ShowCardItem` keyed by url, `animateItem` springs (:580-611).

#### 3.3.6 TrendingSection (:713-865, private)
- Props: `items, isLoading, failed, showPosters, isLoadingMore, hasMore, onLoadMore, onRefresh,
  onRetry, onOpen`.
- Header row: LocalFireDepartment icon, "Trending Now", Refresh IconButton when items present and
  not loading (:766-780), "Retry" clickable text when failed&&empty&&!loading (:781-793).
- Body: skeleton (fixed `TRENDING_ROW_H = 240.dp`, :693) while loading+empty; "Trending is
  unavailable right now"/"Tap Retry to load it again"; else `LazyRow` (`key = site|url`) of
  `TrendingCard`, plus a trailing 60dp spinner item when `isLoadingMore` (:796-862).
- Infinite scroll: `derivedStateOf` — load more when last visible index ≥ total-3 (:729-743).

#### 3.3.7 TrendingCard (:867-943, private)
- 124dp wide; poster 2:3 with `tileColor()` background and hairline border; `PosterImageOrFallback`
  or `PosterFallbackWell` per `showPosters`; `PosterScrim()` bottom vignette (:698-711).
- Title sanitized with `TitleFormatting.cleanPosterTitle`; 2-line fixed title (Type.body fontSize,
  lineHeight 17sp, minLines 2); optional subtitle "YYYY · Season X" (Type.micro) (:877-941).

#### 3.3.8 CategoryTilesGrid (:987-1030, private)
- "Browse by Genre" header, then 2-column rows (`chunked(2)`, 10dp gaps) over
  `CategoryFeed.CATEGORIES + Category("All Genres")` where the last entry is the `ViewMoreTileCard`.
- `GenreTileCard` (:1102-1217): 80dp card, radius 14dp, 2-poster mini-stack (back 40×54 rotated 6°
  alpha .70; front 44×60 1dp border) from `GENRE_POSTER_PREVIEWS` (hardcoded TMDB URLs, :952-981);
  label uppercase 13.5sp ExtraBold + "Explore ▾". Tap → `onOpen(category)`.
- `ViewMoreTileCard` (:1033-1100): identical chrome, Explore icon in a 44dp circle, "ALL GENRES" /
  "Browse catalog ▾". Tap → `onMore()` → `viewModel.openCatalog()`.
- `genreIconFor` mapping (:1220-1230) used only as the fallback icon.

#### 3.3.9 CategoryPage (:1240-1408, private) — genre grid overlay
- Full-page `Column` in the SAME window (`BackgroundDark`, statusBarsPadding). Header: 48dp
  glassy back circle (`SurfaceElevated` + BorderHairline), title uppercase + subtitle
  "`N` titles, every source mixed" or "Latest <genre> posts", Refresh circle (:1273-1331).
- Body: centered spinner + "Loading <genre>..." / unavailable+Retry / `LazyVerticalGrid(3)` of
  `GridPosterCard` keyed by url with `animateItem` and a full-span loading-more row (:1332-1405).
- Infinite scroll threshold: last visible ≥ total-6 (:1258-1272).
- `GridPosterCard` (:1414-1486): same visual language as TrendingCard but cell-width, caption-size
  title.

#### 3.3.10 CatalogPage (:1495-1609, private) — "ALL GENRES" overlay
- Back circle + "ALL GENRES" title; `LazyColumn` over `CategoryFeed.CATEGORIES` — one horizontal
  `LazyRow` per genre (max 12 cards, `TrendingCard`), header row uppercase + "SEE ALL" tappable →
  `onOpenGenre`; each row triggers `LaunchedEffect(category.label) { onEnsureGenre(category) }`
  (lazy load, :1546) and shows `TrendingRowSkeleton` inside a fixed 240dp box while empty
  (:1571-1578).

#### 3.3.11 ShowCardItem (:1611-1711) — search result row; **public**
```kotlin
fun ShowCardItem(
    show: ShowCard, showPosters: Boolean, onClick: () -> Unit,
    verifiedCaption: String? = null, modifier: Modifier = Modifier
)
```
Row: card (`SurfaceCard`, 1dp BorderHairline, radius Radius.lg) → `PosterTile` 74×106
(:1713-1739) + text column: sanitized title (16sp SemiBold, 2 lines), `specLine(show)`
(category · year or "Direct", :1793-1801), badge row — verified caption replaces the hardcoded
`secondaryBadge` when present (:1666-1675; `secondaryBadge` :1803-1807 returns "SUB · DUB" for
Anime, null for torrents, else "✓ 1080p") — bottom row `leftMetric` (:1809-1815) +
green dot + `rightMetric` (:1817-1824).

#### 3.3.12 PosterScrim (:698-711) and tile colours
`tileColor(title)` hashes the title into a 6-colour palette: dark `TILE_COLORS_DARK`
(3A1C1C/2A2A10/20303A/241A33/14301F/33231A) or light `TILE_COLORS_LIGHT`
(F3E3E3/F1EFDC/DDE9F0/E7E0F2/DDEBE1/F2E5DC) (:1777-1791).
`AsianDramaExplorer.kt` carries its own duplicate copies of these palettes (:387-401) — see gap G-5.

#### 3.3.13 AsianDramaHeroSection (:1826-2017, private)
- Header "ASIAN DRAMAS" + "Modern & Historical".
- Two 96dp hero cards (K-DRAMA / C-DRAMA) side by side; each: hardcoded TMDB backdrop image at
  0.32/0.22 alpha (:1873-1878, :1954-1959), horizontal gradient overlay (violet/pink tinted
  per theme), "K-DRAMA"/"C-DRAMA" + "HUB" micro-pill, "Modern · Sageuk" / "Modern · Costume" meta,
  "Explore catalog →". Taps → `openAsianDramaHub(KDRAMA|CDRAMA)` (:571-572).

#### 3.3.14 Overlay stacking order inside HomeScreen (bottom→top)
CategoryPage (:620) → AsianDramaExplorer (:635) → CatalogPage (:656, suppressed while another
overlay is open) → EpisodeDrawer (:667-674). Comment at :614-619 explains why the drawer is stacked
last (tapping a card in a grid opens the drawer above).

**VM wiring of the whole screen:** `uiState` collect (:75); `handlePastedInput` (:268, 345, 375);
`onQueryChanged` (:271, 300, 542); `search` (:289, 319, 506); `onFilterSelected` (:420);
`openEpisodeDrawer` (:567, 592, 632, 653, 663); `loadMoreTrending`/`loadTrending` (:564-566);
`openAsianDramaHub` (:571-572); `openCategory`/`openCatalog` (:576-577, 630-632, 661);
drama selectors + `refreshDramaFeed` (:647-652); `ensureCategoryCards` (:662); `closeCategory`,
`closeCatalog`, `closeAsianDramaHub`, `closeEpisodeDrawer` (:630, 660, 648, 672);
`viewModel.engine.showPostersInResults` read directly at :561, 588, 626, 644, 659.

### 3.4 DownloadsScreen

**File:** `ui/screens/DownloadsScreen.kt` (2620 lines). Signature at :183-188:

```kotlin
fun DownloadsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,                 // -> Search tab (MainScaffold.kt:167)
    onPlayTask: (DownloadTask) -> Unit  // hands the task to MainActivity's player
)
```

#### 3.4.1 Data pipeline inside the screen (structure → groups → live objects)

1. `tasks` = `viewModel.engine.tasks.collectAsState()` (:189).
2. `uiState` = `viewModel.uiState` for storage numbers (:196); ON_RESUME observer calls
   `viewModel.refreshStorageInfo()` (:206-215).
3. `initialSort` read from pref `pref_downloads_sort` (validated against `DownloadsSorter.ALL_MODES`)
   (:221-225); `sortMode` local state (:226).
4. `statusFilter` `rememberSaveable` default `FILTER_ALL` (:228), constants :154-160.
5. `structureSig` = `id:status|…` string, **the key that keeps sorting off the progress-tick path**
   (:254-256).
6. `shelfItems` = tasks where `DownloadsSorter.isShelfItem` (COMPLETED && showTitle ==
   "Direct Downloads") (:263).
7. `groups` = remembered on `(structureSig, sortMode)`: snapshot minus shelf → age overrides seeded
   from `createdAt` (fallback: list index) → `DownloadsSorter.sortDownloads`; in DATE mode an
   "ACTIVE" section is prepended for DOWNLOADING/RESOLVING/VALIDATING (:265-309).
8. `liveGroups` re-maps ids onto the freshest task objects every composition (:317-320).
9. `filteredGroups` applies `matchesStatusFilter` (:325-329); `visibleShelf` only under All/Done
   (:330-332).
10. Header stats `stats` (`downloadsStats`), `pausedCount`, `filterCounts` (:336-349).
11. Track-4 `seriesRemaining` / `seriesInfo` computed from tasks with non-blank `seriesUrl`
    (:358-375).

#### 3.4.2 Header (:425-466)
- 38dp glass back circle (GlassBackScrim 0xB8000000 + GlassBackOutline 0x17FFFFFF) → `onBack`.
- Title "DOWNLOADS & MEDIA" (`Type.screenTitle`), below it the annotated `summaryLine`:
  storage part "U / T GB used · F GB free" (omitted while `totalStorageGb == 0`) then
  "N active · N done · N failed" each count+word colored via `LiveWord/DoneWord/FailedWord`
  (:380-406).

#### 3.4.3 Storage-low banner (:468-498)
Shown when `totalStorageGb > 0 && (free < 2GB || usedRatio >= 0.95)`; amber Surface,
"⚠️" + "Storage Low (N GB free). Downloads may pause if space runs out." (hardcoded literal colors
`0x26F59E0B`/`0x66F59E0B`/`0xFFFBBF24`).

#### 3.4.4 Filter chip row (:500-510, composable :819-873)
`LazyRow` of `FilterChip` for ALL_FILTERS with live counts: All / Active / Paused / Done / Failed
(labels map at :826-832). Selected = AccentPrimary fill + BackgroundDark label.

#### 3.4.5 Actions rail (:512-547)
- "Pause all" pill (`Icons.Rounded.Pause`) shown when `hasActive` → `viewModel.engine.pauseAll()`
  (:520-528).
- "Resume all" pill shown when `hasPaused` → `engine.resumeAll()` (:529-537).
- Right-aligned `DownloadsSortMenu` → sets `sortMode` + writes `pref_downloads_sort`
  (:539-546). The menu itself (:878-942): "Sort: Date ▾" row + `DropdownMenu` with labels
  Date/By Show/Status/Size (:883-888).

`ToolbarPill` (:946-978): 30dp, pill, SurfaceElevated + hairline, 13dp icon, 11sp/600 label.

#### 3.4.6 Empty states
- `tasks.isEmpty()` → DownloadDone icon, "No active or completed downloads" and a
  "Find something to download" button → `onBack()` (:549-569).
- Filter narrowed everything out → FilterList icon, "No <filter> downloads", "Show all" →
  reset to `FILTER_ALL` (:570-594).

#### 3.4.7 list contents (:595-694)
- **Library mode**: `ShowFolderCard` per show group keyed `show-<title>`; expansion default:
  a show is expanded when it has 1 item or any DOWNLOADING/RESOLVING item; user toggles stored in
  `expandedShows` state map (:596-627).
- **Other modes**: per group a `DownloadsGroupHeader` item and `ActivityRow` items keyed by task id
  (:629-661). Group header may carry an inline "Download remaining N" action only when the group's
  remaining pickups belong to exactly one show (:636-649).
- **Social & Media shelf** (:664-692): one `LazyRow` of `SocialShelfTile` (166×94) + mono footnote
  "Hold a tile to share or delete · N items on this device".

#### 3.4.8 Row components
- `DownloadsGroupHeader` (:1006-1091): custom `Layout` — title uppercase 11sp ExtraBold TextMuted,
  " · N" count colored by `groupCountColor(header, chroma)` (:994-999: PAUSED/Failed/DONE else live),
  1dp hairline rule flexing the remaining width. `groupCountColor(header)` (legacy single-arg,
  :984-989) is kept for the JVM test.
- `ActivityRow` (:1215-1468): `Card` radius 12dp (`SurfaceCard` + BorderHairline).
  - Left: 52×72 poster (`PosterImageOrFallback` with `effectivePoster = posterUrl ?: videoThumb`),
    SxxExx stamp bottom-start when the title carries one and the task is not social/movie
    (:1236-1254, :1308-1332). Video-frame thumbnail extraction (`rememberVideoThumbnail`,
    :1127-1160) uses `MediaMetadataRetriever` at 1s into files ≥50KB, caching
    `cacheDir/social_thumbs/<taskId>.jpg`.
  - Center (3 lines): title (`TitleFormatting.extractSeasonEpisodeTag` + `stripTrailingYtDlpId`,
    :1236-1242), Line 2 = `SiteTagChip` + status dot/glyph + status word, Line 3 =
    `cardProgressMetrics` or the humanized failure (:1334-1401).
  - Right: state-dependent actions — Active: Pause + Kebab; Paused: Play(resume→`onRetry`) + Kebab;
    Done: Play + Kebab; Failed: `RetryCircleButton` (filled rust, "RETRY" text) + Kebab; else Kebab
    only (:1404-1429).
  - Whole-card click: enabled only for Done/Failed (`onClickLabel` "Play"/"Retry") (:1287-1295).
  - Bottom flush 3dp `LinearProgressIndicator` while active/paused; indeterminate when active and
    total unknown; semantics `progressBarRangeInfo` + contentDescription "Download progress" /
    "Download in progress" (:1268-1279, :1432-1465).
- `SolidActionButton` (:1474-1492): 36dp contrast-locked circle (dark `#F3F5F9` bg/`#090B10` icon,
  light `#0F172A`/white).
- `StatusDot` (:1521-1543): 6dp; pulsing when live.
- `SiteTagChip` (:1549-1572): 10sp/700 with 0.8sp tracking; tinted by `DownloadsChroma`.
- `KebabButton` (:1585-1602): 32dp "⋮" with `contentDescription = "More actions"`.
- `SocialShelfTile` (:1840-1910): tap = play, **long-press = actions dialog** via
  `detectTapGestures` (:1854-1862); meta line `shelfMetaText` (:1914-1920) = extension · resolution
  or quality · size.
- `ShowFolderCard` (:1922-2071): card; header row (folder/download icon, title, "N eps ·
  N done · N active · size", chevron button with "Collapse"/"Expand"); optional inline remaining
  action; expanded body = hairline divider + stacked `ActivityRow`s.
- `DownloadCard` (:2116-2552): **LEGACY v3 card, NOT rendered by this screen** — kept byte-identical
  as the contract surface for `DownloadCardStateTest` (comment :2073-2078). Same for helper
  `BulkActionChip` (:2080-2114) and `StatusBadge` (:2554-2574), which are public but have no
  call sites in the live screen (see gap G-3).

#### 3.4.9 Dialogs
- **Actions dialog** (:704-775): triggered by `pendingActionsTask` (kebab tap / shelf long-press).
  - FAILED → title "Retry or remove?"; body includes `humanizeFailure(errorMessage)`; buttons
    Retry (→ `engine.retry`) / Remove (→ `engine.cancel`) / dismiss "Keep it".
  - COMPLETED → "Share or delete?"; Share (→ `shareMedia(context, filePath)`) / Delete (→
    `pendingDeleteTask = target`, gated) / "Keep it".
  - else → "Cancel this download?"; Remove (→ `engine.cancel`) / "Keep it".
- **Delete confirm** (:779-812): "Delete downloaded file?" body: "will be removed from storage.
  This cannot be undone." Confirm fires `hapticView.rejectHaptic()` then `engine.cancel(task.id)`;
  dismiss "Keep file".

#### 3.4.10 Status vocabulary and formatting helpers (pure, internal, unit-tested)
- `DownloadsChroma` (:116-128) and `downloadsChroma(isDark)` (:130-152). Dark: live `0xFF38BDF8`,
  done `0xFF10B981`, paused `0xFFF59E0B`, failed `0xFFEF4444`. Light: `LiveWord 0xFF0E7490`,
  `DoneWord 0xFF1F7A4D`, `PausedWord 0xFF92610F`, `FailedWord 0xFFA84A42` (constants :86-97).
- `DownloadStateKind` / `DownloadStatusStyle` / `downloadStatusStyle(status, chroma)` (:1613-1660):
  word is ALWAYS colour + word + glyph ("Downloading ●", "Done ✓", "Paused ❚❚", "Failed ✕",
  "Queued ○", "Checking ●").
- `formatBytesSpan` (:1665-1670) — never emits "/ 0 B".
- `cardProgressMetrics` (:1162-1209) — the Line-3 copy actually rendered by `ActivityRow`.
- `cardStatusDetail` (:1680-1723) and `statusDetailText` (:1729-1777) — pure per-status detail
  strings; the latter has a JVM test; **neither is called by the live screen** (gap G-3).
- `humanizeFailure` (:1801-1828) — regex scrub of resolver class names, locker steps, engine class
  names, file frames; URLs collapse to host; "crack/cracked/cracking" → resolve/…; whitespace
  collapse and trailing punctuation trim.
- `shareMedia(context, filePath)` (:2576-2595): FileProvider URI + `ACTION_SEND` chooser; MIME by
  extension (audio/*, video/*, application/zip, */*).
- `formatBytes` (:2597-2608) and `formatEta` (:2610-2620): "%.2f GB"/"%.1f MB"/"%.1f KB"/"N B";
  "HH:MM:SS left"/"MM:SS left"/"Ns left". Both public.

**VM wiring:** `engine.tasks` (:189), `uiState` (:196), `refreshStorageInfo` (:209),
`engine.pauseAll`/`resumeAll` (:526, 535), `engine.pause/retry/cancel` (:622-624, 655-657),
`openRemainingEpisodes` (:618, 646), `onPlayTask` (:621, 654, 677).

### 3.5 EpisodeDrawer

**File:** `ui/screens/EpisodeDrawer.kt` (1937 lines). Composable at :145-1250.

```kotlin
fun EpisodeDrawer(
    show: ShowCard,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    preselectRemaining: Boolean = false
)
```

Positioned by a **math contract** documented at :105-144 (HALF = 0.60 × H sheet; FULL = top 0;
drag commit > 0.12 × H or velocity > ~1000 dp/s). All drawer colors come from a pinned
`SheetPalette` (dark/light instances :1856-1900), never from theme tokens mid-layout.

#### 3.5.1 State

| State | Type / source | Line |
|---|---|---|
| `pal` | `sheetPalette(AnonTheme.colors.isDark)` | 165 |
| `cachedEpisodeLists` | process-wide `ConcurrentHashMap<String, CachedEpisodeList>` (30-min TTL) | 174-198, 1927-1934 |
| `episodes` | live `uiState.drawerEpisodes` else cached hit | 190 |
| `isLoading` | `uiState.isEpisodesLoading && episodes.isEmpty()` | 191 |
| `showSynopsis` | `uiState.drawerSynopsis` else cache | 192-198 |
| `selectedUrlsList` | `rememberSaveable(key = show.url)` `List<String>` | 208 |
| `rangeText` | rememberSaveable String | 212 |
| `enqueued`, `isEnqueuing` | remember booleans (double-tap guards) | 213-214 |
| `showBatchConfirmation`, `pendingForceAll` | rememberSaveable booleans | 215, 219 |
| `allTasks` | `engine.tasks.collectAsState()` | 221 |
| `downloadedOrQueuedUrls` | task sourceUrls matched by show title | 222-228 |
| `remainingEpisodes` | episodes minus downloaded/queued | 229-231 |
| `autoApplied` | one-shot preselect-remaining guard | 236-242 |
| `seasonGroups` | `Map<Int, List<EpisodeItem>>` parsed from "Sxx"/URL/`episodeNum/100` | 282-293 |
| `expandedSeasons` | set of season numbers (all expanded initially) | 294-296 |
| sheet position `anim` | `Animatable(Hpx)` top-edge px, springs to `halfTopPx` | 403, 481-483 |
| `dragAccumPx`/`nestedAccumPx` | drag accumulators for the 0.12×H rule | 408-409 |

#### 3.5.2 Chassis and gestures (:356-556)
- Host is a `Dialog(usePlatformDefaultWidth=false, decorFitsSystemWindows=false)` (:357-364);
  forces light status-bar icons on the dialog window (:371-380).
- Scrim = Box drawing black at `0.32 * (1 - anim/H)`; tap dismisses (:616-624).
- Sheet = full-H Box offset by `anim.value` lambda (:627-640); tap on empty sheet area is swallowed.
- Drag surfaces: the grip zone (:689-708) and the shared header row (:710-831) carry
  `Modifier.sheetDrag()` (:550-555).
- `NestedScrollConnection` (:496-546): list-at-top + scroll-down collapses the sheet
  (rubber-bands up to `halfTopPx + commitDrag`); scroll-up expands before the list scrolls.
- `commitAfterRelease` (:452-477) implements the detent contract; nearest-detent fallback :443-444.

#### 3.5.3 Geometry constants (:568-594)
`gutter 16`, `gripZoneH 30`, poster 76×108 top at `artTop + 14/8`, title column offset y=16,
band height `posterTop - artTop + posterH + 14` with 48dp bottom fade, `listTop` 176 FULL / 166 HALF,
sheet top corners 24dp.

#### 3.5.4 Art band + header (:641-831)
- Band: charcoal gradient `#3A4254 → #1B2029`, optional poster `AsyncImage` blurred 24dp with
  memory-cache-key reuse, then a black 45%→`0xEB0B0E14` scrim (:649-687).
- Header row: poster card 76×108 radius 10, 1dp `0x1AFFFFFF` border, shadow 18dp black-62%;
  fallback = 2-initial glyph in `BandAccent` (:720-755).
- Right column: badge line (`site uppercase`, `MOVIE` or `N EPS` + `N SEASONS`) in pill pills
  (:765-794), title 18sp Bold `#F4F4F6` 2 lines (:797-805).
- Close: 38dp glass circle (black-72% + white-9% hairline) → `onDismiss` (:811-830).

#### 3.5.5 Content region (:833-1216)
- Loading → `DrawerLoadingState(stageText)` (:844-846); stage strings rotate after 4s:
  "Step 1/2 · Finding episode pages…" → "Step 2/2 · Unlocking stream links…" (:600-608, 1936-1937).
- Empty → `DrawerFailureState(reason = episodesError ?: "No stream links found", failedFetch =
  error startsWith "Failed to load", onRetry = viewModel.openEpisodeDrawer(show, preselectRemaining))`
  (:847-859).
- Content column:
  - Synopsis container (3 lines collapsed; "More ▾"/"Less ▴" when >90 chars; tap toggles)
    (:864-892).
  - Selection rail (`if (!isSingleMovie)`): horizontally scrolling `DrawerChip`s — "All (N)",
    "Remaining (N)" (when some downloaded/queued), per-season "S<n> (N)", "Range" (only >6 eps),
    "Clear (N)" (:894-987).
  - Range field (`OutlinedTextField`, 42dp) visible when Range toggled and >6 eps; every keystroke
    runs `applyRange` (grammar: "all"/"*", "none"/"clear"/"deselect", comma/space/semicolon lists,
    "a-b" ranges; malformed input never wipes the selection) (:989-1024, 245-279).
  - Pinned CTA bar: "Selected N episode(s) · size" text + `Button` whose label is
    "⬇ Download Movie" / "⬇ Download All (N)" / "⬇ Download Selected (N)"; tap logic: force-all >
    10 items → batch confirm dialog, else enqueue; shows "Queueing..." spinner while enqueuing
    (:1027-1101).
  - Episode `LazyColumn` (nestedScroll, bottom padding 24 + nav inset): season slabs (uppercase
    label + hairline rule, tap collapses/expands, :1121-1152) and `EpisodeRow`s keyed
    `season-$season-${ep.url}|$i` (:1153-1211).
- Batch confirmation `AlertDialog` (:1219-1246): title "Queue N episodes?", body warns about data,
  confirm → `enqueueSelected(forceAll = pendingForceAll)`, dismiss "Cancel".

#### 3.5.6 Enqueue logic
`enqueueSelected(forceAll)` (:299-354): dedupes by URL, sorts by (season, episodeNum), haptic
`confirmHaptic()`, runs on `viewModel.viewModelScope + Dispatchers.Default`, per episode calls
`engine.enqueue(showTitle = NameSanitizer.cleanShowFolder(show.title), episodeTitle =
NameSanitizer.formatEpisodeTitle(...), sourceUrl, mirrorUrls, backend="aria2c", site,
parallelSockets = engine.parallelSocketsPerFile, verifiedDirectUrl =
ResultVerifier.verifiedDirect(ep.url), seriesUrl = show.url, seriesTotal = episodes.size,
posterUrl = show.posterUrl)`, then `onDismiss()` on Main. On failure: toast "Could not queue
downloads. Try again." and flags reset (:346-352). Empty selection → toast
"Select at least one episode first" (:314-317).

#### 3.5.7 EpisodeRow (:1269-1517) — **public**
```kotlin
fun EpisodeRow(
    episode: EpisodeItem, isSelected: Boolean, onToggle: () -> Unit,
    isDownloaded: Boolean = false, onDownloadSingle: () -> Unit,
    displayTitle: String = episode.title, groupIndex: Int = 0, groupSize: Int = 1,
    groupColor: Color = Color(0xFF141419)
)
```
- Grouped-inset shape via `groupedRowShape(index, count, radius=14dp)` (:1912-1919); 0.5dp hairline
  divider above rows with `groupIndex > 0` (:1303-1311).
- Row min-height 50dp; left monospace `%02d` episode number in a 28dp column; title 14sp Medium
  with an inline emerald "✓ Downloaded" pill when downloaded (row content dims to 0.78 alpha);
  subtitle derived from `qualityInText`/`codecInText` regexes + `episode.sizeText` joined " · "
  (:1319-1388, 1769-1770).
- Info circle 24dp "i" toggles the details panel (`rememberSaveable` so recycling doesn't collapse
  it) (:1289, 1390-1406).
- 34dp circular download button → `onDownloadSingle` (:1408-1428) and a 22dp checkbox indicator →
  `onToggle` (:1430-1453).
- Expanded panel: `PreviewChip(label="locker", lockerPreview(url).first)` and
  `PreviewChip(label="file", last 34 chars)` (:1456-1471); "Site-reported size" line;
  `FilledTonalButton` "Queue this episode" (:1485-1505) + hint "Queue now, or check this link on a
  browser first."
- `lockerPreview(url)` (:1775-1788) is network-free URI parsing (host + decoded last path segment).

#### 3.5.8 Loading/failure/chips
- `DrawerLoadingState` (:1525-1605): 12dp spinner + stage text + 5dp sweeping accent track
  (34% segment, read in draw phase; respects reduce-motion) + ONE inset container with 5 skeleton
  rows (`DrawerSkeletonRow`, :1608-1643).
- `DrawerFailureState` (:1649-1703): card radius 16 with rust headline ("Couldn't reach the episode
  locker." if failed-fetch else "No playable episodes found.") + reason + RETRY pill.
- `DrawerChip` (:1708-1738): pill; selected = `pal.textPrimary` fill + `pal.chipInk` label.
- `sumSelectedSizeLabel` (:1811-1827) and `sizeTextToBytes` (:1793-1806): pure size parsing
  (GB/MB/KB, GiB/MiB/KiB); returns null when no sizes exist.

**VM wiring:** `uiState` (:157, 174-198, 848), `viewModel.viewModelScope` + `engine.enqueue`
(:321-344, 1174-1192), `engine.parallelSocketsPerFile` (:338, 1187),
`ResultVerifier.verifiedDirect` (:339, 1188), `viewModel.openEpisodeDrawer(show, preselectRemaining)`
for retry (:856).

### 3.6 SettingsScreen (SettingsSheet.kt)

**File:** `ui/screens/SettingsSheet.kt` (1759 lines). NOTE: the FILE is named `SettingsSheet.kt` but
the composable is `SettingsScreen` — there is no sheet anymore; it is a full tab page with an
internal hub/drawer navigation.

```kotlin
fun SettingsScreen(
    viewModel: MainViewModel,
    themeMode: String,
    onThemeChanged: (String) -> Unit,
    onBack: () -> Unit
)
```

#### 3.6.1 Host structure (:242-423)
- Collects `uiState` (:249) and `DynamicRulesManager.version` (:252).
- `state = rememberSettingsState(viewModel, themeMode)` (:254, factory :209-222) — a
  `SettingsState` holder (:81-181) with one `mutableStateOf` per setting, initialized from the
  ENGINE fields, plus `persist()` (:153-180) which snapshots and calls `viewModel.saveSettings(...)`.
- `LaunchedEffect(themeMode) { state.themeMode = themeMode }` (:259) — theme flows in without
  recreating the state (comment :214-220).
- Drawer navigation: `openDrawerName` `rememberSaveable` (:264); `BackHandler` closes drawer (:268);
  `hubGutter` 24dp hub / 16dp drawer (:273).
- Lifecycle observer (:284-297): ON_PAUSE → `viewModel.flushPendingSettings()`;
  ON_RESUME → `viewModel.refreshStorageInfo()`; onDispose also flushes.
- Scaffold(containerColor BackgroundDark, `WindowInsets(0.dp)`) → header Row (back arrow changes
  meaning: "Back to Settings" / "Back to Search"; title = drawer label uppercase or "SETTINGS")
  (:299-334).
- `AnimatedContent(targetState = currentDrawer)` (:336-420) with the slide/fade spec (:338-354).
- Per page: outer bounded Column → `weight(1f)` scroll lane (`verticalScroll`,
  `Arrangement.spacedBy(12.dp)`) → `SettingsPinnedFooter(uiState)` (:368-419, footer :659-671:
  "Anonrode v<BuildConfig.VERSION_NAME> · F GB free of T GB", centered, Type.caption, TextMuted).
  Version comes from `app/build.gradle.kts` `versionName = "3.2.11"` (build.gradle.kts:17).

#### 3.6.2 The five drawers
`SettingsDrawer` enum (:232-238):

| Drawer | Label | Subtitle |
|---|---|---|
| PLAYBACK | "Playback" | "Quality · subtitles · player defaults" |
| DOWNLOADS | "Downloads" | "Parallelism · organize · limits" |
| CONNECTIONS | "Connections" | "Stall · retries · torrents · Wi-Fi" |
| UPDATES | "Updates & Reliability" | "Site logic · yt-dlp core · reporting" |
| LOGS | "Privacy & Logs" | "Content filter · logging · diagnostics" |

Hub (:546-636): `SettingsThemeControl` + one 64dp entry card per drawer (icon in a tinted 40dp
box; colors: PLAYBACK AccentViolet, DOWNLOADS StatusSuccess, CONNECTIONS AccentPrimary,
UPDATES StatusWarning, LOGS StatusError) with `pressScale` + chevron.
`SettingsThemeControl` (:428-538): "App Theme" row with Dark/Light/Auto tri-cell
(`selectable(role = RadioButton)`); tapping sets `state.themeMode` and calls `onThemeChanged(mode)`
(:508-511).

#### 3.6.3 Playback drawer — `SettingsPlaybackSection` (:676-821)

| UI | Control | Backing key(s) | Change handler |
|---|---|---|---|
| "Preferred Stream Resolution" 480p/720p/1080p | FilterChip row | `pref_default_quality` via `state.quality` | `state.quality = q; state.persist()` (:703-719) |
| "Download Subtitles" | `SettingsSwitchRow` | `state.downloadSubs` → `pref_download_subtitles` | `persist()` (:726-732) |
| "Subtitle Language" chips | FilterChip row over `SUBTITLE_LANGS` (:1172-1176: en/es/fr/pt/ar/hi/zh/ja/ko/all) | `pref_subtitle_lang` | `persist()` (:736-769) |
| "Default Playback Speed" 0.75×/1×/1.25×/1.5× | FilterChip row | MediaPlayerPrefs `pref_playback_speed` (separate store) | local `playbackSpeed` + `MediaPlayerPrefs.setPlaybackSpeed` (:682, 788-805) |
| "Allow 150% Volume Boost" | `SettingsSwitchRow` | MediaPlayerPrefs `pref_allow_volume_boost` | `MediaPlayerPrefs.setAllowVolumeBoost` (:809-820) |

#### 3.6.4 Downloads drawer — `SettingsDownloadsSection` (:972-1129)

| UI | Control | Backing key | Range/steps | Handler |
|---|---|---|---|---|
| Max Concurrent Downloads | Slider | `state.maxConcurrent` → `pref_max_downloads` | 1..5, steps 3 | `onValueChangeFinished → persist()` (:993-1005) |
| Global Speed Limit | Slider | `pref_speed_limit_kbs` (0 = ∞) | 0..50000, steps 19 | persist (:1026-1038) |
| Storage Protection Guard | Slider | `pref_storage_guard` (float GB) | 0.5..5.0, steps 9 | persist (:1060-1072) |
| Auto-Organize Folders | Switch row | `pref_auto_organize` | — | persist (:1078-1084) |
| Instant Social Download | Switch row | `pref_instant_social` | — | persist (:1088-1094) |
| Show Posters in Results | Switch row | `pref_show_posters` | — | persist (:1098-1107) |
| Auto-Detect Clipboard Links | Switch row | `pref_clipboard_detect` | — | persist (:1111-1117) |
| Completion Notifications | Switch row | `pref_completion_notifications` | — | persist (:1121-1127) |

#### 3.6.5 Connections drawer — `SettingsConnectionsSection` (:1320-1502)

| UI | Control | Backing key | Range/steps |
|---|---|---|---|
| Peer Connections | Slider (0 = Auto/-1) | `pref_torrent_peers` | 0..500 steps 9 (:1340-1352) |
| Torrent Privacy Mode | Switch row | `pref_torrent_privacy_mode` | — (:1357-1363) |
| Stall Timeout | Slider | `pref_stall_timeout` | 15..300 steps 18 (:1384-1396) |
| Download Retry Count — "Magnet retries" | Slider | `pref_magnet_retries` | 1..10 steps 8 (:1419-1431) |
| Download Retry Count — "yt-dlp retries" | Slider | `pref_ytdlp_retries` | 1..10 steps 8 (:1433-1445) |
| HLS Fragment Concurrency | Slider | `pref_hls_fragments` | 1..16 steps 14 (:1467-1479) |
| Download Torrents Only on Wi-Fi | Switch row | `pref_torrents_wifi_only` | — (:1484-1490) |
| Download Only on Wi-Fi (All) | Switch row | `pref_wifi_only_all` | — (:1494-1500) |

#### 3.6.6 Privacy & Logs drawer — `SettingsLogsSection` (:1134-1161) + Diagnostics (:1508-1642)

| UI | Control | Backing key | Handler |
|---|---|---|---|
| Filter Explicit Content | Switch row | `pref_filter_explicit_content` | `persist()` (:1138-1147); VM also prunes painted feeds (:1243-1256 in MainViewModel) |
| Debug Logging | Switch row | `pref_debug_logging` | persist (:1151-1157) |
| Keep Activity Logs | Slider | `pref_log_retention_days` | 1..30 steps 29, coerced 1..30 (:1530-1542) |
| Share Activity Log | Row (clickable) | — | builds an up-to-8MB, token-redacted log + crash-report share file in cacheDir, shares via FileProvider chooser; toast "No activity log yet" if empty / "Share failed: …" on exception (:1547-1641) |

#### 3.6.7 Updates & Reliability drawer — `SettingsUpdatesSection` (:1182-1311)

- Embeds `SettingsSelfHealingSection` (:823-967):
  - "Sync Scraper & Site Logic" row with subtitle "Version: $rulesVersion" and a "Sync Now"
    FilledTonalButton → `DynamicRulesManager.syncFromGitHub(context)`, spinner while
    `state.isSyncingRules`, toasts on result (:832-870).
  - "Core channel" master/Nightly vs stable pills → `YtDlpCore.setChannel` (:880-919).
  - "yt-dlp Core Engine" row, subtitle "Installed version: X • IG/TikTok/FB fixes", "Update Core"
    button → `YtDlpCore.updateNow(context)`, toast `outcome.message`, updates `state.ytdlpVersion`
    (:922-965). `LaunchedEffect(Unit) { state.ytdlpVersion = YtDlpCore.currentVersion(context) }`
    (:881-883).
- "Send Fatal Error Reports" switch — `RemoteLogger.isEnabled/setEnabled`
  (local `remoteReporting` state, `rememberSaveable`, :1189-1210). This is the ONLY remote-reporting
  kill switch in the app.
- "Check for Updates" action row — `UpdateUiState` (:66-72: Idle/Checking/Available(latestTag,url)/
  UpToDate/Error); tap "Check"/"Retry" → `UpdateChecker.check()`; "Open →" →
  `UpdateChecker.openInBrowser(url)` (:1217-1288). Never runs at start.
- "View Releases on GitHub" → `UpdateChecker.openInBrowser(UpdateChecker.RELEASES_PAGE)` (:1292-1309).

#### 3.6.8 Shared row primitives (public, reusable)
- `SettingsCategoryHeader(title)` (:1644-1658): uppercase 11sp TextMuted with `heading()` semantics.
- `SettingsCard(content)` (:1660-1670): SurfaceCard card, radius lg, hairline border.
- `SettingsSwitchRow(icon, title, subtitle, checked, onCheckedChange)` (:1672-1721): whole row
  `toggleable(role = Switch)`; Switch colors checked thumb AccentPrimary, track
  AccentPrimary 35% alpha.
- `SettingsActionRow(icon, title, subtitle, action)` (:1723-1748).
- `SettingsSliderThumb()` (:1750-1759): 18dp accent circle with 2dp SurfaceCard border.

**Debounce path:** `SettingsState.persist()` → `viewModel.saveSettings(...)` stores a
`SettingsSnapshot` and schedules a 500ms flush (MainViewModel.kt:1257-1290); explicit flushes happen
on ON_PAUSE and dispose (SettingsSheet.kt:286-296).

### 3.7 QuickShareActivity + QuickShareCard

**File:** `ui/screens/QuickShareActivity.kt` (685 lines). Activity declared in the manifest as
`.ui.screens.QuickShareActivity`, exported, `singleInstance`, `excludeFromRecents`, theme
`Theme.AnonDownloader.Dialog`, `ACTION_SEND` + `text/plain` (AndroidManifest.xml:43-54).

#### 3.7.1 Activity flow (`onCreate` :127-210)
1. `extractSharedUrl(intent)` (:221-231): EXTRA_TEXT → clipData → dataString; first
   `https?://[^\s"'<>]+` match wins, else raw text trimmed.
2. Blank → toast "No valid link found in share" + finish (:131-135).
3. `UrlRouter.parse`; a `ParsedUrl.SearchQuery` (no URL in shared text) → toast
   "Shared text contains no link — copy the URL (starting with http) and share again" + finish
   (:137-151).
4. Read `pref_instant_social`; if true → `handleInstantDownload` + finish, NO UI (:152-159).
5. Else setContent with `AnonDownloaderTheme(themeMode = pref_theme_mode)`:
   50% black scrim Box aligned BottomCenter, tap-outside → finish; `QuickShareCard` docked at
   the bottom (:163-209).
6. `onDownload` result false (storage permission missing) keeps the sheet open and re-arms the
   double-tap guard (:183-205, 653-664).

#### 3.7.2 Permission gates (:60-125)
- API 30+: `Environment.isExternalStorageManager()` else toast "Enable 'All files access' for
  Anonrode in Settings" + deep link (OEM fallback to app info).
- API 26-29: `WRITE_EXTERNAL_STORAGE` runtime request; granted → "Storage permission granted —
  tap Download again", denied → "Storage permission required to download".

#### 3.7.3 Instant mode (:233-248, :250-323)
`handleInstantDownload` reuses the engine's default quality (engine.defaultQuality), dispatches,
and toasts "<label> download started in background" where the label is the platform for social,
"Drama" for drama URLs, "Media" otherwise.
`dispatchDownload` chooses backend: override if not "auto" else SocialUrl→"yt-dlp",
Magnet/Direct/Drama→"aria2c"; then `engine.enqueue(...)` per URL type with
`showTitle = Social/<platform> | Torrents | Direct Downloads | parsed.showCard.title | Shared Media`
(:261-322).

#### 3.7.4 QuickShareCard (:337-684) — public, reused design language

```kotlin
fun QuickShareCard(
    parsedUrl: ParsedUrl, rawUrl: String, defaultQuality: String = "720p",
    onDismiss: () -> Unit,
    onDownload: (quality: String, audioOnly: Boolean, makeInstant: Boolean, engineOverride: String) -> Boolean
)
```
Internal state: `audioOnly`, `selectedQuality` (seeded from `defaultQuality`),
`alwaysInstant`, `engineOverride` ("auto"/"aria2c"/"yt-dlp"), `enqueued` guard (:348-353).

Top-to-bottom: drag handle 36×4 (:399-406); identity Row (42dp accent mark + "Anonrode" +
"Shared link · <platformName>", close 32dp circle) (:408-464); media card 64dp thumb + title +
"<platformName> · <quality|Audio (MP3)>" + monospace raw URL (:468-545); "Video/Audio" `SegmentBar`
(:550-565); engine `SegmentBar` Auto/Fast/Extractor (:569-591); quality `SegmentBar`
480p/720p/1080p/Best, animated visibility when video (:594-614); "Download instantly /
Skip this dialog next time" row + Switch (:619-648); primary `Button` "Download audio" /
"Download <quality>" (:652-681).
`platformName` mapping: Social→platform, Drama→"Drama", Magnet→"Torrent", Direct→"Direct Media",
else "Web" (:358-364); `mediaTitle` mapping :368-374; `posterUrl` only for Drama (:375).

### 3.8 PlaylistPickerSheet

**File:** `ui/screens/PlaylistPickerSheet.kt` (418 lines, composable :47-418). Root-hosted in
MainActivity (MainActivity.kt:295-301) when `viewModel.playlistState.url != null`.

**Purpose:** YouTube playlist batch downloader. `ModalBottomSheet` (SurfaceElevated, Radius.xl top
corners, drag handle :91-105).

State (:54-60): `query` (filter text), `rangeSpec` + `rangeError`, `audioOnly`, `selectedQuality`
(seeded `engine.defaultQuality`), `selected: mutableStateListOf<Int>` (1-based indices),
`skipDownloaded` (default true). Entry pre-selection for a watch-in-playlist URL via
`LaunchedEffect(meta)` (:66-70). Derived: `downloaded = PlaylistPicker.downloadedIds(tasks)`,
`filtered = PlaylistPicker.filterIndices(entries, query)`, `effective` (selection minus downloaded
when skipping), `estimate = PlaylistPicker.estimateBytes(...)` (:72-89).

Branches: loading → "Reading the playlist…"/"one metadata call, no video pages" +
`EpisodeListSkeleton(5)` (:108-130); `meta == null` → error copy + Close/Retry
(`viewModel.openPlaylist(url)`) (:132-154); else content:
title + "N videos · duration total" + MIX warning chip (:156-189), search field + "Select all" +
"Invert" (:193-218), range field + "Apply" with error line (:222-246), "Audio only (MP3)"
FilterChip + `QualityChipRow("480p","720p","1080p","Best")` when video (:253-270),
"Skip N already-on-device videos" checkbox (:271-290), entry `LazyColumn` (78×44 thumb,
duration badge, "<idx> · title", "✓ on device", Checkbox) (:293-384), confirm Button
"Download N · ≈size · MP3" → `viewModel.confirmPlaylist(indices = effective.sorted(), audioOnly,
quality = if (audioOnly) null else selectedQuality)` then `onDismiss()` (:387-414).

VM wiring: `playlistState` collect (:50), `engine.tasks` (:51), `engine.defaultQuality` (:58),
`openPlaylist` (:147), `confirmPlaylist` (:391).

### 3.9 SocialModal

**File:** `ui/screens/SocialModal.kt` (294 lines, composable :35-294). Root-hosted in MainActivity
(MainActivity.kt:283-290), opened when a social URL is pasted/shared and instant mode is OFF
(path: `handlePastedInput` → `onOpenSocial`).

`ModalBottomSheet` (SurfaceElevated, Radius.xl, drag handle :53-66). State: `audioOnly`,
`selectedQuality` (`engine.defaultQuality`), `alwaysInstant` (seeded
`engine.instantSocialDownload`), `enqueued` guard (:43-49).
Content: identity row ("Anonrode" + "Shared link · <cleanPlatform>"; `cleanPlatform` strips the
word "video" from the platform and defaults "Social", :51) (:77-130); media card = 56dp tile with
2-letter platform initials + monospace URL (:135-169); Format `SegmentBar` Video (MP4)/Audio (MP3)
(:174-189); quality bar 480p/720p/1080p/Best when video (:192-210); instant switch (:215-243);
primary Button → syncs instant both directions (`engine.setInstantSocial(alwaysInstant)` when
changed) then `engine.enqueue(showTitle = "Social/<platform>", backend="yt-dlp", audioOnly,
quality=…)` and `onDismiss()` (:249-291).

### 3.10 TorrentFilePicker

**File:** `ui/screens/TorrentFilePicker.kt` (228 lines). Two parts:

1. **Bridge object** `TorrentFilePicker` (:42-109): `Request(files, deferred)`,
   `requestState: StateFlow<Request?>` for rotation-safe hosting, `pick(files)` suspend called by
   the engine (AnonApp.kt:33-35) with a **60s timeout** (`PICK_TIMEOUT_MS`, :62) that resolves
   `Cancelled`; single-slot policy cancels a previous unanswered request (:69-72); `resolve(request,
   selection)` completes and clears (:95-100); deprecated `consume()` kept (:102-108).
2. **Dialog** `TorrentFilePickerDialog(request, onDismiss)` (:113-228): AlertDialog with the safe
   files list (checkbox rows + size in MB), blocked files listed in red with
   "Blocked by security shield (N MB)" and a disabled checkbox at 0.38 alpha (:164-191); "No safe
   files found — the whole torrent is blocked." when nothing is safe (:193-199). Confirm
   "Download (N)" (disabled at zero selection) / dismiss "All Safe Files"; when nothing is safe the
   only button is "Close" (Cancel) (:202-227). Dismissing outside = Cancelled (:124).

### 3.11 SplashContent + SplashMotion

**Files:** `ui/screens/SplashContent.kt` (367 lines, :73-367) and `ui/screens/SplashMotion.kt`
(211 lines, pure math object :30-211).

- `SplashMotion` is Android-free: `FULL_MS = 1700f`, `QUICK_MS = 950f` (:33-34); `poseAt(tMs, cutMs)`
  scales time to the 1700ms master (:135); primitives `phase`, `easeOutCubic/Quint`, `gravity`
  (½gt²), `spring` (damped harmonic), `springTo` (velocity-fed spring) (:36-79); timeline beats
  :84-112; `Pose` data class with 29 animated fields (:115-128).
- `SplashContent(cutMs, isDark, onSplashFinished)` (:73-77): full-screen Box in
  `DarkSplashColors` or `LightSplashColors` (:80), Canvas mark sized `42% of screen width capped at
  216dp` (:130-134), the three chevrons/A/arrow/stem/ripple drawn per frame from `poseAt`
  (:148-299), "ANONRODE" + "DOWNLOADER" wordmarks animated via `graphicsLayer` only (:301-333),
  bottom progress line 120×3dp rounding to the actual `t/cutMs` (:336-365).
- Reduced-motion: jump to `t = cutMs`, wait 300ms, finish (:93-99).
- Exit: 150ms settle then 250ms fade of the whole overlay (`exitAlpha` Animatable) then
  `onSplashFinished()` (:109-117). MainActivity additionally arms a fallback dismissal at
  `splashCutMs + 1200ms` (MainActivity.kt:212-215) and swallows taps (:377-388).

---

## 4. Component catalog

### 4.1 MediaPlayerModal

**File:** `ui/components/MediaPlayerModal.kt` (2174 lines). Two public overloads:

```kotlin
fun MediaPlayerModal(filePath: String, title: String, onDismiss: () -> Unit)      // :288-297
fun MediaPlayerModal(ctx: MediaPlayerContext, onDismiss: () -> Unit)              // :301-306
```
Both delegate to `MediaPlayerModalImpl` (:310-1520). **Hosted as a root-level overlay, not a
Dialog** — rendered after `MainScaffold` in MainActivity's single window so it covers the bottom nav
(comment :901-914; call site MainActivity.kt:279-281).

#### 4.1.1 Public context & PiP bridge
- `MediaPlayerContext(filePath, title, queuePeerPaths, onPlayFile)` (:149-154). The queue is built by
  `DownloadsSorter.playQueueFor` in MainActivity (MainActivity.kt:249-257).
- `PlayerPipState.inPip` snapshot boolean (:164-167), written only by
  `MainActivity.onPictureInPictureModeChanged` (MainActivity.kt:408-415).

#### 4.1.2 File-type routing (:169-176, :387-396)
- `AUDIO_EXTS = mp3 m4a aac wav flac opus ogg` → audio UI (centered music note, title, ext).
- `IMAGE_EXTS = jpg jpeg png webp gif` → `MediaImageStill` image viewer in the same overlay
  (:2118-2173): tap anywhere or back closes; title row + close button; "Photo • tap anywhere to
  close".
- `SIDECAR_SUBTITLE_EXTS = srt vtt ass ssa` discovered by prefix match next to the video with the
  same stem (:533-557); language derived by `sidecarLanguageHint` (:256-262).

#### 4.1.3 Player plumbing
- ONE `ExoPlayer` for the modal lifetime (:486-507): audio attributes USAGE_MEDIA,
  `setHandleAudioBecomingNoisy(true)`, `DefaultDataSource`, `DefaultTrackSelector`, starts
  `playWhenReady`, playbackParameters from saved speed, volume from saved volume.
- ONE `MediaSession` with random ID per composition, released on dispose (:508-515).
- `mediaItem` built per file with metadata (title = ctx.title, artist = parent folder name) and
  subtitle configurations (:520-560).
- File switch keeps the player; saves the leaving file's position first, resumes the new file from
  `PlaybackPositions.get(...).positionMs`, resets per-file UI state (:565-604).
- Listener (:607-748): audio-session → LoudnessEnhancer attach; `onIsPlayingChanged`;
  `onPlaybackStateChanged` (READY sets duration; ENDED clears saved position and auto-advances to
  the next existing peer); `onVideoSizeChanged` sets `isVerticalVideo`; `onPlayerError` toasts
  "Playback failed (<code>) — opening external player" then `playExternal` + dismiss;
  `onTracksChanged` builds audio labels ("LANG • kbps • Nch") and subtitle options
  (`"Off"` + labels) and runs the one-time auto-pick.
- Position persistence: poll every 500ms; save every ~10th tick (~5s) plus on ON_STOP, dispose and
  DESTROY (:751-768, :845-877).
- **Subtitle auto-pick** `autoPickSubtitleIndex(tracks, preferredLang)` (:209-244) with
  `LANG_ALIASES` (:188-198); "all" picks default else first; preferred-language default flagged
  first, then any match, else -1 (stay off). SRT sidecars sort above embedded tracks
  (:713-741). Preferred language from `AppSettings.load(context).subtitleLanguage` (:442-445).

#### 4.1.4 UI states and chrome
- `showControls` auto-hides after 3s of playback; re-armed by `interactionTick++` on any control
  touch (:429-430, :774-779). Tap on the black surface toggles controls (:925-932).
- Buffering → top `LinearProgressIndicator` (:1140-1148).
- Volume HUD pill shown 1.2s after gesture end (:782-787, :1151-1164).
- Controls overlay (fade, :1166-1171) contains:
  - Top row: Close (→onDismiss), title, Mini-player (PiP) button (video only), Open-in-external
    (`playExternal` + dismiss) (:1184-1228).
  - Center: Previous (44dp), Rewind 10s (48dp), Play/Pause (68dp accent circle), Forward 10s,
    Next (:1230-1298). Prev/Next enabled only when an existing peer file is found (:882-899).
  - Bottom: elapsed/total labels (elapsed follows the drag) + seek `Slider` (seek once on release;
    `isDragging`/`dragFrac` ownership, :1300-1360); chip row in a horizontally scrollable Row
    (:1362-1462): Speed (cycles `PLAYBACK_SPEEDS = 0.5,0.75,1,1.25,1.5,1.75,2.0`, persists via
    MediaPlayerPrefs), Landscape/Portrait rotate (video only; sets
    `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` / `PORTRAIT` :795-813), Subtitles (opens
    `BottomChoiceSheet`), Fit/Crop/Stretch (`AspectRatioFrameLayout` modes), Audio (opens
    `AudioOptionsSheet`), Boost toggle.
- Gestures on the root Box (:933-1063): tap toggles controls; **vertical drag on the right half =
  volume** with a 22dp dead zone, 28dp left/right and 44dp top system zones excluded, full height =
  100 points, above 100% requires breaking a detent (42dp overdrag, haptic KEYBOARD_TAP) and only
  when `allowVolumeBoost`; persist volume on drag end when ≤100%.
- PiP entry: `enterPictureInPictureMode` with video aspect clamped to 1/2.39..2.39 (:825-840);
  while `inPip` ALL chrome, HUD and tap/volume gestures are suppressed (:1150-1168, :925-935).
- Volume/boost implementation: `PlayerLoudnessEnhancer` (:2059-2108) maps 101..150% to
  `LoudnessEnhancer` target gain (`boostFraction*4000` mB).
- Subtitle rendering `configureSubtitles` (:1571-1611): re-parents `SubtitleView` into
  `exo_content_frame`, CaptionStyleCompat white/transparent outline, embedded styles & fonts
  disabled, fractional size 0.068 portrait / 0.054 landscape, bottom padding 0.22 when controls
  overlap video else 0.065.

#### 4.1.5 Choice sheets (Dialog windows stacked over the overlay)
- `BottomChoiceSheet(title, options, selected, onPick, onDismiss)` (:1711-1793): bottom-anchored,
  black 60% scrim, selects with a check mark. Used for subtitles; picking "Off" clears the persisted
  track (`MediaPlayerPrefs.setSubtitleTrack(null)`) and sets `subtitleUserDecided` (:1504-1519).
- `AudioOptionsSheet(...)` (:1824-1966): "150% Volume Boost" switch (amber when on, shows
  "(N%)" when boosting) + "Tracks" list with check; picking a track overrides the audio group
  (`TrackSelectionOverride`) (:1469-1503).

**Usage sites:** `MainActivity.kt:279-281` only.

### 4.2 AsianDramaExplorer

**File:** `ui/components/AsianDramaExplorer.kt` (480 lines, composable :46-356).

```kotlin
fun AsianDramaExplorer(
    region: DramaRegion, era: DramaEra, statusFilter: DramaStatusFilter, activeGenre: DramaGenre?,
    cards: List<ShowCard>, isLoading: Boolean, failed: Boolean, showPosters: Boolean,
    isLoadingMore: Boolean = false, hasMore: Boolean = true, onLoadMore: () -> Unit = {},
    onBack: () -> Unit, onSelectEra: (DramaEra) -> Unit, onSelectStatus: (DramaStatusFilter) -> Unit,
    onSelectGenre: (DramaGenre?) -> Unit, onRefresh: () -> Unit, onOpen: (ShowCard) -> Unit
)
```
Implementation notes verified:
- `BackHandler(onBack)` (:66); infinite scroll when last visible ≥ total-6 (:70-84).
- `genres` is computed (`AsianDramaTaxonomy.genresFor(region, era)`, :86-88) but **never rendered** —
  there is no genre chip row in the tree; `onSelectGenre` is only ever called by the empty-state
  "Reset filters" button (:312-317). The `activeGenre` parameter therefore has no UI entry point in
  this component (possibly intended for the Home hero section; treat as a gap, see §8 G-6).
- Header: 38dp back circle, `<REGION> <N shows>` + "Korean/Chinese Dramas & Series" subtitle,
  Refresh button (:101-168).
- Era selector: Modern vs "Historical (Sageuk/Costume)" 38dp segmented row (:172-207).
- Status row: "STATUS:" label + chips for `DramaStatusFilter.values()` (All/Completed/Ongoing)
  (:211-248).
- Body: loading spinner + "Loading <region> titles…" / failure "Unable to load dramas from
  providers." + Retry / empty "No titles found matching this filter." + "Reset filters" /
  3-column `LazyVerticalGrid` of `DramaCardItem` + loading-more row (:252-353).
- `DramaCardItem` (:403-480): 2:3 poster, `SubcomposeAsyncImage` with `InitialGlyph` (first letter,
  Type.displayPoster) loading/error, bottom scrim, optional "END"/"AIRING" badge
  (StatusSuccess / AccentViolet at 0.8 alpha) derived from title text or tags (:436-465),
  2-line title.

**Usage site:** `HomeScreen.kt:636-654` (only).

### 4.3 DownloadsSorter (+ downloadsStats)

**File:** `ui/components/DownloadsSorter.kt` (283 lines, pure Kotlin/JVM — no Android imports).

- Mode constants: `SORT_DATE "date"`, `SORT_LIBRARY "library"`, `SORT_STATUS "status"`,
  `SORT_SIZE "size"`; `ALL_MODES` in that order (:14-19).
- Age bucketing: `ageInDays` uses a test seam `TASK_AGE_OVERRIDES`; production seeds overrides via
  `setAgeOverride`/`clearAgeOverrides` (:21-36). `dateBucketLabel`: TODAY/YESTERDAY/THIS WEEK/
  THIS MONTH/EARLIER (:39-45). `sizeBucketLabel`: ≥800 MB LARGE / ≥400 MB MEDIUM / else SMALL
  (:48-55).
- `isShelfItem` = COMPLETED && showTitle == "Direct Downloads" (:68-69); `splitShelf` (:75-76).
- `sortDownloads(tasks, mode)` (:110-121) → `groupLibrary` (:154-171: groups by
  `normalizeShowKey`, shows ordered by completed-count desc then name, episodes by `EPISODE_ORDER`),
  `groupStatus` (:226-235 with `STATUS_ORDER` DOWNLOADING→RESOLVING→QUEUED→VALIDATING→PAUSED→FAILED
  →COMPLETED and `STATUS_HEADER` words DOWNLOADING/RESOLVING/QUEUED/VALIDATING/PAUSED/FAILED/DONE),
  `groupSize` (:237-248, largest first within bucket), `groupDate` (:123-135).
- `normalizeShowKey` (:137-152): strips bracketed site watermarks, site-ish parens, quality/codec
  tokens, trailing Sxx/Season markers.
- `EPISODE_ORDER` (:180-182): numbered episodes ascending, un-numbered (movies) after, then
  `createdAt`.
- `playerQueueFor(tasks, showTitle)` (:189-198): COMPLETED files of the same normalized show in
  episode order — the Library-sort queue.
- `playQueueFor(tasks, mode, task)` (:214-224): LIBRARY → same-show walk; SIZE → all playable
  largest-first; DATE/STATUS → all playable in engine order.
- `DownloadStats(files, done, active, failed)` (:257-265); `badgeCount = active`;
  `downloadsStats` (:267-283) counts DOWNLOADING/RESOLVING/VALIDATING as active; PAUSED and QUEUED
  are in no bucket.

**Usage sites:** DownloadsScreen (sorting, shelf, stats — see §3.4), MainScaffold.kt:313 (badge),
MainActivity.kt:247-253 (player queue). Tests: `app/src/test/.../ui/components/DownloadsSorterTest.kt`.

### 4.4 Skeleton family

**File:** `ui/components/Skeleton.kt` (259 lines). All public:

| API | Purpose | Lines |
|---|---|---|
| `rememberReduceMotion()` | true when system animator duration scale == 0 | 66-75 |
| `rememberShimmerX(reduceMotion)` | 0..1 sweep position, infinite 1200ms linear; 0 when reduced | 80-93 |
| `SkeletonBox(modifier, shape, x, base, highlight)` | one shimmering rectangle; `clearAndSetSemantics` | 98-124 |
| `TrendingRowSkeleton()` | 3-card row in the real LazyRow rhythm | 129-138 |
| `SearchListSkeleton()` | 3 search-row placeholders | 145-153 |
| `TrendingCardSkeleton(x)` | 124dp 2:3 poster + 3 text bars | 158-197 |
| `SearchRowSkeleton(x)` | mirrors ShowCardItem's 74×106 + column | 202-228 |
| `EpisodeRowSkeleton(x)` | 44dp row, 20dp square + bar | 233-249 |
| `EpisodeListSkeleton(rows = 6)` | N episode-row placeholders | 254-259 |

Shimmer is drawn in `drawBehind` so the animated value is read in the draw phase (no per-frame
recomposition) (:49-60, :108-122). Colors default to SurfaceElevated base / SurfaceCard highlight.

**Usage sites:** HomeScreen.kt:481 (`SearchListSkeleton`), :802 (`TrendingRowSkeleton`), :1577
(catalog placeholder); PlaylistPickerSheet.kt:127 (`EpisodeListSkeleton`); EpisodeDrawer.kt uses
`SkeletonBox` + `rememberShimmerX` + `rememberReduceMotion` for `DrawerSkeletonRow` (:85-87, 1618-1640).

### 4.5 MediaFallback

**File:** `ui/components/MediaFallback.kt` (138 lines).

- `PosterFallbackWell(modifier, shape = RoundedCornerShape(8.dp), icon = Videocam, iconSize = 22.dp,
  title: String? = null)` (:44-92). A dark gradient well `#181824 → #0C0C12`; when `title` is blank
  → centered icon at 18% alpha; when titled → 10sp SemiBold white-82% title (max 3 lines) with the
  icon pinned top-center (26dp bottom padding).
- `PosterImageOrFallback(posterUrl, showTitle, modifier, shape, fallbackIcon = PlayArrow,
  showFallbackTitle = true)` (:99-138). If url blank → well; else `SubcomposeAsyncImage` with
  loading/error slots both rendering the well.

**Usage sites:** HomeScreen.kt (PosterTile :1722, TrendingCard :901, GridPosterCard :1444,
GenreTileCard :1155/1184); DownloadsScreen.kt:1310 (52×72 thumb).
There is **no separate file or type named `MediaFallback`** — the filename holds the two
composables above (verified: file :43-138).

### 4.6 SegmentedBar

**File:** `ui/components/SegmentedBar.kt` (94 lines).

- `SegmentBar(innerPadding = Spacing.xs, content: RowScope.() -> Unit)` (:32-50): full-width
  SurfaceCard bar, radius md, hairline border, spacedBy(Spacing.xs).
- `SegmentCell(selected, onClick, label, modifier, icon: ImageVector? = null, cellHeight = 42.dp)`
  (:53-94): press-scale 0.97, selected = AccentPrimary fill + BackgroundDark ink; no ripple
  (`indication = null`), semantics from `clickable`.

**Usage sites:** QuickShareActivity.kt (format :550-565, engine :569-591, quality :603-612),
SocialModal.kt (format :174-189, quality :199-209).

### 4.7 QualityChipRow

**File:** `ui/components/QualityChipRow.kt` (81 lines). `QualityChipRow(options: List<String>,
selected: String, onSelect: (String) -> Unit, modifier)` (:35-81): equal-width 48dp chips,
selected = AccentViolet fill + white bold text, `selectable(role = RadioButton)` for correct
TalkBack semantics. **Usage site:** PlaylistPickerSheet.kt:265-269 only (SocialModal uses SegmentBar
instead — two different selection languages across two similar sheets; noted in gaps).

### 4.8 PlaybackPositions / MediaPlayerPrefs

- `PlaybackPositions` (`ui/components/PlaybackPositions.kt`, 71 lines): prefs file
  `player_positions`, key = absolute file path, value `"positionMs:durationMs"`. Ignore <30s started
  (`MIN_SAVE_MS`), treat <15s-to-end as finished (`NEAR_END_MS`), prune missing files past 200
  entries. API `get/save/clear` (:15-71).
- `MediaPlayerPrefs` (`ui/components/MediaPlayerPrefs.kt`, 72 lines): prefs file
  `downloader_settings`. Keys: `pref_playback_speed` (default 1.0), `pref_media_volume`
  (default 1.0, clamped 0..1), `pref_allow_volume_boost` (default true), `pref_window_brightness`
  (default -1 = system; getter/setter exist but **no UI reads or writes this key** — gap G-7),
  `pref_subtitle_track` (default null).

---

## 5. Theme, tokens, resources

### 5.1 Theme.kt

**File:** `ui/theme/Theme.kt` (260 lines).

`data class AnonColors(isDark, background, surfaceCard, surfaceElevated, borderHairline,
accentPrimary, textPrimary, textSecondary, textMuted, accentViolet, accentPink, statusSuccess,
statusError, statusWarning)` (:8-23).

| Token | Dark (`DarkAnonColors` :25-40) | Light (`LightAnonColors` :42-57) |
|---|---|---|
| `background` | `0xFF090B10` | `0xFFF8F9FA` |
| `surfaceCard` | `0xFF141822` | `0xFFFFFFFF` |
| `surfaceElevated` | `0xFF1A1F2C` | `0xFFF1F3F5` |
| `borderHairline` | white 8% | `0x1A0F172A` |
| `accentPrimary` | `0xFFFFFFFF` | `0xFF0F1115` |
| `textPrimary` | `0xFFF4F4F6` | `0xFF0F1115` |
| `textSecondary` | `0xFF94A3B8` | `0xFF475569` |
| `textMuted` | `0xFF64748B` | `0xFF64748B` |
| `accentViolet` | `0xFF8B5CF6` | `0xFF7C3AED` |
| `accentPink` | `0xFFEC4899` | `0xFFDB2777` |
| `statusSuccess` | `0xFF10B981` | `0xFF059669` |
| `statusError` | `0xFFEF4444` | `0xFFDC2626` |
| `statusWarning` | `0xFFF59E0B` | `0xFFB45309` |

`LocalAnonColors` staticCompositionLocalOf default `DarkAnonColors` (:59); `AnonTheme.colors`
accessor (:61-65); per-token composable getters (`BackgroundDark`, `SurfaceCard`, `SurfaceElevated`,
`BorderHairline`, `AccentPrimary`, `TextPrimary`, `TextSecondary`, `TextMuted`, `AccentViolet`,
`AccentPink`, `StatusSuccess`, `StatusError`, `StatusWarning`) at :68-80.

Splash palettes: `SplashThemeColors(background, glyph, title, subtitle, progressTrack, progressFill)`
(:88-95); `DarkSplashColors` black/white/`#22D3EE`(:97-104); `LightSplashColors`
`#F4F7FA`/`#07131A`/`#0A1620`/`#0E7490` (:106-113). Legacy fallback constants `SplashBackground`,
`SplashOnBackground`, `SplashMuted`, `SplashElevated` (:116-119) — **no call sites** in `ui/`
(gap G-8).

Material schemes (what M3 components such as AlertDialog, Slider, Switch, DropdownMenu resolve):
- `DarkMaterialColorScheme` (:125-159): OLED obsidian — background `#000000`, surface `#0B0B0E`,
  primaryContainer/surfaceContainerHigh `#1E1E26`, outline `0x12FFFFFF`, secondary `#B7A6FA`,
  tertiary `#F2A6CD`, error `#F87171`.
- `LightMaterialColorScheme` (:163-193): background `#F8F9FA`, surface white, primary `#0F1115`,
  secondary `#7C3AED`, tertiary `#DB2777`, error `#DC2626`.
- Note the deliberate split: `AnonColors.background` (used for app surfaces) is `#090B10` dark but
  the Material `background` is `#000000`; `NavigationBar`/bottom bar uses
  `MaterialTheme.colorScheme.surface` = `#0B0B0E` (MainScaffold.kt:224).

`AnonDownloaderTheme(themeMode: String = "dark", content)` (:195-217): resolves "light"→light,
"system"→`isSystemInDarkTheme()`, else dark; provides `LocalAnonColors` + `MaterialTheme` with
`AnonTypography`. **No dynamic color** (no `dynamicDarkColorScheme`/`dynamicLightColorScheme`
anywhere).

`AnonTypography` (:249-260) maps M3 text roles onto the app Type scale:
`headlineSmall = Type.itemTitle`, `titleLarge = Type.screenTitle`, `titleMedium = Type.itemTitle`,
`titleSmall = Type.rowTitle`, `bodyLarge/bodyMedium = Type.body`, `bodySmall = Type.label`,
`labelLarge = Type.label`, `labelMedium = Type.caption`, `labelSmall = Type.micro`.

### 5.2 Tokens.kt

**File:** `ui/theme/Tokens.kt` (131 lines).

`object Spacing` (:8-17): `xxs=2, xs=4, sm=8, md=12, lg=16, xl=24, xxl=32, xxxl=48` (dp).
`object Radius` (:19-28): `xs=4, sm=8, md=12, lg=16, xl=20, xxl=28, pill=999, full=999(alias)` (dp).
`object Motion` (:30-54): `DurationFast=200`, `DurationNormal=350`, `DurationSlow=500`,
`DurationNavExit=120`; M3 standard spring constants `SpatialDamping=0.9f`,
`SpatialStiffnessFast=1400f`, `SpatialStiffnessDefault=700f`, `SpatialStiffnessSlow=300f`,
`EffectsDamping=1.0f`, `EffectsStiffnessDefault=1600f`, `EffectsStiffnessFast=3800f`;
`DurationSkeleton=1200`.

`object Type` (:72-131) — ten roles (fontSize / lineHeight / weight / letterSpacing):

| Role | Size | Line | Weight | Tracking | Intended use (comment) |
|---|---|---|---|---|---|
| `displayPoster` | 34sp | 40 | Black | -0.5sp | poster initial glyph only |
| `brand` | 22sp | 28 | Black | 1.0sp | ANONRODE lockup |
| `screenTitle` | 18sp | 24 | Black | 1.0sp | page titles |
| `itemTitle` | 16sp | 21 | SemiBold | — | primary rows, dialog titles |
| `sectionTitle` | 15sp | 20 | Bold | — | section headers |
| `rowTitle` | 14sp | 18 | SemiBold | — | card/list-row titles |
| `body` | 13sp | 17 | normal | — | default reading |
| `label` | 12sp | 16 | Medium | 0.2sp | chips, subtitles |
| `caption` | 11sp | 15 | normal | 0.4sp | badges, nav labels |
| `micro` | 10sp | 13 | Bold | 0.8sp | uppercase micro-labels |

Call-site convention documented at :64-71: sites use `fontSize = Type.x.fontSize` (a TextUnit);
`.fontWeight`/`.lineHeight` of the role are adopted only when a site is rewritten to `style =`.

### 5.3 UI utilities

- `ui/util/TitleFormatting.kt` (144 lines, pure Kotlin): `extractSeasonEpisodeTag(raw)` →
  `(cleanTitle, "SxxExx")` (:23-30); `stripTrailingYtDlpId(raw)` (:38-47);
  `sanitizeEpisodeTitle(rawTitle, index, seasonNumber)` (:73-93) — falls back to
  "S01E01"-style / "Full Movie" / "Episode N", rejecting titles made only of "download/watch/1080p"
  tokens (:55-56); `splitYearAndSeason(raw)` → `(title, "2021 · Season 6")` (:107-131);
  `collapseSpaces` (:134); `cleanPosterTitle(raw)` (:138-143).
  Usage sites: HomeScreen.kt:877-880, 1421-1424, 1634; DownloadsScreen.kt:1237-1242, 1891;
  EpisodeDrawer.kt:1200-1204.
- `ui/util/PressScale.kt` (43 lines): `Modifier.pressScale(interactionSource, pressedScale = 0.96f)`
  — tween 140ms `CubicBezierEasing(0.2,0,0,1)`, scale animated in `graphicsLayer` (draw phase)
  (:26-43). Caller must share the `MutableInteractionSource` with the `clickable` (doc :18-25).
  Usage: SettingsSheet.kt (hub entries, drawer buttons), EpisodeDrawer.kt (CTA, details button),
  QuickShareActivity.kt, SocialModal.kt, SegmentedBar.kt.
- `ui/util/Haptics.kt` (36 lines): `View.tick()` = CLOCK_TICK (:24); `View.confirmHaptic()` =
  CONFIRM (API30+) else CLOCK_TICK (:27-30); `View.rejectHaptic()` = REJECT (API30+) else LONG_PRESS
  (:33-36). Default flags so the system haptics switch wins. Usage: DownloadsScreen delete confirm
  (:801), EpisodeDrawer (selection :1165, enqueue :320/1173).

### 5.4 AndroidManifest

**File:** `app/src/main/AndroidManifest.xml` (78 lines).

Permissions (:4-12): INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, READ_EXTERNAL_STORAGE (maxSdk 32),
WRITE_EXTERNAL_STORAGE (maxSdk 29), MANAGE_EXTERNAL_STORAGE.

Application (:14-26): `android:name=".AnonApp"`, allowBackup true, `fullBackupContent` +
`dataExtractionRules`, icon `@mipmap/ic_launcher`, roundIcon, label "Anonrode", supportsRtl,
theme `@style/Theme.AnonDownloader`, networkSecurityConfig, `extractNativeLibs=true`,
`requestLegacyExternalStorage=true`.

Components:
- `.MainActivity` — exported, singleTask, configChanges (see §1.1), supportsPictureInPicture,
  resizeableActivity, theme `Theme.AnonDownloader.Splash`, MAIN/LAUNCHER (:28-40).
- `.ui.screens.QuickShareActivity` — exported, singleInstance, excludeFromRecents, theme
  `Theme.AnonDownloader.Dialog`, `ACTION_SEND` + `text/plain` (:43-54).
- `.service.DownloadService` — enabled, not exported, foregroundServiceType dataSync (:56-60).
- `.service.RetryReceiver` — not exported (notification retry action) (:63-65).
- `androidx.core.content.FileProvider` authority `${applicationId}.fileprovider` (:67-75).

### 5.5 res/ inventory

**`res/values/strings.xml`** (4 lines): the ONLY string resource is `app_name` = "Anonrode" (:3).
Every other user-visible string is hardcoded (see §8 G-1).

**`res/values/colors.xml`** (:2-6): `splash_background #F4F7FA`, `splash_glyph_color #07131A`,
`black #000000`, `white #FFFFFF`.
**`res/values-night/colors.xml`** (:2-6): `splash_background #000000`,
`splash_glyph_color #FFFFFF`, `black`, `white`.

**`res/values/styles.xml`** (45 lines):
- `Theme.AnonDownloader` (:3-18): parent `android:Theme.Material.NoActionBar`; transparent status
  and nav bars; `windowBackground @color/splash_background`; contrast enforcement off (API Q+).
- `Theme.AnonDownloader.Splash` (:20-34): parent `Theme.SplashScreen`;
  `windowSplashScreenBackground @color/splash_background`;
  `windowSplashScreenAnimatedIcon @drawable/ic_anon_monogram`; `postSplashScreenTheme`; light
  status/nav bar flags true.
- `Theme.AnonDownloader.Dialog` (:36-44): transparent window background, translucent, elevation 0,
  `Animation.Dialog`, transparent bars.
**`res/values-night/styles.xml`** (:1-24): same three themes; splash/dialog flags set to light=false
(dark icons off) and background resolves dark.

**`res/xml/`**
- `backup_rules.xml` (:1-7): exclude `file logs/` from full backup.
- `data_extraction_rules.xml` (:1-11): same exclusion for cloud-backup and device-transfer.
- `file_paths.xml` (:1-7): external-path `.`, external-files-path, files-path, cache-path.
- `network_security_config.xml` (:1-25): cleartext OFF globally; allowed for
  `downloadwella.com`, `wetafiles.com`, `loadedfiles.net/.st/.to/.org/.com`, `kissorgrab.com`,
  and the telemetry IP `68.155.146.145`.

**`res/layout/`**: `player_modal_texture_view.xml` only (:1-12, PlayerView with
`app:surface_type="texture_view"`).

**`res/drawable/`** (6 files):
| File | What it is |
|---|---|
| `ic_anon_mark.xml` | 48dp squircle brand mark: cyan→teal gradient `#22D3EE`→`#0E7490` tile + ink `#04121A` A/floor/arrow. Declared but **no call sites found in Kotlin** (gap G-9). |
| `ic_anon_monogram.xml` | 48dp bare monogram, paths tinted `@color/splash_glyph_color`; used by the OS splash (styles.xml:26) and the Home header (HomeScreen.kt:178). |
| `ic_launcher_background.xml` | 108dp solid `#000000` adaptive background. |
| `ic_launcher_foreground.xml` | 108dp white monogram foreground (0.85 scale, translated). |
| `ic_launcher_monochrome.xml` | 108dp themed-icon layer, same geometry. |
| `ic_notification.xml` | 24dp white monochrome glyph for notifications (used by DownloadService, not the UI layer). |

**`res/mipmap-anydpi-v26/`**: `ic_launcher.xml` and `ic_launcher_round.xml` — adaptive-icon XML
referencing the three layers above (identical content, :1-7 each).

No `res/values/dimens.xml`, no `res/menu/`, no `res/font/`, no other `res/values-*` folders
(directory listing verified).

---

## 6. ViewModel & state reference

`MainViewModel` — `viewmodel/MainViewModel.kt` (1313 lines), `AndroidViewModel`, class at :91.
Holds `engine: DownloadEngine` from `AnonApp` (:93).

### 6.1 HomeUiState — every field

`data class HomeUiState` :24-89. All fields have defaults; a fresh instance is the initial state.

| # | Field | Type | Default | Line | Rendered by |
|---|---|---|---|---|---|
| 1 | `query` | String | `""` | 25 | HomeScreen search field (:262-295) |
| 2 | `selectedFilter` | String | `"all"` | 26 | HomeScreen filter chips (:416) |
| 3 | `isSearching` | Boolean | false | 27 | skeleton branch (:470) |
| 4 | `searchResults` | List<ShowCard> | empty | 28 | results list (:458-611) |
| 5 | `searchError` | String? | null | 29 | error branch (:483) |
| 6 | `activeShowForDrawer` | ShowCard? | null | 30 | EpisodeDrawer visibility (:668) |
| 7 | `drawerEpisodes` | List<EpisodeItem> | empty | 31 | EpisodeDrawer list (:174, 190) |
| 8 | `drawerSynopsis` | String | `""` | 32 | drawer synopsis block (:865) |
| 9 | `isEpisodesLoading` | Boolean | false | 33 | drawer loading state (:191) |
| 10 | `episodesError` | String? | null | 34 | drawer failure state (:848) |
| 11 | `drawerPreselectRemaining` | Boolean | false | 40 | one-shot preselect (:237-242) |
| 12 | `freeStorageGb` | Long | 0 | 41 | Downloads summary (:386-389), Settings footer (:663) |
| 13 | `totalStorageGb` | Long | 0 | 42 | same |
| 14 | `trending` | List<ShowCard> | empty | 46 | TrendingSection (:557) |
| 15 | `isTrendingLoading` | Boolean | false | 47 | TrendingSection (:558) |
| 16 | `trendingFailed` | Boolean | false | 48 | TrendingSection retry (:559) |
| 17 | `activeCategory` | CategoryFeed.Category? | null | 55 | CategoryPage visibility (:620) |
| 18 | `categoryCards` | List<ShowCard> | empty | 56 | CategoryPage grid (:622) |
| 19 | `isCategoryLoading` | Boolean | false | 57 | CategoryPage spinner (:624) |
| 20 | `categoryFailed` | Boolean | false | 58 | CategoryPage copy (:625) |
| 21 | `categoryPage` | Int | 1 | 59 | paging bookkeeping |
| 22 | `isCategoryLoadingMore` | Boolean | false | 60 | grid loading row (:627) |
| 23 | `categoryHasMore` | Boolean | true | 61 | infinite scroll (:628) |
| 24 | `catalogOpen` | Boolean | false | 62 | CatalogPage visibility (:656) |
| 25 | `catalogRows` | Map<String, List<ShowCard>> | empty | 63 | CatalogPage rows (:658) |
| 26 | `trendingPage` | Int | 1 | 65 | paging bookkeeping |
| 27 | `isTrendingLoadingMore` | Boolean | false | 66 | trailing spinner (:562) |
| 28 | `trendingHasMore` | Boolean | true | 67 | infinite scroll (:563) |
| 29 | `genreTiles` | List<CategoryFeed.GenreTile> | empty | 72 | **hydrated but never rendered** — CategoryTilesGrid uses hardcoded previews (gap G-4) |
| 30 | `verdicts` | Map<String, Verdict> | empty | 76 | HomeScreen via `VerdictPolicy` (:459, 589) |
| 31 | `activeDramaRegion` | DramaRegion? | null | 78 | AsianDramaExplorer visibility (:635) |
| 32 | `activeDramaEra` | DramaEra | MODERN | 79 | era selector (:638) |
| 33 | `activeDramaStatus` | DramaStatusFilter | ALL | 80 | status chips (:639) |
| 34 | `activeDramaGenre` | DramaGenre? | null | 81 | passed to explorer (:640) — **no in-explorer selector** (gap G-6) |
| 35 | `dramaRawCards` | List<ShowCard> | empty | 82 | raw pool for client-side filtering |
| 36 | `dramaCards` | List<ShowCard> | empty | 83 | explorer grid (:641) |
| 37 | `isDramaLoading` | Boolean | false | 84 | explorer spinner (:642) |
| 38 | `dramaFailed` | Boolean | false | 85 | explorer failure (:643) |
| 39 | `dramaPage` | Int | 1 | 86 | paging bookkeeping |
| 40 | `isDramaLoadingMore` | Boolean | false | 87 | explorer loading row (:645) |
| 41 | `dramaHasMore` | Boolean | true | 88 | infinite scroll (:646) |

### 6.2 Other UI flows

| Flow | Type | Line | Consumed by |
|---|---|---|---|
| `uiState` | `StateFlow<HomeUiState>` | 95-96 | HomeScreen, DownloadsScreen, SettingsScreen |
| `playlistState` | `StateFlow<PlaylistUiState>` | 109-110 | MainActivity (:295), PlaylistPickerSheet (:50) |
| `activeSocialTarget` | `MutableStateFlow<Pair<String,String>?>` | 113 | MainActivity (:130, written :271-272, :428) |
| `activePlayingTaskId` | `MutableStateFlow<String?>` | 114 | MainActivity (:238, written :239-241, cleared :280) |
| `engine.tasks` | `StateFlow<List<DownloadTask>>` (engine) | DownloadEngine.kt:115 | DownloadsScreen (:189), MainScaffold badge (:312), EpisodeDrawer (:221), PlaylistPickerSheet (:51) |

`PlaylistUiState` (:102-108): `url: String?` (non-null = sheet open), `parsed:
PlaylistPicker.ParsedList?`, `loading: Boolean`, `meta: PlaylistPicker.PlaylistMeta?`,
`error: String?`.

### 6.3 Every public ViewModel method

Grouped by feature. "Caller" lists verified UI call sites.

**Playlist (YouTube)**
| Method | Line | Behaviour | Caller |
|---|---|---|---|
| `openPlaylist(url)` | 119-143 | `PlaylistPicker.detect`, sets loading state, one `fetchPlaylistJson` + `parseFlatJson`; error copy on failure | PlaylistPickerSheet:147 |
| `closePlaylist()` | 145-148 | cancels job, resets state | MainActivity:299 |
| `confirmPlaylist(indices, audioOnly, quality)` | 151-156 | `engine.enqueuePlaylist` then closes | PlaylistPickerSheet:391 |

**Trending / genre tiles**
| Method | Line | Notes | Caller |
|---|---|---|---|
| `loadGenreTiles(force = false)` | 315-337 | one fetch per process; silent failure | `init` :299 only |
| `loadTrending(force = false)` | 349-394 | cache-first (FeedCache freshness), streaming partials via `onPartial`, spinner only when empty | HomeScreen:565-566 |
| `loadMoreTrending()` | 396-436 | page+1, dedupe by url and normalized title | HomeScreen:564 |

**Categories / catalog**
| Method | Line | Notes | Caller |
|---|---|---|---|
| `openCategory(category)` | 446-469 | paints memory/disk cards instantly, silent refetch if stale | HomeScreen:576, 661 |
| `openCatalog()` / `closeCatalog()` | 474-480 | toggles `catalogOpen` | HomeScreen:577 / :660 |
| `closeCategory()` | 485-501 | cancels crawl, resets page | HomeScreen:630 |
| `ensureCategoryCards(category)` | 505-515 | catalog row loader with pending queue | HomeScreen:662 |
| `loadCategory(category, force=false)` | 538-621 | streaming partials label-guarded; force on Refresh | HomeScreen:631 |
| `loadMoreCategory()` | 623-670 | page+1 dedupe | HomeScreen:629 |

**Asian Drama**
| Method | Line | Caller |
|---|---|---|
| `openAsianDramaHub(region, era = MODERN)` | 676-696 | HomeScreen:571-572 |
| `selectDramaEra(era)` | 698-716 | explorer:649 |
| `selectDramaStatus(status)` | 718-733 | explorer:650 |
| `selectDramaGenre(genre?)` | 735-750 | explorer:651 (only for reset) |
| `closeAsianDramaHub()` | 752-767 | explorer:648 |
| `refreshDramaFeed()` | 769-782 | explorer:652 |
| `loadMoreDrama()` | 865-924 | explorer:647 |

**Search**
| Method | Line | Notes | Caller |
|---|---|---|---|
| `onQueryChanged(newQuery)` | 926-944 | debounce 350ms for ≥2 chars; shorter clears results and cancels tagged HTTP | HomeScreen:271, 273, 300, 542 |
| `onFilterSelected(filter)` | 946-960 | clears stale results, re-searches if query non-blank | HomeScreen:420 |
| `handlePastedInput(input, onOpenSocial)` | 962-1024 | URL router: DramaUrl→drawer; SocialUrl→playlist / instant / SocialModal; Magnet→engine; Direct→engine; SearchQuery→search | HomeScreen:268, 345, 375; MainActivity:427 |
| `search(query = uiState.query)` | 1026-1091 | dedupe by query+filter, clear previous query's cards, cancel tagged calls, stream `ProviderRegistry.searchFlow`, submit `ResultVerifier`, seq-guarded finally | HomeScreen:289, 319, 506 |

**Episode drawer**
| Method | Line | Notes | Caller |
|---|---|---|---|
| `openEpisodeDrawer(show, preselectRemaining = false)` | 1093-1139 | prioritizes verifier, sets loading, loads `ProviderRegistry.loadEpisodes` | HomeScreen:567/592/632/653/663; EpisodeDrawer retry:856; VM:967, 1161 |
| `closeEpisodeDrawer()` | 1141-1151 | cancels job, resets state | HomeScreen:672 |
| `openRemainingEpisodes(seriesTitle, seriesUrl, site)` | 1159-1170 | builds a ShowCard and opens drawer with preselect | DownloadsScreen:618, 647 |
| `downloadEpisode(episode)` | 1172-1191 | **DEAD — no UI caller** (drawer enqueues directly) | — |
| `downloadAllEpisodes(episodes)` | 1193-1211 | **DEAD — no UI caller** | — |

**Settings / storage**
| Method | Line | Notes | Caller |
|---|---|---|---|
| `saveSettings(...)` (23 params, each defaulted) | 1213-1290 | on explicit-filter change prunes painted trending/category/catalog + cache; otherwise stores a `SettingsSnapshot` and schedules a 500ms flush | SettingsSheet:155-179 (via `persist()`) |
| `flushPendingSettings()` | 230-263 | immediate engine `saveAllSettings`, then logs | SettingsSheet:287, 295 |
| `refreshStorageInfo()` | 1297-1312 | `StatFs` on IO, updates `freeStorageGb`/`totalStorageGb`; called at init :266 | MainActivity n/a; DownloadsScreen:209; SettingsSheet:288 |

**Internal machinery (not UI-facing):** `SettingsSnapshot` (:197-221), jobs
(`searchJob`/`debounceJob`/`episodesJob`/`trendingJob` :158-162, `trendingMoreJob` :343,
`categoryMoreJob` :438, `dramaJob`/`dramaMoreJob` :673-674, `playlistJob` :111, `genreTilesJob` :314),
in-process caches `categoryCache` / `pendingCatalogGenres` / `categoryPages` (:167-173, 439),
`init` block (:265-308) which hydro-loads FeedCache snapshots and starts the `ResultVerifier`
verdict stream (:303-307).

### 6.4 Engine (DownloadEngine) surface read by the UI

`engine/DownloadEngine.kt`. `@Volatile` settings fields (:66-97) — all are written from
`saveAllSettings` and read by UI/Settings:

| Engine field | Default | Pref key (AppSettings) | UI reader |
|---|---|---|---|
| `maxConcurrentDownloads` | 4 | `pref_max_downloads` (coerced 1..6) | SettingsSheet:86 |
| `parallelSocketsPerFile` | 16 | `pref_parallel_sockets` (1..16) | SettingsSheet:87; enqueue calls everywhere |
| `defaultQuality` | "720p" | `pref_default_quality` | SettingsSheet:88; SocialModal:44; PlaylistPickerSheet:58; QuickShareActivity:181/237 |
| `autoOrganizeByShow` | true | `pref_auto_organize` | SettingsSheet:89 |
| `instantSocialDownload` | false | `pref_instant_social` | SettingsSheet:90; SocialModal:47/259; QuickShareActivity:191 |
| `downloadTorrentsWifiOnly` | false | `pref_torrents_wifi_only` | SettingsSheet:91 |
| `showPostersInResults` | true | `pref_show_posters` | SettingsSheet:92; HomeScreen:561/588/626/644/659 |
| `storageGuardGb` | 1.0 | `pref_storage_guard` (float) | SettingsSheet:93 |
| `stallTimeoutSec` | 60 | `pref_stall_timeout` | SettingsSheet:95 |
| `magnetMaxAttempts` | 3 | `pref_magnet_retries` | SettingsSheet:96 |
| `ytdlpMaxAttempts` | 3 | `pref_ytdlp_retries` | SettingsSheet:97 |
| `hlsFragmentConcurrency` | 16 | `pref_hls_fragments` | SettingsSheet:98 |
| `globalSpeedLimitKbs` | 0 | `pref_speed_limit_kbs` | SettingsSheet:99 |
| `torrentPeers` | -1 | `pref_torrent_peers` | SettingsSheet:100 |
| `torrentPrivacyMode` | false | `pref_torrent_privacy_mode` | SettingsSheet:101 |
| `wifiOnlyAll` | false | `pref_wifi_only_all` | SettingsSheet:102 |
| `clipboardDetect` | true | `pref_clipboard_detect` | SettingsSheet:103 |
| `completionNotifications` | true | `pref_completion_notifications` | SettingsSheet:104 |
| `debugLogging` | false | `pref_debug_logging` | SettingsSheet:105 |
| `logRetentionDays` | 7 | `pref_log_retention_days` | SettingsSheet:106 |
| `downloadSubtitles` | true | `pref_download_subtitles` | SettingsSheet:110 |
| `subtitleLanguage` | "en" | `pref_subtitle_lang` | SettingsSheet:111; MediaPlayerModal:443 |
| `filterExplicitContent` | true | `pref_filter_explicit_content` | SettingsSheet:112; VM init/fetches |

Engine methods called by the UI: `enqueue(...)` (DownloadEngine.kt:418; called from
MainViewModel:983/998/1009/1175/1197, EpisodeDrawer:325/1174, QuickShareActivity:263/276/287/298/310,
SocialModal:262, MainActivity n/a), `enqueuePlaylist` (:557, from MainViewModel:153),
`pause`/`retry`/`cancel` (:588/:820/:699, from DownloadsScreen:622-624/655-657/741-746/763/802),
`pauseAll` (:3289) / `resumeAll` (:3331) (DownloadsScreen:526/535),
`setInstantSocial` (:304, SocialModal:260, QuickShareActivity:197), `setShowPosters` (:296,
**no UI caller** — the Settings toggle goes through `saveAllSettings`), `saveAllSettings` (:311,
MainViewModel:235), `tasks` StateFlow (:115).

### 6.5 Data models rendered by the UI

`data/models/Models.kt` (116 lines):

- `enum class TaskStatus` (:6-14): QUEUED, RESOLVING, DOWNLOADING, VALIDATING, PAUSED, COMPLETED,
  FAILED.
- `ShowCard` (:17-27): `title`, `url`, `posterUrl`, `site`, `category = "Drama"`, `year`,
  `totalEpisodes`, `tags`, `genres`. Rendered by: HomeScreen cards/tiles, drawer header,
  `ShowCardItem`, `DramaCardItem`.
- `EpisodeItem` (:30-38): `title`, `url`, `episodeNum`, `site`, `sizeText`, `isDownloaded`,
  `mirrorUrls`. Rendered by EpisodeDrawer rows; `isDownloaded` is **not used** there — downloaded
  state is computed live from engine tasks (`downloadedOrQueuedUrls`, EpisodeDrawer:222-228).
- `ShowDetails` (:41-45): `show`, `synopsis`, `episodes` — the drawer payload.
- `DownloadRecipe` (:48-54): engine-side recipe (not rendered).
- `DownloadTask` (:57-116): `id`, `showTitle`, `episodeNum`, `episodeTitle`, `directUrl`,
  `sourceUrl`, `downloadedBytes`, `totalBytes`, `speedBytesPerSec`, `etaSeconds`, `status`,
  `filePath`, `backend`, `parallelSockets`, `site`, `headers`, `audioOnly`, `errorMessage`,
  `userPaused`, `quality`, `resolution`, `validationNote`, `createdAt`, `mirrorUrls`,
  `selectedMirrorUrl`, `seriesUrl`, `seriesTotal`, `posterUrl`.
  Rendered fields worth flagging: `resolution`/`quality` (card meta and shelf meta), `validationNote`
  (only in legacy `DownloadCard` :2302-2311 — the live `ActivityRow` never shows it),
  `createdAt` (date bucketing), `seriesUrl`/`seriesTotal` (Download-remaining chip), `posterUrl`
  (52×72 thumb), `userPaused` (engine-only), `mirrorUrls`/`selectedMirrorUrl` (engine-only),
  `headers` (engine-only).

Also rendered but defined elsewhere: `CategoryFeed.Category(label, queryTerms, sites, aliases)` and
`CategoryFeed.GenreTile(category, posterUrl)` (CategoryFeed.kt:59-154); `DramaRegion`,
`DramaEra`, `DramaStatusFilter(label)`, `DramaGenre(id, label, era, region, …)`
(AsianDramaTaxonomy.kt:5-28); `Verdict.Live/Unreachable/Dead` + `VerdictPolicy` captions
(DownloadVerdict.kt:26-153); `TorrentSecurityShield.TorrentFileEntry` (title dialog);
`PlaylistPicker.ParsedList/PlaylistMeta/ListKind` (sheet).

### 6.6 Content filters and their UI-visible effect

**ExplicitContentFilter** (`util/ExplicitContentFilter.kt`, 220 lines). `filterSafe(cards)` (:218-219)
returns cards minus explicit ones. Applies to **browse feeds only: Trending, Genre/Catalog,
Drama feeds** — never to search (`MainViewModel.search` does not filter; doc :15-16). Filtering
points where cards can disappear:
- VM `init` hydration of FeedCache snapshot (:273-281) — feeds paint pre-filtered.
- `loadTrending`/`loadMoreTrending` pass `filterExplicit = engine.filterExplicitContent`
  (:364, 409).
- `loadCategory`/`loadMoreCategory` (:574, 638), `loadGenreTiles` (:323),
  `openAsianDramaHub`→`loadDramaFeed`/`loadMoreDrama` (:819, 883).
- Turning the setting ON at runtime prunes already-painted lists and the category cache
  (`saveSettings` :1238-1256); turning it OFF clears the cache so refetches repopulate.

**NollywoodFilter** (`util/NollywoodFilter.kt`, 93 lines). OTA-controlled
(`DynamicRulesManager.getContentPolicy().suppressNollywood`); when off, pass-through (:89).
Rules: R1 title term (`nollywood/yoruba/naija/igbo/hausa` defaults, :38), R2 tags/genres on scoped
sites, R3 site-only kill list (:62-85). The class doc claims "Downloads, playback, history and
pasted links are never filtered" (:33). Verified call sites (all upstream of the UI):
`MainViewModel` FeedCache hydration (:273, :280), `TrendingFeed.kt:124`, `CategoryFeed.kt:327`,
`AsianDramaFeed.kt:38`, and — critically — **`ProviderRegistry.searchFlow` (ProviderRegistry.kt:178-180)
filters SEARCH results too**, deliberately unlike the explicit filter ("a content-type block that
search can circumvent is only half-effective", comment :173-177). UI-visible effect: cards silently
absent from browse feeds AND search results; there is deliberately no Settings toggle (doc :30-33).

**VerdictPolicy** (`pipeline/DownloadVerdict.kt`): only a fresh proven-`Dead` verdict hides a
search card (:104-107); `Live` cards float to the top and get a caption (:138-152, :112-122);
`Unreachable` renders as a normal card. HomeScreen executes this via
`visibleOrdered` + `captionFor` (HomeScreen.kt:458-469, 589-591).

### 6.7 Persistence keys

Two prefs files are used by the UI layer plus per-feature stores:

| Store | Keys | Read/written by |
|---|---|---|
| `downloader_settings` | `pref_theme_mode`, `pref_last_tab`, `pref_downloads_sort`, `splash_full_shown`, `pref_torrent_peers`, `pref_instant_social`, `pref_clipboard_detect`, plus all AppSettings keys (AppSettings.kt:51-101) and MediaPlayerPrefs keys | MainActivity, MainScaffold callback, DownloadsScreen, AppSettings, MediaPlayerPrefs |
| `anon_serverless_prefs` | `last_rules_sync`, `last_ytdlp_update` | AnonApp.kt:79-104 |
| `player_positions` | file-path keys | PlaybackPositions |
| `downloader_settings` (AppSettings.load) | see §6.4 table | AppSettings.kt:48-75 (load) / :77-103 (save) |

AppSettings defaults differ from the engine defaults in one place: `hlsFragmentConcurrency = 8` in
the data class (AppSettings.kt:20) vs `pref_hls_fragments` getInt default 16 (:57) and engine field
default 16 (DownloadEngine.kt:83) — the persisted-read default wins at runtime; the data-class
default is only used if a caller constructs `AppSettings()` directly (gap G-11).

---

## 7. UI → behavior map

One line per important flow: **element → handler → ViewModel/engine → downstream**.

| UI element | Handler | VM/engine method | Downstream |
|---|---|---|---|
| Search field text | `onValueChange` | `viewModel.onQueryChanged` (or `handlePastedInput` when empty→URL) | debounced 350ms → `search` → `ProviderRegistry.searchFlow` + `ResultVerifier.submit` |
| Search IME/submit button | `search()` / `search` handler | `viewModel.search` | streaming results into `uiState.searchResults` |
| Filter chip | `onFilterSelected(key)` | `viewModel.onFilterSelected` | clears results, re-runs search with filter |
| Trending card tap | `onOpen(show)` | `viewModel.openEpisodeDrawer(show)` | `ResultVerifier.prioritize` + `ProviderRegistry.loadEpisodes(show)` |
| Trending Refresh | `loadTrending(force = true)` | VM | `TrendingFeed.fetch` |
| Trending Retry | same | VM | same |
| Trending infinite scroll | derived lastVisible ≥ total-3 | `viewModel.loadMoreTrending` | `TrendingFeed.fetch(page+1)` |
| Genre tile tap | `onOpen(category)` | `viewModel.openCategory` | cache paint + `CategoryFeed.fetch` (streaming) |
| View More tile | `onMore()` | `viewModel.openCatalog` | lazy per-row `ensureCategoryCards` → `loadCategory` |
| Catalog row header / SEE ALL | `onOpenGenre` | `viewModel.openCategory` | same as genre tile |
| Category page Refresh / Retry | `loadCategory(category, force=true)` | VM | forced crawl |
| Category grid infinite scroll | lastVisible ≥ total-6 | `viewModel.loadMoreCategory` | page+1 |
| Category back | `closeCategory()` | VM | grid closes; catalog visible again if open |
| Asian drama hero card | `openAsianDramaHub(region)` | VM | `AsianDramaFeed.fetch` with era |
| Drama era / status / genre chips | `selectDrama*` | VM | client-side `DramaTagClassifier.matchesFilter` re-filter (era also refetches) |
| Drama Retry / Reset | `refreshDramaFeed` / `selectDramaStatus(ALL)+selectDramaGenre(null)` | VM | refetch / re-filter |
| Search result card tap | `openEpisodeDrawer(show)` | VM | drawer opens, episodes load |
| Clipboard banner tap | `handlePastedInput(snippet)` | VM | router branch (drawer / social / magnet / direct / search) |
| Paste-link icon | `handlePastedInput(clipboard)` | VM | same |
| Power Paste (keyboard paste into empty field of a URL) | `handlePastedInput` | VM | same; field stays empty |
| Episode row checkbox / row tap | `onToggle` (+`View.tick()` haptic) | local selection only | selection set → footer CTA |
| "All"/"Remaining"/"S{n}"/"Clear" chip | `updateSelected` | local | selection set |
| Range field typing | `applyRange` | local | selection set (malformed input ignored) |
| "⬇ Download …" CTA | `enqueueSelected` / batch dialog | `engine.enqueue(...)` per episode (seriesUrl/seriesTotal/posterUrl stamped) | DownloadEngine queue → tasks flow |
| Episode row download circle / "Queue this episode" | `onDownloadSingle` (haptic confirm) | `engine.enqueue(...)` then `onDismiss()` | same |
| Episode row info circle | `expanded = !expanded` | local | details panel (locker/file preview) |
| Drawer retry pill | `openEpisodeDrawer(show, preselectRemaining)` | VM | re-scrape |
| Downloads card tap (Done) | `onPlay` | `onPlayTask(task)` → `activePlayingTaskId = task.id` | MainActivity builds `MediaPlayerContext` → MediaPlayerModal |
| Downloads card tap (Failed) | `onRetry` | `engine.retry(task.id)` | re-enqueue/resume |
| Row Pause / Resume (solid circles) | `onPause` / `onRetry` | `engine.pause(id)` / `engine.retry(id)` | engine |
| "RETRY" circle (failed) | `onRetry` | `engine.retry(id)` | engine |
| Kebab ribbon tap (any state) | `onOpenActions` | opens screen-local AlertDialog | Retry/Remove/Share/Delete paths |
| Shelf tile tap / long-press | `onPlay` / `onOpenActions` | player / actions dialog | same as above |
| Delete confirm | confirm | `hapticView.rejectHaptic()` + `engine.cancel(id)` | file + task removal |
| "Pause all" / "Resume all" | click | `engine.pauseAll()` / `engine.resumeAll()` | engine bulk |
| Sort menu item | `onSelectMode` | local `sortMode` + pref write | re-groups; changes the player's Next queue |
| Status filter chip | `onSelect` | local `statusFilter` | filters visible groups |
| "Download remaining N" (header/folder/inline) | click | `viewModel.openRemainingEpisodes(title, url, site)` | drawer with preselect |
| Download folder chevron / header | `onToggleExpand` | local `expandedShows` | expand/collapse |
| Back circle (Downloads) | `onBack` | MainScaffold lambda | tab → Search |
| Settings theme tri-cell | `selectable` | `state.themeMode = mode; onThemeChanged(mode)` | MainActivity themeMode state + `pref_theme_mode` + edge-to-edge restyle |
| Settings sliders | `onValueChangeFinished` | `state.persist()` → `viewModel.saveSettings` | 500ms debounced `engine.saveAllSettings` → prefs + engine reconfig |
| Settings switches | `onCheckedChange` | same | same |
| Filter Explicit switch | same | `saveSettings` explicit-filter branch | prunes painted feeds + cache |
| Sync Scraper & Site Logic | click | `DynamicRulesManager.syncFromGitHub` | rules version flow → label |
| Core channel pills | click | `YtDlpCore.setChannel` | persisted; drives update button + daily auto-update |
| Update Core | click | `YtDlpCore.updateNow` | toast outcome + version label |
| Send Fatal Error Reports switch | `onCheckedChange` | `RemoteLogger.setEnabled` | kill-switch for crash/stall uploads |
| Check for Updates | click | `UpdateChecker.check()` → `openInBrowser` | external browser |
| Share Activity Log | row click | `DebugLog.retainedLogFiles` + `CrashHandler.crashReportsText` on IO | redacted file → chooser |
| Storage rationale Grant | click | Settings deep link / runtime permission | window re-check on resume |
| Player Close / external | click | `onDismiss` / `playExternal` + dismiss | overlay closes |
| Player PiP | click | `enterPictureInPictureMode` | MainActivity callback updates `PlayerPipState` |
| Player seek slider | `onValueChangeFinished` | `exoPlayer.seekTo` | single seek on release |
| Player speed chip | cycle | `exoPlayer.playbackParameters` + `MediaPlayerPrefs.setPlaybackSpeed` | persisted |
| Player subtitles sheet pick | `onPick` | `applySubtitleByLabel` + `MediaPlayerPrefs.setSubtitleTrack` | persisted |
| Player audio sheet pick | `onPickTrack` | `TrackSelectionOverride` | session-only |
| Player volume drag / boost | vertical drag | `exoPlayer.volume` + `loudnessEnhancer.setBoostPercent` | volume persisted ≤100% |
| Player Next/Previous/auto-advance | `playNext`/`playPrev`/ENDED | `ctx.onPlayFile(path)` | MainActivity re-points active task |
| QuickShare Download | `onDownload` | `ensureStoragePermission` + `dispatchDownload` → `engine.enqueue` | task queued; sheet closes |
| QuickShare "Download instantly" switch | `alwaysInstant` | engine instant flag + pref on Download | future shares skip the sheet |
| SocialModal Download | click | `engine.setInstantSocial` (if changed) + `engine.enqueue` | task queued |
| Playlist sheet confirm | click | `viewModel.confirmPlaylist` | `engine.enqueuePlaylist` (grouped show) |
| Playlist search / Select all / Invert / range | local | — | selection set |
| Torrent picker confirm / All Safe / Close | `onDismiss(result)` | `TorrentFilePicker.resolve` | engine continues with selection or cancels |
| Social modal opened from paste/share | — | `activeSocialTarget` set | MainActivity hosts SocialModal |

**Accessibility inventory (verified presence/gaps):**
- Present: `contentDescription` on nearly all icons (nav items, back, refresh, play/pause/retry,
  close, share, delete, kebab "More actions", PiP, seek labels; thumbnails set `contentDescription`
  = title in `PosterImageOrFallback`); progress semantics on download bars
  (DownloadsScreen.kt:1441-1459, 2332-2337); `selectable(role = RadioButton)` for quality chips and
  theme cells; `toggleable(role = Switch)` for settings rows; `heading()` on section headers;
  `clearAndSetSemantics` on skeletons; `clickable(onClickLabel = ...)` on download cards.
- Gaps: `EpisodeRow`'s checkbox `contentDescription` is set only when selected ("Selected"/"Not
  selected" branch, EpisodeDrawer.kt:1448) and the clickable row itself has no semantics;
  `DrawerChip` has no `selectable` semantics (plain clickable); shelf tile has no content
  description beyond the play icon; the filter chips in HomeScreen rely on FilterChip defaults;
  DramaCardItem status badge ("END"/"AIRING") is text-only with no semantics; the range TextField's
  semantics are default; the volume HUD has no live-region announcement.

---

## 8. Gaps & risks

Documented, not fixed. Each item includes the evidence line refs.

**G-1 — No string resources / no localization.** `res/values/strings.xml` has exactly one entry,
`app_name` (strings.xml:3). Every other user-visible string is a Kotlin literal (e.g. HomeScreen
"Search series, anime, movies, torrents..." :247, "Trending Now" :760; Downloads "DOWNLOADS & MEDIA"
:453; Settings card titles; player toasts). Consequence: no translations, no lint string checks,
and copy changes are code changes. `grep stringResource` across `ui/` returns zero hits.

**G-2 — Dead ViewModel download helpers.** `MainViewModel.downloadEpisode` (:1172-1191) and
`downloadAllEpisodes` (:1193-1211) have **no callers** anywhere (verified by repo-wide grep for both
names in `ui/`). The EpisodeDrawer enqueues directly through `engine.enqueue` (:325-344, :1174-1192).
The VM versions also duplicate the enqueue argument set, so if the engine signature changes they will
silently rot.

**G-3 — Legacy Downloads card stack kept alive only for tests.** `DownloadCard` (DownloadsScreen.kt
:2116-2552), `StatusBadge` (:2554-2574), `BulkActionChip` (:2080-2114), and the pure string builders
`cardStatusDetail` (:1680-1723) / `statusDetailText` (:1729-1777) are public/internal but not
rendered by the live screen — the comment at :2073-2078 states `DownloadCard` is "the contract
surface for DownloadCardStateTest". Editing anything in that region risks
`app/src/test/.../ui/screens/DownloadCardStateTest.kt` (287 lines) and `DownloadStatusStyleTest.kt`
(154 lines). Same story for the legacy `groupCountColor(header)` overload
(DownloadsScreen.kt:984-989) and the 2-arg `downloadStatusStyle(status, neutralInk, neutralTint)`
(:1623-1640).

**G-4 — `genreTiles` state is maintained but never rendered.** `HomeUiState.genreTiles` (:72) is
hydrated from FeedCache (:285-296), fetched from `CategoryFeed.tilePosters` (:315-337) and persisted
(`FeedCache.saveTiles`, :325-329) — but `CategoryTilesGrid` renders the hardcoded
`GENRE_POSTER_PREVIEWS` TMDB URLs instead (HomeScreen.kt:952-981, used at :1109, :1146, :1175).
The network/disk work therefore has no visible effect on the current grid; a downstream change could
either wire the tiles in or delete the pipeline.

**G-5 — Duplicated components/palettes.** `PosterScrim`, `tileColor`, `TILE_COLORS_DARK/LIGHT` are
declared twice, privately: HomeScreen.kt:698-711 + :1777-1791 and AsianDramaExplorer.kt:359-401.
`TrendingCard` (HomeScreen) and `DramaCardItem` (AsianDramaExplorer) are near-duplicate card
designs. The Anonrode identity header block is duplicated between SocialModal.kt:77-130 and
QuickShareActivity.kt:408-464.

**G-6 — Drama genre selector missing.** `AsianDramaExplorer` receives `activeGenre` and computes
`genres = AsianDramaTaxonomy.genresFor(region, era)` (:86-88) but never renders a genre control;
`onSelectGenre` is only called by the empty-state "Reset filters" button (:312-317). Genre filtering
is reachable only if some other caller sets it — none exists today.

**G-7 — Unused MediaPlayerPrefs key.** `pref_window_brightness` has a getter/setter
(MediaPlayerPrefs.kt:51-61) that no UI reads or writes; the player has no brightness slider
(the VolumeHudPill only covers volume). A leftover from a planned gesture.

**G-8 — Legacy splash color constants unused.** `SplashBackground`, `SplashOnBackground`,
`SplashMuted`, `SplashElevated` (Theme.kt:116-119) have no call sites (the live splash uses
`DarkSplashColors`/`LightSplashColors`).

**G-9 — Unreferenced drawable.** `res/drawable/ic_anon_mark.xml` (49 lines) is referenced nowhere:
verified by grepping `ic_anon_mark` across `res/` (0 hits) and `R.drawable` across `java/`
(only `ic_notification` in DownloadService.kt:155/221/267/273 and `ic_anon_monogram` in
HomeScreen.kt:178).

**G-10 — Default-value drift: HLS fragments.** `AppSettings` data-class default
`hlsFragmentConcurrency = 8` (AppSettings.kt:20) vs its own load default 16 (:57) vs engine field
default 16 (DownloadEngine.kt:83). Runtime behavior is consistent (persisted value wins), but the
data-class default is a trap for any new caller constructing `AppSettings()`.

**G-11 — Filename/composable mismatch.** `ui/screens/SettingsSheet.kt` contains `SettingsScreen`
(:243) — there is no `SettingsSheet` symbol anymore. Grep-driven refactors (or an AI agent told to
"edit SettingsSheet") will miss the real host.

**G-12 — `EpisodeItem.isDownloaded` is never used.** The drawer computes downloaded/queued state
live from `engine.tasks` by show title (EpisodeDrawer.kt:222-228). `isDownloaded` (Models.kt:36) is
rendered nowhere, so providers that set it have no effect on the row's "✓ Downloaded" pill.

**G-13 — Unused player overload.** `MediaPlayerModal(filePath, title, onDismiss)`
(MediaPlayerModal.kt:288-297) has no call site; MainActivity calls the `MediaPlayerContext`
overload (:280).

**G-14 — Deprecated torrent-picker API retained.** `TorrentFilePicker.requests` getter
(TorrentFilePicker.kt:56-57) and `@Deprecated consume()` (:102-108) are unused; the live path is
`requestState` + `resolve`.

**G-15 — Suppressed unused parameters.** `HomeScreen(onOpenDownloads, onOpenSettings)` are
unused (`@Suppress("UnusedParameter")`, HomeScreen.kt:68) — vestigial from the pre-tab layout.

**G-16 — Settings state snapshot can overwrite externally-changed settings.**
`rememberSettingsState` is keyed only on `viewModel` (SettingsSheet.kt:209-222), so all 22 fields
are captured once; `persist()` then writes the FULL snapshot (:153-180). If a setting is changed
outside the screen while Settings stays composed (e.g. the QuickShareActivity's instant-download
checkbox writing `pref_instant_social`, QuickShareActivity.kt:189-198, or SocialModal's
`engine.setInstantSocial`, SocialModal.kt:259-261), flipping ANY other control in Settings persists
the stale value back over it. Only `themeMode` was given an explicit re-sync (SettingsSheet.kt:259).

**G-17 — UI-unreachable settings ranges.** Max Concurrent slider is 1..5 (SettingsSheet.kt:997) while
`AppSettings.save/load` coerce the same key to 1..6 (AppSettings.kt:51, :79) — value 6 is settable
only by editing prefs out-of-band.

**G-18 — Filter-count vs visible-row mismatch in Downloads.** `filterCounts` counts the whole task
list (:338-349) including shelf items and QUEUED tasks that may not render as rows in the current
mode; group/shelf rendering removes shelf items from groups (:263-266). Chips can therefore show
counts higher than what the list shows (e.g. "All N" vs N minus queued-with-no-group).

**G-19 — Mixed selection languages.** Three different selected-state treatments coexist:
`QualityChipRow` = AccentViolet fill + white text (QualityChipRow.kt:56-64); `SegmentCell` =
AccentPrimary fill + BackgroundDark ink (SegmentedBar.kt:69); HomeScreen `FilterChip` = white-ish
fill in dark / AccentPrimary in light (HomeScreen.kt:433-443). PlaylistPickerSheet uses
QualityChipRow (:265) while its sibling SocialModal uses SegmentBar (:199-209) for the same quality
choice.

**G-20 — Hardcoded external assets.** Genre tile posters and both drama hero backdrops are hardcoded
`image.tmdb.org` URLs (HomeScreen.kt:952-981, :1874, :1955). If those assets 404 or TMDB blocks the
UA, the tiles degrade to the gradient fallback (by design) but the hero cards show the plain
gradient only.

**G-21 — Accessibility gaps.** Enumerated at the end of §7: `DrawerChip` lacks selectable semantics,
`EpisodeRow`'s checkbox description only exists in the selected branch (EpisodeDrawer.kt:1448),
shelf tiles and drama badges are not described, and several icon-only buttons (e.g. the shelf play
overlay) have minimal descriptions.

**G-22 — Comments referencing removed code.** DownloadsScreen retains removal tombstones
(:1574-1580, :1832-1834) and SettingsSheet retains "section retired" notes (:1163-1168, :1313-1315,
:1504-1506) — harmless but they can mislead a search for those composables. No `TODO`/`FIXME`
comments exist anywhere under `ui/` (verified by grep).

**Unverified / not fully traceable within this pass:**
- The design mockups (`ui-proposal-downloads-v6-drawer.html`, `UI_OVERHAUL_SPEC.md`) were not
  read; comments in code cite them, and token values were taken from the CODE, not the mocks.
- Provider-layer behavior beyond the call sites listed (what each provider returns) is out of scope;
  the doc records only how the UI consumes it.
- Real device rendering of the drawer detents / nested-scroll handoff was not executed (no build);
  all geometry claims come from the source math.

---

## 9. File inventory

Line counts from `wc -l` on the checked-out files.

### 9.1 App entry / state (non-ui package)

| Path (relative to repo root) | Lines | Role |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | 78 | activities, intents, permissions, service, provider |
| `app/src/main/java/com/anonrode/downloader/AnonApp.kt` | 191 | Application, engine owner, Coil loader factory, OTA sync |
| `app/src/main/java/com/anonrode/downloader/MainActivity.kt` | 538 | root activity, theme/system bars, splash, all root overlays |
| `app/src/main/java/com/anonrode/downloader/viewmodel/MainViewModel.kt` | 1313 | HomeUiState, playlist state, all UI-driven actions |
| `app/src/main/java/com/anonrode/downloader/data/models/Models.kt` | 116 | ShowCard, EpisodeItem, ShowDetails, DownloadTask, TaskStatus |
| `app/src/main/java/com/anonrode/downloader/data/settings/AppSettings.kt` | 126 | settings model + prefs keys/defaults + RAM tier |
| `app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt` | (large, not fully read) | engine; UI reads `tasks` + the @Volatile settings fields (66-97) and calls enqueue/pause/retry/cancel/saveAllSettings |
| `app/src/main/java/com/anonrode/downloader/providers/CategoryFeed.kt` | — | `Category`, `CATEGORIES` (79+), `GenreTile` (154), `tilePosters` (363) |
| `app/src/main/java/com/anonrode/downloader/providers/AsianDramaTaxonomy.kt` | — | `DramaRegion/Era/StatusFilter/Genre`, `AsianDramaTaxonomy.genresFor`, `DramaTagClassifier` |
| `app/src/main/java/com/anonrode/downloader/pipeline/DownloadVerdict.kt` | 153 | `Verdict` + `VerdictPolicy` (search reveal/caption policy) |
| `app/src/main/java/com/anonrode/downloader/pipeline/PlaylistPicker.kt` | — | playlist parsing/estimation used by PlaylistPickerSheet |
| `app/src/main/java/com/anonrode/downloader/providers/ProviderRegistry.kt` | — | `searchFlow` (178-180, Nollywood-filtered), `loadEpisodes` |
| `app/src/main/java/com/anonrode/downloader/util/ExplicitContentFilter.kt` | 220 | explicit feed filter |
| `app/src/main/java/com/anonrode/downloader/util/NollywoodFilter.kt` | 93 | OTA Nollywood suppression |
| `app/src/main/java/com/anonrode/downloader/util/UrlExtractor.kt` | — | `firstUrl` / `isLikelyUrl` used by Home/MainActivity/QuickShare |
| `app/src/main/java/com/anonrode/downloader/service/DownloadService.kt` | — | notification UI (uses `ic_notification`) |

### 9.2 `ui/components/` (10 files)

| Path | Lines | Role |
|---|---|---|
| `.../ui/components/AsianDramaExplorer.kt` | 480 | K/C-drama explorer page |
| `.../ui/components/DownloadsSorter.kt` | 283 | sort/bucket/queue/stats pure logic |
| `.../ui/components/MainScaffold.kt` | 341 | bottom-nav scaffold + tab switcher + badge |
| `.../ui/components/MediaFallback.kt` | 138 | `PosterFallbackWell` / `PosterImageOrFallback` |
| `.../ui/components/MediaPlayerModal.kt` | 2174 | full-screen player + sheets + LoudnessEnhancer + image viewer |
| `.../ui/components/MediaPlayerPrefs.kt` | 72 | player prefs store |
| `.../ui/components/PlaybackPositions.kt` | 71 | per-file resume positions |
| `.../ui/components/QualityChipRow.kt` | 81 | equal-width quality chips |
| `.../ui/components/SegmentedBar.kt` | 94 | `SegmentBar` / `SegmentCell` |
| `.../ui/components/Skeleton.kt` | 259 | shimmer skeletons + reduce-motion helpers |

### 9.3 `ui/screens/` (10 files)

| Path | Lines | Role |
|---|---|---|
| `.../ui/screens/DownloadsScreen.kt` | 2620 | Downloads tab (cards, filters, sort, dialogs, shelf, legacy card) |
| `.../ui/screens/EpisodeDrawer.kt` | 1937 | two-detent episode sheet, rows, enqueue |
| `.../ui/screens/HomeScreen.kt` | 2018 | search + trending + genre tiles + category/catalog overlays |
| `.../ui/screens/PlaylistPickerSheet.kt` | 418 | YouTube playlist picker |
| `.../ui/screens/QuickShareActivity.kt` | 685 | share-sheet activity + `QuickShareCard` |
| `.../ui/screens/SettingsSheet.kt` | 1759 | `SettingsScreen` hub + 5 drawers |
| `.../ui/screens/SocialModal.kt` | 294 | social link download sheet |
| `.../ui/screens/SplashContent.kt` | 367 | splash Canvas + wordmark + progress |
| `.../ui/screens/SplashMotion.kt` | 211 | pure splash physics |
| `.../ui/screens/TorrentFilePicker.kt` | 228 | engine↔UI bridge + file-selection dialog |

### 9.4 `ui/theme/` and `ui/util/` (5 files)

| Path | Lines | Role |
|---|---|---|
| `.../ui/theme/Theme.kt` | 260 | AnonColors palettes, splash colors, M3 schemes, typography mapping |
| `.../ui/theme/Tokens.kt` | 131 | Spacing, Radius, Motion, Type |
| `.../ui/util/Haptics.kt` | 36 | tick / confirmHaptic / rejectHaptic |
| `.../ui/util/PressScale.kt` | 43 | `Modifier.pressScale` |
| `.../ui/util/TitleFormatting.kt` | 144 | title/season/episode sanitizers |

### 9.5 Resources (all under `app/src/main/res/`)

| Path | Lines | Role |
|---|---|---|
| `values/strings.xml` | 4 | only `app_name` |
| `values/colors.xml` | 7 | splash colors (light) |
| `values/styles.xml` | 45 | 3 themes (app / splash / dialog) |
| `values-night/colors.xml` | 7 | splash colors (dark) |
| `values-night/styles.xml` | 24 | dark-mode variants |
| `xml/backup_rules.xml` | 7 | exclude logs from backup |
| `xml/data_extraction_rules.xml` | 11 | exclude logs from cloud/transfer |
| `xml/file_paths.xml` | 7 | FileProvider paths |
| `xml/network_security_config.xml` | 25 | cleartext allowlist |
| `layout/player_modal_texture_view.xml` | 12 | PlayerView (TextureView) for the player |
| `drawable/ic_anon_mark.xml` | 49 (unused) | squircle brand mark |
| `drawable/ic_anon_monogram.xml` | 33 | bare monogram (splash + header) |
| `drawable/ic_launcher_background.xml` | 11 | adaptive bg |
| `drawable/ic_launcher_foreground.xml` | 34 | adaptive fg |
| `drawable/ic_launcher_monochrome.xml` | 34 | themed icon |
| `drawable/ic_notification.xml` | 28 | notification small icon |
| `mipmap-anydpi-v26/ic_launcher.xml` | 6 | adaptive icon |
| `mipmap-anydpi-v26/ic_launcher_round.xml` | 6 | round adaptive icon |

### 9.6 UI-related tests (contract surfaces — do not break casually)

| Path | Lines | Pins |
|---|---|---|
| `app/src/test/.../ui/components/DownloadsSorterTest.kt` | 414 | sort/bucket/queue rules |
| `app/src/test/.../ui/components/SubtitleAutoPickTest.kt` | 104 | `autoPickSubtitleIndex` |
| `app/src/test/.../ui/screens/DownloadCardStateTest.kt` | 287 | Robolectric render contract of legacy `DownloadCard` |
| `app/src/test/.../ui/screens/DownloadStatusStyleTest.kt` | 154 | status word/glyph/chroma mapping |
| `app/src/test/.../ui/screens/SplashMotionTest.kt` | 168 | splash physics/pose invariants |
| `app/src/androidTest/.../EmulatorGatingSmokeTest.kt` | 384 | emulator gating smoke |
| `app/src/androidTest/.../MainSmokeTest.kt` | 68 | app smoke test |
| `app/src/androidTest/.../RegexCompatCatalog.kt` | 229 | regex catalog used by the compat test |
| `app/src/androidTest/.../RegexEngineCompatTest.kt` | 99 | device regex compatibility (guards `RegexCompatCatalog` additions; see EpisodeDrawer note :1792-1793 about avoiding new regexes) |

### 9.7 Coverage note

Every `.kt` file under `ui/` (25), plus `MainActivity.kt`, `AnonApp.kt`, `MainViewModel.kt`,
`data/models/Models.kt`, `data/settings/AppSettings.kt`, the whole `AndroidManifest.xml`, and every
file under `app/src/main/res/` were read for this document. Engine internals were read only where the
UI touches them (`DownloadEngine.kt:66-97`, :115, :296-311, :418-460, :557-588, :699, :820, :3289,
:3331); provider/pipeline code was read only for UI-consumed contracts (verdict policy, category
taxonomy, playlist picker, filters). Anything beyond that is marked unverified in §8.

