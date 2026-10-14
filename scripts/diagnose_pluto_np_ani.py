import requests
import re
from bs4 import BeautifulSoup

session = requests.Session()
session.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
})

print("=== 1. PLUTO MOVIE LINKS ===")
r = session.get("https://plutomovies.com", timeout=10)
links = set(re.findall(r'href=["\']([^"\']*(?:movie|watch|film)[^"\']*)["\']', r.text, re.I))
print(f"Pluto links found ({len(links)}): {list(links)[:5]}")

print("\n=== 2. NAIJAPREY DOWNLOAD LINKS ===")
post_url = "https://www.naijaprey.tv/livestream-from-hell-2026/"
r_np = session.get(post_url, timeout=10)
np_dl = set(re.findall(r'href=["\'](https?://[^"\']*(?:sdm_downloads|wildshare|downloadwella|vikingfile|vdl\.np-downloader)[^"\']*)["\']', r_np.text, re.I))
print(f"NaijaPrey download links found: {list(np_dl)}")

print("\n=== 3. ANITAKU SEARCH HTML ===")
r_ani = session.get("https://anitaku.com.ro/search.html?keyword=one+piece", timeout=10)
soup = BeautifulSoup(r_ani.text, 'html.parser')
# Print all lists or links
ani_links = [a['href'] for a in soup.find_all('a', href=True) if '/category/' in a['href']]
print(f"Anitaku category links: {ani_links[:5]}")
if not ani_links:
    # Look for search results structure
    print("No /category/ links. Showing other links:")
    other = [a['href'] for a in soup.find_all('a', href=True)][:15]
    print(other)
