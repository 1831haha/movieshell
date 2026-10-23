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
    private const val KEY_ACTIVE_KIND = "active_kind"
    private const val KEY_ACTIVE_NAME = "active_name"

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

    /**
     * v0.186.0 M20：删源清理入口——把某规则从禁用表里摘掉，不存在则无操作。
     * 与 setRuleEnabled(enabled=true) 同写盘路径，删源与删订阅共用同一入口，
     * 同名文件日后重新导入时旧禁用态不再复活。
     */
    fun removeDisabled(context: Context, kind: String, fileName: String) {
        if (_disabled.value.getOrDefault(kind, emptySet()).contains(fileName)) {
            setRuleEnabled(context, kind, fileName, true)
        }
    }

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

    /** 有激活且未被禁用的 PHP 规则名，无则 null（v0.147.0：换源弹窗加 PHP） */
    fun activePhpRule(): String? =
        PhpSourceManager.activeRuleName()?.takeIf { isRuleEnabled("php", it) }

    /**
     * v0.147.0：选中态持久化——此前 activeName 只在内存里，App 一重启就丢，
     * 首页回空态、设置页变“未设置”。换源时记一对 (kind, name)，启动时恢复。
     * 持久化只发生在换源/清源的调用点，不进各 Manager 的 setActiveRule，
     * 避免清其他 kind 的 null 调用把刚记下的值冲掉。
     */
    fun saveActiveRule(context: Context, kind: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_KIND, kind)
            .putString(KEY_ACTIVE_NAME, name)
            .apply()
    }

    fun clearActiveRule(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE_KIND)
            .remove(KEY_ACTIVE_NAME)
            .apply()
    }

    /**
     * 启动恢复：仅当文件仍在且未被禁用时恢复选中态，否则清掉旧记录。
     * 须在各 Manager init 与本 Store init 之后调（MainActivity onCreate）。
     * Drpy 的 setActiveRule 会当场建 V8 引擎读文件，文件不存在时它自己会
     * 放弃（activeName 保持 null），这里再加一层文件校验是双保险。
     */
    fun restoreActiveRule(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = prefs.getString(KEY_ACTIVE_KIND, null) ?: return
        val name = prefs.getString(KEY_ACTIVE_NAME, null) ?: return
        val exists = when (kind) {
            "js" -> DrpySourceManager.listRules().any { it.name == name }
            "py" -> PySourceManager.listRules().any { it.name == name }
            "php" -> PhpSourceManager.listRules().any { it.name == name }
            else -> false
        }
        if (!exists || !isRuleEnabled(kind, name)) {
            clearActiveRule(context)
            return
        }
        when (kind) {
            "js" -> DrpySourceManager.setActiveRule(name)
            "py" -> PySourceManager.setActiveRule(name)
            "php" -> PhpSourceManager.setActiveRule(name)
        }
    }
}
