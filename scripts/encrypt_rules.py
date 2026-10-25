"""Scraper-rules OTA pipeline: validate -> encrypt -> sign -> envelope.

Produces the payload served at scraper_rules.json.enc (repo root of
download-toolkit-serverless). Envelope format v2:

    {"v": 2,
     "alg": "aes-128-cbc",
     "iv": "<32 hex chars, random per encryption>",
     "payload": "<base64 AES-CBC-PKCS7 ciphertext>",
     "sig": "<base64 DER ECDSA-P256-SHA256 signature over the payload string>"}

Step-7 signing rotation (scratch/ota_rule_engine_design.md §3.4/§7; Firefox
Remote Settings §1.4 model — pinned ROOT, delivered LEAF): an envelope may
additionally carry an ADDITIVE "chain" object, in which case "sig" is made by
the LEAF key and the leaf is vouched for by a pinned ROOT:

    {"v": 2, "alg": "aes-128-cbc", "iv": "...", "payload": "...",
     "sig": "<base64 ECDSA-P256-SHA256 by the LEAF over the payload string>",
     "chain": {"leafPubB64": "<leaf SPKI-DER base64>",
               "rootSig":   "<base64 ECDSA-P256-SHA256 by the ROOT over the
                             CANONICAL bytes of leafPubB64, i.e. the exact
                             ASCII/base64 string bytes as serialized>",
               "alg":       "P256-SHA256"}}

An envelope WITHOUT "chain" behaves exactly as before (payload signed by the
pinned key itself) — v2 compatibility is sacred: the legacy emission path is
byte-identical to pre-chain output.

The app pins BOTH root-1 (the historical key, bytes untouched) and root-2 and
verifies "first that succeeds"; a chain envelope under root-2 therefore needs
NO app release while leaves rotate (rotation drill: docs/SIGNING_ROTATION.md).

Security model:
- Confidentiality stays obfuscation-grade (AES key ships in the APK) — it
  stops casual reading of the scraping logic, not dedicated analysis. The
  SIGNATURE is the real integrity boundary.
- INTEGRITY/AUTHENTICITY is real: the payload is signed with an ECDSA P-256
  key whose private half exists ONLY as the GitHub Actions secret
  OTA_SIGNING_PRIVATE_KEY (root-1 legacy) or in the keys/ custody folder
  (root-2 / leaves — NEVER committed). The app embeds the public halves and
  rejects any payload that fails verification. A repo hijack or CDN MITM can
  no longer feed users fake rules.
- The IV is random per encryption (upgrades the historical fixed-IV CBC).

Usage:
  python encrypt_rules.py                # validate + encrypt + sign (if key found)
  python encrypt_rules.py --no-sign      # validate + encrypt only (legacy-style output)
  python encrypt_rules.py --gen-keys     # generate the legacy keypair locally
  python encrypt_rules.py --inspect <pem>            # print pub-b64 + fingerprint
  python encrypt_rules.py --generate-root            # ROOT keypair into keys/
  python encrypt_rules.py --generate-leaf --root <pem> [--leaf-name leaf1]
  python encrypt_rules.py --root-key <pem> --leaf-key <pem>   # chain envelope

Key lookup order for legacy signing: --key <pem path> |
$OTA_SIGNING_PRIVATE_KEY |
~/anon-serverless-app-maintenance-build/ota_signing_private_key.pem
(the dedicated maintenance folder OUTSIDE both repos — never committed).
Chain-mode keys live in <repo>/keys/ (gitignored) — private halves never
leave that folder except into CI secrets as documented.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import secrets as _secrets
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

RULES_KEY = bytes.fromhex("8f3a9c21d4e65b0789a2c4f6d1e3b5a7")   # 16 bytes (unchanged)

# CANONICAL copy lives here (scripts/) and is the one CI imports; there is no
# longer a probe/ mirror. The app-side counterpart is DynamicRulesManager.kt.
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVERLESS = REPO_ROOT
# Local dev: the keypair lives in ~/anon-serverless-app-maintenance-build/
# (outside both repos, gitignored by not being IN any repo).
# CI: signing uses $OTA_SIGNING_PRIVATE_KEY, never a file.
def _resolve_default_key_path():
    env_path = os.environ.get("OTA_SIGNING_KEY_FILE")
    if env_path and os.path.exists(env_path):
        return env_path
    candidates = [
        os.path.join(os.path.expanduser("~"), "anon-serverless-app-maintenance-build", "ota_signing_private_key.pem"),
        os.path.join(os.path.dirname(REPO_ROOT), "anon-serverless-app-maintenance-build", "ota_signing_private_key.pem"),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return candidates[0]

KEY_PATH = _resolve_default_key_path()

# PUBLIC half of the OTA signing keypair (safe to commit — it can only
# VERIFY, never sign). Matches the constant embedded in the app's
# DynamicRulesManager; used by CI to validate the committed .enc.
OTA_PUB_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn6hCCQBtu6YyguDIMtKvefov9uwD"
    "0uN9JP0HKkYUJB1wSL3Q928+lQ=="
)

# ---- step 7: ROOT pin set (dual-pin overlap) ------------------------------
# The APK verifies payloads against BOTH roots ("verify first that succeeds",
# docs/SIGNING_ROTATION.md). root-1 above is byte-untouched; root-2 is the
# rotation root generated 2026-10-15 (leaf keys rotate under it via the
# envelope "chain" object — zero app releases). Its PRIVATE half lives only
# in <repo>/keys/ (cold, gitignored). This public string is safe to publish:
# it can VERIFY, never sign.
ROOT2_PUB_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEr6faxWOcnYb9PN7Qtb1kZPm55ZzZ0tlUTpacE7BHBwowx+dxVpqHVLHAE6Ji"
    "U6pDyGEvpta/+vSMxjjNVM5Rag=="
)
# Pinned trust set for CI/fleet parity checks: [root-1, root-2].
ROOT_PINS_B64 = [OTA_PUB_B64, ROOT2_PUB_B64]
CHAIN_ALG = "P256-SHA256"
# Cold custody material (root-2 root, leaf keys) lives OUTSIDE the checkout:
#   - ANON_OTA_KEYS_DIR env override, else
#   - <parent of repo>/anon-ota-keys (the shipped location), else
#   - <repo>/keys (legacy; kept for existing checkouts).
# Private halves never ship and never enter git.
def _resolve_keys_dir() -> str:
    env_dir = os.environ.get("ANON_OTA_KEYS_DIR")
    if env_dir:
        return env_dir
    sibling = os.path.join(os.path.dirname(REPO_ROOT), "anon-ota-keys")
    if os.path.isdir(sibling):
        return sibling
    return os.path.join(REPO_ROOT, "keys")

KEYS_DIR = _resolve_keys_dir()


def pub_to_b64(pub) -> str:
    """SPKI-DER base64 of an EC public key — the exact string format the app
    pins and the envelope's chain.leafPubB64 carries."""
    der = pub.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode("ascii")


def pub_fingerprint(pub) -> str:
    """SHA-256 over the SPKI DER, colon-separated hex (the fingerprint format
    carried in docs/SIGNING_ROTATION.md and the pin comments)."""
    der = pub.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    h = hashlib.sha256(der).hexdigest().upper()
    return ":".join(h[i:i + 2] for i in range(0, len(h), 2))


def load_any_key(path: str):
    """Loads a PEM that may be a PRIVATE or PUBLIC key (custody inspection)."""
    with open(path, "rb") as f:
        data = f.read()
    try:
        return serialization.load_pem_private_key(data, password=None)
    except Exception:
        return serialization.load_pem_public_key(data)

# --- strict schema guards (a bad payload must be rejected BEFORE commit) ---
MAX_PLAINTEXT_BYTES = 512 * 1024
MAX_SELECTOR_LEN = 600
MAX_RULES_ARRAY = 64

# Phase-2 payload families — must equal the exact top-level keys
# DynamicRulesManager.parseRulesJson consumes (docs/PHASE_2_PLAN.md P2-1).
# v3.3.0 platform surfaces (plan §4.1) appended: announcements, update, ads,
# engagement — kept byte-identical to RulesFlags.FAMILY_IDS in the app.
FAMILY_IDS = [
    "domains", "mirrors", "sites", "resolvers", "directMediaExtensions",
    "hostPolicies", "urlTemplates", "knownDead", "lockerHosts",
    "searchStrategies", "pipelines", "dynamic_providers", "contentPolicy",
    "announcements", "update", "ads", "engagement",
    "downloader_routing", "engine_profiles", "proxy_unwrappers",
    "resolver_gates", "resolver_crypto", "permissive_tls_hosts",
    "hls_keep_referer_hosts", "streaming_url_markers",
    "preflight_passthrough_markers", "decoy_signatures", "failover_clusters",
]
FLAG_SUBKEYS = ("enabled", "percent", "minApp", "expiresMs", "shadow")
REQUIRED_TOP_KEYS_VERSIONED = ("version",)

# --- step-pipeline vocabulary (must mirror providers/RulesPipeline.kt) ---
# Closed vocabulary: anything not listed here is refused at validation time,
# so a typo'd primitive can never ship silently to the app. Adding a new
# primitive requires updating RulesPipeline.kt, this validator, and the
# probe harness in the same change.
PIPELINE_MAX_STEPS = 8
PIPELINE_MAX_SOURCES = 4
FIELD_SPEC_PREFIXES = ("literal:", "link:", "selector:", "attr:",
                       "field:", "var:", "template:")
# RulesPipeline.docField (used by items.meta.* and sectionGrouping.seasonSpec)
# evaluates at DOC scope: no anchor element and no JSON document, so
# self/link:/field: can never resolve there. A rule author must not be able to
# pass validation with a spec the executor will silently null.
DOC_FIELD_SPEC_PREFIXES = ("literal:", "selector:", "attr:",
                           "template:", "var:")
LABEL_CHAIN_STRINGS = ("text", "counter")
LABEL_CHAIN_OBJ_KEYS = ("text", "regexText", "regexUrl", "label")


def _check_field_spec(spec, where, problems, prefixes=None, allow_self=True):
    """A field spec is a string or an array of alternative strings, each a
    known primitive (see FIELD_SPEC_PREFIXES / RulesPipeline.kt).
    Doc-scoped evaluators pass a narrower `prefixes` tuple and allow_self=False
    (DOC_FIELD_SPEC_PREFIXES / RulesPipeline.docField)."""
    if isinstance(spec, list):
        if len(spec) > PIPELINE_MAX_SOURCES * 2:
            problems.append(f"{where}: at most 8 alternatives")
            return
        specs = spec
    else:
        specs = [spec]
    prefixes = prefixes or FIELD_SPEC_PREFIXES
    for s in specs:
        if not isinstance(s, str) or not s or len(s) > MAX_SELECTOR_LEN:
            problems.append(
                f"{where}: field spec must be a non-empty string <= {MAX_SELECTOR_LEN}")
        elif s == "desc-img" or s.startswith("desc-img:"):
            # Description-image primitive: first real <img> from an RSS item's
            # content/description (9jaRocks inline poster). Optional ":<css>"
            # narrows the selector.
            pass
        elif (s != "self" if allow_self else True) and not s.startswith(prefixes):
            problems.append(
                f"{where}: unknown field spec {s[:40]!r} (closed vocabulary)")


