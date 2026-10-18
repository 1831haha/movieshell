package com.movieshell.data.novel

import kotlinx.serialization.Serializable

/**
 * v0.95.0 净化替换规则（对标阅读 3.0 净化引擎）。
 *
 * scope 取值：book=单本书 / source=单个书源 / all=全局。
 * target 为 scope!=all 时的目标（bookUrl 或书源 key）。
 */
@Serializable
data class PurifyRule(
    val name: String = "",
    val group: String = "",
    val enable: Boolean = true,
    val scope: String = "all",
    val target: String = "",
    val regex: String = "",
    val replacement: String = "",
)

/**
 * v0.95.0 独立净化引擎：正文拉取后统一过一遍替换规则，再进缓存/展示。
 * 纯函数，可单元测试；失败的单条规则跳过，不影响其他规则。
 */
object NovelPurifyEngine {

    /** 正文净化主入口。bookUrl/sourceKey 用于 scope 过滤（可空=不过滤）。 */
    fun purify(
        text: String,
        rules: List<PurifyRule>,
        bookUrl: String = "",
        sourceKey: String = "",
    ): String {
        var out = text
        for (r in rules) {
            if (!r.enable || r.regex.isBlank()) continue
            if (!scopeMatch(r, bookUrl, sourceKey)) continue
            out = runCatching { out.replace(Regex(r.regex), r.replacement) }
                .getOrDefault(out)
        }
        // 收尾：多余空行合并，段首缩进统一（与 BookRuleEngine.extractContent 格式对齐）
        return out.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n") { "　　$it" }
    }

    private fun scopeMatch(r: PurifyRule, bookUrl: String, sourceKey: String): Boolean =
        when (r.scope) {
            "book" -> r.target.isNotBlank() && r.target == bookUrl
            "source" -> r.target.isNotBlank() && r.target == sourceKey
            else -> true
        }

    /** 内置保守默认规则：只处理明显安全的版式问题，广告类默认关闭交由用户按需开启。 */
    fun defaultRules(): List<PurifyRule> = listOf(
        PurifyRule(
            name = "合并多余空行",
            group = "版式",
            regex = "\\n{3,}",
            replacement = "\n\n",
        ),
        PurifyRule(
            name = "去除重复章节标题行",
            group = "版式",
            // extractContent 以 \n\n 分段，重复标题行之间是双换行
            regex = "(?m)^(.{1,40})\n\n\\1$",
            replacement = "$1",
        ),
        PurifyRule(
            name = "去除站长推广语",
            group = "广告",
            regex = "(?m)^.{0,60}(最新章节|最快更新|无弹窗|手机用户请|请记住本站)[^\\n]*$",
            replacement = "",
        ),
    )
}
