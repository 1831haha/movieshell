package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.novel.BookSource
import com.movieshell.data.novel.BookSourceManager
import org.json.JSONArray
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * v0.294.20：subscribe=订阅包插件（「源广场」）。安装包是一份 JSON：
 * ```
 * {"vod":[{"name","url","note"}],      // TVBox 接口/点播订阅 → 站源管理「接口站源」
 *  "live":[{"name","url"}],            // 直播 M3U/TXT 订阅 → 直播订阅
 *  "book":[<阅读 bookSource 对象>]}     // 书源 → 书源管理
 * ```
 * 安装即把这些地址加进对应订阅（已存在的不重复加、不改动）；
 * 注册表 files 记下本插件真正新增的条目（vod:/live:/book: 前缀），卸载只删这些，
 * 用户自己先加过的同地址订阅不受影响。
 */
object SubscribePlugin {

    data class Entry(val name: String, val url: String, val note: String = "")

    data class Spec(
        val vod: List<Entry>,
        val live: List<Entry>,
        val book: JSONArray,
    ) {
        val isEmpty: Boolean get() = vod.isEmpty() && live.isEmpty() && book.length() == 0
    }

    private fun httpUrl(u: String) = u.startsWith("https://", true) || u.startsWith("http://", true)

    private fun entries(arr: JSONArray?): List<Entry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (!httpUrl(url)) return@mapNotNull null
            Entry(o.optString("name").trim().ifBlank { url.substringAfter("://").substringBefore("/") }, url, o.optString("note").trim())
        }.distinctBy { it.url }
    }

    /** 纯解析（单测覆盖）：非法 JSON 或三类皆空抛异常。 */
    fun parse(text: String): Spec {
        val o = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("订阅包不是合法 JSON") }
        val spec = Spec(entries(o.optJSONArray("vod")), entries(o.optJSONArray("live")), o.optJSONArray("book") ?: JSONArray())
        if (spec.isEmpty) throw IllegalStateException("订阅包里没有可用的订阅地址")
        return spec
    }

    /** 安装：返回 (新增记录 files, 汇总文案)。 */
    suspend fun apply(context: Context, group: String, spec: Spec, oldFiles: List<String>): Pair<List<String>, String> {
        val files = mutableListOf<String>()
        // 升级时：上个版本由本插件加的、这版已不再包含的先撤掉
        val keepVod = spec.vod.map { it.url }.toSet()
        val keepLive = spec.live.map { it.url }.toSet()
        // 点播
        val cur = ApiSourceStore.load(context).toMutableList()
        cur.removeAll { s -> oldFiles.contains("vod:${s.url}") && s.url !in keepVod }
        var vodAdded = 0
        spec.vod.forEach { e ->
            if (cur.none { it.url == e.url }) {
                cur.add(0, ApiSource(e.name, e.url, true, e.note, group))
                vodAdded++
                files.add("vod:${e.url}")
            } else if (oldFiles.contains("vod:${e.url}")) files.add("vod:${e.url}")
        }
        if (!ApiSourceStore.save(context, cur)) throw IllegalStateException("写入接口站源失败")
        // 直播
        LiveStore.init(context)
        oldFiles.filter { it.startsWith("live:") }.map { it.removePrefix("live:") }
            .filter { it !in keepLive }.forEach { LiveStore.removeSource(context, it) }
        var liveAdded = 0
        spec.live.forEach { e ->
            if (LiveStore.addSource(context, e.name, LiveSource.M3U_URL, e.url)) {
                liveAdded++
                files.add("live:${e.url}")
            } else if (oldFiles.contains("live:${e.url}")) files.add("live:${e.url}")
        }
        // 书源
        var bookAdded = 0
        if (spec.book.length() > 0) {
            val list = BookSource.listFromJson(spec.book.toString())
            BookSourceManager.load(context)
            bookAdded = BookSourceManager.importSources(context, list)
            list.forEach { files.add("book:${it.key}") }
        }
        // 新加的点播订阅后台解析展开站点（与站源页手动添加同一口径），直播刷新聚合
        spec.vod.filter { files.contains("vod:${it.url}") }.forEach { e ->
            SubSiteStore.appScope.launch {
                SubSiteStore.refresh(context, e.url)
                    .onSuccess { SubRefreshStore.recordResult(context, e.url, true) }
                    .onFailure { LogBus.w("SubscribePlugin", "订阅解析失败：${e.url} ${it.message}") }
            }
        }
        if (liveAdded > 0) runCatching { LiveStore.refresh(context) }
        val parts = listOfNotNull(
            if (spec.vod.isNotEmpty()) "点播订阅 +$vodAdded" else null,
            if (spec.live.isNotEmpty()) "直播订阅 +$liveAdded" else null,
            if (spec.book.length() > 0) "书源 +$bookAdded" else null,
        )
        return files to "已订阅：" + parts.joinToString("，") + "（已存在的不重复添加）"
    }

    suspend fun remove(context: Context, files: List<String>) {
        val vod = files.filter { it.startsWith("vod:") }.map { it.removePrefix("vod:") }.toSet()
        if (vod.isNotEmpty()) {
            ApiSourceStore.save(context, ApiSourceStore.load(context).filterNot { it.url in vod || it.parentUrl in vod })
            vod.forEach { runCatching { SubSiteStore.removeCache(context, it) } }
        }
        LiveStore.init(context)
        files.filter { it.startsWith("live:") }.forEach { LiveStore.removeSource(context, it.removePrefix("live:")) }
        val books = files.filter { it.startsWith("book:") }
        if (books.isNotEmpty()) {
            BookSourceManager.load(context)
            books.forEach { BookSourceManager.remove(context, it.removePrefix("book:")) }
        }
    }
}