def _check_regex(val, where, problems, max_len=300):
    import re as _re
    if not isinstance(val, str) or not val or len(val) > max_len:
        problems.append(f"{where}: regex must be a non-empty string <= {max_len}")
        return
    try:
        _re.compile(val)
    except _re.error as e:
        problems.append(f"{where}: invalid regex: {e}")
    # ReDoS heuristic: a quantifier on the inside end of a group that is
    # itself quantified — (a+)+, (.*)*, (?:x+y*)+ — is the catastrophic
    # backtracking shape: on a non-matching input the engine enumerates
    # exponentially many partitions of the inner repetition. These regexes
    # come from OTA JSON and run against attacker-influenced page HTML on
    # the parse pool, so one signed-but-wrong rule could pin device cores.
    # It is a HEURISTIC, not a proof of safety: it misses shapes where the
    # group's last token is a char class ending in a quantifier written
    # like [^)]* — treat a pass as "no known bomb", not "safe". Rewrite
    # guidance: possessive (*+ / ++), atomic (?>...), or split the pattern.
    if _NESTED_QUANT.search(val):
        problems.append(f"{where}: nested quantifier ((a+)+ shape) — "
                        "catastrophic-backtracking risk on hostile HTML; "
                        "use a possessive/atomic group or split it")


_NESTED_QUANT = re.compile(r"\((?:[^()\\]|\\.)*[*+]\)(?:[*+]|\{\d+,\d*\})")


def _validate_pipeline_items(where, items, problems):
    if not isinstance(items, dict):
        problems.append(f"{where}: must be an object")
        return

    for key in ("urlBlacklist", "urlAllowlist"):
        arr = items.get(key)
        if arr is not None:
            if not isinstance(arr, list) or len(arr) > MAX_RULES_ARRAY:
                problems.append(f"{where}.{key}: list <= {MAX_RULES_ARRAY}")
            elif not all(isinstance(x, str) and len(x) <= 200 for x in arr):
                problems.append(f"{where}.{key}: entries must be short strings")

    if "limit" in items and (not isinstance(items["limit"], int)
                             or not 1 <= items["limit"] <= 500):
        problems.append(f"{where}.limit: int in 1..500")
    if "urlStripQuery" in items and not isinstance(items["urlStripQuery"], bool):
        problems.append(f"{where}.urlStripQuery: must be a boolean")

    qt = items.get("queryTransform")
    if qt is not None:
        if not isinstance(qt, dict):
            problems.append(f"{where}.queryTransform: must be an object")
        else:
            if "stripRegex" in qt:
                _check_regex(qt["stripRegex"], f"{where}.queryTransform.stripRegex",
                             problems, max_len=200)
            if "minLength" in qt and not isinstance(qt["minLength"], int):
                problems.append(f"{where}.queryTransform.minLength: must be an int")

    for field in ("title", "url", "poster", "year"):
        if field in items:
            _check_field_spec(items[field], f"{where}.{field}", problems)

    cat = items.get("category")
    if cat is not None:
        if isinstance(cat, str):
            _check_field_spec(cat, f"{where}.category", problems)
        elif isinstance(cat, dict):
            if "keywords" in cat:
                kws = cat["keywords"]
                if not isinstance(kws, list) or len(kws) > MAX_RULES_ARRAY:
                    problems.append(f"{where}.category.keywords: list <= {MAX_RULES_ARRAY}")
                else:
                    for i, rule in enumerate(kws):
                        if (not isinstance(rule, dict)
                                or not isinstance(rule.get("contains"), list)
                                or not rule.get("value")):
                            problems.append(
                                f"{where}.category.keywords[{i}]: needs contains[] and value")
        else:
            problems.append(f"{where}.category: must be a string or object")

    # episode-stage keys
    if "anchorSelector" in items:
        sel = items["anchorSelector"]
        if not isinstance(sel, str) or len(sel) > MAX_SELECTOR_LEN:
            problems.append(f"{where}.anchorSelector: string <= {MAX_SELECTOR_LEN}")
    if "hrefRegex" in items:
        _check_regex(items["hrefRegex"], f"{where}.hrefRegex", problems)
    if items.get("sortBy", "none") not in ("none", "captures"):
        problems.append(f"{where}.sortBy: must be none|captures")
    sg = items.get("sectionGrouping")
    if sg is not None:
        if not isinstance(sg, dict):
            problems.append(f"{where}.sectionGrouping: must be an object")
        else:
            unknown = set(sg) - {"sectionSelector", "seasonSpec", "labelTemplate"}
            if unknown:
                problems.append(
                    f"{where}.sectionGrouping: unknown keys {sorted(unknown)} (closed vocabulary)")
            sel = sg.get("sectionSelector")
            if not isinstance(sel, str) or not sel or len(sel) > MAX_SELECTOR_LEN:
                problems.append(
                    f"{where}.sectionGrouping.sectionSelector: string 1..{MAX_SELECTOR_LEN} required")
            if "seasonSpec" in sg:
                _check_field_spec(sg["seasonSpec"], f"{where}.sectionGrouping.seasonSpec",
                                  problems, prefixes=DOC_FIELD_SPEC_PREFIXES,
                                  allow_self=False)
            tmpl = sg.get("labelTemplate")
            if tmpl is not None and (not isinstance(tmpl, str) or len(tmpl) > MAX_SELECTOR_LEN):
                problems.append(
                    f"{where}.sectionGrouping.labelTemplate: string <= {MAX_SELECTOR_LEN}")
            # Cross-check: the executor's sortBy:"captures" branch
            # (RulesPipeline) rebuilds the episode list from RAW DOM order
            # with sequential numbering — it silently discards the season
            # codes and the uniqueness safety net sectionGrouping just built.
            # A rule carrying both states an intent that cannot happen;
            # reject it instead of shipping a half-broken season list.
            if items.get("sortBy") == "captures":
                problems.append(
                    f"{where}: sectionGrouping + sortBy:'captures' are "
                    "incompatible — the captures branch renumbers over the "
                    "season grouping; remove one of them")

    lc = items.get("labelChain")
    if lc is not None:
        if not isinstance(lc, list) or len(lc) > PIPELINE_MAX_STEPS:
            problems.append(f"{where}.labelChain: list <= {PIPELINE_MAX_STEPS}")
        else:
            for i, entry in enumerate(lc):
                ew = f"{where}.labelChain[{i}]"
                if isinstance(entry, str):
                    if entry not in LABEL_CHAIN_STRINGS and not entry.startswith("sibling:"):
                        problems.append(f"{ew}: unknown primitive {entry!r}")
                elif isinstance(entry, dict):
                    if not any(k in entry for k in LABEL_CHAIN_OBJ_KEYS):
                        problems.append(
                            f"{ew}: needs one of {'|'.join(LABEL_CHAIN_OBJ_KEYS)}")
                    for rk in ("regexText", "regexUrl"):
                        if rk in entry:
                            _check_regex(entry[rk], f"{ew}.{rk}", problems)
                    if "text" in entry and not isinstance(entry["text"], dict):
                        problems.append(f"{ew}.text: must be an object")
                else:
                    problems.append(f"{ew}: must be a string or object")

    numbering = items.get("numbering")
    if numbering is not None:
        if not isinstance(numbering, dict):
            problems.append(f"{where}.numbering: must be an object")
        else:
            chain = numbering.get("chain")
            if chain is not None:
                if not isinstance(chain, list) or len(chain) > PIPELINE_MAX_STEPS:
                    problems.append(f"{where}.numbering.chain: list <= {PIPELINE_MAX_STEPS}")
                else:
                    for i, entry in enumerate(chain):
                        if (not isinstance(entry, dict)
                                or not any(k in entry for k in ("regexText", "regexUrl"))):
                            problems.append(
                                f"{where}.numbering.chain[{i}]: needs regexText|regexUrl")
                        else:
                            for rk in ("regexText", "regexUrl"):
                                if rk in entry:
                                    _check_regex(entry[rk],
                                                 f"{where}.numbering.chain[{i}].{rk}",
                                                 problems)

    fb = items.get("fallbackSingle")
    if fb is not None:
        if not isinstance(fb, dict) or not fb.get("title"):
            problems.append(f"{where}.fallbackSingle: object with 'title' required")
        else:
            when = fb.get("when", "always")
            if when != "always" and not (isinstance(when, str)
                                         and when.startswith("hasElement:")):
                problems.append(f"{where}.fallbackSingle.when: always|hasElement:<css>")

    meta = items.get("meta")
    if meta is not None:
        if not isinstance(meta, dict):
            problems.append(f"{where}.meta: must be an object")
        else:
            for field in ("title", "poster", "synopsis"):
                if field in meta:
                    _check_field_spec(meta[field], f"{where}.meta.{field}", problems,
                                      prefixes=DOC_FIELD_SPEC_PREFIXES,
                                      allow_self=False)
            tc = meta.get("titleCleanup")
            if tc is not None:
                if not isinstance(tc, list) or len(tc) > PIPELINE_MAX_STEPS:
                    problems.append(f"{where}.meta.titleCleanup: list <= {PIPELINE_MAX_STEPS}")
                else:
                    for i, op in enumerate(tc):
                        if op != "trim" and not (
                                isinstance(op, dict)
                                and any(k in op for k in ("removePrefix", "substringBefore"))):
                            problems.append(
                                f"{where}.meta.titleCleanup[{i}]: "
                                "trim | removePrefix | substringBefore")

    vars_ = items.get("vars")
    if vars_ is not None:
        if not isinstance(vars_, dict):
            problems.append(f"{where}.vars: must be an object")
        else:
            for vk, vv in vars_.items():
                _check_field_spec(vv, f"{where}.vars.{vk}", problems)

    defaults = items.get("defaults")
    if defaults is not None:
        if not isinstance(defaults, dict):
            problems.append(f"{where}.defaults: must be an object")
        elif not all(isinstance(v, str) for v in defaults.values()):
            problems.append(f"{where}.defaults: values must be strings")


