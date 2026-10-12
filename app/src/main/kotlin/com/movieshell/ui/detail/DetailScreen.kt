package com.movieshell.ui.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Episode
import com.movieshell.data.model.PlayLine
import com.movieshell.data.model.VodDetail
import com.movieshell.data.model.isCmsSite
import com.movieshell.data.model.isJavaSpider
import com.movieshell.data.model.isPySite
import com.movieshell.data.model.isType4Site
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FavoriteItem
import com.movieshell.data.repo.FavoriteStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.player.MagnetPlayer
import com.movieshell.player.SniffPlayHeaders
import com.movieshell.ui.components.GradientBlock
import com.movieshell.ui.data.DemoData
import com.movieshell.ui.data.Vod
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vodId: String,
    onBack: () -> Unit,
    onPlay: (playUrl: String, title: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- drpy / PY 规则接入（drpy 优先，其次 PY） ----
    // v0.5.1：从订阅站源点进来时带站点上下文（DetailSiteHolder），详情/播放按站走引擎；
    // 否则走本地规则旧逻辑。
    val subSite = remember { DetailSiteHolder.site }
    val drpyActive = remember {
        DrpySourceManager.hasActiveRule() || PySourceManager.hasActiveRule()
    }
    var detailLoading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<VodDetail?>(null) }
    LaunchedEffect(vodId) {
        if (subSite != null) {
            detailLoading = true
            detail = runCatching { SiteRepository().detail(subSite, vodId) }.getOrNull()
            detailLoading = false
        } else if (drpyActive) {
            detailLoading = true
            detail = runCatching {
                if (DrpySourceManager.hasActiveRule()) DrpySourceManager.detail(vodId)
                else if (PySourceManager.hasActiveRule()) PySourceManager.detail(vodId)
                else VodDetail(vodId = vodId)
            }.getOrNull()
            detailLoading = false
        }
    }
    // 真实选集：vodPlayFrom/vodPlayUrl 对齐
    val episodes: List<Episode> = remember(detail) {
        detail?.let { SiteRepository().parseEpisodes(it) }.orEmpty()
    }
    // 多线路：按 Episode.flag 分组；只有一条线路时 playLines.size==1，不显示 Tab
    val playLines: List<PlayLine> = remember(episodes) {
        episodes.groupBy { it.flag.ifBlank { "默认线路" } }
            .map { (name, eps) -> PlayLine(name, eps) }
    }
    var selectedLine by remember { mutableIntStateOf(0) }
    val safeLine = if (playLines.isNotEmpty()) selectedLine.coerceIn(playLines.indices) else 0
    val lineEpisodes: List<Episode> = playLines.getOrNull(safeLine)?.episodes.orEmpty()

    val vod: Vod = remember(detail) {
        detail?.let {
            Vod(
                id = it.vodId.ifBlank { vodId },
                title = it.vodName.ifBlank { vodId },
                category = it.typeName,
                year = it.vodYear,
                rating = it.vodRemarks,
                area = "",
                actors = it.vodActor.ifBlank { "未知" },
                desc = it.vodDesc.ifBlank { "暂无简介" },
                episodes = episodes.size,
            )
        } ?: DemoData.find(vodId)
    }

    var selectedEpisode by remember { mutableIntStateOf(0) }
    var showMagnetDialog by remember { mutableStateOf(false) }

    // 当前来源名：收藏时记下来，收藏列表里展示；订阅站同时记 site key 以便回跳
    val sourceName = remember {
        when {
            subSite != null -> subSite.name
            DrpySourceManager.hasActiveRule() ->
                DrpySourceManager.activeRuleName()?.removeSuffix(".js")
                    ?.ifBlank { "drpy规则" } ?: "drpy规则"
            PySourceManager.hasActiveRule() ->
                PySourceManager.activeRuleName()?.removeSuffix(".py")
                    ?.ifBlank { "PY规则" } ?: "PY规则"
            else -> "站源"
        }
    }
    var favorite by remember { mutableStateOf(false) }
    LaunchedEffect(vodId, sourceName) {
        favorite = FavoriteStore.isFavorite(context, vodId, sourceName)
    }

    /** 统一播放决策：磁力 -> MagnetPlayer；否则 drpy/PY playUrl 取直链 */
    fun playEpisode(ep: Episode) {
        LogBus.i("UI", "点击播放: ${ep.name.ifBlank { "未命名剧集" }} url=${ep.url.take(100)}")
        scope.launch {
            if (MagnetPlayer.isMagnet(ep.url)) {
                val ok = runCatching {
                    MagnetPlayer.playIfReady(context, ep.url, vod.title)
                }.getOrDefault(false)
                // 服务通则已直接起播；不通则弹窗提示
                if (!ok) showMagnetDialog = true
            } else {
                val flag = if (ep.flag.isBlank()) "" else ep.flag
                val link = runCatching {
                    when {
                        subSite == null ->
                            if (DrpySourceManager.hasActiveRule()) DrpySourceManager.playUrl(flag, ep.url)
                            else if (PySourceManager.hasActiveRule()) PySourceManager.playUrl(flag, ep.url)
                            else ""
                        // CMS 站：vod_play_url 里就是直链，直接播
                        subSite.isCmsSite() -> ep.url
                        subSite.isPySite() ->
                            SubSiteEngines.pyEngineFor(subSite)?.play(flag, ep.url).orEmpty()
                        // type=4 站：GET {api}?ac=play&flag=&id= 拿直链；header 经
                        // SniffPlayHeaders 透传给播放器（PlayerScreen 用 take 一次性取出）。
                        // parse=1 时走该订阅的 parses 数组自动解析（type4PlayWithParses），
                        // 全部失败 Toast "解析失败"。
                        subSite.isType4Site() -> {
                            val parses = SubSiteStore.findSite(context, subSite.key)
                                ?.let { (subUrl, _) -> SubSiteStore.getParses(context, subUrl) }
                                .orEmpty()
                            val r = SiteRepository().type4PlayWithParses(
                                subSite, flag, ep.url, parses, context
                            )
                            if (r.parse == 1 && r.url.isBlank()) {
                                Toast.makeText(context, "解析失败", Toast.LENGTH_LONG).show()
                            }
                            if (r.url.isNotBlank() && r.header.isNotEmpty()) {
                                SniffPlayHeaders.put(r.url, r.header)
                            }
                            r.url
                        }
                        // Java spider 站（v0.8.0）：playerContent 拿播放 JSON；header 经
                        // SniffPlayHeaders 透传给播放器；parse=1 时走该订阅的 parses
                        // 数组自动解析，全部失败 Toast "解析失败"。
                        subSite.isJavaSpider() -> {
                            val parses = SubSiteStore.findSite(context, subSite.key)
                                ?.let { (subUrl, _) -> SubSiteStore.getParses(context, subUrl) }
                                .orEmpty()
                            val r = SiteRepository().spiderPlayWithParses(
                                subSite, flag, ep.url, parses, context
                            )
                            if (r.parse == 1 && r.url.isBlank()) {
                                Toast.makeText(context, "解析失败", Toast.LENGTH_LONG).show()
                            }
                            if (r.url.isNotBlank() && r.header.isNotEmpty()) {
                                SniffPlayHeaders.put(r.url, r.header)
                            }
                            r.url
                        }
                        else ->
                            SubSiteEngines.drpyEngineFor(subSite)?.play(flag, ep.url).orEmpty()
                    }
                }.getOrDefault("")
                // link 为空 -> 播放页保持"暂无可播地址"逻辑
                onPlay(link, "${vod.title} ${ep.name.ifBlank { "第${selectedEpisode + 1}集" }}")
            }
        }
    }

    val safeSelected = if (lineEpisodes.isNotEmpty()) {
        selectedEpisode.coerceIn(0, lineEpisodes.size - 1)
    } else selectedEpisode
    val playLabel = when {
        lineEpisodes.isNotEmpty() ->
            "播放 ${lineEpisodes[safeSelected].name.ifBlank { "第${safeSelected + 1}集" }}"
        vod.episodes > 1 -> "播放 第${selectedEpisode + 1}集"
        else -> "立即播放"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vod.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            if (favorite) {
                                FavoriteStore.remove(context, vodId, sourceName)
                                Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                FavoriteStore.add(
                                    context,
                                    FavoriteItem(
                                        vodId = vodId,
                                        name = vod.title,
                                        pic = detail?.vodPic.orEmpty(),
                                        sourceName = sourceName,
                                        savedAt = System.currentTimeMillis(),
                                        subSiteKey = subSite?.key.orEmpty(),
                                    ),
                                )
                                Toast.makeText(context, "已收藏", Toast.LENGTH_SHORT).show()
                            }
                            favorite = FavoriteStore.isFavorite(context, vodId, sourceName)
                        }
                    }) {
                        Icon(
                            imageVector = if (favorite) Icons.Filled.Favorite
                            else Icons.Filled.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (favorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { /* 占位：分享 */ }) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                },
            )
        },
    ) { inner ->
        if (drpyActive && detailLoading && detail == null) {
            // drpy 详情加载中
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "加载中…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            ) {
                // 海报头图
                item {
                    GradientBlock(
                        key = vod.id,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.4f to Color.Transparent,
                                            1f to Color(0xAA000000),
                                        )
                                    )
                            )
                            // 居中播放按钮
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xCC000000)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                            Text(
                                text = vod.title,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp),
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // 标题与元信息
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = vod.rating,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB300),
                            )
                            Spacer(Modifier.width(12.dp))
                            MetaChip(vod.year)
                            MetaChip(vod.category)
                            MetaChip(vod.area)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "主演：${vod.actors}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "简介",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = vod.desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp,
                        )
                    }
                }

                // 播放按钮（v0.13.0：无播放源时禁用并明示，不再静默传空地址）
                item {
                    Button(
                        onClick = {
                            if (lineEpisodes.isNotEmpty()) {
                                playEpisode(lineEpisodes[safeSelected])
                            }
                        },
                        enabled = lineEpisodes.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (lineEpisodes.isNotEmpty()) playLabel
                            else "暂无可播地址"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 选集：drpy 真实选集优先；多条线路时上方显示线路 TabRow
                when {
                    lineEpisodes.isNotEmpty() -> {
                        if (playLines.size > 1) {
                            item {
                                ScrollableTabRow(
                                    selectedTabIndex = safeLine,
                                    edgePadding = 16.dp,
                                ) {
                                    playLines.forEachIndexed { i, line ->
                                        Tab(
                                            selected = safeLine == i,
                                            onClick = {
                                                selectedLine = i
                                                selectedEpisode = 0
                                            },
                                            text = { Text(line.name) },
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                text = "选集（共 ${lineEpisodes.size} 集）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        item {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                lineEpisodes.forEachIndexed { i, ep ->
                                    FilterChip(
                                        selected = safeSelected == i,
                                        onClick = { selectedEpisode = i },
                                        label = {
                                            Text(ep.name.ifBlank { "第${i + 1}集" })
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    vod.episodes > 1 -> {
                        item {
                            Text(
                                text = "选集（共 ${vod.episodes} 集）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        item {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                repeat(vod.episodes) { i ->
                                    FilterChip(
                                        selected = selectedEpisode == i,
                                        onClick = { selectedEpisode = i },
                                        label = { Text("第${i + 1}集") },
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    else -> {
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }

    // 磁力转播服务未启动提示
    if (showMagnetDialog) {
        AlertDialog(
            onDismissRequest = { showMagnetDialog = false },
            title = { Text("磁力转播服务未启动") },
            text = {
                Text("未检测到本机磁力转播服务（127.0.0.1:17890），请先在 DsPlayer 的服务页启动 magnet-stream 后再试")
            },
            confirmButton = {
                TextButton(onClick = { showMagnetDialog = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
