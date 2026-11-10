package com.movieshell.data.source_mgr

import android.content.Context
import com.movieshell.core.log.LogBus
import java.io.File

/**
 * v1.1.23：夸克登录态（只存 Cookie，不存账号密码）。
 *
 * 落盘位置：App 私有目录 filesDir/quark/cookie.txt，MODE_PRIVATE，
 * 只有本 App 能读；Cookie 值绝不写进日志、聊天与记忆。
 * 规则侧经 Chaquopy Java 桥调 getCookie() 拿，拿不到按游客跑。
 * 过期处理：播放失败且无登录态时提示去设置里重登；有登录态仍失败时
 * 提示重新登录（Cookie 过期是最常见原因）。
 */
object QuarkAuthStore {

    private const val TAG = "QuarkAuth"
    private const val DIR_NAME = "quark"
    private const val FILE_NAME = "cookie.txt"

    private var appContext: Context? = null

    /** MainActivity 启动时调一次；晚于它调用的读写会返回空而不崩。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun cookieFile(): File? {
        val ctx = appContext ?: return null
        return File(File(ctx.filesDir, DIR_NAME).also { it.mkdirs() }, FILE_NAME)
    }

    /** 规则侧 Java 桥入口：返回整串 Cookie，未登录返回空串。 */
    @JvmStatic
    fun getCookie(): String {
        val f = cookieFile() ?: return ""
        if (!f.isFile) return ""
        return runCatching { f.readText().trim() }.getOrDefault("")
    }

    fun hasCookie(): Boolean = getCookie().isNotBlank()

    /** 保存整串 Cookie（网页登录抓到的，或用户手填的）。 */
    fun saveCookie(cookie: String): Boolean {
        val f = cookieFile() ?: return false
        val v = cookie.trim()
        if (v.isBlank()) return false
        return runCatching {
            f.writeText(v)
            LogBus.i(TAG, "夸克登录态已保存")
            true
        }.onFailure { LogBus.w(TAG, "夸克登录态保存失败：${it.message}") }.getOrDefault(false)
    }

    /** 退出登录：删掉本地 Cookie 文件。 */
    fun clear(): Boolean {
        val f = cookieFile() ?: return false
        return runCatching {
            if (f.exists()) f.delete()
            LogBus.i(TAG, "夸克登录态已清除")
            true
        }.getOrDefault(false)
    }
}
