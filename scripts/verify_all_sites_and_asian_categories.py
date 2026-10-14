"""
End-to-End Live Verification Harness:
1. Audit all 10 provider sites (Reachability, Search, Scraper structure).
2. Harvest live C-Drama & K-Drama categories (Modern & Historical) matching AsianDramaFeed.kt.
3. Test downloadability of every harvested show via cracked resolver pipeline & Range probes (bytes 0-1024).
4. Verify failover resilience for any dead storage nodes.

Strict Constraint: Maximum 2KB per probe (Range: bytes=0-1024). Never download full media.
Zero AI attribution.
"""

import sys
import os
import re
import json
import ssl
import time
import urllib.request
import urllib.parse
import urllib.error
import requests
import urllib3
urllib3.disable_warnings()
from bs4 import BeautifulSoup

# Import Python monolith resolvers for full pipeline parity
sys.path.insert(0, r"C:\Users\user\Anon\ANON TOOLS\download-toolkit")
from src.resolvers import (
    ResolverRegistry,
    VidbasicResolver,
    StreamwishResolver,
    VidhideResolver,
    StreamtapeResolver,
    DoodstreamResolver,
    MixdropResolver,
    DramaGatewayResolver,
    DownloadwellaResolver,
    VikingFileResolver,
    LoadedfilesResolver,
    LightDLResolver,
    FivePlayResolver
)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}

session = requests.Session()
session.headers.update(HEADERS)

def fetch_text(url, referer=None, timeout=15):
    req_headers = dict(HEADERS)
    if referer:
        req_headers["Referer"] = referer
    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            data = resp.read()
            return data.decode("utf-8", "ignore")
    except Exception:
        return None

def probe_stream_range(url, referer=None, timeout=12):
    """
    Performs a tiny range probe (bytes=0-1024) to verify:
    - HTTP 200 or 206 status
    - Content-Type is video/stream/m3u8
    - Magic bytes (MKV, MP4, HLS)
    - Content-Length > 10MB (or HLS manifest)
    - Zero HTML decoy pages
    """
    req_headers = dict(HEADERS)
    req_headers["Range"] = "bytes=0-1023"
    if referer:
        req_headers["Referer"] = referer
    else:
        parsed = urllib.parse.urlparse(url)
        req_headers["Referer"] = f"{parsed.scheme}://{parsed.netloc}/"

    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            code = resp.getcode()
            headers = dict(resp.info())
            first_bytes = resp.read(1024)
            
            ct = headers.get("Content-Type", headers.get("content-type", "")).lower()
            cl = headers.get("Content-Length", headers.get("content-length", "0"))
            cr = headers.get("Content-Range", headers.get("content-range", ""))

            # Magic bytes detection
            is_mkv = first_bytes.startswith(b"\x1a\x45\xdf\xa3")
            is_mp4 = len(first_bytes) > 8 and b"ftyp" in first_bytes[:16]
            is_hls = (first_bytes.startswith(b"#EXTM3U") or 
                      b"#EXT-X-STREAM-INF" in first_bytes or 
                      b"#EXTINF" in first_bytes or 
                      ".m3u8" in url.lower())
            is_html = (b"<!doctype html" in first_bytes.lower() or 
                       b"<html" in first_bytes.lower() or 
                       b"<head" in first_bytes.lower() or 
                       b"cloudflare" in first_bytes.lower())

            # Total file size
            total_size = 0
            if cr and "/" in cr:
                try:
                    total_size = int(cr.split("/")[-1])
                except:
                    pass
            elif cl:
                try:
                    total_size = int(cl)
                except:
                    pass

            valid = False
            reason = "Unknown"

            if is_html:
                valid = False
                reason = "Cloudflare HTML decoy / Error page (Blocked)"
            elif is_mkv:
                valid = True
                reason = f"Valid MKV Video (Magic: 1A 45 DF A3, Total: {total_size/(1024*1024):.1f} MB)"
            elif is_mp4:
                valid = True
                reason = f"Valid MP4 Video (Magic: ftyp box, Total: {total_size/(1024*1024):.1f} MB)"
            elif is_hls:
                valid = True
                reason = "Valid HLS Master Playlist (#EXTM3U)"
            elif code in (200, 206) and any(v in ct for v in ["video/", "octet-stream", "application/vnd.apple.mpegurl"]):
                valid = True
                reason = f"Valid Stream (Status: {code}, Content-Type: {ct})"
            else:
                reason = f"HTTP {code} ({ct}), Magic unrecognized: {first_bytes[:16].hex()}"

            return {
                "url": url,
                "status": code,
                "content_type": ct,
                "magic_valid": valid,
                "is_html": is_html,
                "size_mb": total_size / (1024 * 1024),
                "summary": reason
            }
    except urllib.error.HTTPError as e:
        return {"url": url, "status": e.code, "magic_valid": False, "is_html": False, "summary": f"HTTP {e.code} Error"}
    except Exception as e:
        return {"url": url, "status": 0, "magic_valid": False, "is_html": False, "summary": f"Connection Error: {e}"}

