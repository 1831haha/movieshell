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
 * 启发式边界（与 warnIfLegacySyncRule 同类）：import 写在注释/字符串里
 * 会被误展开；动态 import() 不动。规则目录外的 ../ 一律拒绝。
 */
object RuleImportExpander {

    data class Result(
        val js: String,
        val inlined: List<String>,
        val missing: List<String>,
        val warnings: List<String>,
    )

    /**
     * 行首锚定：真实 import/export 语句都在行首；锚定后规则字符串里
     * 出现 `import '...'` 字样不会被误改坏（误改字符串是重写类逻辑最忌讳的）。
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
        var out = expandMatches(text, sideEffectImport, st) { m ->
            inlineFile(m.groupValues[1], clause = null, st)
        }
        out = expandMatches(out, fromImport, st) { m ->
            inlineFile(m.groupValues[2], clause = m.groupValues[1], st)
        }
        out = expandMatches(out, reExport, st) { m ->
            val clause = m.groupValues[1].ifBlank { null }
            inlineFile(m.groupValues[2], clause = clause, st)
        }
        return out
    }

    /** 对正则的全部匹配做拼接式替换（handler 返回替换文本）。 */
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
     */
    private fun stripExports(src: String, st: State): Pair<String, String?> {
        var out = src
        var defaultName: String? = null
        // 具名默认导出：export default function foo / class Foo → 去前缀，名字即绑定
        val namedDefault =
            Regex("""(?m)^[ \t]*export\s+default\s+(?:async\s+function|function|class)\s+([A-Za-z_$][\w$]*)""")
        namedDefault.find(out)?.let { m ->
            defaultName = m.groupValues[1]
            out = out.replaceRange(m.range, m.value.replace(Regex("""^[ \t]*export\s+default\s+"""), ""))
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
        return out to defaultName
    }
}
