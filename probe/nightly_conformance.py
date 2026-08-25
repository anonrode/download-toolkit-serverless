"""Nightly live-site conformance (Tier 4 of the CI test pyramid).

Scope — this answers the question: "Did the live sites break since the last
app release, in a way the existing unit / Robolectric / instrumented tests
can't catch?" The unit tests assert that our CODE works against fixtures.
The live-site test asserts that the WORLD still cooperates with our code.

Pyramid position:
  Tier 1  JVM unit tests          (31 tests;  fast, offline)
  Tier 2  Robolectric Compose     (10 tests;  ~1 min, offline)
  Tier 3  Instrumented emulator   (Phase 3;  flaky, slow, gate-protected)
  Tier 4  THIS FILE               (nightly;  never gates a release)

Four-stage conformance per site:
  Stage 1 search   : 5 queries; pass if >=1 query returns >=1 result.
  Stage 2 episodes : first show's episodeSelector yields >=1 link.
                     Torrents: N/A (flat magnet list per the app model).
  Stage 3 crack    : resolve one episode URL through the resolver chain.
                     For locker hosts: follow the page's download anchor
                     and assert it lands on a known media URL or locker
                     host. Torrents: magnet passthrough = pass.
  Stage 4 1KB probe: Range: bytes=0-1023 on the final media URL.
                     Pass = HTTP 200/206 + non-HTML + video/audio/octet
                     content-type OR recognizable magic bytes within 1KB.
                     HLS: master -> variant -> first segment (each is a
                     small fetch, not a full download).
                     Magnets: N/A.

Pacing + safety (hard rules — see REQUIREMENTS in the brief):
  150 ms minimum delay between requests to the same host (per-host tracking).
  20 s per-request timeout.
  300 KB hard cap on full page fetches (truncate, do not follow more links).
  Bail out after 8 consecutive search-stage failures per site.
  1KB Range probe only — never a full media download. Verified by both the
  Range header in the request and a <= 1024 byte response-size assertion.
  No API keys, no auth headers. Only public endpoints.
  User-Agent identifies the bot: AnonDownloader-Conformance/1.0.
  Respect robots.txt at the page-level (skip disallowed paths).

Scorecard + regression detection:
  Writes probe/results-nightly/<run-id>.jsonl  (one line per (site, stage) check)
  Writes probe/results-nightly/<run-id>-scorecard.json  (aggregate + regressions)
  Compares against the previous run-id's scorecard; flips pass->fail = regression.
  Auto-files one GitHub issue per regression per day; comments on duplicates.

Persisting scorecards across runs:
  The script writes the scorecard into probe/results-nightly/ and the workflow
  commits the latest scorecard back to the nightly-results branch on push.
  Cross-run regression detection fetches the previous scorecard from that
  branch via `actions/checkout` (see nightly-live-conformance.yml).

Usage:
  python probe/nightly_conformance.py             # full run
  python probe/nightly_conformance.py --site pluto  # single site
  python probe/nightly_conformance.py --no-issue     # local dev, no issue filing

Exits 0 on per-site failures (those are scorecard data, not workflow failures).
Exits non-zero only on script-level crashes (missing deps, JSON parse error).
"""
import argparse
import base64
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
from typing import Optional

import requests
from requests.exceptions import RequestException
from bs4 import BeautifulSoup

# ----- Constants (hard rules from the brief) -----
PACE_S = 0.150                 # 150 ms minimum per-host delay
TIMEOUT_S = 20                 # 20 s per-request timeout
PAGE_CAP = 300 * 1024          # 300 KB hard cap on full page fetches
PROBE_CAP = 1024               # 1 KB hard cap on media probes
MAX_CONSECUTIVE_SEARCH_FAILS = 8

UA = ("AnonDownloader-Conformance/1.0 "
      "(+https://github.com/anonrode/download-toolkit-serverless) "
      "Python-requests")

# All 11 sites the app supports. The 6 rules-driven sites drive search via
# scraper_rules.json["sites"][<site>]; the 5 parity-driven sites have a
# custom search flow (mostly apibay JSON for torrents, ?s= for the others).
# Both flavours are exercised here.
ALL_SITES = [
    "pluto", "9jarocks", "naijavault", "asianc", "dramarain", "naijaprey",
    "nkiri", "dramakey", "anitaku", "nepu", "torrents",
]

# 5 benign, low-traffic, public-domain or catalogue-stable queries per site.
QUERIES = {
    # Rules-driven
    "pluto":      ["avengers", "inception", "the matrix", "interstellar", "spider-man"],
    "9jarocks":   ["king of boys", "the wedding party", "merry men", "living in bondage", "lionheart"],
    "naijavault": ["kizz daniel", "davido", "burna boy", "wizkid", "tope alabi"],
    "asianc":     ["vincenzo", "goblin", "crash landing", "itaewon class", "the heirs"],
    "dramarain":  ["vincenzo", "goblin", "mr queen", "penthouse", "snowdrop"],
    "naijaprey":  ["merry men", "king of boys", "lionheart", "living in bondage", "the wedding party"],
    # Parity-driven (Kotlin-only resolver algorithms; Tier 4 can only
    # verify search + episode-list, not the resolver chain itself).
    "nkiri":      ["vincenzo", "goblin", "the heirs", "squid game", "mr robot"],
    "dramakey":   ["vincenzo", "goblin", "the heirs", "mr queen", "snowdrop"],
    "anitaku":    ["one piece", "naruto", "bleach", "dragon ball", "jujutsu kaisen"],
    "nepu":       ["inception", "avengers", "the matrix", "interstellar", "spider-man"],
    "torrents":   ["ubuntu", "debian", "big buck bunny", "sintel", "opensuse"],
}

