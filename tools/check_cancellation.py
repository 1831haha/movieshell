#!/usr/bin/env python3
"""Cancellation-swallowing toast check (pre-push gate).

Kotlin's runCatching / catch (e: Exception) also catch CancellationException.
When a composable's rememberCoroutineScope() is cancelled (e.g. user switches
tabs, the composition is disposed), the in-flight work throws
LeftCompositionCancellationException("The coroutine scope left the composition")
and a failure branch that toasts it.message reports a *user-abandoned* task as
a failure -- and the raw English runtime text means nothing to the user.

Rule: any `.onFailure { ... }` block or `catch (e: Exception)` /
`catch (e: Throwable)` block that calls AppToast.show must first rethrow
cancellation, i.e. the block must mention CancellationException
(`if (it is CancellationException) throw it`, or catching
CancellationException separately). Otherwise the script fails.

History: v1.4.1 cross-review (LiveScreen.doRefresh toast, MusicScreen
loadChart/importPlaylist) -- same disease already fixed twice before
(PlayResolveScope v0.106.0, MusicPlayer poster v0.125.0, HomeScreen v1.1.25);
this gate stops the fourth recurrence.

Usage: python3 tools/check_cancellation.py [--all]
  no args  -> check git-changed *.kt files (staged + unstaged + untracked)
  --all    -> check every *.kt under app/src
Exit 0 = clean, 1 = violation found.
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

ON_FAILURE_RE = re.compile(r"\.onFailure\s*\{")
CATCH_RE = re.compile(r"catch\s*\(\s*\w+\s*:\s*(Exception|Throwable)\s*\)\s*\{")
CATCH_CANCEL_RE = re.compile(
    r"catch\s*\(\s*\w+\s*:\s*CancellationException\s*\)\s*\{"
)


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
    """Blank out comments / strings / chars, keeping offsets and newlines,
    so brace matching and keyword search see only code."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j == -1 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif two == "/*":
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if src[j:j + 2] == "/*":
                    depth += 1
                    j += 2
                elif src[j:j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            for k in range(i, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif two == '"""':
            j = src.find('"""', i + 3)
            j = n if j == -1 else j + 3
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                if src[j] == "\n":
                    break
                j += 1
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                if src[j] == "\n":
                    break
                j += 1
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def matching_brace(clean: str, open_idx: int):
    """Return index just past the matching '}' for the '{' at open_idx."""
    depth = 0
    i = open_idx
    while i < len(clean):
        if clean[i] == "{":
            depth += 1
        elif clean[i] == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return None


def check_file(path: Path):
    src = path.read_text(encoding="utf-8", errors="replace")
    clean = strip_noise(src)
    problems = []
    for m in ON_FAILURE_RE.finditer(clean):
        open_idx = m.end() - 1
        end = matching_brace(clean, open_idx)
        if end is None:
            continue
        body = clean[open_idx:end]
        if "AppToast" in body and "CancellationException" not in body:
            line = src.count("\n", 0, m.start()) + 1
            problems.append(
                (line, "onFailure 弹 Toast 却不重抛 CancellationException："
                       "取消会被报成失败（先判 if (it is CancellationException) throw it）")
            )
    for m in CATCH_RE.finditer(clean):
        # 已有前置 catch (CancellationException) { throw ... } 的同一 try 链
        # 是正确写法（取消先被单独接住重抛），不再重复告警。
        window = clean[max(0, m.start() - 400):m.start()]
        if re.search(
            r"catch\s*\(\s*\w+\s*:\s*CancellationException\s*\)"
            r"\s*\{\s*throw\s+\w+\s*\}\s*$",
            window,
        ):
            continue
        open_idx = m.end() - 1
        end = matching_brace(clean, open_idx)
        if end is None:
            continue
        body = clean[open_idx:end]
        if "AppToast" in body and "CancellationException" not in body:
            line = src.count("\n", 0, m.start()) + 1
            problems.append(
                (line, "catch (Exception/Throwable) 弹 Toast 却不重抛 CancellationException："
                       "取消会被报成失败（先判 if (e is CancellationException) throw e）")
            )
    for m in CATCH_CANCEL_RE.finditer(clean):
        open_idx = m.end() - 1
        end = matching_brace(clean, open_idx)
        if end is None:
            continue
        body = clean[open_idx:end]
        if "AppToast" in body:
            line = src.count("\n", 0, m.start()) + 1
            problems.append(
                (line, "catch (CancellationException) 里弹 Toast：取消本身不是失败，"
                       "不要在这里提示")
            )
    return problems


def main() -> int:
    targets = all_kt_files() if "--all" in sys.argv else changed_kt_files()
    bad = 0
    for path in targets:
        for line, msg in check_file(path):
            print(f"{path.relative_to(REPO)}:{line}: {msg}")
            bad += 1
    if bad:
        print(f"check_cancellation: {bad} 处违规")
        return 1
    print("check_cancellation: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
