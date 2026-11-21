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
 * main:data（设置页“数据”组，v1.2.16 起可拖，与播放组互拖）、
 * page:playback_settings（“播放设置”子页面，v1.2.19 起只住播放行）、
 * page:data_settings（“数据设置”子页面，v1.2.19 起只住数据行）。
 * 可移项有 23 个（播放器内核 / 直播自动播放 / 去广告 / 同时下载数 /
 * 重力感应横屏 / TMDB Key / 默认播放方向 / 转场动画 / 首选音频语言 /
 * 首选字幕语言 / Anime4K 画质增强 / 数据组 12 项：订阅自动刷新 / 追剧更新提醒 / WebDAV
 * 云同步 / AI 助手 / 打开对话 / 备份与恢复 / 清理缓存 / 视频缓存上限 /
 * 视频缓存保留时长 / 磁力缓存 / 磁力引擎 / 清理旧字幕记忆），导航入口、
 * 统计环、工具磁贴不参与拖动。分家规则（v1.2.19 用户定：播放放播放页、
 * 数据拖进数据页）：主页面两个组之间互拖随便，子页面只认自家行——数据行
 * 进不了播放页，播放行进不了数据页，moveInLayout 直接拦下，存坏了的
 * 布局由 normalize 搬回主组。布局存 SharedPreferences，下次打开还在；
 * 恢复默认回到下面这张 DEFAULT_LAYOUT。机制通用，以后别的行要加入拖动，
 * 只需往 ALL_ITEMS 与 DEFAULT_LAYOUT 里加一项。
 */
object SettingsLayoutStore {
    const val MAIN_PLAYBACK = "main:playback"
    const val MAIN_DATA = "main:data"
    const val PAGE_PLAYBACK = "page:playback_settings"
    /** v1.2.19：数据设置子页面（与播放设置页平行：播放行进播放页，数据行进数据页）。 */
    const val PAGE_DATA = "page:data_settings"

    const val ITEM_ENGINE = "engine"
    const val ITEM_AUTOPLAY = "autoplay"
    const val ITEM_ADFILTER = "adfilter"
    const val ITEM_MAX_PARALLEL = "max_parallel"
    const val ITEM_GRAVITY = "gravity"
    /** v1.2.13：TMDB Key（本地文件夹海报墙用，用户自己填）。 */
    const val ITEM_TMDB_KEY = "tmdb_key"
    /** v1.2.14 B片（9）：默认播放方向。 */
    const val ITEM_DEF_ORIENTATION = "def_orientation"
    /** v1.2.14 B片（9）：播放器转场动画。 */
    const val ITEM_TRANSITION = "player_transition"
    /** v1.2.14 B片（3）：首选音频语言。 */
    const val ITEM_AUDIO_LANG = "audio_lang"
    /** v1.2.14 B片（3）：首选字幕语言。 */
    const val ITEM_SUB_LANG = "subtitle_lang"
    /** v1.3.25 合并 5：Anime4K 画质增强（off/s/m/vl，仅 libmpv 生效）。 */
    const val ITEM_ANIME4K = "anime4k"
    /** v1.4.6 A1.1：首屏分段诊断开关（默认关，仅新播放会话生效）。 */
    const val ITEM_FIRST_SCREEN_TRACE = "first_screen_trace"

    /** v1.2.16：数据组 12 项（默认全在 main:data，顺序同设置页原顺序）。 */
    const val ITEM_DATA_SUB_REFRESH = "data_sub_refresh"
    const val ITEM_DATA_FOLLOW_REMIND = "data_follow_remind"
    const val ITEM_DATA_WEBDAV = "data_webdav"
    const val ITEM_DATA_AI = "data_ai"
    const val ITEM_DATA_AI_CHAT = "data_ai_chat"
    const val ITEM_DATA_BACKUP = "data_backup"
    const val ITEM_DATA_CLEAR_CACHE = "data_clear_cache"
    const val ITEM_DATA_VIDEO_CAP = "data_video_cap"
    const val ITEM_DATA_VIDEO_AGE = "data_video_age"
    const val ITEM_DATA_MAGNET_CACHE = "data_magnet_cache"
    const val ITEM_DATA_MAGNET_ENGINE = "data_magnet_engine"
    const val ITEM_DATA_LEGACY_SUBTITLE = "data_legacy_subtitle"

    val ALL_ITEMS = listOf(
        ITEM_ENGINE, ITEM_AUTOPLAY, ITEM_ADFILTER, ITEM_MAX_PARALLEL, ITEM_GRAVITY,
        ITEM_TMDB_KEY, ITEM_DEF_ORIENTATION, ITEM_TRANSITION, ITEM_AUDIO_LANG, ITEM_SUB_LANG,
        ITEM_ANIME4K, ITEM_FIRST_SCREEN_TRACE,
        ITEM_DATA_SUB_REFRESH, ITEM_DATA_FOLLOW_REMIND, ITEM_DATA_WEBDAV, ITEM_DATA_AI,
        ITEM_DATA_AI_CHAT, ITEM_DATA_BACKUP, ITEM_DATA_CLEAR_CACHE, ITEM_DATA_VIDEO_CAP,
        ITEM_DATA_VIDEO_AGE, ITEM_DATA_MAGNET_CACHE, ITEM_DATA_MAGNET_ENGINE,
        ITEM_DATA_LEGACY_SUBTITLE,
    )