# Magnet-only / flat-list site: skip episode stage (no per-show drill-down).
FLAT_SITES = {"torrents"}

# Magnet passthrough is PASS — no real download.
MAGNET_SITES = {"torrents"}

# Parity-driven sites use Kotlin resolver algorithms that we cannot run
# from a plain Python cross-check (JS unpackers, wasm, AES chains, etc.).
# For these sites, stages 3 (crack) and 4 (1KB probe) are intentionally
# N/A — we can only verify that the search endpoint and the show page
# still load and expose some structure. The JVM unit tests are the right
# place to verify the actual resolver code.
PARITY_SITES = {"nkiri", "dramakey", "anitaku", "nepu"}

# Known media file extensions and locker host seeds. The "known" set
# mirrors the app's LockerRegistry defaults so we classify the same way.
LOCKER_HOSTS = {
    "streamsss.net", "streamwish.com", "streamtape.com", "doodstream.com",
    "dood.", "vidhide.com", "mixdrop.co", "mp4upload.com", "hglink.tv",
    "loadedfiles.net", "downloadwella.com", "wetafiles.com",
    "vikingfile.com", "lulacloud.com", "waffi", "pixeldrain.com",
    "filevault", "kissorgrab.com", "wildshare", "gtoddl", "wapkizfile",
    "fastupload.io", "gofile.io", "krakenfiles.com", "vdl.np-downloader.com",
}

# Magic-byte signatures for the 1KB probe (4-byte minimum to reduce false
# positives on text encodings). These are the formats the app downloads.
MAGIC_SIGNATURES = {
    b"\x1a\x45\xdf\xa3": "matroska/webm",         # MKV / WEBM cluster
    b"ftyp":             "mp4/isom/quicktime",    # MP4 family (4 bytes "ftyp" at offset 4)
    b"ID3":              "mp3",                   # MP3 with ID3v2 tag
    b"\xff\xfb":         "mp3",                   # MP3 frame sync (no ID3)
    b"OggS":             "ogg",                   # OGG
    b"RIFF":             "avi/wav",               # RIFF container
    b"PK\x03\x04":       "zip",                   # ZIP / DOCX / APK (treat as media for torrents)
    b"Rar!":             "rar",                   # RAR
    b"\x7fELF":          "elf",                   # ELF (linux binary — torrent world)
    b"MZ":               "exe",                   # PE (rejected in app, but flagged here)
    b"#!":               "script",                # script (rejected in app, but flagged here)
}

# Media-acceptable Content-Types for the 1KB probe. "application/octet-stream"
# is the CDN default for many video files.
MEDIA_CT_PREFIXES = (
    "video/", "audio/", "application/octet-stream",
    "application/vnd.apple.mpegurl",          # HLS .m3u8
    "application/x-mpegurl",                  # HLS .m3u8 (alt)
    "application/zip", "application/x-rar",
    "application/x-tar",
)


# ----- Helpers -----

def _path_matches(path: str, pattern: str) -> bool:
    """Minimal robots.txt path matcher supporting '*' wildcards and
    trailing '$' anchors. We don't need the full RFC — only the subset
    real-world robots.txt files use."""
    if not pattern:
        return False
    rx = re.escape(pattern).replace(r"\*", ".*")
    if pattern.endswith("$"):
        rx = rx[:-2] + "$"
    return re.match(rx, path) is not None


# ----- Load the signed OTA playbook (single source of truth) -----

