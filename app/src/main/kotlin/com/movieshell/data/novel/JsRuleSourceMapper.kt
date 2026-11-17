package com.movieshell.data.novel

import com.movieshell.core.js.JsRuleSource
import com.movieshell.domain.model.BookSource

/**
 * v1.1.6 分层第 2 批：BookSource → JS 引擎的纯配置（字段逐字对应，见 JsRuleSource）。
 * core/js 不能直接拿 BookSource，映射留在数据层。
 */
internal fun BookSource.toJsRuleSource(): JsRuleSource = JsRuleSource(
    key = key,
    headerUa = headerUa,
    jsLib = jsLib,
    loginCheckJs = loginCheckJs,
    searchUrl = searchUrl,
    ruleSearchList = ruleSearchList,
    ruleSearchName = ruleSearchName,
    ruleSearchAuthor = ruleSearchAuthor,
    ruleSearchBookUrl = ruleSearchBookUrl,
    ruleSearchCover = ruleSearchCover,
    ruleTocList = ruleTocList,
    ruleTocTitle = ruleTocTitle,
    ruleTocUrl = ruleTocUrl,
    ruleTocNext = ruleTocNext,
    ruleContent = ruleContent,
    ruleContentReplace = ruleContentReplace,
    ruleContentNext = ruleContentNext,
    bookSourceComment = bookSourceComment,
)
