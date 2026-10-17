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
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".py") }
            .orEmpty().sortedBy { it.name }
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

    fun enabledRules(): List<PyBookRule> =
        _rules.value.filter { it.name in _enabled.value }

    fun rulePath(context: Context, name: String): String? {
        val f = File(dir(context), name)
        return if (f.isFile) f.absolutePath else null
    }

    fun isEnabled(name: String): Boolean = name in _enabled.value

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        val cur = _enabled.value.toMutableSet()
        if (enabled) cur.add(name) else cur.remove(name)
        _enabled.value = cur
        prefs(context).edit().putStringSet(KEY_ENABLED, cur).apply()
    }

    /** 导入 .py 书源文件：拷贝 + 校验含 bookSearch。 */
    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: return@withContext ImportResult.Failed("读取文件名失败")
        if (!displayName.endsWith(".py", ignoreCase = true)) {
            return@withContext ImportResult.WrongExt
        }
        try {
            val dir = dir(context)
            var dst = File(dir, displayName)
            if (dst.exists()) {
                val base = displayName.substringBeforeLast('.')
                dst = File(dir, "${base}_${System.currentTimeMillis()}.py")
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            } ?: return@withContext ImportResult.Failed("无法读取所选文件")
            // 校验：书源类必须有 bookSearch 方法（与桥的 _load_book_spider 判定一致）
            val head = runCatching {
                dst.inputStream().use { ins ->
                    val buf = ByteArray(8192)
                    val n = ins.read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                }
            }.getOrDefault("")
            if (!head.contains("bookSearch")) {
                dst.delete()
                return@withContext ImportResult.InvalidRule
            }
            load(context)
            ImportResult.Ok(dst.name)
        } catch (e: Exception) {
            Log.w(TAG, "导入书源失败", e)
            ImportResult.Failed(e.message ?: "未知错误")
        }
    }

    /** 删除书源文件 + 清桥缓存 + 清启用标记。 */
    suspend fun remove(context: Context, name: String) = withContext(Dispatchers.IO) {
        val f = File(dir(context), name)
        val path = f.absolutePath
        if (f.isFile) f.delete()
        runCatching { ChaquopyEngine.resetRuleCache(path) }
        setEnabled(context, name, false)
        load(context)
    }

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
