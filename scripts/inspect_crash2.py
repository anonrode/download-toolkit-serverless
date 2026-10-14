with open(r'C:\Users\user\Downloads\anon_crash-2.txt', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

# find first 200 lines of crash
lines = text.split('\n')
for idx, l in enumerate(lines[:100]):
    print(l)