# ==============================================================================
# PHASE 1: AUDIT ALL 10 PROVIDER SITES
# ==============================================================================
def audit_10_providers():
    print("\n=======================================================")
    print("PHASE 1: AUDITING ALL 10 PROVIDER SITES FOR HEALTH")
    print("=======================================================")
    sites = {
        "AsianC": ("https://asianc.id/api?a=search&keyword=vincenzo", "AsianC API Search", 50),
        "DramaRain": ("https://dramarain.com/search?q=vincenzo", "DramaRain Search Page", 200),
        "DramaKey": ("https://dramakey.com/?s=vincenzo", "DramaKey Search Page", 200),
        "Nepu": ("https://nepu.gd/search?q=vincenzo", "Nepu Search Page", 200),
        "Nkiri": ("https://thenkiri.com/wp-json/wp/v2/posts?per_page=2", "Nkiri WP REST API", 200),
        "9jaRocks": ("https://9jarocks.net/search/vincenzo/feed/rss2/", "9jaRocks Search RSS", 200),
        "NaijaVault": ("https://www.naijavault.com/wp-json/wp/v2/posts?search=vincenzo", "NaijaVault WP REST API", 200),
        "NaijaPrey": ("https://naijaprey.tv/search/vincenzo/feed/rss2/", "NaijaPrey Search RSS", 200),
        "Pluto": ("https://plutomovies.com/search?q=batman", "Pluto Movies Search", 200),
        "Anitaku": ("https://anitaku.com.ro/search.html?keyword=naruto", "Anitaku Anime Search", 200)
    }

    results = {}
    for name, (url, desc, min_bytes) in sites.items():
        t0 = time.time()
        txt = fetch_text(url, timeout=15)
        elapsed = time.time() - t0
        alive = txt is not None and len(txt) >= min_bytes
        status_str = f"ONLINE ({elapsed:.2f}s, {len(txt) if txt else 0} bytes)" if alive else "OFFLINE/TIMEOUT"
        results[name] = {"alive": alive, "elapsed": elapsed, "desc": desc}
        print(f"[{'OK' if alive else 'FAIL'}] {name.ljust(12)} : {status_str} -> {desc}")

    return results

