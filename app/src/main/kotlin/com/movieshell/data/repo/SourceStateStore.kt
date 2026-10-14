package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 站源启停状态（v0.24.0，单点）。
 *
 * 本地规则文件（js/py/php/web）默认启用；禁用的记 disabled 流 + prefs 持久化。
 * 首页推荐 / 聚合搜索 / 批量检测统一走这里判定，调用方不许各自另存一份。
 */
object SourceStateStore {

    private const val PREFS = "source_state"
    private const val KEY_DISABLED = "disabled_map"

    private val json = Json { ignoreUnknownKeys = true }

    /** kind -> 被禁用的文件名集合 */
    private val _disabled = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val disabled: StateFlow<Map<String, Set<String>>> = _disabled

    private var inited = false

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        _disabled.value = runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DISABLED, "{}") ?: "{}",
            ).mapValues { it.value.toSet() }
        }.getOrDefault(emptyMap())
    }

    fun isRuleEnabled(kind: String, fileName: String): Boolean =
        !_disabled.value.getOrDefault(kind, emptySet()).contains(fileName)

    fun setRuleEnabled(context: Context, kind: String, fileName: String, enabled: Boolean) {
        val map = _disabled.value.mapValues { it.value.toMutableSet() }.toMutableMap()
        val set = map.getOrPut(kind) { mutableSetOf() }
        if (enabled) set.remove(fileName) else set.add(fileName)
        _disabled.value = map
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                KEY_DISABLED,
                json.encodeToString(
                    MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                    map.mapValues { it.value.toList() },
                ),
            )
            .apply()
    }

    /** 有激活且未被禁用的 drpy 规则名，无则 null（首页/搜索聚合的单点过滤） */
    fun activeDrpyRule(): String? =
        DrpySourceManager.activeRuleName()?.takeIf { isRuleEnabled("js", it) }

    /** 有激活且未被禁用的 PY 规则名，无则 null */
    fun activePyRule(): String? =
        PySourceManager.activeRuleName()?.takeIf { isRuleEnabled("py", it) }
}
