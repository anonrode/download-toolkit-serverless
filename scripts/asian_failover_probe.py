"""
Asian-drama failover coverage probe.

Question: when the dedicated Asian drama sites (asianc, dramarain, dramakey)
all fail to serve a title, do nkiri and/or 9jarocks carry it? And among the
dedicated three, which one actually has the deepest catalog?

Method: query the search endpoints of all five sites for a sample of Asian
drama titles. Search-only: no episode pages loaded, no media downloaded.
Data budget: ~5 requests x ~3-15KB per title = well under 5 MB total.

Usage:
    python scripts/asian_failover_probe.py            # run the probe
    python scripts/asian_failover_probe.py --report   # re-summarize cached results

Output: probe/asian_failover_probe_results.jsonl (one line per title x site,
resumable) plus a printed summary table.
"""
import json
import random
import re
import ssl
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "probe" / "asian_failover_probe_results.jsonl"
TITLES_OUT = ROOT / "probe" / "asian_failover_card_titles.jsonl"
TAG_KEYWORDS = ("korean", "k-drama", "kdrama", "k drama", "china", "chinese",
                "c-drama", "cdrama", "japan", "thai", "taiwan", "lakorn",
                "drama", "kdrama", "complete", "episode")

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# Canonical bases (kept in sync with DynamicRulesManager / OTA playbook).
SITES = {
    "asianc": "https://asianc.id",
    "dramarain": "https://dramarain.com",
    "dramakey": "https://dramakey.com",
    "nkiri": "https://thenkiri.com",
    "9jarocks": "https://9jarocks.net",
}


