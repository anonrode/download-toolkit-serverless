with open(r'C:\Users\user\Downloads\activity-log-share-22.txt', 'r', encoding='utf-8', errors='ignore') as f:
    lines = f.readlines()

for idx, l in enumerate(lines):
    if '3fd624a1-6bd6-4a03-81fe-44b9c9524629' in l:
        print(f"L{idx+1}: {l.strip()}")
