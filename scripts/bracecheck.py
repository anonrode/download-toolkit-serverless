# -*- coding: utf-8 -*-
"""Throwaway analyzer: find unescaped stray `{`/`}` in every main-source regex.
Reads the generated catalog (covers all static literals) plus the interpolated
templates read straight from NameSanitizer.kt. A brace is SAFE when: escaped
(\{ \}), inside a [...] class, or part of a valid quantifier {n} {n,} {n,m}."""
import re, io, os

def kotlin_unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            e = s[i+1]
            if e == "u":
                out.append(chr(int(s[i+2:i+6], 16))); i += 6; continue
            out.append(e); i += 2; continue
        out.append(c); i += 1
    return "".join(out)

def analyze(pat, origin):
    """Return list of problem descriptions for stray braces."""
    probs, i, n = [], 0, len(pat)
    while i < n:
        c = pat[i]
        if c == "\\":
            i += 2; continue
        if c == "[":
            # consume class: [ or [^ ; leading ] is literal
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
            probs.append(f"stray unescaped '{{' at {i} (tail: {pat[i:i+10]!r})")
            i += 1; continue
        if c == "}":
            probs.append(f"stray unescaped '}}' at {i}")
            i += 1; continue
        i += 1
    return probs

here = os.path.dirname(os.path.abspath(__file__))
repo = r"C:\Users\user\Anon\ANON TOOLS\download-toolkit-serverless"
cat = io.open(os.path.join(repo, r"app\src\androidTest\java\com\anonrode\downloader\RegexCompatCatalog.kt"), encoding="utf-8").read()
bad = 0
total = 0
for m in re.finditer(r'"([^"]+)" to "((?:[^"\\]|\\.)*)"', cat):
    origin, raw = m.group(1), m.group(2)
    pat = kotlin_unescape(raw)
    total += 1
    probs = analyze(pat, origin)
    for p in probs:
        bad += 1
        print(f"STATIC  {origin}: {p}  in {pat!r}")

# interpolated templates + their alt-lists, read from NameSanitizer.kt
ns = io.open(os.path.join(repo, r"app\src\main\java\com\anonrode\downloader\util\NameSanitizer.kt"), encoding="utf-8").read()
# every triple-quoted literal in the file, with $X kept as a placeholder atom
for m in re.finditer(r'"""((?:[^$]|$[^a-zA-Z{])*)"""', ns):
    pass  # (raw strings here either have no $ at all or use $alts)
for m in re.finditer(r'"""(.*?)"""', ns, re.S):
    pat = m.group(1)
    if "$alts" in pat or "$" in pat:
        probe = pat.replace("$alts", "ALTPROBE")
        probs = analyze(probe, "NameSanitizer(template)")
        for p in probs:
            bad += 1
            print(f"TEMPLATE NameSanitizer: {p}  in {pat[:60]!r}")
# every alts argument string (they contain no braces today; verify):
for m in re.finditer(r'(?:sepBare|endBare)\(\s*"""(.*?)"""', ns, re.S):
    alts = m.group(1)
    probs = analyze(alts, "NameSanitizer(alts)")
    for p in probs:
        bad += 1
        print(f"ALTS     NameSanitizer: {p}  in {alts[:60]!r}")

print(f"analyzed {total} static + templates; STRAY-BRACE PROBLEMS: {bad}")
raise SystemExit(1 if bad else 0)
