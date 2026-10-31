package com.movieshell.data.source

/**
 * v0.140.0：drpy 规则 import 内联展开。
 *
 * 背景：V8 isolate 用 evaluateJavaScript 跑经典脚本，规则顶层的
 * `import './_lib.js'` 直接报 SyntaxError（Cannot use import statement outside
 * a module），整个规则引导失败。真机日志实证：x1.c 规则
 * import './_lib.cntvParse.js' 导致 engineCats 0 条、首页空白。
 *
 * 做法（尽力而为的经典脚本适配，不是完整模块加载器）：
 * - 相对路径 import（./ 开头，相对规则目录）→ 读文件、递归展开、内联进规则；
 * - 内联内容与规则本身的 `export` 前缀一律剥掉（经典脚本里 export 同样是
 *   SyntaxError）；
 * - 具名 import 的改名（{ b as c }）补 `const c = b;`；默认 import 按
 *   `export default` 的具名/匿名形态补绑定；命名空间 import（* as ns）
 *   只内联并记警告，不搭绑定；
 * - 找不到文件、循环引用、非相对路径 import → 删掉该语句并记 missing，
 *   引导不再死于 SyntaxError，缺符号时在调用处以 ReferenceError 呈现，
 *   日志里点名缺的文件。
 *
 * 启发式边界（与 warnIfLegacySyncRule 同类）：动态 import() 不动。
 * v0.247.0（F33）：注释/模板串里的 import/export 不再误展开——展开前
 * 先按词法切段（行注释、块注释、模板串含 ${} 嵌套；单/双引号串留在
 * 代码段以便匹配路径引号，靠行首锚定挡串内伪语句），
 * 正则只跑在代码段上；行首锚定保留作第二层。第 2 轮补正则字面量：
 * 合法脚本里正则内容中的 // 与块注释开头只可能出现在字符类里（类外的 /
 * 会先闭合正则），代码态遇 / 时按前一有效字符/关键词判是否起正则，
 * 是则整段消费（转义与字符类都跳过），类里的 // 与块注释开头不再误触注释态；
 * 判正则失败（如除法）只当普通字符、不跨行消费，误判的代价最多是
 * 一段内注释检测被抑制，且行首锚定仍是第二层，不产生误展开。
 * 条件后的正则语句（if (x) /re/）另做括号配平判断；非关键词括号后
 * 换行分隔即为新语句（ASI），斜杠起正则，同行即除法。关键词表含
 * throw/default/extends/instanceof，运算符表含展开符的点；完整语句
 * 关键词 break/continue/debugger（含带标号跳转）换行后的斜杠同样起
 * 正则——它们不可能是除法延续。保证口径（已按第 3 轮复核收窄）：
 * 对已覆盖的上下文及回归场景未发现误展开；本实现是启发式词法切段，
 * 不保证覆盖所有合法 JS 语法。仍可能漏展开的窄残留：条件里字符串
 * 含括号干扰配平；串/模板语句后另起一行的正则语句（前一有效字符为
 * 引号/反引号时暂按除法处理——引号后若按正则处理，误判的除法会反过
 * 来破坏 // 注释识别，那是新开回归口子，故不纳入）；除上表外的其他
 * 未列出位置。上述残留均需再叠加“字符类含 / 紧邻 / 或 * + 其后有行
 * 首 import”才会漏展开。
 * 规则目录外的 ../ 一律拒绝。
 */
object RuleImportExpander {

    data class Result(
        val js: String,
        val inlined: List<String>,
        val missing: List<String>,
        val warnings: List<String>,
    )

    /**
     * v0.247.0（F33）：行首锚定是第二层——真实 import/export 语句都在行首，
     * 锚定后规则字符串里出现 `import '...'` 字样不会被误改坏（误改字符串
     * 是重写类逻辑最忌讳的）；第一层是下面的词法切段，注释/字符串段
     * 根本不进正则。两层叠加前，块注释里行首的 import 会被误展开，
     * 还会占掉 visited 名额、导致后面真正的同文件 import 被当成“已内联”
     * 跳过（缺符号、调用处 ReferenceError）。
     */
    /** 副作用 import：import './x.js'; */
    private val sideEffectImport =
        Regex("""(?m)^[ \t]*import\s*['"](\./[^'"]+|../[^'"]+)['"]\s*;?""")

