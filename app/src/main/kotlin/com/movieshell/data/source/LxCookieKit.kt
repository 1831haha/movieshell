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
        // v0.217.0（F36）：正文提及的 key 也并入候选——原来只在头块一个 key
        // 都没有时才反推，头块只声明一个平台时正文提到的其他平台填不了；
        // add() 本就“先到先得”，头块已有的值优先，不会被正文空值覆盖。
        ANY_DECL.findAll(scriptText).forEach { add(it.groupValues[1], "") }
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

    /** v0.123.0：平台名映射收敛到 MusicApi.platformLabel 一处真源，这里只兜底原样返回。 */
    private fun labelOf(key: String): String =
        com.movieshell.data.repo.MusicApi.platformLabel(key) ?: key

    /**
     * v0.123.0：从脚本文本嗅探提到的平台（sources 定义里的 'tx': {...} 之类键、
     * EVENT 上报的 source 字段），返回中文名列表。Cookie 弹窗扫到 0 个声明时展示，
     * 让用户至少知道这个脚本覆盖哪些平台，而不是面对一句"未声明"摸不着头脑。
     */
    fun sniffPlatforms(scriptText: String): List<String> =
        listOf("tx", "wy", "kg", "kw", "mg").filter { c ->
            Regex("""['"]$c['"]\s*:""").containsMatchIn(scriptText) ||
                Regex("""source\s*[:=]\s*['"]$c['"]""").containsMatchIn(scriptText)
        }.map { labelOf(it) }
}
