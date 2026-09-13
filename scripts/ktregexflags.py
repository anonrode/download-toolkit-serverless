# Static gate: Android-portable regex rule. Two device crashes (v3.1.1, v3.1.2
# on 2026-09-13) proved Android's java.util.regex fork rejects constructs the
# desktop JVM happily compiles — a rejected pattern in <clinit> becomes
# ExceptionInInitializerError and kills the first download tap of every
# session. Banned anywhere in a main-source pattern string:
#   - inline flags (?i)(?u)... at a position OTHER than 0  (v3.1.1 crash)
#   - lookBEHIND (?<= (?<! and named groups (?<name>       (v3.1.2 crash)
#   - atomic groups (?<  >  and possessive quantifiers *+ ++ ?+
#   - property classes \p{ \P{
# Allowed: lookAHEAD (?= (?! (universally supported; repo-wide precedent).
# Comment-aware, concatenation-aware, skips interpolated $"..." outside
# triple strings. Usage: python ktregexflags.py <src_dir>
import sys, os, re
FLAG = re.compile(r"\(\?[aiusdmxuU-]")
BANNED = [
    ("lookbehind/named-group (?<", re.compile(r"\(\?<")),
    ("atomic group (?>", re.compile(r"\(\?>")),
    ("possessive quantifier", re.compile(r"[*+?]\+")),
    ("property class \\p{", re.compile(r"\\[pP]\{")),
]
def stray_braces(pat):
    """ICU (Android's real java.util.regex backend) rejects an unescaped `}`
    that the desktop JVM reads as a literal -- the CONFIRMED cause of every
    v3.1.x download crash (anon_crash.txt: 'Syntax error ... near index 16'
    on '\\{([^{}]{1,60})}'). Flag any brace that is not escaped, not inside a
    [...] class, and not a valid {n}/ {n,} / {n,m} quantifier."""
    out, i, n = [], 0, len(pat)
    while i < n:
        c = pat[i]
        if c == "\\": i += 2; continue
        if c == "[":
            j = i + 1
            if j < n and pat[j] == "^": j += 1
            if j < n and pat[j] == "]": j += 1
            while j < n:
                if pat[j] == "\\": j += 2; continue
                if pat[j] == "]": break
                j += 1
            i = j + 1; continue
        if c == "{":
            m = re.match(r"\{\d+(,\d*)?\}", pat[i:])
            if m: i += m.end(); continue
            out.append(i); i += 1; continue
        if c == "}":
            out.append(i); i += 1; continue
        i += 1
    return out
def context_is_regex(text, lit_start):
    """True when the string literal at lit_start is the first argument of a
    Regex( call. The stray-brace rule only applies to actual patterns —
    playbook template strings ("{base}", "{1:%02d}") legitimately carry
    unescaped braces as data."""
    j = lit_start - 1
    while j >= 0 and text[j] in " \t\r\n":
        j -= 1
    if j < 0 or text[j] != "(":
        return False
    j -= 1
    while j >= 0 and text[j] in " \t\r\n":
        j -= 1
    return text[:j + 1].endswith("Regex")

def code_and_strings(text):
    """Return (pattern-run, line-number, is-regex-arg) triples built from
    string literals that sit in a 'literal + literal' run in code (comments
    skipped)."""
    i, n = 0, len(text); runs = []; cur = None; isrx = False; buf = []; curline = 0
    def close():
        nonlocal cur
        if cur is not None:
            runs.append(("".join(buf), curline, isrx)); cur = None; buf.clear()
    def line_at(pos):
        return text.count("\n", 0, pos) + 1
    while i < n:
        c = text[i]
        if c == "/" and text.startswith("//", i):
            close(); j = text.find("\n", i); i = n if j < 0 else j; continue
        if c == "/" and text.startswith("/*", i):
            close(); j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if c == "'":  # char literal (may contain quotes)
            close(); j = i + 1
            while j < n and text[j] != "'": j += 2 if text[j] == "\\" else 1
            i = j + 1; continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            if j < 0: break
            # Kotlin: a run of >3 quotes keeps all but the LAST 3 as content
            # (`""""` = content `"` + terminator `"""`; `"""""` = content `""`).
            while j + 3 < n and text[j + 3] == '"':
                j += 1
            if cur is None: curline = line_at(i); isrx = context_is_regex(text, i)
            cur = True
            buf.append(text[i+3:j]); i = j + 3; continue
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            if j >= n: break
            if cur is None: curline = line_at(i); isrx = context_is_regex(text, i)
            cur = True
            buf.append(text[i+1:j].replace(r"\"", '"').replace(r"\\", "\\")); i = j + 1; continue
        if c in " \t\r\n+": i += 1; continue
        # interpolation start $"..." or standalone $ — not a static pattern
        close(); i += 1
    close()
    return runs
problems, files = [], 0
for dp, _, fns in os.walk(sys.argv[1]):
    for fn in sorted(fns):
        if not fn.endswith(".kt"): continue
        p = os.path.join(dp, fn); files += 1
        src = open(p, encoding="utf-8", errors="replace").read()
        for pat, line, is_rx in code_and_strings(src):
            for m in FLAG.finditer(pat):
                if m.start() != 0:
                    problems.append(f"{os.path.basename(p)}:{line}: mid-pattern flag {pat[m.start():m.start()+4]!r} at pos {m.start()} in {pat[:70]!r}")
            for label, rx in BANNED:
                for m in rx.finditer(pat):
                    problems.append(f"{os.path.basename(p)}:{line}: BANNED {label} at pos {m.start()} in {pat[:70]!r}")
            if is_rx:
                for pos in stray_braces(pat):
                    problems.append(f"{os.path.basename(p)}:{line}: ICU-stray brace at pos {pos} in {pat[:70]!r}")
print(f"scanned {files} kt files")
print("PORTABILITY PROBLEMS:", len(problems))
for x in problems: print("  ", x)
sys.exit(1 if problems else 0)
