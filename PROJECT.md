# AnonDownloader (Serverless) — Project Roadmap & To-Do List

## Active Architecture
- **Multi-Provider Engine**: Parallel search, multi-server iframe racing (`ResolverRegistry.resolveAny`), and transfer-time mirror cooldowns (`MirrorPool`).
- **Cluster Isolation**:
  - `ASIAN_DRAMA_CLUSTER`: `asianc`, `dramarain`, `dramakey`, `pluto`, `nepu`
  - `WESTERN_CLUSTER`: `nkiri`, `9jarocks`, `naijavault`, `naijaprey`
  - `Progress Banking Guard`: Tasks with banked bytes (`bytesLanded > 0`) are locked to their source/mirror to prevent byte-stream corruption across incompatible encodes.

---

## Upcoming / To-Do Items

- [x] **Expand Asian Drama Fallback to `nkiri` & `9jarocks`**: *(implemented, pending CI run — no local toolchain on this machine)*
  - **Objective**: Allow `attemptCrossProviderFailover()` to search `nkiri` and `9jarocks` as secondary fallback targets when all dedicated Asian drama providers (`asianc`, `dramarain`, `dramakey`) fail to resolve or start.
  - **Rationale**: Both `nkiri` and `9jarocks` host extensive K-drama and C-drama collections with high-speed direct links.
  - **Required Guards**:
    - Preserve the 4-layer guard: country tag check (`extractCountry()`), release year match, and strict season/episode normalization.
    - Handle episode numbering discrepancies (e.g. 9jarocks season-batch `105` notation vs. Asian drama 100+ continuous daily episodes) via `parseCandidateSeasonAndEpisode()`.
    - Keep unidirectional safety: Asian drama searches can reach Nkiri/9jarocks as secondary fallbacks, but generic Western shows on Nkiri/9jarocks must remain shielded from querying dedicated Asian sites.

- [x] **Apply `NameSanitizer.cleanTitle()` to Trending & Genre Feeds (`TrendingFeed.kt`)**: *(implemented, pending CI run — no local toolchain on this machine)*
  - **Objective**: Sanitize card titles across Trending carousels and Genre rows by running scraped titles through `NameSanitizer.cleanTitle().ifBlank { raw }` in `TrendingFeed.kt`.
  - **Target Parsers**:
    - `parseWpRestPosts()` (Nkiri, NaijaVault, NaijaPrey)
    - `parseRssItems()` (9jarocks)
    - `parseAsiancResults()` (AsianC)
    - `parseNepuResults()` (Nepu)
  - **Benefits**:
    - Strips noisy scraper tags from Home Screen UI (e.g. `[Episode 1-16 Complete]`, `(Korean Drama)`, `_Watch Online_`, `(Ep 1-8 Added)`).
    - Decodes unescaped HTML entities (`&#8211;` -> `–`, `&amp;` -> `&`).
    - Drastically improves cross-site deduplication in `CategoryFeed.mixCards()` and `TrendingFeed.mergeRoundRobin()` by aligning provider title variants of the same show.
  - **Required Guards**:
    - Use `.ifBlank { rawTitle }` fallback so titles never vanish.
    - Preserve raw taxonomy terms (`wp:term`, RSS categories) for `ExplicitContentFilter` and `confirmTerms` genre matching.
    - Ensure unit tests in `TrendingFeedParseTest.kt` pass and reflect clean titles.
