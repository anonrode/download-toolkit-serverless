# OTA Signing Rotation — ROOT pin + LEAF keys (step 7)

Status: built 2026-10-15, gates green (verification matrix 16/16, Kotlin syntax runner exit 0). Prior-art model: Mozilla Firefox Remote Settings §1.4 of `scratch/ota_rule_engine_design.md` — **pinned ROOT in the app, delivered LEAF inside the envelope, dual-pin overlap, recovery-ladder on failure**. Senior review doc: §3.4/§7 of the same design doc.

## 1. Why this exists (the decision it closes)

Before tonight the fleet was single-pin: ONE ECDSA P-256 public key was baked into the APK; every key change (compromise, expiry, org split) required a full app release, and a lost private half meant losing the update channel for everyone until a new release shipped.

The user ordered everything built before tonight's single release push, step 7 included. **The open question from the design doc (§6, item 3 — "who holds the cold key, is a rotation drill acceptable?") is answered by this build and its custody model below.** The alternative (NOT building) is recorded honestly: the fleet would stay single-pin, leaf rotation would still cost an app release, and a compromised/stale root could only be retired by shipping a new APK and hoping every device updates.

From this build on: rotate the leaf at any time by publishing a new envelope — zero app change, one sync cycle, fleet-wide.

## 2. Key hierarchy

```
ROOT-1  (legacy, 2026-08) — pinned in APK (pin slot 1)  — private half: GitHub secret OTA_SIGNING_PRIVATE_KEY
   |                                                            + local maintenance copy (pre-existing custody)
   |  (still signs payload sigs directly — "legacy mode" envelopes)
   |
ROOT-2  (new, tonight)   — pinned in APK (pin slot 2)  — private half: COLD, this PC only, keys/ (never committed, never leaves)
   |
   +-- LEAF-1  (demo/first leaf)      signs payload sigs via envelope "chain"
   +-- LEAF-2  (CI leaf candidate)    likewise — promote to the CI secret OTA_LEAF_PRIVATE_KEY
   +-- LEAF-3  ... (future, drill)    generate + publish, zero app change
```

