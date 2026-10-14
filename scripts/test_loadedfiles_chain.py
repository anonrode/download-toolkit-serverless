import requests
import re
import time
from bs4 import BeautifulSoup

urls = [
    'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv',
]

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'
}

for u in urls:
    s = requests.Session()
    print("Testing:", u)
    r1 = s.get(u, headers=headers)
    soup = BeautifulSoup(r1.text, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if not btn:
        print('No button in step 1')
        continue
    pt1 = btn['href']
    print('Step 1 button href:', pt1)
    time.sleep(4)
    r2 = s.get(pt1, headers={**headers, 'Referer': u})
    m = re.search(r'link:\s*[\'"]([^\'"]+)[\'"]', r2.text)
    if not m:
        print('No link in step 2! Length:', len(r2.text))
        continue
    raw_link = m.group(1)
    link = raw_link.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
    print('Found link:', link)
    r3 = s.get(link, headers={**headers, 'Referer': pt1}, allow_redirects=False)
    print('Step 3 status:', r3.status_code, 'Location:', r3.headers.get('Location'))
