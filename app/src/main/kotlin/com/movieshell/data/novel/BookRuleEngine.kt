package com.movieshell.data.novel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    suspend fun getHtml(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
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

    /** 正文提取：去掉 script/style，按块级元素分段。 */
    fun extractContent(doc: org.jsoup.nodes.Document, rule: String): String {
        if (rule.isBlank()) return ""
        return runCatching {
            val el = doc.selectFirst(rule) ?: return ""
            el.select("script, style").remove()
            // <br> 转成分段
            el.select("br").forEach { it.after("\n") }
            el.textNodes().joinToString("\n") { it.text().trim() }
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
