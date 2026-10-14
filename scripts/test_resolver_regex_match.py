import re

# Simulate the body as the resolver would see it
with open('loadedfiles_step5.html', 'r', encoding='utf-8') as f:
    body = f.read()

# Check what the Kotlin resolver's regexes would match

# 1. dlTimer regex
m1 = re.search(r"dlTimer\(\{\s*seconds:\s*(\d+),\s*link:\s*['\"]([^'\"]+)['\"]", body, re.I)
print("dlTimer match:", m1)

# 2. var downloadUrl regex
m2 = re.search(r"var downloadUrl = '(https://loadedfiles\.[a-z0-9-]+/[^']+)'", body, re.I)
print("var downloadUrl match:", m2)

# 3. link: regex (the fallback)
m3 = re.search(r"link\s*:\s*['\"]([^'\"]+)['\"]", body, re.I)
print("link: match:", m3)
if m3:
    raw = m3.group(1)
    # unescape
    unescaped = raw.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
    print("  raw:", raw[:100])
    print("  unescaped:", unescaped[:120])

# 4. Jsoup a.nv-btn--primary  
from bs4 import BeautifulSoup
soup = BeautifulSoup(body, 'html.parser')
btn_a = soup.find('a', class_='nv-btn--primary')
print("a.nv-btn--primary:", btn_a)  # None on step 5!
btn_button = soup.find('button', class_='nv-btn--primary')
print("button.nv-btn--primary:", btn_button.text.strip() if btn_button else None)

# The button's parent div has x-data with the link
for div in soup.find_all('div', attrs={'x-data': True}):
    xdata = div.get('x-data', '')
    if 'link' in xdata:
        print("\nx-data div found! x-data attribute length:", len(xdata))
        link_match = re.search(r"link:\s*'([^']+)'", xdata)
        if link_match:
            raw = link_match.group(1)
            unescaped = raw.replace(r'\/', '/').replace(r'\u003A', ':').replace(r'\u003F', '?').replace(r'\u003D', '=').replace(r'\u0025', '%')
            print("  link in x-data:", unescaped[:120])
