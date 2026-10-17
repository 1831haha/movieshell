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
        put("ruleToc", JSONObject().apply {
            put("chapterList", ruleTocList)
            put("chapterName", ruleTocTitle)
            put("chapterUrl", ruleTocUrl)
        })
        put("ruleContent", JSONObject().apply {
            put("content", ruleContent)
        })
    }

    companion object {
        /** 解析单条 bookSource.json（兼容阅读 App 导出的完整格式，只取用到的字段）。 */
        fun fromJson(json: String): BookSource? = runCatching {
            val o = JSONObject(json)
            val rs = o.optJSONObject("ruleSearch")
            val rb = o.optJSONObject("ruleBookInfo")
            val rt = o.optJSONObject("ruleToc")
            val rc = o.optJSONObject("ruleContent")
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
            ).takeIf { it.name.isNotBlank() && it.url.isNotBlank() }
        }.getOrNull()

        /** 解析数组或单条（阅读 App 导出可能是 [{...}] 数组）。 */
        fun listFromJson(json: String): List<BookSource> = runCatching {
            val t = json.trim()
            if (t.startsWith("[")) {
                val arr = org.json.JSONArray(t)
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it).toString()) }
            } else {
                listOfNotNull(fromJson(t))
            }
        }.getOrDefault(emptyList())
    }
}