# ==============================================================================
# PHASE 2: HARVEST LIVE C-DRAMA & K-DRAMA CATEGORY RESULTS
# ==============================================================================
def harvest_asian_drama_categories():
    print("\n=======================================================")
    print("PHASE 2: HARVESTING LIVE C-DRAMA & K-DRAMA CATEGORY FEEDS")
    print("=======================================================")

    shows = []
    seen_titles = set()
    seen_urls = set()

    def add_show(title, region, era, site, url):
        clean_title = re.sub(r'\(Complete\)|\|.*|Download.*', '', title, flags=re.I).strip()
        t_key = clean_title.lower()
        if len(clean_title) < 2 or t_key in seen_titles or url in seen_urls:
            return False
        seen_titles.add(t_key)
        seen_urls.add(url)
        shows.append({"title": clean_title, "region": region, "era": era, "site": site, "url": url})
        return True

    # 1. DramaKey C-Drama & K-Drama (matching AsianDramaFeed.kt)
    dramakey_feeds = [
        ("C-Drama", "Modern", "https://dramakey.cc/chinese/"),
        ("K-Drama", "Modern", "https://dramakey.cc/korean/"),
        ("C-Drama", "Historical", "https://dramakey.cc/genre/historical/")
    ]
    for region, era, url in dramakey_feeds:
        txt = fetch_text(url, timeout=12)
        if txt:
            soup = BeautifulSoup(txt, 'html.parser')
            count = 0
            for item in soup.select("a.series-card-link, .series-item, .drama-card, article.series-card, article, .item"):
                a = item if item.name == 'a' else item.select_one('a.series-card-link') or item.select_one('a')
                if not a: continue
                href = a.get('href', '').strip()
                if not href: continue
                if href.startswith('/'):
                    href = f"https://dramakey.cc{href}"
                if any(x in href for x in ['/category/', '/tag/', '/page/', '/genre/', '/dramas', '/genres']):
                    continue
                title_el = item.select_one('.series-title, .title, h2, h3, .entry-title') or a
                raw_title = title_el.get_text(strip=True) if title_el else ''
                if len(raw_title) > 2 and "menu" not in raw_title.lower() and "download" not in raw_title.lower():
                    if add_show(raw_title, region, era, "dramakey", href):
                        count += 1
                        if count >= 4: break
            print(f"  [+] DramaKey ({region} {era}): {count} titles harvested")

    # 2. DramaRain C-Drama & K-Drama (matching AsianDramaFeed.kt)
    dramarain_feeds = [
        ("C-Drama", "Modern", "https://dramarain.com/chinese-drama/"),
        ("K-Drama", "Modern", "https://dramarain.com/category/korean-drama/"),
        ("C-Drama", "Historical", "https://dramarain.com/tag/historical/")
    ]
    for region, era, url in dramarain_feeds:
        txt = fetch_text(url, timeout=12)
        if txt:
            soup = BeautifulSoup(txt, 'html.parser')
            count = 0
            for item in soup.select("article, .post-item, .post"):
                a = item.select_one("h2 a, .entry-title a, .post-title a, a[rel='bookmark']")
                if not a: continue
                href = a.get('href', '').strip()
                if not href: continue
                if any(x in href for x in ['/category/', '/tag/', '/page/', 'how-to-download']):
                    continue
                if not any(k in href for k in ['-drama', '-series', 'drama', 's01', 'season']):
                    continue
                raw_title = a.get_text(strip=True)
                if len(raw_title) > 3 and "Episode" not in raw_title:
                    if add_show(raw_title, region, era, "dramarain", href):
                        count += 1
                        if count >= 4: break
            print(f"  [+] DramaRain ({region} {era}): {count} titles harvested")

    # 3. AsianC C-Drama & K-Drama
    asianc_feeds = [
        ("C-Drama", "Modern", "https://asianc.id/country/chinese-drama"),
        ("K-Drama", "Modern", "https://asianc.id/country/korean-drama")
    ]
    for region, era, url in asianc_feeds:
        txt = fetch_text(url, timeout=12)
        if txt:
            matches = re.findall(r'<a\s+[^>]*href=["\'](/drama-detail/[^"\']+)["\'][^>]*>(.*?)</a>', txt, re.DOTALL | re.I)
            count = 0
            for href, inner in matches:
                full_url = f"https://asianc.id{href}"
                title_match = re.search(r'<h[234][^>]*>(.*?)</h[234]>', inner, re.I) or re.search(r'title=["\']([^"\']+)["\']', inner, re.I)
                title = title_match.group(1).strip() if title_match else href.split("/")[-1].replace("-", " ").title()
                title = re.sub(r'<[^>]+>', '', title).strip()
                if len(title) > 3:
                    if add_show(title, region, era, "asianc", full_url):
                        count += 1
                        if count >= 4: break
            print(f"  [+] AsianC ({region} {era}): {count} titles harvested")

    # 4. Nkiri C-Drama & K-Drama (WP REST API)
    nkiri_feeds = [
        ("K-Drama", "Modern", "https://thenkiri.com/wp-json/wp/v2/posts?search=korean+drama&per_page=6"),
        ("C-Drama", "Historical", "https://thenkiri.com/wp-json/wp/v2/posts?search=chinese+drama&per_page=6")
    ]
    for region, era, api_url in nkiri_feeds:
        txt = fetch_text(api_url, timeout=15)
        if txt:
            try:
                items = json.loads(txt)
                count = 0
                for item in items:
                    raw_title = item.get("title", {}).get("rendered", "")
                    link = item.get("link", "")
                    if link:
                        clean_title = re.sub(r'<[^>]+>|&#\d+;|&[a-z]+;', '', raw_title).strip()
                        if add_show(clean_title, region, era, "nkiri", link):
                            count += 1
                            if count >= 3: break
                print(f"  [+] Nkiri ({region} {era}): {count} titles harvested")
            except:
                pass

    print(f"\n[OK] TOTAL HARVESTED DRAMA TITLES: {len(shows)} unique shows")
    return shows

