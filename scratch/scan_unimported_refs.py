"""Repo-wide static sweep: cross-package object references used without an
import — the class of bug that broke compilation silently while no JVM build
exists locally (three P0 hits found manually 2026-09-13; this finds the rest)."""
import os, re

root = "app/src/main/java"
files = []
for dp, _, fns in os.walk(root):
    for fn in fns:
        if fn.endswith(".kt"):
            files.append(os.path.join(dp, fn))

decls = {}
decl_re = re.compile(r"^(?:@\w+\s+)*(?:public |internal |private )?(?:object|class|enum class|sealed class|abstract class)\s+(\w+)", re.M)
pkg_re = re.compile(r"^package\s+([\w.]+)", re.M)
for f in files:
    src = open(f, encoding="utf-8").read()
    pkg = pkg_re.search(src)
    pkg = pkg.group(1) if pkg else ""
    for m in decl_re.finditer(src):
        decls.setdefault(m.group(1), set()).add(pkg)

triple = re.compile('"""[\\s\\S]*?"""')
dquote = re.compile(r'"(?:\\.|[^"\\])*"')
squote = re.compile(r"'(?:\\.|[^'\\])'")
line_c = re.compile(r"//.*")
block_c = re.compile(r"/\*[\s\S]*?\*/")
use_re = re.compile(r"(?<![\w.])([A-Z]\w+)\.")

problems = []
for f in files:
    src = open(f, encoding="utf-8").read()
    pkg = pkg_re.search(src).group(1)
    imports = set(re.findall(r"^import\s+(\S+)", src, re.M))
    imported_names = {i.split(".")[-1] for i in imports}
    body = triple.sub('""', src)
    body = dquote.sub('""', body)
    body = squote.sub("''", body)
    body = block_c.sub("", body)
    body = line_c.sub("", body)
    for m in use_re.finditer(body):
        name = m.group(1)
        if name not in decls:
            continue
        pkgs = decls[name]
        if pkg in pkgs or name in imported_names:
            continue
        if any(i.endswith(".*") and i[:-2] in pkgs for i in imports):
            continue
        line = src[:m.start()].count("\n") + 1
        problems.append((f, line, name, sorted(pkgs)))

seen = set()
for f, l, n, p in problems:
    if (f, l, n) in seen:
        continue
    seen.add((f, l, n))
    print(f"{f}:{l} uses {n}. (lives in {p}, not imported, not same-package)")
print("TOTAL:", len(seen))
