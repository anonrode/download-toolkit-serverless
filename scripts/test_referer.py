import requests

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}
url = 'https://loadedfiles.net/a1b699fe62085569/The.Last.Goodbye.To.Mama.S01E15.540p.x265.AAC.[9jaRocks.Com].mkv'

for ref in ['https://my9jarocks.bz/', 'https://9jarocks.net/', 'https://loadedfiles.net/', None]:
    h = dict(headers)
    if ref:
        h['Referer'] = ref
    r = requests.get(url, headers=h, allow_redirects=False)
    has_pt = '?pt=' in r.text
    print(f"Referer: {ref} -> Status: {r.status_code}, len={len(r.content)}, has_pt={has_pt}")
