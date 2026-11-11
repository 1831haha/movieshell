package com.movieshell.data.source_mgr

import android.content.Context
import com.movieshell.core.log.LogBus
import java.io.File

/**
 * v1.1.25：网盘登录态落盘小件（阿里/光鸭/迅雷三家共用，token 只存私有目录）。
 *
 * 每家一个子目录：aliyun/token.json、guangya/token.json、xunlei/token.json；
 * 内容是 token JSON（阿里：access_token＋refresh_token＋expires_in）或
 * 用户手填的 token 原文。token 值绝不写进日志。
 * 与 PanCookieFile（夸克/UC/百度 cookie.txt）成对，只差文件名与用途。
 */
internal class PanTokenFile(private val dirName: String) {

    companion object {
        private const val TAG = "PanAuth"
        private const val FILE_NAME = "token.json"
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, dirName).also { it.mkdirs() }
    }

    private fun tokenFile(): File? = dir()?.let { File(it, FILE_NAME) }

    fun getTokenJson(): String {
        val f = tokenFile() ?: return ""
        if (!f.isFile) return ""
        return runCatching { f.readText().trim() }.getOrDefault("")
    }

    fun hasToken(): Boolean = getTokenJson().isNotBlank()

    fun saveTokenJson(json: String): Boolean {
        val f = tokenFile() ?: return false
        val v = json.trim()
        if (v.isBlank()) return false
        return runCatching {
            f.writeText(v)
            LogBus.i(TAG, "$dirName 登录态已保存")
            true
        }.onFailure { LogBus.w(TAG, "$dirName 登录态保存失败：${it.message}") }.getOrDefault(false)
    }

    fun clear(): Boolean {
        val f = tokenFile() ?: return false
        return runCatching {
            if (f.exists()) f.delete()
            LogBus.i(TAG, "$dirName 登录态已清除")
            true
        }.getOrDefault(false)
    }
}
