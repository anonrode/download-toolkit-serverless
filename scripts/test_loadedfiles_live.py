import requests
import re
from bs4 import BeautifulSoup

s = requests.Session()
url1 = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
r1 = s.get(url1, headers=headers)
soup = BeautifulSoup(r1.text, 'html.parser')
btn = soup.find('a', href=re.compile(r'\?pt='))
pt_url = btn['href']
print('Found pt_url from step 1:', pt_url)

# Test 1: No Referer
r_no_ref = s.get(pt_url, headers=headers)
soup_no_ref = BeautifulSoup(r_no_ref.text, 'html.parser')
btn2 = soup_no_ref.find('a', class_='nv-btn--primary')
print('No Referer response len:', len(r_no_ref.text))
print('Btn in No Referer:', btn2)

# Test 2: Referer = url1
r_ref1 = s.get(pt_url, headers={'User-Agent': headers['User-Agent'], 'Referer': url1})
soup_ref1 = BeautifulSoup(r_ref1.text, 'html.parser')
btn2_ref = soup_ref1.find('a', class_='nv-btn--primary')
print('Referer url1 response len:', len(r_ref1.text))
print('Btn in Referer url1:', btn2_ref)

# Now follow btn2 href!
if btn2:
    step3_url = btn2['href']
    print('\nFollowing step 3 URL:', step3_url)
    r3 = s.get(step3_url, headers={'User-Agent': headers['User-Agent'], 'Referer': pt_url}, allow_redirects=False)
    print('Step 3 status:', r3.status_code)
    print('Step 3 headers:', r3.headers)
    if 'Location' in r3.headers:
        print('Step 3 redirect:', r3.headers['Location'])
    else:
        soup3 = BeautifulSoup(r3.text, 'html.parser')
        btn3 = soup3.find('a', class_='nv-btn--primary')
        print('Step 3 btn:', btn3)
        for a in soup3.find_all('a'):
            if a.get('href') and any(k in a['href'] for k in ['/d/', 'download', 'token']):
                print('  Download link in step 3:', a)
