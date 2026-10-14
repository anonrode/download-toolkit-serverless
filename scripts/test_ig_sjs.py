import urllib.request
import re
import json

shortcode = 'DdoIYvzgiwj'
url = f'https://www.instagram.com/p/{shortcode}/'
req = urllib.request.Request(url, headers={
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5'
})
try:
    with urllib.request.urlopen(req, timeout=10) as r:
        content = r.read().decode('utf-8', errors='ignore')
        sjs = re.findall(r'<script\b[^>]+\bdata-sjs>(\{.+?\})</script>', content)
        print(f"data-sjs count: {len(sjs)}")
        found = 0
        for i, s in enumerate(sjs):
            try:
                data = json.loads(s)
                s_str = json.dumps(data)
                if 'display_url' in s_str or 'image_versions2' in s_str or 'carousel_media' in s_str or 'music_metadata' in s_str:
                    print(f"SJS {i} matched media fingerprints! Length: {len(s_str)}")
                    found += 1
            except Exception as e:
                pass
        print(f"Total matching SJS: {found}")
except Exception as e:
    print("Error:", e)
