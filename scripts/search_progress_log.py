with open(r'C:\Users\user\Downloads\activity-log-share-22.txt', 'r', encoding='utf-8', errors='ignore') as f:
    lines = f.readlines()

for idx, l in enumerate(lines):
    if any(k in l.lower() for k in ['@@dlp@@', 'progress disk=', 'progress:', 'downloading', '% of', 'aria2c start', 'yt-dlp start']):
        print(f"L{idx+1}: {l.strip()[:160]}")
