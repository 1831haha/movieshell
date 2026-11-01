package com.movieshell.player

import android.content.Context
import java.io.File

/** v0.294.32：磁力相关原生引擎插件（.so 经插件市场下载到私有目录）。 */
object EnginePlugins {
    const val BT_ID = "magnet-engine"
    const val XL_ID = "xunlei-engine"
    const val NEED_BT = "需要安装「磁力引擎」插件才能播磁力。"
    const val NEED_ANY = "需要先安装磁力播放引擎：插件市场里的「磁力引擎（BT）」或「迅雷引擎」，装一个即可。"
    private const val PREF = "magnet_engine"

    /** 插件 id → 私有目录（mpv 沿用老目录） */
    fun dirFor(context: Context, id: String): File =
        if (id == MpvPluginLoader.PLUGIN_ID) File(context.filesDir, "mpv-libs")
        else File(context.filesDir, "engine-libs/$id")

    fun libtorrentSo(context: Context) = File(dirFor(context, BT_ID), "libtorrent4j.so")
    fun xunleiDir(context: Context) = dirFor(context, XL_ID)

    fun btInstalled(context: Context) = libtorrentSo(context).isFile
    fun xlInstalled(context: Context) =
        File(xunleiDir(context), "libxl_thunder_sdk.so").isFile && File(xunleiDir(context), "libxl_stat.so").isFile

    /** 引擎优先级："xunlei" 迅雷优先 / "bt" BT 优先 */
    fun prefer(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("prefer", "xunlei") ?: "xunlei"

    fun setPrefer(context: Context, v: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("prefer", v).apply()
    }

    /** 纯逻辑：按优先级和安装情况排出要尝试的引擎顺序 */
    fun order(prefer: String, bt: Boolean, xl: Boolean): List<String> {
        val all = if (prefer == "bt") listOf("bt", "xunlei") else listOf("xunlei", "bt")
        return all.filter { (it == "bt" && bt) || (it == "xunlei" && xl) }
    }
}
