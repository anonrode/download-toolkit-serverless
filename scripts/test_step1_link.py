import requests, re

s = requests.Session()
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}
url = 'https://loadedfiles.net/a1b699fe62085569/The.Last.Goodbye.To.Mama.S01E15.540p.x265.AAC.[9jaRocks.Com].mkv'
r = s.get(url, headers=headers)

body = r.text
print("Searching for mLink pattern:")
m = re.findall(r'link\s*:\s*[\'"][^\'"]+[\'"]', body, re.I)
print("Matches found:", len(m))
for match in m:
    print("  Match:", match[:120])
