import requests, re
from bs4 import BeautifulSoup

s = requests.Session()
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
}
url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
referer = 'https://my9jarocks.bz/'

r = s.get(url, headers={**headers, 'Referer': referer})
body = r.text

# Find ALL x-data attributes
soup = BeautifulSoup(body, 'html.parser')
for el in soup.find_all(attrs={'x-data': True}):
    xdata = el.get('x-data', '')
    tag = el.name
    cls = el.get('class', [])
    print(f"\n<{tag} class={cls}> x-data ({len(xdata)} chars):")
    print(xdata[:500])
    print("...")

# Check for any hidden API endpoints, fetch() calls, or AJAX
print("\n\n=== LOOKING FOR API/AJAX CALLS ===")
for pattern in [r'fetch\s*\(', r'XMLHttpRequest', r'\.ajax', r'api/', r'/generate', r'/get-link', r'x-token', r'x-init', r'@click']:
    matches = re.findall(pattern, body, re.I)
    if matches:
        # Find context
        for m in re.finditer(pattern, body, re.I):
            start = max(0, m.start() - 100)
            end = min(len(body), m.end() + 200)
            print(f"\n  {pattern} match:")
            print(body[start:end])

# Check all links/anchors in the page
print("\n\n=== ALL LINKS ===")
for a in soup.find_all('a', href=True):
    href = a.get('href', '')
    text = a.get_text(strip=True)[:40]
    if 'loadedfiles' in href or '?pt=' in href or '/d/' in href or '/token/' in href:
        print(f"  [{text}] -> {href[:120]}")
