package com.movieshell.data.source_mgr

import android.content.Context

/**
 * v1.1.25：光鸭登录态（Bearer token，无 Cookie）。
 *
 * 落盘位置：App 私有目录 filesDir/guangya/token.json，MODE_PRIVATE，
 * 只有本 App 能读；token 值绝不写进日志、聊天与记忆。
 * 扫码登录端点未钉死（见 PanQrLogin），本版只存手填 token 顶着，
 * 扫码接通后再由扫码流程写这里。
 */
object GuangyaAuthStore {

    private val store = PanTokenFile("guangya")

    /** MainActivity 启动时调一次；晚于它调用的读写会返回空而不崩。 */
    fun init(context: Context) = store.init(context)

    fun hasToken(): Boolean = store.hasToken()

    /** 返回 token.json 原文，未登录返回空串。 */
    fun getTokenJson(): String = store.getTokenJson()

    /** 保存手填的 token 原文。 */
    fun saveTokenJson(json: String): Boolean = store.saveTokenJson(json)

    /** 退出登录：删掉本地 token 文件。 */
    fun clear(): Boolean = store.clear()
}
