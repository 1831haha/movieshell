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
    /** v0.122.0：多仓自动展开出的子仓记录其父仓 URL；空串=用户自加的顶层订阅 */
    val parentUrl: String = "",
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
     * v0.170.0：文件存在但解析失败时保全原文件为 .corrupt.bak 再返回空列表——
     * 空列表只代表"本次读不到"，绝不能由下次写盘把旧数据悄悄清掉。
     */
    fun load(context: Context): List<ApiSource> {
        val list = parseFile(File(context.filesDir, FILE_NAME)) ?: return emptyList()
        return purgeBuiltinDefaultsOnce(context, list)
    }

    /** 文件存在但解析失败时为 true（导出/云同步用：拒绝用空表覆盖好备份）。 */
    fun isCorrupt(context: Context): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return false
        return parseFile(file) == null
    }

    /** 当前文件的原始字节；文件不存在返回 null（回滚时删文件即还原）。 */
    fun readRawBytes(context: Context): ByteArray? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** 回滚用：bytes 为 null 删文件，否则原子写回。返回是否成功。 */
    fun restoreRaw(context: Context, bytes: ByteArray?): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        if (bytes == null) return runCatching { if (file.isFile) file.delete() else true }.getOrDefault(false)
        return AtomicFile.writeText(file, bytes.toString(Charsets.UTF_8))
    }

    /** 解析文件；返回 null = 文件存在但解析失败（已保全原文件）。缺 name/url 的单条静默跳过（读侧容错）。 */
    private fun parseFile(file: File): List<ApiSource>? {
        if (!file.isFile) return emptyList()
        return try {
            file.readText().let(Json::parseToJsonElement).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: true
                val note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val group = o["group"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val parentUrl = o["parentUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ApiSource(name, url, enabled, note, group, parentUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 $FILE_NAME 失败，已保全原文件", e)
            AtomicFile.preserveCorrupt(file)
            null
        }
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

    /** v0.170.0：原子写盘，返回是否成功——写盘失败不许再对外报成功。 */
    fun save(context: Context, list: List<ApiSource>): Boolean {
        val arr = buildJsonArray {
            list.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("url", s.url)
                    put("enabled", s.enabled)
                    put("note", s.note)
                    put("group", s.group)
                    put("parentUrl", s.parentUrl)
                }
            }
        }
        val ok = AtomicFile.writeText(File(context.filesDir, FILE_NAME), arr.toString())
        if (!ok) Log.w(TAG, "写入 $FILE_NAME 失败")
        return ok
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

    sealed interface ExportResult {
        data class Ok(val json: String) : ExportResult
        data class Failed(val reason: String) : ExportResult
    }

    /**
     * 导出当前配置为 JSON 字符串。
     * v0.170.0：本地订阅文件已损坏时拒绝导出——空表绝不允许再覆盖云端好备份。
     */
    fun exportJson(context: Context): ExportResult {
        DrpySourceManager.init(context)
        PySourceManager.init(context)
        if (ApiSourceStore.isCorrupt(context)) {
            Log.w(TAG, "本地订阅文件已损坏，拒绝导出")
            return ExportResult.Failed("本地订阅文件已损坏，拒绝导出（避免覆盖云端好备份）")
        }
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
                        put("parentUrl", s.parentUrl)
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
        return ExportResult.Ok(root.toString())
    }

    /**
     * 从 JSON 恢复：覆盖写入接口站源文件 + 切换 drpy/py 的 active 规则。
     * 规则文件在磁盘上不存在时跳过（不报错，Manager 本来也会拒绝不存在的规则）。
     *
     * v0.170.0 恢复链（四段，缺一段都不算修好）：
     * 1. 全部校验先行——版本、apiSources 键存在且为数组、每条订阅都有非空 name/url、
     *    activeRules 格式合法；任何一项不过直接拒绝，当前数据一动不动。
     *    老版本合法备份（version=1、条目含 name/url，note/group/parentUrl 可缺）照常恢复。
     * 2. 快照——恢复前先存当前订阅文件字节 + drpy/py 当前规则。
     * 3. 写盘后回读校验——原子写盘返回失败、或回读条数对不上，都算失败。
     * 4. 回滚——写盘或切规则任何一步失败，文件与规则双双还原，再返回失败原因。
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
        // ---- 1. 全部校验先行 ----
        val apiEl = root["apiSources"]
            ?: return RestoreResult.Failed("备份缺少 apiSources，拒绝恢复（当前数据未动）")
        val arr = try {
            apiEl.jsonArray
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 apiSources 不是数组，拒绝恢复（当前数据未动）")
        }
        val apiSources = ArrayList<ApiSource>()
        var dropped = 0
        for (el in arr) {
            val o = try {
                el.jsonObject
            } catch (e: Exception) {
                dropped++
                continue
            }
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (name == null || url == null) {
                dropped++
                continue
            }
            val enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val group = o["group"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val parentUrl = o["parentUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            apiSources.add(ApiSource(name, url, enabled, note, group, parentUrl))
        }
        if (dropped > 0) {
            return RestoreResult.Failed("备份有 $dropped 条订阅缺名称或地址，拒绝恢复（当前数据未动）")
        }
        val rules = try {
            root["activeRules"]?.jsonObject
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules 格式不对，拒绝恢复（当前数据未动）")
        }
        val drpyName = try {
            rules?.strictString("drpy")
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules.drpy 格式不对，拒绝恢复（当前数据未动）")
        }
        val pyName = try {
            rules?.strictString("py")
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules.py 格式不对，拒绝恢复（当前数据未动）")
        }
        // ---- 2. 快照当前状态（回滚用） ----
        DrpySourceManager.init(context)
        PySourceManager.init(context)
        val prevBytes = ApiSourceStore.readRawBytes(context)
        val prevDrpy = DrpySourceManager.activeRuleName()
        val prevPy = PySourceManager.activeRuleName()
        fun rollback(): Boolean {
            var ok = ApiSourceStore.restoreRaw(context, prevBytes)
            runCatching { DrpySourceManager.setActiveRule(prevDrpy) }.onFailure { ok = false }
            runCatching { PySourceManager.setActiveRule(prevPy) }.onFailure { ok = false }
            return ok
        }
        // ---- 3. 写盘 + 回读校验 ----
        if (!ApiSourceStore.save(context, apiSources)) {
            return RestoreResult.Failed("写入订阅文件失败，当前数据未动")
        }
        val writtenOk = ApiSourceStore.readRawBytes(context)?.let { bytes ->
            runCatching {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.size == apiSources.size
            }.getOrDefault(false)
        } ?: false
        if (!writtenOk) {
            rollback()
            return RestoreResult.Failed("写入后校验失败，已还原")
        }
        // ---- 4. 切规则（失败则连文件带规则一起还原） ----
        try {
            drpyName?.let { name ->
                if (File(StoragePaths.jsDir(context), name).isFile) {
                    DrpySourceManager.setActiveRule(name)
                }
            }
            pyName?.let { name ->
                if (File(StoragePaths.pyDir(context), name).isFile) {
                    PySourceManager.setActiveRule(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "切换当前规则失败，已还原", e)
            rollback()
            return RestoreResult.Failed("切换当前规则失败，已还原：${e.message}")
        }
        // php / web：当前版本无 active 概念，恢复时忽略
        return RestoreResult.Ok
    }

    /**
     * 取 JsonObject 里的字符串字段：缺失 / JsonNull → null；字符串 → 值；
     * 其它类型 → 抛错（恢复校验用，格式不对直接拒绝，不许静默跳过）。
     */
    private fun kotlinx.serialization.json.JsonObject.strictString(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        val prim = el as? kotlinx.serialization.json.JsonPrimitive
            ?: throw IllegalArgumentException("$key 不是字符串")
        if (!prim.isString) throw IllegalArgumentException("$key 不是字符串")
        return prim.content
    }
}
