import requests
import time
import re
from bs4 import BeautifulSoup

s = requests.Session()
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5'
}
curr_url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'

for step in range(1, 10):
    print(f"=== STEP {step}: Fetching {curr_url} ===")
    r = s.get(curr_url, headers=headers, allow_redirects=False)
    print(f"Status: {r.status_code}, Length: {len(r.content)}")
    if r.status_code in (301, 302, 303, 307, 308):
        loc = r.headers.get('Location')
        print(f"REDIRECT -> {loc}")
        headers['Referer'] = curr_url
        curr_url = loc
        continue
    ct = r.headers.get('Content-Type', '')
    if 'video' in ct or 'octet-stream' in ct or 'matroska' in ct:
        print(f"SUCCESS! Landed media! Content-Type={ct}, Content-Length={r.headers.get('Content-Length')}")
        break

    soup = BeautifulSoup(r.text, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if btn and btn.get('href') and btn.get('href') != curr_url:
        print(f"Found primary anchor btn: {btn.get('href')}")
        headers['Referer'] = curr_url
        curr_url = btn['href']
        time.sleep(3)
        continue

    # Check for x-data link or dlButton
    m_link = re.search(r"link:\s*['\"]([^'\"]+)['\"]", r.text)
    if m_link:
        raw = m_link.group(1)
        unescaped = raw.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
        print(f"Found link in x-data: {unescaped}")
        headers['Referer'] = curr_url
        curr_url = unescaped
        time.sleep(3)
        continue

    print("No next step found! Snippet:")
    print(r.text[:300])
    break
