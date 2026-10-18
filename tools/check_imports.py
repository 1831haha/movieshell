#!/usr/bin/env python3
"""
push 前自查：拦住两类老毛病
1. 漏 import（v0.47.0 连挂两轮、v0.80.0 23 个错、v0.82.0 又犯的根因）
2. @Composable 标在非 fun 上（v0.82.0 MusicScreen 的错）

原理：从全仓库现有 import 建「符号名 -> 包路径」表；
对每个待查文件，找出代码里用到的首字母大写符号，
既没在文件内定义、又没 import、又不在同包/kotlin 自动导入里的 → 报错。

用法：python3 tools/check_imports.py [file1.kt file2.kt ...]
不带参数则检查 git status 里所有改动过的 kt 文件。
返回码：0=通过，1=有错误。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/kotlin/com/movieshell"

# kotlin 自动导入，无需 import
AUTO = {
    "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte",
    "Short", "Unit", "Any", "Nothing", "Array", "List", "Map", "Set",
    "MutableList", "MutableMap", "MutableSet", "Pair", "Triple",
    "Result", "Lazy", "Annotation",
    "IntRange", "LongRange", "CharRange",
    "IllegalStateException", "IllegalArgumentException", "Exception",
    "RuntimeException", "NullPointerException", "UnsupportedOperationException",
    "StringBuilder", "Throwable",
}

STRIP_RE = [
    (r'"""[\s\S]*?"""', '""'),
    (r'"(?:\\.|[^"\\])*"', '""'),
    (r"'(?:\\.|[^'\\])*'", "''"),
]


def strip_strings(s: str) -> str:
    for pat, rep in STRIP_RE:
        s = re.sub(pat, rep, s)
    return s


def strip_comments(s: str) -> str:
    out = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c == "/" and i + 1 < n and s[i + 1] == "/":
            j = s.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and i + 1 < n and s[i + 1] == "*":
            j = s.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def clean(s: str) -> str:
    return strip_comments(strip_strings(s))


def build_import_map() -> dict:
    """全仓库 import 表：符号简单名 -> set(完整 import 行)"""
    table: dict = {}
    for f in SRC.rglob("*.kt"):
        for m in re.finditer(r"^import\s+([\w.]+)$", f.read_text(), re.M):
            full = m.group(1)
            simple = full.split(".")[-1]
            table.setdefault(simple, set()).add(full)
    return table


def file_package(path: Path) -> str:
    m = re.search(r"^package\s+([\w.]+)", path.read_text(), re.M)
    return m.group(1) if m else ""


def defined_symbols(code: str) -> set:
    syms = set()
    for pat in [
        r"\b(?:class|interface|object|enum class|data class)\s+(\w+)",
        r"\bfun\s+(?:<[^>]*>\s*)?(\w+)",
        r"\b(?:val|var)\s+(\w+)",
        r"\btypealias\s+(\w+)",
    ]:
        syms.update(re.findall(pat, code))
    return syms


def file_imports(code: str) -> set:
    return {m.group(1).split(".")[-1] for m in re.finditer(r"^import\s+([\w.]+)$", code, re.M)}


def check_file(path: Path, import_map: dict) -> list:
    errors = []
    raw = path.read_text()
    code = clean(raw)
    pkg = file_package(path)
    imported = file_imports(raw)
    defined = defined_symbols(code)

    # 同包符号：收集同包下所有文件定义的顶层符号
    same_pkg_syms = set()
    if pkg:
        pkg_dir = SRC / pkg.replace("com.movieshell.", "").replace(".", "/")
        if pkg_dir.is_dir():
            for f in pkg_dir.glob("*.kt"):
                if f != path:
                    same_pkg_syms.update(defined_symbols(clean(f.read_text())))

    used = {m.group(1) for m in re.finditer(r"(?<![.\w])([A-Z][A-Za-z0-9_]*)\b", code)}
    for sym in sorted(used):
        if sym in AUTO or sym in imported or sym in defined or sym in same_pkg_syms:
            continue
        if sym in import_map:
            candidates = sorted(import_map[sym])
            errors.append(
                f"{path.name}: 疑似漏 import '{sym}'（仓库里现有写法："
                + candidates[0] + "）"
            )
    # 小写符号定向检查（只保留被真实构建失败证明过的项）：
    # - items/itemsIndexed：v0.82.0 MusicScreen 实锤漏 import 导致构建挂
    # - collectAsState：同类高频，一并盯
    # 注意：grid scope 里的 item(span=) 经 v0.81.0/v0.82.0 两次云构建证明无需显式 import，不查
    for fname, full_imp in {
        "items": "androidx.compose.foundation.lazy.items",
        "itemsIndexed": "androidx.compose.foundation.lazy.itemsIndexed",
        "collectAsState": "androidx.compose.runtime.collectAsState",
    }.items():
        if re.search(r"(?<![.\w])" + fname + r"\s*\(", code) and fname not in defined \
                and full_imp.split(".")[-1] not in imported:
            # 排除 receiver 链式调用：前面是 ) 或 } 或引号结尾不算（简化：只看 import 缺失且非局部定义）
            errors.append(
                f"{path.name}: 疑似漏 import '{fname}'（仓库里现有写法：{full_imp}）"
            )
    # @Composable 只能标 fun
    for m in re.finditer(r"@Composable\s*\n\s*(?:private|public|internal|protected)?\s*(val|var|class)\b", code):
        errors.append(f"{path.name}: @Composable 标在了 {m.group(1)} 上（只能标 fun）")
    return errors


def changed_files() -> list:
    out = subprocess.run(
        ["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True
    )
    files = []
    for line in out.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[-1].endswith(".kt"):
            p = ROOT / parts[-1]
            if p.is_file():
                files.append(p)
    return files


def main() -> int:
    targets = [Path(a) for a in sys.argv[1:]] or changed_files()
    targets = [t for t in targets if str(t).endswith(".kt") and t.is_file()]
    if not targets:
        print("没有 kt 文件需要检查")
        return 0
    import_map = build_import_map()
    all_errors = []
    for t in targets:
        all_errors.extend(check_file(t, import_map))
    if all_errors:
        print(f"发现 {len(all_errors)} 个问题，禁止 push：")
        for e in all_errors:
            print("  ✗ " + e)
        return 1
    print(f"通过：{len(targets)} 个文件 import 自查全绿")
    return 0


if __name__ == "__main__":
    sys.exit(main())
