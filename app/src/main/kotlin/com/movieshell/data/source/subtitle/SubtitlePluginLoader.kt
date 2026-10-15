package com.movieshell.data.source.subtitle

import android.content.Context
import com.movieshell.data.repo.LogBus
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * v0.30.0：字幕搜索插件加载器。
 *
 * 壳子不再内置字幕源实现（如 SubHD），只留接口。实现由「字幕搜索」插件
 * 提供（DEX），经插件市场下载，解到私有 filesDir/subtitle-plugin，
 * 用 DexClassLoader 动态加载（模式同 SpiderEngine 加载 spider jar）。
 *
 * 未安装插件时字幕源列表为空，UI 隐藏「在线搜索」入口或提示去市场安装。
 */
object SubtitlePluginLoader {

    private const val TAG = "SubtitlePlugin"

    /** 插件 id（与市场 market.json 一致）。 */
    const val PLUGIN_ID = "subtitle-search"

    /** 插件主类（插件模块中的实现类）。 */
    private const val IMPL_CLASS = "com.movieshell.plugin.subtitle.SubHdSource"

    private val cached = AtomicReference<SubtitleSource?>(null)
    private var triedLoad = false

    /** 插件 DEX 存放目录（私有 filesDir）。 */
    fun pluginDir(context: Context): File =
        File(context.filesDir, "subtitle-plugin").also { it.mkdirs() }

    /** 插件是否已安装（DEX 到位）。 */
    fun isInstalled(context: Context): Boolean =
        File(pluginDir(context), "subtitle-plugin.dex").exists()

    /**
     * 获取字幕源。已加载过直接返回；未安装返回 null；
     * 加载失败记日志返回 null（调用方隐藏入口）。
     */
    fun getSource(context: Context): SubtitleSource? {
        cached.get()?.let { return it }
        if (triedLoad) return null
        triedLoad = true
        if (!isInstalled(context)) {
            LogBus.i(TAG, "字幕搜索插件未安装")
            return null
        }
        return runCatching {
            val dex = File(pluginDir(context), "subtitle-plugin.dex")
            val loader = DexClassLoader(
                dex.absolutePath,
                pluginDir(context).absolutePath,
                null,
                SubtitleSource::class.java.classLoader,
            )
            val clazz = loader.loadClass(IMPL_CLASS)
            val source = clazz.getDeclaredConstructor().newInstance() as SubtitleSource
            cached.set(source)
            LogBus.i(TAG, "字幕搜索插件加载成功：${source.name}")
            source
        }.onFailure {
            LogBus.w(TAG, "字幕搜索插件加载失败: ${it.message}")
        }.getOrNull()
    }

    /** 卸载插件：删 DEX 目录（下次启动生效）。 */
    fun uninstall(context: Context) {
        pluginDir(context).deleteRecursively()
        cached.set(null)
        triedLoad = false
        LogBus.i(TAG, "字幕搜索插件已删除")
    }

    /** 重置加载状态（插件安装后调用，使下次 getSource 重新加载）。 */
    fun reset() {
        cached.set(null)
        triedLoad = false
    }
}

/**
 * 字幕源注册表（v0.30.0：动态，插件提供）。
 *
 * 壳子只留基础字幕功能（本地加载/样式/记忆，见 OnlineSubtitleKit），
 * 在线搜索实现由插件提供。无插件时列表为空。
 */
object SubtitleSources {
    fun all(context: Context): List<SubtitleSource> =
        listOfNotNull(SubtitlePluginLoader.getSource(context))

    fun default(context: Context): SubtitleSource? = all(context).firstOrNull()
}