    /** v1.2.16：数据组行；v1.2.19 起进数据设置子页面（不再进播放设置页）。 */
    val DATA_ITEMS = listOf(
        ITEM_DATA_SUB_REFRESH, ITEM_DATA_FOLLOW_REMIND, ITEM_DATA_WEBDAV, ITEM_DATA_AI,
        ITEM_DATA_AI_CHAT, ITEM_DATA_BACKUP, ITEM_DATA_CLEAR_CACHE, ITEM_DATA_VIDEO_CAP,
        ITEM_DATA_VIDEO_AGE, ITEM_DATA_MAGNET_CACHE, ITEM_DATA_MAGNET_ENGINE,
        ITEM_DATA_LEGACY_SUBTITLE,
    )

    val DEFAULT_LAYOUT: Map<String, List<String>> = mapOf(
        MAIN_PLAYBACK to listOf(ITEM_GRAVITY),
        MAIN_DATA to listOf(
            ITEM_DATA_SUB_REFRESH, ITEM_DATA_FOLLOW_REMIND, ITEM_DATA_WEBDAV, ITEM_DATA_AI,
            ITEM_DATA_AI_CHAT, ITEM_DATA_BACKUP, ITEM_DATA_CLEAR_CACHE, ITEM_DATA_VIDEO_CAP,
            ITEM_DATA_VIDEO_AGE, ITEM_DATA_MAGNET_CACHE, ITEM_DATA_MAGNET_ENGINE,
            ITEM_DATA_LEGACY_SUBTITLE,
        ),
        PAGE_PLAYBACK to listOf(
            ITEM_ENGINE, ITEM_AUTOPLAY, ITEM_ADFILTER, ITEM_MAX_PARALLEL, ITEM_TMDB_KEY,
            ITEM_DEF_ORIENTATION, ITEM_TRANSITION, ITEM_AUDIO_LANG, ITEM_SUB_LANG, ITEM_ANIME4K,
            ITEM_FIRST_SCREEN_TRACE,
        ),
        // v1.2.19：数据页默认空着（数据行默认全在主页面数据组里，用户长按拖进来收纳）。
        PAGE_DATA to emptyList(),
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

    /** 纯函数版 init（只读存的字符串），供单测直接验补齐/去重逻辑。 */
    internal fun normalize(raw: String?): Map<String, List<String>> {
        val saved = mutableMapOf<String, List<String>>()
        runCatching {
            val obj = JSONObject(raw ?: return@runCatching)
            for (container in DEFAULT_LAYOUT.keys) {
                val arr = obj.optJSONArray(container) ?: continue
                saved[container] = (0 until arr.length()).mapNotNull { arr.optString(it) }.filter { it in ALL_ITEMS }
            }
        }
        val out = mutableMapOf<String, List<String>>()
        val seen = mutableSetOf<String>()
        for (container in DEFAULT_LAYOUT.keys) {
            // 同一行只留第一次出现的位置（存坏了也不重影）。
            out[container] = (saved[container] ?: emptyList()).filter { seen.add(it) }
        }
        // 只补“哪都没住”的行（以后新加可移项，老布局不丢它）；已搬走的行不回默认容器。
        for (id in ALL_ITEMS) {
            if (id !in seen) {
                val home = DEFAULT_LAYOUT.entries.first { id in it.value }.key
                out[home] = out.getValue(home) + id
                seen.add(id)
            }
        }
        // v1.2.19：修串了页的行回主组——数据行进了播放页、播放行进了数据页的，
        // 都搬回各自的主组（1.2.19 中间态没发过版，纯属兜底，正常布局走不到这）。
        for (id in out.getValue(PAGE_PLAYBACK).filter { it in DATA_ITEMS }) {
            out[PAGE_PLAYBACK] = out.getValue(PAGE_PLAYBACK) - id
            out[MAIN_DATA] = out.getValue(MAIN_DATA) + id
        }
        for (id in out.getValue(PAGE_DATA).filter { it !in DATA_ITEMS }) {
            out[PAGE_DATA] = out.getValue(PAGE_DATA) - id
            out[MAIN_PLAYBACK] = out.getValue(MAIN_PLAYBACK) + id
        }
        return out
    }

    /** 把一项挪到目标容器指定位置（同容器 = 调顺序，跨容器 = 搬家），立即存盘。 */
    fun move(context: Context, itemId: String, toContainer: String, toIndex: Int) {
        val next = moveInLayout(_layout.value, itemId, toContainer, toIndex)
        _layout.value = next
        save(context, next)
    }

    /** 纯函数版 move（不碰 Context、不碰磁盘），供单测直接验搬家逻辑。 */
    internal fun moveInLayout(
        layout: Map<String, List<String>>,
        itemId: String,
        toContainer: String,
        toIndex: Int,
    ): Map<String, List<String>> {
        if (itemId !in ALL_ITEMS || toContainer !in DEFAULT_LAYOUT) return layout
        // v1.2.19：子页面只认自家行——数据行进不了播放页，播放行进不了数据页；
        // 主页面两个组之间互拖不受影响。拦下时原表不动（调用方不用另判）。
        if (toContainer == PAGE_PLAYBACK && itemId in DATA_ITEMS) return layout
        if (toContainer == PAGE_DATA && itemId !in DATA_ITEMS) return layout
        val next = layout.mapValues { it.value.toMutableList() }.toMutableMap()
        for (list in next.values) list.remove(itemId)
        val target = next.getValue(toContainer)
        target.add(toIndex.coerceIn(0, target.size), itemId)
        return next
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
