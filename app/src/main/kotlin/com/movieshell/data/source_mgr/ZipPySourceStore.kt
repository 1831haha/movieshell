package com.movieshell.data.source_mgr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.movieshell.core.util.SafeFileName
import com.movieshell.core.util.StoragePaths
import com.movieshell.data.source.ChaquopyEngine
import com.movieshell.data.store.RuleInfo
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.1.14：压缩包 PY 源存储（A 组数据层，UI 组按此契约调用）。
 *
 * - zip 包只存在 xiaying/pyzip/ 下，永远不解压；桥按身份串直接读包内条目。
 * - 身份分两种：存储态 "zip:<zip 文件名>!<entry 路径>"（存盘与 RuleInfo.name 用），
 *   桥态 "zip:<zip 绝对路径>!<entry 路径>"（传给桥 _load_spider 用）。
 * - 显示标题一律取 entry 文件名去掉 .py 后缀（v1.1.11 本地规则源口径）；
 *   前 4KB + 标题正则只做 hasSpiderSubclass 校验，不做显示标题。
 * - 纯 zip 逻辑写在以 File(dir) 为参数的函数上，Context 版只做转发，
 *   JVM 单测不用 Context 就能跑。
 */
object ZipPySourceStore {

    /** PY 标题正则，与 PySourceManager 的 TITLE_REGEX 同款，只供 hasSpiderSubclass 校验用。 */
    private val TITLE_REGEX = Regex("""class\s+(\w+Spider)""")

    fun isZipIdentity(name: String): Boolean =
        name.startsWith("zip:") && name.contains("!")

    fun zipIdentity(zipName: String, entryPath: String): String = "zip:$zipName!$entryPath"

    fun parseIdentity(identity: String): Pair<String, String>? {
        if (!isZipIdentity(identity)) return null
        val rest = identity.removePrefix("zip:")
        val bang = rest.indexOf('!')
        if (bang <= 0) return null
        val zipName = rest.substring(0, bang)
        val entry = rest.substring(bang + 1)
        if (zipName.isEmpty() || entry.isEmpty()) return null
        return zipName to entry
    }

    /** 桥态身份：zip 部分是绝对路径，供 PySourceManager.activeRulePath 与桥共用。 */
    fun bridgeIdentity(zipAbsolutePath: String, entryPath: String): String =
        "zip:$zipAbsolutePath!$entryPath"

    /** 条目过滤：跳过目录、任一路径段为 __MACOSX 或以点开头、非 .py 结尾。 */
    private fun isPyEntry(entryName: String): Boolean {
        if (entryName.endsWith("/")) return false
        if (!entryName.endsWith(".py", ignoreCase = true)) return false
        val segments = entryName.split('/')
        if (segments.any { it == "__MACOSX" || it.startsWith(".") }) return false
        return true
    }

    fun listZips(dir: File): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
            .orEmpty().map { it.name }.sorted()

    // v1.1.21：压缩包全是用户文件，无授权时 Context 版入口一律交空/假；
    // File(dir) 版是纯逻辑，供单测用，不动。
    fun listZips(context: Context): List<String> =
        if (!StoragePaths.hasAccess(context)) emptyList() else listZips(StoragePaths.pyzipDir(context))

