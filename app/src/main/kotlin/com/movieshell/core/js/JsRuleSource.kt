package com.movieshell.core.js

/**
 * v1.1.6 分层第 2 批：JS 规则引擎的源配置（纯数据，不含业务类）。
 *
 * LegadoJsEngine 搬进 core/js 后不能再直接拿 data 层的 BookSource
 * （core 不依赖业务），只取它求值规则要用的 18 个字符串字段；
 * 数据层用 BookSource.toJsRuleSource() 映射（见 data/novel/JsRuleSourceMapper）。
 * 字段名与 BookSource 逐字对应，改 BookSource 字段时同步改这里。
 */
data class JsRuleSource(
    val key: String = "",
    val headerUa: String = "",
    val jsLib: String = "",
    val loginCheckJs: String = "",
    val searchUrl: String = "",
    val ruleSearchList: String = "",
    val ruleSearchName: String = "",
    val ruleSearchAuthor: String = "",
    val ruleSearchBookUrl: String = "",
    val ruleSearchCover: String = "",
    val ruleTocList: String = "",
    val ruleTocTitle: String = "",
    val ruleTocUrl: String = "",
    val ruleTocNext: String = "",
    val ruleContent: String = "",
    val ruleContentReplace: String = "",
    val ruleContentNext: String = "",
)
