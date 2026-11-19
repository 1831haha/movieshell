package com.movieshell.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

/** v1.2.11：看图预取上限——只预取前 10 张（约 7 屏），够 cover 首屏滑动；原来进页就把全部 42 张都预取（约 12.5MB），打开一次就拉满带宽+全解码。往下刷时滚到眼前的图照常按需加载，2:3 占位已定死、拉到底不受影响。 */
private const val PREFETCH_COUNT = 10

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
    // 一张接一张慢慢冒，滑到底要等半天。进页就把前 10 张预取进缓存，往下刷直接命中。
    val density = LocalDensity.current
    val widthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val imageLoader = context.imageLoader
    LaunchedEffect(url) {
        images.take(PREFETCH_COUNT).forEach { imageUrl ->
            imageLoader.enqueue(picsRequest(context, imageUrl, headers, widthPx))
        }
    }
    var uiVisible by remember { mutableStateOf(true) }
    // v1.3.25 合并 8：双击进全屏缩放查看（null = 不在缩放态）。
    var zoomIndex by remember { mutableStateOf<Int?>(null) }

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
                        // v1.3.25 合并 8：点按显隐顶栏（原 clickable），双击进全屏缩放查看。
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { uiVisible = !uiVisible },
                                onDoubleTap = { zoomIndex = index },
                            )
                        },
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
                Text("共 ${images.size} 张 · 双击图片可放大", color = Color.White)
            }
        }
        // v1.3.25 合并 8：全屏缩放查看（key 按图，换图即复位缩放状态）。
        zoomIndex?.let { zi ->
            key(zi) {
                ZoomableImageViewer(
                    imageUrl = images[zi],
                    headers = headers,
                    widthPx = widthPx,
                    title = title,
                    indexText = "第 ${zi + 1} / ${images.size} 张",
                    onClose = { zoomIndex = null },
                )
            }
        }
    }
}

/**
 * v1.3.25 合并 8：看图页双指缩放——全屏单图查看。
 * 双指捏合 1–4 倍、放大后可拖动、双击在 1 倍/2 倍间切换、点按显隐顶栏。
 * 全屏独占手势（没有父级滚动可冲突），detectTransformGestures 直接用；
 * 拖动范围夹在图边不超出视口太多。缩放图按 2 倍屏宽取，放大 2 倍内不糊。
 */
@Composable
private fun ZoomableImageViewer(
    imageUrl: String,
    headers: Map<String, String>,
    widthPx: Int,
    title: String,
    indexText: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(imageUrl, headers, widthPx) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(widthPx * 2, widthPx * 3)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var uiVisible by remember { mutableStateOf(true) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        AsyncImage(
            model = request,
            contentDescription = indexText,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = newScale
                        offset = if (newScale > 1f) {
                            val maxX = (vw * (newScale - 1f) / 2f).coerceAtLeast(0f)
                            val maxY = (vh * (newScale - 1f) / 2f).coerceAtLeast(0f)
                            Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        } else {
                            Offset.Zero
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { uiVisible = !uiVisible },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                            }
                        },
                    )
                },
        )
        if (uiVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    text = title.stripHtml(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(indexText, color = Color.White)
            }
        }
    }
}