def _validate_pipeline(where, pl, problems):
    if not isinstance(pl, dict):
        problems.append(f"{where}: must be an object")
        return
    steps = pl.get("steps")
    if not isinstance(steps, list) or not 1 <= len(steps) <= PIPELINE_MAX_STEPS:
        problems.append(f"{where}.steps: must be a list of 1-{PIPELINE_MAX_STEPS} steps")
        return
    ub = pl.get("urlBinds")
    if ub is not None:
        # Pipeline-level pre-fetch binds (resolve stage reads the episode URL
        # BEFORE any fetch — downloadwella's file-id-in-POST-body class).
        # All-or-nothing at parse: ANY invalid entry voids the pipeline.
        if not isinstance(ub, dict):
            problems.append(f"{where}.urlBinds: must be an object")
        else:
            for name, spec in ub.items():
                w = f"{where}.urlBinds.{name}"
                if not isinstance(spec, dict):
                    problems.append(f"{w}: must be an object")
                    continue
                rx = spec.get("regex")
                if not isinstance(rx, str) or not rx.strip():
                    problems.append(f"{w}.regex: required non-empty string (Java regex)")
                g = spec.get("group", 1)
                if isinstance(g, bool) or not isinstance(g, int) or not 0 <= g <= 32:
                    problems.append(f"{w}.group: must be an int 0-32")
                d = spec.get("decode", False)
                if not isinstance(d, bool):
                    problems.append(f"{w}.decode: must be a boolean")
    for i, step in enumerate(steps):
        w = f"{where}.steps[{i}]"
        if not isinstance(step, dict):
            problems.append(f"{w}: must be an object")
            continue
        if step.get("mode", "single") not in ("single", "failover", "merge"):
            problems.append(f"{w}.mode: must be single|failover|merge")
        if step.get("as", "html") not in ("html", "json", "rss", "packed_js"):
            problems.append(f"{w}.as: must be html|json|rss|packed_js")

        sources = step.get("sources")
        if not isinstance(sources, list) or not 1 <= len(sources) <= PIPELINE_MAX_SOURCES:
            problems.append(
                f"{w}.sources: must be a list of 1-{PIPELINE_MAX_SOURCES} sources")
            continue
        for j, src in enumerate(sources):
            sw = f"{w}.sources[{j}]"
            if not isinstance(src, dict) or not src.get("url"):
                problems.append(f"{sw}: object with non-empty 'url' required")
                continue
            if not isinstance(src["url"], str) or len(src["url"]) > MAX_SELECTOR_LEN:
                problems.append(f"{sw}.url: string <= {MAX_SELECTOR_LEN}")
            if src.get("method", "GET") not in ("GET", "POST", "HEAD"):
                problems.append(f"{sw}.method: must be GET|POST|HEAD")
            headers = src.get("headers") or {}
            if not isinstance(headers, dict):
                problems.append(f"{sw}.headers: must be an object")
            else:
                for hk, hv in headers.items():
                    if not isinstance(hv, str) or len(hv) > MAX_SELECTOR_LEN:
                        problems.append(f"{sw}.headers.{hk}: string <= {MAX_SELECTOR_LEN}")
            form = src.get("form") or {}
            if not isinstance(form, dict):
                problems.append(f"{sw}.form: must be an object")
            else:
                for fk, fv in form.items():
                    if not isinstance(fv, str) or len(fv) > MAX_SELECTOR_LEN:
                        problems.append(f"{sw}.form.{fk}: string <= {MAX_SELECTOR_LEN}")
            queryParams = src.get("queryParams") or {}
            if not isinstance(queryParams, dict):
                problems.append(f"{sw}.queryParams: must be an object")
            else:
                for qk, qv in queryParams.items():
                    if not isinstance(qv, str) or len(qv) > MAX_SELECTOR_LEN:
                        problems.append(f"{sw}.queryParams.{qk}: string <= {MAX_SELECTOR_LEN}")
            if "body" in src and not isinstance(src["body"], str):
                problems.append(f"{sw}.body: must be a string")
            if "followRedirects" in src and not isinstance(src["followRedirects"], bool):
                problems.append(f"{sw}.followRedirects: must be a boolean")
            if "permissiveSsl" in src and not isinstance(src["permissiveSsl"], bool):
                problems.append(f"{sw}.permissiveSsl: must be a boolean")
            if "timeoutMs" in src and (not isinstance(src["timeoutMs"], int) or isinstance(src["timeoutMs"], bool) or not 0 <= src["timeoutMs"] <= 60000):
                problems.append(f"{sw}.timeoutMs: must be an int 0..60000")

        bind = step.get("bind")
        if bind is not None:
            if not isinstance(bind, dict):
                problems.append(f"{w}.bind: must be an object")
            else:
                ALLOWED_BIND_EXTRACTORS = (
                    "regex", "json", "selector", "header", "cookie",
                    "pow", "pow_sha256", "unpackJs", "sourceVar"
                )
                ALLOWED_TRANSFORMS = {
                    "base64decode", "base64encode", "hexdecode", "rot13",
                    "urldecode", "urlencode", "trim", "lowercase", "uppercase", "reverse",
                    # 2026-10-15: JS string-literal unescape (\\uXXXX / \\xXX / \\/ …)
                    # for lockers that embed window.location/link URLs escaped.
                    # Must stay in lockstep with RulesPipeline.applyTransforms.
                    "jsunescape"
                }
                for bk, bv in bind.items():
                    bw = f"{w}.bind.{bk}"
                    if not isinstance(bv, dict):
                        problems.append(f"{bw}: must be an object")
                        continue
                    if not any(k in bv for k in ALLOWED_BIND_EXTRACTORS):
                        problems.append(
                            f"{bw}: needs at least one of {'|'.join(ALLOWED_BIND_EXTRACTORS)}"
                        )
                    if "regex" in bv:
                        _check_regex(bv["regex"], f"{bw}.regex", problems)
                    if "group" in bv and (isinstance(bv["group"], bool) or not isinstance(bv["group"], int) or not 0 <= bv["group"] <= 32):
                        problems.append(f"{bw}.group: must be an int 0-32")
                    if "unpackJs" in bv and not isinstance(bv["unpackJs"], bool):
                        problems.append(f"{bw}.unpackJs: must be a boolean")
                    if "sourceVar" in bv and not isinstance(bv["sourceVar"], str):
                        problems.append(f"{bw}.sourceVar: must be a string")
                    if "header" in bv and not isinstance(bv["header"], str):
                        problems.append(f"{bw}.header: must be a string")
                    if "cookie" in bv and not isinstance(bv["cookie"], str):
                        problems.append(f"{bw}.cookie: must be a string")
                    if "transforms" in bv:
                        tf = bv["transforms"]
                        if not isinstance(tf, list) or len(tf) > 16:
                            problems.append(f"{bw}.transforms: list <= 16")
                        else:
                            for t_name in tf:
                                if t_name not in ALLOWED_TRANSFORMS and not (isinstance(t_name, str) and t_name.startswith("xor:")):
                                    problems.append(f"{bw}.transforms: unknown transform {t_name!r}")
                    for pow_key in ("pow", "pow_sha256"):
                        if pow_key in bv:
                            pv = bv[pow_key]
                            if isinstance(pv, dict):
                                if "algorithm" in pv and pv["algorithm"] not in ("sha256", "md5", "sha1"):
                                    problems.append(f"{bw}.{pow_key}.algorithm: must be sha256|md5|sha1")
                                if "maxIterations" in pv and (not isinstance(pv["maxIterations"], int) or pv["maxIterations"] <= 0):
                                    problems.append(f"{bw}.{pow_key}.maxIterations: must be positive int")
                            elif not isinstance(pv, bool):
                                problems.append(f"{bw}.{pow_key}: must be an object or boolean")

        if "delayMs" in step:
            d = step["delayMs"]
            if not isinstance(d, int) or isinstance(d, bool) or not 0 <= d <= 60000:
                problems.append(f"{w}.delayMs: must be an int 0..60000")

        if "delayVar" in step and not isinstance(step["delayVar"], str):
            problems.append(f"{w}.delayVar: must be a string")

        if "maxBytes" in step and (not isinstance(step["maxBytes"], int) or isinstance(step["maxBytes"], bool) or step["maxBytes"] < 0):
            problems.append(f"{w}.maxBytes: must be a non-negative int")

        if "deadPatterns" in step:
            dp = step["deadPatterns"]
            if not isinstance(dp, list) or len(dp) > MAX_RULES_ARRAY:
                problems.append(f"{w}.deadPatterns: list <= {MAX_RULES_ARRAY}")
            elif not all(isinstance(x, str) and len(x) <= 200 for x in dp):
                problems.append(f"{w}.deadPatterns: entries must be short strings")

        if "items" in step:
            _validate_pipeline_items(f"{w}.items", step["items"], problems)


def _validate_terminal(where, t, problems):
    """Mirror of PipelineModels.parseTerminal — every rejection there must be
    visible HERE too, so an unsignable payload never reaches a fleet phone.
    (Regexes are NOT dialect-checked here: Kotlin validates them at parse via
    java.util.regex; Python's re would reject Java-specific syntax that is
    actually loadable on the phones.)"""
    if not isinstance(t, dict):
        problems.append(f"{where}: must be an object")
        return
    # Kotlin lowercases source/mode before matching — mirror exactly, so the
    # gate and the parser never disagree on what is signable.
    source = str(t.get("source") or "").lower()
    mode = str(t.get("mode") or "").lower()
    if source not in ("entry", "final"):
        problems.append(f"{where}.source: must be entry|final")
    if mode not in ("probe", "handoff"):
        problems.append(f"{where}.mode: must be probe|handoff")
    hosts = t.get("hosts")
    if not isinstance(hosts, list) or not hosts:
        problems.append(f"{where}.hosts: non-empty array of PURE domain names required")
    else:
        for h in hosts:
            # lowercased/trimmed/`*.`-normalized exactly like the Kotlin parse
            hh = str(h).lower().strip().removeprefix("*.")
            if not hh:
                problems.append(f"{where}.hosts: blank entry")
            elif any(c in hh for c in "/: "):
                problems.append(f"{where}.hosts: '{h}' is not a pure domain (scheme/path/port/space)")
    g = t.get("group", 1)
    if not isinstance(g, int) or not 0 <= g <= 32:
        problems.append(f"{where}.group: must be an int 0-32")
    if source == "entry" and not str(t.get("regex") or "").strip():
        problems.append(f"{where}.regex: required for source=entry")
    if source == "final" and not str(t.get("spec") or "").strip():
        problems.append(f"{where}.spec: required for source=final")
    for key in ("regex", "spec", "referer"):
        v = t.get(key)
        if v is not None and (not isinstance(v, str) or len(v) > MAX_SELECTOR_LEN):
            problems.append(f"{where}.{key}: string <= {MAX_SELECTOR_LEN}")
    sqp = t.get("stripQueryParams")
    if sqp is not None:
        if not isinstance(sqp, list) or not all(isinstance(x, str) and len(x) <= 100 for x in sqp):
            problems.append(f"{where}.stripQueryParams: list of strings <= 100")


