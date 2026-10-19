import os
import sys

def check_file(path):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()

    i = 0
    n = len(text)
    stack = []
    line = 1
    col = 1
    in_line_comment = False
    in_block_comment = False

    # Kotlin lexical contexts (v3.3.0 F-13: string templates are CODE).
    # 'code' is the implicit top level; an opening quote pushes 'string' or
    # 'raw'; "${" inside any string pushes 'expr' — inside a template
    # expression the scanner is in code again, where nested string literals
    # are legal ("a${f("b")}c"). A naive quote-to-quote scan miscounts the
    # brackets there (false positive found when the gate widened to all
    # files). Entries are (kind, depth); depth counts nested braces inside
    # an 'expr' frame — the brace that returns it to 0 closes the template.
    ctx_stack = []

    errors = []
    while i < n:
        c = text[i]
        if c == '\n':
            line += 1
            col = 1
            in_line_comment = False
            i += 1
            continue

        if in_line_comment:
            i += 1
            col += 1
            continue

        if in_block_comment:
            if text[i:i+2] == '*/':
                in_block_comment = False
                i += 2
                col += 2
            else:
                i += 1
                col += 1
            continue

        ctx = ctx_stack[-1][0] if ctx_stack else 'code'

        if ctx == 'string':
            if c == '\\':
                i += 2
                col += 2
                continue
            if c == '$' and text[i+1:i+2] == '{':
                ctx_stack.append(('expr', 1))
                i += 2
                col += 2
                continue
            if c == '"':
                ctx_stack.pop()
            i += 1
            col += 1
            continue

        if ctx == 'raw':
            if text[i:i+3] == '"""':
                ctx_stack.pop()
                i += 3
                col += 3
                continue
            if c == '$' and text[i+1:i+2] == '{':
                ctx_stack.append(('expr', 1))
                i += 2
                col += 2
                continue
            i += 1
            col += 1
            continue

        if ctx == 'expr':
            if text[i:i+2] == '//':
                in_line_comment = True
                i += 2
                col += 2
                continue
            if text[i:i+2] == '/*':
                in_block_comment = True
                i += 2
                col += 2
                continue
            if text[i:i+3] == '"""':
                ctx_stack.append(('raw', 0))
                i += 3
                col += 3
                continue
            if c == '"':
                ctx_stack.append(('string', 0))
                i += 1
                col += 1
                continue
            if c == "'":
                if i + 2 < n and text[i+2] == "'":
                    i += 3
                    col += 3
                elif i + 3 < n and text[i+1] == '\\' and text[i+3] == "'":
                    i += 4
                    col += 4
                else:
                    i += 1
                    col += 1
                continue
            if c == '{':
                ctx_stack[-1] = ('expr', ctx_stack[-1][1] + 1)
                i += 1
                col += 1
                continue
            if c == '}':
                depth = ctx_stack[-1][1] - 1
                if depth == 0:
                    ctx_stack.pop()
                else:
                    ctx_stack[-1] = ('expr', depth)
                i += 1
                col += 1
                continue
            if c in '([':
                stack.append((c, line, col))
            elif c in ')]':
                if not stack:
                    errors.append(f"Unmatched closing {c} at line {line}:{col}")
                else:
                    top, tline, tcol = stack.pop()
                    expected = {'{': '}', '[': ']', '(': ')'}[top]
                    if c != expected:
                        errors.append(f"Mismatched bracket: expected {expected} for {top} from line {tline}:{tcol}, got {c} at line {line}:{col}")
            i += 1
            col += 1
            continue

        # Normal code
        if text[i:i+2] == '//':
            in_line_comment = True
            i += 2
            col += 2
            continue
        if text[i:i+2] == '/*':
            in_block_comment = True
            i += 2
            col += 2
            continue
        if text[i:i+3] == '"""':
            ctx_stack.append(('raw', 0))
            i += 3
            col += 3
            continue
        if c == '"':
            ctx_stack.append(('string', 0))
            i += 1
            col += 1
            continue
        if c == "'":
            if i + 2 < n and text[i+2] == "'":
                i += 3
                col += 3
            elif i + 3 < n and text[i+1] == '\\' and text[i+3] == "'":
                i += 4
                col += 4
            else:
                i += 1
                col += 1
            continue

        if c in '{[(':
            stack.append((c, line, col))
        elif c in '}])':
            if not stack:
                errors.append(f"Unmatched closing {c} at line {line}:{col}")
            else:
                top, tline, tcol = stack.pop()
                expected = {'{': '}', '[': ']', '(': ')'}[top]
                if c != expected:
                    errors.append(f"Mismatched bracket: expected {expected} for {top} from line {tline}:{tcol}, got {c} at line {line}:{col}")
        i += 1
        col += 1

    while stack:
        top, tline, tcol = stack.pop()
        errors.append(f"Unclosed {top} from line {tline}:{tcol}")

    return errors

if __name__ == "__main__":
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # v3.3.0 (F-13): no hand-maintained target list anymore — walk EVERY .kt
    # file under app/src/main and app/src/test (sorted, deterministic). A new
    # file is gated the moment it lands; the gate can never silently lag the
    # tree the way a hardcoded list did.
    target_files = []
    for src_dir in ("app/src/main", "app/src/test"):
        base = os.path.join(repo, *src_dir.split("/"))
        for dirpath, _dirnames, filenames in os.walk(base):
            for fn in filenames:
                if fn.endswith(".kt"):
                    rel = os.path.relpath(os.path.join(dirpath, fn), repo)
                    target_files.append(rel.replace(os.sep, "/"))
    target_files.sort()

    has_error = False
    for rel in target_files:
        full = os.path.join(repo, *rel.split("/"))
        errs = check_file(full)
        if errs:
            print(f"FAIL: {rel}")
            for e in errs:
                print(f"   {e}")
            has_error = True
        else:
            print(f"PASS: {rel}")

    print(f"\nchecked {len(target_files)} Kotlin files under app/src/main + app/src/test")
    if has_error:
        sys.exit(1)
    print("ALL TARGET KOTLIN FILES VERIFIED CLEAN WITH ZERO SYNTAX/BRACE ERRORS!")
