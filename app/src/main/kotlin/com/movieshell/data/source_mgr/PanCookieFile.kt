package com.movieshell.data.source_mgr

import android.content.Context
import android.webkit.CookieManager
import com.movieshell.core.log.LogBus
import java.io.File

/**
 * 清 WebView 里指定域的 Cookie：按名逐个写过期＋flush。文件删除是
 * 主路（登录态只认文件），这里只清 WebView 侧，避免下次打开网页
 * 还带着旧登录态。按域清，不动其他站的 Cookie。
 */
internal fun clearWebCookies(url: String, domain: String, names: List<String>) {
    val cm = CookieManager.getInstance()
    for (name in names) {
        cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$domain")
    }
    cm.flush()
}

/**
 * 网盘登录态落盘小件（夸克/UC/百度三家共用，Cookie 只存私有目录）。
 *
 * 每家一个子目录：quark/cookie.txt、uc/cookie.txt、baidu/cookie.txt；
 * 再加一个 expired 空文件当“登录已过期”标记（播前检测到 31001 时写，
 * 存新 Cookie 时清掉，设置页据此显示“登录已过期 · 点进去重登”。
 * Cookie 值绝不写进日志。
 */
internal class PanCookieFile(private val dirName: String) {

    companion object {
        private const val TAG = "PanAuth"
        private const val FILE_NAME = "cookie.txt"
        private const val EXPIRED_NAME = "expired"
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, dirName).also { it.mkdirs() }
    }

    private fun cookieFile(): File? = dir()?.let { File(it, FILE_NAME) }

    private fun expiredFile(): File? = dir()?.let { File(it, EXPIRED_NAME) }

    fun getCookie(): String {
        val f = cookieFile() ?: return ""
        if (!f.isFile) return ""
        return runCatching { f.readText().trim() }.getOrDefault("")
    }

    fun hasCookie(): Boolean = getCookie().isNotBlank()

    fun saveCookie(cookie: String): Boolean {
        val f = cookieFile() ?: return false
        val v = cookie.trim()
        if (v.isBlank()) return false
        return runCatching {
            f.writeText(v)
            expiredFile()?.delete()
            LogBus.i(TAG, "$dirName 登录态已保存")
            true
        }.onFailure { LogBus.w(TAG, "$dirName 登录态保存失败：${it.message}") }.getOrDefault(false)
    }

    fun clear(): Boolean {
        val f = cookieFile() ?: return false
        return runCatching {
            if (f.exists()) f.delete()
            expiredFile()?.delete()
            LogBus.i(TAG, "$dirName 登录态已清除")
            true
        }.getOrDefault(false)
    }

    /** 播前检测到登录态失效（31001）时打标记；设置页据此提示重登。 */
    fun markExpired() {
        runCatching { expiredFile()?.writeText("1") }
    }

    /** 有 Cookie 且打过过期标记。 */
    fun isExpired(): Boolean = expiredFile()?.isFile == true && hasCookie()
}