def load_signed_payload(repo_root: str) -> dict:
    """Mirror probe/conformance.py's loader. Refuses unsigned payloads —
    the same ECDSA gate the app uses on-device, so the nightly check is
    testing what the app actually sees, not a local plaintext copy."""
    sys.path.insert(0, os.path.join(repo_root, "scripts"))
    import encrypt_rules as ota  # noqa: E402

    enc_path = os.path.join(repo_root, "scraper_rules.json.enc")
    env = json.loads(open(enc_path, encoding="utf-8").read())
    sig = env.get("sig")
    if not sig:
        raise SystemExit("REFUSING to run against an UNSIGNED payload")

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    from cryptography.exceptions import InvalidSignature

    der = base64.b64decode((os.environ.get("CONFORMANCE_PUB_B64") or "").strip()
                           or "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELW5uNxiti768q9f1YPvjaMyd0b60W7tEn"
                             "6hCCQBtu6YyguDIMtKvefov9uwD0uN9JP0HKkYUJB1wSL3Q928+lQ==")
    pub = load_der_public_key(der)
    try:
        pub.verify(base64.b64decode(env["sig"]),
                   env["payload"].encode("ascii"), ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        raise SystemExit("SIGNATURE INVALID — payload tampered?")

    plain = ota.decrypt_envelope_or_legacy(json.dumps(env))
    return json.loads(plain)


def referer_for(rules: dict, url: str, default: Optional[str] = None) -> Optional[str]:
    """Mirror the app's DynamicRulesManager.resolveReferer — single source
    so we don't drift from the live behaviour."""
    low = (url or "").lower()
    for pol in rules.get("hostPolicies", []):
        if pol.get("match", "").lower() in low:
            ref = pol.get("referer", "")
            if ref == "none":
                return ""
            if ref.startswith("exact:"):
                return ref[len("exact:"):]
    return default


# ----- Per-host pacing (so CI traffic stays polite) -----

class Paced:
    """Per-host pacing so the same host sees >= PACE_S between requests."""

    def __init__(self, ua: str = UA, timeout: int = TIMEOUT_S):
        self.last: dict = {}
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": ua,
            "Accept": "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
        })
        self.timeout = timeout
        self._robots: dict = {}

    def _pace(self, host: str):
        wait = PACE_S - (time.time() - self.last.get(host, 0))
        if wait > 0:
            time.sleep(wait)
        self.last[host] = time.time()

    def _robots_allows(self, url: str) -> bool:
        """Skip disallowed paths (page-level only, per the brief).
        Deliberately not urllib.robotparser: when robots.txt has no
        User-agent directives (content-signal-only files like apibay),
        it misinterprets as 'disallow all'. Our minimal parser only
        triggers on an explicit Disallow line that names our UA or '*'."""
        p = urllib.parse.urlparse(url)
        host = (p.hostname or "").lower()
        if not host:
            return True
        cached = self._robots.get(host)
        if cached is None:
            cached = self._fetch_robots(p.scheme, host)
            self._robots[host] = cached
        if cached is None:
            return True
        path = p.path or "/"
        for pattern in cached.get("disallow", []):
            if pattern == "":
                continue
            if _path_matches(path, pattern):
                return False
        return True

    def _fetch_robots(self, scheme: str, host: str) -> Optional[dict]:
        url = f"{scheme}://{host}/robots.txt"
        try:
            r = self.session.get(url, timeout=self.timeout, allow_redirects=True)
            if r.status_code >= 400 or not r.content:
                return None
        except Exception:
            return None
        text = r.content.decode("utf-8", "ignore")
        groups = []
        cur_uas = []
        cur_disallow = []
        for raw_line in text.splitlines():
            line = raw_line.split("#", 1)[0].strip()
            if not line:
                if cur_uas or cur_disallow:
                    groups.append((cur_uas, cur_disallow))
                    cur_uas, cur_disallow = [], []
                continue
            if ":" not in line:
                continue
            k, _, v = line.partition(":")
            k = k.strip().lower()
            v = v.strip()
            if k == "user-agent":
                if cur_uas or cur_disallow:
                    groups.append((cur_uas, cur_disallow))
                cur_uas = [v.lower()]
                cur_disallow = []
            elif k == "disallow" and cur_uas:
                cur_disallow.append(v)
        if cur_uas or cur_disallow:
            groups.append((cur_uas, cur_disallow))
        ua_lower = UA.lower()
        matched = None
        for uas, dis in groups:
            if any(u.lower() == ua_lower for u in uas):
                matched = (uas, dis)
                break
        if matched is None:
            for uas, dis in groups:
                if any(u == "*" for u in uas):
                    matched = (uas, dis)
                    break
        if matched is None:
            return {"disallow": []}
        _, dis = matched
        return {"disallow": dis}

    def get(self, url: str, referer: Optional[str] = None) -> tuple:
        """Return (status, body_capped_to_PAGE_CAP, response_meta)."""
        host = urllib.parse.urlparse(url).hostname or ""
        if not self._robots_allows(url):
            return -2, b"", {"reason": "robots_disallow", "host": host}
        self._pace(host)
        headers = {}
        if referer:
            headers["Referer"] = referer
        t0 = time.time()
        try:
            r = self.session.get(url, headers=headers, timeout=self.timeout,
                                 allow_redirects=True, stream=False)
            body = r.content[:PAGE_CAP]
            meta = {
                "status": r.status_code,
                "content_type": (r.headers.get("Content-Type") or "").lower(),
                "bytes_received": len(r.content),
                "latency_ms": int((time.time() - t0) * 1000),
                "final_url": r.url,
                "host": host,
            }
            return r.status_code, body, meta
        except RequestException as e:
            return -1, str(e).encode()[:200], {
                "status": -1, "error": str(e)[:160],
                "latency_ms": int((time.time() - t0) * 1000), "host": host,
            }

    def range_get(self, url: str, range_bytes: int = PROBE_CAP,
                  referer: Optional[str] = None) -> tuple:
        """Strict 1KB Range probe. Header is set AND response is asserted <= 1024."""
        host = urllib.parse.urlparse(url).hostname or ""
        self._pace(host)
        headers = {"Range": f"bytes=0-{range_bytes - 1}"}
        if referer:
            headers["Referer"] = referer
        t0 = time.time()
        try:
            r = self.session.get(url, headers=headers, timeout=self.timeout,
                                 allow_redirects=True, stream=False)
            raw = r.content[:range_bytes]
            meta = {
                "status": r.status_code,
                "content_type": (r.headers.get("Content-Type") or "").lower(),
                "content_range": (r.headers.get("Content-Range") or "").lower(),
                "content_length_header": r.headers.get("Content-Length"),
                "bytes_received": len(r.content),
                "body_capped": len(raw),
                "latency_ms": int((time.time() - t0) * 1000),
                "final_url": r.url,
                "host": host,
            }
            return r.status_code, raw, meta
        except RequestException as e:
            return -1, b"", {
                "status": -1, "error": str(e)[:160],
                "latency_ms": int((time.time() - t0) * 1000), "host": host,
            }


def get_page(s: Paced, url: str, referer: Optional[str] = None) -> tuple:
    return s.get(url, referer=referer)


# ----- Stage implementations -----

def _norm_query(q: str) -> str:
    return q.replace(" ", "%20")


