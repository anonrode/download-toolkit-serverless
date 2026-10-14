with open(r'C:\Users\user\Downloads\anon_crash-2.txt', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

entries = text.split("=== ANONRODE CRASH LOG")
print(f"Total crash entries in anon_crash-2.txt: {len(entries)}")
for i, e in enumerate(entries[1:], 1):
    first_few = [l.strip() for l in e.split('\n') if l.strip()][:5]
    print(f"\nCrash #{i}: " + " | ".join(first_few))
