package com.movieshell.data.source

import com.movieshell.domain.model.CategoryType
import com.movieshell.domain.model.Vod
import com.movieshell.domain.model.VodDetail
import com.movieshell.core.net.DEFAULT_UA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * 通用网页源解析器（v0.294.49 v1）。
 *
 * 纯解析，不依赖 Context 与 UI：网络走全仓 OkHttp 单例，解析走 Jsoup。
 * 目标是视频里那种“输一个影视站网址就能当首页源”的站：站内链接结构
 * 清晰的 HTML 站（典型是苹果 CMS 类模板）。SPA 与 API 站不在 v1 覆盖内，
 * 解析不到会返回空结果，调用方按失败态展示，不冒充有数据。
 * 详情 id 一律用详情页绝对地址，播放集地址一律用播放页绝对地址；
 * 直链抽取只在播放阶段做，详情阶段不把播放页地址当直链。
 */
object GenericWebParser {

    data class HomeResult(
        val categories: List<CategoryType> = emptyList(),
        val vods: List<Vod> = emptyList(),
    )

    private val excludedNavTexts = setOf(
        "首页", "搜索", "登录", "注册", "收藏", "下载", "客户端",
        "关于", "联系", "客服", "帮助", "留言", "友情链接", "广告", "会员", "充值",
    )

    private val categoryPathHints = listOf(
        "/type/", "/list/", "/mlist/", "/category/", "/vodtype/", "/show/",
        "/vod/show/", "/vod/type/", "/class/", "/channel/", "/sort/",
    )

    private val detailPathHints = listOf(
        "detail", "vod", "video", "movie", "show", "subject", "title", "film",
    )

    private val mediaSuffixes = listOf(".m3u8", ".mp4")

    private val mediaUrlRegex = Regex(
        """https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
    )

    private val protocolMediaRegex = Regex(
        """//[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
    )

    private val jsonMediaUrlRegex = Regex(
        """"url"\s*:\s*"([^"]+\.(?:m3u8|mp4)[^"]*)"""",
    )

