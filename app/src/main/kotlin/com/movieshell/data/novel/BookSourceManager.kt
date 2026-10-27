package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.AtomicFile
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * v0.61.0 书源管理：落在 filesDir/book_sources.json。
 * - 增（导入 JSON 文本/文件）、删、启用/禁用；
 * - 内存 StateFlow 缓存，UI 直接 collect。
 */
object BookSourceManager {

    private const val TAG = "BookSourceManager"
    private const val FILE_NAME = "book_sources.json"
    // v0.89.0 内置真实书源的 key（url 去协议去尾斜杠），老用户补种时去重用
    private const val BUILTIN_KEY = "www.quanben.io"

    private val _sources = MutableStateFlow<List<BookSource>>(emptyList())
    val sources: StateFlow<List<BookSource>> = _sources.asStateFlow()

    // v0.186.0 第 9 组：改内存表再写盘的整段串行化——suspend 路径用 Mutex，
    // 唯一非 suspend 公开读函数 enabledSources 用 synchronized；不为加锁改任何公开函数签名。
    private val mutex = Mutex()
    private val syncLock = Any()

    private fun fileOf(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /**
     * 启动时调一次，把磁盘书源载入内存。
     * v0.247.0：返回载入是否成功——文件存在但读坏时返回 false（原文件已保全），
     * 调用方不能把这种空表误判成“没有书源”。条目级约定：单条解析失败只
     * 跳过该条（v0.211.0 逐条隔离的原意），不算整体失败；整体失败仅指文件
     * 不可读或顶层 JSON 非法，以及非空文件一条都解析不出来（等同于源全坏）。
     */
    suspend fun load(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = fileOf(context)
            if (!f.isFile) {
                _sources.value = builtIn()
                save(context)
                true
            } else {
                var ok = true
                var arrLen = 0
                val list = runCatching {
                    val arr = JSONArray(f.readText())
                    arrLen = arr.length()
                    // v0.211.0 审计修复：原来任一元素非对象时 getJSONObject
                    // 抛错，整个 runCatching 兜底返回空表，之后一次写盘就
                    // 覆盖原文件。逐条隔离：坏条目跳过，好条目照常载入。
                    (0 until arr.length()).mapNotNull {
                        runCatching { BookSource.fromJson(arr.getJSONObject(it).toString()) }.getOrNull()
                    }
                }.onFailure {
                    Log.w(TAG, "书源文件读取失败", it)
                    // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
                    AtomicFile.preserveCorrupt(f)
                    ok = false
                }.getOrDefault(emptyList())
                // v0.247.0：非空文件零条解析成功时视为整体失败，不让调用方
                // 误判成“用户没有书源”；原文件同样保全一份。部分条目失败时
                // 只记一条日志，好条目照常可用。
                if (ok && arrLen > 0 && list.isEmpty()) {
                    Log.w(TAG, "书源文件 $arrLen 条全部解析失败，视为载入失败")
                    AtomicFile.preserveCorrupt(f)
                    ok = false
                } else if (ok && arrLen > list.size) {
                    Log.w(TAG, "书源文件 ${arrLen - list.size} 条解析失败，已跳过")
                }
                // v0.107.0 P0-7：删光就是删光，不再自动复活内置源；
                // 内置源走书源管理页"恢复默认"手动加回（restoreBuiltin）
                _sources.value = list
                ok
            }
        }
    }