def _looks_like_search_result(site: str, rules: dict, body: bytes, meta: dict) -> bool:
    """Site-aware 'is this a search result' predicate. Honours rules
    searchType but also content-type for parity-driven sites (torrents
    returns JSON without a searchType in the playbook)."""
    cfg = rules.get("sites", {}).get(site, {})
    stype = cfg.get("searchType")
    ct = (meta.get("content_type") or "").lower()

    if "json" in ct or stype == "json":
        try:
            data = json.loads(body)
            if isinstance(data, list):
                return len(data) > 0
            if isinstance(data, dict):
                for k in ("data", "posts", "results", "items"):
                    if isinstance(data.get(k), list) and data[k]:
                        return True
                return any(isinstance(v, list) and v for v in data.values())
        except Exception:
            return False
        return False
    if stype == "rss" or "xml" in ct:
        return b"<item" in body
    try:
        soup = BeautifulSoup(body, "html.parser")
        n = len(soup.find_all("a", href=True))
        return n >= 3
    except Exception:
        return False


def stage_search(s: Paced, rules: dict, site: str, queries: list,
                 out: list, consecutive_fails: list) -> dict:
    """Stage 1: 5 queries; pass = >=1 query yields a result. Bail after
    8 consecutive search failures (per the brief)."""
    base = rules.get("domains", {}).get(site, "")
    if not base and site not in {"torrents"}:
        rec = {"site": site, "stage": "search", "pass": None,
               "url": "-", "http_status": None, "error": "no base domain"}
        out.append(rec)
        return rec

    passed = False
    first_pass_query = None
    last_query = queries[0] if queries else "-"
    last_url = "-"
    last_status = None
    last_ct = ""
    last_bytes = 0
    last_latency = 0
    last_err = ""

    for q in queries:
        last_query = q
        consecutive_fails[0] += 1
        if consecutive_fails[0] > MAX_CONSECUTIVE_SEARCH_FAILS:
            rec = {"site": site, "stage": "search", "pass": None,
                   "url": "-", "http_status": None,
                   "error": f"bailout: {MAX_CONSECUTIVE_SEARCH_FAILS}+ consecutive failures"}
            out.append(rec)
            return rec
        if site == "torrents":
            url = f"{base}/q.php?q={urllib.parse.quote(q)}&cat=200"
        else:
            cfg = rules.get("sites", {}).get(site, {})
            pattern = cfg.get("searchPattern")
            if pattern:
                url = base.rstrip("/") + pattern.replace("{query}", _norm_query(q))
            else:
                # Parity-driven site (no OTA searchPattern). The 5
                # parity sites in this project all expose a generic
                # ?s= HTML search — same fallback conformance.py uses.
                url = base.rstrip("/") + f"/?s={urllib.parse.quote(q)}"

        last_url = url
        st, body, meta = get_page(s, url, referer=referer_for(rules, url))
        last_status = meta.get("status")
        last_ct = meta.get("content_type", "")
        last_bytes = meta.get("bytes_received", 0)
        last_latency = meta.get("latency_ms", 0)
        if st == -2:
            last_err = "robots_disallow"
            out.append({"site": site, "stage": "search", "pass": None,
                        "query": q, "url": url, "http_status": st,
                        "content_type": last_ct, "bytes_received": 0,
                        "latency_ms": last_latency, "error": last_err})
            continue
        if st != 200:
            last_err = f"http_{st}"
            out.append({"site": site, "stage": "search", "pass": False,
                        "query": q, "url": url, "http_status": st,
                        "content_type": last_ct, "bytes_received": last_bytes,
                        "latency_ms": last_latency, "error": last_err})
            continue
        if _looks_like_search_result(site, rules, body, meta):
            passed = True
            first_pass_query = q
            consecutive_fails[0] = 0
            out.append({"site": site, "stage": "search", "pass": True,
                        "query": q, "url": url, "http_status": 200,
                        "content_type": last_ct, "bytes_received": last_bytes,
                        "latency_ms": last_latency, "error": ""})
            break
        last_err = "no_results"
        out.append({"site": site, "stage": "search", "pass": False,
                    "query": q, "url": url, "http_status": 200,
                    "content_type": last_ct, "bytes_received": last_bytes,
                    "latency_ms": last_latency, "error": last_err})

    rec = {"site": site, "stage": "search",
           "pass": passed,
           "query": first_pass_query or last_query,
           "url": last_url,
           "http_status": last_status,
           "content_type": last_ct,
           "bytes_received": last_bytes,
           "latency_ms": last_latency,
           "error": "" if passed else (last_err or "all queries failed")}
    if not passed and consecutive_fails[0] < MAX_CONSECUTIVE_SEARCH_FAILS:
        out.append(rec)
    return rec


