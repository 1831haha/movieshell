package com.movieshell.player

import android.content.Context
import com.movieshell.data.repo.LogBus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.29.0：libmpv 插件化加载器。
 *
 * 壳子不再内置 mpv/FFmpeg 的 .so（打包时排除，APK 瘦 21MB），只留 AAR 的 Java 层。
 * .so 以"播放器引擎插件"（ZIP）形式经插件市场下载，解到私有 filesDir/mpv-libs
 * （公开目录部分 ROM 上 noexec，dlopen 会失败）。
 *
 * 加载顺序按 FFmpeg 依赖排：先底层库，最后 libmpv.so 和 JNI 桥 libplayer.so。
 * linker 按 SONAME 复用已加载库，所以 MPVLib 静态块里的 System.loadLibrary("player")
 * 会直接命中，不会再去系统路径找。
 */
object MpvPluginLoader {

    private const val TAG = "MpvPlugin"

    /** 插件 id（与市场 market.json 一致）。 */
    const val PLUGIN_ID = "mpv-player"

    /** 依赖顺序：底层库在前，libmpv.so、JNI 桥 libplayer.so 最后。 */
    private val LIBS_IN_ORDER = listOf(
        "libc++_shared.so",
        "libavutil.so",
        "libswresample.so",
        "libswscale.so",
        "libavcodec.so",
        "libavformat.so",
        "libavfilter.so",
        "libavdevice.so",
        "libpostproc.so",
        "libmpv.so",
        "libplayer.so",
    )

    private val loaded = AtomicBoolean(false)

    /** 插件 .so 存放目录（私有 filesDir）。 */
    fun libDir(context: Context): File =
        File(context.filesDir, "mpv-libs").also { it.mkdirs() }

    /** 插件是否已安装（全部 .so 到位）。 */
    fun isInstalled(context: Context): Boolean {
        val dir = libDir(context)
        return LIBS_IN_ORDER.all { File(dir, it).exists() }
    }

    /**
     * 确保 native 库已加载。已加载过直接 true；未安装/加载失败返回 false，
     * 调用方（MpvPlayerManager）走 initFailed 兜底回退 Media3。
     */
    fun ensureLoaded(context: Context): Boolean {
        if (loaded.get()) return true
        if (!isInstalled(context)) {
            LogBus.i(TAG, "libmpv 插件未安装")
            return false
        }
        return runCatching {
            val dir = libDir(context)
            LIBS_IN_ORDER.forEach { name ->
                System.load(File(dir, name).absolutePath)
            }
            loaded.set(true)
            LogBus.i(TAG, "libmpv 插件 native 库加载成功")
            true
        }.onFailure {
            LogBus.w(TAG, "libmpv 插件加载失败: ${it.message}")
        }.getOrDefault(false)
    }

    /** 卸载插件：删 .so 目录（需重启 App 生效，native 库进程内无法卸载）。 */
    fun uninstall(context: Context) {
        libDir(context).deleteRecursively()
        LogBus.i(TAG, "libmpv 插件已删除，重启 App 后生效")
    }
}