Envelopes keep the v2 shape. A payload WITHOUT `chain` is signed by a ROOT directly (legacy mode, byte-for-byte today's format). A payload WITH `chain` is signed by a LEAF:

```json
{"v":2,"alg":"aes-128-cbc","iv":"<hex>",
 "payload":"<b64 AES-CBC ct>",
 "sig":"<b64 ECDSA-P256-SHA256 by the LEAF, over the payload string>",
 "chain":{"leafPubB64":"<leaf SPKI-DER base64>",
          "rootSig":"<b64 ECDSA-P256-SHA256 by a pinned ROOT over the CANONICAL bytes of leafPubB64 = the exact ASCII bytes of that base64 string>",
          "alg":"P256-SHA256"}}
```

The envelope is additive-only: legacy key order `v,alg,iv,payload,sig` is unchanged; `chain` is appended last. Any build that predates this change verifies a chain envelope as "no chain key → legacy sig check → sig is not by root-1 → REJECT" (safe), and this build refuses a chain envelope unless the leaf is vouched by a pinned root (also safe). A malformed/failing chain NEVER falls back to legacy acceptance.

## 3. What is pinned in the APK

`app/src/main/java/com/anonrode/downloader/data/rules/DynamicRulesManager.kt`:

| Pin | Constant | Public key (SPKI-DER base64) | SHA-256 fingerprint (SPKI DER) |
|---|---|---|---|
| ROOT-1 (slot 1, bytes untouched) | `rulesSigningPubB64` | `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn6hCCQBtu6YyguDIMtKvefov9uwD0uN9JP0HKkYUJB1wSL3Q928+lQ==` | `10:BC:10:5C:8B:28:4B:1F:C6:FF:D1:3F:78:6E:FC:FB:77:C7:F7:B0:30:2E:64:B2:7E:E9:3B:D8:FA:FD:1E:AC` |
| ROOT-2 (slot 2, new) | `ROTATION_ROOT2_PUB_B64` | `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEr6faxWOcnYb9PN7Qtb1kZPm55ZzZ0tlUTpacE7BHBwowx+dxVpqHVLHAE6JiU6pDyGEvpta/+vSMxjjNVM5Rag==` | `3F:F1:BB:49:09:F4:2A:A1:E3:78:66:50:3B:C0:C2:1C:D4:3B:DA:47:15:AB:25:47:09:A5:3F:A8:10:E6:0C:A0` |

The mirror pin set also lives in `scripts/encrypt_rules.py` (`OTA_PUB_B64`, `ROOT2_PUB_B64`, `ROOT_PINS_B64`) so CI and local tooling verify exactly what the app verifies. The pure verifier is `SignatureChain.kt` (chain mode + legacy mode, "verify first that succeeds" over the pin list). Fingerprints are `sha256(SPKI DER)` rendered as colon-hex — recompute any time with `python scripts/encrypt_rules.py --inspect <pem>`.

## 4. Custody instructions

- **ROOT-2 private key** — cold. Lives ONLY at `<repo>/keys/ota_root2_private_key.pem` (this PC; `keys/` is gitignored twice over: the whole dir AND `*.pem`). Never paste it into CI, never commit it, never serve it. It signs ONLY the occasional `rootSig` inside a newly built envelope. If this PC dies and there is no backup copy of this file, root-2 rotation capability dies with it — keep ONE offline copy (encrypted USB / password manager attachment) and record the fingerprint above when you make it.
- **ROOT-1 private half** — unchanged custody: GitHub Actions secret `OTA_SIGNING_PRIVATE_KEY` (+ the pre-existing maintenance-folder copy). Nothing moved.
- **LEAF-2** — promote to the CI secret `OTA_LEAF_PRIVATE_KEY` for chain builds. Leaf keys are meant to be cheaper: if leaked, stop using it and publish the next leaf — clients stop trusting it the moment envelopes stop carrying it, and revocation needs NO app release. Chain-mode building from CI: `python scripts/encrypt_rules.py --root-key <root2 pem> --leaf-key <leaf pem> --in scraper_rules.json --out scraper_rules.json.enc`.
- **LEAF-1** — demo leaf left in `keys/` alongside its witness envelopes under `scratch/signing/`. Rotate away from it freely.
- Envelope-build keys never travel: `keys/` is generated by `python scripts/encrypt_rules.py --generate-root` / `--generate-leaf --root keys/ota_root2_public_key.pem [--leaf-name leafN]`; the generator REFUSES to overwrite an existing key pair (a cold root is never silently clobbered).

## 5. Rotation drill (the proof tonight, repeatable)

The drill used no app change and none of the pinned bytes changed:

1. `--generate-leaf --root keys/ota_root2_public_key.pem --leaf-name leaf2`
2. Build a fresh chain envelope with leaf-2 + root-2 (`--root-key --leaf-key`).
3. Verify it against the SAME dual-pin set. PASS (matrix row 11: "leaf-2 under SAME root-2 verifies (no app change)").

To rotate for real: generate leaf-3 the same way, publish its envelope — every device that has this build accepts it on next sync. Reproduce the full matrix any time with `python scratch/signing/test_signing_rotation.py`.

## 6. Failure ladder on bad signatures (on-device order)

1. **Try the next source.** A signature failure at one delivery-chain source (raw.githubusercontent → jsDelivr → statically → self-box) journals `STAGE_VERIFY` and continues the chain — a poisoned mirror never blocks a clean one.
2. **Keep last-known-good if it verifies.** The cached `dynamic_scraper_rules.enc` (and the two-deep `rules_prev_1/2.enc` restore ladder) was only ever written after full verification; on total failure the cached envelope re-verifies on restore and keeps serving. Legacy/bundled rules never lose to a failed fetch.
3. **Keep cached state.** If even the cache fails verification (tampered cache), decrypt returns null and the app keeps its already-parsed active state — a signature failure REJECTS the payload and never silently falls back to accepting anything unsigned. Never keep unsigned local hints.

## 7. Honest note — layering

**AES-128-CBC confidentiality stays obfuscation-grade** (key/IV ship in the APK; it stops repo-watchers reading the playbook, not a determined reverse engineer). **The SIGNATURE is the real integrity boundary**: a repo hijack, CDN MITM, or cleartext self-box attacker can still READ payloads, but cannot forge one accepted by the fleet. The pinning scheme above exists for exactly that layer, and for making key compromise an incident, not an emergency release.

## 8. Failback / revocation of this decision

If the user ever revokes tonight's decision, the exact rollback is recorded here:

1. Future app build: delete `ROTATION_ROOT2_PUB_B64` (and stop appending root-2 to `signingRootPins()`). Root-1-only behavior returns byte-identically to pre-step-7 semantics; root-2-signed/chain payloads then reject like any unknown-key signature. Nothing else changes — legacy envelopes were never affected.
2. Stop publishing chain envelopes; revert `scraper_rules.json.enc` to a root-1-signed legacy envelope byte-format (default `encrypt_rules.py` run with no chain flags — untouched code path).
3. Publication-only (no APK): keep pinning root-2 but simply always publish root-1-signed legacy envelopes — the dual-pin build accepts both forever.

Old clients that only know root-1 keep last-known-good (matrix row 3 proves they REJECT root-2 material rather than accept state) — the standard, accepted cost of introducing any new root, retired only when telemetry shows chain payloads at ~100% of revs.

## 9. Gates (2026-10-15, verbatim tail)

- `python scratch/signing/test_signing_rotation.py` → `===== 16/16 PASS =====` (matrix in §5 / report).
- `python scripts/verify_kotlin_syntax.py` → `ALL TARGET KOTLIN FILES VERIFIED CLEAN WITH ZERO SYNTAX/BRACE ERRORS!` (exit 0).
- `check_file` direct: `SignatureChain.kt → []`, `DynamicRulesManager.kt → []`.