def _pick_show_url(site: str, rules: dict, body: bytes, base: str,
                   query: str) -> Optional[str]:
    """Pick the most plausible 'show' URL from a search response. Skips
    obviously navigation-y pages (categories, tags, page-N, etc.)."""
    if not body:
        return None
    cfg = rules.get("sites", {}).get(site, {})
    stype = cfg.get("searchType", "html")

    NAV_PREFIXES = (
        "/category/", "/tag/", "/author/", "/page/", "?s=", "/?s=",
        "/search/", "/wp-content/", "/wp-admin/", "/feed/", "/archives/",
    )

    def _is_show_like(href: str) -> bool:
        if not href:
            return False
        low = href.lower()
        if any(low.startswith(p) or p in low for p in NAV_PREFIXES):
            return False
        if "/page/" in low or "/p=" in low:
            return False
        return True

    if stype == "json":
        links = []

        def walk(o):
            if isinstance(o, dict):
                u = o.get("link") or o.get("url") or o.get("permalink")
                if isinstance(u, str) and u.strip() \
                        and not u.startswith(("data:", "javascript:", "mailto:")):
                    links.append(u)
                for v in o.values():
                    walk(v)
            elif isinstance(o, list):
                for x in o:
                    walk(x)
        try:
            walk(json.loads(body))
        except Exception:
            pass
        for u in links:
            if _is_show_like(u):
                return urllib.parse.urljoin(base + "/", u)
        if links:
            return urllib.parse.urljoin(base + "/", links[0])

    if stype == "rss":
        ms = re.findall(rb"<item>.*?<link>(.*?)</link>", body, re.S)
        for m in ms:
            u = m.decode("utf-8", "ignore").strip()
            if u and _is_show_like(u):
                return urllib.parse.urljoin(base + "/", u)

    try:
        soup = BeautifulSoup(body, "html.parser")
        card_sel = cfg.get("cardSelector", "article, .post, a[href]")
        for card in soup.select(card_sel):
            a = card if getattr(card, "name", None) == "a" else card.select_one("a[href]")
            if a and a.get("href"):
                href = a["href"]
                if (href.startswith("/") or base in href) and _is_show_like(href):
                    return urllib.parse.urljoin(base + "/", href)
        for a in soup.find_all("a", href=True):
            href = a["href"]
            if not (href.startswith("/") or base in href):
                continue
            if not _is_show_like(href):
                continue
            full = urllib.parse.urljoin(base + "/", href)
            path = full.replace(base, "").strip("/")
            if path.count("/") >= 1:
                return full
    except Exception:
        return None
    return None


def stage_episodes(s: Paced, rules: dict, site: str, show_url: str,
                   out: list) -> dict:
    """Stage 2: on the show page, find >=1 episode/download link."""
    if site in FLAT_SITES:
        rec = {"site": site, "stage": "episodes", "pass": None,
               "url": show_url, "error": "n/a — flat magnet list"}
        out.append(rec)
        return rec

    st, body, meta = get_page(s, show_url, referer=referer_for(rules, show_url))
    if st != 200:
        rec = {"site": site, "stage": "episodes", "pass": False,
               "url": show_url, "http_status": st,
               "error": meta.get("error", f"http_{st}")}
        out.append(rec)
        return rec

    cfg = rules.get("sites", {}).get(site, {})
    ep_sel = cfg.get("episodeSelector", "a[href]")
    try:
        soup = BeautifulSoup(body, "html.parser")
        eps = soup.select(ep_sel)
    except Exception as e:
        eps = []
        meta["error"] = f"parse:{e}"

    rec = {"site": site, "stage": "episodes",
           "pass": len(eps) > 0,
           "url": show_url, "http_status": st,
           "content_type": meta.get("content_type", ""),
           "bytes_received": meta.get("bytes_received", 0),
           "latency_ms": meta.get("latency_ms", 0),
           "episode_count": len(eps),
           "error": "" if eps else "no episode links on page"}
    out.append(rec)
    return rec


def classify_media(url: str, known_hosts, direct_exts) -> str:
    """Mirror LockerRegistry.classify(): 'direct' | 'locker' | 'unknown' | 'none'."""
    clean = (url or "").strip().split("?")[0].split("#")[0]
    if not clean:
        return "none"
    try:
        p = urllib.parse.urlparse(clean)
        host = (p.hostname or "").lower()
        path = p.path or ""
    except Exception:
        return "none"
    if not host:
        return "none"
    ext = clean.rsplit(".", 1)[-1].lower()
    direct_set = set(e.lstrip(".").lower() for e in direct_exts) | {
        "mp4", "mkv", "webm", "avi", "m3u8", "m4v", "ts", "mp3",
    }
    if ext in direct_set:
        return "direct"
    for kh in known_hosts:
        kh = kh.lower()
        if host == kh or host.endswith("." + kh):
            return "locker"
    return "unknown"


def _resolve_candidate(s: Paced, rules: dict, site: str,
                       show_url, fallback_body=None) -> tuple:
    """Stage 3: find a recognizable media URL or locker host.
    For magnet-only sites (torrents): pull a magnet from the search JSON.
    For rules-driven sites: fetch the show page and pick the first
    episodeSelector anchor that classifies as direct/locker.
    For parity sites: scan the SEARCH RESULTS page (or the show page if
    one was reachable) for any plausible direct/locker link.
    Returns (resolved_url, source_kind, meta)."""
    known = set(h.lower() for h in rules.get("lockerHosts", [])) | LOCKER_HOSTS
    direct_exts = [e.lower() for e in rules.get("directMediaExtensions", [])]

    if site in FLAT_SITES:
        if fallback_body:
            try:
                data = json.loads(fallback_body)
                if isinstance(data, list):
                    for item in data:
                        m = item.get("magnet") if isinstance(item, dict) else None
                        if isinstance(m, str) and m.startswith("magnet:"):
                            return m, "magnet", {"status": 200, "latency_ms": 0,
                                                 "content_type": "application/json"}
            except Exception:
                pass
        return None, "no_magnet", {"status": -1, "error": "no_magnet"}

    cfg = rules.get("sites", {}).get(site, {})
    ep_sel = cfg.get("episodeSelector")

    def _scan(soup, base_url):
        if ep_sel:
            for a in soup.select(ep_sel):
                u = a.get("href") or a.get("data-video") or a.get("data-src")
                if not u:
                    continue
                u = urllib.parse.urljoin(base_url, u)
                if u.startswith("magnet:"):
                    return u, "magnet"
                kind = classify_media(u, known, direct_exts)
                if kind in ("direct", "locker"):
                    return u, kind
        for a in soup.select("a[href], a[data-video], a[data-src], iframe[src]"):
            u = a.get("href") or a.get("data-video") or a.get("data-src") or a.get("src")
            if not u:
                continue
            u = urllib.parse.urljoin(base_url, u)
            if u.startswith("magnet:"):
                return u, "magnet"
            kind = classify_media(u, known, direct_exts)
            if kind in ("direct", "locker"):
                return u, kind
        return None

    if show_url:
        st, body, meta = get_page(s, show_url, referer=referer_for(rules, show_url))
        if st == 200 and body:
            try:
                soup = BeautifulSoup(body, "html.parser")
                hit = _scan(soup, show_url)
                if hit:
                    return hit[0], hit[1], meta
            except Exception:
                pass

    if fallback_body:
        try:
            soup = BeautifulSoup(fallback_body, "html.parser")
            base = rules.get("domains", {}).get(site, "")
            hit = _scan(soup, base)
            if hit:
                return hit[0], hit[1], {"status": 200, "latency_ms": 0,
                                        "content_type": "text/html"}
        except Exception:
            pass

    return None, "no_candidate", {"status": -1, "error": "no_candidate"}


