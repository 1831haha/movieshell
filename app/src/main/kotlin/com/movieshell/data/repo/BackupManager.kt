package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** 接口站源（订阅）条目快照；v0.112.0 加 note/group（订阅级备注/分组），缺字段的老数据读作空串 */
data class ApiSource(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val note: String = "",
    val group: String = "",
)

/**
 * 接口站源最小持久化：filesDir/api_sources.json。
 *
 * v0.27.2：无预置默认源；文件不存在/解析失败一律返回空列表，由站源页空态引导添加。
 */
object ApiSourceStore {
    private const val TAG = "ApiSourceStore"
    private const val FILE_NAME = "api_sources.json"
    private const val PREFS = "api_source_store"
    private const val KEY_DEFAULTS_PURGED = "builtin_defaults_purged_v0273"

    /**
     * v0.27.2 及之前预置过的内置默认源（精确 name+url 配对才算，避免误删用户自己加的同名订阅）。
     * v0.27.3：老用户升级时一次性清掉，只做一次。
     */
    private val builtinDefaults = setOf(
        "量子资源" to "https://cj.lziapi.com/api.php/provide/vod/",
        "天空资源" to "https://api.tiankongapi.com/api.php/provide/vod/",
        "暴风资源" to "https://bfzyapi.com/api.php/provide/vod/",
    )

    /**
     * v0.27.2：不再预置默认源。新安装源列表初始为空（空态引导用户添加）；
     * 老用户已有 api_sources.json 的不受影响。
     */
    fun load(context: Context): List<ApiSource> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        val list = try {
            file.readText().let(Json::parseToJsonElement).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: true
                val note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val group = o["group"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ApiSource(name, url, enabled, note, group)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 $FILE_NAME 失败，返回空列表", e)
            emptyList()
        }
        return purgeBuiltinDefaultsOnce(context, list)
    }

    /**
     * v0.27.3：老用户升级一次性迁移——清掉 v0.27.2 及之前预置的内置默认源。
     * 只认精确的 name+url 配对，用户自己后加的订阅（哪怕同名不同 URL）不动。
     */
    private fun purgeBuiltinDefaultsOnce(context: Context, list: List<ApiSource>): List<ApiSource> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULTS_PURGED, false)) return list
        val kept = list.filterNot { (it.name to it.url) in builtinDefaults }
        if (kept.size != list.size) {
            save(context, kept)
            Log.i(TAG, "已清理内置默认源 ${list.size - kept.size} 个")
        }
        prefs.edit().putBoolean(KEY_DEFAULTS_PURGED, true).apply()
        return kept
    }

    fun save(context: Context, list: List<ApiSource>) {
        try {
            val arr = buildJsonArray {
                list.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("url", s.url)
                        put("enabled", s.enabled)
                        put("note", s.note)
                        put("group", s.group)
                    }
                }
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "写入 $FILE_NAME 失败", e)
        }
    }
}

/**
 * 订阅备份 / 恢复。
 *
 * 备份 JSON 格式：
 * { "version": 1,
 *   "apiSources": [ {"name","url","enabled"} ],
 *   "activeRules": {"drpy": "...", "py": "...", "php": null, "web": null} }
 *
 * - apiSources 来自 ApiSourceStore（持久化文件，不存在则用默认订阅）；
 * - activeRules.drpy/py 读各 Manager 现有 API（内存状态）；
 * - php/web 目前没有 active 概念，固定导出 null，恢复时忽略。
 */
object BackupManager {
    private const val TAG = "BackupManager"

    /** 备份格式版本；恢复时校验，不一致直接拒绝并提示 */
    const val BACKUP_VERSION = 1

    sealed interface RestoreResult {
        data object Ok : RestoreResult
        data class Failed(val reason: String) : RestoreResult
    }

    /** 导出当前配置为 JSON 字符串 */
    fun exportJson(context: Context): String {
        DrpySourceManager.init(context)
        PySourceManager.init(context)
        val root = buildJsonObject {
            put("version", BACKUP_VERSION)
            putJsonArray("apiSources") {
                ApiSourceStore.load(context).forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("url", s.url)
                        put("enabled", s.enabled)
                        put("note", s.note)
                        put("group", s.group)
                    }
                }
            }
            putJsonObject("activeRules") {
                put("drpy", DrpySourceManager.activeRuleName())
                put("py", PySourceManager.activeRuleName())
                put("php", null as String?)
                put("web", null as String?)
            }
        }
        return root.toString()
    }

    /**
     * 从 JSON 恢复：覆盖写入接口站源文件 + 切换 drpy/py 的 active 规则。
     * 规则文件在磁盘上不存在时跳过（不报错，Manager 本来也会拒绝不存在的规则）。
     */
    fun importJson(context: Context, json: String): RestoreResult {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "备份文件不是有效 JSON", e)
            return RestoreResult.Failed("文件不是有效的 JSON")
        }
        val version = root["version"]?.jsonPrimitive?.intOrNull
        if (version != BACKUP_VERSION) {
            return RestoreResult.Failed("备份版本不匹配（需要 version=$BACKUP_VERSION）")
        }
        return try {
            val apiSources = root["apiSources"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: true
                val note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val group = o["group"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ApiSource(name, url, enabled, note, group)
            }.orEmpty()
            ApiSourceStore.save(context, apiSources)

            DrpySourceManager.init(context)
            PySourceManager.init(context)
            val rules = root["activeRules"]?.jsonObject
            rules?.stringOrNull("drpy")?.let { name ->
                if (File(StoragePaths.jsDir(context), name).isFile) {
                    DrpySourceManager.setActiveRule(name)
                }
            }
            rules?.stringOrNull("py")?.let { name ->
                if (File(StoragePaths.pyDir(context), name).isFile) {
                    PySourceManager.setActiveRule(name)
                }
            }
            // php / web：当前版本无 active 概念，恢复时忽略
            RestoreResult.Ok
        } catch (e: Exception) {
            Log.w(TAG, "恢复备份失败", e)
            RestoreResult.Failed("解析失败：${e.message}")
        }
    }

    /** 取 JsonObject 里的可空字符串字段（JsonNull / 缺失 / 非字符串都返回 null） */
    private fun kotlinx.serialization.json.JsonObject.stringOrNull(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return el.jsonPrimitive.contentOrNull
    }
}
