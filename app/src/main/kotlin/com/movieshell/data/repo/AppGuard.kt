package com.movieshell.data.repo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * v0.185.0：轻量自保护壳（无账号、无固定签名、无商业加固平台可用的前提下，
 * 能在 App 内做的部分；不是真加固，见下面“做不到什么”）。
 *
 * 能做：
 * - 可调试包（release 包本不该 debuggable）→ 硬拦截：启动即提示并退出。
 * - 调试器已连上 → 硬拦截：同上。
 * - root / 模拟器 → 只警告、不拦截：root 机、模拟器上也有正常用户，
 *   拦了就是误伤；只记日志 + MainActivity 弹一次提示，继续用。
 * - B7：上面两条硬拦截只在 release 包生效；debug 包（BuildConfig.DEBUG）
 *   本来就是用来调试的，硬拦截会让真机调试和 Compose UI 测试没法跑，
 *   所以 debug 下硬拦截全关，只留 root/模拟器警告。
 *
 * 做不到（说清，不装）：
 * - 签名验签：云构建每轮现场生成新 keystore、签名每版都变，
 *   把某版签名写死进去，下版自己先被挡住；真验签要先换固定签名密钥
 *   （见 v0.185.0 changelog），轻量壳不做假验签。
 * - dex 加密 / 防反编译到底：R8 混淆已开（见 proguard-rules.pro），
 *   只能抬高阅读门槛，挡不住专门反编译的人；商业加固平台才有 dex 加密，
 *   且可能破坏 Chaquopy / DexClassLoader 插件动态加载，未经真机验不引入。
 */
object AppGuard {

    data class Result(
        val hardReasons: List<String>,
        val warnings: List<String>,
    ) {
        val hardFail: Boolean get() = hardReasons.isNotEmpty()
    }

    fun check(context: Context): Result {
        val hard = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        // B7：debug 包不硬拦截——可调试包和调试器已连都是调试本身，拦了就没法调试。
        if (!com.movieshell.BuildConfig.DEBUG) {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                hard.add("可调试包")
            }
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                hard.add("调试器已连接")
            }
        } else {
            LogBus.i("AppGuard", "debug 包，硬拦截已关闭")
        }
        if (isRooted()) warnings.add("root 环境")
        if (isEmulator()) warnings.add("模拟器环境")
        if (hard.isNotEmpty()) LogBus.e("AppGuard", "硬拦截：${hard.joinToString("、")}")
        if (warnings.isNotEmpty()) LogBus.w("AppGuard", "环境警告：${warnings.joinToString("、")}")
        return Result(hard, warnings)
    }

    private fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/data/local/bin/su",
            "/data/local/xbin/su",
        )
        return suPaths.any { File(it).exists() }
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("sdk_gphone") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk") ||
            (manufacturer == "google" && Build.BRAND.lowercase() == "generic") ||
            hardware == "goldfish" ||
            hardware == "ranchu" ||
            hardware.contains("vbox")
    }
}
