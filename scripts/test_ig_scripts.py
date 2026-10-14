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
        print(f"data-sjs scripts found: {len(sjs)}")
        eqmc = re.findall(r'__eqmc[^>]*>(\{.*?\})</script>', content)
        print(f"eqmc scripts found: {len(eqmc)}")
        scripts = re.findall(r'<script\b[^>]*>(.*?)</script>', content, re.DOTALL)
        print(f"Total script tags: {len(scripts)}")
        for i, s in enumerate(scripts):
            if 'xdt_shortcode_media' in s or 'shortcode_media' in s or 'Polaris' in s or 'media_id' in s:
                print(f"Script {i} contains keywords! len: {len(s)}")
except Exception as e:
    print("Error:", e)
