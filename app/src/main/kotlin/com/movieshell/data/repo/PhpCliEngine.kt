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
 */
object PhpCliEngine {

    private const val TAG = "PhpCli"

    private const val BOOTSTRAP = """<?php
parse_str($_SERVER['argv'][1] ?? '', $_GET);
$_REQUEST = $_GET;
$_SERVER['QUERY_STRING'] = $_SERVER['argv'][1] ?? '';
include $_SERVER['argv'][2];
"""

    /** php 插件是否已装（bin/php 在位）。 */
    fun isPhpInstalled(context: Context? = null): Boolean = runCatching {
        val ctx = context ?: PhpSourceManager.appContext() ?: return@runCatching false
        val exe = ServicePluginManager.expandPlaceholders(ctx, "__plugin:php__/bin/php")
        File(exe).isFile
    }.getOrDefault(false)

    /** 规则文件：rulesDir/<name>，且必须是源文件（排除 index/config/spider/HtmlParser/`_` 开头）。 */
    fun scriptFile(context: Context, name: String?): File? {
        if (name.isNullOrBlank()) return null
        val f = File(PhpSourceManager.rulesDir(ctx), name)
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

    /** 跑一次查询，返回提取出的 JSON 文本；php 未装、脚本不存在、超时、非 JSON 一律 null（不抛）。 */
    suspend fun runQuery(context: Context? = null, scriptName: String?, query: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val ctx = context ?: PhpSourceManager.appContext() ?: return@runCatching null
                val script = scriptFile(ctx, scriptName ?: activeScriptName()) ?: run {
                    LogBus.w(TAG, "PHP 脚本不存在：$scriptName")
                    return@runCatching null
                }
                val phpExe = ServicePluginManager.expandPlaceholders(ctx, "__plugin:php__/bin/php")
                val bootstrap = ensureBootstrap(ctx)
                val result = ServicePluginManager.runCliOnce(
                    ctx,
                    listOf(phpExe, bootstrap.absolutePath, query, script.absolutePath),
                    PhpSourceManager.rulesDir(ctx),
                    30_000L,
                )
                if (result.exitCode != 0) {
                    LogBus.w(TAG, "php 退出码 ${result.exitCode}：${result.output.take(500)}")
                }
                extractJson(result.output)
            }.onFailure { e ->
                LogBus.e(TAG, "PHP CLI 失败：${e::class.java.simpleName}: ${e.message}")
            }.getOrNull()
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
            LogBus.w(TAG, "PHP 输出无 JSON：${output.take(300)}")
            return null
        }
        return output.substring(start, end + 1)
    }

    private fun enc(v: String): String = URLEncoder.encode(v, Charsets.UTF_8.name())
}
