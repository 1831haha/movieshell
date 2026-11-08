#!/usr/bin/env python3
"""
分层门禁：拦住两条分层红线（v1.1.9 第 4 批新增）

1. 删空的 data/repo 包不得再有任何引用——import 行与行内全限定引用都算。
   原任务书写的 "ui/ 禁止 import data.* 实现类"，按字面执行不了：
   现成架构里 ui/ 文件 import com.movieshell.data.* 共有 273 处，逐个改是
   顺手重构（超出本批"只搬包名"范围），不可能 0 违规。
   故规则收窄为只禁已删空的 data/repo（本批删空的包，残留引用即违规）；
   273 处既有 ui->data 引用待以后分层批次再收。
2. 全仓禁止直接构造 OkHttpClient（字面 `OkHttpClient(`）——全 App 的
   client 统一走 core/net/HttpClient；排除 HttpClient.kt 自身的唯一合法
   构造点，以及注释/字符串里的字面出现（只查代码）。

用法：python3 tools/check_layers.py [file1.kt file2.kt ...]
不带参数则全仓扫描 app/src 下全部 kt 文件。
注意：不像 check_imports.py 那样默认只查 git 改动文件——CI 里刚 checkout
出来的工作区是干净的，git status 没有改动，改动文件模式在 CI 里等于
什么都不查，门禁就形同虚设；所以本脚本默认全仓扫。
返回码：0=通过，1=有违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src"

DELETED_PKG = "com.movieshell.data.repo"
# 唯一合法的 OkHttpClient 直接构造点（目前该文件里其实是 Builder 构造，
# 仍按任务书排除在外，防以后有人在这里加直接构造时被误报）
HTTP_CLIENT = SRC / "main/kotlin/com/movieshell/core/net/HttpClient.kt"

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


def check_file(path: Path) -> list:
    errors = []
    raw = path.read_text()
    code = clean(raw)
    rel = path.relative_to(ROOT)
    for i, line in enumerate(code.splitlines(), 1):
        if DELETED_PKG in line:
            errors.append(f"{rel}:{i}: 仍引用已删空的 data/repo 包：{line.strip()}")
        if path.resolve() != HTTP_CLIENT.resolve() and "OkHttpClient(" in line:
            errors.append(f"{rel}:{i}: 禁止直接构造 OkHttpClient，统一走 core/net/HttpClient：{line.strip()}")
    return errors


def main() -> int:
    targets = [Path(a) for a in sys.argv[1:]]
    if targets:
        targets = [t for t in targets if str(t).endswith(".kt") and Path(t).is_file()]
    else:
        targets = sorted(SRC.rglob("*.kt"))
    if not targets:
        print("没有 kt 文件需要检查")
        return 0
    all_errors = []
    for t in targets:
        all_errors.extend(check_file(t))
    if all_errors:
        print(f"发现 {len(all_errors)} 处分层违规，禁止 push：")
        for e in all_errors:
            print("  ✗ " + e)
        return 1
    print(f"通过：{len(targets)} 个文件分层门禁全绿")
    return 0


if __name__ == "__main__":
    sys.exit(main())
