package com.movieshell.data.novel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.source.ChaquopyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * v0.64.0 本地 PY 书源管理。
 *
 * - 书源文件落在 xiaying/py/book/（与视频 PY 源 xiaying/py/ 分开，互不干扰）。
 * - 导入：文件选择器选 .py → 拷贝 → 校验含 bookSearch 方法。
 * - 启用/禁用：落在 SharedPreferences pybook_prefs 的 StringSet。
 * - Novel.sourceKey 约定为 "pybook:<文件名>"，阅读器凭此前缀走 PY 链路。
 */
object PyBookSourceManager {

    private const val TAG = "PyBookSourceManager"
    private const val PREFS = "pybook_prefs"
    private const val KEY_ENABLED = "enabled"

    /** Novel.sourceKey 前缀：PY 书源加进书架的书用它标记来源。 */
    const val SOURCE_KEY_PREFIX = "pybook:"

    data class PyBookRule(val name: String, val title: String, val sizeKb: Long)

    sealed interface ImportResult {
        data class Ok(val fileName: String) : ImportResult
        data object WrongExt : ImportResult
        data object InvalidRule : ImportResult
        /** v0.76.0：是影视 spider（有 vod 方法、无 bookSearch），可一键转去影视源导入 */
        data object IsVideoSpider : ImportResult
        data class Failed(val msg: String) : ImportResult
    }

