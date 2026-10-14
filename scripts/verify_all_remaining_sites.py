"""
Live Verification Suite for All Remaining Provider Sites:
1. Pluto Movies (plutomovies.com) -> PlutoMoviesResolver
2. Nepu (nepu.gd) -> VidsrcResolver
3. 9jaRocks (9jarocks.net) -> LoadedfilesResolver / DownloadwellaResolver
4. NaijaVault (naijavault.com) -> NaijaVaultGatewayResolver -> Vikingfile/Downloadwella
5. NaijaPrey (naijaprey.tv) -> NaijaPreyChainResolver -> WildshareResolver
6. Anitaku (anitaku.com.ro) -> KisskhMegaplayResolver

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
import requests
from bs4 import BeautifulSoup

# Monolith resolver registry
sys.path.insert(0, r"C:\Users\user\Anon\ANON TOOLS\download-toolkit")
from src.resolvers import (
    ResolverRegistry,
    PlutoMoviesResolver,
    VidsrcResolver,
    LoadedfilesResolver,
    DownloadwellaResolver,
    VikingFileResolver,
    WildshareResolver,
    NaijaVaultGatewayResolver,
    NaijaPreyChainResolver,
    KisskhMegaplayResolver
)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
}

session = requests.Session()
session.headers.update(HEADERS)

def probe_stream(url, referer=None, timeout=12):
    req_headers = dict(HEADERS)
    req_headers["Range"] = "bytes=0-1023"
    if referer:
        req_headers["Referer"] = referer
    else:
        p = urllib.parse.urlparse(url)
        req_headers["Referer"] = f"{p.scheme}://{p.netloc}/"
    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            code = resp.getcode()
            headers = dict(resp.info())
            b = resp.read(1024)
            ct = headers.get("Content-Type", headers.get("content-type", "")).lower()
            cl = headers.get("Content-Length", headers.get("content-length", "0"))
            cr = headers.get("Content-Range", headers.get("content-range", ""))

            is_mkv = b.startswith(b"\x1a\x45\xdf\xa3")
            is_mp4 = len(b) > 8 and b"ftyp" in b[:16]
            is_hls = b.startswith(b"#EXTM3U") or b"#EXT-X-STREAM-INF" in b or ".m3u8" in url.lower()
            is_html = (b"<!doctype html" in b.lower() or b"<html" in b.lower() or b"<head" in b.lower())

            total_size = 0
            if cr and "/" in cr:
                try: total_size = int(cr.split("/")[-1])
                except: pass
            elif cl:
                try: total_size = int(cl)
                except: pass

            valid = (is_mkv or is_mp4 or is_hls or (code in (200, 206) and any(v in ct for v in ["video/", "octet-stream", "apple.mpegurl"]))) and not is_html
            desc = "Valid Media"
            if is_mkv: desc = f"MKV ({total_size/(1024*1024):.1f} MB)"
            elif is_mp4: desc = f"MP4 ({total_size/(1024*1024):.1f} MB)"
            elif is_hls: desc = "HLS Playlist (#EXTM3U)"
            elif is_html: desc = "HTML Decoy (FAIL)"

            return {"status": code, "valid": valid, "content_type": ct, "desc": desc}
    except Exception as e:
        return {"status": 0, "valid": False, "desc": str(e)}

def test_pluto():
    print("\n--- 1. Testing Pluto Movies (plutomovies.com) ---")
    url = "https://plutomovies.com/movie/batman-begins-2005"
    print(f"Loading {url}...")
    try:
        direct = PlutoMoviesResolver.resolve(url, session)
        print(f"Pluto resolved URL: {direct}")
        if direct:
            p = probe_stream(direct, referer="https://plutomovies.com/")
            print(f"Probe: HTTP {p['status']} | {p['desc']}")
            return p['valid']
    except Exception as e:
        print(f"Pluto error: {e}")
    return False

def test_nepu():
    print("\n--- 2. Testing Nepu (nepu.gd / vidsrc) ---")
    url = "https://nepu.gd/movie/27205"  # Inception TMDB 27205
    print(f"Loading {url}...")
    try:
        direct = VidsrcResolver.resolve(url, session)
        print(f"Nepu resolved URL: {direct}")
        if direct:
            p = probe_stream(direct, referer="https://cloudorchestranova.com/")
            print(f"Probe: HTTP {p['status']} | {p['desc']}")
            return p['valid']
    except Exception as e:
        print(f"Nepu error: {e}")
    return False

def test_9jarocks():
    print("\n--- 3. Testing 9jaRocks (9jarocks.net) ---")
    rss_url = "https://9jarocks.net/feed/"
    print(f"Fetching RSS {rss_url}...")
    try:
        r = session.get(rss_url, timeout=12)
        m = re.findall(r'<link>(https?://9jarocks\.net/[^<]+)</link>', r.text)
        post_url = next((u for u in m if '/feed' not in u and len(u) > 25), None)
        print(f"Found post: {post_url}")
        if post_url:
            pr = session.get(post_url, timeout=12)
            # Find loadedfiles or downloadwella links
            lockers = re.findall(r'href=["\'](https?://[^"\']*(?:loadedfiles|downloadwella|vikingfile)[^"\']*)["\']', pr.text)
            print(f"Found {len(lockers)} locker links. First: {lockers[0] if lockers else 'None'}")
            if lockers:
                direct = ResolverRegistry.resolve(lockers[0], session)
                print(f"9jaRocks resolved URL: {direct}")
                if direct:
                    p = probe_stream(direct)
                    print(f"Probe: HTTP {p['status']} | {p['desc']}")
                    return p['valid']
    except Exception as e:
        print(f"9jaRocks error: {e}")
    return False

def test_naijavault():
    print("\n--- 4. Testing NaijaVault (naijavault.com) ---")
    api_url = "https://www.naijavault.com/wp-json/wp/v2/posts?per_page=3"
    print(f"Fetching WP REST {api_url}...")
    try:
        r = session.get(api_url, timeout=12)
        posts = r.json()
        if posts:
            post = posts[0]
            link = post.get('link')
            print(f"Found post: {link}")
            pr = session.get(link, timeout=12)
            # Look for /dl- or locker buttons
            gw_links = re.findall(r'href=["\'](https?://[^"\']*(?:/dl-|/temp/|vikingfile|downloadwella)[^"\']*)["\']', pr.text)
            print(f"Found {len(gw_links)} gateway links. First: {gw_links[0] if gw_links else 'None'}")
            if gw_links:
                direct = NaijaVaultGatewayResolver.resolve(gw_links[0], session)
                if not direct:
                    direct = ResolverRegistry.resolve(gw_links[0], session)
                print(f"NaijaVault resolved URL: {direct}")
                if direct:
                    p = probe_stream(direct)
                    print(f"Probe: HTTP {p['status']} | {p['desc']}")
                    return p['valid']
    except Exception as e:
        print(f"NaijaVault error: {e}")
    return False

def test_naijaprey():
    print("\n--- 5. Testing NaijaPrey (naijaprey.tv) ---")
    rss_url = "https://naijaprey.tv/feed/"
    print(f"Fetching RSS {rss_url}...")
    try:
        r = session.get(rss_url, timeout=12)
        m = re.findall(r'<link>(https?://naijaprey\.tv/[^<]+)</link>', r.text)
        post_url = next((u for u in m if '/feed' not in u and len(u) > 25), None)
        print(f"Found post: {post_url}")
        if post_url:
            pr = session.get(post_url, timeout=12)
            # Find sdm_downloads or wildshare links
            dl_links = re.findall(r'href=["\'](https?://[^"\']*(?:sdm_downloads|wildshare|vdl\.np-downloader)[^"\']*)["\']', pr.text)
            print(f"Found {len(dl_links)} download links. First: {dl_links[0] if dl_links else 'None'}")
            if dl_links:
                direct = NaijaPreyChainResolver.resolve(dl_links[0], session)
                print(f"NaijaPrey resolved URL: {direct}")
                if direct:
                    p = probe_stream(direct)
                    print(f"Probe: HTTP {p['status']} | {p['desc']}")
                    return p['valid']
    except Exception as e:
        print(f"NaijaPrey error: {e}")
    return False

def test_anitaku():
    print("\n--- 6. Testing Anitaku (anitaku.com.ro) ---")
    search_url = "https://anitaku.com.ro/search.html?keyword=one+piece"
    print(f"Fetching search {search_url}...")
    try:
        r = session.get(search_url, timeout=12)
        soup = BeautifulSoup(r.text, 'html.parser')
        a = soup.select_one("ul.items li a[href*='/category/']")
        if a:
            show_url = urllib.parse.urljoin(search_url, a['href'])
            print(f"Found anime: {show_url}")
            # Anitaku watch URL is /<anime-name>-episode-1
            slug = show_url.rstrip('/').split('/')[-1]
            ep_url = f"https://anitaku.com.ro/{slug}-episode-1"
            print(f"Episode 1: {ep_url}")
            epr = session.get(ep_url, timeout=12)
            # Look for player iframe (embtaku, megaplay, gogohd)
            iframes = re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', epr.text)
            print(f"Found {len(iframes)} player iframes. First: {iframes[0] if iframes else 'None'}")
            if iframes:
                ifr_url = iframes[0]
                if ifr_url.startswith('//'): ifr_url = 'https:' + ifr_url
                direct = KisskhMegaplayResolver.resolve(ifr_url, session)
                print(f"Anitaku resolved URL: {direct}")
                if direct:
                    p = probe_stream(direct, referer="https://anitaku.com.ro/")
                    print(f"Probe: HTTP {p['status']} | {p['desc']}")
                    return p['valid']
    except Exception as e:
        print(f"Anitaku error: {e}")
    return False

def main():
    print("=====================================================================")
    print("ALL REMAINING SITES VERIFICATION: Pluto, Nepu, 9jaRocks, NaijaVault, NaijaPrey, Anitaku")
    print("=====================================================================")

    results = {}
    results["Pluto"] = test_pluto()
    results["Nepu"] = test_nepu()
    results["9jaRocks"] = test_9jarocks()
    results["NaijaVault"] = test_naijavault()
    results["NaijaPrey"] = test_naijaprey()
    results["Anitaku"] = test_anitaku()

    print("\n=======================================================")
    print("REMAINING SITES VERIFICATION SUMMARY")
    print("=======================================================")
    for site, ok in results.items():
        print(f"[{'PASS' if ok else 'FAIL'}] {site.ljust(12)} : {'100% Downloadable' if ok else 'Needs Resolver Tune'}")

if __name__ == "__main__":
    main()
