import time
import requests
import re
from bs4 import BeautifulSoup

session = requests.Session()
session.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
})

url = "https://loadedfiles.net/deed0bb13aeccb7a/Signs.Of.A.Psychopath.S11E01.540p.X265.AAC.[9jaRocks.Com].mkv"
print(f"1. Fetching {url}...")
r1 = session.get(url, headers={"Referer": "https://9jarocks.net/"}, timeout=10)
print(f"r1 status: {r1.status_code}")

# Find timer and link
m = re.search(r'''dlTimer\s*\(\s*\{.*?link\s*:\s*['"]([^'"]+)['"]''', r1.text, re.DOTALL | re.I)
if m:
    raw_pt = m.group(1).replace(r'\/', '/').replace(r'\u0026', '&').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=')
    print(f"Found pt url: {raw_pt}")
    
    print("Waiting 6 seconds for Loadedfiles dlTimer to elapse...")
    time.sleep(6)
    
    r2 = session.get(raw_pt, headers={"Referer": url}, timeout=10)
    print(f"r2 status: {r2.status_code}, len={len(r2.text)}")
    
    # Check what is inside r2
    m2 = re.search(r'''(?:href|link)\s*[:=]\s*['"]([^'"]*(?:/d/|\.mkv|\.mp4)[^'"]*)['"]''', r2.text, re.I)
    if m2:
        print(f"FOUND DIRECT FILE LINK: {m2.group(1)}")
    else:
        # Find any anchor with /d/ or direct download
        soup = BeautifulSoup(r2.text, 'html.parser')
        d_links = [a['href'] for a in soup.find_all('a', href=True) if '/d/' in a['href'] or '.mkv' in a['href']]
        print(f"Direct links in soup: {d_links}")
        dl_timer2 = re.findall(r'dlTimer[^\n]+', r2.text)
        print(f"dlTimer in r2: {dl_timer2}")
        # Print first 500 chars of r2 text
        print("Page preview:")
        print(r2.text[:500])
