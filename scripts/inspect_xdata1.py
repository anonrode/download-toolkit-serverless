import re

try:
    with open('loadedfiles_page1.html', 'r', encoding='utf-8') as f:
        html = f.read()
    print("loadedfiles_page1.html length:", len(html))
    for m in re.finditer(r'x-data="([^"]+)"', html):
        print("x-data double:", m.group(1)[:200])
    for m in re.finditer(r"x-data='([^']+)'", html):
        print("x-data single:", m.group(1)[:200])
except Exception as e:
    print("Error:", e)
