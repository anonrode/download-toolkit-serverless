import urllib.request
import re
import json

url = 'https://www.instagram.com/p/DdoIYvzgiwj/'
req = urllib.request.Request(
    url,
    headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept-Language': 'en-US,en;q=0.9'
    }
)
try:
    with urllib.request.urlopen(req, timeout=10) as resp:
        html = resp.read().decode('utf-8', errors='ignore')
        for m in re.finditer(r'"audio_tracks":(\[.*?\])', html):
            print("audio_tracks match:", m.group(1)[:300])
        for m in re.finditer(r'"clips_music_attribution_info":(\{.*?\})', html):
            print("clips_music_attribution_info:", m.group(1)[:300])
        for m in re.finditer(r'"music_info":(\{.*?\})', html):
            print("music_info:", m.group(1)[:300])
        for m in re.finditer(r'"music_asset_info":(\{.*?\})', html):
            print("music_asset_info:", m.group(1)[:300])
        # Find any progressive_download_url
        for m in re.finditer(r'"(?:audio_src|progressive_download_url|fast_start_progressive_download_url)":"([^"]+)"', html):
            print("Audio link match:", m.group(1)[:200])
except Exception as e:
    print('Error:', e)
