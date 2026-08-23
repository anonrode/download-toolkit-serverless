"""OTA playbook conformance runner (manual, via conformance.yml).

Scope — this validates the RULES PAYLOAD, not the resolver algorithms:
  Stage 1  search          : rules-driven search returns >=1 result
  Stage 2  episodes        : episodeSelector on a show page returns >=1 link
  Stage 3  direct-pass     : is_direct_media_url() recognises the payload's
                             directMediaExtensions on real anchor hrefs
  Stage 4  locker-discovery: extract plausible media/locker links from the
                             show page and diff hosts vs the playbook's
                             lockerHosts — reports unknown hosts that the
                             app would learn via HostHealth.hasProvenLocker
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
import re
import sys
import time
from urllib.parse import quote, urljoin, urlparse

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import encrypt_rules as ota  # noqa: E402  (canonical pipeline module)

from curl_cffi import requests as creq  # noqa: E402

PAGE_CAP = 300 * 1024
TIMEOUT = (10, 20)
PACE_S = 0.35          # per-host delay between requests
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")

# Mirror of LockerRegistry.kt's built-in defaults, so the discovery diff
# matches what the app would classify WITHOUT the playbook (playbook
# lockerHosts are added at runtime; see classify_media()).
DEFAULT_LOCKER_HOSTS = [
    "streamsss.net", "streamwish.com", "streamtape.com", "doodstream.com",
    "dood.", "vidhide.com", "mixdrop.co", "mp4upload.com", "hglink.tv",
    "loadedfiles.net", "downloadwella.com", "wetafiles.com",
    "vikingfile.com", "lulacloud.com", "waffi", "pixeldrain.com",
    "filevault", "kissorgrab.com", "wildshare", "gtoddl", "wapkizfile",
    "fastupload.io", "gofile.io", "krakenfiles.com", "swish",
]


# Mirror of LockerRegistry.kt's nav-junk path segments (unknown hosts only).
NAV_SEGMENTS = {
    "tag", "category", "categories", "dmca", "menu", "page", "pages",
    "author", "about", "contact", "privacy", "policy", "terms", "sitemap",
    "feed", "login", "register", "signin", "signup", "account", "cart",
    "checkout", "search", "faq", "help", "request", "submit", "advertise",
    "wp-content", "wp-json", "wp-admin", "cdn-cgi", "email-protection",
    "series-download", "movie-download", "download-movies", "download-series",
    "cant-download", "downloader", "date", "archive",
}


def classify_media(url, known_hosts):
    """Mirror LockerRegistry.classify(): 'direct' | 'locker' | 'unknown' | 'none'.
    Hostname-boundary matching, nav-junk path segments — same semantics as the app."""
    from urllib.parse import urlparse
    clean = (url or "").split("?")[0].split("#")[0]
    if not clean:
        return "none"
    try:
        p = urlparse(clean)
        host = (p.hostname or "").lower()
        path = p.path or ""
    except Exception:
        return "none"
    if not host:
        return "none"
    ext = clean.rsplit(".", 1)[-1].lower()
    if ext in ("mp4", "mkv", "webm", "avi", "m3u8", "m4v", "ts", "mp3"):
        return "direct"
    for kh in known_hosts:
        kh = kh.lower()
        if host == kh or host.endswith("." + kh):
            return "locker"
    segments = [s for s in path.split("/") if s]
    if "/dl-" in path or ("/download/" in path and len(segments) >= 3):
        return "unknown"
    nav = any(s in NAV_SEGMENTS or s.startswith("how-to")
              or s.endswith("-menu") or "movies" in s for s in segments)
    if nav:
        return "none"
    if not segments:
        return "none"
    if len(segments) == 1:
        seg = segments[0]
        show_like = ("-episode-" in seg or "season" in seg or "-movie-" in seg
                     or (seg.endswith("-drama") and seg.count("-") >= 2))
        if not show_like:
            return "none"
    return "unknown"

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
    # Empty secret (unset) must not shadow the embedded public key.
    der = b64mod.b64decode((os.environ.get("CONFORMANCE_PUB_B64") or "").strip()
                           or "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn6hCCQBtu6YyguDIMtKvefov9uwD"
                           "0uN9JP0HKkYUJB1wSL3Q928+lQ==")
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


# ================= search-quality mode (strict hits) ======================
# Lesson from the side-session fuzz test (log_search_test_results.json):
# counting "page returned 200 with content" as a hit scores fake shows
# 15/15. A hit here means: parsed RESULT TITLES fuzzy-match the query.

def _norm(s):
    import re
    return re.sub(r"[^a-z0-9 ]", " ", s.lower()).split()


def _fuzzy_hit(query, titles):
    """True when any result title closely matches the query tokens."""
    q = " ".join(_norm(query))
    if not q:
        return False
    for t in titles:
        tt = " ".join(_norm(t))
        if not tt:
            continue
        if q in tt or tt in q:
            return True
        import difflib
        if difflib.SequenceMatcher(None, q, tt).ratio() >= 0.6:
            return True
    return False


def extract_titles(site, rules, st, body):
    """Pull human-readable result titles out of each site's response shape."""
    titles = []
    cfg = rules["sites"].get(site, {})
    t = cfg.get("searchType", "html")
    try:
        if t == "rss":
            import re
            titles = re.findall(rb"<title>(.*?)</title>", body, re.S)[1:]
            titles = [x.decode("utf-8", "ignore") for x in titles]
        elif t == "json":
            data = json.loads(body)

            def walk(o):
                if isinstance(o, dict):
                    for k, v in o.items():
                        if k.lower() in ("title", "name") and isinstance(v, str) and v.strip():
                            titles.append(v)
                        else:
                            walk(v)
                elif isinstance(o, list):
                    for x in o:
                        walk(x)
            walk(data)
        else:
            soup = bs4_parse(body)
            sel = cfg.get("cardSelector") or "article"
            for card in soup.select(sel):
                a = card if card.name == "a" else card.select_first("a[href], h2 a, .entry-title a")
                txt = (a.get_text(" ", strip=True) if a else "") or ""
                if txt.strip():
                    titles.append(txt)
            if not titles:  # last resort: every anchor text on the page
                titles = [a.get_text(" ", strip=True) for a in soup.find_all("a", href=True)]
    except Exception:
        pass
    return [x for x in titles if x and len(x) > 2][:40]


