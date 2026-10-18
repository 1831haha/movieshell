#!/usr/bin/env python3
"""Kotlin brace/paren balance sanity check (pre-push gate).

Strips block comments, line comments, string literals (regular, triple-quoted,
raw), char literals and string templates, then verifies that '{'/'}' and
'('/')' depth never goes negative and ends at zero per file.

This catches exactly the class of error that broke v0.96.0/v0.96.1/v0.96.2
cloud builds four times in a row (stray or missing braces from the
AlertDialog->TechDialog batch migration): no local Kotlin compiler exists,
so this is the cheapest pre-push net for that mistake class.

Usage: python3 tools/check_braces.py [--all]
  no args  -> check git-changed *.kt files (staged + unstaged + untracked)
  --all    -> check every *.kt under app/src
Exit 0 = clean, 1 = imbalance found.
"""
import re
import subprocess
import sys

REPO = __import__("pathlib").Path(__file__).resolve().parent.parent


def changed_kt_files():
    out = subprocess.run(
        ["git", "diff", "--name-only", "HEAD", "--", "*.kt"],
        cwd=REPO, capture_output=True, text=True,
    ).stdout.splitlines()
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "*.kt"],
        cwd=REPO, capture_output=True, text=True,
    ).stdout.splitlines()
    files = []
    for f in out + untracked:
        p = REPO / f
        if p.is_file() and p not in files:
            files.append(p)
    return files


def all_kt_files():
    return sorted((REPO / "app" / "src").rglob("*.kt"))


def strip_noise(src: str) -> str:
    """Remove comments / strings / chars so only code punctuation remains."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        # line comment
        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            i = n if j == -1 else j
            continue
        # block comment (Kotlin nests them)
        if c == "/" and nxt == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if src[i] == "/" and i + 1 < n and src[i + 1] == "*":
                    depth += 1
                    i += 2
                elif src[i] == "*" and i + 1 < n and src[i + 1] == "/":
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        # triple-quoted raw string. Kotlin rule: a candidate closing """ that is
        # immediately followed by another '"' is content, not the delimiter
        # (e.g. """...""""  -> content ends with '"'). Skip one quote ahead.
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            while j != -1 and j + 3 < n and src[j + 3] == '"':
                j = src.find('"""', j + 1)
            i = n if j == -1 else j + 3
            continue
        # regular string
        if c == '"':
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        # char literal
        if c == "'":
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                if src[i] == "\n":
                    break
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def check_file(path) -> list:
    problems = []
    code = strip_noise(path.read_text(encoding="utf-8"))
    depth_curly = 0
    depth_paren = 0
    for lineno, line in enumerate(code.splitlines(), 1):
        for ch in line:
            if ch == "{":
                depth_curly += 1
            elif ch == "}":
                depth_curly -= 1
                if depth_curly < 0:
                    problems.append(f"{path}:{lineno}: 括号多了 '}}'（深度为负）")
                    depth_curly = 0
            elif ch == "(":
                depth_paren += 1
            elif ch == ")":
                depth_paren -= 1
                if depth_paren < 0:
                    problems.append(f"{path}:{lineno}: 圆括号多了 ')'（深度为负）")
                    depth_paren = 0
    if depth_curly:
        problems.append(f"{path}: 文件末尾 '{{}}' 不平衡，残留深度 {depth_curly}")
    if depth_paren:
        problems.append(f"{path}: 文件末尾 '()' 不平衡，残留深度 {depth_paren}")
    return problems


def main():
    files = all_kt_files() if "--all" in sys.argv else changed_kt_files()
    if not files:
        print("没有改动的 kt 文件，跳过。")
        return 0
    bad = []
    for f in files:
        bad.extend(check_file(f))
    if bad:
        print(f"❌ {len(bad)} 处括号/圆括号不平衡：")
        for b in bad:
            print("  " + b)
        print("\npush 前先修好。这是 v0.96.0 连挂四轮的教训。")
        return 1
    print(f"✅ {len(files)} 个文件括号平衡。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