    /**
     * v0.186.0 第 9 组：save 返回 Boolean，写盘失败调用方能感知。
     * 只在 mutex 内调用，不自行加锁——Mutex 不可重入，内层再锁会死锁。
     * 写盘失败时各公开函数把内存表回滚到旧值，不让内存与磁盘不一致。
     */
    private suspend fun save(context: Context): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val arr = JSONArray()
            _sources.value.forEach { arr.put(it.toJson()) }
            // v0.170.0：原子写盘
            AtomicFile.writeText(fileOf(context), arr.toString())
        }.onFailure { Log.w(TAG, "书源保存失败", it) }.getOrDefault(false)
        if (!ok) Log.w(TAG, "书源保存失败")
        ok
    }

    /**
     * 导入：返回成功条数。写盘失败时内存回滚、返回 0。
     * v0.218.0（S20）：收敛成“解析＋调 importSources”一层——原来
     * import 与 importSources 的去重／回滚／返回值逻辑逐行相同。
     * 注意不套 mutex：Mutex 不可重入，解析是纯函数，锁只留在 importSources 里。
     */
    suspend fun import(context: Context, json: String): Int {
        val list = BookSource.listFromJson(json)
        if (list.isEmpty()) return 0
        return importSources(context, list)
    }

    /**
     * v0.84.0：导入已解析好的书源列表（订阅预览勾选后用）。
     * 去重规则与 import 一致：key 已存在的不重复加。返回成功条数。
     * 写盘失败时内存回滚、返回 0。
     */
    suspend fun importSources(context: Context, list: List<BookSource>): Int = mutex.withLock {
        if (list.isEmpty()) return@withLock 0
        val old = _sources.value
        val cur = old.toMutableList()
        var added = 0
        list.forEach { s ->
            if (cur.none { it.key == s.key }) {
                cur.add(s)
                added++
            }
        }
        _sources.value = cur
        if (!save(context)) {
            _sources.value = old
            return@withLock 0
        }
        added
    }

    suspend fun remove(context: Context, key: String) = mutex.withLock {
        val old = _sources.value
        _sources.value = old.filterNot { it.key == key }
        if (!save(context)) _sources.value = old
    }

    /**
     * v0.107.0 P0-7：手动恢复内置书源（已存在则不重复）。删光书源后靠这个加回来，
     * 不再靠 load() 自动复活。
     * @return true=新增了内置源，false=已存在（无需操作）或写盘失败
     */
    suspend fun restoreBuiltin(context: Context): Boolean = mutex.withLock {
        if (_sources.value.any { it.key == BUILTIN_KEY }) return@withLock false
        val old = _sources.value
        _sources.value = old + builtIn()
        if (!save(context)) {
            _sources.value = old
            return@withLock false
        }
        true
    }

    /**
     * v0.188.0：setEnabled 返回写盘成败——写盘失败时内存表已回滚，
     * 书源管理页据此提示“保存失败”，开关随 StateFlow 回滚值自动弹回。
     * key 不存在时无写盘必要，返回 true，不误报失败。
     */
    suspend fun setEnabled(context: Context, key: String, enabled: Boolean): Boolean = mutex.withLock {
        val old = _sources.value
        if (old.none { it.key == key }) return@withLock true
        _sources.value = old.map {
            if (it.key == key) it.copy(enabled = enabled) else it
        }
        if (!save(context)) {
            _sources.value = old
            return@withLock false
        }
        true
    }

    /**
     * v0.196.0：长按卡片编辑——改名/地址/分组。地址变了 key 也跟着变（key 由 url 派生），
     * 新 key 与已有书源撞了就拒绝；写盘失败内存回滚。返回 false=拒绝或保存失败。
     */
    suspend fun updateSource(context: Context, oldKey: String, name: String, url: String, group: String): Boolean = mutex.withLock {
        val old = _sources.value
        val target = old.find { it.key == oldKey } ?: return@withLock false
        if (name.isBlank() || url.isBlank()) return@withLock false
        val updated = target.copy(name = name, url = url, group = group)
        if (updated.key != oldKey && old.any { it.key == updated.key }) return@withLock false
        _sources.value = old.map { if (it.key == oldKey) updated else it }
        if (!save(context)) {
            _sources.value = old
            return@withLock false
        }
        // v0.211.0 审计修复：地址变了 key 跟着变，书架上用旧 key 的书
        // 要跟着迁，否则静默断源（写盘成功后再迁，失败不迁）。
        // v0.247.0（F09）：迁移写盘失败则回滚书源保存——源已改、书架还指旧 key
        // 就是断源，宁可报保存失败让用户重试，也不要留半截状态。
        if (updated.key != oldKey && NovelStore.migrateSourceKey(context, oldKey, updated.key) < 0) {
            _sources.value = old
            // v0.247.0（F09 复核）：回滚写盘本身也可能失败——不检查就记“已回滚”，
            // 内存恢复而磁盘仍是新书源，书架索引还指旧 key，跨存储不一致。
            if (save(context)) Log.w(TAG, "书源地址迁移失败，已回滚")
            else Log.w(TAG, "书源地址迁移失败，且旧书源回滚写盘失败：内存与磁盘不一致")
            return@withLock false
        }
        true
    }

    /**
     * v0.206.0：长按书源文本编辑——整条 JSON 替换。解析失败、新 key 与已有书源
     * 撞了、写盘失败都拒绝并回滚。返回 false=拒绝或保存失败。
     */
    suspend fun updateSourceJson(context: Context, oldKey: String, jsonText: String): Boolean = mutex.withLock {
        val parsed = BookSource.fromJson(jsonText) ?: return@withLock false
        val old = _sources.value
        val target = old.find { it.key == oldKey } ?: return@withLock false
        if (parsed.key != oldKey && old.any { it.key == parsed.key }) return@withLock false
        _sources.value = old.map { if (it.key == oldKey) parsed else it }
        if (!save(context)) {
            _sources.value = old
            return@withLock false
        }
        // v0.211.0 审计修复：同上，整条 JSON 替换导致 key 变时迁书架。
        // v0.247.0（F09）：同上，迁移失败回滚，不留半截状态。
        if (parsed.key != oldKey && NovelStore.migrateSourceKey(context, oldKey, parsed.key) < 0) {
            _sources.value = old
            // v0.247.0（F09 复核）：同上，回滚写盘失败要如实记，不谎报已回滚。
            if (save(context)) Log.w(TAG, "书源地址迁移失败，已回滚")
            else Log.w(TAG, "书源地址迁移失败，且旧书源回滚写盘失败：内存与磁盘不一致")
            return@withLock false
        }
        true
    }

    fun enabledSources(): List<BookSource> = synchronized(syncLock) {
        _sources.value.filter { it.enabled }
    }

    /**
     * v0.89.0 内置真实书源：全本小说网（https://www.quanben.io），规则编写于 2026-10-18。
     *
     * 站点：公开免费、无需登录、无付费章节；桌面端 HTML 结构简单、UTF-8。
     * 三步实测（2026-10-18，curl + 规则选择器复核）：
     * ①搜索 GET /index.php?c=book&a=search&keywords=斗罗大陆 → div.list2 共 48 条，
     *   首条"斗罗大陆 / 唐家三少"，bookUrl=/n/douluodalu/
     * ②详情页 /n/douluodalu/ 经 div.tool_button a 跳到 /n/douluodalu/list.html，
     *   ul.list3 li 共 83 章（短书目录完整；数百章以上的超长书站方只列前 24 + 后 23 章，
     *   中间章节不展示——已知限制）
     * ③正文 /n/douluodalu/2.html 的 div#content 为正常中文（约 1900 字），无乱码、
     *   无"登录后可见"。
     * 站改版后用户可在书源管理页自行更新规则、关闭或删除。
     */
    private fun builtIn(): List<BookSource> = listOf(
        BookSource(
            name = "全本小说网",
            url = "https://www.quanben.io",
            group = "内置",
            enabled = true,
            searchUrl = "https://www.quanben.io/index.php?c=book&a=search&keywords={{key}}",
            ruleSearchList = "div.list2",
            ruleSearchName = "h3 a@text",
            ruleSearchAuthor = "span[itemprop=author]@text",
            ruleSearchCover = "img@src",
            ruleSearchBookUrl = "h3 a@href",
            // 详情页无内嵌目录：先提"章节列表"按钮跳到目录页（NovelSourceEngine 两步跳转）
            ruleBookTocUrl = "div.tool_button a@href",
            ruleTocList = "ul.list3 li",
            ruleTocTitle = "@text",
            ruleTocUrl = "a@href",
            ruleContent = "div#content",
        ),
    )
}