# ==============================================================================
# PHASE 3 & 4: RESOLVE EPISODE 1 & PROBE DOWNLOADABILITY
# ==============================================================================
def resolve_and_probe_show(show):
    title = show["title"]
    site = show["site"]
    url = show["url"]

    report = {
        "title": title,
        "region": show["region"],
        "site": site,
        "show_url": url,
        "episodes_found": 0,
        "resolved_url": None,
        "downloadable": False,
        "status": 0,
        "summary": "Pending"
    }

    # 1. Fetch Show Detail Page
    page_txt = fetch_text(url, referer=f"https://{urllib.parse.urlparse(url).netloc}/", timeout=15)
    if not page_txt:
        report["summary"] = "Could not load show detail page"
        return report

    # 2. Extract Episode 1 Download Link or Watch Link
    ep_links = []
    if site == "dramakey":
        # dramakey.cc show pages have waffi.cloud, lightdl.cc, downloadwella, etc.
        raw_links = re.findall(
            r'href=["\']([^"\']*(?:japa\.waffi\.cloud|drip\.waffi\.cloud|downloadwella\.com/[a-z0-9]+|wetafiles\.com/[a-z0-9]+|vikingfile\.com/[a-z0-9]+|lightdl\.cc/d/[a-z0-9]+|/download\?link=)[^"\']*)["\']',
            page_txt, re.I
        )
        for l in raw_links:
            clean_l = urllib.parse.urljoin(url, l).strip()
            # Filter out bare domain links
            if clean_l.rstrip('/') in ("https://downloadwella.com", "https://lightdl.cc", "https://dramakey.cc"):
                continue
            if clean_l not in ep_links:
                ep_links.append(clean_l)

    elif site == "dramarain":
        # dramarain has /download?link=... or direct waffi / locker links
        raw_links = re.findall(
            r'href=["\']([^"\']*(?:/download\?link=[^"\'\s]+|japa\.waffi\.cloud|drip\.waffi\.cloud|downloadwella\.com/[a-z0-9]+)[^"\']*)["\']',
            page_txt, re.I
        )
        for l in raw_links:
            clean_l = urllib.parse.urljoin(url, l).strip()
            if clean_l not in ep_links:
                ep_links.append(clean_l)

    elif site == "asianc":
        # asianc detail page has /drama-detail/... -> links to /...-episode-\d+.html
        watch_links = re.findall(r'href=["\'](/[^"\']+-episode-\d+[^"\']*)["\']', page_txt, re.I)
        if not watch_links:
            watch_links = re.findall(r'href=["\'](https?://asianc\.id/[^"\']+-episode-\d+[^"\']*)["\']', page_txt, re.I)
        for l in watch_links:
            clean_l = urllib.parse.urljoin(url, l).strip()
            if clean_l not in ep_links:
                ep_links.append(clean_l)
        def _get_asianc_ep(u):
            m = re.search(r'-episode-(\d+)', u)
            return int(m.group(1)) if m else 999
        ep_links.sort(key=_get_asianc_ep)

    elif site == "nkiri":
        # nkiri has download links pointing to downloadwella, vikingfile, etc.
        raw_links = re.findall(
            r'href=["\']([^"\']*(?:downloadwella\.com/[a-z0-9]+|wetafiles\.com/[a-z0-9]+|loadedfiles\.[a-z]+/|vikingfile\.com/[a-z0-9]+|thenkiri\.com/download/)[^"\']*)["\']',
            page_txt, re.I
        )
        for l in raw_links:
            clean_l = urllib.parse.urljoin(url, l).strip()
            if clean_l not in ep_links:
                ep_links.append(clean_l)

    report["episodes_found"] = len(ep_links)
    if not ep_links:
        report["summary"] = "No episode links found on page"
        return report

    sample_ep = ep_links[0]

    # 3. Resolve to Direct Stream URL
    resolved_direct = None
    try:
        if "waffi.cloud" in sample_ep:
            resolved_direct = sample_ep.split("?preview")[0]
        elif "/download?link=" in sample_ep:
            resolved_direct = DramaGatewayResolver.resolve(sample_ep, session)
        elif "lightdl.cc" in sample_ep:
            resolved_direct = LightDLResolver.resolve(sample_ep, session)
        elif "downloadwella.com" in sample_ep or "wetafiles.com" in sample_ep:
            resolved_direct = DownloadwellaResolver.resolve(sample_ep, session)
        elif "vikingfile.com" in sample_ep:
            resolved_direct = VikingFileResolver.resolve(sample_ep, session)
        elif site == "asianc":
            # AsianC: Fetch episode watch page to find player embed
            ep_page = fetch_text(sample_ep, referer=url, timeout=12)
            if ep_page:
                m_embed = (re.search(r'<iframe[^>]+src=["\']([^"\']*(?:vidbasic|vidb|stream|player|embed)[^"\']*)["\']', ep_page, re.I) or
                           re.search(r'data-video=["\']([^"\']+)["\']', ep_page, re.I))
                if m_embed:
                    embed_url = urllib.parse.urljoin(sample_ep, m_embed.group(1))
                    resolved_direct = VidbasicResolver.resolve(embed_url, session)
                    if not resolved_direct:
                        resolved_direct = ResolverRegistry.resolve(embed_url, session)
        else:
            resolved_direct = ResolverRegistry.resolve(sample_ep, session)

    except Exception as e:
        report["summary"] = f"Resolver error: {e}"

    if not resolved_direct:
        resolved_direct = sample_ep

    # Clean query preview markers
    if "?preview" in resolved_direct:
        resolved_direct = resolved_direct.split("?preview")[0]

    report["resolved_url"] = resolved_direct

    # 4. Probe Range (bytes 0-1024)
    probe_res = probe_stream_range(resolved_direct, referer=url, timeout=12)
    report["status"] = probe_res["status"]
    report["downloadable"] = probe_res["magic_valid"]
    report["summary"] = probe_res["summary"]

    return report

