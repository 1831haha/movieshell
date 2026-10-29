package com.movieshell.ui.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicHistoryStore
import com.movieshell.data.repo.MusicSettings
import com.movieshell.player.MusicPlayer
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

    // v0.213.0 音乐界面误导修复：搜索失败原来把上次好结果清空，
    // 空结果分支又统一渲染“没搜到”，断网与真无结果无法区分；
    // 失败改只记 error 态、不碰旧结果，UI 按 error 态说实话。
    private val _searchError = MutableStateFlow(false)
    val searchError: StateFlow<Boolean> = _searchError

    /** v0.84.0：搜索历史（视频/小说两端独立存，互不串） */
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory

    /**
     * v0.180.0：音乐页平台行——先按已知可搜索平台兜底显示，音源加载成功后
     * 按音源自报的平台刷新（墨澜类脚本以后多一个平台，这里就多一个 chip，
     * UI 不写死）；已选平台即使不在新音源里也保留一行，避免已选状态凭空消失。
     */
    private val _availablePlatforms =
        MutableStateFlow<List<String>>(MusicApi.searchablePlatformCodes())
    val availablePlatforms: StateFlow<List<String>> = _availablePlatforms

    fun refreshPlatforms() {
        viewModelScope.launch {
            val discovered = runCatching {
                MusicPlayer.sourcePlatforms(getApplication())
            }.getOrDefault(emptyList())
            if (discovered.isEmpty()) return@launch
            val current = MusicSettings.musicPlatform.value
            _availablePlatforms.value =
                (discovered + listOf(current)).filter { it.isNotBlank() }.distinct()
        }
    }

    /** v0.109.0 A4：连续搜索时取消前一次的搜索协程 */
    private var searchJob: Job? = null
    private var searchGen = 0

    // v0.275.0（T16）：搜索代际号——旧 Job 被取消后 finally 与新 Job 置 true
    // 无先后保证，旧 finally 可能在新搜索已开始后才跑，把 _searching 误清成
    // false（转圈提前灭）；只有最新一代的 finally 才有权清 searching。

    init {
        reloadHistory()
        refreshPlatforms()
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

    /** 歌单导入成功后回填列表（原 OnlineTab.onTracksLoaded）
     * v0.217.0（F33）：新数据到了，旧的搜索失败标志一并清掉——原来只设新数据，
     * 上次搜索断网失败的横幅会赖在导入成功的列表顶上。 */
    fun setTracks(list: List<MusicApi.Track>) {
        _tracks.value = list
        _searched.value = true
        _searchError.value = false
    }

    /** v0.146.0：空关键词切平台时回榜单态——榜单跟随平台刷新，旧搜索结果不再赖着不走。 */
    fun resetToCharts() {
        _searched.value = false
        _searchError.value = false
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
        val myGen = ++searchGen
        searchJob = viewModelScope.launch {
            _searching.value = true
            _searched.value = true
            _searchError.value = false
            try {
                // v0.84.0：记搜索历史
                MusicHistoryStore.addSearch(getApplication(), q)
                _searchHistory.value = MusicHistoryStore.searchHistory(getApplication())
                // 注意：runCatching 会吞 CancellationException，这里显式重抛，
                // 否则旧慢请求被取消后仍会走到 onFailure 把 _tracks 清空覆盖新结果
                // v0.126.0：按持久化的所选平台搜索（MusicSettings.musicPlatform）
                runCatching { MusicApi.search(q, platform = MusicSettings.musicPlatform.value) }
                    .onSuccess { _tracks.value = it }
                    .onFailure {
                        if (it is CancellationException) throw it
                        LogBus.e("Music", "搜索失败：${it.message}")
                        // v0.213.0：失败不清空旧结果，只记 error 态
                        _searchError.value = true
                    }
            } finally {
                if (myGen == searchGen) _searching.value = false
            }
        }
    }
}
