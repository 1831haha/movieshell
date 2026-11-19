package com.movieshell.core.util

import android.content.Context
import coil.imageLoader
import com.movieshell.core.log.LogBus

/**
 * v1.3.25 合并 8：启动看门狗自愈。
 *
 * 连续启动失败的兜底：Application.onCreate 里把“本次启动未完成”写进
 * 独立 prefs（不用 movieshell_prefs——主 prefs 本身坏了也能读），
 * MainActivity.onResume 走到前台才算本次启动健康、复位计数。
 * 10 分钟内连续 3 次启动都没走到前台，下一次启动时自愈。
 *
 * 时间窗是关键：WorkManager 单独拉起进程时没有 Activity，永远到不了
 * healthy；不设时间窗会被后台启动误计。窗内连续 3 次才动手，单次
 * 误计（后台拉起恰好卡在窗内）无害，计数在下次健康启动时清零。
 *
 * 自愈只清可再生的缓存，不碰用户数据（订阅/追剧/书架/设置/源文件、
 * 播放历史、下载一律不动）；做完记 healed 标记，MainActivity 下次
 * onResume 时弹一次 Toast 告诉用户，不静默。
 */
object StartupWatchdog {
    private const val PREFS = "startup_watchdog"
    private const val KEY_LAST_START_OK = "last_start_ok"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_LAST_START_AT = "last_start_at"
    private const val KEY_HEALED = "healed"
    private const val MAX_CONSECUTIVE_CRASHES = 3
    private const val CRASH_WINDOW_MS = 10L * 60 * 1000

    /** Application.onCreate 里尽早调（CrashHandler.install 之后）。 */
    fun onAppStart(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastOk = p.getBoolean(KEY_LAST_START_OK, true)
        val lastAt = p.getLong(KEY_LAST_START_AT, 0L)
        var count = p.getInt(KEY_CRASH_COUNT, 0)
        if (!lastOk && now - lastAt in 0..CRASH_WINDOW_MS) {
            count++
        } else if (lastOk) {
            count = 0
        }
        // lastOk=false 但超窗时不计数也不清零——可能是后台拉起进程，
        // 留给下一次健康启动的 markHealthy 复位。
        p.edit()
            .putBoolean(KEY_LAST_START_OK, false)
            .putLong(KEY_LAST_START_AT, now)
            .putInt(KEY_CRASH_COUNT, count)
            .apply()
        LogBus.i("Watchdog", "启动标记：连续未完成启动 $count 次")
        if (count >= MAX_CONSECUTIVE_CRASHES) {
            heal(context)
            p.edit().putInt(KEY_CRASH_COUNT, 0).putBoolean(KEY_HEALED, true).apply()
        }
    }

    /** MainActivity.onResume 里调——走到前台即算本次启动健康。 */
    fun markHealthy(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LAST_START_OK, true)
            .putInt(KEY_CRASH_COUNT, 0)
            .apply()
    }

    /** 取走一次“已自愈”提示（MainActivity.onResume 里弹 Toast 用）。 */
    fun consumeHealedNotice(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_HEALED, false)) return false
        p.edit().putBoolean(KEY_HEALED, false).apply()
        return true
    }

    private fun heal(context: Context) {
        LogBus.w("Watchdog", "连续 $MAX_CONSECUTIVE_CRASHES 次启动未完成，开始自愈：清理缓存")
        runCatching {
            context.cacheDir?.listFiles()?.forEach {
                runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() }
            }
            LogBus.i("Watchdog", "自愈：已清 cacheDir")
        }.onFailure { LogBus.w("Watchdog", "自愈清 cacheDir 失败：${it.message}") }
        runCatching {
            context.imageLoader.diskCache?.clear()
            LogBus.i("Watchdog", "自愈：已清 Coil 磁盘缓存")
        }.onFailure { LogBus.w("Watchdog", "自愈清 Coil 缓存失败：${it.message}") }
    }
}
