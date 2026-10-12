package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器设置持久化（v0.14.0）。
 *
 * v0.16.2：片头/片尾跳过改为按源单独设置（用户反馈：每个源片头片尾不一样，不能统一设）。
 * - 全局默认值：skipIntroSec / skipOutroSec（新源的初始值）
 * - 按源覆盖：siteKey → intro/outro 秒数，存在则用源的，不存在用全局
 */
object PlayerSettings {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_SKIP_INTRO = "skip_intro_sec"
    private const val KEY_SKIP_OUTRO = "skip_outro_sec"
    private const val PREFIX_INTRO = "skip_intro_"
    private const val PREFIX_OUTRO = "skip_outro_"

    private val _skipIntroSec = MutableStateFlow(0)
    val skipIntroSec: StateFlow<Int> = _skipIntroSec

    private val _skipOutroSec = MutableStateFlow(0)
    val skipOutroSec: StateFlow<Int> = _skipOutroSec

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _skipIntroSec.value = prefs.getInt(KEY_SKIP_INTRO, 0).coerceIn(0, 600)
        _skipOutroSec.value = prefs.getInt(KEY_SKIP_OUTRO, 0).coerceIn(0, 600)
    }

    /** 全局默认片头（新源初始值）。 */
    fun setSkipIntro(context: Context, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP_INTRO, v).apply()
        _skipIntroSec.value = v
    }

    /** 全局默认片尾（新源初始值）。 */
    fun setSkipOutro(context: Context, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP_OUTRO, v).apply()
        _skipOutroSec.value = v
    }

    /**
     * 取某源的片头跳过秒数：有按源设置用源的，否则用全局默认。
     * siteKey 为空时直接返回全局。
     */
    fun getSkipIntroFor(context: Context, siteKey: String?): Int {
        if (siteKey.isNullOrBlank()) return _skipIntroSec.value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = PREFIX_INTRO + siteKey.hashCode()
        return if (prefs.contains(key)) prefs.getInt(key, 0).coerceIn(0, 600)
        else _skipIntroSec.value
    }

    /**
     * 取某源的片尾跳过秒数：有按源设置用源的，否则用全局默认。
     * siteKey 为空时直接返回全局。
     */
    fun getSkipOutroFor(context: Context, siteKey: String?): Int {
        if (siteKey.isNullOrBlank()) return _skipOutroSec.value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = PREFIX_OUTRO + siteKey.hashCode()
        return if (prefs.contains(key)) prefs.getInt(key, 0).coerceIn(0, 600)
        else _skipOutroSec.value
    }

    /** 设置某源的片头跳过秒数。 */
    fun setSkipIntroFor(context: Context, siteKey: String, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREFIX_INTRO + siteKey.hashCode(), v).apply()
        LogBus.i("PlayerSettings", "源 $siteKey 片头跳过设为 ${v}s")
    }

    /** 设置某源的片尾跳过秒数。 */
    fun setSkipOutroFor(context: Context, siteKey: String, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREFIX_OUTRO + siteKey.hashCode(), v).apply()
        LogBus.i("PlayerSettings", "源 $siteKey 片尾跳过设为 ${v}s")
    }
}