def validate_pipelines(obj) -> list:
    """Deep validation of the declarative step-pipeline key. Mirrors the
    parse-time bounds in PipelineModels.kt and the closed vocabulary in
    RulesPipeline.kt — a payload that fails here can never be emitted."""
    problems = []
    pipelines = obj.get("pipelines")
    if pipelines is None:
        return problems
    if not isinstance(pipelines, dict):
        return ["pipelines must be an object"]
    for site, pl in pipelines.items():
        where = f"pipelines.{site}"
        if not isinstance(pl, dict):
            problems.append(f"{where}: must be an object")
            continue
        if pl.get("schema") != 1:
            problems.append(f"{where}: schema must be 1 (unknown versions are refused)")
            continue
        known = {"schema", "search", "episodes", "resolve", "terminal"}
        for key in pl.keys():
            if key not in known:
                problems.append(f"{where}: unknown key '{key}'")
        for stage in ("search", "episodes", "resolve"):
            if stage in pl:
                _validate_pipeline(f"{where}.{stage}", pl[stage], problems)
        # resolve/terminal coupling (A-5), mirroring parseSitePipeline:
        #  - resolve WITHOUT a valid terminal -> refused (trust lives in the gate)
        #  - terminal(final) WITHOUT resolve  -> refused (its spec vars could
        #    never bind — a final ride needs its pipeline)
        #  - terminal(entry) WITHOUT resolve  -> ALLOWED: zero-fetch recipe
        #    (candidate extracted from the episode URL itself)
        has_resolve, has_terminal = "resolve" in pl, "terminal" in pl
        if has_resolve and not has_terminal:
            problems.append(
                f"{where}: 'resolve' without a valid 'terminal' is refused by the app")
        if has_terminal:
            _validate_terminal(f"{where}.terminal", pl["terminal"], problems)
            source = str(pl["terminal"].get("source") or "").lower() if isinstance(pl["terminal"], dict) else ""
            if not has_resolve and source != "entry":
                problems.append(
                    f"{where}: 'terminal' source=final without a 'resolve' pipeline "
                    "is refused (bound vars can never exist)")
    return problems


def verify_envelope(text: str, pub_b64: str = OTA_PUB_B64,
                    root_pins_b64=None) -> bool:
    """Authenticity check for CI/app parity: ECDSA-P256-SHA256 over the
    payload string. False for unsigned envelopes, tampered payloads or
    unknown keys. Raises SystemExit with a reason on invalid JSON.

    Chain-aware (step 7): an envelope WITH a "chain" object verifies in two
    steps — (1) the delivered leaf public key must be signed by one of the
    PINNED ROOTS (root_pins_b64, default [pub_b64]; dual-pin lists emulate
    the app's "verify first that succeeds") over the canonical ASCII bytes
    of the leafPubB64 string; (2) the payload signature must verify against
    the DELIVERED leaf. A malformed or failing chain REJECTS outright — it
    never falls back to legacy verification. An envelope WITHOUT "chain" is
    verified against the pin list: callers passing only pub_b64 (the CI
    workflow's default) get the FULL app pin set [root-1, root-2] so CI is
    in lockstep with the fleet; callers passing a CUSTOM pub_b64 keep the
    single-key check unless they pass root_pins_b64 explicitly (pre-step-7
    verifier emulation: pass root_pins_b64=[OTA_PUB_B64])."""
    import base64 as b64
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    env = json.loads(text.strip())
    sig = env.get("sig")
    if not sig:
        raise SystemExit("UNSIGNED envelope — refused")
    if root_pins_b64 is not None:
        pins = list(root_pins_b64)
    elif pub_b64 != OTA_PUB_B64:
        pins = [pub_b64]
    else:
        pins = list(ROOT_PINS_B64)

    chain = env.get("chain")
    if chain is not None:
        if not isinstance(chain, dict):
            raise SystemExit("CHAIN malformed (not an object) — refused, no legacy fallback")
        leaf_b64 = chain.get("leafPubB64")
        root_sig = chain.get("rootSig")
        if not leaf_b64 or not root_sig:
            raise SystemExit("CHAIN incomplete (missing leafPubB64/rootSig) — refused")
        if chain.get("alg") != CHAIN_ALG:
            raise SystemExit(f"CHAIN alg {chain.get('alg')!r} unknown — refused (fail closed)")
        # Step 1: the leaf is trusted only to the extent a PINNED ROOT signed
        # its canonical bytes. First root pin that verifies wins; none → reject.
        root_ok = False
        for pin in pins:
            try:
                pub = load_der_public_key(b64.b64decode(pin))
                pub.verify(b64.b64decode(root_sig), leaf_b64.encode("ascii"),
                           ec.ECDSA(hashes.SHA256()))
                root_ok = True
                break
            except Exception:
                continue
        if not root_ok:
            raise SystemExit("CHAIN REJECTED — leaf is signed by no pinned root")
        # Step 2: the payload signature must come from the delivered leaf.
        try:
            leaf_pub = load_der_public_key(b64.b64decode(leaf_b64))
            leaf_pub.verify(b64.b64decode(sig), env["payload"].encode("ascii"),
                            ec.ECDSA(hashes.SHA256()))
            return True
        except Exception:
            raise SystemExit("SIGNATURE INVALID (chain/leaf mode) — payload "
                             "tampered or signed by a leaf other than the enveloped one")

    # Legacy path — byte-for-byte today's semantics for the default call
    # (pins == [pub_b64] is a single check against pub_b64). When a pin SET
    # is supplied (dual-pin rotation window, app parity), verify-first-that-
    # succeeds applies here too: a legacy envelope may be signed by ANY
    # pinned root.
    last_pin_error = "SIGNATURE INVALID — payload tampered or wrong key"
    for pin in pins:
        try:
            pub = load_der_public_key(b64.b64decode(pin))
            pub.verify(b64.b64decode(env["sig"]), env["payload"].encode("ascii"),
                       ec.ECDSA(hashes.SHA256()))
            return True
        except Exception:
            continue
    raise SystemExit(last_pin_error)


# --- v3.3.0 platform-surface sections (plan §4.1) --------------------------
# Lockstep with DynamicRulesManager (buildAnnouncements / buildUpdatePolicy /
# buildAdsConfig / buildEngagementConfig / buildTamperPolicy / buildFcmConfig):
# the client parses leniently, this validator is the HARD gate. Closed
# vocabulary: unknown fields inside a section are refused, like contentPolicy.
ANNOUNCEMENTS_MAX = 16
ANNOUNCEMENT_ID_MAX = 64
ACTION_LABEL_MAX = 64
TITLE_MAX = 80              # Decision 9: every user-visible title
BODY_MAX = 300              # Decision 9: every user-visible body
PLATFORM_URL_MAX = 512
PLATFORM_VERSION_CODE_MAX = 1_000_000
ANNOUNCEMENT_MODES = ("dialog", "sheet", "banner")
ANNOUNCEMENT_ACTIONS = ("none", "url", "update")
ANNOUNCEMENT_ONCE_PER = ("id", "rev")
UPDATE_SOURCE_KINDS = ("vps", "github", "gitea")
UPDATE_FORCE_MODES = ("soft", "hard")
AD_PROVIDERS = ("house", "none", "applovin", "unity", "admob", "auto")
# Known ad-slot ids (plan §4.3 anchors). A new slot lands with its UI change
# AND this list — the same closed-vocabulary discipline as pipeline steps.
AD_PLACEMENTS = ("downloads_shelf", "home_footer")
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")
# Certificate pins are conventionally either case; the client normalizes to
# lowercase before comparing.
CERT_HEX_RE = re.compile(r"^[0-9a-fA-F]{64}$")
# F-04 / Decision 13 (plan §2): `sites` fields the app parses but never reads
# in main — reserved for a future reader. Nothing is removed (harmless data)
# and no fake readers are added; the INFO note below exists so an OTA "fix"
# through one of these fields can never look functional.
RESERVED_SITE_FIELDS = ("searchType", "titleSelector", "linkSelector",
                        "posterSelector", "seriesDescentSelectors")


def _check_str_cap(val, where, problems, max_len, required=False):
    """String-shape guard: type + length cap, non-emptiness when [required]."""
    if val is None:
        if required:
            problems.append(f"{where}: required non-empty string <= {max_len} chars")
        return
    if not isinstance(val, str) or len(val) > max_len or (required and not val.strip()):
        problems.append(
            f"{where}: must be a {'non-empty ' if required else ''}string <= {max_len} chars")


def _check_http_url(val, where, problems, required=False):
    """http(s)-only URL guard (the remote_logging_url contract). An empty
    string is allowed where the URL is unused (action="none"), never where a
    click target is promised (required)."""
    if val is None:
        if required:
            problems.append(f"{where}: required http(s) URL <= {PLATFORM_URL_MAX} chars")
        return
    if val == "" and not required:
        return
    if (not isinstance(val, str) or not val.startswith(("http://", "https://"))
            or len(val) > PLATFORM_URL_MAX):
        problems.append(f"{where}: must be an http(s) URL <= {PLATFORM_URL_MAX} chars")


def _check_int_range(val, where, problems, lo, hi, required=False):
    """Integer-range guard; JSON true is a typo, never 1. None = absent,
    allowed unless [required]."""
    if val is None:
        if required:
            problems.append(f"{where}: required int {lo}..{hi}")
        return
    if isinstance(val, bool) or not isinstance(val, int) or not lo <= val <= hi:
        problems.append(f"{where}: must be an int {lo}..{hi}")


ENGINE_ROUTING_TARGETS = ("ytdlp_only", "aria2_only", "hls_paced", "turbo_aria2")
UNWRAPPER_DECODES = ("url", "uri", "base64", "none", "")