    /** from import：import Name / { a, b as c } / * as ns / Name, {...} from './x.js'; */
    private val fromImport =
        Regex("""(?m)^[ \t]*import\s+([\s\S]*?)\s+from\s*['"](\./[^'"]+|../[^'"]+|[^'"]+)['"]\s*;?""")

    /** 重导出：export * from './y.js'; / export { a } from './y.js'; */
    private val reExport =
        Regex("""(?m)^[ \t]*export\s+(?:\*\s*|(\{[^}]*\})\s*)from\s*['"](\./[^'"]+|../[^'"]+|[^'"]+)['"]\s*;?""")

    /** export { a, b as c };（非 from 形态） */
    private val exportList = Regex("""(?m)^[ \t]*export\s*\{([^}]*)\}\s*;?""")

    private class State(val resolve: (String) -> String?) {
        val visited = linkedSetOf<String>()
        val inlined = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var defaultCounter = 0
    }

    fun expand(js: String, resolve: (String) -> String?): Result {
        val st = State(resolve)
        // 规则本身也要剥 export（经典脚本里 export 同样是 SyntaxError）
        val out = stripExports(expandText(js, st), st).first
        return Result(out, st.inlined, st.missing, st.warnings)
    }

    /** 归一化相对路径：./x.js → x.js；../ 出规则目录 → null（拒绝）。 */
    private fun normalize(spec: String): String? {
        var s = spec.trim()
        while (s.startsWith("./")) s = s.substring(2)
        if (s.startsWith("../") || s == ".." || s.isBlank()) return null
        return s
    }

    /**
     * 只展开一段文本的 import/重导出（不剥 export）。
     * 剥离只在两个地方做：规则本身（expand 入口）和内联文件（inlineFile 内），
     * 否则内联文件的 export default 信息会在二次剥离里丢掉。
     */
    private fun expandText(text: String, st: State): String {
        var out = transformCode(text) { seg ->
            expandMatches(seg, sideEffectImport, st) { m ->
                inlineFile(m.groupValues[1], clause = null, st)
            }
        }
        out = transformCode(out) { seg ->
            expandMatches(seg, fromImport, st) { m ->
                inlineFile(m.groupValues[2], clause = m.groupValues[1], st)
            }
        }
        out = transformCode(out) { seg ->
            expandMatches(seg, reExport, st) { m ->
                val clause = m.groupValues[1].ifBlank { null }
                inlineFile(m.groupValues[2], clause = clause, st)
            }
        }
        return out
    }

