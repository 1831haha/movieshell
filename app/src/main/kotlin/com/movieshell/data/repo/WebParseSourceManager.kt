package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.GenericWebParser
import com.movieshell.data.source.RawPlay
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

/**
 * 通用网页源管理器（v0.294.49 v1）。
 *
 * 与 WebSourceManager 书签完全分开：这里每条记录是一个可当首页源的
 * 解析站，存在 StoragePaths.webParseDir() 下的 JSON 文件里，每条只存
 * 名字与网址。激活态只记在内存里，持久化靠 SourceStateStore 的
 * webparse 激活记录，启动时由 restoreActiveRule 恢复。
 * 全部对外方法异常吞成空结果，唯独取消异常重抛，不让解析异常崩 App。
 */
object WebParseSourceManager {

    data class WebParseSite(val fileName: String, val name: String, val url: String)

    private const val TAG = "WebParse"

    private var appContext: Context? = null
    private var activeSite: WebParseSite? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching { sitesDirOrNull() }
    }

    fun activeSite(): WebParseSite? = activeSite

    fun activeSiteName(): String? = activeSite?.name

    fun hasActiveSite(): Boolean = activeSite != null

    fun listSites(): List<WebParseSite> {
        val dir = sitesDirOrNull() ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty().sortedBy { it.name }
        return files.mapNotNull { f ->
            runCatching {
                val obj = JSONObject(f.readText())
                val name = obj.optString("name").ifBlank { f.nameWithoutExtension }
                val url = obj.optString("url")
                if (url.isBlank()) null else WebParseSite(f.name, name, url)
            }.getOrNull()
        }
    }

    /** 新增解析站：网址必须 http(s) 开头，名称空则取域名。 */
    fun addSite(name: String, url: String): WebParseSite? {
        val dir = sitesDirOrNull() ?: return null
        val cleanUrl = url.trim()
        if (!(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://"))) {
            LogBus.w(TAG, "非法 URL：$cleanUrl")
            return null
        }
        val cleanName = name.trim().ifBlank { GenericWebParser.domainName(cleanUrl) }
        return try {
            val fileName = "webparse_${System.currentTimeMillis()}.json"
            val obj = JSONObject().put("name", cleanName).put("url", cleanUrl)
            if (!AtomicFile.writeText(File(dir, fileName), obj.toString())) {
                LogBus.w(TAG, "保存通用网页源失败：写盘失败")
                null
            } else {
                WebParseSite(fileName, cleanName, cleanUrl)
            }
        } catch (e: Exception) {
            LogBus.w(TAG, "保存通用网页源失败", e)
            null
        }
    }

    /** 激活：记录必须已在列表里，否则返回 false，调用方做回滚。 */
    fun setActiveSite(site: WebParseSite?): Boolean {
        if (site == null) {
            activeSite = null
            return true
        }
        val found = listSites().firstOrNull { it.fileName == site.fileName }
        if (found == null) return false
        activeSite = found
        return true
    }

    fun setActiveByFileName(fileName: String?): Boolean {
        if (fileName == null) {
            activeSite = null
            return true
        }
        val found = listSites().firstOrNull { it.fileName == fileName } ?: return false
        activeSite = found
        return true
    }

    fun deleteSite(fileName: String): Boolean {
        val dir = sitesDirOrNull() ?: return false
        val ok = runCatching { File(dir, fileName).delete() }.getOrDefault(false)
        if (ok && activeSite?.fileName == fileName) activeSite = null
        return ok
    }

    suspend fun home(): List<Vod> {
        val site = activeSite ?: return emptyList()
        return runCatching {
            val html = GenericWebParser.fetch(site.url)
            GenericWebParser.parseHome(html, site.url).vods
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
    }

    suspend fun categories(): List<CategoryType> {
        val site = activeSite ?: return emptyList()
        return runCatching {
            val html = GenericWebParser.fetch(site.url)
            GenericWebParser.parseHome(html, site.url).categories
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
    }

    suspend fun category(tid: String, pg: Int): List<Vod> {
        val site = activeSite ?: return emptyList()
        if (tid.isBlank()) return emptyList()
        return runCatching {
            if (pg <= 1) {
                val html = GenericWebParser.fetch(tid)
                return@runCatching GenericWebParser.parseCategory(html, tid)
            }
            val firstHtml = GenericWebParser.fetch(tid)
            val firstIds = GenericWebParser.parseCategory(firstHtml, tid).map { it.vodId }.toSet()
            for (candidate in GenericWebParser.pageCandidates(tid, pg)) {
                if (candidate == tid) continue
                val html = runCatching { GenericWebParser.fetch(candidate) }.getOrNull() ?: continue
                val vods = GenericWebParser.parseCategory(html, candidate)
                if (vods.isEmpty()) continue
                if (vods.any { it.vodId !in firstIds }) return@runCatching vods
            }
            emptyList()
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
    }

    suspend fun detail(vodId: String): VodDetail {
        val site = activeSite ?: return VodDetail(vodId = vodId)
        return runCatching {
            val html = GenericWebParser.fetch(vodId)
            GenericWebParser.parseDetail(html, vodId)
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(VodDetail(vodId = vodId))
    }

    suspend fun search(keyword: String): List<Vod> {
        val site = activeSite ?: return emptyList()
        val wd = keyword.trim()
        if (wd.isBlank()) return emptyList()
        return runCatching {
            for (candidate in GenericWebParser.searchCandidates(site.url, wd)) {
                val html = runCatching { GenericWebParser.fetch(candidate) }.getOrNull() ?: continue
                val vods = GenericWebParser.parseCategory(html, site.url)
                val matched = vods.filter { it.vodName.contains(wd, ignoreCase = true) }
                if (matched.isNotEmpty()) return@runCatching matched
            }
            emptyList()
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
    }

    /** 播放：先从播放页抽直链，抽到才返回，抽不到返回空，由调用方走嗅探兜底。 */
    suspend fun playRaw(flag: String, id: String): RawPlay {
        if (id.isBlank()) return RawPlay()
        return runCatching {
            val url = GenericWebParser.resolvePlay(id)
            if (url.isBlank()) RawPlay() else RawPlay(url = url, header = mapOf("Referer" to id))
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(RawPlay())
    }

    private fun sitesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return StoragePaths.webParseDir(ctx)
    }
}
