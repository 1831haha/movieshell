package com.movieshell.data.source_mgr

import android.content.Context
import android.util.Log
import com.movieshell.core.util.ExecutableTrust
import com.movieshell.data.store.RuleFileStore
import com.movieshell.data.store.RuleInfo
import com.movieshell.core.util.StoragePaths
import java.io.File

/**
 * PHP 规则管理器（单例，UI 组按此契约调用）。
 *
 * 只管文件：规则目录扫描、选中态、删除/恢复的目录映射。
 * 不执行：零端口方向下 PHP 不再走本地服务（v0.148.0 曾接的 T4-PHP 服务
 * 接线已随瘦身拆除）；执行走 v0.151.0 的 PhpCliEngine（有请求才跑 php，跑完
 * 退出，无端口无常驻），未装 php 插件时返回空结果。
 *
 * 目录约定（用户 T4 包 README）：站点源放 t4_demo 根目录；t4_demo/php/
 * 子目录是独立服务脚本（不进源列表）；`_` 开头是内部依赖；index.php
 * 是服务控制台首页、config.php 生成源列表、spider.php/HtmlParser.php
 * 是框架文件——以上都不算源，列表与探活一律排除。
 */
object PhpSourceManager {

    private const val TAG = "PhpSourceManager"

    /** v1.2.12：标题直接取文件名（不含 .php 后缀），与 v1.1.11 的 PY 同款——此前按类名/注释抠标题：
     * 正常 T4 规则的类就叫 Spider，类名正则（要求名字以 Spider 结尾且前面至少一个字符）抠不到；
     * 注释正则（第一个 `*` 后面的文字）会抠到代码片段，如分页的 `* $limit;`、请求头的 `*/*;q=0.8`。 */

    /** 非源文件：控制台首页、源列表生成器、框架文件。 */
    private val EXCLUDED_FILES = setOf("index.php", "config.php", "spider.php", "HtmlParser.php")

    private var appContext: Context? = null
    private var initialized = false

    private var activeName: String? = null

    /** init 时存下的 applicationContext（供无 context 的聚合层复用，如 SiteRepository）。 */
    fun appContext(): Context? = appContext

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

    /** 扫描规则目录下的源文件。标题取文件名（见上）。 */
    fun listRules(): List<RuleInfo> = RuleFileStore.listRules(
        rulesDirOrNull(),
        { f -> isSourceFile(f) },
        { f -> f.nameWithoutExtension },
    )

    fun activeRuleName(): String? = activeName

    /** v1.2.12：首页胶囊显示名——文件名去 .php 后缀，与列表标题一致（PY/DrpySourceManager 同款）。 */
    fun activeRuleDisplayName(): String? = activeName?.let { File(it).nameWithoutExtension }

    /** 选中规则。主线程调用；name=null 时清空。 */
    /** v0.215.0：同 drpy——先验文件存在再动当前态，返回是否切源成功。 */
    fun setActiveRule(name: String?): Boolean {
        if (name == activeName) return true
        if (name != null) {
            val probe = File(rulesDirOrNull() ?: return false, name)
            if (!probe.isFile) {
                Log.w(TAG, "规则文件不存在: $name")
                return false
            }
        }
        activeName = null
        if (name == null) return true
        val file = File(rulesDirOrNull() ?: return false, name)
        if (!file.isFile) {
            Log.w(TAG, "规则文件不存在: $name")
            return false
        }
        // S2：显式切源重新信任该脚本；之后每次执行前验摘要，公开文件
        // 被改写后拒绝执行，直到用户再次显式切源。脚本带相对 lib
        // 引用，不能单文件拷私有，故用只验不拷的口径。
        val ctx = appContext
        if (ctx != null) {
            ExecutableTrust.verifyPublic(ctx.filesDir, file, "php:$name", allowRetrust = true)
        }
        activeName = name
        Log.i(TAG, "选中 PHP 规则: $name")
        return true
    }

    // ---------------- 内部 ----------------

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        // v1.1.21：PHP 无内置规则，无授权时目录不可见，列表与激活全空；
        // name=null 的清空分支不走这里，不受影响。
        if (!StoragePaths.hasAccess(ctx)) return null
        return rulesDir(ctx)
    }

}