# Asian drama titles across eras & countries (K/C/J/TH/TW). Mix of mainstream
# and mid-tier, deliberately including long-running dailies and multi-season
# shows to stress catalog depth, not just the front page.
TITLES = [
    # Korean — mainstream modern
    "vincenzo", "crash landing on you", "itaewon class", "hometown cha cha cha",
    "business proposal", "twenty five twenty one", "my liberation notes",
    "extraordinary attorney woo", "the glory", "moving", "queen of tears",
    "lovely runner", "my demon", "marry my husband", "doctor slump",
    # Korean — older / classic
    "boys over flowers", "my love from the star", "descendants of the sun",
    "goblin", "reply 1988", "signal", "mr sunshine", "hospital playlist",
    "kingdom", "sweet home", "all of us are dead", "squid game",
    "penthouse war in life", "sky castle", "the world of the married",
    # Korean — dailies / long-runners
    "running man", "home for summer", "the brave yong su jeong",
    "snow white's revenge", "cinderella game",
    # Chinese
    "the untamed", "hidden love", "go ahead", "nirvana in fire",
    "eternal love", "love o2o", "put your head on my shoulder",
    "falling into your smile", "amidst a snowstorm of love",
    "the story of pearl girl", "love between fairy and devil",
    "till the end of the moon", "blossoms in adversity",
    # Japanese
    "alice in borderland", "first love", "silent tokyo", "japan sinks",
    # Thai / Taiwanese
    "2gether the series", "f4 thailand", "someday or one day", "meteor garden",
]

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def fetch(url: str, referer: str = ""):
    """GET text. Returns (status, body, error). Data-capped at 256KB."""
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/json,application/rss+xml;q=0.9,*/*;q=0.8",
            **({"Referer": referer} if referer else {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=12, context=CTX) as res:
            body = res.read(262_144).decode("utf-8", errors="replace")
            return res.status, body, ""
    except urllib.error.HTTPError as e:
        return e.code, "", f"HTTP {e.code}"
    except Exception as e:
        return 0, "", str(e)[:120]


def search(site: str, query: str) -> dict:
    """One search request per site; returns {found, titles, detail}."""
    base = SITES[site]
    q = urllib.parse.quote_plus(query)
    if site == "asianc":
        # Dramacool-family JSON search.
        status, body, err = fetch(f"{base}/api?a=search&keyword={q}", referer=f"{base}/")
        if not body:
            return {"found": False, "titles": 0, "detail": err or f"HTTP {status}"}
        try:
            arr = json.loads(body)
            names = [it.get("name") or it.get("value") or "" for it in arr]
            names = [n for n in names if n]
            hit = any(query.lower() in n.lower() for n in names)
            return {"found": hit, "titles": len(names), "detail": f"{len(names)} results"}
        except Exception as e:
            return {"found": False, "titles": 0, "detail": f"parse: {e}"}
    if site == "9jarocks":
        # WP search RSS (search/QUERY/feed/rss2).
        status, body, err = fetch(f"{base}/search/{q}/feed/rss2/", referer=f"{base}/")
        titles = re.findall(r"<title>(.*?)</title>", body, re.S)[1:]  # [0] = feed title
        titles = [re.sub(r"<!\[CDATA\[|\]\]>", "", t).strip() for t in titles]
        hit = any(query.lower() in t.lower() for t in titles)
        return {"found": hit, "titles": len(titles), "detail": err or f"{len(titles)} items"}
    # nkiri / dramarain / dramakey: WordPress ?s= HTML search.
    status, body, err = fetch(f"{base}/?s={q}", referer=f"{base}/")
    if not body:
        return {"found": False, "titles": 0, "detail": err or f"HTTP {status}"}
    cards = re.findall(r'entry-title[^>]*>.*?<a[^>]*>(.*?)</a>', body, re.S | re.I)
    cards += re.findall(r'<a[^>]*class="[^"]*entry-title[^"]*"[^>]*>(.*?)</a>', body, re.S | re.I)
    cards = [re.sub(r"<[^>]+>", "", c).strip() for c in cards]
    hit = any(query.lower() in c.lower() for c in cards)
    return {"found": hit, "titles": len(cards), "detail": err or f"{len(cards)} cards"}


def load_done() -> set:
    done = set()
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
                done.add(f"{r['title']}|{r['site']}")
            except Exception:
                pass
    return done


def titles_for(site: str, query: str) -> list:
    """Re-run one search and return the raw card-title strings (top 8).

    Search-only, same budget as the probe. Used by --titles to measure
    explicit country-tag density on REAL mixed-site cards — the number the
    Layer-3 design actually leans on.
    """
    base = SITES[site]
    q = urllib.parse.quote_plus(query)
    if site == "asianc":
        _, body, _ = fetch(f"{base}/api?a=search&keyword={q}", referer=f"{base}/")
        try:
            return [(it.get("name") or it.get("value") or "")[:120]
                    for it in json.loads(body)][:8]
        except Exception:
            return []
    if site == "9jarocks":
        _, body, _ = fetch(f"{base}/search/{q}/feed/rss2/", referer=f"{base}/")
        titles = re.findall(r"<title>(.*?)</title>", body, re.S)[1:]
        return [re.sub(r"<!\[CDATA\[|\]\]>", "", t).strip()[:120] for t in titles][:8]
    _, body, _ = fetch(f"{base}/?s={q}", referer=f"{base}/")
    cards = re.findall(r'entry-title[^>]*>.*?<a[^>]*>(.*?)</a>', body, re.S | re.I)
    cards += re.findall(r'<a[^>]*class="[^"]*entry-title[^"]*"[^>]*>(.*?)</a>', body, re.S | re.I)
    return [re.sub(r"<[^>]+>", "", c).strip()[:120] for c in cards][:8]


def collect_titles():
    """For every found row in the results file, record real card titles."""
    rows = [json.loads(l) for l in OUT.read_text(encoding="utf-8").splitlines() if l.strip()]
    targets = [(r["title"], r["site"]) for r in rows
               if r["found"] and r["site"] in ("nkiri", "9jarocks")]
    seen = set()
    if TITLES_OUT.exists():
        for line in TITLES_OUT.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
                seen.add(f"{r['title']}|{r['site']}")
            except Exception:
                pass
    print(f"{len(targets)} found rows, {len(seen)} already banked")
    with TITLES_OUT.open("a", encoding="utf-8") as fh:
        for title, site in targets:
            if f"{title}|{site}" in seen:
                continue
            cards = titles_for(site, title)
            fh.write(json.dumps({"title": title, "site": site, "cards": cards}) + "\n")
            fh.flush()
            print(f"  {site:<9s} {title:<35s} {len(cards)} cards")
            time.sleep(0.6)
    tag_report()



def tag_report():
    """Summarize explicit country-tag density on banked real card titles."""
    if not TITLES_OUT.exists():
        print("No card-title data yet — run with --titles first.")
        return
    rows = [json.loads(l) for l in TITLES_OUT.read_text(encoding="utf-8").splitlines() if l.strip()]
    for site in ("nkiri", "9jarocks"):
        cards = [c for r in rows if r["site"] == site for c in r["cards"]]
        tagged = [c for c in cards if any(k in c.lower() for k in
                  ("korean", "k-drama", "kdrama", "k drama", "china", "chinese",
                   "c-drama", "cdrama", "japan", "japanese", "thai", "taiwan", "lakorn"))]
        print(f"\n[{site}] {len(tagged)}/{len(cards)} cards carry an explicit country tag "
              f"({100 * len(tagged) / max(1, len(cards)):.0f}%)")
        print("  sample tagged:")
        for c in tagged[:6]:
            print(f"    - {c}")
        untagged = [c for c in cards if c not in tagged]
        print("  sample untagged:")
        for c in untagged[:6]:
            print(f"    - {c}")


def main():
    if "--report" in sys.argv:
        return report()
    if "--tag-report" in sys.argv:
        return tag_report()
    if "--titles" in sys.argv:
        return collect_titles()
    random.seed(20260920)
    titles = random.sample(TITLES, min(50, len(TITLES)))
    done = load_done()
    OUT.parent.mkdir(exist_ok=True)
    with OUT.open("a", encoding="utf-8") as fh:
        for i, title in enumerate(titles, 1):
            row = {"title": title}
            for site in SITES:
                key = f"{title}|{site}"
                if key in done:
                    continue
                res = search(site, title)
                rec = {"title": title, "site": site, **res}
                fh.write(json.dumps(rec) + "\n")
                fh.flush()
                row[site] = "Y" if res["found"] else "."
                time.sleep(0.6)  # be polite: ~0.6s between requests
            print(f"[{i:2d}/{len(titles)}] {title:<35s} " +
                  " ".join(f"{s}:{row.get(s, '?')}" for s in SITES))
    report()


def report():
    rows = [json.loads(l) for l in OUT.read_text(encoding="utf-8").splitlines() if l.strip()]
    titles = sorted({r["title"] for r in rows})
    print("\n=== COVERAGE (% of sampled titles with at least one search hit) ===")
    for s in SITES:
        hits = sum(1 for t in titles
                   if any(r["site"] == s and r["found"] for r in rows if r["title"] == t))
        print(f"  {s:<10s} {hits:3d}/{len(titles)}  ({100 * hits / max(1, len(titles)):.0f}%)")
    print("\n=== FAILOVER VALUE (title MISSED by all 3 dedicated sites, found by X) ===")
    missed = []
    for t in titles:
        dedicated = any(r["site"] in ("asianc", "dramarain", "dramakey") and r["found"]
                        for r in rows if r["title"] == t)
        if not dedicated:
            fb = [s for s in ("nkiri", "9jarocks")
                  if any(r["site"] == s and r["found"] for r in rows if r["title"] == t)]
            missed.append((t, fb))
    print(f"  {len(missed)}/{len(titles)} titles missed by all dedicated sites:")
    for t, fb in missed:
        print(f"    {t:<35s} fallback: {', '.join(fb) if fb else 'NONE'}")
    saved = sum(1 for _, fb in missed if fb)
    print(f"\n  => nkiri/9jarocks would RESCUE {saved}/{len(missed)} otherwise-lost titles "
          f"({100 * saved / max(1, len(titles)):.0f}% of the whole sample).")


if __name__ == "__main__":
    main()
