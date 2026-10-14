import sys
import ssl
import re
import urllib.request
import urllib.parse
import requests

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
}
session = requests.Session()
session.headers.update(HEADERS)
session.verify = False

sys.path.insert(0, r"C:\Users\user\Anon\ANON TOOLS\download-toolkit")
from src.resolvers import (
    ResolverRegistry,
    PlutoMoviesResolver,
    NaijaPreyChainResolver,
    DownloadwellaResolver
)

def probe(url, referer=None):
    req_headers = dict(HEADERS)
    req_headers["Range"] = "bytes=0-1023"
    if referer:
        req_headers["Referer"] = referer
    else:
        p = urllib.parse.urlparse(url)
        req_headers["Referer"] = f"{p.scheme}://{p.netloc}/"
    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=12, context=ctx) as resp:
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
            desc = "Unknown"
            if is_mkv: desc = f"MKV ({total_size/(1024*1024):.1f} MB)"
            elif is_mp4: desc = f"MP4 ({total_size/(1024*1024):.1f} MB)"
            elif is_hls: desc = "HLS Playlist (#EXTM3U)"
            elif is_html: desc = "HTML Decoy (FAIL)"

            return {"status": code, "valid": valid, "desc": desc, "magic": b[:12].hex()}
    except Exception as e:
        return {"status": 0, "valid": False, "desc": str(e), "magic": ""}

print("--- 1. Testing Pluto with Live Movie URL ---")
pluto_url = "https://plutomovies.com/movie/920657/the-furious-2026-malay"
try:
    direct = PlutoMoviesResolver.resolve(pluto_url, session)
    print(f"Pluto resolved: {direct}")
    if direct:
        p = probe(direct, referer="https://plutomovies.com/")
        print(f"Pluto probe: HTTP {p['status']} | {p['desc']} | Magic: {p['magic']}")
except Exception as e:
    print(f"Pluto error: {e}")

print("\n--- 2. Testing NaijaPrey with Live Link ---")
np_url = "https://vdl.np-downloader.com/sdm_downloads/download-livestream-from-hell-2026/"
try:
    direct = NaijaPreyChainResolver.resolve(np_url, session)
    print(f"NaijaPrey resolved: {direct}")
    if direct:
        p = probe(direct)
        print(f"NaijaPrey probe: HTTP {p['status']} | {p['desc']} | Magic: {p['magic']}")
except Exception as e:
    print(f"NaijaPrey error: {e}")

print("\n--- 3. Testing 9jaRocks Downloadwella Mirror ---")
# On 9jarocks post: https://9jarocks.net/videodownload/signs-of-a-psychopath-season-11-id404690.html
r = session.get("https://9jarocks.net/videodownload/signs-of-a-psychopath-season-11-id404690.html", timeout=12)
dw_links = re.findall(r'href=["\'](https?://[^"\']*(?:downloadwella|vikingfile)[^"\']*)["\']', r.text)
print(f"9jaRocks downloadwella links: {dw_links[:3]}")
if dw_links:
    direct = ResolverRegistry.resolve(dw_links[0], session)
    print(f"9jaRocks resolved: {direct}")
    if direct:
        p = probe(direct)
        print(f"9jaRocks probe: HTTP {p['status']} | {p['desc']} | Magic: {p['magic']}")
