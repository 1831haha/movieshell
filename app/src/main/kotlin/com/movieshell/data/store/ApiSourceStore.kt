package com.movieshell.data.store

import android.content.Context
import android.util.Log
import com.movieshell.core.util.AtomicFile
import com.movieshell.data.source_mgr.ApiSource
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
     * v0.246.0：内存缓存 + 文件修改时间校验——原来每次 load 都全量读盘解析，
     * 首页/搜索/源选择页等主线程调用点频繁触发。缓存命中直接返回内存；
     * 文件被外部改动（备份恢复等）时时间戳变化，自动重读，不怕 stale。
     */
    private var cached: List<ApiSource>? = null
    private var cachedModified = -1L

    fun load(context: Context): List<ApiSource> {
        val file = File(context.filesDir, FILE_NAME)
        val modified = if (file.isFile) file.lastModified() else 0L
        if (cached != null && cachedModified == modified) return cached!!
        val list = parseFile(file) ?: return emptyList()
        val result = purgeBuiltinDefaultsOnce(context, list)
        cached = result
        cachedModified = if (file.isFile) file.lastModified() else 0L
        return result
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
        val result = if (bytes == null) runCatching { if (file.isFile) file.delete() else true }.getOrDefault(false)
        else AtomicFile.writeText(file, bytes.toString(Charsets.UTF_8))
        // v0.246.0：绕开 save 直接写文件，显式失效缓存（时间戳毫秒粒度可能撞上）
        if (result) invalidateCache()
        return result
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
            // v0.217.0（F64）：写盘成功才标“已清理”——原来写盘结果看
            // 都不看就标了，万一那次写盘失败，下次读就不清理了，默认源
            // 原样复活；失败时返回原列表（盘上还是旧的），下次启动重跑。
            if (save(context, kept)) {
                Log.i(TAG, "已清理内置默认源 ${list.size - kept.size} 个")
                prefs.edit().putBoolean(KEY_DEFAULTS_PURGED, true).apply()
                return kept
            }
            Log.w(TAG, "内置默认源清理写盘失败，未标记，下次启动重跑")
            return list
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
        // v0.246.0：写盘成功同步缓存，失败不更新（盘上还是旧的，下次读按时间戳重读）
        if (ok) {
            cached = list
            cachedModified = File(context.filesDir, FILE_NAME).lastModified()
        }
        return ok
    }

    /** 回滚写回后缓存失效——restoreRaw 绕开 save 直接写文件，时间戳变了下次 load 自动重读。 */
    fun invalidateCache() {
        cached = null
        cachedModified = -1L
    }
}