    fun listZipEntries(dir: File, zipName: String): List<RuleInfo> {
        val zip = File(dir, zipName)
        if (!zip.isFile) return emptyList()
        return runCatching {
            ZipFile(zip).use { zf ->
                zf.entries().asSequence()
                    .filter { !it.isDirectory && isPyEntry(it.name) }
                    .sortedBy { it.name }
                    .map { e ->
                        RuleInfo(
                            zipIdentity(zipName, e.name),
                            File(e.name).nameWithoutExtension,
                            e.size.coerceAtLeast(0L) / 1024,
                        )
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun listZipEntries(context: Context, zipName: String): List<RuleInfo> =
        if (!StoragePaths.hasAccess(context)) emptyList()
        else listZipEntries(StoragePaths.pyzipDir(context), zipName)

    fun listZipRules(dir: File): List<RuleInfo> =
        listZips(dir).flatMap { listZipEntries(dir, it) }

    fun listZipRules(context: Context): List<RuleInfo> =
        if (!StoragePaths.hasAccess(context)) emptyList()
        else listZipRules(StoragePaths.pyzipDir(context))

    /** zip 包里是否有该条目（PySourceManager.ruleExists 的 zip 分支用）。 */
    fun zipEntryExists(dir: File, identity: String): Boolean {
        val (zipName, entryPath) = parseIdentity(identity) ?: return false
        val zip = File(dir, zipName)
        if (!zip.isFile) return false
        return runCatching {
            ZipFile(zip).use { zf ->
                val e = zf.getEntry(entryPath)
                e != null && !e.isDirectory
            }
        }.getOrDefault(false)
    }

    fun zipEntryExists(context: Context, identity: String): Boolean =
        StoragePaths.hasAccess(context) &&
            zipEntryExists(StoragePaths.pyzipDir(context), identity)

    /** 读 entry 前 4KB，标题正则找得到即 true；只做校验，不做显示标题。 */
    fun hasSpiderSubclass(dir: File, identity: String): Boolean {
        val (zipName, entryPath) = parseIdentity(identity) ?: return false
        val zip = File(dir, zipName)
        if (!zip.isFile) return false
        return runCatching {
            ZipFile(zip).use { zf ->
                val entry = zf.getEntry(entryPath) ?: return@runCatching false
                if (entry.isDirectory) return@runCatching false
                zf.getInputStream(entry).use { input ->
                    val buf = ByteArray(4096)
                    val n = input.read(buf)
                    if (n <= 0) return@runCatching false
                    TITLE_REGEX.containsMatchIn(String(buf, 0, n, Charsets.UTF_8))
                }
            }
        }.getOrDefault(false)
    }

    fun hasSpiderSubclass(context: Context, identity: String): Boolean =
        StoragePaths.hasAccess(context) &&
            hasSpiderSubclass(StoragePaths.pyzipDir(context), identity)

    data class ImportZipResult(val ok: Boolean, val zipName: String?, val count: Int, val reason: String?) {
        companion object {
            fun Ok(zipName: String, count: Int) = ImportZipResult(true, zipName, count, null)
            fun Err(reason: String) = ImportZipResult(false, null, 0, reason)
        }
    }

    /**
     * 导入 .zip：uri 内容拷到 pyzipDir，同名直接覆盖。
     * 文件名取 OpenableColumns.DISPLAY_NAME，缺失则 sources-时间戳.zip，
     * 去掉路径分隔符、没有 .zip 后缀就补上。
     * 校验失败（打不开 / 没有 .py 条目）时删掉已拷文件。
     * 成功后清掉该包条目的桥缓存（覆盖同名 zip 时旧实例不再复用）。
     */
    suspend fun importZip(context: Context, uri: Uri): ImportZipResult = withContext(Dispatchers.IO) {
        // v1.1.21：无授权时不许再静默写进私有回退目录，先拦下来。
        if (!StoragePaths.hasAccess(context)) {
            return@withContext ImportZipResult.Err("需要先授予存储权限，才能导入压缩包源")
        }
        val dir = StoragePaths.pyzipDir(context)
        val rawName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
        var zipName = SafeFileName.importName(rawName, "sources-${System.currentTimeMillis()}.zip")
        if (!zipName.endsWith(".zip", ignoreCase = true)) zipName += ".zip"
        val dst = SafeFileName.childInside(dir, zipName)
            ?: return@withContext ImportZipResult.Err("文件名不对，换个名字再试")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("文件打不开")
        }.isSuccess
        if (!copied) {
            dst.delete()
            return@withContext ImportZipResult.Err("文件打不开，重新选一次")
        }
        val count = runCatching {
            ZipFile(dst).use { zf ->
                zf.entries().asSequence().count { !it.isDirectory && isPyEntry(it.name) }
            }
        }.getOrNull()
        if (count == null) {
            dst.delete()
            return@withContext ImportZipResult.Err("压缩包打不开，请确认是完整的 .zip 文件")
        }
        if (count == 0) {
            dst.delete()
            return@withContext ImportZipResult.Err("压缩包里没有 .py 源")
        }
        clearZipCache(zipName)
        ImportZipResult.Ok(zipName, count)
    }

    /** 删 zip 文件，并清掉该包条目的桥缓存。 */
    suspend fun deleteZip(context: Context, zipName: String): Boolean = withContext(Dispatchers.IO) {
        // v1.1.21：无授权时压缩包不可见，也不删看不见的文件，防误删。
        if (!StoragePaths.hasAccess(context)) return@withContext false
        val dir = StoragePaths.pyzipDir(context)
        val f = SafeFileName.childInside(dir, zipName) ?: return@withContext false
        if (!f.isFile) return@withContext false
        val ok = f.delete()
        if (ok) clearZipCache(zipName)
        ok
    }

    /** 调桥的 reset_zip，清掉 _spiders 里该包全部条目的缓存。 */
    suspend fun clearZipCache(zipName: String) {
        runCatching { ChaquopyEngine.resetZipCache(zipName) }
    }
}
