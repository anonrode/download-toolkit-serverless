import requests
from bs4 import BeautifulSoup

s = requests.Session()
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'}
url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'

for hop in range(1, 10):
    r = s.get(url, headers=headers)
    print(f"HOP {hop} status: {r.status_code}, len: {len(r.text)}")
    soup = BeautifulSoup(r.text, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if not btn:
        print(f"HOP {hop} NO PRIMARY BTN! Saving HTML...")
        with open('loadedfiles_final.html', 'w', encoding='utf-8') as f:
            f.write(r.text)
        print("Page text snippet:")
        print(soup.text[:500])
        break
    print(f"HOP {hop} btn: {btn.text.strip()} -> {btn['href'][:60]}")
    url = btn['href']
