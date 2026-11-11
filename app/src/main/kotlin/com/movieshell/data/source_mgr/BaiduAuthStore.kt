package com.movieshell.data.source_mgr

import android.content.Context

/**
 * v1.1.23 扩充：百度登录态（只存 Cookie，不存账号密码）。
 *
 * 落盘位置：App 私有目录 filesDir/baidu/cookie.txt，与夸克/UC 分开存、
 * 不混用；Cookie 值绝不写进日志、聊天与记忆。
 * 规则侧经 Chaquopy Java 桥调 getCookie() 拿，拿不到按游客跑；
 * 百度取直链要登录的 SVIP 账号 BDUSS，普通登录也可能取不到直链。
 */
object BaiduAuthStore {

    private val store = PanCookieFile("baidu")

    /** MainActivity 启动时调一次；晚于它调用的读写会返回空而不崩。 */
    fun init(context: Context) = store.init(context)

    /** 规则侧 Java 桥入口：返回整串 Cookie，未登录返回空串。 */
    @JvmStatic
    fun getCookie(): String = store.getCookie()

    fun hasCookie(): Boolean = store.hasCookie()

    /** 保存整串 Cookie（网页登录抓到的，或用户手填的）。 */
    fun saveCookie(cookie: String): Boolean = store.saveCookie(cookie)

    /** 退出登录：删掉本地 Cookie 文件。 */
    fun clear(): Boolean = store.clear()

    /** 退出登录：删文件＋清 WebView 该域 Cookie（中心页“清除 Cookie”用）。 */
    fun clearAll(): Boolean {
        val ok = store.clear()
        clearWebCookies("https://pan.baidu.com", ".baidu.com", listOf("BDUSS", "STOKEN"))
        return ok
    }

    /** 播前检测到登录态失效时打标记。 */
    fun markExpired() = store.markExpired()

    /** 有 Cookie 且打过过期标记。 */
    fun isExpired(): Boolean = store.isExpired()
}
