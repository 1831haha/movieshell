package com.movieshell.data.source_mgr

import android.content.Context
import com.movieshell.data.source_mgr.PanQrLogin.AliyunTokens
import org.json.JSONObject

/**
 * v1.1.25：阿里云盘登录态（Bearer token，无 Cookie）。
 *
 * 落盘位置：App 私有目录 filesDir/aliyun/token.json，MODE_PRIVATE，
 * 只有本 App 能读；token 值绝不写进日志、聊天与记忆。
 * 扫码登录换回 access_token＋refresh_token 后存这里；转存等后续
 * 流程读它。凭证（client_id/secret）走构建期注入，不进仓库。
 */
object AliyunAuthStore {

    private val store = PanTokenFile("aliyun")

    /** MainActivity 启动时调一次；晚于它调用的读写会返回空而不崩。 */
    fun init(context: Context) = store.init(context)

    fun hasToken(): Boolean = store.hasToken()

    /** 返回 token.json 原文，未登录返回空串。 */
    fun getTokenJson(): String = store.getTokenJson()

    /** 扫码换回的 token 对：access_token＋refresh_token＋expires_in。 */
    fun saveTokens(tokens: AliyunTokens): Boolean {
        val json = JSONObject()
            .put("access_token", tokens.accessToken)
            .put("refresh_token", tokens.refreshToken)
            .put("expires_in", tokens.expiresIn)
            .toString()
        return store.saveTokenJson(json)
    }

    /** 保存手填的 token：已是 JSON 原样存，裸 token 包一层再存。 */
    fun saveTokenJson(json: String): Boolean {
        val v = json.trim()
        if (v.isBlank()) return false
        if (v.startsWith("{")) return store.saveTokenJson(v)
        return store.saveTokenJson(JSONObject().put("access_token", v).toString())
    }

    /** 退出登录：删掉本地 token 文件。 */
    fun clear(): Boolean = store.clear()
}
