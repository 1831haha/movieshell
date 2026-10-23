package com.movieshell.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SearchHistoryStore
import com.movieshell.data.repo.SearchSlowSourceFilter
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SiteSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * v0.103.0：影视聚合搜索状态容器（搜索页切后台中断修复）。
 *
 * 根因同 MusicSearchViewModel：原 SearchScreen 用 rememberCoroutineScope + remember 存
 * 搜索状态，切到设置页后 scope 被取消，在飞的 repo.searchAll 中断、结果丢失。
 * 改：activity 级 VM（viewModelScope），切页/切 tab 不销毁，搜索不中断、结果保留。
 * context 统一用 applicationContext。
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _submitted = MutableStateFlow("")
    val submitted: StateFlow<String> = _submitted

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    /** null=尚未搜过 */
    private val _groupedResults = MutableStateFlow<List<SiteSearchResult>?>(null)
    val groupedResults: StateFlow<List<SiteSearchResult>?> = _groupedResults

    /** v0.162.0：本次搜索因黑名单跳过的源提示，null=无跳过 */
    private val _skippedLabel = MutableStateFlow<String?>(null)
    val skippedLabel: StateFlow<String?> = _skippedLabel

    /** v0.188.0 M28：本次聚合搜索失败的源汇总，null=本次无源失败 */
    private val _failedLabel = MutableStateFlow<String?>(null)
    val failedLabel: StateFlow<String?> = _failedLabel

    // 聚合搜索 repo：drpy / PY 走当前激活规则的引擎适配器
    private val repo = SiteRepository(
        drpyEngine = DrpySourceManager.drpyEngineAdapter,
        pyEngine = PySourceManager.engineAdapter,
    )

    /** v0.109.0 A4：连续搜索时取消前一次的搜索协程 */
    private var searchJob: Job? = null

    init {
        refreshHistory()
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** 空结果页"返回"按钮：回到搜索首页 */
    fun backToSearch() {
        _submitted.value = ""
        _query.value = ""
    }

    /** 搜索历史：进入页面读一次，切回页面时刷新 */
    fun refreshHistory() {
        _history.value = SearchHistoryStore.list(getApplication())
    }

    fun clearHistory() {
        SearchHistoryStore.clear(getApplication())
        refreshHistory()
    }

    fun removeHistory(h: String) {
        SearchHistoryStore.remove(getApplication(), h)
        refreshHistory()
    }

    /** v0.163.0：清除会话黑名单并用当前词重搜一次（结果页“重试被跳过的源”用）。 */
    fun retrySkipped() {
        val t = _submitted.value
        if (t.isBlank()) return
        SearchSlowSourceFilter.clear()
        doSearch(t)
    }

    fun doSearch(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _submitted.value = t
        SearchHistoryStore.add(getApplication(), t)
        refreshHistory()
        _searching.value = true
        _groupedResults.value = null
        _skippedLabel.value = null
        _failedLabel.value = null
        // v0.109.0 A4：连续搜索取消前一次——旧慢请求后返回覆盖新结果；try/finally
        // 保证被取消时 spinner 也能落下
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                repo.loadFromConfig(buildSearchConfigJson(getApplication()))
                _groupedResults.value = repo.searchAll(t)
                _skippedLabel.value = SearchSlowSourceFilter.skippedLabel()
                _failedLabel.value = SiteRepository.failedSearchLabel()
            } finally {
                _searching.value = false
            }
        }
    }
}