def validate_engine_sections(obj) -> list:
    """v3.4.0 OTA engine decoupling surfaces: downloader_routing, engine_profiles,
    proxy_unwrappers, resolver_gates, resolver_crypto, permissive_tls_hosts,
    hls_keep_referer_hosts, streaming_url_markers, preflight_passthrough_markers,
    decoy_signatures, failover_clusters."""
    problems = []

    dr = obj.get("downloader_routing")
    if dr is not None:
        if not isinstance(dr, list) or len(dr) > 64:
            problems.append("downloader_routing: list <= 64 entries")
        else:
            for i, r in enumerate(dr):
                w = f"downloader_routing[{i}]"
                if not isinstance(r, dict):
                    problems.append(f"{w}: must be an object")
                    continue
                for key in r:
                    if key not in ("pattern", "engine", "profile", "keep_referer"):
                        problems.append(f"{w}.{key}: unknown field")
                _check_regex(r.get("pattern"), f"{w}.pattern", problems, max_len=300)
                eng = r.get("engine")
                if eng not in ENGINE_ROUTING_TARGETS:
                    problems.append(f"{w}.engine: one of {'|'.join(ENGINE_ROUTING_TARGETS)}")
                if "profile" in r and (not isinstance(r["profile"], str) or len(r["profile"]) > 64):
                    problems.append(f"{w}.profile: string <= 64")
                if "keep_referer" in r and not isinstance(r["keep_referer"], bool):
                    problems.append(f"{w}.keep_referer: must be a bool")

    ep = obj.get("engine_profiles")
    if ep is not None:
        if not isinstance(ep, dict):
            problems.append("engine_profiles: must be an object")
        else:
            for eng_name in ep:
                if eng_name not in ("ytdlp", "aria2"):
                    problems.append(f"engine_profiles.{eng_name}: unknown engine (allowed: ytdlp, aria2)")
                    continue
                prof_map = ep[eng_name]
                if not isinstance(prof_map, dict):
                    problems.append(f"engine_profiles.{eng_name}: must be an object")
                    continue
                for prof_name, pcfg in prof_map.items():
                    pw = f"engine_profiles.{eng_name}.{prof_name}"
                    if not isinstance(pcfg, dict):
                        problems.append(f"{pw}: must be an object")
                        continue
                    for key in pcfg:
                        if key not in ("args", "headers"):
                            problems.append(f"{pw}.{key}: unknown field")
                    args = pcfg.get("args")
                    if args is not None:
                        if not isinstance(args, list) or len(args) > 64:
                            problems.append(f"{pw}.args: list <= 64")
                        else:
                            for idx, grp in enumerate(args):
                                if isinstance(grp, list):
                                    if not all(isinstance(x, str) and len(x) <= 200 for x in grp):
                                        problems.append(f"{pw}.args[{idx}]: list of strings <= 200")
                                elif isinstance(grp, str):
                                    if len(grp) > 200:
                                        problems.append(f"{pw}.args[{idx}]: string <= 200")
                                else:
                                    problems.append(f"{pw}.args[{idx}]: string or list of strings")
                    hdrs = pcfg.get("headers")
                    if hdrs is not None:
                        if not isinstance(hdrs, dict) or len(hdrs) > 32:
                            problems.append(f"{pw}.headers: object <= 32 entries")
                        else:
                            for hk, hv in hdrs.items():
                                if not isinstance(hk, str) or not isinstance(hv, str) or len(hk) > 100 or len(hv) > 500:
                                    problems.append(f"{pw}.headers.{hk}: string key/value")

    pu = obj.get("proxy_unwrappers")
    if pu is not None:
        if not isinstance(pu, list) or len(pu) > 64:
            problems.append("proxy_unwrappers: list <= 64")
        else:
            for i, u in enumerate(pu):
                w = f"proxy_unwrappers[{i}]"
                if not isinstance(u, dict):
                    problems.append(f"{w}: must be an object")
                    continue
                for key in u:
                    if key not in ("pattern", "decode", "group"):
                        problems.append(f"{w}.{key}: unknown field")
                _check_regex(u.get("pattern"), f"{w}.pattern", problems, max_len=300)
                if "decode" in u and u["decode"] not in UNWRAPPER_DECODES:
                    problems.append(f"{w}.decode: one of {'|'.join(UNWRAPPER_DECODES)}")
                if "group" in u and (isinstance(u["group"], bool) or not isinstance(u["group"], int) or not 1 <= u["group"] <= 10):
                    problems.append(f"{w}.group: int in 1..10")

    rg = obj.get("resolver_gates")
    if rg is not None:
        if not isinstance(rg, dict):
            problems.append("resolver_gates: must be an object")
        else:
            for key in rg:
                if key not in ("exclusions",):
                    problems.append(f"resolver_gates.{key}: unknown field")
            excl = rg.get("exclusions")
            if excl is not None:
                if not isinstance(excl, dict):
                    problems.append("resolver_gates.exclusions: must be an object")
                else:
                    for rname, arr in excl.items():
                        if not isinstance(arr, list) or len(arr) > 64:
                            problems.append(f"resolver_gates.exclusions.{rname}: list <= 64")
                        elif not all(isinstance(x, str) and 0 < len(x) <= 200 for x in arr):
                            problems.append(f"resolver_gates.exclusions.{rname}: non-empty strings <= 200")

    rc = obj.get("resolver_crypto")
    if rc is not None:
        if not isinstance(rc, dict):
            problems.append("resolver_crypto: must be an object")
        else:
            for key in rc:
                if key not in ("rings",):
                    problems.append(f"resolver_crypto.{key}: unknown field")
            rings = rc.get("rings")
            if rings is not None:
                if not isinstance(rings, dict):
                    problems.append("resolver_crypto.rings: must be an object")
                else:
                    for rname, arr in rings.items():
                        if not isinstance(arr, list) or len(arr) > 16:
                            problems.append(f"resolver_crypto.rings.{rname}: list <= 16")
                        else:
                            for idx, item in enumerate(arr):
                                w = f"resolver_crypto.rings.{rname}[{idx}]"
                                if not isinstance(item, dict):
                                    problems.append(f"{w}: must be an object")
                                    continue
                                for key in item:
                                    if key not in ("cipher", "algo", "key_hex", "iv_hex", "key", "iv"):
                                        problems.append(f"{w}.{key}: unknown field")
                                has_hex = "key_hex" in item and "iv_hex" in item
                                has_str = "key" in item and "iv" in item
                                if not (has_hex or has_str):
                                    problems.append(f"{w}: requires (key_hex + iv_hex) or (key + iv)")

    for name, cap in (
        ("permissive_tls_hosts", 128),
        ("hls_keep_referer_hosts", 128),
        ("streaming_url_markers", 128),
        ("preflight_passthrough_markers", 128),
        ("decoy_signatures", 128)
    ):
        arr = obj.get(name)
        if arr is not None:
            if not isinstance(arr, list) or len(arr) > cap:
                problems.append(f"{name}: list <= {cap}")
            elif not all(isinstance(x, str) and 0 < len(x) <= 300 for x in arr):
                problems.append(f"{name}: non-empty strings <= 300")

    fc = obj.get("failover_clusters")
    if fc is not None:
        if not isinstance(fc, dict):
            problems.append("failover_clusters: must be an object")
        else:
            for key in fc:
                if key not in ("animeSecondPass", "clusters"):
                    problems.append(f"failover_clusters.{key}: unknown field")
            asp = fc.get("animeSecondPass")
            if asp is not None:
                if not isinstance(asp, list) or len(asp) > 64:
                    problems.append("failover_clusters.animeSecondPass: list <= 64")
                elif not all(isinstance(x, str) and 0 < len(x) <= 64 for x in asp):
                    problems.append("failover_clusters.animeSecondPass: non-empty strings <= 64")
            cl = fc.get("clusters")
            if cl is not None:
                if not isinstance(cl, dict) or len(cl) > 32:
                    problems.append("failover_clusters.clusters: object <= 32 entries")
                else:
                    for cname, carr in cl.items():
                        if not isinstance(carr, list) or len(carr) > 64:
                            problems.append(f"failover_clusters.clusters.{cname}: list <= 64")
                        elif not all(isinstance(x, str) and 0 < len(x) <= 64 for x in carr):
                            problems.append(f"failover_clusters.clusters.{cname}: non-empty strings <= 64")

    return problems


