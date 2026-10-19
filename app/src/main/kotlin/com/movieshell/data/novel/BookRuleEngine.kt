package com.movieshell.data.novel

import android.util.Log
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA_CHROME120
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * v0.61.0 书源规则引擎：OkHttp 抓取 + Jsoup CSS 解析。
 *
 * 规则语法（实用子集）：
 * - 列表：CSS 选择器，如 "div.book-item"
 * - 字段："选择器@属性"，如 "a.title@text"、"a@href"、"img@src"；
 *   选择器省略时作用于当前元素，如 "@text"、"@href"
 * - searchUrl 中的 {{key}} 替换为 URL 编码后的关键词
 * - 相对链接自动按页面 URL 补全为绝对地址
 */
object BookRuleEngine {

    private const val TAG = "BookRuleEngine"

    // v0.112.0 B1：小说 HTML 抓取（跟随重定向 + 幂等 GET 重试均在共享单例里已有），
    // 改用进程级共享单例。UA/Accept-Language 每次请求显式带，单例拦截器不覆盖。
    private val client = CmsApi.defaultClient()


    suspend fun getHtml(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA_CHROME120)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                // 先按 UTF-8 解，乱码则尝试 GBK（中文小说站常见）
                val utf8 = bytes.toString(Charsets.UTF_8)
                if (utf8.contains('�')) bytes.toString(Charset.forName("GBK")) else utf8
            }
        }.onFailure { Log.w(TAG, "抓取失败 $url", it) }.getOrNull()
    }

    /** 在 root 下按规则提字段。返回空字符串表示没提出来。 */
    fun extract(root: Element, pageUrl: String, rule: String): String {
        val r = rule.trim()
        if (r.isEmpty()) return ""
        return runCatching {
            val at = r.lastIndexOf('@')
            val (selector, attr) = if (at >= 0) {
                r.substring(0, at).trim() to r.substring(at + 1).trim()
            } else {
                r to "text"
            }
            val el: Element = if (selector.isEmpty()) root else root.selectFirst(selector) ?: return ""
            when (attr.lowercase()) {
                "text" -> el.text().trim()
                "html" -> el.html()
                "href", "src" -> absolutize(pageUrl, el.attr(attr).trim())
                else -> el.attr(attr).trim()
            }
        }.getOrDefault("")
    }

    fun selectList(html: String, css: String): List<Element> {
        if (css.isBlank()) return emptyList()
        return runCatching { Jsoup.parse(html).select(css).toList() }.getOrDefault(emptyList())
    }

    fun parseDoc(html: String, baseUrl: String): org.jsoup.nodes.Document =
        Jsoup.parse(html, baseUrl)

    /**
     * 正文提取：去掉 script/style；<br> 与块级元素都转成换行。
     *
     * v0.91.0 修复：此前用 el.textNodes() 只取直接文本节点；全本小说网等站正文
     * 由 <p> 包裹时直接文本节点只有空白，过滤后返回空串 → 阅读器只显示标题。
     * 现把块级元素尾部都追加换行再用 wholeText() 取全文，兼容
     * "直接文本 + <br>" 与 "<p> 包裹" 两种排版。
     */
    fun extractContent(doc: org.jsoup.nodes.Document, rule: String): String {
        if (rule.isBlank()) return ""
        return runCatching {
            val el = doc.selectFirst(rule) ?: return ""
            el.select("script, style").remove()
            // after 只追加换行文本节点，不复制正文；嵌套块产生的多余空行会被 filter 去掉
            el.select("br").forEach { it.after("\n") }
            el.select("p, div, h1, h2, h3, h4, h5, h6, li, blockquote, tr, section, article")
                .forEach { it.after("\n") }
            el.wholeText()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n") { "　　$it" }
        }.getOrDefault("")
    }

    fun buildSearchUrl(template: String, keyword: String): String =
        template.replace("{{key}}", URLEncoder.encode(keyword.trim(), "UTF-8"))

    private fun absolutize(pageUrl: String, href: String): String {
        if (href.isBlank()) return ""
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching {
            java.net.URL(java.net.URL(pageUrl), href).toString()
        }.getOrDefault(href)
    }
}
