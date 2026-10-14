import requests
from bs4 import BeautifulSoup
import re

url1 = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

s = requests.Session()
r1 = s.get(url1, headers=headers, timeout=10)
soup1 = BeautifulSoup(r1.text, 'html.parser')
pt_a = soup1.find('a', href=re.compile(r'\?pt='))
print('Found pt_a:', pt_a)

if pt_a:
    pt_url = pt_a['href']
    r2 = s.get(pt_url, headers=headers, timeout=10)
    soup2 = BeautifulSoup(r2.text, 'html.parser')
    print('\nAll buttons / forms / anchors in page 2:')
    for form in soup2.find_all('form'):
        print('FORM:', form.attrs)
        for inp in form.find_all('input'):
            print('  input:', inp.attrs)
    for a in soup2.find_all('a'):
        if a.get('href') and any(k in a['href'] for k in ['loadedfiles', '/d/', 'token', 'pt=']):
            print('A:', a.attrs)
    print('\nLooking for inline JS variables:')
    for script in soup2.find_all('script'):
        st = script.string or script.text or ""
        if any(w in st.lower() for w in ['dltimer', 'countdown', 'location', 'window.', 'url', 'download']):
            print('SCRIPT:', st.strip()[:400])
