package com.movieshell.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.movieshell.data.source.stripHtml

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

    if (images.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有可看的图片", color = Color.White)
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            }
        }
        return
    }

    // v0.266.0：长流模式——原来是左右翻页的 HorizontalPager + 上一张/
    // 下一张按钮，现在所有图连成一条竖向长流一直往下刷，不再分页、
    // 不再有翻页按钮；每张图按屏宽等比缩放（FillWidth），点图仍可
    // 显隐顶栏；顶栏只显示总张数，不再显示“第几张 / 共几张”。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(images) { index, imageUrl ->
                val request = remember(imageUrl, headers) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = "第 ${index + 1} 张",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uiVisible = !uiVisible },
                )
            }
        }
        if (uiVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    // v0.167.0：源返回的标题常带 HTML 标签（如 <span class="post_title_topimg">(120P)</span>），只留文字
                    text = title.stripHtml(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text("共 ${images.size} 张", color = Color.White)
            }
        }
    }
}
