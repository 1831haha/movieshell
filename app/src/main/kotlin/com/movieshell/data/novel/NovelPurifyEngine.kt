package com.movieshell.data.novel

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

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
    /** v0.102.0：出厂内置规则标记。老数据无此字段时按 false 解码（有默认值）。 */
    val builtin: Boolean = false,
)

/**
 * v0.95.0 独立净化引擎：正文拉取后统一过一遍替换规则，再进缓存/展示。
 * 纯函数，可单元测试；失败的单条规则跳过，不影响其他规则。
 */
object NovelPurifyEngine {

    /** v0.102.0 P2-2：正则预编译缓存。Kotlin Regex 底层 Pattern 线程安全，可跨规则/跨调用共享。 */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    private fun regexOf(pattern: String): Regex =
        regexCache.getOrPut(pattern) { Regex(pattern) }

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
            // 正则写错时 regexOf 抛异常，被 runCatching 吞掉且不进缓存，不影响其他规则
            out = runCatching { out.replace(regexOf(r.regex), r.replacement) }
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
            // v0.217.0（F47）：fail-closed——原来 else 全放行，scope 写错
            // 一个字母（或外来规则带了未知 scope）就从单书／单源变成全局，
            // 悄悄改掉所有书的正文；只有明确的 all 才全局，未知 scope 不生效。
            "all" -> true
            else -> false
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

    /**
     * v0.102.0 P2-3：内置净化规则库（默认全部开启，builtin=true）。
     * 由 PurifyRuleStore 首次运行时补种，不覆盖用户自定义规则。
     * 正则都锚定行首关键词/行尾，宁可漏杀、不误杀正文。
     */
    fun builtinRules(): List<PurifyRule> = listOf(
        PurifyRule(
            name = "去除断章提示",
            group = "内置",
            builtin = true,
            regex = "(?m)^.{0,80}((本章未完|未完待续).{0,20}(下一页|继续阅读)|点击下一页继续阅读)[^\\n]*$",
            replacement = "",
        ),
        PurifyRule(
            name = "去除求票文案",
            group = "内置",
            builtin = true,
            regex = "(?m)^.{0,80}(喜欢本书(请|的话|就)|求收藏|求推荐|求月票|求订阅|投推荐票|请收藏本书)[^\\n]*$",
            replacement = "",
        ),
        PurifyRule(
            name = "去除推荐阅读",
            group = "内置",
            builtin = true,
            regex = "(?m)^.{0,40}(推荐阅读|相关推荐|猜你喜欢|延伸阅读)[：:][^\\n]*$",
            replacement = "",
        ),
        PurifyRule(
            name = "剥离残留 HTML 标签",
            group = "内置",
            builtin = true,
            // 标签名必须以字母 / ! 开头，避免误杀 "3 < 5" 这类数学比较
            regex = "</?[a-zA-Z!][^<>\\n]{0,119}>",
            replacement = "",
        ),
    )
}
