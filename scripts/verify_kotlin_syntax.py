import os
import re
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


# ── Import / scope heuristics (2026-10-22) ────────────────────────────────
# The brace scanner is not a compiler. Two real CI blockers slipped through
# it: a bare `HttpClient` with no import and a local val used before its
# declaration. These checks are deliberately conservative: they only flag a
# PascalCase name that is DECLARED top-level in another package of THIS repo
# and not imported (same-package and wildcard imports are trusted), and a
# local val initializer that reads a name declared later in the same
# indentation block.

TOP_DECL_RE = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s+)*'
    r'(?:(?:public|internal|private|protected|open|abstract|sealed|data|enum|'
    r'annotation|value|inline|suspend|operator|infix|tailrec|external|'
    r'lateinit|const|override|final|actual|expect|companion)\s+)*'
    r'(?:class|object|interface|fun|val|var|typealias)\s+([A-Za-z_]\w*)'
)
ANY_DECL_RE = re.compile(
    r'^(\s*)(?:(?:public|internal|private|protected|open|abstract|sealed|data|'
    r'enum|annotation|value|inline|suspend|operator|infix|tailrec|external|'
    r'lateinit|const|override|final|actual|expect|companion)\s+)*'
    r'(class|object|interface|fun|val|var|typealias)\s+([A-Za-z_]\w*)'
)
IMPORT_RE = re.compile(r'^\s*import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+(\w+))?\s*$')
PKG_RE = re.compile(r'(?m)^\s*package\s+([\w.]+)')
LOCAL_DECL_RE = re.compile(r'^(\s*)(val|var)\s+([A-Za-z_]\w*)\b')


def strip_comments_and_strings(text):
    """Blank comments/strings (including ${...} template expressions) but
    preserve every newline and character offset."""
    out = list(text)
    n = len(text)
    i = 0
    # Context stack: 'code' is implicit; strings/raw push a frame; a ${ in
    # a string pushes ('expr', brace_depth).
    ctx = []
    while i < n:
        c = text[i]
        top = ctx[-1] if ctx else None
        kind = top[0] if top else 'code'

        if kind in ('string', 'raw'):
            if c == '\n':
                i += 1
                continue
            if c == '\\' and kind == 'string':
                out[i] = ' '
                if i + 1 < n and text[i + 1] != '\n':
                    out[i + 1] = ' '
                i += 2
                continue
            if kind == 'string' and c == '"':
                out[i] = ' '
                ctx.pop()
                i += 1
                continue
            if kind == 'raw' and text.startswith('"""', i):
                out[i] = out[i + 1] = out[i + 2] = ' '
                ctx.pop()
                i += 3
                continue
            if c == '$' and text[i + 1:i + 2] == '{':
                out[i] = out[i + 1] = ' '
                ctx.append(('expr', 1))
                i += 2
                continue
            out[i] = ' '
            i += 1
            continue

        if kind == 'line':
            if c == '\n':
                ctx.pop()
                i += 1
            else:
                out[i] = ' '
                i += 1
            continue
        if kind == 'block':
            if text.startswith('*/', i):
                out[i] = out[i + 1] = ' '
                ctx.pop()
                i += 2
            else:
                if c != '\n':
                    out[i] = ' '
                i += 1
            continue

        # code, or an expr frame inside a template
        if text.startswith('//', i):
            out[i] = out[i + 1] = ' '
            ctx.append(('line', 0))
            i += 2
            continue
        if text.startswith('/*', i):
            out[i] = out[i + 1] = ' '
            ctx.append(('block', 0))
            i += 2
            continue
        if text.startswith('"""', i):
            out[i] = out[i + 1] = out[i + 2] = ' '
            ctx.append(('raw', 0))
            i += 3
            continue
        if c == '"':
            out[i] = ' '
            ctx.append(('string', 0))
            i += 1
            continue
        if c == "'":
            out[i] = ' '
            i += 1
            if i < n and text[i] == '\\':
                out[i] = ' '
                i += 1
            if i < n and text[i] != '\n':
                out[i] = ' '
                i += 1
            if i < n and text[i] == "'":
                out[i] = ' '
                i += 1
            continue
        if kind == 'expr':
            if c == '{':
                ctx[-1] = ('expr', top[1] + 1)
            elif c == '}':
                depth = top[1] - 1
                if depth == 0:
                    ctx.pop()
                else:
                    ctx[-1] = ('expr', depth)
            i += 1
            continue
        i += 1
    return ''.join(out)


def build_global_index(files):
    """name -> set(package) for top-level declarations across the repo."""
    index = {}
    packages = {}
    for rel, text in files.items():
        pkg_m = PKG_RE.search(text)
        pkg = pkg_m.group(1) if pkg_m else ''
        packages[rel] = pkg
        stripped = strip_comments_and_strings(text)
        for line in stripped.splitlines():
            # Only COLUMN-0 declarations are package-visible. Members of an
            # object/class (Spacing, Radius, TextPrimary…) are not importable
            # by their bare name from another package.
            if line[:1] in (' ', '\t'):
                continue
            m = TOP_DECL_RE.match(line)
            if m:
                index.setdefault(m.group(1), set()).add(pkg)
    return index, packages


