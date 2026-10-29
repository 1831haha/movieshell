package com.movieshell.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.model.Site
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SearchHistoryStore
import com.movieshell.data.repo.SearchSlowSourceFilter
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SiteSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * v0.189.0：搜索范围开关——false=所有源（默认，旧行为），true=只搜当前源。
     * 存在 SharedPreferences 里，重启后仍保持上次选择；切开关且已有提交词时
     * 立即按新范围重搜，不用用户重新输入。
     */
    private val _onlyCurrentSource = MutableStateFlow(
        getApplication<Application>().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_ONLY_CURRENT, false),
    )
    val onlyCurrentSource: StateFlow<Boolean> = _onlyCurrentSource

    /** v0.189.0：只搜当前源时的当前源展示名，null=未设置任何当前源 */
    private val _currentSourceName = MutableStateFlow<String?>(null)
    val currentSourceName: StateFlow<String?> = _currentSourceName

    /** v0.192.0：左右分栏左栏——本次搜索的目标站（配置原顺序，先到先列不跳动）。 */
    private val _searchSites = MutableStateFlow<List<Site>>(emptyList())
    val searchSites: StateFlow<List<Site>> = _searchSites

    /** v0.192.0：已跑完的源 key 集合（空结果也算跑完，供左栏“搜索中…”/条数切换）。 */
    private val _completedKeys = MutableStateFlow<Set<String>>(emptySet())
    val completedKeys: StateFlow<Set<String>> = _completedKeys

    /** v0.192.0：右栏当前选中的源 key，null=全部（全部时按先到先出的分组顺序混排）。 */
    private val _selectedSiteKey = MutableStateFlow<String?>(null)
    val selectedSiteKey: StateFlow<String?> = _selectedSiteKey

    /** v0.192.0：左栏点源切换右栏（点“全部”传 null）。 */
    fun selectSite(key: String?) {
        _selectedSiteKey.value = key
    }

    companion object {
        private const val PREFS = "search_prefs"
        private const val KEY_ONLY_CURRENT = "only_current_source"
    }

    // 聚合搜索 repo：drpy / PY 走当前激活规则的引擎适配器
    private val repo = SiteRepository(
        drpyEngine = DrpySourceManager.drpyEngineAdapter,
        pyEngine = PySourceManager.engineAdapter,
    )

    /** v0.109.0 A4：连续搜索时取消前一次的搜索协程 */
    private var searchJob: Job? = null

    init {
        refreshHistory()
        refreshCurrentSourceName()
    }

    /** v0.189.0：切开关（持久化；已有提交词时按新范围立即重搜）。 */
    fun setOnlyCurrentSource(only: Boolean) {
        if (_onlyCurrentSource.value == only) return
        _onlyCurrentSource.value = only
        getApplication<Application>().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONLY_CURRENT, only).apply()
        val t = _submitted.value
        if (t.isNotBlank()) doSearch(t)
    }

    /** v0.189.0：刷新当前源展示名（进搜索页 / 切回页面时调，源可能已在别处换过）。 */
    fun refreshCurrentSourceName() {
        _currentSourceName.value = currentSourceName(getApplication())
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** 空结果页"返回"按钮：回到搜索首页 */
    fun backToSearch() {
        _submitted.value = ""
        _query.value = ""
    }

    /** 搜索历史：进入页面读一次，切回页面时刷新
     * v0.270.0（T27）：历史文件读盘走 IO 线程，原来在主线程同步读。 */
    fun refreshHistory() {
        viewModelScope.launch {
            _history.value = withContext(Dispatchers.IO) { SearchHistoryStore.list(getApplication()) }
        }
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
        // v0.192.0：新一轮先清左栏与选中态，半截结果不串到下一轮。
        _searchSites.value = emptyList()
        _completedKeys.value = emptySet()
        _selectedSiteKey.value = null
        // v0.109.0 A4：连续搜索取消前一次——旧慢请求后返回覆盖新结果；try/finally
        // 保证被取消时 spinner 也能落下
        searchJob?.cancel()
        // v0.189.0：只搜当前源且无当前源时直接出空结果，不进 loadFromConfig——
        // 空 sites 配置会被 repo 当成残缺订阅而保留旧站源列表，搜出来就变全量了
        refreshCurrentSourceName()
        if (_onlyCurrentSource.value && _currentSourceName.value == null) {
            searchJob = null
            _groupedResults.value = emptyList()
            _skippedLabel.value = null
            _failedLabel.value = null
            _searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            try {
                // v0.270.0（T27）：拼搜索配置（含订阅文件读、规则目录扫描）走 IO，
                // 原来在主线程协程里同步拼，源多时卡搜索页。
                repo.loadFromConfig(
                    withContext(Dispatchers.IO) {
                        buildSearchConfigJson(getApplication(), _onlyCurrentSource.value)
                    },
                )
                // v0.192.0：左栏先列出本次目标站（配置原顺序），再开始流式跑。
                _searchSites.value = repo.searchTargetSites()
                // v0.191.0：流式累积——每到一个分组就往列表里追加，不等全部源跑完。
                // 旧写法是 searchAll awaitAll 后一次性赋值，搜中途 groupedResults
                // 一直是 null，页面只能放骨架屏。取消时 collect 抛异常走 finally，
                // 半截结果留在原地无妨：下一次 doSearch 开头先置 null 再重搜。
                _groupedResults.value = emptyList()
                repo.searchAllFlow(t).collect { group ->
                    // v0.192.0：空结果也到这里——只计已搜数与左栏状态，不进展示列表。
                    _completedKeys.value = _completedKeys.value + group.site.key
                    if (group.vods.isNotEmpty()) {
                        _groupedResults.value = (_groupedResults.value.orEmpty() + group)
                    }
                }
                _skippedLabel.value = SearchSlowSourceFilter.skippedLabel()
                _failedLabel.value = SiteRepository.failedSearchLabel()
            } finally {
                // v0.217.0（F60）：收尾只认当前任务——旧任务被取消后 finally
                // 可能晚于新任务启动才跑，原来无条件置 false 会把新搜索的
                // 转圈提前灭掉，结果还在往外冒、转圈却没了。
                if (searchJob == coroutineContext[Job]) _searching.value = false
            }
        }
    }
}
