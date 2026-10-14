import re

log_path = r"C:\Users\user\Downloads\activity-log-share-22.txt"

with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
    lines = f.readlines()

print(f"Total lines: {len(lines)}")

# Look for task failures, exceptions, crashes, and progress logs
task_events = {}
errors = []

for idx, line in enumerate(lines):
    line_str = line.strip()
    # Check for crash or exception
    if any(k in line_str.lower() for k in ["exception", "fatal", "crash", "overflow", "stackoverflow"]):
        errors.append((idx+1, line_str))
    
    # Check for task failure
    if "failed" in line_str.lower() or "failure" in line_str.lower():
        errors.append((idx+1, line_str))
        
    # Look for task progress jumps or strange progress
    if "progress" in line_str.lower() or "hls-size" in line_str.lower() or "jump" in line_str.lower() or "byteslanded" in line_str.lower():
        if any(w in line_str.lower() for w in ["hls-size-estimate", "diskbytes", "jump", "banked", "updateprogress"]):
            errors.append((idx+1, line_str))

print(f"Total matching lines: {len(errors)}")
print("\n--- SAMPLE ERROR/PROGRESS LINES ---")
for ln, l in errors[:60]:
    print(f"L{ln}: {l[:160]}")

print("\n--- STACK TRACES / EXCEPTIONS ---")
for idx, line in enumerate(lines):
    if "Exception" in line or "Error" in line or "StackOverflow" in line:
        print(f"L{idx+1}: {line.strip()[:180]}")
        # print next 3 lines if stack trace
        for j in range(1, 6):
            if idx + j < len(lines):
                print(f"   + {lines[idx+j].strip()[:180]}")
