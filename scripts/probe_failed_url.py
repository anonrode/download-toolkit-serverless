import requests

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'}
url = 'https://loadedfiles.net/a1b699fe62085569/The.Last.Goodbye.To.Mama.S01E15.540p.x265.AAC.[9jaRocks.Com].mkv'
referer = 'https://my9jarocks.bz/'

r = requests.get(url, headers={**headers, 'Referer': referer}, allow_redirects=False)
print("Status:", r.status_code)
print("Headers:", r.headers)
print("Body snippet:", r.text[:500])
