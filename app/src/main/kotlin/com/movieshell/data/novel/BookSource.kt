package com.movieshell.data.novel

import org.json.JSONObject

/**
 * v0.61.0 在线书源模型，兼容"阅读"App 的 bookSource.json 主流字段。
 *
 * 规则写法（CSS 子集，见 BookRuleEngine）：
 * - 列表规则：CSS 选择器，如 "div.book-item"
 * - 字段规则："选择器@属性"，如 "a.title@text"、"a.title@href"、"img@src"
 * - searchUrl 里用 {{key}} 占位关键词（自动 URL 编码）
 */
data class BookSource(
    val name: String = "",
    val url: String = "",
    val group: String = "",
    val enabled: Boolean = true,
    val searchUrl: String = "",
    val ruleSearchList: String = "",
    val ruleSearchName: String = "",
    val ruleSearchAuthor: String = "",
    val ruleSearchCover: String = "",
    val ruleSearchBookUrl: String = "",
    val ruleBookName: String = "",
    val ruleBookAuthor: String = "",
    val ruleBookIntro: String = "",
    val ruleBookCover: String = "",
    val ruleBookTocUrl: String = "",
    val ruleTocList: String = "",
    val ruleTocTitle: String = "",
    val ruleTocUrl: String = "",
    val ruleContent: String = "",
    /** v0.198.0：正文替换规则（阅读式 replaceRegex，每行一条，行内用 ## 分隔正则与替换）。 */
    val ruleContentReplace: String = "",
    /** v0.199.0：登录校验 JS（阅读式 loginCheckJs，空表示不需要）。 */
    val loginCheckJs: String = "",
    val loginUrl: String = "",
    /** v0.199.0：目录分页规则（ruleToc.nextTocUrl）。 */
    val ruleTocNext: String = "",
    /** v0.199.0：正文分页规则（ruleContent.nextContentUrl）。 */
    val ruleContentNext: String = "",
    /** v0.199.0：源自定义 UA（header.User-Agent，空则用默认）。 */
    val headerUa: String = "",
    /** v0.200.0：共享 JS 库（阅读式 jsLib，内联代码；URL 形式暂不支持）。 */
    val jsLib: String = "",
    /** v0.201.0：发现页分类（阅读式 exploreUrl：分类 JSON 数组或对象）与分类列表规则。 */
    val exploreUrl: String = "",
    val ruleExploreList: String = "",
    val ruleExploreName: String = "",
    val ruleExploreAuthor: String = "",
    val ruleExploreBookUrl: String = "",
    val ruleExploreCover: String = "",
    val ruleExploreIntro: String = "",
) {
    /** 书源唯一标识：url 去协议去尾斜杠。 */
    val key: String get() = url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    fun toJson(): JSONObject = JSONObject().apply {
        put("bookSourceName", name)
        put("bookSourceUrl", url)
        put("bookSourceGroup", group)
        put("enabled", enabled)
        put("searchUrl", searchUrl)
        put("ruleSearch", JSONObject().apply {
            put("bookList", ruleSearchList)
            put("name", ruleSearchName)
            put("author", ruleSearchAuthor)
            put("coverUrl", ruleSearchCover)
            put("bookUrl", ruleSearchBookUrl)
        })
        put("ruleBookInfo", JSONObject().apply {
            put("name", ruleBookName)
            put("author", ruleBookAuthor)
            put("intro", ruleBookIntro)
            put("coverUrl", ruleBookCover)
            put("tocUrl", ruleBookTocUrl)
        })
        put("ruleContent", JSONObject().apply {
            put("content", ruleContent)
            put("replaceRegex", ruleContentReplace)
            put("nextContentUrl", ruleContentNext)
        })
        put("ruleToc", JSONObject().apply {
            put("chapterList", ruleTocList)
            put("chapterName", ruleTocTitle)
            put("chapterUrl", ruleTocUrl)
            put("nextTocUrl", ruleTocNext)
        })
        put("loginCheckJs", loginCheckJs)
        put("loginUrl", loginUrl)
        put("jsLib", jsLib)
        put("exploreUrl", exploreUrl)
        put("ruleExplore", JSONObject().apply {
            put("bookList", ruleExploreList)
            put("name", ruleExploreName)
            put("author", ruleExploreAuthor)
            put("bookUrl", ruleExploreBookUrl)
            put("coverUrl", ruleExploreCover)
            put("intro", ruleExploreIntro)
        })
        if (headerUa.isNotBlank()) {
            put("header", JSONObject().apply { put("User-Agent", headerUa) })
        }
    }

    companion object {
        /** header 可能是对象或 JSON 字符串（阅读导出是字符串），两种都认，只取 User-Agent。 */
        private fun parseHeaderUa(o: JSONObject): String {
            o.optJSONObject("header")?.let { return it.optString("User-Agent").orEmpty() }
            val raw = o.optString("header")
            if (raw.isBlank()) return ""
            return runCatching { JSONObject(raw).optString("User-Agent") }.getOrDefault("")
        }

        /** 解析单条 bookSource.json（兼容阅读 App 导出的完整格式，只取用到的字段）。 */
        fun fromJson(json: String): BookSource? = runCatching {
            val o = JSONObject(json)
            val rs = o.optJSONObject("ruleSearch")
            val rb = o.optJSONObject("ruleBookInfo")
            val rt = o.optJSONObject("ruleToc")
            val rc = o.optJSONObject("ruleContent")
            val re = o.optJSONObject("ruleExplore")
            BookSource(
                name = o.optString("bookSourceName"),
                url = o.optString("bookSourceUrl"),
                group = o.optString("bookSourceGroup"),
                enabled = o.optBoolean("enabled", true),
                searchUrl = o.optString("searchUrl"),
                ruleSearchList = rs?.optString("bookList").orEmpty(),
                ruleSearchName = rs?.optString("name").orEmpty(),
                ruleSearchAuthor = rs?.optString("author").orEmpty(),
                ruleSearchCover = rs?.optString("coverUrl").orEmpty(),
                ruleSearchBookUrl = rs?.optString("bookUrl").orEmpty(),
                ruleBookName = rb?.optString("name").orEmpty(),
                ruleBookAuthor = rb?.optString("author").orEmpty(),
                ruleBookIntro = rb?.optString("intro").orEmpty(),
                ruleBookCover = rb?.optString("coverUrl").orEmpty(),
                ruleBookTocUrl = rb?.optString("tocUrl").orEmpty(),
                ruleTocList = rt?.optString("chapterList").orEmpty(),
                ruleTocTitle = rt?.optString("chapterName").orEmpty(),
                ruleTocUrl = rt?.optString("chapterUrl").orEmpty(),
                ruleContent = rc?.optString("content").orEmpty(),
                ruleContentReplace = rc?.optString("replaceRegex").orEmpty(),
                ruleContentNext = rc?.optString("nextContentUrl").orEmpty(),
                ruleTocNext = rt?.optString("nextTocUrl").orEmpty(),
                loginCheckJs = o.optString("loginCheckJs").orEmpty(),
                loginUrl = o.optString("loginUrl").orEmpty(),
                headerUa = parseHeaderUa(o),
                jsLib = o.optString("jsLib").orEmpty(),
                exploreUrl = o.optString("exploreUrl").orEmpty(),
                ruleExploreList = re?.optString("bookList").orEmpty(),
                ruleExploreName = re?.optString("name").orEmpty(),
                ruleExploreAuthor = re?.optString("author").orEmpty(),
                ruleExploreBookUrl = re?.optString("bookUrl").orEmpty(),
                ruleExploreCover = re?.optString("coverUrl").orEmpty(),
                ruleExploreIntro = re?.optString("intro").orEmpty(),
            ).takeIf { it.name.isNotBlank() && it.url.isNotBlank() }
        }.getOrNull()

        /** 解析数组或单条（阅读 App 导出可能是 [{...}] 数组）。 */
        fun listFromJson(json: String): List<BookSource> = runCatching {
            val t = json.trim()
            if (t.startsWith("[")) {
                val arr = org.json.JSONArray(t)
                // v0.211.0 审计修复：同 load，坏条目隔离，不整批失败。
                (0 until arr.length()).mapNotNull {
                    runCatching { fromJson(arr.getJSONObject(it).toString()) }.getOrNull()
                }
            } else {
                listOfNotNull(fromJson(t))
            }
        }.getOrDefault(emptyList())
    }
}
