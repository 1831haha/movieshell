package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA_BARE
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * v0.8.0：订阅 spider jar 包的下载与缓存。
 *
 * TVBox 订阅顶层 `spider` 字段指定 jar（如 "./tvkj.jar"，相对订阅 URL 解析），
 * 里面是 csp_ 站对应的 Java spider 类。下载后缓存在
 * /storage/emulated/0/xiaying/jar/（走 StoragePaths），文件名
 * <订阅短id>_<jar名>，避免多订阅重名冲突。
 *
 * v0.171.0：spider 字段的 md5 段不再被丢弃。字段可能是 "URL;md5;哈希" 形态，
 * resolveJarUrl 解析相对地址时把 md5 段原样带在地址后面（带分号的串本身不是
 * 合法下载地址，下载与校验时由 ensureJar / cachedFile 先拆出 URL 与 md5）；
 * 有 md5 的 jar 下载后必验 md5，不符的直接丢弃不进缓存，残包不再当有效缓存。
 */
object JarSpiderStore {
    private const val TAG = "JarSpider"
    private val client by lazy { CmsApi.defaultClient() }

    /** 地址中 URL 部分（分号之前）；无分号时原样返回。 */
    fun urlPart(jarUrl: String): String = jarUrl.substringBefore(";").trim()

    /**
     * 从 spider 字段（或已解析的 jar 地址）里取出 md5 段。
     * 支持 "URL;md5;哈希" 与 "URL;md5=哈希" 两种写法；无 md5 段返回 null。
     */
    fun extractMd5(field: String): String? {
        val parts = field.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        for (i in 1 until parts.size) {
            val p = parts[i]
            if (p.equals("md5", ignoreCase = true) && i + 1 < parts.size) return parts[i + 1]
            if (p.startsWith("md5=", ignoreCase = true)) return p.substringAfter("=").trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun md5Of(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 相对订阅 URL 解析 spider 字段（如 "./tvkj.jar"）；绝对地址原样返回。
     * v0.171.0：分号后的 md5 段不再剥掉丢弃，随解析后的地址一起返回，
     * 供 ensureJar / cachedFile 校验用；下载时只取分号前的 URL 部分。
     */
    fun resolveJarUrl(subUrl: String, spiderField: String): String {
        val field = spiderField.trim()
        if (field.isBlank()) return ""
        // v0.11.5：spider 字段可能是 "URL;md5;哈希" 形态（如 newwex 的 .jpg 伪装 jar），
        // 分号后是校验指示；v0.171.0 起不再丢弃，校验段随地址返回给调用方
        val url = field.substringBefore(";").trim()
        val suffix = field.substringAfter(";", "").trim()
            .takeIf { it.isNotEmpty() }?.let { ";$it" }.orEmpty()
        if (url.startsWith("http", ignoreCase = true)) return url + suffix
        // v0.108.0 A2：同 SubSiteStore.resolveUrl，base path 为空时先补 "/"，否则
        // URI("http://a").resolve("b") 得 "http://ab" 畸形 host。
        return runCatching { SubSiteStore.ensureSlashPath(URI(subUrl.trim())).resolve(url).toString() + suffix }
            .getOrDefault("")
    }

    /** 缓存文件名（纯函数，可单测）。md5 段不参与文件名，换 md5 靠校验失效重下。 */
    fun cacheName(subUrl: String, jarUrl: String): String {
        val raw = urlPart(jarUrl).substringAfterLast('/').substringBefore('?').ifBlank { "spider.jar" }
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(64)
        return "${SubSiteStore.subId(subUrl)}_$safe"
    }

    /**
     * 已缓存的 jar（不下载），没有返回 null。
     * 有 md5 段时必验 md5：不符说明是残包或被替换过的旧包，删掉并返回 null，
     * 调用方重新下载；无 md5 段时仍只能验长度（见 ensureJar 的临时文件说明）。
     */
    fun cachedFile(context: Context, subUrl: String, jarUrl: String): File? {
        if (jarUrl.isBlank()) return null
        val f = File(StoragePaths.jarDir(context), cacheName(subUrl, jarUrl))
        if (!f.isFile || f.length() == 0L) return null
        val expect = extractMd5(jarUrl)
        if (expect != null) {
            val actual = runCatching { md5Of(f) }.getOrNull()
            if (actual == null || !actual.equals(expect, ignoreCase = true)) {
                LogBus.w(TAG, "jar 缓存 md5 不符，已丢弃重下：${f.name}")
                runCatching { f.delete() }
                return null
            }
        }
        return f
    }

    /**
     * 确保 jar 在本地：有缓存直接用，否则下载。
     * 下载先写同目录临时文件，成功且 md5（有的话）通过后才改名进缓存；
     * 中途断网/被杀只留临时文件，正式缓存绝不会是半截包。
     * 必须在 IO 线程调用（同步网络 + 写文件）。
     */
    fun ensureJar(context: Context, subUrl: String, jarUrl: String): File? {
        if (jarUrl.isBlank()) return null
        cachedFile(context, subUrl, jarUrl)?.let { return it }
        val downloadUrl = urlPart(jarUrl)
        val expectMd5 = extractMd5(jarUrl)
        if (downloadUrl.isBlank()) return null
        return runCatching {
            val req = Request.Builder().url(downloadUrl)
                .header("User-Agent", DEFAULT_UA_BARE).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.e(TAG, "jar 下载 HTTP ${resp.code}：$downloadUrl")
                    return null
                }
                val body = resp.body ?: return null
                val dir = StoragePaths.jarDir(context)
                val f = File(dir, cacheName(subUrl, jarUrl))
                val tmp = File(dir, "${f.name}.tmp")
                try {
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (tmp.length() == 0L) {
                        LogBus.e(TAG, "jar 下载为空：$downloadUrl")
                        return null
                    }
                    if (expectMd5 != null) {
                        val actual = md5Of(tmp)
                        if (!actual.equals(expectMd5, ignoreCase = true)) {
                            LogBus.e(TAG, "jar md5 校验失败，已丢弃：$downloadUrl")
                            return null
                        }
                    }
                    if (!tmp.renameTo(f)) {
                        LogBus.e(TAG, "jar 落盘改名失败：${f.name}")
                        return null
                    }
                    LogBus.i(TAG, "jar 下载成功：${f.name}（${f.length()} 字节）")
                    f
                } finally {
                    runCatching { tmp.delete() }
                }
            }
        }.onFailure { LogBus.e(TAG, "jar 下载失败 $downloadUrl：${it.message}") }
            .getOrNull()
    }
}
