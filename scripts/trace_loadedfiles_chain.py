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
        curr_url = loc
        continue
    ct = r.headers.get('Content-Type', '')
    print("Content-Type:", ct)
    if 'video' in ct or 'octet-stream' in ct or 'matroska' in ct:
        print("SUCCESS! Landed media!")
        break
    soup = BeautifulSoup(r.text, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if btn:
        print(f"Found primary btn: text='{btn.text.strip()}' href='{btn.get('href')}'")
        headers['Referer'] = curr_url
        curr_url = btn['href']
        time.sleep(3)
        continue
    # Check for direct or script links
    m = re.findall(r'https?://[^\s"\'<>]+', r.text)
    print(f"No primary btn! Found {len(m)} urls in text.")
    for u in m:
        if 'download' in u or 'cdn' in u or 'storage' in u:
            print("  Candidate:", u)
    with open(f"loadedfiles_step{step}.html", "w", encoding="utf-8") as f:
        f.write(r.text)
    break
