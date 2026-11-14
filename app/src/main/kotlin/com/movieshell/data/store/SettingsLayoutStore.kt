package com.movieshell.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.2.7：设置页“活布局”——可拖动的设置项住哪、按什么顺序，由这张表说了算。
 *
 * 容器 = 一组设置行住的地方：main:playback（设置页“播放 · 解析”组）、
 * page:playback_settings（“播放设置”子页面）。可移项只有 5 个（播放器内核 /
 * 直播自动播放 / 去广告 / 同时下载数 / 重力感应横屏），导航入口、统计环、
 * 工具磁贴不参与拖动。布局存 SharedPreferences，下次打开还在；恢复默认回
 * 到下面这张 DEFAULT_LAYOUT。机制通用，以后别的行要加入拖动，只需往
 * ALL_ITEMS 与 DEFAULT_LAYOUT 里加一项。
 */
object SettingsLayoutStore {
    const val MAIN_PLAYBACK = "main:playback"
    const val PAGE_PLAYBACK = "page:playback_settings"

    const val ITEM_ENGINE = "engine"
    const val ITEM_AUTOPLAY = "autoplay"
    const val ITEM_ADFILTER = "adfilter"
    const val ITEM_MAX_PARALLEL = "max_parallel"
    const val ITEM_GRAVITY = "gravity"

    val ALL_ITEMS = listOf(ITEM_ENGINE, ITEM_AUTOPLAY, ITEM_ADFILTER, ITEM_MAX_PARALLEL, ITEM_GRAVITY)

    val DEFAULT_LAYOUT: Map<String, List<String>> = mapOf(
        MAIN_PLAYBACK to listOf(ITEM_GRAVITY),
        PAGE_PLAYBACK to listOf(ITEM_ENGINE, ITEM_AUTOPLAY, ITEM_ADFILTER, ITEM_MAX_PARALLEL),
    )

    private const val PREFS = "movieshell_prefs"
    private const val KEY_LAYOUT = "settings_layout_v1"

    private val _layout = MutableStateFlow<Map<String, List<String>>>(DEFAULT_LAYOUT)
    val layout: StateFlow<Map<String, List<String>>> = _layout

    /** 读盘；没有存过、存坏了、缺了新项，都以默认布局为底补齐，不丢项、不留野 id。 */
    fun init(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
        _layout.value = normalize(raw)
    }

    private fun normalize(raw: String?): Map<String, List<String>> {
        val saved = mutableMapOf<String, List<String>>()
        runCatching {
            val obj = JSONObject(raw ?: return@runCatching)
            for (container in DEFAULT_LAYOUT.keys) {
                val arr = obj.optJSONArray(container) ?: continue
                saved[container] = (0 until arr.length()).mapNotNull { arr.optString(it) }.filter { it in ALL_ITEMS }
            }
        }
        val out = mutableMapOf<String, List<String>>()
        for ((container, defaults) in DEFAULT_LAYOUT) {
            val list = (saved[container] ?: emptyList()).toMutableList()
            // 默认住这的行，存里没有就补到末尾（以后新加可移项，老布局不丢它）。
            for (id in defaults) if (id !in list) list.add(id)
            out[container] = list
        }
        // 哪行都不住着（旧布局漏了）就放回默认容器。
        val placed = out.values.flatten().toSet()
        for (id in ALL_ITEMS) {
            if (id !in placed) {
                val home = DEFAULT_LAYOUT.entries.first { id in it.value }.key
                out[home] = out.getValue(home) + id
            }
        }
        return out
    }

    /** 把一项挪到目标容器指定位置（同容器 = 调顺序，跨容器 = 搬家），立即存盘。 */
    fun move(context: Context, itemId: String, toContainer: String, toIndex: Int) {
        if (itemId !in ALL_ITEMS || toContainer !in DEFAULT_LAYOUT) return
        val next = _layout.value.mapValues { it.value.toMutableList() }.toMutableMap()
        for (list in next.values) list.remove(itemId)
        val target = next.getValue(toContainer)
        target.add(toIndex.coerceIn(0, target.size), itemId)
        _layout.value = next
        save(context, next)
    }

    fun reset(context: Context) {
        _layout.value = DEFAULT_LAYOUT
        save(context, DEFAULT_LAYOUT)
    }

    private fun save(context: Context, layout: Map<String, List<String>>) {
        val obj = JSONObject()
        for ((container, ids) in layout) obj.put(container, JSONArray(ids))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAYOUT, obj.toString()).apply()
    }
}
