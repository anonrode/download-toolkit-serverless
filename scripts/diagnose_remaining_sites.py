import sys
import ssl
import requests
import re
from bs4 import BeautifulSoup

session = requests.Session()
session.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
})

print("=== 1. DIAGNOSING PLUTO ===")
try:
    r = session.get("https://plutomovies.com", timeout=10)
    print(f"Pluto home status: {r.status_code}, len={len(r.text)}")
    m = re.findall(r'href=["\'](/movie/[^"\']+)["\']', r.text)
    print(f"Pluto movie links found: {m[:5]}")
except Exception as e:
    print(f"Pluto error: {e}")

print("\n=== 2. DIAGNOSING 9JAROCKS & LOADEDFILES ===")
try:
    r_pt = session.get("https://loadedfiles.net/deed0bb13aeccb7a?pt=MmphYmEzeXo0TjFBb2s0SGQ4SDZERXRyWlVaNWJHRnZia1pEUlRKbkwwaG5jMmQxUTFFOVBRPT0%3D", timeout=10)
    print(f"Loadedfiles pt status: {r_pt.status_code}, len={len(r_pt.text)}")
    # Find forms, buttons, links, Alpine dlTimer, or direct download
    soup = BeautifulSoup(r_pt.text, 'html.parser')
    forms = soup.find_all('form')
    print(f"Forms count: {len(forms)}")
    for f in forms:
        print(f"Form action: {f.get('action')}, inputs: {[(i.get('name'), i.get('value')) for i in f.find_all('input')]}")
    links = [a.get('href') for a in soup.find_all('a', href=True) if any(x in a.get('href', '') for x in ['/d/', 'download', 'token', 'mkv', 'mp4'])]
    print(f"Interesting links on pt page: {links}")
    dl_timer = re.findall(r'dlTimer[^\n]+', r_pt.text)
    print(f"dlTimer matches: {dl_timer}")
except Exception as e:
    print(f"Loadedfiles error: {e}")

print("\n=== 3. DIAGNOSING NAIJAPREY RSS ===")
try:
    r = session.get("https://naijaprey.tv/feed/", timeout=10)
    print(f"NaijaPrey RSS status: {r.status_code}, len={len(r.text)}")
    links = re.findall(r'<link>([^<]+)</link>', r.text)
    print(f"All link tags: {links[:10]}")
    guid_links = re.findall(r'<guid[^>]*>([^<]+)</guid>', r.text)
    print(f"Guid links: {guid_links[:5]}")
except Exception as e:
    print(f"NaijaPrey RSS error: {e}")

print("\n=== 4. DIAGNOSING ANITAKU ===")
try:
    r = session.get("https://anitaku.com.ro/search.html?keyword=one+piece", timeout=10)
    print(f"Anitaku search status: {r.status_code}, len={len(r.text)}")
    soup = BeautifulSoup(r.text, 'html.parser')
    items = soup.select(".items li")
    print(f"Items found: {len(items)}")
    for it in items[:3]:
        a = it.select_one("a")
        print(f"Anime item: text='{it.text.strip()[:30]}', href='{a['href'] if a else None}'")
except Exception as e:
    print(f"Anitaku search error: {e}")
