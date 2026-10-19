package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.CmsApi
import okhttp3.Request
import java.io.File
import java.net.URI

/**
 * v0.8.0：订阅 spider jar 包的下载与缓存。
 *
 * TVBox 订阅顶层 `spider` 字段指定 jar（如 "./tvkj.jar"，相对订阅 URL 解析），
 * 里面是 csp_ 站对应的 Java spider 类。下载后缓存在
 * /storage/emulated/0/xiaying/jar/（走 StoragePaths），文件名
 * <订阅短id>_<jar名>，避免多订阅重名冲突。
 */
object JarSpiderStore {
    private const val TAG = "JarSpider"
    private val client by lazy { CmsApi.defaultClient() }

    /** 相对订阅 URL 解析 spider 字段（如 "./tvkj.jar"）；绝对地址原样返回。 */
    fun resolveJarUrl(subUrl: String, spiderField: String): String {
        val field = spiderField.trim()
        if (field.isBlank()) return ""
        // v0.11.5：spider 字段可能是 "URL;md5;哈希" 形态（如 newwex 的 .jpg 伪装 jar），
        // 分号后是校验指示，不是 URL 的一部分，先剥掉
        val url = field.split(";").first().trim()
        if (url.startsWith("http", ignoreCase = true)) return url
        // v0.108.0 A2：同 SubSiteStore.resolveUrl，base path 为空时先补 "/"，否则
        // URI("http://a").resolve("b") 得 "http://ab" 畸形 host。
        return runCatching { SubSiteStore.ensureSlashPath(URI(subUrl.trim())).resolve(url).toString() }
            .getOrDefault("")
    }

    /** 缓存文件名（纯函数，可单测）。 */
    fun cacheName(subUrl: String, jarUrl: String): String {
        val raw = jarUrl.substringAfterLast('/').substringBefore('?').ifBlank { "spider.jar" }
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(64)
        return "${SubSiteStore.subId(subUrl)}_$safe"
    }

    /** 已缓存的 jar（不下载），没有返回 null。 */
    fun cachedFile(context: Context, subUrl: String, jarUrl: String): File? {
        if (jarUrl.isBlank()) return null
        val f = File(StoragePaths.jarDir(context), cacheName(subUrl, jarUrl))
        return if (f.isFile && f.length() > 0) f else null
    }

    /**
     * 确保 jar 在本地：有缓存直接用，否则下载。
     * 必须在 IO 线程调用（同步网络 + 写文件）。
     */
    fun ensureJar(context: Context, subUrl: String, jarUrl: String): File? {
        if (jarUrl.isBlank()) return null
        cachedFile(context, subUrl, jarUrl)?.let { return it }
        return runCatching {
            val req = Request.Builder().url(jarUrl)
                .header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.e(TAG, "jar 下载 HTTP ${resp.code}：$jarUrl")
                    return null
                }
                val body = resp.body ?: return null
                val f = File(StoragePaths.jarDir(context), cacheName(subUrl, jarUrl))
                f.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (f.length() == 0L) {
                    f.delete()
                    return null
                }
                LogBus.i(TAG, "jar 下载成功：${f.name}（${f.length()} 字节）")
                f
            }
        }.onFailure { LogBus.e(TAG, "jar 下载失败 $jarUrl：${it.message}") }
            .getOrNull()
    }
}
