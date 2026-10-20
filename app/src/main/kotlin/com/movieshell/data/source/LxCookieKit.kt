package com.movieshell.data.source

/**
 * v0.118.0：洛雪脚本 cookie 配置工具（轻便版）。
 *
 * 墨澜/聚合类脚本的约定：cookie 声明住在脚本头 `/*! ... */` 注释块里，
 * 形如 ` * @tx_cookie <值>`、` * @wy_cookie <值>`，脚本用
 * `currentScriptInfo.rawScript` 读出头部再解析（见引擎 shim 注入）。
 * App 扫描 `@<key>_cookie` 声明动态生成配置框；保存时写回头部注释，
 * 脚本按原逻辑读取——日志会自报「cookie: 已配置」，无需额外通道。
 *
 * 远端（http/https）脚本不能就地改：调用方先下载转存本地（file://），
 * 再走本工具写 cookie。
 */
object LxCookieKit {

    /** 单个 cookie 槽：key 如 tx/wy/kw/kg/mg，current 为脚本里已填的值（可空）。 */
    data class CookieSlot(val key: String, val label: String, val current: String)

    /** 脚本头 `/*! ... */` 注释块（cookie 声明住在这里）。 */
    private val HEADER_BLOCK = Regex("""^/\*!(?:.|\n)+?\*/""")

    /** 头块内声明行： ` * @tx_cookie xxx` */
    private val DECL_LINE = Regex("""(?m)^\s*\*\s*@(\w+)_cookie\s*([^\r\n]*)""")

    /** 兜底：独立块注释声明 `/* @tx_cookie xxx */` */
    private val DECL_BLOCK = Regex("""/\*\s*@(\w+)_cookie\s*(.*?)\*/""")

    /** 兜底兜底：脚本正文里出现过的任何 @xxx_cookie 提及（如 parseHeader 的正则对） */
    private val ANY_DECL = Regex("""@(\w+)_cookie""")

    /** 扫描脚本全文，返回所有 cookie 声明（按 key 去重，头块优先）。 */
    fun scan(scriptText: String): List<CookieSlot> {
        val seen = LinkedHashMap<String, CookieSlot>()
        fun add(key: String, value: String) {
            val k = key.lowercase()
            if (k.isNotBlank() && k !in seen) {
                seen[k] = CookieSlot(k, labelOf(k), value.trim())
            }
        }
        val header = HEADER_BLOCK.find(scriptText)?.value.orEmpty()
        DECL_LINE.findAll(header).forEach { add(it.groupValues[1], it.groupValues[2]) }
        DECL_BLOCK.findAll(scriptText).forEach { add(it.groupValues[1], it.groupValues[2]) }
        // 用户还没填过声明时：从脚本正文的提及（如 parseHeader 正则对）反推支持哪些 key
        if (seen.isEmpty()) {
            ANY_DECL.findAll(scriptText).forEach { add(it.groupValues[1], "") }
        }
        return seen.values.toList()
    }

    /**
     * 把 value 写进脚本头部：头块内已有该 key 的声明行则替换值；
     * 没有则在头块首行后插入一行声明；连头块都没有则文件头前置独立注释行。
     * value 里的换行会被压掉（cookie 必须是单行）。
     */
    fun apply(scriptText: String, key: String, value: String): String {
        val k = key.lowercase()
        val clean = value.replace("\r", "").replace("\n", "").trim()
        val lineRe = Regex("""(?m)^(\s*\*\s*@${Regex.escape(k)}_cookie\s*)[^\r\n]*""")
        val header = HEADER_BLOCK.find(scriptText)
        if (header != null) {
            val block = header.value
            return if (lineRe.containsMatchIn(block)) {
                scriptText.replaceRange(
                    header.range,
                    lineRe.replace(block) { m -> m.groupValues[1] + clean },
                )
            } else {
                // 头块首行后插入新声明行
                val body = block.removeSuffix("*/").trimEnd()
                val nl = body.indexOf('\n')
                val patched = if (nl >= 0) {
                    body.substring(0, nl + 1) + " * @$k" + "_cookie $clean\n" +
                        body.substring(nl + 1) + "\n */"
                } else {
                    "$body\n * @$k" + "_cookie $clean\n */"
                }
                scriptText.replaceRange(header.range, patched)
            }
        }
        val blockRe = Regex("""/\*(\s*@${Regex.escape(k)}_cookie\s*).*?\*/""")
        return if (blockRe.containsMatchIn(scriptText)) {
            blockRe.replace(scriptText) { m -> "/*${m.groupValues[1]}$clean */" }
        } else {
            "/* @$k" + "_cookie $clean */\n$scriptText"
        }
    }

    private fun labelOf(key: String): String = when (key) {
        "tx", "qq" -> "QQ音乐"
        "wy", "wyy", "netease" -> "网易云音乐"
        "kw" -> "酷我音乐"
        "kg" -> "酷狗音乐"
        "mg" -> "咪咕音乐"
        else -> key
    }
}
