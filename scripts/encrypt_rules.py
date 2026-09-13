"""Scraper-rules OTA pipeline: validate -> encrypt -> sign -> envelope.

Produces the payload served at scraper_rules.json.enc (repo root of
download-toolkit-serverless). Envelope format v2:

    {"v": 2,
     "alg": "aes-128-cbc",
     "iv": "<32 hex chars, random per encryption>",
     "payload": "<base64 AES-CBC-PKCS7 ciphertext>",
     "sig": "<base64 DER ECDSA-P256-SHA256 signature over the payload string>"}

Security model:
- Confidentiality stays obfuscation-grade (AES key ships in the APK) — it
  stops casual reading of the scraping logic, not dedicated analysis.
- INTEGRITY/AUTHENTICITY is real: the payload is signed with an ECDSA
  P-256 key whose private half exists ONLY as the GitHub Actions secret
  OTA_SIGNING_PRIVATE_KEY. The app embeds the public half and rejects any
  payload that fails verification. A repo hijack or CDN MITM can no longer
  feed users fake rules.
- The IV is random per encryption (upgrades the historical fixed-IV CBC).

Usage:
  python encrypt_rules.py                # validate + encrypt + sign (if key found)
  python encrypt_rules.py --no-sign      # validate + encrypt only (legacy-style output)
  python encrypt_rules.py --gen-keys     # generate the keypair locally

Key lookup order for signing: --key <pem path> | $OTA_SIGNING_PRIVATE_KEY |
~/anon-serverless-app-maintenance-build/ota_signing_private_key.pem
(the dedicated maintenance folder OUTSIDE both repos — never committed).
"""
import argparse
import base64
import json
import os
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
KEY_PATH = os.environ.get("OTA_SIGNING_KEY_FILE") or os.path.join(
    os.path.expanduser("~"), "anon-serverless-app-maintenance-build", "ota_signing_private_key.pem")

# PUBLIC half of the OTA signing keypair (safe to commit — it can only
# VERIFY, never sign). Matches the constant embedded in the app's
# DynamicRulesManager; used by CI to validate the committed .enc.
OTA_PUB_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn6hCCQBtu6YyguDIMtKvefov9uwD"
    "0uN9JP0HKkYUJB1wSL3Q928+lQ=="
)

# --- strict schema guards (a bad payload must be rejected BEFORE commit) ---
MAX_PLAINTEXT_BYTES = 512 * 1024
MAX_SELECTOR_LEN = 600
MAX_RULES_ARRAY = 64
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
                _check_field_spec(sg["seasonSpec"], f"{where}.seasonGrouping.seasonSpec",
                                  problems, prefixes=DOC_FIELD_SPEC_PREFIXES,
                                  allow_self=False)
            tmpl = sg.get("labelTemplate")
            if tmpl is not None and (not isinstance(tmpl, str) or len(tmpl) > MAX_SELECTOR_LEN):
                problems.append(
                    f"{where}.sectionGrouping.labelTemplate: string <= {MAX_SELECTOR_LEN}")

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
        if step.get("as", "html") not in ("html", "json", "rss"):
            problems.append(f"{w}.as: must be html|json|rss")

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
            if src.get("method", "GET") not in ("GET", "POST"):
                problems.append(f"{sw}.method: must be GET|POST")
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

        bind = step.get("bind")
        if bind is not None:
            if not isinstance(bind, dict):
                problems.append(f"{w}.bind: must be an object")
            else:
                for bk, bv in bind.items():
                    if (not isinstance(bv, dict)
                            or not any(k in bv for k in ("regex", "json", "selector"))):
                        problems.append(f"{w}.bind.{bk}: needs regex|json|selector")

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


def verify_envelope(text: str, pub_b64: str = OTA_PUB_B64) -> bool:
    """Authenticity check for CI/app parity: ECDSA-P256-SHA256 over the
    payload string. False for unsigned envelopes, tampered payloads or
    unknown keys. Raises SystemExit with a reason on invalid JSON."""
    import base64 as b64
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    env = json.loads(text.strip())
    sig = env.get("sig")
    if not sig:
        raise SystemExit("UNSIGNED envelope — refused")
    pub = load_der_public_key(b64.b64decode(pub_b64))
    try:
        pub.verify(b64.b64decode(env["sig"]), env["payload"].encode("ascii"),
                   ec.ECDSA(hashes.SHA256()))
        return True
    except InvalidSignature:
        raise SystemExit("SIGNATURE INVALID — payload tampered or wrong key")


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

    for key in obj.keys():
        if key not in ("version", "domains", "mirrors", "sites", "resolvers",
                       "directMediaExtensions", "dynamic_providers", "lockerHosts",
                       "hostPolicies", "searchStrategies", "urlTemplates",
                       "knownDead", "tokenTtlMinutes", "validation",
                       "minAppVersion", "pipelines"):
            problems.append(f"unknown top-level field '{key}' (add it to the "
                            "allow-list in encrypt_rules.py AND DynamicRulesManager)")

    mav = obj.get("minAppVersion")
    if mav is not None and (not isinstance(mav, int) or isinstance(mav, bool) or mav <= 0):
        problems.append("minAppVersion must be a positive integer")

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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(SERVERLESS, "scraper_rules.json.enc"))
    ap.add_argument("--in", dest="inp", default=os.path.join(SERVERLESS, "scraper_rules.json"))
    ap.add_argument("--key", default=None, help="PEM private key path")
    ap.add_argument("--no-sign", action="store_true")
    ap.add_argument("--gen-keys", action="store_true")
    args = ap.parse_args()

    if args.gen_keys:
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

    with open(args.inp, "rb") as f:
        plain = f.read()

    problems = validate_schema(plain)
    if problems:
        print("SCHEMA VALIDATION FAILED — refusing to emit a payload:")
        for p in problems:
            print("  -", p)
        sys.exit(1)

    iv = _secrets.token_bytes(16)
    ct = encrypt_random_iv(plain, iv)
    payload_b64 = base64.b64encode(ct).decode("ascii")

    key = None if args.no_sign else load_private_key(args.key)
    if key is not None:
        sk = key.sign(payload_b64.encode("ascii"), ec.ECDSA(hashes.SHA256()))
        sig_b64 = base64.b64encode(sk).decode("ascii")
        env = {"v": 2, "alg": "aes-128-cbc", "iv": iv.hex(),
               "payload": payload_b64, "sig": sig_b64}
    else:
        if not args.no_sign:
            print("WARNING: no signing key found — emitting UNSIGNED payload "
                  "(app accepts it until enforcement flips). Add the "
                  "OTA_SIGNING_PRIVATE_KEY secret or pass --key/--no-sign.")
        env = {"v": 1, "alg": "aes-128-cbc", "iv": iv.hex(), "payload": payload_b64}

    out_text = json.dumps(env, separators=(",", ":"))

    # round-trip self-check with this implementation before writing
    assert decrypt_envelope_or_legacy(out_text) == plain, "round-trip mismatch"

    with open(args.out, "w", encoding="utf-8") as f:
        f.write(out_text)

    mode = "SIGNED" if key is not None else "unsigned"
    print(f"wrote {args.out} ({mode}, v{env['v']}, {len(plain)} bytes plaintext)")


if __name__ == "__main__":
    main()
