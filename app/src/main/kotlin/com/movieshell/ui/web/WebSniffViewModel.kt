package com.movieshell.ui.web

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import com.movieshell.data.source.SniffCandidate
import java.util.Collections

/**
 * v0.275.0（T40）：嗅探候选原来全是 WebSniffScreen 里的 remember，
 * 转屏/配置变化重建 composition 就丢了已嗅到的候选。收进导航入口级
 * ViewModel：转屏不丢，离开嗅探页（入口销毁）时自然清空。
 * pendingHeaders 与候选成对出现，一并收进来，否则转屏后头与候选对不上。
 */
class WebSniffViewModel : ViewModel() {

    val candidates = mutableStateListOf<SniffCandidate>()

    val candidateUrls = Collections.synchronizedSet(mutableSetOf<String>())

    val pendingHeaders = mutableMapOf<String, Map<String, String>>()
}
