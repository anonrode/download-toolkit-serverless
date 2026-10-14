import requests
import re
import time
from bs4 import BeautifulSoup

s = requests.Session()
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
}

url1 = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'

print("--- STEP 1: Fetching initial page ---")
r1 = s.get(url1, headers=headers)
soup1 = BeautifulSoup(r1.text, 'html.parser')
btn1 = soup1.find('a', class_='nv-btn--primary')
pt_url1 = btn1['href']
print("Step 1 -> button href:", pt_url1)

print("\n--- Waiting 5 seconds before hitting step 2 ---")
time.sleep(5)

headers['Referer'] = url1
r2 = s.get(pt_url1, headers=headers)
print("Step 2 status:", r2.status_code)

# Check if dlButton exists
m_link = re.search(r"link:\s*['\"]([^'\"]+)['\"]", r2.text)
if m_link:
    raw_link = m_link.group(1)
    # unescape JS
    link = raw_link.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
    print("Found link in x-data:", link)
    
    headers['Referer'] = pt_url1
    r3 = s.get(link, headers=headers, allow_redirects=False)
    print("\n--- STEP 3: Hitting link from x-data ---")
    print("Step 3 status:", r3.status_code)
    print("Step 3 headers:", dict(r3.headers))
    if 'Location' in r3.headers:
        loc = r3.headers['Location']
        print("Redirect to:", loc)
        r4 = s.get(loc, headers=headers, stream=True, allow_redirects=False)
        print("Step 4 status:", r4.status_code)
        print("Step 4 headers:", dict(r4.headers))
    else:
        print("Step 3 body snippet:", r3.text[:300])
else:
    print("No link found in Step 2 body! Length:", len(r2.text))
