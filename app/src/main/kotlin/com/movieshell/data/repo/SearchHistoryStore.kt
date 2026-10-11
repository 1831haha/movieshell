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
 * 搜索历史持久化：filesDir/search_history.json。
 *
 * - 历史最多保留 MAX_SIZE 条（20）；
 * - add()：空串不记；已存在先删再顶到最前面（去重 + 置顶）；
 * - 提供 list / add / clear，全部静默失败不抛异常。
 */
object SearchHistoryStore {
    private const val TAG = "SearchHistoryStore"
    private const val FILE_NAME = "search_history.json"
    private const val MAX_SIZE = 20

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 历史列表：最新在前。 */
    fun list(context: Context): List<String> {
        val f = file(context)
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
            Log.w(TAG, "读取 $FILE_NAME 失败", e)
            emptyList()
        }
    }

    /** 写入一条：去重后置顶，超长截断。 */
    fun add(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        try {
            val updated = (listOf(q) + list(context).filter { it != q }).take(MAX_SIZE)
            val arr = buildJsonArray { updated.forEach { s: String -> add(JsonPrimitive(s)) } }
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "写入 $FILE_NAME 失败", e)
        }
    }

    /** 清空全部历史。 */
    fun clear(context: Context) {
        try {
            file(context).writeText("[]")
        } catch (e: Exception) {
            Log.w(TAG, "清空 $FILE_NAME 失败", e)
        }
    }
}
