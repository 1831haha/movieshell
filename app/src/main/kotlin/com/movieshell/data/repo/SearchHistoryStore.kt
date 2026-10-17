package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.File

/**
 * 搜索历史持久化：filesDir/search_history[_scope].json。
 *
 * - 历史最多保留 MAX_SIZE 条（20）；
 * - add()：空串不记；已存在先删再顶到最前面（去重 + 置顶）；
 * - 提供 list / add / remove / clear，全部静默失败不抛异常。
 * - v0.67.0：支持 scope 分作用域（视频/小说各存一份）；加 remove() 单条删除。
 */
object SearchHistoryStore {
    private const val TAG = "SearchHistoryStore"
    private const val FILE_PREFIX = "search_history"
    private const val MAX_SIZE = 20

    private fun file(context: Context, scope: String): File =
        File(context.filesDir, if (scope.isBlank()) "$FILE_PREFIX.json" else "${FILE_PREFIX}_$scope.json")

    /** 历史列表：最新在前。 */
    fun list(context: Context, scope: String = ""): List<String> {
        val f = file(context, scope)
        if (!f.isFile) return emptyList()
        return try {
            val arr: JsonArray =
                Json.parseToJsonElement(f.readText()) as? JsonArray ?: return emptyList()
            val out = ArrayList<String>(arr.size)
            for (el: JsonElement in arr) {
                val prim: JsonPrimitive = el as? JsonPrimitive ?: continue
                if (!prim.isString) continue
                val s: String = prim.content.trim()
                if (s.isNotEmpty()) out.add(s)
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "读取历史失败", e)
            emptyList()
        }
    }

    /** 写入一条：去重后置顶，超长截断。 */
    fun add(context: Context, query: String, scope: String = "") {
        val q = query.trim()
        if (q.isEmpty()) return
        try {
            val updated = (listOf(q) + list(context, scope).filter { it != q }).take(MAX_SIZE)
            val arr = buildJsonArray { updated.forEach { s: String -> add(JsonPrimitive(s)) } }
            file(context, scope).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "写入历史失败", e)
        }
    }

    /** 删除单条。 */
    fun remove(context: Context, query: String, scope: String = "") {
        try {
            val updated = list(context, scope).filter { it != query.trim() }
            val arr = buildJsonArray { updated.forEach { s: String -> add(JsonPrimitive(s)) } }
            file(context, scope).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "删除历史失败", e)
        }
    }

    /** 清空全部历史。 */
    fun clear(context: Context, scope: String = "") {
        try {
            file(context, scope).writeText("[]")
        } catch (e: Exception) {
            Log.w(TAG, "清空历史失败", e)
        }
    }
}
