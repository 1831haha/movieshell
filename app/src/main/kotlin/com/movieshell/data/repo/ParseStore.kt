package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.ParseConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 用户自定义解析接口（v0.19.0）：设置页增删，SharedPreferences 持久化。
 *
 * 生效顺序由 [merged] 统一：自定义优先，其次订阅顶层 parses。
 * 单一条目失败不影响其他（ParseApi 内部已按顺序尝试）。
 */
object ParseStore {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_CUSTOM_PARSES = "custom_parses_json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _custom = MutableStateFlow<List<ParseConfig>>(emptyList())
    val custom: StateFlow<List<ParseConfig>> = _custom.asStateFlow()

    private var loaded = false

    /** 懒加载：首次调用时从 prefs 读入。 */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _custom.value = runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_PARSES, null).orEmpty()
            if (raw.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(ParseConfig.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun persist(context: Context, list: List<ParseConfig>) {
        _custom.value = list
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(
                    KEY_CUSTOM_PARSES,
                    json.encodeToString(ListSerializer(ParseConfig.serializer()), list)
                )
                .apply()
        }.onFailure { LogBus.w("ParseStore", "自定义解析持久化失败：${it.message}") }
    }

    /** 新增一条（name/url 非空，type 只认 0/1）。 */
    fun add(context: Context, name: String, url: String, type: Int) {
        load(context)
        val n = name.trim()
        val u = url.trim()
        if (n.isEmpty() || u.isEmpty()) return
        persist(context, _custom.value + ParseConfig(name = n, url = u, type = type.coerceIn(0, 1)))
        LogBus.i("ParseStore", "新增自定义解析：$n")
    }

    /** 按下标删除。 */
    fun remove(context: Context, index: Int) {
        load(context)
        val cur = _custom.value
        if (index !in cur.indices) return
        persist(context, cur.filterIndexed { i, _ -> i != index })
        LogBus.i("ParseStore", "删除自定义解析：${cur[index].name}")
    }

    /**
     * 生效的解析线路：自定义优先 + 订阅 parses 去重（按 url 去重，自定义已覆盖的不重复加）。
     * 解析链路的唯一合并入口。
     */
    fun merged(context: Context, subParses: List<ParseConfig>): List<ParseConfig> {
        load(context)
        val custom = _custom.value
        if (custom.isEmpty()) return subParses
        val seen = custom.map { it.url.trim() }.toMutableSet()
        return custom + subParses.filter { seen.add(it.url.trim()) }
    }
}
