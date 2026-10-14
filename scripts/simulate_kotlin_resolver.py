import requests
import time
import re
from bs4 import BeautifulSoup

s = requests.Session()
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
}
curr_url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
referer = 'https://my9jarocks.bz/'

for step in range(1, 10):
    print(f"\n=== STEP {step}: {curr_url} ===")
    r = s.get(curr_url, headers={**headers, 'Referer': referer}, allow_redirects=False)
    print(f"Status: {r.status_code}, Length: {len(r.content)}")
    
    if r.status_code in (301, 302, 303, 307, 308):
        loc = r.headers.get('Location')
        print(f"REDIRECT -> {loc}")
        if '/token/download/' in loc or '/d/' in loc or 'static.' in loc:
            print("SUCCESS! Direct CDN URL!")
            break
        referer = curr_url
        curr_url = loc
        continue
    
    ct = r.headers.get('Content-Type', '')
    if 'video' in ct or 'octet-stream' in ct:
        print(f"Direct media! CT={ct}")
        break
    
    body = r.text
    
    # Check all patterns the Kotlin resolver tries, IN ORDER
    # 1. dlTimer
    m1 = re.search(r"dlTimer\(\{\s*seconds:\s*(\d+),\s*link:\s*['\"]([^'\"]+)['\"]", body, re.I)
    if m1:
        print(f"  [1] dlTimer matched! seconds={m1.group(1)}")
    
    # 2. var downloadUrl
    m2 = re.search(r"var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'", body, re.I)
    if m2:
        print(f"  [2] var downloadUrl matched!")
    
    # 3. link: regex
    m3 = re.search(r"link\s*:\s*['\"]([^'\"]+)['\"]", body, re.I)
    if m3:
        raw = m3.group(1)
        unescaped = raw.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
        print(f"  [3] link: regex matched! -> {unescaped[:80]}")
    
    # 4. Jsoup a.nv-btn--primary
    soup = BeautifulSoup(body, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if btn:
        print(f"  [4] a.nv-btn--primary found! -> {btn.get('href', '')[:80]}")
    
    btn2 = soup.find('button', class_='nv-btn--primary')
    if btn2:
        print(f"  [5] button.nv-btn--primary found! (NOT an anchor)")
    
    # Determine what the Kotlin resolver would pick
    if m1:
        next_url = m1.group(2).replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=')
        print(f"  -> Kotlin picks: dlTimer link")
    elif m2:
        next_url = m2.group(1)
        print(f"  -> Kotlin picks: var downloadUrl")
    elif m3:
        next_url = unescaped
        print(f"  -> Kotlin picks: link: regex")
    elif btn:
        next_url = btn.get('href', '')
        print(f"  -> Kotlin picks: a.nv-btn--primary")
    else:
        print(f"  -> STALLED! No next URL found.")
        break
    
    referer = curr_url
    curr_url = next_url
    time.sleep(2)
