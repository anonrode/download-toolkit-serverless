# PHASE 2 IMPLEMENTATION PLAN — OTA-completeness (make everything OTA-fixable)

Source of truth: `scratch/ota_rule_engine_design.md` §3 (architecture) + §5 (migration order).
This plan executes that design as whole, independently shippable slices. Nothing is half-shipped.

## Rules of engagement (non-negotiable)
1. **Additive-only.** Every payload/pack change must be ignored gracefully by already-shipped
   clients (v3.2.9 and the OTA-fetching fleet). Any breaking change would need a new v3 envelope —
   not used tonight. No remote executable logic EVER enters rules (fixed vocabulary only).
2. **No stubs/TODOs.** Every slice lands whole: code + validation + telemetry + syntax gates.
3. **Held tree.** No git commands except read-only status/diff. No gradle. Release push is the
   main agent's gate after all slices + agent work pass.
4. **File ownership:** executor owns `pipeline/**`, `data/rules/**`, `providers/RulesPipeline.kt`
   + NEW files. Others (UI agent/testers) own ui/** and scratch/**. Main agent owns scripts/**,
   publishing, .enc. Providers other than RulesPipeline.kt are NOT edited — family flags work
   through the existing data getters so compiled fallback engages automatically.
5. **Never return candidate results to the user** while shadow-running (Scientist rule).

## Slice P2-1 — Per-family flags + kill switches (doc step 1)
- `data/rules/RulesFlags.kt` (new): parses top-level `"flags"` object:
  `family → {enabled: Bool, percent: Int 0..100, minApp: String?, expiresMs: Long?, shadow: Bool}`.
  Unknown family keys → journal + skip (AdGuard trust-level lesson). Unknown SUB-keys → skip that
  family (typo hazard). Flags are durable only inside the payload: a payload that omits flags
  resets everything to defaults (Firebase in-app-defaults floor).
- Default state per family: enabled (`true`), percent `100`, shadow `false`.
- Families (ids = exact top-level payload keys used by `parseRulesJson`): `domains`, `mirrors`,
  `sites`, `resolvers`, `directMediaExtensions`, `hostPolicies`, `urlTemplates`, `knownDead`,
  `lockerHosts`, `searchStrategies`, `pipelines`, `dynamic_providers`.
- Enforcement (no provider edits): when a family flag disables a family, the family's parsed
  section publishes EMPTY (activePipelines, activeResolverConfigs, dynamicProviders, …),
  exactly like today's "malformed/missing section" case, so every compiled fallback engages
  with zero call-site changes. knownDead/lockerHosts disabled → publishes empty lists (their
  only use is pre-filtering; removing them loosens, never breaks).
- `minAppVersion` per family: `minApp` field compared with the app's build version; below it →
  family publishes empty + journal skip (old-browser-on-new-pack semantics).
- Risks/mitigations (doc §5 step 1): typo'd fleet-wide kill → unknown-key rejection + family-id
  check; old apps ignore flags entirely → only publish payloads that depend on flag semantics
  for FIXES (never for safety).

## Slice P2-2 — Integer `rev` monotonic rejection + last-known-good cache (doc step 2)
- Payload gains optional `"rev"` (integer). Client persists `activeRev` in prefs.
- On OTA payload: `rev` present and `<= activeRev` → REJECT (DebugLog.engine + telemetry),
  stay on current atomically-published state. A payload WITHOUT rev passes (legacy tolerant)
  but never sets activeRev.
- Keep last N=2 previous signed envelopes on disk (`rules_prev_1.enc`, `rules_prev_2.enc`,
  raw verified bytes) for the restore ladder (CRLSet/AdGuard registry semantics).
- Sticky source ordering: persist last successful source index; next sync tries it first, then
  canonical order. Empty/HTML-as-error check BEFORE decrypt (uBO rule) in the chain fetch
  (Html/JSON detect: content-type + first-byte sniff).
- Risks: stale-CDN rev at scale now REJECTS (desired) and could read as "updates stopped" →
  telemetry names the source + serves-before/after revs; sticky ordering reduces its frequency.

## Slice P2-3 — Load-time validation caps, WARN phase (doc step 3)
- `data/rules/RuleCaps.kt` (new): whole-payload ≤ 2MB decrypted; regex pattern length ≤ 300
  (matches the shipped publish MAX of 300 — client and publish use the SAME constant);
  `percent` 0..100; arrays against existing MAX_STEPS/MAX_SOURCES; endpoint URL fields must be
  http(s) (extend existing remote_logging_url guard to every URL-bearing family field).
- Phase = WARN for one rev cycle: caps violations journal + telemetry count each + DebugLog;
  enforcement flag flips to enforce after field data says clean (publish-side caps land in
  encrypt_rules.py simultaneously as hard validation by main agent).

## Slice P2-4 — Shadow-run harness for the behavior family (doc step 5, Scientist pattern)
- `pipeline/ShadowRunner.kt` (new): for `pipelines` family when flag `shadow: true` and
  family `enabled` — run candidate pipeline AFTER the compiled control for the same site task:
  user result ALWAYS from control; candidate outcome recorded.
- Sampling: ≤10% of eligible runs/site/day (persist counter in prefs keyed by day), skip when
  the site/host is under a 429 cooldown or HostHealth backoff (never double-hammer a fragile
  site); respect ResolveBudget caps.
- Comparison record: site, family, outcome class match (success/no-result/error), quality class
  match, wall-time both sides, request count both sides → RemoteLogger via the existing
  OtaFetchDiagnostics fallback sink path, ≤3 records/session.
- Risks per doc: double request load (sampling + backoff skip), both-right mismatches
  (normalized classes, not strings).

## Slice P2-5 — Stable-bucket percent + install UUID (doc step 6 client half)
- Install-scoped `app UUID` generated once into prefs on first init (no advertising ID).
- `stableBucket(uuid, family) = (sha256(uuid + "/" + family) mod 100)`; family applies when
  `percent > bucket` (sticky across syncs/restarts; churn only on reinstall — accepted noise).
- Promotion ladder is payload-driven (flags are data): 5 → 25 → 100 published by main agent
  per family after shadow records look clean; control stays compiled ≥2 releases at 100%.

## Slice P2-6 — Activation + delivery telemetry (doc 3.8 partial)
- On every successful activation: one RemoteLogger record — rev, version, applied/skipped
  family-entry counts (existing journals), winning source, duration.
- On every chain attempt: per-source success/failure counters already exist in
  OtaFetchDiagnostics Attempt records → roll-up into the same record (cap 1/24h aggregate).
- never logs content or URLs beyond host names.

## Slice P2-7 — Diff-based state invalidation upgrade (doc step 4 refinement)
- On activation with changed rev/version: diff old vs new family target-hosts (pipelines' hosts,
  knownDead, hostPolicies, lockerHosts); `HostHealth.clearBackoffsFor(hostSet)` clears backoff
  judgment only for CHANGED/removed-policy hosts. Fall back to the existing coarse
  clearBackoffsOnNewRules() only when the payload provides no rev (legacy payloads can't be
  diffed). Keeps "clear only when policy changed" so a truly-dead host's live backoff isn't
  wiped for unrelated updates.
- Fine print: hosts removed entirely from knownDead/lockerHosts MUST have their backoff cleared
  immediately (the confirmed "remembers-dead-after-fix" bug).

## Deferred (NOT tonight, explicit)
- **Step 7 signing rotation scaffold (root-pin + leaf + dual-pin):** deferred pending the
  open question on offline root-key custody (doc §6 Q3). Current single-pin ECDSA stays.
- **Step 8 decompile-down:** only after a family runs 100% for ≥2 stable releases.
- **Envelope v3:** not needed; all today's changes are additive on v2.

## Publish-side (main agent, in encrypt_rules.py)
- Authoring schema: optional `rev` + `flags` generation; hard validation of the same caps
  (P2-3) at publish; flags family-id whitelist identical to client.

## Open questions — operating defaults (user may veto in the morning)
Q1 shadow telemetry = 1 aggregate per site+family/day (quiet). Q2 app UUID = yes.
Q5 first shadow family = `pipelines` on ONE proven site (anitaku), others added later.
Q6 stale-but-functional banner = silent (no UI work). Q7 keep every rev in repo (cheap).
Q8 bundle a knownDead+domains seed in APK = yes, populated at each release.

## Done-when (all slices)
- All files syntax-gate clean (verify_kotlin_syntax + check_file direct for non-listed files).
- grep invariants: no TODO/stub markers added; `ShadowRunner` always routes control to user.
- Full PASS report per slice → release gate (push + tag) may proceed afterward.
