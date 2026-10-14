import re

with open(r'C:\Users\user\Downloads\activity-log-share-22.txt', 'r', encoding='utf-8', errors='ignore') as f:
    lines = f.readlines()[2038:]

tasks = {}
curr_task = None
for l in lines:
    m = re.search(r'task=([0-9a-f\-]+)', l)
    if m:
        t = m.group(1)
        tasks.setdefault(t, []).append(l.strip())

print(f"Found {len(tasks)} tasks in recent log:")
for tid, tlines in tasks.items():
    print(f"\n=================== TASK: {tid} ({len(tlines)} lines) ===================")
    # print first line, url if found, errors, final lines
    urls = [x for x in tlines if 'url=' in x or 'start task' in x]
    if urls:
        print("  URL info: " + urls[0][:150])
    hls_info = [x for x in tlines if 'hls' in x.lower() or 'progress' in x.lower() or 'bytes' in x.lower()]
    for h in hls_info[:5]:
        print("  Progress/HLS: " + h[:150])
    errors = [x for x in tlines if any(k in x.lower() for k in ['fail', 'err', 'except', 'overflow', 'reject', 'warn'])]
    print(f"  Errors count: {len(errors)}")
    for e in errors[:5]:
        print("  ERR: " + e[:150])
    print("  LAST LINE: " + tlines[-1][:150])
