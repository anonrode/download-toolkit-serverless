import requests
from bs4 import BeautifulSoup
import time

s = requests.Session()
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'}
url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'

for hop in range(1, 10):
    print(f"\n--- HOP {hop}: {url} ---")
    r = s.get(url, headers=headers, allow_redirects=False)
    print(f"Status: {r.status_code}")
    print(f"Content-Type: {r.headers.get('Content-Type')}")
    if 'Location' in r.headers:
        print(f"Redirect: {r.headers['Location']}")
        url = r.headers['Location']
        continue
    
    soup = BeautifulSoup(r.text, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if not btn:
        print("No nv-btn--primary found!")
        # check all anchors
        for a in soup.find_all('a'):
            if a.get('href') and any(k in a['href'] for k in ['/d/', 'download', 'token']):
                print(f"Found download anchor: {a['href']}")
        break
    
    print(f"Button text: {btn.text.strip()}")
    print(f"Button href: {btn['href']}")
    if btn['href'] == url:
        print("Same URL, looping detected!")
        break
    url = btn['href']
    # Check if there is any wait or timer
    time.sleep(1)
