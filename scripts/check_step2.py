import requests
from bs4 import BeautifulSoup
import re

s = requests.Session()
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'}
url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
referer = 'https://my9jarocks.bz/'

r1 = s.get(url, headers={**headers, 'Referer': referer}, allow_redirects=False)
soup1 = BeautifulSoup(r1.text, 'html.parser')
btn1 = soup1.find('a', class_='nv-btn--primary')
print("Step 1 btn:", btn1['href'] if btn1 else None)

import time
time.sleep(5)

r2 = s.get(btn1['href'], headers={**headers, 'Referer': url}, allow_redirects=False)
soup2 = BeautifulSoup(r2.text, 'html.parser')
btn2 = soup2.find('a', class_='nv-btn--primary')
btn2_button = soup2.find('button', class_='nv-btn--primary')
print("Step 2 btn <a>:", btn2['href'] if btn2 else None)
print("Step 2 btn <button>:", btn2_button.text.strip() if btn2_button else None)

# Print all elements with x-data in step 2
for el in soup2.find_all(attrs={'x-data': True}):
    print("x-data in step 2:", el.get('x-data')[:200])

m_link = re.search(r'link:\s*[\'"]([^\'"]+)[\'"]', r2.text)
print("m_link:", m_link.group(1)[:100] if m_link else None)
