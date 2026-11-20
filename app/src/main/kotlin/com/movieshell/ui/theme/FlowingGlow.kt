package com.movieshell.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/**
 * v0.33.0 影院黑：首页顶部流动渐变光斑。
 *
 * v0.279.0 起平底；v0.292.0 V5：保留函数签名（调用点不动），只画一层 M3 surface 底色。
 */
@Composable
fun FlowingGlowBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
}

/**
 * v1.3.33：全站共享静态深色渐变底（不做动画、不铺全站动态背景）。
 *
 * 首页仍走 [FlowingGlowBackground]；二级页（设置/媒体服务器/主题收藏/
 * 音乐源/找书/目录/净化规则/书籍详情/发现聚合/AI）在根容器下铺这一层：
 * 纵向由 surface 向 surfaceContainerHigh 渐变，顶部带一层低透明度
 * primaryContainer 柔光（静态，不漂移、不呼吸，减动效与性能都不受影响）。
 * 卡片仍走各自毛玻璃/微光描边，只补底，不动卡片本身。
 * 详情页不铺（已有海报取色渐变底，会冲突）、阅读器不铺（阅读背景由用户自选）、
 * 音乐播放器不铺（已有封面模糊氛围底）。
 */
@Composable
fun StaticGlowBackground(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    0f to cs.surface,
                    1f to cs.surfaceContainerHigh,
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to cs.primaryContainer.copy(alpha = 0.10f),
                        0.45f to cs.primaryContainer.copy(alpha = 0.0f),
                    ),
                ),
        )
    }
}
