import requests
import re
import time

def test_chain(sleep_time):
    print(f"\n--- Testing with sleep_time = {sleep_time}s ---")
    s = requests.Session()
    headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'}
    url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
    referer = 'https://my9jarocks.bz/'

    r1 = s.get(url, headers={**headers, 'Referer': referer}, allow_redirects=False)
    m = re.search(r'href="([^"]+\?pt=[^"]+)"', r1.text)
    if not m:
        print("Step 1 failed to find pt link")
        return
    pt1 = m.group(1)
    print(f"Step 1 got pt link: {pt1[:60]}...")
    
    print(f"Sleeping {sleep_time}s...")
    time.sleep(sleep_time)
    
    r2 = s.get(pt1, headers={**headers, 'Referer': url}, allow_redirects=False)
    print(f"Step 2 status: {r2.status_code}")
    has_link = 'link:' in r2.text
    print(f"Step 2 has 'link:': {has_link}")
    if has_link:
        m2 = re.search(r'link:\s*[\'"]([^\'"]+)[\'"]', r2.text)
        print("Step 2 link extracted:", m2.group(1)[:60])

test_chain(1)
test_chain(5)