    /** 同步请求改可取消版：调用方超时或切源时关掉底层连接，不让线程空跑。 */
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val client = CmsApi.defaultClient()
        val call = client.newCall(
            Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build(),
        )
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val job = currentCoroutineContext()[Job]
            if (job?.isCancelled == true) currentCoroutineContext().ensureActive()
            throw e
        }
    }

    fun parseHome(html: String, base: String): HomeResult {
        val doc = Jsoup.parse(html, base)
        return HomeResult(
            categories = parseCategories(doc, base),
            vods = extractCards(doc, base),
        )
    }

    fun parseCategory(html: String, base: String): List<Vod> {
        val doc = Jsoup.parse(html, base)
        return extractCards(doc, base)
    }

    fun parseDetail(html: String, detailUrl: String): VodDetail {
        val doc = Jsoup.parse(html, detailUrl)
        val title = cleanTitle(doc)
        val pic = doc.selectFirst("meta[property=\"og:image\"]")?.attr("abs:src").orEmpty()
            .ifBlank { doc.selectFirst(".detail img, .vod-detail img, .content img")?.attr("abs:src").orEmpty() }
        val desc = doc.selectFirst("meta[name=\"description\"]")?.attr("content").orEmpty()
            .ifBlank {
                doc.selectFirst(".desc, .description, .vod-desc, .synopsis, .intro, .content")
                    ?.text().orEmpty()
            }.trim().take(2000)
        val groups = extractPlayGroups(doc, detailUrl)
        if (groups.isEmpty()) {
            val playable = extractMediaCandidates(doc, detailUrl).isNotEmpty()
            if (playable) {
                return VodDetail(
                    vodId = detailUrl,
                    vodName = title,
                    vodPic = pic,
                    vodDesc = desc,
                    vodPlayFrom = "默认线路",
                    vodPlayUrl = "正片$$detailUrl",
                )
            }
            // v0.294.49 嗅探兜底：无选集且详情页本身无媒体时，不再返回空详情，
            // 而是给 1 集“嗅探播放”，地址就是详情页本身。点播时 PlayResolver
            // 先静态抽直链，抽不到再走 WebView 嗅探；嗅不到按现有失败态收尾，
            // 不伪造直链、不把网盘或下载链接当直链。
            return VodDetail(
                vodId = detailUrl,
                vodName = title,
                vodPic = pic,
                vodDesc = desc,
                vodPlayFrom = "嗅探线路",
                vodPlayUrl = "嗅探播放$$detailUrl",
            )
        }
        val from = groups.joinToString("$$$") { it.first }
        val urls = groups.joinToString("$$$") { group ->
            group.second.joinToString("#") { ep ->
                "${ep.first.replace("$", "").replace("#", "")}$$${ep.second}"
            }
        }
        return VodDetail(
            vodId = detailUrl,
            vodName = title,
            vodPic = pic,
            vodDesc = desc,
            vodPlayFrom = from,
            vodPlayUrl = urls,
        )
    }

    /** 播放页抽直链：播放器配置、video 标签、直链正则、iframe 再抽一次。 */
    suspend fun resolvePlay(playPageUrl: String): String {
        if (!isHttpUrl(playPageUrl)) return ""
        val html = runCatching { fetch(playPageUrl) }.getOrNull().orEmpty()
        if (html.isBlank()) return ""
        val doc = Jsoup.parse(html, playPageUrl)
        extractMediaCandidates(doc, playPageUrl).firstOrNull()?.let { return it }
        val iframe = doc.select("iframe[src]").firstOrNull()?.attr("abs:src").orEmpty()
        if (iframe.isNotBlank() && iframe != playPageUrl && isHttpUrl(iframe)) {
            val inner = runCatching { fetch(iframe) }.getOrNull().orEmpty()
            if (inner.isNotBlank()) {
                val innerDoc = Jsoup.parse(inner, iframe)
                extractMediaCandidates(innerDoc, iframe).firstOrNull()?.let { return it }
            }
        }
        return ""
    }

    /** 分类分页候选地址：按常见形态依次生成，调用方逐个试。 */
    fun pageCandidates(categoryUrl: String, pg: Int): List<String> {
        if (pg <= 1) return listOf(categoryUrl)
        val out = ArrayList<String>()
        fun add(u: String) {
            val v = u.trim()
            if (v.isNotBlank() && v !in out) out.add(v)
        }
        if (categoryUrl.contains("page=")) {
            add(categoryUrl.replace(Regex("page=\\d+"), "page=$pg"))
        }
        if (categoryUrl.contains("/page/")) {
            add(categoryUrl.replace(Regex("/page/\\d+/"), "/page/$pg/"))
        }
        val noQuery = categoryUrl.substringBefore("?")
        if (noQuery.endsWith(".html")) {
            val replaced = noQuery.replace(Regex("-\\d+\\.html$"), "-$pg.html")
            if (replaced != noQuery) add(replaced) else add(noQuery.removeSuffix(".html") + "-$pg.html")
            add(noQuery.removeSuffix(".html") + "_$pg.html")
            add(noQuery.removeSuffix(".html") + "/page/$pg.html")
        }
        val base = categoryUrl.trimEnd('/')
        add("$base/page/$pg/")
        add("$base/page/$pg")
        if (categoryUrl.contains("?")) add("$categoryUrl&page=$pg") else add("$categoryUrl?page=$pg")
        return out
    }

    /** 搜索候选地址：按常见形态依次生成，调用方逐个试并过滤标题。 */
    fun searchCandidates(base: String, keyword: String): List<String> {
        val root = base.trimEnd('/')
        val enc = runCatching { URLEncoder.encode(keyword, "UTF-8") }.getOrDefault(keyword)
        return listOf(
            "$root/search/$enc",
            "$root/vod/search.html?wd=$enc",
            "$root/search/-------------.html?wd=$enc",
            "$root/?s=$enc",
            "$root/search?q=$enc",
        )
    }

    fun domainName(url: String): String {
        val host = runCatching { java.net.URL(url).host }.getOrDefault("")
        return host.removePrefix("www.").ifBlank { "网页源" }
    }

    // ---------------- 分类 ----------------

    /**
     * v1.1.12：导航区选择器。#menu 是补的——老模板站常把菜单放 id="menu" 的 div 里
     * （原先六个选不中）；#header 与 .header 故意不加：老模板的 header div 常没闭合，
     * 会把整页都包进去，导航区一下变成全站链接（实测某站 #header 命中 156 个）。
     */
    private val navAreaSelectors =
        "nav a[href], header a[href], .nav a[href], .navbar a[href], .menu a[href], #nav a[href], #menu a[href]"

    private fun parseCategories(doc: Document, base: String): List<CategoryType> {
        val seen = LinkedHashMap<String, String>()
        // 认什么、挡什么（v1.1.12 通用口径）：
        // 前三关只认本站（同注册域，兄弟子域名算本站）：导航区、分类路径、菜单簇。
        // 第四关是兜底：前三关一个分类都没抽到，说明本站导航本身就挂在外站上，
        // 才把导航区的外站链接当分类认；本站已有分类时绝不触发，294.50 清掉的
        // 内容区外站链接不会回来；内容区的链接永远不走这关。
        fun collect(el: Element, allowCrossDomain: Boolean = false) {
            val href = el.attr("abs:href").trim()
            if (href.isBlank()) return
            if (!allowCrossDomain && !isSameHost(href, base)) return
            if (isDetailOrPlayOrSearch(href)) return
            val text = cleanText(el.text())
            if (text.length !in 2..8) return
            if (text in excludedNavTexts) return
            // v1.1.12：留言板不是栏目（“留言”在排除集里是精确匹配，“留言板”漏网）。
            if (text.contains("收藏") || text.contains("主页") || text.contains("设为") || text.contains("留言")) return
            if (text.any { it.isDigit() }) return
            seen.putIfAbsent(href, text)
        }
        doc.select(navAreaSelectors)
            .forEach { collect(it) }
        if (seen.size < 10) {
            doc.select("a[href]").forEach { el ->
                val href = el.attr("abs:href").trim()
                if (categoryPathHints.any { href.contains(it) }) collect(el)
            }
        }
        if (seen.size < 10) {
            val groups = LinkedHashMap<Element, MutableList<Element>>()
            doc.select("a[href]").forEach { el ->
                val key = if (el.parent()?.tagName() == "li") el.parent()?.parent() else el.parent()
                if (key != null) groups.getOrPut(key) { mutableListOf() }.add(el)
            }
            for ((_, members) in groups) {
                if (members.size >= 5) {
                    members.forEach { collect(it) }
                    break
                }
            }
        }
        if (seen.isEmpty()) {
            doc.select(navAreaSelectors).forEach { collect(it, allowCrossDomain = true) }
        }
        return seen.entries.take(30).map { (href, name) ->
            CategoryType(typeId = href, typeName = name)
        }
    }

    // ---------------- 列表卡片 ----------------

    private fun extractCards(doc: Document, base: String): List<Vod> {
        val out = LinkedHashMap<String, Vod>()
        doc.select("a[href]:has(img)").forEach { a ->
            val href = a.attr("abs:href").trim()
            if (!isDetailCandidate(href, base)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim()
                .ifBlank { img.attr("title").trim() }
                .ifBlank { cleanText(a.text()) }
            if (title.isBlank()) return@forEach
            if (title.contains("下载") || title.contains("密码") || title.contains("登录") ||
                title.contains("注册") || title.contains("收藏")
            ) return@forEach
            val pic = img.attr("abs:src").trim()
                .ifBlank { img.attr("abs:data-src").trim() }
                .ifBlank { img.attr("abs:data-original").trim() }
                .ifBlank { firstSrcset(img) }
            out.putIfAbsent(href, Vod(vodId = href, vodName = title, vodPic = pic))
        }
        if (out.size < 5) {
            doc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href").trim()
                if (!isDetailCandidate(href, base)) return@forEach
                val title = cleanText(a.text())
                if (title.length !in 2..40) return@forEach
                if (title in excludedNavTexts) return@forEach
                out.putIfAbsent(href, Vod(vodId = href, vodName = title))
            }
        }
        return out.values.take(60)
    }

    private fun firstSrcset(img: Element): String {
        val raw = img.attr("srcset").trim()
        if (raw.isBlank()) return ""
        val first = raw.split(",").firstOrNull()?.trim()?.substringBefore(" ")?.trim().orEmpty()
        if (first.isBlank()) return ""
        return runCatching { java.net.URL(java.net.URL(img.baseUri()), first).toString() }.getOrDefault(first)
    }

    private fun isDetailCandidate(href: String, base: String): Boolean {
        if (href.isBlank() || !isSameHost(href, base)) return false
        if (isDetailOrPlayOrSearch(href)) return false
        val path = href.substringBefore("?").substringBefore("#")
        if (path.trimEnd('/') == base.trimEnd('/')) return false
        val lower = path.lowercase()
        if (categoryPathHints.any { lower.contains(it) }) return false
        val baseName = lower.substringAfterLast("/")
        if (baseName == "index.html" || baseName == "index.htm" || baseName == "list.html" || baseName == "page.html") return false
        if (lower.contains("/app/") || lower.contains("/download")) return false
        if (lower.contains("/user/") || lower.contains("/member/") || lower.contains("/account/") ||
            lower.contains("/support/") || lower.contains("/help/") || lower.contains("/about/") ||
            lower.contains("findpass") || lower.contains("forget")
        ) return false
        if (detailPathHints.any { lower.contains(it) }) return true
        val segments = lower.substringAfter("://").substringAfter("/")
            .split("/").filter { it.isNotBlank() }
        if (segments.size >= 2 && (lower.endsWith(".html") || lower.endsWith(".htm"))) return true
        return false
    }

    // ---------------- 详情 ----------------

    private fun cleanTitle(doc: Document): String {
        val raw = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=\"og:title\"]")?.attr("content").orEmpty() }
            .ifBlank { doc.title().trim() }
        if (raw.isBlank()) return ""
        val cut = listOf(" - ", " | ", " _ ")
            .mapNotNull { sep ->
                val idx = raw.indexOf(sep)
                if (idx > 0) raw.substring(0, idx).trim() else null
            }.firstOrNull { it.isNotBlank() }
        return cut ?: raw
    }

    private fun extractPlayGroups(
        doc: Document,
        detailUrl: String,
    ): List<Pair<String, List<Pair<String, String>>>> {
        val links = doc.select("a[href]").filter { el ->
            val href = el.attr("abs:href").trim()
            href.contains("/play/") && isSameHost(href, detailUrl)
        }
        if (links.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        val groupNames = LinkedHashMap<String, String>()
        links.forEachIndexed { index, el ->
            val href = el.attr("abs:href").trim()
            val name = cleanText(el.text()).ifBlank { "第${index + 1}集" }
            val container = findPlayContainer(el)
            val key = container?.let { System.identityHashCode(it).toString() } ?: "default"
            val lineName = container?.let { lineNameFor(it) } ?: "默认线路"
            groupNames.putIfAbsent(key, lineName)
            grouped.getOrPut(key) { mutableListOf() }.add(name to href)
        }
        if (grouped.size == 1) {
            val only = grouped.entries.first()
            val lineName = groupNames[only.key] ?: "默认线路"
            return listOf(lineName to only.value)
        }
        return grouped.entries.map { (key, eps) ->
            (groupNames[key] ?: "默认线路") to eps
        }
    }

    private fun findPlayContainer(el: Element): Element? {
        var cur: Element? = el.parent()
        var steps = 0
        while (cur != null && steps < 6) {
            val cls = cur.className().lowercase()
            val playLinks = cur.select("a[href]").count { it.attr("abs:href").contains("/play/") }
            if (playLinks >= 2 && (cls.contains("play") || cls.contains("episode") || cls.contains("list") || cur.tagName() == "ul")) {
                return cur
            }
            cur = cur.parent()
            steps++
        }
        return null
    }

    private fun lineNameFor(container: Element): String {
        val heading = container.selectFirst("h2, h3, h4, .tab-title, .play-from, .from, strong, b")
            ?.text()?.let { cleanText(it) }.orEmpty()
        if (heading.isNotBlank() && heading.length <= 12) return heading
        var prev = container.previousElementSibling()
        var steps = 0
        while (prev != null && steps < 3) {
            val text = cleanText(prev.text())
            if (text.isNotBlank() && text.length <= 12 && !text.contains("第")) return text
            prev = prev.previousElementSibling()
            steps++
        }
        val parentHeading = container.parent()?.selectFirst("h2, h3, h4")
            ?.text()?.let { cleanText(it) }.orEmpty()
        if (parentHeading.isNotBlank() && parentHeading.length <= 12) return parentHeading
        return "默认线路"
    }

    // ---------------- 播放直链 ----------------

    private fun extractMediaCandidates(doc: Document, pageUrl: String): List<String> {
        val out = ArrayList<String>()
        fun add(raw: String) {
            val v = normalizeMediaUrl(raw, pageUrl) ?: return
            if (v !in out) out.add(v)
        }
        var macUrl: String? = null
        runCatching { MacCmsPlayer.extract(doc.html()) }.getOrNull()?.let { r ->
            macUrl = MacCmsPlayer.absolutize(r.url, pageUrl)
        }
        doc.select("video source[src], video[src]").forEach { add(it.attr("abs:src")) }
        doc.select("[data-url]").forEach { add(it.attr("abs:data-url")) }
        val html = doc.html()
        mediaUrlRegex.findAll(html).forEach { add(it.value.replace("\\/", "/")) }
        protocolMediaRegex.findAll(html).forEach { add("https:" + it.value.replace("\\/", "/")) }
        jsonMediaUrlRegex.findAll(html).forEach { add(it.groupValues[1].replace("\\/", "/")) }
        val mediaOnly = out.filter { isMediaUrl(it) }
        val mac = macUrl
        if (mac != null && (isMediaUrl(mac) || PlayRouting.isVipUrl(mac))) {
            return listOf(mac) + mediaOnly.filter { it != mac }
        }
        return mediaOnly
    }

    private fun normalizeMediaUrl(raw: String, pageUrl: String): String? {
        val v = raw.trim().replace("\\/", "/").replace("&amp;", "&")
        if (v.lowercase().contains("ftp://") || v.lowercase().contains("thunder://") ||
            v.lowercase().contains("ed2k://")
        ) return null
        val schemeCount = Regex("://").findAll(v).count()
        if (schemeCount > 1) return null
        if (v.isBlank()) return null
        return when {
            v.startsWith("http://", true) || v.startsWith("https://", true) -> v
            v.startsWith("//") -> "https:$v"
            v.startsWith("/") && pageUrl.contains("://") ->
                pageUrl.substringBefore("://") + "://" + pageUrl.substringAfter("://").substringBefore('/') + v
            else -> null
        }
    }

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore("?")
        return mediaSuffixes.any { lower.endsWith(it) } || lower.contains(".m3u8") || lower.contains(".mp4")
    }

    // ---------------- 通用小件 ----------------

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    private fun isSameHost(url: String, base: String): Boolean {
        val a = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        val b = runCatching { java.net.URL(base).host.lowercase() }.getOrDefault("")
        if (a.isBlank() || b.isBlank()) return false
        if (a == b || a.endsWith(".$b") || b.endsWith(".$a")) return true
        // 兄弟子域名也算本站：m.youku.com 与 www.youku.com 同属一个站，
        // 旧的精确比对会把移动端、新闻子域名下的卡片全挡掉。
        return registrableDomain(a) == registrableDomain(b)
    }

    // 注册域近似取法：二段式国家后缀取后三段，其余取后两段；
    // 两段以下的主机名本身就是注册域，不再截断。
    private fun registrableDomain(host: String): String {
        val twoLevelSuffixes = listOf(
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "com.hk",
            "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.jp",
            "com.br", "co.kr",
        )
        for (suffix in twoLevelSuffixes) {
            if (host.endsWith(".$suffix")) {
                val parts = host.split(".")
                return if (parts.size >= 3) parts.takeLast(3).joinToString(".") else host
            }
        }
        val parts = host.split(".")
        return if (parts.size >= 3) parts.takeLast(2).joinToString(".") else host
    }

    private fun isDetailOrPlayOrSearch(href: String): Boolean {
        val lower = href.lowercase()
        return lower.contains("/play/") || lower.contains("search") || lower.contains("/login") ||
            lower.contains("/register") || lower.contains("javascript:")
    }

    private fun cleanText(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()
}
