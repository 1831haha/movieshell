package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * v0.61.0 书源管理：落在 filesDir/book_sources.json。
 * - 增（导入 JSON 文本/文件）、删、启用/禁用；
 * - 内存 StateFlow 缓存，UI 直接 collect。
 */
object BookSourceManager {

    private const val TAG = "BookSourceManager"
    private const val FILE_NAME = "book_sources.json"
    // v0.89.0 内置真实书源的 key（url 去协议去尾斜杠），老用户补种时去重用
    private const val BUILTIN_KEY = "www.quanben.io"

    private val _sources = MutableStateFlow<List<BookSource>>(emptyList())
    val sources: StateFlow<List<BookSource>> = _sources.asStateFlow()

    private fun fileOf(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** 启动时调一次，把磁盘书源载入内存。 */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val f = fileOf(context)
        if (!f.isFile) {
            _sources.value = builtIn()
            save(context)
            return@withContext
        }
        val list = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull {
                BookSource.fromJson(arr.getJSONObject(it).toString())
            }
        }.onFailure {
            Log.w(TAG, "书源文件读取失败", it)
            // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
            AtomicFile.preserveCorrupt(f)
        }.getOrDefault(emptyList())
        // v0.107.0 P0-7：删光就是删光，不再自动复活内置源；
        // 内置源走书源管理页"恢复默认"手动加回（restoreBuiltin）
        _sources.value = list
    }

    private suspend fun save(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            _sources.value.forEach { arr.put(it.toJson()) }
            // v0.170.0：原子写盘
            AtomicFile.writeText(fileOf(context), arr.toString())
        }.onFailure { Log.w(TAG, "书源保存失败", it) }
    }

    /** 导入：返回成功条数。 */
    suspend fun import(context: Context, json: String): Int {
        val list = BookSource.listFromJson(json)
        if (list.isEmpty()) return 0
        val cur = _sources.value.toMutableList()
        var added = 0
        list.forEach { s ->
            if (cur.none { it.key == s.key }) {
                cur.add(s)
                added++
            }
        }
        _sources.value = cur
        save(context)
        return added
    }

    /**
     * v0.84.0：导入已解析好的书源列表（订阅预览勾选后用）。
     * 去重规则与 import 一致：key 已存在的不重复加。返回成功条数。
     */
    suspend fun importSources(context: Context, list: List<BookSource>): Int {
        if (list.isEmpty()) return 0
        val cur = _sources.value.toMutableList()
        var added = 0
        list.forEach { s ->
            if (cur.none { it.key == s.key }) {
                cur.add(s)
                added++
            }
        }
        _sources.value = cur
        save(context)
        return added
    }

    suspend fun remove(context: Context, key: String) {
        _sources.value = _sources.value.filterNot { it.key == key }
        save(context)
    }

    /**
     * v0.107.0 P0-7：手动恢复内置书源（已存在则不重复）。删光书源后靠这个加回来，
     * 不再靠 load() 自动复活。
     * @return true=新增了内置源，false=已存在（无需操作）
     */
    suspend fun restoreBuiltin(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_sources.value.any { it.key == BUILTIN_KEY }) return@withContext false
        _sources.value = _sources.value + builtIn()
        save(context)
        true
    }

    suspend fun setEnabled(context: Context, key: String, enabled: Boolean) {
        _sources.value = _sources.value.map {
            if (it.key == key) it.copy(enabled = enabled) else it
        }
        save(context)
    }

    fun enabledSources(): List<BookSource> = _sources.value.filter { it.enabled }

    /**
     * v0.89.0 内置真实书源：全本小说网（https://www.quanben.io），规则编写于 2026-10-18。
     *
     * 站点：公开免费、无需登录、无付费章节；桌面端 HTML 结构简单、UTF-8。
     * 三步实测（2026-10-18，curl + 规则选择器复核）：
     * ①搜索 GET /index.php?c=book&a=search&keywords=斗罗大陆 → div.list2 共 48 条，
     *   首条"斗罗大陆 / 唐家三少"，bookUrl=/n/douluodalu/
     * ②详情页 /n/douluodalu/ 经 div.tool_button a 跳到 /n/douluodalu/list.html，
     *   ul.list3 li 共 83 章（短书目录完整；数百章以上的超长书站方只列前 24 + 后 23 章，
     *   中间章节不展示——已知限制）
     * ③正文 /n/douluodalu/2.html 的 div#content 为正常中文（约 1900 字），无乱码、
     *   无"登录后可见"。
     * 站改版后用户可在书源管理页自行更新规则、关闭或删除。
     */
    private fun builtIn(): List<BookSource> = listOf(
        BookSource(
            name = "全本小说网",
            url = "https://www.quanben.io",
            group = "内置",
            enabled = true,
            searchUrl = "https://www.quanben.io/index.php?c=book&a=search&keywords={{key}}",
            ruleSearchList = "div.list2",
            ruleSearchName = "h3 a@text",
            ruleSearchAuthor = "span[itemprop=author]@text",
            ruleSearchCover = "img@src",
            ruleSearchBookUrl = "h3 a@href",
            // 详情页无内嵌目录：先提"章节列表"按钮跳到目录页（NovelSourceEngine 两步跳转）
            ruleBookTocUrl = "div.tool_button a@href",
            ruleTocList = "ul.list3 li",
            ruleTocTitle = "@text",
            ruleTocUrl = "a@href",
            ruleContent = "div#content",
        ),
    )
}