def check_imports(rel, text, stripped, global_index, pkg):
    """Flag a repo-declared PascalCase type/function used across packages
    without an import. Conservative: wildcard imports disable the check."""
    imported = set()
    wildcard_packages = set()
    for line in stripped.splitlines():
        m = IMPORT_RE.match(line)
        if not m:
            continue
        target, alias = m.group(1), m.group(2)
        if target.endswith('.*'):
            wildcard_packages.add(target[:-2])
        else:
            imported.add(alias or target.rsplit('.', 1)[-1])
    local_names = set()
    for line in stripped.splitlines():
        m = ANY_DECL_RE.match(line)
        if m:
            local_names.add(m.group(3))
    # Same-package top-level declarations are visible without imports.
    same_pkg = {name for name, pkgs in global_index.items() if pkg in pkgs}
    known = imported | local_names | same_pkg
    errors = []
    for m in re.finditer(r'(?<![\w.])[A-Z][A-Za-z0-9_]*\b', stripped):
        name = m.group(0)
        if name in known:
            continue
        line_start = stripped.rfind('\n', 0, m.start()) + 1
        line_end = stripped.find('\n', m.start())
        if line_end < 0:
            line_end = len(stripped)
        whole_line = stripped[line_start:line_end]
        if whole_line.lstrip().startswith(('import ', 'package ')):
            continue
        prefix = stripped[line_start:m.start()]
        if re.search(r'\b(class|object|interface|fun|val|var|typealias|enum)\s+$', prefix):
            continue
        packages = global_index.get(name)
        if not packages:
            continue
        if pkg in packages:
            continue
        # `import a.b.*` makes every top-level name in package a.b visible.
        if packages & wildcard_packages:
            continue
        line = stripped.count('\n', 0, m.start()) + 1
        errors.append(
            f"Unresolved import: '{name}' at line {line} "
            f"(declared in {', '.join(sorted(packages))}; add an import)"
        )
    return errors


def check_forward_locals(stripped):
    """Kotlin locals are not visible before their declaration. Catch the
    single-line initializer shape that broke CI (bar used watchInProgress
    before declaring it)."""
    lines = stripped.splitlines()
    decls = []
    for idx, line in enumerate(lines):
        m = LOCAL_DECL_RE.match(line)
        if m:
            decls.append((idx, len(m.group(1)), m.group(3), line))
    errors = []
    for idx, indent, name, line in decls:
        m = re.match(r'^(\s*)(val|var)\s+\w+[^=]*=(.*)$', line)
        if not m:
            continue
        expr = m.group(3)
        for um in re.finditer(r'\b([A-Za-z_]\w*)\b', expr):
            used = um.group(1)
            # Qualified member access (policy.force, u.host) is not a local.
            if um.start() > 0 and expr[um.start() - 1] in ('.', ':', '?'):
                continue
            if used == name:
                continue
            if any(d[0] < idx and d[2] == used for d in decls):
                continue
            # A parameter with this name is visible before the local.
            if re.search(r'\bfun\b[^\n]*\b' + re.escape(used) + r'\s*:', '\n'.join(lines[:idx])):
                continue
            for j in range(idx + 1, len(lines)):
                candidate = lines[j]
                if not candidate.strip():
                    continue
                cur_indent = len(candidate) - len(candidate.lstrip())
                if cur_indent < indent:
                    break
                dm = LOCAL_DECL_RE.match(candidate)
                if dm and len(dm.group(1)) == indent and dm.group(3) == used:
                    errors.append(
                        f"Local '{used}' used at line {idx + 1} before its "
                        f"declaration at line {j + 1}"
                    )
                    break
    return errors


if __name__ == "__main__":
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # v3.3.0 (F-13): no hand-maintained target list anymore — walk EVERY .kt
    # file under app/src/main and app/src/test (sorted, deterministic). A new
    # file is gated the moment it lands; the gate can never silently lag the
    # tree the way a hardcoded list did.
    target_files = []
    for src_dir in ("app/src/main", "app/src/test", "app/src/debug"):
        base = os.path.join(repo, *src_dir.split("/"))
        for dirpath, _dirnames, filenames in os.walk(base):
            for fn in filenames:
                if fn.endswith(".kt"):
                    rel = os.path.relpath(os.path.join(dirpath, fn), repo)
                    target_files.append(rel.replace(os.sep, "/"))
    target_files.sort()

    texts = {}
    for rel in target_files:
        with open(os.path.join(repo, *rel.split("/")), "r", encoding="utf-8") as f:
            texts[rel] = f.read()
    global_index, packages = build_global_index(texts)

    has_error = False
    for rel in target_files:
        full = os.path.join(repo, *rel.split("/"))
        stripped = strip_comments_and_strings(texts[rel])
        errs = check_file(full)
        errs += check_imports(rel, texts[rel], stripped, global_index, packages.get(rel, ""))
        errs += check_forward_locals(stripped)
        if errs:
            print(f"FAIL: {rel}")
            for e in errs:
                print(f"   {e}")
            has_error = True
        else:
            print(f"PASS: {rel}")

    print(f"\nchecked {len(target_files)} Kotlin files under app/src/main + app/src/test + app/src/debug")
    if has_error:
        sys.exit(1)
    print("ALL TARGET KOTLIN FILES VERIFIED CLEAN WITH ZERO SYNTAX/BRACE/IMPORT/SCOPE ERRORS!")