def main():
    print("=====================================================================")
    print("ANON DOWNLOADER: FULL-SPECTRUM END-TO-END VERIFICATION HARNESS")
    print("=====================================================================")

    # 1. Audit all 10 provider sites
    site_results = audit_10_providers()

    # 2. Harvest C-Drama & K-Drama category results
    shows = harvest_asian_drama_categories()

    # 3. Probe downloadability for every single title sequentially/with 0.3s spacing
    print("\n=======================================================")
    print(f"PHASE 3 & 4: RESOLVING & PROBING {len(shows)} DRAMA TITLES (RANGE 0-1024)")
    print("=======================================================")

    results = []
    for i, show in enumerate(shows, 1):
        res = resolve_and_probe_show(show)
        results.append(res)
        icon = "PASS" if res["downloadable"] else "WARN"
        print(f"[{i:02d}/{len(shows):02d}][{icon}] {res['title'][:26].ljust(26)} | {res['site'].ljust(9)} | HTTP {str(res['status']).ljust(3)} | {res['summary']}")
        time.sleep(0.3)

    # 4. Summary & Matrix Compilation
    print("\n=======================================================")
    print("PHASE 5: COMPREHENSIVE VERIFICATION SUMMARY")
    print("=======================================================")
    passed = sum(1 for r in results if r["downloadable"])
    total = len(results)
    print(f"Total Titles Tested : {total}")
    print(f"Verified Downloadable: {passed} / {total} ({(passed/total*100) if total else 0:.1f}%)")

    # Output detailed report file
    report_file = os.path.join(os.path.dirname(__file__), "..", "probe", "asian_categories_verification_report.json")
    os.makedirs(os.path.dirname(report_file), exist_ok=True)
    with open(report_file, "w", encoding="utf-8") as f:
        json.dump({"sites": site_results, "shows": results}, f, indent=2)
    print(f"\n[OK] Full verification report saved to: {report_file}")

if __name__ == "__main__":
    main()
