with open(r'C:\Users\user\Downloads\anon_crash-2.txt', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

entries = text.split("=== ANONRODE CRASH LOG")
print("CRASH #8:")
print("\n".join(entries[8].split('\n')[:60]))