CURATED_QUERIES = [
    # real titles across the catalogs (K-drama / Nollywood / anime / movies)
    "vincenzo", "cobra kai", "the impossible", "the beekeeper", "titanic",
    "uncharted", "house of anubis", "talking stage", "kesari",
    "eran iya osogbo", "muniru ati ambali", "kori kosun", "abbott",
    "the heirs", "yellow stone", "divergent", "goblin", "the omen",
    # deliberate fakes + typos: correct behaviour is a MISS on these
    "denice the menace", "cobta", "dennice the menamce", "yellow stont",
    "the tatanic", "bee keejet",
]


def run_search_quality(s, rules, site, queries, out_path):
    """Per-query strict-hit testing; returns records."""
    records = []
    base = rules["domains"].get(site, "")
    cfg = rules["sites"].get(site)
    if not base or not cfg:
        return [{"site": site, "query": "-", "pass": None, "detail": "no search config"}]
    for q in queries:
        url = base.rstrip("/") + cfg["searchPattern"].replace("{query}", q.replace(" ", "%20"))
        st, body = s.get(url, referer_for(rules, base))
        titles = extract_titles(site, rules, st, body) if st == 200 else []
        hit = _fuzzy_hit(q, titles)
        records.append({"site": site, "query": q,
                        "pass": hit if st == 200 else None,
                        "status": st, "titles_seen": len(titles),
                        "detail": f"HTTP {st}, {len(titles)} titles" + ("" if not hit else ", MATCH")})
        with open(out_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(records[-1]) + "\n")
    return records


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--titles", type=int, default=1)
    ap.add_argument("--shows", type=int, default=3)
    ap.add_argument("--site", action="append", default=None)
    ap.add_argument("--out-dir", default="probe")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--search-quality", action="store_true",
                    help="strict per-query hit testing instead of the stage sweep")
    ap.add_argument("--queries-file", default=None,
                    help="JSON file with 'searches' list; default = curated subset")
    args = ap.parse_args()

    rules = load_signed_payload()
    sites = args.site or list(rules["domains"].keys())
    total_fail = 0

    if args.search_quality:
        queries = CURATED_QUERIES
        if args.queries_file:
            fixture = json.load(open(args.queries_file, encoding="utf-8"))
            queries = fixture["searches"]
        print(f"search-quality: {len(queries)} queries x {len(sites)} sites\n")
        scorecard = {}
        for site in sites:
            out = os.path.join(args.out_dir, f"results-searchq-{site}.jsonl")
            if os.path.exists(out):
                os.remove(out)
            s = Paced()
            try:
                recs = run_search_quality(s, rules, site, queries, out)
            except Exception as e:
                recs = [{"site": site, "query": "-", "pass": False, "detail": str(e)[:160]}]
            hits = sum(1 for r in recs if r["pass"])
            tested = sum(1 for r in recs if r["pass"] is not None)
            scorecard[site] = {"hits": hits, "tested": tested}
        print("\n=== SEARCH-QUALITY SCORECARD ===")
        for site, sc in scorecard.items():
            rate = 100.0 * sc["hits"] / sc["tested"] if sc["tested"] else 0
            print(f"{site:12s} {sc['hits']}/{sc['tested']} strict hits ({rate:.0f}%)")
        return

    # ---- default mode: stage sweep ----
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
        return None, "no episodeSelector configured", None
    st, body = s.get(show_url, referer_for(rules, show_url))
    if st != 200:
        return None, f"HTTP {st} on show page", None
    eps = bs4_parse(body).select(cfg["episodeSelector"])
    return len(eps) > 0, f"{len(eps)} episode links", body


