import urllib.request
import re

shortcode = 'DdoIYvzgiwj'
url = f'https://www.instagram.com/p/{shortcode}/embed/'
req = urllib.request.Request(url, headers={
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
})
try:
    with urllib.request.urlopen(req, timeout=10) as r:
        content = r.read().decode('utf-8', errors='ignore')
        print(f"Embed status: {r.status}, len: {len(content)}")
        imgs = re.findall(r'https://[^"\'\s]+\.(?:jpg|jpeg|webp)[^"\'\s]*', content)
        print(f"Found {len(imgs)} image urls in embed:")
        for img in imgs[:5]:
            print("  ", img[:120])
except Exception as e:
    print("Embed Error:", e)
