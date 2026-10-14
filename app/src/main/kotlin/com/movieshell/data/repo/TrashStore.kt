package com.movieshell.data.repo

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 回收站条目 */
@Serializable
data class TrashItem(
    val id: String = UUID.randomUUID().toString(),
    /** js / py / php / web / sub / live-manual */
    val kind: String = "",
    /** 展示名 */
    val name: String = "",
    /** 订阅 url / 频道 url 等补充信息 */
    val detail: String = "",
    /** 文件类：移入回收站后的绝对路径；恢复时搬回原目录 */
    val trashPath: String = "",
    val deletedAt: Long = System.currentTimeMillis(),
)

private val trashJson = Json { ignoreUnknownKeys = true }

/**
 * 回收站（v0.24.0）。
 *
 * 站源页的删除改为软删除：规则文件搬进 xiaying/.trash/<kind>/（带时间戳防重名），
 * 订阅/手动直播频道只记元数据。恢复=搬回原位/重新添加；彻底删除=删文件+清记录。
 */
object TrashStore {

    private const val PREFS = "trash_prefs"
    private const val KEY_LIST = "trash_list"

    fun list(context: Context): List<TrashItem> =
        runCatching {
            trashJson.decodeFromString(
                ListSerializer(TrashItem.serializer()),
                prefs(context).getString(KEY_LIST, "[]") ?: "[]",
            )
        }.getOrDefault(emptyList()).sortedByDescending { it.deletedAt }

    fun count(context: Context): Int = list(context).size

    /** 规则文件移入回收站 */
    fun trashFile(context: Context, kind: String, file: File, name: String): Boolean {
        if (!file.isFile) return false
        val dst = File(trashDir(context, kind), "${System.currentTimeMillis()}_${file.name}")
        if (!runCatching { file.copyTo(dst, overwrite = true) && file.delete(); true }
                .getOrDefault(false)) return false
        add(context, TrashItem(kind = kind, name = name, trashPath = dst.absolutePath))
        return true
    }

    /** 订阅移入回收站（只记元数据） */
    fun trashSubscription(context: Context, name: String, url: String) {
        add(context, TrashItem(kind = "sub", name = name, detail = url))
    }

    /** 手动直播频道移入回收站（只记元数据） */
    fun trashLiveManual(context: Context, channel: LiveChannel) {
        add(
            context,
            TrashItem(kind = "live-manual", name = channel.name, detail = channel.url),
        )
    }

    /** 恢复：文件搬回原目录；订阅/手动频道重新添加。失败返回 false */
    fun restore(context: Context, item: TrashItem): Boolean {
        val ok = when (item.kind) {
            "js", "py", "php", "web" -> {
                val src = File(item.trashPath)
                if (!src.isFile) return false
                val dst = File(kindDir(context, item.kind), src.name.substringAfter('_'))
                runCatching {
                    if (dst.exists()) return false
                    src.copyTo(dst, overwrite = false) && src.delete()
                }.getOrDefault(false)
            }
            "sub" -> {
                val cur = ApiSourceStore.load(context)
                if (cur.any { it.url == item.detail }) true
                else runCatching {
                    ApiSourceStore.save(context, cur + ApiSource(item.name, item.detail, true)); true
                }.getOrDefault(false)
            }
            "live-manual" ->
                LiveStore.addManual(context, item.name, item.detail)
            else -> false
        }
        if (ok) remove(context, item.id)
        return ok
    }

    fun deleteForever(context: Context, item: TrashItem) {
        if (item.trashPath.isNotBlank()) runCatching { File(item.trashPath).delete() }
        remove(context, item.id)
    }

    fun clearAll(context: Context) {
        list(context).forEach { deleteForever(context, it) }
    }

    // ---------------- 内部 ----------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun add(context: Context, item: TrashItem) {
        val all = list(context) + item
        prefs(context).edit()
            .putString(KEY_LIST, trashJson.encodeToString(ListSerializer(TrashItem.serializer()), all))
            .apply()
    }

    private fun remove(context: Context, id: String) {
        val all = list(context).filterNot { it.id == id }
        prefs(context).edit()
            .putString(KEY_LIST, trashJson.encodeToString(ListSerializer(TrashItem.serializer()), all))
            .apply()
    }

    private fun trashDir(context: Context, kind: String): File =
        File(StoragePaths.baseDir(context), ".trash/$kind").also { it.mkdirs() }

    private fun kindDir(context: Context, kind: String): File = when (kind) {
        "js" -> StoragePaths.jsDir(context)
        "py" -> StoragePaths.pyDir(context)
        "php" -> StoragePaths.phpDir(context)
        else -> StoragePaths.webDir(context)
    }
}