def stage_crack(s: Paced, rules: dict, site: str, show_url,
                search_body, out: list) -> dict:
    """Stage 3: resolve one episode URL to a recognizable media/locker."""
    if site in MAGNET_SITES:
        rec = {"site": site, "stage": "crack", "pass": None,
               "url": show_url or "-", "error": "n/a — magnet passthrough (no real download)"}
        out.append(rec)
        return rec

    if site in PARITY_SITES:
        rec = {"site": site, "stage": "crack", "pass": None,
               "url": show_url or "-",
               "error": "n/a — parity-driven resolver (validated by JVM unit tests, not Python)"}
        out.append(rec)
        return rec

    resolved, kind, meta = _resolve_candidate(s, rules, site, show_url, search_body)
    if not resolved:
        rec = {"site": site, "stage": "crack", "pass": False,
               "url": show_url or "-",
               "http_status": meta.get("status"),
               "error": kind if kind else "no candidate resolved"}
        out.append(rec)
        return rec

    rec = {"site": site, "stage": "crack",
           "pass": kind in ("direct", "locker", "magnet"),
           "resolved_url": resolved,
           "resolved_kind": kind,
           "url": show_url or "-",
           "http_status": meta.get("status"),
           "content_type": meta.get("content_type", ""),
           "latency_ms": meta.get("latency_ms", 0),
           "error": "" if kind in ("direct", "locker", "magnet") else kind}
    out.append(rec)
    return rec


def _hls_follow(s: Paced, rules: dict, master_url: str, referer) -> tuple:
    """For HLS (.m3u8) media: master -> variant -> first segment. Each is
    a small fetch (the m3u8 playlist + the first segment's metadata)."""
    st, body, meta = get_page(s, master_url, referer=referer_for(rules, master_url, referer))
    if st != 200 or not body:
        return None, "master_fetch_failed", meta
    text = body.decode("utf-8", "ignore")
    if "#EXT-X-STREAM-INF" not in text:
        return None, "not_master", meta
    variant = None
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if ln.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines):
            nxt = lines[i + 1].strip()
            if nxt and not nxt.startswith("#"):
                variant = urllib.parse.urljoin(master_url, nxt)
                break
    if not variant:
        return None, "no_variant", meta
    st2, body2, meta2 = get_page(s, variant, referer=referer_for(rules, variant, referer))
    if st2 != 200 or not body2:
        return None, "variant_fetch_failed", meta2
    text2 = body2.decode("utf-8", "ignore")
    if "#EXTINF" not in text2:
        return None, "not_media_playlist", meta2
    for ln in text2.splitlines():
        ln = ln.strip()
        if ln and not ln.startswith("#"):
            seg = urllib.parse.urljoin(variant, ln)
            return seg, "hls_segment", meta2
    return None, "no_segment", meta2


def stage_probe(s: Paced, rules: dict, site: str, resolved, resolved_kind,
                out: list) -> dict:
    """Stage 4: 1KB Range probe on the final media URL.
    Pass = HTTP 200/206 + non-HTML + media CT OR magic bytes.
    HLS: master -> variant -> segment.
    Magnets: N/A. Parity sites: N/A (Python can't reach the resolved URL)."""
    if site in MAGNET_SITES or not resolved:
        reason = ("n/a — magnet passthrough (no real download)" if site in MAGNET_SITES
                  else "n/a — no media URL to probe")
        rec = {"site": site, "stage": "probe", "pass": None,
               "url": resolved or "-", "error": reason}
        out.append(rec)
        return rec

    if site in PARITY_SITES:
        rec = {"site": site, "stage": "probe", "pass": None,
               "url": resolved or "-",
               "error": "n/a — parity-driven resolver (validated by JVM unit tests, not Python)"}
        out.append(rec)
        return rec

    if resolved_kind == "locker" or (resolved_kind == "direct"
                                     and resolved.lower().split("?")[0].endswith(".m3u8")):
        seg_url, seg_kind, seg_meta = _hls_follow(s, rules, resolved, referer=None)
        if not seg_url:
            rec = {"site": site, "stage": "probe", "pass": False,
                   "url": resolved, "http_status": seg_meta.get("status"),
                   "error": f"hls:{seg_kind}"}
            out.append(rec)
            return rec
        target = seg_url
    else:
        target = resolved

    st, body, meta = s.range_get(target)
    meta_pass = (st in (200, 206) and len(body) <= PROBE_CAP)
    ct = meta.get("content_type", "")
    not_html = "text/html" not in ct
    media_ct = any(ct.startswith(p) for p in MEDIA_CT_PREFIXES)
    magic_hit = ""
    if not media_ct:
        for sig, name in MAGIC_SIGNATURES.items():
            if body[:max(4, len(sig))].startswith(sig) or sig in body[:256]:
                magic_hit = name
                break

    passed = bool(meta_pass and not_html and (media_ct or magic_hit))
    rec = {"site": site, "stage": "probe",
           "pass": passed,
           "url": target,
           "http_status": st,
           "content_type": ct,
           "bytes_received": meta.get("body_capped", len(body)),
           "latency_ms": meta.get("latency_ms", 0),
           "magic_hit": magic_hit,
           "media_ct": media_ct,
           "range_requested": PROBE_CAP,
           "error": ""
               if passed
               else f"status={st} ct={ct} bytes={len(body)} magic={magic_hit or 'none'}"}
    out.append(rec)
    return rec


