package com.movieshell.core.net

/**
 * v1.1.6 分层第 2 批：抓取结果（正文 + 落地 URL，供登录校验判断重定向）。
 * 原 BookRuleEngine.PageResult——正文/目录/搜索三条链共用，JS 引擎（core/js）与
 * CSS 引擎（data/novel）都要它，放 core/net 当共享抓取结果类型。
 */
data class PageResult(val body: String, val url: String)