def stage_direct_pass(rules, hrefs):
    exts = [e.lower() for e in rules.get("directMediaExtensions", [])]

    def is_direct(u):
        u = u.lower().split("?")[0]
        return any(u.endswith(e) or u.endswith(e.replace(".", "-")) for e in exts)

    hits = [h for h in hrefs if is_direct(h)]
    return bool(exts), f"direct-media anchors found: {len(hits)}"


def stage_locker_discovery(rules, site, body, page_url):
    """Extract every plausible media/locker link from the show page (same
    extraction rules as LockerRegistry.findLockerLinksInHtml) and diff their
    hosts against the playbook's lockerHosts + built-in defaults.

    PASS = extraction works. The detail reports UNKNOWN HOSTS FOUND — hosts
    the playbook never seeded. Those are a playbook GAP, not a bug: the app
    learns them via HostHealth.hasProvenLocker on first use. FAIL = no
    plausible media links at all (selectors/extraction decayed).
    """
    known = [h.lower() for h in rules.get("lockerHosts", [])]
    known += DEFAULT_LOCKER_HOSTS
    found, unknown = [], {}
    try:
        soup = bs4_parse(body)
        for a in soup.select("a[href], a[data-video], a[data-src], "
                             "iframe[src], video[src], source[src]"):
            u = a.get("href") or a.get("data-video") or a.get("data-src") or a.get("src")
            if not u:
                continue
            u = urljoin(page_url, u)
            kind = classify_media(u, known)
            if kind == "none":
                continue
            found.append(u)
            if kind == "unknown":
                host = urlparse(u).hostname or "?"
                unknown.setdefault(host.lower(), []).append(u)
    except Exception as e:
        return None, f"extract error: {e}"
    if not found:
        return False, "no plausible media/locker links on page (extraction decayed?)"
    detail = f"{len(found)} media links"
    if unknown:
        hosts = ", ".join(f"{h}({len(v)} links)" for h, v in sorted(unknown.items()))
        detail += f" | UNKNOWN HOSTS FOUND: {hosts}"
    return True, detail


def run_strategy_chain(s, rules, site, query):
    """Execute the site's OTA searchStrategies chain, in order, until one
    yields a real show URL. Mirrors SearchStrategyRunner.kt semantics:
      slugGuess  : probe pattern URLs with {slug} (query slugified) and each
                   {suffix}/{country} variant; HTTP 200 = found show URL.
      urlTemplate: fetch pattern URL, extract card links via cardSelector +
                   linkSelector; drop shallow nav-junk links (requires >=3
                   path segments, same guard as the app).
      rss        : fetch pattern URL, take <item> <link> entries.
    Returns (show_url, strategy_type) or (None, None).
    """
    strategies = rules.get("searchStrategies", {}).get(site, [])
    if not strategies:
        return None, None
    base = rules["domains"].get(site, "")
    if not base:
        return None, None

    for st in strategies:
        stype = st.get("type")
        if stype == "slugGuess":
            slug = re.sub(r"[^a-z0-9]+", "-", query.lower()).strip("-")
            pattern = st.get("pattern", "/{slug}{suffix}/")
            suffixes = st.get("suffixes", [""])
            countries = st.get("countries", [])
            # Playbook may express variants as {suffix} or {country}.
            variants = countries if countries else suffixes
            placeholder = "{country}" if countries else "{suffix}"
            for v in variants:
                url = (base.rstrip("/") + pattern
                       .replace("{slug}", slug)
                       .replace(placeholder, v))
                code, _ = s.get(url, referer_for(rules, base))
                if code == 200:
                    return url, "slugGuess"

        elif stype == "urlTemplate":
            pattern = st.get("pattern", "/?s={query}")
            url = base.rstrip("/") + pattern.replace("{query}", quote(query))
            code, body = s.get(url, referer_for(rules, base))
            if code != 200:
                continue
            soup = bs4_parse(body)
            card_sel = st.get("cardSelector", "article")
            link_sel = st.get("linkSelector", "a[href], h2 a, .entry-title a")
            for card in soup.select(card_sel):
                a = card if card.name == "a" else card.select_one(link_sel)
                if not a or not a.get("href"):
                    continue
                full_url = urljoin(base + "/", a["href"])
                # Nav-junk guard, same semantics as SearchStrategyRunner.kt:
                # dead search endpoints return category cards like
                # /chinese-drama/ (1 segment); real show pages are deep.
                # Count slashes after the first path segment, require >=2.
                path = full_url.replace(base, "").strip("/")
                if path.count("/") < 2:
                    continue
                return full_url, "urlTemplate"

        elif stype == "rss":
            pattern = st.get("pattern", "/search/{query}/feed/rss2/")
            url = base.rstrip("/") + pattern.replace("{query}", quote(query))
            code, body = s.get(url, referer_for(rules, base))
            if code != 200:
                continue
            if b"<item" not in body:
                continue
            soup = bs4_parse(body)
            for item in soup.select("item"):
                link = item.select_one("link")
                if link:
                    href = link.text.strip() or link.get("href", "")
                    if href:
                        return href, "rss"

    return None, None