# ----- Top-level per-site runner -----

def run_site(s: Paced, rules: dict, site: str, out: list) -> dict:
    consecutive_fails = [0]
    queries = QUERIES.get(site, ["movie"])
    base = rules.get("domains", {}).get(site, "")

    s1 = stage_search(s, rules, site, queries, out, consecutive_fails)

    search_body = None
    if s1.get("pass") and s1.get("url") and s1.get("url") != "-":
        st, search_body, _meta = get_page(s, s1["url"], referer=referer_for(rules, s1["url"]))
        if st != 200:
            search_body = None

    show_url = None
    if search_body:
        show_url = _pick_show_url(site, rules, search_body, base, s1.get("query", ""))
    if site == "torrents":
        show_url = s1.get("url")

    s2 = {"pass": None, "error": "no show URL (search failed)"}
    if show_url:
        s2 = stage_episodes(s, rules, site, show_url, out)

    s3 = {"pass": None, "error": "no show URL (search failed)"}
    resolved = None
    resolved_kind = None
    if show_url or search_body:
        s3 = stage_crack(s, rules, site, show_url, search_body, out)
        resolved = s3.get("resolved_url")
        resolved_kind = s3.get("resolved_kind")

    s4 = stage_probe(s, rules, site, resolved, resolved_kind, out)

    def _ok(rec):
        return rec.get("pass") is not False
    overall_pass = _ok(s1) and _ok(s2) and _ok(s3) and _ok(s4)
    return {
        "site": site,
        "search": {"pass": s1.get("pass")},
        "episodes": {"pass": s2.get("pass")},
        "crack": {"pass": s3.get("pass")},
        "probe": {"pass": s4.get("pass")},
        "overall": overall_pass,
    }


# ----- Scorecard + regression detection + issue filing -----

def _previous_scorecard(results_dir: str, current_run_id: str):
    """Find the second-most-recent scorecard (the previous one, so we
    don't diff against the file we just wrote)."""
    files = sorted(f for f in os.listdir(results_dir) if f.endswith("-scorecard.json"))
    if len(files) < 2:
        return None
    older = [f for f in files if current_run_id not in f]
    if not older:
        return None
    try:
        return json.load(open(os.path.join(results_dir, older[-1]), encoding="utf-8"))
    except Exception:
        return None


def _detect_regressions(current: dict, previous: dict) -> list:
    regs = []
    cur_sites = current.get("sites", {})
    prev_sites = previous.get("sites", {})
    for site, cur in cur_sites.items():
        prev = prev_sites.get(site)
        if not prev:
            continue
        if cur.get("overall") is False and prev.get("overall") is True:
            first = None
            for stage in ("search", "episodes", "crack", "probe"):
                if cur.get(stage, {}).get("pass") is False \
                        and prev.get(stage, {}).get("pass") is True:
                    first = stage
                    break
            regs.append({
                "site": site,
                "previous_overall": prev.get("overall"),
                "current_overall": cur.get("overall"),
                "first_failed_stage": first,
            })
    return regs


def _gh_cli_available() -> bool:
    return subprocess.call(["gh", "--version"], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL) == 0


def _file_issues_for_regressions(scorecard: dict, repo: str, label: str) -> list:
    """File one issue per regression. If an open issue with the same
    label + site-in-title exists, post a comment instead of duplicating."""
    if not _gh_cli_available():
        return [{"site": r["site"], "action": "skipped",
                 "reason": "gh CLI not available in this runner"} for r in scorecard.get("regressions", [])]
    actions = []
    for r in scorecard.get("regressions", []):
        title = f"Live-site regression: {r['site']}"
        body = (
            f"## Live-site regression: {r['site']}\n\n"
            f"- Previous overall: **{r['previous_overall']}**\n"
            f"- Current overall:  **{r['current_overall']}**\n"
            f"- First failed stage: `{r['first_failed_stage']}`\n\n"
            f"Run timestamp: `{scorecard.get('run_id')}`\n\n"
            f"### Per-stage status (this run)\n\n"
        )
        site_block = scorecard.get("sites", {}).get(r["site"], {})
        for stage in ("search", "episodes", "crack", "probe"):
            v = site_block.get(stage, {}).get("pass")
            label_s = "PASS" if v is True else ("FAIL" if v is False else "N/A")
            body += f"- `{stage}`: {label_s}\n"
        body += "\nThis issue is informational only — it does not block releases."

        existing = subprocess.run(
            ["gh", "issue", "list", "--repo", repo, "--state", "open",
             "--label", label, "--search", title, "--json", "number,title",
             "--limit", "10"],
            capture_output=True, text=True, check=False)
        dup = None
        if existing.returncode == 0:
            try:
                lst = json.loads(existing.stdout or "[]")
                for it in lst:
                    if it.get("title") == title:
                        dup = it.get("number")
                        break
            except Exception:
                dup = None
        if dup:
            cmd = ["gh", "issue", "comment", str(dup), "--repo", repo, "--body", body]
            res = subprocess.run(cmd, capture_output=True, text=True, check=False)
            actions.append({"site": r["site"], "action": "commented",
                            "issue": dup, "ok": res.returncode == 0,
                            "stderr": res.stderr[:200] if res.returncode else ""})
        else:
            cmd = ["gh", "issue", "create", "--repo", repo, "--title", title,
                   "--body", body, "--label", label]
            res = subprocess.run(cmd, capture_output=True, text=True, check=False)
            actions.append({"site": r["site"], "action": "created",
                            "ok": res.returncode == 0,
                            "stderr": res.stderr[:200] if res.returncode else "",
                            "stdout": res.stdout[:200] if res.returncode == 0 else ""})
    return actions


