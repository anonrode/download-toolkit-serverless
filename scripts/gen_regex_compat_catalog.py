#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate app/src/androidTest RegexCompatCatalog.kt from the main source.

The catalog is what makes CI's Tier-2 emulator the ground truth for the
"desktop JVM compiles it, the phone throws" bug class (v3.1.1 + v3.1.2 device
crashes). It extracts:
  CLINIT_CLASSES   every top-level class/object in any file that mentions
                   Regex( -- Class.forName'ing these runs their <clinit>
                   (where Pattern.compile of static vals happens) on the
                   device engine, mirroring the exact crash vector.
  STATIC_PATTERNS  every string literal (concatenation-joined) that feeds a
                   Regex(...) first argument -- compiled with Pattern.compile
                   on the device engine. Covers function-local builders whose
                   class init alone would not reach.
Skipped (reported): patterns containing Kotlin interpolation ($) -- these are
covered by runtime calls in RegexEngineCompatTest instead.

Regenerate after ANY regex change and commit the result:
  python scripts/gen_regex_compat_catalog.py
CI/pre-push drift check: rerun and `git diff --exit-code` the catalog file.
"""
import os, re, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__))) if os.path.basename(os.path.dirname(__file__)) == "scripts" else os.getcwd()
SRC = os.path.join(REPO, "app", "src", "main")
OUT = os.path.join(REPO, "app", "src", "androidTest", "java", "com", "anonrode", "downloader", "RegexCompatCatalog.kt")

def strip_and_scan(text):
    """Yield (start,end) of string literals and comment spans for masking."""
    i, n = 0, len(text)
    spans = []  # (kind, start, end) kind in {'raw','esc'}
    while i < n:
        c = text[i]
        if c == "/" and text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if c == "/" and text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if c == "'":
            j = i + 1
            while j < n and text[j] != "'": j += 2 if text[j] == "\\" else 1
            i = j + 1; continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            if j < 0: break
            spans.append(("raw", i + 3, j)); i = j + 3; continue
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                if text[j] == "\\": j += 2
                else: j += 1
            spans.append(("esc", i + 1, j)); i = j + 1; continue
        i += 1
    return spans

ESC_MAP = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", '"': '"', "$": "$", "'": "'"}
def decode_esc(raw):
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            e = raw[i + 1]
            if e == "u":
                out.append(chr(int(raw[i + 2:i + 6], 16))); i += 6; continue
            if e in ESC_MAP:
                out.append(ESC_MAP[e]); i += 2; continue
            out.append(e); i += 2; continue
        out.append(c); i += 1
    return "".join(out)

def extract_from(text):
    """Return (patterns, has_interp_skipped, interp_list)."""
    spans = strip_and_scan(text)
    pats, interp = [], []
    for m in re.finditer(r"\bRegex\(", text):
        start = m.end()
        # skip to first string literal at the argument head
        j = start
        parts, ok = [], True
        while j < len(text):
            c = text[j]
            if c in " \t\r\n": j += 1; continue
            if c == '"':
                match = next(((k, s, e) for k, s, e in spans if s == j + (3 if text.startswith('"""', j) else 1)), None)
                if match is None: ok = False; break
                kind, s, e = match
                lit = text[s:e]
                if re.search(r"\$(?!\{)\w|\$\{", lit):
                    interp.append(lit[:60]); ok = False; break
                parts.append(lit if kind == "raw" else decode_esc(lit))
                j = e + (3 if kind == "raw" else 1)
                # continue only across '+' concatenation
                k = j
                while k < len(text) and text[k] in " \t\r\n": k += 1
                if k < len(text) and text[k] == "+":
                    j = k + 1; continue
                break
            else:
                ok = False; break
        if ok and parts:
            p = "".join(parts)
            if p not in pats: pats.append(p)
    return pats, interp

def top_class_names(text, stem):
    # Kotlin convention in this repo: the principal type is named after the
    # file; anything declared at indent 0 is top-level too.
    names = [stem]
    for m in re.finditer(r"(?:^|\n)(?:@\w+(?:\([^)]*\))?\s*)?(?:public\s+|internal\s+|private\s+)?(?:open\s+|abstract\s+|sealed\s+|data\s+|enum\s+)*(?:object|class)\s+([A-Za-z_][A-Za-z0-9_]*)", text):
        if m.group(1) not in names: names.append(m.group(1))
    return names

classes, patterns, interp_notes = [], [], []
for dp, _, fns in os.walk(SRC):
    for fn in sorted(fns):
        if not fn.endswith(".kt"): continue
        p = os.path.join(dp, fn)
        text = open(p, encoding="utf-8", errors="replace").read()
        if "Regex(" not in text: continue
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg: continue
        for cls in top_class_names(text, fn[:-3]):
            fqn = pkg.group(1) + "." + cls
            if fqn not in classes: classes.append(fqn)
        pats, interp = extract_from(text)
        for pat in pats:
            entry = (os.path.relpath(p, SRC).replace("\\", "/"), pat)
            if entry not in patterns: patterns.append(entry)
        for it in interp:
            interp_notes.append(f"{os.path.relpath(p, SRC).replace(chr(92),'/')}: {it}")

def kt_str(s):
    # emit as a normal Kotlin string literal: every special fully escaped
    t = (s.replace("\\", "\\\\").replace('"', '\\"')
           .replace("$", "\\$").replace("\n", "\\n")
           .replace("\r", "\\r").replace("\t", "\\t"))
    return '"' + t + '"'

lines = []
lines.append("package com.anonrode.downloader")
lines.append("")
lines.append("// GENERATED by scripts/gen_regex_compat_catalog.py -- DO NOT EDIT BY HAND.")
lines.append("// Regenerate after any regex change and commit; RegexEngineCompatTest compiles")
lines.append("// every entry below against the DEVICE's real java.util.regex engine (CI Tier-2),")
lines.append("// which is the only pre-ship test that can catch the Android-vs-JVM syntax split.")
lines.append("object RegexCompatCatalog {")
lines.append("")
lines.append("    val CLINIT_CLASSES: List<String> = listOf(")
for c in sorted(classes):
    lines.append(f'        "{c}",')
lines.append("    )")
lines.append("")
lines.append("    // (origin file, pattern literal) -- compiled verbatim in the test.")
lines.append("    val STATIC_PATTERNS: List<Pair<String, String>> = listOf(")
for f, p in patterns:
    lines.append(f"        \"{f}\" to {kt_str(p)},")
lines.append("    )")
lines.append("")
lines.append("    // Interpolated templates (${'$'}alt-lists etc) are NOT extractable as static")
lines.append("    // strings; RegexEngineCompatTest covers them by calling the runtime entry")
lines.append("    // points that build them. Count: " + str(len(interp_notes)))
for note in interp_notes:
    lines.append("    //   " + note.replace("\n", " "))
lines.append("}")
open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
print(f"classes={len(classes)} static_patterns={len(patterns)} interpolated={len(interp_notes)}")
print("wrote", OUT)
