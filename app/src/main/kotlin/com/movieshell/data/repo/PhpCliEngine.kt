package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.Type4Api
import com.movieshell.data.source.Type4PlayResult
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.151.0：PHP CLI 引擎（零端口 Phase 3）。
 *
 * 哲学：有请求才跑 php，跑完退出，无端口、无常驻、不走 php -S 服务。
 * 复用两条既有单一入口，不另起一套：
 * - 执行：ServicePluginManager.runCliOnce（直接 exec 失败回退 linker64，
 *   php 依赖库走 LD_LIBRARY_PATH，私有目录路线已在 v0.136.0/v0.138.0 真机验证）；
 * - 解析：Type4Api 的纯解析函数（BaseSpider 的 JSON 与 type=4 站源同构，
 *   home/class/list、category/search/detail/play 的字段口径一致）。
 *
 * 查询约定（demo_spider.php 注释 + BaseSpider::run 的 $_GET 路由）：
 *   首页：空查询；分类：ac=videolist&t=&pg=；详情：ac=detail&ids=；
 *   搜索：wd=&pg=；播放：先 ac=play&flag=&id=，无效再 play=（Type4Api 的双试探）。
 *
 * CLI 与 php -S 的唯一差别是 $_GET 的来源：php -S 由服务器填 $_GET，
 * CLI 下 $_GET 是空的，所以用一个私有目录的小引导文件先 parse_str(argv[1])
 * 填好 $_GET 再 include 源文件——源文件本身不用改，结尾的 $spider->run()
 * 照常按 $_GET 路由、echo JSON。引导文件不在规则目录，不会被当成源。
 *
 * 限制（诚实写）：php 插件未安装时全部返回空结果，不虚报；脚本里
 * display_errors 开着时警告会混进输出，取首个 '{' 到末个 '}' 之间的 JSON
 * 段解析，警告只记日志；Node CLI 不在这里，见本版 changelog（无 Android
 * Node 二进制可复用，drpy-node 的手机包是 Termux 方案，不是插件二进制）。
 * v0.152.0：空结果不再等于"规则没数据"——最近一次失败原因记在 [lastError]，
 * 首页与批量检测据此给可见提示（php 未装、退出码、缺 lib/spider 之类），
 * 不再让用户对着空首页猜。
 */
object PhpCliEngine {

    private const val TAG = "PhpCli"

    // 注意：Kotlin 字符串里 $ 会触发模板插值，所以 PHP 的 $ 必须写成 \$；
    // 原 v0.151.0 用三引号原样写 $_SERVER/$_GET/$_REQUEST，编译器把它当变量名报 Unresolved reference。
    private const val BOOTSTRAP =
        "<?php\n" +
            "parse_str(\$_SERVER['argv'][1] ?? '', \$_GET);\n" +
            "\$_REQUEST = \$_GET;\n" +
            "\$_SERVER['QUERY_STRING'] = \$_SERVER['argv'][1] ?? '';\n" +
            "include \$_SERVER['argv'][2];\n"

    /** v0.152.0：最近一次 runQuery 的失败原因（null=最近一次成功或未跑过），供首页/检测透出。 */
    @Volatile
    var lastError: String? = null
        private set

    /** v0.188.0 恢复：SourceProbe.probePhp 仍在调它，审计 L01 的零调用结论有误，不要再删。 */
    fun isPhpInstalled(context: Context? = null): Boolean = runCatching {
        val ctx = context ?: PhpSourceManager.appContext() ?: return@runCatching false
        val exe = ServicePluginManager.expandPlaceholders(ctx, "__plugin:php__/bin/php")
        File(exe).isFile
    }.getOrDefault(false)

    /** 规则文件：rulesDir/<name>，且必须是源文件（排除 index/config/spider/HtmlParser/`_` 开头）。 */
    fun scriptFile(context: Context, name: String?): File? {
        if (name.isNullOrBlank()) return null
        val f = File(PhpSourceManager.rulesDir(context), name)
        return if (PhpSourceManager.isSourceFile(f)) f else null
    }

    /** 当前激活规则的脚本名，无激活规则时 null。 */
    fun activeScriptName(): String? = PhpSourceManager.activeRuleName()

    /** 从 php: 站源 key/api 里取脚本名：key 优先（php:<name>），api 兜底。 */
    fun scriptNameForSite(key: String, api: String): String? {
        val fromKey = key.removePrefix("php:").takeIf { it.isNotBlank() }
        return fromKey ?: api.takeIf { it.isNotBlank() }
    }

    suspend fun home(context: Context? = null, scriptName: String? = null): List<Vod> =
        runQuery(context, scriptName, "")?.let { Type4Api().parseHome(it).second }.orEmpty()

    suspend fun categories(context: Context? = null, scriptName: String? = null): List<CategoryType> =
        runQuery(context, scriptName, "")?.let { Type4Api().parseHome(it).first }.orEmpty()

    suspend fun category(
        context: Context? = null,
        tid: String,
        pg: Int,
        scriptName: String? = null,
    ): List<Vod> {
        if (tid.isBlank()) return emptyList()
        val q = "ac=videolist&t=${enc(tid)}&pg=$pg"
        return runQuery(context, scriptName, q)?.let { Type4Api().parseCategory(it) }.orEmpty()
    }

    suspend fun detail(context: Context? = null, vodId: String, scriptName: String? = null): VodDetail {
        val q = "ac=detail&ids=${enc(vodId)}"
        return runQuery(context, scriptName, q)
            ?.let { Type4Api().parseDetail(it, vodId) } ?: VodDetail(vodId = vodId)
    }