def validate_platform_sections(obj) -> list:
    """v3.3.0 platform-surface sections (docs/FINAL_BUILD_EXECUTION_PLAN.md
    §4.1): announcements / update / ads / engagement / tamper / fcm. Same
    discipline as contentPolicy: a typo'd field hard-fails HERE instead of
    silently no-op'ing on every device."""
    problems = []

    ann = obj.get("announcements")
    if ann is not None:
        if not isinstance(ann, list) or len(ann) > ANNOUNCEMENTS_MAX:
            problems.append(f"announcements: list <= {ANNOUNCEMENTS_MAX} entries")
        else:
            for i, a in enumerate(ann):
                w = f"announcements[{i}]"
                if not isinstance(a, dict):
                    problems.append(f"{w}: must be an object")
                    continue
                for key in a:
                    if key not in ("id", "title", "body", "mode", "action",
                                   "actionLabel", "actionUrl", "oncePer",
                                   "minApp", "expiresAtMs"):
                        problems.append(f"{w}.{key}: unknown field")
                _check_str_cap(a.get("id"), f"{w}.id", problems,
                               ANNOUNCEMENT_ID_MAX, required=True)
                _check_str_cap(a.get("title"), f"{w}.title", problems,
                               TITLE_MAX, required=True)
                _check_str_cap(a.get("body"), f"{w}.body", problems, BODY_MAX)
                _check_str_cap(a.get("actionLabel"), f"{w}.actionLabel",
                               problems, ACTION_LABEL_MAX)
                if a.get("mode") is not None and a["mode"] not in ANNOUNCEMENT_MODES:
                    problems.append(
                        f"{w}.mode: one of {'|'.join(ANNOUNCEMENT_MODES)}")
                if a.get("action") is not None and a["action"] not in ANNOUNCEMENT_ACTIONS:
                    problems.append(
                        f"{w}.action: one of {'|'.join(ANNOUNCEMENT_ACTIONS)}")
                if a.get("oncePer") is not None and a["oncePer"] not in ANNOUNCEMENT_ONCE_PER:
                    problems.append(
                        f"{w}.oncePer: one of {'|'.join(ANNOUNCEMENT_ONCE_PER)}")
                # A url action without a url is a dead button fleet-wide.
                _check_http_url(a.get("actionUrl"), f"{w}.actionUrl", problems,
                                required=(a.get("action") == "url"))
                _check_int_range(a.get("minApp"), f"{w}.minApp", problems,
                                 0, PLATFORM_VERSION_CODE_MAX)
                _check_int_range(a.get("expiresAtMs"), f"{w}.expiresAtMs",
                                 problems, 0, 2 ** 53)

    upd = obj.get("update")
    if upd is not None:
        if not isinstance(upd, dict):
            problems.append("update: must be an object")
        else:
            for key in upd:
                if key not in ("latestVersionName", "latestVersionCode",
                               "sources", "apkArm64", "apkUniversal",
                               "sha256Arm64", "sha256Universal", "message",
                               "force"):
                    problems.append(f"update.{key}: unknown field")
            _check_str_cap(upd.get("latestVersionName"),
                           "update.latestVersionName", problems, 40, required=True)
            _check_int_range(upd.get("latestVersionCode"),
                             "update.latestVersionCode", problems, 0,
                             PLATFORM_VERSION_CODE_MAX, required=True)
            srcs = upd.get("sources")
            if not isinstance(srcs, list) or not 1 <= len(srcs) <= 4:
                problems.append("update.sources: list of 1..4 sources")
            else:
                for j, s in enumerate(srcs):
                    w = f"update.sources[{j}]"
                    if not isinstance(s, dict):
                        problems.append(f"{w}: must be an object")
                        continue
                    for key in s:
                        if key not in ("kind", "base"):
                            problems.append(f"{w}.{key}: unknown field")
                    if s.get("kind") not in UPDATE_SOURCE_KINDS:
                        problems.append(
                            f"{w}.kind: one of {'|'.join(UPDATE_SOURCE_KINDS)}")
                    _check_http_url(s.get("base"), f"{w}.base", problems,
                                    required=True)
            # APK file names may be empty only until release-day; the SHA-256
            # pins are explicitly allowed empty (release-day step, plan §6.5).
            for fld in ("apkArm64", "apkUniversal"):
                _check_str_cap(upd.get(fld), f"update.{fld}", problems, 120)
            for fld in ("sha256Arm64", "sha256Universal"):
                v = upd.get(fld)
                if v is not None and v != "" and (
                        not isinstance(v, str) or not HEX64_RE.match(v)):
                    problems.append(f"update.{fld}: 64 lowercase hex chars or empty")
            _check_str_cap(upd.get("message"), "update.message", problems, BODY_MAX)
            force = upd.get("force")
            if force is not None:
                if not isinstance(force, dict):
                    problems.append("update.force: must be an object")
                else:
                    for key in force:
                        if key not in ("enabled", "minSupportedVersionCode",
                                       "mode", "snoozeHours", "escapeHatch"):
                            problems.append(f"update.force.{key}: unknown field")
                    if "enabled" in force and not isinstance(force["enabled"], bool):
                        problems.append("update.force.enabled: must be a bool")
                    _check_int_range(force.get("minSupportedVersionCode"),
                                     "update.force.minSupportedVersionCode",
                                     problems, 0, PLATFORM_VERSION_CODE_MAX)
                    if force.get("mode") is not None and force["mode"] not in UPDATE_FORCE_MODES:
                        problems.append(
                            f"update.force.mode: one of {'|'.join(UPDATE_FORCE_MODES)}")
                    _check_int_range(force.get("snoozeHours"),
                                     "update.force.snoozeHours", problems, 0, 168)
                    if "escapeHatch" in force and not isinstance(force["escapeHatch"], bool):
                        problems.append("update.force.escapeHatch: must be a bool")

    ads = obj.get("ads")
    if ads is not None:
        if not isinstance(ads, dict):
            problems.append("ads: must be an object")
        else:
            for key in ads:
                if key not in ("enabled", "provider", "placements",
                               "frequencyPerDay", "house", "applovinSdkKey",
                               "unityGameId", "admobAppId", "admobBannerUnitId",
                               "admobNativeUnitId"):
                    problems.append(f"ads.{key}: unknown field")
            if "enabled" in ads and not isinstance(ads["enabled"], bool):
                problems.append("ads.enabled: must be a bool")
            if ads.get("provider") is not None and ads["provider"] not in AD_PROVIDERS:
                problems.append(f"ads.provider: one of {'|'.join(AD_PROVIDERS)}")
            pl = ads.get("placements")
            if pl is not None:
                if (not isinstance(pl, list) or len(pl) > 8
                        or any(p not in AD_PLACEMENTS for p in pl)):
                    problems.append("ads.placements: list <= 8 of known slot "
                                    f"ids ({', '.join(AD_PLACEMENTS)})")
            _check_int_range(ads.get("frequencyPerDay"), "ads.frequencyPerDay",
                             problems, 0, 20)
            house = ads.get("house")
            if house is not None:
                if not isinstance(house, dict):
                    problems.append("ads.house: must be an object")
                else:
                    for key in house:
                        if key not in ("title", "body", "actionLabel",
                                       "actionUrl", "id"):
                            problems.append(f"ads.house.{key}: unknown field")
                    _check_str_cap(house.get("title"), "ads.house.title",
                                   problems, TITLE_MAX, required=True)
                    _check_str_cap(house.get("body"), "ads.house.body",
                                   problems, BODY_MAX)
                    _check_str_cap(house.get("actionLabel"),
                                   "ads.house.actionLabel", problems,
                                   ACTION_LABEL_MAX)
                    _check_str_cap(house.get("id"), "ads.house.id", problems,
                                   ANNOUNCEMENT_ID_MAX)
                    _check_http_url(house.get("actionUrl"),
                                    "ads.house.actionUrl", problems)
            # Vendor credential slots (plan §4.6): runtime strings only, empty
            # until registration day — OTA-activatable without a rebuild.
            for key in ("applovinSdkKey", "unityGameId", "admobAppId",
                        "admobBannerUnitId", "admobNativeUnitId"):
                _check_str_cap(ads.get(key), f"ads.{key}", problems, 256)

    eng = obj.get("engagement")
    if eng is not None:
        if not isinstance(eng, dict):
            problems.append("engagement: must be an object")
        else:
            for key in eng:
                if key not in ("enabled", "inactiveDays", "cooldownDays",
                               "title", "body", "welcomeBack"):
                    problems.append(f"engagement.{key}: unknown field")
            if "enabled" in eng and not isinstance(eng["enabled"], bool):
                problems.append("engagement.enabled: must be a bool")
            _check_int_range(eng.get("inactiveDays"), "engagement.inactiveDays",
                             problems, 1, 365)
            _check_int_range(eng.get("cooldownDays"), "engagement.cooldownDays",
                             problems, 1, 365)
            _check_str_cap(eng.get("title"), "engagement.title", problems,
                           TITLE_MAX, required=True)
            _check_str_cap(eng.get("body"), "engagement.body", problems, BODY_MAX)
            if "welcomeBack" in eng and not isinstance(eng["welcomeBack"], bool):
                problems.append("engagement.welcomeBack: must be a bool")

    tam = obj.get("tamper")
    if tam is not None:
        if not isinstance(tam, dict):
            problems.append("tamper: must be an object")
        else:
            for key in tam:
                if key not in ("expectedCertSha256", "reportOnly"):
                    problems.append(f"tamper.{key}: unknown field")
            cert = tam.get("expectedCertSha256")
            if cert is not None and cert != "" and (
                    not isinstance(cert, str) or not CERT_HEX_RE.match(cert)):
                problems.append("tamper.expectedCertSha256: empty or 64 hex chars")
            if "reportOnly" in tam and not isinstance(tam["reportOnly"], bool):
                problems.append("tamper.reportOnly: must be a bool")

    fcm = obj.get("fcm")
    if fcm is not None:
        if not isinstance(fcm, dict):
            problems.append("fcm: must be an object")
        else:
            for key in fcm:
                if key not in ("enabled", "apiKey", "appId", "senderId", "projectId"):
                    problems.append(f"fcm.{key}: unknown field")
            if "enabled" in fcm and not isinstance(fcm["enabled"], bool):
                problems.append("fcm.enabled: must be a bool")
            for key in ("apiKey", "appId", "senderId", "projectId"):
                _check_str_cap(fcm.get(key), f"fcm.{key}", problems, 256)

    return problems


def reserved_site_field_notes(plain: bytes) -> list:
    """F-04 / Decision 13: INFO line naming the `sites` fields the app parses
    but never reads in main. Keeps a future OTA "fix" through those fields
    from ever looking functional. Empty list when the payload carries no
    sites section; malformed plaintext yields none (validate_schema refuses
    it separately)."""
    try:
        obj = json.loads(plain.decode("utf-8"))
        sites = obj.get("sites")
        if not isinstance(sites, dict) or not sites:
            return []
        return ["sites reserved fields (no readers in the app — F-04/Decision 13; "
                "editing them changes nothing): " + ", ".join(RESERVED_SITE_FIELDS)]
    except Exception:
        return []


