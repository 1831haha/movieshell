package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器设置持久化（v0.14.0）。
 *
 * - skipIntroSec：片头跳过秒数（从0开始跳过N秒），0=不跳
 * - skipOutroSec：片尾跳过秒数（距离结尾N秒时自动下一集/停止），0=不跳
 */
object PlayerSettings {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_SKIP_INTRO = "skip_intro_sec"
    private const val KEY_SKIP_OUTRO = "skip_outro_sec"

    private val _skipIntroSec = MutableStateFlow(0)
    val skipIntroSec: StateFlow<Int> = _skipIntroSec

    private val _skipOutroSec = MutableStateFlow(0)
    val skipOutroSec: StateFlow<Int> = _skipOutroSec

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _skipIntroSec.value = prefs.getInt(KEY_SKIP_INTRO, 0).coerceIn(0, 600)
        _skipOutroSec.value = prefs.getInt(KEY_SKIP_OUTRO, 0).coerceIn(0, 600)
    }

    fun setSkipIntro(context: Context, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP_INTRO, v).apply()
        _skipIntroSec.value = v
    }

    fun setSkipOutro(context: Context, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP_OUTRO, v).apply()
        _skipOutroSec.value = v
    }
}
