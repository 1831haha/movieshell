package com.movieshell.ui.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicHistoryStore
import com.movieshell.data.repo.PlayerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * v0.103.0：音乐在线搜索状态容器（搜索页切后台中断修复）。
 *
 * 根因：原 MusicScreen 用 rememberCoroutineScope + remember 存 query/tracks/searching，
 * 切到设置页后 MusicScreen 离开 composition，scope 被取消，在飞的 MusicApi.search 中断、
 * 结果丢失。改：状态与搜索逻辑搬进 activity 级 VM（viewModelScope），切页/切 tab 不销毁，
 * 搜索不中断、结果保留。context 统一用 applicationContext（MusicHistoryStore 只读写 filesDir）。
 */
class MusicSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _tracks = MutableStateFlow<List<MusicApi.Track>>(emptyList())
    val tracks: StateFlow<List<MusicApi.Track>> = _tracks

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched

    /** v0.84.0：搜索历史（视频/小说两端独立存，互不串） */
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory

    /** v0.109.0 A4：连续搜索时取消前一次的搜索协程 */
    private var searchJob: Job? = null

    init {
        reloadHistory()
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun reloadHistory() {
        viewModelScope.launch {
            _searchHistory.value = MusicHistoryStore.searchHistory(getApplication())
        }
    }

    fun removeHistory(h: String) {
        viewModelScope.launch {
            MusicHistoryStore.removeSearch(getApplication(), h)
            reloadHistory()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            MusicHistoryStore.clearSearch(getApplication())
            reloadHistory()
        }
    }

    /** 歌单导入成功后回填列表（原 OnlineTab.onTracksLoaded） */
    fun setTracks(list: List<MusicApi.Track>) {
        _tracks.value = list
        _searched.value = true
    }

    /** v0.146.0：空关键词切平台时回榜单态——榜单跟随平台刷新，旧搜索结果不再赖着不走。 */
    fun resetToCharts() {
        _searched.value = false
    }

    /**
     * v0.82.0：接受显式 query（风格 chips 点后直接搜，不等 state 落定）。
     * 键盘收起由调用方（composable）在调用前处理，不在这里碰 UI。
     */
    fun doSearch(q0: String = _query.value) {
        val q = q0.trim()
        if (q.isBlank()) return
        // v0.109.0 A4：连续搜索取消前一次——之前的 `_searching` 守卫会让一次慢搜索
        // 锁死后续所有点击（30s 内点搜索无反应）；改为取消旧 Job 后重搜
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _searched.value = true
            try {
                // v0.84.0：记搜索历史
                MusicHistoryStore.addSearch(getApplication(), q)
                _searchHistory.value = MusicHistoryStore.searchHistory(getApplication())
                // 注意：runCatching 会吞 CancellationException，这里显式重抛，
                // 否则旧慢请求被取消后仍会走到 onFailure 把 _tracks 清空覆盖新结果
                // v0.126.0：按持久化的所选平台搜索（PlayerSettings.musicPlatform）
                runCatching { MusicApi.search(q, platform = PlayerSettings.musicPlatform.value) }
                    .onSuccess { _tracks.value = it }
                    .onFailure {
                        if (it is CancellationException) throw it
                        LogBus.e("Music", "搜索失败：${it.message}")
                        _tracks.value = emptyList()
                    }
            } finally {
                _searching.value = false
            }
        }
    }
}
