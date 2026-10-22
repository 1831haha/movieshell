package com.movieshell.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * v0.164.0：pics:// 看图。
 *
 * 协议约定（按 74P 这类 PY 源的 playerContent）：url 形如
 * pics://图1&&图2&&图3，parse=0。壳子此前白名单里没有 pics，
 * EngineHost 直接以“地址格式暂不支持”拒绝，看图一直进不去。
 * 本页只负责看图：不走 Media3/libmpv，不碰解析线路。
 */
fun isPicsUrl(url: String): Boolean = url.trim().startsWith("pics://", true)

/** pics:// 后按 && 切图，空段丢掉；非 pics 地址返回空列表。 */
fun parsePicsUrls(url: String): List<String> {
    if (!isPicsUrl(url)) return emptyList()
    return url.trim().removePrefix("pics://")
        .split("&&")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

@Composable
fun PicsViewerScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val images = remember(url) { parsePicsUrls(url) }
    // 解析链路把源返回的 Referer/UA 存在 SniffPlayHeaders 里（key 是整条 pics:// 地址），
    // 看图时原样带给每张图，防盗链站点不带 Referer 会 403。peek 不消费，翻页重组还能用。
    val headers = remember(url) { SniffPlayHeaders.peek(url) }
    var uiVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    if (images.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有可看的图片", color = Color.White)
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            }
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { images.size })
    val current = pagerState.currentPage
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val request = remember(images[page], headers) {
                ImageRequest.Builder(context)
                    .data(images[page])
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = "第 ${page + 1} 张",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { uiVisible = !uiVisible },
            )
        }
        if (uiVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text("${current + 1} / ${images.size}", color = Color.White)
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = current > 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(current - 1) } },
                ) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, null, tint = Color.White)
                    Text("上一张", color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = current < images.size - 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(current + 1) } },
                ) {
                    Text("下一张", color = Color.White)
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, null, tint = Color.White)
                }
            }
        }
    }
}
