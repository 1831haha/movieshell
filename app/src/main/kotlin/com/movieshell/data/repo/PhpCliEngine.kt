package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.Type4Api
import com.movieshell.data.source.Type4PlayResult
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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
 * v0.152.0：空结果不再等于"规则没数据"——最近一次失败原因记在 [lastErrorFor]，
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

    /**
     * v0.217.0（F54）：失败原因按脚本分流——原来单例一份 lastError，
     * 聚合搜索多 PHP 站并发时 A 站成功会把 B 站刚记下的失败原因清掉，
     * 失败计数忽多忽少；现在每个脚本各记各的，调用方按脚本名取。
     * v0.152.0 起供首页/检测透出（null=该脚本最近一次成功或未跑过）。
     */
    private val lastErrorByScript = ConcurrentHashMap<String, String>()

    // v0.276.0（T24）：首页 JSON 短缓存——home() 与 categories() 原来
    // 各跑一次 php（同一空查询、同一份 JSON），首页加载时连跑两遍。
    // 只缓存空查询结果，且同时满足：脚本文件指纹（路径+修改时间+大小）
    // 未变、且距上次跑出不超过 30 秒（覆盖一次加载里 home→cats 的
    // 先后调用，不把动态首页长期冻住；规则改了指纹变、缓存即失效）。
    private const val HOME_CACHE_TTL_MS = 30_000L
    private data class HomeCacheEntry(val json: String, val stamp: Long, val at: Long)
    private val homeCache = ConcurrentHashMap<String, HomeCacheEntry>()

    /** 取某脚本最近一次 runQuery 的失败原因；脚本名空时取当前激活规则。 */
    fun lastErrorFor(scriptName: String?): String? =
        (scriptName ?: activeScriptName())?.let { lastErrorByScript[it] }

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
        homeJson(context, scriptName)?.let { Type4Api().parseHome(it).second }.orEmpty()

    suspend fun categories(context: Context? = null, scriptName: String? = null): List<CategoryType> =
        homeJson(context, scriptName)?.let { Type4Api().parseHome(it).first }.orEmpty()

    /** 首页空查询 JSON：命中短缓存直接复用，未命中跑一次并记缓存；失败不缓存。 */
    private suspend fun homeJson(context: Context? = null, scriptName: String? = null): String? {
        val ctx = context ?: PhpSourceManager.appContext()
        val script = ctx?.let { scriptFile(it, scriptName ?: activeScriptName()) }
        if (ctx == null || script == null) {
            return runQuery(context, scriptName, "")
        }
        val key = script.absolutePath
        val stamp = script.lastModified() * 31L + script.length()
        val cached = homeCache[key]
        if (cached != null && cached.stamp == stamp &&
            System.currentTimeMillis() - cached.at < HOME_CACHE_TTL_MS
        ) {
            return cached.json
        }
        val json = runQuery(ctx, scriptName, "")
        if (json != null) {
            homeCache[key] = HomeCacheEntry(json, stamp, System.currentTimeMillis())
        } else {
            homeCache.remove(key)
        }
        return json
    }

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
    ): com.movieshell.data.source.RawPlay {
        val first = runQuery(context, scriptName, "ac=play&flag=${enc(flag)}&id=${enc(id)}")
            ?.let { Type4Api().parsePlay(it) }
        if (first != null && (first.url.isNotBlank() || first.parse != 0)) {
            return first.toRaw()
        }
        val second = runQuery(context, scriptName, "play=${enc(id)}")
            ?.let { Type4Api().parsePlay(it) } ?: Type4PlayResult()
        return second.toRaw()
    }

    /** 跑一次查询，返回提取出的 JSON 文本；php 未装、脚本不存在、超时、非 JSON 一律 null（不抛），原因记 [lastErrorFor]。 */
    suspend fun runQuery(context: Context? = null, scriptName: String?, query: String): String? =
        withContext(Dispatchers.IO) {
            val errKey = scriptName ?: activeScriptName()
            if (errKey != null) lastErrorByScript.remove(errKey)
            runCatching {
                // v0.187.0 H03：上下文缺失不再静默返回 null，显式记失败原因并记日志，
                // 调用方据 lastErrorFor 可区分“上下文未就绪”与“规则没数据”。
                val ctx = context ?: PhpSourceManager.appContext() ?: run {
                    if (errKey != null) lastErrorByScript[errKey] = "应用上下文未就绪"
                    LogBus.w(TAG, "PHP CLI 未跑：应用上下文未就绪")
                    return@runCatching null
                }
                val script = scriptFile(ctx, scriptName ?: activeScriptName()) ?: run {
                    if (errKey != null) lastErrorByScript[errKey] = "脚本不存在：${scriptName ?: activeScriptName()}"
                    LogBus.w(TAG, "PHP 脚本不存在：$scriptName")
                    return@runCatching null
                }
                // S2：执行前验摘要，公开脚本被改写后拒绝执行（只验不拷，
                // 脚本带相对 lib 引用，单文件拷私有会断引用；重新信任
                // 只在用户显式切源时做，见 PhpSourceManager.setActiveRule）。
                val scriptKey = "php:${scriptName ?: activeScriptName()}"
                if (!ExecutableTrust.verifyPublic(ctx.filesDir, script, scriptKey, allowRetrust = false)) {
                    if (errKey != null) {
                        lastErrorByScript[errKey] = "PHP 脚本在公开目录被改动，未自动执行，请重新切源确认"
                    }
                    LogBus.e(TAG, "PHP 脚本信任校验失败，拒绝执行：$scriptKey")
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
                    val reason = if (result.exitCode == -1) {
                        "php 执行超时（30 秒），进程已强制结束"
                    } else {
                        "php 退出码 ${result.exitCode}：${firstReasonLine(result.output)}"
                    }
                    if (errKey != null) lastErrorByScript[errKey] = reason
                    LogBus.w(TAG, "php 执行失败：$reason：${result.output.take(500)}")
                }
                val json = extractJson(result.output, errKey)
                // v0.188.0 M14：成功取出 JSON 即清 lastError。此前退出码非 0
                // 但输出里恰好有合法 JSON 时返回正常 JSON、lastError 仍非空，
                // 与“null=最近一次成功”自相矛盾，调用方会把成功当失败展示。
                if (json != null && errKey != null) lastErrorByScript.remove(errKey)
                json
            }.onFailure { e ->
                if (errKey != null) e.message?.take(140)?.let { lastErrorByScript.putIfAbsent(errKey, it) }
                LogBus.e(TAG, "PHP CLI 失败：${e::class.java.simpleName}: ${e.message}")
            }.getOrNull()
        }

    /** php 输出里最能说明问题的一行：含 Failed/Fatal 的行优先，否则首个非空行，截 140 字。 */
    private fun firstReasonLine(output: String): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hit = lines.firstOrNull { "Failed" in it || "Fatal" in it } ?: lines.firstOrNull()
        return (hit ?: "无输出").take(140)
    }

    // v0.276.0（T24）：引导文件写锁——原来无锁读文比文后直接写，
    // 并发跑查询时可能读到写一半的引导文件；锁内比文、临时文件
    // 写完再改名落位，php 进程永远只读到完整版。
    private val bootstrapLock = Any()

    private fun ensureBootstrap(context: Context): File {
        val dir = File(context.filesDir, "php-cli").also { it.mkdirs() }
        val f = File(dir, "bootstrap.php")
        synchronized(bootstrapLock) {
            if (!f.isFile || runCatching { f.readText() }.getOrNull() != BOOTSTRAP) {
                val tmp = File(dir, "bootstrap.php.tmp")
                tmp.writeText(BOOTSTRAP)
                runCatching { f.delete() }
                if (!tmp.renameTo(f)) {
                    f.writeText(BOOTSTRAP)
                }
            }
        }
        return f
    }

    private fun extractJson(output: String, errKey: String?): String? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) {
            if (errKey != null) lastErrorByScript.putIfAbsent(errKey, "PHP 输出无 JSON：${output.take(140)}")
            LogBus.w(TAG, "PHP 输出无 JSON：${output.take(300)}")
            return null
        }
        return output.substring(start, end + 1)
    }

    private fun enc(v: String): String = URLEncoder.encode(v, Charsets.UTF_8.name())
}
