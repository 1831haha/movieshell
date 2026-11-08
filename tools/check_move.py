#!/usr/bin/env python3
"""搬包后的全仓引用核对（v1.1.5 分层第 1 批的教训：check_imports.py 只扫 git 改动过
的文件，搬包后同包可见断裂的未改动文件它扫不到——v1.1.4 #561 就是这么挂的）。

用法：
    python3 tools/check_move.py <旧包> <新包> <符号1> [符号2 ...]

查两类：
  (a) 每个被搬符号：全仓每个使用点必须 import 新包，或与定义文件同包；
  (b) 每个已搬文件：用到留在旧包的顶层符号时，必须 import 旧包。
只认代码引用：先剥离 // 与 /* */（Kotlin 块注释可嵌套）注释、字符串与字符字面量，
注释/文案里提到的符号名不算。退出码 0=无违规，1=有违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path("app/src")


def strip_code(s):
    out = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if s.startswith("//", i):
            j = s.find("\n", i)
            i = n if j == -1 else j
            out.append(" ")
        elif s.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth:
                if s.startswith("/*", i):
                    depth += 1
                    i += 2
                elif s.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            out.append(" ")
        elif s.startswith('"""', i):
            j = s.find('"""', i + 3)
            i = n if j == -1 else j + 3
            out.append('""')
        elif c == '"':
            i += 1
            while i < n and s[i] != '"':
                if s[i] == "\\":
                    i += 1
                i += 1
            i += 1
            out.append('""')
        elif c == "'":
            i += 1
            while i < n and s[i] != "'":
                if s[i] == "\\":
                    i += 1
                i += 1
            i += 1
            out.append("''")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    old_pkg, new_pkg, moved = sys.argv[1], sys.argv[2], sys.argv[3:]
    files = list(ROOT.rglob("*.kt"))
    texts = {p: p.read_text() for p in files}
    pkgs = {}
    for p, s in texts.items():
        m = re.search(r"^package\s+([\w.]+)", s, re.M)
        pkgs[p] = m.group(1) if m else ""
    stripped = {p: strip_code(s) for p, s in texts.items()}

    def uses(p, sym):
        for line in stripped[p].splitlines():
            if line.strip().startswith("import "):
                continue
            if re.search(rf"\b{re.escape(sym)}\b", line):
                return True
        return False

    def has_import(p, pkg, sym):
        return f"import {pkg}.{sym}" in texts[p]

    violations = []
    for sym in moved:
        for p in files:
            if pkgs[p] == new_pkg:
                continue
            if uses(p, sym) and not has_import(p, new_pkg, sym):
                violations.append(f"(a) {p} uses {sym} without {new_pkg} import")

    decl_re = re.compile(
        r"^(?:public\s+|internal\s+|private\s+)?"
        r"(?:data\s+class|sealed\s+class|sealed\s+interface|class|object|interface"
        r"|enum\s+class|typealias|fun|val|const\s+val)\s+([A-Za-z_][A-Za-z0-9_]*)",
        re.M,
    )
    old_syms = {}
    for p, s in texts.items():
        if pkgs[p] != old_pkg:
            continue
        for m in decl_re.finditer(s):
            old_syms.setdefault(m.group(1), p)
    for p in files:
        if pkgs[p] != new_pkg:
            continue
        for sym in old_syms:
            if sym in moved:
                continue
            if uses(p, sym) and not has_import(p, old_pkg, sym):
                violations.append(f"(b) moved {p.name} uses {sym} without import")

    print(f"checked {len(files)} files, {len(moved)} moved symbols, "
          f"{len(old_syms)} old-package symbols")
    if violations:
        print(f"VIOLATIONS: {len(violations)}")
        for v in sorted(violations):
            print(" ", v)
        return 1
    print("NO VIOLATIONS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
