package com.movieshell.ui.novel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.SearchHistoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * v0.103.0：找书页状态容器（搜索页切后台中断修复）。
 *
 * 根因同 MusicSearchViewModel：原 NovelSearchScreen 用 rememberCoroutineScope + remember，
 * 切到设置页后 scope 被取消，在飞的多书源并发搜索中断、结果丢失。
 * 改：activity 级 VM（viewModelScope），切页/切 tab 不销毁，搜索不中断、结果保留。
 * Toast/context 统一用 applicationContext。
 *
 * v0.66.0 锁单源：lockPyName 由页面经 setLockPyName 传入（LaunchedEffect(pySourceName) 同步）；
 * 锁变化时清空旧结果（等价于原来"新路由=新 composition=空结果"）。
 */
class NovelSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _results = MutableStateFlow<List<SourceGroup>>(emptyList())
    val results: StateFlow<List<SourceGroup>> = _results

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched

    private val _joining = MutableStateFlow<String?>(null)
    val joining: StateFlow<String?> = _joining

    /** v0.67.0：小说搜索历史（独立作用域） */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    /** v0.66.0：锁定的 PY 源名（书源管理点源直达时带入） */
    var lockPyName: String? = null
        private set

    init {
        viewModelScope.launch {
            BookSourceManager.load(getApplication())
            PyBookSourceManager.load(getApplication())
        }
        refreshHistory()
    }

    fun setLockPyName(name: String?) {
        if (lockPyName != name) {
            lockPyName = name
            _results.value = emptyList()
            _searched.value = false
            _keyword.value = ""
        }
    }

    fun setKeyword(k: String) {
        _keyword.value = k
    }

    fun refreshHistory() {
        _history.value = SearchHistoryStore.list(getApplication(), "novel")
    }

    fun clearHistory() {
        SearchHistoryStore.clear(getApplication(), "novel")
        refreshHistory()
    }

    fun removeHistory(h: String) {
        SearchHistoryStore.remove(getApplication(), h, "novel")
        refreshHistory()
    }

    private fun lockedPyRule() =
        lockPyName?.let { name -> PyBookSourceManager.rules.value.find { it.name == name } }

    fun doSearch() {
        val kw = _keyword.value.trim()
        val locked = lockedPyRule()
        // v0.66.0：锁定模式下只要该源存在即可搜
        val canSearch = if (locked != null) {
            true
        } else {
            BookSourceManager.sources.value.any { it.enabled } ||
                PyBookSourceManager.rules.value.any { it.name in PyBookSourceManager.enabled.value }
        }
        if (kw.isEmpty() || !canSearch) {
            if (!canSearch) {
                Toast.makeText(getApplication(), "没有启用的书源，先去书源管理添加", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // v0.67.0：记搜索历史
        SearchHistoryStore.add(getApplication(), kw, "novel")
        refreshHistory()
        viewModelScope.launch {
            _loading.value = true
            _searched.value = true
            _results.value = coroutineScope {
                // v0.66.0：锁定单 PY 源 → 只搜它
                if (locked != null) {
                    NovelSourceEngine.searchAllPy(getApplication(), listOf(locked), kw)
                        .filter { it.second.isNotEmpty() }
                        .map { (r, books) ->
                            SourceGroup(
                                PyBookSourceManager.SOURCE_KEY_PREFIX + r.name,
                                "${r.title}（本地PY）",
                                books,
                            )
                        }
                } else {
                    val enabledSources = BookSourceManager.sources.value.filter { it.enabled }
                    val enabledPyRules = PyBookSourceManager.rules.value
                        .filter { it.name in PyBookSourceManager.enabled.value }
                    val jsonJob = async {
                        NovelSourceEngine.searchAll(enabledSources, kw)
                            .filter { it.second.isNotEmpty() }
                            .map { (s, books) -> SourceGroup(s.key, s.name, books) }
                    }
                    val pyJob = async {
                        NovelSourceEngine.searchAllPy(getApplication(), enabledPyRules, kw)
                            .filter { it.second.isNotEmpty() }
                            .map { (r, books) ->
                                SourceGroup(
                                    PyBookSourceManager.SOURCE_KEY_PREFIX + r.name,
                                    "${r.title}（本地PY）",
                                    books,
                                )
                            }
                    }
                    jsonJob.await() + pyJob.await()
                }
            }
            _loading.value = false
        }
    }

    fun joinShelf(group: SourceGroup, item: NovelSearchResult) {
        viewModelScope.launch {
            _joining.value = group.key + item.remoteId
            val ok = runCatching {
                val metas = if (group.key.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)) {
                    NovelSourceEngine.chaptersPy(getApplication(), item)
                } else {
                    val source = BookSourceManager.sources.value.firstOrNull { it.key == group.key }
                        ?: return@runCatching false
                    NovelSourceEngine.chapters(source, item)
                }
                if (metas.isEmpty()) return@runCatching false
                val novel = Novel(
                    id = "bs_${group.key.hashCode()}_${item.remoteId.hashCode()}",
                    sourceKey = group.key,
                    title = item.title,
                    author = item.author,
                    cover = item.cover,
                    addedAt = System.currentTimeMillis(),
                )
                // 只存目录（带章节 URL），正文阅读时按需拉取并缓存
                val chapters = metas.map {
                    Chapter(
                        novelId = novel.id,
                        index = it.index,
                        title = it.title,
                        content = "",
                        url = it.url,
                    )
                }
                NovelStore.addNovel(getApplication(), novel, chapters)
            }.getOrDefault(false)
            _joining.value = null
            Toast.makeText(
                getApplication(),
                if (ok) "已加入书架" else "加入失败（目录拉取失败）",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
