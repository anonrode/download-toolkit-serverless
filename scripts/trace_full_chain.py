import requests

s = requests.Session()
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
}
url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
referer = 'https://my9jarocks.bz/'

for step in range(1, 20):
    print(f"\n=== STEP {step}: {url[:90]} ===")
    r = s.get(url, headers={**headers, 'Referer': referer}, allow_redirects=False)
    print(f"Status: {r.status_code}, Len: {len(r.content)}, Cookies: {dict(s.cookies)}")
    
    if r.status_code in (301, 302, 303, 307, 308):
        loc = r.headers.get('Location', '')
        print(f"REDIRECT -> {loc[:120]}")
        if 'static.' in loc or '/token/download/' in loc or '/d/' in loc:
            print("*** SUCCESS: CDN URL! ***")
            # Probe the final URL
            probe = s.head(loc, headers={**headers, 'Referer': url}, allow_redirects=False)
            print(f"CDN probe: {probe.status_code}, CT={probe.headers.get('Content-Type')}, CL={probe.headers.get('Content-Length')}")
            break
        referer = url
        url = loc
        continue
    
    from bs4 import BeautifulSoup
    import re
    body = r.text
    
    # Check x-data link
    m_link = re.search(r"link\s*:\s*['\"]([^'\"]+)['\"]", body, re.I)
    if m_link:
        raw = m_link.group(1)
        unesc = raw.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
        print(f"  link: matched -> {unesc[:100]}")
        referer = url
        url = unesc
        import time; time.sleep(2)
        continue
    
    soup = BeautifulSoup(body, 'html.parser')
    btn = soup.find('a', class_='nv-btn--primary')
    if not btn:
        btn = soup.select_one('a[href*="?pt="]')
    if btn:
        href = btn.get('href', '')
        print(f"  a.nv-btn -> {href[:100]}")
        referer = url
        url = href
        import time; time.sleep(2)
        continue
    
    print("  STALLED - no next URL")
    break