def run_site(site, rules, titles, shows_cap, out_path):
    s = Paced()
    records = []
    q = SEARCH_QUERIES.get(site, ["movie"])[0]
    search_ok, search_detail = stage_search(s, rules, site, q)

    ep_ok, ep_detail, ep_body = None, "skipped", None
    show_url = None
    via_strategy = None
    if search_ok:
        # Reuse the search page's first plausible internal link as the "show".
        base = rules["domains"].get(site, "")
        st, body = s.get(base.rstrip("/") +
                         rules["sites"][site]["searchPattern"]
                         .replace("{query}", q.replace(" ", "%20")),
                         referer_for(rules, base))
        if st == 200:
            stype_cfg = rules["sites"][site].get("searchType", "html")
            if stype_cfg == "rss":
                # <link> is a void element under the HTML parser, so its URL
                # text never lands inside the tag — regex the raw body, same
                # approach as extract_titles() for <title>.
                links = [u.decode("utf-8", "ignore").strip()
                         for u in re.findall(rb"<item>.*?<link>(.*?)</link>", body, re.S)]
                links = [u for u in links if u]
                hrefs = links[:50]
                if links:
                    show_url = urljoin(base + "/", links[0])
                    ep_ok, ep_detail, ep_body = stage_episodes(s, rules, site, show_url)
            else:
                cands = [a["href"] for a in bs4_parse(body).find_all("a", href=True)]
                hrefs = cands[:50]
                internal = [h for h in cands if h.startswith("/") or base in h]
                if internal:
                    show_url = urljoin(base + "/", internal[0])
                    ep_ok, ep_detail, ep_body = stage_episodes(s, rules, site, show_url)

    # Strategy-chain fallback: a dead/junk search endpoint (dramarain's ?s=
    # returns category cards) or a chosen link with no episode links must not
    # report FAIL when the playbook's searchStrategies find the real show.
    if not ep_ok:
        strategy_url, stype = run_strategy_chain(s, rules, site, q)
        if strategy_url:
            ep_ok, ep_detail, ep_body = stage_episodes(s, rules, site, strategy_url)
            via_strategy = stype
            if ep_ok:
                ep_detail = f"{ep_detail} [via strategy: {stype}]"

    dp_ok, dp_detail = stage_direct_pass(rules, hrefs if search_ok else [])

    # Stage 4: locker discovery on whichever page we landed on (the show page
    # carries the download links; the strategy page carries them too when the
    # search endpoint was junk).
    ld_ok, ld_detail = None, "no page fetched"
    if ep_body:
        page_url = strategy_url if via_strategy else show_url
        ld_ok, ld_detail = stage_locker_discovery(rules, site, ep_body, page_url or "")

    for name, okv, detail in (("search", search_ok, search_detail),
                              ("episodes", ep_ok, ep_detail),
                              ("direct_pass", dp_ok, dp_detail),
                              ("locker_discovery", ld_ok, ld_detail)):
        rec = {"site": site, "stage": name,
               "pass": bool(okv) if okv is not None else None,
               "detail": detail}
        if name == "episodes" and via_strategy:
            rec["via"] = "strategy"
        records.append(rec)
    with open(out_path, "a", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    return records


if __name__ == "__main__":
    main()
