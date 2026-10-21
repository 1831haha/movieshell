package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import java.io.File

/**
 * PHP 规则管理器（单例，UI 组按此契约调用）。
 *
 * 只管文件：规则目录扫描、选中态、删除/恢复的目录映射。
 * 不执行：零端口方向下 PHP 不再走本地服务（v0.148.0 曾接的 T4-PHP 服务
 * 接线已随瘦身拆除）；执行等 Phase 3 的 CLI 引擎（有请求才跑 php，跑完
 * 退出，无端口无常驻），当前版本选中 PHP 规则仅记状态、不出内容。
 *
 * 目录约定（用户 T4 包 README）：站点源放 t4_demo 根目录；t4_demo/php/
 * 子目录是独立服务脚本（不进源列表）；`_` 开头是内部依赖；index.php
 * 是服务控制台首页、config.php 生成源列表、spider.php/HtmlParser.php
 * 是框架文件——以上都不算源，列表与探活一律排除。
 */
object PhpSourceManager {

    data class RuleInfo(val name: String, val title: String, val sizeKb: Long)

    private const val TAG = "PhpSourceManager"

    /** 非源文件：控制台首页、源列表生成器、框架文件。 */
    private val EXCLUDED_FILES = setOf("index.php", "config.php", "spider.php", "HtmlParser.php")

    private var appContext: Context? = null
    private var initialized = false

    private var activeName: String? = null

    /** 规则目录（单一入口）：xiaying/php/t4_demo/。 */
    fun rulesDir(context: Context): File =
        File(StoragePaths.phpDir(context), "t4_demo").also { it.mkdirs() }

    /** 是否算一条源：.php 文件，且不是控制台/清单/框架/`_` 内部文件。 */
    fun isSourceFile(f: File): Boolean =
        f.isFile && f.name.endsWith(".php", ignoreCase = true) &&
            !f.name.startsWith("_") && f.name !in EXCLUDED_FILES

    /** init：主线程，幂等；创建规则目录。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        try {
            rulesDir(appContext!!).mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "init 建目录失败", e)
        }
    }

    /** 扫描规则目录下的源文件。 */
    fun listRules(): List<RuleInfo> {
        val dir = rulesDirOrNull() ?: return emptyList()
        return dir.listFiles { f -> isSourceFile(f) }
            .orEmpty()
            .sortedBy { it.name }
            .map { f ->
                RuleInfo(
                    name = f.name,
                    title = extractTitle(f) ?: f.nameWithoutExtension,
                    sizeKb = f.length() / 1024
                )
            }
    }

    fun activeRuleName(): String? = activeName

    /** 选中规则。主线程调用；name=null 时清空。 */
    fun setActiveRule(name: String?) {
        if (name == activeName) return
        activeName = null
        if (name == null) return
        val file = File(rulesDirOrNull() ?: return, name)
        if (!file.isFile) {
            Log.w(TAG, "规则文件不存在: $name")
            return
        }
        activeName = name
        Log.i(TAG, "选中 PHP 规则: $name")
    }

    // ---------------- 内部 ----------------

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return rulesDir(ctx)
    }

    /** 从 PHP 源码头抠类名/注释名，抠不到返回 null。 */
    private fun extractTitle(file: File): String? {
        return try {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
            Regex("""class\s+(\w+Spider)""").find(head)?.groupValues?.get(1)
                ?: Regex("""\*\s*([^\r\n*]{2,40})""").find(head)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            null
        }
    }
}