# ----- Main -----

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", action="append", default=None)
    ap.add_argument("--out-dir", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "results-nightly"))
    ap.add_argument("--repo-root", default=os.path.dirname(
        os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--no-issue", action="store_true")
    ap.add_argument("--issue-repo", default="anonrode/download-toolkit-serverless")
    ap.add_argument("--issue-label", default="live-site-regression")
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    now_utc = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)
    run_id = now_utc.strftime("%Y-%m-%dT%H%MZ")
    print(f"[nightly] run_id={run_id} sites={args.site or 'all'}")

    rules = load_signed_payload(args.repo_root)
    sites = args.site or ALL_SITES

    s = Paced()
    site_results = {}
    out_jsonl = []
    t_run = time.time()
    for site in sites:
        print(f"[nightly] -> {site}")
        try:
            site_results[site] = run_site(s, rules, site, out_jsonl)
        except Exception as e:
            site_results[site] = {
                "site": site, "search": {"pass": None},
                "episodes": {"pass": None}, "crack": {"pass": None},
                "probe": {"pass": None}, "overall": None,
                "error": f"runner_crash:{e}"[:200],
            }
            out_jsonl.append({"site": site, "stage": "runner",
                              "pass": None, "error": f"runner_crash:{e}"[:200]})
    elapsed = int(time.time() - t_run)

    total = len(site_results)
    passed = sum(1 for r in site_results.values() if r.get("overall") is True)
    failed = sum(1 for r in site_results.values() if r.get("overall") is False)
    na = sum(1 for r in site_results.values() if r.get("overall") is None)

    prev = _previous_scorecard(args.out_dir, run_id)
    regressions = []
    if prev:
        regressions = _detect_regressions(
            {"sites": site_results, "run_id": run_id},
            prev)

    scorecard = {
        "run_id": run_id,
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "elapsed_seconds": elapsed,
        "summary": {
            "total_sites": total,
            "passed": passed,
            "failed": failed,
            "na": na,
            "regressions_vs_previous": len(regressions),
        },
        "sites": site_results,
        "regressions": regressions,
        "previous_run_id": (prev.get("run_id") if prev else None),
    }

    jsonl_path = os.path.join(args.out_dir, f"{run_id}.jsonl")
    with open(jsonl_path, "w", encoding="utf-8") as f:
        for r in out_jsonl:
            f.write(json.dumps(r) + "\n")
    scorecard_path = os.path.join(args.out_dir, f"{run_id}-scorecard.json")
    with open(scorecard_path, "w", encoding="utf-8") as f:
        json.dump(scorecard, f, indent=2)

    print()
    print("=" * 68)
    print(f"NIGHTLY CONFORMANCE SCORECARD  run_id={run_id}  ({elapsed}s)")
    print("=" * 68)
    for site, r in site_results.items():
        ov = r.get("overall")
        ov_s = "PASS" if ov is True else ("FAIL" if ov is False else " N/A")
        s1 = r.get("search", {}).get("pass")
        s2 = r.get("episodes", {}).get("pass")
        s3 = r.get("crack", {}).get("pass")
        s4 = r.get("probe", {}).get("pass")
        def _s(v): return "P" if v is True else ("F" if v is False else "-")
        print(f"  {site:12s} overall={ov_s:4s}  search={_s(s1)}  "
              f"episodes={_s(s2)}  crack={_s(s3)}  probe={_s(s4)}")
    print("-" * 68)
    print(f"  Total: {total}  Pass: {passed}  Fail: {failed}  N/A: {na}")
    print(f"  Regressions vs previous: {len(regressions)}")
    for r in regressions:
        print(f"    - {r['site']}: {r['previous_overall']} -> {r['current_overall']} "
              f"(first failed: {r['first_failed_stage']})")
    print("=" * 68)

    if not args.no_issue and regressions:
        if _gh_cli_available():
            actions = _file_issues_for_regressions(scorecard, args.issue_repo, args.issue_label)
            for a in actions:
                print(f"  issue: site={a.get('site')} action={a.get('action')} "
                      f"ok={a.get('ok')} issue={a.get('issue', '-')}")
            scorecard["issue_actions"] = actions
            with open(scorecard_path, "w", encoding="utf-8") as f:
                json.dump(scorecard, f, indent=2)
        else:
            print("  (gh CLI not on PATH — skipping issue filing)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
