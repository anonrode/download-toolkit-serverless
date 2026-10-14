from bs4 import BeautifulSoup

with open('loadedfiles_page1.html', 'r', encoding='utf-8') as f:
    soup = BeautifulSoup(f.read(), 'html.parser')

print("Title:", soup.title.text if soup.title else None)
for a in soup.find_all('a'):
    if a.get('href') and ('pt=' in a['href'] or 'download' in a.text.lower() or 'btn' in str(a.get('class'))):
        print("Anchor:", a.get('class'), a.get('href'), a.text.strip())

for btn in soup.find_all('button'):
    print("Button:", btn.get('class'), btn.text.strip())

for form in soup.find_all('form'):
    print("Form:", form.get('action'), form.get('method'))
    for inp in form.find_all('input'):
        print("  Input:", inp.get('name'), inp.get('value'), inp.get('type'))
