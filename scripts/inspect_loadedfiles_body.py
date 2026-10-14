import requests, re

s = requests.Session()
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
}
url = 'https://loadedfiles.net/fe6b8f63f37ae237/The.Last.Goodbye.To.Mama.S01E01.540p.x265.AAC.[9jaRocks.Com].mkv'
referer = 'https://my9jarocks.bz/'

# Fetch step 1 and check full body for x-data, link:, Alpine, etc.
r = s.get(url, headers={**headers, 'Referer': referer})
body = r.text

print("=== BODY INSPECTION (Step 1) ===")
print(f"Length: {len(body)}")
print(f"Contains 'x-data': {'x-data' in body}")
print(f"Contains 'alpine': {'alpine' in body.lower()}")
print(f"Contains 'link:': {'link:' in body}")
print(f"Contains 'dlTimer': {'dlTimer' in body}")
print(f"Contains 'downloadUrl': {'downloadUrl' in body}")
print(f"Contains 'Download now': {'Download now' in body}")

# Check all scripts
for m in re.finditer(r'<script[^>]*>(.*?)</script>', body, re.S):
    src = m.group(1).strip()
    if src:
        print(f"\nScript block ({len(src)} chars):")
        print(src[:300])
        print("...")

# Check the nv-btn
for m in re.finditer(r'nv-btn--primary', body):
    start = max(0, m.start() - 200)
    end = min(len(body), m.end() + 200)
    print(f"\nnv-btn context:")
    print(body[start:end])
