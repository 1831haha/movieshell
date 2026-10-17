package com.movieshell.data.novel

import android.content.Context
import android.util.Log
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
        }.onFailure { Log.w(TAG, "书源文件读取失败", it) }.getOrDefault(emptyList())
        _sources.value = list.ifEmpty { builtIn() }
    }

    private suspend fun save(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            _sources.value.forEach { arr.put(it.toJson()) }
            fileOf(context).writeText(arr.toString())
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

    suspend fun remove(context: Context, key: String) {
        _sources.value = _sources.value.filterNot { it.key == key }
        save(context)
    }

    suspend fun setEnabled(context: Context, key: String, enabled: Boolean) {
        _sources.value = _sources.value.map {
            if (it.key == key) it.copy(enabled = enabled) else it
        }
        save(context)
    }

    fun enabledSources(): List<BookSource> = _sources.value.filter { it.enabled }

    /**
     * 内置示例书源（开箱即用）。规则按目标站当前结构编写，站改版后用户可自行更新规则。
     */
    private fun builtIn(): List<BookSource> = listOf(
        BookSource(
            name = "示例书源（请导入自己的书源）",
            url = "https://example.com",
            group = "内置",
            enabled = false,
            searchUrl = "https://example.com/s?q={{key}}",
            ruleSearchList = "div.item",
            ruleSearchName = "a@text",
            ruleSearchAuthor = "span.author@text",
            ruleSearchCover = "img@src",
            ruleSearchBookUrl = "a@href",
            ruleTocList = "div.chapters a",
            ruleTocTitle = "@text",
            ruleTocUrl = "@href",
            ruleContent = "div.content",
        ),
    )
}
