package com.movieshell.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import coil.compose.AsyncImage
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.goldTitleColor
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import com.movieshell.ui.components.TechDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.launch

/**
 * v0.61.0 找书页：多书源并发真搜 → 加入书架（只拉目录，正文阅读时按需拉取）。
 * v0.64.0：JSON 书源 + 本地 PY 书源一起搜。
 * v0.66.0：支持锁定单个 PY 源（pySourceName 非空时只搜该源，用于书源管理点源直达）。
 */
@Composable
fun NovelSearchScreen(
    onBack: () -> Unit = {},
    onManageSources: () -> Unit = {},
    pySourceName: String? = null,
    embedded: Boolean = false, // v0.79.0：嵌入书架子 tab 时隐藏头部
    autoFocus: Boolean = false, // v0.195.0：书架内嵌搜索时打开即聚焦键盘，直接输
    hideSearchField: Boolean = false, // v0.196.0：搜索框已上移到书架的“找书”标题行，这里不再重复画框
    // v0.229.0：点结果卡片进书籍信息整页（卡片里的加入书架钮自己消费点击，不触发它）。
    onOpenDetail: (SourceGroup, NovelSearchResult) -> Unit = { _, _ -> },
) {
    // v0.103.0：activity 级 VM——切后台/切 tab 不销毁，viewModelScope 里的多书源搜索不中断、结果保留
    val activity = LocalContext.current as ComponentActivity
    val vm: NovelSearchViewModel = viewModel(activity)
    // v0.221.0：离页清搜索框——活动级 VM 的 keyword 切 tab/切页也不销毁，
    // 回找书页框里还留着上次的词；离页时清掉。只清框里的词，搜索历史
    // （下方历史记录）与已出结果不动；独立找书页与书架内嵌共用此屏，
    // 两处宿主离页都会走到这个 dispose。
    DisposableEffect(Unit) {
        onDispose { vm.setKeyword("") }
    }
    // v0.109.0 A9：亮色主题白底金字对比度约 2.1:1，按底色明度自适应
    val gold = goldTitleColor()

    val sources by BookSourceManager.sources.collectAsState()
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val pyEnabled by PyBookSourceManager.enabled.collectAsState()
    val keyword by vm.keyword.collectAsState()
    val loading by vm.loading.collectAsState()
    val results by vm.results.collectAsState()
    val searched by vm.searched.collectAsState()
    val joining by vm.joining.collectAsState()
    // v0.67.0：小说搜索历史（独立作用域）
    val history by vm.history.collectAsState()

    // v0.108.0 B4：书架成员 id 快照——按钮按此切换"加入书架"/"已在书架"
    var shelfIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(results, joining) {
        // 结果刷新或一次加入结束 → 重拉书架成员，按钮状态实时跟上
        if (results.isNotEmpty() && joining.isEmpty()) {
            shelfIds = NovelStore.list(appCtx).map { it.id }.toSet()
        }
    }
    // v0.108.0 B4：已在书架时点按钮 → 二次确认移出的目标
    var removeTarget by remember { mutableStateOf<Pair<SourceGroup, NovelSearchResult>?>(null) }

    // v0.66.0：锁定单 PY 源时只搜它（书源管理点源直达）；锁变化时 VM 清空旧结果
    val lockedPyRule = remember(pyRules, pySourceName) {
        pySourceName?.let { name -> pyRules.find { it.name == name } }
    }
    val totalEnabled = remember(sources, pyRules, pyEnabled) {
        sources.count { it.enabled } + pyRules.count { it.name in pyEnabled }
    }
    LaunchedEffect(pySourceName) { vm.setLockPyName(pySourceName) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!embedded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = gold)
            }
            Text(
                // v0.66.0：锁定单源时标题显示源名
                if (lockedPyRule != null) lockedPyRule.title else "找书",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = gold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // v0.66.0：锁定单源时不显示书源管理入口
            if (lockedPyRule == null) {
                TextButton(onClick = onManageSources) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Source, "书源管理", tint = gold, modifier = Modifier.padding(end = 4.dp))
                        Text("书源($totalEnabled)", color = gold, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        }
        // v0.195.0：自动聚焦（书架点搜索即输，不用再点一次框；框已外移到标题行时由外层聚焦）
        val searchFocus = remember { FocusRequester() }
        LaunchedEffect(autoFocus) {
            if (autoFocus && !hideSearchField) searchFocus.requestFocus()
        }
        if (!hideSearchField) {
        // v0.223.0：找书搜索框重设计——整条一体：左搜索图标 + 无框输入 +
        // 右清除/搜索，不再是胶囊描边框 + 外挂搜索钮；填充底 + 聚焦青色描边。
        OutlinedTextField(
            value = keyword,
            onValueChange = { vm.setKeyword(it) },
            placeholder = { Text("书名 / 作者") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            leadingIcon = {
                Icon(
                    Icons.Filled.Search, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { vm.setKeyword("") }) {
                            Icon(
                                Icons.Filled.Close, "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { vm.doSearch() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("搜索", color = gold, fontSize = 14.sp)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.doSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4DD0E1),
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                cursorColor = Color(0xFF4DD0E1),
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
        )
        Spacer(Modifier.height(12.dp))
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // v0.223.0：加载圈专业化——细环 32dp、无中央点（颜色语义不动，
                    // 黄仍是加载中）；主副两行文案，不再是大黄圈 + 一行小字。
                    StatusRing(
                        state = StatusRingState.Loading,
                        diameter = 32.dp,
                        showDot = false,
                        strokeWidth = 2.5.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("正在搜索…", fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("$totalEnabled 个书源", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            searched && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没找到，换个关键词或检查书源规则", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !searched -> {
                // v0.67.0：搜索历史（一点即搜，X 单删）
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (totalEnabled == 0) "还没有启用的书源，点右上角去添加"
                            else "输入书名或作者开始搜索",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item(key = "history_header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "搜索历史",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = gold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    vm.clearHistory()
                                }) { Text("清空", fontSize = 13.sp) }
                            }
                        }
                        items(history.distinct(), key = { "h_$it" }) { h ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.setKeyword(h)
                                        vm.doSearch()
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    h,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                IconButton(onClick = {
                                    vm.removeHistory(h)
                                }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // v0.211.0：底部留 96dp，最后一行不再被底部 dock / 精致提示盖住。
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                results.forEach { group ->
                    item(key = "header_${group.key}") {
                        Text(
                            "来自 ${group.name}（${group.books.size} 本）",
                            fontSize = 13.sp,
                            color = gold,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(group.books.distinctBy { it.remoteId }, key = { "${group.key}_${it.remoteId}" }) { item ->
                        AppCard {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onOpenDetail(group, item) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // v0.211.0：结果卡片补封面——之前只画标题/作者，
                                // 截图里那种“左图右文”才是用户一眼能认的书。无封面时
                                // 用书名首字占位，不留空白方块。
                                // v0.227.0：卡片瘦身——封面 56×76→48×64、内边距
                                // 14→10dp，单卡约 104→84dp，一屏多看两本左右。
                                val coverShape = RoundedCornerShape(10.dp)
                                if (!item.cover.isNullOrBlank()) {
                                    AsyncImage(
                                        model = item.cover,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp, 64.dp).clip(coverShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(48.dp, 64.dp)
                                            .clip(coverShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            item.title.firstOrNull()?.toString() ?: "书",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = gold,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    // v0.227.0：标题最多两行（长书名不断尾），
                                    // 作者仍一行；卡片不再被三四行撑高。
                                    Text(
                                        item.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        item.author ?: "佚名",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                val onShelf = shelfNovelIds(group, item).any { it in shelfIds }
                                val busy = (group.key + item.remoteId) in joining
                                // v0.211.0：原生 Button（系统紫）改统一 AppButton；
                                // 已在书架仍走 Secondary 灰底（v0.193.0 口径不变）。
                                AppButton(
                                    text = if (busy) "加入中" else if (onShelf) "已在书架" else "加入书架",
                                    onClick = {
                                        if (onShelf) removeTarget = group to item
                                        else vm.joinShelf(group, item)
                                    },
                                    enabled = !busy,
                                    variant = if (onShelf) AppButtonVariant.Secondary else AppButtonVariant.Primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        // v0.108.0 B4：已在书架点按钮 → 二次确认移出（影图风 TechDialog，文案走主题色）
        removeTarget?.let { (g, it) ->
            TechDialog(
                onDismissRequest = { removeTarget = null },
                title = "移出《${it.title}》？",
                confirmText = "移出",
                onConfirm = {
                    removeTarget = null
                    scope.launch {
                        val rid = shelfNovelIds(g, it).firstOrNull { id -> id in shelfIds }
                            ?: shelfNovelId(g, it)
                        val ok = NovelStore.deleteNovelCascade(appCtx, rid)
                        if (ok) shelfIds = shelfIds - rid
                        AppToast.show(appCtx, if (ok) "已移出书架" else "移出失败", long = false)
                    }
                },
            ) {
                Text("将从书架移除，阅读进度、书签与追更标记也会清除。")
            }
        }
    }
}

/** v0.64.0：找书结果分组（JSON 书源 / 本地 PY 书源统一展示）。v0.103.1：去 internal——
 * NovelSearchViewModel 的 public 成员 results/joinShelf 暴露它，internal 会编译失败。 */
data class SourceGroup(
    val key: String,
    val name: String,
    val books: List<NovelSearchResult>,
)

/** v0.216.0（C10）：书架小说 id——旧拼法是两个 hashCode，撞了会互相覆盖。
 * 新拼法用净化后的源 key + 远端 id 全量拼接（只留字母数字与 - _ .，其余转 _），
 * 超长时截断并缀一段 hash 作区分；老 id 仍认（见 shelfNovelIds），旧书不受影响。 */
fun shelfNovelId(group: SourceGroup, item: NovelSearchResult): String {
    val raw = "${group.key}__${item.remoteId}"
    val safe = raw.map {
        if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_'
    }.joinToString("")
    val body = if (safe.length > 150) {
        safe.take(120) + "_" + safe.hashCode().toString().replace("-", "m")
    } else {
        safe
    }
    return "bs_$body"
}

/** 新旧两种 id——书架里已有的老书仍用老 hash 拼法，判“已在书架”与移出时两边都要认。 */
fun shelfNovelIds(group: SourceGroup, item: NovelSearchResult): List<String> =
    listOf(
        shelfNovelId(group, item),
        "bs_${group.key.hashCode()}_${item.remoteId.hashCode()}",
    )
