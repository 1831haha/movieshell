package com.movieshell.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import android.content.Context
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

/**
 * 看图请求：解码按屏宽取（默认 Scale.FIT，不裁剪），预取与展示共用同一构造，
 * 缓存键一致，预取完往下刷直接命中缓存。尺寸按 2:3 占位框取，与条目一致。
 */
fun picsRequest(context: Context, imageUrl: String, headers: Map<String, String>, widthPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(imageUrl)
        .size(widthPx, widthPx * 3 / 2)
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .build()

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
    // v1.2.9：42 张大图一次拉到底——原来每张图只在滚到眼前时才开始下载+全尺寸解码，
    // 一张接一张慢慢冒，滑到底要等半天。进页就把全部图预取进缓存，往下刷直接命中。
    val density = LocalDensity.current
    val widthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val imageLoader = context.imageLoader
    LaunchedEffect(url) {
        images.forEach { imageUrl ->
            imageLoader.enqueue(picsRequest(context, imageUrl, headers, widthPx))
        }
    }
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
    // 不再有翻页按钮；点图仍可显隐顶栏；顶栏只显示总张数，不再显示“第几张 / 共几张”。
    // v1.2.10：条目先按 2:3 占位——原来没图时条目是 0 高，图一张一张长出来，
    // 列表总高度一直变，根本拉不到底。现在高度第一帧就定死，Fit 不裁剪，
    // 非 2:3 的图留黑边、不丢内容；图集图多为 2:3 竖图，正好贴合。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(images) { index, imageUrl ->
                val request = remember(imageUrl, headers, widthPx) {
                    picsRequest(context, imageUrl, headers, widthPx)
                }
                AsyncImage(
                    model = request,
                    contentDescription = "第 ${index + 1} 张",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
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
