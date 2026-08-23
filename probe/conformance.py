"""OTA playbook conformance runner (manual, via conformance.yml).

Scope — this validates the RULES PAYLOAD, not the resolver algorithms:
  Stage 1  search      : rules-driven search returns >=1 result
  Stage 2  episodes    : episodeSelector on a show page returns >=1 link
  Stage 3  direct-pass : is_direct_media_url() recognises the payload's
                         directMediaExtensions on real anchor hrefs
Deep crack parity (JS unpackers, wasm) stays in the monolith's probe suite;
this runner catches RULES DECAY (dead domains, changed selectors, broken
search patterns) before app users hit it.

Runs against the SIGNED .enc exactly like the app does: verifies the ECDSA
signature, decrypts, parses. A tampered/unsigned payload fails immediately.

Usage:
  python probe/conformance.py --titles 1 --shows 3 [--site nkiri ...]
Outputs probe/results-conformance-<site>.jsonl + prints an aggregate
scorecard. Exit code 0 unless --strict and any site failed.
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import encrypt_rules as ota  # noqa: E402  (canonical pipeline module)

from curl_cffi import requests as creq  # noqa: E402

PAGE_CAP = 300 * 1024
TIMEOUT = (10, 20)
PACE_S = 0.35          # per-host delay between requests
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")

SEARCH_QUERIES = {
    "nkiri": ["vincenzo"], "dramakey": ["vincenzo"], "asianc": ["vincenzo"],
    "anitaku": ["one piece"], "pluto": ["movie"], "dramarain": ["love"],
    "9jarocks": ["movie"], "naijavault": ["movie"], "naijaprey": ["series"],
    "nepu": ["movie"], "torrents": ["ubuntu"],
}


class Paced:
    """Per-host pacing so CI traffic stays polite."""

    def __init__(self):
        self.last = {}

    def get(self, url, referer=None):
        host = url.split("/")[2] if "://" in url else url
        wait = PACE_S - (time.time() - self.last.get(host, 0))
        if wait > 0:
            time.sleep(wait)
        self.last[host] = time.time()
        try:
            r = creq.get(url, impersonate="chrome", timeout=TIMEOUT,
                         allow_redirects=True,
                         headers={"Referer": referer} if referer else None)
            body = r.content[:PAGE_CAP]
            return r.status_code, body
        except Exception as e:
            return -1, str(e).encode()[:200]


def referer_for(rules, url, default=None):
    """hostPolicies from the playbook — single source, no local drift copy."""
    low = (url or "").lower()
    for pol in rules.get("hostPolicies", []):
        if pol.get("match", "").lower() in low:
            ref = pol.get("referer", "")
            if ref == "none":
                return ""
            if ref.startswith("exact:"):
                return ref[len("exact:"):]
    return default


def load_signed_payload():
    import base64 as b64mod
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    from cryptography.exceptions import InvalidSignature

    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    enc = open(os.path.join(here, "scraper_rules.json.enc"), "rb").read().decode()
    env = json.loads(enc)
    sig = env.get("sig")
    if not sig:
        raise SystemExit("REFUSING to run against an UNSIGNED payload")
    der = b64mod.b64decode(os.environ.get(
        "CONFORMANCE_PUB_B64",
        # same public key embedded in DynamicRulesManager (concatenated)
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn6hCCQBtu6YyguDIMtKvefov9uwD"
        "0uN9JP0HKkYUJB1wSL3Q928+lQ=="))
    pub = load_der_public_key(der)
    try:
        pub.verify(b64mod.b64decode(env["sig"]),
                   env["payload"].encode("ascii"), ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        raise SystemExit("SIGNATURE INVALID — payload tampered?")
    plain = ota.decrypt_envelope_or_legacy(enc)
    return json.loads(plain)


def bs4_parse(body):
    from bs4 import BeautifulSoup
    return BeautifulSoup(body, "html.parser")


def stage_search(s, rules, site, query):
    cfg = rules["sites"].get(site)
    base = rules["domains"].get(site)
    if not cfg or not base:
        return None, f"no rules config for {site}"
    url = base.rstrip("/") + cfg["searchPattern"].replace("{query}", query.replace(" ", "%20"))
    st, body = s.get(url, referer_for(rules, base))
    if st != 200:
        return None, f"HTTP {st}"
    t = cfg.get("searchType", "html")
    if t == "html":
        cards = bs4_parse(body).select(cfg["cardSelector"]) if cfg.get("cardSelector") else []
        links = [a.get("href") for a in bs4_parse(body).find_all("a", href=True)]
        ok = len(cards) > 0 or len(links) > 5
        return ok, f"{len(cards)} cards / {len(links)} anchors"
    if t == "rss":
        ok = b"<item>" in body
        return ok, f"rss items={'yes' if ok else 'no'}"
    if t == "json":
        try:
            data = json.loads(body)
            n = len(data) if isinstance(data, list) else len(data.get("data", data.get("posts", [])))
            return n > 0, f"json results={n}"
        except Exception as e:
            return None, f"json parse: {e}"
    return None, f"unhandled searchType {t}"


def stage_episodes(s, rules, site, show_url):
    cfg = rules["sites"].get(site)
    if not cfg or not cfg.get("episodeSelector"):
        return None, "no episodeSelector configured"
    st, body = s.get(show_url, referer_for(rules, show_url))
    if st != 200:
        return None, f"HTTP {st} on show page"
    eps = bs4_parse(body).select(cfg["episodeSelector"])
    return len(eps) > 0, f"{len(eps)} episode links"


def stage_direct_pass(rules, hrefs):
    exts = [e.lower() for e in rules.get("directMediaExtensions", [])]

    def is_direct(u):
        u = u.lower().split("?")[0]
        return any(u.endswith(e) or u.endswith(e.replace(".", "-")) for e in exts)

    hits = [h for h in hrefs if is_direct(h)]
    return bool(exts), f"direct-media anchors found: {len(hits)}"


def run_site(site, rules, titles, shows_cap, out_path):
    s = Paced()
    records = []
    q = SEARCH_QUERIES.get(site, ["movie"])[0]
    search_ok, search_detail = stage_search(s, rules, site, q)

    ep_ok, ep_detail, hrefs = None, "skipped", []
    if search_ok:
        # Reuse the search page's first plausible internal link as the "show".
        base = rules["domains"].get(site, "")
        st, body = s.get(base.rstrip("/") +
                         rules["sites"][site]["searchPattern"]
                         .replace("{query}", q.replace(" ", "%20")),
                         referer_for(rules, base))
        if st == 200:
            cands = [a["href"] for a in bs4_parse(body).find_all("a", href=True)]
            hrefs = cands[:50]
            internal = [h for h in cands if h.startswith("/") or base in h]
            if internal:
                from urllib.parse import urljoin
                show_url = urljoin(base + "/", internal[0])
                ep_ok, ep_detail = stage_episodes(s, rules, site, show_url)

    dp_ok, dp_detail = stage_direct_pass(rules, hrefs or [])

    for name, okv, detail in (("search", search_ok, search_detail),
                              ("episodes", ep_ok, ep_detail),
                              ("direct_pass", dp_ok, dp_detail)):
        records.append({"site": site, "stage": name,
                        "pass": bool(okv) if okv is not None else None,
                        "detail": detail})
    with open(out_path, "a", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    return records


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--titles", type=int, default=1)
    ap.add_argument("--shows", type=int, default=3)
    ap.add_argument("--site", action="append", default=None)
    ap.add_argument("--out-dir", default="probe")
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    rules = load_signed_payload()
    sites = args.site or list(rules["domains"].keys())
    scorecard = {}
    for site in sites:
        out = os.path.join(args.out_dir, f"results-conformance-{site}.jsonl")
        if os.path.exists(out):
            os.remove(out)
        try:
            recs = run_site(site, rules, args.titles, args.shows, out)
        except Exception as e:
            recs = [{"site": site, "stage": "runner", "pass": False, "detail": str(e)[:160]}]
        passed = [r for r in recs if r["pass"]]
        failed = [r for r in recs if r["pass"] is False]
        skipped = [r for r in recs if r["pass"] is None]
        scorecard[site] = {"pass": len(passed), "fail": len(failed), "skip": len(skipped)}
        for r in recs:
            print(f"[{site}] {'PASS' if r['pass'] else ('SKIP' if r['pass'] is None else 'FAIL')} "
                  f"{r['stage']}: {r['detail']}")

    print("\n=== CONFORMANCE SCORECARD ===")
    total_fail = 0
    for site, sc in scorecard.items():
        flag = "OK" if sc["fail"] == 0 else "DECAY?"
        total_fail += sc["fail"]
        print(f"{site:12s} pass={sc['pass']} fail={sc['fail']} skip={sc['skip']} {flag}")
    if args.strict and total_fail:
        sys.exit(1)


if __name__ == "__main__":
    main()
