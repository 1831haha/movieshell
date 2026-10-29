package com.movieshell.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * v0.33.0 影院黑：首页顶部流动渐变光斑。
 *
 * v0.279.0 V4：平底设计，不要光斑与装饰——保留函数签名（调用点不动），
 * 只画一层 V4 平底色。
 */
@Composable
fun FlowingGlowBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(V4.c.bg))
}
