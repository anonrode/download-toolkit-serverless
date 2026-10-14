import requests
import re
import time
from bs4 import BeautifulSoup

s = requests.Session()
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'}
url = 'https://loadedfiles.net/a1b699fe62085569/The.Last.Goodbye.To.Mama.S01E15.540p.x265.AAC.[9jaRocks.Com].mkv'

curr_url = url
referer = 'https://my9jarocks.bz/'

for step in range(1, 6):
    print(f"\n--- STEP {step}: Fetching {curr_url} (Referer: {referer}) ---")
    r = s.get(curr_url, headers={**headers, 'Referer': referer}, allow_redirects=False)
    print(f"Status: {r.status_code}")
    if r.status_code in (301, 302, 303, 307, 308):
        loc = r.headers.get('Location')
        print(f"Redirect -> {loc}")
        referer = curr_url
        curr_url = loc
        if 'static.' in loc or 'download_token=' in loc or '/token/download/' in loc:
            print("SUCCESS! Final direct media URL reached:", loc)
            break
        continue
    
    # Check for x-data link
    m_link = re.search(r'link:\s*[\'"]([^\'"]+)[\'"]', r.text)
    if m_link:
        raw = m_link.group(1)
        link = raw.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
        print(f"Found x-data link: {link}")
        referer = curr_url
        curr_url = link
        continue

    # Check for button href with ?pt= or /d/
    soup = BeautifulSoup(r.text, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if not btn:
        btn = soup.find('a', href=lambda h: h and ('?pt=' in h or '/d/' in h or '/token/download/' in h))
    
    if btn and btn.get('href'):
        next_url = btn['href']
        print(f"Found button href: {next_url}")
        print("Waiting 5s for cooldown/timer...")
        time.sleep(5)
        referer = curr_url
        curr_url = next_url
        continue
    
    print("Could not find next URL in page! Snippet:\n", r.text[:500])
    break
