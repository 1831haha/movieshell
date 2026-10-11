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

/** 接口站源（订阅）条目快照 */
data class ApiSource(val name: String, val url: String, val enabled: Boolean = true)

/**
 * 接口站源最小持久化：filesDir/api_sources.json。
 *
 * 背景：SourcesScreen 里的接口站源分组目前是纯内存 remember，
 * 本 Store 提供独立的读写工具；备份/恢复走它，待站源页接入后
 * 即成为真正的持久化（默认条目与 SourcesScreen 写死的保持一致）。
 */
object ApiSourceStore {
    private const val TAG = "ApiSourceStore"
    private const val FILE_NAME = "api_sources.json"

    val defaults = listOf(
        ApiSource("量子资源", "https://cj.lziapi.com/api.php/provide/vod/", true),
        ApiSource("天空资源", "https://api.tiankongapi.com/api.php/provide/vod/", true),
        ApiSource("暴风资源", "https://bfzyapi.com/api.php/provide/vod/", false),
    )

    fun load(context: Context): List<ApiSource> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return defaults
        return try {
            file.readText().let(Json::parseToJsonElement).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: true
                ApiSource(name, url, enabled)
            }.ifEmpty { defaults }
        } catch (e: Exception) {
            Log.w(TAG, "读取 $FILE_NAME 失败，用默认订阅", e)
            defaults
        }
    }

    fun save(context: Context, list: List<ApiSource>) {
        try {
            val arr = buildJsonArray {
                list.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("url", s.url)
                        put("enabled", s.enabled)
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
 *   "activeRules": {"drpy": "...", "py": "...", "ai": null, "php": null, "web": null} }
 *
 * - apiSources 来自 ApiSourceStore（持久化文件，不存在则用默认订阅）；
 * - activeRules.drpy/py 读各 Manager 现有 API（内存状态）；
 * - ai/php/web 目前没有 active 概念，固定导出 null，恢复时忽略。
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
                    }
                }
            }
            putJsonObject("activeRules") {
                put("drpy", DrpySourceManager.activeRuleName())
                put("py", PySourceManager.activeRuleName())
                put("ai", null as String?)
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
                ApiSource(name, url, enabled)
            }.orEmpty()
            ApiSourceStore.save(context, apiSources.ifEmpty { ApiSourceStore.defaults })

            DrpySourceManager.init(context)
            PySourceManager.init(context)
            val rules = root["activeRules"]?.jsonObject
            rules?.stringOrNull("drpy")?.let { name ->
                if (File(context.filesDir, "drpy/$name").isFile) {
                    DrpySourceManager.setActiveRule(name)
                }
            }
            rules?.stringOrNull("py")?.let { name ->
                if (File(context.filesDir, "py/$name").isFile) {
                    PySourceManager.setActiveRule(name)
                }
            }
            // ai / php / web：当前版本无 active 概念，恢复时忽略
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