def validate_schema(plain: bytes) -> list:
    """Returns a list of human-readable problems; empty list == valid."""
    problems = []
    if len(plain) > MAX_PLAINTEXT_BYTES:
        problems.append(f"payload too large: {len(plain)} bytes")
    try:
        obj = json.loads(plain.decode("utf-8"))
    except Exception as e:
        return [f"invalid JSON: {e}"]
    if not isinstance(obj, dict):
        return ["top level must be an object"]

    ver = obj.get("version")
    if not isinstance(ver, str) or not ver or len(ver) > 40:
        problems.append("version must be a non-empty short string")

    # --- Phase-2: rev + flags (additive payload fields; hard-validated here
    # because the client treats malformed flags as a fleet-wide kill switch). ---
    rev = obj.get("rev")
    if rev is not None and (isinstance(rev, bool) or not isinstance(rev, int) or rev <= 0 or rev > 2**31 - 1):
        # F6 (OTA red-team P2): a date-as-rev typo (e.g. 20261016) would
        # permanently floor the fleet's monotonic pointer — bound to int32.
        problems.append("rev must be a positive integer <= 2^31-1")

    flags = obj.get("flags")
    if flags is not None:
        if not isinstance(flags, dict):
            problems.append("flags must be an object")
        elif len(flags) > 32:
            problems.append("flags: too many entries (<= 32)")
        else:
            for fam, fcfg in flags.items():
                if fam not in FAMILY_IDS:
                    problems.append(
                        f"flags.{fam}: unknown family id (allowed: {', '.join(FAMILY_IDS)})")
                    continue
                if not isinstance(fcfg, dict):
                    problems.append(f"flags.{fam}: must be an object")
                    continue
                for sk, sv in fcfg.items():
                    if sk not in FLAG_SUBKEYS:
                        problems.append(
                            f"flags.{fam}.{sk}: unknown sub-key "
                            f"(allowed: {', '.join(FLAG_SUBKEYS)})")
                    elif sk in ("enabled", "shadow") and not isinstance(sv, bool):
                        problems.append(f"flags.{fam}.{sk}: must be a bool")
                    elif sk == "percent":
                        if isinstance(sv, bool) or not isinstance(sv, int) or not 0 <= sv <= 100:
                            problems.append(f"flags.{fam}.percent: must be an int 0..100")
                    elif sk == "expiresMs":
                        if isinstance(sv, bool) or not isinstance(sv, int) or sv <= 0:
                            problems.append(f"flags.{fam}.expiresMs: must be a positive int")
                    elif sk == "minApp":
                        # F2 (OTA red-team P1): the client parses minApp as an
                        # integer VERSION_CODE — a "3.2.10"-style string fails
                        # toIntOrNull there and the whole flag is SKIPPED,
                        # shipping the family fully ON to old builds. Require
                        # an integer string at publish time.
                        if not isinstance(sv, str) or not sv.isdigit() or not (1 <= len(sv) <= 9):
                            problems.append(f"flags.{fam}.minApp: must be an integer VERSION_CODE string (e.g. \"32910\")")

    def check_selector(val, where):
        if not isinstance(val, str):
            problems.append(f"{where}: selector must be a string")
        elif len(val) > MAX_SELECTOR_LEN:
            problems.append(f"{where}: selector too long ({len(val)})")

    domains = obj.get("domains", {})
    if not isinstance(domains, dict):
        problems.append("domains must be an object")
    else:
        for k, v in domains.items():
            if not isinstance(v, str) or not v.startswith("https://"):
                problems.append(f"domains.{k}: must be an https URL")

    mirrors = obj.get("mirrors", {})
    if not isinstance(mirrors, dict):
        problems.append("mirrors must be an object")
    else:
        for k, arr in mirrors.items():
            if not isinstance(arr, list) or len(arr) > MAX_RULES_ARRAY:
                problems.append(f"mirrors.{k}: must be a list (<= {MAX_RULES_ARRAY})")

    sites = obj.get("sites", {})
    if not isinstance(sites, dict):
        problems.append("sites must be an object")
    else:
        for name, cfg in sites.items():
            if not isinstance(cfg, dict):
                problems.append(f"sites.{name}: must be an object")
                continue
            for field in ("searchPattern", "cardSelector", "episodeSelector",
                          "downloadAnchorSelector"):
                if field in cfg:
                    check_selector(cfg[field], f"sites.{name}.{field}")
            for field in ("seriesDescentSelectors", "slugSuffixes"):
                if field in cfg and (not isinstance(cfg[field], list)
                                     or len(cfg[field]) > MAX_RULES_ARRAY):
                    problems.append(f"sites.{name}.{field}: list <= {MAX_RULES_ARRAY}")

    policies = obj.get("hostPolicies", [])
    if not isinstance(policies, list) or len(policies) > 128:
        problems.append(f"hostPolicies must be a list <= 128")
    else:
        for i, pol in enumerate(policies):
            if not isinstance(pol, dict) or "match" not in pol:
                problems.append(f"hostPolicies[{i}]: object with 'match' required")
            elif "referer" not in pol and "ua" not in pol:
                problems.append(f"hostPolicies[{i}]: needs referer and/or ua")
            elif "referer" in pol:
                ref = pol["referer"]
                if ref != "none" and not ref.startswith(("exact:", "site")):
                    problems.append(
                        f"hostPolicies[{i}].referer: must be none|site|exact:<url>")
            if isinstance(pol, dict) and len(pol.get("match", "")) > 120:
                problems.append(f"hostPolicies[{i}].match too long")

    # These collections used to be signature-through: ANY JSON type passed
    # here while DynamicRulesManager silently skips malformed entries
    # (optJSONObject/?: continue). Net effect: a typo shipped a playbook
    # where a whole feature quietly did nothing and nothing ever reported
    # it. The shapes below mirror what the executor actually consumes.
    resolvers = obj.get("resolvers")
    if resolvers is not None:
        if not isinstance(resolvers, dict) or len(resolvers) > MAX_RULES_ARRAY:
            problems.append(f"resolvers: object (<= {MAX_RULES_ARRAY} hosts)")
        else:
            for k, v in resolvers.items():
                if not isinstance(v, dict):
                    problems.append(f"resolvers.{k}: must be an object "
                                    "(the app silently drops it otherwise)")

    exts = obj.get("directMediaExtensions")
    if exts is not None:
        if not isinstance(exts, list) or len(exts) > MAX_RULES_ARRAY:
            problems.append(f"directMediaExtensions: list <= {MAX_RULES_ARRAY}")
        elif not all(isinstance(x, str) and 0 < len(x) <= 10 for x in exts):
            problems.append("directMediaExtensions: short strings, e.g. \".mp4\"")

    for name in ("knownDead", "lockerHosts"):
        arr = obj.get(name)
        if arr is None:
            continue
        if not isinstance(arr, list) or len(arr) > 512:
            problems.append(f"{name}: list <= 512")
        elif not all(
            isinstance(x, str) and 0 < len(x) <= 120 and " " not in x
            and ("." in x or (len(x) >= 5 and x.replace("-", "").isalnum()))
            for x in arr):
            # F4 (OTA red-team P0): the client does a CONTAINS match on these
            # fragments — a tiny string like "a" would match nearly every url
            # and freeze resolution fleet-wide from one typo. Host-like
            # (dot) OR a real token (>= 5 alnum chars) is required; the live
            # playbook ships both shapes ("vidsrc.buzz", "jiminido", "waffi").
            problems.append(f"{name}: host-like fragments (dot) or >=5-char alnum tokens, <= 120, no spaces")

    ut = obj.get("urlTemplates")
    if ut is not None:
        if not isinstance(ut, dict) or len(ut) > MAX_RULES_ARRAY:
            problems.append(f"urlTemplates: object (<= {MAX_RULES_ARRAY} keys)")
        else:
            for k, v in ut.items():
                if not isinstance(v, str) or not v.strip() or len(v) > 500:
                    problems.append(f"urlTemplates.{k}: non-empty string <= 500")

    ss = obj.get("searchStrategies")
    if ss is not None:
        if not isinstance(ss, dict) or len(ss) > 128:
            problems.append("searchStrategies: object keyed by site (<= 128)")
        else:
            for k, arr in ss.items():
                if not isinstance(arr, list) or len(arr) > 16:
                    problems.append(f"searchStrategies.{k}: list of <= 16 steps")
                elif not all(isinstance(s, dict) and isinstance(s.get("type"), str)
                             and s["type"].strip() for s in arr):
                    problems.append(f"searchStrategies.{k}: every step needs a "
                                    "string 'type' (unknown/absent types are dead weight)")

    dp = obj.get("dynamic_providers")
    if dp is not None:
        if not isinstance(dp, list) or len(dp) > 64:
            problems.append("dynamic_providers: list <= 64")
        else:
            for i, p in enumerate(dp):
                if (not isinstance(p, dict)
                        or not isinstance(p.get("id"), str) or not p["id"].strip()
                        or not isinstance(p.get("base_url"), str)
                        or not p["base_url"].strip()):
                    problems.append(f"dynamic_providers[{i}]: object with "
                                    "non-empty id and base_url required")

    for key in obj.keys():
        if key not in ("version", "domains", "mirrors", "sites", "resolvers",
                       "directMediaExtensions", "dynamic_providers", "lockerHosts",
                       "hostPolicies", "searchStrategies", "urlTemplates",
                       "knownDead", "tokenTtlMinutes", "validation",
                       "minAppVersion", "pipelines", "remote_logging_url",
                       "rev", "flags", "contentPolicy",
                       "announcements", "update", "ads", "engagement",
                       "tamper", "fcm",
                       "downloader_routing", "engine_profiles", "proxy_unwrappers",
                       "resolver_gates", "resolver_crypto", "permissive_tls_hosts",
                       "hls_keep_referer_hosts", "streaming_url_markers",
                       "preflight_passthrough_markers", "decoy_signatures",
                       "failover_clusters"):
            problems.append(f"unknown top-level field '{key}' (add it to the "
                            "allow-list in encrypt_rules.py AND DynamicRulesManager)")

    mav = obj.get("minAppVersion")
    if mav is not None and (not isinstance(mav, int) or isinstance(mav, bool) or mav <= 0):
        problems.append("minAppVersion must be a positive integer")

    # Remote error-log sink (Track 6): consumed verbatim by
    # DynamicRulesManager.getRemoteLoggingUrl / RemoteLogger, so only a
    # well-formed http(s) endpoint string may ship — the app additionally
    # re-checks the scheme at parse and drops anything else.
    rlu = obj.get("remote_logging_url")
    if rlu is not None:
        if (not isinstance(rlu, str)
                or not rlu.startswith(("http://", "https://"))
                or len(rlu) > 300):
            problems.append(
                "remote_logging_url: http(s) URL string <= 300 chars")
        elif not rlu.split("://", 1)[1]:
            problems.append("remote_logging_url: host part missing")

    # contentPolicy (2026-10): OTA overlay consumed by
    # DynamicRulesManager.buildContentPolicy / HostPolicyRegistry — keep the
    # two in lockstep (same as FAMILY_IDS). Lenient on the client, strict here:
    # a typo'd policy would silently no-op on every device.
    cp = obj.get("contentPolicy")
    if cp is not None:
        if not isinstance(cp, dict):
            problems.append("contentPolicy: must be an object")
        else:
            caps = cp.get("socketCaps")
            if caps is not None:
                if not isinstance(caps, dict) or len(caps) > 64:
                    problems.append(
                        "contentPolicy.socketCaps: object of <= 64 host->int entries")
                else:
                    for h, v in caps.items():
                        if (not isinstance(h, str) or not h.strip()
                                or isinstance(v, bool) or not isinstance(v, int)
                                or not 1 <= v <= 16):
                            problems.append(
                                f"contentPolicy.socketCaps.{h}: host->int 1..16")
            if "suppressNollywood" in cp and not isinstance(cp["suppressNollywood"], bool):
                problems.append("contentPolicy.suppressNollywood: must be a bool")
            for arr_name, cap in (("nollywoodSites", 64),
                                  ("nollywoodOnlySites", 64),
                                  ("nollywoodTerms", 128),
                                  ("episodesAuthority", 32)):
                arr = cp.get(arr_name)
                if arr is not None and (not isinstance(arr, list) or len(arr) > cap
                                        or any(not isinstance(x, str) or not x.strip()
                                               for x in arr)):
                    problems.append(
                        f"contentPolicy.{arr_name}: list of <= {cap} non-empty strings")
            # Per-site OTA switch (2026-10-20): sites listed here are disabled
            # for NEW work; absent/blank/malformed on-device parses as all-on.
            ds = cp.get("disabledSites")
            if ds is not None:
                if not isinstance(ds, list) or len(ds) > 64:
                    problems.append(
                        "contentPolicy.disabledSites: array of <= 64 strings")
                else:
                    for site in ds:
                        if not isinstance(site, str) or not site.strip() or len(site) > 64:
                            problems.append(
                                "contentPolicy.disabledSites: entries must be "
                                "non-empty strings <= 64 chars")
            for key in cp:
                if key not in ("socketCaps", "suppressNollywood",
                               "nollywoodSites", "nollywoodOnlySites",
                               "nollywoodTerms", "disabledSites",
                               "episodesAuthority"):
                    problems.append(f"contentPolicy.{key}: unknown field")

    # v3.3.0 platform surfaces (plan §4.1) — announcements / update / ads /
    # engagement / tamper / fcm, strict like contentPolicy above.
    problems.extend(validate_platform_sections(obj))

    # v3.4.0 OTA engine decoupling surfaces
    problems.extend(validate_engine_sections(obj))

    problems.extend(validate_pipelines(obj))
    return problems


def load_private_key(explicit=None):
    pem = None
    if explicit:
        pem = open(explicit, "rb").read()
    elif os.environ.get("OTA_SIGNING_PRIVATE_KEY"):
        pem = os.environ["OTA_SIGNING_PRIVATE_KEY"].encode()
        if b"\\n" in pem and b"-----BEGIN" not in pem:
            pem = pem.replace(b"\\n", b"\n")  # secret pasted with literal \n
    elif os.path.exists(KEY_PATH):
        pem = open(KEY_PATH, "rb").read()
    if not pem:
        return None
    return serialization.load_pem_private_key(pem, password=None)


def encrypt_random_iv(plaintext: bytes, iv: bytes) -> bytes:
    padder = padding.PKCS7(128).padder()
    padded = padder.update(plaintext) + padder.finalize()
    enc = Cipher(algorithms.AES(RULES_KEY), modes.CBC(iv)).encryptor()
    return enc.update(padded) + enc.finalize()


def decrypt_envelope_or_legacy(text: str) -> bytes:
    """Round-trip helper used by --verify; understands both formats."""
    text = text.strip()
    if text.startswith("{"):
        env = json.loads(text)
        iv = bytes.fromhex(env["iv"])
        ct = base64.b64decode(env["payload"])
        padder = padding.PKCS7(128).unpadder()
        dec = Cipher(algorithms.AES(RULES_KEY), modes.CBC(iv)).decryptor()
        return padder.update(dec.update(ct) + dec.finalize()) + padder.finalize()
    from cryptography.hazmat.primitives.ciphers import Cipher as _C  # legacy fixed IV
    iv = bytes.fromhex("5b7e9d2f4a6c8e10f3a5c7d9b1e2f4a6")
    ct = base64.b64decode(text)
    dec = _C(algorithms.AES(RULES_KEY), modes.CBC(iv)).decryptor()
    padder = padding.PKCS7(128).unpadder()
    return padder.update(dec.update(ct) + dec.finalize()) + padder.finalize()


