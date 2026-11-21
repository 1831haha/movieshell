#!/usr/bin/env python3
"""TechDialog 嵌套滚动门禁（2026-11-21 v1.4.5 实证，审查后两处补强）。

背景：TechDialog 内容区默认 scrollable=true，内容包在 verticalScroll 的
Column 里；若弹窗内容本身又是 verticalScroll 的 Column / LazyColumn，
内层滚动组件会被外层以无限高测量，直接抛
IllegalStateException: Vertically scrollable component was measured with an
infinity maximum height constraints，打开弹窗即闪退。
2026-11-21 真机：主题「自定义配色」一点即崩；同批扫出音乐「播放界面」
与音乐源 Cookie 配置（有 Cookie 项时）两个同类地雷。

判定口径（一个滚动组件“有界”当且仅当满足其一）：
  1. 自带限高：自己的 Modifier 链上有 .height( / .heightIn(——
     verticalScroll 看关键字之前的链；LazyColumn/LazyVerticalGrid 看其
     调用参数里的 modifier 链（写在关键字后面）；
  2. 包住它的布局自带限高：外层 Box/Column/Row 的参数里有
     .height( / .heightIn(；
  3. 外层不是滚动容器：TechDialog 传了 scrollable = false，且内容里
     没有包住它的另一个滚动组件（滚动套滚动仍会崩，豁免只免外层那一层）。

已知盲区：内容抽成独立 @Composable 函数的，脚本看不到函数体内部的滚动
组件（只扫了一层直接调用）；更深的调用链（弹窗 → A → B，滚动在 B 里）
与变量传递的 content 也没扫；新增这类弹窗时须人工核对。
用法：不带参数查 git 改动过的 kt 文件；--all 全量扫；也可传具体文件。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCROLLABLES = ("LazyColumn(", "LazyVerticalGrid(", "verticalScroll(")
LAYOUTS = ("Box(", "Column(", "Row(", "LazyColumn(", "LazyVerticalGrid(", "LazyRow(")


def strip_strings_comments(src: str) -> str:
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            if j == -1:
                j = n
            out.append(" " * (j - i))
            i = j
        elif src.startswith("/*", i):
            # Kotlin 块注释可嵌套：按深度剥离（与 check_braces.py 同口径）。
            depth = 0
            j = i
            while j < n:
                if src.startswith("/*", j):
                    depth += 1
                    j += 2
                elif src.startswith("*/", j):
                    depth -= 1
                    j += 2
                    if depth == 0:
                        break
                else:
                    j += 1
            out.append(" " * (j - i))
            i = j
        elif src[i] == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                if j == -1:
                    j = n
                else:
                    j += 3
                out.append(" " * (j - i))
                i = j
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
                j = min(j + 1, n)
                out.append(" " * (j - i))
                i = j
        elif src[i] == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 1
                j += 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def balanced(src: str, i: int, open_c: str, close_c: str) -> int:
    depth = 0
    j = i
    while j < len(src):
        c = src[j]
        if c == open_c:
            depth += 1
        elif c == close_c:
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    return len(src)


def call_extent(src: str, start: int) -> tuple:
    """返回 (args_start, args_end, full_end)：args 为括号内文本范围，
    full 含尾随 lambda。"""
    i = src.index("(", start)
    paren_end = balanced(src, i, "(", ")")
    end = paren_end
    k = end
    while k < len(src) and src[k] in " \t\r\n":
        k += 1
    if k < len(src) and src[k] == "{":
        end = balanced(src, k, "{", "}")
    return i, paren_end, end


def extract_call(src: str, start: int) -> str:
    _, _, end = call_extent(src, start)
    return src[start:end]


def chain_has_height(chain: str) -> bool:
    return ".height(" in chain or ".heightIn(" in chain


def _top_level_split(args: str) -> list:
    """按顶层逗号切分调用参数（括号/花括号/方括号内逗号不切）。"""
    parts, depth, cur = [], 0, []
    for c in args:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    parts.append("".join(cur))
    return parts


def own_height(call: str, kw: str, kw_pos: int) -> bool:
    """滚动组件自己 Modifier 链上的限高。"""
    if kw == "verticalScroll(":
        head = call[:kw_pos]
        m = head.rfind("Modifier")
        if m == -1:
            return False
        chain = head[m:kw_pos]
        if "{" in chain or ";" in chain:
            return False
        return chain_has_height(chain)
    # LazyColumn/LazyVerticalGrid：modifier 参数写在关键字后面
    args_start, paren_end, _ = call_extent(call, kw_pos)
    args = call[args_start + 1:paren_end - 1]
    for part in _top_level_split(args):
        part = part.strip()
        if part.startswith("modifier"):
            eq = part.find("=")
            if eq != -1 and chain_has_height(part[eq + 1:]):
                return True
        elif part.startswith("Modifier"):
            if chain_has_height(part):
                return True
    return False


def enclosing_invocations(call: str, kw_pos: int) -> list:
    """包住该滚动组件的所有布局调用：(args_text, args_start)。"""
    found = []
    head = call[:kw_pos]
    for kind in LAYOUTS:
        for mm in re.finditer(re.escape(kind), head):
            _, paren_end, full_end = call_extent(call, mm.start())
            if full_end > kw_pos:
                args_start = call.index("(", mm.start())
                found.append((call[args_start:paren_end], args_start))
    return found


def scrollable_is_bounded(call: str, kw: str, kw_pos: int, outer_scroll: bool) -> bool:
    if own_height(call, kw, kw_pos):
        return True
    enclosing = enclosing_invocations(call, kw_pos)
    for args_text, _ in enclosing:
        brace = args_text.find("{")
        if brace != -1:
            args_text = args_text[:brace]
        if chain_has_height(args_text):
            return True
    if not outer_scroll:
        # 外层不滚动时，顶层滚动组件有界；但内容里还有一层滚动包住它时仍会崩。
        for args_text, args_start in enclosing:
            for skw in SCROLLABLES:
                for sm in re.finditer(re.escape(skw), args_text):
                    if args_start + sm.start() != kw_pos:
                        return False
        return True
    return False


def check_file(path: Path) -> list:
    raw = path.read_text(encoding="utf-8")
    src = strip_strings_comments(raw)
    errors = []
    for m in re.finditer(r"\bTechDialog\s*\(", src):
        # 跳过 TechDialog 本体的 fun 声明
        before = src[max(0, m.start() - 8):m.start()]
        if before.rstrip().endswith("fun"):
            continue
        call = extract_call(src, m.start())
        line = raw[:m.start()].count("\n") + 1
        # 参数区（第一个右括号之前）传了 scrollable = false：只免外层滚动，
        # 内容内部仍按同一规则查（滚动套滚动仍会崩）。
        args_start, paren_end, _ = call_extent(call, 0)
        outer_scroll = "scrollable = false" not in call[args_start:paren_end]
        for kw in SCROLLABLES:
            for km in re.finditer(re.escape(kw), call):
                if not scrollable_is_bounded(call, kw, km.start(), outer_scroll):
                    try:
                        shown = path.relative_to(ROOT)
                    except ValueError:
                        shown = path
                    errors.append(
                        f"{shown}:{line}: "
                        f"TechDialog 内容含未限高的 {kw[:-1]}（滚动套滚动，打开即闪退；"
                        f"传 scrollable = false 或给内层滚动加 height/heightIn）"
                    )
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
    args = sys.argv[1:]
    if "--all" in args:
        targets = sorted((ROOT / "app/src/main/kotlin").rglob("*.kt"))
    else:
        targets = [Path(a) for a in args] or changed_files()
    targets = [t for t in targets if str(t).endswith(".kt") and t.is_file()]
    if not targets:
        print("没有 kt 文件需要检查")
        return 0
    all_errors = []
    for t in targets:
        all_errors.extend(check_file(t))
    if all_errors:
        print(f"发现 {len(all_errors)} 个问题，禁止 push：")
        for e in all_errors:
            print("  ✗ " + e)
        return 1
    print(f"通过：{len(targets)} 个文件 TechDialog 嵌套滚动自查全绿")
    return 0


if __name__ == "__main__":
    sys.exit(main())
