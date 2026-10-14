package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.PluginStore.InstalledPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 插件市场客户端 + 安装器（v0.22.0，单入口）。
 *
 * - fetch(url)：拉 market.json → MarketPlugin 列表。
 * - install(context, plugin)：下载 zip → MD5 强制校验 → 按 type/engine 解压到对应目录 → 登记 PluginStore。
 *   source 插件：py→pyDir（*.py）、js→jsDir（*.js）、php→phpDir（*.php），只取规则文件（扁平存放，与各 Manager 的 listRules 扫描口径一致）。
 *   server 插件：整包解压到 pluginsDir/<id>/（App 只做下载校验与版本管理；Node.js 服务需外部运行，见市场页说明）。
 * - uninstall(context, id)：删文件 + 清记录；若删掉的是当前 active 规则，顺手清空 active。
 *
 * 所有失败都抛 IllegalStateException（中文原因），调用方负责 Toast + LogBus，不静默。
 */
object PluginMarket {

    private const val TAG = "PluginMarket"

    data class MarketPlugin(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val engine: String,
        val md5: String,
        val url: String,
        val desc: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 拉取市场列表。 */
    suspend fun fetch(url: String): List<MarketPlugin> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "XiaYing/0.22.0").build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("市场请求失败：HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("市场返回为空")
        }
        val plugins = mutableListOf<MarketPlugin>()
        val arr = runCatching { JSONObject(body).getJSONArray("plugins") }
            .getOrElse { throw IllegalStateException("market.json 格式错误：缺 plugins 数组") }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            plugins.add(
                MarketPlugin(
                    id = o.optString("id").also { if (it.isBlank()) throw IllegalStateException("插件条目缺 id") },
                    name = o.optString("name", o.optString("id")),
                    version = o.optString("version", ""),
                    type = o.optString("type", ""),
                    engine = o.optString("engine", ""),
                    md5 = o.optString("md5"),
                    url = o.optString("url").also { if (it.isBlank()) throw IllegalStateException("插件 ${o.optString("id")} 缺 url") },
                    desc = o.optString("desc", ""),
                )
            )
        }
        Log.i(TAG, "市场拉取成功：${plugins.size} 个插件")
        plugins
    }

    /** 一键安装（含更新：覆盖安装）。 */
    suspend fun install(context: Context, plugin: MarketPlugin) = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始安装：${plugin.id} v${plugin.version}")
        val tmp = File.createTempFile("plugin_", ".zip", context.cacheDir)
        try {
            download(plugin.url, tmp)
            verifyMd5(tmp, plugin.md5, plugin.id)
            val installedFiles = extract(context, plugin, tmp)
            PluginStore.recordInstalled(
                context,
                InstalledPlugin(
                    id = plugin.id, name = plugin.name, version = plugin.version,
                    type = plugin.type, engine = plugin.engine, files = installedFiles,
                ),
            )
            Log.i(TAG, "安装成功：${plugin.id} v${plugin.version}，${installedFiles.size} 个文件")
        } finally {
            tmp.delete()
        }
    }

    /** 卸载：删文件 + 清记录 + 若命中 active 规则则清空。 */
    suspend fun uninstall(context: Context, id: String) = withContext(Dispatchers.IO) {
        val rec = PluginStore.installedOf(id)
            ?: throw IllegalStateException("未安装：$id")
        when (rec.type) {
            "server" -> File(StoragePaths.pluginsDir(context), id).deleteRecursively()
            else -> {
                val dir = ruleDir(context, rec.type, rec.engine)
                rec.files.forEach { File(dir, it).delete() }
                clearActiveIfGone(rec)
            }
        }
        PluginStore.removeInstalled(context, id)
        Log.i(TAG, "卸载成功：$id")
    }

    /** 远程版本是否新于本地版本。 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".", "-"); val l = local.split(".", "-")
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrNull(i)?.toIntOrNull() ?: 0
            val b = l.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }
        return false
    }

    // ---------------- 内部 ----------------

    private fun download(url: String, dst: File) {
        val req = Request.Builder().url(url).header("User-Agent", "XiaYing/0.22.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("下载内容为空")
            body.byteStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
        }
        if (dst.length() == 0L) throw IllegalStateException("下载内容为空")
    }

    private fun verifyMd5(file: File, expect: String, id: String) {
        if (expect.isBlank()) throw IllegalStateException("插件 $id 未提供 md5，拒绝安装")
        val actual = MessageDigest.getInstance("MD5").let { md ->
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!actual.equals(expect, ignoreCase = true)) {
            throw IllegalStateException("MD5 校验失败（插件 $id），文件可能被篡改，已拒绝安装")
        }
    }

    /** 按 type/engine 解压，返回已安装文件清单（source 为文件名，server 为相对路径）。 */
    private fun extract(context: Context, plugin: MarketPlugin, zip: File): List<String> {
        val installed = mutableListOf<String>()
        ZipFile(zip).use { zf ->
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            if (entries.isEmpty()) throw IllegalStateException("插件包为空：${plugin.id}")
            when (plugin.type) {
                "source" -> {
                    val dir = ruleDir(context, plugin.type, plugin.engine)
                    val ext = when (plugin.engine) {
                        "py" -> ".py"; "js" -> ".js"; "php" -> ".php"
                        else -> throw IllegalStateException("不支持的源引擎：${plugin.engine}")
                    }
                    entries.filter { it.name.endsWith(ext, ignoreCase = true) }.forEach { e ->
                        val name = File(e.name).name
                        val dst = safeDst(dir, name)
                        zf.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                        installed.add(name)
                    }
                    if (installed.isEmpty()) throw IllegalStateException("插件包里没有 $ext 规则文件")
                }
                "server" -> {
                    val root = File(StoragePaths.pluginsDir(context), plugin.id)
                    entries.forEach { e ->
                        val dst = safeDst(root, e.name)
                        dst.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                        installed.add(e.name)
                    }
                }
                else -> throw IllegalStateException("不支持的插件类型：${plugin.type}")
            }
        }
        return installed
    }

    /** zip-slip 防护：解压目标必须落在 dir 内。 */
    private fun safeDst(dir: File, name: String): File {
        val dst = File(dir, name)
        if (!dst.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw IllegalStateException("插件包路径非法：$name")
        }
        return dst
    }

    private fun ruleDir(context: Context, type: String, engine: String): File {
        if (type != "source") throw IllegalStateException("未知插件类型：$type")
        return when (engine) {
            "py" -> StoragePaths.pyDir(context)
            "js" -> StoragePaths.jsDir(context)
            "php" -> StoragePaths.phpDir(context)
            else -> throw IllegalStateException("不支持的源引擎：$engine")
        }
    }

    /** 卸载后若 active 规则文件已不存在，清空 active，避免悬空。 */
    private fun clearActiveIfGone(rec: InstalledPlugin) {
        // active 规则名即文件名；被删文件里包含它才清空
        val gone = { manager: String, active: String? ->
            active != null && rec.files.contains(active).also {
                if (it) Log.i(TAG, "active $manager 规则随插件卸载：$active")
            }
        }
        if (gone("py", PySourceManager.activeRuleName())) PySourceManager.setActiveRule(null)
        if (gone("js", DrpySourceManager.activeRuleName())) DrpySourceManager.setActiveRule(null)
        if (gone("php", PhpSourceManager.activeRuleName())) PhpSourceManager.setActiveRule(null)
    }
}