def _generate_hierarchy_key(tag: str) -> None:
    """Generates an ECDSA P-256 keypair for the ROOT/LEAF hierarchy into
    keys/ (gitignored). REFUSES to overwrite an existing key of the same
    tag — a cold root must never be silently clobbered by a re-run."""
    os.makedirs(KEYS_DIR, exist_ok=True)
    priv_path = os.path.join(KEYS_DIR, f"ota_{tag}_private_key.pem")
    pub_path = os.path.join(KEYS_DIR, f"ota_{tag}_public_key.pem")
    if os.path.exists(priv_path) or os.path.exists(pub_path):
        print(f"REFUSING to overwrite existing keys/ota_{tag}_*_key.pem — "
              "rename or remove the previous pair deliberately, then re-run "
              "(custody rule: a cold key is never silently clobbered).")
        sys.exit(1)
    priv = ec.generate_private_key(ec.SECP256R1())
    with open(priv_path, "wb") as f:
        f.write(priv.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))
    with open(pub_path, "wb") as f:
        f.write(priv.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    pub_b64 = pub_to_b64(priv.public_key())
    b64_lines = [pub_b64[i:i + 96] for i in range(0, len(pub_b64), 96)]
    print(f"wrote {priv_path}")
    print(f"wrote {pub_path}")
    print(f"public key (SPKI-DER base64) — this EXACT string is what the app pins:")
    for ln in b64_lines:
        print(f"  \"{ln}\"")
    print(f"SHA-256 fingerprint (SPKI DER): {pub_fingerprint(priv.public_key())}")
    return priv_path, pub_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(SERVERLESS, "scraper_rules.json.enc"))
    ap.add_argument("--in", dest="inp", default=os.path.join(SERVERLESS, "scraper_rules.json"))
    ap.add_argument("--key", default=None, help="PEM private key path (legacy single-key mode)")
    ap.add_argument("--no-sign", action="store_true")
    ap.add_argument("--gen-keys", action="store_true")
    # --- step 7: root-pin + leaf signing rotation (additive flags) ---
    ap.add_argument("--root-key", default=None,
                    help="ROOT private-key PEM (signs the leaf's public key "
                         "into envelope.chain; pass together with --leaf-key)")
    ap.add_argument("--leaf-key", default=None,
                    help="LEAF private-key PEM (signs the payload in chain "
                         "mode; pass together with --root-key)")
    ap.add_argument("--generate-root", action="store_true",
                    help="generate the ROOT keypair into keys/ (default tag "
                         "root2; private half = cold custody on this PC)")
    ap.add_argument("--generate-leaf", action="store_true",
                    help="generate a LEAF keypair into keys/ (needs --root)")
    ap.add_argument("--leaf-name", default="leaf1",
                    help="leaf key tag for --generate-leaf (leaf1, leaf2, ...)")
    ap.add_argument("--root", default=None, metavar="PEM",
                    help="root key PEM (private or public) for --generate-leaf")
    ap.add_argument("--inspect", default=None, metavar="PEM",
                    help="print SPKI-DER base64 + SHA-256 fingerprint of any key PEM and exit")
    args = ap.parse_args()

    if args.inspect:
        k = load_any_key(args.inspect)
        pub = k.public_key() if hasattr(k, "sign") else k
        print(f"{args.inspect}")
        if hasattr(k, "sign"):
            print("  kind: PRIVATE key")
        else:
            print("  kind:  public key")
        print(f"  pub_b64: {pub_to_b64(pub)}")
        print(f"  fingerprint (SHA-256, SPKI DER): {pub_fingerprint(pub)}")
        return

    if args.gen_keys:
        # Legacy single-key generation (pre-step-7 path, unchanged behavior).
        priv = ec.generate_private_key(ec.SECP256R1())
        os.makedirs(os.path.dirname(KEY_PATH), exist_ok=True)
        open(KEY_PATH, "wb").write(priv.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))
        pubpem = priv.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        open(KEY_PATH.replace("_private_", "_public_"), "wb").write(pubpem)
        print(f"wrote {KEY_PATH} (+ public key PEM)")
        print("ACTION REQUIRED: paste the PRIVATE pem into the GitHub secret "
              "OTA_SIGNING_PRIVATE_KEY.")
        return

    if args.generate_root:
        _generate_hierarchy_key("root2")
        print("ACTION REQUIRED: private half = COLD. Keep it ONLY in keys/ "
              "(gitignored); the public half gets pinned into "
              "DynamicRulesManager.kt (ROTATION_ROOT2_PUB_B64) and "
              "scripts/encrypt_rules.py (ROOT2_PUB_B64). See "
              "docs/SIGNING_ROTATION.md.")
        return

    if args.generate_leaf:
        if not args.root:
            ap.error("--generate-leaf needs the custodial root PEM: --root <pem>")
        root = load_any_key(args.root)
        if root is None:
            print(f"could not load root PEM: {args.root}", file=sys.stderr)
            sys.exit(1)
        root_pub = root.public_key() if hasattr(root, "sign") else root
        print(f"custodial root: {args.root}")
        print(f"  root fingerprint (SHA-256, SPKI DER): {pub_fingerprint(root_pub)}")
        _generate_hierarchy_key(args.leaf_name)
        print("Custody: leaf private half may be promoted into the CI secret "
              "OTA_LEAF_PRIVATE_KEY for chain builds; rotate freely — the app "
              "verifies it only through the pinned root (no app release for a "
              "leaf rotation). See docs/SIGNING_ROTATION.md.")
        return

    # Chain mode: BOTH --root-key and --leaf-key, or neither (legacy).
    chain_mode = args.root_key is not None or args.leaf_key is not None
    if chain_mode and (args.root_key is None or args.leaf_key is None):
        ap.error("chain mode needs BOTH --root-key and --leaf-key, or neither "
                 "(legacy single-key envelope)")
    if chain_mode and args.key is not None:
        ap.error("--key is the legacy single-key signer; chain mode signs the "
                 "payload with --leaf-key (omit --key)")
    if chain_mode and args.no_sign:
        ap.error("chain mode always signs: drop --no-sign")

    with open(args.inp, "rb") as f:
        plain = f.read()

    problems = validate_schema(plain)
    if problems:
        print("SCHEMA VALIDATION FAILED — refusing to emit a payload:")
        for p in problems:
            print("  -", p)
        sys.exit(1)

    # F-04 / Decision 13: reserved `sites` fields get an INFO line on every
    # publish (and every --dry-run, which routes through this main()).
    for note in reserved_site_field_notes(plain):
        print(f"INFO: {note}")

    iv = _secrets.token_bytes(16)
    ct = encrypt_random_iv(plain, iv)
    payload_b64 = base64.b64encode(ct).decode("ascii")

    # Chain mode signs the payload with the LEAF only: the legacy default-key
    # lookup (env OTA_SIGNING_PRIVATE_KEY / maintenance folder) must NEVER be
    # consulted here — with --key absent it loads root-1 and would silently
    # downgrade the envelope to legacy while printing "chain".
    key = None if (args.no_sign or chain_mode) else load_private_key(args.key)
    leaf_priv = root_priv = None
    if chain_mode:
        # validate -> encrypt -> sign order preserved: signing material is
        # loaded only AFTER validation succeeded.
        leaf_priv = load_private_key(args.leaf_key)
        root_priv = load_private_key(args.root_key)
        if leaf_priv is None:
            print(f"CHAIN MODE: could not load leaf private key: {args.leaf_key}", file=sys.stderr)
            sys.exit(1)
        if root_priv is None:
            print(f"CHAIN MODE: could not load root private key: {args.root_key}", file=sys.stderr)
            sys.exit(1)

    if key is not None:
        sk = key.sign(payload_b64.encode("ascii"), ec.ECDSA(hashes.SHA256()))
        sig_b64 = base64.b64encode(sk).decode("ascii")
        env = {"v": 2, "alg": "aes-128-cbc", "iv": iv.hex(),
               "payload": payload_b64, "sig": sig_b64}
    elif chain_mode:
        # Chain envelope (step 7): payload sig by the LEAF; chain.rootSig is
        # the ROOT's signature over the CANONICAL bytes of the leaf pubkey —
        # the exact ASCII bytes of the base64 string as serialized below.
        # ADDITIVE-ONLY: keys are exactly today's order plus "chain" last.
        sk = leaf_priv.sign(payload_b64.encode("ascii"), ec.ECDSA(hashes.SHA256()))
        sig_b64 = base64.b64encode(sk).decode("ascii")
        leaf_pub_b64 = pub_to_b64(leaf_priv.public_key())
        root_sig = root_priv.sign(leaf_pub_b64.encode("ascii"), ec.ECDSA(hashes.SHA256()))
        env = {"v": 2, "alg": "aes-128-cbc", "iv": iv.hex(),
               "payload": payload_b64, "sig": sig_b64,
               "chain": {"leafPubB64": leaf_pub_b64,
                         "rootSig": base64.b64encode(root_sig).decode("ascii"),
                         "alg": CHAIN_ALG}}
    else:
        if not args.no_sign:
            # F9 (OTA red-team P2): the fleet REJECTS unsigned envelopes — the
            # old "app accepts it until enforcement flips" note was wrong.
            # Refuse to write a would-be-dead artifact; explicit --no-sign
            # remains for local/dev-only runs.
            print("ERROR: no signing key found and --no-sign not passed — "
                  "refusing to emit an UNSIGNED payload (the app/fleet "
                  "REJECTS unsigned envelopes). Add OTA_SIGNING_PRIVATE_KEY "
                  "or pass --key.")
            sys.exit(2)
        print("WARNING: emitting UNSIGNED payload via explicit --no-sign "
              "(fleet REJECTS it — local/dev artifact only).")
        env = {"v": 1, "alg": "aes-128-cbc", "iv": iv.hex(), "payload": payload_b64}

    out_text = json.dumps(env, separators=(",", ":"))

    # round-trip self-check with this implementation before writing
    assert decrypt_envelope_or_legacy(out_text) == plain, "round-trip mismatch"

    # publish-gate parity: the envelope MUST verify with this exact
    # implementation before it may be written. Chain envelopes self-verify
    # against the root that produced rootSig (CI/fleet verifies against the
    # pinned root set; see docs/SIGNING_ROTATION.md for the drill).
    if key is not None:
        verify_envelope(out_text, pub_to_b64(key.public_key()))
    elif chain_mode:
        verify_envelope(out_text, root_pins_b64=[pub_to_b64(root_priv.public_key())])

    with open(args.out, "w", encoding="utf-8") as f:
        f.write(out_text)

    if chain_mode:
        mode = "SIGNED chain (root + leaf)"
        print(f"chain: leaf fingerprint {pub_fingerprint(leaf_priv.public_key())}")
        print(f"chain: root fingerprint {pub_fingerprint(root_priv.public_key())}")
    else:
        mode = "SIGNED" if key is not None else "unsigned"
    print(f"wrote {args.out} ({mode}, v{env['v']}, {len(plain)} bytes plaintext)")


if __name__ == "__main__":
    main()