    /** 对正则的全部匹配做拼接式替换（handler 返回替换文本）。只跑在代码段上。 */
    private fun expandMatches(
        text: String,
        regex: Regex,
        st: State,
        handler: (MatchResult) -> String,
    ): String {
        val sb = StringBuilder()
        var pos = 0
        for (m in regex.findAll(text)) {
            // 动态 import('...') 不进这两个正则（后无 from/引号直连），天然跳过
            sb.append(text, pos, m.range.first)
            sb.append(handler(m))
            pos = m.range.last + 1
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    /**
     * v0.247.0（F33）：词法切段——把文本切成（内容，是否代码）段。
     * 跟踪行注释、块注释、模板串（非代码）；单/双引号串留在代码段但
     * 仍跳过其内容以免误触注释。模板串里的 ${} 回到代码态，用栈记花括号
     * 深度，} 回到对应层模板串（嵌套模板串同样处理）。
     * 转义符跳下一个字符；正则字面量见下方 looksLikeRegexStart。
     */
    private fun splitCodeSegments(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val cur = StringBuilder()
        var curIsCode = true
        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(cur.toString() to curIsCode)
                cur.clear()
            }
        }
        fun switch(toCode: Boolean) {
            if (toCode != curIsCode) {
                flush()
                curIsCode = toCode
            }
        }
        var mode = 0 // 0 代码 1 行注释 2 块注释 3 单引号 4 双引号 5 模板串
        val interpStack = ArrayDeque<Int>()
        var braceDepth = 0
        var i = 0
        // v0.247.0（F33 第 2 轮）：正斜杠可能是正则起止——合法脚本里正则
        // 内容中的 // 与块注释开头只可能出现在字符类里（如方括号内的斜杠星号，类外的 / 会
        // 先闭合正则），因此在代码态遇到 / 时先判它是否起正则：是则整段
        // 消费（转义与字符类都跳过），类里的 // 与块注释开头不再误触注释态；
        // 判正则失败（如除法）则只当普通字符，不跨行消费。
        fun prevSignificantIndex(pos: Int): Int {
            var j = pos - 1
            while (j >= 0 && (text[j] == ' ' || text[j] == '\t' || text[j] == '\n' || text[j] == '\r')) j--
            return j
        }
        fun prevSignificantChar(pos: Int): Char? {
            val j = prevSignificantIndex(pos)
            return if (j >= 0) text[j] else null
        }
        fun prevWord(pos: Int): String {
            var j = pos - 1
            while (j >= 0 && (text[j] == ' ' || text[j] == '\t' || text[j] == '\n' || text[j] == '\r')) j--
            if (j < 0 || !(text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) return ""
            val end = j + 1
            while (j >= 0 && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j--
            return text.substring(j + 1, end)
        }
        /** 从 endExclusive 往回取一个单词，返回（单词，单词起点）；取不到返回（"", -1）。 */
        fun wordEndingAt(endExclusive: Int): Pair<String, Int> {
            var j = endExclusive - 1
            while (j >= 0 && (text[j] == ' ' || text[j] == '\t' || text[j] == '\n' || text[j] == '\r')) j--
            if (j < 0 || !(text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) return "" to -1
            val end = j + 1
            while (j >= 0 && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j--
            return text.substring(j + 1, end) to (j + 1)
        }
        fun tryConsumeRegex(start: Int): Int {
            // 返回正则字面量结尾下标（含闭合 /），不是正则返回 -1。
            var k = start + 1
            while (k < text.length) {
                val ch = text[k]
                if (ch == '\n' || ch == '\r') return -1
                if (ch == '\\') {
                    k += 2
                    continue
                }
                if (ch == '[') {
                    k++
                    while (k < text.length) {
                        if (text[k] == '\\') {
                            k += 2
                            continue
                        }
                        if (text[k] == ']') break
                        if (text[k] == '\n' || text[k] == '\r') return -1
                        k++
                    }
                    if (k >= text.length) return -1
                    k++
                    continue
                }
                if (ch == '/') return k
                k++
            }
            return -1
        }
        fun looksLikeRegexStart(pos: Int): Boolean {
            val prev = prevSignificantChar(pos) ?: return true
            if (prev == '(' || prev == ',' || prev == '=' || prev == ':' ||
                prev == '[' || prev == '!' || prev == '&' || prev == '|' ||
                prev == '?' || prev == '{' || prev == '}' || prev == ';' ||
                prev == '+' || prev == '-' || prev == '*' || prev == '/' ||
                prev == '%' || prev == '^' || prev == '~' || prev == '<' ||
                prev == '>' || prev == '.'
            ) return true
            // 第 2 轮补：) 之后可能是条件后的正则语句（if (x) /re/.test()），
            // 也可能是除法（f(x) / 2）——往回配平括号，看 ( 之前是不是
            // if/for/while/switch/catch/with；不是关键词时，换行分隔即为
            // 新语句（ASI），斜杠起正则，同行即除法；配不平按除法处理。
            if (prev == ')') {
                val closeIdx = prevSignificantIndex(pos)
                var depth = 0
                var j = pos - 1
                var matchedOpen = -1
                while (j >= 0) {
                    when (text[j]) {
                        ')' -> depth++
                        '(' -> {
                            depth--
                            if (depth == 0) {
                                matchedOpen = j
                                break
                            }
                        }
                    }
                    j--
                }
                if (matchedOpen >= 0) {
                    var k = matchedOpen - 1
                    while (k >= 0 && (text[k] == ' ' || text[k] == '\t' || text[k] == '\n' || text[k] == '\r')) k--
                    if (k >= 0) {
                        val end = k + 1
                        while (k >= 0 && (text[k].isLetterOrDigit() || text[k] == '_' || text[k] == '$')) k--
                        if (text.substring(k + 1, end) in setOf(
                                "if", "for", "while", "switch", "catch", "with",
                            )
                        ) return true
                    }
                } else {
                    return false
                }
                if (closeIdx >= 0) {
                    for (t in closeIdx + 1 until pos) {
                        if (text[t] == '\n' || text[t] == '\r') return true
                    }
                }
                return false
            }
            val prevWordValue = prevWord(pos)
            if (prevWordValue in setOf(
                    "return", "throw", "typeof", "case", "delete", "void",
                    "in", "of", "new", "await", "yield", "else", "do",
                    "default", "extends", "instanceof",
                    // 第 3 轮补：break/continue/debugger 是完整语句，换行后
                    // 的斜杠只能是新语句的正则（ASI），不可能是除法延续。
                    "break", "continue", "debugger",
                )
            ) return true
            // 带标号的 break/continue：前一单词是标号时，再往前一个单词
            // 是 break/continue 即为标号跳转语句，换行后的斜杠同样起正则。
            if (prevWordValue.isNotEmpty()) {
                val (_, labelStart) = wordEndingAt(pos)
                if (labelStart >= 0) {
                    val (beforeLabel, _) = wordEndingAt(labelStart)
                    if (beforeLabel == "break" || beforeLabel == "continue") return true
                }
            }
            return false
        }
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when (mode) {
                0 -> when {
                    c == '/' && next == '/' -> {
                        switch(false); cur.append(c); cur.append(next); i += 2; mode = 1
                    }
                    c == '/' && next == '*' -> {
                        switch(false); cur.append(c); cur.append(next); i += 2; mode = 2
                    }
                    c == '/' && looksLikeRegexStart(i) -> {
                        val end = tryConsumeRegex(i)
                        if (end >= 0) {
                            // 正则整段仍是代码段，只是不让其中内容误触注释态。
                            cur.append(text, i, end + 1); i = end + 1
                        } else {
                            cur.append(c); i++
                        }
                    }
                    // 单/双引号串留在代码段：import/export 路径本身就在引号里，
                    // 切成非代码段会导致 from './a.js' 对不齐正则。行首锚定已挡住
                    // 串内容里的伪 import（合法 JS 单/双引号不能跨行）。仍跟踪
                    // 串态，避免串内 // /* ` 误切注释/模板。
                    c == '\'' -> { cur.append(c); i++; mode = 3 }
                    c == '"' -> { cur.append(c); i++; mode = 4 }
                    c == '`' -> { switch(false); cur.append(c); i++; mode = 5 }
                    c == '{' -> {
                        if (interpStack.isNotEmpty()) braceDepth++
                        cur.append(c); i++
                    }
                    c == '}' && interpStack.isNotEmpty() -> {
                        cur.append(c); i++
                        if (braceDepth == interpStack.last()) {
                            interpStack.removeLast()
                            // 回到模板串：段也切回非代码（嵌套模板串后
                            // 的外层模板尾巴不得误标为代码段）。
                            switch(false); mode = 5
                        } else braceDepth--
                    }
                    else -> { cur.append(c); i++ }
                }
                1 -> {
                    cur.append(c); i++
                    if (c == '\n') { switch(true); mode = 0 }
                }
                2 -> {
                    cur.append(c); i++
                    if (c == '*' && next == '/') { cur.append(next); i++; switch(true); mode = 0 }
                }
                3, 4 -> {
                    // 串内容仍属代码段（进入时未 switch(false)），只维护串态。
                    val quote = if (mode == 3) '\'' else '"'
                    cur.append(c); i++
                    if (c == '\\' && i < text.length) { cur.append(text[i]); i++ }
                    else if (c == quote) { mode = 0 }
                }
                else -> { // 模板串
                    cur.append(c); i++
                    if (c == '\\' && i < text.length) { cur.append(text[i]); i++ }
                    else if (c == '`') { switch(true); mode = 0 }
                    else if (c == '$' && next == '{') {
                        cur.append(next); i++
                        interpStack.addLast(braceDepth)
                        // 进插值表达式：段切回代码（表达式里写不成
                        // import/export 语句，标代码段是安全的）。
                        switch(true); mode = 0
                    }
                }
            }
        }
        flush()
        return out
    }

    /** 只对代码段跑 transform，非代码段原样拼回。 */
    private fun transformCode(text: String, transform: (String) -> String): String {
        val sb = StringBuilder()
        for ((seg, isCode) in splitCodeSegments(text)) {
            sb.append(if (isCode) transform(seg) else seg)
        }
        return sb.toString()
    }

    /**
     * 内联一个 import 的文件内容。clause 为 import 子句（null = 副作用 import；
     * reExport 时为 { ... } 列表或 null）。
     */
    private fun inlineFile(specRaw: String, clause: String?, st: State): String {
        val spec = normalize(specRaw)
            ?: return "// [import] 超出规则目录，已跳过：$specRaw".also {
                st.missing += specRaw
            }
        if (spec in st.visited) {
            return "// [import] 已内联过，跳过：$specRaw"
        }
        val src = st.resolve(spec)
        if (src == null) {
            st.missing += specRaw
            return "// [import] 找不到文件，已跳过：$specRaw（请放进 xiaying/js/）"
        }
        st.visited += spec
        st.inlined += spec
        val (stripped, defaultName) = stripExports(expandText(src, st), st)
        val sb = StringBuilder()
        sb.append("\n// [import] 内联自 $specRaw\n")
        sb.append(stripped)
        if (clause != null) {
            val parsed = parseClause(clause, st, specRaw)
            sb.append(parsed)
            val defaultBinding = parsedDefaultBinding(clause)
            if (defaultBinding != null) {
                if (defaultName != null) {
                    sb.append("\nconst $defaultBinding = $defaultName;")
                } else {
                    st.warnings += "默认 import 无对应 export default：$specRaw"
                }
            }
        }
        sb.append("\n")
        return sb.toString()
    }

    /** 解析 import 子句里的具名部分，返回改名绑定代码（同名无需绑定）。 */
    private fun parseClause(clause: String, st: State, specRaw: String): String {
        val sb = StringBuilder()
        val brace = Regex("""\{([^}]*)\}""").find(clause)?.groupValues?.get(1)
        if (brace != null) {
            brace.split(",").forEach { raw ->
                val parts = raw.trim().split(Regex("""\s+as\s+"""))
                if (parts.size == 2) {
                    sb.append("\nconst ${parts[1].trim()} = ${parts[0].trim()};")
                }
            }
        }
        if (clause.trimStart().startsWith("*")) {
            val ns = Regex("""\*\s+as\s+(\w+)""").find(clause)?.groupValues?.get(1)
            st.warnings += "命名空间 import（* as $ns）不搭绑定，${ns}.x 可能失败：$specRaw"
        }
        return sb.toString()
    }

    /** import 子句里的默认绑定名（import Name / import Name, {...}），无则 null。 */
    private fun parsedDefaultBinding(clause: String): String? {
        val head = clause.trimStart().substringBefore("{").substringBefore("*").trim().trimEnd(',')
        if (head.isBlank()) return null
        val name = head.split(",").first().trim()
        return if (name.matches(Regex("""[A-Za-z_$][\w$]*"""))) name else null
    }

    /**
     * 剥一段文本的 export 前缀。返回（剥后文本，默认导出名）。
     * 默认导出名：具名 function/class 的名字，或匿名形态生成的 const 名。
     * v0.247.0（F33）：按代码段逐段剥，非代码段不动；默认导出只认第一处
     * （双默认导出本就是非法脚本，段内优先与整文优先的差异只影响非法文件）。
     */
    private fun stripExports(src: String, st: State): Pair<String, String?> {
        var defaultName: String? = null
        val sb = StringBuilder()
        for ((seg, isCode) in splitCodeSegments(src)) {
            if (!isCode) {
                sb.append(seg)
                continue
            }
            var out = seg
            if (defaultName == null) {
                // 具名默认导出：export default function foo / class Foo → 去前缀，名字即绑定
                val namedDefault =
                    Regex("""(?m)^[ \t]*export\s+default\s+(?:async\s+function|function|class)\s+([A-Za-z_$][\w$]*)""")
                namedDefault.find(out)?.let { m ->
                    defaultName = m.groupValues[1]
                    out = out.replaceRange(m.range, m.value.replace(Regex("""^[ \t]*export\s+default\s+"""), ""))
                }
            }
            // 其余默认导出：export default <expr>; → const __exp_default_N = <expr>;
            // 只换前缀、原文分号保留，函数/对象字面量里的分号不受影响
            if (defaultName == null) {
                val anonDefault = Regex("""(?m)^[ \t]*export\s+default\s+""")
                anonDefault.find(out)?.let { m ->
                    defaultName = "__exp_default_${st.defaultCounter++}"
                    out = out.replaceRange(m.range, "const $defaultName = ")
                }
            }
            // 普通导出：export function/const/... → 去 export
            out = Regex("""(?m)^[ \t]*export\s+(async\s+function|function|class|const|let|var)\s+""")
                .replace(out, "$1 ")
            // 导出列表：export { a, b as c }; → 改名补 const，同名无需
            out = exportList.replace(out) { m ->
                val aliases = m.groupValues[1].split(",").mapNotNull { raw ->
                    val parts = raw.trim().split(Regex("""\s+as\s+"""))
                    if (parts.size == 2) "const ${parts[1].trim()} = ${parts[0].trim()};" else null
                }
                aliases.joinToString("\n")
            }
            sb.append(out)
        }
        return sb.toString() to defaultName
    }
}