    suspend fun search(context: Context? = null, wd: String, scriptName: String? = null): List<Vod> {
        if (wd.isBlank()) return emptyList()
        val q = "wd=${enc(wd)}&pg=1"
        return runQuery(context, scriptName, q)?.let { Type4Api().parseSearch(it) }.orEmpty()
    }

    /** 播放原始结果（url, parse, header），供 PlayResolver 做 parse=1 收尾。 */
    suspend fun playRaw(
        context: Context? = null,
        flag: String,
        id: String,
        scriptName: String? = null,
    ): Triple<String, Int, Map<String, String>> {
        val first = runQuery(context, scriptName, "ac=play&flag=${enc(flag)}&id=${enc(id)}")
            ?.let { Type4Api().parsePlay(it) }
        if (first != null && (first.url.isNotBlank() || first.parse != 0)) {
            return Triple(first.url, first.parse, first.header)
        }
        val second = runQuery(context, scriptName, "play=${enc(id)}")
            ?.let { Type4Api().parsePlay(it) } ?: Type4PlayResult()
        return Triple(second.url, second.parse, second.header)
    }

    /** 跑一次查询，返回提取出的 JSON 文本；php 未装、脚本不存在、超时、非 JSON 一律 null（不抛），原因记 [lastError]。 */
    suspend fun runQuery(context: Context? = null, scriptName: String?, query: String): String? =
        withContext(Dispatchers.IO) {
            lastError = null
            runCatching {
                // v0.187.0 H03：上下文缺失不再静默返回 null，显式记失败原因并记日志，
                // 调用方据 lastError 可区分“上下文未就绪”与“规则没数据”。
                val ctx = context ?: PhpSourceManager.appContext() ?: run {
                    lastError = "应用上下文未就绪"
                    LogBus.w(TAG, "PHP CLI 未跑：应用上下文未就绪")
                    return@runCatching null
                }
                val script = scriptFile(ctx, scriptName ?: activeScriptName()) ?: run {
                    lastError = "脚本不存在：${scriptName ?: activeScriptName()}"
                    LogBus.w(TAG, "PHP 脚本不存在：$scriptName")
                    return@runCatching null
                }
                val phpExe = ServicePluginManager.expandPlaceholders(ctx, "__plugin:php__/bin/php")
                // v0.188.0 M14：lib 目录缺失时记日志。phpLibDirFor 在
                // ServicePluginManager 侧是私有的、这里调不到，按同一结构
                // （bin/php + 同级 lib/）本地判定：缺失只记日志不拦截，
                // 动态 PIE 路线只用系统库、缺 lib 也可能跑通，失败时这条
                // 日志能把“缺库”与“linker64 失败”区分开。
                val phpExeFile = File(phpExe)
                val phpLibDir = phpExeFile.parentFile
                    ?.takeIf { it.name == "bin" }?.parentFile?.let { File(it, "lib") }
                if (phpExeFile.name == "php" && phpLibDir?.isDirectory != true) {
                    LogBus.w(TAG, "PHP 运行时不完整：lib 目录缺失（${phpLibDir?.absolutePath}），LD_LIBRARY_PATH 未设置")
                }
                val bootstrap = ensureBootstrap(ctx)
                val result = ServicePluginManager.runCliOnce(
                    ctx,
                    listOf(phpExe, bootstrap.absolutePath, query, script.absolutePath),
                    PhpSourceManager.rulesDir(ctx),
                    30_000L,
                )
                if (result.exitCode != 0) {
                    // v0.188.0 M14：runCliOnce 超时以退出码 -1 哨兵返回（真实
                    // 进程退出码取不到 -1），PHP 侧不再照搬成“退出码 -1”，
                    // 单独记为超时，与真实退出码区分开。
                    lastError = if (result.exitCode == -1) {
                        "php 执行超时（30 秒），进程已强制结束"
                    } else {
                        "php 退出码 ${result.exitCode}：${firstReasonLine(result.output)}"
                    }
                    LogBus.w(TAG, "php 执行失败：$lastError：${result.output.take(500)}")
                }
                val json = extractJson(result.output)
                // v0.188.0 M14：成功取出 JSON 即清 lastError。此前退出码非 0
                // 但输出里恰好有合法 JSON 时返回正常 JSON、lastError 仍非空，
                // 与“null=最近一次成功”自相矛盾，调用方会把成功当失败展示。
                if (json != null) lastError = null
                json
            }.onFailure { e ->
                if (lastError == null) lastError = e.message?.take(140)
                LogBus.e(TAG, "PHP CLI 失败：${e::class.java.simpleName}: ${e.message}")
            }.getOrNull()
        }

    /** php 输出里最能说明问题的一行：含 Failed/Fatal 的行优先，否则首个非空行，截 140 字。 */
    private fun firstReasonLine(output: String): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hit = lines.firstOrNull { "Failed" in it || "Fatal" in it } ?: lines.firstOrNull()
        return (hit ?: "无输出").take(140)
    }

    private fun ensureBootstrap(context: Context): File {
        val dir = File(context.filesDir, "php-cli").also { it.mkdirs() }
        val f = File(dir, "bootstrap.php")
        if (!f.isFile || f.readText() != BOOTSTRAP) f.writeText(BOOTSTRAP)
        return f
    }

    private fun extractJson(output: String): String? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) {
            if (lastError == null) lastError = "PHP 输出无 JSON：${output.take(140)}"
            LogBus.w(TAG, "PHP 输出无 JSON：${output.take(300)}")
            return null
        }
        return output.substring(start, end + 1)
    }

    private fun enc(v: String): String = URLEncoder.encode(v, Charsets.UTF_8.name())
}
