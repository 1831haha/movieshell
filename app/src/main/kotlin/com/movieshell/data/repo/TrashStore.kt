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

    // v0.246.0 七轮（GPT 审）：回收站记录读改写串行化——原来 add/addSync/
    // removeByPath/remove 都是“读列表→改→写盘”无锁，两次 trashFile 并发时
    // 读到同一旧列表、各自加记录、后 commit 覆盖前一条，丢记录；
    // 改所有记录改动走同一把锁（Kotlin synchronized 可重入，trashFile 内调
    // addSync/removeByPath 不死锁），读列表展示方不持锁、允许短暂 stale。
    private val lock = Any()

    fun list(context: Context): List<TrashItem> =
        runCatching {
            trashJson.decodeFromString(
                ListSerializer(TrashItem.serializer()),
                prefs(context).getString(KEY_LIST, "[]") ?: "[]",
            )
        }.getOrDefault(emptyList()).sortedByDescending { it.deletedAt }

    fun count(context: Context): Int = list(context).size

    /** 规则文件移入回收站
     * v0.246.0：原来 copyTo 成功、delete() 返回 false 时整个 runCatching 返回 false，
     * 但 dst 副本已留下且无回收站记录，成孤儿。改分步判断：删源失败时删掉 dst 回滚。
     * v0.246.0 三轮（GPT 审）：① 目标路径唯一且不覆盖——原来时间戳 + 文件名 +
     * overwrite=true，同毫秒同名会覆盖已有回收站文件；改循环找不存在的路径、
     * overwrite=false。② 记录写入可确认——原来 add() 返回 Unit、apply() 异步，
     * 记录失败时源已删、副本成无记录孤儿；改顺序：先拷副本、再 commit() 同步写
     * 记录（删文件是用户主动操作，小 prefs 同步写可接受）、记录失败删副本回滚、
     * 最后删源，删源失败则删记录 + 删副本全回滚。 */
    fun trashFile(context: Context, kind: String, file: File, name: String): Boolean = synchronized(lock) {
        if (!file.isFile) return false
        val dir = trashDir(context, kind)
        // 唯一目标路径：时间戳 + 文件名，撞名则加序号，绝不覆盖已有文件
        var dst = File(dir, "${System.currentTimeMillis()}_${file.name}")
        var n = 1
        while (dst.exists()) {
            dst = File(dir, "${System.currentTimeMillis()}_${n++}_${file.name}")
        }
        if (!runCatching { file.copyTo(dst, overwrite = false) }.getOrDefault(false)) return false
        val item = TrashItem(kind = kind, name = name, trashPath = dst.absolutePath)
        if (!addSync(context, item)) {
            // v0.246.0 四轮（GPT 审）：副本删除结果也要确认——删失败会留无记录孤儿，
            // 删不掉就记日志留径，调用方返回 false，孤儿可凭日志路径手动清理。
            // v0.246.0 六轮（GPT 审）：addSync 落盘失败时内存可能已有记录（幻影），
            // 删副本后当进程内会看到指向不存在文件的记录；顺手清掉内存幻影，
            // 清不掉也无妨（重启后盘上无记录，自然干净；恢复遇缺文件会优雅失败）。
            if (!runCatching { dst.delete() }.getOrDefault(false)) {
                android.util.Log.w("TrashStore", "回收站记录写失败且副本删不掉，孤儿路径：${dst.absolutePath}")
            } else {
                runCatching { removeByPath(context, dst.absolutePath) }
            }
            return false
        }
        if (!file.delete()) {
            // v0.246.0 四轮（GPT 审）：记录删除失败时不删副本——原来不管记录删没删
            // 成都删副本，会留下指向不存在文件的记录；记录删不掉就保留副本 + 记录，
            // 源文件还在，用户可重试，绝不产生不一致状态。
            // v0.246.0 五轮（GPT 审）：记录删成功后副本删除结果也要确认——
            // 原来删副本返回值直接丢掉，删失败会留无记录孤儿且无日志；
            // 删失败则恢复记录（副本还在、记录回来，状态一致），恢复失败记日志。
            // v0.246.0 六轮（GPT 审）：commit()==false 不等于记录没变——
            // SharedPreferences 是先改内存再落盘，落盘失败返回 false 但内存已改；
            // 不能凭返回值断言记录还在。改以实际 list() 核验为准：
            // 记录还在内存 → 副本 + 记录一致，直接保留；
            // 记录已不在内存 → 内存已改但盘未落，重新写回恢复一致，写不回记日志
            // （盘上旧值仍有记录，重启后记录会回来，孤儿只是当进程内 transient）。
            if (removeByPath(context, dst.absolutePath)) {
                if (!runCatching { dst.delete() }.getOrDefault(false)) {
                    android.util.Log.w("TrashStore", "删源失败回滚时副本删不掉，恢复记录：${dst.absolutePath}")
                    if (!addSync(context, item)) {
                        android.util.Log.w("TrashStore", "删源失败回滚时记录恢复也失败，孤儿路径：${dst.absolutePath}")
                    }
                }
            } else {
                val stillThere = runCatching {
                    list(context).any { it.trashPath == dst.absolutePath }
                }.getOrDefault(true)
                if (stillThere) {
                    android.util.Log.w("TrashStore", "删源失败且记录回滚落盘失败，核验记录仍在，保留副本与记录：${dst.absolutePath}")
                } else {
                    android.util.Log.w("TrashStore", "删源失败且记录回滚落盘失败，核验记录已不在内存，重新写回：${dst.absolutePath}")
                    if (!addSync(context, item)) {
                        android.util.Log.w("TrashStore", "删源失败回滚核验后写回也失败，孤儿路径（重启后盘上旧记录会回来）：${dst.absolutePath}")
                    }
                }
            }
            return false
        }
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
                    // v0.216.0（C13）：原来先拷后删、删的返回值直接丢掉——
                    // 删失败时源文件与恢复副本同时存在，回收站记录又已删，
                    // 下次恢复因目标已存在被拒，等于丢了一份。改走改名（同盘
                    // 原子移动），改名失败才回退拷贝，且删源失败时撤回副本。
                    if (src.renameTo(dst)) {
                        true
                    } else {
                        src.copyTo(dst, overwrite = false)
                        if (dst.length() != src.length()) {
                            dst.delete()
                            false
                        } else if (src.delete()) {
                            true
                        } else {
                            dst.delete()
                            false
                        }
                    }
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
        synchronized(lock) {
            val all = list(context) + item
            prefs(context).edit()
                .putString(KEY_LIST, trashJson.encodeToString(ListSerializer(TrashItem.serializer()), all))
                .apply()
        }
    }

    // v0.246.0：同步写记录并返回成败——trashFile 回滚链依赖它确认记录已落盘。
    // commit() 同步写盘，只用于删文件这类用户主动操作，不用于高频路径。
    private fun addSync(context: Context, item: TrashItem): Boolean {
        return synchronized(lock) {
            runCatching {
                val all = list(context) + item
                prefs(context).edit()
                    .putString(KEY_LIST, trashJson.encodeToString(ListSerializer(TrashItem.serializer()), all))
                    .commit()
            }.getOrDefault(false)
        }
    }

    // v0.246.0：按 trashPath 删一条记录（trashFile 删源失败时的回滚用），返回成败。
    private fun removeByPath(context: Context, trashPath: String): Boolean {
        return synchronized(lock) {
            runCatching {
                val all = list(context).filterNot { it.trashPath == trashPath }
                prefs(context).edit()
                    .putString(KEY_LIST, trashJson.encodeToString(ListSerializer(TrashItem.serializer()), all))
                    .commit()
            }.getOrDefault(false)
        }
    }

    private fun remove(context: Context, id: String) {
        synchronized(lock) {
            val all = list(context).filterNot { it.id == id }
            prefs(context).edit()
                .putString(KEY_LIST, trashJson.encodeToString(ListSerializer(TrashItem.serializer()), all))
                .apply()
        }
    }

    private fun trashDir(context: Context, kind: String): File =
        File(StoragePaths.baseDir(context), ".trash/$kind").also { it.mkdirs() }

    private fun kindDir(context: Context, kind: String): File = when (kind) {
        "js" -> StoragePaths.jsDir(context)
        "py" -> StoragePaths.pyDir(context)
        "php" -> PhpSourceManager.rulesDir(context)
        else -> StoragePaths.webDir(context)
    }
}
