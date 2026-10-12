package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 网页站源管理器（单例，UI 组按此契约调用）。
 *
 * - 规则存 StoragePaths.webDir()（/storage/emulated/0/xiaying/web/) 下的 JSON 文件，每条 {"name": "...", "url": "..."}。
 * - init(context)：主线程，幂等；建目录。
 * - listSites()：扫描 JSON 文件。
 * - addSite(name, url)：name 为空时自动用 url 的 host；url 必须 http(s) 开头。
 * - deleteSite(fileName)：删除文件；若是当前 active 则清空激活。
 * - setActiveSite(name)：主线程；null=清空。网页源不走搜索/详情引擎，
 *   active 只是记录"上次打开的网页站"，嗅探页直接按 url 进入。
 */
object WebSourceManager {

    data class WebSite(val fileName: String, val name: String, val url: String)

    private const val TAG = "WebSourceManager"
    // v0.7.2：目录走 StoragePaths.webDir()（/storage/emulated/0/xiaying/web/）

    private var appContext: Context? = null
    private var initialized = false
    private var activeFileName: String? = null

    /** init：主线程，幂等；建 filesDir/web 目录。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        runCatching { sitesDirOrNull() }.getOrNull()
    }

    /**
     * 扫描 StoragePaths.webDir()（/storage/emulated/0/xiaying/web/) 下的站源文件。
     * - .json：{"name": "...", "url": "..."} 描述文件；
     * - .html/.htm（v0.10 新增）：直接当网页源用，url 为 file:// 本地地址，
     *   在嗅探页打开可抓直链。name 取文件名（去后缀）。
     */
    fun listSites(): List<WebSite> {
        val dir = sitesDirOrNull() ?: return emptyList()
        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".html") || f.name.endsWith(".htm"))
        }.orEmpty().sortedBy { it.name }
        return files.mapNotNull { f ->
            runCatching {
                when {
                    f.name.endsWith(".json") -> {
                        val obj = JSONObject(f.readText())
                        val name = obj.optString("name").ifBlank { f.nameWithoutExtension }
                        val url = obj.optString("url")
                        if (url.isBlank()) null else WebSite(f.name, name, url)
                    }
                    else -> WebSite(
                        f.name,
                        f.nameWithoutExtension,
                        "file://" + f.absolutePath,
                    )
                }
            }.getOrNull()
        }
    }

    /**
     * 新增网页站源。url 必须 http(s) 开头，否则返回 null。
     * 文件名按时间戳生成避免冲突；成功返回 WebSite。
     */
    fun addSite(name: String, url: String): WebSite? {
        val dir = sitesDirOrNull() ?: return null
        val cleanUrl = url.trim()
        if (!(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://"))) {
            Log.w(TAG, "非法 URL: $cleanUrl")
            return null
        }
        val cleanName = name.trim().ifBlank {
            runCatching { cleanUrl.substringAfter("://").substringBefore("/").substringBefore(":") }
                .getOrDefault("网页站源").ifBlank { "网页站源" }
        }
        return try {
            val fileName = "web_${System.currentTimeMillis()}.json"
            val obj = JSONObject().put("name", cleanName).put("url", cleanUrl)
            File(dir, fileName).writeText(obj.toString())
            WebSite(fileName, cleanName, cleanUrl)
        } catch (e: Exception) {
            Log.w(TAG, "保存网页站源失败", e)
            null
        }
    }

    /** 删除网页站源文件；若删的是当前 active 则清空激活状态。 */
    fun deleteSite(fileName: String): Boolean {
        val dir = sitesDirOrNull() ?: return false
        val ok = runCatching { File(dir, fileName).delete() }.getOrDefault(false)
        if (ok && activeFileName == fileName) {
            activeFileName = null
        }
        return ok
    }

    fun activeSiteName(): String? = activeFileName

    fun setActiveSite(fileName: String?) {
        activeFileName = fileName
    }

    /** 按 fileName 取站源，找不到返回 null。 */
    fun findSite(fileName: String): WebSite? = listSites().find { it.fileName == fileName }

    // ---------------- 内部 ----------------

    private fun sitesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return StoragePaths.webDir(ctx)
    }
}
