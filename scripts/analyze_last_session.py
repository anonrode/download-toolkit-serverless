with open(r'C:\Users\user\Downloads\activity-log-share-22.txt', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

# Split by app started
sessions = text.split("=== app started")
print(f"Total sessions: {len(sessions)}")
for i, sess in enumerate(sessions):
    first_line = sess.strip().split("\n")[0] if sess.strip() else ""
    print(f"Session {i}: {first_line[:100]} (length: {len(sess)})")

# Let's inspect the LAST session (which is v3.2.5 on 10-01)
last_sess = sessions[-1]
print("\n" + "="*80)
print("LAST SESSION ANALYSIS:")
print("="*80)

lines = [l for l in last_sess.split("\n") if l.strip()]
print(f"Total lines in last session: {len(lines)}")

# Extract all [ENGINE], [RESOLVE], [BACKEND], [USER], [ERROR]
for l in lines:
    if any(k in l for k in ["[ENGINE]", "[BACKEND]", "[ERROR]", "hls-size", "progress", "failover", "resolver chain"]):
        print(l)
