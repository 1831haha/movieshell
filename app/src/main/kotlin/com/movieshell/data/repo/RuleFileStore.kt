package com.movieshell.data.repo

import android.content.res.AssetManager
import android.util.Log
import java.io.File

/**
 * 规则文件信息（drpy／PY／PHP 三路共用；原来三家各写一个一模一样的
 * 嵌套 RuleInfo，调用方还得分开写三种类型）。
 */
data class RuleInfo(val name: String, val title: String, val sizeKb: Long)

/**
 * v0.218.0（S21）：规则文件共用工具——原来 drpy／PY／PHP 三个管理器
 * 各抄一遍 init 拷贝 + 扫描 + 抠标题（读文件头 4KB），只有目录名、
 * 后缀、标题正则不同；现在三处只传这三个参数。
 */
object RuleFileStore {

    /** 读文件头（默认前 4KB），抠标题用；读失败返回空串。 */
    fun readHead(file: File, bytes: Int = 4096): String = try {
        file.inputStream().use { input ->
            val buf = ByteArray(bytes)
            val n = input.read(buf)
            if (n > 0) String(buf, 0, n) else ""
        }
    } catch (e: Exception) {
        ""
    }

    /** 按标题正则从文件头抠标题，抠不到返回 null。 */
    fun extractTitle(file: File, titleRegex: Regex): String? = runCatching {
        titleRegex.find(readHead(file))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 首次把 assets 下内置规则拷到规则目录（已存在的不覆盖）。
     * dir 不存在时先建目录；整段异常只记日志，不抛（init 在主线程调）。
     */
    fun copyBuiltinAssets(
        assets: AssetManager,
        assetsDir: String,
        dir: File,
        suffix: String,
        exclude: Set<String> = emptySet(),
        logTag: String,
    ) {
        try {
            dir.mkdirs()
            (assets.list(assetsDir) ?: emptyArray())
                .filter { it.endsWith(suffix) && it !in exclude }
                .forEach { name ->
                    val dst = File(dir, name)
                    if (!dst.exists()) {
                        assets.open("$assetsDir/$name").use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        }
                        Log.i(logTag, "拷贝内置规则: $name")
                    }
                }
        } catch (e: Exception) {
            Log.w(logTag, "init 拷贝规则失败", e)
        }
    }

    /** 扫描规则目录：过滤＋按名排序＋标题回退文件名＋大小 KB。 */
    fun listRules(
        dir: File?,
        isSource: (File) -> Boolean,
        titleOf: (File) -> String?,
    ): List<RuleInfo> {
        if (dir == null) return emptyList()
        return dir.listFiles { f -> isSource(f) }
            .orEmpty()
            .sortedBy { it.name }
            .map { f ->
                RuleInfo(
                    name = f.name,
                    title = titleOf(f) ?: f.nameWithoutExtension,
                    sizeKb = f.length() / 1024,
                )
            }
    }
}