    private val _rules = MutableStateFlow<List<PyBookRule>>(emptyList())
    val rules: StateFlow<List<PyBookRule>> = _rules.asStateFlow()

    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    fun dir(context: Context): File =
        File(StoragePaths.pyDir(context), "book").also { it.mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 扫描书源目录 + 载入启用集合（启动/页面进入时调）。 */
    fun load(context: Context) {
        // v0.186.0 第 9 组：只把含 bookSearch 的文件列为规则候选——
        // 无 bookSearch 的半截文件、影视 spider、杂 .py 不再进候选，启用集合也随之清理。
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".py") }
            .orEmpty().filter { hasBookSearch(it) }.sortedBy { it.name }
        _rules.value = files.map {
            PyBookRule(it.name, extractTitle(it) ?: it.nameWithoutExtension, it.length() / 1024)
        }
        // 文件删了但启用集合里还有 → 清理掉
        val names = files.map { it.name }.toSet()
        val enabledSet = prefs(context).getStringSet(KEY_ENABLED, emptySet()).orEmpty()
            .filter { it in names }.toSet()
        _enabled.value = enabledSet
        if (enabledSet.size != prefs(context).getStringSet(KEY_ENABLED, emptySet()).orEmpty().size) {
            prefs(context).edit().putStringSet(KEY_ENABLED, enabledSet).apply()
        }
    }

    fun rulePath(context: Context, name: String): String? {
        val f = File(dir(context), name)
        return if (f.isFile) f.absolutePath else null
    }

    fun isEnabled(name: String): Boolean = name in _enabled.value

    fun setEnabled(context: Context, name: String, enabled: Boolean): Boolean {
        val old = _enabled.value
        val cur = old.toMutableSet()
        if (enabled) cur.add(name) else cur.remove(name)
        _enabled.value = cur
        val ok = prefs(context).edit().putStringSet(KEY_ENABLED, cur).commit()
        if (!ok) _enabled.value = old
        return ok
    }

    /** 导入 .py 书源文件：先写 .tmp、校验含 bookSearch、通过再改名。 */
    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: return@withContext ImportResult.Failed("读取文件名失败")
        if (!displayName.endsWith(".py", ignoreCase = true)) {
            return@withContext ImportResult.WrongExt
        }
        // v0.186.0 第 9 组：tmp 文件名带随机串，拷贝中途被杀只留无害 tmp，
        // 正式 .py 只在校验通过后由改名产生，绝不出现半截 .py。
        var tmp: File? = null
        try {
            val dir = dir(context)
            var dst = File(dir, displayName)
            if (dst.exists()) {
                val base = displayName.substringBeforeLast('.')
                dst = File(dir, "${base}_${System.currentTimeMillis()}.py")
            }
            val staging = File(dir, "${dst.name}.${UUID.randomUUID()}.tmp")
            tmp = staging
            val input = context.contentResolver.openInputStream(uri) ?: run {
                staging.delete()
                return@withContext ImportResult.Failed("无法读取所选文件")
            }
            input.use { ins ->
                staging.outputStream().use { ins.copyTo(it) }
            }
            // 校验：书源类必须有 bookSearch 方法（与桥的 _load_book_spider 判定一致）
            val head = runCatching {
                staging.inputStream().use { ins ->
                    val buf = ByteArray(8192)
                    val n = ins.read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                }
            }.getOrDefault("")
            if (!head.contains("bookSearch")) {
                staging.delete()
                tmp = null
                // v0.76.0：智能识别——是影视 spider 就明确告诉用户，别只说"不是书源"
                return@withContext if (looksLikeVideoSpider(head)) {
                    ImportResult.IsVideoSpider
                } else {
                    ImportResult.InvalidRule
                }
            }
            if (!staging.renameTo(dst)) {
                staging.delete()
                tmp = null
                return@withContext ImportResult.Failed("保存书源失败")
            }
            tmp = null
            load(context)
            ImportResult.Ok(dst.name)
        } catch (e: Exception) {
            Log.w(TAG, "导入书源失败", e)
            tmp?.delete()
            ImportResult.Failed(e.message ?: "未知错误")
        }
    }

    /**
     * 删除书源文件 + 清桥缓存 + 清启用标记。
     * v0.186.0 第 9 组：文件存在但删除返回 false 时直接返回 false，
     * 不清启用标记、不重载——不再出现删了又出现且是禁用态。
     * @return true=已删除（或文件本就不存在），false=删除失败
     */
    suspend fun remove(context: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(dir(context), name)
        val path = f.absolutePath
        if (f.isFile && !f.delete()) {
            Log.w(TAG, "删除书源失败：$name")
            return@withContext false
        }
        runCatching { ChaquopyEngine.resetRuleCache(path) }
        setEnabled(context, name, false)
        load(context)
        true
    }

    /**
     * v0.76.0：像不像影视 spider——有 TVBox 影视方法（homeContent/searchContent/
     * detailContent/playerContent/categoryContent），却没有 bookSearch。
     * 只看文件头 8KB，混淆过的也认得出方法名。
     */
    private fun looksLikeVideoSpider(head: String): Boolean {
        if (head.contains("bookSearch")) return false
        val marks = listOf(
            "homeContent", "searchContent", "detailContent",
            "playerContent", "categoryContent", "vod_play",
        )
        return marks.count { head.contains(it) } >= 2
    }

    /** v0.186.0 第 9 组：load 用的 bookSearch 存在性过滤（整文件读，头 8KB 外的也算）。 */
    private fun hasBookSearch(file: File): Boolean = runCatching {
        file.readText(Charsets.UTF_8).contains("bookSearch")
    }.getOrDefault(false)

    /** 从文件头抠类名做标题（class Xxx），抠不到返回 null。 */
    private fun extractTitle(file: File): String? = runCatching {
        val head = file.inputStream().use { input ->
            val buf = ByteArray(4096)
            val n = input.read(buf)
            if (n > 0) String(buf, 0, n) else ""
        }
        Regex("""class\s+(\w+)""").find(head)?.groupValues?.get(1)
    }.getOrNull()

    /** Novel.sourceKey → 书源文件名（非 PY 书源返回 null）。 */
    fun fileNameOf(sourceKey: String): String? =
        sourceKey.removePrefix(SOURCE_KEY_PREFIX).takeIf { sourceKey.startsWith(SOURCE_KEY_PREFIX) }
}
